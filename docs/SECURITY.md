# Security

flowww assumes a hostile local network and no trusted third party. It does not assume the device itself is compromised.

## Goals

- Only paired devices can exchange content.
- A paired device is identified by a key, not by an IP address or a display name.
- Content cannot be modified, replayed, or redirected in transit.
- Pairing requires access to a short-lived secret delivered out of band.
- The browser UI is not reachable or controllable from another origin.

## Non-goals

- Hiding that flowww is running, or hiding device names and fingerprints from the LAN.
- Protecting a device whose disk, OS account, or process memory is already compromised.
- Anonymity, internet transfer, NAT traversal, or censorship resistance.
- Multi-user access control inside one OS account.
- Availability against a LAN flood or targeted packet flood.

## Identity

Each installation generates an Ed25519 key pair on first boot. The key is written to `<data-dir>/identity.json` with mode `0600` on Unix. On Windows the file inherits the user's profile ACL; a higher-assurance deployment should wrap the secret store in DPAPI or an OS keystore. The private key is never logged, advertised, or sent.

The stable device ID is stored in SQLite and is independent of the key. The public-key fingerprint is `SHA-256(verifying_key)` printed as hex and grouped in four blocks for manual comparison. Public keys are base64 in protocol messages; fingerprints are hex.

## Trust

Trust is explicit and local. A device is trusted only after a successful pairing, or after a deliberate administrative import. Revoking a device removes its row and its pins; it does not notify the remote device.

Discovery is untrusted metadata. An attacker can advertise a spoofed `_flowww._tcp` record with a familiar name, but they cannot complete the TLS pin or the Ed25519 transcript without the paired private keys.

## Transport

- TLS 1.3 only, using rustls with the `ring` provider.
- Each device has a persistent self-signed certificate and key under `<data-dir>/tls/`.
- The peer certificate is pinned by SHA-256 fingerprint against the trust entry. There is no public CA and no hostname verification.
- Mutual certificates are required. The normal listener rejects a client certificate that is not in the trust directory.
- The pairing listener is the only exception: it accepts any client certificate for the duration of one pairing session, then authenticates that certificate through the signed pairing transcript.

Compromise of a paired device's private keys allows impersonation until the peer revokes it. There is no certificate transparency, revocation list, or online status service.

## Channel binding

After TLS, both sides exchange `HELLO` with a fresh 32-byte nonce. Each side then signs:

```text
SHA-256(
  "flowww.authenticated-hello.v1" ||
  len(protocol_version) || protocol_version ||
  len(initiator_device_json) || initiator_device_json ||
  len(responder_device_json) || responder_device_json ||
  len(initiator_nonce) || initiator_nonce ||
  len(responder_nonce) || responder_nonce ||
  len(client_tls_fingerprint) || client_tls_fingerprint ||
  len(server_tls_fingerprint) || server_tls_fingerprint ||
  len(exporter) || exporter
)
```

`exporter` is 32 bytes from the TLS exporter with label `EXPORT-flowww-channel-v1` and context `flowww/tls`. The signature is sent in `AUTH` and verified against the peer's stored Ed25519 public key.

Covering the complete device descriptors prevents an attacker from swapping a friendly name or platform after the handshake. Covering both certificates and the exporter prevents a relay from transplanting a valid signature onto another TLS connection. Covering both nonces prevents replay across sessions. The handshake has a 15-second timeout.

## Pairing

The host creates a random UUID session, a 32-byte token, a 32-byte nonce, and a payload containing its device descriptor, public key, TLS fingerprint, endpoint, and expiry. The payload is base64url-encoded into a `flowww://pair/v1/…` URI and rendered as a QR code.

The joiner connects to the pairing listener over TLS, verifies the host's TLS fingerprint from the payload, and sends a signed `Join` containing:

- the host payload (all fields, including the host device descriptor and token);
- the joiner's complete `DeviceInfo`;
- a fresh joiner nonce;
- the joiner's TLS fingerprint.

