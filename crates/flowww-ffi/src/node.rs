//! The UniFFI-exported node object. Every method is a thin adapter over
//! `flowww-node`; the protocol, crypto and transfer engine stay in Rust.

use std::fs;
use std::net::{Ipv4Addr, SocketAddr};
use std::path::{Component, Path, PathBuf};
use std::sync::{Arc, MutexGuard};

use tokio::runtime::Runtime;
use tokio::sync::broadcast;

use flowww_node::{Node, NodeConfig, NodeEvent};

use crate::secret_store::{CallbackSecretStore, PlatformSecretStore};

use crate::dto::{
    device_dto, offer_dto, pairing_view_dto, parse_device_id, parse_transfer_id, peer_dto,
    record_dto, trusted_device_dto, DeviceDto, PairingViewDto, PeerDto, PendingOfferDto,
    TransferRecordDto, TrustedDeviceDto,
};
use crate::error::{sanitize, FlowwwError};
use crate::events::{
    fan_out, lock_listeners, map_event, new_listener_list, FlowwwEvent, FlowwwEventListener,
    ListenerList,
};

struct NodeInner {
    runtime: Option<std::sync::Arc<Runtime>>,
    node: Option<Node>,
    listeners: ListenerList,
    next_listener_id: u64,
    data_dir: Option<PathBuf>,
}

/// The Flowww node, bound to the local process. One instance per app process.
#[derive(uniffi::Object)]
pub struct FlowwwNode {
    state: std::sync::Mutex<NodeInner>,
}

impl Default for FlowwwNode {
    fn default() -> Self {
        Self::new()
    }
}

#[uniffi::export]
impl FlowwwNode {
    /// Construct a stopped node. Call `start` to activate it.
    #[uniffi::constructor]
    pub fn new() -> Self {
        Self {
            state: std::sync::Mutex::new(NodeInner {
                runtime: None,
                node: None,
                listeners: new_listener_list(),
                next_listener_id: 1,
                data_dir: None,
            }),
        }
    }

    /// Start the node: bind the listener, load or create the Ed25519
    /// identity, open the app-private database, start mDNS discovery and the
    /// event forwarder. `auto_accept` is always false — incoming offers
    /// require an explicit approval via `respond_to_offer`.
    pub fn start(
        &self,
        data_dir: String,
        device_name: String,
        download_dir: String,
    ) -> Result<DeviceDto, FlowwwError> {
        if !Path::new(&data_dir).is_absolute() {
            return Err(FlowwwError::InvalidInput {
                detail: "data_dir must be an absolute path inside the app sandbox".into(),
            });
        }
        if !Path::new(&download_dir).is_absolute() {
            return Err(FlowwwError::InvalidInput {
                detail: "download_dir must be an absolute path inside the app sandbox".into(),
            });
        }
        let device_name = device_name.trim().to_string();
        if device_name.is_empty() || device_name.chars().count() > 64 {
            return Err(FlowwwError::InvalidInput {
                detail: "device_name must be 1-64 characters".into(),
            });
        }

        let mut state = self.lock_state();
        if state.node.is_some() {
            return Err(FlowwwError::AlreadyStarted);
        }
        let runtime =
            std::sync::Arc::new(Runtime::new().map_err(|error| FlowwwError::Internal {
                detail: sanitize(error),
            })?);
        let config = NodeConfig::new(&data_dir)
            .with_download_dir(&download_dir)
            .with_device_name(device_name)
            .with_discovery(true)
            .with_auto_accept(false)
            .with_listen_addr(SocketAddr::new(Ipv4Addr::UNSPECIFIED.into(), 0));
        let node = runtime
            .block_on(Node::start(config))
            .map_err(FlowwwError::from)?;

        spawn_event_forwarder(&runtime, node.subscribe(), state.listeners.clone());
        let dto = device_dto(node.device());
        state.runtime = Some(runtime);
        state.node = Some(node);
        state.data_dir = Some(PathBuf::from(data_dir));
        fan_out(&state.listeners, &FlowwwEvent::NodeStarted);
        Ok(dto)
    }

