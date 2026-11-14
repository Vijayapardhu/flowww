use thiserror::Error;

#[derive(Debug, Error)]
pub enum SecurityError {
    #[error("cryptographic operation failed")]
    Crypto,
    #[error("invalid key or signature encoding")]
    Encoding,
    #[error("signature verification failed")]
    BadSignature,
    #[error("the pairing payload is malformed: {0}")]
    BadPairingPayload(String),
    #[error("the pairing session has expired")]
    PairingExpired,
    #[error("the pairing token is invalid or already used")]
    BadPairingToken,
    #[error("the two pairing devices do not match the transcript")]
    PairingMismatch,
    #[error("the transfer authorization has expired")]
    AuthorizationExpired,
    #[error("the transfer authorization is not valid for this content")]
    AuthorizationMismatch,
    #[error("the transfer authorization has already been used")]
    AuthorizationReplay,
    #[error("the spatial content capability has expired")]
    SpatialExpired,
    #[error("the spatial capability or command is not valid for this content")]
    SpatialMismatch,
    #[error("the spatial command has already been used")]
    SpatialReplay,
    #[error("identity file I/O failed: {0}")]
    Io(String),
    #[error("secret storage is not available: {0}")]
    SecretStore(String),
}
