# Android Companion Architecture — v4.2

**Version:** 1.0 (v4.2 milestone)  
**Baseline:** flowww v4.1 (commit `f59bf45`)  
**Scope:** Native Android companion application for the flowww Windows background agent.

## 1. Goals

The Android companion application is not a traditional file-sharing application. Its primary purpose is:

1. **Receive content from Android's native Sharesheet** — users share images, videos, documents, URLs, and text from any app into Flowww.
2. **Stage content for cross-device handoff** — content is prepared but not automatically transmitted.
3. **Connect to trusted flowww Windows devices** — pairing, discovery, and connection management reuse the existing Rust protocol.
4. **Provide the foundation for phone-motion-based transfer gestures** — sensor abstraction prepared for v4.3.

## 2. Non-goals (v4.2)

- Motion-triggered transfers (v4.3)
- Gesture engine implementation
- Windows-to-Android content receiving (initiated from Windows side only)

## 3. Process Model

```text
Android application (Kotlin, Jetpack Compose)
├── Sharesheet receiver (ACTION_SEND, ACTION_SEND_MULTIPLE)
│   ├── ContentResolver-based URI reading
│   ├── Temporary content staging
│   └── Transfer preparation state
├── Device pairing & discovery
│   ├── Trusted device list
│   ├── Pairing flow (existing Rust protocol via FFI)
│   └── Target selection and persistence
├── Content staging store (Room / encrypted preferences)
│   ├── Typed content abstraction (FILE, IMAGE, VIDEO, TEXT, URL, DOCUMENT, MULTIPLE_FILES)
│   └── Expiration and cleanup
├── Connection layer (FFI → Rust node)
│   ├── Secure device connection (pinned mTLS)
│   ├── Connection lifecycle and reconnection
│   └── Transfer state synchronization
├── Motion readiness (sensor abstraction for v4.3)
│   ├── Accelerometer / Gyroscope availability
│   └── Calibration interface
└── Settings
```

## 4. Reuse of Existing Rust Core

### Integration Decision: UniFFI (recommended) / JNI (fallback)

**Primary choice: Mozilla UniFFI**  
UniFFI generates Kotlin bindings from a Rust interface definition (.udl file). It handles:
- Complex type mapping (structs, enums, optionals, vectors)
- Async/await bridging between Rust tokio and Kotlin coroutines
- Error propagation
- Object lifetime management across FFI boundary

**Trade-offs considered:**

| Approach | Pros | Cons |
|----------|------|------|
| **UniFFI** | Type-safe Kotlin bindings, async support, automatic FFI generation, minimal boilerplate | Additional build tool (uniffi-bindgen), Rust Android target required |
| **Raw JNI** | No extra tools, full control | Error-prone, manual type marshaling, no async support, extensive boilerplate |
| **Dedicated native lib (no FFI)** | Simplest Kotlin side | Duplicates crypto/protocol in Kotlin — **violates security requirement** |

**Decision:** Use UniFFI for Rust-Kotlin bindings. The Rust core is compiled as a static library for Android targets (aarch64-linux-android, armv7-linux-androideabi, x86_64-linux-android, i686-linux-android) using the Android NDK. A `.udl` file defines the FFI interface; `uniffi-bindgen` generates Kotlin bindings.

**Fallback:** If UniFFI tooling cannot be configured, a thin JNI layer exposes `start_node`, `get_identity`, `pair_with_uri`, `discover_peers`, `send_content`, `cancel_transfer` as C-compatible functions called from Kotlin.

### What is NOT reimplemented in Kotlin

- Ed25519 device identity generation and signing
- Signed pairing transcripts
- Pinned mutual TLS 1.3
- Signed transfer authorization
- Replay protection
- SHA-256 integrity verification
- Transfer lifecycle state machine

All of these live in the Rust `flowww-node`, `flowww-security`, `flowww-transfer`, and `flowww-spatial` crates and are called via FFI.

## 5. Content Provider Architecture

### Content Types

```kotlin
enum class ContentType { FILE, IMAGE, VIDEO, TEXT, URL, DOCUMENT, MULTIPLE_FILES }
```

### Staged Content Model

Every staged item contains:

