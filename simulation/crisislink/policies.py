"""Forwarding policies. `decide` returns (forward_mask, score) over a pair's candidate messages."""
import numpy as np


class Policy:
    name = "policy"

    def reset(self, sim):
        pass

    def decide(self, pair):
        raise NotImplementedError

    def on_transfer(self, sim, a, b, mid):
        pass


def _arrival_score(pair):
    # tie-break: oldest message first
    return -pair.sim.created[pair.msgs]


class Direct(Policy):
    """Hand over only to the destination."""
    name = "direct"

    def decide(self, pair):
        return np.zeros(len(pair.msgs), bool), _arrival_score(pair)


class Epidemic(Policy):
    """Flooding baseline: copy everything to every peer met."""
    name = "epidemic"

    def decide(self, pair):
        return np.ones(len(pair.msgs), bool), _arrival_score(pair)


class SprayAndWait(Policy):
    """Binary spray-and-wait: split copy tokens, then wait for the destination."""
    name = "spray_and_wait"

    def decide(self, pair):
        tokens = pair.sim.tokens[pair.a, pair.msgs]
        return tokens > 1, _arrival_score(pair)

    def on_transfer(self, sim, a, b, mid):
        give = np.floor(sim.tokens[a, mid] / 2)
        sim.tokens[b, mid] = give
        sim.tokens[a, mid] -= give


class Prophet(Policy):
    """PRoPHET: forward when the peer is more likely to meet the destination."""
    name = "prophet"

    def decide(self, pair):
        sim = pair.sim
        dst = sim.dst[pair.msgs]
        pa, pb = sim.P[pair.a, dst], sim.P[pair.b, dst]
        return pb > pa, pb - pa


class DQNPolicy(Policy):
    """Greedy (or epsilon-greedy while training) policy over a Q-network.

    Forwards when Q(forward) beats Q(hold) by `margin`; candidates are served in
    priority order, then by Q-advantage.
    """

    def __init__(self, qnet, epsilon=0.0, explore_forward_p=0.3, margin=0.0, rng=None, name="dqn"):
        self.qnet, self.epsilon, self.p_fwd, self.margin = qnet, epsilon, explore_forward_p, margin
        self.rng = rng or np.random.default_rng(0)
        self.name = name

    def decide(self, pair):
        q = self.qnet.q_values(pair.feats)
        adv = q[:, 1] - q[:, 0]
        forward = adv > self.margin
        if self.epsilon > 0:
            explore = self.rng.random(len(adv)) < self.epsilon
            forward = np.where(explore, self.rng.random(len(adv)) < self.p_fwd, forward)
        return forward, adv
