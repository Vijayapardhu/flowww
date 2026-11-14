use serde::{Deserialize, Serialize};
use std::fmt;
use uuid::Uuid;

use crate::CoreError;

/// Stable, locally generated device identifier.
///
/// An id is an identifier, never a credential. It is safe to advertise.
#[derive(Debug, Clone, PartialEq, Eq, Hash, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(transparent)]
pub struct DeviceId(String);

impl DeviceId {
    pub fn new(value: impl Into<String>) -> Result<Self, CoreError> {
        let value = value.into();
        let valid = (8..=64).contains(&value.len())
            && value.starts_with("fw_")
            && value
                .chars()
                .all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-');
        if !valid {
            return Err(CoreError::InvalidDeviceId);
        }
        Ok(Self(value))
    }

    /// Generate a random, non-sequential id.
    pub fn generate() -> Self {
        Self(format!("fw_{}", Uuid::new_v4().simple()))
    }

    pub fn as_str(&self) -> &str {
        &self.0
    }
}

impl fmt::Display for DeviceId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}

impl TryFrom<&str> for DeviceId {
    type Error = CoreError;
    fn try_from(value: &str) -> Result<Self, Self::Error> {
        Self::new(value)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(transparent)]
pub struct TransferId(Uuid);

impl TransferId {
    pub fn new() -> Self {
        Self(Uuid::new_v4())
    }

    /// Deterministic id for a logical file transfer.
    ///
    /// Re-offering the same bytes to the same device maps to the same session,
    /// which is what makes receiver-side resume safe across reconnects.
    pub fn for_content(source: &DeviceId, destination: &DeviceId, sha256: &str, size: u64) -> Self {
        let namespace = Uuid::new_v5(&Uuid::NAMESPACE_OID, b"flowww.transfer.v1");
        let key = format!(
            "{}:{}:{}:{}",
            source.as_str(),
            destination.as_str(),
            sha256,
            size
        );
        Self(Uuid::new_v5(&namespace, key.as_bytes()))
    }

    pub fn from_uuid(value: Uuid) -> Self {
        Self(value)
    }

    pub fn as_uuid(&self) -> Uuid {
        self.0
    }

    pub fn as_bytes(&self) -> &[u8; 16] {
        self.0.as_bytes()
    }
}

impl Default for TransferId {
    fn default() -> Self {
        Self::new()
    }
}

impl fmt::Display for TransferId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}", self.0)
    }
}

impl std::str::FromStr for TransferId {
    type Err = uuid::Error;
    fn from_str(value: &str) -> Result<Self, Self::Err> {
        Ok(Self(Uuid::parse_str(value)?))
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(transparent)]
pub struct ContentId(pub String);

impl ContentId {
    pub fn new() -> Self {
        Self(format!("content_{}", Uuid::new_v4().simple()))
    }
}

impl Default for ContentId {
    fn default() -> Self {
        Self::new()
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(transparent)]
pub struct SessionId(Uuid);

impl SessionId {
    pub fn new() -> Self {
        Self(Uuid::new_v4())
    }
}

impl Default for SessionId {
    fn default() -> Self {
        Self::new()
    }
}

impl fmt::Display for SessionId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}", self.0)
    }
}

impl std::str::FromStr for SessionId {
    type Err = uuid::Error;
    fn from_str(value: &str) -> Result<Self, Self::Err> {
        Ok(Self(Uuid::parse_str(value)?))
    }
}
