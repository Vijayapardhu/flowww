use thiserror::Error;

use flowww_core::CoreError;
use flowww_discovery::DiscoveryError;
use flowww_network::NetworkError;
use flowww_protocol::ProtocolError;
use flowww_security::SecurityError;
use flowww_spatial::SpatialError;
use flowww_storage::StorageError;
use flowww_transfer::TransferError;

#[derive(Debug, Error)]
pub enum NodeError {
    #[error("configuration error: {0}")]
    Config(String),
    #[error("storage error: {0}")]
    Storage(#[from] StorageError),
    #[error("security error: {0}")]
    Security(#[from] SecurityError),
    #[error("network error: {0}")]
    Network(#[from] NetworkError),
    #[error("transfer error: {0}")]
    Transfer(#[from] TransferError),
    #[error("discovery error: {0}")]
    Discovery(#[from] DiscoveryError),
    #[error("spatial error: {0}")]
    Spatial(#[from] SpatialError),
    #[error("core error: {0}")]
    Core(#[from] CoreError),
    #[error("protocol error: {0}")]
    Protocol(#[from] ProtocolError),
    #[error("I/O error: {0}")]
    Io(#[from] std::io::Error),
    #[error("unknown or unpaired device: {0}")]
    UnknownPeer(String),
    #[error("the peer is not currently discoverable: {0}")]
    PeerUnavailable(String),
    #[error("the transfer offer was rejected: {0}")]
    OfferRejected(String),
    #[error("no spatial target at that position")]
    NoSpatialTarget,
    #[error("no content is being held")]
    NothingHeld,
    #[error("a pairing session is already active")]
    PairingBusy,
    #[error("there is no active pairing session")]
    NoPairing,
    #[error("pairing failed: {0}")]
    PairingFailed(String),
    #[error("the peer sent an unexpected message: {0}")]
    UnexpectedMessage(&'static str),
    #[error("the peer reported an error: {0}")]
    PeerError(String),
    #[error("the operation timed out")]
    Timeout,
    #[error("the peer closed the connection")]
    Closed,
    #[error("the device has no content to offer: {0}")]
    ContentUnavailable(String),
    #[error("the transfer record is missing: {0}")]
    MissingRecord(String),
}

pub type NodeResult<T> = Result<T, NodeError>;
