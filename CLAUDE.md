# Leyline — guide for Claude Code sessions

Offline BLE mesh SOS app (Code Sangam 2026 DroidRush, PS 07). Kotlin only.

## Module ownership
- Arnav: `mesh-core`, `mesh-ble`, and the app's mesh layer (`MeshController`, `MeshService`,
  `KeystoreSigner`, `BoxKeys`, `PacketTrace`, `AppContainer`). Do not change these from another
  member's session; propose interface changes instead.
- Abhay: `mesh-sim`, `app/.../data/`, and the device services `LocationProvider`, `BatteryMonitor`,
  `Topology`, `Notifier`, `Alarm` and `SosTileService`.
- Ayush: `app/.../ui/` and `MainActivity`.
Each member commits only their own work from their own machine.

## Rules
- `mesh-core` must never import `android.*`. It is tested on the JVM.
- Node identity is `NodeId`, never a BLE address (addresses rotate).
- Chunk size derives from the negotiated MTU minus 3; default chunk ~180 bytes.
- One GATT operation in flight per connection (Nordic library queues them).
- Every new library goes into the README tool-disclosure table in the same commit.
- Explain the why of each change to the person you're working with — every member defends their
  code in a live viva.

## Commands
- `./gradlew :mesh-core:test` — router tests
- `./gradlew :mesh-sim:run` — simulator
- `./gradlew :app:assembleDebug` — build the APK
