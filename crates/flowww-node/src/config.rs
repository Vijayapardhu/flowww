use std::net::SocketAddr;
use std::path::{Path, PathBuf};
use std::time::Duration;

/// Everything the daemon needs to boot.
#[derive(Debug, Clone)]
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
