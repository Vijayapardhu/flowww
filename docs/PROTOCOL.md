# Protocol

flowww speaks **FLW/1.0** over pinned mutual TLS. This document describes the
base protocol at a high level and, in detail, the spatial extension used for
three-device transfers.

## Versioning and compatibility

- `PROTOCOL_VERSION` is `FLW/1.0`.
- An envelope is compatible when its major version is `1`; `FLW/1.x` peers
  accept each other.
- The spatial engine adds new message kinds and new signed claim types. It
  does **not** change the shape or validation of existing messages.
- All existing messages (`HELLO`, `AUTH`, `CAPABILITIES`, `TRANSFER_OFFER`,
  `TRANSFER_ACCEPT`, chunks, `COMPLETE`, `CANCEL`, `ERROR`, `PING`/`PONG`, and
  pairing messages) keep their FLW/1.0 semantics.
- New spatial messages are only sent by a spatial-aware controller. An older
  FLW/1.0 peer does not know their message kinds and will reject them as a
  protocol error; there is no cross-version spatial negotiation yet.

## Encoding

Control messages are JSON `Envelope` values:

```json
{
  "protocol": "FLW/1.0",
  "message_id": "uuid",
  "sent_at": 1700000000000,
  "type": "SPATIAL_TRANSFER_COMMAND",
  "...": "message fields"
}
```

The envelope is serialized, bounded by `MAX_CONTROL_FRAME`, validated on encode
and decode, and sent as a length-prefixed control frame. File bytes travel in
binary chunk frames with a fixed header, transfer ID, index, payload length,
and per-chunk SHA-256. None of this changes for spatial transfers.

## Spatial roles

| Role | Name | Responsibility |
| --- | --- | --- |
| A | source | Owns the bytes. Stages content behind a handle, authorizes one destination, sends or relays the bytes. |
| B | controller | Captures a capability from A, signs a command naming C, may relay bytes when A cannot reach C. |
| C | destination | Verifies the source and controller proofs, accepts bytes only if both are paired, verifies the file hash. |

The three device IDs must be distinct. Every hop is a normal pinned TLS session
with the FLW/1.0 channel-bound identity handshake.

## Content handles

A handle is an opaque, source-owned identifier:

```text
fh_<32 lowercase hex characters>
```

It is generated from 16 random bytes and parsed strictly. A handle is never a
path, never contains a path, and is only meaningful to the source process. The
source keeps the optional local path in memory next to the handle; it does not
leave the process and is never serialized into a capability, command, offer, or
log line.

All content descriptors on the wire are wire-safe: `source_reference` is absent.
`ContentCapability::issue` refuses a descriptor that carries a local path, and
the protocol validator rejects a spatial offer whose content is not wire-safe.

## Signed spatial objects

### Content capability (source → controller)

The source signs a `ContentCapabilityClaim`:

```text
handle_id, source_device, controller_device, content,
issued_at, expires_at, nonce
```

- `content` is the complete wire-safe descriptor (name, MIME type, size, hash,
  kind, inline payload if any).
- `nonce` is 16 random bytes rendered as 32 hex characters.
- The signature is Ed25519 over
  `"flowww.content-capability.v1\n" || canonical_json(claim)`.
- The capability is bound to exactly one source, one controller, one handle, and
  one content descriptor. It expires with the source's hold.

### Spatial command (controller → source)

The controller signs a `SpatialCommandClaim`:

```text
source_device, controller_device, destination_device,
handle_id, content_id, transfer_id, expires_at, nonce,
controller_endpoint?
```

- `nonce` is 16 random bytes rendered as 32 hex characters.
- `controller_endpoint` is an optional `host:port` hint, used by the source to
  reach the controller as a relay when discovery is unavailable. It is signed,
  but it is only an address hint; TLS identity still decides who is on the
  other end.
- The signature is Ed25519 over
  `"flowww.spatial-command.v1\n" || canonical_json(claim)`.

