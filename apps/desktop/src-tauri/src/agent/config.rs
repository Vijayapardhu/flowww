//! Agent configuration persistence.
//!
//! Stores v4.0.2 agent settings: auto-start opt-in, download directory,
//! incoming-transfer policy, pause/resume state, and diagnostics level.

use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};

/// Agent settings persisted to disk.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AgentConfig {
    /// Whether the agent should start at user sign-in.
    pub auto_start: bool,
    /// Directory where received files are written.
    pub download_dir: PathBuf,
    /// Incoming transfer policy: require explicit approval unless trusted.
    pub require_incoming_approval: bool,
    /// Whether new transfers are currently paused.
    pub transfers_paused: bool,
    /// Minimum diagnostics level.
    pub diagnostics_level: DiagnosticsLevel,
}

impl Default for AgentConfig {
    fn default() -> Self {
        Self {
            auto_start: false,
            download_dir: dirs::download_dir()
                .unwrap_or_else(std::env::temp_dir)
                .join("flowww"),
            require_incoming_approval: true,
            transfers_paused: false,
            diagnostics_level: DiagnosticsLevel::Minimal,
        }
    }
}

/// Diagnostics verbosity. Never logs sensitive content.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum DiagnosticsLevel {
    None,
    Minimal,
    Verbose,
}

impl Default for DiagnosticsLevel {
    fn default() -> Self {
        DiagnosticsLevel::Minimal
    }
}

/// Load agent config from disk, or return defaults.
pub fn load(path: impl AsRef<Path>) -> AgentConfig {
    let path = path.as_ref();
    if path.exists() {
        match std::fs::read_to_string(path) {
            Ok(content) => match serde_json::from_str(&content) {
                Ok(cfg) => return cfg,
                Err(e) => tracing::warn!("failed to parse agent config: {}", e),
            },
            Err(e) => tracing::warn!("failed to read agent config: {}", e),
        }
    }
    AgentConfig::default()
}

/// Save agent config to disk.
pub fn save(path: impl AsRef<Path>, config: &AgentConfig) -> std::io::Result<()> {
    let path = path.as_ref();
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    let content = serde_json::to_string_pretty(config)
        .map_err(|e| std::io::Error::new(std::io::ErrorKind::Other, e))?;
    std::fs::write(path, content)
}
