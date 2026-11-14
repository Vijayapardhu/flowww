use std::sync::Arc;
use std::time::Duration;

use bytes::Bytes;
use flowww_core::{Capability, DeviceId, DeviceInfo, Platform, TransferId};
use flowww_network::{
    connect, connect_pairing, DeviceTls, NetworkError, PairingListener, PeerListener, PeerSession,
};
use flowww_protocol::{Frame, Message};
use flowww_security::{
    Fingerprint, Identity, MemoryTrust, PairedPeer, PairingJoin, PairingSession, PairingTransport,
};

fn device(id: &str, name: &str) -> DeviceInfo {
    DeviceInfo::new(
        DeviceId::new(id).unwrap(),
        name,
        Platform::Windows,
        Capability::standard(),
    )
    .unwrap()
}

fn peer(device: &DeviceInfo, identity: &Identity, tls: &DeviceTls) -> PairedPeer {
    PairedPeer {
        device: device.clone(),
        public_key: identity.public_key_base64(),
        tls_fingerprint: tls.fingerprint(),
    }
}

struct Fixture {
    client_device: DeviceInfo,
    server_device: DeviceInfo,
    client_identity: Arc<Identity>,
    server_identity: Arc<Identity>,
    client_tls: DeviceTls,
    server_tls: DeviceTls,
}

fn fixture() -> Fixture {
    Fixture {
        client_device: device("fw_client_001", "Laptop"),
        server_device: device("fw_server_001", "Studio"),
        client_identity: Arc::new(Identity::generate()),
        server_identity: Arc::new(Identity::generate()),
        client_tls: DeviceTls::generate().unwrap(),
        server_tls: DeviceTls::generate().unwrap(),
    }
}

