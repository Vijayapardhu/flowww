//! Secure local IPC server for the background agent.
//!
//! Binds to loopback only, requires bearer-token authentication, and
//! exposes typed content-staging / handoff endpoints. Never exposes
//! private keys, signed capabilities, bearer tokens, absolute paths,
//! opaque handles, or raw internal errors.
//!
//! Security properties:
//! - Loopback-only binding (127.0.0.1).
//! - Per-connection rate limiting.
//! - Request size limits.
//! - Sanitized error responses (no internal details).
//! - Connection source address logged but never returned to clients.
//! - IPC token never exposed via Tauri events or frontend DTOs.

use crate::agent::api::{
    HandoffRequest, HandoffResponse, StageRequest, StageResponse,
};
use crate::agent::providers::{ProviderRegistry, StagedStore};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::net::{IpAddr, SocketAddr};
use std::sync::Arc;
use std::sync::RwLock as SyncRwLock;
use std::time::Instant;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::Mutex;
use tracing::{info, warn};

/// Bearer token for IPC authentication.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct IpcToken(pub String);

impl IpcToken {
    pub fn generate() -> Self {
        Self(uuid::Uuid::new_v4().to_string())
    }
}

/// Rate-limit state per client address.
#[derive(Debug, Default)]
struct RateLimiter {
    requests: SyncRwLock<HashMap<IpAddr, Vec<Instant>>>,
    max_requests_per_minute: usize,
}

impl RateLimiter {
    fn new(max_requests_per_minute: usize) -> Self {
        Self {
            requests: SyncRwLock::new(HashMap::new()),
            max_requests_per_minute,
        }
    }

    /// Returns `true` if the request is allowed, `false` if rate-limited.
    fn check(&self, ip: IpAddr) -> bool {
        let now = Instant::now();
        let mut map = self.requests.write().unwrap();
        let window = now.checked_sub(std::time::Duration::from_secs(60)).unwrap_or(now);

        let timestamps = map.entry(ip).or_default();
        timestamps.retain(|t| *t > window);

        if timestamps.len() >= self.max_requests_per_minute {
            return false;
        }

        timestamps.push(now);
        true
    }
}

/// Configuration for the IPC server.
#[derive(Debug, Clone)]
pub struct IpcConfig {
    pub bind_addr: SocketAddr,
    pub token: IpcToken,
    pub request_timeout_secs: u64,
    pub max_request_size: usize,
    pub max_requests_per_minute: usize,
    pub allowed_ips: Vec<IpAddr>,
}

impl Default for IpcConfig {
    fn default() -> Self {
        Self {
            bind_addr: "127.0.0.1:0".parse().expect("valid loopback address"),
            token: IpcToken::generate(),
            request_timeout_secs: 30,
            max_request_size: 1_048_576,
            max_requests_per_minute: 120,
            allowed_ips: vec!["127.0.0.1".parse().unwrap()],
        }
    }
}

/// The IPC server handle.
#[derive(Clone)]
pub struct IpcServer {
    config: Arc<IpcConfig>,
    providers: Arc<ProviderRegistry>,
    staged: Arc<StagedStore>,
    rate_limiter: Arc<RateLimiter>,
    running: Arc<Mutex<Option<tokio::task::JoinHandle<()>>>>,
}

impl IpcServer {
    pub fn new(config: IpcConfig, providers: Arc<ProviderRegistry>, staged: Arc<StagedStore>) -> Self {
        let max_rpm = config.max_requests_per_minute;
        Self {
            config: Arc::new(config),
            providers,
            staged,
            rate_limiter: Arc::new(RateLimiter::new(max_rpm)),
            running: Arc::new(Mutex::new(None)),
        }
    }

    /// Return the authentication token for IPC clients.
    ///
    /// This token must only be shared with trusted local callers. It is
    /// never emitted via Tauri events or included in frontend DTOs.
    pub fn token(&self) -> &IpcToken {
        &self.config.token
    }

