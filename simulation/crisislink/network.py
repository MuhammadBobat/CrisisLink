"""Discrete-time delay-tolerant network simulator.

World randomness (mobility, traffic) is seeded independently of policy decisions, so
every policy sees identical worlds for a given seed and comparisons are paired.
"""
from __future__ import annotations

from dataclasses import dataclass, field

import numpy as np

from .config import SimConfig
from .features import CONTACT_AGE_CAP_S, COPIES_CAP, NEIGHBOUR_CAP, NUM_FEATURES
from .mobility import ClusteredWaypoint


@dataclass
class Metrics:
    generated: int = 0
    delivered: int = 0
    urgent_generated: int = 0
    urgent_delivered: int = 0
    transmissions: int = 0
    evictions: int = 0
    latencies: list = field(default_factory=list)
    dead_nodes: int = 0
    mean_battery: float = 0.0

    def summary(self) -> dict:
        lat = np.array(self.latencies) if self.latencies else np.array([np.nan])
        return {
            "delivery_ratio": self.delivered / max(self.generated, 1),
            "urgent_delivery_ratio": self.urgent_delivered / max(self.urgent_generated, 1),
            "median_latency_min": float(np.nanmedian(lat)) / 60.0,
            "overhead": self.transmissions / max(self.delivered, 1),
            "transmissions": self.transmissions,
            "evictions": self.evictions,
            "dead_node_frac": self.dead_nodes,
            "mean_battery": self.mean_battery,
        }


class Pair:
    """Everything a policy may look at when node `a` considers forwarding to `b`."""

    __slots__ = ("sim", "a", "b", "msgs", "feats", "link_quality", "contact_age")

    def __init__(self, sim, a, b, msgs, feats):
        self.sim, self.a, self.b, self.msgs, self.feats = sim, a, b, msgs, feats


