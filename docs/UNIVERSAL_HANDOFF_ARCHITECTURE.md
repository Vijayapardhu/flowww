# Universal Spatial Handoff Architecture

**Version:** 1.0 (v4.0.2 milestone foundation)  
**Baseline:** flowww v4.0.1 (commit `d4c56bd`)  
**Scope:** Windows background foundation only. Android, motion gestures, and full spatial targeting are future milestones.

## 1. Goals

Transform flowww from a user-facing daemon into a universal, OS-integrated spatial content handoff system. The Windows desktop runs as a user-session background agent that:

- Starts at user sign-in with explicit opt-in.
- Runs without a mandatory main window.
- Manages the existing Rust `flowww-node::Node`.
- Stages content from registered providers (Explorer, browser, clipboard, etc.).
- Receives transfer commands from an Android companion.
- Exposes a local authenticated content-staging IPC interface.
- Detects the foreground application to resolve content context.
- Shows a system tray for configuration (not a mandatory transfer workflow).

## 2. Non-goals (v4.0.2)

- Android companion application.
- Motion/gesture sensing on the phone.
- Directional or UWB-based physical targeting.
- Browser extensions (Chromium/Firefox Native Messaging).
- Windows Explorer Shell context menu integration.
- Clipboard provider.
- Any network exposure of unauthenticated local IPC.

## 3. Process Model

```text
user-session background agent (flowww desktop, Tauri 2)
├── tray (system tray icon, configuration only)
│   ├── connection status
│   ├── trusted devices
│   ├── pause / resume
│   ├── settings
│   ├── diagnostics
│   └── exit
├── secure local IPC (authenticated, loopback / named pipe)
│   ├── stage_content
│   ├── inspect_staged_content
│   ├── initiate_send / initiate_receive
│   ├── get_active_target / select_target
│   ├── cancel_handoff / get_handoff_status / get_content_history
├── content provider abstraction (pluggable providers)
│   ├── foreground application detection
│   ├── registry of registered providers
│   └── provider 1+ placeholders (Explorer, browser, clipboard)
└── flowww-node::Node (authoritative for identity, trust, pairing, transfer)
```

The agent is **not** a Windows service. It is a user-session process owned by the signed-in user. It uses Tauri's `tray` subsystem and runs with no visible window by default.

## 4. Reuse of Existing Rust Core

The v4.0.1 ten-crate workspace is preserved unchanged:

| Crate | Role in v4.0.2 |
|-------|----------------|
| `flowww-core` | Content, device, transfer ID/state/route types; filename sanitization |
| `flowww-protocol` | FLW/1.0 message framing (not extended here) |
| `flowww-security` | Ed25519 identity, pinned mTLS, transfer authorization, replay guard |
| `flowww-storage` | SQLite devices/transfers/settings/nonces |
| `flowww-discovery` | mDNS browse/advertise (trusted-device hint) |
| `flowww-network` | Pinned TLS 1.3 transport, peer listener |
| `flowww-transfer` | Chunked send/receive, SHA-256, atomic `.partial` completion |
| `flowww-spatial` | Content vault, hold/aim/drop state machine (future integration point) |
| `flowww-node` | Single authority for identity, pairing, trust, transfer orchestration |
| `flowww-bridge` | Loopback HTTP/WebSocket bridge for local browser UI |

No second networking or transfer implementation is created. The node remains authoritative.

## 5. Content Staging Abstraction

A new content staging layer sits between providers and the node:

```
content-type: FILE | URL | TEXT | IMAGE | VIDEO | DOCUMENT | MULTIPLE_FILES
content-id, display-name, mime-type, size, source-application,
created-at, expires-at, content-reference, authorization-context
```

Staged content is stored in a short-lived staging store (in-memory with optional SQLite backing). Large file contents are never placed in event messages or DTOs; only handles/references are exposed.

## 6. Secure Local IPC

The IPC interface is local-only and authenticated:

- Binds to loopback (or Windows named pipe) only.
- Requires a bearer token or equivalent per-request authentication.
- Validates host/origin where applicable.
- Never exposes private keys, signed capabilities, bearer tokens, absolute paths, opaque handles, or raw internal errors.
- Does not expose file contents inline; returns content descriptors/references only.

## 7. Foreground Application Detection

Windows-only. Detects the active foreground window and its process to identify the source application for content resolution (browser tab, Explorer selection, etc.). Implementation is best-effort and never promises universal arbitrary-application selection extraction.

## 8. System Tray

The tray is the primary user-facing surface when no window is open:

- Connection status (node online / paired devices count).
- Trusted devices list.
- Pause/resume (halt new transfers without shutting down node).
- Settings (auto-start, download dir, incoming policy).
- Diagnostics (minimal, no sensitive content).
- Exit (clean node shutdown).

Tray is for configuration, not a mandatory transfer workflow.

## 9. Startup Configuration

Explicit opt-in via user settings. When enabled, the agent registers itself to start at user sign-in (Windows startup folder / run key, not a system service). The user can disable this at any time from the tray settings.

## 10. Handoff State Machine (interface foundation)

The v4.0.2 layer defines stable state types for the full handoff state machine (implemented in later milestones):

```
IDLE -> TARGET_SELECTED -> CONTENT_DETECTED -> CONTENT_STAGED ->
GESTURE_DETECTED -> TRANSFER_REQUESTED -> AWAITING_APPROVAL ->
TRANSFERRING -> VERIFYING -> COMPLETED
                      \-> CANCELLED / FAILED / EXPIRED (from any active state)
```

Terminal states (COMPLETED, CANCELLED, FAILED, EXPIRED) cannot transition back. Duplicate gestures and replayed commands are prevented at the IPC layer.

## 11. Security Boundaries

- Explicit pairing and trusted-device management (existing node).
- Pinned mutual TLS for device-to-device transfers (existing node).
- Local IPC is authenticated and loopback/named-pipe only.
- Content expiration for staged items.
- Filename sanitization and safe temporary storage (existing core).
- No automatic clipboard upload; no silent background transfer of arbitrary files.
- Minimal diagnostics without sensitive content.

## 12. Integration Points for Future Milestones

| Milestone | Integration point established in v4.0.2 |
|-----------|-----------------------------------------|
| v4.1 Explorer | Provider registration trait + Explorer provider placeholder |
| v4.1 Browser | Native Messaging IPC endpoint + browser provider placeholder |
| v4.1 Clipboard | Clipboard provider placeholder |
| v4.2 Android | `initiate_receive` API + target selection interface |
| v4.3 Gestures | Gesture event sink (`SEND`/`RECEIVE`/`CANCEL`/`IDLE`) interface |
| v4.5 Spatial | `get_active_target` / `select_target` + spatial registry endpoint |