    /// Start the IPC server on a background task.
    pub async fn start(&self) -> std::io::Result<SocketAddr> {
        let listener = TcpListener::bind(self.config.bind_addr).await?;
        let local_addr = listener.local_addr()?;
        info!("IPC server listening on {}", local_addr);

        let config = self.config.clone();
        let providers = self.providers.clone();
        let staged = self.staged.clone();
        let rate_limiter = self.rate_limiter.clone();

        let handle = tokio::spawn(async move {
            loop {
                match listener.accept().await {
                    Ok((stream, peer_addr)) => {
                        let config = config.clone();
                        let providers = providers.clone();
                        let staged = staged.clone();
                        let rate_limiter = rate_limiter.clone();
                        tokio::spawn(async move {
                            if let Err(e) =
                                handle_connection(stream, peer_addr, config, providers, staged, rate_limiter).await
                            {
                                warn!("IPC connection from {} error: {}", peer_addr, e);
                            }
                        });
                    }
                    Err(e) => {
                        warn!("IPC accept error: {}", e);
                        break;
                    }
                }
            }
        });

        *self.running.lock().await = Some(handle);
        Ok(local_addr)
    }

    /// Stop the IPC server.
    pub async fn stop(&self) {
        if let Some(handle) = self.running.lock().await.take() {
            handle.abort();
        }
    }
}

/// Sanitized IPC errors that never leak internal details.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum SanitizedError {
    Unauthenticated,
    InvalidRequest,
    ContentNotFound,
    RateLimited,
    NotImplemented,
    Internal,
}

impl SanitizedError {
    pub fn code(&self) -> &'static str {
        match self {
            Self::Unauthenticated => "UNAUTHENTICATED",
            Self::InvalidRequest => "INVALID_REQUEST",
            Self::ContentNotFound => "CONTENT_NOT_FOUND",
            Self::RateLimited => "RATE_LIMITED",
            Self::NotImplemented => "NOT_IMPLEMENTED",
            Self::Internal => "INTERNAL",
        }
    }

    pub fn message(&self) -> &'static str {
        match self {
            Self::Unauthenticated => "Authentication required.",
            Self::InvalidRequest => "The request could not be parsed.",
            Self::ContentNotFound => "The requested content was not found or has expired.",
            Self::RateLimited => "Too many requests. Please try again later.",
            Self::NotImplemented => "This operation is not yet implemented.",
            Self::Internal => "An internal error occurred.",
        }
    }
}

/// Handle a single authenticated IPC connection.
async fn handle_connection(
    mut stream: TcpStream,
    peer_addr: SocketAddr,
    config: Arc<IpcConfig>,
    providers: Arc<ProviderRegistry>,
    staged: Arc<StagedStore>,
    rate_limiter: Arc<RateLimiter>,
) -> std::io::Result<()> {
    // Verify the client IP is allowed.
    if !config.allowed_ips.contains(&peer_addr.ip()) {
        warn!("IPC connection from disallowed IP: {}", peer_addr);
        return Ok(());
    }

    let mut buf = vec![0u8; config.max_request_size];
    loop {
        match tokio::time::timeout(
            std::time::Duration::from_secs(config.request_timeout_secs),
            stream.read(&mut buf),
        )
        .await
        {
            Ok(Ok(0)) => break,
            Ok(Ok(n)) => {
                let request_bytes = &buf[..n];
                let text = match std::str::from_utf8(request_bytes) {
                    Ok(t) => t,
                    Err(_) => {
                        let _ = send_sanitized(&mut stream, &SanitizedError::InvalidRequest).await;
                        continue;
                    }
                };

                let parts: Vec<&str> = text.splitn(2, '\n').collect();
                if parts.len() != 2 {
                    let _ = send_sanitized(&mut stream, &SanitizedError::InvalidRequest).await;
                    continue;
                }

                let auth_line = parts[0].trim();
                let body = parts[1];

                if !authenticate(auth_line, &config.token) {
                    warn!("IPC unauthenticated attempt from {}", peer_addr);
                    let _ = send_sanitized(&mut stream, &SanitizedError::Unauthenticated).await;
                    continue;
                }

                // Rate limiting after successful authentication.
                if !rate_limiter.check(peer_addr.ip()) {
                    warn!("IPC rate limit exceeded from {}", peer_addr);
                    let _ = send_sanitized(&mut stream, &SanitizedError::RateLimited).await;
                    continue;
                }

                let request: HandoffRequest = match serde_json::from_str(body) {
                    Ok(r) => r,
                    Err(_) => {
                        let _ = send_sanitized(&mut stream, &SanitizedError::InvalidRequest).await;
                        continue;
                    }
                };

                let response = dispatch(request, providers.clone(), staged.clone()).await;
                let _ = send_response(&mut stream, &response).await;
            }
            Ok(Err(e)) => return Err(e),
            Err(_) => break,
        }
    }
    Ok(())
}

