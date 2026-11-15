use std::collections::HashMap;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::path::{Path, PathBuf};
use std::str::FromStr;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use tokio::sync::{broadcast, oneshot, RwLock};
use tokio::time::timeout;
use tokio_util::sync::CancellationToken;
use tracing::{debug, info, warn};
use uuid::Uuid;

use flowww_core::{
    unix_millis, Capability, Content, ContentHandleId, DeviceId, DeviceInfo, Platform,
    TransferAction, TransferId, TransferRoute, TransferState,
};
use flowww_discovery::{DiscoveredPeer, Discovery, DiscoveryEvent};
use flowww_network::{
    connect, connect_pairing, DeviceTls, PairingListener, PeerListener, PeerSession,
};
use flowww_protocol::{ErrorCode, Message};
use flowww_security::{
    verify_authorization, verify_spatial_proof, AuthorizationExpectation, ContentCapability,
    FileSecretStore, Fingerprint, Identity, PairedPeer, PairingJoin, PairingPayload,
    PairingSession, PairingTransport, PublicIdentity, ReplayGuard, SharedTrust, SpatialCommand,
    SpatialExpectation, TransferAuthorization, TransferClaim,
};
use flowww_spatial::{
    resolve_drop, ContentVault, SpatialRegistry, SpatialSession, SpatialTarget, DEFAULT_HOLD_TTL,
};
use flowww_storage::{Store, TransferDirection, TransferRecord};
use flowww_transfer::{
    hash_file, receive_file, receive_inline, relay_transfer, send_file as stream_file,
    send_inline as stream_inline, IncomingFile, Progress,
};

use crate::config::NodeConfig;
use crate::error::{NodeError, NodeResult};
use crate::event::{NodeEvent, NodeSnapshot, PairingView, PeerView, PendingOfferView, SpatialView};

const OFFER_APPROVAL_TTL: Duration = Duration::from_secs(120);
const OFFER_REPLY_TTL: Duration = Duration::from_secs(30);
const TRANSFER_TTL_MS: u64 = 5 * 60 * 1000;
const SPATIAL_TARGET_RADIUS: f32 = 0.34;
/// How long a controller waits for the terminal status of a spatial transfer.
const SPATIAL_WAIT_TTL: Duration = Duration::from_secs(90);
/// A spatial transfer may be retried (resume) before relay fallback.
const SPATIAL_DIRECT_ATTEMPTS: usize = 2;

/// The validated parts of a spatial exchange.
struct SpatialOfferParts {
    transfer_id: TransferId,
    source_device: DeviceId,
    controller_device: DeviceId,
    destination_device: DeviceId,
    content: Content,
    action: TransferAction,
    route: TransferRoute,
    expires_at: u64,
    authorization: TransferAuthorization,
    capability: ContentCapability,
    command: SpatialCommand,
}

/// The parts of an incoming offer that must be checked before it is surfaced.
struct OfferVerification<'a> {
    source_device: &'a DeviceId,
    destination_device: &'a DeviceId,
    content: &'a Content,
    action: TransferAction,
    expires_at: u64,
    authorization: &'a TransferAuthorization,
}

/// A running flowww node.
///
/// Cheap to clone: every clone shares the same listener, database and state.
#[derive(Clone)]
pub struct Node {
    inner: Arc<Inner>,
}

struct Inner {
    config: NodeConfig,
    store: Store,
    identity: Identity,
    tls: Arc<DeviceTls>,
    device: DeviceInfo,
    listener: PeerListener,
    listen_port: u16,
    trust: SharedTrust,
    events: broadcast::Sender<NodeEvent>,
    peers: RwLock<HashMap<String, DiscoveredPeer>>,
    /// Explicit endpoints registered by tests or the local UI. Discovery is the
    /// normal source, but spatial transfers must work with it disabled.
    endpoints: RwLock<HashMap<String, SocketAddr>>,
    discovery: Mutex<Option<Discovery>>,
    pending: Mutex<HashMap<TransferId, PendingEntry>>,
    /// Cancellation tokens for ordinary (non-spatial) transfers, keyed by transfer id.
    /// Ordinary transfers stream inline inside `send_content_to`/`receive_offer`,
    /// so the token has to live on the inner state rather than in a caller frame.
    transfer_cancels: Mutex<HashMap<TransferId, CancellationToken>>,
    pairing: Mutex<Option<ActivePairing>>,
    spatial: Mutex<SpatialRuntime>,
    controller: Mutex<ControllerRuntime>,
    replay: ReplayGuard,
    shutdown: CancellationToken,
}

struct PendingEntry {
    sender: oneshot::Sender<bool>,
    view: PendingOfferView,
}

struct ActivePairing {
    session_id: String,
    payload: PairingPayload,
    uri: String,
    cancel: CancellationToken,
}

struct SpatialRuntime {
    session: SpatialSession,
    registry: SpatialRegistry,
    vault: ContentVault,
    /// The staged handle backing the current hold, if any.
    held_handle: Option<ContentHandleId>,
    /// Source-side delivery cancellation tokens, keyed by transfer.
    cancels: HashMap<TransferId, CancellationToken>,
}

impl Default for SpatialRuntime {
    fn default() -> Self {
        Self {
            session: SpatialSession::default_ttl(),
            registry: SpatialRegistry::default_config(),
            vault: ContentVault::default_ttl(),
            held_handle: None,
            cancels: HashMap::new(),
        }
    }
}

/// The controller's view: one captured capability and the commands it issued.
#[derive(Default)]
struct ControllerRuntime {
    capability: Option<ContentCapability>,
    journal: HashMap<TransferId, JournalEntry>,
    cancels: HashMap<TransferId, CancellationToken>,
    cancel_requests: HashMap<TransferId, CancellationToken>,
    disconnects: HashMap<TransferId, CancellationToken>,
}

struct JournalEntry {
    command: SpatialCommand,
    capability: ContentCapability,
    content: Content,
    destination: DeviceId,
    expires_at: u64,
}

/// Everything the source authorized for one spatial delivery attempt.
struct SpatialDelivery {
    transfer_id: TransferId,
    destination: DeviceId,
    content: Content,
    route: TransferRoute,
    authorization: TransferAuthorization,
    capability: ContentCapability,
    command: SpatialCommand,
    expires_at: u64,
    local_path: Option<PathBuf>,
    relay_endpoint: Option<SocketAddr>,
}

enum AttemptError {
    Rejected(String),
    Unavailable(String),
}

/// What the source observed on the controller connection during delivery.
enum ControllerWatch {
    Closed,
    Cancelled,
}

impl ControllerWatch {
    fn reason(&self) -> &'static str {
        match self {
            Self::Closed => "the controller disconnected",
            Self::Cancelled => "the controller cancelled the transfer",
        }
    }
}

/// Resolve when the controller connection closes, cancels, or sends a ping.
///
/// This is raced against the actual destination transfer so a phone that goes
/// away stops the source instead of letting a transfer finish unobserved.
async fn watch_controller(session: &mut PeerSession) -> ControllerWatch {
    loop {
        match session.recv_message().await {
            Ok(Message::SpatialCancel { .. }) => return ControllerWatch::Cancelled,
            Ok(Message::Ping { nonce }) => {
                if session.send_message(Message::Pong { nonce }).await.is_err() {
                    return ControllerWatch::Closed;
                }
            }
            Ok(_) => {}
            Err(_) => return ControllerWatch::Closed,
        }
    }
}

/// A live controller-side command session.
pub struct SpatialControl {
    node: Node,
    transfer_id: TransferId,
    session: Option<PeerSession>,
    record: TransferRecord,
    cancel: CancellationToken,
    cancel_request: CancellationToken,
    disconnect_signal: CancellationToken,
}

impl SpatialControl {
    pub fn transfer_id(&self) -> TransferId {
        self.transfer_id
    }

    pub fn route(&self) -> TransferRoute {
        self.record.route
    }

    /// Drop the controller connection without cancelling source-side work.
    ///
    /// This models a phone that is backgrounded or goes out of range: the
    /// source observes a closed connection and its own cancellation logic
    /// decides what happens next.
    pub fn disconnect(&mut self) {
        self.disconnect_signal.cancel();
        self.session.take();
    }

    /// Wait for the terminal result, applying status messages to the local
    /// record and to every subscriber of `NodeEvent::TransferUpdated`.
    pub async fn wait(mut self) -> NodeResult<TransferRecord> {
        let transfer_id = self.transfer_id;
        let deadline = tokio::time::Instant::now() + SPATIAL_WAIT_TTL;
        loop {
            if let Some(record) = self.node.inner.store.get_transfer(transfer_id)? {
                if record.status.is_terminal() {
                    self.finish();
                    return terminal(record);
                }
                self.record = record;
            }
            let now = tokio::time::Instant::now();
            if now >= deadline {
                self.finish();
                return Err(NodeError::Timeout);
            }
            let Some(session) = self.session.as_mut() else {
                tokio::time::sleep(Duration::from_millis(25)).await;
                continue;
            };
            let poll = deadline.min(now + Duration::from_millis(250));
            let receive = timeout(
                poll.saturating_duration_since(now)
                    .max(Duration::from_millis(1)),
                session.recv_message(),
            );
            tokio::select! {
                _ = self.cancel_request.cancelled() => {
                    let _ = session
                        .send_message(Message::SpatialCancel {
                            transfer_id,
                            reason: Some("cancelled by the controller".into()),
                        })
                        .await;
                    self.record.status = TransferState::Cancelled;
                    self.record.error = Some("cancelled by the controller".into());
                    self.record.completed_at = Some(unix_millis());
                    let record = self.record.clone();
                    self.node.publish_record(&record);
                    self.finish();
                    return Err(NodeError::OfferRejected(
                        "the spatial transfer was cancelled".into(),
                    ));
                }
                _ = self.disconnect_signal.cancelled() => {
                    self.session.take();
                    self.record.status = TransferState::Failed;
                    self.record.error = Some("the controller disconnected".into());
                    self.record.completed_at = Some(unix_millis());
                    let record = self.record.clone();
                    self.node.publish_record(&record);
                    self.finish();
                    return Err(NodeError::Closed);
                }
                result = receive => match result {
                Ok(Ok(Message::SpatialStatus {
                    transfer_id: id,
                    status,
                    bytes_transferred,
                    detail,
                })) if id == transfer_id => {
                    self.record.status = status;
                    self.record.bytes_transferred = bytes_transferred;
                    if let Some(detail) = detail {
                        if status == TransferState::Failed {
                            self.record.error = Some(detail);
                        }
                    }
                    if status.is_terminal() {
                        self.record.completed_at = Some(unix_millis());
                        let record = self.record.clone();
                        self.node.publish_record(&record);
                        self.finish();
                        return terminal(record);
                    }
                }
                Ok(Ok(Message::SpatialCancel {
                    transfer_id: id,
                    reason,
                })) if id == transfer_id => {
                    self.record.status = TransferState::Cancelled;
                    self.record.error = reason;
                    self.record.completed_at = Some(unix_millis());
                    let record = self.record.clone();
                    self.node.publish_record(&record);
                    self.finish();
                    return Err(NodeError::OfferRejected(
                        "the spatial transfer was cancelled".into(),
                    ));
                }
                Ok(Ok(Message::Ping { nonce })) => {
                    let _ = session.send_message(Message::Pong { nonce }).await;
                }
                Ok(Ok(_)) => {}
                Ok(Err(_)) => {
                    // The controller connection closed. The source stops its
                    // own work; surface that as a failure instead of hanging.
                    self.session.take();
                    self.record.status = TransferState::Failed;
                    self.record.error = Some("the controller connection closed".into());
                    self.record.completed_at = Some(unix_millis());
                    let record = self.record.clone();
                    self.node.publish_record(&record);
                    self.finish();
                    return Err(NodeError::Closed);
                }
                Err(_) => {}
                }
            }
        }
    }

    fn finish(&mut self) {
        self.cancel.cancel();
        self.session.take();
        self.node
            .inner
            .controller
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .cancels
            .remove(&self.transfer_id);
        self.node
            .inner
            .controller
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .cancel_requests
            .remove(&self.transfer_id);
        self.node
            .inner
            .controller
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .disconnects
            .remove(&self.transfer_id);
    }
}

fn terminal(record: TransferRecord) -> NodeResult<TransferRecord> {
    match record.status {
        TransferState::Completed => Ok(record),
        TransferState::Cancelled => Err(NodeError::OfferRejected(
            record
                .error
                .clone()
                .unwrap_or_else(|| "the spatial transfer was cancelled".into()),
        )),
        TransferState::Failed => Err(NodeError::OfferRejected(
            record
                .error
                .clone()
                .unwrap_or_else(|| "the spatial transfer failed".into()),
        )),
        _ => Ok(record),
    }
}

