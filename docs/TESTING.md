# Testing and fault injection

flowww's automated network tests use real `Node` instances, pinned TLS, loopback
TCP listeners, temporary SQLite databases, and the production FLW/1.0 transfer
path. They do not claim physical-device or Wi-Fi testing.

## Standard validation

```sh
cargo fmt --all -- --check
cargo clippy --workspace --all-targets -- -D warnings
cargo test --workspace
node --check crates/flowww-bridge/assets/app.js
```

The three-device suite is:

```sh
cargo test -p flowww-node --test spatial_three_node -- --nocapture
```

It covers direct and relay routes, controller disconnect, cancellation,
revocation, expiry, replay, large files, interrupted connections, resume, and
status events. The network proxy in that suite deterministically supports
delays and connection interruption; it does not depend on external LAN timing.

## Multi-device stress tests

Five- and ten-node concurrent direct-transfer tests are opt-in and ignored by
default:

```sh
$env:FLOWWW_STRESS_SIZE_MB = "100"
cargo test -p flowww-node --test multi_device_stress -- --ignored --nocapture
```

For a one-gigabyte payload, set `FLOWWW_STRESS_ONE_GB=1`. These tests run on
loopback and measure the production transfer path. They do not represent a
physical LAN benchmark. Ensure adequate disk space and memory before running.

## Fault model

Deterministic tests cover transport interruption, delayed forwarding, missing
or incomplete data, invalid chunk order, duplicate/replayed commands,
authorization revocation, expired capabilities, cancellation, controller
disconnect, and resume from a chunk-aligned partial file. Protocol and transfer
unit tests exercise malformed frames, digest failures, out-of-order chunks, and
invalid resume offsets without requiring a network.

## Physical-device testing

Physical Windows/macOS/Linux device testing is not performed by CI or by this
repository session. A LAN test report must record device identities by short
fingerprint, OS versions, topology, wired/Wi-Fi conditions, payload sizes,
elapsed time, retries, and failures without recording keys, bearer tokens, or
content.
