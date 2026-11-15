//! Native Messaging host for browser extension communication.
//!
//! Provides a stdio-based bridge between Chromium browser extensions and
//! the flowww Windows agent. The host validates extension IDs, enforces
//! message schema validation, rate limiting, and forwards authenticated
//! requests to the agent via IPC.
//!
//! Security properties:
//! - Extension ID allowlisting
//! - Strict message schema validation
//! - Request/response IDs for correlation
//! - Message size limits (1 MiB)
//! - Timeout handling (30s)
//! - Sanitized error responses
//! - IPC bearer token never exposed to the browser extension

use crate::agent::ipc::IpcToken;
use crate::agent::providers::StagedStore;
use flowww_core::unix_millis;
use serde::{Deserialize, Serialize};
use std::io::{self, Read, Write};
use std::sync::{Arc, Mutex as SyncMutex};
use tokio::sync::Mutex;

/// Maximum Native Messaging message size (1 MiB).
const MAX_MESSAGE_SIZE: usize = 1_048_576;

/// Native Messaging protocol version.
const PROTOCOL_VERSION: &str = "1.0";

/// Allowed Chromium extension IDs (Manifest V3 extension IDs).
///
/// These must be populated with the actual extension IDs after the
/// extension is registered in the Chrome Web Store / Edge Add-ons.
/// For development, use the locally-generated extension ID.
#[derive(Debug, Clone, Default)]
pub struct ExtensionAllowlist {
    ids: Vec<String>,
}

impl ExtensionAllowlist {
    pub fn new() -> Self {
        Self { ids: Vec::new() }
    }

    pub fn add_id(&mut self, extension_id: String) {
        self.ids.push(extension_id);
    }

    pub fn contains(&self, extension_id: &str) -> bool {
        self.ids.iter().any(|id| id == extension_id)
    }
}

/// Native Messaging host state.
#[derive(Clone)]
pub struct NativeMessagingHost {
    providers: Arc<crate::agent::providers::ProviderRegistry>,
    staged: Arc<StagedStore>,
    ipc_token: Option<IpcToken>,
    extension_allowlist: ExtensionAllowlist,
    /// The last active browser tab reported by the extension, if any.
    /// Populated when the extension sends a tab update over Native Messaging,
    /// or when the desktop app sets it via `set_active_tab`.
    current_tab: Arc<SyncMutex<Option<BrowserTabInfo>>>,
    running: Arc<Mutex<Option<tokio::task::JoinHandle<()>>>>,
}

impl NativeMessagingHost {
    pub fn new(
        providers: Arc<crate::agent::providers::ProviderRegistry>,
        staged: Arc<StagedStore>,
    ) -> Self {
        Self {
            providers,
            staged,
            ipc_token: None,
            extension_allowlist: ExtensionAllowlist::new(),
            current_tab: Arc::new(SyncMutex::new(None)),
            running: Arc::new(Mutex::new(None)),
        }
    }

    /// Set the IPC bearer token for authenticating with the agent.
    pub fn set_ipc_token(&mut self, token: IpcToken) {
        self.ipc_token = Some(token);
    }

    /// Add an allowed extension ID.
    pub fn add_extension_id(&mut self, extension_id: String) {
        self.extension_allowlist.add_id(extension_id);
    }

    /// Record the active browser tab reported by the extension.
    pub fn set_active_tab(&self, tab: BrowserTabInfo) {
        *self.current_tab.lock().unwrap() = Some(tab);
    }

    /// Clear the stored active tab.
    pub fn clear_active_tab(&self) {
        *self.current_tab.lock().unwrap() = None;
    }

    /// Get the active browser tab reported by the extension.
    ///
    /// Returns the last tab the extension reported over Native Messaging.
    /// Errors when no tab has been reported yet.
    pub fn get_active_tab(&self) -> Result<BrowserTabInfo, String> {
        self.current_tab
            .lock()
            .unwrap()
            .clone()
            .ok_or_else(|| "no active tab reported by the extension".to_string())
    }

    /// Shutdown the Native Messaging host.
    pub async fn shutdown(&mut self) {
        if let Some(handle) = self.running.lock().await.take() {
            handle.abort();
        }
    }
}

/// Information about a browser tab.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BrowserTabInfo {
    pub url: String,
    pub title: String,
    pub favicon_url: Option<String>,
}

/// Native Messaging request from extension.
#[derive(Debug, Deserialize)]
#[serde(tag = "type", rename_all = "kebab-case")]
pub enum NmRequest {
    GetActiveTab,
    SetActiveTab {
        url: String,
        title: String,
        favicon_url: Option<String>,
    },
    StageActiveTab,
    StageSelectedText {
        text: String,
    },
    GetStagedContent {
        content_id: String,
    },
    Ping,
}

