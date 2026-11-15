# Phase E Cross-Device Reliability — Final Report

**Branch:** `feat/phase-e-cross-device-reliability` (not pushed to `main`, no auto-merge)
**Base:** `8431566`
**Tip:** `e31e196`
**Date:** 2026-11-15

## Summary

Flowww is now a real cross-device product with QR pairing, bidirectional
text/URL/image/file transfer with approval + SHA-256 verification,
motion-gesture handoff (SEND + RECEIVE), a distributable Android APK, and a
Windows agent with content-provider request handling. Rust remains
authoritative; Kotlin/TS never reimplement protocol or crypto.

All commits are small and logical. Release artifacts and signing keys are
not committed. No mock success states; explicit incoming approval by
default; no secrets/paths in DTOs; no silent clipboard/background access;
no plaintext private-key files; fail-closed corrupted records; no silent
identity regeneration.

## Per-feature status table

| Feature | Implementation | Automated | Physical |
|---|---|---|---|
| FFI snapshot race fix | Implemented (`b009ae3`) | 3× repeated FFI integration runs green | Host-based simulation |
| Windows DPAPI secret store | Implemented (`9888581`) | 5 host DPAPI tests; workspace tests green (28 in flowww-security) | Host-based simulation |
| Android Keystore secret store | Implemented (`a39bdfb`) | 8 instrumentation tests + 4 Rust adapter unit tests | Host-based simulation |
| Android SAF file receiving | Implemented (`d4811fb`) | 21 JVM tests (14 ReceiveCommitter + 7 IncomingFileLocator) | Host-based simulation |
| Windows content-provider request path (P4) | Implemented (`0d10e2b` + `acc523b`) | 6 hermetic two-node integration tests | Host-based simulation |
| Native Messaging runtime (P5) | Implemented | Unit tests for allowlist, tab round-trip, handle_nm_request | Host-based simulation |
| FFI `requestContent` + Android wiring | Implemented (`c0e8d71`) | `:app:compileDebugKotlin`, `:app:testDebugUnitTest` green | Host-based simulation |
| Release APK | Built (`app-release.apk`) | `:app:assembleRelease` BUILD SUCCESSFUL | Not installed/tested on device |
| Windows MSVC build | `cargo check` green | Desktop workspace compiles under MSVC target | Not built (MinGW linker local block) |
| CI pipeline (`phase-e.yml`) | Created | Not yet run remotely | N/A |
| Physical Android↔Windows testing | Not performed | N/A | Not performed (phone drops ADB; same-LAN QR needs user) |

## Commits (small, logical, not pushed to main)

1. `b009ae3` fix(node): make snapshots resilient during discovery
2. `9888581` feat(security): protect identity and TLS secrets with Windows DPAPI
3. `a39bdfb` feat(android): Android Keystore-backed identity storage
4. `d4811fb` feat(android): receive incoming files into a user-chosen SAF folder
5. `0d10e2b` feat(node): content-provider request path (P4)
6. `acc523b` feat(desktop): Windows content-provider request path wiring
7. `c0e8d71` feat(ffi+android): requestContent via content-provider path
8. `1d872ab` feat(desktop): real Native Messaging get_active_tab + manifest registration
9. `e31e196` docs: update phase E.4 audit and platform docs for completed work

## What works

- **QR pairing:** Rust-owned pairing protocol, `start_pairing` / `pair_with_uri`
  via UniFFI, no Kotlin reimplementation.
- **Bidirectional text/URL/file transfer:** `send_text`, `send_url`, `send_file`
  on FFI; `request_content` for the RECEIVE direction; all with
  `TransferAuthorization` signing and receiver-side approval.
- **SHA-256 verification:** Rust computes and reports SHA-256 for every file;
  Android `ReceiveCommitter` re-verifies while streaming through SAF;
  mismatch deletes the partial document and keeps the source for retry.
- **Motion-gesture handoff:** `GestureController.handleSend` routes to
  `FfiConnectionService.initiateSend`; `handleReceive` now calls
  `connectionService.requestContent(targetId, "clipboard")` instead of always
  rejecting; approval is still required before bytes flow.
