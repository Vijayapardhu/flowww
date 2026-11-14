use bytes::Bytes;
use rand::rngs::OsRng;
use rand::RngCore;
use sha2::{Digest, Sha256};
use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;
use std::time::Instant;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::net::TcpStream;
use tokio::time::timeout;
use tokio_rustls::TlsConnector;
use tracing::debug;

use crate::tls::DeviceTls;
use crate::NetworkError;
use flowww_core::{DeviceId, DeviceInfo, TransferId, PROTOCOL_VERSION};
use flowww_protocol::envelope::version_is_compatible;
use flowww_protocol::{Envelope, Frame, FrameHeader, Message, FRAME_HEADER_LEN};
use flowww_security::{Fingerprint, Identity, PairedPeer, PublicIdentity};

pub(crate) const CHANNEL_BINDING_LABEL: &[u8] = b"EXPORT-flowww-channel-v1";
pub(crate) const CHANNEL_BINDING_CONTEXT: &[u8] = b"flowww/tls";
const AUTH_DOMAIN: &[u8] = b"flowww.authenticated-hello.v1";
/// Both sides must finish the identity handshake promptly.
pub(crate) const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(15);

pub trait IoStream: AsyncRead + AsyncWrite + Unpin + Send {}
impl<T: AsyncRead + AsyncWrite + Unpin + Send> IoStream for T {}

pub type BoxedStream = Box<dyn IoStream>;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum Role {
    Initiator,
    Responder,
}

/// One authenticated, encrypted peer connection.
pub struct PeerSession {
    io: BoxedStream,
    peer: DeviceInfo,
    peer_public: PublicIdentity,
    peer_tls_fingerprint: Fingerprint,
    remote_addr: SocketAddr,
    channel_binding: [u8; 32],
}

impl std::fmt::Debug for PeerSession {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("PeerSession")
            .field("peer", &self.peer.device_id)
            .field("remote_addr", &self.remote_addr)
            .field("channel_binding", &hex::encode(&self.channel_binding[..4]))
            .finish()
    }
}

impl PeerSession {
    pub fn peer(&self) -> &DeviceInfo {
        &self.peer
    }

    pub fn peer_id(&self) -> &DeviceId {
        &self.peer.device_id
    }

    pub fn peer_public(&self) -> &PublicIdentity {
        &self.peer_public
    }

    pub fn peer_tls_fingerprint(&self) -> Fingerprint {
        self.peer_tls_fingerprint
    }

    pub fn remote_addr(&self) -> SocketAddr {
        self.remote_addr
    }

    pub fn channel_binding(&self) -> &[u8; 32] {
        &self.channel_binding
    }

    pub async fn send_message(&mut self, message: Message) -> Result<(), NetworkError> {
        send_control(&mut self.io, message).await
    }

    pub async fn send_chunk(
        &mut self,
        transfer_id: TransferId,
        index: u64,
        payload: Bytes,
    ) -> Result<(), NetworkError> {
        let chunk = flowww_protocol::Chunk::new(transfer_id, index, payload)?;
        write_frame(&mut self.io, &Frame::Chunk(chunk)).await
    }

    pub async fn next_frame(&mut self) -> Result<Frame, NetworkError> {
        read_frame(&mut self.io).await
    }

    /// Receive the next control message, transparently answering PINGs.
    pub async fn recv_message(&mut self) -> Result<Message, NetworkError> {
        loop {
            match self.next_frame().await? {
                Frame::Control(envelope) => match envelope.message {
                    Message::Ping { nonce } => {
                        self.send_message(Message::Pong { nonce }).await?;
                    }
                    message => return Ok(message),
                },
                Frame::Chunk(_) => return Err(NetworkError::UnexpectedChunk),
            }
        }
    }
}

