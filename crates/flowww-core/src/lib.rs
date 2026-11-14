//! Shared domain model for flowww.
//!
//! This crate deliberately contains no networking, storage or platform code. It is
//! the vocabulary used by every other crate: devices, content, transfers and the
//! small state machines that keep the rest of the system honest.

pub mod content;
pub mod device;
pub mod error;
pub mod ids;
pub mod time;
pub mod transfer;

pub use content::{Content, ContentHash, ContentKind, TransferAction};
pub use device::{Capability, Device, DeviceInfo, PairingState, Platform};
pub use error::{CoreError, CoreResult};
pub use ids::{ContentHandleId, ContentId, DeviceId, SessionId, TransferId};
pub use time::{is_expired, unix_millis};
pub use transfer::{TransferRoute, TransferState};

/// Wire protocol identity. Every message carries this literal.
pub const PROTOCOL_NAME: &str = "FLW";
/// Initial protocol version.
pub const PROTOCOL_VERSION: &str = "FLW/1.0";
/// DNS-SD service type used for local discovery.
pub const DISCOVERY_SERVICE_TYPE: &str = "_flowww._tcp.local.";
/// Default chunk size for file transfers (1 MiB).
pub const DEFAULT_CHUNK_SIZE: u32 = 1024 * 1024;
/// Hard ceiling for a single binary chunk accepted from the network.
pub const MAX_CHUNK_SIZE: u32 = 4 * 1024 * 1024;
/// Hard ceiling for a control message.
pub const MAX_CONTROL_FRAME: usize = 256 * 1024;
/// Maximum length of an inline text or URL transfer.
pub const MAX_INLINE_CONTENT: usize = 32 * 1024;

/// Sanitize an untrusted file name.
///
/// The receiver must never join a network-supplied name directly to a directory.
/// This keeps only the final component, removes control characters and Windows
/// reserved names, and bounds the result to 180 characters.
pub fn sanitize_filename(input: &str) -> String {
    let base = input
        .rsplit(['/', '\\'])
        .next()
        .unwrap_or("file")
        .trim()
        .trim_matches('.');

    let mut cleaned: String = base
        .chars()
        .map(|c| {
            if c.is_control() || matches!(c, '<' | '>' | ':' | '"' | '|' | '?' | '*') {
                '_'
            } else {
                c
            }
        })
        .collect();

    if cleaned.is_empty() {
        cleaned.push_str("file");
    }

    let stem_upper = cleaned
        .split('.')
        .next()
        .unwrap_or_default()
        .to_ascii_uppercase();

    const RESERVED: [&str; 22] = [
        "CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8",
        "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
    ];
    if RESERVED.contains(&stem_upper.as_str()) {
        cleaned.insert(0, '_');
    }

    let mut out = String::with_capacity(cleaned.len());
    for (count, ch) in cleaned.chars().enumerate() {
        if count >= 180 {
            break;
        }
        out.push(ch);
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn filename_strips_paths_and_reserved_names() {
        assert_eq!(sanitize_filename("../../etc/passwd"), "passwd");
        assert_eq!(sanitize_filename(r"C:\Users\me\report.pdf"), "report.pdf");
        assert_eq!(sanitize_filename("CON.txt"), "_CON.txt");
        assert_eq!(sanitize_filename("what?.txt"), "what_.txt");
        assert_eq!(sanitize_filename("   "), "file");
        assert!(sanitize_filename(&"a".repeat(500)).len() <= 180);
    }
}
