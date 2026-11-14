use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use rand::rngs::OsRng;
use rand::RngCore;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::net::SocketAddr;
use std::str::FromStr;
use subtle::ConstantTimeEq;
use uuid::Uuid;

use crate::{Fingerprint, Identity, PublicIdentity, SecurityError, SecurityResult, SignatureB64};
use flowww_core::{unix_millis, DeviceInfo};

const PAIRING_URI_PREFIX: &str = "flowww://pair/v1/";
const PAIRING_VERSION: u8 = 1;
const PAIRING_DOMAIN: &[u8] = b"flowww.pairing.v1\n";
const CONFIRM_DOMAIN: &[u8] = b"flowww.pairing.confirm.v1\n";

/// The QR/shareable pairing payload.
///
/// It contains only public identity data plus a short-lived one-time token.
/// Private keys are never part of this structure.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct PairingPayload {
    pub version: u8,
    pub session_id: String,
    pub token: String,
    pub host_device: DeviceInfo,
    pub host_public_key: String,
    pub host_tls_fingerprint: String,
    pub endpoint: String,
    pub nonce: String,
    pub expires_at: u64,
}

impl PairingPayload {
    pub fn to_uri(&self) -> SecurityResult<String> {
        let json = serde_json::to_vec(self).map_err(|_| SecurityError::Encoding)?;
        if json.len() > 8192 {
            return Err(SecurityError::BadPairingPayload("payload too large".into()));
        }
        Ok(format!(
            "{}{}",
            PAIRING_URI_PREFIX,
            URL_SAFE_NO_PAD.encode(json)
        ))
    }

    pub fn from_uri(uri: &str) -> SecurityResult<Self> {
        let encoded = uri.trim().strip_prefix(PAIRING_URI_PREFIX).ok_or_else(|| {
            SecurityError::BadPairingPayload("expected a flowww://pair/v1/ URI".into())
        })?;
        let bytes = URL_SAFE_NO_PAD
            .decode(encoded)
            .map_err(|_| SecurityError::BadPairingPayload("invalid base64".into()))?;
        let payload: Self = serde_json::from_slice(&bytes)
            .map_err(|_| SecurityError::BadPairingPayload("invalid JSON".into()))?;
        payload.validate()?;
        Ok(payload)
    }

    pub fn validate(&self) -> SecurityResult<()> {
        if self.version != PAIRING_VERSION {
            return Err(SecurityError::BadPairingPayload(
                "unsupported version".into(),
            ));
        }
        Uuid::parse_str(&self.session_id)
            .map_err(|_| SecurityError::BadPairingPayload("invalid session id".into()))?;
        let token = URL_SAFE_NO_PAD
            .decode(&self.token)
            .map_err(|_| SecurityError::BadPairingPayload("invalid token".into()))?;
        if token.len() != 32 {
            return Err(SecurityError::BadPairingPayload("invalid token".into()));
        }
        if self.nonce.len() != 64 || hex::decode(&self.nonce).is_err() {
            return Err(SecurityError::BadPairingPayload("invalid nonce".into()));
        }
        Fingerprint::parse(&self.host_tls_fingerprint)
            .map_err(|_| SecurityError::BadPairingPayload("invalid TLS fingerprint".into()))?;
        PublicIdentity::from_base64(&self.host_public_key)
            .map_err(|_| SecurityError::BadPairingPayload("invalid host key".into()))?;
        SocketAddr::from_str(&self.endpoint)
            .map_err(|_| SecurityError::BadPairingPayload("invalid endpoint".into()))?;
        if self.host_device.device_id.as_str().is_empty() {
            return Err(SecurityError::BadPairingPayload(
                "invalid host device".into(),
            ));
        }
        if flowww_core::is_expired(self.expires_at) {
            return Err(SecurityError::PairingExpired);
        }
        Ok(())
    }

    pub fn host_tls_fingerprint(&self) -> SecurityResult<Fingerprint> {
        Fingerprint::parse(&self.host_tls_fingerprint)
    }

    pub fn host_public(&self) -> SecurityResult<PublicIdentity> {
        PublicIdentity::from_base64(&self.host_public_key)
    }
}

/// Host-side state for one short-lived pairing attempt.
#[derive(Debug, Clone)]
pub struct PairingSession {
    pub session_id: String,
    pub token: String,
    pub host_device: DeviceInfo,
    pub host_public_key: String,
    pub host_tls_fingerprint: Fingerprint,
    pub endpoint: String,
    pub nonce: String,
    pub expires_at: u64,
}

