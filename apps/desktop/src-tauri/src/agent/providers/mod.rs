//! Content provider abstraction for v4.1.
//!
//! Defines the common content model, staged-content store with lifecycle
//! tracking, and provider registry. Individual providers (Browser, Explorer,
//! Clipboard) implement the ContentProvider trait.

pub mod clipboard;
pub mod explorer;

use async_trait::async_trait;
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

/// Content staging lifecycle state.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ContentLifecycleState {
    Created,
    Staged,
    Reserved,
    Transferred,
    Expired,
    Cancelled,
    Failed,
}

/// Information about current content available from a provider.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CurrentContentDto {
    pub content_type: ContentType,
    pub display_name: String,
    pub mime_type: Option<String>,
    pub size: Option<u64>,
    pub source_application: Option<String>,
    pub content_reference: String,
    pub authorization_context: Option<String>,
}

/// Payload for staging content from a provider.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StagePayload {
    pub content_type: ContentType,
    pub display_name: String,
    pub mime_type: Option<String>,
    pub size: Option<u64>,
    pub source_application: Option<String>,
    pub content_reference: String,
    pub authorization_context: Option<String>,
    pub ttl_ms: Option<u64>,
}

/// Result of staging content.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StageResult {
    pub content_id: ContentId,
    pub expires_at: u64,
}

/// A staged content item with lifecycle tracking.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StagedContent {
    pub content_id: ContentId,
    pub content_type: ContentType,
    pub lifecycle_state: ContentLifecycleState,
    pub display_name: String,
    pub mime_type: Option<String>,
    pub size: Option<u64>,
    pub source_application: Option<String>,
    pub created_at: u64,
    pub expires_at: u64,
    /// Opaque reference used by the provider to resolve the actual bytes.
    /// Never an absolute filesystem path exposed to clients.
    pub content_reference: String,
    pub authorization_context: Option<String>,
}

impl StagedContent {
    pub fn is_expired(&self, now: u64) -> bool {
        now >= self.expires_at
    }

    pub fn is_terminal(&self) -> bool {
        matches!(
            self.lifecycle_state,
            ContentLifecycleState::Transferred
                | ContentLifecycleState::Expired
                | ContentLifecycleState::Cancelled
                | ContentLifecycleState::Failed
        )
    }

    pub fn can_transfer(&self) -> bool {
        self.lifecycle_state == ContentLifecycleState::Staged
            || self.lifecycle_state == ContentLifecycleState::Reserved
    }
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
    #[error("content not transferable: {0}")]
    NotTransferable(String),
}

/// A content provider stages content from a specific source.
#[async_trait]
pub trait ContentProvider: Send + Sync {
    fn name(&self) -> &str;
    /// Initialize the provider (called once at agent startup).
    async fn initialize(&self) -> Result<(), ProviderError>;
    /// Whether the provider is available on this system.
    fn is_available(&self) -> bool;
    /// Get the current content available from this provider (if any).
    async fn get_current_content(&self) -> Result<Option<CurrentContentDto>, ProviderError>;
    /// Stage content from this provider. Returns a StagedContent descriptor.
    async fn stage_content(&self, payload: StagePayload) -> Result<StageResult, ProviderError>;
    /// Shutdown the provider (called once at agent shutdown).
    async fn shutdown(&self) -> Result<(), ProviderError>;
}

