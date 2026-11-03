# Fixes and next steps

Ranked by priority. Each item says what is wrong and what to do about it.

## 1. Test on real phones

The Bluetooth layer, foreground service and Keystore storage compile and pass lint but have never run on a device. Everything else depends on this.

- Two phones: they find each other with no taps, exactly one link forms, a sealed message arrives and the sender sees it delivered.
- Screen off and app swiped away: a message still arrives within a couple of minutes.
- Reboot: the service comes back on its own.
- Three phones: A sends to C while out of range, B carries it, and A's copy is dropped once C's signed receipt reaches A.
- Expect trouble on specific makers (connection status 133, scan throttling, aggressive background killing on Xiaomi, Huawei and Samsung). Note what each phone needed.
- Log contact length and throughput per link. These are the numbers needed for item 9.

## 2. Phone clocks

Offline phones drift. The app rejects messages stamped more than 10 minutes in the future and expires messages by the sender's clock, so a wrong clock can silently drop or kill messages.

- Expire by time since first seen on this phone, not only by the sender's timestamp.
- Be forgiving about future timestamps, or clamp them, instead of dropping.

## 3. Urgent-flag abuse

Anyone can mark a message urgent, and an urgent message evicts a normal one from a full buffer (12 slots). A flood of fake urgent messages pushes out everyone else's.

- Limit how many buffer slots urgent messages may hold in total.
- Limit urgent messages accepted per sender per hour.
- Prefer messages from known contacts when the buffer is under pressure.

## 4. Stored messages are readable on disk

Keys are encrypted by the Android Keystore, but received message text is stored in plain text in the database.

- Encrypt message text before it goes into Room (a Keystore-wrapped key, or SQLCipher for the whole database).
- Panic wipe already deletes everything; make sure it also clears notifications.

## 5. Replayed handshakes

A recorded `HELLO` can be played back later. It is signed, so it cannot be forged, but nothing checks that it is fresh.

- Reject a `HELLO` whose timestamp is far from now (after item 2 makes clocks tolerable).
- Remember recent `HELLO` signatures per link and drop repeats.

## 6. Hybrid routing: give the DQN the spray tokens

The learned policy delivers 0.75 against Spray-and-Wait's 0.81 in the 30-world benchmark. It cannot see how many copies it is allowed to make.

- Add remaining spray tokens as a state feature and hard-cap copies the way Spray-and-Wait does. The network then only chooses which neighbours deserve a copy, using their encounter history with the destination.
- This changes the feature list, so edit `features.py`, `FeatureBuilder.kt` and `DqnRouter` together, rerun `make_fixtures.py`, retrain, and keep `ParityTest` green.
- The Kotlin engine already splits tokens for every router, but `DqnRouter` ignores them today.

## 7. Train on many worlds

Every training run used 50 nodes, a 12-message buffer and one traffic level. Randomise node count, area, buffer size, message rate and lifetime per episode so the policy does not overfit one setup.

## 8. Search the training settings properly

- Tune `copy_credit` (0.7, never tuned) and `tx_cost` together. Only 0.005, 0.01 and 0.02 were tried.
- Run several seeds, generate episodes on all cores, and pick the winner by paired comparison against Spray-and-Wait over many validation worlds instead of 5 or 6.
- Smaller upgrades to try after that: a larger or dueling network, multi-step returns, prioritized replay.

## 9. Fairer baseline, and real numbers

- Sweep Spray-and-Wait's token count (only 8 was used) so the bar the DQN has to clear is honest.
- Run the buffer-size and node-count sweeps that were skipped (`python evaluate.py` without `--skip-sweeps`).
- Replace simulator guesses (contact range, link budget, message rate) with the measurements from item 1, then retrain.
- Keep Spray-and-Wait as the default router in the app until the learned one beats it on real data.

## 10. Android tests and CI

Only the shared logic in `core/` is tested. Add instrumented tests for the Room database and the Keystore identity store, and a CI job that runs `./gradlew :core:test :app:lintDebug` and the Python tests.

## 11. Release packaging

- Signing config, minification, a monochrome launcher icon.
- Google Play restricts the battery-optimisation exemption prompt (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`). Decide whether to keep it or send people to settings instead.

## 12. Usability

- Show contact names for senders instead of an ID prefix.
- Clearer delivery states and a short first-run explanation of what the app does and does not protect.
- Nobody besides the author has used the interface yet.

## 13. Stronger cryptography (later)

- Messages sealed to a contact have no forward secrecy. Fixing that needs an interactive exchange, which is hard when the recipient is offline.
- Encrypt each hop between neighbouring phones (Noise XX) so passers-by cannot see who a message is for.
- Handle a contact's key changing.
- Get an independent review before anyone depends on this for something that matters.

## 14. iOS (maybe never)

Background Bluetooth is much more restricted on iPhone, so it would be a separate effort.
