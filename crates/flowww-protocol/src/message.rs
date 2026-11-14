use serde::{Deserialize, Serialize};
use std::collections::BTreeSet;
use uuid::Uuid;

use crate::ProtocolError;
use flowww_core::{
    is_expired, Capability, Content, ContentHash, DeviceId, DeviceInfo, TransferAction, TransferId,
    TransferRoute, TransferState, MAX_CONTROL_FRAME,
};
use flowww_security::{ContentCapability, SignatureB64, SpatialCommand, TransferAuthorization};

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
    /// Extension: a controller asks a source to expose its currently held content.
    SpatialCaptureRequest {
        request_id: Uuid,
        source_device: DeviceId,
        controller_device: DeviceId,
    },
    /// Extension: the source's short-lived, signed content capability.
    SpatialCaptureGrant {
        request_id: Uuid,
        capability: ContentCapability,
    },
    /// Extension: the source declined to expose content.
    SpatialCaptureDeny {
        request_id: Uuid,
        reason: String,
    },
    /// Extension: the controller directs a captured handle to one destination.
    SpatialTransferCommand {
        command: SpatialCommand,
        capability: ContentCapability,
    },
    /// Extension: the source accepted a command and chose a route.
    SpatialCommandAccepted {
        transfer_id: TransferId,
        route: TransferRoute,
    },
    /// Extension: the source rejected a command.
    SpatialCommandRejected {
        transfer_id: TransferId,
        reason: String,
    },
    /// Extension: a validated spatial exchange arriving at the destination.
    SpatialOffer {
        transfer_id: TransferId,
        source_device: DeviceId,
        controller_device: DeviceId,
        destination_device: DeviceId,
        content: Content,
        action: TransferAction,
        route: TransferRoute,
        expires_at: u64,
        authorization: TransferAuthorization,
        capability: ContentCapability,
        command: SpatialCommand,
    },
    /// Extension: progress or a terminal result for a spatial transfer.
    SpatialStatus {
        transfer_id: TransferId,
        status: TransferState,
        bytes_transferred: u64,
        detail: Option<String>,
    },
    /// Extension: either side may cancel a spatial exchange.
    SpatialCancel {
        transfer_id: TransferId,
        reason: Option<String>,
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
            Self::SpatialCaptureRequest { .. } => "SPATIAL_CAPTURE_REQUEST",
            Self::SpatialCaptureGrant { .. } => "SPATIAL_CAPTURE_GRANT",
            Self::SpatialCaptureDeny { .. } => "SPATIAL_CAPTURE_DENY",
            Self::SpatialTransferCommand { .. } => "SPATIAL_TRANSFER_COMMAND",
            Self::SpatialCommandAccepted { .. } => "SPATIAL_COMMAND_ACCEPTED",
            Self::SpatialCommandRejected { .. } => "SPATIAL_COMMAND_REJECTED",
            Self::SpatialOffer { .. } => "SPATIAL_OFFER",
            Self::SpatialStatus { .. } => "SPATIAL_STATUS",
            Self::SpatialCancel { .. } => "SPATIAL_CANCEL",
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
            | Self::Complete { transfer_id, .. }
            | Self::SpatialCommandAccepted { transfer_id, .. }
            | Self::SpatialCommandRejected { transfer_id, .. }
            | Self::SpatialOffer { transfer_id, .. }
            | Self::SpatialStatus { transfer_id, .. }
            | Self::SpatialCancel { transfer_id, .. } => Some(*transfer_id),
            Self::Error { transfer_id, .. } => *transfer_id,
            Self::SpatialTransferCommand { command, .. } => Some(command.claim.transfer_id),
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
            Self::SpatialCaptureRequest {
                source_device,
                controller_device,
                ..
            } => {
                if source_device == controller_device {
                    return Err(ProtocolError::Invalid(
                        "a controller cannot capture from itself".into(),
                    ));
                }
                Ok(())
            }
            Self::SpatialCaptureGrant { capability, .. } => validate_spatial_capability(capability),
            Self::SpatialCaptureDeny { reason, .. } => validate_reason(reason),
            Self::SpatialTransferCommand {
                command,
                capability,
            } => validate_spatial_pair(command, capability),
            Self::SpatialCommandAccepted { .. } => Ok(()),
            Self::SpatialCommandRejected { reason, .. } => validate_reason(reason),
            Self::SpatialOffer {
                transfer_id,
                source_device,
                controller_device,
                destination_device,
                content,
                action,
                expires_at,
                authorization,
                capability,
                command,
                ..
            } => {
                if source_device == destination_device
                    || controller_device == source_device
                    || controller_device == destination_device
                {
                    return Err(ProtocolError::Invalid(
                        "spatial devices must be distinct".into(),
                    ));
                }
                if is_expired(*expires_at) {
                    return Err(ProtocolError::Invalid(
                        "the spatial offer has expired".into(),
                    ));
                }
                if content.source_device != *source_device || !content.is_wire_safe() {
                    return Err(ProtocolError::Invalid(
                        "the spatial content is not wire-safe or has the wrong source".into(),
                    ));
                }
                content
                    .validate()
                    .map_err(|e| ProtocolError::Invalid(e.to_string()))?;
                if capability.claim.content != *content {
                    return Err(ProtocolError::Invalid(
                        "the capability does not match the offered content".into(),
                    ));
                }
                validate_spatial_pair(command, capability)?;
                if command.claim.destination_device != *destination_device
                    || command.claim.transfer_id != *transfer_id
                    || command.claim.content_id != content.content_id
                {
                    return Err(ProtocolError::Invalid(
                        "the command does not match the spatial offer".into(),
                    ));
                }
                if command.claim.expires_at != authorization.claim.expires_at
                    || *expires_at != authorization.claim.expires_at
                {
                    return Err(ProtocolError::Invalid(
                        "the spatial offer expiry does not match its authorization".into(),
                    ));
                }
                let claim = &authorization.claim;
                if claim.source_device != *source_device
                    || claim.destination_device != *destination_device
                    || claim.content_id != content.content_id
                    || claim.transfer_id != *transfer_id
                    || claim.action != *action
                {
                    return Err(ProtocolError::Invalid(
                        "the authorization does not match the spatial offer".into(),
                    ));
                }
                if content.size > claim.max_size {
                    return Err(ProtocolError::Invalid(
                        "content exceeds the authorized size".into(),
                    ));
                }
                if content.inline.is_some() && content.size > MAX_CONTROL_FRAME as u64 {
                    return Err(ProtocolError::Invalid("inline content is too large".into()));
                }
                Ok(())
            }
            Self::SpatialStatus {
                bytes_transferred,
                detail,
                ..
            } => {
                if *bytes_transferred > u64::MAX / 2 {
                    return Err(ProtocolError::Invalid("invalid byte count".into()));
                }
                if detail
                    .as_ref()
                    .is_some_and(|value| value.len() > MAX_REJECT_REASON)
                {
                    return Err(ProtocolError::Invalid("detail is too long".into()));
                }
                Ok(())
            }
            Self::SpatialCancel { reason, .. } => {
                if reason
                    .as_ref()
                    .is_some_and(|value| value.len() > MAX_REJECT_REASON)
                {
                    return Err(ProtocolError::Invalid("reason is too long".into()));
                }
                Ok(())
            }
        }
    }
}

