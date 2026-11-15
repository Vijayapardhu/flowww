//! Content-request (provider RECEIVE) path over real pinned TLS.
//!
//! Two devices: a source that holds content through a fake platform
//! `ContentSource`, and a receiver that asks for it via `Node::request_content`.
//! Discovery is disabled everywhere; endpoints are registered explicitly so
//! every test is hermetic and fully offline.

use std::net::SocketAddr;
use std::path::{Path, PathBuf};
use std::sync::Arc;

use async_trait::async_trait;
use flowww_core::{Content, ContentHash, DeviceId, TransferState};
use flowww_node::{
    ContentSource, Node, NodeConfig, ProviderContent,
};
use flowww_storage::Store;
use sha2::{Digest, Sha256};

fn sha256_of(bytes: &[u8]) -> ContentHash {
    ContentHash::from_slice(&Sha256::digest(bytes)).expect("SHA-256 is 32 bytes")
}

/// A platform source that always holds one staged file.
struct StagedSource {
    source_device: DeviceId,
    path: PathBuf,
    bytes: Vec<u8>,
    name: String,
    mime: String,
}

#[async_trait]
impl ContentSource for StagedSource {
    async fn current_content(&self, _provider: &str) -> flowww_node::NodeResult<
        Option<ProviderContent>,
    > {
        Ok(Some(ProviderContent::file(
            Content::file(
                self.source_device.clone(),
                &self.name,
                &self.mime,
                self.bytes.len() as u64,
                sha256_of(&self.bytes),
                self.path.to_string_lossy().to_string(),
            )
            .expect("valid file content"),
            self.path.clone(),
        )))
    }

    fn providers(&self) -> Vec<String> {
        vec!["explorer".into()]
    }
}

fn source_config(dir: &Path, name: &str, source: Arc<dyn ContentSource>) -> NodeConfig {
    NodeConfig::new(dir)
        .with_download_dir(dir.join("downloads"))
        .with_device_name(name)
        .with_discovery(false)
        .with_listen_addr("127.0.0.1:0".parse().expect("loopback address"))
        .with_auto_accept(true)
        .with_content_source(source)
}

async fn source_node(
    dir: &Path,
    name: &str,
    source_device: &DeviceId,
    source: Arc<dyn ContentSource>,
) -> Node {
    preset_device_id(dir, source_device);
    Node::start(source_config(dir, name, source))
        .await
        .expect("source node starts")
}

/// A platform source that holds nothing.
struct EmptySource;

#[async_trait]
impl ContentSource for EmptySource {
    async fn current_content(&self, _provider: &str) -> flowww_node::NodeResult<
        Option<ProviderContent>,
    > {
        Ok(None)
    }

    fn providers(&self) -> Vec<String> {
        Vec::new()
    }
}

/// A platform source that holds inline text.
struct TextSource {
    source_device: DeviceId,
    text: String,
}

#[async_trait]
impl ContentSource for TextSource {
    async fn current_content(&self, _provider: &str) -> flowww_node::NodeResult<
        Option<ProviderContent>,
    > {
        Ok(Some(ProviderContent::inline(
            Content::text(self.source_device.clone(), self.text.clone())
                .expect("valid text"),
        )))
    }

    fn providers(&self) -> Vec<String> {
        vec!["clipboard".into()]
    }
}

/// Persist one direction of trust.
fn trust(node: &Node, peer: &Node) {
    node.store()
        .save_pair(
            peer.device(),
            &peer.public_key(),
            &peer.tls_fingerprint(),
            None,
        )
        .expect("peer is trusted");
}

async fn register(node: &Node, peer: &Node) {
    node.register_peer_endpoint(peer.device_id(), {
        SocketAddr::from(([127, 0, 0, 1], peer.listen_port()))
    })
    .await;
}

async fn wait_for_terminal(node: &Node, id: flowww_core::TransferId) {
    for _ in 0..1000 {
        if let Some(record) = node
            .store()
            .list_transfers(80)
            .expect("transfers are readable")
            .into_iter()
            .find(|record| record.id == id)
        {
            if record.status.is_terminal() {
                return;
            }
        }
        tokio::time::sleep(std::time::Duration::from_millis(10)).await;
    }
    panic!("transfer {id} did not reach a terminal state");
}