impl Node {
    /// Boot the daemon: identity, TLS pin, database, trusted listener and
    /// discovery. Discovery failure degrades to manual pairing and is not fatal.
    pub async fn start(config: NodeConfig) -> NodeResult<Self> {
        std::fs::create_dir_all(&config.data_dir)?;
        std::fs::create_dir_all(&config.download_dir)?;

        let identity =
            Identity::load_or_create(&FileSecretStore::new(config.data_dir.join("identity.json")))?;
        let tls = Arc::new(DeviceTls::load_or_create(config.data_dir.join("tls"))?);
        let store = Store::open(config.data_dir.join("flowww.db"))?;

        let device_id = match store.get_setting("device_id")? {
            Some(stored) => DeviceId::new(stored)?,
            None => {
                let generated = DeviceId::generate();
                store.set_setting("device_id", generated.as_str())?;
                generated
            }
        };
        let device = DeviceInfo::new(
            device_id,
            config.device_name.clone(),
            Platform::current(),
            Capability::standard(),
        )?;

        let trust: SharedTrust = Arc::new(store.clone());
        let listener = PeerListener::bind(config.listen_addr, tls.clone(), trust.clone()).await?;
        let listen_port = listener.local_addr()?.port();
        let (events, _) = broadcast::channel(256);

        if store.get_setting("auto_accept")?.is_none() {
            store.set_setting(
                "auto_accept",
                if config.auto_accept { "true" } else { "false" },
            )?;
        }

        let inner = Arc::new(Inner {
            config,
            store,
            identity,
            tls,
            device,
            listener,
            listen_port,
            trust,
            events,
            peers: RwLock::new(HashMap::new()),
            endpoints: RwLock::new(HashMap::new()),
            discovery: Mutex::new(None),
            pending: Mutex::new(HashMap::new()),
            transfer_cancels: Mutex::new(HashMap::new()),
            pairing: Mutex::new(None),
            spatial: Mutex::new(SpatialRuntime::default()),
            controller: Mutex::new(ControllerRuntime::default()),
            replay: ReplayGuard::new(),
            shutdown: CancellationToken::new(),
        });
        let node = Self { inner };
        node.spawn_accept_loop();
        if node.inner.config.enable_discovery {
            if let Err(error) = node.start_discovery() {
                warn!("discovery unavailable: {}", error);
            }
        }
        info!(
            "flowww node {} listening on {}",
            node.device_id(),
            node.inner.listen_port
        );
        Ok(node)
    }

    // ------------------------------------------------------------- accessors

    pub fn device(&self) -> &DeviceInfo {
        &self.inner.device
    }

    pub fn device_id(&self) -> &DeviceId {
        &self.inner.device.device_id
    }

    pub fn listen_port(&self) -> u16 {
        self.inner.listen_port
    }

    pub fn tls_fingerprint(&self) -> Fingerprint {
        self.inner.tls.fingerprint()
    }

    pub fn public_key(&self) -> String {
        self.inner.identity.public_key_base64()
    }

    pub fn public_key_fingerprint(&self) -> Fingerprint {
        self.inner.identity.fingerprint()
    }

    pub fn config(&self) -> &NodeConfig {
        &self.inner.config
    }

    pub fn store(&self) -> &Store {
        &self.inner.store
    }

    pub fn subscribe(&self) -> broadcast::Receiver<NodeEvent> {
        self.inner.events.subscribe()
    }

    pub fn shutdown(&self) {
        self.inner.shutdown.cancel();
        {
            let mut spatial = self.inner.spatial.lock().unwrap_or_else(|p| p.into_inner());
            for token in spatial.cancels.values() {
                token.cancel();
            }
            spatial.cancels.clear();
        }
        {
            let mut controller = self
                .inner
                .controller
                .lock()
                .unwrap_or_else(|p| p.into_inner());
            for token in controller.cancels.values() {
                token.cancel();
            }
            for token in controller.cancel_requests.values() {
                token.cancel();
            }
            for token in controller.disconnects.values() {
                token.cancel();
            }
            controller.cancels.clear();
            controller.cancel_requests.clear();
            controller.disconnects.clear();
        }
        {
            let mut pending = self.inner.pending.lock().unwrap_or_else(|p| p.into_inner());
            for (_, entry) in pending.drain() {
                let _ = entry.sender.send(false);
            }
        }
        if let Some(mut discovery) = self
            .inner
            .discovery
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .take()
        {
            discovery.stop();
        }
        self.cancel_pairing();
    }

    // ---------------------------------------------------------------- events

    fn emit(&self, event: NodeEvent) {
        let _ = self.inner.events.send(event);
    }

    fn publish_record(&self, record: &TransferRecord) {
        if let Err(error) = self.inner.store.upsert_transfer(record) {
            warn!("could not persist transfer {}: {}", record.id, error);
        }
        let duration_ms = record
            .completed_at
            .map(|completed| completed.saturating_sub(record.created_at));
        let throughput_bytes_per_second = duration_ms
            .filter(|duration| *duration > 0)
            .map(|duration| record.bytes_transferred.saturating_mul(1000) / duration);
        tracing::info!(
            target: "flowww.transfer",
            event = "transfer_state",
            transfer_id = %record.id,
            direction = record.direction.as_str(),
            route = record.route.as_str(),
            status = record.status.as_str(),
            bytes_transferred = record.bytes_transferred,
            total_bytes = record.size,
            duration_ms = ?duration_ms,
            throughput_bytes_per_second = ?throughput_bytes_per_second,
            failure = record.error.is_some(),
            "transfer state updated"
        );
        self.emit(NodeEvent::TransferUpdated(record.clone()));
    }

    // ------------------------------------------------------------- discovery

    /// Start LAN discovery (mDNS) if it is not already running.
    ///
    /// Discovery is a bidirectional broadcast of device metadata only: peers
    /// discovered this way are not trusted until pairing succeeds. Failure to
    /// start degrades to manual pairing and is not fatal.
    pub fn start_discovery(&self) -> NodeResult<()> {
        if self
            .inner
            .discovery
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .is_some()
        {
            return Ok(());
        }
        let (discovery, mut receiver) = Discovery::start(
            &self.inner.device,
            self.inner.identity.fingerprint(),
            self.inner.tls.fingerprint(),
            self.inner.listen_port,
        )?;
        *self
            .inner
            .discovery
            .lock()
            .unwrap_or_else(|p| p.into_inner()) = Some(discovery);

        let node = self.clone();
        let shutdown = self.inner.shutdown.clone();
        tokio::spawn(async move {
            loop {
                tokio::select! {
                    _ = shutdown.cancelled() => break,
                    event = receiver.recv() => match event {
                        Ok(DiscoveryEvent::Found(peer)) => node.on_peer_found(*peer).await,
                        Ok(DiscoveryEvent::Lost(device_id)) => node.on_peer_lost(&device_id).await,
                        Err(broadcast::error::RecvError::Closed) => break,
                        Err(broadcast::error::RecvError::Lagged(_)) => continue,
                    }
                }
            }
        });
        Ok(())
    }

    /// Stop LAN discovery if it is running. Returns true when a running
    /// discovery task was stopped.
    pub fn stop_discovery(&self) -> bool {
        self.inner
            .discovery
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .take()
            .map(|mut discovery| discovery.stop())
            .is_some()
    }

    /// Cancel an ordinary, non-spatial transfer that is currently streaming.
    ///
    /// Returns true when a running transfer was flagged for cancellation. The
    /// streaming loop observes the token at chunk boundaries: the record is
    /// marked `Cancelled`, the session is dropped (closing the connection to
    /// the peer), and the in-memory replay authorisation of that transfer is
    /// not consumed again.
    ///
    /// Returns false for transfers that are not streaming right now (already
    /// finished, spatial-only transfers — see `cancel_spatial`, or offers the
    /// user has not answered yet — decline those with
    /// `respond_to_offer(_, false)`).
    pub fn cancel_transfer(&self, transfer_id: &TransferId) -> bool {
        let cancel = self
            .inner
            .transfer_cancels
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .get(transfer_id)
            .cloned();
        cancel.map(|token| token.cancel()).is_some()
    }

    async fn on_peer_found(&self, peer: DiscoveredPeer) {
        let paired = self
            .inner
            .trust
            .by_device_id(&peer.device.device_id)
            .is_some();
        let view = PeerView::new(&peer, paired);
        self.inner
            .peers
            .write()
            .await
            .insert(peer.device.device_id.as_str().to_string(), peer.clone());
        if let Some(addr) = peer.endpoint() {
            self.inner
                .endpoints
                .write()
                .await
                .insert(peer.device.device_id.as_str().to_string(), addr);
        }

        if let Some(target) = marker_for(&peer) {
            let spatial = {
                let mut state = self.inner.spatial.lock().unwrap_or_else(|p| p.into_inner());
                state.registry.upsert(target).ok();
                spatial_view(&mut state)
            };
            self.emit(NodeEvent::SpatialUpdated(spatial));
        }
        self.emit(NodeEvent::PeerFound(view));
    }

    async fn on_peer_lost(&self, device_id: &DeviceId) {
        self.inner.peers.write().await.remove(device_id.as_str());
        let spatial = {
            let mut state = self.inner.spatial.lock().unwrap_or_else(|p| p.into_inner());
            state.registry.remove(device_id);
            spatial_view(&mut state)
        };
        self.emit(NodeEvent::device_lost(device_id));
        self.emit(NodeEvent::SpatialUpdated(spatial));
    }

    pub async fn discovered_peers(&self) -> Vec<PeerView> {
        let peers = self.inner.peers.read().await;
        let mut views = peers
            .values()
            .map(|peer| {
                let paired = self
                    .inner
                    .trust
                    .by_device_id(&peer.device.device_id)
                    .is_some();
                PeerView::new(peer, paired)
            })
            .collect::<Vec<_>>();
        views.sort_by(|left, right| left.name.cmp(&right.name));
        views
    }

    // -------------------------------------------------------------- listener

    fn spawn_accept_loop(&self) {
        let node = self.clone();
        let inner = self.inner.clone();
        tokio::spawn(async move {
            loop {
                let accepted = tokio::select! {
                    _ = inner.shutdown.cancelled() => break,
                    result = inner.listener.accept(&inner.device, &inner.identity) => result,
                };
                match accepted {
                    Ok(session) => {
                        debug!("authenticated peer {}", session.peer_id());
                        let node = node.clone();
                        tokio::spawn(async move { node.handle_session(session).await });
                    }
                    Err(error) => {
                        if !matches!(error, flowww_network::NetworkError::Closed) {
                            debug!("incoming connection rejected: {}", error);
                        }
                        tokio::time::sleep(Duration::from_millis(100)).await;
                    }
                }
            }
            debug!("peer listener stopped");
        });
    }

