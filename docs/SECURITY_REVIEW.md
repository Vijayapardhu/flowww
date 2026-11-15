# Phase E Security Review

## Present controls

- Ed25519 device identities and signed pairing/authorization transcripts.
- TLS 1.3 with pinned certificate fingerprints for normal sessions.
- One-time, expiring pairing tokens and replay guards.
- Explicit incoming approval by default.
- Framed protocol size limits, bounded transfer chunks and timeouts.
- SHA-256 verification before received-file rename.
- Filename sanitization and Rust-side path sandboxing.
- DTOs intentionally omit private keys and transfer content.

## Open findings

1. `FileSecretStore` and `DeviceTls` currently persist secrets as ordinary
   files. Windows DPAPI and Android Keystore-backed adapters are required for
   the Phase E platform-storage requirement.
2. Android received-file destination and URI access are not yet integrated.
3. Native Messaging and local IPC need runtime authentication/registration
   evidence.
4. Log and error redaction needs a complete cross-platform review.
5. Physical firewall, multicast and background-execution behavior is untested.

Until these findings are closed and physical tests pass, the branch must not
be called production-ready.
