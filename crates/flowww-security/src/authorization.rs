use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::Mutex;

use crate::{Identity, PublicIdentity, SecurityError, SecurityResult};
use flowww_core::{unix_millis, ContentId, DeviceId, TransferAction, TransferId};

const AUTHORIZATION_DOMAIN: &[u8] = b"flowww.authorization.v1\n";

/// The exact claims a source device authorizes.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct TransferClaim {
    pub source_device: DeviceId,
    pub destination_device: DeviceId,
    pub content_id: ContentId,
    pub transfer_id: TransferId,
    pub action: TransferAction,
    /// Absolute Unix time in milliseconds.
    pub expires_at: u64,
    /// Upper bound on the content size the grant covers.
    pub max_size: u64,
    /// Random single-use nonce.
    pub nonce: String,
}

impl TransferClaim {
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        source_device: DeviceId,
        destination_device: DeviceId,
        content_id: ContentId,
        transfer_id: TransferId,
        action: TransferAction,
        max_size: u64,
        ttl_ms: u64,
    ) -> Self {
        let mut nonce_bytes = [0u8; 24];
        use rand::RngCore;
        rand::rngs::OsRng.fill_bytes(&mut nonce_bytes);
        Self {
            source_device,
            destination_device,
            content_id,
            transfer_id,
            action,
            expires_at: unix_millis() + ttl_ms,
            max_size,
            nonce: hex::encode(nonce_bytes),
        }
    }

    pub fn canonical_bytes(&self) -> SecurityResult<Vec<u8>> {
        let mut bytes = AUTHORIZATION_DOMAIN.to_vec();
        bytes.extend(serde_json::to_vec(self).map_err(|_| SecurityError::Encoding)?);
        Ok(bytes)
    }

    pub fn is_expired(&self) -> bool {
        flowww_core::is_expired(self.expires_at)
    }
}

/// A source-signed grant shown to the destination before any bytes move.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct TransferAuthorization {
    pub claim: TransferClaim,
    pub signature: crate::SignatureB64,
}

impl TransferAuthorization {
    pub fn sign(identity: &Identity, claim: TransferClaim) -> SecurityResult<Self> {
        let signature = identity.sign(&claim.canonical_bytes()?);
        Ok(Self { claim, signature })
    }

    pub fn verify(&self, public: &PublicIdentity) -> SecurityResult<()> {
        if self.claim.is_expired() {
            return Err(SecurityError::AuthorizationExpired);
        }
        if self.claim.nonce.len() < 16 || self.claim.nonce.len() > 128 {
            return Err(SecurityError::AuthorizationMismatch);
        }
        public.verify(&self.claim.canonical_bytes()?, &self.signature)
    }
}

/// What the destination expects the grant to say.
#[derive(Debug, Clone)]
pub struct AuthorizationExpectation<'a> {
    pub source_device: &'a DeviceId,
    pub destination_device: &'a DeviceId,
    pub content_id: &'a ContentId,
    pub transfer_id: TransferId,
    pub action: TransferAction,
    pub content_size: u64,
    pub max_size: u64,
}

/// Verify a grant and every field it binds. A valid signature over the wrong
/// content is still a rejected transfer.
pub fn verify_authorization(
    authorization: &TransferAuthorization,
    source_public: &PublicIdentity,
    expected: &AuthorizationExpectation<'_>,
) -> SecurityResult<()> {
    authorization.verify(source_public)?;
    let claim = &authorization.claim;
    if claim.source_device != *expected.source_device
        || claim.destination_device != *expected.destination_device
        || claim.content_id != *expected.content_id
        || claim.transfer_id != expected.transfer_id
        || claim.action != expected.action
    {
        return Err(SecurityError::AuthorizationMismatch);
    }
    if expected.content_size > claim.max_size || expected.content_size > expected.max_size {
        return Err(SecurityError::AuthorizationMismatch);
    }
    Ok(())
}