impl PairingSession {
    /// Create a pairing session and its shareable payload.
    ///
    /// `ttl_ms` should be short (two to three minutes). The token is generated
    /// with the operating system CSPRNG and is single-use.
    pub fn host(
        host_device: DeviceInfo,
        identity: &Identity,
        host_tls_fingerprint: Fingerprint,
        endpoint: SocketAddr,
        ttl_ms: u64,
    ) -> SecurityResult<(Self, PairingPayload)> {
        let session_id = Uuid::new_v4().to_string();
        let mut token = [0u8; 32];
        OsRng.fill_bytes(&mut token);
        let mut nonce = [0u8; 32];
        OsRng.fill_bytes(&mut nonce);
        let expires_at = unix_millis() + ttl_ms;
        let session = Self {
            session_id,
            token: URL_SAFE_NO_PAD.encode(token),
            host_device,
            host_public_key: identity.public_key_base64(),
            host_tls_fingerprint,
            endpoint: endpoint.to_string(),
            nonce: hex::encode(nonce),
            expires_at,
        };
        let payload = session.payload()?;
        Ok((session, payload))
    }

    pub fn payload(&self) -> SecurityResult<PairingPayload> {
        let payload = PairingPayload {
            version: PAIRING_VERSION,
            session_id: self.session_id.clone(),
            token: self.token.clone(),
            host_device: self.host_device.clone(),
            host_public_key: self.host_public_key.clone(),
            host_tls_fingerprint: self.host_tls_fingerprint.to_hex(),
            endpoint: self.endpoint.clone(),
            nonce: self.nonce.clone(),
            expires_at: self.expires_at,
        };
        Ok(payload)
    }

    pub fn is_expired(&self) -> bool {
        flowww_core::is_expired(self.expires_at)
    }

    pub fn verify_join(&self, join: &PairingJoin) -> SecurityResult<VerifiedJoin> {
        if self.is_expired() {
            return Err(SecurityError::PairingExpired);
        }
        if join.session_id != self.session_id {
            return Err(SecurityError::PairingMismatch);
        }
        let expected_token = URL_SAFE_NO_PAD
            .decode(&self.token)
            .map_err(|_| SecurityError::Encoding)?;
        let provided_token = URL_SAFE_NO_PAD
            .decode(&join.token)
            .map_err(|_| SecurityError::BadPairingToken)?;
        if expected_token.len() != provided_token.len()
            || !bool::from(expected_token.ct_eq(&provided_token))
        {
            return Err(SecurityError::BadPairingToken);
        }
        if join.nonce.len() != 64 || hex::decode(&join.nonce).is_err() {
            return Err(SecurityError::PairingMismatch);
        }
        if join.device.device_id == self.host_device.device_id {
            return Err(SecurityError::PairingMismatch);
        }
        let join_public = PublicIdentity::from_base64(&join.public_key)?;
        let join_tls = Fingerprint::parse(&join.tls_fingerprint)?;
        let transcript = pairing_transcript(
            &self.session_id,
            &self.token,
            self.expires_at,
            &self.host_device,
            &self.host_public_key,
            &self.nonce,
            &self.host_tls_fingerprint.to_hex(),
            &join.device,
            &join.public_key,
            &join.nonce,
            &join.tls_fingerprint,
        )?;
        join_public.verify(&transcript, &join.signature)?;
        Ok(VerifiedJoin {
            device: join.device.clone(),
            public: join_public,
            tls_fingerprint: join_tls,
            nonce: join.nonce.clone(),
            transcript,
        })
    }

    /// Sign a host acceptance for a verified join request.
    pub fn accept(
        &self,
        identity: &Identity,
        verified: &VerifiedJoin,
    ) -> SecurityResult<PairingAccept> {
        if self.is_expired() {
            return Err(SecurityError::PairingExpired);
        }
        let signature = identity.sign(&verified.transcript);
        Ok(PairingAccept {
            session_id: self.session_id.clone(),
            host_device: self.host_device.clone(),
            host_public_key: self.host_public_key.clone(),
            host_tls_fingerprint: self.host_tls_fingerprint.to_hex(),
            host_nonce: self.nonce.clone(),
            signature,
        })
    }

    pub fn verify_confirm(
        &self,
        verified: &VerifiedJoin,
        confirm: &PairingConfirm,
    ) -> SecurityResult<()> {
        if confirm.session_id != self.session_id {
            return Err(SecurityError::PairingMismatch);
        }
        let mut message = CONFIRM_DOMAIN.to_vec();
        message.extend_from_slice(&verified.transcript);
        verified.public.verify(&message, &confirm.signature)
    }
}

/// The joiner's request, sent over a TLS channel pinned to the host.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PairingJoin {
    pub session_id: String,
    pub token: String,
    pub device: DeviceInfo,
    pub public_key: String,
    pub tls_fingerprint: String,
    pub nonce: String,
    pub signature: SignatureB64,
}