| Field | Description |
|-------|-------------|
| contentId | UUID-based identifier |
| contentType | FILE, IMAGE, VIDEO, TEXT, URL, DOCUMENT, MULTIPLE_FILES |
| displayName | Human-readable name |
| mimeType | MIME type when available |
| size | Size in bytes when applicable |
| createdAt | Creation timestamp (epoch ms) |
| expiresAt | Expiration timestamp (epoch ms) |
| contentReference | Opaque internal reference (not a filesystem path) |
| authorizationContext | Authorization context for the staged item |

### Content Staging Lifecycle

```
CREATED → STAGED → RESERVED → TRANSFERRED
              ↘      ↘      ↘
            EXPIRED CANCELLED FAILED (terminal)
```

- Content must not become transferable merely because it exists in storage.
- An authorized handoff request is required.
- Content expires after a configurable TTL.
- Temporary storage is cleaned up on expiration or app exit.

### Sharesheet Integration

Intent filters for:
- `ACTION_SEND` / `ACTION_SEND_MULTIPLE`
- MIME types: `text/plain`, `image/*`, `video/*`, `application/pdf`, `application/octet-stream`

Content is read via `ContentResolver` and content URIs. Absolute filesystem paths are never assumed. URIs are copied into controlled temporary storage when necessary.

## 6. Device Pairing

### Pairing Flow

1. User initiates "Add Device" from the Android app.
2. The Windows desktop generates a pairing URI (via `flowww-node::Node::start_pairing()`).
3. The user scans/enters the pairing URI on Android (or the URI is shared via clipboard/NFC).
4. Android validates the pairing payload (expiry, signatures, endpoint).
5. Android establishes a pinned mTLS connection to the Windows desktop.
6. Both sides exchange signed pairing transcripts (Ed25519).
7. The paired device is stored in the local trust store.

### Target Selection

- Paired trusted devices are listed with name, platform, connection state.
- User selects a target device before initiating a transfer.
- Last active target is persisted.
- Device availability is checked before transfer initiation.

## 7. Connection Architecture

### Secure Connection

- Pinned mutual TLS 1.3 (existing Rust implementation).
- Connection established via FFI call to the Rust node.
- Connection lifecycle: connecting → connected → disconnected → reconnecting.
- Network change handling: Wi-Fi disconnection triggers reconnect with backoff.
- Foreground/background transitions: connection is maintained via foreground service when transfers are active.

### Transfer State Synchronization

- Transfer events (started, progressing, completed, failed, cancelled) flow from Rust via FFI callbacks.
- Android UI observes transfer state as a Kotlin Flow/StateFlow.
- History is persisted locally.

## 8. Motion Readiness (v4.3 Foundation)

A separate sensor abstraction module exposes:

- Accelerometer availability (`Sensor.TYPE_ACCELEROMETER`)
- Gyroscope availability (`Sensor.TYPE_GYROSCOPE`)
- Sensor sampling interface
- Device orientation estimation
- Calibration interface (gravity compensation, bias removal)
- Sensor diagnostics (noise floor, sampling rate)

**Restrictions:** No continuous background sensor polling. If Android background execution restrictions prevent continuous sensing, a visible foreground service session with clear user consent is used.

## 9. Privacy and Security

- Explicit trusted-device authorization before any transfer.
- Content staging uses encrypted temporary storage.
- Temporary files are cleaned up after transfer or expiration.
- No automatic clipboard upload.
- No automatic transfer of shared content — user must explicitly select target and confirm.
- Minimum permissions: no unnecessary permissions requested.
- No sensitive content in logs.
- Safe URI handling — content URIs only, no file:// paths exposed to UI.
- File type and size validation before staging.
- IPC bearer token from the Windows agent is never exposed to Android.

## 10. Application Interface

### Design Language

- Minimal, premium, monochrome.
- Subtle orange accent (`#FF7A1D`).
- Clear typography.
- Small, purposeful animations.

### Screens

| Screen | Purpose |
|--------|---------|
| Nearby Devices | Discovered flowww devices on the network |
| Trusted Devices | Paired devices with connection state |
| Pairing | Add a new device via pairing URI |
| Staged Content | Content shared via Sharesheet, awaiting transfer |
| Active Transfers | In-progress transfers with progress |
| Transfer History | Completed/failed transfers |
| Settings | App configuration, security, diagnostics |

### Entry Point

The native Sharesheet is the main content entry point. Users should not need to navigate the app for ordinary sharing — Share → Flowww → stage → select device → transfer.

## 11. Permissions

