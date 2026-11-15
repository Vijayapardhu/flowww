//! Two-node end-to-end tests through the FFI boundary.
//!
//! Every test drives the public FFI surface only — the same calls Android
//! will make — so regressions in the boundary show up here, not on device.
//! Loopback TCP is used; multicast discovery is not required for pairing or
//! transfers (pairing registers the host endpoint automatically).

use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use flowww_ffi::{
    DeviceDto, FlowwwError, FlowwwEvent, FlowwwEventListener, FlowwwNode, PendingOfferDto,
};

const TIMEOUT: Duration = Duration::from_secs(30);

// ---------------------------------------------------------------------------
// Recording listener
// ---------------------------------------------------------------------------

#[derive(Default)]
struct Recorder {
    events: Mutex<Vec<FlowwwEvent>>,
}

impl Recorder {
    fn take_matching(&self, pred: impl Fn(&FlowwwEvent) -> bool) -> Vec<FlowwwEvent> {
        let mut guard = self.events.lock().unwrap_or_else(|p| p.into_inner());
        let (hits, rest): (Vec<_>, Vec<_>) = guard.drain(..).partition(pred);
        *guard = rest;
        hits
    }
}

impl FlowwwEventListener for Recorder {
    fn on_event(&self, event: FlowwwEvent) {
        self.events
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .push(event);
    }
}

// ---------------------------------------------------------------------------
// Test fixtures
// ---------------------------------------------------------------------------

struct TestNode {
    node: Arc<FlowwwNode>,
    recorder: Arc<Recorder>,
    data_dir: PathBuf,
    download_dir: PathBuf,
    device: DeviceDto,
    _temp: tempfile::TempDir,
}

fn start_node(label: &str) -> TestNode {
    let temp = tempfile::tempdir().expect("temp dir");
    let data_dir = temp.path().join("data");
    let download_dir = temp.path().join("downloads");
    let node = Arc::new(FlowwwNode::new());
    let recorder = Arc::new(Recorder::default());
    let proxy: Arc<dyn FlowwwEventListener> = recorder.clone();
    node.add_listener(proxy);
    let device = node
        .start(
            data_dir.to_string_lossy().into_owned(),
            format!("flowww-test-{label}"),
            download_dir.to_string_lossy().into_owned(),
        )
        .expect("node starts");
    assert!(!device.device_id.is_empty());
    TestNode {
        node,
        recorder,
        data_dir,
        download_dir,
        device,
        _temp: temp,
    }
}

fn stop_node(test: &TestNode) {
    test.node.stop().expect("stop is idempotent and succeeds");
    assert!(!test.node.is_running());
}

fn pair_nodes(a: &TestNode, b: &TestNode) {
    let view = a.node.start_pairing().expect("pairing session starts");
    assert!(view.uri.starts_with("flowww://pair/v1/"));
    assert!(view.expires_at > flowww_core::unix_millis());
    let joined = b.node.pair_with_uri(view.uri.clone()).expect("join succeeds");
    assert_eq!(joined.device_id, a.device.device_id);
    let a_trusted = a.node.list_trusted().expect("host trust list");
    assert!(
        a_trusted
            .iter()
            .any(|t| t.device_id == b.device.device_id && t.trusted),
        "host did not pin the joiner"
    );
    let b_trusted = b.node.list_trusted().expect("joiner trust list");
    assert!(
        b_trusted
            .iter()
            .any(|t| t.device_id == a.device.device_id && t.trusted),
        "joiner did not pin the host"
    );
    // The QR endpoint is the short-lived pairing listener, so loopback tests
    // register real transfer endpoints explicitly (the supported fallback for
    // networks without multicast discovery).
    a.node
        .register_peer_endpoint(
            b.device.device_id.clone(),
            "127.0.0.1".into(),
            b.node.listen_port().expect("guest listen port"),
        )
        .expect("host registers guest endpoint");
    b.node
        .register_peer_endpoint(
            a.device.device_id.clone(),
            "127.0.0.1".into(),
            a.node.listen_port().expect("host listen port"),
        )
        .expect("guest registers host endpoint");
}