    async fn handle_session(&self, mut session: PeerSession) {
        let first = timeout(OFFER_REPLY_TTL, session.recv_message()).await;
        let message = match first {
            Ok(Ok(message)) => message,
            Ok(Err(error)) => {
                debug!(
                    "peer {} disconnected before an offer: {}",
                    session.peer_id(),
                    error
                );
                return;
            }
            Err(_) => {
                let _ = session
                    .send_message(Message::Error {
                        code: ErrorCode::Protocol,
                        message: "timed out waiting for an offer".into(),
                        transfer_id: None,
                    })
                    .await;
                return;
            }
        };

        if let Err(error) = message.validate() {
            let _ = session
                .send_message(Message::Error {
                    code: ErrorCode::Protocol,
                    message: error.to_string(),
                    transfer_id: message.transfer_id(),
                })
                .await;
            return;
        }

        let (
            transfer_id,
            source_device,
            destination_device,
            content,
            action,
            route,
            expires_at,
            authorization,
        ) = match message {
            Message::TransferOffer {
                transfer_id,
                source_device,
                destination_device,
                content,
                action,
                route,
                expires_at,
                authorization,
            } => (
                transfer_id,
                source_device,
                destination_device,
                content,
                action,
                route,
                expires_at,
                authorization,
            ),
            Message::SpatialCaptureRequest {
                request_id,
                source_device,
                controller_device,
            } => {
                self.handle_spatial_capture(
                    &mut session,
                    request_id,
                    source_device,
                    controller_device,
                )
                .await;
                return;
            }
            Message::SpatialTransferCommand {
                command,
                capability,
            } => {
                self.handle_spatial_command(session, command, capability)
                    .await;
                return;
            }
            Message::SpatialOffer {
                transfer_id,
                source_device,
                controller_device,
                destination_device,
                content,
                action,
                route,
                expires_at,
                authorization,
                capability,
                command,
            } => {
                self.handle_spatial_offer(
                    session,
                    SpatialOfferParts {
                        transfer_id,
                        source_device,
                        controller_device,
                        destination_device,
                        content,
                        action,
                        route,
                        expires_at,
                        authorization,
                        capability,
                        command,
                    },
                )
                .await;
                return;
            }
            Message::SpatialCancel { transfer_id, .. } => {
                self.cancel_local_spatial(&transfer_id);
                return;
            }
            other => {
                let _ = session
                    .send_message(Message::Error {
                        code: ErrorCode::Protocol,
                        message: format!(
                            "expected TRANSFER_OFFER or a spatial message, got {}",
                            other.kind()
                        ),
                        transfer_id: other.transfer_id(),
                    })
                    .await;
                return;
            }
        };

        let peer_id = session.peer_id().clone();
        let peer_public = session.peer_public().clone();
        if let Err(reason) = self.validate_offer(
            &peer_id,
            &peer_public,
            OfferVerification {
                source_device: &source_device,
                destination_device: &destination_device,
                content: &content,
                action,
                expires_at,
                authorization: &authorization,
            },
        ) {
            self.reject_offer(&mut session, transfer_id, reason).await;
            return;
        }

        let now = unix_millis();
        let mut record = TransferRecord {
            id: transfer_id,
            source_device: source_device.clone(),
            destination_device: destination_device.clone(),
            filename: content.inline.is_none().then(|| content.name.clone()),
            mime_type: Some(content.mime_type.clone()),
            size: content.size,
            sha256: content.sha256.map(|hash| hash.to_hex()),
            kind: content.kind.as_str().to_string(),
            status: TransferState::Offered,
            route,
            direction: TransferDirection::Incoming,
            bytes_transferred: 0,
            inline_content: None,
            error: None,
            created_at: now,
            completed_at: None,
        };
        self.publish_record(&record);

        let accepted = if self.auto_accept() {
            true
        } else {
            let (sender, receiver) = oneshot::channel();
            let view = PendingOfferView {
                transfer_id,
                source: session.peer().clone(),
                content: content.clone(),
                route,
                action,
            };
            self.inner
                .pending
                .lock()
                .unwrap_or_else(|p| p.into_inner())
                .insert(
                    transfer_id,
                    PendingEntry {
                        sender,
                        view: view.clone(),
                    },
                );
            self.emit(NodeEvent::OfferReceived(view));
            let decision = timeout(OFFER_APPROVAL_TTL, receiver).await;
            self.inner
                .pending
                .lock()
                .unwrap_or_else(|p| p.into_inner())
                .remove(&transfer_id);
            self.emit(NodeEvent::OfferClosed { transfer_id });
            matches!(decision, Ok(Ok(true)))
        };

        if !accepted {
            self.reject_offer(&mut session, transfer_id, "the offer was not accepted")
                .await;
            record.status = TransferState::Cancelled;
            record.error = Some("declined or timed out".into());
            record.completed_at = Some(unix_millis());
            self.publish_record(&record);
            return;
        }

        self.receive_offer(session, transfer_id, content, action, record)
            .await;
    }

    fn validate_offer(
        &self,
        peer_id: &DeviceId,
        peer_public: &PublicIdentity,
        offer: OfferVerification<'_>,
    ) -> Result<(), String> {
        let OfferVerification {
            source_device,
            destination_device,
            content,
            action,
            expires_at,
            authorization,
        } = offer;
        if destination_device != self.device_id() {
            return Err("the offer was addressed to another device".into());
        }
        if source_device != peer_id {
            return Err("the offer source does not match the authenticated peer".into());
        }
        if source_device == destination_device {
            return Err("the source and destination are the same device".into());
        }
        if content.source_device != *source_device {
            return Err("the content source does not match the offer".into());
        }
        if flowww_core::is_expired(expires_at) {
            return Err("the offer has expired".into());
        }
        content.validate().map_err(|error| error.to_string())?;
        let expectation = AuthorizationExpectation {
            source_device,
            destination_device,
            content_id: &content.content_id,
            transfer_id: authorization.claim.transfer_id,
            action,
            content_size: content.size,
            max_size: content.size,
        };
        verify_authorization(authorization, peer_public, &expectation)
            .map_err(|error| error.to_string())?;
        if authorization.claim.expires_at != expires_at {
            return Err("the authorization expiry does not match the offer".into());
        }
        let fresh = self
            .inner
            .replay
            .accept(&authorization.claim.nonce, authorization.claim.expires_at);
        let persisted = self
            .inner
            .store
            .record_nonce(&authorization.claim.nonce, authorization.claim.expires_at)
            .map_err(|error| error.to_string())?;
        if !fresh || !persisted {
            return Err("the authorization has already been used".into());
        }
        Ok(())
    }

    async fn reject_offer(
        &self,
        session: &mut PeerSession,
        transfer_id: TransferId,
        reason: impl Into<String>,
    ) {
        let reason = truncate_reason(&reason.into());
        let _ = session
            .send_message(Message::TransferReject {
                transfer_id,
                reason,
            })
            .await;
    }

    async fn receive_offer(
        &self,
        mut session: PeerSession,
        transfer_id: TransferId,
        content: Content,
        _action: TransferAction,
        mut record: TransferRecord,
    ) {
        if content.inline.is_some() {
            self.receive_inline_offer(session, transfer_id, content, record)
                .await;
            return;
        }

        let Some(hash) = content.sha256 else {
            let _ = session
                .send_message(Message::TransferReject {
                    transfer_id,
                    reason: "the offer did not include a file hash".into(),
                })
                .await;
            record.status = TransferState::Failed;
            record.error = Some("missing file hash".into());
            record.completed_at = Some(unix_millis());
            self.publish_record(&record);
            return;
        };

        let incoming = match IncomingFile::open(
            &self.inner.config.download_dir,
            &content.name,
            transfer_id,
            content.size,
            hash,
        )
        .await
        {
            Ok(incoming) => incoming,
            Err(error) => {
                let _ = session
                    .send_message(Message::TransferReject {
                        transfer_id,
                        reason: truncate_reason(&format!(
                            "receiver could not open the file: {error}"
                        )),
                    })
                    .await;
                record.status = TransferState::Failed;
                record.error = Some(error.to_string());
                record.completed_at = Some(unix_millis());
                self.publish_record(&record);
                return;
            }
        };

        let resume_from = incoming.received();
        if let Err(error) = session
            .send_message(Message::TransferAccept {
                transfer_id,
                resume_from,
            })
            .await
        {
            record.status = TransferState::Failed;
            record.error = Some(error.to_string());
            record.completed_at = Some(unix_millis());
            self.publish_record(&record);
            return;
        }
        record.status = TransferState::Accepted;
        record.bytes_transferred = resume_from;
        self.publish_record(&record);

        let mut live = record.clone();
        let cancel = CancellationToken::new();
        self.inner
            .transfer_cancels
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .insert(transfer_id, cancel.clone());
        let result = {
            let mut on_progress = |progress: Progress| {
                live.bytes_transferred = progress.transferred;
                live.status = TransferState::Transferring;
                self.publish_record(&live);
            };
            tokio::select! {
                _ = cancel.cancelled() => Err(flowww_transfer::TransferError::Cancelled),
                outcome = receive_file(&mut session, transfer_id, incoming, &mut on_progress) => outcome,
            }
        };
        self.inner
            .transfer_cancels
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .remove(&transfer_id);

        match result {
            Ok(completed) => {
                live.status = TransferState::Completed;
                live.bytes_transferred = completed.size;
                live.size = completed.size;
                live.sha256 = Some(completed.sha256.to_hex());
                live.filename = completed
                    .path
                    .file_name()
                    .map(|name| name.to_string_lossy().to_string());
                live.completed_at = Some(unix_millis());
                self.publish_record(&live);
                info!(
                    "received {} from {}",
                    completed.path.display(),
                    session.peer_id()
                );
            }
            Err(error) => {
                live.status = if matches!(&error, flowww_transfer::TransferError::Cancelled) {
                    TransferState::Cancelled
                } else {
                    TransferState::Failed
                };
                live.error = Some(error.to_string());
                live.completed_at = Some(unix_millis());
                self.publish_record(&live);
                let _ = session
                    .send_message(Message::Error {
                        code: ErrorCode::Storage,
                        message: truncate_reason(&error.to_string()),
                        transfer_id: Some(transfer_id),
                    })
                    .await;
            }
        }
    }

    async fn receive_inline_offer(
        &self,
        mut session: PeerSession,
        transfer_id: TransferId,
        content: Content,
        mut record: TransferRecord,
    ) {
        let payload = content.inline.clone().unwrap_or_default();
        if let Err(error) = session
            .send_message(Message::TransferAccept {
                transfer_id,
                resume_from: 0,
            })
            .await
        {
            record.status = TransferState::Failed;
            record.error = Some(error.to_string());
            record.completed_at = Some(unix_millis());
            self.publish_record(&record);
            return;
        }
        record.status = TransferState::Accepted;
        self.publish_record(&record);

        let cancel = CancellationToken::new();
        self.inner
            .transfer_cancels
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .insert(transfer_id, cancel.clone());
        let outcome = tokio::select! {
            _ = cancel.cancelled() => Err(flowww_transfer::TransferError::Cancelled),
            outcome = receive_inline(&mut session, transfer_id, payload.as_bytes()) => outcome,
        };
        self.inner
            .transfer_cancels
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .remove(&transfer_id);

        match outcome {
            Ok(bytes) => {
                record.status = TransferState::Completed;
                record.bytes_transferred = bytes.len() as u64;
                record.size = bytes.len() as u64;
                record.inline_content = Some(String::from_utf8_lossy(&bytes).to_string());
                record.completed_at = Some(unix_millis());
                self.publish_record(&record);
                info!("received inline content from {}", session.peer_id());
            }
            Err(error) => {
                record.status = if matches!(&error, flowww_transfer::TransferError::Cancelled) {
                    TransferState::Cancelled
                } else {
                    TransferState::Failed
                };
                record.error = Some(error.to_string());
                record.completed_at = Some(unix_millis());
                self.publish_record(&record);
            }
        }
    }

    // ------------------------------------------------- spatial v2 handlers

    async fn handle_spatial_capture(
        &self,
        session: &mut PeerSession,
        request_id: Uuid,
        source_device: DeviceId,
        controller_device: DeviceId,
    ) {
        let deny = |reason: &str| Message::SpatialCaptureDeny {
            request_id,
            reason: truncate_reason(reason),
        };
        if source_device != *self.device_id()
            || controller_device != *session.peer_id()
            || controller_device == source_device
        {
            let _ = session
                .send_message(deny("the capture request names the wrong devices"))
                .await;
            return;
        }
        if self.inner.trust.by_device_id(&controller_device).is_none() {
            let _ = session
                .send_message(deny("the controller is not paired"))
                .await;
            return;
        }

        let now = unix_millis();
        let held = {
            let mut state = self.inner.spatial.lock().unwrap_or_else(|p| p.into_inner());
            state.session.expire_if_needed(now);
            state.held_handle.as_ref().and_then(|handle| {
                if state.vault.live(handle, now).is_err() {
                    return None;
                }
                state
                    .session
                    .held()
                    .map(|held| (handle.clone(), held.content.clone(), held.expires_at))
            })
        };
        let Some((handle, content, hold_expires_at)) = held else {
            let _ = session
                .send_message(deny("the source is not holding content"))
                .await;
            return;
        };
        let ttl = hold_expires_at.saturating_sub(now);
        if ttl == 0 {
            let _ = session
                .send_message(deny("the held content has expired"))
                .await;
            return;
        }
        match ContentCapability::issue(
            &self.inner.identity,
            handle,
            controller_device,
            content,
            ttl,
        ) {
            Ok(capability) => {
                let _ = session
                    .send_message(Message::SpatialCaptureGrant {
                        request_id,
                        capability,
                    })
                    .await;
            }
            Err(error) => {
                let _ = session
                    .send_message(deny(&format!("could not issue a capability: {error}")))
                    .await;
            }
        }
    }

