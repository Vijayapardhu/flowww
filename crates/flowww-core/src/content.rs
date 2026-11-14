use serde::{Deserialize, Deserializer, Serialize, Serializer};
use std::fmt;

use crate::{ContentId, CoreError, DeviceId, MAX_INLINE_CONTENT};

/// SHA-256 digest serialized as lowercase hex.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct ContentHash(pub [u8; 32]);

impl ContentHash {
    pub fn from_slice(bytes: &[u8]) -> Result<Self, CoreError> {
        let array: [u8; 32] = bytes
            .try_into()
            .map_err(|_| CoreError::InvalidContent("hash must be 32 bytes".into()))?;
        Ok(Self(array))
    }

    pub fn to_hex(self) -> String {
        hex::encode(self.0)
    }

    pub fn parse(value: &str) -> Result<Self, CoreError> {
        let bytes = hex::decode(value)
            .map_err(|_| CoreError::InvalidContent("hash is not hexadecimal".into()))?;
        Self::from_slice(&bytes)
    }

    /// A short label that is safe to show in a dense UI.
    pub fn short(self) -> String {
        let hex = self.to_hex();
        format!("{}…{}", &hex[..8], &hex[hex.len() - 4..])
    }
}

impl fmt::Display for ContentHash {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.to_hex())
    }
}

impl Serialize for ContentHash {
    fn serialize<S: Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        serializer.serialize_str(&self.to_hex())
    }
}

impl<'de> Deserialize<'de> for ContentHash {
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        let value = String::deserialize(deserializer)?;
        Self::parse(&value).map_err(serde::de::Error::custom)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum ContentKind {
    Document,
    Image,
    Video,
    Audio,
    Url,
    Text,
    Other,
}

impl ContentKind {
    pub fn from_mime(mime: &str) -> Self {
        let mime = mime.to_ascii_lowercase();
        if mime == "text/uri-list" || mime == "text/x-uri" {
            Self::Url
        } else if mime.starts_with("image/") {
            Self::Image
        } else if mime.starts_with("video/") {
            Self::Video
        } else if mime.starts_with("audio/") {
            Self::Audio
        } else if mime.starts_with("text/")
            || mime.contains("pdf")
            || mime.contains("document")
            || mime.contains("sheet")
            || mime.contains("presentation")
            || mime.contains("json")
            || mime.contains("xml")
        {
            Self::Document
        } else {
            Self::Other
        }
    }

    pub fn as_str(self) -> &'static str {
        match self {
            Self::Document => "document",
            Self::Image => "image",
            Self::Video => "video",
            Self::Audio => "audio",
            Self::Url => "url",
            Self::Text => "text",
            Self::Other => "other",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum TransferAction {
    Open,
    Save,
    Preview,
}

impl TransferAction {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Open => "OPEN",
            Self::Save => "SAVE",
            Self::Preview => "PREVIEW",
        }
    }
}

/// Content offered for transfer.
///
/// For files, `sha256` identifies the bytes and `size` is exact. For inline
/// text and URLs the payload travels in `inline` (bounded) and no file content
/// is ever opened.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Content {
    pub content_id: ContentId,
    pub source_device: DeviceId,
    pub kind: ContentKind,
    pub name: String,
    pub mime_type: String,
    pub size: u64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub sha256: Option<ContentHash>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub source_reference: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub inline: Option<String>,
    pub created_at: u64,
}

impl Content {
    pub fn file(
        source_device: DeviceId,
        name: impl Into<String>,
        mime_type: impl Into<String>,
        size: u64,
        sha256: ContentHash,
        source_reference: impl Into<String>,
    ) -> Result<Self, CoreError> {
        let name = crate::sanitize_filename(&name.into());
        let mime_type = normalize_mime(&mime_type.into());
        if size > 0 && sha256.0 == [0u8; 32] {
            return Err(CoreError::InvalidContent("missing file hash".into()));
        }
        Ok(Self {
            content_id: ContentId::new(),
            source_device,
            kind: ContentKind::from_mime(&mime_type),
            name,
            mime_type,
            size,
            sha256: Some(sha256),
            source_reference: Some(source_reference.into()),
            inline: None,
            created_at: crate::unix_millis(),
        })
    }

