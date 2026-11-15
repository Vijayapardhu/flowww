//! UniFFI boundary between the Flowww Rust core and native clients (Android).
//!
//! Design rules (see `docs/CROSS_PLATFORM_INTEGRATION_AUDIT.md`):
//!
//! * **Rust stays authoritative.** This crate only adapts `flowww-node`; it
//!   never re-implements protocol, crypto, pairing or transfer logic.
//! * **No secrets cross the FFI.** Private keys, binding material and tokens
//!   never appear in DTOs or errors. Fingerprints are SHA-256 of public keys.
//! * **No sensitive absolute paths cross the FFI for display.** The only path
//!   that crosses (`send_file`) must live inside the app-private sandbox
//!   recorded at `start`; the boundary is enforced, not documented-only.
//! * **Events are pushed, small and typed** ([`FlowwwEvent`]); listeners are
//!   invoked from a Rust worker thread, never the Kotlin main thread.
//!
//! Threading: every method blocks the calling thread briefly. Long operations
//! (`send_file`, `pair_with_uri`) run on a private multi-threaded tokio
//! runtime and report progress through [`FlowwwEvent`] callbacks; cancellation
//! uses `cancel_transfer` from any other thread.

mod dto;
mod error;
mod events;
mod node;

pub use dto::{
    DeviceDto, PairingViewDto, PeerDto, PendingOfferDto, TransferRecordDto, TrustedDeviceDto,
};
pub use error::FlowwwError;
pub use events::{FlowwwEvent, FlowwwEventListener};
pub use node::FlowwwNode;

uniffi::setup_scaffolding!();