    async fn handle_spatial_command(
        &self,
        mut session: PeerSession,
        command: SpatialCommand,
        capability: ContentCapability,
    ) {
        let transfer_id = command.claim.transfer_id;
        let controller_id = session.peer_id().clone();
        let controller_public = session.peer_public().clone();
        let delivery = match self
            .authorize_spatial_command(&controller_id, &controller_public, &command, &capability)
            .await
        {
            Ok(delivery) => delivery,
            Err(reason) => {
                let _ = session
                    .send_message(Message::SpatialCommandRejected {
                        transfer_id,
                        reason: truncate_reason(&reason),
                    })
                    .await;
                return;
            }
        };
        if session
            .send_message(Message::SpatialCommandAccepted {
                transfer_id,
                route: delivery.route,
            })
            .await
            .is_err()
        {
            return;
        }

        let cancel = CancellationToken::new();
        self.inner
            .spatial
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .cancels
            .insert(transfer_id, cancel.clone());

        let mut record = spatial_record(
            transfer_id,
            self.device_id().clone(),
            delivery.destination.clone(),
            &delivery.content,
            delivery.route,
            TransferDirection::Outgoing,
            TransferState::Connecting,
        );
        self.publish_record(&record);
        send_spatial_status(
            &mut session,
            transfer_id,
            TransferState::Transferring,
            0,
            None,
        )
        .await;

        let outcome = tokio::select! {
            _ = cancel.cancelled() => Err("the transfer was cancelled".to_string()),
            result = self.deliver_spatial(&mut session, &delivery, &mut record, &cancel) => result,
        };

        match outcome {
            Ok(()) => {
                record.status = TransferState::Completed;
                record.bytes_transferred = delivery.content.size;
                record.completed_at = Some(unix_millis());
                self.publish_record(&record);
                send_spatial_status(
                    &mut session,
                    transfer_id,
                    TransferState::Completed,
                    delivery.content.size,
                    None,
                )
                .await;
            }
            Err(reason) => {
                let cancelled = cancel.is_cancelled();
                record.status = if cancelled {
                    TransferState::Cancelled
                } else {
                    TransferState::Failed
                };
                record.error = Some(reason.clone());
                record.completed_at = Some(unix_millis());
                self.publish_record(&record);
                if cancelled {
                    let _ = session
                        .send_message(Message::SpatialCancel {
                            transfer_id,
                            reason: Some(truncate_reason(&reason)),
                        })
                        .await;
                } else {
                    send_spatial_status(
                        &mut session,
                        transfer_id,
                        TransferState::Failed,
                        record.bytes_transferred,
                        Some(&reason),
                    )
                    .await;
                }
            }
        }

        self.inner
            .spatial
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .cancels
            .remove(&transfer_id);
    }

    async fn authorize_spatial_command(
        &self,
        controller_id: &DeviceId,
        controller_public: &PublicIdentity,
        command: &SpatialCommand,
        capability: &ContentCapability,
    ) -> Result<SpatialDelivery, String> {
        let claim = &command.claim;
        if claim.controller_device != *controller_id {
            return Err("the command was not signed by the connected controller".into());
        }
        command
            .verify(controller_public)
            .map_err(|error| error.to_string())?;
        if claim.source_device != *self.device_id() {
            return Err("the command names another source device".into());
        }
        capability
            .verify(&self.inner.identity.public_identity())
            .map_err(|error| error.to_string())?;
        if capability.claim.source_device != claim.source_device
            || capability.claim.controller_device != claim.controller_device
        {
            return Err("the capability is bound to different devices".into());
        }

        let now = unix_millis();
        let staged = {
            let mut state = self.inner.spatial.lock().unwrap_or_else(|p| p.into_inner());
            state.session.expire_if_needed(now);
            state
                .vault
                .live(&claim.handle_id, now)
                .map_err(|error| error.to_string())?
                .clone()
        };
        let expectation = SpatialExpectation {
            source_device: &claim.source_device,
            controller_device: &claim.controller_device,
            destination_device: &claim.destination_device,
            transfer_id: claim.transfer_id,
            content: &staged.content,
        };
        verify_spatial_proof(
            command,
            capability,
            &self.inner.identity.public_identity(),
            controller_public,
            &expectation,
        )
        .map_err(|error| error.to_string())?;

        self.paired_peer(&claim.destination_device)
            .map_err(|_| "the destination is not paired with the source".to_string())?;

        let direct = self.lookup_peer_endpoint(&claim.destination_device).await;
        let relay_endpoint = claim
            .controller_endpoint
            .as_deref()
            .and_then(|raw| SocketAddr::from_str(raw).ok())
            .or(self.lookup_peer_endpoint(&claim.controller_device).await);
        let route = if direct.is_some() {
            TransferRoute::Direct
        } else if relay_endpoint.is_some() {
            TransferRoute::PhoneRelay
        } else {
            return Err("neither a direct nor a relay route is available".into());
        };

        if !self.inner.replay.accept(&claim.nonce, claim.expires_at) {
            return Err("the spatial command has already been used".into());
        }
        let persisted = self
            .inner
            .store
            .record_nonce(&claim.nonce, claim.expires_at)
            .map_err(|error| error.to_string())?;
        if !persisted {
            return Err("the spatial command has already been used".into());
        }

        let consumed = {
            let mut state = self.inner.spatial.lock().unwrap_or_else(|p| p.into_inner());
            let entry = state
                .vault
                .consume(&claim.handle_id, now)
                .map_err(|error| error.to_string())?;
            if state.held_handle.as_ref() == Some(&claim.handle_id) {
                state.session.drop_content().ok();
                state.held_handle = None;
            }
            entry
        };
        if consumed.content.inline.is_none() && consumed.local_path.is_none() {
            return Err("the staged content has no local bytes".into());
        }
        if let Some(path) = &consumed.local_path {
            if !path.exists() {
                return Err("the staged local file no longer exists".into());
            }
        }

        let authorization = TransferAuthorization::sign(
            &self.inner.identity,
            TransferClaim::new_with_expiry(
                self.device_id().clone(),
                claim.destination_device.clone(),
                consumed.content.content_id.clone(),
                claim.transfer_id,
                TransferAction::Save,
                consumed.content.size,
                claim.expires_at,
            ),
        )
        .map_err(|error| error.to_string())?;

        Ok(SpatialDelivery {
            transfer_id: claim.transfer_id,
            destination: claim.destination_device.clone(),
            content: consumed.content,
            route,
            authorization,
            capability: capability.clone(),
            command: command.clone(),
            expires_at: claim.expires_at,
            local_path: consumed.local_path,
            relay_endpoint,
        })
    }

    async fn deliver_spatial(
        &self,
        status_session: &mut PeerSession,
        delivery: &SpatialDelivery,
        record: &mut TransferRecord,
        cancel: &CancellationToken,
    ) -> Result<(), String> {
        let mut route = delivery.route;
        let mut attempts = 0;
        loop {
            if cancel.is_cancelled() {
                return Err("the transfer was cancelled".into());
            }
            send_spatial_status(
                status_session,
                delivery.transfer_id,
                TransferState::Transferring,
                record.bytes_transferred,
                None,
            )
            .await;
            let attempt = tokio::select! {
                _ = cancel.cancelled() => Err(AttemptError::Unavailable(
                    "the transfer was cancelled".into(),
                )),
                watch = watch_controller(status_session) => {
                    if matches!(watch, ControllerWatch::Cancelled) {
                        cancel.cancel();
                    }
                    Err(AttemptError::Unavailable(watch.reason().to_string()))
                },
                result = self.attempt_spatial_delivery(route, delivery, record, cancel) => result,
            };
            match attempt {
                Ok(()) => return Ok(()),
                Err(AttemptError::Rejected(reason)) => return Err(reason),
                Err(AttemptError::Unavailable(reason)) => {
                    if route == TransferRoute::Direct {
                        if attempts + 1 < SPATIAL_DIRECT_ATTEMPTS {
                            attempts += 1;
                            tokio::time::sleep(Duration::from_millis(50)).await;
                            continue;
                        }
                        if delivery.relay_endpoint.is_some() {
                            route = TransferRoute::PhoneRelay;
                            record.route = route;
                            self.publish_record(record);
                            attempts = 0;
                            continue;
                        }
                    }
                    return Err(reason);
                }
            }
        }
    }

    async fn attempt_spatial_delivery(
        &self,
        route: TransferRoute,
        delivery: &SpatialDelivery,
        record: &mut TransferRecord,
        cancel: &CancellationToken,
    ) -> Result<(), AttemptError> {
        let (addr, peer) = match route {
            TransferRoute::Direct => {
                let addr = self
                    .lookup_peer_endpoint(&delivery.destination)
                    .await
                    .ok_or_else(|| {
                        AttemptError::Unavailable("the destination is unreachable".into())
                    })?;
                let peer = self
                    .paired_peer(&delivery.destination)
                    .map_err(|error| AttemptError::Unavailable(error.to_string()))?;
                (addr, peer)
            }
            TransferRoute::PhoneRelay => {
                let controller = delivery.command.claim.controller_device.clone();
                let addr = delivery
                    .relay_endpoint
                    .ok_or_else(|| AttemptError::Unavailable("the relay is unreachable".into()))?;
                let peer = self
                    .paired_peer(&controller)
                    .map_err(|error| AttemptError::Unavailable(error.to_string()))?;
                (addr, peer)
            }
            _ => {
                return Err(AttemptError::Rejected(
                    "the selected spatial route is not supported".into(),
                ))
            }
        };
        let mut session = connect(
            addr,
            &peer,
            &self.inner.device,
            &self.inner.identity,
            &self.inner.tls,
        )
        .await
        .map_err(|error| AttemptError::Unavailable(error.to_string()))?;

        session
            .send_message(Message::SpatialOffer {
                transfer_id: delivery.transfer_id,
                source_device: delivery.command.claim.source_device.clone(),
                controller_device: delivery.command.claim.controller_device.clone(),
                destination_device: delivery.destination.clone(),
                content: delivery.content.clone(),
                action: TransferAction::Save,
                route,
                expires_at: delivery.expires_at,
                authorization: delivery.authorization.clone(),
                capability: delivery.capability.clone(),
                command: delivery.command.clone(),
            })
            .await
            .map_err(|error| AttemptError::Unavailable(error.to_string()))?;

        let resume_from = match timeout(OFFER_REPLY_TTL, session.recv_message())
            .await
            .map_err(|_| AttemptError::Unavailable("the destination did not answer".into()))?
            .map_err(|error| AttemptError::Unavailable(error.to_string()))?
        {
            Message::TransferAccept {
                transfer_id,
                resume_from,
            } => {
                if transfer_id != delivery.transfer_id {
                    return Err(AttemptError::Rejected(
                        "the destination accepted another transfer".into(),
                    ));
                }
                resume_from
            }
            Message::TransferReject { reason, .. } => return Err(AttemptError::Rejected(reason)),
            Message::Error { message, .. } => return Err(AttemptError::Rejected(message)),
            other => {
                return Err(AttemptError::Rejected(format!(
                    "the destination sent {} instead of an answer",
                    other.kind()
                )))
            }
        };
        if resume_from > delivery.content.size {
            return Err(AttemptError::Rejected(
                "the destination asked to resume past the end of the content".into(),
            ));
        }
        record.route = route;
        record.status = TransferState::Transferring;
        record.bytes_transferred = resume_from;
        self.publish_record(record);

        match &delivery.local_path {
            Some(path) => {
                let sha256 = delivery
                    .content
                    .sha256
                    .ok_or_else(|| AttemptError::Rejected("the content has no hash".into()))?;
                let mut on_progress = |progress: Progress| {
                    record.bytes_transferred = progress.transferred;
                    record.status = TransferState::Transferring;
                    self.publish_record(record);
                };
                stream_file(
                    &mut session,
                    delivery.transfer_id,
                    path,
                    delivery.content.size,
                    sha256,
                    resume_from,
                    &mut on_progress,
                )
                .await
                .map_err(|error| match error {
                    flowww_transfer::TransferError::Cancelled
                    | flowww_transfer::TransferError::PeerError(_)
                    | flowww_transfer::TransferError::HashMismatch
                    | flowww_transfer::TransferError::SizeMismatch { .. } => {
                        AttemptError::Unavailable(error.to_string())
                    }
                    other => AttemptError::Unavailable(other.to_string()),
                })?;
            }
            None => {
                let payload = delivery.content.inline.as_deref().ok_or_else(|| {
                    AttemptError::Rejected("the inline content is missing".into())
                })?;
                if resume_from != 0 {
                    return Err(AttemptError::Rejected(
                        "the destination asked to resume inline content".into(),
                    ));
                }
                stream_inline(&mut session, delivery.transfer_id, payload.as_bytes())
                    .await
                    .map_err(|error| AttemptError::Unavailable(error.to_string()))?;
            }
        }
        if cancel.is_cancelled() {
            return Err(AttemptError::Unavailable(
                "the transfer was cancelled".into(),
            ));
        }
        Ok(())
    }

