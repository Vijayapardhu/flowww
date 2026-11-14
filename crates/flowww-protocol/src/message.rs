use serde::{Deserialize, Serialize};
use std::collections::BTreeSet;

use crate::ProtocolError;
use flowww_core::{
    is_expired, Capability, Content, ContentHash, DeviceId, DeviceInfo, TransferAction, TransferId,
    TransferRoute, MAX_CONTROL_FRAME,
};
use flowww_security::{SignatureB64, TransferAuthorization};

pub const MAX_REJECT_REASON: usize = 512;
pub const MAX_ERROR_LENGTH: usize = 1024;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum AckStatus {
    Accepted,
    Duplicate,
    Paused,
    Rejected,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ErrorCode {
    Protocol,
    Authentication,
    Unsupported,
    Busy,
    Storage,
    HashMismatch,
    Cancelled,
    Internal,
}

/// FLW/1.0 control-plane messages.
///
/// Messages are built once per operation and immediately serialized; the size
/// spread between variants does not matter here, so boxing would only add
/// indirection without changing what goes on the wire.
#[allow(clippy::large_enum_variant)]
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "SCREAMING_SNAKE_CASE")]
pub enum Message {
    Hello {
        device: DeviceInfo,
        /// 32 random bytes, hex encoded.
        nonce: String,
    },
    DeviceInfo {
        device: DeviceInfo,
    },
    /// Extension: proves possession of the Ed25519 identity bound to the TLS channel.
    Auth {
        device_id: DeviceId,
        signature: SignatureB64,
    },
    Capabilities {
        capabilities: BTreeSet<Capability>,
    },
    TransferOffer {
        transfer_id: TransferId,
        source_device: DeviceId,
        destination_device: DeviceId,
        content: Content,
        action: TransferAction,
        route: TransferRoute,
        expires_at: u64,
        authorization: TransferAuthorization,
    },
    TransferAccept {
        transfer_id: TransferId,
        /// Receiver's verified resume offset, always <= content size.
        resume_from: u64,
    },
    TransferReject {
        transfer_id: TransferId,
        reason: String,
    },
    TransferStart {
        transfer_id: TransferId,
        resume_from: u64,
    },
    Ack {
        transfer_id: TransferId,
        chunk_index: u64,
        status: AckStatus,
        received_bytes: u64,
    },
    Pause {
        transfer_id: TransferId,
        reason: Option<String>,
    },
    Resume {
        transfer_id: TransferId,
        offset: u64,
    },
    Cancel {
        transfer_id: TransferId,
        reason: Option<String>,
    },
    Complete {
        transfer_id: TransferId,
        sha256: ContentHash,
        total_bytes: u64,
    },
    Error {
        code: ErrorCode,
        message: String,
        transfer_id: Option<TransferId>,
    },
    Ping {
        nonce: String,
    },
    Pong {
        nonce: String,
    },
}

