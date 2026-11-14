use std::sync::Mutex;

use flowww_core::{Device, DeviceInfo, TransferId, TransferState};
use flowww_node::{Node, NodeConfig, NodeEvent, NodeSnapshot, PairingView};
use flowww_storage::TransferRecord;
use serde::Serialize;
use tauri::{AppHandle, Emitter, Manager, RunEvent, State};
use tokio::sync::{watch, Mutex as AsyncMutex};

struct AppState {
    node: Mutex<Option<Node>>,
    initialize: AsyncMutex<()>,
    events: Mutex<Option<EventForwarder>>,
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
        .map(|peer| peer.device_id.as_str())
        .collect::<std::collections::HashSet<_>>();
    snapshot
        .devices
        .iter()
        .map(|device: &Device| {
            device_info(
                &device.info,
                device.trusted,
                online.contains(device.info.device_id.as_str()),
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
    if node(&state)?.cancel_spatial(&id) {
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

pub fn run() {
    tauri::Builder::default()
        .manage(AppState {
            node: Mutex::new(None),
            initialize: AsyncMutex::new(()),
            events: Mutex::new(None),
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
            get_transfer_status,
            cancel_transfer,
            shutdown_node
        ])
        .build(tauri::generate_context!())
        .expect("error while building flowww desktop")
        .run(|app, event| {
            if matches!(event, RunEvent::Exit) {
                app.state::<AppState>().shutdown_now();
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
            node: Mutex::new(None),
            initialize: AsyncMutex::new(()),
            events: Mutex::new(None),
        };
        state.shutdown_now();
        state.shutdown_now();
    }
}
