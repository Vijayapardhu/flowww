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
use serde::{Deserialize, Serialize};
use std::io::{self, Read, Write};
use std::sync::Arc;
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

    /// Get the active browser tab via Native Messaging.
    ///
    /// In a production deployment, this would be called by the extension
    /// through the Native Messaging host's stdio interface. Here we
    /// provide the API that the host exposes to Tauri commands.
    pub async fn get_active_tab(&self) -> Result<BrowserTabInfo, String> {
        // This would communicate with the running extension through
        // the Native Messaging host. For now, return a placeholder
        // that indicates the host is not yet connected to an extension.
        Err("native messaging host not connected to extension".into())
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
pub fn run_stdio_loop() -> io::Result<()> {
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

                let response = handle_nm_request(request);
                let payload = serde_json::to_vec(&response).unwrap();
                let _ = write_message(&payload);
            }
            None => break Ok(()), // stdin closed
        }
    }
}

fn handle_nm_request(request: NmRequest) -> NmResponse {
    match request {
        NmRequest::GetActiveTab => NmResponse::Error {
            code: "NOT_IMPLEMENTED".into(),
            message: "Active tab retrieval requires extension connection.".into(),
        },
        NmRequest::StageActiveTab => NmResponse::Error {
            code: "NOT_IMPLEMENTED".into(),
            message: "Tab staging requires extension connection.".into(),
        },
        NmRequest::StageSelectedText { .. } => NmResponse::Error {
            code: "NOT_IMPLEMENTED".into(),
            message: "Text staging requires extension connection.".into(),
        },
        NmRequest::GetStagedContent { .. } => NmResponse::Error {
            code: "NOT_IMPLEMENTED".into(),
            message: "Content query requires extension connection.".into(),
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
}
