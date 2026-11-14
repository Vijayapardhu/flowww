# ADR 0003: Direct first, relay only as a fallback

- Status: accepted
- Date: 2026-11-14

## Context

The controller may be the only device that knows how to reach the destination.
The source may still be able to reach the destination directly. Sending bytes
through the phone is slower and makes the phone a confidentiality bottleneck;
sending directly is preferable whenever it is possible.

At the same time, a controller that is out of range of the destination, or a
destination that only has a route to the controller, must still work.

## Decision

The source selects the route at authorization time:

1. `DIRECT` when the source has a known endpoint for the destination;
2. `PHONE_RELAY` when no direct endpoint is known but the command carries a
   reachable controller endpoint;
3. otherwise reject the command.

For a direct attempt, transport-class failures are retried once. If the retry
fails and a relay endpoint is available, the source switches to `PHONE_RELAY`
with the same command, proofs, authorization, and transfer ID. A semantic
rejection from the destination (`TRANSFER_REJECT`/`ERROR`) is terminal and is
not retried through the relay.

The destination validates the route against the authenticated peer:

- a `DIRECT` offer must arrive from the source;
- a `PHONE_RELAY` offer must arrive from the controller.

In both cases the destination requires trust in the source and the controller.
The controller independently requires an exact match against the journal entry
it created for the command.

## Consequences

- The common case avoids the phone in the data path.
- A destination that cannot be reached directly still receives bytes through
  the controller.
- A route can change from `DIRECT` to `PHONE_RELAY` without changing transfer
  identity, so resume still works.
- The controller cannot inject an unsolicited relay offer: there must be a
  prepared command with the same transfer ID and identical signed material.
- A malicious controller can still deny service by not relaying; availability
  is not a security property of this design.
