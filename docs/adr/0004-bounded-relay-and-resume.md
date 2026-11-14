# ADR 0004: Bounded streaming relay and resume semantics

- Status: accepted
- Date: 2026-11-14

## Context

When the phone relays bytes, it must not buffer an entire file, write payloads
to its download directory, or create a second transfer identity. It also must
not break the FLW/1.0 chunk integrity and resume guarantees.

## Decision

Add `relay_transfer`, a one-chunk streaming primitive:

1. read one upstream chunk;
2. verify the chunk's SHA-256 and transfer ID;
3. send the chunk downstream;
4. wait for the downstream ACK, validating transfer ID, chunk index, and byte
   count;
5. forward that ACK upstream;
6. repeat.

The relay sends `COMPLETE` downstream only after the downstream has received
every acknowledged chunk, waits for the downstream completion confirmation,
forwards `COMPLETE` upstream, and then reports progress locally. No payload is
written to disk on the controller; memory is bounded by one chunk plus frame
headers. Cancel and error messages are forwarded in both directions where the
connection is still usable.

Transfer identity remains deterministic for files:

```text
TransferId::for_content(source, destination, sha256, size)
```

A retry after an interruption re-captures the content, signs a new command, and
uses the same transfer ID. The destination opens its existing `.partial` file,
reports the resume offset rounded down to a chunk boundary, and the source
streams from there. The source consumes a fresh handle and command nonce for the
new attempt; the destination allows the repeated transfer ID only while its
local record is resumable and the new proof still validates.

Inline text and URLs use random transfer IDs. They are small and are not
resumed.

## Consequences

- The relay is memory-bounded and leaves no payload on the phone.
- Resume works across direct and relay routes because the transfer ID does not
  change.
- A terminal record (completed or cancelled) cannot be resumed, so a repeated
  transfer ID is not a replay channel.
- A command nonce is single-use even if the transfer later resumes; resume is a
  new command, not a replay of the old one.
