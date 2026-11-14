//! Agent lifecycle management for the Windows background agent.
//!
//! Orchestrates startup, shutdown, single-instance enforcement, and
//! integration with the existing flowww-node::Node.

use crate::agent::config::AgentConfig;
use crate::agent::foreground;
use crate::agent::ipc::IpcServer;
use crate::agent::providers::{ProviderRegistry, StagedStore};
use flowww_node::Node;
use serde::{Deserialize, Serialize};
use std::fmt;
use std::net::SocketAddr;
use std::sync::{Arc, Mutex};
use tokio::sync::watch;

/// Lifecycle state of the agent.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum AgentState {
    Stopped,
    Starting,
    Running,
    Paused,
    Stopping,
}

impl fmt::Display for AgentState {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Stopped => write!(f, "stopped"),
            Self::Starting => write!(f, "starting"),
            Self::Running => write!(f, "running"),
            Self::Paused => write!(f, "paused"),
            Self::Stopping => write!(f, "stopping"),
        }
    }
}

/// Result of lifecycle operations.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LifecycleInfo {
    pub state: AgentState,
    pub node_initialized: bool,
    pub ipc_addr: Option<SocketAddr>,
    pub auto_start: bool,
    pub single_instance_held: bool,
}

/// Single-instance lock backed by an OS-level named mutex on Windows.
///
/// A unique, application-specific mutex name prevents a second flowww
/// agent process from initializing a duplicate Node or IPC server.
/// The mutex is released automatically when the lock is dropped.
#[derive(Debug)]
pub struct SingleInstanceLock {
    #[cfg(target_os = "windows")]
    handle: Option<isize>,
    pub acquired: bool,
}

#[cfg(target_os = "windows")]
mod ffi {
    use std::os::raw::c_void;

    extern "system" {
        pub fn CreateMutexW(
            lp_mutex_attributes: *mut c_void,
            b_initial_owner: i32,
            lp_name: *const u16,
        ) -> *mut c_void;
        pub fn ReleaseMutex(h_mutex: *mut c_void) -> i32;
        pub fn CloseHandle(h_object: *mut c_void) -> i32;
        pub fn WaitForSingleObject(h_handle: *mut c_void, dw_milliseconds: u32) -> u32;
    }

    pub const WAIT_OBJECT_0: u32 = 0x00000000;
    pub const WAIT_TIMEOUT: u32 = 0x00000080;
    pub const INVALID_HANDLE_VALUE: *mut c_void = -1isize as *mut c_void;
}

impl SingleInstanceLock {
    /// Unique application-specific mutex name.
    const MUTEX_NAME: &str = "Local\\FlowwwDesktopAgentSingleInstance";

    /// Attempt to acquire the OS-level named mutex.
    ///
    /// Returns a lock with `acquired = true` if this is the first instance.
    /// Returns `acquired = false` if another instance already holds the mutex.
    pub fn try_acquire() -> Self {
        #[cfg(target_os = "windows")]
        {
            unsafe {
                let wide: Vec<u16> = format!("{}\0", Self::MUTEX_NAME)
                    .encode_utf16()
                    .collect();
                let handle = ffi::CreateMutexW(
                    std::ptr::null_mut(),
                    1,
                    wide.as_ptr() as *const u16,
                );
                if handle.is_null() || handle == ffi::INVALID_HANDLE_VALUE {
                    return Self {
                        handle: None,
                        acquired: false,
                    };
                }
                let wait = ffi::WaitForSingleObject(handle, 0);
                if wait == ffi::WAIT_TIMEOUT {
                    // Another instance holds the mutex.
                    let _ = ffi::CloseHandle(handle);
                    return Self {
                        handle: None,
                        acquired: false,
                    };
                }
                if wait != ffi::WAIT_OBJECT_0 {
                    let _ = ffi::CloseHandle(handle);
                    return Self {
                        handle: None,
                        acquired: false,
                    };
                }
                Self {
                    handle: Some(handle as isize),
                    acquired: true,
                }
            }
        }
        #[cfg(not(target_os = "windows"))]
        {
            Self {
                acquired: true,
            }
        }
    }
}

impl Drop for SingleInstanceLock {
    fn drop(&mut self) {
        #[cfg(target_os = "windows")]
        {
            if let Some(handle) = self.handle {
                unsafe {
                    let _ = ffi::ReleaseMutex(handle as *mut std::os::raw::c_void);
                    let _ = ffi::CloseHandle(handle as *mut std::os::raw::c_void);
                }
            }
        }
    }
}

// HANDLE is a process-wide OS handle; it is safe to move across threads.
unsafe impl Send for SingleInstanceLock {}
unsafe impl Sync for SingleInstanceLock {}

