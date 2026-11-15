//! Event surface pushed to foreign listeners. Small, typed, secret-free.

use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::{Arc, Mutex, MutexGuard};

use flowww_node::NodeEvent;

use crate::dto::{
    device_dto, offer_dto, peer_dto, record_dto, DeviceDto, PeerDto, PendingOfferDto,
    TransferRecordDto,
};

/// Events pushed to Kotlin listeners.
///
/// Mirrors [`flowww_node::NodeEvent`]; the spatial-relay variants are not
/// forwarded (spatial is out of scope for this boundary).
#[derive(Debug, Clone, uniffi::Enum)]
pub enum FlowwwEvent {
    /// The node started and its listener is bound.
    NodeStarted,
    /// The node shut down.
    NodeStopped,
    /// A peer appeared on the local network.
    PeerFound { peer: PeerDto },
    /// A peer left the local network.
    PeerLost { device_id: String },
    /// A pairing session started. Render `uri` as a QR code.
    PairingStarted {
        uri: String,
        endpoint: String,
        expires_at: u64,
    },
    /// Pairing completed and the peer is pinned in the trust store.
    PairingCompleted { device: DeviceDto },
    /// Pairing failed (timeout, bad URI, mismatch, cancelled, ...).
    PairingFailed { reason: String },
    /// Another device offered us content; show approval UI.
    OfferReceived { offer: PendingOfferDto },
    /// An offer was answered or timed out.
    OfferClosed { transfer_id: String },
    /// Transfer lifecycle snapshot; emitted per chunk and at terminal state.
    TransferUpdated { record: TransferRecordDto },
    /// Non-fatal error worth showing in diagnostics.
    Error { message: String },
}

/// Foreign (Kotlin) event callback. Called from a Rust worker thread; marshal
/// to the main loop inside the implementation.
#[uniffi::export(foreign)]
pub trait FlowwwEventListener: Send + Sync {
    fn on_event(&self, event: FlowwwEvent);
}

/// Shared listener registry. Cheap to clone for the forwarder task.
pub(crate) type ListenerList = Arc<Mutex<Vec<(u64, Arc<dyn FlowwwEventListener>)>>>;

pub(crate) fn new_listener_list() -> ListenerList {
    Arc::new(Mutex::new(Vec::new()))
}

pub(crate) fn lock_listeners(
    list: &ListenerList,
) -> MutexGuard<'_, Vec<(u64, Arc<dyn FlowwwEventListener>)>> {
    list.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
}

/// Map a core event into the FFI surface, or `None` for spatial-relay
/// variants, which are out of scope for this boundary.
pub(crate) fn map_event(event: NodeEvent) -> Option<FlowwwEvent> {
    match event {
        NodeEvent::PeerFound(peer) => Some(FlowwwEvent::PeerFound {
            peer: peer_dto(&peer),
        }),
        NodeEvent::PeerLost { device_id } => Some(FlowwwEvent::PeerLost { device_id }),
        NodeEvent::PairingStarted(view) => Some(FlowwwEvent::PairingStarted {
            uri: view.uri,
            endpoint: view.endpoint,
            expires_at: view.expires_at,
        }),
        NodeEvent::PairingCompleted(device) => Some(FlowwwEvent::PairingCompleted {
            device: device_dto(&device),
        }),
        NodeEvent::PairingFailed { reason } => Some(FlowwwEvent::PairingFailed { reason }),
        NodeEvent::OfferReceived(view) => Some(FlowwwEvent::OfferReceived {
            offer: offer_dto(&view),
        }),
        NodeEvent::OfferClosed { transfer_id } => Some(FlowwwEvent::OfferClosed {
            transfer_id: transfer_id.as_uuid().to_string(),
        }),
        NodeEvent::TransferUpdated(record) => Some(FlowwwEvent::TransferUpdated {
            record: record_dto(&record),
        }),
        NodeEvent::SpatialUpdated(_) => None,
        NodeEvent::Error { message } => Some(FlowwwEvent::Error { message }),
    }
}

/// Fan an event out to every listener. Panics in a listener are swallowed so
/// one broken listener cannot take down the forwarder for the others.
pub(crate) fn fan_out(list: &ListenerList, event: &FlowwwEvent) {
    let snapshot: Vec<Arc<dyn FlowwwEventListener>> = {
        let guard = lock_listeners(list);
        guard
            .iter()
            .map(|(_, listener)| Arc::clone(listener))
            .collect()
    };
    for listener in snapshot {
        let event = event.clone();
        let _ = catch_unwind(AssertUnwindSafe(|| listener.on_event(event)));
    }
}
