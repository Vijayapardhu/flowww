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

use flowww_core::{
    unix_millis, Capability, Content, DeviceId, DeviceInfo, Platform, TransferAction, TransferId,
    TransferRoute, TransferState,
};
use flowww_discovery::{DiscoveredPeer, Discovery, DiscoveryEvent};
use flowww_network::{
    connect, connect_pairing, DeviceTls, PairingListener, PeerListener, PeerSession,
};
use flowww_protocol::{ErrorCode, Message};
use flowww_security::{
    verify_authorization, AuthorizationExpectation, FileSecretStore, Fingerprint, Identity,
    PairedPeer, PairingJoin, PairingPayload, PairingSession, PairingTransport, PublicIdentity,
    ReplayGuard, SharedTrust, TransferAuthorization, TransferClaim,
};
use flowww_spatial::{resolve_drop, SpatialRegistry, SpatialSession, SpatialTarget};
use flowww_storage::{Store, TransferDirection, TransferRecord};
use flowww_transfer::{
    hash_file, receive_file, receive_inline, send_file as stream_file,
    send_inline as stream_inline, IncomingFile, Progress,
};

use crate::config::NodeConfig;
use crate::error::{NodeError, NodeResult};
use crate::event::{NodeEvent, NodeSnapshot, PairingView, PeerView, PendingOfferView, SpatialView};

const OFFER_APPROVAL_TTL: Duration = Duration::from_secs(120);
const OFFER_REPLY_TTL: Duration = Duration::from_secs(30);
const TRANSFER_TTL_MS: u64 = 5 * 60 * 1000;
const SPATIAL_TARGET_RADIUS: f32 = 0.34;

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
    discovery: Mutex<Option<Discovery>>,
    pending: Mutex<HashMap<TransferId, PendingEntry>>,
    pairing: Mutex<Option<ActivePairing>>,
    spatial: Mutex<SpatialRuntime>,
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
}

impl Default for SpatialRuntime {
    fn default() -> Self {
        Self {
            session: SpatialSession::default_ttl(),
            registry: SpatialRegistry::default_config(),
        }
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
            discovery: Mutex::new(None),
            pending: Mutex::new(HashMap::new()),
            pairing: Mutex::new(None),
            spatial: Mutex::new(SpatialRuntime::default()),
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
        self.emit(NodeEvent::TransferUpdated(record.clone()));
    }

    // ------------------------------------------------------------- discovery

    fn start_discovery(&self) -> NodeResult<()> {
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

        if !matches!(message, Message::TransferOffer { .. }) {
            let _ = session
                .send_message(Message::Error {
                    code: ErrorCode::Protocol,
                    message: format!("expected TRANSFER_OFFER, got {}", message.kind()),
                    transfer_id: message.transfer_id(),
                })
                .await;
            return;
        }
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
        let Message::TransferOffer {
            transfer_id,
            source_device,
            destination_device,
            content,
            action,
            route,
            expires_at,
            authorization,
        } = message
        else {
            unreachable!()
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
        let result = {
            let mut on_progress = |progress: Progress| {
                live.bytes_transferred = progress.transferred;
                live.status = TransferState::Transferring;
                self.publish_record(&live);
            };
            receive_file(&mut session, transfer_id, incoming, &mut on_progress).await
        };

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
                live.status = TransferState::Failed;
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

        match receive_inline(&mut session, transfer_id, payload.as_bytes()).await {
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
                record.status = TransferState::Failed;
                record.error = Some(error.to_string());
                record.completed_at = Some(unix_millis());
                self.publish_record(&record);
            }
        }
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
        let result = {
            let mut on_progress = |progress: Progress| {
                live.bytes_transferred = progress.transferred;
                live.status = TransferState::Transferring;
                self.publish_record(&live);
            };
            self.drive_send(
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
            )
            .await
        };

        if let Err(error) = result {
            record.status = TransferState::Failed;
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
        let view = {
            let mut state = self.inner.spatial.lock().unwrap_or_else(|p| p.into_inner());
            state
                .session
                .pick(content, Some(self.device_id().clone()))?;
            spatial_view(&mut state)
        };
        self.emit(NodeEvent::SpatialUpdated(view.clone()));
        Ok(view)
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
            spatial_view(&mut state)
        };
        self.emit(NodeEvent::SpatialUpdated(view.clone()));
        view
    }

    /// Resolve a release at normalized coordinates and send the held content.
    pub async fn drop_spatial(&self, x: f32, y: f32) -> NodeResult<TransferRecord> {
        let (held, decision, target_id) = {
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
            let decision = resolve_drop(
                &held.content.source_device,
                held.controller.as_ref(),
                &target.device_id,
                distance,
            );
            state.session.drop_content()?;
            (held, decision, target.device_id)
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
        let content = held.content;
        let path = content.source_reference.as_ref().map(PathBuf::from);
        self.send_content_to(
            &peer,
            addr,
            content,
            TransferAction::Save,
            decision.route,
            path,
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
        let peers = self.inner.peers.read().await;
        peers
            .get(device_id.as_str())
            .and_then(|peer| peer.endpoint())
            .ok_or_else(|| NodeError::PeerUnavailable(device_id.as_str().to_string()))
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
