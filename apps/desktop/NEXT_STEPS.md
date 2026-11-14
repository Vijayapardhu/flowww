# Desktop runtime next steps

This document records the process after the v4.0.1 foundation hardening.

## Required Windows validation

Run on a supported Windows machine with the MSVC Rust toolchain, Visual C++
Build Tools (including `link.exe`), and WebView2:

```powershell
rustup default stable-x86_64-pc-windows-msvc
cd apps/desktop
npm install
npm run typecheck
npm test
npm run build
npx tauri dev
```

Record startup, repeated initialization, pairing, discovery, event delivery,
window close, explicit shutdown, reinitialization, and process termination.
Do not mark the runtime validated from `cargo check` alone.

## Implementation sequence

1. Add Windows runtime tests around the Tauri command/event boundary.
2. Add a complete transfer command surface using existing Node APIs, with
   explicit cancellation support for each transfer type.
3. Add file-picker and drag/drop flows only after authoritative transfer state
   restoration is covered.
4. Validate desktop/browser-controller coexistence and restart recovery.
5. Enable packaging only after installer, uninstall, upgrade, signing, and
   clean-machine tests pass.

## Release gates

- No frontend DTO contains a key, capability, opaque handle, path, token, or
  inline content.
- Every privileged operation has a node-backed authorization path.
- Runtime event loss triggers snapshot resynchronization.
- Shutdown leaves no node or event-forwarding task running.
- Physical Windows validation is recorded separately from loopback tests.
