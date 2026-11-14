//! The localhost bridge and daemon entrypoint.
//!
//! The daemon owns the network and storage. This crate serves a small web UI
//! and a JSON API on loopback only. Every API and WebSocket request needs a
//! random bearer token, and requests are checked against the expected `Host`
//! and `Origin` to block DNS rebinding and cross-site access.

mod api;
mod qr;

use std::net::{IpAddr, SocketAddr};
use std::sync::Arc;

use axum::Router;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use rand::rngs::OsRng;
use rand::RngCore;
use thiserror::Error;
use tokio::net::TcpListener;
use tokio::task::JoinHandle;
use tokio_util::sync::CancellationToken;
use tracing::info;

use flowww_node::Node;

#[derive(Debug, Error)]
pub enum BridgeError {
    #[error("could not start the local UI server: {0}")]
    Io(#[from] std::io::Error),
}

pub type BridgeResult<T> = Result<T, BridgeError>;

/// How the local UI server should listen.
#[derive(Debug, Clone)]
pub struct BridgeConfig {
    /// Loopback address. Port 0 picks a free port.
    pub bind_addr: SocketAddr,
    /// Largest accepted upload, in bytes.
    pub max_upload_bytes: u64,
}

impl Default for BridgeConfig {
    fn default() -> Self {
        Self {
            bind_addr: SocketAddr::from(([127, 0, 0, 1], 0)),
            max_upload_bytes: 1024 * 1024 * 1024,
        }
    }
}

/// A running localhost UI server.
pub struct Bridge {
    addr: SocketAddr,
    #[allow(dead_code)] // Kept for tests and future programmatic clients.
    token: String,
    url: String,
    #[allow(dead_code)]
    router: Router,
    stop: CancellationToken,
    task: JoinHandle<()>,
}

impl Bridge {
    pub async fn start(node: Node, config: BridgeConfig) -> BridgeResult<Self> {
        let uploads_dir = node.config().data_dir().join("uploads");
        tokio::fs::create_dir_all(&uploads_dir).await?;

        let listener = TcpListener::bind(config.bind_addr).await?;
        let addr = listener.local_addr()?;
        let token = random_token();

        let host = url_host(addr.ip());
        let allowed_hosts = vec![
            format!("{host}:{}", addr.port()),
            format!("localhost:{}", addr.port()),
        ];
        let allowed_origins = allowed_hosts
            .iter()
            .map(|host| format!("http://{host}"))
            .collect::<Vec<_>>();
        let url = format!("http://{}:{}/#token={}", host, addr.port(), token);

        let state = api::ApiState {
            node,
            token: Arc::from(token.as_str()),
            allowed_hosts,
            allowed_origins,
            uploads_dir,
            max_upload_bytes: config.max_upload_bytes,
        };
        let router = api::router(state.clone());
        let server_router = router.clone();
        let stop = CancellationToken::new();
        let shutdown = stop.clone();
        let task = tokio::spawn(async move {
            let server = axum::serve(listener, server_router)
                .with_graceful_shutdown(async move { shutdown.cancelled().await });
            if let Err(error) = server.await {
                tracing::error!("local UI server stopped: {error}");
            }
        });

        info!("local UI listening on {}", url);
        Ok(Self {
            addr,
            token,
            url,
            router,
            stop,
            task,
        })
    }

    /// The one-time URL to open. The access token lives in the fragment, so it
    /// is never sent to the server in a request line or referrer.
    pub fn url(&self) -> &str {
        &self.url
    }

    pub fn addr(&self) -> SocketAddr {
        self.addr
    }