fn validate_reason(reason: &str) -> Result<(), ProtocolError> {
    if reason.is_empty() || reason.len() > MAX_REJECT_REASON {
        return Err(ProtocolError::Invalid("invalid reason".into()));
    }
    Ok(())
}

fn validate_spatial_capability(capability: &ContentCapability) -> Result<(), ProtocolError> {
    let claim = &capability.claim;
    if claim.is_expired() {
        return Err(ProtocolError::Invalid(
            "the content capability has expired".into(),
        ));
    }
    if claim.source_device != claim.content.source_device
        || claim.source_device == claim.controller_device
    {
        return Err(ProtocolError::Invalid(
            "the capability source does not match its content".into(),
        ));
    }
    if !claim.content.is_wire_safe() {
        return Err(ProtocolError::Invalid(
            "the capability content is not wire-safe".into(),
        ));
    }
    claim
        .content
        .validate()
        .map_err(|e| ProtocolError::Invalid(e.to_string()))?;
    if claim.content.inline.is_some() && claim.content.size > MAX_CONTROL_FRAME as u64 {
        return Err(ProtocolError::Invalid("inline content is too large".into()));
    }
    Ok(())
}

fn validate_spatial_pair(
    command: &SpatialCommand,
    capability: &ContentCapability,
) -> Result<(), ProtocolError> {
    validate_spatial_capability(capability)?;
    let command = &command.claim;
    if command.is_expired() {
        return Err(ProtocolError::Invalid(
            "the spatial command has expired".into(),
        ));
    }
    if command.source_device == command.destination_device
        || command.controller_device == command.source_device
        || command.controller_device == command.destination_device
    {
        return Err(ProtocolError::Invalid(
            "spatial devices must be distinct".into(),
        ));
    }
    if command.handle_id != capability.claim.handle_id
        || command.source_device != capability.claim.source_device
        || command.controller_device != capability.claim.controller_device
        || command.content_id != capability.claim.content.content_id
    {
        return Err(ProtocolError::Invalid(
            "the command does not match its capability".into(),
        ));
    }
    if command.expires_at > capability.claim.expires_at {
        return Err(ProtocolError::Invalid(
            "the command outlives its capability".into(),
        ));
    }
    Ok(())
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
    use flowww_core::{
        unix_millis, ContentHandleId, ContentId, ContentKind, DeviceId, Platform, PROTOCOL_VERSION,
    };
    use flowww_security::{ContentCapability, Identity, SpatialCommand, TransferClaim};
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

    fn spatial_offer() -> Message {
        let source = id("fw_source_01");
        let controller = id("fw_phone_001");
        let destination = id("fw_dest_0001");
        let source_identity = Identity::generate();
        let controller_identity = Identity::generate();
        let content = Content::text(source.clone(), "spatial payload").unwrap();
        let handle = ContentHandleId::generate();
        let capability = ContentCapability::issue(
            &source_identity,
            handle.clone(),
            controller.clone(),
            content.clone(),
            120_000,
        )
        .unwrap();
        let transfer_id = TransferId::new();
        let command = SpatialCommand::issue(
            &controller_identity,
            controller.clone(),
            source.clone(),
            destination.clone(),
            handle,
            content.content_id.clone(),
            transfer_id,
            60_000,
            None,
        )
        .unwrap();
        let expires_at = command.claim.expires_at;
        let claim = TransferClaim::new_with_expiry(
            source.clone(),
            destination.clone(),
            content.content_id.clone(),
            transfer_id,
            TransferAction::Save,
            content.size,
            expires_at,
        );
        let authorization = TransferAuthorization::sign(&source_identity, claim).unwrap();
        Message::SpatialOffer {
            transfer_id,
            source_device: source,
            controller_device: controller,
            destination_device: destination,
            content,
            action: TransferAction::Save,
            route: TransferRoute::PhoneRelay,
            expires_at,
            authorization,
            capability,
            command,
        }
    }

    fn messages() -> Vec<Message> {
        let source = id("fw_source_01");
        let transfer_id = TransferId::new();
        let spatial = spatial_offer();
        let (capability, command) = match &spatial {
            Message::SpatialOffer {
                capability,
                command,
                ..
            } => (capability.clone(), command.clone()),
            _ => unreachable!(),
        };
        let request_id = uuid::Uuid::new_v4();
        vec![
            Message::Hello {
                device: device("fw_12345678"),
                nonce: "ab".repeat(32),
            },
            Message::DeviceInfo {
                device: device("fw_12345678"),
            },
            Message::Auth {
                device_id: source.clone(),
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
            Message::SpatialCaptureRequest {
                request_id,
                source_device: source.clone(),
                controller_device: id("fw_phone_001"),
            },
            Message::SpatialCaptureGrant {
                request_id,
                capability: capability.clone(),
            },
            Message::SpatialCaptureDeny {
                request_id,
                reason: "nothing is held".into(),
            },
            Message::SpatialTransferCommand {
                command: command.clone(),
                capability: capability.clone(),
            },
            Message::SpatialCommandAccepted {
                transfer_id,
                route: TransferRoute::Direct,
            },
            Message::SpatialCommandRejected {
                transfer_id,
                reason: "not authorized".into(),
            },
            spatial,
            Message::SpatialStatus {
                transfer_id,
                status: flowww_core::TransferState::Transferring,
                bytes_transferred: 4096,
                detail: Some("relaying".into()),
            },
            Message::SpatialCancel {
                transfer_id,
                reason: Some("user cancelled".into()),
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
