"""Train the shared-parameter forwarding DQN in simulation and export it for the app.

    python train.py --episodes 150 --out ../app/src/main/assets/policy/dqn_policy.json
"""
import argparse
import json
import time
from dataclasses import asdict, replace

import numpy as np
import torch

torch.set_num_threads(1)

from crisislink.config import RewardConfig, SimConfig
from crisislink.export import export_policy
from crisislink.learning import QNet, Replay, TransitionBuilder, Trainer
from crisislink.network import DTNSimulator
from crisislink.policies import DQNPolicy

TRAIN_SEED0 = 10_000     # training worlds
VAL_SEED0 = 50_000       # validation worlds (model selection); test worlds (1000+) are never touched here


def utility(summary: dict, sim_generated: int, tx_cost: float) -> float:
    return summary["delivery_ratio"] - tx_cost * summary["transmissions"] / max(sim_generated, 1)


def validate(qnet, cfg, rew, seeds):
    scores = []
    for s in seeds:
        sim = DTNSimulator(cfg, s)
        out = sim.run(DQNPolicy(qnet))
        scores.append((utility(out, sim.metrics.generated, rew.tx_cost), out))
    mean = float(np.mean([u for u, _ in scores]))
    return mean, {k: float(np.mean([o[k] for _, o in scores])) for k in ("delivery_ratio", "overhead")}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--episodes", type=int, default=150)
    ap.add_argument("--tx-cost", type=float, default=RewardConfig.tx_cost)
    ap.add_argument("--hidden", type=int, default=64)
    ap.add_argument("--val-every", type=int, default=10)
    ap.add_argument("--val-seeds", type=int, default=5)
    ap.add_argument("--updates-per-step", type=int, default=1)
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--out", default="../app/src/main/assets/policy/dqn_policy.json")
    args = ap.parse_args()

    torch.manual_seed(args.seed)
    cfg, rew = SimConfig(), replace(RewardConfig(), tx_cost=args.tx_cost)
    qnet = QNet(args.hidden)
    replay = Replay()
    trainer = Trainer(qnet, replay, seed=args.seed)
    rng = np.random.default_rng(args.seed)
    best, best_state = -1e9, None
    t0 = time.time()

    for ep in range(args.episodes):
        eps = max(0.05, 1.0 - ep / (0.6 * args.episodes))
        sim = DTNSimulator(cfg, TRAIN_SEED0 + ep)
        builder = TransitionBuilder(replay, rew)
        builder.bind(sim)
        policy = DQNPolicy(qnet, epsilon=eps, rng=rng)

        def cb():
            for _ in range(args.updates_per_step):
                trainer.update()

        out = sim.run(policy, builder, cb)
        line = (f"ep {ep:3d} eps {eps:.2f} replay {replay.n:6d} loss {trainer.last_loss:.4f} "
                f"train delivery {out['delivery_ratio']:.2f} overhead {out['overhead']:.1f}")
        if (ep + 1) % args.val_every == 0 or ep == args.episodes - 1:
            score, v = validate(qnet, cfg, rew, [VAL_SEED0 + i for i in range(args.val_seeds)])
            line += f" | val utility {score:.3f} delivery {v['delivery_ratio']:.2f} overhead {v['overhead']:.1f}"
            if score > best:
                best, best_state = score, {k: x.clone() for k, x in qnet.state_dict().items()}
        print(line, f"[{time.time() - t0:.0f}s]", flush=True)

    qnet.load_state_dict(best_state)
    meta = {"trained_episodes": args.episodes, "best_val_utility": best, "sim": asdict(cfg), "reward": asdict(rew)}
    export_policy(qnet, args.out, meta)
    torch.save(best_state, "results/dqn_best.pt")
    print(f"exported {args.out} (val utility {best:.3f})")


if __name__ == "__main__":
    main()
