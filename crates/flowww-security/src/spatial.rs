//! Signed spatial content capabilities and transfer commands.
//!
//! The source device owns a content handle and issues a short-lived capability
//! to one controller. The controller later signs a command that names a
//! destination for that handle. The destination verifies both signatures and
//! the source's transfer authorization before accepting bytes.
//!
//! Every structure is self-contained and uses a domain-separated canonical
//! encoding. No component contains a filesystem path.

use rand::rngs::OsRng;
use rand::RngCore;
use serde::{Deserialize, Serialize};

use crate::{Identity, PublicIdentity, SecurityError, SecurityResult, SignatureB64};
use flowww_core::{
    is_expired, unix_millis, Content, ContentHandleId, ContentId, DeviceId, TransferId,
};

const CAPABILITY_DOMAIN: &[u8] = b"flowww.content-capability.v1\n";
const COMMAND_DOMAIN: &[u8] = b"flowww.spatial-command.v1\n";
const NONCE_BYTES: usize = 16;

/// The source-signed exposure of one content handle to one controller.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ContentCapabilityClaim {
    pub handle_id: ContentHandleId,
    pub source_device: DeviceId,
    pub controller_device: DeviceId,
    pub content: Content,
    pub issued_at: u64,
    pub expires_at: u64,
    /// Random single-use binding value.
    pub nonce: String,
}

impl ContentCapabilityClaim {
    pub fn canonical_bytes(&self) -> SecurityResult<Vec<u8>> {
        let mut bytes = CAPABILITY_DOMAIN.to_vec();
        bytes.extend(serde_json::to_vec(self).map_err(|_| SecurityError::Encoding)?);
        Ok(bytes)
    }

    pub fn is_expired(&self) -> bool {
        is_expired(self.expires_at)
    }
}

/// A short-lived, signed capability held by a spatial controller.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ContentCapability {
    pub claim: ContentCapabilityClaim,
    pub signature: SignatureB64,
}

impl ContentCapability {
    /// Issue a capability. `content` must already be wire-safe.
    pub fn issue(
        identity: &Identity,
        handle_id: ContentHandleId,
        controller_device: DeviceId,
        content: Content,
        ttl_ms: u64,
    ) -> SecurityResult<Self> {
        if !content.is_wire_safe() {
            return Err(SecurityError::SpatialMismatch);
        }
        content
            .validate()
            .map_err(|_| SecurityError::SpatialMismatch)?;
        if content.source_device == controller_device {
            return Err(SecurityError::SpatialMismatch);
        }
        if ttl_ms == 0 {
            return Err(SecurityError::SpatialMismatch);
        }
        let now = unix_millis();
        let claim = ContentCapabilityClaim {
            handle_id,
            source_device: content.source_device.clone(),
            controller_device,
            content,
            issued_at: now,
            expires_at: now.saturating_add(ttl_ms),
            nonce: random_nonce(),
        };
        let signature = identity.sign(&claim.canonical_bytes()?);
        Ok(Self { claim, signature })
    }

    /// Verify structure, expiry and the source signature.
    pub fn verify(&self, source_public: &PublicIdentity) -> SecurityResult<()> {
        let claim = &self.claim;
        if claim.is_expired() {
            return Err(SecurityError::SpatialExpired);
        }
        if claim.nonce.len() != NONCE_BYTES * 2 || hex::decode(&claim.nonce).is_err() {
            return Err(SecurityError::SpatialMismatch);
        }
        if !claim.content.is_wire_safe() {
            return Err(SecurityError::SpatialMismatch);
        }
        claim
            .content
            .validate()
            .map_err(|_| SecurityError::SpatialMismatch)?;
        if claim.content.source_device != claim.source_device
            || claim.source_device == claim.controller_device
        {
            return Err(SecurityError::SpatialMismatch);
        }
        source_public.verify(&claim.canonical_bytes()?, &self.signature)
    }

    pub fn handle_id(&self) -> &ContentHandleId {
        &self.claim.handle_id
    }

    pub fn content(&self) -> &Content {
        &self.claim.content
    }

    pub fn expires_at(&self) -> u64 {
        self.claim.expires_at
    }
}

/// The controller-signed request to move one content handle to a destination.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct SpatialCommandClaim {
    pub source_device: DeviceId,
    pub controller_device: DeviceId,
    pub destination_device: DeviceId,
    pub handle_id: ContentHandleId,
    pub content_id: ContentId,
    pub transfer_id: TransferId,
    pub expires_at: u64,
    /// Random single-use nonce.
    pub nonce: String,
    /// Optional controller listener endpoint, used when discovery is unavailable.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub controller_endpoint: Option<String>,
}

impl SpatialCommandClaim {
    pub fn canonical_bytes(&self) -> SecurityResult<Vec<u8>> {
        let mut bytes = COMMAND_DOMAIN.to_vec();
        bytes.extend(serde_json::to_vec(self).map_err(|_| SecurityError::Encoding)?);
        Ok(bytes)
    }

