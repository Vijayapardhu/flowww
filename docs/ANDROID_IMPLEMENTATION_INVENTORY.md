# Flowww Android Implementation Inventory (Phase 0 audit)

**Branch:** `feat/android-complete-companion`  
**Baseline inspected:** `main@4624a6b` (UI/logo pass committed; build artifacts locally modified then reverted)  
**Date:** 2026-11-14 (repo state)  
**Scope:** `apps/android/`, `crates/`, `apps/desktop/`, `docs/ANDROID_COMPANION_ARCHITECTURE.md`, `docs/MOTION_TRANSFER_ARCHITECTURE.md`

## Feature inventory

| Feature | Existing implementation | Actual runtime status | Required work |
|---|---|---|---|
| Device discovery | `ConnectionService`/`ConnectionServiceImpl` stubs (`discoverPeers()` returns empty), `NearbyDevicesScreen` static empty UI, no ViewModel/repository/event stream, no Rust/FFI/mDNS integration in Android | **Unverified / stubbed (not functional)** | Wire to Rust node discovery events via FFI/JNI bridge, reactive state, network-change handling, dedupe, real peer/lost updates, permissions/connectivity UX |
| Device pairing | `PairingService` + `PairingServiceImpl`/`PairingUriParser` (minimal JSON parsing, fake async success), `PairingScreen` URI text field only, no QR scan/camera flow, no persistence/identity verification in Android path | **Unverified / stubbed (not secure/complete)** | Reuse Rust pairing protocol end-to-end (URI parse/validate/expiry/sig/TLS pinning/transcripts), real success/failure/progress, persist trusted devices, reconnect/forget/rename support as protocol allows, QR scan as permissioned optional feature |
| Trusted devices | `getTrustedDevices()` stub returns empty, `TrustedDevicesScreen` static empty state, no trust store UI actions bound to real data | **Unverified / stubbed** | Load from Rust trust store, list with status/last seen/actions, forget/rename, persistence across restart, connect actions |
| Content staging | `StagedStore` (in-memory + Java serialization to disk), `ShareReceiverActivity` stages text/URIs, `ContentSanitizer`, `StagedContentScreen` static UI; `StagedStoreProvider.init()` never called, `StagedContent`/models not `Serializable` for store format, mixed cache dirs possible | **Partially implemented but runtime-broken/unverified** | Fix initialization/persistence/serialization & storage location, real staged list/preview/remove/multi-select/expiry cleanup, safe URI streaming, no auto-transfer, UI states, Room or robust store after audit |
| File transfer | `ConnectionServiceImpl.initiateSend()` stub/fake transfer id, `ActiveTransfersScreen` static, no real chunked/resumable/verified transfer pipeline, `TransferForegroundService` basic notification | **Unverified / stubbed** | Rust-authoritative send with streaming/chunks/SHA-256/resume/cancel/retry/timeout/progress events, foreground service compliance, no false success, real UI/state |
| Incoming transfer | No incoming-offer UI flow, no accept/reject path, no receive-to-controlled-storage implementation in Android UI/services observed | **Not implemented** | Receive offers, approval UI (sender/file/size/type), controlled storage save/open, resume/integrity, notifications |
| Gesture recognition | Sensor/gesture modules present (`SensorManagerWrapper`, `MotionFilter`, `SensorCalibration`, `GestureClassifier`, `GestureEngine`, `GestureEvent`, diagnostics VM/screens/activities), `GestureController` routes to connection service with preconditions; real hardware accuracy not validated | **Implemented in code, unverified on device** | Complete user-visible session flow, calibration persistence/UX, confidence/cooldown/neutral safety, diagnostics polish, integrate with real handoff preconditions and Rust transfer ops, lifecycle/sensor stop guarantees |
| Gesture handoff | `GestureController` can call `ConnectionService.initiateSend/cancel` (mock/stub services in session activity), no real RECEIVE target/content resolution path tied to Rust | **Stubbed/mock** | Route semantic events through controller to real staged/target/transfer operations, confirmations, no accidental transfers, no direct networking in engine |
| Rust FFI | No UniFFI `.udl`, no JNI/native libs packaged, no `externalNativeBuild`/NDK cargo integration, no Android binding crate; Rust core/workspace has `flowww-node` API | **Not implemented** | Define minimal Android boundary (UniFFI preferred), build Rust libs for supported ABIs, package `.so`, generate bindings, bridge events to Kotlin Flow/StateFlow, honest missing-API work in Rust if needed |
| Android Sharesheet | `ShareReceiverActivity` handles SEND/SEND_MULTIPLE, copies content URIs to temp files, stages text/URLs; silent finish, no user confirmation/navigation to staged item, metadata/size handling partial, store init/persistence issues | **Partially implemented, runtime-broken/unverified** | Robust ACTION_SEND/_MULTIPLE, MIME/URI grants/validation, streaming/bounded memory, confirmation + view/remove/select destination, explicit authorization before transfer |
| Persistent storage | `StagedStore` custom file serialization (cache dir), trust/transfer history not backed by Room/real stores in Android layer, settings toggles not persisted | **Unverified / inadequate** | Introduce appropriate persistent storage (Room or verified alternative) for staged metadata/history/settings/trusted-device cache as needed, safe migrations/cleanup, no absolute paths in UI models |

