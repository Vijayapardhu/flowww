//! Trusted peer listener.
//!
//! Incoming TCP connections must complete a pinned mutual-TLS handshake with a
//! certificate already present in the local trust directory. Only then does the
//! Ed25519 application handshake run.

use std::net::SocketAddr;
use std::sync::Arc;
use tokio::net::{TcpListener, TcpStream};
use tokio::time::timeout;
use tokio_rustls::TlsAcceptor;
use tracing::debug;

use crate::session::{
    accept_handshake, export_binding, BoxedStream, PeerSession, HANDSHAKE_TIMEOUT,
};
use crate::tls::DeviceTls;
use crate::NetworkError;
use flowww_core::DeviceInfo;
use flowww_security::{Fingerprint, Identity, SharedTrust};

/// A bound TCP listener that only accepts paired peers.
pub struct PeerListener {
    listener: TcpListener,
    tls: Arc<DeviceTls>,
    trust: SharedTrust,
}

impl PeerListener {
    pub async fn bind(
        addr: SocketAddr,
        tls: Arc<DeviceTls>,
        trust: SharedTrust,
    ) -> Result<Self, NetworkError> {
        let listener = TcpListener::bind(addr).await?;
        Ok(Self {
            listener,
            tls,
            trust,
        })
    }

    pub fn local_addr(&self) -> Result<SocketAddr, NetworkError> {
        Ok(self.listener.local_addr()?)
    }

    pub fn tls_fingerprint(&self) -> Fingerprint {
        self.tls.fingerprint()
    }

    /// Accept one TCP connection and complete both handshakes.
    ///
    /// Any unpaired client is rejected during the TLS handshake, before it can
    /// send a single application byte.
    pub async fn accept(
        &self,
        own_device: &DeviceInfo,
        identity: &Identity,
    ) -> Result<PeerSession, NetworkError> {
        let (tcp, remote) = self.listener.accept().await?;
        tcp.set_nodelay(true)?;
        self.complete(tcp, remote, own_device, identity).await
    }

    async fn complete(
        &self,
        tcp: TcpStream,
        remote: SocketAddr,
        own_device: &DeviceInfo,
        identity: &Identity,
    ) -> Result<PeerSession, NetworkError> {
        let config = self.tls.server_config(self.trust.clone())?;
        let acceptor = TlsAcceptor::from(Arc::new(config));
        let stream = timeout(HANDSHAKE_TIMEOUT, acceptor.accept(tcp))
            .await
            .map_err(|_| NetworkError::Timeout)??;

        let (client_fingerprint, binding) = {
            let (_, connection) = stream.get_ref();
            let client_fingerprint = connection
                .peer_certificates()
                .and_then(|certs| certs.first())
                .map(|cert| Fingerprint::of(cert.as_ref()))
                .ok_or(NetworkError::UntrustedPeer)?;
            let binding = export_binding(connection)?;
            (client_fingerprint, binding)
        };

        let expected = self
            .trust
            .by_tls_fingerprint(&client_fingerprint)
            .ok_or(NetworkError::UntrustedPeer)?;
        debug!(
            "incoming peer {} ({}) via {}",
            expected.device.device_name,
            client_fingerprint.short(),
            remote
        );

        let server_fingerprint = self.tls.fingerprint();
        let io: BoxedStream = Box::new(stream);
        timeout(
            HANDSHAKE_TIMEOUT,
            accept_handshake(
                io,
                own_device,
                identity,
                expected,
                client_fingerprint,
                server_fingerprint,
                binding,
                remote,
            ),
        )
        .await
        .map_err(|_| NetworkError::Timeout)?
    }
}
