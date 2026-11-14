# Content Integration Architecture — v4.1

**Version:** 1.0 (v4.1 milestone)  
**Baseline:** flowww v4.0.3 (commit `0e67acb`)  
**Scope:** Universal content acquisition and staging for Windows background agent.

## 1. Goals

Transform the flowww Windows background agent into a universal content acquisition layer that quietly identifies and stages content from the user's normal applications without requiring a dashboard, file browser, or manual device selection.

Users continue working normally in:
- Windows Explorer
- Google Chrome / Microsoft Edge
- Other applications exposing content through supported OS interfaces

## 2. Non-goals (v4.1)

- Android companion application
- Motion/gesture sensing on the phone
- Directional or UWB-based physical targeting
- Automatic background transfers without explicit user action

## 3. Process Model

```text
user-session background agent (flowww desktop, Tauri 2)
├── tray (system tray icon, configuration only)
├── secure local IPC (authenticated bearer token, loopback TCP)
│   └── Native Messaging host (browser extension bridge)
│   └── Typed Tauri commands (Explorer, clipboard, etc.)
├── content provider registry (pluggable providers)
│   ├── BrowserProvider (via Native Messaging)
│   ├── ExplorerProvider (via Shell context menu)
│   └── ClipboardProvider (explicit trigger only)
├── staged content lifecycle store (TTL-based)
└── flowww-node::Node (authoritative for identity, trust, pairing, transfer)
```

The agent remains a **user-session process** — not a Windows service. The tray is for configuration only, not a mandatory transfer workflow.

## 4. Content Staging Lifecycle

```
CREATED → STAGED → RESERVED → TRANSFERRED
              ↘      ↘      ↘
            EXPIRED CANCELLED FAILED (terminal)
```

| State | Meaning |
|-------|---------|
| CREATED | Content identified but not yet staged |
| STAGED | Content reference stored, ready for handoff |
| RESERVED | Content reserved for an active transfer |
| TRANSFERRED | Content successfully transferred |
| EXPIRED | Content expired before transfer (terminal) |
| CANCELLED | Content cancelled before transfer (terminal) |
| FAILED | Content staging or transfer failed (terminal) |

### Rules

- A content item must not become transferable merely because it exists in temporary storage — it requires an authorized handoff request.
- Content expiration is enforced with configurable retention.
- Duplicate content detection prevents re-staging identical references.
- Concurrent staging is safe via the staged store's internal locking.
- Large file contents are never placed in event messages or DTOs — only opaque references.

## 5. Content Provider Interface

### Trait

Each provider implements a consistent async interface:

```rust
pub trait ContentProvider: Send + Sync {
    fn name(&self) -> &str;
    async fn initialize(&self) -> Result<(), ProviderError>;
    fn is_available(&self) -> bool;
    async fn get_current_content(&self) -> Result<Option<CurrentContentDto>, ProviderError>;
    async fn stage_content(&self, payload: StagePayload) -> Result<StageResult, ProviderError>;
    async fn shutdown(&self) -> Result<(), ProviderError>;
}
```

### Provider Types

| Provider | Source | Integration Method |
|----------|--------|-------------------|
| BrowserProvider | Chrome/Edge active tab, selected text | Native Messaging host |
| ExplorerProvider | Selected files in Explorer | Shell context menu |
| ClipboardProvider | Clipboard contents | Explicit trigger (tray/shortcut) |

## 6. Native Messaging Bridge (Browser → Agent)

The Chromium extension communicates with the Windows agent through a **Native Messaging host** — never directly to the local HTTP IPC endpoint.

```
Browser Extension → Native Messaging Host (stdio) → Windows Agent (IPC)
```

### Security Properties

- Native Messaging host validates extension ID against an allowlist
- Strict message schema validation on both sides
- Request/response IDs for correlation
- Message size limits (1 MiB)
- Timeout handling (30s)
- Connection recovery on host restart
- Sanitized error responses (no internal details)
- IPC bearer token is **never** exposed to the browser extension

### Extension → Host Messages

| Message | Purpose |
|---------|---------|
| `get_active_tab` | Retrieve active tab URL, title, favicon |
| `stage_active_tab` | Stage current page for handoff |
| `stage_selected_text` | Stage selected text for handoff |
| `get_staged_content` | Query staged content status |
| `ping` | Connection health check |

### Host → Agent Messages

The host forwards validated requests to the agent's IPC interface using the IPC bearer token (which only the host possesses).

## 7. Provider Implementations

### BrowserProvider

- Communicates with extension via Native Messaging host
- Supports active tab URL, page title, selected text, optional selected image URL
- User-triggered staging only (no silent/continuous collection)
- `activeTab` and `nativeMessaging` permissions only

### ExplorerProvider

- Shell context menu integration (`IExplorerCommand` / context menu verb)
- Supports single and multiple file selection
- Validates file existence, type, size limits
- Prevents path traversal, sanitizes filenames
- Handles inaccessible/locked files gracefully
- Does not load file contents into memory — stores references only

### ClipboardProvider

- Explicit trigger only (tray action or configurable shortcut)
- Never continuously monitors clipboard
- Supports plain text, URLs, images
- Applies size limits, sanitizes metadata
- Never logs clipboard contents

## 8. Default Application Launching

A safe Windows content-opening abstraction:

- URLs → default browser (`ShellExecuteEx` with `open` verb)
- Documents → registered default application
- Images → configured image viewer
- Other files → normal Windows file associations

Security:
- Shell command injection prevention
- URL scheme validation (http/https only for browser launch)
- Reject dangerous executable/script launching
- Never automatically execute downloaded files
- Safe process-launch APIs (`CreateProcess` with explicit parameters)

## 9. Security Boundaries

- Local IPC bound to loopback only, bearer-token authenticated
- Native Messaging host validates extension IDs
- No network exposure of unauthenticated local IPC
- Content expiration with configurable retention
- Filename sanitization and safe temporary storage
- No inline content in DTOs; opaque references only
- Minimal diagnostics without sensitive content
- IPC bearer token never shared with browser extension

## 10. Integration Points

| Milestone | Integration Point |
|-----------|------------------|
| v4.1 Browser | Native Messaging host + BrowserProvider |
| v4.1 Explorer | Shell context menu + ExplorerProvider |
| v4.1 Clipboard | Explicit trigger + ClipboardProvider |
| v4.2 Android | `initiate_receive` API + target selection |
| v4.3 Gestures | Gesture event sink interface |