## Key files inspected (non-exhaustive)

- **Android app:** `AndroidManifest.xml`, `build.gradle.kts`, `app/src/main/java/com/flowww/android/{FlowwwApplication.kt,FlowwwError.kt,di/StagedStoreProvider.kt,content/ContentModel.kt,storage/StagedStore.kt,connection/{ConnectionService.kt,ConnectionModel.kt,TransferForegroundService.kt},pairing/Pairing.kt,sharesheet/ShareReceiverActivity.kt,security/ContentSanitizer.kt,ui/{MainActivity.kt,components/FlowwwComponents.kt,navigation/FlowwwBottomNavigation.kt,theme/*,screens/*},sensor/*}`, `app/src/test/java/.../ContentSanitizerTest.kt`
- **Docs:** `apps/android/NEXT_STEPS.md`, `docs/ANDROID_COMPANION_ARCHITECTURE.md`, `docs/MOTION_TRANSFER_ARCHITECTURE.md`
- **Rust/workspace:** `Cargo.toml`, `crates/flowww-node/{Cargo.toml,src/lib.rs,src/node.rs}`, `crates/flowww-protocol/src/message.rs`, other core crates present (`flowww-network`, `flowww-discovery`, `flowww-transfer`, `flowww-security`, `flowww-storage`, etc.)
- **Desktop/agent context:** `apps/desktop/src-tauri/src/agent/native_messaging.rs` (protocol/IPC hints)

## Critical blockers / gaps (truthful)

1. **No Android Rust FFI/native bridge:** discovery, pairing, secure connection, and transfers cannot reuse Rust core on device yet (stub services only).
2. **Staging runtime issues:** provider/init + non-serializable models for current store + cache-dir/temp-file lifecycle risks; Sharesheet has no user-facing confirmation/flow.
3. **No real transfer lifecycle:** no incoming approval, no chunked/resumable/verified send/receive, no progress events from engine.
4. **Architecture gaps:** no Navigation Compose/deep-link-ready graph, minimal ViewModels/repositories, settings not persisted, limited automated tests.
5. **Environment/tooling:** Android NDK installed (`$ANDROID_HOME/ndk/*`) but `cargo`/`cargo-ndk` not on PATH in this shell; physical-device acceptance requires real Windows agent + network setup beyond this turn.

## Milestone plan (execution order)

- **A (UI foundation):** theme tokens/resources, icons, nav shell, reusable components/states, responsive screen polish.
- **B (Android features):** fix staging/share flow, repositories/ViewModels, persisted settings/history scaffolding, permission-aware UX, honest discovery/pairing wiring points.
- **C (Native integration):** Rust Android build + UniFFI/JNI boundary + event bridge (smallest necessary Rust additions if required).
- **D (Real transfer):** bidirectional chunked/resumable/verified transfers with approval/progress/recovery.
- **E (Motion & release):** gesture session integration, diagnostics, tests, physical-device acceptance, security/release hardening.

**Status labels used below/in commits:** Implemented / Tested locally / Tested on emulator / Tested on physical device / Blocked. No feature is marked production-ready based on compile alone.
