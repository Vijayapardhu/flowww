//! Typed error categories. Messages are sanitised — they never contain
//! private keys, binding material, tokens or absolute paths.

use flowww_node::NodeError;
use flowww_security::SecurityError;

/// Error categories surfaced to native code. Every variant carries a flat,
/// sanitised message; category alone is enough for UI decisions.
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum FlowwwError {
    #[error("configuration error: {message}")]
    Config { message: String },
    #[error("storage error: {message}")]
    Storage { message: String },
    #[error("cryptographic error: {message}")]
    Crypto { message: String },
    #[error("network error: {message}")]
    Network { message: String },
    #[error("transfer error: {message}")]
    Transfer { message: String },
    #[error("discovery error: {message}")]
    Discovery { message: String },
    #[error("protocol error: {message}")]
    Protocol { message: String },
    #[error("the device is not paired with this node: {message}")]
    UnknownPeer { message: String },
    #[error("the peer is not currently discoverable: {message}")]
    PeerUnavailable { message: String },
    #[error("the transfer offer was rejected: {message}")]
    OfferRejected { message: String },
    #[error("a pairing session is already active")]
    PairingBusy,
    #[error("there is no active pairing session")]
    NoPairing,
    #[error("pairing failed: {message}")]
    PairingFailed { message: String },
    #[error("the operation timed out")]
    Timeout,
    #[error("the peer closed the connection")]
    Closed,
    #[error("the peer sent an unexpected message: {message}")]
    UnexpectedMessage { message: String },
    #[error("the peer reported an error: {message}")]
    PeerError { message: String },
    #[error("the node is not running")]
    NotStarted,
    #[error("the node is already running")]
    AlreadyStarted,
    #[error("invalid input: {message}")]
    InvalidInput { message: String },
    #[error("an internal error occurred: {message}")]
    Internal { message: String },
}

/// Strip filesystem-looking absolute path tokens out of a message.
///
/// Upstream messages are path-free by construction today; this is defence in
/// depth so a future sloppy `io::Error` cannot smuggle a path into the UI.
pub(crate) fn sanitize(message: impl std::fmt::Display) -> String {
    let raw = message.to_string();
    let mut out = String::with_capacity(raw.len());
    for token in raw.split_whitespace() {
        let trimmed = token.trim_matches(|c| c == '\'' || c == '"' || c == '(' || c == ')');
        let looks_absolute = trimmed.starts_with('/')
            || (trimmed.len() > 2
                && trimmed.as_bytes()[0].is_ascii_alphabetic()
                && trimmed.as_bytes()[1] == b':');
        if looks_absolute {
            let base = trimmed.rsplit(['/', '\\']).next().unwrap_or(trimmed);
            out.push_str(&token.replace(trimmed, &format!("<path>/{base}")));
        } else {
            out.push_str(token);
        }
        out.push(' ');
    }
    out.trim_end().to_string()
}

impl From<NodeError> for FlowwwError {
    fn from(error: NodeError) -> Self {
        use NodeError as E;
        match error {
            E::Config(m) => FlowwwError::Config { message: sanitize(m) },
            E::Storage(e) => FlowwwError::Storage { message: sanitize(e) },
            E::Security(e) => {
                let message = sanitize(&e);
                match e {
                    SecurityError::BadPairingPayload(_)
                    | SecurityError::PairingExpired
                    | SecurityError::BadPairingToken
                    | SecurityError::PairingMismatch => FlowwwError::PairingFailed { message },
                    _ => FlowwwError::Crypto { message },
                }
            }
            E::Network(e) => FlowwwError::Network { message: sanitize(e) },
            E::Transfer(e) => {
                if matches!(e, flowww_transfer::TransferError::Cancelled) {
                    FlowwwError::Transfer {
                        message: "the transfer was cancelled".into(),
                    }
                } else {
                    FlowwwError::Transfer { message: sanitize(e) }
                }
            }
            E::Discovery(e) => FlowwwError::Discovery { message: sanitize(e) },
            E::Spatial(e) => FlowwwError::Internal { message: sanitize(e) },
            E::Core(e) => FlowwwError::Protocol { message: sanitize(e) },
            E::Protocol(e) => FlowwwError::Protocol { message: sanitize(e) },
            E::Io(e) => FlowwwError::Internal { message: sanitize(e) },
            E::UnknownPeer(m) => FlowwwError::UnknownPeer { message: m },
            E::PeerUnavailable(m) => FlowwwError::PeerUnavailable { message: m },
            E::OfferRejected(m) => FlowwwError::OfferRejected { message: sanitize(m) },
            E::NoSpatialTarget => FlowwwError::Internal { message: "no spatial target".into() },
            E::NothingHeld => FlowwwError::Internal { message: "no content is held".into() },
            E::PairingBusy => FlowwwError::PairingBusy,
            E::NoPairing => FlowwwError::NoPairing,
            E::PairingFailed(m) => FlowwwError::PairingFailed { message: sanitize(m) },
            E::UnexpectedMessage(m) => FlowwwError::UnexpectedMessage { message: m.to_string() },
            E::PeerError(m) => FlowwwError::PeerError { message: sanitize(m) },
            E::Timeout => FlowwwError::Timeout,
            E::Closed => FlowwwError::Closed,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn error_mapping_timeout() {
        let err = FlowwwError::from(NodeError::Timeout);
        assert!(matches!(err, FlowwwError::Timeout));
    }

    #[test]
    fn error_mapping_pairing_busy() {
        let err = FlowwwError::from(NodeError::PairingBusy);
        assert!(matches!(err, FlowwwError::PairingBusy));
    }

    #[test]
    fn error_mapping_unknown_peer() {
        let err = FlowwwError::from(NodeError::UnknownPeer("fw_test".into()));
        match err {
            FlowwwError::UnknownPeer { message } => assert_eq!(message, "fw_test"),
            other => panic!("expected UnknownPeer, got {other:?}"),
        }
    }

    #[test]
    fn sanitize_strips_windows_paths() {
        let err = FlowwwError::from(NodeError::Io(std::io::Error::new(
            std::io::ErrorKind::NotFound,
            "cannot open C:\\Users\\phone\\files\\secret.bin for reading",
        )));
        let text = err.to_string();
        assert!(!text.contains("phone\\files"), "leaked path: {text}");
        assert!(text.contains("secret.bin"));
    }

    #[test]
    fn sanitize_strips_unix_paths() {
        let err = FlowwwError::from(NodeError::Config(
            "bad file /home/user/downloads/very-secret.txt given".into(),
        ));
        let text = err.to_string();
        assert!(!text.contains("home/user"));
    }
}
