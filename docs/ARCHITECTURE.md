# Architecture

flowww is a single Rust workspace with one process per device. The process is a daemon that owns identity, transport, storage, and policy; the browser UI is a local client of that daemon.

## Process model

```text
main (flowww binary)
  └── Bridge             loopback HTTP + WebSocket + embedded assets
        └── Node         orchestration and protocol policy
              ├── Identity / DeviceTls     persisted keys and certificate
              ├── Store                    SQLite
              ├── SharedTrust              paired-device pins
              ├── PeerListener             pinned TLS, paired traffic
              ├── PairingListener          pinned TLS, short-lived pairing
              ├── Discovery                mDNS advertise + browse
              ├── ReplayGuard              in-memory authorization nonces
              ├── SpatialSession           hold/aim/drop gesture state
              ├── SpatialRegistry          spatial target registry
              ├── ContentVault             source-owned, short-lived content handles
              ├── ControllerRuntime        captured capability + prepared commands
               ├── tasks                    accept loop, discovery loop, transfers
               └── diagnostics              structured connection/transfer measurements
```

`Node` is a cheap handle over `Arc<Inner>`. Cloning a node is how tasks and the bridge share one daemon. The bridge contains no transfer logic: HTTP handlers call node methods, and a WebSocket forwards `NodeEvent` values.

## Startup

1. Load or create the Ed25519 identity at `<data-dir>/identity.json`.
2. Load or create the persistent self-signed TLS certificate in `<data-dir>/tls/`.
3. Open SQLite at `<data-dir>/flowww.db` and load the stable device ID.
4. Bind the peer listener (`0.0.0.0:0` by default) and record the chosen port.
5. Start mDNS advertising unless discovery is disabled.
6. Spawn the accept loop and the discovery event loop.
7. Start the bridge on loopback with a fresh random bearer token.

The device ID is persisted so it survives certificate and identity regeneration. The public-key fingerprint is derived from the identity; the TLS fingerprint is derived from the certificate.

## Pairing

Pairing is the only time an unpaired TLS client is accepted.

```text
host                                   joiner
  │ start_pairing                        │
  │  • random session id, token, nonce   │
  │  • ephemeral pairing listener        │
  │  • payload = identity + TLS pin      │
  │  • QR / flowww:// URI                │
  │                                      │
  │◀──────── TLS pairing channel ────────│  joiner verifies host pin
  │◀──────── signed Join ────────────────│  covers host payload + joiner info
  │───────── signed Accept ─────────────▶│  covers full transcript
  │                                      │
  │ persist host trust                   │ persist host trust
```

Both signatures cover the complete host and joiner `DeviceInfo` objects, the session ID, the host nonce, both TLS fingerprints, and the one-time token. The host verifies the joiner's certificate fingerprint against the one presented in the join; the joiner verifies the host pin from the QR payload. A tampered name, platform, endpoint, or certificate is rejected.

The pairing listener is cancelled when the session completes, expires, or is cancelled. Tokens are never reusable.

## Normal connection

A paired connection is established only to a device in the local trust store.

1. The initiator connects with TLS 1.3, presents its client certificate, and pins the server certificate to the stored fingerprint.
2. Both sides send `HELLO` with their `DeviceInfo` and a 32-byte nonce.
3. Each side signs a canonical transcript binding the protocol version, both device descriptors, both nonces, both certificate fingerprints, and the TLS exporter value; each verifies the other's signature against the stored Ed25519 public key.
4. Both sides exchange `CAPABILITIES`.
5. The session is ready for offers and chunks.

The exporter binding makes a recorded handshake useless on a different TLS connection. Certificate pinning rejects an impostor before any application message is accepted.

## Sending

`send_text`, `send_url`, and `send_file` share one path:

