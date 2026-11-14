use serde::Serialize;

use flowww_core::{
    Content, Device, DeviceId, DeviceInfo, Platform, TransferAction, TransferId, TransferRoute,
};
use flowww_discovery::DiscoveredPeer;
use flowww_spatial::{SpatialState, SpatialTarget};
use flowww_storage::TransferRecord;

/// A nearby device as the UI should display it.
#[derive(Debug, Clone, Serialize)]
pub struct PeerView {
    pub device_id: String,
    pub name: String,
    pub platform: Platform,
    pub address: Option<String>,
    pub port: u16,
    pub paired: bool,
    pub fingerprint: String,
    pub last_seen_ms: u64,
}

impl PeerView {
    pub fn new(peer: &DiscoveredPeer, paired: bool) -> Self {
        Self {
            device_id: peer.device.device_id.as_str().to_string(),
            name: peer.device.device_name.clone(),
            platform: peer.device.platform,
            address: peer.endpoint().map(|addr| addr.ip().to_string()),
            port: peer.port,
            paired,
            fingerprint: peer.public_key_fingerprint.short(),
            last_seen_ms: peer.last_seen_ms,
        }
    }
}

/// Public pairing data for the QR panel.
#[derive(Debug, Clone, Serialize)]
pub struct PairingView {
    pub uri: String,
    pub endpoint: String,
    pub expires_at: u64,
}

/// An incoming offer waiting for the user's decision.
#[derive(Debug, Clone, Serialize)]
pub struct PendingOfferView {
    pub transfer_id: TransferId,
    pub source: DeviceInfo,
    pub content: Content,
    pub route: TransferRoute,
    pub action: TransferAction,
}

/// Current spatial relay state.
#[derive(Debug, Clone, Serialize)]
pub struct SpatialView {
    pub state: SpatialState,
    pub held: Option<Content>,
    pub targets: Vec<SpatialTarget>,
}

/// Everything the local UI needs in one poll.
#[derive(Debug, Clone, Serialize)]
pub struct NodeSnapshot {
    pub device: DeviceInfo,
    pub listen_port: u16,
    pub public_key_fingerprint: String,
    pub tls_fingerprint: String,
    pub download_dir: String,
    pub auto_accept: bool,
    pub peers: Vec<PeerView>,
    pub devices: Vec<Device>,
    pub transfers: Vec<TransferRecord>,
    pub pending_offers: Vec<PendingOfferView>,
    pub spatial: SpatialView,
    pub pairing: Option<PairingView>,
}

/// Broadcast to the embedded UI over the local WebSocket.
#[derive(Debug, Clone, Serialize)]
#[serde(tag = "type", content = "data", rename_all = "SCREAMING_SNAKE_CASE")]
pub enum NodeEvent {
    PeerFound(PeerView),
    PeerLost { device_id: String },
    PairingStarted(PairingView),
    PairingCompleted(DeviceInfo),
    PairingFailed { reason: String },
    OfferReceived(PendingOfferView),
    OfferClosed { transfer_id: TransferId },
    TransferUpdated(TransferRecord),
    SpatialUpdated(SpatialView),
    Error { message: String },
}

impl NodeEvent {
    pub fn device_lost(device_id: &DeviceId) -> Self {
        Self::PeerLost {
            device_id: device_id.as_str().to_string(),
        }
    }
}