    /// Start the node with a platform-protected secret store (Android
    /// Keystore). The identity seed is only loaded through the callback; it
    /// never appears in a DTO, event, error or log line.
    pub fn start_with_secret_store(
        &self,
        data_dir: String,
        device_name: String,
        download_dir: String,
        store: std::sync::Arc<dyn PlatformSecretStore>,
    ) -> Result<DeviceDto, FlowwwError> {
        let mut state = self.lock_state();
        if state.node.is_some() {
            return Err(FlowwwError::AlreadyStarted);
        }
        if !Path::new(&data_dir).is_absolute() {
            return Err(FlowwwError::InvalidInput {
                detail: "data_dir must be an absolute path inside the app sandbox".into(),
            });
        }
        if !Path::new(&download_dir).is_absolute() {
            return Err(FlowwwError::InvalidInput {
                detail: "download_dir must be an absolute path inside the app sandbox".into(),
            });
        }
        let device_name = device_name.trim().to_string();
        if device_name.is_empty() || device_name.chars().count() > 64 {
            return Err(FlowwwError::InvalidInput {
                detail: "device_name must be 1-64 characters".into(),
            });
        }

        let runtime =
            std::sync::Arc::new(Runtime::new().map_err(|error| FlowwwError::Internal {
                detail: sanitize(error),
            })?);
        let config = NodeConfig::new(&data_dir)
            .with_download_dir(&download_dir)
            .with_device_name(device_name)
            .with_discovery(true)
            .with_auto_accept(false)
            .with_listen_addr(SocketAddr::new(Ipv4Addr::UNSPECIFIED.into(), 0))
            .with_secret_store(Arc::new(CallbackSecretStore(store)));
        let node = runtime
            .block_on(Node::start(config))
            .map_err(FlowwwError::from)?;

        spawn_event_forwarder(&runtime, node.subscribe(), state.listeners.clone());
        let dto = device_dto(node.device());
        state.runtime = Some(runtime);
        state.node = Some(node);
        state.data_dir = Some(PathBuf::from(data_dir));
        fan_out(&state.listeners, &FlowwwEvent::NodeStarted);
        Ok(dto)
    }

    /// Explicit identity reset. The node must be stopped first. Removes the
    /// identity record, the TLS pin and the trust database; downloads and
    /// staged transfers are kept. Every pairing must be re-established by
    /// scanning a new QR code. The optional platform store clears the
    /// Keystore-backed record.
    pub fn reset_identity(
        &self,
        data_dir: String,
        store: Option<std::sync::Arc<dyn PlatformSecretStore>>,
    ) -> Result<(), FlowwwError> {
        if !Path::new(&data_dir).is_absolute() {
            return Err(FlowwwError::InvalidInput {
                detail: "data_dir must be an absolute path inside the app sandbox".into(),
            });
        }
        let state = self.lock_state();
        if state.node.is_some() {
            return Err(FlowwwError::InvalidInput {
                detail: "stop the node before resetting the identity".into(),
            });
        }
        if let Some(store) = store {
            store.clear_secret().map_err(|_| FlowwwError::Storage {
                detail: "the platform secret store rejected the reset".into(),
            })?;
        }
        let base = Path::new(&data_dir);
        let identity_files = ["identity.json", "identity.dpapi", "identity.keystore"];
        for name in identity_files {
            let _ = fs::remove_file(base.join(name));
        }
        for name in ["tls-key.der", "tls-key.dpapi"] {
            let _ = fs::remove_file(base.join("tls").join(name));
        }
        let _ = fs::remove_file(base.join("tls").join("tls-cert.der"));
        let _ = fs::remove_dir(base.join("tls"));
        for name in ["flowww.db", "flowww.db-wal", "flowww.db-shm"] {
            let _ = fs::remove_file(base.join(name));
        }
        Ok(())
    }

    /// Shut the node down: cancel discovery, pairing and any in-flight
    /// transfer, drop the listener. Idempotent.
    pub fn stop(&self) -> Result<(), FlowwwError> {
        let mut state = self.lock_state();
        let Some(node) = state.node.take() else {
            return Ok(());
        };
        node.shutdown();
        drop(node);
        state.runtime = None;
        state.data_dir = None;
        fan_out(&state.listeners, &FlowwwEvent::NodeStopped);
        Ok(())
    }

