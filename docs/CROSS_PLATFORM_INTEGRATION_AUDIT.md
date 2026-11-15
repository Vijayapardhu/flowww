# Cross-Platform Integration Audit — Android ↔ Rust ↔ Windows

**Scope:** Phase 0 of Milestone C/D/E (`feat/android-rust-integration`)
**Baseline:** `feat/android-standalone-complete` @ `a4ac00f` (standalone app),
Rust workspace @ `e4076db` lineage, desktop agent under `apps/desktop`.
**Date:** 2026-11-15

This audit records what exists, what is missing, and the plan. It is written
against the actual working tree, not against the docs (the docs are cited where
they agree with reality). Verified facts: `cargo check --workspace` is green on
the development host (1m28s, only warnings), Rust 1.100.0 with
`aarch64-linux-android` and `x86_64-linux-android` targets installed, NDK
26.3/27.0/27.1 present at `%ANDROID_HOME%\ndk`, `gcc C:\mingw64` available for
`rusqlite` bundled builds.

---

## 1. Existing Rust APIs (what the Android FFI wraps)

All of these are **portable, compile for Android targets** (no Windows-only
code): `flowww-core`, `-protocol`, `-security`, `-transfer`, `-storage`
(bundled SQLite; needs NDK `clang`), `-discovery` (mdns-sd), `-network`
(pairing + TLS), `-node`. `flowww-bridge` is the desktop's loopback HTTP/WS
host, not needed on Android.

### 1.1 Node lifecycle — `crates/flowww-node/src/node.rs`

| API | Signature | Line |
|---|---|---|
| `Node::start` | `pub async fn start(config: NodeConfig) -> NodeResult<Self>` | 405 |
| `Node::shutdown` | `pub fn shutdown(&self)` (idempotent teardown of spatial/controller tokens, discovery, pairing) | 514 |
| `Node::device` | `pub fn device(&self) -> &DeviceInfo` | 478 |
| `Node::device_id` / `listen_port` | `pub fn device_id(&self) -> &DeviceId` / `listen_port() -> u16` | 482/486 |
| `Node::subscribe` | `pub fn subscribe(&self) -> broadcast::Receiver<NodeEvent>` | 510 |
| `Node::snapshot` | `pub async fn snapshot(&self) -> NodeResult<NodeSnapshot>` | 3282 |
| `spatial_view` | `pub fn spatial_view(&self) -> SpatialView` | 3275 |

`Node` is `Clone` (Arc-backed). `NodeConfig` (`crates/flowww-node/src/config.rs`):
`NodeConfig::new(data_dir)` → `.with_download_dir(..)`, `.with_device_name(..)`,
`.with_discovery(bool)`, `.with_listen_addr(SocketAddr)`,
`.with_auto_accept(bool)`; `listen_addr` defaults to `0.0.0.0:0` (**LAN-reachable**, ephemeral
port). NOTE: `default_path()` uses `dirs::data_local_dir()` which is unreliable
on Android — the FFI layer must always pass an explicit `data_dir` (app-private
`filesDir`) and an explicit `download_dir`.

### 1.2 Discovery

| API | Signature | Line |
|---|---|---|
| `Node::discovered_peers` | `pub async fn discovered_peers(&self) -> Vec<PeerView>` | 668 |

`PeerView` (`event.rs:12`): `{ device_id, name, platform, address, port, paired,
fingerprint, last_seen_ms }`. `NodeEvent::PeerFound(PeerView)` / `PeerLost
{ device_id }` are broadcast to subscribers. Discovery transport: mDNS
`_flowww._tcp.local.` via `mdns-sd` (`crates/flowww-discovery`). Android needs
a `WifiManager.MulticastLock` and the multicast flags in the manifest — none
of that is in Rust.

### 1.3 Pairing (the QR path)

| API | File:line |
|---|---|
| `PairingView { uri, endpoint, expires_at }` | `event.rs:40` |
| `Node::start_pairing` → `NodeResult<PairingView>` | node.rs:2516 |
| `Node::cancel_pairing` → `bool` | node.rs:2649 |
| `Node::active_pairing` → `Option<PairingView>` | node.rs:2665 |
| `Node::pair_with_uri(uri: &str) -> NodeResult<DeviceInfo>` | node.rs:2678 |
| `PairingPayload::{to_uri, from_uri, validate}` | `flowww-security/src/pairing.rs:38/50/63` |
| URI format | `flowww://pair/v1/<base64url-no-pad(JSON(PairingPayload))>`; prefix `flowww://pair/v1/`, `flowww-security/pairing.rs:15,38` |