    /// Stop accepting requests and wait for the server task to finish.
    pub async fn shutdown(mut self) {
        self.stop.cancel();
        let _ = (&mut self.task).await;
    }
}

fn random_token() -> String {
    let mut bytes = [0u8; 32];
    OsRng.fill_bytes(&mut bytes);
    URL_SAFE_NO_PAD.encode(bytes)
}

fn url_host(ip: IpAddr) -> String {
    match ip {
        IpAddr::V4(ip) => ip.to_string(),
        IpAddr::V6(ip) => format!("[{ip}]"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::body::Body;
    use axum::http::{header, Method, Request, StatusCode};
    use flowww_node::{Node, NodeConfig};
    use http_body_util::BodyExt;
    use std::path::Path;
    use tower::ServiceExt;

    async fn test_bridge(dir: &Path) -> Bridge {
        let config = NodeConfig::new(dir)
            .with_download_dir(dir.join("downloads"))
            .with_discovery(false)
            .with_listen_addr("127.0.0.1:0".parse().expect("loopback"))
            .with_device_name("Test device")
            .with_auto_accept(true);
        let node = Node::start(config).await.expect("node starts");
        Bridge::start(
            node,
            BridgeConfig {
                bind_addr: "127.0.0.1:0".parse().expect("loopback"),
                max_upload_bytes: 8 * 1024 * 1024,
            },
        )
        .await
        .expect("bridge starts")
    }

    fn request(bridge: &Bridge, method: Method, path: &str, auth: bool) -> Request<Body> {
        let host = format!("127.0.0.1:{}", bridge.addr.port());
        let mut builder = Request::builder()
            .method(method)
            .uri(path)
            .header(header::HOST, host.clone())
            .header(header::ORIGIN, format!("http://{host}"));
        if auth {
            builder = builder.header(header::AUTHORIZATION, format!("Bearer {}", bridge.token));
        }
        builder.body(Body::empty()).expect("request builds")
    }

    async fn call(
        bridge: &Bridge,
        request: Request<Body>,
    ) -> (StatusCode, axum::http::HeaderMap, Vec<u8>) {
        let response = bridge
            .router
            .clone()
            .oneshot(request)
            .await
            .expect("request completes");
        let status = response.status();
        let headers = response.headers().clone();
        let body = response
            .into_body()
            .collect()
            .await
            .expect("body is readable")
            .to_bytes()
            .to_vec();
        (status, headers, body)
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn ui_is_public_but_api_is_not() {
        let dir = tempfile::tempdir().expect("temp dir");
        let bridge = test_bridge(dir.path()).await;

        let (status, headers, body) =
            call(&bridge, request(&bridge, Method::GET, "/", false)).await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(
            headers
                .get(header::CONTENT_TYPE)
                .and_then(|v| v.to_str().ok()),
            Some("text/html; charset=utf-8")
        );
        assert!(String::from_utf8_lossy(&body).contains("flowww"));

        let (status, _, _) = call(
            &bridge,
            request(&bridge, Method::GET, "/api/snapshot", false),
        )
        .await;
        assert_eq!(status, StatusCode::UNAUTHORIZED);

        let (status, _, body) = call(
            &bridge,
            request(&bridge, Method::GET, "/api/snapshot", true),
        )
        .await;
        assert_eq!(status, StatusCode::OK);
        let snapshot: serde_json::Value = serde_json::from_slice(&body).expect("snapshot JSON");
        assert_eq!(snapshot["device"]["device_name"], "Test device");

        let (status, _, _) = call(
            &bridge,
            request(
                &bridge,
                Method::GET,
                &format!("/api/snapshot?token={}", bridge.token),
                false,
            ),
        )
        .await;
        assert_eq!(status, StatusCode::OK);

        bridge.shutdown().await;
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn host_and_origin_are_checked() {
        let dir = tempfile::tempdir().expect("temp dir");
        let bridge = test_bridge(dir.path()).await;

        let mut wrong_origin = request(&bridge, Method::GET, "/api/snapshot", true);
        wrong_origin.headers_mut().insert(
            header::ORIGIN,
            "http://evil.example".parse().expect("header value"),
        );
        let (status, _, _) = call(&bridge, wrong_origin).await;
        assert_eq!(status, StatusCode::FORBIDDEN);

        let mut wrong_host = request(&bridge, Method::GET, "/api/snapshot", true);
        wrong_host
            .headers_mut()
            .insert(header::HOST, "evil.example".parse().expect("header value"));
        let (status, _, _) = call(&bridge, wrong_host).await;
        assert_eq!(status, StatusCode::FORBIDDEN);

        bridge.shutdown().await;
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn pairing_qr_requires_an_active_session() {
        let dir = tempfile::tempdir().expect("temp dir");
        let bridge = test_bridge(dir.path()).await;

        let (status, _, _) = call(
            &bridge,
            request(&bridge, Method::GET, "/api/pairing/qr.svg", true),
        )
        .await;
        assert_eq!(status, StatusCode::NOT_FOUND);

        let (status, _, body) = call(
            &bridge,
            request(&bridge, Method::POST, "/api/pairing/start", true),
        )
        .await;
        assert_eq!(status, StatusCode::OK);
        let pairing: serde_json::Value = serde_json::from_slice(&body).expect("pairing JSON");
        assert!(pairing["uri"]
            .as_str()
            .is_some_and(|uri| uri.starts_with("flowww://pair/v1/")));

        let (status, headers, body) = call(
            &bridge,
            request(&bridge, Method::GET, "/api/pairing/qr.svg", true),
        )
        .await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(
            headers
                .get(header::CONTENT_TYPE)
                .and_then(|v| v.to_str().ok()),
            Some("image/svg+xml")
        );
        assert!(String::from_utf8_lossy(&body).contains("<svg"));

        let (status, _, body) = call(
            &bridge,
            request(&bridge, Method::POST, "/api/pairing/cancel", true),
        )
        .await;
        assert_eq!(status, StatusCode::OK);
        let cancelled: serde_json::Value = serde_json::from_slice(&body).expect("cancel JSON");
        assert_eq!(cancelled["cancelled"], true);

        let (status, _, _) = call(
            &bridge,
            request(&bridge, Method::GET, "/api/pairing/qr.svg", true),
        )
        .await;
        assert_eq!(status, StatusCode::NOT_FOUND);

        bridge.shutdown().await;
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn static_assets_and_security_headers_are_served() {
        let dir = tempfile::tempdir().expect("temp dir");
        let bridge = test_bridge(dir.path()).await;

        for path in ["/app.css", "/app.js", "/favicon.svg"] {
            let (status, _, data) = call(&bridge, request(&bridge, Method::GET, path, false)).await;
            assert_eq!(status, StatusCode::OK, "{path} is served");
            assert!(!data.is_empty(), "{path} is not empty");
        }

        let (_, headers, _) = call(&bridge, request(&bridge, Method::GET, "/", false)).await;
        assert_eq!(
            headers
                .get("content-security-policy")
                .and_then(|value| value.to_str().ok()),
            Some(api::CONTENT_SECURITY_POLICY)
        );
        assert_eq!(
            headers
                .get(header::X_CONTENT_TYPE_OPTIONS)
                .and_then(|value| value.to_str().ok()),
            Some("nosniff")
        );

        bridge.shutdown().await;
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 4)]
    async fn spatial_capture_returns_remote_capability_content_without_handle() {
        let source_dir = tempfile::tempdir().expect("source temp dir");
        let controller_dir = tempfile::tempdir().expect("controller temp dir");
        let source = Node::start(
            NodeConfig::new(source_dir.path())
                .with_download_dir(source_dir.path().join("downloads"))
                .with_discovery(false)
                .with_listen_addr("127.0.0.1:0".parse().expect("loopback"))
                .with_device_name("Remote source")
                .with_auto_accept(true),
        )
        .await
        .expect("source starts");
        let controller = Node::start(
            NodeConfig::new(controller_dir.path())
                .with_download_dir(controller_dir.path().join("downloads"))
                .with_discovery(false)
                .with_listen_addr("127.0.0.1:0".parse().expect("loopback"))
                .with_device_name("Controller")
                .with_auto_accept(true),
        )
        .await
        .expect("controller starts");
        source
            .store()
            .save_pair(
                controller.device(),
                &controller.public_key(),
                &controller.tls_fingerprint(),
                None,
            )
            .expect("source trusts controller");
        controller
            .store()
            .save_pair(
                source.device(),
                &source.public_key(),
                &source.tls_fingerprint(),
                None,
            )
            .expect("controller trusts source");
        source
            .register_peer_endpoint(
                controller.device_id(),
                format!("127.0.0.1:{}", controller.listen_port())
                    .parse()
                    .expect("source endpoint"),
            )
            .await;
        controller
            .register_peer_endpoint(
                source.device_id(),
                format!("127.0.0.1:{}", source.listen_port())
                    .parse()
                    .expect("controller endpoint"),
            )
            .await;
        source
            .hold_text("remote preview")
            .expect("source holds content");

        let bridge = Bridge::start(controller, BridgeConfig::default())
            .await
            .expect("bridge starts");
        let host = format!("127.0.0.1:{}", bridge.addr.port());
        let body = serde_json::to_vec(&serde_json::json!({
            "source_device_id": source.device_id().as_str(),
        }))
        .expect("request JSON");
        let response = bridge
            .router
            .clone()
            .oneshot(
                Request::builder()
                    .method(Method::POST)
                    .uri("/api/spatial/capture")
                    .header(header::HOST, &host)
                    .header(header::ORIGIN, format!("http://{host}"))
                    .header(header::AUTHORIZATION, format!("Bearer {}", bridge.token))
                    .header(header::CONTENT_TYPE, "application/json")
                    .body(Body::from(body))
                    .expect("capture request"),
            )
            .await
            .expect("capture response");
        assert_eq!(response.status(), StatusCode::OK);
        let response_body = response
            .into_body()
            .collect()
            .await
            .expect("capture body")
            .to_bytes();
        let payload: serde_json::Value =
            serde_json::from_slice(&response_body).expect("capture JSON");
        assert_eq!(payload["content"]["inline"], "remote preview");
        assert_eq!(
            payload["content"]["source_device"],
            source.device_id().as_str()
        );
        assert!(payload.get("capability").is_none());
        assert!(serde_json::to_string(&payload)
            .expect("payload encoding")
            .find("handle_id")
            .is_none());

        bridge.shutdown().await;
        source.shutdown();
    }

    #[test]
    fn tokens_are_random_and_persistable_in_urls() {
        let first = random_token();
        let second = random_token();
        assert_ne!(first, second);
        assert_eq!(first.len(), 43);
        assert!(first
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_'));
    }
}