impl PairingJoin {
    pub fn create(
        payload: &PairingPayload,
        identity: &Identity,
        device: DeviceInfo,
        tls_fingerprint: Fingerprint,
    ) -> SecurityResult<Self> {
        payload.validate()?;
        let mut nonce = [0u8; 32];
        OsRng.fill_bytes(&mut nonce);
        let nonce_hex = hex::encode(nonce);
        let transcript = pairing_transcript(
            &payload.session_id,
            &payload.token,
            payload.expires_at,
            &payload.host_device,
            &payload.host_public_key,
            &payload.nonce,
            &payload.host_tls_fingerprint,
            &device,
            &identity.public_key_base64(),
            &nonce_hex,
            &tls_fingerprint.to_hex(),
        )?;
        let signature = identity.sign(&transcript);
        Ok(Self {
            session_id: payload.session_id.clone(),
            token: payload.token.clone(),
            device,
            public_key: identity.public_key_base64(),
            tls_fingerprint: tls_fingerprint.to_hex(),
            nonce: nonce_hex,
            signature,
        })
    }

    /// Verify the host's acceptance on the joiner side. This proves the host
    /// holds the private key matching the public key inside the QR payload.
    pub fn verify_accept(
        &self,
        payload: &PairingPayload,
        accept: &PairingAccept,
    ) -> SecurityResult<()> {
        payload.validate()?;
        if accept.session_id != payload.session_id
            || accept.host_nonce != payload.nonce
            || accept.host_device != payload.host_device
            || accept.host_public_key != payload.host_public_key
            || accept.host_tls_fingerprint != payload.host_tls_fingerprint
        {
            return Err(SecurityError::PairingMismatch);
        }
        let host_public = payload.host_public()?;
        let transcript = pairing_transcript(
            &payload.session_id,
            &payload.token,
            payload.expires_at,
            &payload.host_device,
            &payload.host_public_key,
            &payload.nonce,
            &payload.host_tls_fingerprint,
            &self.device,
            &self.public_key,
            &self.nonce,
            &self.tls_fingerprint,
        )?;
        host_public.verify(&transcript, &accept.signature)
    }

    pub fn confirm(
        &self,
        identity: &Identity,
        payload: &PairingPayload,
    ) -> SecurityResult<PairingConfirm> {
        let transcript = pairing_transcript(
            &payload.session_id,
            &payload.token,
            payload.expires_at,
            &payload.host_device,
            &payload.host_public_key,
            &payload.nonce,
            &payload.host_tls_fingerprint,
            &self.device,
            &self.public_key,
            &self.nonce,
            &self.tls_fingerprint,
        )?;
        let mut message = CONFIRM_DOMAIN.to_vec();
        message.extend_from_slice(&transcript);
        Ok(PairingConfirm {
            session_id: payload.session_id.clone(),
            signature: identity.sign(&message),
        })
    }
}

