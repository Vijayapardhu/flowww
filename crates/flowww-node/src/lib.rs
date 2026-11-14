//! The flowww daemon.
//!
//! One `Node` owns the device identity, the pinned TLS listener, the local
//! database, discovery, pairing attempts and transfer orchestration. The bridge
//! and CLI are thin clients of this crate; no protocol logic lives in the UI.

pub mod config;
pub mod error;
pub mod event;
pub mod node;

pub use config::NodeConfig;
pub use error::{NodeError, NodeResult};
pub use event::{NodeEvent, NodeSnapshot, PairingView, PeerView, PendingOfferView, SpatialView};
pub use node::Node;
