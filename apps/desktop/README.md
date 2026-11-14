# flowww desktop foundation

This directory contains the separate Tauri 2 + React + TypeScript desktop
shell. It does not replace the embedded browser UI. The Tauri Rust host owns a
single existing `flowww-node::Node`; TypeScript receives sanitized DTOs and
never creates capabilities, commands, identities, or transfer authorizations.

## Development

Prerequisites:

- Node.js 20+ and npm;
- Rust 1.82+;
- Windows WebView2 for a native Windows run;
- the Tauri CLI installed through the local npm dev dependency.

```powershell
cd apps/desktop
npm install
npm run typecheck
npm test
npm run build
npx tauri dev
```

The Rust host can be checked independently:

```powershell
cargo check --manifest-path apps/desktop/src-tauri/Cargo.toml
```

Frontend checks pass with `npm run typecheck`, `npm test`, and `npm run build`.
The host's Rust unit test target is present, but this GNU Windows environment
cannot link the Tauri test binary because its linker reports `export ordinal too
large`; use a supported MSVC Windows toolchain for native Tauri test execution.

The nested `src-tauri` manifest has its own workspace table so the existing
root `cargo test --workspace` continues to cover the ten engine crates without
pulling the desktop shell into the daemon workspace.

## Boundary

The command layer owns node initialization, pairing, sanitized device views,
transfer history, active status, cancellation, and shutdown. Events are emitted
through the Tauri event channel as sanitized transfer/peer/pairing DTOs.
Private keys, signed capabilities, opaque handles, local paths, inline content,
and the loopback bridge token are not exposed to the frontend.

This is a development foundation, not a production installer. Bundling is
disabled until Windows packaging, signing, installation, upgrade, and uninstall
are tested on a Windows machine.
