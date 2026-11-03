"""Dump real simulator decisions (raw inputs + feature vector) for the Dart parity test.

    python make_fixtures.py   # writes ../test/fixtures/feature_cases.json
"""
import json
import os
from dataclasses import replace

import numpy as np

from crisislink import policies as P
from crisislink.config import SimConfig
from crisislink.network import DTNSimulator

OUT = os.path.join(os.path.dirname(__file__), "..", "test", "fixtures", "feature_cases.json")


class Probe(P.Epidemic):
    def __init__(self):
        self.cases = []
        self.rng = np.random.default_rng(7)

    def decide(self, pair):
        sim, a, b = pair.sim, pair.a, pair.b
        if len(self.cases) < 400 and self.rng.random() < 0.05:
            i = int(self.rng.integers(len(pair.msgs)))
            m = int(pair.msgs[i])
            dst = int(sim.dst[m])
            self.cases.append({
                "inputs": {
                    "urgent": bool(sim.urgent[m]),
                    "ttlLeft": float(sim.ttl_left(np.array([m]))[0]),
                    "hops": int(sim.hops[a, m]),
                    "pSelfToDst": float(sim.P[a, dst]),
                    "pPeerToDst": float(sim.P[b, dst]),
                    "peerFree": float(sim.free_frac(b)),
                    "selfFree": float(sim.free_frac(a)),
                    "peerBattery": float(sim.battery[b]),
                    "selfBattery": float(sim.battery[a]),
                    "linkQuality": float(np.clip(1 - sim.dist[a, b] / sim.cfg.comm_range, 0, 1)),
                    "contactAgeSeconds": float(sim.contact_age[a, b]),
                    "neighbours": int(sim.adj[a].sum()),
                    "isSource": bool(sim.src[m] == a),
                    "peerIsDestination": bool(dst == b),
                    "copiesMade": int(sim.copies[a, m]),
                },
                "features": [float(x) for x in pair.feats[i]],
            })
        return super().decide(pair)


if __name__ == "__main__":
    probe = Probe()
    DTNSimulator(replace(SimConfig(), duration=3 * 3600.0), 99).run(probe)
    cases = probe.cases[:60]
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    json.dump(cases, open(OUT, "w"))
    print(f"wrote {len(cases)} cases to {OUT}")