    async fn handle_spatial_offer(&self, mut session: PeerSession, offer: SpatialOfferParts) {
        if offer.controller_device == *self.device_id() && offer.route == TransferRoute::PhoneRelay
        {
            self.handle_relay_offer(session, offer).await;
            return;
        }
        if offer.destination_device != *self.device_id() {
            let _ = session
                .send_message(Message::TransferReject {
                    transfer_id: offer.transfer_id,
                    reason: "the spatial offer was addressed to another device".into(),
                })
                .await;
            return;
        }
        let resumable = match self.validate_spatial_offer(&session, &offer) {
            Ok(resumable) => resumable,
            Err(reason) => {
                let _ = session
                    .send_message(Message::TransferReject {
                        transfer_id: offer.transfer_id,
                        reason: truncate_reason(&reason),
                    })
                    .await;
                return;
            }
        };

        let existing = self
            .inner
            .store
            .get_transfer(offer.transfer_id)
            .ok()
            .flatten();
        let mut record = match &existing {
            Some(record) => {
                let mut record = record.clone();
                record.route = offer.route;
                record.status = TransferState::Offered;
                record.error = None;
                record
            }
            None => spatial_record(
                offer.transfer_id,
                offer.source_device.clone(),
                offer.destination_device.clone(),
                &offer.content,
                offer.route,
                TransferDirection::Incoming,
                TransferState::Offered,
            ),
        };
        self.publish_record(&record);

        let accepted = if self.auto_accept() || resumable {
            true
        } else {
            let (sender, receiver) = oneshot::channel();
            let source = self
                .paired_peer(&offer.source_device)
                .map(|peer| peer.device)
                .unwrap_or_else(|_| session.peer().clone());
            let view = PendingOfferView {
                transfer_id: offer.transfer_id,
                source,
                content: offer.content.clone(),
                route: offer.route,
                action: offer.action,
            };
            self.inner
                .pending
                .lock()
                .unwrap_or_else(|p| p.into_inner())
                .insert(
                    offer.transfer_id,
                    PendingEntry {
                        sender,
                        view: view.clone(),
                    },
                );
            self.emit(NodeEvent::OfferReceived(view));
            let decision = timeout(OFFER_APPROVAL_TTL, receiver).await;
            self.inner
                .pending
                .lock()
                .unwrap_or_else(|p| p.into_inner())
                .remove(&offer.transfer_id);
            self.emit(NodeEvent::OfferClosed {
                transfer_id: offer.transfer_id,
            });
            matches!(decision, Ok(Ok(true)))
        };

        if !accepted {
            let _ = session
                .send_message(Message::TransferReject {
                    transfer_id: offer.transfer_id,
                    reason: "the spatial offer was not accepted".into(),
                })
                .await;
            record.status = TransferState::Cancelled;
            record.error = Some("declined or timed out".into());
            record.completed_at = Some(unix_millis());
            self.publish_record(&record);
            return;
        }

        self.receive_offer(
            session,
            offer.transfer_id,
            offer.content,
            offer.action,
            record,
        )
        .await;
    }

    fn validate_spatial_offer(
        &self,
        session: &PeerSession,
        offer: &SpatialOfferParts,
    ) -> Result<bool, String> {
        let source_peer = self
            .paired_peer(&offer.source_device)
            .map_err(|_| "the source is not paired with the destination".to_string())?;
        let controller_peer = self
            .paired_peer(&offer.controller_device)
            .map_err(|_| "the controller is not paired with the destination".to_string())?;
        match offer.route {
            TransferRoute::Direct if *session.peer_id() != offer.source_device => {
                return Err("a direct spatial offer must come from its source".into());
            }
            TransferRoute::PhoneRelay if *session.peer_id() != offer.controller_device => {
                return Err("a relayed spatial offer must come from its controller".into());
            }
            TransferRoute::PhoneRelay | TransferRoute::Direct => {}
            _ => return Err("unsupported spatial route".into()),
        }

        let source_public = source_peer
            .public_identity()
            .map_err(|error| error.to_string())?;
        let controller_public = controller_peer
            .public_identity()
            .map_err(|error| error.to_string())?;
        let expectation = SpatialExpectation {
            source_device: &offer.source_device,
            controller_device: &offer.controller_device,
            destination_device: &offer.destination_device,
            transfer_id: offer.transfer_id,
            content: &offer.content,
        };
        verify_spatial_proof(
            &offer.command,
            &offer.capability,
            &source_public,
            &controller_public,
            &expectation,
        )
        .map_err(|error| error.to_string())?;
        let authorization_expectation = AuthorizationExpectation {
            source_device: &offer.source_device,
            destination_device: &offer.destination_device,
            content_id: &offer.content.content_id,
            transfer_id: offer.transfer_id,
            action: offer.action,
            content_size: offer.content.size,
            max_size: offer.content.size,
        };
        verify_authorization(
            &offer.authorization,
            &source_public,
            &authorization_expectation,
        )
        .map_err(|error| error.to_string())?;
        if offer.authorization.claim.expires_at != offer.expires_at
            || offer.command.claim.expires_at != offer.expires_at
        {
            return Err("the spatial expiries do not match".into());
        }

        let existing = self
            .inner
            .store
            .get_transfer(offer.transfer_id)
            .map_err(|error| error.to_string())?;
        if let Some(record) = &existing {
            if record.source_device != offer.source_device
                || record.destination_device != offer.destination_device
                || record.size != offer.content.size
                || record.sha256 != offer.content.sha256.map(|hash| hash.to_hex())
            {
                return Err("the transfer id is already bound to other content".into());
            }
            return match record.status {
                TransferState::Completed | TransferState::Cancelled => {
                    Err("the spatial command has already been used".into())
                }
                _ => Ok(true),
            };
        }

        let fresh_command = self
            .inner
            .store
            .record_nonce(&offer.command.claim.nonce, offer.command.claim.expires_at)
            .map_err(|error| error.to_string())?;
        if !fresh_command {
            return Err("the spatial command has already been used".into());
        }
        let fresh_authorization = self
            .inner
            .store
            .record_nonce(
                &offer.authorization.claim.nonce,
                offer.authorization.claim.expires_at,
            )
            .map_err(|error| error.to_string())?;
        if !fresh_authorization {
            return Err("the authorization has already been used".into());
        }
        Ok(false)
    }

    async fn handle_relay_offer(&self, mut upstream: PeerSession, offer: SpatialOfferParts) {
        let reject = |reason: &str| Message::TransferReject {
            transfer_id: offer.transfer_id,
            reason: truncate_reason(reason),
        };
        if *upstream.peer_id() != offer.source_device
            || offer.controller_device != *self.device_id()
            || offer.destination_device == *self.device_id()
        {
            let _ = upstream
                .send_message(reject("the relay names the wrong devices"))
                .await;
            return;
        }
        let entry = {
            let mut controller = self
                .inner
                .controller
                .lock()
                .unwrap_or_else(|p| p.into_inner());
            controller.journal.remove(&offer.transfer_id)
        };
        let Some(entry) = entry else {
            let _ = upstream
                .send_message(reject("no controller command prepared this relay"))
                .await;
            return;
        };
        let matches = entry.command == offer.command
            && entry.capability == offer.capability
            && entry.content == offer.content
            && entry.destination == offer.destination_device
            && entry.expires_at == offer.expires_at;
        if !matches || entry.expires_at <= unix_millis() {
            let _ = upstream
                .send_message(reject("the relay does not match the issued command"))
                .await;
            return;
        }

        let source_peer = match self.paired_peer(&offer.source_device) {
            Ok(peer) => peer,
            Err(_) => {
                let _ = upstream
                    .send_message(reject("the source is not paired with the relay"))
                    .await;
                return;
            }
        };
        let source_public = match source_peer.public_identity() {
            Ok(public) => public,
            Err(error) => {
                let _ = upstream.send_message(reject(&error.to_string())).await;
                return;
            }
        };
        let expectation = SpatialExpectation {
            source_device: &offer.source_device,
            controller_device: &offer.controller_device,
            destination_device: &offer.destination_device,
            transfer_id: offer.transfer_id,
            content: &offer.content,
        };
        if verify_spatial_proof(
            &offer.command,
            &offer.capability,
            &source_public,
            &self.inner.identity.public_identity(),
            &expectation,
        )
        .is_err()
        {
            let _ = upstream
                .send_message(reject("the relay proof is not valid"))
                .await;
            return;
        }
        let authorization_expectation = AuthorizationExpectation {
            source_device: &offer.source_device,
            destination_device: &offer.destination_device,
            content_id: &offer.content.content_id,
            transfer_id: offer.transfer_id,
            action: offer.action,
            content_size: offer.content.size,
            max_size: offer.content.size,
        };
        if verify_authorization(
            &offer.authorization,
            &source_public,
            &authorization_expectation,
        )
        .is_err()
        {
            let _ = upstream
                .send_message(reject("the relay authorization is not valid"))
                .await;
            return;
        }
        if self.paired_peer(&offer.destination_device).is_err() {
            let _ = upstream
                .send_message(reject("the destination is not paired with the relay"))
                .await;
            return;
        }
        let destination_addr = match self.peer_endpoint(&offer.destination_device).await {
            Ok(addr) => addr,
            Err(_) => {
                let _ = upstream
                    .send_message(reject("the destination is unreachable"))
                    .await;
                return;
            }
        };
        let destination_peer = match self.paired_peer(&offer.destination_device) {
            Ok(peer) => peer,
            Err(error) => {
                let _ = upstream.send_message(reject(&error.to_string())).await;
                return;
            }
        };
        let mut downstream = match connect(
            destination_addr,
            &destination_peer,
            &self.inner.device,
            &self.inner.identity,
            &self.inner.tls,
        )
        .await
        {
            Ok(session) => session,
            Err(error) => {
                let _ = upstream.send_message(reject(&error.to_string())).await;
                return;
            }
        };
        if let Err(error) = downstream
            .send_message(Message::SpatialOffer {
                transfer_id: offer.transfer_id,
                source_device: offer.source_device.clone(),
                controller_device: offer.controller_device.clone(),
                destination_device: offer.destination_device.clone(),
                content: offer.content.clone(),
                action: offer.action,
                route: TransferRoute::PhoneRelay,
                expires_at: offer.expires_at,
                authorization: offer.authorization.clone(),
                capability: offer.capability.clone(),
                command: offer.command.clone(),
            })
            .await
        {
            let _ = upstream.send_message(reject(&error.to_string())).await;
            return;
        }
        let resume_from = match timeout(OFFER_REPLY_TTL, downstream.recv_message()).await {
            Ok(Ok(Message::TransferAccept {
                transfer_id,
                resume_from,
            })) if transfer_id == offer.transfer_id => resume_from,
            Ok(Ok(Message::TransferReject { reason, .. })) => {
                let _ = upstream.send_message(reject(&reason)).await;
                return;
            }
            Ok(Ok(Message::Error { message, .. })) => {
                let _ = upstream.send_message(reject(&message)).await;
                return;
            }
            _ => {
                let _ = upstream
                    .send_message(reject("the destination did not accept the relay"))
                    .await;
                return;
            }
        };

        let (size, hash) = match offer.content.sha256 {
            Some(hash) => (offer.content.size, hash),
            None => {
                let Some(payload) = offer.content.inline.as_deref() else {
                    let _ = upstream
                        .send_message(reject("the relayed content has no payload"))
                        .await;
                    return;
                };
                (
                    payload.len() as u64,
                    flowww_transfer::hash_bytes(payload.as_bytes()),
                )
            }
        };
        if resume_from > size {
            let _ = upstream
                .send_message(reject("the destination asked to resume past the end"))
                .await;
            return;
        }
        let mut record = match self.inner.store.get_transfer(offer.transfer_id) {
            Ok(Some(mut record)) => {
                record.route = TransferRoute::PhoneRelay;
                record.status = TransferState::Transferring;
                record.bytes_transferred = resume_from;
                record
            }
            _ => spatial_record(
                offer.transfer_id,
                offer.source_device.clone(),
                offer.destination_device.clone(),
                &offer.content,
                TransferRoute::PhoneRelay,
                TransferDirection::Outgoing,
                TransferState::Transferring,
            ),
        };
        self.publish_record(&record);
        if let Err(error) = upstream
            .send_message(Message::TransferAccept {
                transfer_id: offer.transfer_id,
                resume_from,
            })
            .await
        {
            debug!("relay controller disconnected: {}", error);
            record.status = TransferState::Failed;
            record.error = Some("the controller disconnected".into());
            record.completed_at = Some(unix_millis());
            self.publish_record(&record);
            return;
        }

        let cancel = CancellationToken::new();
        self.inner
            .controller
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .cancels
            .insert(offer.transfer_id, cancel.clone());
        let outcome = {
            let mut on_progress = |progress: Progress| {
                record.bytes_transferred = progress.transferred;
                record.status = TransferState::Transferring;
                self.publish_record(&record);
            };
            tokio::select! {
                _ = cancel.cancelled() => Err(flowww_transfer::TransferError::Cancelled),
                result = relay_transfer(
                    &mut upstream,
                    &mut downstream,
                    offer.transfer_id,
                    size,
                    hash,
                    &mut on_progress,
                ) => result,
            }
        };

        match outcome {
            Ok(()) => {
                record.status = TransferState::Completed;
                record.bytes_transferred = size;
                record.completed_at = Some(unix_millis());
            }
            Err(flowww_transfer::TransferError::Cancelled) => {
                record.status = TransferState::Cancelled;
                record.error = Some("the relay was cancelled".into());
                record.completed_at = Some(unix_millis());
            }
            Err(error) => {
                record.status = TransferState::Failed;
                record.error = Some(error.to_string());
                record.completed_at = Some(unix_millis());
            }
        }
        self.publish_record(&record);
        self.inner
            .controller
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .cancels
            .remove(&offer.transfer_id);
    }