/// In-memory single-use authorization guard.
///
/// A production daemon persists nonces for the lifetime of their expiry as well;
/// the in-memory guard already prevents replay within a running session.
#[derive(Debug, Default)]
pub struct ReplayGuard {
    seen: Mutex<HashMap<String, u64>>,
}

impl ReplayGuard {
    pub fn new() -> Self {
        Self::default()
    }

    /// Record a nonce. Returns `false` if it was already seen and is unexpired.
    pub fn accept(&self, nonce: &str, expires_at: u64) -> bool {
        let now = unix_millis();
        let mut seen = self
            .seen
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        seen.retain(|_, expiry| *expiry > now);
        if seen.contains_key(nonce) {
            return false;
        }
        seen.insert(nonce.to_string(), expires_at);
        true
    }

    pub fn len(&self) -> usize {
        self.seen
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .len()
    }

    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn fixture() -> (Identity, DeviceId, DeviceId, ContentId, TransferId) {
        (
            Identity::generate(),
            DeviceId::new("fw_source_01").unwrap(),
            DeviceId::new("fw_destination_01").unwrap(),
            ContentId::new(),
            TransferId::new(),
        )
    }

    fn expectation<'a>(
        source: &'a DeviceId,
        destination: &'a DeviceId,
        content: &'a ContentId,
        transfer: TransferId,
    ) -> AuthorizationExpectation<'a> {
        AuthorizationExpectation {
            source_device: source,
            destination_device: destination,
            content_id: content,
            transfer_id: transfer,
            action: TransferAction::Save,
            content_size: 2048,
            max_size: 4096,
        }
    }

    #[test]
    fn valid_grant_is_accepted() {
        let (identity, source, destination, content, transfer) = fixture();
        let claim = TransferClaim::new(
            source.clone(),
            destination.clone(),
            content.clone(),
            transfer,
            TransferAction::Save,
            4096,
            60_000,
        );
        let grant = TransferAuthorization::sign(&identity, claim).unwrap();
        verify_authorization(
            &grant,
            &identity.public_identity(),
            &expectation(&source, &destination, &content, transfer),
        )
        .unwrap();
    }

    #[test]
    fn grant_for_other_content_is_rejected() {
        let (identity, source, destination, content, transfer) = fixture();
        let claim = TransferClaim::new(
            source.clone(),
            destination.clone(),
            content.clone(),
            transfer,
            TransferAction::Save,
            4096,
            60_000,
        );
        let grant = TransferAuthorization::sign(&identity, claim).unwrap();
        let other_content = ContentId::new();
        assert!(verify_authorization(
            &grant,
            &identity.public_identity(),
            &expectation(&source, &destination, &other_content, transfer),
        )
        .is_err());
    }

    #[test]
    fn tampered_grant_is_rejected() {
        let (identity, source, destination, content, transfer) = fixture();
        let claim = TransferClaim::new(
            source,
            destination.clone(),
            content.clone(),
            transfer,
            TransferAction::Save,
            4096,
            60_000,
        );
        let mut grant = TransferAuthorization::sign(&identity, claim).unwrap();
        grant.claim.max_size = 999_999_999;
        assert!(grant.verify(&identity.public_identity()).is_err());
    }

    #[test]
    fn expired_grant_is_rejected() {
        let (identity, source, destination, content, transfer) = fixture();
        let mut claim = TransferClaim::new(
            source,
            destination,
            content,
            transfer,
            TransferAction::Save,
            4096,
            60_000,
        );
        claim.expires_at = unix_millis().saturating_sub(1);
        let grant = TransferAuthorization::sign(&identity, claim).unwrap();
        assert_eq!(
            grant
                .verify(&identity.public_identity())
                .unwrap_err()
                .to_string(),
            "the transfer authorization has expired"
        );
    }

    #[test]
    fn replay_guard_accepts_once() {
        let guard = ReplayGuard::new();
        let expiry = unix_millis() + 10_000;
        assert!(guard.accept("abc123abc123", expiry));
        assert!(!guard.accept("abc123abc123", expiry));
    }
}
