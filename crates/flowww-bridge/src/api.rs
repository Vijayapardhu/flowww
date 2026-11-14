//! Localhost JSON API, WebSocket event stream and static UI.

use std::path::{Path, PathBuf};
use std::sync::Arc;

use axum::body::Body;
use axum::extract::multipart::Multipart;
use axum::extract::ws::{Message as WsMessage, WebSocket, WebSocketUpgrade};
use axum::extract::{Path as UrlPath, State};
use axum::http::{header, HeaderMap, HeaderValue, Request, StatusCode};
use axum::middleware::{self, Next};
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post};
use axum::{Json, Router};
use futures_util::StreamExt;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use subtle::ConstantTimeEq;
use tokio::io::AsyncWriteExt;
use tokio::sync::broadcast;
use tower_http::limit::RequestBodyLimitLayer;
use tracing::debug;
use uuid::Uuid;

use flowww_core::{
    sanitize_filename, Content, DeviceId, DeviceInfo, TransferId, TransferRoute, TransferState,
};
use flowww_node::{Node, NodeError, NodeSnapshot, PairingView, SpatialView};
use flowww_storage::{StorageError, TransferRecord};

use crate::qr;

const INDEX_HTML: &str = include_str!("../assets/index.html");
const APP_CSS: &str = include_str!("../assets/app.css");
const APP_JS: &str = include_str!("../assets/app.js");
const FAVICON: &str = include_str!("../assets/favicon.svg");

pub(crate) const CONTENT_SECURITY_POLICY: &str = "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' blob: data:; connect-src 'self' ws://127.0.0.1:* ws://localhost:*; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";

#[derive(Clone)]
pub(crate) struct ApiState {
    pub node: Node,
    pub token: Arc<str>,
    pub allowed_hosts: Vec<String>,
    pub allowed_origins: Vec<String>,
    pub uploads_dir: PathBuf,
    pub max_upload_bytes: u64,
}

pub(crate) fn router(state: ApiState) -> Router {
    let max_body =
        usize::try_from(state.max_upload_bytes.saturating_add(1024 * 1024)).unwrap_or(usize::MAX);

    let api = Router::new()
        .route("/health", get(health))
        .route("/snapshot", get(snapshot))
        .route("/events", get(events))
        .route("/pairing", get(pairing_state))
        .route("/pairing/start", post(start_pairing))
        .route("/pairing/join", post(join_pairing))
        .route("/pairing/cancel", post(cancel_pairing))
        .route("/pairing/qr.svg", get(pairing_qr))
        .route("/offers/{transfer_id}/accept", post(accept_offer))
        .route("/offers/{transfer_id}/decline", post(decline_offer))
        .route("/send/text", post(send_text))
        .route("/send/url", post(send_url))
        .route("/send/file/{device_id}", post(send_file))
        .route("/spatial/hold", post(hold_content))
        .route("/spatial/cancel", post(cancel_hold))
        .route("/spatial/drop", post(drop_content))
        .route("/spatial/capture", post(request_capture))
        .route("/spatial/command/prepare", post(prepare_command))
        .route("/spatial/command/submit", post(submit_command))
        .route(
            "/spatial/control/{transfer_id}/disconnect",
            post(disconnect_control),
        )
        .route(
            "/spatial/control/{transfer_id}/cancel",
            post(cancel_control),
        )
        .route("/spatial/control/{transfer_id}/status", get(control_status))
        .route("/settings", post(update_settings))
        .route("/trust/revoke", post(revoke_device))
        .route_layer(middleware::from_fn_with_state(state.clone(), guard));

    Router::new()
        .route("/", get(index))
        .route("/app.css", get(stylesheet))
        .route("/app.js", get(script))
        .route("/favicon.svg", get(favicon))
        .nest("/api", api)
        .layer(RequestBodyLimitLayer::new(max_body))
        .layer(middleware::from_fn(security_headers))
        .with_state(state)
}

// ------------------------------------------------------------------ security

