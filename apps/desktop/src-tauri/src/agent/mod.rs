//! Agent module: Windows background foundation for v4.0.2.
//!
//! Provides the user-session background agent lifecycle, system tray,
//! secure local IPC, content provider abstraction, foreground application
//! detection, and integration with the existing flowww-node::Node.

pub mod api;
pub mod config;
pub mod default_launcher;
pub mod foreground;
pub mod ipc;
pub mod lifecycle;
pub mod native_messaging;
pub mod providers;
pub mod startup;
pub mod tray;
pub mod windows_content_source;
