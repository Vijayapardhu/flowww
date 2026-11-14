# Universal Handoff Implementation — v4.4

**Version:** 1.0 (v4.4 milestone)  
**Baseline:** flowww v4.3 (commit `11b2cee`)  
**Goal:** Real end-to-end bidirectional Windows ↔ Android content handoff controlled by phone motion gestures.

---

## 1. Repository Audit Summary

### REAL (fully implemented Rust core)

| Crate | Status | Role |
|-------|--------|------|
| `flowww-core` | REAL | Content, DeviceId, TransferId, Platform, Capability, ContentKind, TransferAction, TransferState, TransferRoute |
| `flowww-protocol` | REAL | FLW/1.0 wire protocol (envelope, frame, message) |
| `flowww-security` | REAL | Ed25519 identity, signing, verification, pairing payloads, authorization, replay guard, spatial proof |
| `flowww-storage` | REAL | SQLite Store, TransferRecord, nonce persistence |
| `flowww-discovery` | REAL | mDNS Discovery |
| `flowww-network` | REAL | Pinned mTLS, PeerListener, PeerSession, DeviceTls, connect, connect_pairing |
| `flowww-transfer` | REAL | send_file, receive_file, stream_file, hash_file, relay_transfer |
| `flowww-spatial` | REAL | SpatialSession, SpatialRegistry, ContentVault, resolve_drop |
| `flowww-node` | REAL | Full Node: pairing, transfer send/receive, spatial, cancellation, event broadcast |
| `flowww-bridge` | REAL | Local HTTP bridge with authentication |

### STUBS (Android — needs FFI integration)

| Component | Status | Notes |
|-----------|--------|-------|
| `ConnectionServiceImpl` | STUB | All methods return empty/null/false. No Rust FFI calls. |
| `PairingServiceImpl` | STUB | `initiatePairing` returns fake success. No `pair_with_uri` call. |
| `GestureController` | STUB-BASED | Uses stub ConnectionService. `handleReceive` returns mock success. |
| Android FFI | NONE | No NDK library, no UniFFI, no JNI bridge exists. |

### REAL (Windows desktop)

| Component | Status | Notes |
|-----------|--------|-------|
| `flowww-node::Node` | REAL | Integrated via Tauri commands |
| IPC server | REAL | Loopback TCP + bearer token auth |
| Content providers | STUB | Explorer/Clipboard stage but don't call Node transfers |
| Native Messaging host | STUB | `get_active_tab` returns error |
| Tray | REAL | Menu + handlers |
| Lifecycle | REAL | Start/stop/single-instance |

---

## 2. Integration Architecture

```
Windows Desktop                              Android Companion
┌─────────────────────────┐                  ┌─────────────────────────┐
│  Tauri App (Tray/UI)    │                  │  Jetpack Compose UI     │
│                         │                  │                         │
│  flowww-node::Node      │◄───── mTLS ─────►│  Rust Node (FFI via     │
│  (pairing, transfer)    │   UniFFI/JNI     │  UniFFI Kotlin bindings)│
│                         │                  │                         │
│  Content Providers      │                  │  Sharesheet Receiver    │
│  (Explorer, Clipboard)  │                  │  + Content Staging      │
│                         │                  │                         │
│  IPC Server (loopback)  │                  │  Gesture Engine         │
│  (bearer token auth)    │                  │  (SEND/RECEIVE/CANCEL)  │
└─────────────────────────┘                  └─────────────────────────┘
        ▲                                              ▲
        │                                              │
        └──────────────── P2P mTLS ────────────────────┘
```

### Key Design Decisions

1. **No second transfer engine.** All transfers go through `flowww-node::Node` on both sides.
2. **No second identity system.** Ed25519 identity lives in Rust; Android accesses it via FFI only.
3. **No bypassing pairing.** Trust directory is shared via FFI; Android `get_trusted_devices` calls Rust `node.paired_peer()`.
4. **No mock networking.** Production code uses real `flowww-network` mTLS connections.
5. **UniFFI preferred** for Rust↔Kotlin bindings with async support.

