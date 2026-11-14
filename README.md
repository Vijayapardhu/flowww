# flowww

**Send things to the devices around you. Nothing leaves your network.**

flowww is an offline-first transfer tool. A small Rust daemon runs on each device, finds the others over mDNS, pairs them once with a QR code, then moves text, links, and files directly between them. There is no account system, no cloud relay, and no external service in the data path.

Every daemon serves a minimal browser interface on loopback. Open the URL it prints and the app is ready — no install, no external assets, no telemetry.

## What it does

- **Nearby discovery** — advertises `_flowww._tcp` on the local network. Discovery is a hint; every connection is authenticated separately.
- **One-time pairing** — a two-minute, single-use QR code binds both devices' Ed25519 identities and TLS certificates.
- **Direct transfer** — paired devices connect over pinned TLS and exchange text, links, or files without an intermediary.
- **Resume and verification** — files move in 1 MiB chunks; partial transfers resume at the last complete chunk, and every chunk and final file is checked against SHA-256.
- **Spatial engine** — hold content on one device, capture it from a second, and aim it at a third. The source authorizes the destination and transfers directly, or streams the bytes through the controller when no direct path exists.
- **Approval** — incoming offers wait for you unless auto-accept is enabled for that device.
- **Local history** — device trusts and transfer records live in SQLite under the data directory.

## Status

The daemon, protocol, storage, security library, and browser UI are implemented and tested end to end. The Spatial Engine v2 three-device flow is implemented in the node and covered by deterministic three-node integration tests over real pinned TLS. Optional five- and ten-node concurrent stress tests and structured transfer/network diagnostics are included. These are loopback tests; no physical-device LAN or performance claim is made by the repository. Hosts: Windows, macOS, and Linux. The UI is a web app served by the daemon, so it is platform-neutral; native Android packaging is a later milestone.

## Quick start

You need a stable Rust toolchain (1.82 or newer).

```sh
cargo run -p flowww-bridge
```

On Windows, if the MSVC linker is unavailable, switch to the GNU toolchain first:

```powershell
rustup toolchain install stable-x86_64-pc-windows-gnu
rustup override set stable-x86_64-pc-windows-gnu
```

The daemon prints a one-time URL:

```text
http://127.0.0.1:47800/#token=…
```

Open it in a browser. The access token stays in the URL fragment, is moved to `sessionStorage` on load, and is removed from the address bar. Every API request and WebSocket connection after that is authenticated with it.

### Pair two devices

1. Start flowww on both devices on the same network.
2. On either device, choose **Pair a device**.
3. Scan the shown QR code with the other device, or copy the `flowww://pair/v1/…` code and paste it into **Join** on the other side.
4. Both devices persist the new trust immediately.

Pairing codes are single-use and expire after two minutes.

### Send something

Choose a paired, online device in **Nearby**, then:

- write text or paste a link and choose **Send text** or **Send link**;
- drop a file on the drop zone (or browse);
- choose **Hold for space** and then drop the item on a device in **Space**.

Received files go to the download directory shown at startup. Incoming offers appear at the top of the page with **Accept** and **Decline**; enable **Auto-accept** in the Transfers section to skip the prompt for this device.

### Three-device spatial transfer

The embedded browser UI can drive the same three-device flow through the authenticated loopback bridge. In a three-node test or a small embedding:

1. **Source A** holds content: `hold_text`, `hold_url`, or `hold_file`.
2. **Controller B** calls `request_capture(A)` and receives a short-lived, source-signed content capability.
3. **B** picks destination **C** and calls `send_spatial(C)`. The node prepares a signed command, submits it to A, and returns a `SpatialControl`.
4. **A** verifies the command against B's TLS identity, checks that C is paired, consumes the handle, and transfers directly to C when it knows C's endpoint. Otherwise it streams through B, one chunk at a time.
5. `control.wait()` resolves when the transfer is completed, failed, or cancelled. `control.disconnect()` models B going away; A stops the delivery.