    // -------------------------------------------------------------- outgoing

    pub async fn send_file(
        &self,
        device_id: &DeviceId,
        path: impl AsRef<Path>,
    ) -> NodeResult<TransferRecord> {
        let peer = self.paired_peer(device_id)?;
        let addr = self.peer_endpoint(device_id).await?;
        self.send_file_to(addr, &peer, path).await
    }

    /// Send directly to an address. Useful when discovery is unavailable but
    /// the peer's endpoint is known (for example on the same machine).
    pub async fn send_file_to(
        &self,
        addr: SocketAddr,
        peer: &PairedPeer,
        path: impl AsRef<Path>,
    ) -> NodeResult<TransferRecord> {
        let path = path.as_ref().to_path_buf();
        let metadata = tokio::fs::metadata(&path).await?;
        if !metadata.is_file() {
            return Err(NodeError::Config("the path is not a regular file".into()));
        }
        let filename = path
            .file_name()
            .map(|name| name.to_string_lossy().to_string())
            .ok_or_else(|| NodeError::Config("the file has no name".into()))?;
        let sha256 = hash_file(&path).await?;
        let mime_type = mime_guess::from_path(&path)
            .first_or_octet_stream()
            .to_string();
        let content = Content::file(
            self.device_id().clone(),
            filename,
            mime_type,
            metadata.len(),
            sha256,
            path.to_string_lossy().to_string(),
        )?;
        self.send_content_to(
            peer,
            addr,
            content,
            TransferAction::Save,
            TransferRoute::Direct,
            Some(path),
        )
        .await
    }

    pub async fn send_text(&self, device_id: &DeviceId, text: &str) -> NodeResult<TransferRecord> {
        let peer = self.paired_peer(device_id)?;
        let addr = self.peer_endpoint(device_id).await?;
        self.send_text_to(addr, &peer, text).await
    }

    pub async fn send_text_to(
        &self,
        addr: SocketAddr,
        peer: &PairedPeer,
        text: &str,
    ) -> NodeResult<TransferRecord> {
        let content = Content::text(self.device_id().clone(), text)?;
        self.send_content_to(
            peer,
            addr,
            content,
            TransferAction::Save,
            TransferRoute::Direct,
            None,
        )
        .await
    }

    pub async fn send_url(&self, device_id: &DeviceId, url: &str) -> NodeResult<TransferRecord> {
        let peer = self.paired_peer(device_id)?;
        let addr = self.peer_endpoint(device_id).await?;
        self.send_url_to(addr, &peer, url).await
    }

    pub async fn send_url_to(
        &self,
        addr: SocketAddr,
        peer: &PairedPeer,
        url: &str,
    ) -> NodeResult<TransferRecord> {
        let content = Content::url(self.device_id().clone(), url)?;
        self.send_content_to(
            peer,
            addr,
            content,
            TransferAction::Save,
            TransferRoute::Direct,
            None,
        )
        .await
    }

    async fn send_content_to(
        &self,
        peer: &PairedPeer,
        addr: SocketAddr,
        content: Content,
        action: TransferAction,
        route: TransferRoute,
        file_path: Option<PathBuf>,
    ) -> NodeResult<TransferRecord> {
        content.validate()?;
        // A local filesystem path is source-only state. It must never appear in
        // an offer or in anything signed for another device.
        let content = content.wire_safe();
        if peer.device.device_id == *self.device_id() {
            return Err(NodeError::Config(
                "cannot send content to this same device".into(),
            ));
        }
        let transfer_id = match (&file_path, content.sha256) {
            (Some(_), Some(hash)) => TransferId::for_content(
                &content.source_device,
                &peer.device.device_id,
                &hash.to_hex(),
                content.size,
            ),
            _ => TransferId::new(),
        };
        let expires_at = unix_millis() + TRANSFER_TTL_MS;
        let claim = TransferClaim::new(
            content.source_device.clone(),
            peer.device.device_id.clone(),
            content.content_id.clone(),
            transfer_id,
            action,
            content.size,
            TRANSFER_TTL_MS,
        );
        let authorization = TransferAuthorization::sign(&self.inner.identity, claim)?;

        let mut record = TransferRecord {
            id: transfer_id,
            source_device: content.source_device.clone(),
            destination_device: peer.device.device_id.clone(),
            filename: file_path
                .as_ref()
                .and_then(|path| path.file_name())
                .map(|name| name.to_string_lossy().to_string()),
            mime_type: Some(content.mime_type.clone()),
            size: content.size,
            sha256: content.sha256.map(|hash| hash.to_hex()),
            kind: content.kind.as_str().to_string(),
            status: TransferState::Offered,
            route,
            direction: TransferDirection::Outgoing,
            bytes_transferred: 0,
            inline_content: None,
            error: None,
            created_at: unix_millis(),
            completed_at: None,
        };
        self.publish_record(&record);

        let mut live = record.clone();
        let cancel = CancellationToken::new();
        self.inner
            .transfer_cancels
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .insert(transfer_id, cancel.clone());
        let result = {
            let mut on_progress = |progress: Progress| {
                live.bytes_transferred = progress.transferred;
                live.status = TransferState::Transferring;
                self.publish_record(&live);
            };
            tokio::select! {
                _ = cancel.cancelled() => Err(NodeError::Transfer(
                    flowww_transfer::TransferError::Cancelled,
                )),
                outcome = self.drive_send(
                    transfer_id,
                    peer,
                    addr,
                    &content,
                    action,
                    route,
                    expires_at,
                    &authorization,
                    file_path.as_deref(),
                    &mut on_progress,
                ) => outcome,
            }
        };
        self.inner
            .transfer_cancels
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .remove(&transfer_id);

        if let Err(error) = result {
            let cancelled = matches!(
                error,
                NodeError::Transfer(flowww_transfer::TransferError::Cancelled)
            );
            record.status = if cancelled {
                TransferState::Cancelled
            } else {
                TransferState::Failed
            };
            record.error = Some(error.to_string());
            record.completed_at = Some(unix_millis());
            self.publish_record(&record);
            return Err(error);
        }

        record.status = TransferState::Completed;
        record.bytes_transferred = content.size;
        record.completed_at = Some(unix_millis());
        if content.inline.is_some() {
            record.inline_content = content.inline.clone();
        }
        self.publish_record(&record);
        Ok(record)
    }

    #[allow(clippy::too_many_arguments)]
    async fn drive_send(
        &self,
        transfer_id: TransferId,
        peer: &PairedPeer,
        addr: SocketAddr,
        content: &Content,
        action: TransferAction,
        route: TransferRoute,
        expires_at: u64,
        authorization: &TransferAuthorization,
        file_path: Option<&Path>,
        on_progress: &mut (dyn FnMut(Progress) + Send),
    ) -> NodeResult<()> {
        let mut session = connect(
            addr,
            peer,
            &self.inner.device,
            &self.inner.identity,
            &self.inner.tls,
        )
        .await?;

        session
            .send_message(Message::TransferOffer {
                transfer_id,
                source_device: content.source_device.clone(),
                destination_device: peer.device.device_id.clone(),
                content: content.clone(),
                action,
                route,
                expires_at,
                authorization: authorization.clone(),
            })
            .await?;

        let response = timeout(OFFER_REPLY_TTL, session.recv_message())
            .await
            .map_err(|_| NodeError::Timeout)??;
        let resume_from = match response {
            Message::TransferAccept {
                transfer_id: accepted,
                resume_from,
            } => {
                if accepted != transfer_id {
                    return Err(NodeError::UnexpectedMessage("accept for another transfer"));
                }
                resume_from
            }
            Message::TransferReject { reason, .. } => return Err(NodeError::OfferRejected(reason)),
            Message::Error { message, .. } => return Err(NodeError::PeerError(message)),
            other => {
                return Err(NodeError::UnexpectedMessage(
                    if matches!(other, Message::Ping { .. }) {
                        "ping instead of an offer response"
                    } else {
                        "unexpected offer response"
                    },
                ))
            }
        };
        if resume_from > content.size {
            return Err(NodeError::OfferRejected(
                "the receiver asked to resume past the end of the file".into(),
            ));
        }

        match file_path {
            Some(path) => {
                let sha256 = content
                    .sha256
                    .ok_or_else(|| NodeError::Config("missing content hash".into()))?;
                stream_file(
                    &mut session,
                    transfer_id,
                    path,
                    content.size,
                    sha256,
                    resume_from,
                    on_progress,
                )
                .await?;
            }
            None => {
                let payload = content
                    .inline
                    .as_deref()
                    .ok_or_else(|| NodeError::Config("inline content is missing".into()))?;
                if resume_from != 0 {
                    return Err(NodeError::OfferRejected(
                        "the receiver asked to resume inline content".into(),
                    ));
                }
                stream_inline(&mut session, transfer_id, payload.as_bytes()).await?;
            }
        }
        Ok(())
    }

    // --------------------------------------------------------------- pairing

    pub async fn start_pairing(&self) -> NodeResult<PairingView> {
        if self
            .inner
            .pairing
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .is_some()
        {
            return Err(NodeError::PairingBusy);
        }
        let bind_ip = self.inner.config.listen_addr.ip();
        let listener =
            PairingListener::bind(SocketAddr::new(bind_ip, 0), self.inner.tls.clone()).await?;
        let advertise_ip = if bind_ip.is_unspecified() {
            advertise_ip()
        } else {
            bind_ip
        };
        let endpoint = SocketAddr::new(advertise_ip, listener.local_addr()?.port());
        let (session, payload) = PairingSession::host(
            self.inner.device.clone(),
            &self.inner.identity,
            self.inner.tls.fingerprint(),
            endpoint,
            self.inner.config.pairing_ttl.as_millis() as u64,
        )?;
        let uri = payload.to_uri()?;
        let view = PairingView {
            uri,
            endpoint: payload.endpoint.clone(),
            expires_at: payload.expires_at,
        };
        let cancel = CancellationToken::new();
        {
            let mut active = self.inner.pairing.lock().unwrap_or_else(|p| p.into_inner());
            *active = Some(ActivePairing {
                session_id: payload.session_id.clone(),
                payload: payload.clone(),
                uri: view.uri.clone(),
                cancel: cancel.clone(),
            });
        }
        self.emit(NodeEvent::PairingStarted(view.clone()));

        let node = self.clone();
        let session_for_task = session.clone();
        let session_id = payload.session_id.clone();
        let ttl = self.inner.config.pairing_ttl;
        tokio::spawn(async move {
            let result = node
                .run_pairing_host(listener, session_for_task, cancel, ttl)
                .await;
            {
                let mut active = node.inner.pairing.lock().unwrap_or_else(|p| p.into_inner());
                if active
                    .as_ref()
                    .is_some_and(|entry| entry.session_id == session_id)
                {
                    active.take();
                }
            }
            match result {
                Ok(device) => node.emit(NodeEvent::PairingCompleted(device)),
                Err(error) => node.emit(NodeEvent::PairingFailed {
                    reason: error.to_string(),
                }),
            }
        });

        Ok(view)
    }

    async fn run_pairing_host(
        &self,
        listener: PairingListener,
        session: PairingSession,
        cancel: CancellationToken,
        ttl: Duration,
    ) -> NodeResult<DeviceInfo> {
        let mut channel = tokio::select! {
            _ = cancel.cancelled() => return Err(NodeError::PairingFailed("cancelled".into())),
            result = timeout(ttl, listener.accept()) => result.map_err(|_| NodeError::Timeout)??,
        };

        let join = match channel.recv().await? {
            PairingTransport::Join(join) => join,
            other => {
                let _ = channel
                    .send(&PairingTransport::Reject {
                        reason: format!("expected JOIN, got {:?}", other),
                    })
                    .await;
                return Err(NodeError::PairingFailed("expected JOIN".into()));
            }
        };
        let verified = session.verify_join(&join)?;
        if !verified
            .tls_fingerprint
            .equals(channel.peer_tls_fingerprint())
        {
            let _ = channel
                .send(&PairingTransport::Reject {
                    reason: "the TLS pin does not match the signed join".into(),
                })
                .await;
            return Err(NodeError::PairingFailed(
                "the TLS pin does not match the signed join".into(),
            ));
        }
        let accept = session.accept(&self.inner.identity, &verified)?;
        channel.send(&PairingTransport::Accept(accept)).await?;

        let confirm = match channel.recv().await? {
            PairingTransport::Confirm(confirm) => confirm,
            _ => {
                return Err(NodeError::PairingFailed("expected CONFIRM".into()));
            }
        };
        session.verify_confirm(&verified, &confirm)?;
        self.inner.store.save_pair(
            &verified.device,
            &join.public_key,
            &verified.tls_fingerprint,
            Some(unix_millis()),
        )?;
        channel.send(&PairingTransport::Complete).await?;
        info!(
            "paired with {} ({})",
            verified.device.device_name, verified.device.device_id
        );
        Ok(verified.device)
    }