async fn guard(State(state): State<ApiState>, request: Request<Body>, next: Next) -> Response {
    if !host_is_allowed(&state, request.headers()) {
        return ApiError::forbidden("unexpected Host header").into_response();
    }
    if !origin_is_allowed(&state, request.headers()) {
        return ApiError::forbidden("unexpected Origin header").into_response();
    }
    if !request_is_authorized(&state, request.headers(), request.uri().query()) {
        return ApiError::unauthorized("missing or invalid access token").into_response();
    }
    next.run(request).await
}

fn host_is_allowed(state: &ApiState, headers: &HeaderMap) -> bool {
    let Some(host) = headers
        .get(header::HOST)
        .and_then(|value| value.to_str().ok())
    else {
        return false;
    };
    state
        .allowed_hosts
        .iter()
        .any(|allowed| allowed.eq_ignore_ascii_case(host))
}

fn origin_is_allowed(state: &ApiState, headers: &HeaderMap) -> bool {
    let Some(origin) = headers.get(header::ORIGIN) else {
        return true;
    };
    let Ok(origin) = origin.to_str() else {
        return false;
    };
    state
        .allowed_origins
        .iter()
        .any(|allowed| allowed.eq_ignore_ascii_case(origin))
}

fn request_is_authorized(state: &ApiState, headers: &HeaderMap, query: Option<&str>) -> bool {
    if let Some(value) = headers
        .get(header::AUTHORIZATION)
        .and_then(|value| value.to_str().ok())
    {
        if let Some(token) = value.strip_prefix("Bearer ") {
            if constant_time_eq(&state.token, token) {
                return true;
            }
        }
    }
    // WebSocket clients cannot set headers, so the token may travel in the
    // query string. It is still compared in constant time.
    query
        .and_then(query_token)
        .is_some_and(|token| constant_time_eq(&state.token, token))
}

fn query_token(query: &str) -> Option<&str> {
    query.split('&').find_map(|pair| {
        let (key, value) = pair.split_once('=')?;
        (key == "token").then_some(value)
    })
}

fn constant_time_eq(expected: &str, provided: &str) -> bool {
    expected.as_bytes().ct_eq(provided.as_bytes()).into()
}

async fn security_headers(request: Request<Body>, next: Next) -> Response {
    let mut response = next.run(request).await;
    let headers = response.headers_mut();
    headers.insert(
        "content-security-policy",
        HeaderValue::from_static(CONTENT_SECURITY_POLICY),
    );
    headers.insert(
        header::X_CONTENT_TYPE_OPTIONS,
        HeaderValue::from_static("nosniff"),
    );
    headers.insert(
        header::REFERRER_POLICY,
        HeaderValue::from_static("no-referrer"),
    );
    headers.insert("x-frame-options", HeaderValue::from_static("DENY"));
    headers.insert(
        "cross-origin-opener-policy",
        HeaderValue::from_static("same-origin"),
    );
    headers.insert(
        "cross-origin-resource-policy",
        HeaderValue::from_static("same-origin"),
    );
    headers.insert(header::CACHE_CONTROL, HeaderValue::from_static("no-store"));
    response
}

// ---------------------------------------------------------------- UI assets

async fn index() -> Response {
    asset(INDEX_HTML, "text/html; charset=utf-8")
}

async fn stylesheet() -> Response {
    asset(APP_CSS, "text/css; charset=utf-8")
}

async fn script() -> Response {
    asset(APP_JS, "text/javascript; charset=utf-8")
}

async fn favicon() -> Response {
    asset(FAVICON, "image/svg+xml")
}

fn asset(body: &'static str, content_type: &'static str) -> Response {
    (
        [
            (header::CONTENT_TYPE, content_type),
            (header::CACHE_CONTROL, "no-store"),
        ],
        body,
    )
        .into_response()
}

// ---------------------------------------------------------------- API: core

async fn health() -> Json<Value> {
    Json(json!({ "ok": true }))
}

async fn snapshot(State(state): State<ApiState>) -> Result<Json<NodeSnapshot>, ApiError> {
    Ok(Json(state.node.snapshot()?))
}

async fn events(ws: WebSocketUpgrade, State(state): State<ApiState>) -> Response {
    ws.on_upgrade(move |socket| event_stream(socket, state.node))
}

