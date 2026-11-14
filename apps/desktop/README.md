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
The host's Rust unit test target is present. In the current environment, the
GNU linker reports `export ordinal too large`, while the installed MSVC target
cannot find Visual Studio's `link.exe`. Native Tauri unit/runtime execution
therefore remains pending on a machine with Visual C++ Build Tools installed.

The nested `src-tauri` manifest has its own workspace table so the existing
root `cargo test --workspace` continues to cover the ten engine crates without
pulling the desktop shell into the daemon workspace.

## Boundary

The command layer owns node initialization, pairing, sanitized device views,
transfer history, active status, cancellation, and shutdown. Events are emitted
through the Tauri event channel as sanitized transfer/peer/pairing DTOs.
Private keys, signed capabilities, opaque handles, local paths, inline content,
and the loopback bridge token are not exposed to the frontend.

Initialization is serialized and idempotent. One event-forwarding task is owned
by the application state, has an explicit cancellation signal, and does not
capture a `Node` clone. Lagged event receivers emit a resync event; the
frontend then reloads authoritative snapshots. Application exit and explicit
shutdown both cancel the forwarder and shut down the node idempotently.

The cancellation command is explicitly limited to transfers for which the
local controller has a live spatial cancellation operation. Unsupported or
already-terminal transfers return `accepted: false` with a public error code;
the desktop does not report cancellation success for ordinary transfers that
the current node API cannot cancel.

## Dependency audit

The current `npm audit` report contains six findings in development tooling:

| Package | Dependency path | Severity | Disposition |
| --- | --- | --- | --- |
| `vitest` | direct dev dependency | critical | Tests use `vitest run`, not the vulnerable UI server. The available audit fix is Vitest 5, a major upgrade, so it is deferred pending compatibility validation. |
| `@vitest/mocker` | Vitest transitive dependency | moderate | Same development-only path; no compatible non-major fix reported. |
| `tinypool` | Vitest transitive dependency | critical | Test worker dependency only; no compatible non-major fix reported. |
| `vite-node` | Vitest transitive dependency | moderate | Development/test-only; major Vitest upgrade required by the reported fix. |
| `vite` | Vitest nested dependency | high | The production build uses the patched direct Vite resolution; the vulnerable nested resolution is pulled by the current Vitest line. |
| `esbuild` | Vite/Vitest transitive dependency | moderate | Development server/build tooling only; addressed by the same future toolchain upgrade. |

None of these packages are bundled into the native runtime payload as an
application service. Do not expose Vite or Vitest servers to a LAN. Re-run
`npm audit` after any toolchain upgrade; do not use `npm audit fix --force`
without checking the Tauri/Vite compatibility matrix.

This is a development foundation, not a production installer. Bundling is
disabled until Windows packaging, signing, installation, upgrade, and uninstall
are tested on a Windows machine.