/// In-memory staged-content store with lifecycle tracking and TTL-based expiry.
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

    pub fn get_with_state(
        &self,
        content_id: &ContentId,
    ) -> Option<(StagedContent, ContentLifecycleState)> {
        let now = flowww_core::unix_millis();
        self.items
            .read()
            .unwrap()
            .get(content_id)
            .filter(|item| !item.is_expired(now))
            .map(|item| (item.clone(), item.lifecycle_state))
    }

    pub fn remove(&self, content_id: &ContentId) -> Option<StagedContent> {
        self.items.write().unwrap().remove(content_id)
    }

    /// Transition a staged item to a new lifecycle state.
    pub fn transition_state(
        &self,
        content_id: &ContentId,
        new_state: ContentLifecycleState,
    ) -> Result<(), ProviderError> {
        let mut items = self.items.write().unwrap();
        let item = items
            .get_mut(content_id)
            .ok_or_else(|| ProviderError::ContentNotFound(content_id.0.clone()))?;

        if item.is_expired(flowww_core::unix_millis()) {
            return Err(ProviderError::ContentExpired(content_id.0.clone()));
        }

        item.lifecycle_state = new_state;
        Ok(())
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
            if let Some(item) = items.get_mut(&id) {
                item.lifecycle_state = ContentLifecycleState::Expired;
            }
        }
        count
    }

    pub fn len(&self) -> usize {
        self.items.read().unwrap().len()
    }

    pub fn is_empty(&self) -> bool {
        self.items.read().unwrap().is_empty()
    }

    pub fn count_by_state(&self, state: ContentLifecycleState) -> usize {
        self.items
            .read()
            .unwrap()
            .values()
            .filter(|i| i.lifecycle_state == state)
            .count()
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

    pub async fn initialize_all(&self) -> Result<(), ProviderError> {
        let providers: Vec<Arc<dyn ContentProvider>> = {
            let map = self.providers.read().unwrap();
            map.values().cloned().collect()
        };
        for provider in providers {
            provider.initialize().await?;
        }
        Ok(())
    }

    pub async fn shutdown_all(&self) -> Result<(), ProviderError> {
        let providers: Vec<Arc<dyn ContentProvider>> = {
            let map = self.providers.read().unwrap();
            map.values().cloned().collect()
        };
        for provider in providers {
            provider.shutdown().await?;
        }
        Ok(())
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
            lifecycle_state: ContentLifecycleState::Staged,
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
    fn can_transfer_only_from_staged_or_reserved() {
        let now = flowww_core::unix_millis();
        let staged = StagedContent {
            content_id: new_content_id(),
            content_type: ContentType::Text,
            lifecycle_state: ContentLifecycleState::Staged,
            display_name: "note.txt".into(),
            mime_type: None,
            size: None,
            source_application: None,
            created_at: now,
            expires_at: now.saturating_add(60_000),
            content_reference: "ref".into(),
            authorization_context: None,
        };
        assert!(staged.can_transfer());

        let reserved = StagedContent {
            content_id: new_content_id(),
            content_type: ContentType::Text,
            lifecycle_state: ContentLifecycleState::Reserved,
            display_name: "note.txt".into(),
            mime_type: None,
            size: None,
            source_application: None,
            created_at: now,
            expires_at: now.saturating_add(60_000),
            content_reference: "ref".into(),
            authorization_context: None,
        };
        assert!(reserved.can_transfer());

        let transferred = StagedContent {
            content_id: new_content_id(),
            content_type: ContentType::Text,
            lifecycle_state: ContentLifecycleState::Transferred,
            display_name: "note.txt".into(),
            mime_type: None,
            size: None,
            source_application: None,
            created_at: now,
            expires_at: now.saturating_add(60_000),
            content_reference: "ref".into(),
            authorization_context: None,
        };
        assert!(!transferred.can_transfer());
    }

    #[test]
    fn store_expire_marks_expired() {
        let store = StagedStore::new();
        let now = flowww_core::unix_millis();
        let item = StagedContent {
            content_id: new_content_id(),
            content_type: ContentType::Text,
            lifecycle_state: ContentLifecycleState::Staged,
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
        assert_eq!(store.count_by_state(ContentLifecycleState::Expired), 1);
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

    #[test]
    fn lifecycle_state_serialization() {
        let json = serde_json::to_string(&ContentLifecycleState::Staged).unwrap();
        assert_eq!(json, "\"STAGED\"");
        let json = serde_json::to_string(&ContentLifecycleState::Expired).unwrap();
        assert_eq!(json, "\"EXPIRED\"");
    }

    #[test]
    fn staged_content_serialization_excludes_inline_content() {
        let now = flowww_core::unix_millis();
        let item = StagedContent {
            content_id: new_content_id(),
            content_type: ContentType::File,
            lifecycle_state: ContentLifecycleState::Staged,
            display_name: "document.pdf".into(),
            mime_type: Some("application/pdf".into()),
            size: Some(1024),
            source_application: Some("Explorer".into()),
            created_at: now,
            expires_at: now.saturating_add(300_000),
            content_reference: "opaque-ref-123".into(),
            authorization_context: None,
        };

        let json = serde_json::to_string(&item).unwrap();
        assert!(!json.contains("inline_content"));
        assert!(!json.contains("file_bytes"));
        assert!(!json.contains("C:\\"));
        assert!(json.contains("opaque-ref-123"));
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

#[async_trait]
impl ContentProvider for TestProvider {
    fn name(&self) -> &str {
        self.name
    }

    async fn initialize(&self) -> Result<(), ProviderError> {
        Ok(())
    }

    fn is_available(&self) -> bool {
        true
    }

    async fn get_current_content(&self) -> Result<Option<CurrentContentDto>, ProviderError> {
        Ok(None)
    }

    async fn stage_content(&self, _payload: StagePayload) -> Result<StageResult, ProviderError> {
        Ok(StageResult {
            content_id: new_content_id(),
            expires_at: flowww_core::unix_millis().saturating_add(300_000),
        })
    }

    async fn shutdown(&self) -> Result<(), ProviderError> {
        Ok(())
    }
}
