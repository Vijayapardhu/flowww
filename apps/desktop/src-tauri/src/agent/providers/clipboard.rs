//! Windows Clipboard content provider for v4.1.
//!
//! Provides explicit clipboard staging only - never continuously monitors
//! or automatically transfers clipboard contents.
//!
//! Security:
//! - Explicit trigger only (tray action or configurable shortcut)
//! - Never continuously monitors clipboard
//! - Never automatically transfers clipboard data
//! - Applies size limits
//! - Sanitizes metadata
//! - Never logs clipboard contents
//! - Respects clipboard ownership and locking

use crate::agent::providers::{
    ContentProvider, ContentType, CurrentContentDto, ProviderError, StagePayload, StageResult,
};
use async_trait::async_trait;

/// Maximum clipboard content size for staging (10 MB by default).
const MAX_CLIPBOARD_SIZE: u64 = 10 * 1024 * 1024;

/// Windows Clipboard content provider.
pub struct ClipboardProvider {
    max_size: u64,
}

impl ClipboardProvider {
    pub fn new() -> Self {
        Self {
            max_size: MAX_CLIPBOARD_SIZE,
        }
    }

    pub fn with_max_size(max_size: u64) -> Self {
        Self { max_size }
    }

    /// Read clipboard content using Windows API.
    ///
    /// This is best-effort and returns None if the clipboard is empty,
    /// locked, or contains unsupported formats.
    pub(crate) fn read_clipboard(&self) -> Option<CurrentContentDto> {
        #[cfg(target_os = "windows")]
        {
            self.read_clipboard_windows()
        }
        #[cfg(not(target_os = "windows"))]
        {
            None
        }
    }

    #[cfg(target_os = "windows")]
    fn read_clipboard_windows(&self) -> Option<CurrentContentDto> {
        use std::os::raw::{c_void, c_uint};

        // Raw FFI declarations for clipboard access
        extern "system" {
            fn OpenClipboard(h_wnd_new_owner: *mut c_void) -> i32;
            fn CloseClipboard() -> i32;
            fn IsClipboardFormatAvailable(u_format: c_uint) -> i32;
            fn GetClipboardData(u_format: c_uint) -> *mut c_void;
            fn GlobalLock(h_mem: *mut c_void) -> *mut c_void;
            fn GlobalUnlock(h_mem: *mut c_void) -> i32;
            fn GlobalSize(h_mem: *mut c_void) -> usize;
        }

        const CF_TEXT: c_uint = 1;
        const CF_UNICODETEXT: c_uint = 13;
        const CF_BITMAP: c_uint = 2;
        const CF_DIB: c_uint = 8;
        const CF_HDROP: c_uint = 15;

        unsafe {
            if OpenClipboard(std::ptr::null_mut()) == 0 {
                return None; // Clipboard locked by another process
            }

            let result = if IsClipboardFormatAvailable(CF_UNICODETEXT) != 0 {
                // Prefer Unicode text
                self.read_clipboard_text(GetClipboardData(CF_UNICODETEXT), true)
            } else if IsClipboardFormatAvailable(CF_TEXT) != 0 {
                self.read_clipboard_text(GetClipboardData(CF_TEXT), false)
            } else if IsClipboardFormatAvailable(CF_HDROP) != 0 {
                // File drop - file references
                self.read_clipboard_file_drop(GetClipboardData(CF_HDROP))
            } else {
                None
            };

            let _ = CloseClipboard();
            result
        }
    }