/// Connect to a paired peer and run the authenticated handshake.
///
/// The TLS layer pins the peer certificate; the handshake that follows proves
/// possession of the Ed25519 identity bound to that certificate and to the
/// exported TLS channel keying material.
pub async fn connect(
    addr: SocketAddr,
    expected: &PairedPeer,
    own_device: &DeviceInfo,
    identity: &Identity,
    tls: &DeviceTls,
) -> Result<PeerSession, NetworkError> {
    let started = Instant::now();
    let config = tls.client_config(expected.tls_fingerprint)?;
    let connector = TlsConnector::from(Arc::new(config));
    let tcp = timeout(HANDSHAKE_TIMEOUT, TcpStream::connect(addr))
        .await
        .map_err(|_| NetworkError::Timeout)??;
    tcp.set_nodelay(true)?;

    let server_name = rustls::pki_types::ServerName::try_from("flowww.local")
        .map_err(|_| NetworkError::TlsConfig("invalid server name".into()))?;
    let tls_stream = timeout(HANDSHAKE_TIMEOUT, connector.connect(server_name, tcp))
        .await
        .map_err(|_| NetworkError::Timeout)??;

    let (binding, observed) = {
        let (_, connection) = tls_stream.get_ref();
        let observed = connection
            .peer_certificates()
            .and_then(|certs| certs.first())
            .map(|cert| Fingerprint::of(cert.as_ref()))
            .ok_or(NetworkError::UntrustedPeer)?;
        let binding = export_binding(connection)?;
        (binding, observed)
    };
    if !observed.equals(expected.tls_fingerprint) {
        return Err(NetworkError::IdentityMismatch);
    }

    debug!(
        target: "flowww.network",
        event = "connection_established",
        address = %addr,
        peer_fingerprint = %observed.short(),
        elapsed_ms = started.elapsed().as_millis() as u64,
        "pinned TLS connection established"
    );
    let own_tls = tls.fingerprint();
    let mut io: BoxedStream = Box::new(tls_stream);
    let (peer, peer_public) = timeout(
        HANDSHAKE_TIMEOUT,
        handshake(
            &mut io,
            Role::Initiator,
            own_device,
            identity,
            expected,
            own_tls,
            observed,
            &binding,
        ),
    )
    .await
    .map_err(|_| NetworkError::Timeout)??;

    Ok(PeerSession {
        io,
        peer,
        peer_public,
        peer_tls_fingerprint: observed,
        remote_addr: addr,
        channel_binding: binding,
    })
}

/// Run the responder side of the handshake over an already-established TLS stream.
#[allow(clippy::too_many_arguments)]
pub(crate) async fn accept_handshake(
    mut io: BoxedStream,
    own_device: &DeviceInfo,
    identity: &Identity,
    expected: PairedPeer,
    client_tls: Fingerprint,
    server_tls: Fingerprint,
    channel_binding: [u8; 32],
    remote_addr: SocketAddr,
) -> Result<PeerSession, NetworkError> {
    let (peer, peer_public) = handshake(
        &mut io,
        Role::Responder,
        own_device,
        identity,
        &expected,
        client_tls,
        server_tls,
        &channel_binding,
    )
    .await?;
    Ok(PeerSession {
        io,
        peer,
        peer_public,
        peer_tls_fingerprint: client_tls,
        remote_addr,
        channel_binding,
    })
}

pub(crate) fn export_binding(
    connection: &(impl ExportableConnection + ?Sized),
) -> Result<[u8; 32], NetworkError> {
    connection
        .export_keying_material(
            [0u8; 32],
            CHANNEL_BINDING_LABEL,
            Some(CHANNEL_BINDING_CONTEXT),
        )
        .map_err(|_| NetworkError::TlsConfig("TLS exporter failed".into()))
}

/// The rustls connection method used for channel binding.
pub(crate) trait ExportableConnection {
    fn export_keying_material(
        &self,
        output: [u8; 32],
        label: &[u8],
        context: Option<&[u8]>,
    ) -> Result<[u8; 32], rustls::Error>;
}

impl ExportableConnection for rustls::ClientConnection {
    fn export_keying_material(
        &self,
        output: [u8; 32],
        label: &[u8],
        context: Option<&[u8]>,
    ) -> Result<[u8; 32], rustls::Error> {
        rustls::ConnectionCommon::export_keying_material(self, output, label, context)
    }
}

