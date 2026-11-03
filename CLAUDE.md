# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

CrisisLink is a native Android app (Kotlin, Jetpack Compose) for delay-tolerant, end-to-end-encrypted messaging over Bluetooth LE. Forwarding is decided by a Deep Q-Learning policy **trained in Python (`simulation/`) and evaluated on-device in Kotlin (`core/`)**. It replaced an earlier Flutter app (the Flutter code and `ios/` are gone; do not re-add them). README.md has the overview; TODO.md has the ranked list of known problems and next steps.

## Commands

Kotlin (repo root; needs JDK 17+ and the Android SDK — `local.properties` points `sdk.dir` at `/opt/homebrew/share/android-commandlinetools`; use `JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home`):
- `./gradlew :core:test` — all logic tests, no device. Single class: `./gradlew :core:test --tests 'org.crisislink.core.MeshEngineTest'`
- `./gradlew :app:assembleDebug`, `./gradlew :app:lintDebug` (keep lint at 0 errors)

Simulation (`cd simulation`; a local `.venv` with `--system-site-packages` has pytest):
- `.venv/bin/python -m pytest tests -q`
- `python train.py --episodes 150 --tx-cost 0.01` (writes `app/src/main/assets/policy/dqn_policy.json`)
- `python evaluate.py --runs 30 --skip-sweeps` (the sweeps are slow); `python make_fixtures.py`

## Architecture

**Two implementations of one contract.** The 16-feature decision state is defined in `simulation/crisislink/features.py` (+ `network.py:features`) and re-implemented in `core/.../routing/FeatureBuilder.kt`. `ParityTest` checks Kotlin against `simulation/fixtures/feature_cases.json` (feature vectors from the simulator) and against the parity vectors embedded in `dqn_policy.json` (Q-values from PyTorch). **Any change to features, order or normalisation must be made on both sides, then `make_fixtures.py`, retrain/re-export, and `:core:test`.** `DqnPolicy.fromJson` also rejects a feature-name mismatch.

**`core/` (pure Kotlin, JVM tests)** is everything that is not Android: `routing/` (`Router` base with shared rules — destination first, then urgent, per-exchange cap, broadcast hop cap — and `DqnRouter`/`ProphetRouter`/`SprayAndWaitRouter`, `EncounterTable`), `protocol/` (`Packets` binary format, `Fragmenter`/`Reassembler`), `crypto/` (`Identity`, `Crypto` = Tink HPKE sealing + Ed25519), `mesh/MeshEngine` (the store-and-forward state machine over an abstract `Link`), `storage/MeshStore` + `BufferPolicy`. `FakeNetwork` in the tests wires engines together without BLE. Prefer testing new behaviour there.

**`app/` (Android)**: `ble/BleTransport` is GATT server and client at once; the lower node ID dials (tiebreak via the 8-byte node ID in the scan response); `GattLink` fragments packets and sends one frame at a time. `ble/PowerProfiles` holds the bitchat-derived schedule table. `mesh/MeshRuntime` owns identity/DB/engine/transport (single worker thread; BLE callbacks arrive on binder threads), `service/MeshForegroundService` (type `connectedDevice`, sticky) starts it, `BootReceiver` restarts it. `storage/Db.kt` is Room behind `MeshStore`. `identity/KeystoreIdentityStore` wraps keys with an Android Keystore AES-GCM key. UI is Compose (`ui/`).

**Security invariants (easy to break):** nothing is stored or forwarded before its signature verifies; a delivery ack counts only if signed by the message's *destination* (otherwise anyone can suppress messages by forging acks); the sealed payload binds the signed header as HPKE context; node IDs are derived from the public keys, never taken from a packet.

**RL formulation (non-obvious)**: decision unit = (message at node a, peer b), actions hold/forward, semi-MDP time discounting. A forward's target sums *both* copies' futures (the sender keeps its copy); crediting only the receiver makes forwarding look worse than holding by exactly the tx cost and training collapses to "never forward". Hold transitions are subsampled to one per 300 s and batches are balanced by action. `tx_cost` is the delivery-vs-overhead dial. Seeds: train 10000+, validation 50000+, test 1000+ (never mix).

## Gotchas

- Shared constants must match across Python and Kotlin: buffer 12 (`SimConfig.buffer_size` / `BufferPolicy.CAPACITY`), TTL 2 h, PRoPHET constants, `max_hops` 10, `COPIES_CAP` 8, spray copies 8.
- The BLE layer has **never run on a real phone**; do not claim it works end to end. Only `core/` is verified.
- `simulation/results/*.log|pt|json` and `.venv/` are local artifacts (gitignored).