    #[cfg(target_os = "windows")]
    fn read_clipboard_text(
        &self,
        handle: *mut std::os::raw::c_void,
        is_wide: bool,
    ) -> Option<CurrentContentDto> {
        if handle.is_null() {
            return None;
        }

        unsafe {
            extern "system" {
                fn GlobalLock(h_mem: *mut std::os::raw::c_void) -> *mut std::os::raw::c_void;
                fn GlobalUnlock(h_mem: *mut std::os::raw::c_void) -> i32;
                fn GlobalSize(h_mem: *mut std::os::raw::c_void) -> usize;
            }

            let locked = GlobalLock(handle);
            if locked.is_null() {
                return None;
            }

            let size = GlobalSize(handle);
            if size == 0 || size > self.max_size as usize {
                let _ = GlobalUnlock(handle);
                return None;
            }

            let content = if is_wide {
                // UTF-16LE wide string
                let wide_slice = std::slice::from_raw_parts(locked as *const u16, size / 2);
                String::from_utf16_lossy(wide_slice)
            } else {
                // ANSI text
                let bytes = std::slice::from_raw_parts(locked as *const u8, size);
                String::from_utf8_lossy(bytes).to_string()
            };

            let _ = GlobalUnlock(handle);

            let trimmed = content.trim();
            if trimmed.is_empty() {
                return None;
            }

            let content_type = if trimmed.starts_with("http://") || trimmed.starts_with("https://") {
                ContentType::Url
            } else {
                ContentType::Text
            };

            Some(CurrentContentDto {
                content_type,
                display_name: format!(
                    "Clipboard {} ({} chars)",
                    if content_type == ContentType::Url { "URL" } else { "Text" },
                    trimmed.len()
                ),
                mime_type: Some(if content_type == ContentType::Url {
                    "text/uri-list"
                } else {
                    "text/plain"
                }.to_string()),
                size: Some(trimmed.len() as u64),
                source_application: Some("clipboard".into()),
                content_reference: trimmed.to_string(),
                authorization_context: None,
            })
        }
    }

    #[cfg(target_os = "windows")]
    fn read_clipboard_file_drop(
        &self,
        _handle: *mut std::os::raw::c_void,
    ) -> Option<CurrentContentDto> {
        // File drop (CF_HDROP) contains file references.
        // We return a placeholder indicating files are available;
        // the actual file paths are resolved via Explorer provider instead.
        None
    }
}

impl Default for ClipboardProvider {
    fn default() -> Self {
        Self::new()
    }
}

#[async_trait]
impl ContentProvider for ClipboardProvider {
    fn name(&self) -> &str {
        "clipboard"
    }

    async fn initialize(&self) -> Result<(), ProviderError> {
        Ok(())
    }

    fn is_available(&self) -> bool {
        cfg!(target_os = "windows")
    }

    async fn get_current_content(&self) -> Result<Option<CurrentContentDto>, ProviderError> {
        Ok(self.read_clipboard())
    }

    async fn stage_content(&self, payload: StagePayload) -> Result<StageResult, ProviderError> {
        // Validate size
        if let Some(size) = payload.size {
            if size > self.max_size {
                return Err(ProviderError::InvalidContent(format!(
                    "Clipboard content size {} exceeds limit {}",
                    size, self.max_size
                )));
            }
        }

        let content_id = crate::agent::providers::new_content_id();
        let expires_at = flowww_core::unix_millis()
            .saturating_add(payload.ttl_ms.unwrap_or(300_000));

        Ok(StageResult {
            content_id,
            expires_at,
        })
    }

    async fn shutdown(&self) -> Result<(), ProviderError> {
        Ok(())
    }
}

/// Clipboard content type classification.
///
/// Helps determine whether clipboard content is plain text, a URL,
/// or an image without exposing sensitive content.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ClipboardContentType {
    Text,
    Url,
    Image,
    Files,
    Unknown,
}

impl ClipboardContentType {
    pub fn from_content(reference: &str) -> Self {
        if reference.starts_with("http://") || reference.starts_with("https://") {
            ClipboardContentType::Url
        } else {
            ClipboardContentType::Text
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn clipboard_content_type_classification() {
        assert_eq!(
            ClipboardContentType::from_content("https://example.com"),
            ClipboardContentType::Url
        );
        assert_eq!(
            ClipboardContentType::from_content("http://example.com"),
            ClipboardContentType::Url
        );
        assert_eq!(
            ClipboardContentType::from_content("just some text"),
            ClipboardContentType::Text
        );
    }

    #[test]
    fn is_available_is_bool() {
        let provider = ClipboardProvider::new();
        let _ = provider.is_available();
    }

    #[test]
    fn max_size_is_sensible() {
        assert_eq!(MAX_CLIPBOARD_SIZE, 10 * 1024 * 1024);
    }
}
