### Main: 50 nodes, buffer 12, 30 runs (mean ± 95% CI)

| policy | delivery | urgent delivery | median latency (min) | tx per delivery | transmissions |
|---|---|---|---|---|---|
| direct | 0.36 ± 0.03 | 0.36 ± 0.04 | 50.8 ± 2.4 | 1.0 ± 0.0 | 143 ± 11 |
| epidemic | 0.69 ± 0.04 | 0.99 ± 0.01 | 58.3 ± 2.6 | 21.1 ± 0.6 | 5771 ± 253 |
| spray_and_wait | 0.81 ± 0.03 | 0.90 ± 0.02 | 48.6 ± 3.3 | 8.1 ± 0.2 | 2610 ± 57 |
| prophet | 0.73 ± 0.04 | 0.94 ± 0.02 | 55.9 ± 2.7 | 11.0 ± 0.2 | 3244 ± 181 |
| dqn | 0.75 ± 0.03 | 0.85 ± 0.02 | 39.5 ± 2.4 | 9.4 ± 0.5 | 2853 ± 234 |
| dqn@0.03 | 0.72 ± 0.03 | 0.75 ± 0.03 | 38.0 ± 2.5 | 7.5 ± 0.4 | 2196 ± 209 |

Paired DQN − epidemic, delivery_ratio: +0.062 (p = 3.5e-06)

Paired DQN − epidemic, overhead: -11.730 (p = 1.8e-21)
