//! mDNS/DNS-SD discovery.
//!
//! Only public descriptors are advertised. Discovery is a hint, never trust:
//! every connection is authenticated and pinned separately.

use mdns_sd::{ResolvedService, ServiceDaemon, ServiceEvent, ServiceInfo};
use std::collections::HashMap;
use std::fmt;
use std::net::{IpAddr, SocketAddr};
use std::time::Duration;
use thiserror::Error;
use tokio::sync::broadcast;

use flowww_core::{
    unix_millis, Capability, DeviceId, DeviceInfo, Platform, DISCOVERY_SERVICE_TYPE,
};
use flowww_protocol::envelope::version_is_compatible;
use flowww_security::Fingerprint;

#[derive(Debug, Error)]
pub enum DiscoveryError {
    #[error("mDNS daemon failed: {0}")]
    Mdns(String),
    #[error("discovery is not available: {0}")]
    Unavailable(String),
}

pub type DiscoveryResult<T> = Result<T, DiscoveryError>;

/// A peer seen on the local network.
#[derive(Debug, Clone)]
pub struct DiscoveredPeer {
    pub device: DeviceInfo,
    pub public_key_fingerprint: Fingerprint,
    pub tls_fingerprint: Fingerprint,
    pub addresses: Vec<IpAddr>,
    pub port: u16,
    pub last_seen_ms: u64,
    pub fullname: String,
}

impl DiscoveredPeer {
    /// Prefer IPv4 on a real interface; fall back to any non-loopback address.
    pub fn endpoint(&self) -> Option<SocketAddr> {
        let ipv4 = self
            .addresses
            .iter()
            .find(
                |addr| matches!(addr, IpAddr::V4(v4) if !v4.is_loopback() && !v4.is_unspecified()),
            )
            .copied();
        let any = self
            .addresses
            .iter()
            .find(|addr| !addr.is_loopback() && !addr.is_unspecified())
            .copied();
        ipv4.or(any).map(|ip| SocketAddr::new(ip, self.port))
    }
}

impl fmt::Display for DiscoveredPeer {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            f,
            "{} ({}, {})",
            self.device.device_name, self.device.platform, self.device.device_id
        )
    }
}

#[derive(Debug, Clone)]
pub enum DiscoveryEvent {
    Found(Box<DiscoveredPeer>),
    Lost(DeviceId),
}

/// Advertise this device and browse for peers using one mDNS daemon.
pub struct Discovery {
    daemon: ServiceDaemon,
    fullname: Option<String>,
    events: broadcast::Sender<DiscoveryEvent>,
    own_device: DeviceId,
}

impl Discovery {
    pub fn start(
        device: &DeviceInfo,
        public_key_fingerprint: Fingerprint,
        tls_fingerprint: Fingerprint,
        port: u16,
    ) -> DiscoveryResult<(Self, broadcast::Receiver<DiscoveryEvent>)> {
        let daemon = ServiceDaemon::new().map_err(|e| DiscoveryError::Mdns(e.to_string()))?;
        let properties = advertisement_properties(device, public_key_fingerprint, tls_fingerprint);

        let hostname = format!("flowww-{}.local.", short_id(&device.device_id));
        let instance = format!("flowww {}", device.device_name);
        let mut info = ServiceInfo::new(
            DISCOVERY_SERVICE_TYPE,
            &instance,
            &hostname,
            "",
            port,
            Some(properties),
        )
        .map_err(|e| DiscoveryError::Mdns(e.to_string()))?
        .enable_addr_auto();

        // Name probing avoids collisions when the same device is restarted.
        info.set_requires_probe(true);
        let fullname = info.get_fullname().to_string();
        daemon
            .register(info)
            .map_err(|e| DiscoveryError::Mdns(e.to_string()))?;

        let receiver = daemon
            .browse(DISCOVERY_SERVICE_TYPE)
            .map_err(|e| DiscoveryError::Mdns(e.to_string()))?;
        let (events, event_rx) = broadcast::channel(128);
        let events_tx = events.clone();
        let own_device = device.device_id.clone();

        tokio::spawn(async move {
            let mut names: HashMap<String, DeviceId> = HashMap::new();
            while let Ok(event) = receiver.recv_async().await {
                match event {
                    ServiceEvent::ServiceResolved(info) => {
                        if let Some(peer) = parse_service_info(&*info, &own_device) {
                            tracing::debug!("discovered {} at {}", peer, peer.fullname);
                            names.insert(peer.fullname.clone(), peer.device.device_id.clone());
                            let _ = events_tx.send(DiscoveryEvent::Found(Box::new(peer)));
                        }
                    }
                    ServiceEvent::ServiceRemoved(_, fullname) => {
                        if let Some(id) = names.remove(&fullname) {
                            tracing::debug!("lost {} ({})", id, fullname);
                            let _ = events_tx.send(DiscoveryEvent::Lost(id));
                        }
                    }
                    _ => {}
                }
            }
        });

        Ok((
            Self {
                daemon,
                fullname: Some(fullname),
                events,
                own_device: device.device_id.clone(),
            },
            event_rx,
        ))
    }

