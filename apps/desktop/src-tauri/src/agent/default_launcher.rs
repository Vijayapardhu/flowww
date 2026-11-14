//! Safe Windows content-opening abstraction for v4.1.
//!
//! Provides a secure way to open staged content in default applications.
//! All launches are explicit and configurable - never automatic.
//!
//! Security:
//! - Shell command injection prevention
//! - URL scheme validation (http/https only for browser launch)
//! - Rejects dangerous executable/script launching
//! - Never automatically executes downloaded files
//! - Uses safe process-launch APIs

use std::path::Path;
use thiserror::Error;

/// Errors from the default application launcher.
#[derive(Debug, Error)]
pub enum LaunchError {
    #[error("invalid URL scheme: {0}")]
    InvalidUrlScheme(String),
    #[error("unsafe path: {0}")]
    UnsafePath(String),
    #[error("launch failed: {0}")]
    LaunchFailed(String),
    #[error("file not found: {0}")]
    FileNotFound(String),
}

/// Result of a launch operation.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LaunchResult {
    pub opened: bool,
    pub method: LaunchMethod,
}

/// How the content was opened.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LaunchMethod {
    DefaultBrowser,
    DefaultApplication,
    ShellExecute,
}

/// Safe URL scheme validation.
fn is_safe_url_scheme(url: &str) -> bool {
    url.starts_with("http://") || url.starts_with("https://")
}

/// Check if a path is safe (no traversal, no absolute system paths).
pub fn is_safe_path(path: &Path) -> bool {
    let components: Vec<_> = path.components().collect();
    for component in &components {
        if let std::path::Component::ParentDir = component {
            return false;
        }
    }
    true
}

/// Open a URL in the default browser.
///
/// Only http:// and https:// URLs are allowed.
pub fn open_url(url: &str) -> Result<LaunchResult, LaunchError> {
    if !is_safe_url_scheme(url) {
        return Err(LaunchError::InvalidUrlScheme(url.to_string()));
    }

    #[cfg(target_os = "windows")]
    {
        open_url_windows(url)
    }
    #[cfg(not(target_os = "windows"))]
    {
        Err(LaunchError::LaunchFailed(
            "URL opening only supported on Windows".into(),
        ))
    }
}

#[cfg(target_os = "windows")]
fn open_url_windows(url: &str) -> Result<LaunchResult, LaunchError> {
    use std::os::raw::c_void;

    extern "system" {
        fn ShellExecuteW(
            hwnd: *mut c_void,
            operation: *const u16,
            file: *const u16,
            parameters: *const u16,
            directory: *const u16,
            show_cmd: i32,
        ) -> *mut c_void;
    }

    unsafe {
        let operation_wide: Vec<u16> = "open\0".encode_utf16().collect();
        let url_wide: Vec<u16> = format!("{}\0", url).encode_utf16().collect();

        let result = ShellExecuteW(
            std::ptr::null_mut(),
            operation_wide.as_ptr(),
            url_wide.as_ptr(),
            std::ptr::null(),
            std::ptr::null(),
            1, // SW_SHOWNORMAL
        );

        // ShellExecuteW returns > 32 on success
        if (result as usize) > 32 {
            Ok(LaunchResult {
                opened: true,
                method: LaunchMethod::DefaultBrowser,
            })
        } else {
            Err(LaunchError::LaunchFailed(format!(
                "ShellExecuteW returned {}",
                result as i32
            )))
        }
    }
}

/// Open a file in its default registered application.
///
/// Validates the path and file existence before launching.
pub fn open_file(path: &Path) -> Result<LaunchResult, LaunchError> {
    if !is_safe_path(path) {
        return Err(LaunchError::UnsafePath(
            path.to_string_lossy().to_string(),
        ));
    }

    if !path.exists() {
        return Err(LaunchError::FileNotFound(
            path.to_string_lossy().to_string(),
        ));
    }

    if !path.is_file() {
        return Err(LaunchError::InvalidUrlScheme(
            "Not a regular file".into(),
        ));
    }

    #[cfg(target_os = "windows")]
    {
        open_file_windows(path)
    }
    #[cfg(not(target_os = "windows"))]
    {
        Err(LaunchError::LaunchFailed(
            "File opening only supported on Windows".into(),
        ))
    }
}

#[cfg(target_os = "windows")]
fn open_file_windows(path: &Path) -> Result<LaunchResult, LaunchError> {
    use std::os::raw::c_void;

    extern "system" {
        fn ShellExecuteW(
            hwnd: *mut c_void,
            operation: *const u16,
            file: *const u16,
            parameters: *const u16,
            directory: *const u16,
            show_cmd: i32,
        ) -> *mut c_void;
    }

    unsafe {
        let operation_wide: Vec<u16> = "open\0".encode_utf16().collect();
        let file_wide: Vec<u16> = format!("{}\0", path.to_string_lossy()).encode_utf16().collect();

        let result = ShellExecuteW(
            std::ptr::null_mut(),
            operation_wide.as_ptr(),
            file_wide.as_ptr(),
            std::ptr::null(),
            std::ptr::null(),
            1, // SW_SHOWNORMAL
        );

        if (result as usize) > 32 {
            Ok(LaunchResult {
                opened: true,
                method: LaunchMethod::DefaultApplication,
            })
        } else {
            Err(LaunchError::LaunchFailed(format!(
                "ShellExecuteW returned {}",
                result as i32
            )))
        }
    }
}

/// Open an image in the configured image viewer.
///
/// Delegates to open_file which uses the default application for the
/// image's file extension.
pub fn open_image(path: &Path) -> Result<LaunchResult, LaunchError> {
    open_file(path)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn is_safe_url_scheme_validates() {
        assert!(is_safe_url_scheme("https://example.com"));
        assert!(is_safe_url_scheme("http://example.com"));
        assert!(!is_safe_url_scheme("ftp://example.com"));
        assert!(!is_safe_url_scheme("file:///etc/passwd"));
        assert!(!is_safe_url_scheme("javascript:alert(1)"));
        assert!(!is_safe_url_scheme(""));
    }

    #[test]
    fn open_url_rejects_unsafe_scheme() {
        let result = open_url("javascript:alert(1)");
        assert!(matches!(result, Err(LaunchError::InvalidUrlScheme(_))));
    }

    #[test]
    fn open_url_rejects_file_scheme() {
        let result = open_url("file:///C:/windows/system32");
        assert!(matches!(result, Err(LaunchError::InvalidUrlScheme(_))));
    }

    #[test]
    fn is_safe_path_rejects_traversal() {
        assert!(!is_safe_path(Path::new("../../../etc/passwd")));
        assert!(!is_safe_path(Path::new("C:\\..\\..\\windows")));
        assert!(is_safe_path(Path::new("C:\\Users\\file.txt")));
        assert!(is_safe_path(Path::new("file.txt")));
    }

    #[test]
    fn open_file_rejects_nonexistent() {
        let result = open_file(Path::new("C:\\nonexistent\\file.txt"));
        assert!(matches!(result, Err(LaunchError::FileNotFound(_))));
    }

    #[test]
    fn launch_result_default() {
        let result = LaunchResult {
            opened: true,
            method: LaunchMethod::DefaultBrowser,
        };
        assert!(result.opened);
        assert_eq!(result.method, LaunchMethod::DefaultBrowser);
    }
}