    /// True while the node is running; the UI must reflect this directly.
    pub fn is_running(&self) -> bool {
        self.lock_state().node.is_some()
    }

    /// The TCP port this node is listening on for peer connections. Zero
    /// before `start`. Useful for diagnostics and for registering this node
    /// with peers that already know the address.
    pub fn listen_port(&self) -> Result<u16, FlowwwError> {
        let (node, _) = self.require()?;
        Ok(node.listen_port())
    }

    /// Register a peer's transfer endpoint explicitly. Discovery normally
    /// supplies endpoints, but this is the supported fallback when multicast
    /// is unavailable (for example on the same machine, or in a restricted
    /// network). Registering an endpoint never implies trust: pairing state is
    /// still checked before any transfer.
    pub fn register_peer_endpoint(
        &self,
        device_id: String,
        host: String,
        port: u16,
    ) -> Result<(), FlowwwError> {
        let id = parse_device_id(&device_id)?;
        let ip = host.parse().map_err(|_| FlowwwError::InvalidInput {
            detail: "the host is not a valid IP address".into(),
        })?;
        let addr = SocketAddr::new(ip, port);
        if addr.port() == 0 {
            return Err(FlowwwError::InvalidInput {
                detail: "the port must not be zero".into(),
            });
        }
        let (node, runtime) = self.require()?;
        runtime.block_on(node.register_peer_endpoint(&id, addr));
        Ok(())
    }

    /// The local device's advertised identity (metadata only, no keys).
    pub fn local_device(&self) -> Result<DeviceDto, FlowwwError> {
        let (node, _) = self.require()?;
        Ok(device_dto(node.device()))
    }

    /// Start LAN discovery (mDNS). Discovery advertises metadata only; a
    /// discovered peer is never trusted until pairing succeeds.
    pub fn start_discovery(&self) -> Result<(), FlowwwError> {
        let (node, _) = self.require()?;
        node.start_discovery().map_err(FlowwwError::from)
    }

    /// Stop LAN discovery. Returns true when a running discovery was stopped.
    pub fn stop_discovery(&self) -> Result<bool, FlowwwError> {
        let (node, _) = self.require()?;
        Ok(node.stop_discovery())
    }

    /// Peers currently visible on the LAN, with paired/trust state.
    pub fn list_peers(&self) -> Result<Vec<PeerDto>, FlowwwError> {
        let (node, runtime) = self.require()?;
        Ok(runtime
            .block_on(node.discovered_peers())
            .iter()
            .map(peer_dto)
            .collect())
    }

    /// Devices pinned in the local trust store.
    pub fn list_trusted(&self) -> Result<Vec<TrustedDeviceDto>, FlowwwError> {
        let (node, _) = self.require()?;
        let snapshot = node.snapshot().map_err(FlowwwError::from)?;
        Ok(snapshot.devices.iter().map(trusted_device_dto).collect())
    }

    /// Revoke a trusted device locally. The remote peer is not notified.
    pub fn revoke_peer(&self, device_id: String) -> Result<(), FlowwwError> {
        let id = parse_device_id(&device_id)?;
        let (node, _) = self.require()?;
        node.revoke(&id).map_err(FlowwwError::from)
    }

    /// Toggle auto-accept for incoming offers. Android must keep this false
    /// unless the user explicitly opts in in settings.
    pub fn set_auto_accept(&self, enabled: bool) -> Result<(), FlowwwError> {
        let (node, _) = self.require()?;
        node.set_auto_accept(enabled).map_err(FlowwwError::from)
    }

    /// Start a pairing session and return the URI to render as a QR code.
    pub fn start_pairing(&self) -> Result<PairingViewDto, FlowwwError> {
        let (node, runtime) = self.require()?;
        let view = runtime
            .block_on(node.start_pairing())
            .map_err(FlowwwError::from)?;
        Ok(pairing_view_dto(&view))
    }

    /// Cancel the active pairing session, if any.
    pub fn cancel_pairing(&self) -> bool {
        self.lock_state()
            .node
            .as_ref()
            .map(Node::cancel_pairing)
            .unwrap_or(false)
    }

