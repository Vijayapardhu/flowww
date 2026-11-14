# ADR 0002: Two signatures for a three-device command

- Status: accepted
- Date: 2026-11-14

## Context

In a three-device transfer, the controller selects a destination but does not
own the content. The source owns the content but is not the party initiating
the gesture. The destination must be able to distinguish:

- who exposed the content;
- who authorized this particular destination;
- what content and transfer the source actually authorized.

A single signature from either side would collapse two separate authorities.

## Decision

Require both a source-signed `ContentCapability` and a controller-signed
`SpatialCommand`.

- The capability is bound to one handle, one source, one controller, the
  complete wire-safe content descriptor, an issue/expiry window, and a random
  nonce.
- The command is bound to the source, controller, destination, handle, content
  ID, transfer ID, expiry, a random nonce, and an optional controller endpoint.
- Signatures are Ed25519 over domain-separated canonical JSON:
  `flowww.content-capability.v1\n` and `flowww.spatial-command.v1\n`.
- The command may not outlive the capability.
- The source issues a normal FLW/1.0 `TransferAuthorization` whose expiry is
  exactly the command expiry.
- `verify_spatial_proof` verifies both signatures, then checks every binding
  field, content equality, handle equality, transfer ID equality, and the
  expiry relation.

The destination verifies both proofs and the authorization, and requires that
it trusts both the source and the controller. The source independently checks
that the destination is paired before issuing the authorization.

## Consequences

- Neither device alone can move content to an arbitrary third device.
- A changed destination, handle, content ID, transfer ID, or descriptor fails a
  signature or an explicit binding check.
- Expiry has one source of truth per flow: capability >= command ==
  authorization == offer.
- The signed controller endpoint is an address hint only; identity still comes
  from pinned TLS and the Ed25519 handshake.