The host checks the token, session, expiry, joiner device ID, and the signature, then checks that the joiner's TLS fingerprint matches the certificate presented on the connection. It responds with a signed `Accept` over the full transcript. Both sides persist the other's public key and TLS pin.

Properties:

- Single use: the host removes the session on completion, expiry, or cancellation.
- Short lived: two-minute default TTL.
- Out-of-band: the token is only in the QR/URI, never in mDNS.
- Channel bound: the joiner's certificate fingerprint is signed into the transcript, so a relay cannot substitute its own certificate.
- Tamper evident: both descriptors are signed, so changing a device name or platform invalidates the pairing.

Residual risk: a user who scans an attacker's QR code pairs with the attacker. The UI shows the device name on both sides, and the fingerprint is available for manual comparison, but there is no independent verify step yet.

## Transfer authorization

Before sending an offer, the source signs a `TransferClaim`:

```text
source_device, destination_device, content_id, transfer_id,
action, expires_at, max_size, nonce
```

The signature is Ed25519 over `"flowww.authorization.v1\n" || claim_json`. The destination verifies:

1. the claim has not expired;
2. the signature verifies against the paired source key;
3. every claim field matches the offer and the content;
4. the content size is within `max_size`;
5. the nonce has not been used before.

Nonces are random 24-byte values, consumed in an in-memory replay guard and in the `nonces` table in SQLite. Both must report fresh; a crash between the two records can only reject a valid retry, never accept a replay.

A signed claim is valid only for the exact content ID, transfer ID, action, devices, and expiry it names. A valid signature over different content is still rejected. Offers also have their own short expiry, and an unreviewed pending offer times out after two minutes.

## Spatial authorization

A three-device transfer separates three questions that the base protocol
combines in one sender:

1. **May this controller see the content?** The source mints an unguessable
   handle and signs a `ContentCapability` bound to that handle, the source, one
   controller, the complete wire-safe content descriptor, an issue/expiry
   window, and a random nonce.
2. **May this content move to this destination?** The controller signs a
   `SpatialCommand` bound to the source, controller, destination, handle,
   content ID, transfer ID, expiry, nonce, and an optional relay endpoint.
3. **May these bytes arrive here?** The source issues a normal FLW/1.0
   `TransferAuthorization` for the exact destination, content, transfer ID,
   action, size, and the same expiry.

The destination verifies both spatial signatures, every cross-field binding,
and the source authorization before accepting a byte. It also requires that it
trusts **both** the source and the controller. The controller is not a
man-in-the-middle with unilateral authority: it can choose a destination, but
only the source can expose the content and only the source can move the bytes
to that destination.

Properties:

- **Opaque handles.** A handle is `fh_` plus 16 random bytes in hex. It is
  strictly parsed, short-lived, single-use, and only meaningful to the source.
  It is never a path.
- **No path disclosure.** Capabilities, commands, offers, and signed JSON never
  contain `source_reference`. The source keeps its local path in process memory
  next to the handle. `ContentCapability::issue` rejects a descriptor with a
  path, and the protocol validator rejects a spatial offer that is not
  wire-safe.
- **Single-use command.** The source consumes the command nonce in memory and
  in SQLite and consumes the handle before transferring. Replaying the same
  command and capability after success fails because the handle is consumed.
- **Expiry alignment.** The command may not outlive the capability, and the
  source authorization uses exactly the command expiry. The source, destination,
  and protocol validator all reject mismatched expiries.
- **Device binding.** Commands are verified against the Ed25519 key of the
  TLS-authenticated controller. A command signed by a different paired device,
  even if it names the right source, is rejected. Pairing is re-checked at the
  source for the destination and at the destination for both other devices.
- **Substitution resistance.** Changing the destination, handle, content ID,
  transfer ID, content descriptor, or device IDs invalidates either a signature
  or an explicit binding check.
- **Revocation.** Revoking a controller removes its TLS pin and trust entry.
  The controller cannot open a new authenticated session to the source, so its
  commands never reach authorization. Existing sessions are closed by the
  normal session lifecycle; revoke and restart if immediate tear-down is needed.
