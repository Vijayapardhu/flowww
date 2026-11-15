# Distribution and Installation

## Android

The Gradle project can assemble a debug APK and package arm64-v8a/x86_64 Rust
libraries. Release signing is intentionally supplied through ignored
`local.properties` values. No signed release APK is claimed until a configured
keystore build and install validation are recorded.

## Windows

The Tauri Rust target passes `cargo check` in this environment. A full MinGW
link/package run is blocked locally by the linker error `export ordinal too
large`; a native Windows CI runner is required for installer validation.

No distributable installer or signed artifact is currently claimed.
