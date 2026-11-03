import json
import os
import sys
from dataclasses import replace

import numpy as np
import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from crisislink import policies as P
from crisislink.config import RewardConfig, SimConfig
from crisislink.export import forward_json
from crisislink.features import FEATURE_NAMES, NUM_FEATURES
from crisislink.learning import QNet, Replay, TransitionBuilder, Trainer
from crisislink.network import DTNSimulator

SMALL = replace(SimConfig(), n_nodes=20, duration=3600.0, ttl=1800.0, area=800.0)


def test_world_is_policy_independent():
    a, b = DTNSimulator(SMALL, 3), DTNSimulator(SMALL, 3)
    a.run(P.Epidemic())
    b.run(P.Direct())
    assert np.array_equal(a.created, b.created) and np.array_equal(a.dst, b.dst)
    assert np.allclose(a.mob.pos, b.mob.pos)


def test_never_delivers_to_wrong_node_and_respects_ttl():
    sim = DTNSimulator(SMALL, 5)
    sim.run(P.Epidemic())
    delivered = sim.delivered_at >= 0
    assert delivered.sum() == sim.metrics.delivered
    assert np.all(sim.delivered_at[delivered] <= sim.created[delivered] + SMALL.ttl + SMALL.dt)
    assert np.all(sim.has.sum(1) <= SMALL.buffer_size)


def test_direct_has_unit_overhead():
    out = DTNSimulator(SMALL, 1).run(P.Direct())
    if out["transmissions"]:
        assert out["overhead"] == pytest.approx(1.0)


def test_flooding_beats_direct_when_unconstrained():
    cfg = replace(SMALL, buffer_size=500)
    d = DTNSimulator(cfg, 2).run(P.Direct())["delivery_ratio"]
    e = DTNSimulator(cfg, 2).run(P.Epidemic())["delivery_ratio"]
    assert e >= d


def test_features_in_unit_range():
    sim = DTNSimulator(SMALL, 4)
    seen = []

    class Probe(P.Epidemic):
        def decide(self, pair):
            seen.append(pair.feats.copy())
            return super().decide(pair)

    sim.run(Probe())
    feats = np.concatenate(seen)
    assert feats.shape[1] == NUM_FEATURES == len(FEATURE_NAMES)
    assert feats.min() >= 0.0 and feats.max() <= 1.0


def test_transition_builder_closes_branches_and_trains():
    replay, rew = Replay(5000), RewardConfig()
    sim = DTNSimulator(SMALL, 6)
    builder = TransitionBuilder(replay, rew)
    builder.bind(sim)
    sim.run(P.Epidemic(), builder)
    n = replay.n
    assert n > 0
    # terminal delivery transitions carry the delivery reward net of tx cost
    terminal = (replay.wa[:n] == 0) & (replay.wb[:n] == 0)
    assert replay.r[:n][terminal].max() > 0.3
    # forwards to relays bootstrap from both the kept copy and the new copy
    two_sided = (replay.a[:n] == 1) & (replay.wa[:n] > 0) & (replay.wb[:n] > 0)
    assert two_sided.any()
    tr = Trainer(QNet(16), replay, batch=32)
    tr.update(warmup=10)
    assert tr.updates == 1


def test_export_roundtrip(tmp_path):
    from crisislink.export import export_policy
    net = QNet(8)
    doc = export_policy(net, str(tmp_path / "p.json"), {"x": 1}, n_parity=5)
    saved = json.load(open(tmp_path / "p.json"))
    x = np.array(saved["parity"]["inputs"], np.float32)
    expect = net.q_values(x)
    got = forward_json(saved["layers"], x)
    assert np.allclose(expect, got, atol=1e-3)
    assert saved["feature_names"] == FEATURE_NAMES