    pub fn text(source_device: DeviceId, text: impl Into<String>) -> Result<Self, CoreError> {
        let text = text.into();
        validate_inline(&text)?;
        Ok(Self {
            content_id: ContentId::new(),
            source_device,
            kind: ContentKind::Text,
            name: "Text".into(),
            mime_type: "text/plain".into(),
            size: text.len() as u64,
            sha256: None,
            source_reference: None,
            inline: Some(text),
            created_at: crate::unix_millis(),
        })
    }

    pub fn url(source_device: DeviceId, url: impl Into<String>) -> Result<Self, CoreError> {
        let url = url.into();
        validate_inline(&url)?;
        if !(url.starts_with("https://") || url.starts_with("http://")) {
            return Err(CoreError::InvalidContent(
                "only http and https URLs can be shared".into(),
            ));
        }
        let host = url
            .split("://")
            .nth(1)
            .and_then(|rest| rest.split('/').next())
            .unwrap_or("Link")
            .to_string();
        Ok(Self {
            content_id: ContentId::new(),
            source_device,
            kind: ContentKind::Url,
            name: host,
            mime_type: "text/uri-list".into(),
            size: url.len() as u64,
            sha256: None,
            source_reference: None,
            inline: Some(url),
            created_at: crate::unix_millis(),
        })
    }

    pub fn is_inline(&self) -> bool {
        self.inline.is_some()
    }

    /// Remove the local source reference before the descriptor crosses a trust
    /// boundary. Peers must never learn a local filesystem path, and received
    /// paths are never used.
    pub fn strip_source_reference(&mut self) {
        self.source_reference = None;
    }

    /// A wire-safe clone with no local source reference.
    pub fn wire_safe(mut self) -> Self {
        self.strip_source_reference();
        self
    }

    /// True when the descriptor is safe to place on the wire.
    pub fn is_wire_safe(&self) -> bool {
        self.source_reference.is_none()
    }

    pub fn validate(&self) -> Result<(), CoreError> {
        if self.name.is_empty() || self.name.len() > 200 {
            return Err(CoreError::InvalidContent("invalid content name".into()));
        }
        if let Some(inline) = &self.inline {
            validate_inline(inline)?;
            if self.size != inline.len() as u64 {
                return Err(CoreError::InvalidContent(
                    "inline size does not match payload".into(),
                ));
            }
        } else if self.kind != ContentKind::Other && self.sha256.is_none() {
            return Err(CoreError::InvalidContent(
                "file content is missing a hash".into(),
            ));
        }
        Ok(())
    }
}

fn validate_inline(value: &str) -> Result<(), CoreError> {
    if value.is_empty() {
        return Err(CoreError::InvalidContent("content is empty".into()));
    }
    if value.len() > MAX_INLINE_CONTENT {
        return Err(CoreError::InvalidContent(format!(
            "inline content exceeds {} bytes",
            MAX_INLINE_CONTENT
        )));
    }
    Ok(())
}

fn normalize_mime(value: &str) -> String {
    let value = value.trim().to_ascii_lowercase();
    if value.is_empty() || value.len() > 128 || !value.contains('/') {
        mime_guess::from_path("file.bin")
            .first_or_octet_stream()
            .to_string()
    } else {
        value
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::DeviceId;

    fn device() -> DeviceId {
        DeviceId::new("fw_12345678").unwrap()
    }

    #[test]
    fn hashes_are_hex_round_trip() {
        let hash = ContentHash([7u8; 32]);
        let json = serde_json::to_string(&hash).unwrap();
        assert_eq!(json, format!("\"{}\"", "07".repeat(32)));
        assert_eq!(serde_json::from_str::<ContentHash>(&json).unwrap(), hash);
    }

    #[test]
    fn text_is_bounded() {
        assert!(Content::text(device(), "hello").is_ok());
        assert!(Content::text(device(), "x".repeat(MAX_INLINE_CONTENT + 1)).is_err());
    }

    #[test]
    fn urls_accept_http_and_https_only() {
        assert!(Content::url(device(), "https://example.com/a").is_ok());
        assert!(Content::url(device(), "javascript:alert(1)").is_err());
        assert!(Content::url(device(), "file:///etc/passwd").is_err());
    }

    #[test]
    fn file_names_are_sanitized() {
        let content = Content::file(
            device(),
            "../../report.pdf",
            "application/pdf",
            10,
            ContentHash([1u8; 32]),
            "local",
        )
        .unwrap();
        assert_eq!(content.name, "report.pdf");
    }
}