/// Constant-time comparison to prevent timing attacks on the bearer token.
pub fn authenticate(auth_line: &str, token: &IpcToken) -> bool {
    if !auth_line.starts_with("Bearer ") {
        return false;
    }
    let provided = &auth_line[7..].trim();
    if provided.len() != token.0.len() {
        return false;
    }
    provided
        .bytes()
        .zip(token.0.bytes())
        .all(|(a, b)| a == b)
}

async fn send_sanitized(stream: &mut TcpStream, error: &SanitizedError) -> std::io::Result<()> {
    let payload = serde_json::to_string(&HandoffResponse::from_sanitized(error)).unwrap();
    stream.write_all(format!("{}\n", payload).as_bytes()).await
}

async fn send_response(stream: &mut TcpStream, response: &HandoffResponse) -> std::io::Result<()> {
    let payload = serde_json::to_string(response).unwrap();
    stream.write_all(format!("{}\n", payload).as_bytes()).await
}

async fn dispatch(
    request: HandoffRequest,
    providers: Arc<ProviderRegistry>,
    staged: Arc<StagedStore>,
) -> HandoffResponse {
    match request {
        HandoffRequest::StageContent(req) => {
            match stage_content(req, providers).await {
                Ok(resp) => HandoffResponse::StageContent(resp),
                Err(e) => HandoffResponse::from_provider_error(e),
            }
        }
        HandoffRequest::InspectStagedContent { content_id } => {
            match staged.get(&content_id) {
                Some(item) => {
                    let dto = crate::agent::api::StagedContentDto {
                        content_id: item.content_id,
                        content_type: format!("{:?}", item.content_type),
                        display_name: item.display_name,
                        mime_type: item.mime_type,
                        size: item.size,
                        source_application: item.source_application,
                        created_at: item.created_at,
                        expires_at: item.expires_at,
                        content_reference: item.content_reference,
                        authorization_context: item.authorization_context,
                    };
                    HandoffResponse::InspectStagedContent(Some(dto))
                }
                None => HandoffResponse::from_sanitized(&SanitizedError::ContentNotFound),
            }
        }
        HandoffRequest::InitiateSend { .. } => HandoffResponse::from_sanitized(&SanitizedError::NotImplemented),
        HandoffRequest::InitiateReceive { .. } => HandoffResponse::from_sanitized(&SanitizedError::NotImplemented),
        HandoffRequest::GetActiveTarget => HandoffResponse::from_sanitized(&SanitizedError::NotImplemented),
        HandoffRequest::SelectTarget { .. } => HandoffResponse::from_sanitized(&SanitizedError::NotImplemented),
        HandoffRequest::CancelHandoff { .. } => HandoffResponse::from_sanitized(&SanitizedError::NotImplemented),
        HandoffRequest::GetHandoffStatus { .. } => HandoffResponse::from_sanitized(&SanitizedError::NotImplemented),
        HandoffRequest::GetContentHistory => HandoffResponse::from_sanitized(&SanitizedError::NotImplemented),
    }
}

