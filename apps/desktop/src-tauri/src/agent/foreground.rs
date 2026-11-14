//! Foreground application detection for the background agent.
//!
//! Best-effort detection of the active foreground application, used to
//! identify the source application for content resolution.
//! Does not promise universal arbitrary-application selection extraction.
//!
//! Privacy: does not capture screen contents, does not record keystrokes,
//! does not continuously store window titles, and returns sanitized DTOs.

use serde::{Deserialize, Serialize};
use std::sync::atomic::{AtomicBool, Ordering};

/// Information about the foreground application.
///
/// All fields are optional and best-effort. Sensitive window information
/// is avoided where possible; only the process name and window title are
/// returned, never screen contents or keystroke data.
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct ForegroundApp {
    pub window_title: Option<String>,
    pub process_name: Option<String>,
    pub process_id: Option<u32>,
    pub class_name: Option<String>,
}

/// Detect the currently active foreground application.
///
/// Returns `None` when detection is unavailable or not implemented
/// for the current platform.
pub fn detect_foreground() -> Option<ForegroundApp> {
    #[cfg(target_os = "windows")]
    {
        detect_foreground_windows()
    }
    #[cfg(not(target_os = "windows"))]
    {
        None
    }
}

#[cfg(target_os = "windows")]
mod ffi {
    use std::os::raw::{c_int, c_void};

    extern "system" {
        pub fn GetForegroundWindow() -> *mut c_void;
        pub fn GetWindowTextW(hwnd: *mut c_void, lp_string: *mut u16, n_max_count: c_int) -> c_int;
        pub fn GetClassNameW(hwnd: *mut c_void, lp_class_name: *mut u16, n_max_count: c_int) -> c_int;
        pub fn GetWindowThreadProcessId(
            hwnd: *mut c_void,
            lpdw_process_id: *mut u32,
        ) -> u32;
        pub fn OpenProcess(
            dw_desired_access: u32,
            b_inherit_handle: i32,
            dw_process_id: u32,
        ) -> *mut c_void;
        pub fn QueryFullProcessImageNameW(
            h_process: *mut c_void,
            dw_flags: u32,
            lp_exe_name: *mut u16,
            lpcch_size: *mut u32,
        ) -> i32;
        pub fn CloseHandle(h_object: *mut c_void) -> i32;
    }

    pub const PROCESS_QUERY_LIMITED_INFORMATION: u32 = 0x00001000;
    pub const PROCESS_NAME_FORMAT: u32 = 0;
}

#[cfg(target_os = "windows")]
fn detect_foreground_windows() -> Option<ForegroundApp> {
    use crate::agent::foreground::ffi::*;

    unsafe {
        let hwnd = GetForegroundWindow();
        if hwnd.is_null() {
            return None;
        }

        let mut title_buf = [0u16; 512];
        let title_len = GetWindowTextW(hwnd, title_buf.as_mut_ptr(), title_buf.len() as i32);
        let title = if title_len > 0 {
            let t = String::from_utf16_lossy(&title_buf[..title_len as usize]);
            let trimmed = t.trim().to_string();
            if trimmed.is_empty() { None } else { Some(trimmed) }
        } else {
            None
        };

        let mut class_buf = [0u16; 256];
        let class_len = GetClassNameW(hwnd, class_buf.as_mut_ptr(), class_buf.len() as i32);
        let class = if class_len > 0 {
            let c = String::from_utf16_lossy(&class_buf[..class_len as usize]);
            let trimmed = c.trim().to_string();
            if trimmed.is_empty() { None } else { Some(trimmed) }
        } else {
            None
        };

        let mut process_id = 0u32;
        GetWindowThreadProcessId(hwnd, &mut process_id);

        if process_id == 0 {
            return None;
        }

        let process_name = get_process_name(process_id);

        Some(ForegroundApp {
            window_title: title,
            process_name,
            process_id: Some(process_id),
            class_name: class,
        })
    }
}

#[cfg(target_os = "windows")]
fn get_process_name(process_id: u32) -> Option<String> {
    use crate::agent::foreground::ffi::*;

    unsafe {
        let handle = OpenProcess(
            PROCESS_QUERY_LIMITED_INFORMATION,
            0,
            process_id,
        );
        if handle.is_null() {
            return None;
        }

        let mut buf = [0u16; 512];
        let mut size: u32 = buf.len() as u32;
        let result = QueryFullProcessImageNameW(
            handle,
            PROCESS_NAME_FORMAT,
            buf.as_mut_ptr(),
            &mut size,
        );
        let _ = CloseHandle(handle);

        if result != 0 && size > 0 {
            let full_path = String::from_utf16_lossy(&buf[..size as usize]);
            full_path.split('\\').last().unwrap_or(&full_path).to_string().into()
        } else {
            None
        }
    }
}

/// Whether foreground detection is available on this platform.
pub fn is_available() -> bool {
    cfg!(target_os = "windows")
}

/// Static availability flag.
static FOREGROUND_AVAILABLE: AtomicBool = AtomicBool::new(false);

pub fn init_available() {
    FOREGROUND_AVAILABLE.store(is_available(), Ordering::Relaxed);
}

pub fn available() -> bool {
    FOREGROUND_AVAILABLE.load(Ordering::Relaxed)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn foreground_app_default_is_sanitized() {
        let app = ForegroundApp::default();
        assert!(app.window_title.is_none());
        assert!(app.process_name.is_none());
        assert!(app.process_id.is_none());
        assert!(app.class_name.is_none());
    }

    #[test]
    fn is_available_returns_bool() {
        let _ = is_available();
    }

    #[test]
    fn available_and_init_available_do_not_panic() {
        init_available();
        let _ = available();
    }
}
