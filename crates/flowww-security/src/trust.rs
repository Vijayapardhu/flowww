use std::collections::HashMap;
use std::sync::{Arc, Mutex};

use crate::{Fingerprint, PublicIdentity};
use flowww_core::{DeviceId, DeviceInfo};

/// A peer the local installation has explicitly paired with.
#[derive(Debug, Clone)]
pub struct PairedPeer {
    pub device: DeviceInfo,
    pub public_key: String,
    pub tls_fingerprint: Fingerprint,
}

impl PairedPeer {
    pub fn public_identity(&self) -> crate::SecurityResult<PublicIdentity> {
        PublicIdentity::from_base64(&self.public_key)
    }
}

/// Read-only view of local trust used by the network layer.
///
/// Implementations must be fast: TLS verification calls this on every handshake.
pub trait TrustDirectory: Send + Sync {
    fn by_tls_fingerprint(&self, fingerprint: &Fingerprint) -> Option<PairedPeer>;
    fn by_device_id(&self, device_id: &DeviceId) -> Option<PairedPeer>;
}

/// In-memory directory used by tests and by the pairing flow before persistence.
#[derive(Default)]
pub struct MemoryTrust {
    by_tls: Mutex<HashMap<[u8; 32], PairedPeer>>,
    by_device: Mutex<HashMap<String, PairedPeer>>,
}

impl MemoryTrust {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn insert(&self, peer: PairedPeer) {
        let tls_key = peer.tls_fingerprint.0;
        let device_key = peer.device.device_id.as_str().to_string();
        self.by_tls
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .insert(tls_key, peer.clone());
        self.by_device
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .insert(device_key, peer);
    }

    pub fn remove(&self, device_id: &DeviceId) {
        let mut by_device = self.by_device.lock().unwrap_or_else(|p| p.into_inner());
        if let Some(peer) = by_device.remove(device_id.as_str()) {
            self.by_tls
                .lock()
                .unwrap_or_else(|p| p.into_inner())
                .remove(&peer.tls_fingerprint.0);
        }
    }
}

impl TrustDirectory for MemoryTrust {
    fn by_tls_fingerprint(&self, fingerprint: &Fingerprint) -> Option<PairedPeer> {
        self.by_tls
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .get(&fingerprint.0)
            .cloned()
    }

    fn by_device_id(&self, device_id: &DeviceId) -> Option<PairedPeer> {
        self.by_device
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .get(device_id.as_str())
            .cloned()
    }
}

/// Shared trust handle.
pub type SharedTrust = Arc<dyn TrustDirectory>;

#[cfg(test)]
mod tests {
    use super::*;
    use flowww_core::{Capability, Platform};
    use std::collections::BTreeSet;

    #[test]
    fn memory_trust_indexes_both_keys() {
        let trust = MemoryTrust::new();
        let device = DeviceInfo::new(
            DeviceId::new("fw_peer_001").unwrap(),
            "Peer",
            Platform::Windows,
            BTreeSet::<Capability>::new(),
        )
        .unwrap();
        let fp = Fingerprint([1u8; 32]);
        trust.insert(PairedPeer {
            device: device.clone(),
            public_key: "key".into(),
            tls_fingerprint: fp,
        });
        assert!(trust.by_tls_fingerprint(&fp).is_some());
        assert!(trust.by_device_id(&device.device_id).is_some());
        trust.remove(&device.device_id);
        assert!(trust.by_tls_fingerprint(&fp).is_none());
    }
}
