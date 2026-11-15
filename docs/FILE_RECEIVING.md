# File Receiving

Rust receives bounded chunks into a transfer-specific partial file and verifies
size plus SHA-256 before atomic completion. Filenames are sanitized by Rust.

Android currently writes Rust downloads under the app-private node download
directory. A complete SAF destination picker and ContentResolver output stream
are still pending. Windows Tauri currently has the node download directory but
does not yet expose a complete user-selected destination flow.

No received file is executed automatically. Destination integration must copy
or stream into a user-approved location, preserve partial cleanup and report
storage/permission failures without claiming completion.
