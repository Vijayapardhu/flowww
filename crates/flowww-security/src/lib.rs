//! Identity, pairing and transfer authorization primitives.
//!
//! flowww uses a persistent Ed25519 identity per installation. The private key
//! never leaves the device and is never advertised. TLS certificates pin the
//! transport, while signed application messages authenticate the device identity.

pub mod authorization;
pub mod error;
pub mod identity;
pub mod pairing;
pub mod spatial;
pub mod trust;

pub use authorization::{
    verify_authorization, AuthorizationExpectation, ReplayGuard, TransferAuthorization,
    TransferClaim,
};
pub use error::SecurityError;
#[cfg(windows)]
pub use identity::DpapiSecretStore;
#[cfg(windows)]
pub use identity::{dpapi_protect, dpapi_unprotect};
pub use identity::{
    FileSecretStore, Fingerprint, Identity, IdentityDocument, PublicIdentity, SecretStore,
    SignatureB64,
};
pub use pairing::{
    PairingAccept, PairingConfirm, PairingJoin, PairingPayload, PairingSession, PairingTransport,
    VerifiedJoin,
};
pub use spatial::{
    verify_spatial_proof, ContentCapability, ContentCapabilityClaim, SpatialCommand,
    SpatialCommandClaim, SpatialExpectation,
};
pub use trust::{MemoryTrust, PairedPeer, SharedTrust, TrustDirectory};

pub type SecurityResult<T> = Result<T, SecurityError>;
