# Leyline

Offline SOS messaging over a Bluetooth Low Energy mesh. When towers and Wi-Fi are down, phones relay
messages hop by hop (A → B → C) with store-and-forward, so two people out of each other's range can
still reach each other, even when no chain of phones connects them at the same moment. Works in
airplane mode with Bluetooth on. Every packet is signed with a key held in the phone's secure hardware,
and private messages are end-to-end encrypted.

Beyond delivering an SOS, it helps close the rescue: an SOS carries what's wrong, how many people and
whether anyone is hurt; rescuers see every live SOS sorted by urgency and distance, answer "I'm coming"
so the person knows help is on the way (and other rescuers don't all run to the same place), and in
the last 30 metres follow the victim's Bluetooth signal and make their phone ring.

Built for Code Sangam 2026 · DroidRush · PS 07 "The Blackout Leyline".

- [Modules](#modules) · [Build](#build) · [How it works](#how-it-works)
- [Binary packet specification](#binary-packet-specification)
- [Routing and TTL proofs](#routing-and-ttl-proofs)
- [Security model](#security-model)
- [Failure modes](#failure-modes)
- [Test log](#test-log) · [Simulator results](#simulator-results)
- [Known limits](#known-limits) · [Tool disclosure](#tool-disclosure)

## Modules

| Module | What it does | Android? |
|---|---|---|
| `mesh-core` | Packet codec, flood router, dedup, TTL, priority queue, chunking, signatures, store-and-forward node | No: pure Kotlin |
| `mesh-ble` | Advertise, scan, GATT client and server, connection pool, link framing | Yes |
| `mesh-sim` | Virtual-node simulator and stress tests running the real `mesh-core` | No |
| `app` | Compose UI, Room storage, foreground relay service, Keystore signer | Yes |

`mesh-core` has no Android dependencies, so the routing logic is unit-tested on a laptop and the
simulator runs exactly the code the phones run. `mesh-ble` only moves whole packets between direct
neighbours (the `Transport` interface); everything above it is identical on BLE and in the simulator.

## Build

Requires Android Studio (bundled JDK 21) or JDK 17+ with the Android SDK. Phones: Android 8.0+ (API 26).

```
./gradlew :mesh-core:test        # router, codec, signature, encryption and escalation tests (92)
./gradlew :mesh-sim:test         # simulator tests (15)
./gradlew :mesh-sim:run          # simulator scenarios -> mesh-sim/build/reports/sim-report.md
./gradlew :app:assembleDebug     # APK
```

## How it works

```
 app      Compose UI ── ViewModels ── MeshController ── Room (messages, outbox, key pins)
                                          │
 core     MeshNode: Router (dedup, TTL) · Outbox (store-and-forward) · ForwardQueue (SOS first)
                    PacketCodec · PacketSignatures · per-neighbour sender coroutines
                                          │  Transport: send(neighbour, bytes) / events
 core     EscalatingTransport: Bluetooth by default, Wi-Fi Direct while a big backlog waits
                                          │
 ble      BleTransport: Scanner · Advertiser · GATT server · PeerConnection (Nordic) · LinkFrames
          WifiDirectLink: autonomous group · TCP socket
```

1. **Discovery.** Every phone advertises the Leyline service UUID and scans for it. The scan response
   carries the phone's 4-byte node id and two flags (carrying an SOS, relaying), because the
   Bluetooth address rotates and can't identify a phone.
2. **Links.** Of two phones that see each other, the one with the lower node id connects. Both send a
   `Hello` with their node id and display name. Up to 6 links per phone.
3. **Sending.** A message is signed, written to the outbox first (it survives a crash), then queued
   for every linked neighbour. SOS packets jump the queue.
4. **Relaying.** A receiver verifies the signature, asks the router whether it is new, delivers it,
   and if TTL allows, stores and forwards it to every neighbour that doesn't already have it.
5. **Store-and-forward.** When a new neighbour connects, everything still alive in the outbox is
   offered to it. A message crosses a gap in the network by being carried until a link appears.
6. **Receipts.** The receiver of an SOS or a private message sends a signed receipt back along the
   reversed path, so the sender sees "Received by Abhay (2 hops)".
7. **Rescue.** A rescuer's "I'm coming" / "Found them" is a broadcast message that names the SOS it
   answers, so the victim gets a "Help is coming" alert and the Rescue tab shows who covers which SOS.
8. **Finding.** Near the victim, the rescuer's finder smooths the victim's advert signal strength
   (RSSI, about once a second) into warmer / colder, and can send a signed ring request that makes the
   victim's phone sound an alarm and flash its torch for 30 s.
9. **Wi-Fi Direct escalation.** BLE moves a few KB/s. When more than 24 KB waits for one neighbour
   (a person arriving with hundreds of carried messages), the two phones agree over Bluetooth to
   switch: one makes a Wi-Fi Direct group, its name and passphrase go over the BLE link, the other
   joins with them (no pairing dialog) and opens a TCP socket. That neighbour's packets then go over
   Wi-Fi until the backlog has drained and the link is idle for 20 s; any Wi-Fi failure falls back to
   BLE. Joining needs Android 10, so an older phone takes the host role. It only runs with Wi-Fi
   switched on (airplane mode allows that) and the "Nearby devices" permission.

## Binary packet specification

All integers are big-endian. Four layers, outermost first.

### 1. Advertisement (scan response service data)

| Bytes | Field |
|---|---|
| 4 | node id |
| 1 | flags: bit 0 = carrying a live SOS, bit 1 = relaying |

The advert itself holds only the 128-bit service UUID `6c65796c-696e-4510-9a3e-5d0c0000a001`
(21 of 31 bytes): filtering on a service UUID works on every chip, while filtering on service data
silently matched nothing on our Android 9 test phone.

### 2. GATT profile

| Characteristic | UUID | Use |
|---|---|---|
| RX | `…a002` | the connecting phone writes frames here |
| TX | `…a003` | the other phone sends frames back as notifications |

One connection carries both directions. MTU 517 is requested (Android 14+ always negotiates 517);
frames are capped at **180 bytes** whatever the MTU, as the problem statement asks, and fall back to
MTU − 3 on links that negotiate less (minimum 20).

### 3. Link frames (one GATT write each)

```
Hello: type=1 u8 | version u8 | node id i32 | [name length u8 | name UTF-8]      6 + (1 + n) bytes
Data:  type=2 u8 | transfer id u16 | index u8 | count u8 | chunk                  5 + n bytes
```

A packet is split into at most 255 `Data` frames of `frame size − 5` bytes. The receiver's
`Reassembler` tolerates repeated frames, forgets a partial transfer after 10 s, keeps at most 4
transfers in progress per link, and refuses more bytes than the largest valid packet, so a broken or
hostile peer can't make it buffer without limit.

### 4. Packet (version 2)

```
offset  size  field
 0      1     version (2)
 1      1     type (1 = MESSAGE, 2 = ACK)
 2      1     flags (bit 0 = SOS, bit 1 = signed)
 3      1     ttl: hops it may still travel, 0..7
 4      8     message id (random 64-bit)
12      4     origin node id
16      4     destination node id (FFFFFFFF = broadcast)
20      8     created at, epoch milliseconds, origin's clock
28      1     path length n (0..7)
29      4n    relay node ids, in the order crossed
29+4n   2     payload length L (0..1200)
31+4n   L     payload
if signed:
        33    origin public key, compressed P-256 (prefix 02/03 + x)
        64    ECDSA-SHA256 signature, raw r‖s
```

Header 31 bytes + 4 per relay + payload + 97 for the signature. Every field is validated on decode:
unknown version or type, path longer than 7, a length that doesn't match the bytes present, or a
truncated buffer all raise `MalformedPacketException`, which is counted and dropped, never thrown
out of the radio callback.

**MESSAGE payload**

```
flags u8: bit 0 location, 1 sender name, 2 SOS details, 3 response, 4 ring, 5 box key, 6 sealed
[lat i32 | lon i32 | accuracy u16]        if bit 0; degrees × 10^7, metres
[name length u8 | name UTF-8, ≤ 32 B]     if bit 1
[kind u8 | people u8 | detail flags u8]   if bit 2; kind 0 help, 1 medical, 2 trapped, 3 fire,
                                          4 food/water; detail bit 0 = someone injured
[answered SOS id u64 | status u8]         if bit 3; status 1 = on the way, 2 = found
[SOS id u64]                              if bit 4; "ring so I can find you"
[sender's encryption public key, 33 B]    if bit 5
text UTF-8                                rest of the payload, if bit 6 clear; an SOS is ≤ 280 chars
sealed inner body                         rest of the payload, if bit 6 set (see Encryption)
```

Triage costs 3 bytes, so an SOS with details still fits one 180-byte frame. Chat carries the
sender's encryption key (33 B); an SOS doesn't, to stay in one frame. A ring request is honoured
only by the phone that sent that SOS, and only while it is live, so it can't set off other phones.

**ACK payload** (delivery receipt)

```
acked message id u64 | hops u8 | route length n u8 | n node ids i32 | [name length u8 | name UTF-8]
```

`route` is the original's path reversed, ending at its origin, so relays send the receipt back along
it instead of flooding. `hops` is how many links the original took to reach the receipt's sender.

**What the signature covers**

```
"leyline/packet/v2" | type u8 | sos u8 | id u64 | origin i32 | destination i32 | createdAt u64
| payload length u16 | payload | public key (33 B)
```

Everything the origin wrote, but not `ttl` or `path`: relays must change those.

**Worked example.** A default SOS ("SOS: I need help.", GPS, name "Phone-6488") has a 39-byte
payload. Leaving its origin it is 31 + 39 + 97 = 167 bytes; after one relay, 171. Both fit in a single
180-byte frame (175 bytes of chunk), so a 2-hop SOS is one GATT write per hop. A maximum 280-character
SOS with location and name is 327 bytes: 2 frames at 180 B, or 22 frames on a 20-byte link.

## Routing and TTL proofs

The router (`Router.kt`) is managed flooding: every node relays a new message to every neighbour
that doesn't have it, bounded by TTL and deduplicated by message id. The claims below are tested in
`mesh-core` and measured in `mesh-sim`.

Notation: a message is sent by origin O with initial TTL t ≤ 7. A node receiving a copy with ttl k
delivers it and, if k > 1 and relaying is on, forwards a copy with ttl k − 1 and itself appended to
the path.

**Invariant 1: ttl + path length = t for every honest copy.** The origin sends ttl t with an empty
path. Each forward subtracts 1 from ttl and appends exactly one id. ∎ So a receiver is
`path.size + 1` hops from the origin (the hop count shown in the app), and a copy never crosses more
than t links.

**Theorem 1: no loops.** A node drops any copy whose origin is itself (`OWN_PACKET`) or whose path
already contains itself (`LOOP`). Therefore every accepted copy's route, origin + path + receiver, is
a simple path: no node appears twice. ∎ This holds even after the seen cache forgets an id, because
it checks the packet itself.

**Theorem 2: every message causes finitely many transmissions.** A node forwards message m only when
its copy is NEW or BETTER (strictly more TTL than any earlier copy of m; `SeenCache.offer`). TTL is
an integer in 1..t, so each node forwards m at most t times, and each forward goes at most once to
each neighbour (a neighbour that took the packet is recorded as a holder and skipped). With N nodes
of degree at most Δ, m costs at most N · t · Δ packet writes. The outbox expires m after its lifetime
(30 min, SOS 6 h), which ends retries too. ∎ In practice it is far fewer: a full mesh of 8 wrote
50.5% of naive flooding's packets (storm scenario below).

**Theorem 3: TTL t reaches exactly the nodes within t hops (static, loss-free network).** Claim: a
node at distance d ≤ t from O eventually holds a copy with ttl ≥ t − d + 1. For d = 1 the origin
sends ttl t to every neighbour. Assume it for d − 1 < t: that node holds ttl ≥ t − d + 2 ≥ 2, so it
forwards ttl ≥ t − d + 1 ≥ 1 to each neighbour, unless that neighbour is a holder of a copy at least
as good (then the claim already holds for it). A node at distance d > t can't be reached, by
Invariant 1. ∎

The subtle step is "at least as good". Flooding races: the first copy to reach a node may have come
the long way with ttl 1 left, and plain dedup would then discard the short-way copy that could still
travel further, silently shrinking the reach below t hops. Leyline remembers the best TTL per id and
re-forwards a strictly better copy (`Seen.BETTER`, counted as "Upgrades"). The `random-geometric`
scenario checks the theorem on 30 nodes: 100% of nodes within TTL hops received every broadcast, for
TTL 4 and 7.

**Theorem 4: each message is shown at most once per phone.** The seen cache keeps ids for 7 h, longer
than any packet lives (6 h), and `MeshConfig` refuses a configuration where it doesn't. Ids still in
the outbox count as seen even if the cache's memory cap evicted them. After a restart, ids from the
message database are loaded back into the cache, and the database's primary key on message id is a
final guard. ∎ Every simulator run reports 0 repeat deliveries.

**Receipts don't flood.** A receipt is unicast along its route with TTL hops + 1 (one spare in case
the way back has moved). Each relay sends it to the linked node furthest along the route, skipping
ahead when the mesh allows, and only falls back to flooding when none of the route is in range.

**SOS goes first.** Each neighbour has its own queue with an SOS lane drained before the normal lane.
When a queue is full, a packet may only evict one of strictly lower priority, so chat can never
delay or drop an SOS; anything evicted is still in the outbox and is replayed on the next resync.

## Security model

**Identity.** On first launch the phone generates a P-256 key pair inside the Android Keystore
(hardware-backed where the phone has a TEE). The private key never leaves it, not even to our app;
the app can only ask the Keystore to sign. The node id is the first 4 bytes of SHA-256(public key),
so an id can't be claimed without its private key.

**Signing.** Every packet a phone originates (messages and receipts) carries its public key and an
ECDSA-SHA256 signature: 97 bytes. ECDSA P-256 was chosen over Ed25519 (64 + 32 B, slightly smaller)
because Android's Keystore only supports Ed25519 from Android 13, and our phones start at Android 9.

**Verification.** Before the router sees a packet, the receiver checks:

| Check | Fails as | What it stops |
|---|---|---|
| Is it signed? | `UNSIGNED` | old builds, scripts, anything unauthenticated |
| Does SHA-256(key) start with the origin id? | `WRONG_ORIGIN` | claiming to be another phone |
| Does the signature verify? | `BAD_SIGNATURE` | a relay editing the text, priority, destination, time |
| Is this the first key seen for that id? | `IMPOSTOR` | a brute-forced key whose hash collides with a known id |

A rejected packet is neither shown nor relayed, and never enters the seen cache, so a forgery can't
block the genuine message with the same id. The app's Mesh tab lists each blocked packet; the
Settings screen can send a forged SOS on purpose to demonstrate this.

**Why the key pin.** 32 bits of hash can be brute-forced offline in hours. The first key seen for each
id is stored (trust on first use), and since a reinstall makes a new key and so a new id, a second
key for a known id is always an attack.

**Limits.** Signing proves who wrote a message and that it wasn't changed. It doesn't stop:
- a relay changing TTL or path (bounded: TTL can't exceed 7, so it can't cause unbounded flooding);
- a relay dropping messages (other paths and store-and-forward route around it);
- an attacker who learns a message id before the real message spreads from sending their own packet
  under that id, so some phones discard the real one as a duplicate (it can't replace a copy a phone
  already holds: an upgrade must be byte-identical apart from TTL and path);
- spoofing at the link layer: `Hello` frames and adverts aren't signed, so a phone can pretend to be
  a neighbour, but it still can't forge messages;
- a phone lying about its own GPS position;
- relays reading broadcasts and SOS: those are meant for everyone and are signed, not encrypted.

### Encryption (private messages)

Relays carry a private message but can't read it. Each phone has a second P-256 key pair just for
this. Its public half rides in that phone's signed chat messages, so a relay can't swap in its own,
and the first key seen per node is kept. The private half is generated in software (Keystore key
agreement needs Android 12, our oldest phone runs 9) and stored encrypted under an AES-256 key that
lives in the Keystore.

```
shared = ECDH(our private key, their public key)
key    = HKDF-SHA256(salt = nonce, ikm = shared, info = "leyline/dm/v1")
sealed = nonce (12 B) | AES-256-GCM(key, nonce, inner MessageBody, aad = domain | origin | destination)
```

The fresh random nonce also salts the key, so every message has its own key. Binding origin and
destination into the AAD stops a sealed message being replayed as addressed to someone else.
Overhead: 12 + 16 bytes, plus the 33-byte key. A private message to someone who hasn't sent us a
message yet goes signed but readable, and the app labels it "not encrypted". Static keys mean no
forward secrecy: whoever later extracts a phone's key could read old messages they recorded.

## Failure modes

**1. A link drops or a write fails mid-transfer.** BLE writes fail often (GATT 133, 8 timeout,
22 terminated). A failed write is retried twice at once (250 ms, 500 ms); the packet stays in the
outbox and is re-offered every 15 s while that neighbour is linked, and to any neighbour that links
later. A half-received packet is discarded after 10 s by the reassembler. A failed connection is
retried with exponential backoff and jitter: 1 s, 2 s, 4 s, up to 30 s. *Evidence:* the `lossy` scenario delivers 100% at 30% frame loss where naive
flooding delivers 15%; field test 5.

**2. No path exists (network partition).** Plain flooding loses the message. Leyline stores it and
hands it to every neighbour that appears before it expires (30 min, SOS 6 h), so a person walking
between groups carries messages across. An SOS also turns on beacon mode: the phone advertises the
SOS flag and scans aggressively so passers-by pick it up fast. *Evidence:* `dtn-mule`, two clusters
1 km apart bridged only by one walker: 100% delivered (naive 0%); field test 5, where a phone that
was offline received the message about a second after it came back.

**3. Broadcast storm and congestion.** Many phones flooding at once can choke the radio exactly when
an SOS matters. Holder tracking skips neighbours known to have a packet, random jitter (0–150 ms,
SOS 0–20 ms) staggers relays, and the SOS lane keeps emergencies at the front. *Evidence:* `storm`,
8 phones with 160 messages: half of naive flooding's writes; `priority`, an SOS behind 900 queued
messages arrives in 51 ms, against 1.4 s for naive flooding.

**4. Forged or tampered messages.** A fake "all clear" or a fake SOS from someone's id is dropped at
the first honest phone (see [Security model](#security-model)). *Evidence:* field test 2, 5 signed-mesh
unit tests.

**5. Low battery.** Relaying for others costs power. Battery saver (on by default) stops relaying
below 15% unless charging; the phone still sends and receives its own messages.

**6. Clocks disagree.** With no network, phones can't sync time; in our field test two phones were
4.6 s apart. Messages are sorted by when they arrived, and the app only shows durations measured on
one phone's clock (a receipt's round trip), never "sent here, arrived there".

## Test log

Full records with device models and timings will be in `docs/field-tests.md`.

**2026-10-19, three phones in one room** (vivo 1723 Android 9, motorola signature Android 16,
OPPO CPH2293 Android 13), topology mode used to force A and C to talk only through B:

| # | Test | Result |
|---|---|---|
| 1 | SOS, all three linked | Pass: delivered with "signature verified", 2 receipts |
| 2 | Forged SOS claiming another phone's id | Pass: blocked on both receivers, never shown |
| 3 | Text over 2 hops | Pass: "2 hops · via motorolla" |
| 4 | SOS over 2 hops with receipts back | Pass: 431 ms round trip for the 2-hop receipt |
| 5 | Store-and-forward: receiver's app killed, back after 19 s | Pass: delivered ~1 s after it returned |
| 6 | Relay switched off on the middle phone | Pass: stopped at the middle phone |
| 7 | Middle and receiving phones locked | Pass: SOS notification "2 hops", 848 ms round trip |

**Still to run:** airplane mode with Bluetooth on (every phone), a 4+ phone chain, outdoor range per hop,
Wi-Fi Direct escalation between two phones.

## Simulator results

`mesh-sim` runs the real `mesh-core` over virtual links (180 B frames, 15 ± 10 ms per frame, one GATT
operation in flight per link) with a fixed seed, against a naive flood (dedup and TTL, but no
outbox, holders, priorities or retries). Full report: `./gradlew :mesh-sim:run`.

| Scenario | Leyline | Naive flood |
|---|---|---|
| `line3`: A–B–C SOS | delivered in 27 ms, both receipts back by 68 ms | delivered, no receipts |
| `storm`: 8-node full mesh, 160 broadcasts | 100%, 5 387 writes, p95 38 ms | 100%, 10 666 writes, p95 64 ms |
| `priority`: SOS behind 900 queued messages | SOS in 51 ms | SOS in 1 429 ms |
| `dtn-mule`: clusters 1 km apart, one walker | 100%, receipts back by 26.5 min | 0% across the gap |
| `dtn-walkers`: 16 people moving, no stable path | 100% of 75 | 6.7% |
| `random-geometric`: 30 nodes, TTL 7 | 100% of nodes within TTL, 407 writes | 100%, 787 writes |
| `churn`: links up ~75% of the time | 100% | 86% |
| `lossy`: 30% frame loss over 4 hops | 100% | 15% |
| `worst-mtu`: 280-char SOS over 20 B frames | delivered over 3 hops in 1 064 ms | delivered |

Every scenario reports 0 repeat deliveries, 0 crashes and 0 malformed packets.

## Known limits

- **Range per hop** is what Bluetooth LE gives: roughly 10–30 m indoors, 50–100 m in the open.
  Reach comes from hops (TTL 4 by default, 7 at most) and from people carrying messages.
- **Topology mode** (Settings) makes a phone ignore chosen neighbours so multi-hop can be shown in
  one room. It is a test tool and is disclosed as such.
- **Android only.** iPhones can't run the app; iOS limits background BLE advertising.
- **Aggressive battery managers** on some brands (vivo, OPPO, Xiaomi) may still stop the background
  service despite it running in the foreground; on those phones the user has to allow background
  activity for Leyline in the system settings.
- **Up to 6 simultaneous links** per phone, a limit of phone Bluetooth stacks.
- **Wi-Fi Direct** serves one neighbour at a time (a phone can be in one group), and two Android 9
  phones can't use it with each other. Below 24 KB BLE finishes before a group would be up.
- See [Security model](#security-model) for what signing does not cover.

## Tool disclosure

**Libraries**

| Library | Version | Licence | Used for |
|---|---|---|---|
| Kotlin | 2.4.21 | Apache 2.0 | Language |
| kotlinx.coroutines | 1.11.0 | Apache 2.0 | Concurrency, flows |
| Jetpack Compose (BOM) + Material 3 | 2026.09.00 | Apache 2.0 | UI |
| Compose Material Icons Core | 1.7.8 | Apache 2.0 | Icons |
| AndroidX Activity Compose | 1.13.0 | Apache 2.0 | Activity host |
| AndroidX Lifecycle (ViewModel, runtime Compose) | 2.11.0 | Apache 2.0 | MVVM |
| AndroidX Room (+ KSP) | 2.8.5 | Apache 2.0 | Messages, outbox, key pins |
| Nordic Android-BLE-Library (+ ktx) | 2.11.0 | BSD-3-Clause | GATT operation queue, retries, MTU |
| JUnit Jupiter | 6.1.3 | EPL 2.0 | Tests |
| kotlinx-coroutines-test | 1.11.0 | Apache 2.0 | Virtual-time tests |

Cryptography uses only the platform: `java.security` (ECDSA P-256, SHA-256) and the Android
Keystore. No other third-party code.

**Tools**

| Tool | Used for |
|---|---|
| Android Studio, Gradle (AGP 9.4.1) | Build |
| adb | Installing on test phones, screenshots, pulling test databases |
| nRF Connect (Nordic) | Checking each phone's GATT server and client support and MTU before building |
| Claude Code (Anthropic) | AI coding assistant: wrote most of the code, tests, simulator and this README from the team's requirements and design decisions. The team chose the problem, the scope and the trade-offs, ran every physical test, and reviewed the code. |
