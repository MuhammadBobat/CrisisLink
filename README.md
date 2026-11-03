# GazaLink: Offline BLE Message Routing for Crisis Zones

GazaLink is an Android Flutter app for delay-tolerant messaging when there is no internet, WiFi or
cellular service. Phones exchange messages directly over low-rate Bluetooth LE whenever two of them
meet, and carry other people's messages onward until they reach the recipient.

The hard part is deciding **which messages to hand to which peer**. Flooding everything to everyone
delivers well but exhausts batteries, buffers and a very slow radio. GazaLink instead uses a
**Deep Q-Learning policy trained in simulation** and evaluated on-device: each phone decides, from
its own local view plus a small handshake, whether to copy a message to the peer in front of it.

## How it works

```
 simulation/ (Python)                      lib/ (Flutter / Dart)
 ────────────────────                      ──────────────────────
 mobility + traffic + BLE budget  ─┐
 DQN training (PyTorch)            ├─►  assets/policy/dqn_policy.json ─► DqnPolicy (pure Dart MLP)
 benchmark vs flooding & DTN       ─┘                                     │
                                                                          ▼
 BLE scan/connect ─► hello (PeerSummary) ─► RoutingService.selectForPeer ─► bundle ─► ingest
```

1. **Hello:** two connected nodes swap a small summary: battery, free buffer, their encounter
   predictability row (PRoPHET-style), the ids of messages they hold, and ids known to be delivered.
2. **Decide:** for each message it holds, a node builds a 16-feature state (priority, remaining TTL,
   hops, how likely each side is to meet the destination, buffer/battery, link quality, copies
   already made, ...) and asks the Q-network whether forwarding beats holding.
3. **Bundle:** only the chosen messages (urgent first, a destination always first) are sent, within a
   small per-contact budget. Delivered ids spread as anti-packets so nodes stop carrying them.

No routes are computed or stored. See [simulation/README.md](simulation/README.md) for the
simulation model, state/action/reward design and benchmark results.

## Repository layout

| Path | What it is |
|---|---|
| `lib/routing/` | feature builder, Q-network inference, encounter table, handshake summary, routing service |
| `lib/services/` | BLE service (scan, connect, framed sync), storage (buffer limits, anti-packets), frame codec |
| `lib/screens`, `lib/widgets` | message queue, create (with recipient node ID), dashboard (your node ID, policy status) |
| `simulation/` | Python simulator, DQN trainer, baselines, evaluation, tests |
| `assets/policy/dqn_policy.json` | the trained policy shipped in the app (weights + parity vectors) |

## Getting started

```bash
flutter pub get
flutter analyze
flutter test
flutter run -d android        # BLE needs physical devices; use two to test sync
```

Android permissions (Bluetooth scan/connect, location for BLE scanning) are declared in
`android/app/src/main/AndroidManifest.xml`. Development targets Android; the `ios/` project was removed.

### Retraining the policy

```bash
cd simulation
pip install -r requirements.txt
python train.py --episodes 150 --out ../assets/policy/dqn_policy.json
python evaluate.py                 # benchmark on held-out worlds
python make_fixtures.py            # refresh Dart feature-parity fixtures
cd .. && flutter test              # parity tests fail if Python and Dart disagree
```

## Addressing messages

Each install has an 8-character **node ID** (dashboard, with a copy button). When creating a message,
enter the recipient's node ID to have it routed to them, or leave it empty to broadcast to nearby
nodes (flooded up to 3 hops).

## Known limitations

- **No GATT server yet.** `flutter_blue_plus` is central-only, so this build can connect to and sync
  with a peer that exposes the GazaLink service, but a phone cannot yet advertise and serve the
  message characteristic itself. Two-phone sync needs a native Android GATT server (not implemented);
  `startAdvertising()` only sets a flag. The routing, framing and storage logic is fully tested
  without hardware.
- **Sim-to-real gap.** The policy is trained on synthetic clustered-waypoint mobility. Validate against
  real contact traces before relying on the benchmark numbers.
- No message encryption yet.