Pairing payload fields: `version`, `session_id` (UUID), `token` (32 B, one-time),
`host_device` (public `DeviceInfo`), `host_public_key` (base64 Ed25519),
`host_tls_fingerprint` (SHA-256 hex), `endpoint` (`host:port`), `nonce` (64 hex),
`expires_at` (unix ms, default TTL 2 min). Contains **no private material**.
Verification: joiner checks expiry, session, token (constant-time),
distinct device IDs, Ed25519 signature over
`SHA-256("flowww.pairing.v1\n" || canonical JSON transcript)`, and that the
joiner's TLS fingerprint equals the certificate on the connection
(`PairingSession::verify_join`, `pairing.rs:170`). The host saves the peer via
`Store::save_pair` (`flowww-storage`, SQLite `nonces` + `devices` tables).

QR rendering exists on the **Windows** side only: desktop
`PairingDialog` (`apps/desktop/src/App.tsx:146-153`) draws `QRCodeSVG
value={pairing.uri}`; the `flowww-bridge` also has `pairing_svg()`
(`crates/flowww-bridge/src/qr.rs:14`). **No QR scanner exists anywhere in the
repo** — Android needs the scan side.

### 1.4 Trust / paired devices

| API | Line |
|---|---|
| `Node::snapshot() -> NodeSnapshot` (carries `devices: Vec<Device>` — the trust store) | node.rs:3282 |
| `Node::paired_peer(&DeviceId)` | node.rs:2735 |
| `Node::revoke(&DeviceId)` | node.rs:2742 |
| `Node::set_auto_accept(bool)` | node.rs:2757 |

Trust records persist in SQLite `devices` table (see `LIST_DEVICES`/`INSERT_PAIR`
in `crates/flowww-storage/src/store.rs`). There is **no verify-trust-by-key
call beyond TLS pinning**; pinning is the trust check (see §3). No rename
command is exposed by Rust — rename must be app-level metadata (Android's
`TrustedDeviceStore` already does this honestly).

### 1.5 Transfers (both directions)

| API | Line |
|---|---|
| `Node::send_file(…)` / `send_file_to(…)` | node.rs:2215/2227 |
| `Node::send_text` / `send_text_to` | node.rs:2265/2271 |
| `Node::send_url` / `send_url_to` | node.rs:2289/2295 |
| `Node::pending_offers() -> Vec<PendingOfferView>` | node.rs:2764 |
| `Node::respond_to_offer(transfer_id, accept) -> bool` | node.rs:2774 |
| `TransferRecord` (whole transfer history row; `Serialize`) | `flowww-core` |
| `Store::list_transfers()` via snapshot `transfers` | node.rs:3282 |
| Spatial relay path (`SpatialControl`, `hold_file`, `send_spatial`) | node.rs:213/2789/3146 |

Chunking: 1 MiB chunks, per-chunk SHA-256, cumulative byte-count ACKs, aligned
resume offset (`resume_from`), whole-file SHA-256 before `.partial` → final
rename (`flowww-transfer/src/lib.rs:333/392/661`). Transfer IDs for files are
deterministic (`TransferId::for_content(source, destination, sha256, size)`),
which makes retry/resume idempotent. Text/URLs use random IDs and do not
resume. `PendingOfferView { transfer_id, source: DeviceInfo, content: Content,
route, action }` (`event.rs:48`).

### 1.6 Events

`Node::subscribe()` returns `broadcast::Receiver<NodeEvent>`. Variants
(`event.rs:82-95`, `SCREAMING_SNAKE_CASE`, JSON `{type, data}`):

`PeerFound(PeerView)` · `PeerLost{device_id}` · `PairingStarted(PairingView)` ·
`PairingCompleted(DeviceInfo)` · `PairingFailed{reason}` ·
`OfferReceived(PendingOfferView)` · `OfferClosed{transfer_id}` ·
`TransferUpdated(TransferRecord)` · `SpatialUpdated(SpatialView)` ·
`Error{message}`.

None of this is callable across FFI — a bridge is mandatory.

---

## 2. Missing APIs (nothing exists yet — this is the work)

