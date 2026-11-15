use crate::agent::api::{StageRequest, StageResponse};
use crate::agent::config::AgentConfig;
use crate::agent::foreground::{self, ForegroundApp};
use crate::agent::ipc::IpcServer;
use crate::agent::lifecycle::AgentLifecycle;
use crate::agent::native_messaging::{BrowserTabInfo, NativeMessagingHost};
use crate::agent::providers::{ContentLifecycleState, ContentType, ProviderRegistry, StagePayload, StagedStore};
use crate::agent::tray::build_tray;
use flowww_core::{Device, DeviceInfo, TransferId, TransferState};
use flowww_node::{Node, NodeConfig, NodeEvent, NodeSnapshot, PairingView, PendingOfferView};
use flowww_storage::TransferRecord;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::Arc;
use std::sync::Mutex as SyncMutex;
use tauri::{AppHandle, Emitter, Manager, RunEvent, State};
use std::thread;
use std::time::Duration;
use tokio::sync::{watch, Mutex as AsyncMutex};

pub mod agent;

struct AppState {
    node: SyncMutex<Option<Node>>,
    initialize: AsyncMutex<()>,
    events: SyncMutex<Option<EventForwarder>>,
    agent: SyncMutex<Option<AgentLifecycle>>,
    ipc: SyncMutex<Option<IpcServer>>,
    providers: SyncMutex<Option<Arc<ProviderRegistry>>>,
    staged: SyncMutex<Option<Arc<StagedStore>>>,
    native_messaging: SyncMutex<Option<NativeMessagingHost>>,
}

struct EventForwarder {
    stop: watch::Sender<bool>,
    task: tauri::async_runtime::JoinHandle<()>,
}

fn public_error(code: &str) -> String {
    format!("{code}: The operation could not be completed.")
}