The UI uses `POST /api/spatial/capture`, transfer-ID-only command prepare/submit endpoints, `GET /api/spatial/control/{transfer_id}/status`, and the authenticated `/api/events` WebSocket. Capture returns only the source content metadata represented by the verified capability; the opaque capability and signed command remain inside the local node and are not sent to browser JavaScript. The bridge does not expose private keys, local content paths, or raw content handles. Progress is rendered from backend transfer records and events rather than simulated timers.

All three devices must be paired with each other (A↔B, A↔C, B↔C), either through the UI or by seeding trust directly in a test. Discovery can be disabled as long as each node registers the others' endpoints with `register_peer_endpoint`.

See [docs/PROTOCOL.md](docs/PROTOCOL.md) for the message flow.

## Native desktop foundation

The separate Tauri 2 desktop foundation lives in [`apps/desktop`](apps/desktop).
It uses React, TypeScript, Tailwind CSS, and Framer Motion while keeping
`flowww-node` authoritative for identity, pairing, authorization, discovery,
and transfers. The desktop host exposes only sanitized device and transfer DTOs
over typed Tauri commands/events. See [apps/desktop/README.md](apps/desktop/README.md)
for setup. Packaging and installation are intentionally not enabled yet.

## Reliability testing

The deterministic fault-injection and stress-test workflow is documented in
[docs/TESTING.md](docs/TESTING.md). Performance measurements and their limits
are documented in [docs/PERFORMANCE.md](docs/PERFORMANCE.md). The optional
multi-device tests use loopback nodes and can be run with `--ignored`; they do
not substitute for testing on actual LAN devices.

## Command line

```text
flowww [OPTIONS]

  --port <PORT>              Local interface port (default 47800; 0 picks a free one)
  --data-dir <DIR>           Identity, TLS material, and database
  --download-dir <DIR>       Where accepted files are written
  --name <NAME>              Device name shown to peers
  --no-discovery             Do not advertise or browse on the LAN
  --auto-accept              Seed auto-accept on first boot
  -h, --help                 Print help
  -V, --version              Print version
```

Environment overrides: `FLOWWW_DATA_DIR`, `FLOWWW_DOWNLOAD_DIR`, `FLOWWW_DEVICE_NAME`. Logging is controlled by `RUST_LOG` (for example `RUST_LOG=debug`).

Build a release binary with:

```sh
cargo build --release -p flowww-bridge
./target/release/flowww
```

## Where data lives

| Path | Contents |
| --- | --- |
| `<data-dir>/identity.json` | Ed25519 private identity (owner-only permissions where supported) |
| `<data-dir>/tls/` | Persistent self-signed certificate and key |
| `<data-dir>/flowww.db` | Devices, trusts, transfer history, settings, replay nonces |
| `<download-dir>/` | Accepted files; partial files stay in `.partial` until verified |

Defaults: the platform local data directory plus `flowww` for `<data-dir>`, and the platform Downloads directory plus `flowww` for `<download-dir>`.

## Architecture

```text
┌───────────────────────────── one device ─────────────────────────────┐
│                                                                      │
│  browser UI ──HTTP/WS──▶ flowww-bridge ──▶ flowww-node               │
│  (loopback + token)                         │  │  │  │  │            │
│                                             │  │  │  │  └─ discovery  │
│                                             │  │  │  └──── storage    │
│                                             │  │  └─────── spatial    │
│                                             │  └────────── transfer   │
│                                             └───────────── network    │
│                                                              │       │
└──────────────────────────────────────────────────────────────┼───────┘
                                                               │
                                          pinned TLS + FLW/1.0  │
                                                               ▼
                                                        another node
```

