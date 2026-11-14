//! Three-device spatial engine tests over real pinned TLS.
//!
//! A is the source that holds content, B is the phone controller and C is the
//! third device that receives. Discovery is disabled everywhere: endpoints are
//! registered explicitly so every test is hermetic and fully offline.

use std::net::SocketAddr;
use std::path::Path;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;
use std::time::Duration;

use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};

use flowww_core::{TransferRoute, TransferState};
use flowww_node::{Node, NodeConfig};
use flowww_storage::TransferRecord;

fn config(dir: &Path, name: &str) -> NodeConfig {
    NodeConfig::new(dir)
        .with_download_dir(dir.join("downloads"))
        .with_device_name(name)
        .with_discovery(false)
        .with_listen_addr("127.0.0.1:0".parse().expect("loopback address"))
        .with_auto_accept(true)
}

async fn start(dir: &Path, name: &str) -> Node {
    Node::start(config(dir, name)).await.expect("node starts")
}

fn endpoint(node: &Node) -> SocketAddr {
    SocketAddr::from(([127, 0, 0, 1], node.listen_port()))
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

/// Persist a full trust triangle and teach every node how to reach the others.
async fn wire_triangle(nodes: &[&Node]) {
    for left in nodes {
        for right in nodes {
            if left.device_id() != right.device_id() {
                trust(left, right);
            }
        }
    }
    for left in nodes {
        for right in nodes {
            if left.device_id() != right.device_id() {
                left.register_peer_endpoint(right.device_id(), endpoint(right))
                    .await;
            }
        }
    }
}

async fn register(node: &Node, peer: &Node) {
    node.register_peer_endpoint(peer.device_id(), endpoint(peer))
        .await;
}

async fn wait_for_transfer(node: &Node, id: flowww_core::TransferId) -> TransferRecord {
    for _ in 0..1000 {
        if let Some(record) = node
            .store()
            .list_transfers(80)
            .expect("transfers are readable")
            .into_iter()
            .find(|record| record.id == id)
        {
            if record.status.is_terminal() {
                return record;
            }
        }
        tokio::time::sleep(Duration::from_millis(10)).await;
    }
    panic!("transfer {id} did not reach a terminal state");
}

async fn wait_for_any_terminal(node: &Node, id: flowww_core::TransferId) -> Option<TransferRecord> {
    for _ in 0..1000 {
        if let Some(record) = node
            .store()
            .list_transfers(80)
            .expect("transfers are readable")
            .into_iter()
            .find(|record| record.id == id)
        {
            if record.status.is_terminal() {
                return Some(record);
            }
        }
        tokio::time::sleep(Duration::from_millis(10)).await;
    }
    None
}

async fn wait_for_record(node: &Node, id: flowww_core::TransferId) -> Option<TransferRecord> {
    for _ in 0..500 {
        if let Some(record) = node
            .store()
            .list_transfers(80)
            .expect("transfers are readable")
            .into_iter()
            .find(|record| record.id == id)
        {
            return Some(record);
        }
        tokio::time::sleep(Duration::from_millis(10)).await;
    }
    None
}

fn payload(size: usize) -> Vec<u8> {
    let mut bytes = vec![0u8; size];
    for (index, byte) in bytes.iter_mut().enumerate() {
        *byte = (index % 251) as u8;
    }
    bytes
}

fn write_source(dir: &Path, name: &str, size: usize) -> (std::path::PathBuf, Vec<u8>) {
    let bytes = payload(size);
    let path = dir.join(name);
    std::fs::write(&path, &bytes).expect("test file is written");
    (path, bytes)
}

fn received_path(node_dir: &Path, record: &TransferRecord) -> std::path::PathBuf {
    node_dir
        .join("downloads")
        .join(record.filename.as_ref().expect("received file has a name"))
}

struct Proxy {
    addr: SocketAddr,
    connections: Arc<AtomicUsize>,
}

struct ProxyConfig {
    /// Only the first connection is truncated after this many bytes.
    first_limit: Option<u64>,
    /// Delay after every forwarded slice, to keep a transfer observable.
    delay: Option<Duration>,
}

async fn start_proxy(target: SocketAddr, config: ProxyConfig) -> Proxy {
    let listener = TcpListener::bind(("127.0.0.1", 0))
        .await
        .expect("proxy binds");
    let addr = listener.local_addr().expect("proxy address");
    let connections = Arc::new(AtomicUsize::new(0));
    let counter = connections.clone();
    tokio::spawn(async move {
        loop {
            let Ok((mut downstream, _)) = listener.accept().await else {
                break;
            };
            let index = counter.fetch_add(1, Ordering::SeqCst);
            let limit = if index == 0 { config.first_limit } else { None };
            tokio::spawn(async move {
                let Ok(mut upstream) = TcpStream::connect(target).await else {
                    return;
                };
                let (mut down_read, mut down_write) = downstream.split();
                let (mut up_read, mut up_write) = upstream.split();
                let client_to_server = pump(&mut down_read, &mut up_write, limit, config.delay);
                let server_to_client = pump(&mut up_read, &mut down_write, None, config.delay);
                tokio::select! {
                    _ = client_to_server => {}
                    _ = server_to_client => {}
                }
            });
        }
    });
    Proxy { addr, connections }
}

async fn pump<R, W>(reader: &mut R, writer: &mut W, limit: Option<u64>, delay: Option<Duration>)
where
    R: AsyncRead + Unpin,
    W: AsyncWrite + Unpin,
{
    let mut remaining = limit;
    let mut buffer = vec![0u8; 16 * 1024];
    loop {
        let allowed = match remaining {
            Some(0) => return,
            Some(left) => (left as usize).min(buffer.len()),
            None => buffer.len(),
        };
        let read = match reader.read(&mut buffer[..allowed]).await {
            Ok(0) | Err(_) => return,
            Ok(read) => read,
        };
        if writer.write_all(&buffer[..read]).await.is_err() {
            return;
        }
        if let Some(delay) = delay {
            tokio::time::sleep(delay).await;
        }
        if let Some(left) = remaining.as_mut() {
            *left -= read as u64;
        }
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 6)]
async fn direct_spatial_transfer_completes_on_all_three_nodes() {
    let source_dir = tempfile::tempdir().expect("temp dir");
    let controller_dir = tempfile::tempdir().expect("temp dir");
    let destination_dir = tempfile::tempdir().expect("temp dir");
    let source = start(source_dir.path(), "Source").await;
    let controller = start(controller_dir.path(), "Controller").await;
    let destination = start(destination_dir.path(), "Destination").await;
    wire_triangle(&[&source, &controller, &destination]).await;

    source
        .hold_text("three-device hello")
        .expect("content is held");
    let capability = controller
        .request_capture(source.device_id())
        .await
        .expect("controller captures the handle");
    assert_eq!(capability.claim.source_device, *source.device_id());
    assert_eq!(capability.claim.controller_device, *controller.device_id());
    assert_eq!(
        capability.content().inline.as_deref(),
        Some("three-device hello")
    );
    assert_eq!(capability.content().source_device, *source.device_id());
    assert!(!serde_json::to_string(&capability)
        .unwrap()
        .contains("source_reference"));

    let record = controller
        .send_spatial(destination.device_id())
        .await
        .expect("direct spatial transfer completes");
    assert_eq!(record.status, TransferState::Completed);
    assert_eq!(record.route, TransferRoute::Direct);
    assert_eq!(record.bytes_transferred, record.size);

    let received = wait_for_transfer(&destination, record.id).await;
    assert_eq!(received.status, TransferState::Completed);
    assert_eq!(received.source_device, *source.device_id());
    assert_eq!(
        received.inline_content.as_deref(),
        Some("three-device hello")
    );

    let at_source = wait_for_transfer(&source, record.id).await;
    assert_eq!(at_source.status, TransferState::Completed);

    source.shutdown();
    controller.shutdown();
    destination.shutdown();
}

#[tokio::test(flavor = "multi_thread", worker_threads = 6)]
async fn controller_cancel_requests_cancellation_without_disconnect_semantics() {
    let source_dir = tempfile::tempdir().expect("temp dir");
    let controller_dir = tempfile::tempdir().expect("temp dir");
    let destination_dir = tempfile::tempdir().expect("temp dir");
    let source = start(source_dir.path(), "Source").await;
    let controller = start(controller_dir.path(), "Controller").await;
    let destination = start(destination_dir.path(), "Destination").await;
    wire_triangle(&[&source, &controller, &destination]).await;

    let (path, _) = write_source(source_dir.path(), "cancel.bin", 3 * 1024 * 1024);
    source.hold_file(&path).await.expect("content is held");
    controller
        .request_capture(source.device_id())
        .await
        .expect("capture succeeds");
    let command = controller
        .prepare_spatial_command(destination.device_id())
        .expect("command is prepared");
    let capability = controller
        .captured_capability()
        .expect("capability is captured");
    let transfer_id = command.claim.transfer_id;
    let control = controller
        .submit_spatial_command(command, capability)
        .await
        .expect("source accepts the command");
    tokio::spawn(async move {
        let _ = control.wait().await;
    });
    wait_for_record(&destination, transfer_id)
        .await
        .expect("destination sees the transfer");

    assert!(controller.cancel_spatial(&transfer_id));
    let source_record = wait_for_any_terminal(&source, transfer_id)
        .await
        .expect("source reaches a terminal state");
    assert_eq!(source_record.status, TransferState::Cancelled);
    assert!(!controller.disconnect_spatial(&transfer_id));

    source.shutdown();
    controller.shutdown();
    destination.shutdown();
}

#[tokio::test(flavor = "multi_thread", worker_threads = 6)]
async fn controller_disconnect_endpoint_closes_observation_without_cancel_request() {
    let source_dir = tempfile::tempdir().expect("temp dir");
    let controller_dir = tempfile::tempdir().expect("temp dir");
    let destination_dir = tempfile::tempdir().expect("temp dir");
    let source = start(source_dir.path(), "Source").await;
    let controller = start(controller_dir.path(), "Controller").await;
    let destination = start(destination_dir.path(), "Destination").await;
    wire_triangle(&[&source, &controller, &destination]).await;

    let (path, _) = write_source(source_dir.path(), "disconnect.bin", 3 * 1024 * 1024);
    source.hold_file(&path).await.expect("content is held");
    controller
        .request_capture(source.device_id())
        .await
        .expect("capture succeeds");
    let command = controller
        .prepare_spatial_command(destination.device_id())
        .expect("command is prepared");
    let capability = controller
        .captured_capability()
        .expect("capability is captured");
    let transfer_id = command.claim.transfer_id;
    let control = controller
        .submit_spatial_command(command, capability)
        .await
        .expect("source accepts the command");
    tokio::spawn(async move {
        let _ = control.wait().await;
    });
    wait_for_record(&destination, transfer_id)
        .await
        .expect("destination sees the transfer");

    assert!(controller.disconnect_spatial(&transfer_id));
    let source_record = wait_for_any_terminal(&source, transfer_id)
        .await
        .expect("source reaches a terminal state");
    assert_ne!(source_record.status, TransferState::Completed);
    assert_ne!(source_record.status, TransferState::Cancelled);

    source.shutdown();
    controller.shutdown();
    destination.shutdown();
}

#[tokio::test(flavor = "multi_thread", worker_threads = 6)]
async fn relay_spatial_transfer_carries_bytes_through_controller() {
    let source_dir = tempfile::tempdir().expect("temp dir");
    let controller_dir = tempfile::tempdir().expect("temp dir");
    let destination_dir = tempfile::tempdir().expect("temp dir");
    let source = start(source_dir.path(), "Source").await;
    let controller = start(controller_dir.path(), "Controller").await;
    let destination = start(destination_dir.path(), "Destination").await;

    // Triangle trust, but the source deliberately cannot reach the
    // destination directly: only the controller knows C's address.
    trust(&source, &controller);
    trust(&controller, &source);
    trust(&source, &destination);
    trust(&destination, &source);
    trust(&controller, &destination);
    trust(&destination, &controller);
    register(&source, &controller).await;
    register(&controller, &source).await;
    register(&controller, &destination).await;
    register(&destination, &controller).await;

    let (path, bytes) = write_source(source_dir.path(), "relayed.bin", 3 * 1024 * 1024 + 17);
    source.hold_file(&path).await.expect("file is held");
    controller
        .request_capture(source.device_id())
        .await
        .expect("capture succeeds");

    let record = controller
        .send_spatial(destination.device_id())
        .await
        .expect("relayed spatial transfer completes");
    assert_eq!(record.status, TransferState::Completed);
    assert_eq!(record.route, TransferRoute::PhoneRelay);
    assert_eq!(record.bytes_transferred, bytes.len() as u64);

    let received = wait_for_transfer(&destination, record.id).await;
    assert_eq!(received.status, TransferState::Completed);
    assert_eq!(received.route, TransferRoute::PhoneRelay);
    assert_eq!(
        std::fs::read(received_path(destination_dir.path(), &received)).unwrap(),
        bytes
    );

    let at_controller = wait_for_transfer(&controller, record.id).await;
    assert_eq!(at_controller.status, TransferState::Completed);
    assert_eq!(at_controller.route, TransferRoute::PhoneRelay);

    source.shutdown();
    controller.shutdown();
    destination.shutdown();
}

#[tokio::test(flavor = "multi_thread", worker_threads = 6)]
async fn controller_disconnect_stops_the_source() {
    let source_dir = tempfile::tempdir().expect("temp dir");
    let controller_dir = tempfile::tempdir().expect("temp dir");
    let destination_dir = tempfile::tempdir().expect("temp dir");
    let source = start(source_dir.path(), "Source").await;
    let controller = start(controller_dir.path(), "Controller").await;
    let destination = start(destination_dir.path(), "Destination").await;
    wire_triangle(&[&source, &controller, &destination]).await;

    // Route the source through a slow proxy so there is a real window in which
    // the controller can disappear.
    let proxy = start_proxy(
        endpoint(&destination),
        ProxyConfig {
            first_limit: None,
            delay: Some(Duration::from_millis(15)),
        },
    )
    .await;
    source
        .register_peer_endpoint(destination.device_id(), proxy.addr)
        .await;

    let (path, _) = write_source(source_dir.path(), "interrupted.bin", 2 * 1024 * 1024 + 3);
    source.hold_file(&path).await.expect("file is held");
    controller
        .request_capture(source.device_id())
        .await
        .expect("capture succeeds");
    let command = controller
        .prepare_spatial_command(destination.device_id())
        .expect("command is prepared");
    let capability = controller
        .captured_capability()
        .expect("capability is captured");
    let transfer_id = command.claim.transfer_id;
    let mut control = controller
        .submit_spatial_command(command, capability)
        .await
        .expect("source accepts the command");
    assert_eq!(control.transfer_id(), transfer_id);

    // Wait until the destination has actually seen the offer, then make the
    // controller disappear in the middle of the transfer.
    let started = wait_for_record(&destination, transfer_id)
        .await
        .expect("destination sees the spatial offer");
    assert!(!started.status.is_terminal());
    control.disconnect();

    let at_source = wait_for_any_terminal(&source, transfer_id)
        .await
        .expect("source reaches a terminal state");
    assert_ne!(at_source.status, TransferState::Completed);
    let at_destination = wait_for_any_terminal(&destination, transfer_id)
        .await
        .expect("destination reaches a terminal state");
    assert_ne!(at_destination.status, TransferState::Completed);

    source.shutdown();
    controller.shutdown();
    destination.shutdown();
}

#[tokio::test(flavor = "multi_thread", worker_threads = 6)]
async fn destination_that_does_not_trust_the_controller_rejects_the_transfer() {
    let source_dir = tempfile::tempdir().expect("temp dir");
    let controller_dir = tempfile::tempdir().expect("temp dir");
    let destination_dir = tempfile::tempdir().expect("temp dir");
    let source = start(source_dir.path(), "Source").await;
    let controller = start(controller_dir.path(), "Controller").await;
    let destination = start(destination_dir.path(), "Destination").await;

    // A<->B and A<->C are paired, but C never paired with B.
    trust(&source, &controller);
    trust(&controller, &source);
    trust(&source, &destination);
    trust(&destination, &source);
    // The controller must be able to address C to prepare the command, but C
    // does not trust the controller and must reject the exchange.
    trust(&controller, &destination);
    for node in [&source, &controller, &destination] {
        for peer in [&source, &controller, &destination] {
            register(node, peer).await;
        }
    }

    source.hold_text("do not accept").expect("content is held");
    controller
        .request_capture(source.device_id())
        .await
        .expect("capture succeeds");
    let result = controller.send_spatial(destination.device_id()).await;
    assert!(result.is_err(), "destination must reject the transfer");

    source.shutdown();
    controller.shutdown();
    destination.shutdown();
}

#[tokio::test(flavor = "multi_thread", worker_threads = 6)]
async fn expired_capability_is_rejected_by_the_source() {
    let source_dir = tempfile::tempdir().expect("temp dir");
    let controller_dir = tempfile::tempdir().expect("temp dir");
    let destination_dir = tempfile::tempdir().expect("temp dir");
    let source = start(source_dir.path(), "Source").await;
    let controller = start(controller_dir.path(), "Controller").await;
    let destination = start(destination_dir.path(), "Destination").await;
    wire_triangle(&[&source, &controller, &destination]).await;

    let content = flowww_core::Content::text(source.device_id().clone(), "short lived").unwrap();
    source
        .hold_for(content, Duration::from_millis(300))
        .expect("content is held briefly");
    controller
        .request_capture(source.device_id())
        .await
        .expect("capture succeeds");
    let command = controller
        .prepare_spatial_command(destination.device_id())
        .expect("command is prepared");
    let capability = controller
        .captured_capability()
        .expect("capability is captured");

    tokio::time::sleep(Duration::from_millis(450)).await;
    let result = controller.submit_spatial_command(command, capability).await;
    let error = result.err().expect("expired handle must be rejected");
    assert!(
        error.to_string().to_lowercase().contains("expired"),
        "unexpected error: {error}"
    );

    source.shutdown();
    controller.shutdown();
    destination.shutdown();
}

#[tokio::test(flavor = "multi_thread", worker_threads = 6)]
async fn replayed_spatial_command_is_rejected() {
    let source_dir = tempfile::tempdir().expect("temp dir");
    let controller_dir = tempfile::tempdir().expect("temp dir");
    let destination_dir = tempfile::tempdir().expect("temp dir");
    let source = start(source_dir.path(), "Source").await;
    let controller = start(controller_dir.path(), "Controller").await;
    let destination = start(destination_dir.path(), "Destination").await;
    wire_triangle(&[&source, &controller, &destination]).await;

    source.hold_text("single use").expect("content is held");
    controller
        .request_capture(source.device_id())
        .await
        .expect("capture succeeds");
    let command = controller
        .prepare_spatial_command(destination.device_id())
        .expect("command is prepared");
    let capability = controller
        .captured_capability()
        .expect("capability is captured");
    let replay = command.clone();
    let replay_capability = capability.clone();

    controller
        .submit_spatial_command(command, capability)
        .await
        .expect("first command is accepted")
        .wait()
        .await
        .expect("first transfer completes");

    let error = controller
        .submit_spatial_command(replay, replay_capability)
        .await
        .err()
        .expect("replayed command must be rejected");
    let rendered = error.to_string().to_lowercase();
    assert!(
        rendered.contains("already used") || rendered.contains("handle"),
        "unexpected replay error: {error}"
    );

    source.shutdown();
    controller.shutdown();
    destination.shutdown();
}

#[tokio::test(flavor = "multi_thread", worker_threads = 6)]
async fn revoked_controller_cannot_submit_a_command() {
    let source_dir = tempfile::tempdir().expect("temp dir");
    let controller_dir = tempfile::tempdir().expect("temp dir");
    let destination_dir = tempfile::tempdir().expect("temp dir");
    let source = start(source_dir.path(), "Source").await;
    let controller = start(controller_dir.path(), "Controller").await;
    let destination = start(destination_dir.path(), "Destination").await;
    wire_triangle(&[&source, &controller, &destination]).await;

    source.hold_text("revoked").expect("content is held");
    controller
        .request_capture(source.device_id())
        .await
        .expect("capture succeeds");
    let command = controller
        .prepare_spatial_command(destination.device_id())
        .expect("command is prepared");
    let capability = controller
        .captured_capability()
        .expect("capability is captured");

    source
        .store()
        .revoke_device(controller.device_id())
        .expect("controller is revoked");

    let result = controller.submit_spatial_command(command, capability).await;
    assert!(
        result.is_err(),
        "a revoked controller must not reach the source"
    );

    source.shutdown();
    controller.shutdown();
    destination.shutdown();
}

#[tokio::test(flavor = "multi_thread", worker_threads = 6)]
async fn large_direct_spatial_file_is_hash_verified() {
    let source_dir = tempfile::tempdir().expect("temp dir");
    let controller_dir = tempfile::tempdir().expect("temp dir");
    let destination_dir = tempfile::tempdir().expect("temp dir");
    let source = start(source_dir.path(), "Source").await;
    let controller = start(controller_dir.path(), "Controller").await;
    let destination = start(destination_dir.path(), "Destination").await;
    wire_triangle(&[&source, &controller, &destination]).await;

    let (path, bytes) = write_source(source_dir.path(), "large.bin", 5 * 1024 * 1024 + 12_345);
    let held = source.hold_file(&path).await.expect("file is held");
    assert!(
        !serde_json::to_string(&held)
            .unwrap()
            .contains("source_reference"),
        "held content must not expose a local path"
    );
    controller
        .request_capture(source.device_id())
        .await
        .expect("capture succeeds");

    let record = controller
        .send_spatial(destination.device_id())
        .await
        .expect("large spatial transfer completes");
    assert_eq!(record.status, TransferState::Completed);
    assert_eq!(record.bytes_transferred, bytes.len() as u64);

    let received = wait_for_transfer(&destination, record.id).await;
    assert_eq!(received.status, TransferState::Completed);
    assert_eq!(received.sha256, record.sha256);
    assert_eq!(
        std::fs::read(received_path(destination_dir.path(), &received)).unwrap(),
        bytes
    );

    source.shutdown();
    controller.shutdown();
    destination.shutdown();
}

#[tokio::test(flavor = "multi_thread", worker_threads = 6)]
async fn interrupted_direct_transfer_resumes_and_verifies() {
    let source_dir = tempfile::tempdir().expect("temp dir");
    let controller_dir = tempfile::tempdir().expect("temp dir");
    let destination_dir = tempfile::tempdir().expect("temp dir");
    let source = start(source_dir.path(), "Source").await;
    let controller = start(controller_dir.path(), "Controller").await;
    let destination = start(destination_dir.path(), "Destination").await;
    wire_triangle(&[&source, &controller, &destination]).await;

    // The first connection carries a few MiB and is then cut; the retry must
    // resume C's partial file instead of starting over.
    let proxy = start_proxy(
        endpoint(&destination),
        ProxyConfig {
            first_limit: Some(2 * 1024 * 1024),
            delay: None,
        },
    )
    .await;
    source
        .register_peer_endpoint(destination.device_id(), proxy.addr)
        .await;

    let (path, bytes) = write_source(source_dir.path(), "resumable.bin", 5 * 1024 * 1024 + 99);
    source.hold_file(&path).await.expect("file is held");
    controller
        .request_capture(source.device_id())
        .await
        .expect("capture succeeds");

    let record = controller
        .send_spatial(destination.device_id())
        .await
        .expect("the retry resumes and completes");
    assert_eq!(record.status, TransferState::Completed);
    assert_eq!(record.bytes_transferred, bytes.len() as u64);
    assert!(
        proxy.connections.load(Ordering::SeqCst) >= 2,
        "the source must have retried through the proxy"
    );

    let received = wait_for_transfer(&destination, record.id).await;
    assert_eq!(received.status, TransferState::Completed);
    assert_eq!(
        std::fs::read(received_path(destination_dir.path(), &received)).unwrap(),
        bytes
    );

    source.shutdown();
    controller.shutdown();
    destination.shutdown();
}

#[tokio::test(flavor = "multi_thread", worker_threads = 6)]
async fn status_events_reach_every_device() {
    let source_dir = tempfile::tempdir().expect("temp dir");
    let controller_dir = tempfile::tempdir().expect("temp dir");
    let destination_dir = tempfile::tempdir().expect("temp dir");
    let source = start(source_dir.path(), "Source").await;
    let controller = start(controller_dir.path(), "Controller").await;
    let destination = start(destination_dir.path(), "Destination").await;
    wire_triangle(&[&source, &controller, &destination]).await;

    let mut source_events = source.subscribe();
    let mut controller_events = controller.subscribe();
    let mut destination_events = destination.subscribe();

    let (path, _) = write_source(source_dir.path(), "events.bin", 256 * 1024);
    source.hold_file(&path).await.expect("file is held");
    controller
        .request_capture(source.device_id())
        .await
        .expect("capture succeeds");
    let record = controller
        .send_spatial(destination.device_id())
        .await
        .expect("spatial transfer completes");

    for (name, events) in [
        ("source", &mut source_events),
        ("controller", &mut controller_events),
        ("destination", &mut destination_events),
    ] {
        let mut completed = false;
        for _ in 0..500 {
            match tokio::time::timeout(Duration::from_millis(20), events.recv()).await {
                Ok(Ok(flowww_node::NodeEvent::TransferUpdated(update)))
                    if update.id == record.id && update.status == TransferState::Completed =>
                {
                    completed = true;
                    break;
                }
                Ok(Ok(_)) => {}
                Ok(Err(_)) => break,
                Err(_) => {}
            }
        }
        assert!(completed, "{name} did not observe the completed transfer");
    }

    source.shutdown();
    controller.shutdown();
    destination.shutdown();
}

#[tokio::test(flavor = "multi_thread", worker_threads = 6)]
async fn spatial_route_names_are_stable() {
    // Small guard against accidentally downgrading the wire contract.
    assert_eq!(TransferRoute::Direct.as_str(), "direct");
    assert_eq!(TransferRoute::PhoneRelay.as_str(), "phone_relay");
}
