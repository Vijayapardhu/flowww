# Implementation Plan — v4.0.2 Windows Background Foundation

**Baseline:** flowww v4.0.1 (commit `d4c56bd`)  
**Milestone:** v4.0.2 — Windows Background Foundation  
**Constraint:** Do not implement Android, motion gestures, or full spatial targeting in this milestone. Establish stable interfaces for future integration.

## 1. Milestone Objectives

| Objective | Description |
|-----------|-------------|
| Background agent lifecycle | User-session process, starts at sign-in with opt-in, runs without main window, clean shutdown. |
| Single-instance behavior | Only one agent instance per user session at a time. |
| Startup configuration | Explicit opt-in to launch at Windows user sign-in. |
| System tray | Connection status, trusted devices, pause/resume, settings, diagnostics, exit. |
| Secure local IPC | Authenticated, loopback/named-pipe content-staging interface; no network exposure without auth. |
| Content provider abstraction | Pluggable provider trait, registry, typed staged-content model. |
| Foreground application detection | Windows foreground window/process detection for content context. |
| Existing Rust Node integration | Reuse `flowww-node::Node` as the single authority; no second networking/transfer impl. |

## 2. Deliverable Files

### New files

| File | Purpose |
|------|---------|
| `docs/UNIVERSAL_HANDOFF_ARCHITECTURE.md` | Architecture overview and integration points. |
| `apps/desktop/IMPLEMENTATION_PLAN.md` | This plan. |
| `apps/desktop/NEXT_STEPS.md` | v4.0.2 completion gates and manual validation steps. |
| `apps/desktop/src-tauri/src/agent/mod.rs` | Agent module entrypoint and lifecycle. |
| `apps/desktop/src-tauri/src/agent/lifecycle.rs` | Startup/shutdown/single-instance orchestration. |
| `apps/desktop/src-tauri/src/agent/tray.rs` | System tray menu and handlers. |
| `apps/desktop/src-tauri/src/agent/startup.rs` | Windows sign-in auto-start opt-in (registry/startup folder). |
| `apps/desktop/src-tauri/src/agent/ipc.rs` | Authenticated local IPC server (loopback or named pipe). |
| `apps/desktop/src-tauri/src/agent/providers/mod.rs` | Content provider trait, registry, staged-content model. |
| `apps/desktop/src-tauri/src/agent/providers/staged.rs` | Staged content store (in-memory + expiry). |
| `apps/desktop/src-tauri/src/agent/providers/registry.rs` | Provider registration and lookup. |
| `apps/desktop/src-tauri/src/agent/foreground.rs` | Windows foreground application detection. |
| `apps/desktop/src-tauri/src/agent/config.rs` | Agent settings persistence (auto-start, download dir, policy). |
| `apps/desktop/src-tauri/src/agent/api.rs` | Typed IPC/tauri command contracts for staging/handoff. |
| `apps/desktop/src-tauri/src/lib.rs` | Updated to integrate agent, tray, IPC, provider abstraction. |
| `apps/desktop/src-tauri/Cargo.toml` | New dependencies (tray, IPC, Windows API). |
| `apps/desktop/src-tauri/tauri.conf.json` | Tray config, no default window, security policy. |
| `apps/desktop/src-tauri/tests/agent_tests.rs` | Integration tests for agent lifecycle, IPC, providers. |

### Modified files

| File | Change |
|------|--------|
| `apps/desktop/src-tauri/src/lib.rs` | Add agent state, tray, IPC, provider registry; new commands. |
| `apps/desktop/Cargo.toml` | No change (frontend unchanged for v4.0.2). |
| `apps/desktop/package.json` | No change. |

## 3. Dependency Plan

Add to `apps/desktop/src-tauri/Cargo.toml`:

- `tauri` (already present, v2) — tray, window management, commands, events.
- `tauri-plugin-single-instance` or a `std::sync::Mutex`-based named mutex for single-instance enforcement.
- `windows` crate (targeted `Win32_System_Threading`, `Win32_UI_WindowsAndMessaging`, `Win32_Foundation`) — foreground window detection, startup registration.
- `serde`, `serde_json` (already present) — config serialization.
- `tokio` (already present) — async IPC server.
- `tracing` (already present) — minimal diagnostics.

Note: `windows` crate adds significant compile time; gate it behind `#[cfg(target_os = "windows")]`.

