# Desktop Runtime Next Steps — v4.2 Android Companion

This document records the implementation sequence and completion gates for **v4.2 — Native Android Companion**.

## What was implemented in v4.2

- Android project structure (Gradle Kotlin DSL, Jetpack Compose, Hilt-ready)
- Native Android Sharesheet integration (ACTION_SEND, ACTION_SEND_MULTIPLE)
- Content staging abstraction with lifecycle tracking (CREATED → STAGED → RESERVED → TRANSFERRED, terminal EXPIRED/CANCELLED/FAILED)
- Device pairing integration with existing Rust protocol (PairingPayload, PairingJoin, PairingAccept, PairingConfirm)
- Rust protocol integration via FFI/UniFFI (documented interface, stub implementations)
- Connection architecture (secure device connection, lifecycle, reconnection)
- Motion readiness sensor abstraction (accelerometer, gyroscope, calibration interface)
- Privacy and security measures (content sanitization, URI validation, size limits, no automatic transfers)
- Minimal monochrome UI with subtle orange accent (7 screens)
- Foreground service for active transfers
- Unit tests for content model, sanitizer, pairing parser, and staged store

## Required Android Validation

Run on a supported Android device or emulator with API level 24+:

```powershell
# Install Android SDK command-line tools if not present
# Set ANDROID_HOME environment variable
$env:ANDROID_HOME = "C:\Users\PARDHU\AppData\Local\Android\Sdk"

cd apps/android
./gradlew assembleDebug
./gradlew test
./gradlew connectedAndroidTest  # Requires connected device or emulator
```

Record: sharesheet integration, pairing flow, content staging, connection to trusted device, transfer initiation, process termination.

## Integration with Rust Core (v4.2 Foundation)

The Android app uses stub implementations for FFI calls. To connect to the actual Rust node:

1. Add `cargo-ndk` or configure `ndk` target for Rust compilation
2. Create a `.udl` UniFFI definition file in `apps/android/flowww-rust-android/`
3. Generate Kotlin bindings with `uniffi-bindgen`
4. Replace stub implementations in `PairingServiceImpl` and `ConnectionServiceImpl` with FFI calls

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

## Release Gates

- No frontend DTO contains internal filesystem paths or opaque handles.
- Content DTOs contain only opaque references.
- IPC bearer token from Windows agent is never exposed to Android.
- Local IPC is authenticated; no unauthenticated network exposure.
- Content expiration enforced with configurable TTL.
- Filename sanitization and safe temporary storage.
- No automatic clipboard upload; no silent background transfer.
- Sharesheet content read via ContentResolver only — no absolute file paths.
- File type and size validation before staging.
- Minimum permissions requested; no unnecessary permissions.
- No sensitive content in logs.
- Unit tests pass on JVM.

## Next Milestone

**v4.3 — Motion Transfer:**
- Sensor calibration on Android
- Positive Y = SEND, Negative Y = RECEIVE gesture detection
- Twist = CANCEL gesture detection
- Gesture diagnostics
- False-positive protection