fn wait_for_offer(node: &FlowwwNode, timeout: Duration) -> PendingOfferDto {
    let deadline = Instant::now() + timeout;
    loop {
        if let Some(offer) = node
            .pending_offers()
            .expect("offers are readable")
            .into_iter()
            .next()
        {
            return offer;
        }
        if Instant::now() > deadline {
            panic!("no offer arrived within {timeout:?}");
        }
        std::thread::sleep(Duration::from_millis(20));
    }
}

fn wait_for_terminal(
    node: &FlowwwNode,
    transfer_id: &str,
    timeout: Duration,
) -> flowww_ffi::TransferRecordDto {
    let deadline = Instant::now() + timeout;
    loop {
        if let Some(record) = node
            .transfer_history()
            .expect("history is readable")
            .into_iter()
            .find(|record| record.id == transfer_id)
        {
            if record.is_terminal {
                return record;
            }
        }
        if Instant::now() > deadline {
            panic!("transfer {transfer_id} did not reach a terminal state");
        }
        std::thread::sleep(Duration::from_millis(20));
    }
}

fn wait_for_active(
    node: &FlowwwNode,
    transfer_id: &str,
    timeout: Duration,
) -> flowww_ffi::TransferRecordDto {
    let deadline = Instant::now() + timeout;
    loop {
        if let Some(record) = node
            .transfer_history()
            .expect("history is readable")
            .into_iter()
            .find(|record| record.id == transfer_id && record.is_active)
        {
            return record;
        }
        if Instant::now() > deadline {
            panic!("transfer {transfer_id} did not become active");
        }
        std::thread::sleep(Duration::from_millis(20));
    }
}