class DTNSimulator:
    def __init__(self, cfg: SimConfig, seed: int):
        self.cfg, self.seed = cfg, seed
        world = np.random.default_rng([seed, 0])
        self.order_rng = np.random.default_rng([seed, 1])
        n = cfg.n_nodes

        self.mob = ClusteredWaypoint(cfg, world)
        self.battery = world.uniform(0.6, 1.0, n)
        self._make_traffic(world)

        m = self.n_msgs
        self.has = np.zeros((n, m), bool)
        self.known_delivered = np.zeros((n, m), bool)
        self.hops = np.zeros((n, m))
        self.tokens = np.zeros((n, m))
        self.copies = np.zeros((n, m))
        self.delivered_at = np.full(m, -1.0)
        self.P = np.zeros((n, n))
        self.contact_age = np.zeros((n, n))
        self.prev_adj = np.zeros((n, n), bool)
        self.adj = np.zeros((n, n), bool)
        self.dist = np.zeros((n, n))
        self.t = 0.0
        self.metrics = Metrics()
        self._next_msg = 0

    # ---------------------------------------------------------------- traffic
    def _make_traffic(self, rng):
        cfg = self.cfg
        window = max(cfg.duration - cfg.ttl, cfg.dt)
        times, srcs = [], []
        for node in range(cfg.n_nodes):
            k = rng.poisson(window / cfg.msg_interval)
            times.extend(rng.uniform(0, window, k))
            srcs.extend([node] * k)
        order = np.argsort(times)
        self.created = np.array(times)[order]
        self.src = np.array(srcs, dtype=int)[order]
        m = len(self.created)
        offset = rng.integers(1, cfg.n_nodes, m)
        self.dst = (self.src + offset) % cfg.n_nodes
        self.urgent = rng.random(m) < cfg.urgent_frac
        self.n_msgs = m

    # --------------------------------------------------------------- helpers
    def free_frac(self, node: int) -> float:
        return 1.0 - self.has[node].sum() / self.cfg.buffer_size

    def _active(self) -> np.ndarray:
        return (self.created <= self.t) & (self.t < self.created + self.cfg.ttl)

    def ttl_left(self, msgs: np.ndarray) -> np.ndarray:
        return np.clip((self.created[msgs] + self.cfg.ttl - self.t) / self.cfg.ttl, 0, 1)

    def make_room(self, node: int, incoming_urgent: bool):
        """Free a buffer slot. Returns (ok, evicted_message_or_None)."""
        if self.has[node].sum() < self.cfg.buffer_size:
            return True, None
        if not incoming_urgent:
            return False, None
        held = np.flatnonzero(self.has[node] & ~self.urgent)
        if len(held) == 0:
            return False, None
        victim = held[np.argmin(self.created[held])]
        self.has[node, victim] = False
        self.metrics.evictions += 1
        return True, int(victim)

    # ------------------------------------------------------------- features
    def features(self, a: int, b: int, msgs: np.ndarray) -> np.ndarray:
        cfg = self.cfg
        k = len(msgs)
        f = np.empty((k, NUM_FEATURES), np.float32)
        dst = self.dst[msgs]
        pa, pb = self.P[a, dst], self.P[b, dst]
        f[:, 0] = self.urgent[msgs]
        f[:, 1] = self.ttl_left(msgs)
        f[:, 2] = np.clip(self.hops[a, msgs] / cfg.max_hops, 0, 1)
        f[:, 3] = pa
        f[:, 4] = pb
        f[:, 5] = (pb - pa + 1) / 2
        f[:, 6] = self.free_frac(b)
        f[:, 7] = self.free_frac(a)
        f[:, 8] = np.clip(self.battery[b], 0, 1)
        f[:, 9] = np.clip(self.battery[a], 0, 1)
        f[:, 10] = np.clip(1 - self.dist[a, b] / cfg.comm_range, 0, 1)
        f[:, 11] = min(self.contact_age[a, b] / CONTACT_AGE_CAP_S, 1.0)
        f[:, 12] = min(self.adj[a].sum() / NEIGHBOUR_CAP, 1.0)
        f[:, 13] = self.src[msgs] == a
        f[:, 14] = dst == b
        f[:, 15] = np.clip(self.copies[a, msgs] / COPIES_CAP, 0, 1)
        return f

    # ------------------------------------------------------------ encounters
    def _encounter(self, a: int, b: int):
        c = self.cfg
        P = self.P
        for x, y in ((a, b), (b, a)):
            P[x, y] += (1 - P[x, y]) * c.p_init
        for x, y in ((a, b), (b, a)):
            P[x] += (1 - P[x]) * P[x, y] * P[y] * c.p_beta
            P[x, x] = 0.0

    def _exchange_anti_packets(self, a: int, b: int):
        kd = self.known_delivered[a] | self.known_delivered[b]
        for n in (a, b):
            self.known_delivered[n] = kd
            self.has[n] &= ~kd

    # ------------------------------------------------------------------ run
    def run(self, policy, learner=None, step_callback=None) -> dict:
        cfg = self.cfg
        n_steps = int(cfg.duration / cfg.dt)
        policy.reset(self)
        for _ in range(n_steps):
            self._step(policy, learner)
            if step_callback:
                step_callback()
        self.metrics.dead_nodes = float((self.battery <= 0).mean())
        self.metrics.mean_battery = float(np.clip(self.battery, 0, 1).mean())
        return self.metrics.summary()

    def _step(self, policy, learner):
        cfg, m = self.cfg, self.metrics
        self.t += cfg.dt
        pos = self.mob.step(cfg.dt)
        self.battery -= cfg.battery_idle * cfg.dt
        alive = self.battery > 0

        self._expire(learner)
        self._generate(alive)

        diff = pos[:, None, :] - pos[None, :, :]
        self.dist = np.sqrt((diff ** 2).sum(-1))
        adj = (self.dist <= cfg.comm_range) & alive[:, None] & alive[None, :]
        np.fill_diagonal(adj, False)
        self.adj = adj
        self.contact_age = np.where(adj, self.contact_age + cfg.dt, 0.0)
        self.P *= cfg.p_gamma ** (cfg.dt / cfg.p_unit)
        iu, ju = np.where(np.triu(adj & ~self.prev_adj, 1))
        for a, b in zip(iu, ju):
            self._encounter(int(a), int(b))
        self.prev_adj = adj

        ia, ja = np.where(np.triu(adj, 1))
        for a, b in zip(ia, ja):
            self._exchange_anti_packets(int(a), int(b))

        pairs = [(int(a), int(b)) for a, b in zip(ia, ja)] + [(int(b), int(a)) for a, b in zip(ia, ja)]
        for idx in self.order_rng.permutation(len(pairs)):
            self._serve(pairs[idx], policy, learner)

    def _generate(self, alive):
        while self._next_msg < self.n_msgs and self.created[self._next_msg] <= self.t:
            mid = self._next_msg
            self._next_msg += 1
            src = self.src[mid]
            self.metrics.generated += 1
            self.metrics.urgent_generated += int(self.urgent[mid])
            if not alive[src]:
                continue
            if self.has[src].sum() >= self.cfg.buffer_size:
                held = np.flatnonzero(self.has[src] & ~self.urgent)
                if len(held) == 0:
                    continue
                self.has[src, held[np.argmin(self.created[held])]] = False
            self.has[src, mid] = True
            self.tokens[src, mid] = self.cfg.spray_copies

    def _expire(self, learner):
        done = np.flatnonzero((self.t >= self.created + self.cfg.ttl) & (self.created + self.cfg.ttl - self.t < self.cfg.dt))
        if len(done):
            self.has[:, done] = False
            if learner:
                for mid in done:
                    learner.finish_message(int(mid))

    def _serve(self, pair, policy, learner):
        a, b = pair
        cfg = self.cfg
        cand = np.flatnonzero(self.has[a] & self._active() & ~self.has[b]
                              & ~self.known_delivered[b] & ~self.known_delivered[a]
                              & (self.hops[a] < cfg.max_hops))
        if len(cand) == 0:
            return
        feats = self.features(a, b, cand)
        if learner:
            learner.observe(a, cand, feats)
        forward, score = policy.decide(Pair(self, a, b, cand, feats))
        is_dst = self.dst[cand] == b
        forward = forward | is_dst                      # a destination in range always receives
        order = np.lexsort((-score, ~self.urgent[cand], ~is_dst))
        executed = np.zeros(len(cand), bool)
        evict_pen = np.zeros(len(cand))
        sent = 0
        for i in order:
            if sent >= cfg.link_budget:
                break
            if not forward[i] or self.battery[a] <= 0 or self.battery[b] <= 0:
                continue
            mid = int(cand[i])
            if is_dst[i]:
                ok, victim = True, None
            else:
                ok, victim = self.make_room(b, bool(self.urgent[mid]))
            if not ok:
                continue
            if victim is not None:
                evict_pen[i] = 1.0
                if learner:
                    learner.drop(b, victim)
            self._transfer(a, b, mid, bool(is_dst[i]), policy)
            executed[i] = True
            sent += 1
        if learner:
            learner.act(a, b, cand, feats, executed, is_dst, evict_pen,
                        self.urgent[cand], self.ttl_left(cand))
            for i in np.flatnonzero(executed & is_dst):
                learner.finish_message(int(cand[i]))

    def _transfer(self, a, b, mid, to_dst, policy):
        m, cfg = self.metrics, self.cfg
        m.transmissions += 1
        self.battery[a] -= cfg.battery_tx
        self.battery[b] -= cfg.battery_rx
        if to_dst:
            if self.delivered_at[mid] < 0:
                self.delivered_at[mid] = self.t
                m.delivered += 1
                m.urgent_delivered += int(self.urgent[mid])
                m.latencies.append(self.t - self.created[mid])
            self.known_delivered[b, mid] = True
            self.known_delivered[a, mid] = True
            self.has[a, mid] = False
            return
        self.copies[a, mid] += 1
        self.has[b, mid] = True
        self.hops[b, mid] = self.hops[a, mid] + 1
        policy.on_transfer(self, a, b, mid)