| # | Missing | Owner phase |
|---|---|---|
| 1 | UniFFI crate exposing a typed Android surface (`crates/flowww-ffi`) | C |
| 2 | `.udl`/proc-macro scaffolding + `uniffi-bindgen` → Kotlin bindings | C |
| 3 | Android-safe secret store (`Identity` JSON is plain text, only chmod 0600 on Unix — `flowww-security/src/identity.rs:165`). Must be backed by Android Keystore for the identity private key | C/D |
| 4 | Event bridge: `broadcast::Receiver<NodeEvent>` → Kotlin `callbackFlow`/Flow | C/D |
| 5 | Android ABI `.so` builds + `jniLibs` packaging + reproducible build script | C |
| 6 | Android camera QR scanner (CameraX + ZXing core; **not** ML Kit dependency) + `CAMERA` runtime permission | D |
| 7 | `NodeEvent` → `TransferRepository.recordTransfer` mapping (Kotlin) | D |
| 8 | Offer-inbox screen wiring: `pending_offers()` + `respond_to_offer()` | D |
| 9 | Foreground service lifecycle for active transfers (`dataSync` type, notification permission) | D/E |
| 10 | `MulticastLock` for mDNS on Android | D |
| 11 | Scoped-storage receive path (incoming files must land in an app-controlled location, never "anywhere") | D2 |
| 12 | Cross-run verification harness (two in-process Rust nodes pairing + sending in `cargo test`) — proves the protocol, then physical device proof | D2 |

## 3. Android requirements

Existing Kotlin seams (from `docs/MILESTONE_C_PREREQUISITES.md` and codebase
review @ `a4ac00f`):

- **`ConnectionServiceImpl`** (`apps/android/.../connection/ConnectionService.kt`)
  — every method is a stub that throws `ProviderUnavailable` or returns false.
  FFI must replace the body, keeping the interface (`initiateSend(deviceId,
  contentId)`, `cancelTransfer`, `getTransferStatus`, `shutdown`).
- **`PairingServiceImpl`** (`pairing/Pairing.kt:158`) — parses/expiry-checks
  only; FFI replaces the body with `pair_with_uri`.
- **`DefaultDiscoveryRepository`** (`data/repository/DefaultDiscoveryRepository.kt`)
  — never emits peers; FFI replaces with node discovery + event stream.
- **`AppViewModelFactory` / `AppContainer` (`di/AppContainer.kt`)** — screens
  resolve repositories only through `container.*`, so a parallel
  `RustAppContainer` is viable without touching any screen.
- **`GestureController`** — applies gesture→action preconditions; not wired to
  any screen. Must gate `SEND/RECEIVE/CANCEL` on real state from the FFI repos.
- **Staged blobs** stay in app-private storage (`filesDir/staged_store/blobs/`).
  The FFI passes **file handles/paths within that app-private boundary only** —
  never arbitrary absolute paths, never `source_reference` on the wire.
- **Manifest** gains: `CAMERA` (runtime), `FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS` (runtime),
  `NEARBY_WIFI_DEVICES` / multicast flag as required by `mdns-sd` on Android 12+.
- **minSdk 26 / targetSdk 34** and **Java 1.8** stay; no Compose changes unless
  an integration bug forces them.

## 4. Windows requirements

| Item | Status |
|---|---|
| QR rendering in desktop `PairingDialog` (`qrcode.react`, `App.tsx:146-153`) | **Done** |
| `start_pairing` / `pair_with_uri` Tauri commands (`lib.rs:221-227`) | **Done** |
| mTLS listener on `0.0.0.0`, mDNS `_flowww._tcp` | **Done** (phone can reach the PC) |
| Trusted device list, transfer cancel/history | **Done** (`cancel_spatial`, SQLite) |
| Send/accept commands for incoming offers in the Tauri surface | **Missing** — desktop is read/cancel-only today; needs `send`, `accept/reject offer` commands if Windows→Android is to be driven from the desktop UI rather than only by accepting on Android |
| Interactive send-from-desktop UI to phone | **Missing** (same gap) |
| Device revoke from UI | **Missing** |
| Windows packaging/signing/bundling | Deliberately deferred (`bundle.active: false`) |
| MSVC vs GNU toolchain caveat | README records GNU `export ordinal too large`; full Tauri runtime validation happens on this PC only if it has MSVC Build Tools; otherwise the headless `flowww-node`-backed host (CLI harness used for tests) validates interoperability |

Both endpoints already share the exact crates (`flowww-node` + workspace deps),
so protocol compatibility holds by construction **as long as neither side forks
the protocol**. The Windows app must not be modified in ways that diverge from
the Android FFI surface.

