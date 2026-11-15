# Platform Key Storage

## Current status

Rust currently uses `FileSecretStore` for the node identity and file-backed
TLS material. Atomic writes and corruption rejection are implemented, but this
is **not yet Android Keystore or Windows DPAPI protection**.

## Required migration

```mermaid
flowchart LR
  K[Android Keystore / Windows DPAPI] --> E[Encrypted identity envelope]
  E --> A[Platform adapter]
  A --> R[Rust Identity / TLS runtime memory]
```

The platform adapter must decrypt only during node initialization, must never
place private material in UniFFI DTOs, and must fail closed on corruption,
invalidated keys or profile changes. It must not silently generate a new
identity; reset and re-pair must be explicit.
