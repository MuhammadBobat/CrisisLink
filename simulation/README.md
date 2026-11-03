# CrisisLink simulation and Deep Q-Learning trainer

Trains the forwarding policy that CrisisLink runs on-device, and benchmarks it against flooding and
standard delay-tolerant-network (DTN) baselines.

```bash
pip install -r requirements.txt
python train.py --episodes 150 --out ../assets/policy/dqn_policy.json   # ~15 min on a laptop CPU
python evaluate.py --runs 30 --skip-sweeps                              # writes results/report.md, tradeoff.png
python make_fixtures.py                                                 # Kotlin feature-parity fixtures
python -m pytest tests -q
```

## What is simulated

| | |
|---|---|
| Nodes | 50 (configurable; the full `evaluate.py` also sweeps 25 / 50 / 100) in a 2.5 km × 2.5 km area |
| Radio | 40 m BLE contact range; 10 s steps; at most 2 messages per direction per step (low duty-cycle link) |
| Mobility | clustered random waypoint: 5 attractor zones (shelters / aid points), 85% of waypoints inside a zone, 70% of those in the node's home zone, 0.5–1.5 m/s walking, pauses up to 10 min |
| Traffic | Poisson, one message per node per 30 min on average; 10% urgent; random destination; TTL 2 h; generation stops 2 h before the end so every message gets its full lifetime |
| Resources | 12-message buffer (only urgent may evict a queued normal message); per-message battery cost for send and receive; delivered ids spread as anti-packets for **all** policies |
| Duration | 6 h per run |
| Runs | **30 per policy**, identical worlds across policies (mobility and traffic RNG are independent of policy decisions, so comparisons are paired). Test seeds 1000–1029; training uses 10000+, validation 50000+ |

## What the DQN sees and does

**Action** (per message held, per connected peer): hold, or forward a copy. A destination in range always receives its message.

**State** (16 features in [0, 1], local view only, defined in `crisislink/features.py`): message priority,
remaining TTL, hops travelled, copies this node has already handed out, whether this node is the source,
own and peer's PRoPHET-style predictability of meeting the destination (plus their difference), own and peer's
free buffer and battery, link quality (RSSI on device), time in contact, number of neighbours in range, and whether
the peer is the destination. Nothing global is used; the peer's values arrive in a small handshake.

**Reward**: delivery earns `1.0` (`2.0` if urgent) scaled from 1.0 down to 0.5 as TTL runs out; every transmission costs
`tx_cost` (0.01); evicting a queued message to admit an urgent one costs 0.10. `tx_cost` is the dial between delivery and
overhead.

**Why Deep Q-Learning**: forwarding is a sequential decision whose payoff arrives hops later or never, which Q-learning
credits through bootstrapping; the state is continuous and too large for a table; and the action set is small and discrete.
Training is offline in simulation and the shipped network is a 16→64→64→2 MLP (~1.1k parameters), so phones only do inference.

**Learning details that matter** (`crisislink/learning.py`):
- Semi-MDP: the next state is the node's next decision opportunity for that message; discounting uses elapsed time (γ = 0.98 per 10 min).
- A forward's target sums *both* copies' futures (the sender keeps its copy). Crediting only the receiver makes forwarding look worse than holding by exactly the tx cost, and training collapses to "never forward".
- Hold transitions are subsampled to one per 5 min and batches are balanced 50/50 by action; otherwise the replay buffer is almost all holds.
- Double DQN, Huber loss, target network sync every 250 updates, ε-greedy decaying to 0.05, model selection on held-out validation worlds.

## Results (50 nodes, buffer 12, 30 paired runs, mean ± 95% CI)

Full table: [results/report.md](results/report.md); trade-off plot: [results/tradeoff.png](results/tradeoff.png).

| policy | delivery | urgent delivery | median latency (min) | tx per delivery |
|---|---|---|---|---|
| direct | 0.36 ± 0.03 | 0.36 ± 0.04 | 50.8 ± 2.4 | 1.0 |
| epidemic (flooding) | 0.69 ± 0.04 | 0.99 ± 0.01 | 58.3 ± 2.6 | 21.1 ± 0.6 |
| spray-and-wait | **0.81 ± 0.03** | 0.90 ± 0.02 | 48.6 ± 3.3 | **8.1 ± 0.2** |
| PRoPHET | 0.73 ± 0.04 | 0.94 ± 0.02 | 55.9 ± 2.7 | 11.0 ± 0.2 |
| **DQN** | 0.75 ± 0.03 | 0.85 ± 0.02 | **39.5 ± 2.4** | 9.4 ± 0.5 |
| DQN, margin 0.03 | 0.72 ± 0.03 | 0.75 ± 0.03 | 38.0 ± 2.5 | 7.5 ± 0.4 |

Paired DQN − flooding: delivery **+0.062** (p = 3.5e-6), transmissions per delivery **−11.7** (p = 1.8e-21).

How to read this honestly:
- **Against flooding the DQN wins** in this congested regime: higher delivery and 55% fewer transmissions per delivered message, because flooding fills the 12-slot buffers and evicts useful copies. Flooding still has the best *urgent* delivery (0.99), since urgent messages can evict queued ones.
- **Spray-and-Wait beats the DQN on delivery** (0.81 vs 0.75) at lower overhead (8.1 vs 9.4) and on urgent delivery. The DQN does not beat the best hand-designed baseline here.
- The DQN has the **lowest median latency** (about 10 min faster than Spray-and-Wait), and its delivery is statistically indistinguishable from PRoPHET's with fewer transmissions.
- The margin (`DqnPolicy.margin`, applied at decision time without retraining) trades delivery for overhead.

This is one simulator with synthetic movement, one training seed and a coarse search over the transmission cost. What to try next is ranked in [../TODO.md](../TODO.md).