## 5. Protocol compatibility

- Control frames: JSON `Envelope` (`protocol: "FLW/1.0"`), `MAX_CONTROL_FRAME`
  256 KiB, length-prefixed; file bytes in fixed-header binary chunks with
  per-chunk SHA-256 (`flowww-protocol`, `flowww-transfer`).
- Transport: TLS 1.3 (rustls+ring), per-device self-signed certs, SHA-256
  fingerprint pinning, no CA/hostname checking (`flowww-security`).
- Pairing: single-use, short-lived (2 min TTL), channel-bound; phone and PC
  run the **same** `flowww-security` pairing code — no reimplementation in
  Kotlin. **Do not write a Kotlin pairing protocol.**
- Trust: TOFU via signed pairing; revocation is local-only; discovery names are
  never used for authorization decisions.
- Spatial relay (III-device) is untouched — the FFI exposes only the two-device
  paths (`send_file`, `respond_to_offer`, `hold_file`, …) unless a separate
  decision adds spatial.
- Versioning: `PROTOCOL_VERSION = "FLW/1.0"`; peers accept `1.x`. Any additive
  Android-FFI work must not bump the wire version.

## 6. Security gaps (pre-existing; integration must not widen)

1. SQLite is stored unencrypted (`flowww-storage`); the identity private key is
   a JSON file with mode 0600 (Unix-only semantics). **Android must move the
   identity bytes into the Keystore**; the FFI accepts only a `data_dir`, so
   the store layout stays Rust-owned but created under the app-private dir with
   tight umask/ACLs. Document this explicitly.
2. Pairing host accepts **any** client certificate, then authenticates via the
   signed transcript (by design; see SECURITY.md §3). Mobile UIs must label this
   correctly.
3. `send_file` logs transfer IDs and content names; the file **path** must never
   cross the FFI for display. The file name stays, paths don't.
4. Receiving offers: incoming content is never auto-accepted; approval policy is
   enforced by `respond_to_offer(accept=true)` from user action in the UI. The
   Rust transfer authorization is single-use with nonces consumed in both the
   in-memory replay guard and the SQLite `nonces` table.
5. Discovery is spoofable — peer names and addresses are metadata only; all
   identity decisions derive from the pinned key.
6. No capability leaks: `source_reference` is never sent; staged blobs live
   behind app-private handles.
7. `auto_accept` exists in `NodeConfig`; the Android build must pin it `false`
   unless the user explicitly opts in via settings.
8. Pairing URI is shown as text in both UIs and is not logged; keep it that way
   on Android (no `Log.d("uri")`).
9. Spatial capture (III-device) has no user-visible consent prompt yet — out of
   scope for this milestone; do not wire `SPATIAL_CAPTURE_*` to the launcher.
10. Endpoints are not protected against compromise of either endpoint (malicious
    app with root on the phone can read app-private storage) — documented
    limitation, not something this milestone weakens or claims to fix.

## 7. Build requirements

| Requirement | Status |
|---|---|
| Rust ≥ 1.82 (workspace `rust-version`), 1.100.0 installed | ✔ |
| `aarch64-linux-android`, `x86_64-linux-android` std | ✔ (installed this session via rustup) |
| Android NDK | ✔ (26.3 / 27.0 / 27.1 installed; 27.1 pinned for the build script) |
| ARM32 (`armv7-linux-androideabi`) | **excluded** — `minSdk 26` + 64-bit-only devices cover the supported matrix; document |
| `gcc` for `rusqlite` bundled on Windows host | ✔ (`C:\mingw64\bin\gcc.exe`) |
| `cargo check --workspace` (host) | ✔ green (warnings only) |
| `cargo-ndk` | **not installed**; not required — NDK linker is wired explicitly in `apps/android/scripts/build_rust_android.ps1` + `.cargo/config.toml` style flags passed by the script (reproducible, no floaty global state) |
| UniFFI | `uniffi` + `uniffi_macros` for scaffolding (proc-macro style, no `.udl` file to hand-maintain); `uniffi-bindgen` runs once per binding regeneration via `cargo run -p flowww-ffi --bin uniffi-bindgen`; generated Kotlin **is committed** so the Android build does not need the Rust toolchain |
| `.so` binaries committed? | **No** — built by `build_rust_android.ps1` into `app/src/main/jniLibs/{arm64-v8a,x86_64}/libflowww_ffi.so`, which is gitignored; CI builds them per ABI |
| Android Gradle wiring | `jniLibs` source set default; `ndkVersion 27.1.12297006`; signing unchanged; minSdk 26/targetSdk 34 unchanged |
| Kotlin → Rust event marshalling | `Dispatchers.IO` + `callbackFlow`; native callbacks never touch the main looper |

