//! Plain-data DTOs crossing the FFI. All fields are display-safe on arrival:
//! names are sanitised by the core, no paths, no keys beyond public
//! fingerprints.

use std::str::FromStr;

use flowww_core::{Device, DeviceId, DeviceInfo, TransferId};
use flowww_node::{PairingView, PendingOfferView, PeerView};
use flowww_storage::TransferRecord;

/// Public device identity. Advertised metadata only — never keys.
#[derive(Debug, Clone, uniffi::Record)]
pub struct DeviceDto {
    pub device_id: String,
    pub device_name: String,
    pub platform: String,
    pub protocol_version: String,
    pub capabilities: Vec<String>,
}

/// A peer found on the local network. Not trusted until pairing succeeds.
#[derive(Debug, Clone, uniffi::Record)]
pub struct PeerDto {
    pub device_id: String,
    pub name: String,
    pub platform: String,
    pub address: Option<String>,
    pub port: u16,
    pub paired: bool,
    pub fingerprint: String,
    pub last_seen_ms: u64,
}

/// A device this node has paired with (from the local trust store).
#[derive(Debug, Clone, uniffi::Record)]
pub struct TrustedDeviceDto {
    pub device_id: String,
    pub device_name: String,
    pub platform: String,
    pub public_key_fingerprint: String,
    pub trusted: bool,
    pub last_seen_ms: Option<u64>,
    pub created_at_ms: u64,
}

/// Pairing material for the QR code shown by the host. `uri` is the whole
/// payload of the QR — treat it like the one-time pairing secret and never
/// log it.
#[derive(Debug, Clone, uniffi::Record)]
pub struct PairingViewDto {
    pub uri: String,
    pub endpoint: String,
    pub expires_at: u64,
}

/// An incoming offer waiting for the user's approval decision.
#[derive(Debug, Clone, uniffi::Record)]
pub struct PendingOfferDto {
    pub transfer_id: String,
    pub source_device_id: String,
    pub source_name: String,
    pub source_platform: String,
    pub content_name: String,
    pub mime_type: String,
    pub kind: String,
    pub size: u64,
    pub action: String,
    pub route: String,
    pub offered_at: u64,
}

/// One persisted transfer record (history entry or live progress snapshot).
#[derive(Debug, Clone, uniffi::Record)]
pub struct TransferRecordDto {
    pub id: String,
    pub direction: String,
    pub status: String,
    pub source_device_id: String,
    pub destination_device_id: String,
    pub filename: Option<String>,
    pub mime_type: Option<String>,
    pub kind: String,
    pub route: String,
    pub size: u64,
    pub sha256: Option<String>,
    pub bytes_transferred: u64,
    pub error: Option<String>,
    pub created_at: u64,
    pub completed_at: Option<u64>,
    pub is_terminal: bool,
    pub is_active: bool,
}

pub(crate) fn device_dto(device: &DeviceInfo) -> DeviceDto {
    DeviceDto {
        device_id: device.device_id.as_str().to_string(),
        device_name: device.device_name.clone(),
        platform: device.platform.as_str().to_string(),
        protocol_version: device.protocol_version.clone(),
        capabilities: device
            .capabilities
            .iter()
            .map(|capability| capability.as_str().to_string())
            .collect(),
    }
}

pub(crate) fn peer_dto(view: &PeerView) -> PeerDto {
    PeerDto {
        device_id: view.device_id.clone(),
        name: view.name.clone(),
        platform: view.platform.as_str().to_string(),
        address: view.address.clone(),
        port: view.port,
        paired: view.paired,
        fingerprint: view.fingerprint.clone(),
        last_seen_ms: view.last_seen_ms,
    }
}

pub(crate) fn trusted_device_dto(device: &Device) -> TrustedDeviceDto {
    TrustedDeviceDto {
        device_id: device.info.device_id.as_str().to_string(),
        device_name: device.info.device_name.clone(),
        platform: device.info.platform.as_str().to_string(),
        public_key_fingerprint: device.public_key_fingerprint.clone(),
        trusted: device.trusted,
        last_seen_ms: device.last_seen_ms,
        created_at_ms: device.created_at_ms,
    }
}

pub(crate) fn pairing_view_dto(view: &PairingView) -> PairingViewDto {
    PairingViewDto {
        uri: view.uri.clone(),
        endpoint: view.endpoint.clone(),
        expires_at: view.expires_at,
    }
}

pub(crate) fn offer_dto(view: &PendingOfferView) -> PendingOfferDto {
    PendingOfferDto {
        transfer_id: view.transfer_id.as_uuid().to_string(),
        source_device_id: view.source.device_id.as_str().to_string(),
        source_name: view.source.device_name.clone(),
        source_platform: view.source.platform.as_str().to_string(),
        content_name: view.content.name.clone(),
        mime_type: view.content.mime_type.clone(),
        kind: view.content.kind.as_str().to_string(),
        size: view.content.size,
        action: view.action.as_str().to_string(),
        route: view.route.as_str().to_string(),
        offered_at: view.content.created_at,
    }
}

pub(crate) fn record_dto(record: &TransferRecord) -> TransferRecordDto {
    TransferRecordDto {
        id: record.id.as_uuid().to_string(),
        direction: record.direction.as_str().to_string(),
        status: record.status.as_str().to_string(),
        source_device_id: record.source_device.as_str().to_string(),
        destination_device_id: record.destination_device.as_str().to_string(),
        filename: record.filename.clone(),
        mime_type: record.mime_type.clone(),
        kind: record.kind.clone(),
        route: record.route.as_str().to_string(),
        size: record.size,
        sha256: record.sha256.clone(),
        bytes_transferred: record.bytes_transferred,
        error: record.error.clone(),
        created_at: record.created_at,
        completed_at: record.completed_at,
        is_terminal: record.status.is_terminal(),
        is_active: record.status.is_active(),
    }
}

pub(crate) fn parse_device_id(raw: &str) -> Result<DeviceId, crate::FlowwwError> {
    DeviceId::try_from(raw).map_err(|_| crate::FlowwwError::InvalidInput {
        message: "the device id is not a valid Flowww device identifier".into(),
    })
}

pub(crate) fn parse_transfer_id(raw: &str) -> Result<TransferId, crate::FlowwwError> {
    TransferId::from_str(raw.trim()).map_err(|_| crate::FlowwwError::InvalidInput {
        message: "the transfer id is not a valid UUID".into(),
    })
}
