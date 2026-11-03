"""Benchmark the exported DQN policy against flooding and DTN baselines on held-out worlds.

    python evaluate.py --policy ../assets/policy/dqn_policy.json --runs 30

Every policy sees the same worlds (same seeds => same mobility and traffic), so
comparisons are paired. Test seeds (1000+) are disjoint from training/validation seeds.
"""
import argparse
import json
import os
from concurrent.futures import ProcessPoolExecutor
from dataclasses import replace

import numpy as np
from scipy import stats

from crisislink import policies as P
from crisislink.config import SimConfig
from crisislink.network import DTNSimulator

TEST_SEED0 = 1000
METRICS = ["delivery_ratio", "urgent_delivery_ratio", "median_latency_min", "overhead",
           "transmissions", "mean_battery"]


class JsonQNet:
    """The shipped policy file, evaluated exactly as the app will evaluate it."""

    def __init__(self, path):
        layers = json.load(open(path))["layers"]
        self.layers = [(np.array(l["w"], np.float32), np.array(l["b"], np.float32), l["act"] == "relu")
                       for l in layers]

    def q_values(self, feats):
        h = np.asarray(feats, np.float32)
        for w, b, relu in self.layers:
            h = h @ w + b
            if relu:
                h = np.maximum(h, 0)
        return h


def make_policy(name, policy_path):
    if name.startswith("dqn"):
        margin = float(name.split("@")[1]) if "@" in name else 0.0
        return P.DQNPolicy(JsonQNet(policy_path), margin=margin)
    return {"direct": P.Direct, "epidemic": P.Epidemic,
            "spray_and_wait": P.SprayAndWait, "prophet": P.Prophet}[name]()


def run_one(task):
    cfg, name, seed, policy_path = task
    return name, seed, DTNSimulator(cfg, seed).run(make_policy(name, policy_path))


def experiment(cfg, names, runs, policy_path, pool):
    tasks = [(cfg, n, TEST_SEED0 + s, policy_path) for n in names for s in range(runs)]
    out = {n: {m: [] for m in METRICS} for n in names}
    for name, _, res in pool.map(run_one, tasks, chunksize=2):
        for m in METRICS:
            out[name][m].append(res[m])
    return out


def ci95(x):
    x = np.asarray(x, float)
    if len(x) < 2:
        return 0.0
    return float(stats.t.ppf(0.975, len(x) - 1) * x.std(ddof=1) / np.sqrt(len(x)))


def table(results, names, title):
    lines = [f"### {title}", "",
             "| policy | delivery | urgent delivery | median latency (min) | tx per delivery | transmissions |",
             "|---|---|---|---|---|---|"]
    for n in names:
        r = results[n]
        cell = lambda m, f="{:.2f}": f"{f.format(np.mean(r[m]))} ± {f.format(ci95(r[m]))}"
        lines.append(f"| {n} | {cell('delivery_ratio')} | {cell('urgent_delivery_ratio')} | "
                     f"{cell('median_latency_min', '{:.1f}')} | {cell('overhead', '{:.1f}')} | "
                     f"{cell('transmissions', '{:.0f}')} |")
    return "\n".join(lines) + "\n"


def paired(results, a, b, metric):
    x, y = np.array(results[a][metric]), np.array(results[b][metric])
    d = x - y
    t, p = stats.ttest_rel(x, y)
    return float(d.mean()), float(p)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--policy", default="../assets/policy/dqn_policy.json")
    ap.add_argument("--runs", type=int, default=30)
    ap.add_argument("--sweep-runs", type=int, default=10)
    ap.add_argument("--out", default="results")
    ap.add_argument("--skip-sweeps", action="store_true")
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)
    names = ["direct", "epidemic", "spray_and_wait", "prophet", "dqn", "dqn@0.03"]
    base = SimConfig()
    report, raw = [], {}

    with ProcessPoolExecutor() as pool:
        main_res = experiment(base, names, args.runs, args.policy, pool)
        raw["main"] = main_res
        report.append(table(main_res, names,
                            f"Main: {base.n_nodes} nodes, buffer {base.buffer_size}, {args.runs} runs (mean ± 95% CI)"))
        for metric in ("delivery_ratio", "overhead"):
            d, p = paired(main_res, "dqn", "epidemic", metric)
            report.append(f"Paired DQN − epidemic, {metric}: {d:+.3f} (p = {p:.2g})\n")

        if not args.skip_sweeps:
            for label, key, values, cfgs in (
                ("Buffer size", "buffer", [6, 12, 24, 200], lambda v: replace(base, buffer_size=v)),
                ("Node count", "nodes", [25, 50, 100], lambda v: replace(base, n_nodes=v)),
            ):
                sweep = {}
                for v in values:
                    sweep[v] = experiment(cfgs(v), names, args.sweep_runs, args.policy, pool)
                    report.append(table(sweep[v], names, f"{label} = {v} ({args.sweep_runs} runs)"))
                raw[key] = {str(v): sweep[v] for v in values}
                _plot(sweep, names, label, os.path.join(args.out, f"sweep_{key}.png"))

    _plot_tradeoff(raw["main"], names, os.path.join(args.out, "tradeoff.png"))
    with open(os.path.join(args.out, "results.json"), "w") as f:
        json.dump(raw, f)
    with open(os.path.join(args.out, "report.md"), "w") as f:
        f.write("\n".join(report))
    print("\n".join(report))


def _plot(sweep, names, label, path):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    xs = list(sweep)
    fig, ax = plt.subplots(1, 2, figsize=(9, 3.5))
    for n in names:
        for a, m in zip(ax, ("delivery_ratio", "overhead")):
            a.errorbar(range(len(xs)), [np.mean(sweep[x][n][m]) for x in xs],
                       yerr=[ci95(sweep[x][n][m]) for x in xs], marker="o", label=n, capsize=2)
    for a, t in zip(ax, ("Delivery ratio", "Transmissions per delivery")):
        a.set_xticks(range(len(xs)), [str(x) for x in xs]); a.set_xlabel(label); a.set_title(t)
    ax[0].legend(fontsize=7)
    fig.tight_layout(); fig.savefig(path, dpi=140); plt.close(fig)


def _plot_tradeoff(res, names, path):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    fig, ax = plt.subplots(figsize=(5, 3.8))
    for n in names:
        ax.errorbar(np.mean(res[n]["overhead"]), np.mean(res[n]["delivery_ratio"]),
                    xerr=ci95(res[n]["overhead"]), yerr=ci95(res[n]["delivery_ratio"]), marker="o", label=n, capsize=2)
    ax.set_xlabel("Transmissions per delivery"); ax.set_ylabel("Delivery ratio"); ax.legend(fontsize=7)
    fig.tight_layout(); fig.savefig(path, dpi=140); plt.close(fig)


if __name__ == "__main__":
    main()
