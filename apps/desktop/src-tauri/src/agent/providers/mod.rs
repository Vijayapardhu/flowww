//! Content provider abstraction for v4.0.2.
//!
//! Defines the common content model, staged-content store, and provider
//! registry. Individual providers (Explorer, browser, clipboard) are added
//! in later milestones; this milestone establishes the stable interface.

use flowww_core::{ContentId, DeviceId};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::{Arc, RwLock};
use thiserror::Error;

/// Content types supported by the staging layer.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ContentType {
    File,
    Url,
    Text,
    Image,
    Video,
    Document,
    MultipleFiles,
}

/// A staged content item, ready for transfer.
///
/// Large file contents are never placed in this DTO; only a reference is
/// stored. File bytes are resolved by the provider at transfer time.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StagedContent {
    pub content_id: ContentId,
    pub content_type: ContentType,
    pub display_name: String,
    pub mime_type: Option<String>,
    pub size: Option<u64>,
    pub source_application: Option<String>,
    pub created_at: u64,
    pub expires_at: u64,
    /// Opaque reference used by the provider to resolve the actual bytes.
    /// Never an absolute filesystem path exposed to clients.
    pub content_reference: String,
    /// Authorization context for the staged item.
    pub authorization_context: Option<String>,
}

impl StagedContent {
    pub fn is_expired(&self, now: u64) -> bool {
        now >= self.expires_at
    }
}

/// Result of staging content.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StageResult {
    pub content_id: ContentId,
    pub expires_at: u64,
}

/// Errors from the provider layer.
#[derive(Debug, Error)]
pub enum ProviderError {
    #[error("provider not found: {0}")]
    NotFound(String),
    #[error("provider already registered: {0}")]
    AlreadyRegistered(String),
    #[error("content not found: {0}")]
    ContentNotFound(String),
    #[error("content expired: {0}")]
    ContentExpired(String),
    #[error("invalid content: {0}")]
    InvalidContent(String),
    #[error("provider error: {0}")]
    Provider(String),
}

/// A content provider stages content from a specific source.
pub trait ContentProvider: Send + Sync {
    fn name(&self) -> &str;
    /// Stage content from this provider. Returns a StagedContent descriptor.
    fn stage(&self, payload: serde_json::Value) -> Result<StageResult, ProviderError>;
    /// Inspect a previously staged item.
    fn inspect(&self, content_id: &ContentId) -> Result<Option<StagedContent>, ProviderError>;
    /// Resolve the actual bytes/reference for transfer. Called at transfer time.
    fn resolve(&self, content_id: &ContentId) -> Result<Vec<u8>, ProviderError>;
}

/// In-memory staged-content store with TTL-based expiry.
#[derive(Debug, Default)]
pub struct StagedStore {
    items: RwLock<HashMap<ContentId, StagedContent>>,
}

impl StagedStore {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn insert(&self, item: StagedContent) {
        let id = item.content_id.clone();
        self.items.write().unwrap().insert(id, item);
    }

    pub fn get(&self, content_id: &ContentId) -> Option<StagedContent> {
        let now = flowww_core::unix_millis();
        self.items
            .read()
            .unwrap()
            .get(content_id)
            .filter(|item| !item.is_expired(now))
            .cloned()
    }

    pub fn remove(&self, content_id: &ContentId) -> Option<StagedContent> {
        self.items.write().unwrap().remove(content_id)
    }

    pub fn expire(&self, now: u64) -> usize {
        let mut items = self.items.write().unwrap();
        let expired: Vec<ContentId> = items
            .iter()
            .filter(|(_, item)| item.is_expired(now))
            .map(|(id, _)| id.clone())
            .collect();
        let count = expired.len();
        for id in expired {
            items.remove(&id);
        }
        count
    }

    pub fn len(&self) -> usize {
        self.items.read().unwrap().len()
    }

    pub fn is_empty(&self) -> bool {
        self.items.read().unwrap().is_empty()
    }
}

/// Registry of content providers.
#[derive(Default)]
pub struct ProviderRegistry {
    providers: RwLock<HashMap<String, Arc<dyn ContentProvider>>>,
}

impl ProviderRegistry {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn register(&self, provider: Arc<dyn ContentProvider>) -> Result<(), ProviderError> {
        let name = provider.name().to_string();
        let mut map = self.providers.write().unwrap();
        if map.contains_key(&name) {
            return Err(ProviderError::AlreadyRegistered(name));
        }
        map.insert(name, provider);
        Ok(())
    }

    pub fn get(&self, name: &str) -> Option<Arc<dyn ContentProvider>> {
        self.providers.read().unwrap().get(name).cloned()
    }

    pub fn names(&self) -> Vec<String> {
        self.providers.read().unwrap().keys().cloned().collect()
    }
}

/// Generate a new content ID.
pub fn new_content_id() -> ContentId {
    ContentId::new()
}

/// Generate a new device ID placeholder (used for local staging context).
pub fn new_device_id() -> DeviceId {
    DeviceId::generate()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn staged_content_expiry() {
        let now = flowww_core::unix_millis();
        let item = StagedContent {
            content_id: new_content_id(),
            content_type: ContentType::Text,
            display_name: "note.txt".into(),
            mime_type: Some("text/plain".into()),
            size: Some(5),
            source_application: Some("notepad".into()),
            created_at: now,
            expires_at: now.saturating_add(60_000),
            content_reference: "ref_1".into(),
            authorization_context: None,
        };
        assert!(!item.is_expired(now));
        assert!(item.is_expired(now + 120_000));
    }

    #[test]
    fn store_expire_removes_expired() {
        let store = StagedStore::new();
        let now = flowww_core::unix_millis();
        let item = StagedContent {
            content_id: new_content_id(),
            content_type: ContentType::Text,
            display_name: "note.txt".into(),
            mime_type: None,
            size: None,
            source_application: None,
            created_at: now,
            expires_at: now.saturating_sub(1),
            content_reference: "ref".into(),
            authorization_context: None,
        };
        store.insert(item.clone());
        assert_eq!(store.len(), 1);
        let removed = store.expire(now);
        assert_eq!(removed, 1);
        assert_eq!(store.len(), 0);
        assert!(store.get(&item.content_id).is_none());
    }

    #[test]
    fn provider_register_and_get() {
        let registry = ProviderRegistry::new();
        let provider = TestProvider::new();
        registry.register(Arc::new(provider)).unwrap();
        assert_eq!(registry.names(), vec!["test"]);
        assert!(registry.get("test").is_some());
        assert!(registry.get("missing").is_none());
    }

    #[test]
    fn provider_duplicate_rejected() {
        let registry = ProviderRegistry::new();
        let provider = Arc::new(TestProvider::new());
        registry.register(provider.clone()).unwrap();
        let result = registry.register(provider);
        assert!(matches!(result, Err(ProviderError::AlreadyRegistered(_))));
    }
}

struct TestProvider {
    name: &'static str,
}

impl TestProvider {
    fn new() -> Self {
        Self { name: "test" }
    }
}

impl ContentProvider for TestProvider {
    fn name(&self) -> &str {
        self.name
    }

    fn stage(&self, _payload: serde_json::Value) -> Result<StageResult, ProviderError> {
        Ok(StageResult {
            content_id: new_content_id(),
            expires_at: flowww_core::unix_millis().saturating_add(300_000),
        })
    }

    fn inspect(&self, _content_id: &ContentId) -> Result<Option<StagedContent>, ProviderError> {
        Ok(None)
    }

    fn resolve(&self, _content_id: &ContentId) -> Result<Vec<u8>, ProviderError> {
        Err(ProviderError::ContentNotFound("not staged".into()))
    }
}
