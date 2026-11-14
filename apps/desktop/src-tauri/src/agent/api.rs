//! Typed API contracts for the v4.0.2 background agent.
//!
//! All DTOs are sanitized: no private keys, signed capabilities, bearer
//! tokens, absolute filesystem paths, opaque handles, or inline content.

use flowww_core::{ContentId, DeviceId};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

/// A handoff request received over IPC or Tauri commands.
#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(tag = "kind", content = "payload", rename_all = "kebab-case")]
pub enum HandoffRequest {
    StageContent(StageRequest),
    InspectStagedContent { content_id: ContentId },
    InitiateSend {
        target_device_id: DeviceId,
        content_id: ContentId,
        action: TransferAction,
    },
    InitiateReceive {
        content_reference: String,
        source_device_id: DeviceId,
    },
    GetActiveTarget,
    SelectTarget { device_id: DeviceId },
    CancelHandoff { transfer_id: TransferId },
    GetHandoffStatus { transfer_id: TransferId },
    GetContentHistory,
}

/// A handoff response.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "kind", content = "payload", rename_all = "kebab-case")]
pub enum HandoffResponse {
    StageContent(StageResponse),
    InspectStagedContent(Option<StagedContentDto>),
    InitiateSend(TransferRecordDto),
    InitiateReceive(TransferRecordDto),
    GetActiveTarget(Option<DeviceRefDto>),
    SelectTarget(TargetSelectionDto),
    CancelHandoff(CancellationDto),
    GetHandoffStatus(TransferStatusDto),
    GetContentHistory(Vec<TransferRecordDto>),
    Error(IpcError),
}

impl HandoffResponse {
    pub fn error(code: IpcError) -> Self {
        HandoffResponse::Error(code)
    }

    /// Create an error response from a sanitized IPC error.
    pub fn from_sanitized(error: &crate::agent::ipc::SanitizedError) -> Self {
        let code = match error {
            crate::agent::ipc::SanitizedError::Unauthenticated => IpcError::Unauthenticated,
            crate::agent::ipc::SanitizedError::InvalidRequest => IpcError::InvalidRequest,
            crate::agent::ipc::SanitizedError::ContentNotFound => {
                IpcError::ContentNotFound(String::new())
            }
            crate::agent::ipc::SanitizedError::RateLimited => IpcError::Internal,
            crate::agent::ipc::SanitizedError::NotImplemented => IpcError::NotImplemented,
            crate::agent::ipc::SanitizedError::Internal => IpcError::Internal,
        };
        HandoffResponse::Error(code)
    }

    /// Create an error response from a provider error.
    pub(crate) fn from_provider_error(
        e: crate::agent::providers::ProviderError,
    ) -> Self {
        match e {
            crate::agent::providers::ProviderError::ContentNotFound(_) => {
                HandoffResponse::from_sanitized(&crate::agent::ipc::SanitizedError::ContentNotFound)
            }
            _ => HandoffResponse::from_sanitized(&crate::agent::ipc::SanitizedError::Internal),
        }
    }
}

/// Request to stage content.
#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct StageRequest {
    pub provider: Option<String>,
    pub payload: serde_json::Value,
}

/// Response to staging content.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StageResponse {
    pub content_id: ContentId,
    pub expires_at: u64,
}

/// Staged content DTO (sanitized, no inline content or paths).
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StagedContentDto {
    pub content_id: ContentId,
    pub content_type: String,
    pub display_name: String,
    pub mime_type: Option<String>,
    pub size: Option<u64>,
    pub source_application: Option<String>,
    pub created_at: u64,
    pub expires_at: u64,
    /// Opaque reference; not an absolute filesystem path.
    pub content_reference: String,
    pub authorization_context: Option<String>,
}

/// Transfer action.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum TransferAction {
    Open,
    Save,
    Preview,
}

/// A device reference for targeting (sanitized, no keys/paths).
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DeviceRefDto {
    pub device_id: DeviceId,
    pub name: String,
    pub platform: String,
    pub online: bool,
}

/// Target selection result.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TargetSelectionDto {
    pub device_id: DeviceId,
    pub selected_at: u64,
}

/// Cancellation result.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CancellationDto {
    pub accepted: bool,
    pub code: String,
    pub message: String,
}

/// Transfer status DTO.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TransferStatusDto {
    pub transfer_id: TransferId,
    pub status: String,
    pub bytes_transferred: u64,
    pub size: u64,
    pub error: Option<String>,
}

/// Transfer record DTO.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TransferRecordDto {
    pub transfer_id: TransferId,
    pub source_device_id: DeviceId,
    pub destination_device_id: DeviceId,
    pub filename: Option<String>,
    pub size: u64,
    pub status: String,
    pub route: String,
    pub direction: String,
    pub bytes_transferred: u64,
    pub error: Option<String>,
    pub created_at: u64,
    pub completed_at: Option<u64>,
}

/// IPC-level errors (sanitized, no internal details).
#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum IpcError {
    Unauthenticated,
    InvalidRequest,
    ContentNotFound(String),
    NotImplemented,
    ProviderUnavailable,
    Internal,
}

impl std::fmt::Display for IpcError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            IpcError::Unauthenticated => write!(f, "unauthenticated"),
            IpcError::InvalidRequest => write!(f, "invalid request"),
            IpcError::ContentNotFound(id) => write!(f, "content not found: {}", id),
            IpcError::NotImplemented => write!(f, "not implemented"),
            IpcError::ProviderUnavailable => write!(f, "provider unavailable"),
            IpcError::Internal => write!(f, "internal error"),
        }
    }
}

/// A UUID-based transfer ID alias used across DTOs.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub struct TransferId(Uuid);

impl TransferId {
    pub fn new() -> Self {
        Self(Uuid::new_v4())
    }
}

impl Default for TransferId {
    fn default() -> Self {
        Self::new()
    }
}

impl std::fmt::Display for TransferId {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}", self.0)
    }
}

impl From<flowww_core::TransferId> for TransferId {
    fn from(value: flowww_core::TransferId) -> Self {
        Self::from_uuid(value.as_uuid())
    }
}

impl From<TransferId> for flowww_core::TransferId {
    fn from(value: TransferId) -> Self {
        Self::from_uuid(value.0)
    }
}

impl TransferId {
    pub fn from_uuid(value: Uuid) -> Self {
        Self(value)
    }
    pub fn as_uuid(self) -> Uuid {
        self.0
    }
}