1. Resolve the destination to a live endpoint. Discovery supplies the address and port; a persisted paired endpoint is the fallback.
2. Open a pinned session and run the authenticated handshake.
3. Build the content descriptor and a deterministic transfer ID (`source`, `destination`, `sha256`, `size`). Re-sending identical content reuses the same transfer identity, which makes receiver-side resume safe.
4. Sign a `TransferAuthorization` with a short expiry, size bound, and single-use nonce.
5. Send `TRANSFER_OFFER` and wait for `TRANSFER_ACCEPT`, `TRANSFER_REJECT`, or timeout.
6. On acceptance, send `TRANSFER_START` with the resume offset, then binary chunks. Each chunk is acknowledged and checked before the next one is sent.
7. Send `COMPLETE` with the final hash and byte count. The sender's record becomes `COMPLETED` only after the receiver confirms.

Progress is recorded in SQLite and emitted as `TRANSFER_UPDATED` events, which the UI renders as a list and a thin progress bar.

## Receiving

1. Validate the message, destination, source, expiry, content descriptor, authorization signature, claim fields, and nonce freshness.
2. Persist an `OFFERED` record and either auto-accept or surface a `PendingOfferView` for up to two minutes.
3. On acceptance, open the destination file as a `.partial` file and re-hash the existing prefix if resuming. The offset is rounded down to a chunk boundary.
4. For each chunk: verify its digest, reject duplicates, append at the expected offset, acknowledge with the total received.
5. On `COMPLETE`, verify total size and the whole-file SHA-256. Only then rename the partial file into its final name.
6. Mark the transfer `COMPLETED` or record a terminal failure with a reason.

Received filenames are sanitized (`..`, path separators, Windows device names, control characters, length). Files are written inside `<download-dir>` only.

## Spatial engine

Spatial transfer has two layers.

### Gesture layer (one device)

`hold` stages a content descriptor in the node's `SpatialSession` and
`ContentVault`. The node exposes a target for each online paired peer;
coordinates are stable pseudo-random positions derived from device IDs, so the
UI can lay them out without a shared map. `drop(x, y)` resolves the nearest
target within a fixed radius and sends the held content over the normal direct
path. This is the local hold/drop flow exposed by the browser UI today.

### Three-device layer (source / controller / destination)

The Spatial Engine v2 performs a real transfer from source A through controller
B to destination C:

1. **Capture.** B opens a pinned session to A and sends
   `SPATIAL_CAPTURE_REQUEST`. A checks that B is the authenticated peer, is
   paired, and that A holds live content. A then mints a fresh `ContentHandleId`,
   stages the wire-safe descriptor plus the local path in its in-memory
   `ContentVault`, and returns a source-signed `ContentCapability` bound to
   that handle and to B.
2. **Prepare.** B verifies the capability, chooses C, and signs a
   `SpatialCommand`. The command names A, B, C, the handle, content ID,
   deterministic transfer ID, expiry, a single-use nonce, and B's listener
   endpoint as a relay hint. B journals the exact (command, capability, content,
   destination, expiry) tuple in its `ControllerRuntime`.
3. **Authorize.** B sends the command and capability to A. A verifies the
   controller signature against the TLS-authenticated peer, verifies its own
   capability, re-checks every binding, requires that C is paired with A,
   consumes the command nonce and the handle, and issues the normal
   `TransferAuthorization` for C with the command's expiry. A chooses a route:
   `DIRECT` when it knows an endpoint for C, otherwise `PHONE_RELAY` when it
   knows B's endpoint.
4. **Offer and deliver.** A opens a pinned session to the selected peer and
   sends `SPATIAL_OFFER` with both proofs and the authorization. On the direct
   route C receives it from A. On the relay route B receives it first, matches
   it to its journal entry, verifies the proofs, then opens a pinned session to
   C and forwards the offer. C validates the proofs, the authorization, and the
   trust triangle A↔C and B↔C, consumes the command and authorization nonces,
   and accepts with a resume offset.
5. **Stream.** On the direct route A streams straight to C using the normal
   chunk/ACK/hash pipeline. On the relay route B forwards one chunk at a time
   and proxies ACKs; no payload is written to disk on B and memory is bounded by
   one chunk. C renames the verified `.partial` file into place and A reports a
   terminal `SPATIAL_STATUS` to B.
