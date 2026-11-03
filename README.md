# CrisisLink

An Android app that passes messages from phone to phone over Bluetooth when there is no internet, WiFi or mobile signal. Phones carry other people's messages along and hand them over when they meet the recipient.

This is a personal project. I built it to practice deep Q-learning on a problem with a real use: communication during a crisis, where the network is down but people still move around and meet each other. A trained network decides which messages to copy to which nearby phone. It has not been tested on real devices yet, so treat it as a learning project that is aimed at being usable, not a finished app. Known problems are listed in [TODO.md](TODO.md).

## How it works

Every phone advertises over Bluetooth LE and scans for others at the same time. When two phones connect, they swap a short signed summary (battery, free space, who each has met lately, which messages they hold, which deliveries they know about). Each phone then decides, message by message, whether to give the other a copy. Nothing is routed along a planned path; each phone only looks at itself and the phone in front of it.

That decision is made by a small Q-network with two actions, hold or forward. Its input is 16 numbers about the message and the neighbour: priority, time left, hops so far, how likely each phone is to meet the destination, free buffer, battery, signal strength, copies already made, and a few more. The reward is positive when a message reaches its recipient and costs a little for every handoff, so the network learns to spend radio and battery only where it helps.

The network is trained in Python against a simulator and shipped to the phone as a JSON file. The Kotlin app runs only the forward pass. A parity test checks that Kotlin and Python produce the same inputs and the same outputs.

Messages to a contact are encrypted to their key and signed. Contacts are added in person by scanning a QR code. Delivery receipts are signed by the recipient, so a relay can drop its copy only when the real recipient has confirmed.

## What it is compared against

The simulator has 50 phones moving between shelter areas, a 40 m Bluetooth range, 12-message buffers and 2-hour message lifetimes. Each strategy is run on the same 30 worlds (same movement, same messages) so the comparison is paired.

| Strategy | Delivered | Handoffs per delivery | Median delay |
|---|---|---|---|
| Direct only | 0.36 | 1.0 | 51 min |
| Flooding | 0.69 | 21.1 | 58 min |
| PRoPHET | 0.73 | 11.0 | 56 min |
| Spray-and-Wait | 0.81 | 8.1 | 49 min |
| Learned (DQN) | 0.75 | 9.4 | 40 min |

The learned policy beats flooding on delivery with far fewer handoffs, and is the fastest. Spray-and-Wait still delivers more, so the app lets you choose the router and I would use Spray-and-Wait until the learned one catches up. All of this is on simulated movement. [simulation/README.md](simulation/README.md) has the full setup and the training details.

You can watch a run in `simulation/viz/crisislink_sim.html`: the same 50 people under flooding, Spray-and-Wait and the learned policy, with any one message traced across the map.

## Layout

| Path | What it is |
|---|---|
| `simulation/` | Python simulator, DQN training, baselines, benchmark |
| `core/` | Kotlin library with routing, wire format, encryption and the mesh engine (JVM tests) |
| `app/` | Android app: Bluetooth, background service, storage, UI |
| `app/src/main/assets/policy/` | The trained network |

## Build

Needs JDK 17+ and the Android SDK (platform 35).

```bash
./gradlew :core:test            # logic tests, no phone needed
./gradlew :app:assembleDebug    # builds the APK
cd simulation && pip install -r requirements.txt && python train.py --episodes 150
```
