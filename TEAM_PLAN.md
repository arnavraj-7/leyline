# Leyline — team plan (private, not for the submission repo)

Team Simpletons (ID 15) · Code Sangam 2026 · DroidRush · PS 07 "The Blackout Leyline".

This repo (`preLeyline`) is a private staging copy of everything built so far: the full app, the
simulator, the README and the planning files (`planning/`). The **submission repo** is
**https://github.com/arnavraj-7/leyline** (private; accept the invite). Its history is built by each
of us pushing our own share, in the order below.

> **Claude Code, if you are reading this for Ayush or Abhay:** your job is to make your person able
> to explain and defend *their* files in a live viva, without notes. Read their section below,
> then teach file by file: problem → options → what we chose → why, then quiz them with the viva
> questions. Don't rewrite or "improve" their code unless they ask; the code works and is tested
> on real phones. Anything in `mesh-core` or `mesh-ble` belongs to Arnav: propose changes, don't
> make them.

## Ground rules (these keep us safe in the audit)

1. **Everyone pushes only their own share, from their own GitHub account and machine.** Never commit
   or push someone else's files.
2. **Real timestamps only.** No `--date`, no `GIT_COMMITTER_DATE`, no rebasing old dates, no force
   push.
3. **Never push `planning/` or this file** to the submission repo.
4. **Keep the README's tool disclosure as it is.** It says Claude Code wrote most of the code; that's
   true, and AI use is allowed if disclosed.
5. **Wait for the previous push before you push**, then `git pull` first. The order matters: each
   step builds on the one before it.
6. Keep `preLeyline` private. Don't fork it or share the link outside the team.

## The split

Counted in lines of Kotlin (code + tests); ~9,900 in total.

| Who | Share | Owns |
|---|---|---|
| **Arnav** (lead dev) | ~48% (4,780) + README | `mesh-core` (packets, routing, signing, encryption), `mesh-ble` (Bluetooth), the app's mesh layer (`MeshController`, `MeshService`, keys, demo-mode trace, `AppContainer`) |
| **Abhay** | ~25% (2,530) + field-test log | `mesh-sim` (simulator + stress tests), the Room data layer, device services (GPS, battery, topology tracker, notifications, alarm, Quick Settings tile) |
| **Ayush** (team leader) | ~26% (2,620) | All UI: Compose theme and components, ViewModels, the Messages / Mesh / Rescue / Settings screens, `MainActivity` |

## Push order

