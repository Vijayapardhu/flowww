# File Receiving

## Status

Rust receives bounded chunks into a transfer-specific partial file, verifies
size plus SHA-256 before atomic completion, and sanitizes filenames. Filename
collisions are deduped deterministically (`name (1).ext`) matching Rust's
`unique_path` on both sides.

**Android SAF receiving** is implemented (commit `d4811fb`):
- Rust downloads accepted files into the app-private `filesDir/downloads`
  directory.
- When an incoming file transfer completes, `FfiTransfersBridge` enqueues a
  `PendingReceive` into the persisted `PendingReceiveStore`.
- `ReceiveCommitter` streams the file in 64 KiB chunks through a
  `DocumentWriter` abstraction into a user-chosen SAF tree, computing SHA-256
  while writing and re-verifying it against the digest Rust reported.
- `SafDocumentWriter` implements `DocumentWriter` over `DocumentsContract`;
  `IncomingFileLocator` resolves the downloaded file by digest (unambiguous
  when the same filename arrives twice), falling back to the newest candidate.
- Duplicate names are resolved deterministically, counting names already in
  the tree, names claimed earlier in the same sweep, and names other queued
  entries are waiting for.
- The source path is checked against the canonical download directory with a
  separator, so a sibling directory can never be written to.
- Destination-unavailable is a transient condition: bounded retry budget
  (`MAX_AUTOMATIC_ATTEMPTS=3`), then FAILED. Process-death recovery resets
  COMMITTING entries to PENDING on start.
- Mismatched digest/size deletes the partial document and keeps the source
  file for retry. No silent success.

**Android UI:** MainActivity shows a "Save received files?" dialog
(`OpenDocumentTree`) when files are waiting and no destination is set.
Settings label updated to "Folder for exports and received files".

**Windows:** The node download directory is used; a user-selected destination
flow is exposed via the Windows agent's content-provider request path
(`WINDOWS_CONTENT_PROVIDERS.md`).

## Safety rules

- No received file is executed automatically.
- Destination integration streams into a user-approved location.
- Partial documents are cleaned up on failure.
- Storage/permission failures are reported without claiming completion.
- Source files are kept until the commit is verified.
