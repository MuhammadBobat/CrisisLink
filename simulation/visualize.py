"""Record real simulator runs and build a self-contained animation of how messages travel.

    python visualize.py --seed 1003            # writes viz/crisislink_sim.html

The same seed gives every policy identical movement and identical messages, so the panels differ
only in which handoffs each policy chooses to make.
"""
import argparse
import json
import os
from dataclasses import asdict

import numpy as np

from crisislink.config import SimConfig
from crisislink.network import DTNSimulator
from evaluate import make_policy

HERE = os.path.dirname(os.path.abspath(__file__))
POLICIES = [("epidemic", "Flooding"), ("spray_and_wait", "Spray-and-Wait"), ("dqn", "Learned (DQN)")]


class RecordingSimulator(DTNSimulator):
    """Logs every handoff and samples node positions."""

    def __init__(self, cfg, seed, stride):
        super().__init__(cfg, seed)
        self.stride, self.step_no = stride, 0
        self.positions, self.events = [], []

    def _step(self, policy, learner):
        super()._step(policy, learner)
        if self.step_no % self.stride == 0:
            self.positions.append(np.round(self.mob.pos).astype(int).tolist())
        self.step_no += 1

    def _transfer(self, a, b, mid, to_dst, policy):
        self.events.append([self.step_no, int(a), int(b), int(mid), int(to_dst)])
        super()._transfer(a, b, mid, to_dst, policy)


def record(cfg, seed, name, policy_path, stride):
    sim = RecordingSimulator(cfg, seed, stride)
    result = sim.run(make_policy(name, policy_path))
    return sim, result


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--seed", type=int, default=1003)
    ap.add_argument("--stride", type=int, default=4, help="steps per frame (4 = 40 s)")
    ap.add_argument("--policy", default=os.path.join(HERE, "..", "app/src/main/assets/policy/dqn_policy.json"))
    ap.add_argument("--fragment-out", default=None)
    ap.add_argument("--out", default=os.path.join(HERE, "viz", "crisislink_sim.html"))
    args = ap.parse_args()

    cfg = SimConfig()
    runs, ref = {}, None
    for key, label in POLICIES:
        sim, result = record(cfg, args.seed, key, args.policy, args.stride)
        if ref is None:
            ref = sim
        else:
            assert np.array_equal(np.array(ref.positions), np.array(sim.positions)), "worlds diverged"
        runs[key] = {
            "label": label,
            "events": sim.events,
            "delivered_at": [round(float(t) / cfg.dt, 1) if t >= 0 else None for t in sim.delivered_at],
            "summary": {k: round(float(v), 3) for k, v in result.items()},
        }
        print(f"{label:16s} delivery {result['delivery_ratio']:.2f}  tx/delivery {result['overhead']:.1f}  events {len(sim.events)}")

    data = {
        "seed": args.seed, "dt": cfg.dt, "stride": args.stride, "area": cfg.area, "range": cfg.comm_range,
        "ttl_steps": cfg.ttl / cfg.dt, "n_nodes": cfg.n_nodes,
        "zones": np.round(ref.mob.zones).astype(int).tolist(), "zone_radius": cfg.zone_radius,
        "positions": ref.positions,
        "messages": [
            {"src": int(ref.src[m]), "dst": int(ref.dst[m]), "urgent": bool(ref.urgent[m]),
             "created": round(float(ref.created[m]) / cfg.dt, 1)}
            for m in range(ref.n_msgs)
        ],
        "runs": runs,
    }
    fragment = open(os.path.join(HERE, "viz", "template.html")).read().replace("__DATA__", json.dumps(data, separators=(",", ":")))
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    # Standalone file: a doctype and charset in front so it renders correctly when opened directly.
    with open(args.out, "w") as f:
        f.write('<!doctype html>\n<meta charset="utf-8">\n<meta name="viewport" content="width=device-width, initial-scale=1">\n' + fragment)
    print(f"wrote {args.out} ({os.path.getsize(args.out) // 1024} KB)")
    if args.fragment_out:   # for publishing as an artifact, which supplies its own document skeleton
        with open(args.fragment_out, "w") as f:
            f.write(fragment)


if __name__ == "__main__":
    main()
