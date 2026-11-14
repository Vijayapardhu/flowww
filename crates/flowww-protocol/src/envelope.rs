use serde::{Deserialize, Serialize};
use std::fmt;
use thiserror::Error;
use uuid::Uuid;

use crate::Message;
use flowww_core::{unix_millis, MAX_CONTROL_FRAME, PROTOCOL_VERSION};

#[derive(Debug, Error)]
pub enum ProtocolError {
    #[error("protocol message exceeds the {0} byte limit")]
    TooLarge(usize),
    #[error("malformed protocol frame: {0}")]
    Malformed(String),
    #[error("unsupported protocol version: {0}")]
    UnsupportedVersion(String),
    #[error("invalid message: {0}")]
    Invalid(String),
}

pub type ProtocolResult<T> = Result<T, ProtocolError>;

/// A versioned control message.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Envelope {
    pub protocol: String,
    pub message_id: Uuid,
    pub sent_at: u64,
    #[serde(flatten)]
    pub message: Message,
}

impl Envelope {
    pub fn new(message: Message) -> Self {
        Self {
            protocol: PROTOCOL_VERSION.to_string(),
            message_id: Uuid::new_v4(),
            sent_at: unix_millis(),
            message,
        }
    }

    pub fn encode(&self) -> ProtocolResult<Vec<u8>> {
        self.validate()?;
        let bytes =
            serde_json::to_vec(self).map_err(|e| ProtocolError::Malformed(e.to_string()))?;
        if bytes.len() > MAX_CONTROL_FRAME {
            return Err(ProtocolError::TooLarge(MAX_CONTROL_FRAME));
        }
        Ok(bytes)
    }

    pub fn decode(bytes: &[u8]) -> ProtocolResult<Self> {
        if bytes.len() > MAX_CONTROL_FRAME {
            return Err(ProtocolError::TooLarge(MAX_CONTROL_FRAME));
        }
        let envelope: Self =
            serde_json::from_slice(bytes).map_err(|e| ProtocolError::Malformed(e.to_string()))?;
        envelope.validate()?;
        Ok(envelope)
    }

    pub fn validate(&self) -> ProtocolResult<()> {
        if !version_is_compatible(&self.protocol) {
            return Err(ProtocolError::UnsupportedVersion(self.protocol.clone()));
        }
        if self.message_id.is_nil() {
            return Err(ProtocolError::Invalid("nil message id".into()));
        }
        self.message.validate()
    }
}

/// FLW/1.x is compatible; a different major version is not.
pub fn version_is_compatible(value: &str) -> bool {
    let Some(rest) = value.strip_prefix("FLW/") else {
        return false;
    };
    rest.split('.').next() == Some("1")
}

impl fmt::Display for Envelope {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{} id={}", self.message.kind(), self.message_id)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use flowww_core::{Capability, DeviceId, DeviceInfo, Platform};
    use std::collections::BTreeSet;

    fn hello() -> Message {
        Message::Hello {
            device: DeviceInfo::new(
                DeviceId::new("fw_12345678").unwrap(),
                "Test",
                Platform::Windows,
                BTreeSet::<Capability>::new(),
            )
            .unwrap(),
            nonce: "ab".repeat(32),
        }
    }

    #[test]
    fn envelope_round_trip() {
        let envelope = Envelope::new(hello());
        let bytes = envelope.encode().unwrap();
        let decoded = Envelope::decode(&bytes).unwrap();
        assert_eq!(decoded.message, hello());
        assert_eq!(decoded.protocol, PROTOCOL_VERSION);
    }

    #[test]
    fn wrong_major_version_is_rejected() {
        let mut envelope = Envelope::new(hello());
        envelope.protocol = "FLW/2.0".into();
        assert!(matches!(
            envelope.encode(),
            Err(ProtocolError::UnsupportedVersion(_))
        ));
    }

    #[test]
    fn minor_versions_are_accepted() {
        assert!(version_is_compatible("FLW/1.9"));
        assert!(!version_is_compatible("FLW/2.0"));
        assert!(!version_is_compatible("nope"));
    }

    #[test]
    fn oversized_control_messages_are_rejected() {
        let bytes = vec![b' '; MAX_CONTROL_FRAME + 1];
        assert!(matches!(
            Envelope::decode(&bytes),
            Err(ProtocolError::TooLarge(_))
        ));
    }
}
