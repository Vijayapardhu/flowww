//! Integration tests for v4.0.3 Windows Runtime Hardening.
//!
//! These tests verify the agent modules work together correctly.
//! They do not require a running Windows desktop or physical device.

use flowww_desktop_lib::agent::api::{HandoffRequest, HandoffResponse, IpcError, StageRequest};
use flowww_desktop_lib::agent::config::AgentConfig;
use flowww_desktop_lib::agent::foreground::{self};
use flowww_desktop_lib::agent::ipc::{authenticate, IpcToken, SanitizedError};
use flowww_desktop_lib::agent::lifecycle::{AgentLifecycle, AgentState, SingleInstanceLock};
use flowww_desktop_lib::agent::providers::{
    ContentProvider, ProviderRegistry, StagedContent, StagedStore,
};
use flowww_desktop_lib::agent::providers::ProviderError;
use flowww_desktop_lib::agent::startup::{self, current_exe_path, validate_startup_entry};
use flowww_core::{ContentId, DeviceId};
use serde_json::json;

/// Helper: create a minimal test provider.
struct TestProvider {
    name: &'static str,
    staged: std::sync::Arc<std::sync::Mutex<Option<StagedContent>>>,
}

impl TestProvider {
    fn new(name: &'static str) -> std::sync::Arc<Self> {
        std::sync::Arc::new(Self {
            name,
            staged: std::sync::Arc::new(std::sync::Mutex::new(None)),
        })
    }
}

impl ContentProvider for TestProvider {
    fn name(&self) -> &str {
        self.name
    }

    fn stage(&self, _payload: serde_json::Value) -> Result<flowww_desktop_lib::agent::providers::StageResult, ProviderError> {
        Ok(flowww_desktop_lib::agent::providers::StageResult {
            content_id: ContentId::new(),
            expires_at: flowww_core::unix_millis().saturating_add(300_000),
        })
    }

    fn inspect(&self, _content_id: &ContentId) -> Result<Option<StagedContent>, ProviderError> {
        Ok(self.staged.lock().unwrap().clone())
    }

    fn resolve(&self, _content_id: &ContentId) -> Result<Vec<u8>, ProviderError> {
        Err(ProviderError::ContentNotFound("not staged".into()))
    }
}

// -------------------------------------------------------------------
// Single-instance tests
// -------------------------------------------------------------------

#[test]
fn single_instance_try_acquire_succeeds() {
    let lock = SingleInstanceLock::try_acquire();
    assert!(lock.acquired);
}

#[test]
fn single_instance_lock_drop_releases() {
    {
        let _lock = SingleInstanceLock::try_acquire();
    }
    // A new lock can be acquired after the previous one is dropped.
    let lock = SingleInstanceLock::try_acquire();
    assert!(lock.acquired);
}

// -------------------------------------------------------------------
// Lifecycle tests
// -------------------------------------------------------------------

#[test]
fn lifecycle_default_is_stopped() {
    let lifecycle = AgentLifecycle::default();
    assert_eq!(lifecycle.state(), AgentState::Stopped);
}

#[test]
fn agent_state_display() {
    assert_eq!(AgentState::Stopped.to_string(), "stopped");
    assert_eq!(AgentState::Starting.to_string(), "starting");
    assert_eq!(AgentState::Running.to_string(), "running");
    assert_eq!(AgentState::Paused.to_string(), "paused");
    assert_eq!(AgentState::Stopping.to_string(), "stopping");
}

#[test]
fn agent_config_defaults() {
    let config = AgentConfig::default();
    assert!(!config.auto_start);
    assert!(config.require_incoming_approval);
    assert!(!config.transfers_paused);
}

// -------------------------------------------------------------------
// IPC security tests
// -------------------------------------------------------------------

#[test]
fn ipc_token_generation_is_unique() {
    let t1 = IpcToken::generate();
    let t2 = IpcToken::generate();
    assert_ne!(t1.0, t2.0);
}

#[test]
fn ipc_authenticate_accepts_valid_bearer() {
    let token = IpcToken::generate();
    let auth_line = format!("Bearer {}", token.0);
    assert!(authenticate(&auth_line, &token));
}

#[test]
fn ipc_authenticate_rejects_invalid() {
    let token = IpcToken::generate();
    assert!(!authenticate("Bearer wrongtoken", &token));
    assert!(!authenticate("InvalidFormat", &token));
    assert!(!authenticate("", &token));
    assert!(!authenticate("Bearer ", &token));
    assert!(!authenticate(&format!("Bearer {}extra", token.0), &token));
}

