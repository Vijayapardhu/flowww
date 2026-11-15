# Cross-Device Testing Matrix

## Host-based automated evidence

The Rust workspace contains real two-node loopback tests for pairing,
mutual trust, manual approval, bidirectional file transfer, cancellation,
resume and hash verification. These run with ephemeral localhost listeners and
are **not** physical-device tests.

## Required physical test

| Test | Android | Windows | Evidence |
|---|---|---|---|
| QR scan and pairing | physical device | physical host | pending |
| LAN discovery | same Wi-Fi | same Wi-Fi | pending |
| text/URL | physical device | physical host | pending |
| image/file | physical device | physical host | pending |
| 100 MB transfer | physical device | physical host | pending |
| rejection/cancellation | physical device | physical host | pending |
| Wi-Fi interruption/reconnect | physical device | physical host | pending |
| process restart/trust persistence | physical device | physical host | pending |

No simulated or host-based result may be reported as physical acceptance.