impl AppState {
    fn shutdown_now(&self) {
        if let Ok(mut events) = self.events.lock() {
            if let Some(forwarder) = events.take() {
                let _ = forwarder.stop.send(true);
            }
        }
        if let Ok(mut agent) = self.agent.lock() {
            if let Some(mut agent) = agent.take() {
                let _ = agent.stop();
            }
        }
        if let Ok(mut ipc) = self.ipc.lock() {
            if let Some(ipc) = ipc.take() {
                // IPC stop is async; best effort during sync shutdown.
                let _ = ipc.stop();
            }
        }
        if let Ok(mut nm) = self.native_messaging.lock() {
            if let Some(mut nm) = nm.take() {
                // Native messaging shutdown is async; best effort during sync shutdown.
                let _ = nm.shutdown();
            }
        }
        if let Ok(mut node) = self.node.lock() {
            if let Some(node) = node.take() {
                node.shutdown();
            }
        }
    }
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct LocalDeviceDto {
    device_id: String,
    name: String,
    platform: String,
    protocol_version: String,
    fingerprint: String,
    listen_port: u16,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct DeviceDto {
    device_id: String,
    name: String,
    platform: String,
    paired: bool,
    online: bool,
    fingerprint: Option<String>,
    last_seen_ms: Option<u64>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct TransferDto {
    transfer_id: String,
    source_device_id: String,
    destination_device_id: String,
    filename: Option<String>,
    size: u64,
    status: TransferState,
    route: flowww_core::TransferRoute,
    direction: flowww_storage::TransferDirection,
    bytes_transferred: u64,
    error: Option<PublicErrorDto>,
    created_at: u64,
    completed_at: Option<u64>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct PairingDto {
    uri: String,
    endpoint: String,
    expires_at: u64,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct PublicErrorDto {
    code: String,
    message: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct CancellationDto {
    accepted: bool,
    code: String,
    message: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(tag = "kind", rename_all = "kebab-case")]
enum DesktopEvent {
    TransferUpdated { transfer: TransferDto },
    PeerFound { device: DeviceDto },
    PeerLost { device_id: String },
    PairingStarted { pairing: PairingDto },
    PairingCompleted { device: DeviceDto },
    PairingFailed { reason: String },
    ResyncRequired,
    Error { code: String, message: String },
    AgentStateChanged { state: String },
}

fn node(state: &State<'_, AppState>) -> Result<Node, String> {
    state
        .node
        .lock()
        .map_err(|_| "node state lock poisoned".to_string())?
        .clone()
        .ok_or_else(|| "flowww node is not initialized".to_string())
}

fn local_device(node: &Node) -> LocalDeviceDto {
    LocalDeviceDto {
        device_id: node.device_id().as_str().to_string(),
        name: node.device().device_name.clone(),
        platform: node.device().platform.as_str().to_string(),
        protocol_version: node.device().protocol_version.clone(),
        fingerprint: node.public_key_fingerprint().short(),
        listen_port: node.listen_port(),
    }
}

fn device_info(
    device: &DeviceInfo,
    paired: bool,
    online: bool,
    fingerprint: Option<String>,
    last_seen_ms: Option<u64>,
) -> DeviceDto {
    DeviceDto {
        device_id: device.device_id.as_str().to_string(),
        name: device.device_name.clone(),
        platform: device.platform.as_str().to_string(),
        paired,
        online,
        fingerprint,
        last_seen_ms,
    }
}

fn transfer(record: &TransferRecord) -> TransferDto {
    TransferDto {
        transfer_id: record.id.to_string(),
        source_device_id: record.source_device.as_str().to_string(),
        destination_device_id: record.destination_device.as_str().to_string(),
        filename: record.filename.clone(),
        size: record.size,
        status: record.status,
        route: record.route,
        direction: record.direction,
        bytes_transferred: record.bytes_transferred,
        error: public_transfer_error(record),
        created_at: record.created_at,
        completed_at: record.completed_at,
    }
}

fn public_transfer_error(record: &TransferRecord) -> Option<PublicErrorDto> {
    if record.status == TransferState::Failed {
        Some(PublicErrorDto {
            code: "TRANSFER_FAILED".into(),
            message: "The transfer failed. Check the transfer history for its state.".into(),
        })
    } else if record.status == TransferState::Cancelled {
        Some(PublicErrorDto {
            code: "TRANSFER_CANCELLED".into(),
            message: "The transfer was cancelled.".into(),
        })
    } else {
        None
    }
}

fn pairing(view: PairingView) -> PairingDto {
    PairingDto {
        uri: view.uri,
        endpoint: view.endpoint,
        expires_at: view.expires_at,
    }
}

fn summary(snapshot: &NodeSnapshot) -> Vec<DeviceDto> {
    let online = snapshot
        .peers
        .iter()
        .map(|peer| (peer.device_id.as_str(), ()))
        .collect::<HashMap<_, ()>>();
    snapshot
        .devices
        .iter()
        .map(|device: &Device| {
            device_info(
                &device.info,
                device.trusted,
                online.contains_key(device.info.device_id.as_str()),
                Some(device.public_key_fingerprint.clone()),
                device.last_seen_ms,
            )
        })
        .collect()
}

fn emit_node_events(app: AppHandle, node: &Node) -> EventForwarder {
    let mut events = node.subscribe();
    let (stop, mut stopped) = watch::channel(false);
    let task = tauri::async_runtime::spawn(async move {
        loop {
            tokio::select! {
                biased;
                _ = stopped.changed() => {
                    if *stopped.borrow() { break; }
                }
                event = events.recv() => match event {
                    Ok(NodeEvent::TransferUpdated(record)) => {
                        let _ = app.emit("flowww://event", DesktopEvent::TransferUpdated { transfer: transfer(&record) });
                    }
                    Ok(NodeEvent::PeerFound(peer)) => {
                        let _ = app.emit("flowww://event", DesktopEvent::PeerFound { device: DeviceDto {
                            device_id: peer.device_id, name: peer.name,
                            platform: peer.platform.as_str().to_string(), paired: peer.paired,
                            online: true, fingerprint: Some(peer.fingerprint), last_seen_ms: Some(peer.last_seen_ms),
                        }});
                    }
                    Ok(NodeEvent::PeerLost { device_id }) => {
                        let _ = app.emit("flowww://event", DesktopEvent::PeerLost { device_id });
                    }
                    Ok(NodeEvent::PairingStarted(view)) => {
                        let _ = app.emit("flowww://event", DesktopEvent::PairingStarted { pairing: pairing(view) });
                    }
                    Ok(NodeEvent::PairingCompleted(info)) => {
                        let _ = app.emit("flowww://event", DesktopEvent::PairingCompleted { device: device_info(&info, true, true, None, None) });
                    }
                    Ok(NodeEvent::PairingFailed { .. }) => {
                        let _ = app.emit("flowww://event", DesktopEvent::PairingFailed { reason: "Pairing could not be completed.".into() });
                    }
                    Ok(NodeEvent::Error { .. }) => {
                        let _ = app.emit("flowww://event", DesktopEvent::Error { code: "NODE_ERROR".into(), message: "The local engine reported an error.".into() });
                    }
                    Ok(NodeEvent::OfferReceived(_)) | Ok(NodeEvent::OfferClosed { .. }) | Ok(NodeEvent::SpatialUpdated(_)) => {}
                    Err(tokio::sync::broadcast::error::RecvError::Lagged(_)) => {
                        let _ = app.emit("flowww://event", DesktopEvent::ResyncRequired);
                    }
                    Err(tokio::sync::broadcast::error::RecvError::Closed) => break,
                }
            }
        }
    });
    EventForwarder { stop, task }
}

#[tauri::command]
async fn initialize_node(
    app: AppHandle,
    state: State<'_, AppState>,
) -> Result<LocalDeviceDto, String> {
    let _initializing = state.initialize.lock().await;
    if let Some(node) = state
        .node
        .lock()
        .map_err(|_| "node state lock poisoned".to_string())?
        .clone()
    {
        return Ok(local_device(&node));
    }
    let node = Node::start(NodeConfig::default())
        .await
        .map_err(|_| public_error("NODE_START_FAILED"))?;
    let dto = local_device(&node);
    let mut slot = state
        .node
        .lock()
        .map_err(|_| "node state lock poisoned".to_string())?;
    if let Some(existing) = slot.clone() {
        return Ok(local_device(&existing));
    }
    *slot = Some(node.clone());
    drop(slot);
    let forwarder = emit_node_events(app, &node);
    *state
        .events
        .lock()
        .map_err(|_| "event state lock poisoned".to_string())? = Some(forwarder);
    Ok(dto)
}

#[tauri::command]
async fn get_local_device(state: State<'_, AppState>) -> Result<LocalDeviceDto, String> {
    Ok(local_device(&node(&state)?))
}

#[tauri::command]
async fn get_nearby_devices(state: State<'_, AppState>) -> Result<Vec<DeviceDto>, String> {
    Ok(node(&state)?
        .discovered_peers()
        .await
        .into_iter()
        .map(|peer| DeviceDto {
            device_id: peer.device_id,
            name: peer.name,
            platform: peer.platform.as_str().to_string(),
            paired: peer.paired,
            online: true,
            fingerprint: Some(peer.fingerprint),
            last_seen_ms: Some(peer.last_seen_ms),
        })
        .collect())
}

#[tauri::command]
async fn get_trusted_devices(state: State<'_, AppState>) -> Result<Vec<DeviceDto>, String> {
    Ok(summary(
        &node(&state)?
            .snapshot()
            .map_err(|_| public_error("STATE_UNAVAILABLE"))?,
    ))
}

#[tauri::command]
async fn start_pairing(state: State<'_, AppState>) -> Result<PairingDto, String> {
    Ok(pairing(
        node(&state)?
            .start_pairing()
            .await
            .map_err(|_| public_error("PAIRING_FAILED"))?,
    ))
}

#[tauri::command]
async fn approve_pairing(state: State<'_, AppState>, uri: String) -> Result<DeviceDto, String> {
    let node = node(&state)?;
    let info = node
        .pair_with_uri(&uri)
        .await
        .map_err(|_| public_error("PAIRING_FAILED"))?;
    Ok(device_info(&info, true, true, None, None))
}

#[tauri::command]
async fn reject_pairing(state: State<'_, AppState>) -> Result<bool, String> {
    Ok(node(&state)?.cancel_pairing())
}

#[tauri::command]
async fn get_transfer_history(state: State<'_, AppState>) -> Result<Vec<TransferDto>, String> {
    Ok(node(&state)?
        .snapshot()
        .map_err(|_| public_error("STATE_UNAVAILABLE"))?
        .transfers
        .iter()
        .map(transfer)
        .collect())
}

#[tauri::command]
async fn get_active_transfers(state: State<'_, AppState>) -> Result<Vec<TransferDto>, String> {
    Ok(get_transfer_history(state)
        .await?
        .into_iter()
        .filter(|record| !record.status.is_terminal())
        .collect())
}

#[tauri::command]
async fn get_pending_offers(state: State<'_, AppState>) -> Result<Vec<PendingOfferView>, String> {
    Ok(node(&state)?.pending_offers())
}

#[tauri::command]
async fn respond_to_offer(
    state: State<'_, AppState>,
    transfer_id: String,
    accept: bool,
) -> Result<bool, String> {
    let id: TransferId = transfer_id
        .parse()
        .map_err(|_| "invalid transfer id".to_string())?;
    Ok(node(&state)?.respond_to_offer(id, accept))
}

#[tauri::command]
async fn get_transfer_status(
    state: State<'_, AppState>,
    transfer_id: String,
) -> Result<TransferDto, String> {
    let id: TransferId = transfer_id
        .parse()
        .map_err(|_| "invalid transfer id".to_string())?;
    let node = node(&state)?;
    node.store()
        .get_transfer(id)
        .map_err(|error| error.to_string())?
        .map(|record| transfer(&record))
        .ok_or_else(|| "transfer not found".into())
}

#[tauri::command]
async fn cancel_transfer(
    state: State<'_, AppState>,
    transfer_id: String,
) -> Result<CancellationDto, String> {
    let id: TransferId = transfer_id
        .parse()
        .map_err(|_| "invalid transfer id".to_string())?;
    let node = node(&state)?;
    // Ordinary direct/phone transfers are owned by the node. Spatial
    // cancellation is the fallback for the controller relay path.
    if node.cancel_transfer(&id) || node.cancel_spatial(&id) {
        Ok(CancellationDto {
            accepted: true,
            code: "CANCELLATION_REQUESTED".into(),
            message: "Cancellation requested.".into(),
        })
    } else {
        Ok(CancellationDto {
            accepted: false,
            code: "TRANSFER_NOT_CANCELLABLE".into(),
            message: "This transfer cannot be cancelled by the desktop controller.".into(),
        })
    }
}

#[tauri::command]
async fn shutdown_node(state: State<'_, AppState>) -> Result<(), String> {
    let _initializing = state.initialize.lock().await;
    let forwarder = state
        .events
        .lock()
        .map_err(|_| "event state lock poisoned".to_string())?
        .take();
    if let Some(forwarder) = forwarder {
        let _ = forwarder.stop.send(true);
        let _ = forwarder.task.await;
    }
    if let Some(node) = state
        .node
        .lock()
        .map_err(|_| "node state lock poisoned".to_string())?
        .take()
    {
        node.shutdown();
    }
    Ok(())
}

// ---------------------------------------------------------------- agent commands

#[tauri::command]
async fn agent_start(state: State<'_, AppState>) -> Result<serde_json::Value, String> {
    let config = AgentConfig::default();
    let mut agent = AgentLifecycle::new(config);
    let info = agent.start().await.map_err(|e| format!("agent start failed: {}", e))?;
    let node = agent
        .node()
        .ok_or_else(|| "agent node not running".to_string())?;
    // Store the agent in state.
    *state
        .agent
        .lock()
        .map_err(|_| "agent state lock poisoned".to_string())? = Some(agent);

    // Start IPC server.
    let providers = Arc::new(ProviderRegistry::new());
    let staged = Arc::new(StagedStore::new());

    // Register content providers.
    providers
        .register(Arc::new(crate::agent::providers::explorer::ExplorerProvider::new()))
        .map_err(|e| format!("failed to register explorer provider: {}", e))?;
    providers
        .register(Arc::new(crate::agent::providers::clipboard::ClipboardProvider::new()))
        .map_err(|e| format!("failed to register clipboard provider: {}", e))?;

    let ipc_config = crate::agent::ipc::IpcConfig::default();
    let ipc_server =
        IpcServer::new(ipc_config, providers.clone(), staged.clone(), node);
    let _addr = ipc_server.start().await.map_err(|e| format!("ipc start failed: {}", e))?;
    *state.ipc.lock().map_err(|_| "ipc state lock poisoned".to_string())? = Some(ipc_server);
    *state.providers.lock().map_err(|_| "providers state lock poisoned".to_string())? = Some(providers);
    *state.staged.lock().map_err(|_| "staged state lock poisoned".to_string())? = Some(staged);

    // Initialize Native Messaging host for browser extension communication.
    let nm_host = NativeMessagingHost::new(
        state.providers.lock().map_err(|_| "providers state lock poisoned".to_string())?.clone().unwrap(),
        state.staged.lock().map_err(|_| "staged state lock poisoned".to_string())?.clone().unwrap(),
    );
    *state
        .native_messaging
        .lock()
        .map_err(|_| "native messaging state lock poisoned".to_string())? = Some(nm_host);

    Ok(serde_json::json!({
        "state": info.state.to_string(),
        "node_initialized": info.node_initialized,
        "ipc_addr": info.ipc_addr.map(|a| a.to_string()),
        "auto_start": info.auto_start,
        "single_instance_held": info.single_instance_held,
    }))
}

#[tauri::command]
async fn agent_stop(state: State<'_, AppState>) -> Result<(), String> {
    let agent = {
        let mut guard = state
            .agent
            .lock()
            .map_err(|_| "agent state lock poisoned".to_string())?;
        guard.take()
    };
    if let Some(mut agent) = agent {
        agent.stop().await.map_err(|e| format!("agent stop failed: {}", e))?;
    }
    let ipc = {
        let mut guard = state
            .ipc
            .lock()
            .map_err(|_| "ipc state lock poisoned".to_string())?;
        guard.take()
    };
    if let Some(ipc) = ipc {
        ipc.stop().await;
    }
    Ok(())
}

#[tauri::command]
fn agent_state(state: State<'_, AppState>) -> Result<String, String> {
    let agent = state
        .agent
        .lock()
        .map_err(|_| "agent state lock poisoned".to_string())?;
    match &*agent {
        Some(a) => Ok(a.state().to_string()),
        None => Ok("stopped".to_string()),
    }
}

#[tauri::command]
fn agent_toggle_pause(state: State<'_, AppState>) -> Result<bool, String> {
    let mut agent = state
        .agent
        .lock()
        .map_err(|_| "agent state lock poisoned".to_string())?;
    if let Some(a) = agent.as_mut() {
        if a.is_paused() {
            a.resume();
            Ok(false)
        } else {
            a.pause();
            Ok(true)
        }
    } else {
        Err("agent not running".into())
    }
}

#[tauri::command]
async fn stage_content(
    state: State<'_, AppState>,
    request: StageRequest,
) -> Result<StageResponse, String> {
    let providers = state
        .providers
        .lock()
        .map_err(|_| "providers state lock poisoned".to_string())?
        .clone()
        .ok_or_else(|| "providers not initialized".to_string())?;
    crate::agent::ipc::stage_content(request, providers)
        .await
        .map_err(|e| format!("stage failed: {}", e))
}

#[tauri::command]
async fn inspect_staged_content(
    state: State<'_, AppState>,
    content_id: String,
) -> Result<Option<serde_json::Value>, String> {
    let staged = state
        .staged
        .lock()
        .map_err(|_| "staged state lock poisoned".to_string())?
        .clone()
        .ok_or_else(|| "staged store not initialized".to_string())?;
    let content_id = flowww_core::ContentId(content_id);
    match staged.get(&content_id) {
        Some(item) => Ok(Some(serde_json::to_value(&item).map_err(|e| e.to_string())?)),
        None => Ok(None),
    }
}

#[tauri::command]
fn get_foreground_app() -> Result<Option<ForegroundApp>, String> {
    if !foreground::available() {
        return Ok(None);
    }
    Ok(foreground::detect_foreground())
}

// ---------------------------------------------------------------- content staging commands

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct BrowserTabDto {
    url: String,
    title: String,
    favicon_url: Option<String>,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct StageContentResult {
    content_id: String,
    expires_at: u64,
}

#[tauri::command]
async fn get_active_browser_tab(state: State<'_, AppState>) -> Result<Option<BrowserTabDto>, String> {
    let host = {
        let guard = state
            .native_messaging
            .lock()
            .map_err(|_| "native messaging state lock poisoned".to_string())?;
        guard.clone()
    };
    match host {
        Some(host) => host
            .get_active_tab()
            .map(|tab| Some(BrowserTabDto {
                url: tab.url,
                title: tab.title,
                favicon_url: tab.favicon_url,
            }))
            .map_err(|e| format!("failed to get active tab: {}", e)),
        None => Ok(None),
    }
}

#[tauri::command]
#[allow(dead_code)] // Called from the Tauri frontend, not from Rust code directly.
async fn set_active_browser_tab(
    state: State<'_, AppState>,
    url: String,
    title: String,
    favicon_url: Option<String>,
) -> Result<(), String> {
    let host = {
        let guard = state
            .native_messaging
            .lock()
            .map_err(|_| "native messaging state lock poisoned".to_string())?;
        guard.clone()
    };
    match host {
        Some(host) => {
            host.set_active_tab(BrowserTabInfo {
                url,
                title,
                favicon_url,
            });
            Ok(())
        }
        None => Err("native messaging host not initialized".into()),
    }
}

#[tauri::command]
async fn stage_active_browser_tab(state: State<'_, AppState>) -> Result<StageContentResult, String> {
    let providers = state
        .providers
        .lock()
        .map_err(|_| "providers state lock poisoned".to_string())?
        .clone()
        .ok_or_else(|| "providers not initialized".to_string())?;

    let tab = {
        let host_opt = {
            let guard = state
                .native_messaging
                .lock()
                .map_err(|_| "native messaging state lock poisoned".to_string())?;
            (*guard).clone()
        };
        match host_opt {
            Some(host) => host
                .get_active_tab()
                .map_err(|e| format!("failed to get active tab: {}", e))?,
            None => return Err("native messaging host not initialized".into()),
        }
    };

    // Stage via BrowserProvider
    let provider = providers.get("browser").ok_or_else(|| "browser provider not registered".to_string())?;
    let payload = StagePayload {
        content_type: ContentType::Url,
        display_name: tab.title,
        mime_type: None,
        size: None,
        source_application: Some(tab.url.clone()),
        content_reference: tab.url,
        authorization_context: None,
        ttl_ms: None,
    };
    let result = provider.stage_content(payload).await.map_err(|e| format!("stage failed: {}", e))?;
    Ok(StageContentResult {
        content_id: result.content_id.0,
        expires_at: result.expires_at,
    })
}

#[tauri::command]
async fn stage_selected_text(state: State<'_, AppState>, text: String) -> Result<StageContentResult, String> {
    let providers = state
        .providers
        .lock()
        .map_err(|_| "providers state lock poisoned".to_string())?
        .clone()
        .ok_or_else(|| "providers not initialized".to_string())?;

    let provider = providers.get("browser").ok_or_else(|| "browser provider not registered".to_string())?;
    let payload = StagePayload {
        content_type: ContentType::Text,
        display_name: format!("Selected text ({} chars)", text.len()),
        mime_type: Some("text/plain".into()),
        size: Some(text.len() as u64),
        source_application: Some("browser".into()),
        content_reference: text,
        authorization_context: None,
        ttl_ms: None,
    };
    let result = provider.stage_content(payload).await.map_err(|e| format!("stage failed: {}", e))?;
    Ok(StageContentResult {
        content_id: result.content_id.0,
        expires_at: result.expires_at,
    })
}

#[tauri::command]
async fn stage_selected_files(state: State<'_, AppState>, files: Vec<String>) -> Result<Vec<StageContentResult>, String> {
    let providers = state
        .providers
        .lock()
        .map_err(|_| "providers state lock poisoned".to_string())?
        .clone()
        .ok_or_else(|| "providers not initialized".to_string())?;

    let provider = providers.get("explorer").ok_or_else(|| "explorer provider not registered".to_string())?;

    let mut results = Vec::new();
    for file_path in files {
        let display_name = std::path::Path::new(&file_path)
            .file_name()
            .and_then(|n| n.to_str())
            .unwrap_or(&file_path)
            .to_string();
        let payload = StagePayload {
            content_type: ContentType::File,
            display_name,
            mime_type: None,
            size: None,
            source_application: Some("explorer".into()),
            content_reference: file_path.clone(),
            authorization_context: None,
            ttl_ms: None,
        };
        let result = provider
            .stage_content(payload)
            .await
            .map_err(|e| format!("stage failed for {}: {}", file_path, e))?;
        results.push(StageContentResult {
            content_id: result.content_id.0,
            expires_at: result.expires_at,
        });
    }
    Ok(results)
}

#[tauri::command]
async fn stage_clipboard_content(state: State<'_, AppState>) -> Result<StageContentResult, String> {
    let providers = state
        .providers
        .lock()
        .map_err(|_| "providers state lock poisoned".to_string())?
        .clone()
        .ok_or_else(|| "providers not initialized".to_string())?;

    let provider = providers.get("clipboard").ok_or_else(|| "clipboard provider not registered".to_string())?;
    let current = provider.get_current_content().await.map_err(|e| format!("failed to read clipboard: {}", e))?;
    let content = current.ok_or_else(|| "clipboard is empty or contains unsupported content".to_string())?;

    let payload = StagePayload {
        content_type: content.content_type,
        display_name: content.display_name,
        mime_type: content.mime_type,
        size: content.size,
        source_application: content.source_application,
        content_reference: content.content_reference,
        authorization_context: content.authorization_context,
        ttl_ms: None,
    };
    let result = provider.stage_content(payload).await.map_err(|e| format!("stage failed: {}", e))?;
    Ok(StageContentResult {
        content_id: result.content_id.0,
        expires_at: result.expires_at,
    })
}

#[tauri::command]
async fn expire_staged_content(state: State<'_, AppState>, content_id: String) -> Result<bool, String> {
    let staged = state
        .staged
        .lock()
        .map_err(|_| "staged state lock poisoned".to_string())?
        .clone()
        .ok_or_else(|| "staged store not initialized".to_string())?;
    let content_id = flowww_core::ContentId(content_id);
    staged
        .transition_state(&content_id, ContentLifecycleState::Expired)
        .map_err(|e| format!("expire failed: {}", e))?;
    Ok(true)
}

pub fn run() {
    tauri::Builder::default()
        .manage(AppState {
            node: SyncMutex::new(None),
            initialize: AsyncMutex::new(()),
            events: SyncMutex::new(None),
            agent: SyncMutex::new(None),
            ipc: SyncMutex::new(None),
            providers: SyncMutex::new(None),
            staged: SyncMutex::new(None),
            native_messaging: SyncMutex::new(None),
        })
        .invoke_handler(tauri::generate_handler![
            initialize_node,
            get_local_device,
            get_nearby_devices,
            get_trusted_devices,
            start_pairing,
            approve_pairing,
            reject_pairing,
            get_transfer_history,
            get_active_transfers,
            get_pending_offers,
            respond_to_offer,
            get_transfer_status,
            cancel_transfer,
            shutdown_node,
            agent_start,
            agent_stop,
            agent_state,
            agent_toggle_pause,
            stage_content,
            inspect_staged_content,
            get_foreground_app,
            get_active_browser_tab,
            stage_active_browser_tab,
            stage_selected_text,
            stage_selected_files,
            stage_clipboard_content,
            expire_staged_content,
        ])
        .setup(|app| {
            let _ = build_tray(app.handle());
            Ok(())
        })
        .build(tauri::generate_context!())
        .expect("error while building flowww desktop")
        .run(|app, event| {
            if matches!(event, RunEvent::Exit) {
                app.state::<AppState>().shutdown_now();
            }
            if let RunEvent::Ready = event {
                let _ = app.emit("flowww://event", DesktopEvent::AgentStateChanged {
                    state: "stopped".to_string(),
                });
                // Show the main window when ready.
                if let Some(window) = app.get_webview_window("main") {
                    let _ = window.show();
                    let _ = window.set_focus();
                }
                // Auto-start pairing so the user sees a QR code to scan with their phone.
                let app_handle = app.clone();
                tauri::async_runtime::spawn(async move {
                    thread::sleep(Duration::from_millis(500));
                    let state = app_handle.state::<AppState>();
                    let node = {
                        let guard = match state.node.lock() {
                            Ok(g) => g,
                            Err(_) => {
                                eprintln!("flowww node state lock poisoned");
                                return;
                            }
                        };
                        match guard.clone() {
                            Some(n) => n,
                            None => {
                                eprintln!("flowww node is not initialized");
                                return;
                            }
                        }
                    };

                    match node.start_pairing().await {
                        Ok(view) => {
                            let dto = pairing(view);
                            let _ = app_handle.emit("flowww://event", DesktopEvent::PairingStarted { pairing: dto });
                        }
                        Err(e) => {
                            eprintln!("Pairing failed: {}", e);
                            let _ = app_handle.emit("flowww://event", DesktopEvent::PairingFailed { reason: e.to_string() });
                        }
                    }
                });
            }
        });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn transfer_dto_does_not_contain_content_or_paths() {
        let json = serde_json::to_string(&TransferDto {
            transfer_id: "tr_test".into(),
            source_device_id: "dev_a".into(),
            destination_device_id: "dev_b".into(),
            filename: Some("photo.png".into()),
            size: 10,
            status: TransferState::Completed,
            route: flowww_core::TransferRoute::Direct,
            direction: flowww_storage::TransferDirection::Outgoing,
            bytes_transferred: 10,
            error: None,
            created_at: 1,
            completed_at: Some(2),
        })
        .expect("dto serializes");
        assert!(!json.contains("source_reference"));
        assert!(!json.contains("handle"));
        assert!(!json.contains("inline_content"));
    }

    #[test]
    fn public_errors_do_not_forward_internal_details() {
        let error = public_error("NODE_START_FAILED");
        assert_eq!(error, "NODE_START_FAILED: The operation could not be completed.");
        assert!(!error.contains("sqlite"));
        assert!(!error.contains("identity"));
    }

    #[test]
    fn shutdown_is_idempotent_before_initialization() {
        let state = AppState {
            node: SyncMutex::new(None),
            initialize: AsyncMutex::new(()),
            events: SyncMutex::new(None),
            agent: SyncMutex::new(None),
            ipc: SyncMutex::new(None),
            providers: SyncMutex::new(None),
            staged: SyncMutex::new(None),
            native_messaging: SyncMutex::new(None),
        };
        state.shutdown_now();
        state.shutdown_now();
    }

    #[test]
    fn agent_config_defaults_are_safe() {
        let config = AgentConfig::default();
        assert!(!config.auto_start);
        assert!(config.require_incoming_approval);
        assert!(!config.transfers_paused);
    }

    #[test]
    fn lifecycle_default_is_stopped() {
        let lifecycle = AgentLifecycle::default();
        assert_eq!(lifecycle.state(), crate::agent::lifecycle::AgentState::Stopped);
    }
}
