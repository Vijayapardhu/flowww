# Desktop Runtime Next Steps — v4.1 Universal Content Integration

This document records the implementation sequence and completion gates for **v4.1 — Universal Content Integration**.

## What was implemented in v4.1

- Content provider abstraction with lifecycle tracking (CREATED → STAGED → RESERVED → TRANSFERRED, terminal EXPIRED/CANCELLED/FAILED)
- Chromium Manifest V3 browser extension (Chrome/Edge)
- Native Messaging host for secure extension-to-agent communication
- Browser provider: active tab URL, page title, selected text staging
- Windows Explorer provider: Shell context menu integration, file validation
- Clipboard provider: explicit trigger only, text/URL/image support
- Default application launching: URL/file/image opening with safety validation
- Typed Tauri commands: get_active_browser_tab, stage_active_browser_tab, stage_selected_text, stage_selected_files, stage_clipboard_content, get_staged_content, expire_staged_content
- Provider registry with initialize_all/shutdown_all

## Required Windows Validation

Run on a supported Windows machine with the MSVC Rust toolchain, Visual C++ Build Tools (including `link.exe`), and WebView2:

```powershell
rustup default stable-x86_64-pc-windows-msvc
cd apps/desktop
npm install
npm run typecheck
npm test
npm run build
npx tauri dev
```

### Browser extension installation

1. Load the unpacked extension from `apps/desktop/browser-extension` in Chrome/Edge (chrome://extensions → Developer mode → Load unpacked)
2. Register the Native Messaging host using `apps/desktop/native-messaging-host/register-host.ps1` (run as Administrator)
3. Replace `<REPLACE_WITH_ACTUAL_EXTENSION_ID>` with the actual extension ID from chrome://extensions
4. Install `flowww-native-host.exe` at `C:\Program Files\Flowww\flowww-native-host.exe`

### Explorer context menu registration

Registry keys are registered during installer setup (not at runtime):

```
HKEY_CLASSES_ROOT\*\shell\FlowwwStage\command
HKEY_CLASSES_ROOT\Directory\shell\FlowwwStage\command
```

## Implementation Sequence (v4.1)

1. Provider interface and lifecycle (done)
2. Chromium extension (done)
3. Native Messaging host (done)
4. Active tab retrieval (done)
5. URL and selected-text staging (done)
6. Windows Explorer integration (done)
7. Clipboard integration (done)
8. Default application launching (done)
9. Integration tests (done)
10. Security review and documentation (done)

## Release Gates

- No frontend DTO contains a key, capability, opaque handle, path, token, or inline content.
- IPC token is never exposed via Tauri commands or frontend DTOs.
- Native Messaging host validates extension IDs against allowlist.
- Local IPC is authenticated and loopback-only; no unauthenticated network exposure.
- IPC enforces per-connection rate limiting and request size limits.
- All IPC errors are sanitized; no internal details leaked.
- Single-instance enforced via OS-level named mutex.
- Agent starts at user sign-in only with explicit opt-in.
- Startup enable/disable is idempotent; graceful when registry access fails.
- Foreground detection returns sanitized DTOs (no screen contents, no keystrokes).
- Explorer provider validates file existence, type, size; prevents path traversal.
- Clipboard provider never continuously monitors; explicit trigger only.
- Default launcher validates URL schemes (http/https only); rejects dangerous schemes.
- Staged content lifecycle enforces expiration and transfer authorization.
- Tray menu operates connection status, trusted devices, pause/resume, settings, diagnostics, exit.
- Node initialization and cleanup are idempotent and leak-free.
- Runtime event loss triggers snapshot resynchronization.
- Shutdown leaves no node or event-forwarding task running.
- Physical Windows validation is recorded separately from loopback tests.

## Next Milestone

**v4.2 — Android Companion:**
- Native Android application (Kotlin/Jetpack Compose)
- Sharesheet integration (ACTION_SEND, ACTION_SEND_MULTIPLE)
- File and content staging via ContentResolver
- Trusted desktop pairing
- Transfer status interface