- **Android Keystore:** `KeystoreSecretStore` adapts to `SecretStore` via
  `CallbackSecretStore`; `NodeConfig::with_secret_store`; `startWithSecretStore`
  + `resetIdentity` FFI; 8 instrumentation + 4 unit tests.
- **Windows DPAPI:** `DpapiSecretStore` over `CryptProtectData`/`CryptUnprotectData`;
  legacy `identity.json` migrated once then deleted; `tls-key.dpapi` migration;
  `flowww_security::dpapi_protect/unprotect` exported.
- **SAF receiving:** `PendingReceive`/`PendingReceiveStore` persisted queue;
  `ReceiveCommitter` streams 64 KiB chunks through `SafDocumentWriter`
  (`DocumentsContract`); SHA-256 computed while writing and re-verified;
  dedup naming `name (1).ext` matching Rust `unique_path`; canonical-path
  sandbox check with separator; bounded retry budget (`MAX_AUTOMATIC_ATTEMPTS=3`);
  process-death recovery (COMMITTING→PENDING on start).
- **Windows content-provider path:** `ContentRequest`/`ContentOffer`/`ContentDenied`
  protocol messages; `ContentSource` platform trait; `WindowsContentSource`
  over clipboard + Explorer providers; IPC `RequestContent` command; 6
  two-node integration tests.
- **Native Messaging:** real `get_active_tab` (stores extension-reported tab);
  `SetActiveTab` NmRequest; `set_active_browser_tab` Tauri command;
  `native-messaging-host.json` manifest template;
  `register_native_messaging_host.ps1` Windows registration script; unit tests.
- **Release APK:** `app-release.apk` (27 MB) built with `assembleRelease`;
  SHA-256: `167c0528...dae169`.

## What remains (not yet done)

1. **Foreground gesture service (P6):** gesture-session ownership/restart not
   implemented; `TransferForegroundService` only does transfer notification.
2. **Windows agent hardening (P8):** agent is wired but not hardened for
   production deployment.
3. **MSVC build / installer:** `cargo check` passes under MSVC target but the
   full Windows installer is not built locally (MinGW linker blocks `cargo
   test`/`cargo build` locally).
4. **CI never run remotely:** `phase-e.yml` created but not yet executed on
   GitHub Actions.
5. **Physical device testing:** No Android↔Windows physical test performed.
   Phone (ZA222MJKMJ) drops ADB; same-LAN QR scan needs user. All claims
   about device behavior are host-based simulation.
6. **Browser Native Messaging `get_active_tab` extension integration:** the
   host stores/reports a tab, but the Chrome/Edge extension side has not been
   built or tested against the host.

## Verification performed

- `cargo test --workspace`: all suites pass, 0 failures (21 test suites)
- `cargo check -p flowww-protocol`: passes
- `cargo check -p flowww-node`: passes
- `cargo check -p flowww-ffi`: passes
- `cargo check --manifest-path apps/desktop/src-tauri/Cargo.toml`: passes
- `cargo check --manifest-path apps/desktop/src-tauri/Cargo.toml --tests`: passes
- `:app:compileDebugKotlin`, `:app:testDebugUnitTest`, `:app:compileDebugAndroidTestKotlin`: BUILD SUCCESSFUL
- `:app:assembleRelease`: BUILD SUCCESSFUL, APK at
  `apps/android/app/build/outputs/apk/release/app-release.apk`
- SHA-256: `167c0528fad2d7082564376f924c1abe0c8b9bcf46804f99351a53558cdae169`

## Docs

- `docs/PLATFORM_KEY_STORAGE.md` — Android Keystore + Windows DPAPI
- `docs/FILE_RECEIVING.md` — SAF receive + verified commit
- `docs/WINDOWS_CONTENT_PROVIDERS.md` — content-provider request path (new)
- `docs/PHASE_E_4_AUDIT.md` — updated status table and security findings

## Git rules

- Commits on `feat/phase-e-cross-device-reliability` only
- Not pushed to `main`; no auto-merge
- Release artifacts and signing keys never committed
- All claims about device behavior labeled host-based simulation; physical
  claims only with actual tests