    pub fn is_expired(&self) -> bool {
        is_expired(self.expires_at)
    }
}

/// A single-use spatial transfer command signed by the controller.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct SpatialCommand {
    pub claim: SpatialCommandClaim,
    pub signature: SignatureB64,
}

impl SpatialCommand {
    /// Issue a command that lives for `ttl_ms` from now.
    #[allow(clippy::too_many_arguments)]
    pub fn issue(
        identity: &Identity,
        controller_device: DeviceId,
        source_device: DeviceId,
        destination_device: DeviceId,
        handle_id: ContentHandleId,
        content_id: ContentId,
        transfer_id: TransferId,
        ttl_ms: u64,
        controller_endpoint: Option<String>,
    ) -> SecurityResult<Self> {
        if ttl_ms == 0 {
            return Err(SecurityError::SpatialMismatch);
        }
        Self::issue_with_expiry(
            identity,
            controller_device,
            source_device,
            destination_device,
            handle_id,
            content_id,
            transfer_id,
            unix_millis().saturating_add(ttl_ms),
            controller_endpoint,
        )
    }

    /// Issue a command with an absolute expiry.
    ///
    /// A controller uses this to make the command expire exactly with the
    /// capability it was derived from, without losing a millisecond to the
    /// clock advancing between the two calls.
    #[allow(clippy::too_many_arguments)]
    pub fn issue_with_expiry(
        identity: &Identity,
        controller_device: DeviceId,
        source_device: DeviceId,
        destination_device: DeviceId,
        handle_id: ContentHandleId,
        content_id: ContentId,
        transfer_id: TransferId,
        expires_at: u64,
        controller_endpoint: Option<String>,
    ) -> SecurityResult<Self> {
        if source_device == destination_device || expires_at <= unix_millis() {
            return Err(SecurityError::SpatialExpired);
        }
        let claim = SpatialCommandClaim {
            source_device,
            controller_device,
            destination_device,
            handle_id,
            content_id,
            transfer_id,
            expires_at,
            nonce: random_nonce(),
            controller_endpoint,
        };
        let signature = identity.sign(&claim.canonical_bytes()?);
        Ok(Self { claim, signature })
    }

    /// Verify structure, expiry and the controller signature.
    pub fn verify(&self, controller_public: &PublicIdentity) -> SecurityResult<()> {
        let claim = &self.claim;
        if claim.is_expired() {
            return Err(SecurityError::SpatialExpired);
        }
        if claim.nonce.len() != NONCE_BYTES * 2 || hex::decode(&claim.nonce).is_err() {
            return Err(SecurityError::SpatialMismatch);
        }
        if claim.source_device == claim.destination_device
            || claim.controller_device == claim.source_device
            || claim.controller_device == claim.destination_device
        {
            return Err(SecurityError::SpatialMismatch);
        }
        controller_public.verify(&claim.canonical_bytes()?, &self.signature)
    }

    pub fn transfer_id(&self) -> TransferId {
        self.claim.transfer_id
    }

    pub fn expires_at(&self) -> u64 {
        self.claim.expires_at
    }
}

/// What a verifier expects a spatial proof to authorize.
#[derive(Debug, Clone)]
pub struct SpatialExpectation<'a> {
    pub source_device: &'a DeviceId,
    pub controller_device: &'a DeviceId,
    pub destination_device: &'a DeviceId,
    pub transfer_id: TransferId,
    pub content: &'a Content,
}

/// Verify a capability and command together.
///
/// The signatures are checked independently, then every binding is compared.
/// A valid controller signature over a modified destination is still rejected.
pub fn verify_spatial_proof(
    command: &SpatialCommand,
    capability: &ContentCapability,
    source_public: &PublicIdentity,
    controller_public: &PublicIdentity,
    expected: &SpatialExpectation<'_>,
) -> SecurityResult<()> {
    capability.verify(source_public)?;
    command.verify(controller_public)?;

    let capability = &capability.claim;
    let command = &command.claim;
    if capability.source_device != *expected.source_device
        || capability.controller_device != *expected.controller_device
        || capability.content != *expected.content
        || !capability.content.is_wire_safe()
        || command.source_device != *expected.source_device
        || command.controller_device != *expected.controller_device
        || command.destination_device != *expected.destination_device
        || command.transfer_id != expected.transfer_id
        || command.handle_id != capability.handle_id
        || command.content_id != capability.content.content_id
        || command.content_id != expected.content.content_id
        || command.expires_at > capability.expires_at
    {
        return Err(SecurityError::SpatialMismatch);
    }
    if expected.source_device == expected.destination_device
        || expected.controller_device == expected.destination_device
        || expected.controller_device == expected.source_device
    {
        return Err(SecurityError::SpatialMismatch);
    }
    Ok(())
}

fn random_nonce() -> String {
    let mut bytes = [0u8; NONCE_BYTES];
    OsRng.fill_bytes(&mut bytes);
    hex::encode(bytes)
}

