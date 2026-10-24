# Code Sangam 2026 — DroidRush (planning folder)

This folder (code-sangham-26/droidrush) holds **DroidRush planning only** (dossier PDF, design notes, `abstract-draft.md`). Project code
lives in `C:\Projects\leyline` (app name **Leyline**), scaffolded 2026-10-18 after the user said the
coordinators allow coding before the dev period. Commits use real timestamps — never backdate or
commit on a teammate's behalf. GitHub repo is created when the dev period starts.

## Team & abstract
Team **Simpletons**, CodeSangam team ID **15**: Ayush Jadaun 20244048 (registered leader),
Arnav Raj 20244036 (the user), Abhay Agarwal 20245003.
(branch, GitHub, Discord): Ayush ECE, ayush-jadaun, ayush_jadaun; Arnav ECE, arnavraj-7, arnav050148;
Abhay Electrical Engineering, Abhay030405, abhayagarwal3751.
Abstract: `Simpletons_3rd_Droidrush_2026.docx/.pdf` (template format TeamName_Year_Droidrush_2026). Written
entirely in future tense ("will"): it is a proposal, so keep it that way on any rebuild.
Rebuild: `python abstract-src/build_abstract.py <unpacked template dir> <out.docx> abstract-src/classdiagram.png
abstract-src/flowdiagram.png`, then export PDF via Word (`ExportAsFixedFormat`, format 17). Missing
details are `{{...}}` placeholders in the script, rendered with yellow highlight.

## Scope (user wants everything, 2026-10-18)
All pass + victory features, plus our additions: GPS location in SOS (works offline), delivery
receipts on the reverse path, live mesh map, battery-aware relaying, Quick Settings SOS tile, mesh
health dashboard, simulator, stress tests. Build order protects the demo: pass tier end-to-end on
real phones first, then victory, then additions. A feature that isn't solid by the freeze gets cut,
not demoed half-working.

## Event rules that shape decisions
- MNNIT Computer Club. Max 3 per team, same academic year. Live viva per member, cumulative scoring.
- Repo created only after dev period begins; no commits after it ends; timestamps audited.
- Zero plagiarism. AI/tools allowed but every library and AI tool must be disclosed in README.
- Dev period: 7 days. Starts ~3 days after 2026-10-18 (confirm exact date).
- Abstract must follow the official template (user will send it).

## Chosen PS: 07 — The Blackout Leyline (Hard)
Delay-tolerant BLE mesh: A ↔ C talk through B with store-and-forward flooding, airplane mode + BT on.
Native Android, Kotlin only. PS wants a "shared portable routing core with native BLE bindings".
- **Pass:** BLE advertise + scan on a shared service UUID, GATT connect, ~180B chunked writes;
  flood router with message-ID dedup, TTL 3–5, SQLite outbox, store-forward on reconnect;
  1-hop and 2-hop SOS (<280 chars) showing sender, timestamp, hop telemetry; works in airplane mode.
- **Victory:** background relay with priority queue (SOS first); chunked long-message reassembly,
  reconnect loops, SOS beacon mode, basic crypto signing; Wi-Fi Direct escalation for big payloads;
  4+ node storm stress test.
- **Deliverables:** modular repo; README with binary packet spec, TTL routing proofs, 3 failure modes;
  tool disclosure; multi-device physical test log.

## Hardware (verified with nRF Connect, 2026-10-18)
| Phone | GATT server | GATT client | MTU |
|---|---|---|---|
| Motorola Signature (new, recent Android) | ✅ | ✅ | 517 (Android 14+ forces 517) |
| 3–4 yr old | ✅ | ✅ | not measured |
| 6–7 yr old (Android ≤13) | ✅ | ✅ | 247 |
Plus 3–4 friends' Androids in the hostel for the 4+ node test. iPhones are out of scope.
Observed errors: 8 (GATT_CONN_TIMEOUT), 22 (terminated by local host), 35/0x3B (conn-param update, harmless).

## Design rules already established
- Node ID (random, persisted) inside adverts and every packet — never the BLE address (it rotates).
- Chunk size from negotiated MTU − 3; keep chunks ~180B so every phone works.
- Location must be ON for scanning on Android ≤11.
- Topology mode: a node can ignore chosen node IDs to force multi-hop in small rooms. Disclose it.
- Retry with backoff on GATT errors 8/22/133. One GATT operation in flight per connection.
- Cancel pairing prompts — mesh nodes never bond.

## Tech stack (decided 2026-10-18 — user asked me to choose)
- Kotlin 2.x, Gradle Kotlin DSL + version catalog, minSdk 26, target latest.
- Modules: `:mesh-core` (pure Kotlin/JVM — packet codec, router, dedup, TTL, priority queue,
  chunker/reassembler, outbox interface), `:mesh-ble` (Android — advertise/scan with platform APIs,
  GATT client/server via Nordic library, connection pool), `:app` (Compose UI, Room, foreground
  service), `:mesh-sim` (JVM simulator + stress tests on top of mesh-core).
- UI: Jetpack Compose + Material 3, MVVM (ViewModel + StateFlow, unidirectional data flow).
- Async: coroutines + Flow. Storage: Room (KSP).
- BLE: Nordic library for GATT ops (user challenged raw APIs — reliability beats reinventing the
  queue). Verify on day 1 which Nordic library (Android-BLE-Library + ble-ktx vs Kotlin rewrite) is stable.
- Packets: custom binary format (the PS deliverable is a binary spec).
- DI: manual `AppContainer` (no Hilt — learning load; explain DI concept for interviews).
- Tests: JUnit + kotlinx-coroutines-test on mesh-core; simulator for multi-node scenarios.
- Deferred: signing algorithm, Wi-Fi Direct details (victory tier, day 5+).

## Team and ownership (each person really builds their part)
- **User (lead, ~55–60%)** — Kotlin. BLE transport + routing core + foreground service.
  Writes the pure-Kotlin router himself with review (interview defence piece).
- **Teammate (React Native / Expo + backend)** — Compose UI, Room persistence, settings, topology UI.
- **Teammate (ML)** — mesh simulator + stress tests, metrics, compatibility matrix, failure-mode docs.
Teammates work through handoff briefs in their own Claude Code sessions. No commits on someone
else's behalf.

## How to work with the user here
- Teach while building: explain problem → options → choice → why, before writing a module.
  User explains it back. Goal: defend every decision in the viva and in job interviews.
- Connect each piece to fundamentals (networking, OS, DBMS, distributed systems).
- Blunt feedback; user is a 3rd-year B.Tech, new to Kotlin, knows React Native.