impl PairingJoin {
    pub fn transcript(&self, payload: &PairingPayload) -> SecurityResult<Vec<u8>> {
        pairing_transcript(
            &payload.session_id,
            &payload.token,
            payload.expires_at,
            &payload.host_device,
            &payload.host_public_key,
            &payload.nonce,
            &payload.host_tls_fingerprint,
            &self.device,
            &self.public_key,
            &self.nonce,
            &self.tls_fingerprint,
        )
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PairingAccept {
    pub session_id: String,
    pub host_device: DeviceInfo,
    pub host_public_key: String,
    pub host_tls_fingerprint: String,
    pub host_nonce: String,
    pub signature: SignatureB64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PairingConfirm {
    pub session_id: String,
    pub signature: SignatureB64,
}

/// A verified join request. Only the host can construct one.
#[derive(Debug, Clone)]
pub struct VerifiedJoin {
    pub device: DeviceInfo,
    pub public: PublicIdentity,
    pub tls_fingerprint: Fingerprint,
    pub nonce: String,
    pub transcript: Vec<u8>,
}

/// Pairing messages as exchanged over the short-lived TLS pairing channel.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type", content = "payload", rename_all = "SCREAMING_SNAKE_CASE")]
pub enum PairingTransport {
    Join(PairingJoin),
    Accept(PairingAccept),
    Confirm(PairingConfirm),
    Reject { reason: String },
    Complete,
}

#[allow(clippy::too_many_arguments)]
fn pairing_transcript(
    session_id: &str,
    token: &str,
    expires_at: u64,
    host_device: &DeviceInfo,
    host_public_key: &str,
    host_nonce: &str,
    host_tls_fingerprint: &str,
    join_device: &DeviceInfo,
    join_public_key: &str,
    join_nonce: &str,
    join_tls_fingerprint: &str,
) -> SecurityResult<Vec<u8>> {
    let body = serde_json::json!({
        "domain": "flowww.pairing.v1",
        "session_id": session_id,
        "token": token,
        "expires_at": expires_at,
        "host": {
            "device": host_device,
            "public_key": host_public_key,
            "nonce": host_nonce,
            "tls_fingerprint": host_tls_fingerprint,
        },
        "join": {
            "device": join_device,
            "public_key": join_public_key,
            "nonce": join_nonce,
            "tls_fingerprint": join_tls_fingerprint,
        }
    });
    let json = serde_json::to_vec(&body).map_err(|_| SecurityError::Encoding)?;
    let mut hasher = Sha256::new();
    hasher.update(PAIRING_DOMAIN);
    hasher.update(json);
    Ok(hasher.finalize().to_vec())
}

#[cfg(test)]
mod tests {
    use super::*;
    use flowww_core::{Capability, DeviceId, Platform};
    use std::collections::BTreeSet;

    fn device(id: &str, name: &str) -> DeviceInfo {
        DeviceInfo::new(
            DeviceId::new(id).unwrap(),
            name,
            Platform::Windows,
            BTreeSet::<Capability>::new(),
        )
        .unwrap()
    }

    fn payload() -> (PairingSession, PairingPayload, Identity, PairingPayload) {
        let host = Identity::generate();
        let (session, payload) = PairingSession::host(
            device("fw_host_0001", "Studio"),
            &host,
            Fingerprint([9u8; 32]),
            "192.168.1.10:49152".parse().unwrap(),
            120_000,
        )
        .unwrap();
        let mirror = payload.clone();
        (session, payload, host, mirror)
    }

    #[test]
    fn uri_round_trip_and_no_secret_material() {
        let (_, payload, _, _) = payload();
        let uri = payload.to_uri().unwrap();
        assert_eq!(PairingPayload::from_uri(&uri).unwrap(), payload);
        assert!(!uri.contains("secret"));
        assert!(!uri.contains("private"));
        assert!(uri.len() < 4096);
    }

    #[test]
    fn full_pairing_transcript_verifies() {
        let (session, payload, host_identity, _) = payload();
        let joiner_identity = Identity::generate();
        let join = PairingJoin::create(
            &payload,
            &joiner_identity,
            device("fw_join_0001", "Phone"),
            Fingerprint([8u8; 32]),
        )
        .unwrap();
        let verified = session.verify_join(&join).unwrap();
        let accept = session.accept(&host_identity, &verified).unwrap();
        join.verify_accept(&payload, &accept).unwrap();
        let confirm = join.confirm(&joiner_identity, &payload).unwrap();
        session.verify_confirm(&verified, &confirm).unwrap();
    }

    #[test]
    fn malformed_or_replayed_tokens_are_rejected() {
        let (session, payload, _, _) = payload();
        let joiner_identity = Identity::generate();
        let mut join = PairingJoin::create(
            &payload,
            &joiner_identity,
            device("fw_join_0001", "Phone"),
            Fingerprint([8u8; 32]),
        )
        .unwrap();
        join.token = URL_SAFE_NO_PAD.encode([0u8; 32]);
        assert!(matches!(
            session.verify_join(&join),
            Err(SecurityError::BadPairingToken)
        ));
    }

    #[test]
    fn tampered_join_is_rejected() {
        let (session, payload, _, _) = payload();
        let joiner_identity = Identity::generate();
        let mut join = PairingJoin::create(
            &payload,
            &joiner_identity,
            device("fw_join_0001", "Phone"),
            Fingerprint([8u8; 32]),
        )
        .unwrap();
        join.device.device_name = "Other".into();
        assert!(session.verify_join(&join).is_err());
    }

    #[test]
    fn expired_payload_is_rejected() {
        let mut payload = payload().1;
        payload.expires_at = unix_millis().saturating_sub(1);
        assert_eq!(
            payload.validate().unwrap_err().to_string(),
            "the pairing session has expired"
        );
    }

    #[test]
    fn accept_for_another_session_is_rejected() {
        let (session, payload, host_identity, _) = payload();
        let joiner_identity = Identity::generate();
        let join = PairingJoin::create(
            &payload,
            &joiner_identity,
            device("fw_join_0001", "Phone"),
            Fingerprint([8u8; 32]),
        )
        .unwrap();
        let verified = session.verify_join(&join).unwrap();
        let mut accept = session.accept(&host_identity, &verified).unwrap();
        accept.session_id = Uuid::new_v4().to_string();
        assert!(join.verify_accept(&payload, &accept).is_err());
    }
}
