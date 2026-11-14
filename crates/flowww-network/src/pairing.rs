//! Short-lived pairing transport.
//!
//! The pairing channel also uses TLS, but the client certificate is not known
//! in advance. Trust comes from the one-time QR token and from the signed
//! pairing transcript defined in `flowww-security`, not from the TLS layer.
//! Pairing traffic is length-prefixed JSON with a hard size ceiling.

use std::net::SocketAddr;
use std::sync::Arc;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::time::timeout;
use tokio_rustls::{TlsAcceptor, TlsConnector};
use tracing::debug;

use crate::session::{export_binding, BoxedStream, HANDSHAKE_TIMEOUT};
use crate::tls::DeviceTls;
use crate::NetworkError;
use flowww_security::{Fingerprint, PairingTransport};

/// Pairing messages are small. This ceiling is deliberately much lower than
/// the control-frame ceiling; no file data travels on this channel.
pub const MAX_PAIRING_FRAME: usize = 64 * 1024;

/// A pairing connection, from the joiner's or host's perspective.
pub struct PairingChannel {
    io: BoxedStream,
    remote_addr: SocketAddr,
    peer_tls_fingerprint: Fingerprint,
    binding: [u8; 32],
}

impl std::fmt::Debug for PairingChannel {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("PairingChannel")
            .field("remote_addr", &self.remote_addr)
            .field("peer_tls", &self.peer_tls_fingerprint.short())
            .finish()
    }
}

impl PairingChannel {
    pub fn remote_addr(&self) -> SocketAddr {
        self.remote_addr
    }

    pub fn peer_tls_fingerprint(&self) -> Fingerprint {
        self.peer_tls_fingerprint
    }

    pub fn channel_binding(&self) -> &[u8; 32] {
        &self.binding
    }

    pub async fn send(&mut self, message: &PairingTransport) -> Result<(), NetworkError> {
        let bytes = serde_json::to_vec(message)
            .map_err(|_| NetworkError::InvalidHandshake("pairing encoding failed".into()))?;
        if bytes.is_empty() || bytes.len() > MAX_PAIRING_FRAME {
            return Err(NetworkError::InvalidHandshake(
                "pairing frame is too large".into(),
            ));
        }
        self.io
            .write_all(&(bytes.len() as u32).to_be_bytes())
            .await?;
        self.io.write_all(&bytes).await?;
        self.io.flush().await?;
        Ok(())
    }

    pub async fn recv(&mut self) -> Result<PairingTransport, NetworkError> {
        let mut length_bytes = [0u8; 4];
        match self.io.read_exact(&mut length_bytes).await {
            Ok(_) => {}
            Err(error) if error.kind() == std::io::ErrorKind::UnexpectedEof => {
                return Err(NetworkError::Closed)
            }
            Err(error) => return Err(NetworkError::Io(error)),
        }
        let length = u32::from_be_bytes(length_bytes) as usize;
        if length == 0 || length > MAX_PAIRING_FRAME {
            return Err(NetworkError::InvalidHandshake(
                "invalid pairing frame length".into(),
            ));
        }
        let mut payload = vec![0u8; length];
        self.io.read_exact(&mut payload).await?;
        let message = serde_json::from_slice(&payload)
            .map_err(|_| NetworkError::InvalidHandshake("invalid pairing frame".into()))?;
        Ok(message)
    }
}

/// Host side of the pairing transport.
pub struct PairingListener {
    listener: TcpListener,
    tls: Arc<DeviceTls>,
}

impl PairingListener {
    pub async fn bind(addr: SocketAddr, tls: Arc<DeviceTls>) -> Result<Self, NetworkError> {
        let listener = TcpListener::bind(addr).await?;
        Ok(Self { listener, tls })
    }

    pub fn local_addr(&self) -> Result<SocketAddr, NetworkError> {
        Ok(self.listener.local_addr()?)
    }

    /// Wait for one joiner and establish the pinned TLS channel.
    ///
    /// Pairing instructions should run this under an overall deadline; the
    /// per-connection TLS handshake timeout bounds a slow client.
    pub async fn accept(&self) -> Result<PairingChannel, NetworkError> {
        let (tcp, remote) = self.listener.accept().await?;
        tcp.set_nodelay(true)?;
        let config = self.tls.pairing_server_config()?;
        let acceptor = TlsAcceptor::from(Arc::new(config));
        let stream = timeout(HANDSHAKE_TIMEOUT, acceptor.accept(tcp))
            .await
            .map_err(|_| NetworkError::Timeout)??;
        let fingerprint = {
            let (_, connection) = stream.get_ref();
            connection
                .peer_certificates()
                .and_then(|certs| certs.first())
                .map(|cert| Fingerprint::of(cert.as_ref()))
                .ok_or(NetworkError::UntrustedPeer)?
        };
        let binding = {
            let (_, connection) = stream.get_ref();
            export_binding(connection)?
        };
        debug!("pairing channel from {} ({})", remote, fingerprint.short());
        Ok(PairingChannel {
            io: Box::new(stream),
            remote_addr: remote,
            peer_tls_fingerprint: fingerprint,
            binding,
        })
    }
}

/// Joiner side of the pairing transport.
///
/// `host_pin` comes from the QR payload, so the joiner can reject an impostor
/// before the one-time token is revealed.
pub async fn connect_pairing(
    addr: SocketAddr,
    host_pin: Fingerprint,
    tls: &DeviceTls,
) -> Result<PairingChannel, NetworkError> {
    let config = tls.client_config(host_pin)?;
    let connector = TlsConnector::from(Arc::new(config));
    let tcp = timeout(HANDSHAKE_TIMEOUT, TcpStream::connect(addr))
        .await
        .map_err(|_| NetworkError::Timeout)??;
    tcp.set_nodelay(true)?;
    let server_name = rustls::pki_types::ServerName::try_from("flowww.local")
        .map_err(|_| NetworkError::TlsConfig("invalid server name".into()))?;
    let stream = timeout(HANDSHAKE_TIMEOUT, connector.connect(server_name, tcp))
        .await
        .map_err(|_| NetworkError::Timeout)??;
    let (observed, binding) = {
        let (_, connection) = stream.get_ref();
        let observed = connection
            .peer_certificates()
            .and_then(|certs| certs.first())
            .map(|cert| Fingerprint::of(cert.as_ref()))
            .ok_or(NetworkError::UntrustedPeer)?;
        let binding = export_binding(connection)?;
        (observed, binding)
    };
    if !observed.equals(host_pin) {
        return Err(NetworkError::IdentityMismatch);
    }
    debug!("pairing channel to {} ({})", addr, observed.short());
    Ok(PairingChannel {
        io: Box::new(stream),
        remote_addr: addr,
        peer_tls_fingerprint: observed,
        binding,
    })
}