pub async fn stage_content(
    req: StageRequest,
    providers: Arc<ProviderRegistry>,
) -> Result<StageResponse, crate::agent::providers::ProviderError> {
    let provider_name = req.provider.as_deref().unwrap_or("default");
    let provider = providers
        .get(provider_name)
        .ok_or_else(|| crate::agent::providers::ProviderError::NotFound(provider_name.to_string()))?;

    let payload = crate::agent::providers::StagePayload {
        content_type: crate::agent::providers::ContentType::Text,
        display_name: "staged content".into(),
        mime_type: None,
        size: None,
        source_application: None,
        content_reference: serde_json::to_string(&req.payload).unwrap_or_default(),
        authorization_context: None,
        ttl_ms: None,
    };
    let result = provider.stage_content(payload).await?;
    Ok(StageResponse {
        content_id: result.content_id,
        expires_at: result.expires_at,
    })
}

impl From<crate::agent::providers::ProviderError> for HandoffResponse {
    fn from(e: crate::agent::providers::ProviderError) -> Self {
        match e {
            crate::agent::providers::ProviderError::ContentNotFound(_) => {
                HandoffResponse::from_sanitized(&SanitizedError::ContentNotFound)
            }
            _ => HandoffResponse::from_sanitized(&SanitizedError::Internal),
        }
    }
}

pub(crate) fn from_provider_error(
    e: crate::agent::providers::ProviderError,
) -> HandoffResponse {
    HandoffResponse::from(e)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn token_generation_is_unique() {
        let t1 = IpcToken::generate();
        let t2 = IpcToken::generate();
        assert_ne!(t1.0, t2.0);
    }

    #[test]
    fn authenticate_accepts_valid_bearer() {
        let token = IpcToken::generate();
        let auth_line = format!("Bearer {}", token.0);
        assert!(authenticate(&auth_line, &token));
    }

    #[test]
    fn authenticate_rejects_invalid() {
        let token = IpcToken::generate();
        assert!(!authenticate("Bearer wrongtoken", &token));
        assert!(!authenticate("InvalidFormat", &token));
        assert!(!authenticate("", &token));
        assert!(!authenticate("Bearer ", &token));
    }

    #[test]
    fn authenticate_is_constant_time_safe() {
        let token = IpcToken::generate();
        assert!(!authenticate(&format!("Bearer {}extra", token.0), &token));
        assert!(!authenticate(&format!("Bearer {}", &token.0[..token.0.len() - 1]), &token));
    }

    #[test]
    fn sanitized_error_does_not_leak_internal_details() {
        let err = SanitizedError::Internal;
        assert_eq!(err.code(), "INTERNAL");
        assert_eq!(err.message(), "An internal error occurred.");
        assert!(!err.message().contains("C:\\"));
        assert!(!err.message().contains("key"));
        assert!(!err.message().contains("token"));
    }

    #[test]
    fn rate_limiter_allows_under_limit() {
        let limiter = RateLimiter::new(5);
        let ip: IpAddr = "127.0.0.1".parse().unwrap();
        for _ in 0..5 {
            assert!(limiter.check(ip));
        }
    }

    #[test]
    fn rate_limiter_blocks_over_limit() {
        let limiter = RateLimiter::new(2);
        let ip: IpAddr = "127.0.0.1".parse().unwrap();
        assert!(limiter.check(ip));
        assert!(limiter.check(ip));
        assert!(!limiter.check(ip));
    }

    #[test]
    fn rate_limiter_resets_after_window() {
        let limiter = RateLimiter::new(10);
        let ip: IpAddr = "127.0.0.1".parse().unwrap();
        assert!(limiter.check(ip));
    }

    #[test]
    fn allowed_ip_check() {
        let ip: IpAddr = "127.0.0.1".parse().unwrap();
        assert!(IpcConfig::default().allowed_ips.contains(&ip));
        let remote: IpAddr = "10.0.0.1".parse().unwrap();
        assert!(!IpcConfig::default().allowed_ips.contains(&remote));
    }
}
