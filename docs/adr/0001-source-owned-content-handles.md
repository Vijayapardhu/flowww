# ADR 0001: Source-owned content handles

- Status: accepted
- Date: 2026-11-14

## Context

Spatial transfer moves content from a source that the controller does not own.
The controller must receive a durable reference to that content, but it must not
receive a filesystem path: paths are local implementation details and a
malicious controller could otherwise probe or substitute them.

A signed descriptor by itself is also not enough. Content descriptors are
immutable metadata; the source needs a mutable, revocable, single-use reference
that only it can resolve.

## Decision

Introduce `ContentHandleId`, a random opaque identifier:

```text
fh_<16 random bytes as 32 hex characters>
```

The source stages content in an in-memory `ContentVault`:

- the entry holds the wire-safe `Content` descriptor, an optional local path, a
  staged time, an expiry, and a consumed flag;
- only the vault sees the local path;
- the handle is minted by the source, lives for the hold TTL, and is consumed
  exactly once;
- capabilities carry the handle plus the wire-safe descriptor, never the path.

All outgoing content descriptors are normalized with `Content::wire_safe`,
which removes `source_reference`. `ContentCapability::issue` rejects a
descriptor that still has one, and protocol validation rejects a spatial offer
whose descriptor is not wire-safe.

## Consequences

- Controllers can hold a reference across a capture/command flow without
  learning anything about the source filesystem.
- A replayed command cannot reuse the handle.
- Handles are memory-only, so a source restart invalidates every outstanding
  capability. The controller must capture again. This is acceptable for a
  short-lived spatial gesture.
- Resume uses a fresh capture and handle with the same deterministic transfer
  ID, not reuse of the old handle.
