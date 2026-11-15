# Pairing Protocol (Current Rust Implementation)

```mermaid
sequenceDiagram
  participant W as Windows/Rust host
  participant A as Android/Rust joiner
  W->>W: Generate short-lived token and nonce
  W-->>A: flowww://pair/v1/<base64 JSON> QR
  A->>A: Rust validates version, UUID, token, nonce, expiry, endpoint and pins
  A->>W: TLS 1.3 channel + signed PairingJoin transcript
  W->>A: Verify join, sign PairingAccept
  A->>W: Verify accept and send signed confirmation
  W->>W: Persist mutual trust records
  A->>A: Persist mutual trust records
```

The URI contains public identity metadata and a one-time token; it does not
contain private keys. The Rust security crate owns parsing, validation,
transcript construction, Ed25519 signatures, expiration and replay checks.
Android's parser is UX-level validation only and must not establish trust.

Normal sessions use TLS 1.3 with pinned certificate fingerprints and a second
Ed25519 identity/transcript check. Discovery metadata is never trusted.

## Required negative cases

Expired, malformed, tampered, replayed, wrong-session, wrong-device and
cancelled invitations must fail before trust persistence. Existing Rust tests
cover malformed/replayed tokens, tampered joins and full transcript
verification; Android↔Windows physical evidence is still missing.
