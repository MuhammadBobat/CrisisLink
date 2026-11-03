import numpy as np

from .config import SimConfig


class ClusteredWaypoint:
    """Random waypoint where most waypoints fall inside a few attractor zones.

    Models displaced people moving between shelters and aid points: contacts are
    structured (a node's home zone) but not predictable.
    """

    def __init__(self, cfg: SimConfig, rng: np.random.Generator):
        self.cfg, self.rng = cfg, rng
        n, k = cfg.n_nodes, cfg.n_zones
        self.zones = rng.uniform(0.15 * cfg.area, 0.85 * cfg.area, (k, 2))
        self.weights = rng.dirichlet(np.full(k, 2.0))
        self.home = rng.choice(k, n, p=self.weights)
        everyone = np.arange(n)
        self.pos = self._targets(everyone)
        self.target = self._targets(everyone)
        self.speed = rng.uniform(cfg.speed_min, cfg.speed_max, n)
        self.pause = rng.uniform(0, cfg.pause_max, n)

    def _targets(self, idx: np.ndarray) -> np.ndarray:
        cfg, rng, n = self.cfg, self.rng, len(idx)
        in_zone = rng.random(n) < cfg.zone_bias
        at_home = rng.random(n) < cfg.home_bias
        zone = np.where(at_home, self.home[idx], rng.choice(cfg.n_zones, n, p=self.weights))
        angle = rng.uniform(0, 2 * np.pi, n)
        radius = cfg.zone_radius * np.sqrt(rng.random(n))
        pts = self.zones[zone] + radius[:, None] * np.stack([np.cos(angle), np.sin(angle)], 1)
        anywhere = rng.uniform(0, cfg.area, (n, 2))
        return np.clip(np.where(in_zone[:, None], pts, anywhere), 0, cfg.area)

    def step(self, dt: float) -> np.ndarray:
        cfg = self.cfg
        paused = self.pause > 0
        self.pause[paused] -= dt
        moving = ~paused
        delta = self.target - self.pos
        dist = np.linalg.norm(delta, axis=1)
        travel = self.speed * dt
        arrive = moving & (dist <= travel)
        advance = moving & ~arrive
        self.pos[advance] += delta[advance] / dist[advance, None] * travel[advance, None]
        if arrive.any():
            idx = np.flatnonzero(arrive)
            self.pos[idx] = self.target[idx]
            self.target[idx] = self._targets(idx)
            self.speed[idx] = self.rng.uniform(cfg.speed_min, cfg.speed_max, len(idx))
            self.pause[idx] = self.rng.uniform(0, cfg.pause_max, len(idx))
        return self.pos
