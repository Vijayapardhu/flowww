# Desktop Runtime Next Steps — v4.0.3 Windows Runtime Hardening

This document records the implementation sequence and completion gates for **v4.0.3 — Windows Runtime Hardening**.

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

Record startup, repeated initialization, single-instance enforcement, tray menu operation, auto-start opt-in, local IPC authentication, node shutdown, process termination, and foreground detection. Do not mark the runtime validated from `cargo check` alone.

## Implementation Sequence (v4.0.3)

1. Replace in-memory single-instance tracking with OS-level Windows named mutex (`SingleInstanceLock`).
2. Replace foreground detection placeholder with real Windows APIs (`GetForegroundWindow`, `GetWindowThreadProcessId`, `OpenProcess`, `QueryFullProcessImageNameW`, `GetWindowTextW`, `GetClassNameW`).
3. Harden startup behavior: idempotent enable/disable, registry validation, graceful failure when registry access fails.
4. IPC security hardening: loopback-only binding, per-connection rate limiting, request size limits, sanitized errors, constant-time token comparison, IPC token not exposed via Tauri commands.
5. Add lifecycle tests: first startup, duplicate startup, shutdown, restart, Node init failure, IPC failure, tray init failure, startup validation.
6. Update `NEXT_STEPS.md`.
7. Run `cargo check`, `cargo clippy`, `npm run typecheck`, `npm test`, `npm run build`.

## Release Gates

- No frontend DTO contains a key, capability, opaque handle, path, token, or inline content.
- IPC token is never exposed via Tauri commands or frontend DTOs.
- Local IPC is authenticated and loopback-only; no unauthenticated network exposure.
- IPC enforces per-connection rate limiting and request size limits.
- All IPC errors are sanitized; no internal details leaked.
- Single-instance enforced via OS-level named mutex; second instance exits cleanly.
- Agent starts at user sign-in only with explicit opt-in.
- Startup enable/disable is idempotent; graceful when registry access fails.
- Foreground detection returns sanitized DTOs (no screen contents, no keystrokes).
- Tray menu operates connection status, trusted devices, pause/resume, settings, diagnostics, exit.
- Node initialization and cleanup are idempotent and leak-free.
- Runtime event loss triggers snapshot resynchronization.
- Shutdown leaves no node or event-forwarding task running.
- Physical Windows validation is recorded separately from loopback tests.

## Next Milestone

**v4.1 — Windows Content Integrations:**
- Explorer file integration (Shell context menu / selection).
- Chromium browser extension + Native Messaging.
- Clipboard provider.
- Active URL and selected text staging.
- Default application launching.