- **Relay is bounded.** The controller forwards one verified chunk at a time,
  waits for the downstream ACK, and keeps no payload on disk. It verifies that
  the incoming relay offer exactly matches the command it prepared; an
  unsolicited relay offer is rejected.
- **Controller disconnect.** The source watches the controller connection while
  delivering. A closed or cancelled controller session stops the source and
  closes the destination leg, so an unobserved transfer does not silently
  finish.

The destination accepts a repeated offer with the same transfer ID only when a
resumable local record already exists and the full proof and binding checks
still pass. Completed and cancelled IDs are never reopened. This keeps resume
usable without turning the command nonce into a replay hole.

Residual risks:

- The controller learns the content descriptor (but not the source path) and,
  on the relay route, sees plaintext bytes inside its TLS sessions. The relay is
  trusted for availability and confidentiality in transit, not for content
  authenticity: the destination still verifies the source's hash.
- A compromised controller can direct captured content to any destination that
  trusts it. The source's explicit capture and its trust in the destination are
  the guardrails.
- There is no user-visible consent prompt for a capture request yet; pairing is
  the current authorization boundary.

## Chunk integrity

Every chunk frame carries a fixed header with transfer ID, index, payload length, and SHA-256 of the payload. The receiver verifies the digest before writing, acknowledges the expected cumulative byte count, and rejects out-of-order or duplicate writes that do not match the resume offset. On completion, the whole file is hashed and compared with the offer's digest before the `.partial` file is renamed into place.

This detects corruption in transit and at rest during the transfer. It does not protect against a compromised endpoint, which can always write different bytes after verification.

## Local storage

- SQLite is not encrypted. Device names, transfer metadata, and settings are readable by anyone with access to the data directory.
- File content is stored in the clear in the download directory, with normal filesystem permissions.
- The TLS private key and identity file are separate from the database. Unix modes are `0600`; Windows relies on the profile ACL.
- Consumed nonces are stored with expiry and pruned by the storage layer.
- Spatial handles and their local paths are process memory only; they are not
  written to SQLite and do not survive a restart.
- Received filenames are sanitized against path separators, `..`, control characters, Windows reserved names, trailing dots/spaces, and length limits.

## Browser bridge

The bridge is a local control surface with its own threat model:

- Binds to loopback only.
- Every API and WebSocket request requires a 32-byte random token, compared in constant time.
- Token transport: `Authorization: Bearer …` for fetches, `?token=…` for WebSockets (browsers cannot set headers there). The token is never accepted in a cookie.
- The token is delivered in the URL fragment, copied to `sessionStorage`, and removed from the address bar with `history.replaceState`. Fragments are not sent in HTTP requests or referrers.
- `Host` must equal the bound loopback address or `localhost` on the same port, blocking DNS rebinding.
- `Origin`, when present, must match one of the allowed loopback origins, blocking cross-site requests.
- CSP denies all default sources, allows only same-origin scripts/styles/images and loopback WebSockets, and forbids framing (`frame-ancestors 'none'`, `X-Frame-Options: DENY`).
- `Referrer-Policy: no-referrer`, `X-Content-Type-Options: nosniff`, COOP/CORP same-origin, and `Cache-Control: no-store`.
- Uploads are size-limited, written under a random directory in the data directory, and deleted after the send attempt. Filenames are sanitized before use.
- Only embedded assets are served; there is no static file route and no path traversal surface.

Anyone who can read the daemon's stdout, the browser's session storage, or the process memory can act as the UI. Treat the token like a local password. Do not reverse-proxy the bridge or bind it to a non-loopback interface.

## Logging

The daemon logs device IDs, ports, and transfer outcomes at `info`. Tokens, private keys, signatures, and inline content are not logged by the runtime. Debug logs may include peer addresses and fingerprints. Redact logs before sharing them.

## Reporting

This is a reference implementation, not an audited product. If you find a flaw, open a private security advisory on the repository rather than a public issue.