---

## 3. FFI Design (UniFFI)

### Android-compatible Rust library crate

**Path:** `apps/android/flowww-rust-android/`  
**Type:** `cdylib` + `staticlib` for Android ABIs (aarch64, armv7, x86_64, i686)

### UniFFI `.udl` interface

```uniffi
namespace flowww {

struct DeviceInfoDto {
    device_id: String,
    device_name: String,
    platform: String,
    protocol_version: String,
    fingerprint: String,
    capabilities: List<String>,
};

struct TransferRecordDto {
    transfer_id: String,
    source_device_id: String,
    destination_device_id: String,
    filename: Option<String>,
    size: u64,
    status: String,
    route: String,
    direction: String,
    bytes_transferred: u64,
    error: Option<String>,
    created_at: u64,
    completed_at: Option<u64>,
};

struct PairingViewDto {
    uri: String,
    endpoint: String,
    expires_at: u64,
};

enum TransferAction { Open, Save, Preview };

interface FlowwwNode {
    /// Initialize the Rust node.
    async start_node(data_dir: String, download_dir: String, device_name: String) -> Result<DeviceInfoDto, String>;

    /// Shutdown the node.
    async shutdown() -> Result<(), String>;

    /// Get local device info.
    Result<DeviceInfoDto, String> get_device_info();

    /// List trusted/paired devices.
    Result<List<DeviceInfoDto>, String> get_trusted_devices();

    /// Discover nearby peers via mDNS.
    async Result<List<DeviceInfoDto>, String> discover_peers();

    /// Start pairing (host side). Returns pairing URI.
    async Result<PairingViewDto, String> start_pairing();

    /// Complete pairing from a URI (join side).
    async Result<DeviceInfoDto, String> pair_with_uri(uri: String);

    /// Revoke a paired device.
    Result<bool, String> revoke_device(device_id: String);

    /// Connect to a device (establish mTLS).
    async Result<bool, String> connect_to_device(device_id: String);

    /// Disconnect from a device.
    async disconnect_device(device_id: String) -> Result<(), String>;

    /// Check if device is connected.
    Result<bool, String> is_device_connected(device_id: String);

    /// Hold content for spatial transfer (source side).
    async Result<SpatialHoldDto, String> hold_content(content: ContentDto);

    /// Request capture from a source (controller side).
    async Result<ContentCapabilityDto, String> request_capture(source_device: String);

    /// Stage content for transfer.
    async Result<StagedContentDto, String> stage_content(content: ContentDto);

    /// Initiate a send transfer.
    async Result<TransferRecordDto, String> initiate_send(device_id: String, content_id: String, action: TransferAction);

    /// Request content from a device (receive).
    async Result<TransferRecordDto, String> initiate_receive(device_id: String, content_reference: String);

    /// Cancel a transfer.
    async Result<bool, String> cancel_transfer(transfer_id: String);

    /// Get transfer status.
    async Result<Option<TransferRecordDto>, String> get_transfer_status(transfer_id: String);

    /// Get active transfers.
    async Result<List<TransferRecordDto>, String> get_active_transfers();

    /// Get transfer history.
    async Result<List<TransferRecordDto>, String> get_transfer_history();

    /// Subscribe to transfer events (callback-based).
    async subscribe_transfer_events() -> Result<(), String>;
};

struct ContentDto {
    content_id: String,
    content_type: String,
    display_name: String,
    mime_type: Option<String>,
    size: Option<u64>,
    source_application: Option<String>,
    content_reference: String,
    authorization_context: Option<String>,
    created_at: u64,
    expires_at: u64,
};

struct StagedContentDto {
    content_id: String,
    expires_at: u64,
};

struct SpatialHoldDto {
    handle: String,
    content: ContentDto,
    expires_at: u64,
};

struct ContentCapabilityDto {
    capability_id: String,
    source_device: String,
    expires_at: u64,
};

} // namespace flowww
```

### Security Boundaries in FFI