    /// The active pairing session, if one is running.
    pub fn active_pairing(&self) -> Option<PairingViewDto> {
        self.lock_state()
            .node
            .as_ref()
            .and_then(|node| node.active_pairing())
            .as_ref()
            .map(pairing_view_dto)
    }

    /// Join a pairing session from a URI (QR payload or pasted text). The
    /// same validated path the desktop uses; no Kotlin reimplementation.
    pub fn pair_with_uri(&self, uri: String) -> Result<DeviceDto, FlowwwError> {
        let trimmed = uri.trim();
        if !trimmed.starts_with("flowww://pair/v1/") {
            return Err(FlowwwError::InvalidInput {
                detail: "expected a flowww://pair/v1/ URI".into(),
            });
        }
        let (node, runtime) = self.require()?;
        let device = runtime
            .block_on(node.pair_with_uri(trimmed))
            .map_err(FlowwwError::from)?;
        Ok(device_dto(&device))
    }

    /// Send a file to a paired device. The path must live inside the
    /// app-private data directory recorded at `start`. Blocks the calling
    /// thread until the transfer reaches a terminal state; progress is
    /// pushed through events. Call from a background thread.
    pub fn send_file(
        &self,
        device_id: String,
        path: String,
    ) -> Result<TransferRecordDto, FlowwwError> {
        let path = self.sandbox_path(&path)?;
        let id = parse_device_id(&device_id)?;
        let (node, runtime) = self.require()?;
        let record = runtime
            .block_on(node.send_file(&id, path))
            .map_err(FlowwwError::from)?;
        Ok(record_dto(&record))
    }

    /// Send inline text to a paired device.
    pub fn send_text(
        &self,
        device_id: String,
        text: String,
    ) -> Result<TransferRecordDto, FlowwwError> {
        let id = parse_device_id(&device_id)?;
        let (node, runtime) = self.require()?;
        let record = runtime
            .block_on(node.send_text(&id, &text))
            .map_err(FlowwwError::from)?;
        Ok(record_dto(&record))
    }

    /// Send a URL to a paired device.
    pub fn send_url(
        &self,
        device_id: String,
        url: String,
    ) -> Result<TransferRecordDto, FlowwwError> {
        let id = parse_device_id(&device_id)?;
        let (node, runtime) = self.require()?;
        let record = runtime
            .block_on(node.send_url(&id, &url))
            .map_err(FlowwwError::from)?;
        Ok(record_dto(&record))
    }

    /// Ask a paired device for the content it currently holds (for example
    /// the files selected in Windows Explorer or the current clipboard
    /// entry). The offer still requires the receiver's explicit approval
    /// unless auto-accept is enabled. Blocks until the transfer reaches a
    /// terminal state; call from a background thread.
    pub fn request_content(
        &self,
        device_id: String,
        provider: String,
    ) -> Result<TransferRecordDto, FlowwwError> {
        let id = parse_device_id(&device_id)?;
        let (node, runtime) = self.require()?;
        let record = runtime
            .block_on(node.request_content(&id, &provider))
            .map_err(FlowwwError::from)?;
        Ok(record_dto(&record))
    }

    /// Offers currently waiting for the user's decision.
    pub fn pending_offers(&self) -> Result<Vec<PendingOfferDto>, FlowwwError> {
        let (node, _) = self.require()?;
        Ok(node.pending_offers().iter().map(offer_dto).collect())
    }

    /// Approve (`accept = true`) or decline an offer. Declining never
    /// executes or stores the content.
    pub fn respond_to_offer(&self, transfer_id: String, accept: bool) -> Result<bool, FlowwwError> {
        let id = parse_transfer_id(&transfer_id)?;
        let (node, _) = self.require()?;
        Ok(node.respond_to_offer(id, accept))
    }

    /// Cancel a streaming transfer (either direction). False when no such
    /// transfer is streaming right now.
    pub fn cancel_transfer(&self, transfer_id: String) -> Result<bool, FlowwwError> {
        let id = parse_transfer_id(&transfer_id)?;
        let (node, _) = self.require()?;
        Ok(node.cancel_transfer(&id))
    }

