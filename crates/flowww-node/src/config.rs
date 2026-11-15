use std::net::SocketAddr;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::Duration;

use flowww_security::SecretStore;

use crate::content_source::ContentSource;

/// Everything the daemon needs to boot.
#[derive(Clone)]
pub struct NodeConfig {
    /// Private application data: identity, TLS certificate, database.
    pub data_dir: PathBuf,
    /// Where accepted files are written.
    pub download_dir: PathBuf,
    /// Name advertised to peers (1-64 characters).
    pub device_name: String,
    /// Address the trusted peer listener binds to. Port 0 picks a free port.
    pub listen_addr: SocketAddr,
    /// Advertise over mDNS and browse for peers.
    pub enable_discovery: bool,
    /// Accept incoming offers without asking the user.
    pub auto_accept: bool,
    /// Lifetime of a pairing QR code.
    pub pairing_ttl: Duration,
    /// Platform-protected secret store (Android Keystore, Windows DPAPI).
    /// `None` falls back to the platform default file/DPAPI store.
    pub secret_store: Option<Arc<dyn SecretStore>>,
    /// Platform content providers (Windows Explorer, clipboard, ...).
    ///
    /// `None` means this device can never satisfy a content request, and
    /// says so instead of pretending a provider exists.
    pub content_source: Option<Arc<dyn ContentSource>>,
}

impl std::fmt::Debug for NodeConfig {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("NodeConfig")
            .field("data_dir", &self.data_dir)
            .field("download_dir", &self.download_dir)
            .field("device_name", &self.device_name)
            .field("listen_addr", &self.listen_addr)
            .field("enable_discovery", &self.enable_discovery)
            .field("auto_accept", &self.auto_accept)
            .field("pairing_ttl", &self.pairing_ttl)
            .field(
                "secret_store",
                &self.secret_store.as_ref().map(|_| "<platform>"),
            )
            .field(
                "content_source",
                &self
                    .content_source
                    .as_ref()
                    .map(|source| source.providers()),
            )
            .finish()
    }
}

impl NodeConfig {
    pub fn new(data_dir: impl Into<PathBuf>) -> Self {
        let data_dir = data_dir.into();
        let download_dir = dirs::download_dir()
            .unwrap_or_else(|| data_dir.join("downloads"))
            .join("flowww");
        Self {
            data_dir,
            download_dir,
            device_name: default_device_name(),
            listen_addr: "0.0.0.0:0".parse().expect("valid address"),
            enable_discovery: true,
            auto_accept: false,
            pairing_ttl: Duration::from_secs(120),
            secret_store: None,
            content_source: None,
        }
    }

    /// Platform default data directory.
    pub fn default_path() -> PathBuf {
        dirs::data_local_dir()
            .or_else(dirs::data_dir)
            .unwrap_or_else(std::env::temp_dir)
            .join("flowww")
    }

    pub fn default_config() -> Self {
        Self::new(Self::default_path())
    }

    pub fn data_dir(&self) -> &Path {
        &self.data_dir
    }

    pub fn download_dir(&self) -> &Path {
        &self.download_dir
    }

    pub fn with_download_dir(mut self, download_dir: impl Into<PathBuf>) -> Self {
        self.download_dir = download_dir.into();
        self
    }

    pub fn with_device_name(mut self, name: impl Into<String>) -> Self {
        self.device_name = name.into();
        self
    }

    pub fn with_listen_addr(mut self, addr: SocketAddr) -> Self {
        self.listen_addr = addr;
        self
    }

    pub fn with_discovery(mut self, enabled: bool) -> Self {
        self.enable_discovery = enabled;
        self
    }

    pub fn with_auto_accept(mut self, enabled: bool) -> Self {
        self.auto_accept = enabled;
        self
    }

    /// Supply a platform-protected secret store (Android Keystore etc.).
    pub fn with_secret_store(mut self, store: Arc<dyn SecretStore>) -> Self {
        self.secret_store = Some(store);
        self
    }

    /// Supply the platform content providers this device can offer.
    pub fn with_content_source(mut self, source: Arc<dyn ContentSource>) -> Self {
        self.content_source = Some(source);
        self
    }
}

impl Default for NodeConfig {
    fn default() -> Self {
        Self::default_config()
    }
}

fn default_device_name() -> String {
    let raw = std::env::var("COMPUTERNAME")
        .or_else(|_| std::env::var("HOSTNAME"))
        .or_else(|_| std::env::var("USERNAME"))
        .unwrap_or_else(|_| "flowww device".to_string());
    let trimmed: String = raw.trim().chars().take(64).collect();
    if trimmed.is_empty() {
        "flowww device".to_string()
    } else {
        trimmed
    }
}