async fn event_stream(mut socket: WebSocket, node: Node) {
    let mut events = node.subscribe();
    loop {
        tokio::select! {
            event = events.recv() => match event {
                Ok(event) => {
                    let Ok(text) = serde_json::to_string(&event) else {
                        continue;
                    };
                    if socket.send(WsMessage::Text(text.into())).await.is_err() {
                        break;
                    }
                }
                Err(broadcast::error::RecvError::Lagged(_)) => {
                    // The client must not assume it saw every transfer update.
                    // Tell it to reload the authoritative snapshot/status.
                    let _ = socket
                        .send(WsMessage::Text(
                            r#"{"type":"RESYNC_REQUIRED"}"#.to_string().into(),
                        ))
                        .await;
                    continue;
                }
                Err(broadcast::error::RecvError::Closed) => break,
            },
            incoming = socket.next() => match incoming {
                Some(Ok(WsMessage::Close(_))) | None => break,
                Some(Ok(WsMessage::Ping(payload))) => {
                    if socket.send(WsMessage::Pong(payload)).await.is_err() {
                        break;
                    }
                }
                Some(Ok(_)) => {}
                Some(Err(error)) => {
                    debug!("local UI socket closed: {error}");
                    break;
                }
            }
        }
    }
}

// ------------------------------------------------------------- API: pairing

async fn pairing_state(State(state): State<ApiState>) -> Json<Option<PairingView>> {
    Json(state.node.active_pairing())
}

async fn start_pairing(State(state): State<ApiState>) -> Result<Json<PairingView>, ApiError> {
    Ok(Json(state.node.start_pairing().await?))
}

async fn cancel_pairing(State(state): State<ApiState>) -> Json<Value> {
    Json(json!({ "cancelled": state.node.cancel_pairing() }))
}

#[derive(Deserialize)]
struct JoinRequest {
    uri: String,
}

async fn join_pairing(
    State(state): State<ApiState>,
    Json(request): Json<JoinRequest>,
) -> Result<Json<DeviceInfo>, ApiError> {
    Ok(Json(state.node.pair_with_uri(&request.uri).await?))
}

async fn pairing_qr(State(state): State<ApiState>) -> Result<Response, ApiError> {
    let Some(pairing) = state.node.active_pairing() else {
        return Err(ApiError::not_found("no pairing session is active"));
    };
    let svg = qr::pairing_svg(&pairing.uri)
        .map_err(|_| ApiError::internal("could not render the pairing code"))?;
    Ok((
        [
            (header::CONTENT_TYPE, "image/svg+xml"),
            (header::CACHE_CONTROL, "no-store"),
        ],
        svg,
    )
        .into_response())
}

// --------------------------------------------------------------- API: offers

async fn accept_offer(
    State(state): State<ApiState>,
    UrlPath(transfer_id): UrlPath<String>,
) -> Result<Json<Value>, ApiError> {
    respond_to_offer(&state, &transfer_id, true)
}

async fn decline_offer(
    State(state): State<ApiState>,
    UrlPath(transfer_id): UrlPath<String>,
) -> Result<Json<Value>, ApiError> {
    respond_to_offer(&state, &transfer_id, false)
}

fn respond_to_offer(
    state: &ApiState,
    transfer_id: &str,
    accept: bool,
) -> Result<Json<Value>, ApiError> {
    let transfer_id = parse_transfer_id(transfer_id)?;
    if state.node.respond_to_offer(transfer_id, accept) {
        Ok(Json(json!({ "ok": true })))
    } else {
        Err(ApiError::not_found("that offer is no longer pending"))
    }
}

// ----------------------------------------------------------------- API: send

#[derive(Deserialize)]
struct TextRequest {
    device_id: String,
    text: String,
}

#[derive(Deserialize)]
struct UrlRequest {
    device_id: String,
    url: String,
}

async fn send_text(
    State(state): State<ApiState>,
    Json(request): Json<TextRequest>,
) -> Result<Json<TransferRecord>, ApiError> {
    let device_id = parse_device_id(&request.device_id)?;
    Ok(Json(state.node.send_text(&device_id, &request.text).await?))
}