fn write_source(dir: &Path, name: &str, size: usize) -> (PathBuf, Vec<u8>) {
    let mut bytes = vec![0u8; size];
    for (index, byte) in bytes.iter_mut().enumerate() {
        *byte = (index % 251) as u8;
    }
    let path = dir.join(name);
    std::fs::write(&path, &bytes).expect("test file is written");
    (path, bytes)
}

/// Pre-seeds the store so `Node::start` uses a known device id. This lets the
/// test's fake `ContentSource` embed the same device id in the content it
/// offers, which the receiver's offer validation requires.
fn preset_device_id(dir: &Path, id: &DeviceId) {
    Store::open(dir.join("flowww.db"))
        .expect("test store opens")
        .set_setting("device_id", id.as_str())
        .expect("device_id is stored");
}

async fn receiver_node(dir: &Path, name: &str) -> Node {
    Node::start(
        NodeConfig::new(dir)
            .with_download_dir(dir.join("downloads"))
            .with_device_name(name)
            .with_discovery(false)
            .with_listen_addr("127.0.0.1:0".parse().expect("loopback address"))
            .with_auto_accept(true),
    )
    .await
    .expect("receiver node starts")
}

async fn pair(source: &Node, receiver: &Node) {
    trust(source, receiver);
    trust(receiver, source);
    register(source, receiver).await;
    register(receiver, source).await;
}

#[tokio::test]
async fn request_content_transfers_a_staged_file() {
    let source_dir = tempfile::tempdir().expect("temp dir");
    let receiver_dir = tempfile::tempdir().expect("temp dir");

    let (path, bytes) = write_source(source_dir.path(), "photo.jpg", 12_345);
    let mime = "image/jpeg".to_string();

    let source_device = DeviceId::generate();
    let source = source_node(
        source_dir.path(),
        "source",
        &source_device,
        Arc::new(StagedSource {
            source_device: source_device.clone(),
            path,
            bytes: bytes.clone(),
            name: "photo.jpg".to_string(),
            mime: mime.clone(),
        }),
    )
    .await;
    let receiver = receiver_node(receiver_dir.path(), "receiver").await;

    pair(&source, &receiver).await;

    let record = match receiver
        .request_content(source.device_id(), "explorer")
        .await
    {
        Ok(r) => r,
        Err(e) => panic!("request_content failed: {e:?}"),
    };

    wait_for_terminal(&receiver, record.id).await;
    let received = receiver
        .store()
        .list_transfers(80)
        .expect("transfers are readable")
        .into_iter()
        .find(|entry| entry.id == record.id)
        .expect("record exists");
    assert_eq!(received.status, TransferState::Completed);
    assert_eq!(received.filename.as_deref(), Some("photo.jpg"));

    let on_disk = receiver_dir.path().join("downloads").join("photo.jpg");
    let got = std::fs::read(&on_disk).expect("received file is on disk");
    assert_eq!(got, bytes);
    assert_eq!(received.sha256, Some(sha256_of(&bytes).to_hex()));
}

#[tokio::test]
async fn request_content_transfers_inline_text() {
    let source_dir = tempfile::tempdir().expect("temp dir");
    let receiver_dir = tempfile::tempdir().expect("temp dir");

    let text = "hello from the provider".to_string();

    let source_device = DeviceId::generate();
    let source = source_node(
        source_dir.path(),
        "source",
        &source_device,
        Arc::new(TextSource {
            source_device: source_device.clone(),
            text: text.clone(),
        }),
    )
    .await;
    let receiver = receiver_node(receiver_dir.path(), "receiver").await;

    pair(&source, &receiver).await;

    let record = receiver
        .request_content(source.device_id(), "clipboard")
        .await
        .expect("text content request succeeds");

    wait_for_terminal(&receiver, record.id).await;
    let received = receiver
        .store()
        .list_transfers(80)
        .expect("transfers are readable")
        .into_iter()
        .find(|entry| entry.id == record.id)
        .expect("record exists");
    assert_eq!(received.status, TransferState::Completed);
    assert_eq!(received.inline_content.as_deref(), Some(text.as_str()));
}

