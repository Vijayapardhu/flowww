# Motion Transfer Architecture — v4.3

**Version:** 1.0 (v4.3 milestone)  
**Baseline:** flowww v4.2 (commit `4ac6ba3`)  
**Scope:** Phone-motion gesture recognition engine for the flowww Android companion.

## 1. Goals

The phone becomes the physical controller for flowww. The user points their phone toward a trusted Windows device and performs physical movements to send, receive, or cancel content.

This milestone implements:
- Sensor processing (accelerometer + gyroscope sampling, filtering, gravity compensation)
- Calibration workflow (neutral baseline, gesture threshold estimation, directional signatures)
- Gesture recognition engine (SEND / RECEIVE / CANCEL / IDLE / CALIBRATION_REQUIRED / LOW_CONFIDENCE)
- False-positive protection (confidence thresholds, minimum duration, return-to-neutral, cooldown, duplicate suppression)
- Gesture diagnostics UI
- Safety and handoff integration controller

No second transfer engine is created. The gesture engine emits semantic events only and does not execute networking or transfer operations.

## 2. Non-goals (v4.3)

- Real cross-device transfer execution (mock/test controllers used for this milestone)
- Windows-to-Android content receiving initiated from the phone
- Spatial/UWB-based directional targeting

## 3. Process Model

```text
Motion gesture subsystem (Android)
├── SensorManagerWrapper (existing v4.2)
│   ├── Accelerometer sampling (TYPE_ACCELEROMETER)
│   ├── Gyroscope sampling (TYPE_GYROSCOPE)
│   └── Raw SensorReading emission
├── MotionFilter
│   ├── Gravity estimation and compensation
│   ├── Linear acceleration calculation
│   └── Low-pass / high-pass filtering
├── SensorCalibration
│   ├── Neutral baseline recording
│   ├── Gesture threshold estimation
│   └── Directional signature capture
├── GestureClassifier
│   ├── Directional acceleration classification
│   ├── Rotation detection (twist)
│   └── Confidence scoring
├── GestureEngine
│   ├── Semantic event emission (SEND / RECEIVE / CANCEL / IDLE / ...)
│   ├── False-positive protection
│   └── State machine (IDLE → GESTURE_DETECTED → confirmed)
├── GestureController (safety layer)
│   ├── SEND requires staged content + trusted target
│   ├── RECEIVE requires valid trusted target
│   ├── CANCEL requires active cancellable operation
│   └── Event → handoff routing
└── GestureDiagnosticsScreen
    ├── Live sensor readout
    ├── Calibration status
    └── Gesture testing mode
```

## 4. Gesture Specification

| Gesture | Sensor | Semantic Action | Controller Behavior |
|---------|--------|-----------------|---------------------|
| Positive Y impulse | Accelerometer | SEND | Request handoff of staged content to selected trusted device |
| Negative Y impulse | Accelerometer | RECEIVE | Request content from selected trusted device |
| Deliberate twist | Gyroscope | CANCEL | Cancel current pending/cancellable handoff |
| Neutral | Sensors | IDLE | No action |

### Y-Axis Convention

The user has experimentally established:
- **Positive Y = SEND**
- **Negative Y = RECEIVE**

This mapping is preserved after calibration. Calibration does not flip the convention; it adapts thresholds and directional signatures to the user's phone orientation.

### Detection Rules

- A single acceleration spike must NOT trigger a transfer.
- A complete recognized gesture pattern is required.
- A twist must not accidentally trigger SEND or RECEIVE.
- A SEND/RECEIVE gesture must not be interpreted as CANCEL.

## 5. Sensor Processing

### Raw Coordinate System

Raw sensor coordinates are kept separate from calibrated gesture coordinates. The raw accelerometer frame is Android's sensor coordinate system (X right, Y up, Z forward when phone is held naturally). Calibration maps this to the user's gesture frame.

### Processing Pipeline

```
Raw sensor event
    → Gravity estimation (low-pass filter on accelerometer)
    → Linear acceleration = raw − gravity
    → High-pass / impulse detection
    → Directional classification (positive/negative Y in calibrated frame)
    → Rotation detection (gyroscope integrated angular velocity)
    → Gesture classification with confidence scoring
```

### Sensor Sampling

- Accelerometer: `SENSOR_DELAY_NORMAL` or faster during active gesture sessions
- Gyroscope: `SENSOR_DELAY_NORMAL`
- Timestamps: nanoseconds from `SensorEvent.timestamp`
- No continuous background polling; sensor activation is visible to the user via foreground session

## 6. Calibration System

### Workflow

1. User holds phone in normal pointing position.
2. Record neutral sensor readings (2+ seconds).
3. Estimate baseline acceleration and noise floor.
4. User performs a SEND gesture (positive Y impulse).
5. User performs a RECEIVE gesture (negative Y impulse).
6. User performs a CANCEL twist (rotation).
7. Estimate gesture thresholds and directional signatures.
8. Validate that the three gestures are distinguishable.
9. Save calibration profile locally.

### Calibration Output