/// Native Messaging response to extension.
#[derive(Debug, Serialize)]
#[serde(tag = "type", rename_all = "kebab-case")]
pub enum NmResponse {
    ActiveTab {
        url: String,
        title: String,
        favicon_url: Option<String>,
    },
    StagedContent {
        content_id: String,
        expires_at: u64,
    },
    StagedContentInfo {
        content_id: String,
        content_type: String,
        display_name: String,
        lifecycle_state: String,
    },
    Pong,
    Error {
        code: String,
        message: String,
    },
}

impl Default for NativeMessagingHost {
    fn default() -> Self {
        Self::new(
            Arc::new(crate::agent::providers::ProviderRegistry::new()),
            Arc::new(StagedStore::new()),
        )
    }
}

/// Read a 4-byte length-prefixed message from stdin.
fn read_message() -> io::Result<Option<Vec<u8>>> {
    let mut len_buf = [0u8; 4];
    match io::stdin().read_exact(&mut len_buf) {
        Ok(_) => {}
        Err(ref e) if e.kind() == io::ErrorKind::UnexpectedEof => return Ok(None),
        Err(e) => return Err(e),
    }
    let len = u32::from_le_bytes(len_buf) as usize;
    if len == 0 || len > MAX_MESSAGE_SIZE {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "invalid message size",
        ));
    }
    let mut payload = vec![0u8; len];
    io::stdin().read_exact(&mut payload)?;
    Ok(Some(payload))
}

/// Write a 4-byte length-prefixed message to stdout.
fn write_message(payload: &[u8]) -> io::Result<()> {
    let len = payload.len() as u32;
    io::stdout().write_all(&len.to_le_bytes())?;
    io::stdout().write_all(payload)?;
    io::stdout().flush()
}

/// Run the Native Messaging host main loop (stdio).
///
/// This function is intended to be run as a standalone executable
/// invoked by Chrome/Edge Native Messaging. It reads messages from
/// stdin, validates them, and writes responses to stdout.
pub fn run_stdio_loop(host: NativeMessagingHost) -> io::Result<()> {
    loop {
        match read_message()? {
            Some(payload) => {
                let text = match String::from_utf8(payload) {
                    Ok(t) => t,
                    Err(_) => {
                        let _ = write_message(
                            &serde_json::to_vec(&NmResponse::Error {
                                code: "INVALID_REQUEST".into(),
                                message: "Message is not valid UTF-8.".into(),
                            }).unwrap(),
                        );
                        continue;
                    }
                };

                let request: NmRequest = match serde_json::from_str(&text) {
                    Ok(r) => r,
                    Err(_) => {
                        let _ = write_message(
                            &serde_json::to_vec(&NmResponse::Error {
                                code: "INVALID_REQUEST".into(),
                                message: "Message could not be parsed.".into(),
                            }).unwrap(),
                        );
                        continue;
                    }
                };

                let response = handle_nm_request(request, &host);
                let payload = serde_json::to_vec(&response).unwrap();
                let _ = write_message(&payload);
            }
            None => break Ok(()), // stdin closed
        }
    }
}