    /// One transfer's current state, by id.
    pub fn transfer_status(
        &self,
        transfer_id: String,
    ) -> Result<Option<TransferRecordDto>, FlowwwError> {
        let id = parse_transfer_id(&transfer_id)?;
        let (node, _) = self.require()?;
        let snapshot = node.snapshot().map_err(FlowwwError::from)?;
        Ok(snapshot
            .transfers
            .iter()
            .find(|record| record.id == id)
            .map(record_dto))
    }

    /// Full transfer history, newest last (node order).
    pub fn transfer_history(&self) -> Result<Vec<TransferRecordDto>, FlowwwError> {
        let (node, _) = self.require()?;
        let snapshot = node.snapshot().map_err(FlowwwError::from)?;
        Ok(snapshot.transfers.iter().map(record_dto).collect())
    }

    /// Register an event listener. Returns its id for `remove_listener`.
    ///
    /// Kotlin implements [`FlowwwEventListener`]; the proxy arrives here as an
    /// `Arc`, which is stored directly in the registry.
    pub fn add_listener(&self, listener: std::sync::Arc<dyn FlowwwEventListener>) -> u64 {
        let mut state = self.lock_state();
        let id = state.next_listener_id;
        state.next_listener_id += 1;
        lock_listeners(&state.listeners).push((id, listener));
        id
    }

    /// Remove a listener by id. Always true; missing ids are a no-op.
    pub fn remove_listener(&self, listener_id: u64) -> bool {
        let state = self.lock_state();
        lock_listeners(&state.listeners).retain(|(id, _)| *id != listener_id);
        true
    }
}

impl Drop for FlowwwNode {
    fn drop(&mut self) {
        let _ = self.stop();
    }
}

impl FlowwwNode {
    fn lock_state(&self) -> MutexGuard<'_, NodeInner> {
        self.state
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    fn require(&self) -> Result<(Node, std::sync::Arc<Runtime>), FlowwwError> {
        let state = self.lock_state();
        match (&state.node, &state.runtime) {
            (Some(node), Some(runtime)) => Ok((node.clone(), std::sync::Arc::clone(runtime))),
            _ => Err(FlowwwError::NotStarted),
        }
    }

    /// Enforce the app-private storage boundary: only paths inside `data_dir`
    /// may cross into the Rust core. Kotlin resolves content ids to blobs
    /// under `filesDir`; anything else is rejected here.
    fn sandbox_path(&self, raw: &str) -> Result<PathBuf, FlowwwError> {
        let state = self.lock_state();
        let Some(base) = &state.data_dir else {
            return Err(FlowwwError::NotStarted);
        };
        let path = Path::new(raw);
        if !path.is_absolute() {
            return Err(FlowwwError::InvalidInput {
                detail: "the path must be absolute".into(),
            });
        }
        let normalized = normalize_within(path);
        if !normalized.starts_with(base) {
            return Err(FlowwwError::InvalidInput {
                detail: "the path must live inside the app-private data directory".into(),
            });
        }
        if !normalized.is_file() {
            return Err(FlowwwError::InvalidInput {
                detail: "the path is not a regular file".into(),
            });
        }
        Ok(normalized)
    }
}

/// Lexically resolve `.`/`..` without touching the filesystem.
fn normalize_within(path: &Path) -> PathBuf {
    let mut out = PathBuf::new();
    for component in path.components() {
        match component {
            Component::ParentDir => {
                out.pop();
            }
            Component::CurDir => {}
            other => out.push(other.as_os_str()),
        }
    }
    out
}

/// Forward node events to listeners on the shared runtime. The task ends when
/// the node is dropped (the broadcast channel closes).
fn spawn_event_forwarder(
    runtime: &std::sync::Arc<Runtime>,
    mut receiver: broadcast::Receiver<NodeEvent>,
    listeners: ListenerList,
) {
    runtime.spawn(async move {
        loop {
            match receiver.recv().await {
                Ok(event) => {
                    if let Some(mapped) = map_event(event) {
                        fan_out(&listeners, &mapped);
                    }
                }
                Err(broadcast::error::RecvError::Lagged(_)) => continue,
                Err(broadcast::error::RecvError::Closed) => break,
            }
        }
    });
}
