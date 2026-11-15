# Transfer Protocol and State Reconciliation

Rust owns the authoritative record. Android and Windows consume snapshots and
events and must upsert by stable transfer ID.

| State | Valid next states |
|---|---|
| `CREATED` | `OFFERED`, `FAILED`, `CANCELLED` |
| `OFFERED` | `ACCEPTED`, `CANCELLED`, `FAILED` |
| `ACCEPTED` | `CONNECTING`, `CANCELLED`, `FAILED` |
| `CONNECTING` | `TRANSFERRING`, `PAUSED`, `CANCELLED`, `FAILED` |
| `TRANSFERRING` | `PAUSED`, `VERIFYING`, `CANCELLED`, `FAILED` |
| `PAUSED` | `CONNECTING`, `TRANSFERRING`, `CANCELLED`, `FAILED` |
| `VERIFYING` | `COMPLETED`, `FAILED`, `CANCELLED` |
| `COMPLETED` | terminal |
| `CANCELLED` | terminal |
| `FAILED` | terminal unless a new transfer ID is created |

Files use bounded chunks and a transfer-specific partial file. A received file
is renamed into its final destination only after size and SHA-256 verification.
Partial files may be retained for resume at a complete chunk boundary and must
be discarded when the transfer is abandoned permanently.

Incoming transfers are never auto-accepted by default. An offer decision is a
separate authenticated protocol action and is reflected in the sender's
authoritative record.
