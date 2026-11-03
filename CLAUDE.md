# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

GazaLink is an Android-targeted Flutter app (Dart SDK ^3.8.1) for delay-tolerant messaging over Bluetooth LE in crisis zones. Message forwarding is decided by a Deep Q-Learning policy that is **trained in Python (`simulation/`) and run on-device in Dart (`lib/routing/`)**. The `ios/` project was deliberately removed; do not re-add iOS-specific code. README.md has the overview and known limitations.

## Commands

Flutter (repo root):
- `flutter pub get`, `flutter analyze`, `flutter test`
- Single test: `flutter test test/routing_test.dart --plain-name "holds when the peer is no better placed"`
- `flutter run -d android` — BLE needs physical devices

Simulation (`cd simulation`; deps in `requirements.txt`, a local `.venv` with `--system-site-packages` is used for pytest):
- `.venv/bin/python -m pytest tests -q` (single: `... tests/test_sim.py::test_direct_has_unit_overhead`)
- `python train.py --episodes 150 --tx-cost 0.08 --out ../assets/policy/dqn_policy.json`
- `python evaluate.py [--skip-sweeps]` — paired benchmark on held-out seeds, writes `results/`
- `python make_fixtures.py` — regenerate `test/fixtures/feature_cases.json`

## Architecture

**Two implementations of one contract.** The 16-feature decision state is defined in `simulation/crisislink/features.py` (+ `network.py:features`) and re-implemented in `lib/routing/feature_builder.dart`. The trained network ships as `assets/policy/dqn_policy.json` (declared in pubspec). `test/feature_parity_test.dart` (fixtures from the simulator) and `test/dqn_policy_test.dart` (parity vectors embedded in the policy JSON) fail if the two sides drift. **Any change to features, their order, or their normalisation must be made in both and followed by `make_fixtures.py` + retraining/re-export** — `DqnPolicy.fromJson` also rejects a feature-name mismatch.

**Simulator (`simulation/crisislink/`)**: `network.py` is a discrete-time DTN simulator (world RNG seeded separately from policy RNG, so all policies see identical worlds per seed); `mobility.py` clustered random-waypoint; `policies.py` baselines (direct, epidemic/flooding, spray-and-wait, PRoPHET) + `DQNPolicy`; `learning.py` replay, `TransitionBuilder`, double-DQN `Trainer`; `export.py` writes the policy JSON. Seeds: train 10000+, validation 50000+, test 1000+ (never mix).

**RL formulation (non-obvious, easy to break)**: decision unit = (message at node a, peer b), actions hold/forward. Semi-MDP: next states are sampled at each node's next decision for that message, discounted by elapsed time. A forward target sums *both* copies' futures (sender keeps its copy: `wa`, receiver's new copy: `wb = copy_credit`); crediting only the receiver makes forwarding look worse than holding by exactly the tx cost and training collapses to "never forward". Hold transitions are subsampled to one per 300 s and batches are balanced 50/50 by action, otherwise the buffer is ~all holds. `tx_cost` is the delivery-vs-overhead dial.

**App routing (`lib/routing/`, `lib/services/`)**: `BluetoothService` (singleton, flutter_blue_plus central role) syncs per peer via two length-prefixed frames (`sync_protocol.dart`): *hello* (`PeerSummary`: battery, free buffer, PRoPHET row, held ids, delivered ids) then *bundle* (only messages `RoutingService.selectForPeer` picked: destination first, then urgent, then Q-advantage, capped at `maxPerExchange`). `EncounterTable` mirrors the simulator's PRoPHET update. `StorageService` enforces the 12-message buffer (only urgent may evict the oldest normal), tracks delivered ids (anti-packets), TTL expiry, and `copiesMade`. Without a loadable policy asset, routing falls back to the PRoPHET rule. Messages with no destination are broadcasts (hop-capped, not learned).

## Gotchas

- `flutter_blue_plus` has no peripheral role: `startAdvertising()` is only a flag and there is no GATT server, so real two-phone sync is not yet possible. Don't claim end-to-end BLE sync works.
- Shared constants must match across languages: buffer size 12 (`SimConfig.buffer_size` / `StorageService.bufferCapacity`), TTL 2 h, PRoPHET constants (`SimConfig` p_* / `EncounterTable`), `max_hops` 10, `COPIES_CAP` 8.
- `simulation/results/*.log|json`, `.venv/` are local artifacts.