async fn send_url(
    State(state): State<ApiState>,
    Json(request): Json<UrlRequest>,
) -> Result<Json<TransferRecord>, ApiError> {
    let device_id = parse_device_id(&request.device_id)?;
    Ok(Json(state.node.send_url(&device_id, &request.url).await?))
}

async fn send_file(
    State(state): State<ApiState>,
    UrlPath(device_id): UrlPath<String>,
    mut multipart: Multipart,
) -> Result<Json<TransferRecord>, ApiError> {
    let device_id = parse_device_id(&device_id)?;
    let mut upload: Option<(PathBuf, PathBuf)> = None;

    while let Some(mut field) = multipart
        .next_field()
        .await
        .map_err(|error| ApiError::bad_request(format!("could not read the upload: {error}")))?
    {
        if field.name() != Some("file") {
            continue;
        }
        if upload.is_some() {
            return Err(ApiError::bad_request("send one file per request"));
        }

        let original = field
            .file_name()
            .map(sanitize_filename)
            .filter(|name| !name.is_empty() && name != "file")
            .unwrap_or_else(|| "upload.bin".to_string());
        let slot = state.uploads_dir.join(Uuid::new_v4().simple().to_string());
        tokio::fs::create_dir_all(&slot).await?;
        let path = slot.join(&original);
        let mut file = tokio::fs::File::create(&path).await?;

        let mut total = 0u64;
        loop {
            let chunk = match field.chunk().await {
                Ok(Some(chunk)) => chunk,
                Ok(None) => break,
                Err(error) => {
                    remove_upload(&path).await;
                    return Err(ApiError::bad_request(format!(
                        "the upload ended early: {error}"
                    )));
                }
            };
            total += chunk.len() as u64;
            if total > state.max_upload_bytes {
                remove_upload(&path).await;
                return Err(ApiError::payload_too_large(
                    "the upload is larger than this bridge allows",
                ));
            }
            file.write_all(&chunk).await?;
        }
        file.flush().await?;
        drop(file);

        if total == 0 {
            remove_upload(&path).await;
            return Err(ApiError::bad_request("the uploaded file is empty"));
        }
        upload = Some((slot, path));
    }

    let Some((slot, path)) = upload else {
        return Err(ApiError::bad_request("the request did not include a file"));
    };

    let result = state.node.send_file(&device_id, &path).await;
    let _ = tokio::fs::remove_file(&path).await;
    let _ = tokio::fs::remove_dir(&slot).await;
    Ok(Json(result?))
}

async fn remove_upload(path: &Path) {
    let _ = tokio::fs::remove_file(path).await;
    if let Some(parent) = path.parent() {
        let _ = tokio::fs::remove_dir(parent).await;
    }
}

// -------------------------------------------------------------- API: spatial

#[derive(Deserialize)]
struct HoldRequest {
    kind: String,
    value: String,
}

async fn hold_content(
    State(state): State<ApiState>,
    Json(request): Json<HoldRequest>,
) -> Result<Json<SpatialView>, ApiError> {
    let view = match request.kind.as_str() {
        "text" => state.node.hold_text(&request.value)?,
        "url" => state.node.hold_url(&request.value)?,
        _ => return Err(ApiError::bad_request("kind must be text or url")),
    };
    Ok(Json(view))
}

async fn cancel_hold(State(state): State<ApiState>) -> Json<SpatialView> {
    Json(state.node.cancel_hold())
}

#[derive(Deserialize)]
struct DropRequest {
    x: f32,
    y: f32,
}

async fn drop_content(
    State(state): State<ApiState>,
    Json(request): Json<DropRequest>,
) -> Result<Json<TransferRecord>, ApiError> {
    if !request.x.is_finite() || !request.y.is_finite() {
        return Err(ApiError::bad_request("the drop position is invalid"));
    }
    Ok(Json(state.node.drop_spatial(request.x, request.y).await?))
}

#[derive(Deserialize)]
struct CaptureRequest {
    source_device_id: String,
}