    pub fn cancel_pairing(&self) -> bool {
        let active = self
            .inner
            .pairing
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .take();
        match active {
            Some(active) => {
                active.cancel.cancel();
                true
            }
            None => false,
        }
    }

    pub fn active_pairing(&self) -> Option<PairingView> {
        self.inner
            .pairing
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .as_ref()
            .map(|active| PairingView {
                uri: active.uri.clone(),
                endpoint: active.payload.endpoint.clone(),
                expires_at: active.payload.expires_at,
            })
    }

    pub async fn pair_with_uri(&self, uri: &str) -> NodeResult<DeviceInfo> {
        let payload = PairingPayload::from_uri(uri)?;
        self.pair_with_payload(payload).await
    }

    pub async fn pair_with_payload(&self, payload: PairingPayload) -> NodeResult<DeviceInfo> {
        payload.validate()?;
        let addr = SocketAddr::from_str(&payload.endpoint)
            .map_err(|_| NodeError::PairingFailed("invalid pairing endpoint".into()))?;
        let host_tls = payload.host_tls_fingerprint()?;
        let join = PairingJoin::create(
            &payload,
            &self.inner.identity,
            self.inner.device.clone(),
            self.inner.tls.fingerprint(),
        )?;
        let mut channel = connect_pairing(addr, host_tls, &self.inner.tls).await?;
        channel.send(&PairingTransport::Join(join.clone())).await?;
        let accept = match timeout(OFFER_REPLY_TTL, channel.recv())
            .await
            .map_err(|_| NodeError::Timeout)??
        {
            PairingTransport::Accept(accept) => accept,
            PairingTransport::Reject { reason } => return Err(NodeError::PairingFailed(reason)),
            _ => return Err(NodeError::PairingFailed("expected ACCEPT".into())),
        };
        join.verify_accept(&payload, &accept)?;
        channel
            .send(&PairingTransport::Confirm(
                join.confirm(&self.inner.identity, &payload)?,
            ))
            .await?;
        match timeout(OFFER_REPLY_TTL, channel.recv())
            .await
            .map_err(|_| NodeError::Timeout)??
        {
            PairingTransport::Complete => {}
            PairingTransport::Reject { reason } => return Err(NodeError::PairingFailed(reason)),
            _ => return Err(NodeError::PairingFailed("expected COMPLETE".into())),
        }
        let host_public: PublicIdentity = payload.host_public()?;
        self.inner.store.save_pair(
            &payload.host_device,
            &host_public.to_base64(),
            &host_tls,
            Some(unix_millis()),
        )?;
        // NOTE: the payload endpoint is the short-lived PAIRING listener, which
        // closes when the handshake completes. It must not be registered as a
        // transfer endpoint — doing so would shadow real discovery data with a
        // dead address. Post-pairing connectivity comes from mDNS discovery or
        // an explicit `register_peer_endpoint` call.
        self.emit(NodeEvent::PairingCompleted(payload.host_device.clone()));
        info!(
            "paired with {} ({})",
            payload.host_device.device_name, payload.host_device.device_id
        );
        Ok(payload.host_device)
    }

    // ----------------------------------------------------------------- trust

    pub fn paired_peer(&self, device_id: &DeviceId) -> NodeResult<PairedPeer> {
        self.inner
            .trust
            .by_device_id(device_id)
            .ok_or_else(|| NodeError::UnknownPeer(device_id.as_str().to_string()))
    }

    pub fn revoke(&self, device_id: &DeviceId) -> NodeResult<()> {
        self.inner.store.revoke_device(device_id)?;
        Ok(())
    }

    pub fn auto_accept(&self) -> bool {
        self.inner
            .store
            .get_setting("auto_accept")
            .ok()
            .flatten()
            .map(|value| value == "true")
            .unwrap_or(self.inner.config.auto_accept)
    }

    pub fn set_auto_accept(&self, enabled: bool) -> NodeResult<()> {
        self.inner
            .store
            .set_setting("auto_accept", if enabled { "true" } else { "false" })?;
        Ok(())
    }

    pub fn pending_offers(&self) -> Vec<PendingOfferView> {
        let pending = self.inner.pending.lock().unwrap_or_else(|p| p.into_inner());
        let mut views = pending
            .values()
            .map(|entry| entry.view.clone())
            .collect::<Vec<_>>();
        views.sort_by_key(|view| view.content.created_at);
        views
    }

    pub fn respond_to_offer(&self, transfer_id: TransferId, accept: bool) -> bool {
        let entry = self
            .inner
            .pending
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .remove(&transfer_id);
        match entry {
            Some(entry) => entry.sender.send(accept).is_ok(),
            None => false,
        }
    }

    // --------------------------------------------------------------- spatial

    pub fn hold(&self, content: Content) -> NodeResult<SpatialView> {
        self.hold_for(content, DEFAULT_HOLD_TTL)
    }

    /// Hold content for a specific lifetime. The source mints a fresh handle and
    /// never places its local path in the descriptor.
    pub fn hold_for(&self, content: Content, ttl: Duration) -> NodeResult<SpatialView> {
        let local_path = content.source_reference.as_ref().map(PathBuf::from);
        let content = content.wire_safe();
        let view = {
            let mut state = self.inner.spatial.lock().unwrap_or_else(|p| p.into_inner());
            let now = unix_millis();
            state.session.expire_if_needed(now);
            if state.session.held().is_some() {
                return Err(flowww_spatial::SpatialError::AlreadyHolding.into());
            }
            state.session = SpatialSession::new(ttl);
            state.vault = ContentVault::new(ttl);
            let staged = state.vault.stage(content.clone(), local_path)?;
            state
                .session
                .pick(content, Some(self.device_id().clone()))?;
            state.held_handle = Some(staged.handle);
            spatial_view(&mut state)
        };
        self.emit(NodeEvent::SpatialUpdated(view.clone()));
        Ok(view)
    }

    /// Hold a local file without revealing its path to any peer.
    pub async fn hold_file(&self, path: impl AsRef<Path>) -> NodeResult<SpatialView> {
        let path = path.as_ref().to_path_buf();
        let metadata = tokio::fs::metadata(&path).await?;
        if !metadata.is_file() {
            return Err(NodeError::Config(
                "the spatial source is not a regular file".into(),
            ));
        }
        let filename = path
            .file_name()
            .map(|name| name.to_string_lossy().to_string())
            .ok_or_else(|| NodeError::Config("the file has no name".into()))?;
        let sha256 = hash_file(&path).await?;
        let mime_type = mime_guess::from_path(&path)
            .first_or_octet_stream()
            .to_string();
        let content = Content::file(
            self.device_id().clone(),
            filename,
            mime_type,
            metadata.len(),
            sha256,
            path.to_string_lossy().to_string(),
        )?;
        self.hold(content)
    }

    pub fn hold_text(&self, text: &str) -> NodeResult<SpatialView> {
        let content = Content::text(self.device_id().clone(), text)?;
        self.hold(content)
    }

    pub fn hold_url(&self, url: &str) -> NodeResult<SpatialView> {
        let content = Content::url(self.device_id().clone(), url)?;
        self.hold(content)
    }

    pub fn cancel_hold(&self) -> SpatialView {
        let view = {
            let mut state = self.inner.spatial.lock().unwrap_or_else(|p| p.into_inner());
            state.session.cancel();
            state.session.reset();
            state.vault.clear();
            state.held_handle = None;
            spatial_view(&mut state)
        };
        self.emit(NodeEvent::SpatialUpdated(view.clone()));
        view
    }

    /// Controller side: request a short-lived capability from a source.
    pub async fn request_capture(&self, source_device: &DeviceId) -> NodeResult<ContentCapability> {
        let peer = self.paired_peer(source_device)?;
        let addr = self.peer_endpoint(source_device).await?;
        let mut session = connect(
            addr,
            &peer,
            &self.inner.device,
            &self.inner.identity,
            &self.inner.tls,
        )
        .await?;
        let request_id = Uuid::new_v4();
        session
            .send_message(Message::SpatialCaptureRequest {
                request_id,
                source_device: source_device.clone(),
                controller_device: self.device_id().clone(),
            })
            .await?;
        let message = timeout(OFFER_REPLY_TTL, session.recv_message())
            .await
            .map_err(|_| NodeError::Timeout)??;
        match message {
            Message::SpatialCaptureGrant {
                request_id: granted,
                capability,
            } => {
                if granted != request_id {
                    return Err(NodeError::UnexpectedMessage(
                        "the grant answers another request",
                    ));
                }
                if capability.claim.source_device != *source_device
                    || capability.claim.controller_device != *self.device_id()
                {
                    return Err(NodeError::OfferRejected(
                        "the capability is bound to other devices".into(),
                    ));
                }
                capability.verify(&peer.public_identity()?)?;
                self.inner
                    .controller
                    .lock()
                    .unwrap_or_else(|p| p.into_inner())
                    .capability = Some(capability.clone());
                self.emit(NodeEvent::SpatialUpdated(self.spatial_view()));
                Ok(capability)
            }
            Message::SpatialCaptureDeny { reason, .. } => Err(NodeError::OfferRejected(reason)),
            Message::Error { message, .. } => Err(NodeError::PeerError(message)),
            other => Err(NodeError::UnexpectedMessage(
                if matches!(other, Message::Ping { .. }) {
                    "ping instead of a capture answer"
                } else {
                    "unexpected capture answer"
                },
            )),
        }
    }

    pub fn captured_capability(&self) -> Option<ContentCapability> {
        self.inner
            .controller
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .capability
            .clone()
    }

    /// Whether this node prepared a spatial command for the transfer. This is
    /// used by local control surfaces to avoid exposing unrelated transfer
    /// records through a guessed transfer id.
    pub fn owns_spatial_transfer(&self, transfer_id: &TransferId) -> bool {
        self.inner
            .controller
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .journal
            .contains_key(transfer_id)
    }

    /// Return a previously prepared command without exposing it through the
    /// browser bridge. Commands remain node-owned signed protocol objects.
    pub fn prepared_spatial_command(&self, transfer_id: &TransferId) -> Option<SpatialCommand> {
        self.inner
            .controller
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .journal
            .get(transfer_id)
            .map(|entry| entry.command.clone())
    }

    /// Controller side: sign a command that names one destination.
    pub fn prepare_spatial_command(&self, destination: &DeviceId) -> NodeResult<SpatialCommand> {
        let capability = self.captured_capability().ok_or(NodeError::NothingHeld)?;
        let source = capability.claim.source_device.clone();
        let controller = capability.claim.controller_device.clone();
        if destination == self.device_id() || destination == &source || destination == &controller {
            return Err(NodeError::Config(
                "a spatial destination must be a third device".into(),
            ));
        }
        self.paired_peer(destination)?;
        if flowww_core::is_expired(capability.claim.expires_at) {
            return Err(NodeError::OfferRejected(
                "the captured content has expired".into(),
            ));
        }
        let content = capability.content().clone();
        let transfer_id = if content.inline.is_none() {
            match content.sha256 {
                Some(hash) => {
                    TransferId::for_content(&source, destination, &hash.to_hex(), content.size)
                }
                None => TransferId::new(),
            }
        } else {
            TransferId::new()
        };
        let command = SpatialCommand::issue_with_expiry(
            &self.inner.identity,
            controller,
            source,
            destination.clone(),
            capability.claim.handle_id.clone(),
            content.content_id.clone(),
            transfer_id,
            capability.claim.expires_at,
            self.controller_endpoint(),
        )?;
        let entry = JournalEntry {
            command: command.clone(),
            capability: capability.clone(),
            content: content.clone(),
            destination: destination.clone(),
            expires_at: command.claim.expires_at,
        };
        self.inner
            .controller
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .journal
            .insert(transfer_id, entry);
        let record = spatial_record(
            transfer_id,
            command.claim.source_device.clone(),
            destination.clone(),
            &content,
            TransferRoute::Direct,
            TransferDirection::Outgoing,
            TransferState::Created,
        );
        self.publish_record(&record);
        Ok(command)
    }

