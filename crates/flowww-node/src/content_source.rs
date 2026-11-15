//! The content-provider source side of the request path.
//!
//! A device can hold content that lives outside the transfer engine — a file
//! selected in Windows Explorer, the current clipboard entry, a browser
//! selection. The platform supplies those through a [`ContentSource`], and
//! the node offers them to a paired peer when that peer asks.
//!
//! The node stays authoritative: the source only describes *what* is
//! available and where its bytes live locally. Every authorisation, offer
//! validation and digest check stays in Rust.

use std::path::PathBuf;

use async_trait::async_trait;
use flowww_core::Content;

use crate::error::{NodeError, NodeResult};

/// Content a platform provider can offer, plus the local path of its bytes.
#[derive(Debug, Clone)]
pub struct ProviderContent {
    /// The descriptor that goes on the wire. It never carries a local path.
    pub content: Content,
    /// Where the bytes live on this machine. `None` for inline content.
    pub file_path: Option<PathBuf>,
}

impl ProviderContent {
    /// Inline content (text or a link) with no local file.
    pub fn inline(content: Content) -> Self {
        Self {
            content,
            file_path: None,
        }
    }

    /// A file whose bytes are read from `path` when the transfer runs.
    pub fn file(content: Content, path: PathBuf) -> Self {
        Self {
            content,
            file_path: Some(path),
        }
    }
}

/// A platform hook that answers "what content do you currently hold?".
///
/// Implementations must be conservative: returning `None` means "I have
/// nothing to offer", and the peer is told so. Returning content means the
/// user has it staged or selected, not that it may be sent silently — the
/// receiving device still approves the offer.
#[async_trait]
pub trait ContentSource: Send + Sync {
    /// The content currently available for `provider`, if any.
    ///
    /// `provider` is the family name the peer asked for, for example
    /// "explorer" or "clipboard". An unknown family yields `None`.
    async fn current_content(&self, provider: &str) -> NodeResult<Option<ProviderContent>>;

    /// The provider families this source can serve.
    fn providers(&self) -> Vec<String>;
}

/// A source that never has anything to offer.
///
/// Used when a platform has no content providers (for example the headless
/// daemon), so the request path still answers honestly instead of pretending
/// a provider exists.
#[derive(Debug, Default, Clone, Copy)]
pub struct NoContentSource;

#[async_trait]
impl ContentSource for NoContentSource {
    async fn current_content(&self, _provider: &str) -> NodeResult<Option<ProviderContent>> {
        Ok(None)
    }

    fn providers(&self) -> Vec<String> {
        Vec::new()
    }
}

/// Normalises a provider name: lower case, ASCII, no separators.
pub fn normalize_provider(provider: &str) -> Option<String> {
    let trimmed = provider.trim();
    if trimmed.is_empty() || trimmed.len() > 64 {
        return None;
    }
    let normalized: String = trimmed
        .chars()
        .map(|c| {
            if c.is_ascii_alphanumeric() || c == '-' || c == '_' {
                c.to_ascii_lowercase()
            } else {
                '-'
            }
        })
        .collect();
    if normalized.is_empty() {
        None
    } else {
        Some(normalized)
    }
}

/// Rejects a provider name that is not one the source can serve.
pub fn ensure_provider_known(
    source: &dyn ContentSource,
    provider: &str,
) -> Result<String, NodeError> {
    let normalized = normalize_provider(provider)
        .ok_or_else(|| NodeError::Config("invalid provider name".into()))?;
    let known = source
        .providers()
        .iter()
        .filter_map(|name| normalize_provider(name))
        .any(|name| name == normalized);
    if !known {
        return Err(NodeError::Config("unknown content provider".into()));
    }
    Ok(normalized)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn provider_names_are_normalised() {
        assert_eq!(normalize_provider("Explorer"), Some("explorer".into()));
        assert_eq!(normalize_provider(" Clipboard "), Some("clipboard".into()));
        assert_eq!(normalize_provider("clip board"), Some("clip-board".into()));
        assert_eq!(normalize_provider(""), None);
        assert_eq!(normalize_provider("   "), None);
        assert_eq!(normalize_provider(&"x".repeat(65)), None);
    }

    #[test]
    fn unknown_providers_are_rejected() {
        let source = NoContentSource;
        assert!(ensure_provider_known(&source, "explorer").is_err());
    }
}