#[derive(Serialize)]
struct CaptureResponse {
    content: Content,
}

async fn request_capture(
    State(state): State<ApiState>,
    Json(request): Json<CaptureRequest>,
) -> Result<Json<CaptureResponse>, ApiError> {
    let source_device = parse_device_id(&request.source_device_id)?;
    let capability = state.node.request_capture(&source_device).await?;
    Ok(Json(CaptureResponse {
        content: capability.content().clone(),
    }))
}

#[derive(Deserialize)]
struct PrepareCommandRequest {
    destination_device_id: String,
}

#[derive(Serialize)]
struct PrepareCommandResponse {
    transfer_id: TransferId,
}

async fn prepare_command(
    State(state): State<ApiState>,
    Json(request): Json<PrepareCommandRequest>,
) -> Result<Json<PrepareCommandResponse>, ApiError> {
    let destination = parse_device_id(&request.destination_device_id)?;
    let command = state.node.prepare_spatial_command(&destination)?;
    Ok(Json(PrepareCommandResponse {
        transfer_id: command.claim.transfer_id,
    }))
}

#[derive(Deserialize)]
struct SubmitCommandRequest {
    transfer_id: TransferId,
}

#[derive(Serialize)]
struct SubmitCommandResponse {
    transfer_id: TransferId,
    route: TransferRoute,
    controller_device_id: String,
}

async fn submit_command(
    State(state): State<ApiState>,
    Json(request): Json<SubmitCommandRequest>,
) -> Result<Json<SubmitCommandResponse>, ApiError> {
    let control = state
        .node
        .submit_spatial_command(
            state
                .node
                .prepared_spatial_command(&request.transfer_id)
                .ok_or(NodeError::NothingHeld)?,
            state
                .node
                .captured_capability()
                .ok_or(NodeError::NothingHeld)?,
        )
        .await?;
    let transfer_id = control.transfer_id();
    let route = control.route();
    let controller_device_id = state.node.device_id().as_str().to_string();
    // Keep consuming the controller session so progress, terminal states, and
    // disconnects are reflected in this node's store and event stream.
    tokio::spawn(async move {
        let _ = control.wait().await;
    });
    Ok(Json(SubmitCommandResponse {
        transfer_id,
        route,
        controller_device_id,
    }))
}

async fn disconnect_control(
    State(state): State<ApiState>,
    UrlPath(transfer_id): UrlPath<String>,
) -> Result<Json<Value>, ApiError> {
    let transfer_id = parse_transfer_id(&transfer_id)?;
    let disconnected = state.node.disconnect_spatial(&transfer_id);
    Ok(Json(
        json!({ "disconnected": disconnected, "action": "disconnect" }),
    ))
}

async fn cancel_control(
    State(state): State<ApiState>,
    UrlPath(transfer_id): UrlPath<String>,
) -> Result<Json<Value>, ApiError> {
    let transfer_id = parse_transfer_id(&transfer_id)?;
    let cancelled = state.node.cancel_spatial(&transfer_id);
    Ok(Json(json!({ "cancelled": cancelled, "action": "cancel" })))
}

#[derive(Serialize)]
struct ControlStatusResponse {
    transfer_id: TransferId,
    status: TransferState,
    bytes_transferred: u64,
    total_size: u64,
    route: TransferRoute,
    source_device_id: String,
    destination_device_id: String,
    controller_device_id: String,
    error: Option<String>,
    completed_at: Option<u64>,
}

async fn control_status(
    State(state): State<ApiState>,
    UrlPath(transfer_id): UrlPath<String>,
) -> Result<Json<ControlStatusResponse>, ApiError> {
    let transfer_id = parse_transfer_id(&transfer_id)?;
    if !state.node.owns_spatial_transfer(&transfer_id) {
        return Err(ApiError::not_found("transfer not found"));
    }
    let record = state
        .node
        .store()
        .get_transfer(transfer_id)?
        .ok_or_else(|| ApiError::not_found("transfer not found"))?;
    Ok(Json(ControlStatusResponse {
        transfer_id: record.id,
        status: record.status,
        bytes_transferred: record.bytes_transferred,
        total_size: record.size,
        route: record.route,
        source_device_id: record.source_device.as_str().to_string(),
        destination_device_id: record.destination_device.as_str().to_string(),
        // The status endpoint is scoped to this bridge instance, which is the
        // controller that submitted the spatial command.
        controller_device_id: state.node.device_id().as_str().to_string(),
        error: record.error,
        completed_at: record.completed_at,
    }))
}