fn sha256_hex(path: &Path) -> String {
    let bytes = std::fs::read(path).expect("payload readable");
    flowww_transfer::hash_bytes(&bytes).to_hex()
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[test]
fn lifecycle_emits_started_and_stopped_events() {
    let test = start_node("lifecycle");
    assert!(test.node.is_running());
    assert_eq!(test.device.device_name, "flowww-test-lifecycle");
    let started = test
        .recorder
        .take_matching(|e| matches!(e, FlowwwEvent::NodeStarted));
    assert_eq!(started.len(), 1, "NodeStarted must be emitted exactly once");

    // Calling stop twice must be a harmless no-op.
    test.node.stop().expect("first stop");
    test.node.stop().expect("second stop is a no-op");
    assert!(!test.node.is_running());
    let stopped = test
        .recorder
        .take_matching(|e| matches!(e, FlowwwEvent::NodeStopped));
    assert_eq!(stopped.len(), 1);
}

#[test]
fn local_identity_is_metadata_only() {
    let test = start_node("identity");
    let device = test.node.local_device().expect("identity is available");
    assert_eq!(device.device_id, test.device.device_id);
    assert!(!device.device_name.is_empty());
    assert!(!device.platform.is_empty());
    assert!(!device.protocol_version.is_empty());
    stop_node(&test);
}

#[test]
fn pairing_uri_shape_and_cancel() {
    let test = start_node("pairing");
    let view = test.node.start_pairing().expect("pairing starts");
    assert!(view.uri.starts_with("flowww://pair/v1/"));
    assert!(view.endpoint.contains(':'));
    assert!(view.expires_at > flowww_core::unix_millis());

    // A second concurrent session must report busy, not panic.
    let busy = test.node.start_pairing();
    assert!(matches!(busy, Err(FlowwwError::PairingBusy)));

    // Discovered peers are not trusted until pairing succeeds.
    assert!(test.node.list_trusted().expect("trust list").is_empty());

    let active = test.node.active_pairing().expect("active pairing visible");
    assert_eq!(active.uri, view.uri);

    assert!(test.node.cancel_pairing());
    assert!(test.node.active_pairing().is_none());
    stop_node(&test);
}

#[test]
fn pair_with_uri_rejects_garbage() {
    let test = start_node("bad-uri");
    assert!(matches!(
        test.node.pair_with_uri("hello world".into()),
        Err(FlowwwError::InvalidInput { .. })
    ));
    assert!(matches!(
        test.node.pair_with_uri("flowww://pair/v2/abc".into()),
        Err(FlowwwError::InvalidInput { .. })
    ));
    stop_node(&test);
}

#[test]
fn two_nodes_pair_and_transfer_file_both_ways() {
    let a = start_node("host");
    let b = start_node("guest");
    pair_nodes(&a, &b);

    // ---------- joiner -> host (the QR-scan-and-send path) ----------
    let payload: Vec<u8> = (0..(2 * 1024 * 1024 + 123_457u64))
        .map(|index| (index % 251) as u8)
        .collect();
    let source = b.data_dir.join("outbound.bin");
    std::fs::write(&source, &payload).expect("payload written");
    let expected_sha = sha256_hex(&source);

    let sender = Arc::clone(&b.node);
    let a_device_id = a.device.device_id.clone();
    let source_for_thread = source.to_string_lossy().into_owned();
    let handle = std::thread::spawn(move || sender.send_file(a_device_id, source_for_thread));

    let offer = wait_for_offer(&a.node, TIMEOUT);
    assert_eq!(offer.content_name, "outbound.bin");
    assert_eq!(offer.source_device_id, b.device.device_id);
    assert_eq!(offer.size, payload.len() as u64);

    assert!(a.node.respond_to_offer(offer.transfer_id.clone(), true).expect("offer is answerable"));

    let record = handle
        .join()
        .expect("send thread does not panic")
        .expect("transfer completes after approval");
    assert_eq!(record.status, "COMPLETED");
    assert_eq!(record.filename.as_deref(), Some("outbound.bin"));
    assert!(!record
        .filename
        .as_deref()
        .unwrap_or_default()
        .contains(['/', '\\']));
    assert_eq!(record.sha256.as_deref(), Some(expected_sha.as_str()));

    let received = std::fs::read(a.download_dir.join("outbound.bin"))
        .expect("file landed in the host download dir");
    assert_eq!(received, payload, "received bytes must match the original");

    let host_record = wait_for_terminal(&a.node, &offer.transfer_id, TIMEOUT);
    assert_eq!(host_record.status, "COMPLETED");
    assert_eq!(host_record.direction, "incoming");

    // ---------- host -> joiner (the reverse path, same paired session) ----------
    let return_payload = "hello again from the host side — this must survive the trip intact"
        .as_bytes()
        .to_vec();
    let reply_source = a.data_dir.join("reply.txt");
    std::fs::write(&reply_source, &return_payload).expect("reply written");
    let reply_sha = sha256_hex(&reply_source);

    let sender = Arc::clone(&a.node);
    let b_device_id = b.device.device_id.clone();
    let reply_for_thread = reply_source.to_string_lossy().into_owned();
    let handle = std::thread::spawn(move || sender.send_file(b_device_id, reply_for_thread));

    let offer = wait_for_offer(&b.node, TIMEOUT);
    assert_eq!(offer.content_name, "reply.txt");
    assert!(b.node.respond_to_offer(offer.transfer_id.clone(), true).expect("offer is answerable"));

    let record = handle
        .join()
        .expect("send thread does not panic")
        .expect("host-to-guest transfer completes");
    assert_eq!(record.status, "COMPLETED");
    assert_eq!(record.sha256.as_deref(), Some(reply_sha.as_str()));
    let received = std::fs::read(b.download_dir.join("reply.txt")).expect("reply landed");
    assert_eq!(received, return_payload);

    stop_node(&a);
    stop_node(&b);
}

#[test]
fn declining_an_offer_sends_no_bytes() {
    let a = start_node("decline-host");
    let b = start_node("decline-guest");
    pair_nodes(&a, &b);

    let source = b.data_dir.join("unwanted.bin");
    std::fs::write(&source, b"rejected bytes").expect("payload written");

    let sender = Arc::clone(&b.node);
    let a_device_id = a.device.device_id.clone();
    let source_for_thread = source.to_string_lossy().into_owned();
    let handle = std::thread::spawn(move || sender.send_file(a_device_id, source_for_thread));

    let offer = wait_for_offer(&a.node, TIMEOUT);
    assert!(a.node.respond_to_offer(offer.transfer_id.clone(), false).expect("offer is answerable"));

    let result = handle.join().expect("send thread does not panic");
    assert!(matches!(result, Err(FlowwwError::OfferRejected { .. })));
    assert_eq!(
        std::fs::read_dir(&a.download_dir)
            .map(|it| it.count())
            .unwrap_or(0),
        0,
        "a declined offer must not write any file"
    );
    stop_node(&a);
    stop_node(&b);
}

#[test]
fn cancel_while_waiting_for_approval_marks_transfer_cancelled() {
    let a = start_node("cancel-host");
    let b = start_node("cancel-guest");
    pair_nodes(&a, &b);

    let source = b.data_dir.join("cancel-me.bin");
    std::fs::write(&source, vec![7u8; 1024 * 1024 + 1]).expect("payload written");

    let sender = Arc::clone(&b.node);
    let a_device_id = a.device.device_id.clone();
    let source_for_thread = source.to_string_lossy().into_owned();
    let handle = std::thread::spawn(move || sender.send_file(a_device_id, source_for_thread));

    // Wait until the offer is visible in the host's pending offers.
    let offer = wait_for_offer(&a.node, TIMEOUT);

    // The joiner's own history must show the outgoing transfer as active.
    let active = wait_for_active(&b.node, &offer.transfer_id, TIMEOUT);

    assert!(b.node.cancel_transfer(active.id.clone()).expect("cancel call succeeds"));
    let result = handle.join().expect("send thread does not panic");
    assert!(matches!(result, Err(FlowwwError::Transfer { .. })));

    let cancelled = wait_for_terminal(&b.node, &offer.transfer_id, TIMEOUT);
    assert_eq!(cancelled.status, "CANCELLED");

    // Cancelling a made-up id must not affect anything.
    assert!(!b.node.cancel_transfer(uuid::Uuid::new_v4().to_string()).expect("unknown id is a clean false"));

    stop_node(&a);
    stop_node(&b);
}

#[test]
fn send_file_rejects_paths_outside_data_dir() {
    let a = start_node("sandbox-host");
    let b = start_node("sandbox-guest");
    pair_nodes(&a, &b);

    // Relative path: rejected.
    assert!(matches!(
        b.node.send_file(a.device.device_id.clone(), "payload.bin".into()),
        Err(FlowwwError::InvalidInput { .. })
    ));
    // Absolute path outside the sandbox: rejected.
    let outside = std::env::temp_dir().join("flowww-ffi-outside-sandbox.bin");
    std::fs::write(&outside, b"outside").expect("outside file written");
    assert!(matches!(
        b.node.send_file(
            a.device.device_id.clone(),
            outside.to_string_lossy().into_owned()
        ),
        Err(FlowwwError::InvalidInput { .. })
    ));
    // A traversal attempt like `<data>/../elsewhere` normalises outside the
    // sandbox root and must be rejected.
    let traversal = format!("{}/../elsewhere.bin", b.data_dir.to_string_lossy());
    assert!(matches!(
        b.node.send_file(a.device.device_id.clone(), traversal),
        Err(FlowwwError::InvalidInput { .. })
    ));
    let _ = std::fs::remove_file(&outside);
    stop_node(&a);
    stop_node(&b);
}

#[test]
fn pairing_requires_the_full_uri() {
    let a = start_node("uri-host");
    let b = start_node("uri-guest");
    let view = a.node.start_pairing().expect("pairing starts");

    // A truncated URI must fail, and the host must stay unpaired.
    let truncated = &view.uri[..view.uri.len() - 8];
    assert!(matches!(
        b.node.pair_with_uri(truncated.into()),
        Err(FlowwwError::PairingFailed { .. }) | Err(FlowwwError::Crypto { .. })
    ));
    assert!(a.node.list_trusted().expect("trust list").is_empty());
    stop_node(&a);
    stop_node(&b);
}
