# Android Runtime Next Steps — v4.3 Motion Transfer Engine

This document records the implementation sequence and completion gates for **v4.3 — Motion Transfer Engine**.

## What was implemented in v4.3

- Motion sensor processing: `MotionFilter` (gravity estimation, linear acceleration, low-pass filtering)
- Calibration system: `SensorCalibration` (neutral baseline, gesture threshold estimation, directional signatures, profile persistence)
- Gesture recognition engine: `GestureEngine` (state machine, false-positive protection, semantic event emission)
- Gesture classifier: `GestureClassifier` (SEND/RECEIVE/CANCEL classification with confidence scoring)
- Sensor sample model: `SensorSample` and `ProcessedSample` data classes
- Gesture events: `GestureEvent` sealed class (SEND, RECEIVE, CANCEL, IDLE, CALIBRATION_REQUIRED, LOW_CONFIDENCE)
- Safety/handoff integration: `GestureController` (preconditions: staged content for SEND, trusted target for RECEIVE, active cancellable for CANCEL)
- Gesture diagnostics UI: `GestureDiagnosticsViewModel`, `GestureDiagnosticsScreen`, `GestureSessionActivity`, `GestureTestingActivity`
- False-positive protection: confidence thresholds, min/max impulse duration, return-to-neutral, cooldown, duplicate suppression, noise rejection
- Android lifecycle: gesture session tied to activity lifecycle, sensors unregistered on pause/stop, no background polling
- Unit tests: `MotionFilterTest`, `GestureClassifierTest`, `GestureEngineTest`, `GestureControllerTest` (4 test files)

## Required Android Validation

Run on a supported Android device or emulator with API level 24+ and accelerometer/gyroscope hardware:

```powershell
# Set ANDROID_HOME environment variable
$env:ANDROID_HOME = "C:\Users\PARDHU\AppData\Local\Android\Sdk"

cd apps/android
./gradlew assembleDebug
./gradlew test
./gradlew connectedAndroidTest  # Requires connected device or emulator
```

### Demonstration criteria (v4.3)

1. Open Flowww → navigate to gesture diagnostics/testing
2. Start a gesture session
3. Calibrate the phone (hold in pointing position, perform SEND/RECEIVE/CANCEL)
4. Perform positive Y movement → see SEND recognized
5. Perform negative Y movement → see RECEIVE recognized
6. Twist the phone → see CANCEL recognized
7. Verify ordinary movement (walking, bumping) does not trigger accidental gestures

## Integration with Rust Core (v4.3 Foundation)

The Android app uses stub implementations for FFI calls. The GestureController delegates to ConnectionService for actual handoff operations. To connect to the actual Rust node:

1. Add `cargo-ndk` or configure `ndk` target for Rust compilation
2. Create a `.udl` UniFFI definition file in `apps/android/flowww-rust-android/`
3. Generate Kotlin bindings with `uniffi-bindgen`
4. Replace stub implementations in `ConnectionServiceImpl` with FFI calls

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

- Gesture engine emits semantic events only; no direct network/transfer execution.
- GestureController enforces preconditions: staged content for SEND, trusted target for RECEIVE, active cancellable for CANCEL.
- False-positive protection: confidence thresholds, min/max impulse duration, return-to-neutral, cooldown, duplicate suppression.
- Calibration data stored locally; raw sensor traces never uploaded.
- Sensors active only during explicit user-visible gesture session; unregistered on pause/stop.
- No unrestricted background sensor polling.
- Unit tests pass on JVM (where Gradle toolchain permits).
- No physical-device accuracy claims without real hardware testing.

## Known Limitations

- *(Corrected)* The v4.3 build blocker ("Gradle build cannot run due to Java 24 + Gradle 8.11.1 incompatibility") is resolved: the wrapper is Gradle 8.14, and `assembleDebug`, `testDebugUnitTest`, `lintDebug` and `connectedDebugAndroidTest` all run green. See `MILESTONE_C_PREREQUISITES.md` for the full toolchain status and the remaining limitations.
- Real cross-device transfer is still not implemented: send, cancel, retry, pairing and discovery report their real unavailability instead of fabricating state.
- Calibration profile persistence uses Java serialization; consider Protobuf/JSON for production.
- Milestone A/B of the app layer (domain/data/presentation split, staged content, settings, sharesheet, gesture diagnostics, navigation) is complete; the Rust-pending statuses and the prerequisites for replacing them are tracked in `MILESTONE_C_PREREQUISITES.md`.

## Next Milestone

**v4.4 — Universal Handoff:**
- Complete content routing (Windows-to-Android and Android-to-Windows)
- URL movement, file movement, text and image movement
- Reliable synchronization between gesture engine and Rust transfer engine
- Real FFI integration with Rust node (replace stubs)