impl ExportableConnection for rustls::ServerConnection {
    fn export_keying_material(
        &self,
        output: [u8; 32],
        label: &[u8],
        context: Option<&[u8]>,
    ) -> Result<[u8; 32], rustls::Error> {
        rustls::ConnectionCommon::export_keying_material(self, output, label, context)
    }
}

#[allow(clippy::too_many_arguments)]
async fn handshake(
    io: &mut BoxedStream,
    role: Role,
    own_device: &DeviceInfo,
    identity: &Identity,
    expected: &PairedPeer,
    client_tls: Fingerprint,
    server_tls: Fingerprint,
    binding: &[u8; 32],
) -> Result<(DeviceInfo, PublicIdentity), NetworkError> {
    if own_device.device_id == expected.device.device_id {
        return Err(NetworkError::InvalidHandshake(
            "a device cannot connect to itself".into(),
        ));
    }
    let own_nonce = random_nonce();

    let peer_hello = match role {
        Role::Initiator => {
            send_control(
                io,
                Message::Hello {
                    device: own_device.clone(),
                    nonce: own_nonce.clone(),
                },
            )
            .await?;
            let peer = expect_hello(io, expected).await?;
            send_auth(
                io,
                Role::Initiator,
                own_device,
                &peer.device,
                identity,
                &own_nonce,
                &peer.nonce,
                client_tls,
                server_tls,
                binding,
            )
            .await?;
            verify_auth(
                io,
                Role::Responder,
                own_device,
                &peer.device,
                expected,
                &own_nonce,
                &peer.nonce,
                client_tls,
                server_tls,
                binding,
            )
            .await?;
            peer
        }
        Role::Responder => {
            let peer = expect_hello(io, expected).await?;
            send_control(
                io,
                Message::Hello {
                    device: own_device.clone(),
                    nonce: own_nonce.clone(),
                },
            )
            .await?;
            verify_auth(
                io,
                Role::Initiator,
                own_device,
                &peer.device,
                expected,
                &peer.nonce,
                &own_nonce,
                client_tls,
                server_tls,
                binding,
            )
            .await?;
            send_auth(
                io,
                Role::Responder,
                own_device,
                &peer.device,
                identity,
                &peer.nonce,
                &own_nonce,
                client_tls,
                server_tls,
                binding,
            )
            .await?;
            peer
        }
    };

    exchange_capabilities(io, role, own_device, &peer_hello.device).await?;

    let peer_public = expected.public_identity()?;
    Ok((peer_hello.device, peer_public))
}

async fn expect_hello(
    io: &mut BoxedStream,
    expected: &PairedPeer,
) -> Result<HelloData, NetworkError> {
    match read_control(io).await? {
        Message::Hello { device, nonce } => {
            if device.device_id != expected.device.device_id {
                return Err(NetworkError::IdentityMismatch);
            }
            if !version_is_compatible(&device.protocol_version) {
                return Err(NetworkError::InvalidHandshake(
                    "incompatible protocol".into(),
                ));
            }
            if nonce.len() != 64 || hex::decode(&nonce).map(|b| b.len() != 32).unwrap_or(true) {
                return Err(NetworkError::InvalidHandshake("bad nonce".into()));
            }
            Ok(HelloData { device, nonce })
        }
        other => Err(NetworkError::InvalidHandshake(format!(
            "expected HELLO, got {}",
            other.kind()
        ))),
    }
}

struct HelloData {
    device: DeviceInfo,
    nonce: String,
}

#[allow(clippy::too_many_arguments)]
async fn send_auth(
    io: &mut BoxedStream,
    signer_role: Role,
    own_device: &DeviceInfo,
    peer_device: &DeviceInfo,
    identity: &Identity,
    initiator_nonce: &str,
    responder_nonce: &str,
    client_tls: Fingerprint,
    server_tls: Fingerprint,
    binding: &[u8; 32],
) -> Result<(), NetworkError> {
    let transcript = auth_transcript(
        signer_role,
        own_device,
        peer_device,
        initiator_nonce,
        responder_nonce,
        client_tls,
        server_tls,
        binding,
    )?;
    send_control(
        io,
        Message::Auth {
            device_id: own_device.device_id.clone(),
            signature: identity.sign(&transcript),
        },
    )
    .await
}

