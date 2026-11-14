use thiserror::Error;

#[derive(Debug, Error)]
pub enum NetworkError {
    #[error("network I/O error: {0}")]
    Io(#[from] std::io::Error),
    #[error("TLS error: {0}")]
    Tls(#[from] rustls::Error),
    #[error("TLS configuration error: {0}")]
    TlsConfig(String),
    #[error("protocol error: {0}")]
    Protocol(#[from] flowww_protocol::ProtocolError),
    #[error("security error: {0}")]
    Security(#[from] flowww_security::SecurityError),
    #[error("the peer is not trusted")]
    UntrustedPeer,
    #[error("the peer identity does not match the pairing record")]
    IdentityMismatch,
    #[error("the connection timed out")]
    Timeout,
    #[error("the peer closed the connection")]
    Closed,
    #[error("received a chunk while a control message was expected")]
    UnexpectedChunk,
    #[error("received an invalid handshake: {0}")]
    InvalidHandshake(String),
}