6. **Observe and cancel.** B's `SpatialControl` consumes status messages and
   polls its local record until terminal. A races delivery against the
   controller session; if B disconnects or cancels, A drops the destination leg.
   `SpatialCancel` is accepted by either side.

Routing is deliberately conservative. A direct attempt is retried once on
transport failures and then falls back to the relay only when the controller
endpoint is known. A semantic rejection from C is final: the source does not
retry it through another path.

Endpoints come from mDNS or from an explicit in-memory registry. The registry
exists so spatial transfers work with discovery disabled, including hermetic
tests. It is never trusted as identity: every connection still performs the
full pinned-TLS and Ed25519 handshake.

File transfer IDs stay deterministic (`source`, `destination`, `sha256`,
`size`), so re-holding the same file and retrying produces the same ID and C
resumes its partial file. The command/handle nonces are only consumed once, so
resume is a fresh capture plus a fresh command with the same transfer identity,
not a replay of the old command.

The current UI is a 2D plane. The model is intentionally transport-agnostic so a
phone AR view or a desktop pointer can feed the same coordinates. The
three-device controller flow is exposed through the node API and covered by
three-node integration tests; it is not yet wired into a native phone UI.

## Storage schema

SQLite stores:

- `devices` — paired devices, public-key fingerprint, TLS fingerprint, capabilities, trust, last seen;
- `transfers` — one row per transfer with status, direction, route, byte counts, hashes, inline content, and timestamps;
- `settings` — device ID, auto-accept, and other durable preferences;
- `nonces` — consumed authorization nonces with expiry, so replay protection survives restarts.

Writes are serialized through a mutex-guarded connection. Transfers use an upsert keyed by the deterministic transfer ID; later updates merge status and progress without losing inline content or filenames.

## Bridge boundary

The bridge is a control surface, not a network service:

- binds only to `127.0.0.1` (or `::1`);
- accepts a 32-byte random token in `Authorization: Bearer …` or a WebSocket query parameter;
- validates the `Host` header against the bound address and `localhost`;
- validates `Origin` when present;
- applies a request body limit and a stricter upload limit;
- sets CSP, `X-Content-Type-Options`, `Referrer-Policy`, `X-Frame-Options`, COOP, CORP, and `Cache-Control: no-store`;
- streams multipart uploads directly to a temp directory and removes them after the send attempt;
- serves only embedded assets, so there are no filesystem paths to traverse.

The token is placed in the URL fragment, copied to `sessionStorage` on load, then stripped from the address bar. A page without a token disables its controls and offers no API.

## Reliability and diagnostics

Connection attempts have bounded TCP/TLS/handshake timeouts. File transfer
ACKs are bounded, partial files are resumed only at verified chunk boundaries,
and direct spatial delivery retries transport failures before using the relay
hint. Cancellation and controller disconnect use separate signals. Node
shutdown cancels active spatial/controller work, resolves pending approvals,
stops discovery, and closes listeners.

The node emits structured `flowww.network` and `flowww.transfer` tracing events.
They contain transfer IDs, short peer fingerprints, route/state, byte counts,
durations, throughput when measurable, and failure presence. They do not
contain keys, tokens, signed objects, handles, paths, or content. See
`docs/PERFORMANCE.md` for the measurement contract.

## Failure behavior

- Transfers are terminal on hash mismatch, cancellation, timeout, or storage failure; the record carries the reason.
- A declined or timed-out offer rejects on the sender and leaves a cancelled record on the receiver.
- A source-side spatial delivery retries a direct transport failure once, then falls back to the controller relay when one is available; semantic rejections are terminal.
- A dropped controller connection during a spatial transfer stops the source and closes the destination leg.
- Discovery loss only removes the live endpoint; the trust record and history remain.
- Pairing failures emit `PAIRING_FAILED` and cancel the listener.
- The daemon shuts down on Ctrl+C: the bridge stops accepting, the listener and discovery loop are cancelled, and active tasks are dropped.