    pub fn subscribe(&self) -> broadcast::Receiver<DiscoveryEvent> {
        self.events.subscribe()
    }

    pub fn own_device(&self) -> &DeviceId {
        &self.own_device
    }

    /// Stop advertising and shut the daemon down gracefully.
    pub fn stop(&mut self) {
        if let Some(fullname) = self.fullname.take() {
            let _ = self.daemon.unregister(&fullname);
        }
        let _ = self.daemon.shutdown();
    }
}

impl Drop for Discovery {
    fn drop(&mut self) {
        self.stop();
    }
}

/// The subset of an mDNS record that flowww cares about.
///
/// `mdns-sd` exposes both `ServiceInfo` (own/service lookups and tests) and
/// `ResolvedService` (browse events); this trait lets one parser handle both
/// without copying records into a synthetic `ServiceInfo`.
pub trait ServiceRecord {
    fn property(&self, key: &str) -> Option<&str>;
    fn addresses(&self) -> Vec<IpAddr>;
    fn port(&self) -> u16;
    fn fullname(&self) -> String;
}

impl ServiceRecord for ServiceInfo {
    fn property(&self, key: &str) -> Option<&str> {
        self.get_property_val_str(key)
    }

    fn addresses(&self) -> Vec<IpAddr> {
        self.get_addresses().iter().copied().collect()
    }

    fn port(&self) -> u16 {
        self.get_port()
    }

    fn fullname(&self) -> String {
        self.get_fullname().to_string()
    }
}

impl ServiceRecord for ResolvedService {
    fn property(&self, key: &str) -> Option<&str> {
        self.get_property_val_str(key)
    }

    fn addresses(&self) -> Vec<IpAddr> {
        self.get_addresses()
            .iter()
            .map(|addr| addr.to_ip_addr())
            .collect()
    }

    fn port(&self) -> u16 {
        self.get_port()
    }

    fn fullname(&self) -> String {
        self.get_fullname().to_string()
    }
}

/// Reconstruct a peer from an mDNS service record.
///
/// This intentionally rejects incomplete records rather than inventing values:
/// a device that cannot prove its fingerprints cannot be connected to safely.
pub fn parse_service_info(
    info: &impl ServiceRecord,
    own_device: &DeviceId,
) -> Option<DiscoveredPeer> {
    let device_id = info.property("device_id")?;
    if device_id == own_device.as_str() {
        return None;
    }
    let device_id = DeviceId::new(device_id).ok()?;
    let name = info.property("device_name")?;
    if name.trim().is_empty() || name.len() > 64 {
        return None;
    }
    let platform = parse_platform(info.property("platform")?);
    let protocol_version = info.property("protocol_version")?;
    if !version_is_compatible(protocol_version) {
        return None;
    }
    let public_key_fingerprint = Fingerprint::parse(info.property("fingerprint")?).ok()?;
    let tls_fingerprint = Fingerprint::parse(info.property("tls_fingerprint")?).ok()?;
    let capabilities = info
        .property("capabilities")
        .unwrap_or_default()
        .split(',')
        .filter(|value| !value.is_empty())
        .filter_map(|value| value.parse::<Capability>().ok())
        .collect();
    let device = DeviceInfo {
        device_id,
        device_name: name.to_string(),
        platform,
        protocol_version: protocol_version.to_string(),
        capabilities,
    };
    let addresses = info.addresses();
    let port = info.port();
    if port == 0 || addresses.is_empty() {
        return None;
    }
    Some(DiscoveredPeer {
        device,
        public_key_fingerprint,
        tls_fingerprint,
        addresses,
        port,
        last_seen_ms: unix_millis(),
        fullname: info.fullname(),
    })
}