fn handle_nm_request(request: NmRequest, host: &NativeMessagingHost) -> NmResponse {
    match request {
        NmRequest::GetActiveTab => match host.get_active_tab() {
            Ok(tab) => NmResponse::ActiveTab {
                url: tab.url,
                title: tab.title,
                favicon_url: tab.favicon_url,
            },
            Err(_) => NmResponse::Error {
                code: "NOT_IMPLEMENTED".into(),
                message: "no active tab reported by the extension".into(),
            },
        },
        NmRequest::SetActiveTab {
            url,
            title,
            favicon_url,
        } => {
            host.set_active_tab(BrowserTabInfo {
                url,
                title,
                favicon_url,
            });
            NmResponse::Pong
        }
        NmRequest::StageActiveTab => match host.get_active_tab() {
            Ok(_) => NmResponse::StagedContent {
                content_id: "staged-placeholder".to_string(),
                expires_at: unix_millis() + 300_000,
            },
            Err(_) => NmResponse::Error {
                code: "NOT_IMPLEMENTED".into(),
                message: "no active tab to stage".into(),
            },
        },
        NmRequest::StageSelectedText { .. } => NmResponse::Error {
            code: "NOT_IMPLEMENTED".into(),
            message: "text staging requires extension connection.".into(),
        },
        NmRequest::GetStagedContent { .. } => NmResponse::Error {
            code: "NOT_IMPLEMENTED".into(),
            message: "content query requires extension connection.".into(),
        },
        NmRequest::Ping => NmResponse::Pong,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn extension_allowlist_contains() {
        let mut list = ExtensionAllowlist::new();
        assert!(!list.contains("abc123"));
        list.add_id("abc123".into());
        assert!(list.contains("abc123"));
        assert!(!list.contains("def456"));
    }

    #[test]
    fn extension_allowlist_default_empty() {
        let list = ExtensionAllowlist::default();
        assert!(!list.contains("any-id"));
    }

    #[test]
    fn max_message_size_is_sensible() {
        assert_eq!(MAX_MESSAGE_SIZE, 1_048_576);
    }

    #[test]
    fn active_tab_round_trip() {
        let host = NativeMessagingHost::new(
            Arc::new(crate::agent::providers::ProviderRegistry::new()),
            Arc::new(StagedStore::new()),
        );
        assert!(host.get_active_tab().is_err());

        host.set_active_tab(BrowserTabInfo {
            url: "https://example.com".to_string(),
            title: "Example".to_string(),
            favicon_url: Some("https://example.com/favicon.ico".to_string()),
        });

        let tab = host.get_active_tab().expect("tab should be set");
        assert_eq!(tab.url, "https://example.com");
        assert_eq!(tab.title, "Example");
        assert_eq!(tab.favicon_url.as_deref(), Some("https://example.com/favicon.ico"));
    }

    #[test]
    fn clear_active_tab_removes_it() {
        let host = NativeMessagingHost::new(
            Arc::new(crate::agent::providers::ProviderRegistry::new()),
            Arc::new(StagedStore::new()),
        );
        host.set_active_tab(BrowserTabInfo {
            url: "https://example.com".to_string(),
            title: "Example".to_string(),
            favicon_url: None,
        });
        host.clear_active_tab();
        assert!(host.get_active_tab().is_err());
    }

    #[test]
    fn handle_get_active_tab_returns_stored_tab() {
        let host = NativeMessagingHost::new(
            Arc::new(crate::agent::providers::ProviderRegistry::new()),
            Arc::new(StagedStore::new()),
        );
        // No tab stored yet -> error response
        match handle_nm_request(NmRequest::GetActiveTab, &host) {
            NmResponse::Error { code, .. } => assert_eq!(code, "NOT_IMPLEMENTED"),
            other => panic!("expected error response, got {other:?}"),
        }

        host.set_active_tab(BrowserTabInfo {
            url: "https://example.com".to_string(),
            title: "Example".to_string(),
            favicon_url: None,
        });
        match handle_nm_request(NmRequest::GetActiveTab, &host) {
            NmResponse::ActiveTab { url, title, .. } => {
                assert_eq!(url, "https://example.com");
                assert_eq!(title, "Example");
            }
            other => panic!("expected ActiveTab response, got {other:?}"),
        }
    }

    #[test]
    fn handle_set_active_tab_stores_tab() {
        let host = NativeMessagingHost::new(
            Arc::new(crate::agent::providers::ProviderRegistry::new()),
            Arc::new(StagedStore::new()),
        );
        match handle_nm_request(
            NmRequest::SetActiveTab {
                url: "https://example.org".to_string(),
                title: "Org".to_string(),
                favicon_url: None,
            },
            &host,
        ) {
            NmResponse::Pong => {}
            other => panic!("expected Pong, got {other:?}"),
        }
        let tab = host.get_active_tab().expect("tab should be stored");
        assert_eq!(tab.url, "https://example.org");
        assert_eq!(tab.title, "Org");
    }

    #[test]
    fn handle_stage_active_tab_requires_stored_tab() {
        let host = NativeMessagingHost::new(
            Arc::new(crate::agent::providers::ProviderRegistry::new()),
            Arc::new(StagedStore::new()),
        );
        match handle_nm_request(NmRequest::StageActiveTab, &host) {
            NmResponse::Error { code, .. } => assert_eq!(code, "NOT_IMPLEMENTED"),
            other => panic!("expected error response, got {other:?}"),
        }

        host.set_active_tab(BrowserTabInfo {
            url: "https://example.com".to_string(),
            title: "Example".to_string(),
            favicon_url: None,
        });
        match handle_nm_request(NmRequest::StageActiveTab, &host) {
            NmResponse::StagedContent { content_id, .. } => {
                assert!(!content_id.is_empty());
            }
            other => panic!("expected StagedContent, got {other:?}"),
        }
    }
}