    /// Controller side: deliver an already prepared command to its source.
    pub async fn submit_spatial_command(
        &self,
        command: SpatialCommand,
        capability: ContentCapability,
    ) -> NodeResult<SpatialControl> {
        let source = command.claim.source_device.clone();
        let peer = self.paired_peer(&source)?;
        let addr = self.peer_endpoint(&source).await?;
        let mut session = connect(
            addr,
            &peer,
            &self.inner.device,
            &self.inner.identity,
            &self.inner.tls,
        )
        .await?;
        session
            .send_message(Message::SpatialTransferCommand {
                command: command.clone(),
                capability,
            })
            .await?;
        let transfer_id = command.claim.transfer_id;
        let response = timeout(OFFER_REPLY_TTL, session.recv_message())
            .await
            .map_err(|_| NodeError::Timeout)??;
        match response {
            Message::SpatialCommandAccepted {
                transfer_id: accepted,
                route,
            } if accepted == transfer_id => {
                let fallback = self
                    .inner
                    .controller
                    .lock()
                    .unwrap_or_else(|p| p.into_inner())
                    .journal
                    .get(&transfer_id)
                    .map(|entry| entry.content.clone());
                let mut record = match self.inner.store.get_transfer(transfer_id)? {
                    Some(record) => record,
                    None => spatial_record(
                        transfer_id,
                        command.claim.source_device.clone(),
                        command.claim.destination_device.clone(),
                        fallback.as_ref().ok_or(NodeError::NothingHeld)?,
                        route,
                        TransferDirection::Outgoing,
                        TransferState::Created,
                    ),
                };
                record.route = route;
                record.status = TransferState::Connecting;
                self.publish_record(&record);
                let cancel = CancellationToken::new();
                self.inner
                    .controller
                    .lock()
                    .unwrap_or_else(|p| p.into_inner())
                    .cancels
                    .insert(transfer_id, cancel.clone());
                let disconnect_signal = CancellationToken::new();
                let cancel_request = CancellationToken::new();
                self.inner
                    .controller
                    .lock()
                    .unwrap_or_else(|p| p.into_inner())
                    .disconnects
                    .insert(transfer_id, disconnect_signal.clone());
                self.inner
                    .controller
                    .lock()
                    .unwrap_or_else(|p| p.into_inner())
                    .cancel_requests
                    .insert(transfer_id, cancel_request.clone());
                Ok(SpatialControl {
                    node: self.clone(),
                    transfer_id,
                    session: Some(session),
                    record,
                    cancel,
                    cancel_request,
                    disconnect_signal,
                })
            }
            Message::SpatialCommandRejected { reason, .. } => {
                if let Some(mut record) = self.inner.store.get_transfer(transfer_id)? {
                    if !record.status.is_terminal() {
                        record.status = TransferState::Failed;
                        record.error = Some(reason.clone());
                        record.completed_at = Some(unix_millis());
                        self.publish_record(&record);
                    }
                }
                Err(NodeError::OfferRejected(reason))
            }
            Message::Error { message, .. } => Err(NodeError::PeerError(message)),
            other => Err(NodeError::UnexpectedMessage(
                if matches!(other, Message::Ping { .. }) {
                    "ping instead of a command answer"
                } else {
                    "unexpected command answer"
                },
            )),
        }
    }

    pub async fn begin_spatial_transfer(
        &self,
        destination: &DeviceId,
    ) -> NodeResult<SpatialControl> {
        let command = self.prepare_spatial_command(destination)?;
        let capability = self.captured_capability().ok_or(NodeError::NothingHeld)?;
        self.submit_spatial_command(command, capability).await
    }

    /// Controller side: command a capture to a destination and wait for the
    /// terminal result.
    pub async fn send_spatial(&self, destination: &DeviceId) -> NodeResult<TransferRecord> {
        self.begin_spatial_transfer(destination).await?.wait().await
    }

    pub fn cancel_spatial(&self, transfer_id: &TransferId) -> bool {
        if let Some(request) = self
            .inner
            .controller
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .cancel_requests
            .get(transfer_id)
            .cloned()
        {
            request.cancel();
            return true;
        }
        self.cancel_local_spatial(transfer_id)
    }

    /// Close the controller-side observation session without requesting
    /// cancellation from the source. The source observes the closed session
    /// and applies its normal controller-disconnect lifecycle.
    pub fn disconnect_spatial(&self, transfer_id: &TransferId) -> bool {
        let signal = self
            .inner
            .controller
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .disconnects
            .get(transfer_id)
            .cloned();
        let Some(signal) = signal else {
            return false;
        };
        signal.cancel();
        true
    }

    fn cancel_local_spatial(&self, transfer_id: &TransferId) -> bool {
        let token = {
            let spatial = self.inner.spatial.lock().unwrap_or_else(|p| p.into_inner());
            spatial.cancels.get(transfer_id).cloned()
        }
        .or_else(|| {
            self.inner
                .controller
                .lock()
                .unwrap_or_else(|p| p.into_inner())
                .cancels
                .get(transfer_id)
                .cloned()
        });
        let Some(token) = token else {
            return false;
        };
        token.cancel();
        if let Ok(Some(mut record)) = self.inner.store.get_transfer(*transfer_id) {
            if !record.status.is_terminal() {
                record.status = TransferState::Cancelled;
                record.error = Some("cancelled by the local device".into());
                record.completed_at = Some(unix_millis());
                self.publish_record(&record);
            }
        }
        true
    }

    /// Resolve a release at normalized coordinates and send the held content.
    pub async fn drop_spatial(&self, x: f32, y: f32) -> NodeResult<TransferRecord> {
        let (held, decision, target_id, local_path) = {
            let mut state = self.inner.spatial.lock().unwrap_or_else(|p| p.into_inner());
            let now = unix_millis();
            if state.session.expire_if_needed(now) {
                let view = spatial_view(&mut state);
                drop(state);
                self.emit(NodeEvent::SpatialUpdated(view));
                return Err(NodeError::NothingHeld);
            }
            let target = state
                .registry
                .nearest(x, y, now)
                .ok_or(NodeError::NoSpatialTarget)?
                .clone();
            let distance = target.distance_to(x, y);
            let held = state
                .session
                .held()
                .cloned()
                .ok_or(NodeError::NothingHeld)?;
            let local_path = state
                .held_handle
                .as_ref()
                .and_then(|handle| state.vault.get(handle))
                .and_then(|staged| staged.local_path.clone());
            let decision = resolve_drop(
                &held.content.source_device,
                held.controller.as_ref(),
                &target.device_id,
                distance,
            );
            state.session.drop_content()?;
            state.held_handle = None;
            (held, decision, target.device_id, local_path)
        };

        {
            let mut state = self.inner.spatial.lock().unwrap_or_else(|p| p.into_inner());
            let view = spatial_view(&mut state);
            drop(state);
            self.emit(NodeEvent::SpatialUpdated(view));
        }

        if target_id == *self.device_id() {
            return Err(NodeError::NoSpatialTarget);
        }
        let peer = self.paired_peer(&target_id)?;
        let addr = self.peer_endpoint(&target_id).await?;
        self.send_content_to(
            &peer,
            addr,
            held.content,
            TransferAction::Save,
            decision.route,
            local_path,
        )
        .await
    }

    pub fn spatial_view(&self) -> SpatialView {
        let mut state = self.inner.spatial.lock().unwrap_or_else(|p| p.into_inner());
        spatial_view(&mut state)
    }

    // -------------------------------------------------------------- snapshot

    pub fn snapshot(&self) -> NodeResult<NodeSnapshot> {
        let peers = {
            let peers = self
                .inner
                .peers
                .try_read()
                .map_err(|_| NodeError::Config("peer state is busy".into()))?;
            let mut views = peers
                .values()
                .map(|peer| {
                    let paired = self
                        .inner
                        .trust
                        .by_device_id(&peer.device.device_id)
                        .is_some();
                    PeerView::new(peer, paired)
                })
                .collect::<Vec<_>>();
            views.sort_by(|left, right| left.name.cmp(&right.name));
            views
        };
        let devices = self.inner.store.list_devices()?;
        let transfers = self.inner.store.list_transfers(60)?;
        let pending_offers = self.pending_offers();
        let spatial = self.spatial_view();
        let pairing = self.active_pairing();
        Ok(NodeSnapshot {
            device: self.inner.device.clone(),
            listen_port: self.inner.listen_port,
            public_key_fingerprint: self.inner.identity.fingerprint().to_hex(),
            tls_fingerprint: self.inner.tls.fingerprint().to_hex(),
            download_dir: self.inner.config.download_dir.to_string_lossy().to_string(),
            auto_accept: self.auto_accept(),
            peers,
            devices,
            transfers,
            pending_offers,
            spatial,
            pairing,
        })
    }

    // ----------------------------------------------------------------- utils

    async fn peer_endpoint(&self, device_id: &DeviceId) -> NodeResult<SocketAddr> {
        if let Some(addr) = self
            .inner
            .endpoints
            .read()
            .await
            .get(device_id.as_str())
            .copied()
        {
            return Ok(addr);
        }
        let peers = self.inner.peers.read().await;
        peers
            .get(device_id.as_str())
            .and_then(|peer| peer.endpoint())
            .ok_or_else(|| NodeError::PeerUnavailable(device_id.as_str().to_string()))
    }

    /// Register or refresh the address of a peer this node can reach.
    ///
    /// Discovery normally supplies this, but spatial transfers must also work
    /// when discovery is disabled, for example in hermetic tests.
    pub async fn register_peer_endpoint(&self, device_id: &DeviceId, addr: SocketAddr) {
        self.inner
            .endpoints
            .write()
            .await
            .insert(device_id.as_str().to_string(), addr);
    }

    async fn lookup_peer_endpoint(&self, device_id: &DeviceId) -> Option<SocketAddr> {
        if let Some(addr) = self
            .inner
            .endpoints
            .read()
            .await
            .get(device_id.as_str())
            .copied()
        {
            return Some(addr);
        }
        let peers = self.inner.peers.read().await;
        peers
            .get(device_id.as_str())
            .and_then(|peer| peer.endpoint())
    }

    /// The address a peer should dial to reach this device as a relay.
    fn controller_endpoint(&self) -> Option<String> {
        let bind = self.inner.config.listen_addr;
        let ip = if bind.ip().is_unspecified() {
            advertise_ip()
        } else {
            bind.ip()
        };
        Some(SocketAddr::new(ip, self.inner.listen_port).to_string())
    }
}

fn spatial_view(state: &mut SpatialRuntime) -> SpatialView {
    let now = unix_millis();
    state.session.expire_if_needed(now);
    let held = state.session.held().map(|held| held.content.clone());
    let targets = state
        .registry
        .fresh_targets(now)
        .into_iter()
        .cloned()
        .collect();
    SpatialView {
        state: state.session.state(),
        held,
        targets,
    }
}

fn marker_for(peer: &DiscoveredPeer) -> Option<SpatialTarget> {
    let seed = peer.tls_fingerprint.0;
    let angle =
        (u16::from_be_bytes([seed[0], seed[1]]) as f32 / u16::MAX as f32) * std::f32::consts::TAU;
    SpatialTarget::new(
        peer.device.device_id.clone(),
        peer.device.device_name.clone(),
        0.5 + angle.cos() * SPATIAL_TARGET_RADIUS,
        0.5 + angle.sin() * SPATIAL_TARGET_RADIUS,
        unix_millis(),
    )
    .ok()
}

fn advertise_ip() -> IpAddr {
    let socket = std::net::UdpSocket::bind("0.0.0.0:0");
    if let Ok(socket) = socket {
        // A UDP connect performs a route lookup without sending a packet.
        if socket.connect("192.0.2.1:80").is_ok() {
            if let Ok(addr) = socket.local_addr() {
                if !addr.ip().is_unspecified() {
                    return addr.ip();
                }
            }
        }
    }
    IpAddr::V4(Ipv4Addr::LOCALHOST)
}

fn truncate_reason(reason: &str) -> String {
    reason.chars().take(480).collect()
}

fn spatial_record(
    transfer_id: TransferId,
    source_device: DeviceId,
    destination_device: DeviceId,
    content: &Content,
    route: TransferRoute,
    direction: TransferDirection,
    status: TransferState,
) -> TransferRecord {
    TransferRecord {
        id: transfer_id,
        source_device,
        destination_device,
        filename: content.inline.is_none().then(|| content.name.clone()),
        mime_type: Some(content.mime_type.clone()),
        size: content.size,
        sha256: content.sha256.map(|hash| hash.to_hex()),
        kind: content.kind.as_str().to_string(),
        status,
        route,
        direction,
        bytes_transferred: 0,
        inline_content: content.inline.clone(),
        error: None,
        created_at: unix_millis(),
        completed_at: None,
    }
}

async fn send_spatial_status(
    session: &mut PeerSession,
    transfer_id: TransferId,
    status: TransferState,
    bytes_transferred: u64,
    detail: Option<&str>,
) {
    let _ = session
        .send_message(Message::SpatialStatus {
            transfer_id,
            status,
            bytes_transferred,
            detail: detail.map(truncate_reason),
        })
        .await;
}
