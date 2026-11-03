"""Replay buffer, semi-MDP transition construction and the DQN trainer."""
from collections import defaultdict

import numpy as np
import torch
import torch.nn as nn

from .config import RewardConfig
from .features import NUM_FEATURES


class QNet(nn.Module):
    def __init__(self, hidden=64):
        super().__init__()
        self.net = nn.Sequential(
            nn.Linear(NUM_FEATURES, hidden), nn.ReLU(),
            nn.Linear(hidden, hidden), nn.ReLU(),
            nn.Linear(hidden, 2),
        )

    def forward(self, x):
        return self.net(x)

    @torch.no_grad()
    def q_values(self, feats: np.ndarray) -> np.ndarray:
        return self.net(torch.as_tensor(feats)).numpy()


class Replay:
    """Transitions with up to two bootstrapped successors (sender's kept copy, receiver's new copy)."""

    def __init__(self, capacity=300_000):
        self.cap, self.n, self.i = capacity, 0, 0
        z = lambda *shape, dt=np.float32: np.zeros((capacity, *shape), dt)
        self.s, self.s2a, self.s2b = z(NUM_FEATURES), z(NUM_FEATURES), z(NUM_FEATURES)
        self.a = z(dt=np.int64)
        self.r, self.wa, self.wb, self.dta, self.dtb = z(), z(), z(), z(), z()

    def push(self, s, a, r, s2a=None, wa=0.0, dta=0.0, s2b=None, wb=0.0, dtb=0.0):
        i = self.i
        self.s[i], self.a[i], self.r[i] = s, a, r
        self.wa[i], self.dta[i], self.wb[i], self.dtb[i] = wa, dta, wb, dtb
        if s2a is not None:
            self.s2a[i] = s2a
        if s2b is not None:
            self.s2b[i] = s2b
        self.i = (i + 1) % self.cap
        self.n = min(self.n + 1, self.cap)

    def sample(self, batch, rng):
        """Half forward, half hold samples: forwards are rare and carry the learning signal."""
        a = self.a[:self.n]
        fwd, hold = np.flatnonzero(a == 1), np.flatnonzero(a == 0)
        if len(fwd) == 0 or len(hold) == 0:
            idx = rng.integers(0, self.n, batch)
        else:
            idx = np.concatenate([rng.choice(fwd, batch // 2), rng.choice(hold, batch - batch // 2)])
        t = lambda x: torch.as_tensor(x[idx])
        return (t(self.s), t(self.a), t(self.r), t(self.s2a), t(self.s2b),
                t(self.wa), t(self.wb), t(self.dta), t(self.dtb))


class _Fwd:
    __slots__ = ("s", "r", "t", "na", "nb", "ta", "tb", "a_done", "b_done")

    def __init__(self, s, r, t):
        self.s, self.r, self.t = s, r, t
        self.na = self.nb = None
        self.ta = self.tb = 0.0
        self.a_done = self.b_done = False


class TransitionBuilder:
    """Turns simulator events into semi-MDP transitions.

    Decision unit: (message m at node a, peer b), action 0=hold / 1=forward.
      * hold               -> target  gamma^dt * V(next state of m at a)
      * forward to a relay -> target -c + gamma^dta * V(next state of m at a)       (a keeps its copy)
                                          + credit * gamma^dtb * V(next state of m at b)  (b's new copy)
      * forward to the destination -> terminal: delivery value - c
      * message delivered/expired  -> open branches close with value 0
    Next states are sampled at each node's next decision opportunity, so discounting uses
    elapsed time. The forward target counts both copies; without the sender's kept copy,
    forwarding would look strictly worse than holding by the transmission cost.
    """

    def __init__(self, replay: Replay, rew: RewardConfig, hold_gap_s: float = 300.0):
        self.replay, self.rew, self.hold_gap = replay, rew, hold_gap_s
        self.fa = defaultdict(list)     # (a, m) -> forwards awaiting a's next state
        self.fb = defaultdict(list)     # (b, m) -> forwards awaiting b's next state
        self.hold = defaultdict(list)   # (a, m) -> [(state, t)]
        self.by_msg = defaultdict(set)
        self.sim = None

    def bind(self, sim):
        self.sim = sim

    def _maybe_push(self, f: _Fwd):
        if f.a_done and f.b_done:
            self.replay.push(f.s, 1, f.r,
                             f.na, 1.0 if f.na is not None else 0.0, f.ta,
                             f.nb, self.rew.copy_credit if f.nb is not None else 0.0, f.tb)

    def observe(self, node, msgs, feats):
        now = self.sim.t
        for i, m in enumerate(msgs):
            key = (node, int(m))
            for f in self.fa.pop(key, ()):
                f.na, f.ta, f.a_done = feats[i], now - f.t, True
                self._maybe_push(f)
            for f in self.fb.pop(key, ()):
                f.nb, f.tb, f.b_done = feats[i], now - f.t, True
                self._maybe_push(f)
            pending = self.hold.get(key)
            if pending and now - pending[0][1] >= self.hold_gap:
                for s, t0 in self.hold.pop(key):
                    self.replay.push(s, 0, 0.0, feats[i], 1.0, now - t0)

    def act(self, a, b, msgs, feats, executed, is_dst, evict_pen, urgent, ttl_left):
        now, rew = self.sim.t, self.rew
        for i, m in enumerate(msgs):
            m = int(m)
            if executed[i]:
                r = -rew.tx_cost - rew.evict_penalty * evict_pen[i]
                if is_dst[i]:
                    value = rew.deliver * (rew.urgent_mult if urgent[i] else 1.0)
                    value *= rew.latency_floor + (1 - rew.latency_floor) * ttl_left[i]
                    self.replay.push(feats[i], 1, r + value)
                else:
                    f = _Fwd(feats[i], r, now)
                    self.fa[(a, m)].append(f)
                    self.fb[(b, m)].append(f)
                    self.by_msg[m].update(((a, m), (b, m)))
            elif (a, m) not in self.hold:      # hold decisions are subsampled to one per hold_gap
                self.hold[(a, m)].append((feats[i], now))
                self.by_msg[m].add((a, m))

    def drop(self, node, m):
        self._close((node, m))

    def finish_message(self, m):
        for key in self.by_msg.pop(m, ()):
            self._close(key)

    def _close(self, key):
        for f in self.fa.pop(key, ()):
            f.a_done = True
            self._maybe_push(f)
        for f in self.fb.pop(key, ()):
            f.b_done = True
            self._maybe_push(f)
        for s, _ in self.hold.pop(key, ()):
            self.replay.push(s, 0, 0.0)


class Trainer:
    """Double DQN with a semi-MDP time-discounted target."""

    def __init__(self, qnet: QNet, replay: Replay, gamma=0.98, tau_s=600.0, lr=1e-3,
                 batch=128, target_sync=250, seed=0):
        self.q, self.replay = qnet, replay
        self.target = QNet(qnet.net[0].out_features)
        self.target.load_state_dict(qnet.state_dict())
        self.opt = torch.optim.Adam(qnet.parameters(), lr=lr)
        self.gamma, self.tau_s, self.batch, self.sync = gamma, tau_s, batch, target_sync
        self.rng = np.random.default_rng(seed)
        self.updates = 0
        self.last_loss = float("nan")

    def update(self, warmup=2000):
        if self.replay.n < warmup:
            return
        s, a, r, s2a, s2b, wa, wb, dta, dtb = self.replay.sample(self.batch, self.rng)
        with torch.no_grad():
            def value(s2):
                best = self.q(s2).argmax(1, keepdim=True)
                return self.target(s2).gather(1, best).squeeze(1)
            target = (r + wa * torch.pow(self.gamma, dta / self.tau_s) * value(s2a)
                      + wb * torch.pow(self.gamma, dtb / self.tau_s) * value(s2b))
        pred = self.q(s).gather(1, a.unsqueeze(1)).squeeze(1)
        loss = nn.functional.smooth_l1_loss(pred, target)
        self.opt.zero_grad()
        loss.backward()
        nn.utils.clip_grad_norm_(self.q.parameters(), 5.0)
        self.opt.step()
        self.updates += 1
        self.last_loss = loss.item()
        if self.updates % self.sync == 0:
            self.target.load_state_dict(self.q.state_dict())