fn advertisement_properties(
    device: &DeviceInfo,
    public_key_fingerprint: Fingerprint,
    tls_fingerprint: Fingerprint,
) -> HashMap<String, String> {
    let capabilities = device
        .capabilities
        .iter()
        .map(|c| c.as_str().to_string())
        .collect::<Vec<_>>()
        .join(",");
    HashMap::from([
        ("device_id".into(), device.device_id.as_str().to_string()),
        ("device_name".into(), device.device_name.clone()),
        ("platform".into(), device.platform.as_str().to_string()),
        ("protocol_version".into(), device.protocol_version.clone()),
        ("fingerprint".into(), public_key_fingerprint.to_hex()),
        ("tls_fingerprint".into(), tls_fingerprint.to_hex()),
        ("capabilities".into(), capabilities),
    ])
}

fn parse_platform(value: &str) -> Platform {
    match value {
        "windows" => Platform::Windows,
        "android" => Platform::Android,
        "web" => Platform::Web,
        "macos" => Platform::Macos,
        "linux" => Platform::Linux,
        _ => Platform::Unknown,
    }
}

fn short_id(id: &DeviceId) -> String {
    let tail = id.as_str().trim_start_matches("fw_");
    tail.chars().take(12).collect()
}

/// Helper used by the daemon to disable discovery without failing startup.
pub fn unavailable(reason: impl Into<String>) -> DiscoveryError {
    DiscoveryError::Unavailable(reason.into())
}

/// A small retention helper for the UI: remove peers that missed several
/// announcements. Misplaced mDNS records are common on busy networks.
pub fn is_stale(peer: &DiscoveredPeer, now_ms: u64, stale_after: Duration) -> bool {
    now_ms.saturating_sub(peer.last_seen_ms) > stale_after.as_millis() as u64
}

#[cfg(test)]
mod tests {
    use super::*;
    use flowww_core::Capability;
    use std::collections::BTreeSet;

    fn service_info(overrides: &[(&str, &str)]) -> ServiceInfo {
        let mut props = advertisement_properties(
            &DeviceInfo::new(
                DeviceId::new("fw_peer_0001").unwrap(),
                "Studio",
                Platform::Windows,
                Capability::standard(),
            )
            .unwrap(),
            Fingerprint([1u8; 32]),
            Fingerprint([2u8; 32]),
        );
        for (key, value) in overrides {
            if value.is_empty() {
                props.remove(*key);
            } else {
                props.insert((*key).into(), (*value).into());
            }
        }
        ServiceInfo::new(
            DISCOVERY_SERVICE_TYPE,
            "flowww Studio",
            "flowww-self.local.",
            "127.0.0.1",
            47832,
            Some(props),
        )
        .unwrap()
    }

    #[test]
    fn valid_advertisement_is_parsed() {
        let info = service_info(&[]);
        let peer = parse_service_info(&info, &DeviceId::new("fw_self_0001").unwrap()).unwrap();
        assert_eq!(peer.device.device_name, "Studio");
        assert_eq!(peer.port, 47832);
        assert!(peer.tls_fingerprint.equals(Fingerprint([2u8; 32])));
    }

    #[test]
    fn own_advertisement_is_ignored() {
        let info = service_info(&[]);
        assert!(parse_service_info(&info, &DeviceId::new("fw_peer_0001").unwrap()).is_none());
    }

    #[test]
    fn missing_fingerprint_is_rejected() {
        let info = service_info(&[("tls_fingerprint", "")]);
        assert!(parse_service_info(&info, &DeviceId::new("fw_self_0001").unwrap()).is_none());
    }

    #[test]
    fn wrong_major_protocol_is_rejected() {
        let info = service_info(&[("protocol_version", "FLW/2.0")]);
        assert!(parse_service_info(&info, &DeviceId::new("fw_self_0001").unwrap()).is_none());
    }

    #[test]
    fn stale_peers_are_detected() {
        let info = service_info(&[]);
        let peer = parse_service_info(&info, &DeviceId::new("fw_self_0001").unwrap()).unwrap();
        assert!(is_stale(
            &peer,
            peer.last_seen_ms + 10_000,
            Duration::from_secs(3)
        ));
    }

    #[test]
    fn standard_capability_set_is_complete() {
        let caps: BTreeSet<Capability> = Capability::standard();
        assert!(caps.contains(&Capability::SpatialTarget));
    }
}