#[allow(clippy::too_many_arguments)]
async fn verify_auth(
    io: &mut BoxedStream,
    signer_role: Role,
    own_device: &DeviceInfo,
    peer_device: &DeviceInfo,
    expected: &PairedPeer,
    initiator_nonce: &str,
    responder_nonce: &str,
    client_tls: Fingerprint,
    server_tls: Fingerprint,
    binding: &[u8; 32],
) -> Result<(), NetworkError> {
    match read_control(io).await? {
        Message::Auth {
            device_id,
            signature,
        } => {
            if device_id != peer_device.device_id || device_id != expected.device.device_id {
                return Err(NetworkError::IdentityMismatch);
            }
            let transcript = auth_transcript(
                signer_role,
                peer_device,
                own_device,
                initiator_nonce,
                responder_nonce,
                client_tls,
                server_tls,
                binding,
            )?;
            expected
                .public_identity()?
                .verify(&transcript, &signature)
                .map_err(|_| NetworkError::IdentityMismatch)
        }
        other => Err(NetworkError::InvalidHandshake(format!(
            "expected AUTH, got {}",
            other.kind()
        ))),
    }
}

async fn exchange_capabilities(
    io: &mut BoxedStream,
    role: Role,
    own_device: &DeviceInfo,
    peer_device: &DeviceInfo,
) -> Result<(), NetworkError> {
    match role {
        Role::Initiator => {
            send_control(
                io,
                Message::Capabilities {
                    capabilities: own_device.capabilities.clone(),
                },
            )
            .await?;
            expect_capabilities(io, peer_device).await
        }
        Role::Responder => {
            expect_capabilities(io, peer_device).await?;
            send_control(
                io,
                Message::Capabilities {
                    capabilities: own_device.capabilities.clone(),
                },
            )
            .await
        }
    }
}

async fn expect_capabilities(
    io: &mut BoxedStream,
    peer_device: &DeviceInfo,
) -> Result<(), NetworkError> {
    match read_control(io).await? {
        Message::Capabilities { capabilities } => {
            if capabilities != peer_device.capabilities {
                return Err(NetworkError::InvalidHandshake(
                    "capabilities do not match HELLO".into(),
                ));
            }
            Ok(())
        }
        other => Err(NetworkError::InvalidHandshake(format!(
            "expected CAPABILITIES, got {}",
            other.kind()
        ))),
    }
}

/// Canonical transcript both sides sign.
///
/// The transcript binds the identity keys to the TLS certificates and to the
/// exporter value, so a relay cannot transplant a valid signature onto another
/// connection.
#[allow(clippy::too_many_arguments)]
fn auth_transcript(
    signer_role: Role,
    signer_device: &DeviceInfo,
    verifier_device: &DeviceInfo,
    initiator_nonce: &str,
    responder_nonce: &str,
    client_tls: Fingerprint,
    server_tls: Fingerprint,
    binding: &[u8; 32],
) -> Result<Vec<u8>, NetworkError> {
    let (initiator, responder) = match signer_role {
        Role::Initiator => (signer_device, verifier_device),
        Role::Responder => (verifier_device, signer_device),
    };
    let initiator_device = serde_json::to_vec(initiator)
        .map_err(|_| NetworkError::InvalidHandshake("device encoding failed".into()))?;
    let responder_device = serde_json::to_vec(responder)
        .map_err(|_| NetworkError::InvalidHandshake("device encoding failed".into()))?;
    let mut hasher = Sha256::new();
    hasher.update(AUTH_DOMAIN);
    for part in [
        PROTOCOL_VERSION.as_bytes(),
        initiator_device.as_slice(),
        responder_device.as_slice(),
        initiator_nonce.as_bytes(),
        responder_nonce.as_bytes(),
        client_tls.to_hex().as_bytes(),
        server_tls.to_hex().as_bytes(),
        binding,
    ] {
        hasher.update((part.len() as u32).to_be_bytes());
        hasher.update(part);
    }
    Ok(hasher.finalize().to_vec())
}