| Crate | Responsibility |
| --- | --- |
| `flowww-core` | Domain model: devices, content, transfer IDs, state machines, limits, filename sanitizing |
| `flowww-protocol` | FLW/1.0 control messages and binary frame encoding; no I/O |
| `flowww-security` | Ed25519 identities, pairing payloads and transcripts, transfer and spatial authorization, replay guard, trust store |
| `flowww-network` | Pinned TLS 1.3 transport, peer handshake, framed session I/O, pairing listener |
| `flowww-storage` | SQLite persistence for devices, transfers, settings, and consumed nonces |
| `flowww-discovery` | mDNS advertise/browse and peer descriptors |
| `flowww-transfer` | Chunked send/receive, hashing, resume, atomic file placement |
| `flowww-spatial` | Deterministic hold/aim/drop state, spatial target registry, and the source-owned content vault |
| `flowww-node` | Orchestration: listener, discovery, pairing, offers, approvals, transfers, spatial capture/command routing, events |
| `flowww-bridge` | Loopback HTTP/WebSocket bridge, embedded UI, and the `flowww` daemon binary |

The node owns all protocol and policy. The bridge is deliberately thin: it translates HTTP requests into node calls and streams node events to the UI.

## Security model

- Each installation has a persistent Ed25519 identity. The private key is stored separately from the database and never advertised.
- TLS uses a persistent self-signed certificate per device. Peers are pinned by SHA-256 fingerprint from the local trust store, not by a public CA.
- Inside TLS, each side signs a canonical transcript covering the protocol version, both complete `DeviceInfo` objects, both nonces, both certificate fingerprints, and a TLS exporter value. This binds the application identity to the specific encrypted channel and rejects replayed or relayed handshakes.
- Pairing is a short-lived, single-use token delivered out of band. The join request is signed and covers the host payload and the joiner's device descriptor.
- Before any payload moves, the sender issues an Ed25519-signed authorization for the exact source, destination, content ID, transfer ID, action, size bound, expiry, and a single-use nonce. The receiver verifies the claim and consumes the nonce in memory and in SQLite.
- A three-device spatial transfer requires two additional signatures. The source signs a short-lived capability for an unguessable content handle; the controller signs a command naming the destination. The destination verifies both proofs, checks every binding, and requires that it trusts both other devices. Handles are memory-only and single-use, and no descriptor on the wire contains a filesystem path.
- The local bridge listens on loopback only. It requires a 32-byte bearer token, validates `Host` and `Origin`, denies framing, and serves a strict CSP with no third-party origins.
- Incoming offers require explicit approval unless the user turns on auto-accept.

See [docs/SECURITY.md](docs/SECURITY.md) for the threat model, [docs/PROTOCOL.md](docs/PROTOCOL.md) for message flows, [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the data flow, and [docs/adr](docs/adr) for design decisions.

## Development

```sh
cargo fmt --all -- --check
cargo clippy --workspace --all-targets -- -D warnings
cargo test --workspace
```

The test suite includes real loopback TLS sessions, multi-chunk file transfers with byte-for-byte comparison, pairing, manual accept/decline, bridge authentication, remote capture preview validation, UI asset checks, and three-node spatial transfers. The spatial tests cover direct A→C delivery, an actual A→B→C byte relay, controller disconnect, cancellation, an unauthorized destination, an expired capability, replayed commands, revoked controllers, a 5 MiB hash-verified transfer, resume after an interrupted connection, and status events on all three nodes. Tests disable mDNS and bind to ephemeral loopback ports, so they run without network access.

## Limitations

- One destination at a time; no group or broadcast sends yet.
- The browser spatial flow requires all participating devices to be paired and online. A source must already be holding content, and the UI cannot manufacture a target or bypass node authorization.
- Source availability is confirmed by the capture request rather than inferred from discovery; a device can become unavailable between selection and capture/submit and the UI reports the authoritative node error.
- Cancel sends a cancellation request to the source. Disconnect closes the controller observation session without sending a cancellation request; the source then applies its controller-disconnect lifecycle.
- Reload/reconnect restores active and terminal records from the local transfer store. A browser reconnect cannot recover a transfer that has expired from the node's retained history.
- LAN only by design. There is no internet relay, NAT traversal, or cloud fallback.
- Android is not packaged yet. The protocol is platform-neutral and the UI is a web app, but there is no Android service shell in this repository.
- The bridge is a local control surface, not a remote API. Do not expose its port beyond loopback.

## License

MIT.
