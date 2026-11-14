use std::time::{SystemTime, UNIX_EPOCH};

/// Milliseconds since the Unix epoch.
pub fn unix_millis() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_millis() as u64)
        .unwrap_or(0)
}

/// Whether an absolute millisecond deadline has passed.
pub fn is_expired(expires_at_ms: u64) -> bool {
    unix_millis() >= expires_at_ms
}

/// A short, human-shareable expiry description.
pub fn expiration_label(expires_at_ms: u64) -> String {
    let now = unix_millis();
    let remaining = expires_at_ms.saturating_sub(now);
    if remaining == 0 {
        "expired".into()
    } else {
        format!("{}s", remaining.div_ceil(1000))
    }
}