The command must not outlive the capability (`command.expires_at <=
capability.expires_at`). The source also sets the transfer authorization expiry
to exactly `command.expires_at`, so all three proofs share one deadline.

### Transfer authorization (source → destination)

The source issues the existing FLW/1.0 `TransferAuthorization` (`TransferClaim`)
for the exact source, destination, content ID, transfer ID, action, size bound,
expiry, and a fresh 24-byte nonce. Its expiry is the command expiry.

`verify_spatial_proof` checks the capability and command signatures
independently, then checks every binding: devices, content equality, handle,
content ID, transfer ID, and command expiry against capability expiry. A
valid controller signature over a changed destination is therefore rejected.

## Messages

All spatial messages are FLW/1.0 envelope extensions.

| Kind | Direction | Purpose |
| --- | --- | --- |
| `SPATIAL_CAPTURE_REQUEST` | controller → source | `request_id`, `source_device`, `controller_device` |
| `SPATIAL_CAPTURE_GRANT` | source → controller | `request_id`, signed `capability` |
| `SPATIAL_CAPTURE_DENY` | source → controller | `request_id`, non-empty `reason` |
| `SPATIAL_TRANSFER_COMMAND` | controller → source | signed `command`, signed `capability` |
| `SPATIAL_COMMAND_ACCEPTED` | source → controller | `transfer_id`, selected `route` |
| `SPATIAL_COMMAND_REJECTED` | source → controller | `transfer_id`, `reason` |
| `SPATIAL_OFFER` | source → destination (direct) or controller → destination (relay) | the offer, both proofs, transfer authorization, content, expiry, route |
| `SPATIAL_STATUS` | source → controller | `transfer_id`, `status`, `bytes_transferred`, optional `detail` |
| `SPATIAL_CANCEL` | either direction | `transfer_id`, optional `reason` |

`SPATIAL_OFFER` requires distinct source/controller/destination, a wire-safe
content descriptor whose source matches, a non-expired capability, matching
command/capability/offer fields, an authorization matching the offer, and equal
expiries for the command, authorization, and offer.

## Flow 1: capture

```text
controller B                              source A
     │ SPATIAL_CAPTURE_REQUEST              │
     │  request_id, A, B                    │
     │─────────────────────────────────────▶│  B must be the authenticated peer
     │                                      │  B must be paired with A
     │                                      │  A must hold live, unconsumed content
     │◀─────────────────────────────────────│
     │ SPATIAL_CAPTURE_GRANT                │
     │  signed capability                   │
```

The source denies the request when it is not holding content, the hold has
expired, the controller is not paired, the named devices do not match the TLS
peer, or the capability cannot be issued. The grant is a signed object; the
controller verifies the signature and checks that the capability names itself
and the expected source before storing it.

## Flow 2: direct transfer

```text
B                    A                         C
│ command+capability │                         │
├───────────────────▶│ verify signatures        │
│                    │ verify A↔B, A↔C trust    │
│                    │ consume command nonce    │
│                    │ consume handle           │
│◀───────────────────┤ SPATIAL_COMMAND_ACCEPTED │
│                    │ route = DIRECT           │
│                    │                          │
│                    │ SPATIAL_OFFER            │
│                    ├─────────────────────────▶│ verify both proofs
│                    │                          │ verify source authorization
│                    │                          │ verify A↔C and B↔C trust
│                    │                          │ consume command + auth nonces
│                    │◀─────────────────────────┤ TRANSFER_ACCEPT(resume_from)
│                    │ binary chunks + ACKs     │
│                    ├─────────────────────────▶│
│                    │ COMPLETE + hash          │
│                    ├─────────────────────────▶│ verify file hash
│                    │◀─────────────────────────┤ COMPLETE
│◀───────────────────┤ SPATIAL_STATUS completed │
```

The controller selects the route before accepting the command. `DIRECT` is
chosen only when the source has an endpoint for the destination in its endpoint
registry or discovery table. The destination additionally requires that it
trusts both the source and the controller; otherwise it rejects the offer and
the source reports a failure to the controller.

## Flow 3: streaming relay

