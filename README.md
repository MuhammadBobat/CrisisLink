# CrisisLink: offline Bluetooth message mesh for crisis zones

CrisisLink is an Android app that passes messages phone to phone over Bluetooth LE when there is no
internet, WiFi or cellular service. Phones carry other people's messages onward and hand them over
when they meet the recipient. A **Deep Q-Learning policy trained in simulation** decides which
messages to copy to which neighbour, so batteries, buffers and the slow radio are not wasted on flooding.

**Status: a working prototype that has not yet been run on real phones.** All logic is unit-tested and
the app builds, but nothing has been tested over a real BLE link. See [Limitations](#limitations).

## How it works

- **Every phone is both a BLE GATT server and client.** It advertises a CrisisLink service with its node ID
  in the scan response and scans for others. Only the phone with the lower ID dials, so each pair has one link.
- **A foreground service** (`connectedDevice`) keeps the mesh running with the app closed, restarts after
  reboot, and switches between four power profiles (scan duty cycles from continuous down to 1 s every 30 s)
  by charging state, battery level and foreground/background.
- **Handshake, then routed bundle.** Linked phones swap a signed `HELLO` (battery, free buffer, encounter
  history, held message IDs, signed delivery acks). Each side then sends only the messages its router picks
  for that neighbour, urgent first.
- **Routing from the local view.** The default router is a 16-input Q-network ([simulation/](simulation/README.md)).
  Spray-and-Wait and PRoPHET are selectable in the app; Spray-and-Wait delivered more in simulation.
- **Delivery receipts** are signed by the recipient, so relays can drop their copies without anyone being able
  to forge "delivered" and suppress a message.

## Encryption

| What | How |
|---|---|
| Identity | X25519 (encrypt) + Ed25519 (sign) keys generated on first launch, stored encrypted by an Android Keystore AES-GCM key. Node ID = first 8 bytes of SHA-256 of both public keys |
| Contacts | Exchanged in person by QR code (name + both public keys). The node ID is recomputed from the keys, so a code cannot claim another's ID. Fingerprints can be compared and marked verified |
| Direct messages | Sealed to the recipient with HPKE (X25519, HKDF-SHA256, ChaCha20-Poly1305) via Google Tink, padded to 256/512/1024/2048-byte buckets, signed by the sender, with the signed header bound into the ciphertext |
| Broadcasts | Signed, not encrypted (they are public). Hop-capped at 3 |
| Handshakes and acks | Signed; unsigned or badly signed packets are rejected before anything is stored |
| Abuse limits | Per-link packet rate limit, payload and fragment size caps, reassembly timeouts |
| Panic wipe | Deletes keys, database and settings |

**What this does not protect:** sealed messages have **no forward secrecy** (the recipient may be offline for hours, so
there is no interactive handshake); relays can see who a message is addressed to, its priority and its size bucket;
there is no hop-by-hop link encryption yet; and anyone can read broadcasts. Treat the design as unreviewed until it
has had an independent security review.

## Repository layout

| Path | What it is |
|---|---|
| `core/` | Pure-Kotlin library (JVM-testable): routing, binary protocol, fragmentation, crypto, mesh engine, storage interface |
| `app/` | Android app: BLE transport, power profiles, foreground service, Room storage, Keystore, Compose UI |
| `app/src/main/assets/policy/dqn_policy.json` | The trained policy (weights plus parity vectors) |
| `simulation/` | Python simulator, DQN trainer, baselines, benchmark |

## Build and test

Needs JDK 17+ (21 works) and the Android SDK (platform 35, build-tools 35).

```bash
./gradlew :core:test              # 29 tests, no device needed
./gradlew :app:assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:lintDebug
cd simulation && python -m pytest tests -q
```

Retrain the policy: `cd simulation && python train.py --episodes 150` (writes into the app's assets), then
`python make_fixtures.py` and `./gradlew :core:test`. The parity tests fail if Python and Kotlin disagree.

## Limitations

- **Not tested on hardware.** BLE behaviour varies a lot by vendor (advertising limits, GATT status 133, scan
  throttling, background killing on Xiaomi/Huawei/Samsung). Expect to tune on real devices.
- **The learned policy loses to Spray-and-Wait on delivery** in simulation (0.75 vs 0.81) and has only been
  evaluated on synthetic mobility. See [simulation/README.md](simulation/README.md).
- **Android only.** iPhones cannot take part.
- **Contacts only for private messages**, since the sender needs the recipient's public key while the recipient is offline.
- **Unreviewed cryptography design**; no forward secrecy; metadata is visible to relays.