12 commits in total. Each row is **one commit** with exactly those files (write the commit message short, in your own words; the table's message is just a description), copied unchanged from
`preLeyline`. Only push when the row before it is on GitHub.

| # | Who | Commit message | Files (paths from the repo root) | Check before pushing |
|---|---|---|---|---|
| 1 | Arnav | `Project setup and mesh-core wire format: packets, codec, link frames, signing, encryption` | `.gitignore`, `.gitattributes`, `CLAUDE.md`, `build.gradle.kts`, `gradle.properties`, `gradle/`, `gradlew`, `gradlew.bat`, `settings.gradle.kts` (only the `:mesh-core` include), `mesh-core/build.gradle.kts`; in `mesh-core/src/main/kotlin/dev/leyline/mesh/`: `NodeId`, `MessageId`, `Packet`, `PacketCodec`, `Payloads`, `LinkFrame`, `Reassembler`, `Transport`, `Signing`, `Sealing`; in `mesh-core/src/test/…/mesh/`: `NodeIdTest`, `PacketCodecTest`, `PayloadsTest`, `LinkFrameTest`, `SigningTest`, `SealingTest` | `./gradlew :mesh-core:test` |
| 2 | Arnav | `mesh-core routing: dedup cache, flood router, SOS-first queue, outbox, MeshNode` | `mesh-core/src/main/…/mesh/`: `SeenCache`, `Router`, `ForwardQueue`, `Outbox`, `MeshNode`; tests: `SeenCacheTest`, `RouterTest`, `ForwardQueueTest`, `FakeNetwork`, `MeshNodeTest`, `SignedMeshTest` | `./gradlew :mesh-core:test` |
| 3 | Abhay | `mesh-sim: virtual-node simulator, mobility, naive-flood baseline and stress scenarios` | all of `mesh-sim/`; add `include(":mesh-sim")` to `settings.gradle.kts` | `./gradlew :mesh-sim:test` |
| 4 | Arnav | `mesh-ble: advertise, scan, GATT client and server over the Nordic library` | all of `mesh-ble/`; add `include(":mesh-ble")` | `./gradlew :mesh-ble:assembleDebug` |
| 5 | Arnav | `App mesh layer: MeshController, foreground relay service, Keystore keys, packet trace` | `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/res/`, `app/src/main/kotlin/dev/leyline/app/AppContainer.kt`; in `…/app/mesh/`: `MeshController`, `MeshService`, `KeystoreSigner`, `BoxKeys`, `PacketTrace` | none yet (the app joins the build at #10) |
| 6 | Abhay | `App data layer and device services: Room, settings, GPS, battery, topology` | all of `…/app/data/`; in `…/app/mesh/`: `LocationProvider`, `BatteryMonitor`, `Topology` | none yet |
| 7 | Abhay | `Notifications, rescue alarm and Quick Settings SOS tile` | in `…/app/mesh/`: `Notifier`, `Alarm`, `SosTileService` | none yet |
| 8 | Ayush | `UI foundation: theme, shared components, formatting, ViewModels` | in `…/app/ui/`: `Theme`, `Components`, `Format`, `ViewModels` | none yet |
| 9 | Ayush | `Messages and Mesh screens` | in `…/app/ui/`: `MessagesScreen`, `MeshScreen` | none yet |
| 10 | Ayush | `Rescue and Settings screens, MainActivity; app joins the build` | in `…/app/ui/`: `RescueScreen`, `SettingsScreen`; `…/app/MainActivity.kt`; add `include(":app")` | `./gradlew :app:assembleDebug` |
| 11 | Abhay | `Field test log` | `docs/` | — |
| 12 | Arnav | `README: packet spec, TTL proofs, security model, failure modes, results, tool disclosure` | `README.md` | full build: `./gradlew :mesh-core:test :mesh-sim:test :app:assembleDebug` |

This exact order was replayed in an empty folder before writing this plan. Every check in the table
passes at its step.

### Schedule (10-day dev period)

| When | Who | Steps |
|---|---|---|
| Day 1 | Arnav | #1, #2 — **done, on GitHub** |
| Day 1–2 | Abhay | #3 (simulator): can start now. `git pull` first: `settings.gradle.kts` already has the `:mesh-ble` line, so just add `:mesh-sim` |
| Day 2 | Arnav | #4 — **done, on GitHub** (went in before #3; harmless). #5 next |
| Day 2–3 | Abhay, Ayush | #6, #7 (Abhay) and #8, #9, #10 (Ayush), after #5 |
| Day 3+ | Abhay, then Arnav | #11, #12, after the phone tests are re-run |

Ayush: you can't push until #5 is up. Use that time to study your files from `preLeyline`.

**#11 and #12 hold test logs.** Before pushing them, re-run the phone tests on the current build.
Log those runs with the date they actually happen.

`settings.gradle.kts` grows one line at a time. After #10 it must match `preLeyline`'s copy exactly.

### How each person pushes

```
# once: accept the invite to the submission repo, then
git clone https://github.com/arnavraj-7/leyline.git
git clone https://github.com/arnavraj-7/preLeyline.git      # read-only source

# for your step
cd leyline
git pull
# copy exactly the files in your row from ../preLeyline (same paths)
git add <those paths>
git status            # only your files should be staged
git commit -m "<message from the table>"
git push
```

Setup to build locally: Android Studio (bundled JDK 21) or JDK 17+ and the Android SDK. Create
`local.properties` with `sdk.dir=<path to your Android SDK>` (it is git-ignored).

---

## Arnav — what to know (the core)

**Files:** everything in `mesh-core`, `mesh-ble`, plus `app/…/mesh/MeshController.kt`,
`MeshService.kt`, `KeystoreSigner.kt`, `BoxKeys.kt`, `PacketTrace.kt`, `app/…/AppContainer.kt`, the README.

- **Wire format.** The packet layout byte by byte (README "Binary packet specification"). Link frames
  of at most 180 bytes. Why we use our own binary format and not JSON or Protobuf.
- **Flood routing.** `SeenCache` returns NEW, BETTER (same id, more TTL left) or DUPLICATE. Loop
  drop via the path list, TTL decrement, never sending back to the sender. Know the README proofs:
  each node sends each message at most once, and no message goes further than its TTL.
- **Store-and-forward.** The outbox tracks which neighbours ("holders") already have each packet, so
  a new neighbour only gets what it lacks.
- **Priority.** Each neighbour has its own `ForwardQueue`, and the SOS lane always drains first.
- **Receipts.** They travel back along the reversed path.
- **Signing.** ECDSA P-256 with the private key in the Android Keystore (secure hardware).
  - NodeId = first 4 bytes of SHA-256(public key), so a node id can't be claimed without the key.
  - Key pins are trust-on-first-use (TOFU).
  - Signatures are checked before routing, so forged packets never spread.
- **Encryption.**
  - The key is ECDH P-256 → HKDF-SHA256 → AES-256-GCM. The "box key" is a software key, wrapped by
    a Keystore AES key. The reason: Keystore ECDH needs Android 12, and the Vivo is Android 9.
  - The additional authenticated data (AAD) binds the sender and the recipient.
  - There is no forward secrecy. Know why, and what it would take.
- **BLE.**
  - Each phone is both a GATT server and a GATT client.
  - The Nordic library queues GATT operations (one in flight per connection).
  - MTU is negotiated; chunk size = MTU − 3, capped at about 180 bytes.
  - At most 6 links. Reconnect backoff runs from 1 s to 30 s.
  - The node id sits in the scan response, because BLE addresses rotate.
- **Android.**
  - A foreground service of type `connectedDevice` keeps relaying when the screen is locked.
  - `AppContainer` is manual dependency injection instead of Hilt.

## Abhay — what to study

**Files:** all of `mesh-sim/`; `app/…/data/` (`Entities`, `Daos`, `LeylineDatabase`,
`MessageRepository`, `RoomOutbox`, `RoomKeyPins`, `Settings`); `app/…/mesh/LocationProvider.kt`,
`BatteryMonitor.kt`, `Topology.kt`, `Notifier.kt`, `Alarm.kt`, `SosTileService.kt`;
`docs/field-tests.md`.

**Concepts to master:**
- **Simulator.**
  - Why it exists: the simulator runs the *real* `mesh-core` code on virtual nodes. Virtual links can
    add delay, loss and churn. Mobility models: walkers and a "data mule" carrying messages between
    groups.
  - `NaiveFloodNode` is the baseline: rebroadcast everything, which is what our router beats.
  - Know the results table in the README:
    - storm: our router used 50.5% of the naive writes
    - SOS priority: 51 ms vs 1,429 ms naive
    - store-and-forward with a mule: 100% vs 0% delivered
    - walkers: 100% vs 6.7%
    - churn: 100% vs 86%
    - 30% packet loss: 100% vs 15%
  - `MeshPropertiesTest` checks properties (no duplicates delivered, TTL respected) over many random
    networks, not a few hand-picked cases.
- **Room.** Entities, DAOs, why the outbox is in SQLite (a packet survives the app being killed or a
  reboot), and why the message id is the primary key (inserts are idempotent). `RoomOutbox`
  implements `mesh-core`'s `Outbox` interface, which is how pure Kotlin stays free of Android. The
  database migration is destructive (prototype); know what a real migration would do.
- **TOFU key pins.** `RoomKeyPins` remembers the first public key seen for each node id. A later
  different key is an impostor.
- **Device services.**
  - GPS works with no network, so an SOS can carry its location in airplane mode.
    `LocationProvider` uses the platform `LocationManager`, not Play Services, which some phones
    lack.
  - The battery level comes from a sticky broadcast. With the battery-saver setting on, below 15% and
    not charging, the phone stops relaying for others.
  - `Topology` builds the mesh graph from the relay path inside every packet.
  - Notification channels: SOS alerts get high priority.
  - The ring alarm uses the alarm stream at full volume and restores the volume afterwards, with
    vibration and the torch. A phone only obeys a ring request for its own live SOS.
  - The Quick Settings tile sends an SOS in one tap.
- **Field tests.** What each 3-phone test proved and how it was run.

## Ayush — what to study

**Files:** `app/…/ui/` (`Theme`, `Components`, `Format`, `ViewModels`, `MessagesScreen`,
`MeshScreen`, `RescueScreen`, `SettingsScreen`) and `app/…/MainActivity.kt`.

**Concepts to master:**
- **Jetpack Compose basics.** Composable functions, state and recomposition, `remember`,
  `collectAsState`, `LaunchedEffect`, Material 3 components, and our theme colours.
- **MVVM with unidirectional data flow.** Each `ViewModel` exposes one `StateFlow<UiState>` (built
  with `combine`), and the screen only renders it and sends events back. Know why the ViewModel
  survives rotation and why screens never touch Room or Bluetooth directly.
- **`MainActivity`.**
  - It has 4 tabs, and the Rescue tab has a badge counting open SOS.
  - An onboarding screen explains the permissions before requesting them: Bluetooth on Android 12+,
    location (GPS for SOS, and needed for scanning on Android 11 and below), notifications on 13+.
  - It starts the foreground service.
- **MessagesScreen.**
  - Chat, and private messages with the 🔒 Encrypted / Not encrypted pill.
  - Hold-to-send SOS, with the optional details chips (kind, people, injured).
  - SOS cards with distance and direction.
  - The route sheet: the chain of relays, and receipt round-trip times. Only times measured on one
    phone's own clock are shown, because phone clocks disagree.
- **MeshScreen.**
  - The radar map is drawn on a `Canvas` that redraws every frame.
  - Animated route trails show each packet's path.
  - The live packet log shows every routing decision: received, relayed, duplicate, loop, blocked.
  - The stats card shows the mesh health numbers.
- **RescueScreen.**
  - Live SOS list sorted by state (found last), then urgency, then distance.
  - "I'm coming" → "Found them".
  - The finder: Bluetooth signal strength (RSSI), smoothed with a moving average, a trend arrow
    (warmer/colder), proximity bands, and "Ring their phone".
- **SettingsScreen.**
  - Display name and the relay on/off switch.
  - Topology mode: block chosen phones to force multi-hop in a small room. It is always shown in the
    UI.
  - The security test: fake another phone's SOS to show it gets blocked.

## Viva questions each of us should be able to answer

These are about the whole app, not one person's part:

- **Range and hops.** Why can't phones A and C talk directly, and how does B carry the message? What
  stops a message bouncing between phones forever?
- **Store-and-forward.** What happens to a message if nobody is in range?
- **Identity and forgery.** Why not use the Bluetooth MAC address as identity? How do we stop someone
  faking an SOS?
- **Mesh size.** What happens with 20 phones in one room (broadcast storm)? What did the simulator
  show?
- **Disaster conditions.** Why does it work in airplane mode? Why does GPS work with no internet?
- **AI and libraries.** What did AI write, what did we decide, and how did we test it? (Answer
  honestly. The README's tool disclosure is the reference.)
