use serde::{Deserialize, Deserializer, Serialize, Serializer};
use std::collections::BTreeSet;
use std::fmt;
use std::str::FromStr;

use crate::{CoreError, DeviceId, PROTOCOL_VERSION};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Default)]
pub enum Platform {
    Windows,
    Android,
    Web,
    Macos,
    Linux,
    #[default]
    Unknown,
}

impl Platform {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Windows => "windows",
            Self::Android => "android",
            Self::Web => "web",
            Self::Macos => "macos",
            Self::Linux => "linux",
            Self::Unknown => "unknown",
        }
    }

    pub fn current() -> Self {
        if cfg!(target_os = "windows") {
            Self::Windows
        } else if cfg!(target_os = "android") {
            Self::Android
        } else if cfg!(target_os = "macos") {
            Self::Macos
        } else if cfg!(target_os = "linux") {
            Self::Linux
        } else {
            Self::Unknown
        }
    }
}

impl fmt::Display for Platform {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.as_str())
    }
}

impl Serialize for Platform {
    fn serialize<S: Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        serializer.serialize_str(self.as_str())
    }
}

impl<'de> Deserialize<'de> for Platform {
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        let value = String::deserialize(deserializer)?;
        Ok(match value.to_ascii_lowercase().as_str() {
            "windows" => Self::Windows,
            "android" => Self::Android,
            "web" => Self::Web,
            "macos" => Self::Macos,
            "linux" => Self::Linux,
            _ => Self::Unknown,
        })
    }
}

/// A feature a device can offer. Unknown capabilities round-trip instead of
/// failing the whole message, which keeps FLW/1.x forward-compatible.
#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub enum Capability {
    FileSend,
    FileReceive,
    UrlSend,
    UrlReceive,
    TextSend,
    TextReceive,
    SpatialTarget,
    Other(String),
}

impl Capability {
    pub fn as_str(&self) -> &str {
        match self {
            Self::FileSend => "file_send",
            Self::FileReceive => "file_receive",
            Self::UrlSend => "url_send",
            Self::UrlReceive => "url_receive",
            Self::TextSend => "text_send",
            Self::TextReceive => "text_receive",
            Self::SpatialTarget => "spatial_target",
            Self::Other(value) => value,
        }
    }

    pub fn standard() -> BTreeSet<Self> {
        [
            Self::FileSend,
            Self::FileReceive,
            Self::UrlSend,
            Self::UrlReceive,
            Self::TextSend,
            Self::TextReceive,
            Self::SpatialTarget,
        ]
        .into_iter()
        .collect()
    }
}

impl fmt::Display for Capability {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.as_str())
    }
}

impl Serialize for Capability {
    fn serialize<S: Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        serializer.serialize_str(self.as_str())
    }
}

impl<'de> Deserialize<'de> for Capability {
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        let value = String::deserialize(deserializer)?;
        Ok(match value.as_str() {
            "file_send" => Self::FileSend,
            "file_receive" => Self::FileReceive,
            "url_send" => Self::UrlSend,
            "url_receive" => Self::UrlReceive,
            "text_send" => Self::TextSend,
            "text_receive" => Self::TextReceive,
            "spatial_target" => Self::SpatialTarget,
            other => Self::Other(other.to_string()),
        })
    }
}

impl FromStr for Capability {
    type Err = CoreError;
    fn from_str(value: &str) -> Result<Self, Self::Err> {
        if value.is_empty() || value.len() > 64 {
            return Err(CoreError::InvalidValue(value.into()));
        }
        let parsed = match value {
            "file_send" => Self::FileSend,
            "file_receive" => Self::FileReceive,
            "url_send" => Self::UrlSend,
            "url_receive" => Self::UrlReceive,
            "text_send" => Self::TextSend,
            "text_receive" => Self::TextReceive,
            "spatial_target" => Self::SpatialTarget,
            other => Self::Other(other.to_string()),
        };
        Ok(parsed)
    }
}

/// Public, non-secret information a device may advertise.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct DeviceInfo {
    pub device_id: DeviceId,
    pub device_name: String,
    pub platform: Platform,
    pub protocol_version: String,
    #[serde(default)]
    pub capabilities: BTreeSet<Capability>,
}

impl DeviceInfo {
    pub fn new(
        device_id: DeviceId,
        device_name: impl Into<String>,
        platform: Platform,
        capabilities: BTreeSet<Capability>,
    ) -> Result<Self, CoreError> {
        let device_name: String = device_name.into().trim().to_string();
        if device_name.is_empty() || device_name.chars().count() > 64 {
            return Err(CoreError::InvalidContent(
                "device name must be 1-64 characters".into(),
            ));
        }
        Ok(Self {
            device_id,
            device_name,
            platform,
            protocol_version: PROTOCOL_VERSION.to_string(),
            capabilities,
        })
    }

    pub fn has(&self, capability: &Capability) -> bool {
        self.capabilities.contains(capability)
    }
}

/// A persisted relationship with another device.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Device {
    pub info: DeviceInfo,
    pub public_key_fingerprint: String,
    pub trusted: bool,
    pub last_seen_ms: Option<u64>,
    pub created_at_ms: u64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum PairingState {
    Unpaired,
    Discovered,
    Pairing,
    AwaitingApproval,
    Paired,
    Connected,
    Disconnected,
    Revoked,
}

impl PairingState {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Unpaired => "UNPAIRED",
            Self::Discovered => "DISCOVERED",
            Self::Pairing => "PAIRING",
            Self::AwaitingApproval => "AWAITING_APPROVAL",
            Self::Paired => "PAIRED",
            Self::Connected => "CONNECTED",
            Self::Disconnected => "DISCONNECTED",
            Self::Revoked => "REVOKED",
        }
    }

    pub fn is_usable(self) -> bool {
        matches!(self, Self::Paired | Self::Connected | Self::Disconnected)
    }
}

impl fmt::Display for PairingState {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.as_str())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn unknown_capabilities_round_trip() {
        let parsed: Capability = "future_thing".parse().unwrap();
        assert_eq!(parsed, Capability::Other("future_thing".into()));
        let json = serde_json::to_string(&parsed).unwrap();
        assert_eq!(json, "\"future_thing\"");
    }

    #[test]
    fn device_name_is_validated() {
        let id = DeviceId::new("fw_12345678").unwrap();
        assert!(DeviceInfo::new(id.clone(), " ", Platform::Windows, BTreeSet::new()).is_err());
        assert!(DeviceInfo::new(id, "Laptop", Platform::Windows, BTreeSet::new()).is_ok());
    }

    #[test]
    fn platform_deserializes_unknown_as_unknown() {
        let platform: Platform = serde_json::from_str("\"ios\"").unwrap();
        assert_eq!(platform, Platform::Unknown);
    }
}