## 4. Work Sequence

### Phase 1 — Docs and scaffolding (this turn)
1. Write `docs/UNIVERSAL_HANDOFF_ARCHITECTURE.md`. (done)
2. Write `apps/desktop/IMPLEMENTATION_PLAN.md`. (this file)
3. Write `apps/desktop/NEXT_STEPS.md`.

### Phase 2 — Agent core
4. Add `agent/config.rs` — settings persistence via `flowww-storage` settings table or a local JSON file.
5. Add `agent/lifecycle.rs` — `Agent` struct owning `Node`, IPC server, tray handle; start/shutdown/single-instance.
6. Add `agent/tray.rs` — tray menu build + handlers (status, trusted devices, pause/resume, settings, diagnostics, exit).
7. Add `agent/startup.rs` — Windows auto-start opt-in (registry `HKCU\Software\Microsoft\Windows\CurrentVersion\Run` or startup folder).

### Phase 3 — IPC and providers
8. Add `agent/providers/mod.rs`, `staged.rs`, `registry.rs` — provider trait, staged content model, registry.
9. Add `agent/ipc.rs` — authenticated local IPC server with typed staging/handoff endpoints.
10. Add `agent/foreground.rs` — foreground window/process detection (Windows only).
11. Add `agent/api.rs` — typed DTOs and command contracts: `stage_content`, `inspect_staged_content`, `initiate_send`, `initiate_receive`, `get_active_target`, `select_target`, `cancel_handoff`, `get_handoff_status`, `get_content_history`.

### Phase 4 — Tauri integration
12. Update `lib.rs` — manage `Agent` state, register new commands, wire tray + IPC + event forwarding.
13. Update `tauri.conf.json` — tray, no default window, CSP/security.
14. Update `Cargo.toml` — new dependencies.

### Phase 5 — Tests
15. Add unit tests: single-instance, startup/shutdown, node init/cleanup, IPC auth, content staging/expiry, invalid content, provider registration, foreground detection (mocked), event sync, trusted-device validation.
16. Add integration test file `apps/desktop/src-tauri/tests/agent_tests.rs`.

### Phase 6 — Verification
17. `cargo check` on desktop crate (and workspace crates).
18. `cargo clippy` where toolchain permits.
19. `npm run typecheck`, `npm test`, `npm run build` in `apps/desktop`.
20. Report actual results; do not claim Windows runtime validation.

## 5. API Contracts (typed, sanitized DTOs)

```
stage_content(content) -> StagedContentDto { content_id, type, display_name, mime_type, size, source_app, created_at, expires_at, reference, auth_context }
inspect_staged_content(content_id) -> StagedContentDto | NotFound
initiate_send(target_device_id, content_id, action) -> TransferRecordDto
initiate_receive(content_reference, source_device_id) -> TransferRecordDto (requires approval unless trusted policy)
get_active_target() -> DeviceRefDto | None
select_target(device_id) -> TargetSelectionDto
cancel_handoff(transfer_id) -> CancellationDto
get_handoff_status(transfer_id) -> TransferStatusDto
get_content_history() -> Vec<TransferRecordDto>
```

Never expose: private keys, signed capabilities, bearer tokens, absolute paths, opaque handles, raw internal errors, sensitive content.

## 6. Security Implications

- Local IPC bound to loopback/named pipe only, bearer-token authenticated.
- No unauthenticated network exposure.
- Staged content expires; large file contents never in DTOs.
- Filename sanitization and safe temp storage via existing `flowww-core`/`flowww-transfer`.
- Diagnostics exclude keys, tokens, paths, content.

## 7. Known Limitations

- No native Windows runtime validation claimed (MSVC `link.exe` unavailable in this environment).
- Foreground detection is best-effort; does not promise universal arbitrary-app selection extraction.
- No browser extension, Explorer Shell, or clipboard provider implementations in v4.0.2 (placeholders only).
- No physical-device or Android testing claimed.

## 8. Exit Criteria

- `cargo check --manifest-path apps/desktop/src-tauri/Cargo.toml` passes.
- `cargo clippy --manifest-path apps/desktop/src-tauri/Cargo.toml` passes (if toolchain permits).
- `npm run typecheck`, `npm test`, `npm run build` pass in `apps/desktop`.
- New agent tests pass.
- Three docs created.