impl Message {
    pub fn kind(&self) -> &'static str {
        match self {
            Self::Hello { .. } => "HELLO",
            Self::DeviceInfo { .. } => "DEVICE_INFO",
            Self::Auth { .. } => "AUTH",
            Self::Capabilities { .. } => "CAPABILITIES",
            Self::TransferOffer { .. } => "TRANSFER_OFFER",
            Self::TransferAccept { .. } => "TRANSFER_ACCEPT",
            Self::TransferReject { .. } => "TRANSFER_REJECT",
            Self::TransferStart { .. } => "TRANSFER_START",
            Self::Ack { .. } => "ACK",
            Self::Pause { .. } => "PAUSE",
            Self::Resume { .. } => "RESUME",
            Self::Cancel { .. } => "CANCEL",
            Self::Complete { .. } => "COMPLETE",
            Self::Error { .. } => "ERROR",
            Self::Ping { .. } => "PING",
            Self::Pong { .. } => "PONG",
        }
    }

    pub fn transfer_id(&self) -> Option<TransferId> {
        match self {
            Self::TransferOffer { transfer_id, .. }
            | Self::TransferAccept { transfer_id, .. }
            | Self::TransferReject { transfer_id, .. }
            | Self::TransferStart { transfer_id, .. }
            | Self::Ack { transfer_id, .. }
            | Self::Pause { transfer_id, .. }
            | Self::Resume { transfer_id, .. }
            | Self::Cancel { transfer_id, .. }
            | Self::Complete { transfer_id, .. } => Some(*transfer_id),
            Self::Error { transfer_id, .. } => *transfer_id,
            _ => None,
        }
    }

    pub fn validate(&self) -> Result<(), ProtocolError> {
        match self {
            Self::Hello { device, nonce } => {
                validate_device(device)?;
                validate_nonce(nonce)
            }
            Self::DeviceInfo { device } => validate_device(device),
            Self::Auth {
                device_id,
                signature,
            } => {
                if device_id.as_str().is_empty() {
                    return Err(ProtocolError::Invalid("empty device id".into()));
                }
                if signature.0.is_empty() || signature.0.len() > 128 {
                    return Err(ProtocolError::Invalid("invalid signature".into()));
                }
                Ok(())
            }
            Self::Capabilities { capabilities } => {
                if capabilities.len() > 128 {
                    return Err(ProtocolError::Invalid("too many capabilities".into()));
                }
                Ok(())
            }
            Self::TransferOffer {
                transfer_id,
                source_device,
                destination_device,
                content,
                action,
                expires_at,
                authorization,
                ..
            } => {
                if source_device == destination_device {
                    return Err(ProtocolError::Invalid(
                        "source and destination must differ".into(),
                    ));
                }
                if is_expired(*expires_at) {
                    return Err(ProtocolError::Invalid("offer has expired".into()));
                }
                content
                    .validate()
                    .map_err(|e| ProtocolError::Invalid(e.to_string()))?;
                if content.source_device != *source_device {
                    return Err(ProtocolError::Invalid(
                        "content source does not match offer".into(),
                    ));
                }
                let claim = &authorization.claim;
                if claim.source_device != *source_device
                    || claim.destination_device != *destination_device
                    || claim.content_id != content.content_id
                    || claim.transfer_id != *transfer_id
                    || claim.action != *action
                    || claim.expires_at != *expires_at
                {
                    return Err(ProtocolError::Invalid(
                        "authorization does not match the offer".into(),
                    ));
                }
                if content.size > claim.max_size {
                    return Err(ProtocolError::Invalid(
                        "content exceeds the authorized size".into(),
                    ));
                }
                if content.size > MAX_CONTROL_FRAME as u64 && content.inline.is_some() {
                    return Err(ProtocolError::Invalid("inline content is too large".into()));
                }
                Ok(())
            }
            Self::TransferAccept { resume_from, .. } | Self::TransferStart { resume_from, .. } => {
                if *resume_from > (u64::MAX / 2) {
                    return Err(ProtocolError::Invalid("resume offset out of range".into()));
                }
                Ok(())
            }
            Self::TransferReject { reason, .. } => {
                if reason.len() > MAX_REJECT_REASON {
                    return Err(ProtocolError::Invalid("reject reason is too long".into()));
                }
                Ok(())
            }
            Self::Ack { received_bytes, .. } => {
                if *received_bytes > u64::MAX / 2 {
                    return Err(ProtocolError::Invalid("invalid received byte count".into()));
                }
                Ok(())
            }
            Self::Pause { reason, .. } | Self::Cancel { reason, .. } => {
                if reason.as_ref().is_some_and(|r| r.len() > MAX_REJECT_REASON) {
                    return Err(ProtocolError::Invalid("reason is too long".into()));
                }
                Ok(())
            }
            Self::Resume { offset, .. } => {
                if *offset > (u64::MAX / 2) {
                    return Err(ProtocolError::Invalid("resume offset out of range".into()));
                }
                Ok(())
            }
            Self::Complete {
                sha256,
                total_bytes,
                ..
            } => {
                if sha256.0 == [0u8; 32] {
                    return Err(ProtocolError::Invalid("empty final hash".into()));
                }
                if *total_bytes > u64::MAX / 2 {
                    return Err(ProtocolError::Invalid("invalid total size".into()));
                }
                Ok(())
            }
            Self::Error { message, .. } => {
                if message.is_empty() || message.len() > MAX_ERROR_LENGTH {
                    return Err(ProtocolError::Invalid("invalid error message".into()));
                }
                Ok(())
            }
            Self::Ping { nonce } | Self::Pong { nonce } => validate_nonce(nonce),
        }
    }
}

fn validate_nonce(nonce: &str) -> Result<(), ProtocolError> {
    if nonce.len() != 64 || hex::decode(nonce).is_err() {
        return Err(ProtocolError::Invalid(
            "nonce must be 32 hex-encoded bytes".into(),
        ));
    }
    Ok(())
}