- **Private keys never cross FFI.** All signing happens inside Rust.
- **Signed capabilities never cross FFI.** Only DTOs with identifiers.
- **Absolute paths never exposed.** `content_reference` is an opaque handle.
- **Raw internal errors never exposed.** Mapped to sanitized strings.

---

## 4. Content Ownership Model

| Stage | Owner | Location | Lifetime |
|-------|-------|----------|----------|
| Content detected/staged | Source device | Source temp storage | TTL (default 30 min) |
| Content offered | Source device | Source temp storage | Until accepted/rejected |
| Content transferring | Both devices | In transit (mTLS stream) | Until complete/failed |
| Content received | Destination device | Destination downloads dir | Permanent (user file) |
| Content reference | Source device | Opaque handle only | Until transfer complete |

Rules:
- Large file contents never cross FFI as byte arrays. Only references/handles.
- File paths are source-side only; never in DTOs, never on the wire.
- SHA-256 integrity verified on every transfer.
- Atomic completion via `.partial` → rename pattern.

---

## 5. Transfer State Synchronization

Single handoff state machine shared across both devices:

```
IDLE → TARGET_SELECTED → CONTENT_AVAILABLE → CONTENT_STAGED →
GESTURE_DETECTED → HANDOFF_REQUESTED → AWAITING_APPROVAL →
TRANSFERRING → VERIFYING → COMPLETED
                         ↘           ↘           ↘
                       CANCELLED   FAILED    EXPIRED  (terminal)
```

### Rules

- Terminal states cannot transition back to active states.
- Stale events cannot overwrite terminal states.
- Replay protection via nonce guard (`flowww-security::ReplayGuard`).
- Duplicate transfer IDs prevented via deterministic `TransferId::for_content()`.
- Transfers to disconnected devices rejected.
- Event loss triggers resync via `get_transfer_status()` / `get_transfer_history()`.

### State Synchronization Mechanism

1. Both sides publish `TransferRecord` updates via Node events.
2. Android observes Rust Node events via FFI callback subscription.
3. Windows observes via Tauri event emission.
4. On reconnect, both sides query authoritative state from the Node.

---

## 6. Failure Handling

| Failure | Handling |
|---------|----------|
| Pairing URI expired | Return `PairingResult.Expired`, require new URI |
| Untrusted peer | Reject all transfers, show "Not trusted" in UI |
| Network interruption | Retry with exponential backoff, resume from `bytes_transferred` |
| Hash mismatch | Fail transfer, show integrity error |
| Cancel requested | Stop stream, mark `CANCELLED`, notify both sides |
| Device disconnect during transfer | Mark `FAILED`, allow resume on reconnect |
| FFI call error | Return sanitized error, no internal details |
| Low-confidence gesture | Ignore with haptic feedback, no transfer attempt |

---

## 7. Phase Dependencies

```
Phase 1: Android build env (JDK 17, Gradle 8.x)
       ↓
Phase 2: Rust FFI facade (UniFFI .udl + cdylib)
       ↓
Phase 3: Kotlin FFI bindings + replace ConnectionService stubs
       ↓
Phase 4: Unified content routing (both directions)
       ↓
Phase 5: Handoff state machine (shared state model)
       ↓
Phase 6: Motion → real transfers (GestureController → FFI)
       ↓
Phase 7: Windows integration (providers → Node transfers)
       ↓
Phase 8: User feedback (notifications, haptics, progress)
       ↓
Phase 9: Testing (unit + integration + e2e)
       ↓
Phase 10: Documentation
```

---

## 8. Completion Criteria

1. Android and Windows devices securely paired via Rust protocol.
2. Android shares image → Positive Y gesture → real transfer → Windows receives/verifies.
3. Windows stages file → Negative Y gesture → real transfer → Android receives/verifies.
4. Chrome URL moves between devices.
5. Twist cancels active cancellable transfer.
6. Both devices show consistent transfer status.
7. No mock networking in production code.
8. All security properties preserved (mTLS, signing, replay protection).
