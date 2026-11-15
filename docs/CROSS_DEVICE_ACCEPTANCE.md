# Cross-Device Acceptance

Physical acceptance is pending. Required evidence uses one ordinary Android
phone and one Windows laptop on the same LAN:

1. Pair by scanning the Windows QR.
2. Restart both applications and verify trust persistence.
3. Discover each device without trusting discovery metadata.
4. Send staged text, URL, image, PDF and file in both directions.
5. Approve and reject incoming offers.
6. Cancel from both sides and interrupt Wi-Fi during a large transfer.
7. Verify SHA-256, restart recovery and destination behavior.
8. Run SEND/CANCEL motion during an active visible gesture session.

Host-based Rust loopback results must remain labelled simulated/host-based,
not physical-device evidence.
