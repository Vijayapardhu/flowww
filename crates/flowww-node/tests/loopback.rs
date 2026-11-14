//! End-to-end tests for two nodes talking over real pinned TLS on localhost.
//!
//! Discovery is disabled; tests use the low-level `*_to` entry points with the
//! peer's loopback address so the test suite stays hermetic and fast.

use std::net::SocketAddr;
use std::path::Path;
use std::time::Duration;

use flowww_core::{TransferId, TransferState};
use flowww_node::{Node, NodeConfig};
use flowww_security::PairedPeer;

fn config(dir: &Path, name: &str, auto_accept: bool) -> NodeConfig {
    NodeConfig::new(dir)
        .with_download_dir(dir.join("downloads"))
        .with_device_name(name)
        .with_discovery(false)
        .with_listen_addr("127.0.0.1:0".parse().expect("loopback address"))
        .with_auto_accept(auto_accept)
}

async fn start(dir: &Path, name: &str, auto_accept: bool) -> Node {
    Node::start(config(dir, name, auto_accept))
        .await
        .expect("node starts")
}

/// Persist mutual trust and return the record the sender needs.
fn trust_each_other(sender: &Node, receiver: &Node) -> PairedPeer {
    receiver
        .store()
        .save_pair(
            sender.device(),
            &sender.public_key(),
            &sender.tls_fingerprint(),
            None,
        )
        .expect("receiver trusts sender");
    sender
        .store()
        .save_pair(
            receiver.device(),
            &receiver.public_key(),
            &receiver.tls_fingerprint(),
            None,
        )
        .expect("sender trusts receiver")
}

fn endpoint(node: &Node) -> SocketAddr {
    SocketAddr::from(([127, 0, 0, 1], node.listen_port()))
}