// ------------------------------------------------------------- API: settings

#[derive(Deserialize)]
struct SettingsRequest {
    auto_accept: bool,
}

async fn update_settings(
    State(state): State<ApiState>,
    Json(request): Json<SettingsRequest>,
) -> Result<Json<Value>, ApiError> {
    state.node.set_auto_accept(request.auto_accept)?;
    Ok(Json(json!({ "auto_accept": state.node.auto_accept() })))
}

#[derive(Deserialize)]
struct RevokeRequest {
    device_id: String,
}

async fn revoke_device(
    State(state): State<ApiState>,
    Json(request): Json<RevokeRequest>,
) -> Result<Json<Value>, ApiError> {
    let device_id = parse_device_id(&request.device_id)?;
    state.node.revoke(&device_id)?;
    Ok(Json(json!({ "revoked": true })))
}

// --------------------------------------------------------------------- utils

fn parse_device_id(raw: &str) -> Result<DeviceId, ApiError> {
    DeviceId::new(raw).map_err(|error| ApiError::bad_request(error.to_string()))
}

fn parse_transfer_id(raw: &str) -> Result<TransferId, ApiError> {
    raw.parse()
        .map_err(|_| ApiError::bad_request("invalid transfer id"))
}

#[derive(Debug)]
pub(crate) struct ApiError {
    status: StatusCode,
    message: String,
}

impl ApiError {
    fn bad_request(message: impl Into<String>) -> Self {
        Self {
            status: StatusCode::BAD_REQUEST,
            message: message.into(),
        }
    }

    fn unauthorized(message: impl Into<String>) -> Self {
        Self {
            status: StatusCode::UNAUTHORIZED,
            message: message.into(),
        }
    }

    fn forbidden(message: impl Into<String>) -> Self {
        Self {
            status: StatusCode::FORBIDDEN,
            message: message.into(),
        }
    }

    fn not_found(message: impl Into<String>) -> Self {
        Self {
            status: StatusCode::NOT_FOUND,
            message: message.into(),
        }
    }

    fn payload_too_large(message: impl Into<String>) -> Self {
        Self {
            status: StatusCode::PAYLOAD_TOO_LARGE,
            message: message.into(),
        }
    }

    fn internal(message: impl Into<String>) -> Self {
        Self {
            status: StatusCode::INTERNAL_SERVER_ERROR,
            message: message.into(),
        }
    }
}

impl From<std::io::Error> for ApiError {
    fn from(error: std::io::Error) -> Self {
        Self {
            status: StatusCode::INTERNAL_SERVER_ERROR,
            message: format!("local storage error: {error}"),
        }
    }
}

impl From<NodeError> for ApiError {
    fn from(error: NodeError) -> Self {
        let status = match &error {
            NodeError::UnknownPeer(_)
            | NodeError::PeerUnavailable(_)
            | NodeError::NoSpatialTarget => StatusCode::NOT_FOUND,
            NodeError::PairingBusy | NodeError::NoPairing => StatusCode::CONFLICT,
            NodeError::Timeout => StatusCode::GATEWAY_TIMEOUT,
            _ => StatusCode::BAD_REQUEST,
        };
        Self {
            status,
            message: error.to_string(),
        }
    }
}

impl From<StorageError> for ApiError {
    fn from(error: StorageError) -> Self {
        Self {
            status: StatusCode::INTERNAL_SERVER_ERROR,
            message: format!("storage error: {error}"),
        }
    }
}

#[derive(Serialize)]
struct ErrorBody {
    error: String,
}

impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        (
            self.status,
            Json(ErrorBody {
                error: self.message,
            }),
        )
            .into_response()
    }
}