#[test]
fn sanitized_error_does_not_leak_internal_details() {
    let err = SanitizedError::Internal;
    assert_eq!(err.code(), "INTERNAL");
    assert_eq!(err.message(), "An internal error occurred.");
    assert!(!err.message().contains("C:\\"));
    assert!(!err.message().contains("key"));
    assert!(!err.message().contains("token"));

    let err = SanitizedError::RateLimited;
    assert_eq!(err.code(), "RATE_LIMITED");
    assert_eq!(err.message(), "Too many requests. Please try again later.");

    let err = SanitizedError::ContentNotFound;
    assert_eq!(err.code(), "CONTENT_NOT_FOUND");
}

#[test]
fn handoff_response_error_serialization() {
    let resp = HandoffResponse::error(IpcError::Unauthenticated);
    let json = serde_json::to_string(&resp).unwrap();
    assert!(json.contains("unauthenticated"));
    assert!(!json.contains("private_key"));
    assert!(!json.contains("token"));
    assert!(!json.contains("path"));
}

// -------------------------------------------------------------------
// Content staging tests
// -------------------------------------------------------------------

#[test]
fn content_staging_and_expiry() {
    let store = StagedStore::new();
    let now = flowww_core::unix_millis();

    let item = StagedContent {
        content_id: ContentId::new(),
        content_type: flowww_desktop_lib::agent::providers::ContentType::Text,
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

    store.insert(item.clone());
    assert_eq!(store.len(), 1);
    assert!(store.get(&item.content_id).is_some());

    let removed = store.expire(now + 120_000);
    assert_eq!(removed, 1);
    assert_eq!(store.len(), 0);
}

#[test]
fn staged_content_serialization_excludes_inline_content() {
    let now = flowww_core::unix_millis();
    let item = StagedContent {
        content_id: ContentId::new(),
        content_type: flowww_desktop_lib::agent::providers::ContentType::File,
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

// -------------------------------------------------------------------
// Provider tests
// -------------------------------------------------------------------

#[test]
fn provider_registration_and_lookup() {
    let registry = ProviderRegistry::new();
    let provider = TestProvider::new("test-provider");
    let name = provider.name().to_string();
    registry.register(provider.clone()).unwrap();
    assert_eq!(registry.names(), vec![name.clone()]);
    assert!(registry.get(&name).is_some());
    assert!(registry.get("missing").is_none());
}

#[test]
fn provider_duplicate_registration_rejected() {
    let registry = ProviderRegistry::new();
    let provider = TestProvider::new("dup");
    registry.register(provider.clone()).unwrap();
    let result = registry.register(provider);
    assert!(matches!(
        result,
        Err(ProviderError::AlreadyRegistered(_))
    ));
}

// -------------------------------------------------------------------
// Foreground detection tests
// -------------------------------------------------------------------

#[test]
fn foreground_app_default_is_sanitized() {
    let app = flowww_desktop_lib::agent::foreground::ForegroundApp::default();
    assert!(app.window_title.is_none());
    assert!(app.process_name.is_none());
    assert!(app.process_id.is_none());
    assert!(app.class_name.is_none());
}

#[test]
fn foreground_availability_is_bool() {
    let _ = foreground::is_available();
    foreground::init_available();
    let _ = foreground::available();
}

// -------------------------------------------------------------------
// Startup behavior tests
// -------------------------------------------------------------------

#[test]
fn startup_constants_are_defined() {
    assert!(!startup::RUN_KEY.is_empty());
    assert!(!startup::VALUE_NAME.is_empty());
}

#[test]
fn startup_current_exe_path_returns_option() {
    let _ = current_exe_path();
}

#[test]
fn startup_validate_returns_bool() {
    let _ = validate_startup_entry();
}

#[cfg(target_os = "windows")]
#[test]
fn startup_parse_reg_query_line() {
    let line = r#"HKCU\Software\Microsoft\Windows\CurrentVersion\Run    Flowww    REG_SZ    C:\flowww\agent.exe"#;
    let parts: Vec<&str> = line.split_whitespace().collect();
    assert!(parts.len() >= 4);
    assert_eq!(parts[3..].join(" "), r#"C:\flowww\agent.exe"#);
}

// -------------------------------------------------------------------
// Handoff DTO serialization tests
// -------------------------------------------------------------------

#[test]
fn handoff_request_serialization() {
    let req = HandoffRequest::StageContent(StageRequest {
        provider: Some("test".into()),
        payload: json!({"type": "url", "value": "https://example.com"}),
    });
    let json = serde_json::to_string(&req).unwrap();
    assert!(json.contains("stage-content"));
    assert!(json.contains("test"));
}

#[test]
fn device_id_is_sanitized_in_dto() {
    let id = DeviceId::generate();
    let s = id.as_str();
    assert!(!s.contains("/"));
    assert!(!s.contains("\\"));
    assert!(!s.contains(":"));
}