pub(crate) async fn send_control(
    io: &mut BoxedStream,
    message: Message,
) -> Result<(), NetworkError> {
    write_frame(io, &Frame::Control(Box::new(Envelope::new(message)))).await
}

async fn read_control(io: &mut BoxedStream) -> Result<Message, NetworkError> {
    match read_frame(io).await? {
        Frame::Control(envelope) => Ok(envelope.message),
        Frame::Chunk(_) => Err(NetworkError::UnexpectedChunk),
    }
}

pub(crate) async fn write_frame(io: &mut BoxedStream, frame: &Frame) -> Result<(), NetworkError> {
    let bytes = frame.encode()?;
    io.write_all(&bytes).await?;
    io.flush().await?;
    Ok(())
}

pub(crate) async fn read_frame(io: &mut BoxedStream) -> Result<Frame, NetworkError> {
    let mut header_bytes = [0u8; FRAME_HEADER_LEN];
    match io.read_exact(&mut header_bytes).await {
        Ok(_) => {}
        Err(error) if error.kind() == std::io::ErrorKind::UnexpectedEof => {
            return Err(NetworkError::Closed)
        }
        Err(error) => return Err(NetworkError::Io(error)),
    }
    let header = FrameHeader::decode(&header_bytes)?;
    let mut payload = vec![0u8; header.payload_len as usize];
    io.read_exact(&mut payload).await?;
    Ok(Frame::from_parts(header, payload)?)
}

fn random_nonce() -> String {
    let mut bytes = [0u8; 32];
    OsRng.fill_bytes(&mut bytes);
    hex::encode(bytes)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn transcript_is_symmetric_for_both_roles() {
        let initiator = DeviceInfo {
            device_id: DeviceId::new("fw_initiator_1").unwrap(),
            device_name: "Laptop".into(),
            platform: flowww_core::Platform::Windows,
            protocol_version: PROTOCOL_VERSION.into(),
            capabilities: flowww_core::Capability::standard(),
        };
        let responder = DeviceInfo {
            device_id: DeviceId::new("fw_responder_1").unwrap(),
            device_name: "Phone".into(),
            platform: flowww_core::Platform::Android,
            protocol_version: PROTOCOL_VERSION.into(),
            capabilities: flowww_core::Capability::standard(),
        };
        let nonce_a = "aa".repeat(32);
        let nonce_b = "bb".repeat(32);
        let client = Fingerprint([1u8; 32]);
        let server = Fingerprint([2u8; 32]);
        let binding = [3u8; 32];
        let from_initiator = auth_transcript(
            Role::Initiator,
            &initiator,
            &responder,
            &nonce_a,
            &nonce_b,
            client,
            server,
            &binding,
        )
        .unwrap();
        let from_responder = auth_transcript(
            Role::Responder,
            &responder,
            &initiator,
            &nonce_a,
            &nonce_b,
            client,
            server,
            &binding,
        )
        .unwrap();
        assert_eq!(from_initiator, from_responder);
    }

    #[test]
    fn transcript_changes_with_channel_binding() {
        let device = DeviceInfo {
            device_id: DeviceId::new("fw_device_01").unwrap(),
            device_name: "Laptop".into(),
            platform: flowww_core::Platform::Windows,
            protocol_version: PROTOCOL_VERSION.into(),
            capabilities: flowww_core::Capability::standard(),
        };
        let nonce = "aa".repeat(32);
        let first = auth_transcript(
            Role::Initiator,
            &device,
            &device,
            &nonce,
            &nonce,
            Fingerprint([1u8; 32]),
            Fingerprint([2u8; 32]),
            &[3u8; 32],
        )
        .unwrap();
        let second = auth_transcript(
            Role::Initiator,
            &device,
            &device,
            &nonce,
            &nonce,
            Fingerprint([1u8; 32]),
            Fingerprint([2u8; 32]),
            &[4u8; 32],
        )
        .unwrap();
        assert_ne!(first, second);
    }
}