## 8. Proposed implementation plan (honest milestones)

**Phase C — `crates/flowww-ffi`**
1. Scaffold workspace member `flowww-ffi` (`cdylib`, depends on `flowww-node`,
   `flowww-core`, `flowww-storage`, `flowww-security`, `flowww-transfer`,
   `uniffi`, `tokio`, `serde`).
2. Typed surface (records/enums/errors/callback interface):
   `FlowwwNode` object with `start(data_dir, device_name)`, `stop()`,
   `device_info()`, `snapshot()`,
   `start_discovery()`, `stop_discovery()`, `refresh_peers()`, `list_peers()`,
   `list_trusted()`, `revoke_peer(device_id)`,
   `start_pairing(ttl_seconds)` → `PairingViewDto`, `cancel_pairing()`,
   `pair_with_uri(uri)`,
   `send_file(device_id, path)` / `send_text(device_id, text)` /
   `send_url(device_id, url)`,
   `pending_offers()`, `approve_offer(transfer_id)`, `reject_offer(transfer_id)`,
   `cancel_transfer(transfer_id)`, `transfer_history()`, `auto_accept(bool)`.
3. Errors: `FlowwwError { category, message }` — categories mapped from
   `NodeError` (`PairingBusy`, `PairingFailed`, `Timeout`, `Storage`, `Crypto`,
   `Protocol`, …) without internal traces.
4. Events: UniFFI `callback interface FlowwwEventListener` — one `on_event(event:
   FlowwwEvent)` with a payload enum mirroring `NodeEvent`.
5. Unit tests in Rust: error mapping, node start/stop on a temp dir, event
   delivery, two-node pairing (host + joiner) and two-node file send with
   SHA-256 verification — all deterministic, no device needed.
6. `build_rust_android.ps1` → `jniLibs`; bindings regeneration script; generated
   Kotlin committed under `app/src/main/java/uniffi/flowww_ffi/FlowwwFfi.kt`.

**Phase D — app wiring (device-testable)**
7. `AndroidManifest`: camera/foreground/notification/wifi-multicast permissions;
   `TransferForegroundService` becomes real.
8. `FfiConnectionService`, `FfiPairingService`, `FfiDiscoveryRepository`,
   `FfiTransfersBridge` replace stubs; `AppContainer` switches to them; fakes
   remain in `androidTest` only.
9. **QR scan pairing**: `PairingScreen` gains "Scan QR code" → full-screen
   CameraX scanner with ZXing decoding (offline, no ML Kit dependency) → the
   decoded `flowww://pair/v1/…` URI goes through the same validated `pair_with_uri`
   path — no separate trust decision.
10. Nearby / Trusted / Pairing / Transfers screens read live Rust state; honest
    empty states remain when Rust reports zero peers/no offers.
11. Foreground service only for active transfers; sensor sessions stay untouched.

**Phase D2 — bidirectional interop**
12. Windows: add Tauri commands `send_file`, `approve_offer`/`reject_offer`
    (thin wrappers over node APIs that the CLI/host already uses); document.
    Android receives offers, user approves, bytes land in app-private
    `received/`, verified SHA-256, exposed as staged items.
13. Two-node in-process Rust test + physical-device validation with the
    desktop agent on this PC.

**Phase E — motion handoff**
14. `GestureController` consumes real FFI state (staged availability, trusted
    peers, active transfers) and triggers `send_file` on SEND confirmation,
    opens approval for RECEIVE, `cancel_transfer` on CANCEL. Diagnostics stay
    separate.

**Risks / honesty constraints**
- Physical-device verification needs this PC and the phone on the same LAN and,
  for the camera-scan step, the user to point the phone at the Windows QR once;
  I cannot scan a screen with ADB.
- If the desktop Tauri runtime cannot be validated on this host (GNU toolchain
  caveat), Windows-side proof is via the equivalent headless host running the
  same `flowww-node` (protocol-equal), and the Windows UI change is kept
  minimal and typechecked.
- The `.so` is ~5–15 MB per ABI (SQLite bundled + ring). Strip symbols in
  release; measure.
- No claim of production readiness; statuses reported per milestone.