fn validate_device(device: &DeviceInfo) -> Result<(), ProtocolError> {
    if device.device_name.trim().is_empty() || device.device_name.len() > 64 {
        return Err(ProtocolError::Invalid("invalid device name".into()));
    }
    if device.capabilities.len() > 128 {
        return Err(ProtocolError::Invalid("too many capabilities".into()));
    }
    if !crate::envelope::version_is_compatible(&device.protocol_version) {
        return Err(ProtocolError::UnsupportedVersion(
            device.protocol_version.clone(),
        ));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::Envelope;
    use flowww_core::{unix_millis, ContentId, ContentKind, DeviceId, Platform, PROTOCOL_VERSION};
    use flowww_security::{Identity, TransferClaim};
    use std::collections::BTreeSet;

    fn id(name: &str) -> DeviceId {
        DeviceId::new(name).unwrap()
    }

    fn device(name: &str) -> DeviceInfo {
        DeviceInfo::new(
            id(name),
            "Test device",
            Platform::Windows,
            BTreeSet::<Capability>::new(),
        )
        .unwrap()
    }

    fn offer() -> Message {
        let source = id("fw_source_01");
        let destination = id("fw_dest_0001");
        let identity = Identity::generate();
        let content = Content::text(source.clone(), "hello").unwrap();
        let transfer_id = TransferId::new();
        let expires_at = unix_millis() + 60_000;
        let claim = TransferClaim::new(
            source.clone(),
            destination.clone(),
            content.content_id.clone(),
            transfer_id,
            TransferAction::Save,
            content.size,
            (expires_at - unix_millis()).max(1000),
        );
        let mut authorization = TransferAuthorization::sign(&identity, claim).unwrap();
        authorization.claim.expires_at = expires_at;
        // Re-sign because the test overrides the expiry above.
        let claim = authorization.claim.clone();
        let authorization = TransferAuthorization::sign(&identity, claim).unwrap();
        Message::TransferOffer {
            transfer_id,
            source_device: source,
            destination_device: destination,
            content,
            action: TransferAction::Save,
            route: TransferRoute::Direct,
            expires_at,
            authorization,
        }
    }

    fn messages() -> Vec<Message> {
        let source = id("fw_source_01");
        let transfer_id = TransferId::new();
        vec![
            Message::Hello {
                device: device("fw_12345678"),
                nonce: "ab".repeat(32),
            },
            Message::DeviceInfo {
                device: device("fw_12345678"),
            },
            Message::Auth {
                device_id: source,
                signature: SignatureB64("c2ln".into()),
            },
            Message::Capabilities {
                capabilities: Capability::standard(),
            },
            offer(),
            Message::TransferAccept {
                transfer_id,
                resume_from: 1024,
            },
            Message::TransferReject {
                transfer_id,
                reason: "no space".into(),
            },
            Message::TransferStart {
                transfer_id,
                resume_from: 0,
            },
            Message::Ack {
                transfer_id,
                chunk_index: 3,
                status: AckStatus::Accepted,
                received_bytes: 4096,
            },
            Message::Pause {
                transfer_id,
                reason: Some("cold".into()),
            },
            Message::Resume {
                transfer_id,
                offset: 4096,
            },
            Message::Cancel {
                transfer_id,
                reason: None,
            },
            Message::Complete {
                transfer_id,
                sha256: ContentHash([3u8; 32]),
                total_bytes: 4096,
            },
            Message::Error {
                code: ErrorCode::Storage,
                message: "disk full".into(),
                transfer_id: Some(transfer_id),
            },
            Message::Ping {
                nonce: "cd".repeat(32),
            },
            Message::Pong {
                nonce: "cd".repeat(32),
            },
        ]
    }

    #[test]
    fn all_messages_round_trip_through_envelopes() {
        for message in messages() {
            let envelope = Envelope::new(message.clone());
            let bytes = envelope.encode().unwrap();
            let decoded = Envelope::decode(&bytes).unwrap();
            assert_eq!(decoded.message, message, "{} failed", message.kind());
        }
    }

    #[test]
    fn invalid_nonces_are_rejected() {
        let message = Message::Ping {
            nonce: "short".into(),
        };
        assert!(message.validate().is_err());
    }

    #[test]
    fn offer_must_match_authorization() {
        let Message::TransferOffer {
            mut authorization,
            transfer_id,
            ..
        } = offer()
        else {
            unreachable!()
        };
        authorization.claim.transfer_id = TransferId::new();
        let Message::TransferOffer {
            source_device,
            destination_device,
            content,
            action,
            route,
            expires_at,
            ..
        } = offer()
        else {
            unreachable!()
        };
        let broken = Message::TransferOffer {
            transfer_id,
            source_device,
            destination_device,
            content,
            action,
            route,
            expires_at,
            authorization,
        };
        assert!(broken.validate().is_err());
    }

    #[test]
    fn content_kind_is_serialized_lowercase() {
        assert_eq!(
            serde_json::to_string(&ContentKind::Document).unwrap(),
            "\"document\""
        );
        assert_eq!(PROTOCOL_VERSION, "FLW/1.0");
        assert!(!ContentId::new().0.is_empty());
    }
}