#[cfg(test)]
mod tests {
    use super::*;
    use flowww_core::ContentHash;

    fn device(id: &str) -> DeviceId {
        DeviceId::new(id).unwrap()
    }

    fn identity() -> Identity {
        Identity::generate()
    }

    fn content(source: &DeviceId) -> Content {
        Content::text(source.clone(), "spatial payload").unwrap()
    }

    struct Fixture {
        source: Identity,
        controller: Identity,
        source_id: DeviceId,
        controller_id: DeviceId,
        destination_id: DeviceId,
        capability: ContentCapability,
        command: SpatialCommand,
        content: Content,
    }

    fn fixture() -> Fixture {
        let source = identity();
        let controller = identity();
        let source_id = device("fw_source_01");
        let controller_id = device("fw_phone_001");
        let destination_id = device("fw_dest_0001");
        let content = content(&source_id);
        let handle = ContentHandleId::generate();
        let capability = ContentCapability::issue(
            &source,
            handle.clone(),
            controller_id.clone(),
            content.clone(),
            120_000,
        )
        .unwrap();
        let command = SpatialCommand::issue(
            &controller,
            controller_id.clone(),
            source_id.clone(),
            destination_id.clone(),
            handle,
            content.content_id.clone(),
            TransferId::new(),
            60_000,
            None,
        )
        .unwrap();
        Fixture {
            source,
            controller,
            source_id,
            controller_id,
            destination_id,
            capability,
            command,
            content,
        }
    }

    fn expectation<'a>(fixture: &'a Fixture) -> SpatialExpectation<'a> {
        SpatialExpectation {
            source_device: &fixture.source_id,
            controller_device: &fixture.controller_id,
            destination_device: &fixture.destination_id,
            transfer_id: fixture.command.transfer_id(),
            content: &fixture.content,
        }
    }

    #[test]
    fn valid_capability_and_command_verify() {
        let fixture = fixture();
        verify_spatial_proof(
            &fixture.command,
            &fixture.capability,
            &fixture.source.public_identity(),
            &fixture.controller.public_identity(),
            &expectation(&fixture),
        )
        .unwrap();
    }

    #[test]
    fn changed_destination_is_rejected() {
        let mut fixture = fixture();
        fixture.command.claim.destination_device = device("fw_attacker_1");
        assert!(verify_spatial_proof(
            &fixture.command,
            &fixture.capability,
            &fixture.source.public_identity(),
            &fixture.controller.public_identity(),
            &expectation(&fixture),
        )
        .is_err());
    }

    #[test]
    fn capability_is_bound_to_one_controller() {
        let fixture = fixture();
        let other = identity();
        assert!(verify_spatial_proof(
            &fixture.command,
            &fixture.capability,
            &fixture.source.public_identity(),
            &other.public_identity(),
            &expectation(&fixture),
        )
        .is_err());
    }

    #[test]
    fn expired_capability_is_rejected() {
        let source = identity();
        let source_id = device("fw_source_01");
        let content = content(&source_id);
        let handle = ContentHandleId::generate();
        let capability =
            ContentCapability::issue(&source, handle, device("fw_phone_001"), content, 1).unwrap();
        std::thread::sleep(std::time::Duration::from_millis(5));
        assert_eq!(
            capability
                .verify(&source.public_identity())
                .unwrap_err()
                .to_string(),
            "the spatial content capability has expired"
        );
    }

    #[test]
    fn content_paths_are_never_valid_in_a_capability() {
        let source = identity();
        let source_id = device("fw_source_01");
        let content = Content::file(
            source_id,
            "report.pdf",
            "application/pdf",
            10,
            ContentHash([1u8; 32]),
            "C:\\Users\\secret\\report.pdf",
        )
        .unwrap();
        assert!(ContentCapability::issue(
            &source,
            ContentHandleId::generate(),
            device("fw_phone_001"),
            content.clone(),
            60_000,
        )
        .is_err());
        let capability = ContentCapability::issue(
            &source,
            ContentHandleId::generate(),
            device("fw_phone_001"),
            content.wire_safe(),
            60_000,
        )
        .unwrap();
        assert!(capability.claim.content.is_wire_safe());
        let json = serde_json::to_string(&capability).unwrap();
        assert!(!json.contains("Users"));
    }

    #[test]
    fn handles_are_opaque_and_well_formed() {
        let handle = ContentHandleId::generate();
        assert!(handle.as_str().starts_with("fh_"));
        assert_eq!(handle.as_str().len(), 35);
        let parsed = ContentHandleId::parse(handle.as_str()).unwrap();
        assert_eq!(parsed, handle);
        assert!(ContentHandleId::parse("../../etc/passwd").is_err());
        assert!(ContentHandleId::parse("FH_00000000000000000000000000000000").is_err());
        assert!(ContentHandleId::parse(handle.as_str().to_uppercase()).is_err());
    }
}