#[tokio::test]
async fn request_content_with_no_content_returns_unavailable() {
    let source_dir = tempfile::tempdir().expect("temp dir");
    let receiver_dir = tempfile::tempdir().expect("temp dir");

    let source_device = DeviceId::generate();
    let source = source_node(
        source_dir.path(),
        "source",
        &source_device,
        Arc::new(EmptySource),
    )
    .await;
    let receiver = receiver_node(receiver_dir.path(), "receiver").await;

    pair(&source, &receiver).await;

    let error = receiver
        .request_content(source.device_id(), "explorer")
        .await
        .expect_err("a source with nothing to offer is unavailable");
    assert!(
        matches!(error, flowww_node::NodeError::ContentUnavailable(_)),
        "expected ContentUnavailable, got {error:?}"
    );
}

#[tokio::test]
async fn request_content_with_unknown_provider_returns_unavailable() {
    let source_dir = tempfile::tempdir().expect("temp dir");
    let receiver_dir = tempfile::tempdir().expect("temp dir");

    let source_device = DeviceId::generate();
    let source = source_node(
        source_dir.path(),
        "source",
        &source_device,
        Arc::new(flowww_node::NoContentSource),
    )
    .await;
    let receiver = receiver_node(receiver_dir.path(), "receiver").await;

    pair(&source, &receiver).await;

    let error = receiver
        .request_content(source.device_id(), "explorer")
        .await
        .expect_err("an unknown provider is unavailable");
    assert!(
        matches!(error, flowww_node::NodeError::ContentUnavailable(_)),
        "expected ContentUnavailable, got {error:?}"
    );
}

#[tokio::test]
async fn request_content_from_an_unpaired_peer_returns_unknown_peer() {
    let source_dir = tempfile::tempdir().expect("temp dir");
    let receiver_dir = tempfile::tempdir().expect("temp dir");

    let source_device = DeviceId::generate();
    let source = source_node(
        source_dir.path(),
        "source",
        &source_device,
        Arc::new(StagedSource {
            source_device: source_device.clone(),
            path: PathBuf::from("photo.jpg"),
            bytes: b"bytes".to_vec(),
            name: "photo.jpg".to_string(),
            mime: "image/jpeg".to_string(),
        }),
    )
    .await;
    let receiver = receiver_node(receiver_dir.path(), "receiver").await;

    // No trust in either direction: the receiver does not know the source.
    register(&source, &receiver).await;
    register(&receiver, &source).await;

    let error = receiver
        .request_content(source.device_id(), "explorer")
        .await
        .expect_err("an unpaired peer is unknown");
    assert!(
        matches!(error, flowww_node::NodeError::UnknownPeer(_)),
        "expected UnknownPeer, got {error:?}"
    );
}

#[tokio::test]
async fn request_content_requires_approval_when_auto_accept_is_off() {
    let source_dir = tempfile::tempdir().expect("temp dir");
    let receiver_dir = tempfile::tempdir().expect("temp dir");

    let source_device = DeviceId::generate();
    let source = source_node(
        source_dir.path(),
        "source",
        &source_device,
        Arc::new(StagedSource {
            source_device: source_device.clone(),
            path: PathBuf::from("photo.jpg"),
            bytes: b"bytes".to_vec(),
            name: "photo.jpg".to_string(),
            mime: "image/jpeg".to_string(),
        }),
    )
    .await;
    let receiver = Node::start(
        NodeConfig::new(receiver_dir.path())
            .with_download_dir(receiver_dir.path().join("downloads"))
            .with_device_name("receiver")
            .with_discovery(false)
            .with_listen_addr("127.0.0.1:0".parse().expect("loopback address"))
            .with_auto_accept(false),
    )
    .await
    .expect("receiver node starts");

    pair(&source, &receiver).await;

    // Auto-accept is off and nobody responds: the offer times out and the
    // request is rejected.
    let error = receiver
        .request_content(source.device_id(), "explorer")
        .await
        .expect_err("an unapproved content offer is rejected");
    assert!(
        matches!(error, flowww_node::NodeError::OfferRejected(_)),
        "expected OfferRejected, got {error:?}"
    );
}
