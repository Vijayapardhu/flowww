# Windows Content Providers

## Status

The Windows content-provider request path (P4) is implemented. A paired peer
can ask this Windows machine for the content it currently holds (files
selected in Windows Explorer, current clipboard text/URL entry). The source
answers via platform `ContentSource` hooks, offers content under a `TransferAuthorization`
exactly like a normal transfer, and the receiver still requires explicit
approval before any bytes are accepted. Committed `0d10e2b` and `acc523b`.

## Protocol (flowww-protocol)

Three new messages (commit `0d10e2b`):

- `ContentRequest { request_id, receiver_device, source_device, provider, route }`
  — a receiver asks a paired source for content from a provider family
  (e.g. "explorer", "clipboard"). Validated: devices differ, provider name
  non-empty and ≤64 chars.
- `ContentOffer { request_id, transfer_id, source_device, destination_device,
  content, action, route, expires_at, authorization }` — the source offers
  content. Authorized like a transfer offer: matching devices, live
  `TransferAuthorization`, content size ≤ authorized max, wire-safe content.
- `ContentDenied { request_id, reason }` — the source has nothing to offer or
  declined. Reason ≤512 chars.

`MAX_PROVIDER_NAME = 64`.

## Node (flowww-node)

- `ContentSource` async trait (`content_source.rs`):
  - `current_content(provider) -> NodeResult<Option<ProviderContent>>`
  - `providers() -> Vec<String>`
- `ProviderContent { content, file_path }` — the descriptor never carries a
  local path on the wire; `file_path` is local-only.
- `NoContentSource` for platforms without providers.
- `NodeConfig::with_content_source(Arc<dyn ContentSource>)`.
- `normalize_provider` / `ensure_provider_known` helpers.
- `NodeError::ContentUnavailable(String)` — source has nothing to offer.
- `NodeError::MissingRecord(String)` — transfer record not found after
  receive.
- `handle_content_request` — source side: validates the request, asks the
  platform provider, offers content, streams via the shared `complete_send`
  tail (refactored out of `drive_send`).
- `Node::request_content(source_device, provider)` — receiver side: connects,
  sends `ContentRequest`, validates the offer (`validate_offer`), requires
  approval (unless auto-accept), then `receive_offer`.
- 6 hermetic two-node integration tests (`tests/content_request.rs`):
  file transfer, inline text, no content, unknown provider, unpaired peer,
  approval required.

## Windows agent wiring (flowww-desktop)

- `WindowsContentSource` (`windows_content_source.rs`): implements
  `ContentSource` over the `ProviderRegistry`. Converts clipboard text/URL
  `CurrentContentDto`s into inline `flowww_core::Content`. The local node's
  device id is set after `Node::start`.
- `lifecycle.rs`: registers `ClipboardProvider` and `ExplorerProvider` into
  the `ProviderRegistry`, builds `WindowsContentSource` with the node's device
  id, passes `with_content_source` into `NodeConfig`, hands the `Node` to the
  IPC server.
- `ipc.rs`: `IpcServer` holds the `Node`; new `RequestContent` command calls
  `Node::request_content` and returns a `TransferRecordDto`. Errors map to
  `ContentNotFound` (no content) or `Internal` (other failures).
- `api.rs`: `RequestContent` request/response DTOs;
  `TransferRecordDto::from_record` sanitising conversion.
- `lib.rs`: passes the running node to `IpcServer::new`.

## Provider behavior

- **Clipboard:** `ClipboardProvider::get_current_content()` reads the Windows
  clipboard (CF_UNICODETEXT / CF_TEXT / CF_HDROP). Text URLs become
  `ContentKind::Url`; plain text becomes `ContentKind::Text`. File drops are
  not reported via `current_content` (use Explorer staging instead).
- **Explorer:** `ExplorerProvider` stages selected file paths. `get_current_content`
  returns `None` (Explorer content is staged explicitly, not polled).

## Security

- Local filesystem paths never cross the trust boundary (`wire_safe()` strips
  `source_reference`).
- Every offer is authorised with `TransferAuthorization`; the receiver
  validates and approves independently.
- No content is sent silently: `auto_accept` defaults to `false`.
- `ContentUnavailable` is returned honestly when a provider has nothing to
  offer — no fabricated content.

## Known limitations

- `ExplorerProvider::get_current_content` returns `None`; Explorer-staged
  files require the separate staging flow (`StageContent` via IPC).
- Clipboard file drops (CF_HDROP) are not resolved to file paths.
- No clean-machine physical test has been performed (host-based simulation
  only).