async fn connected_pair() -> (PeerSession, PeerSession) {
    let fixture = fixture();
    let server_trust = Arc::new(MemoryTrust::new());
    server_trust.insert(peer(
        &fixture.client_device,
        &fixture.client_identity,
        &fixture.client_tls,
    ));
    let client_trust = Arc::new(MemoryTrust::new());
    client_trust.insert(peer(
        &fixture.server_device,
        &fixture.server_identity,
        &fixture.server_tls,
    ));

    let listener = PeerListener::bind(
        "127.0.0.1:0".parse().unwrap(),
        Arc::new(fixture.server_tls.clone()),
        server_trust,
    )
    .await
    .unwrap();
    let addr = listener.local_addr().unwrap();

    let server_device = fixture.server_device.clone();
    let server_identity = fixture.server_identity.clone();
    let server_task =
        tokio::spawn(async move { listener.accept(&server_device, &server_identity).await });

    let client = connect(
        addr,
        &peer(
            &fixture.server_device,
            &fixture.server_identity,
            &fixture.server_tls,
        ),
        &fixture.client_device,
        &fixture.client_identity,
        &fixture.client_tls,
    )
    .await
    .unwrap();

    let server = server_task.await.unwrap().unwrap();
    (client, server)
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn handshake_authenticates_both_sides() {
    let (client, server) = connected_pair().await;
    assert_eq!(client.peer().device_name, "Studio");
    assert_eq!(server.peer().device_name, "Laptop");
    assert_eq!(client.peer_id().as_str(), "fw_server_001");
    assert_eq!(server.peer_id().as_str(), "fw_client_001");
    assert_eq!(client.channel_binding(), server.channel_binding());
    assert_eq!(client.remote_addr().ip().to_string(), "127.0.0.1");
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn control_and_chunk_frames_round_trip() {
    let (mut client, mut server) = connected_pair().await;
    let nonce = "ab".repeat(32);
    client
        .send_message(Message::Ping {
            nonce: nonce.clone(),
        })
        .await
        .unwrap();
    match server.next_frame().await.unwrap() {
        Frame::Control(envelope) => {
            assert!(matches!(envelope.message, Message::Ping { nonce: n } if n == nonce));
        }
        Frame::Chunk(_) => panic!("expected a control frame"),
    }
    server
        .send_message(Message::Pong {
            nonce: nonce.clone(),
        })
        .await
        .unwrap();
    assert!(matches!(
        client.recv_message().await.unwrap(),
        Message::Pong { nonce: n } if n == nonce
    ));

    let transfer = TransferId::new();
    client
        .send_chunk(transfer, 3, Bytes::from_static(b"flowww payload"))
        .await
        .unwrap();
    match server.next_frame().await.unwrap() {
        Frame::Chunk(chunk) => {
            assert_eq!(chunk.transfer_id, transfer);
            assert_eq!(chunk.index, 3);
            assert_eq!(&chunk.payload[..], b"flowww payload");
            chunk.verify().unwrap();
        }
        Frame::Control(_) => panic!("expected a chunk frame"),
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn unpaired_client_is_rejected_during_tls() {
    let fixture = fixture();
    let server_trust = Arc::new(MemoryTrust::new());
    let listener = PeerListener::bind(
        "127.0.0.1:0".parse().unwrap(),
        Arc::new(fixture.server_tls.clone()),
        server_trust,
    )
    .await
    .unwrap();
    let addr = listener.local_addr().unwrap();

    let server_device = fixture.server_device.clone();
    let server_identity = fixture.server_identity.clone();
    let server_task =
        tokio::spawn(async move { listener.accept(&server_device, &server_identity).await });
    let client = connect(
        addr,
        &peer(
            &fixture.server_device,
            &fixture.server_identity,
            &fixture.server_tls,
        ),
        &fixture.client_device,
        &fixture.client_identity,
        &fixture.client_tls,
    )
    .await;
    assert!(client.is_err());
    let server = tokio::time::timeout(Duration::from_secs(10), server_task)
        .await
        .unwrap()
        .unwrap();
    assert!(server.is_err());
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn wrong_server_pin_is_rejected() {
    let fixture = fixture();
    let server_trust = Arc::new(MemoryTrust::new());
    server_trust.insert(peer(
        &fixture.client_device,
        &fixture.client_identity,
        &fixture.client_tls,
    ));
    let listener = PeerListener::bind(
        "127.0.0.1:0".parse().unwrap(),
        Arc::new(fixture.server_tls.clone()),
        server_trust,
    )
    .await
    .unwrap();
    let addr = listener.local_addr().unwrap();
    let server_device = fixture.server_device.clone();
    let server_identity = fixture.server_identity.clone();
    let server_task =
        tokio::spawn(async move { listener.accept(&server_device, &server_identity).await });

    let mut wrong = peer(
        &fixture.server_device,
        &fixture.server_identity,
        &fixture.server_tls,
    );
    wrong.tls_fingerprint = Fingerprint([9u8; 32]);
    let client = connect(
        addr,
        &wrong,
        &fixture.client_device,
        &fixture.client_identity,
        &fixture.client_tls,
    )
    .await;
    assert!(client.is_err());
    let server = tokio::time::timeout(Duration::from_secs(10), server_task)
        .await
        .unwrap()
        .unwrap();
    assert!(server.is_err());
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn pairing_channel_exchanges_a_full_transcript() {
    let host_identity = Identity::generate();
    let host_device = device("fw_host_00001", "Studio");
    let host_tls = DeviceTls::generate().unwrap();
    let host_listener =
        PairingListener::bind("127.0.0.1:0".parse().unwrap(), Arc::new(host_tls.clone()))
            .await
            .unwrap();
    let addr = host_listener.local_addr().unwrap();
    let (session, payload) = PairingSession::host(
        host_device.clone(),
        &host_identity,
        host_tls.fingerprint(),
        addr,
        120_000,
    )
    .unwrap();

    let joiner_identity = Identity::generate();
    let joiner_device = device("fw_phone_00001", "Phone");
    let joiner_tls = DeviceTls::generate().unwrap();
    let join = PairingJoin::create(
        &payload,
        &joiner_identity,
        joiner_device.clone(),
        joiner_tls.fingerprint(),
    )
    .unwrap();

    let host_task = tokio::spawn(async move {
        let mut channel = host_listener.accept().await?;
        let join = match channel.recv().await? {
            PairingTransport::Join(join) => join,
            other => {
                return Err(NetworkError::InvalidHandshake(format!(
                    "expected JOIN, got {other:?}"
                )))
            }
        };
        let verified = session.verify_join(&join)?;
        if !verified
            .tls_fingerprint
            .equals(channel.peer_tls_fingerprint())
        {
            return Err(NetworkError::IdentityMismatch);
        }
        let accept = session.accept(&host_identity, &verified)?;
        channel.send(&PairingTransport::Accept(accept)).await?;
        let confirm = match channel.recv().await? {
            PairingTransport::Confirm(confirm) => confirm,
            other => {
                return Err(NetworkError::InvalidHandshake(format!(
                    "expected CONFIRM, got {other:?}"
                )))
            }
        };
        session.verify_confirm(&verified, &confirm)?;
        channel.send(&PairingTransport::Complete).await?;
        Ok::<(), NetworkError>(())
    });

    let mut channel = connect_pairing(addr, payload.host_tls_fingerprint().unwrap(), &joiner_tls)
        .await
        .unwrap();
    channel
        .send(&PairingTransport::Join(join.clone()))
        .await
        .unwrap();
    let accept = match channel.recv().await.unwrap() {
        PairingTransport::Accept(accept) => accept,
        other => panic!("expected ACCEPT, got {other:?}"),
    };
    join.verify_accept(&payload, &accept).unwrap();
    assert_eq!(accept.host_device, host_device);
    channel
        .send(&PairingTransport::Confirm(
            join.confirm(&joiner_identity, &payload).unwrap(),
        ))
        .await
        .unwrap();
    assert!(matches!(
        channel.recv().await.unwrap(),
        PairingTransport::Complete
    ));
    host_task.await.unwrap().unwrap();
}