| Permission | Purpose | Runtime |
|------------|---------|---------|
| `INTERNET` | Network connections to trusted devices | Install-time |
| `ACCESS_NETWORK_STATE` | Network change detection | Install-time |
| `READ_MEDIA_IMAGES` | Read shared images via ContentResolver | Runtime (Android 13+) |
| `READ_MEDIA_VIDEO` | Read shared videos via ContentResolver | Runtime (Android 13+) |
| `READ_EXTERNAL_STORAGE` | Read shared documents (legacy) | Runtime |
| `FOREGROUND_SERVICE` | Maintain connections during active transfers | Install-time |
| `FOREGROUND_SERVICE_DATA_SYNC` | Data sync in foreground | Install-time (Android 14+) |
| `POST_NOTIFICATIONS` | Transfer notifications | Runtime |

No `READ_CALL_LOG`, `CAMERA`, `LOCATION`, `MICROPHONE`, or `SMS` permissions.

## 12. Integration Points with Rust Core

### FFI Interface (via UniFFI)

| Function | Purpose |
|----------|---------|
| `start_node(data_dir, device_name)` | Initialize the Rust node |
| `get_device_info()` | Return local device identity |
| `get_trusted_devices()` | List paired devices |
| `start_pairing(ttl_ms)` | Generate pairing URI |
| `pair_with_uri(uri)` | Complete pairing with a remote device |
| `discover_peers()` | Discover nearby flowww devices |
| `connect_to_device(device_id)` | Establish connection to a device |
| `send_content(device_id, content_ref)` | Initiate a content transfer |
| `cancel_transfer(transfer_id)` | Cancel an in-progress transfer |
| `get_transfer_status(transfer_id)` | Query transfer state |
| `shutdown_node()` | Clean shutdown |

### Event Callbacks (Rust → Kotlin)

| Event | Purpose |
|-------|---------|
| `onPeerFound` | New device discovered |
| `onPeerLost` | Device gone from network |
| `onTransferUpdated` | Transfer state changed |
| `onOfferReceived` | Incoming transfer offer |
| `onPairingCompleted` | Pairing finished |
| `onError` | Error notification |

## 13. Build Configuration

- **Language:** Kotlin (1.9+)
- **UI:** Jetpack Compose
- **Build system:** Gradle Kotlin DSL
- **Minimum SDK:** 24 (Android 7.0)
- **Target SDK:** 35 (Android 15)
- **Database:** Room (content staging persistence)
- **Dependency injection:** Hilt or manual
- **Coroutines:** kotlinx-coroutines for async
- **Serialization:** kotlinx.serialization for JSON
- **Encryption:** AndroidX Security / Tink for temporary storage encryption

## 14. Motion Gesture Subsystem (v4.3)

The motion gesture subsystem enables the phone to act as a physical motion controller for flowww. It is documented in detail in `docs/MOTION_TRANSFER_ARCHITECTURE.md`.

### Components

| Component | Responsibility |
|-----------|---------------|
| `SensorManagerWrapper` | Raw accelerometer + gyroscope sampling (reused from v4.2) |
| `MotionFilter` | Gravity estimation, linear acceleration, filtering |
| `SensorCalibration` | Calibration profile management and storage |
| `GestureClassifier` | SEND/RECEIVE/CANCEL classification with confidence scoring |
| `GestureEngine` | State machine, false-positive protection, event emission |
| `GestureController` | Safety preconditions before handoff routing |
| `GestureDiagnosticsViewModel` / `GestureDiagnosticsScreen` | Live diagnostics and testing UI |
| `GestureSessionActivity` | Lifecycle-managed gesture sensing session |

### Gesture Mapping

| Gesture | Sensor | Semantic Action |
|---------|--------|-----------------|
| Positive Y impulse | Accelerometer | SEND |
| Negative Y impulse | Accelerometer | RECEIVE |
| Deliberate twist | Gyroscope | CANCEL |

The convention Positive Y = SEND and Negative Y = RECEIVE is preserved after calibration.

### Safety

- The gesture engine emits semantic events only; it never executes networking or transfers.
- The GestureController enforces preconditions: staged content for SEND, trusted target for RECEIVE, active cancellable operation for CANCEL.
- False-positive protection: confidence thresholds, minimum/maximum impulse duration, return-to-neutral, cooldown, duplicate suppression.

### Android Lifecycle

- Sensors are active only during an explicit user-visible gesture session.
- Sensors are unregistered on pause/stop/destroy.
- No unrestricted background sensor polling.

