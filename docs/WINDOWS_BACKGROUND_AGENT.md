# Windows Background Agent

The Tauri application has an agent lifecycle, tray, IPC, provider registry and
Native Messaging host modules. Rust node initialization is serialized and
desktop offer response/cancellation commands are exposed.

Runtime installation, single-instance behavior, startup registration, browser
Native Messaging exchange, firewall behavior and clean-machine packaging are
not yet physically validated.
