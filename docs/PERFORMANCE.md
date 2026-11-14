# Performance and diagnostics

## Measurement contract

The node emits structured `flowww.transfer` events through `tracing` for each
transfer record update. Fields include:

- `transfer_id` (public identifier), direction, route, and state;
- acknowledged and total bytes;
- `duration_ms` when a terminal timestamp exists;
- `throughput_bytes_per_second` when duration is non-zero;
- whether a failure is present.

The network layer emits `flowww.network` connection-establishment timing with
the peer's short TLS fingerprint and remote address. No private key, bearer
token, signature, capability, handle, or content payload is logged.

Missing measurements are represented as absent fields, not as zero. CPU and
RSS memory are host/process measurements and are intentionally not fabricated
by the Rust engine; collect them with the platform profiler when benchmarking.

## Reproducible loopback benchmark

```powershell
$env:RUST_LOG = "flowww.transfer=info,flowww.network=debug"
$env:FLOWWW_STRESS_SIZE_MB = "100"
cargo test -p flowww-node --test multi_device_stress -- --ignored --nocapture
```

The test prints topology size, payload bytes, transfer count, duration, and
aggregate MiB/s. Run multiple repetitions after warming the build. Report the
median and range, filesystem type, CPU, RAM, OS, Rust version, and whether the
test used loopback or physical LAN. A loopback result is not a real-device LAN
result.

As a repository-session smoke measurement, one five-node loopback run with four
concurrent 100 MiB transfers completed in 24,104 ms at an aggregate 16.59
MiB/s. This is an environment-specific smoke result, not a claimed product
benchmark or a physical-LAN result.

## Interpretation

Transfer throughput is based on cumulative acknowledged bytes and terminal
duration. It includes protocol and hashing work but is not a wire-rate claim.
Resume tests should report both total logical payload and bytes sent after the
interruption. Recovery duration is the interval from reconnect/command retry
to the next authoritative transfer state; if it is not collected, omit it.

The current engine does not expose a fabricated CPU, RSS, retransmission, or
failure counter. Retry and failure details remain visible through structured
state/error events and test output; production dashboards should aggregate
those events externally.