/// The agent lifecycle handle.
pub struct AgentLifecycle {
    state: Mutex<AgentState>,
    node: Mutex<Option<Node>>,
    ipc: Mutex<Option<IpcServer>>,
    ipc_addr: Mutex<Option<SocketAddr>>,
    config: AgentConfig,
    single_instance: Option<SingleInstanceLock>,
    shutdown_tx: watch::Sender<bool>,
    shutdown_rx: watch::Receiver<bool>,
}

impl AgentLifecycle {
    pub fn new(config: AgentConfig) -> Self {
        let (tx, rx) = watch::channel(false);
        Self {
            state: Mutex::new(AgentState::Stopped),
            node: Mutex::new(None),
            ipc: Mutex::new(None),
            ipc_addr: Mutex::new(None),
            config,
            single_instance: None,
            shutdown_tx: tx,
            shutdown_rx: rx,
        }
    }

    /// Start the agent: enforce single-instance, init node, start IPC.
    pub async fn start(&mut self) -> Result<LifecycleInfo, String> {
        *self.state.lock().unwrap() = AgentState::Starting;

        // Single-instance enforcement via OS-level named mutex.
        let lock = SingleInstanceLock::try_acquire();
        if !lock.acquired {
            *self.state.lock().unwrap() = AgentState::Stopped;
            return Err("another flowww agent instance is already running".into());
        }
        self.single_instance = Some(lock);

        // Initialize the existing Rust node.
        let node = Node::start(flowww_node::NodeConfig::default())
            .await
            .map_err(|e| format!("node start failed: {}", e))?;
        let _listen_port = node.listen_port();
        *self.node.lock().unwrap() = Some(node);

        // Start the IPC server on loopback.
        let providers = Arc::new(ProviderRegistry::new());
        let staged = Arc::new(StagedStore::new());
        let ipc_config = crate::agent::ipc::IpcConfig::default();
        let ipc_server = IpcServer::new(ipc_config, providers.clone(), staged.clone());
        let addr = ipc_server.start().await.map_err(|e| format!("ipc start failed: {}", e))?;
        *self.ipc.lock().unwrap() = Some(ipc_server);
        *self.ipc_addr.lock().unwrap() = Some(addr);

        *self.state.lock().unwrap() = AgentState::Running;
        foreground::init_available();

        Ok(LifecycleInfo {
            state: AgentState::Running,
            node_initialized: true,
            ipc_addr: Some(addr),
            auto_start: self.config.auto_start,
            single_instance_held: true,
        })
    }

    /// Stop the agent: stop IPC, shutdown node, release single-instance.
    pub async fn stop(&mut self) -> Result<(), String> {
        *self.state.lock().unwrap() = AgentState::Stopping;

        let _ = self.shutdown_tx.send(true);

        // Scope the lock so it's dropped before awaiting.
        let ipc_server = {
            let mut guard = self.ipc.lock().unwrap();
            guard.take()
        };
        if let Some(ipc) = ipc_server {
            ipc.stop().await;
        }

        let node = {
            let mut guard = self.node.lock().unwrap();
            guard.take()
        };
        if let Some(node) = node {
            node.shutdown();
        }

        self.single_instance = None;
        *self.state.lock().unwrap() = AgentState::Stopped;
        Ok(())
    }

    /// Pause new transfers without shutting down the node.
    pub fn pause(&mut self) {
        *self.state.lock().unwrap() = AgentState::Paused;
    }

    /// Resume transfers.
    pub fn resume(&mut self) {
        *self.state.lock().unwrap() = AgentState::Running;
    }

    pub fn state(&self) -> AgentState {
        *self.state.lock().unwrap()
    }

    pub fn node(&self) -> Option<Node> {
        self.node.lock().unwrap().clone()
    }

    pub fn config(&self) -> &AgentConfig {
        &self.config
    }

    pub fn is_paused(&self) -> bool {
        matches!(self.state(), AgentState::Paused)
    }

    pub fn shutdown_receiver(&mut self) -> watch::Receiver<bool> {
        self.shutdown_rx.clone()
    }

    pub fn shutdown_sender(&self) -> watch::Sender<bool> {
        self.shutdown_tx.clone()
    }
}

impl Default for AgentLifecycle {
    fn default() -> Self {
        Self::new(AgentConfig::default())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn single_instance_try_acquire_succeeds() {
        let lock = SingleInstanceLock::try_acquire();
        assert!(lock.acquired);
    }

    #[test]
    fn lifecycle_default_is_stopped() {
        let lifecycle = AgentLifecycle::default();
        assert_eq!(lifecycle.state(), AgentState::Stopped);
    }

    #[test]
    fn agent_state_display() {
        assert_eq!(AgentState::Stopped.to_string(), "stopped");
        assert_eq!(AgentState::Running.to_string(), "running");
        assert_eq!(AgentState::Paused.to_string(), "paused");
    }
}