When the source cannot reach the destination but the controller's endpoint is
known, the source selects `PHONE_RELAY` and connects to the controller:

```text
A ── SPATIAL_OFFER (relay) ──▶ B ── SPATIAL_OFFER ──▶ C
A ◀──────────── TRANSFER_ACCEPT ───────────────────── B
A ── chunk ──▶ B ── chunk ──▶ C
A ◀── ACK ──── B ◀── ACK ──── C
A ── COMPLETE ▶ B ── COMPLETE ▶ C
              B ◀── COMPLETE ── C
A ◀── COMPLETE ─ B
```

The controller accepts a relay offer only when:

1. the offer arrived from the source over a paired TLS session;
2. the offer names the controller as `controller_device`;
3. the controller has a live journal entry for the transfer ID, and the
   command, capability, content, destination, and expiry exactly match what it
   prepared;
4. both proofs verify against the source and the controller's own identity;
5. the source's transfer authorization verifies;
6. the controller is paired with the destination and can reach it.

The relay forwards **one chunk at a time**: it verifies the chunk digest, writes
the chunk to the downstream session, waits for the downstream ACK, forwards that
ACK upstream, then reads the next chunk. No payload is written to disk on the
controller and memory is bounded by one chunk plus headers. If any leg closes,
the relay sends the appropriate cancel/error downstream and marks its record
failed.

## Status and cancellation

The source sends `SPATIAL_STATUS` to the controller before delivery and again
when the transfer reaches a terminal state. Status values use the normal
`TransferState` enum. `bytes_transferred` is the cumulative acknowledged count.
Terminal failures include a bounded human-readable `detail`.

The controller's `SpatialControl` waits for terminal status while also polling
its local transfer record. If the controller connection closes, the source
cancels delivery: the watched controller session is dropped, which closes the
source→destination (or source→relay) session, and the destination observes EOF.

Either side may send `SPATIAL_CANCEL`. The source also accepts a cancel from the
controller while it is delivering.

## Replay and expiry rules

Source:

- The command nonce is consumed in the in-memory replay guard and in the
  persistent `nonces` table before bytes move.
- The handle is consumed once. A second command for the same handle is rejected
  even if it is otherwise valid.
- `ReplayGuard` and SQLite must both report freshness. A crash between the two
  writes can only reject a valid retry; it cannot accept a replay.

Destination:

- The command and source-authorization nonces are consumed before accepting a
  fresh transfer.
- A repeat offer with the same transfer ID is allowed only when a local record
  already exists for that transfer and is resumable (not completed or
  cancelled). This is what makes retry/resume work. Such a repeat still has to
  pass the full signature and binding validation.
- A completed or cancelled transfer ID is never resumed.

Expiry:

- Capabilities, commands, authorizations, and offers all carry absolute Unix
  millisecond deadlines.
- The source refuses an expired capability or command.
- The destination refuses an expired capability, command, authorization, or
  offer, and requires all expiries to agree.

## Resume

File transfer IDs are deterministic for file content:

```text
TransferId::for_content(source_device, destination_device, sha256, size)
```

Re-holding the same file and issuing a new command produces the same transfer
ID. If the destination has a partial `.partial` file and a resumable record, the
new `SPATIAL_OFFER` carries the same transfer ID, the destination replies with
its resume offset, and the source streams from that offset. Chunk digests,
cumulative ACK checks, and the final whole-file SHA-256 are unchanged from
FLW/1.0.

Inline text and URLs use random transfer IDs and do not resume.

## Failure behavior

- Rejections are explicit `SPATIAL_COMMAND_REJECTED` or `TRANSFER_REJECT`
  messages with a bounded reason.
- A direct attempt is retried once on transport-class failures. If both attempts
  fail and the command names a controller endpoint, the source switches to
  `PHONE_RELAY`; a semantic rejection from the destination is not retried.
- Dropping the controller session stops the source and closes the destination
  leg.
- Unknown spatial routes, cross-device substitutions, malformed handles, and
  non-wire-safe content are protocol errors.
