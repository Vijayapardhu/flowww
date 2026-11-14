//! Pinned TLS transport and authenticated peer sessions.
//!
//! Two layers protect a connection:
//!
//! 1. TLS 1.3 with mutual certificates. Certificates are self-signed but pinned
//!    by SHA-256 fingerprint against the local trust directory.
//! 2. An Ed25519 handshake inside the tunnel that binds the device identity to
//!    both nonces, both certificates and the TLS exporter value.

pub mod error;
pub mod listener;
pub mod pairing;
pub mod session;
pub mod tls;

pub use error::NetworkError;
pub use listener::PeerListener;
pub use pairing::{connect_pairing, PairingChannel, PairingListener, MAX_PAIRING_FRAME};
pub use session::{connect, BoxedStream, IoStream, PeerSession};
pub use tls::DeviceTls;