| Parameter | Description |
|-----------|-------------|
| baselineAccel | Neutral acceleration vector (X, Y, Z) |
| noiseFloor | Standard deviation of neutral readings |
| sendThreshold | Minimum calibrated Y impulse for SEND |
| receiveThreshold | Minimum calibrated Y impulse for RECEIVE |
| sendDirection | Calibrated direction signature for SEND |
| receiveDirection | Calibrated direction signature for RECEIVE |
| twistThreshold | Minimum integrated rotation for CANCEL |
| twistAxis | Detected rotation axis during calibration |

### Constraints

- Default profile is a starting point only, never a universal guarantee.
- Calibration data is stored locally; raw sensor traces are never uploaded.
- Recalibration is always available.
- Calibration validates gesture distinguishability before saving.

## 7. Gesture Recognition Engine

### Semantic Events

```
SEND, RECEIVE, CANCEL, IDLE, CALIBRATION_REQUIRED, LOW_CONFIDENCE
```

Each event includes:
- Gesture type
- Confidence score (0.0–1.0)
- Detection timestamp
- Relevant calibrated signal metrics

### State Machine

```
IDLE → GESTURE_DETECTED → CONFIRMED (emit event) → IDLE
          ↘
        REJECTED (return to IDLE, no event)
```

Terminal guard: events cannot overwrite terminal handoff states (handled by the controller).

### Confidence Scoring

Confidence is computed from:
- Impulse magnitude relative to calibrated threshold
- Direction alignment with calibrated signature
- Duration within [minDuration, maxDuration]
- Return-to-neutral detection
- Absence of conflicting signals (e.g., rotation during linear impulse)

## 8. False-Positive Protection

| Mechanism | Purpose |
|-----------|---------|
| Minimum confidence threshold | Reject weak/ambiguous movements |
| Minimum impulse duration | Filter out single spikes |
| Maximum impulse duration | Filter out sustained movements |
| Neutral baseline requirement | Ensure gesture starts from rest |
| Return-to-neutral detection | Ensure gesture completes cleanly |
| Cooldown between gestures | Prevent rapid-fire triggers |
| Duplicate event suppression | Ignore repeated detections of same gesture |
| Gesture refractory period | Brief window after a gesture where new gestures are suppressed |
| Sensor noise rejection | High-pass filtering removes low-frequency drift |
| Device movement rejection | Walking/bumping patterns don't match gesture signatures |
| Orientation-change detection | Reject if phone orientation shifts during gesture |

## 9. Safety and Handoff Integration

The gesture engine emits semantic events only. It does not execute networking or transfers.

### Gesture Controller (safety layer)

| Event | Precondition | Action |
|-------|--------------|--------|
| SEND | Staged content exists + trusted target selected | Route to ConnectionService.initiateSend |
| RECEIVE | Valid trusted target selected | Route to ConnectionService (mock for v4.3) |
| CANCEL | Active cancellable operation exists | Route to ConnectionService.cancelTransfer |
| Any | No target / no content / low confidence | Ignore + haptic feedback |

### Rules

- No target means no transfer.
- No staged content means no SEND.
- Low-confidence gestures are ignored.
- Repeated gestures cannot create duplicate transfers (cooldown + duplicate suppression).
- Require explicit arming or active controller session.
- Provide feedback for rejected gestures.

### Mock Controller (v4.3)

For this milestone, mock/test controllers are used where the real Rust connection integration is not yet available. Real cross-device transfer functionality is NOT claimed based on mocked operations.

## 10. Android Lifecycle

- Foreground gesture sessions: sensor active only during explicit gesture session
- Sensor registration/unregistration: tied to session lifecycle
- Activity lifecycle transitions: pause sensors on pause, resume on resume
- Screen-off behavior: session ends, sensors disabled
- Application background behavior: session ends, sensors disabled
- Battery-conscious sampling: normal delay during active session, off otherwise
- Foreground service: not required for v4.3 (session is short-lived and user-visible)
- No unrestricted background sensor polling

## 11. Motion Diagnostics UI

### Screens

**Gesture Diagnostics:**
- Accelerometer availability
- Gyroscope availability
- Current calibrated Y-axis value
- Neutral baseline
- Detected motion direction
- Gesture confidence
- Last recognized gesture
- Calibration status
- Current sensitivity

**Gesture Testing Mode:**
- Recognize gestures without initiating transfers
- Display detected action
- Provide haptic feedback
- Allow sensitivity adjustment

### Constraints

- No continuous sensor histories stored.
- Live visual indicator for positive and negative Y movement.

## 12. Security and Privacy

- No raw sensor traces uploaded.
- Calibration data stored locally only.
- Gesture events do not expose sensitive content.
- No additional permissions beyond existing sensor permissions.
- Existing Rust security architecture preserved (no crypto, pairing, or transfer reimplementation).

## 13. Integration Points with Existing Code

| Existing Component | v4.3 Integration |
|-------------------|------------------|
| `SensorManagerWrapper` | Reused as raw sensor source; extended with gesture-aware sampling |
| `ConnectionService` | Gesture controller routes SEND/RECEIVE/CANCEL to it |
| `StagedStore` | Gesture controller checks staged content availability |
| `PairingService` | Gesture controller checks trusted target availability |
| `TransferForegroundService` | Not used for v4.3 (session is user-visible and short-lived) |

## 14. Build Configuration

- No new dependencies beyond existing Kotlin coroutines and AndroidX
- All motion processing is pure Kotlin
- No NDK or Rust FFI changes required for v4.3