async fn wait_for_transfer(node: &Node, id: TransferId) -> flowww_storage::TransferRecord {
    for _ in 0..300 {
        if let Some(record) = node
            .store()
            .list_transfers(40)
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

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn inline_text_transfer_completes_on_both_sides() {
    let sender_dir = tempfile::tempdir().expect("temp dir");
    let receiver_dir = tempfile::tempdir().expect("temp dir");
    let sender = start(sender_dir.path(), "Sender", true).await;
    let receiver = start(receiver_dir.path(), "Receiver", true).await;
    let peer = trust_each_other(&sender, &receiver);

    let sent = sender
        .send_text_to(endpoint(&receiver), &peer, "hello over flowww")
        .await
        .expect("text is sent");
    assert_eq!(sent.status, TransferState::Completed);

    let received = wait_for_transfer(&receiver, sent.id).await;
    assert_eq!(received.status, TransferState::Completed);
    assert_eq!(
        received.inline_content.as_deref(),
        Some("hello over flowww")
    );
    assert_eq!(received.source_device.as_str(), sender.device_id().as_str());

    sender.shutdown();
    receiver.shutdown();
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn multi_chunk_file_transfer_matches_bytes() {
    let sender_dir = tempfile::tempdir().expect("temp dir");
    let receiver_dir = tempfile::tempdir().expect("temp dir");
    let sender = start(sender_dir.path(), "Sender", true).await;
    let receiver = start(receiver_dir.path(), "Receiver", true).await;
    let peer = trust_each_other(&sender, &receiver);

    // Larger than the 1 MiB chunk size, so the transfer spans several chunks.
    let size = 5 * 1024 * 1024 + 12_345;
    let mut payload = vec![0u8; size];
    for (index, byte) in payload.iter_mut().enumerate() {
        *byte = (index % 251) as u8;
    }
    let source = sender_dir.path().join("payload.bin");
    std::fs::write(&source, &payload).expect("test file is written");

    let sent = sender
        .send_file_to(endpoint(&receiver), &peer, &source)
        .await
        .expect("file is sent");
    assert_eq!(sent.status, TransferState::Completed);
    assert_eq!(sent.bytes_transferred, size as u64);

    let received = wait_for_transfer(&receiver, sent.id).await;
    assert_eq!(received.status, TransferState::Completed);
    let filename = received.filename.expect("received file has a name");
    let stored = std::fs::read(receiver_dir.path().join("downloads").join(filename))
        .expect("received file exists");
    assert_eq!(stored, payload);

    sender.shutdown();
    receiver.shutdown();
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn manual_approval_accepts_and_declines() {
    let sender_dir = tempfile::tempdir().expect("temp dir");
    let receiver_dir = tempfile::tempdir().expect("temp dir");
    let sender = start(sender_dir.path(), "Sender", true).await;
    let receiver = start(receiver_dir.path(), "Receiver", false).await;
    let peer = trust_each_other(&sender, &receiver);

    let send_task = {
        let sender = sender.clone();
        let receiver = receiver.clone();
        let peer = peer.clone();
        tokio::spawn(async move {
            sender
                .send_text_to(endpoint(&receiver), &peer, "needs approval")
                .await
        })
    };

    let mut offer = None;
    for _ in 0..300 {
        if let Some(view) = receiver.pending_offers().into_iter().next() {
            offer = Some(view);
            break;
        }
        tokio::time::sleep(Duration::from_millis(10)).await;
    }
    let offer = offer.expect("receiver surfaced a pending offer");
    assert_eq!(offer.content.inline.as_deref(), Some("needs approval"));
    assert!(receiver.respond_to_offer(offer.transfer_id, true));

    let sent = send_task
        .await
        .expect("send task joins")
        .expect("approved transfer completes");
    let received = wait_for_transfer(&receiver, sent.id).await;
    assert_eq!(received.status, TransferState::Completed);
    assert_eq!(received.inline_content.as_deref(), Some("needs approval"));

    // A declined offer must fail on the sender and leave nothing accepted.
    let decline_task = {
        let sender = sender.clone();
        let receiver = receiver.clone();
        let peer = peer.clone();
        tokio::spawn(async move {
            sender
                .send_text_to(endpoint(&receiver), &peer, "please decline")
                .await
        })
    };
    let mut declined = None;
    for _ in 0..300 {
        if let Some(view) = receiver.pending_offers().into_iter().next() {
            declined = Some(view);
            break;
        }
        tokio::time::sleep(Duration::from_millis(10)).await;
    }
    let declined = declined.expect("second offer is pending");
    assert!(receiver.respond_to_offer(declined.transfer_id, false));
    assert!(decline_task.await.expect("decline task joins").is_err());

    sender.shutdown();
    receiver.shutdown();
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn qr_pairing_establishes_mutual_trust() {
    let host_dir = tempfile::tempdir().expect("temp dir");
    let joiner_dir = tempfile::tempdir().expect("temp dir");
    let host = start(host_dir.path(), "Host", false).await;
    let joiner = start(joiner_dir.path(), "Joiner", true).await;

    let pairing = host.start_pairing().await.expect("pairing host starts");
    assert!(host.active_pairing().is_some());

    let paired = joiner
        .pair_with_uri(&pairing.uri)
        .await
        .expect("joiner completes pairing");
    assert_eq!(paired.device_id, *host.device_id());

    // Both sides persist the other as trusted.
    let host_devices = host.store().list_devices().expect("host devices");
    assert!(host_devices
        .iter()
        .any(|device| device.info.device_id == *joiner.device_id() && device.trusted));
    let joiner_devices = joiner.store().list_devices().expect("joiner devices");
    assert!(joiner_devices
        .iter()
        .any(|device| device.info.device_id == *host.device_id() && device.trusted));

    // The paired trust is immediately usable for a transfer.
    let host_peer = host
        .paired_peer(joiner.device_id())
        .expect("host can address the joiner");
    let sent = host
        .send_text_to(endpoint(&joiner), &host_peer, "paired hello")
        .await
        .expect("transfer works after pairing");
    let received = wait_for_transfer(&joiner, sent.id).await;
    assert_eq!(received.inline_content.as_deref(), Some("paired hello"));

    assert!(host.active_pairing().is_none());
    host.shutdown();
    joiner.shutdown();
}
