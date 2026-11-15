//! Typed error categories. Messages are sanitised — they never contain
//! private keys, binding material, tokens or absolute paths.

use flowww_node::NodeError;
use flowww_security::SecurityError;

/// Error categories surfaced to native code. Every variant carries a flat,
/// sanitised message; category alone is enough for UI decisions.
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum FlowwwError {
    #[error("configuration error: {detail}")]
    Config { detail: String },
    #[error("storage error: {detail}")]
    Storage { detail: String },
    #[error("cryptographic error: {detail}")]
    Crypto { detail: String },
    #[error("network error: {detail}")]
    Network { detail: String },
    #[error("transfer error: {detail}")]
    Transfer { detail: String },
    #[error("discovery error: {detail}")]
    Discovery { detail: String },
    #[error("protocol error: {detail}")]
    Protocol { detail: String },
    #[error("the device is not paired with this node: {detail}")]
    UnknownPeer { detail: String },
    #[error("the peer is not currently discoverable: {detail}")]
    PeerUnavailable { detail: String },
    #[error("the transfer offer was rejected: {detail}")]
    OfferRejected { detail: String },
    #[error("a pairing session is already active")]
    PairingBusy,
    #[error("there is no active pairing session")]
    NoPairing,
    #[error("pairing failed: {detail}")]
    PairingFailed { detail: String },
    #[error("the operation timed out")]
    Timeout,
    #[error("the peer closed the connection")]
    Closed,
    #[error("the peer sent an unexpected message: {detail}")]
    UnexpectedMessage { detail: String },
    #[error("the peer reported an error: {detail}")]
    PeerError { detail: String },
    #[error("the node is not running")]
    NotStarted,
    #[error("the node is already running")]
    AlreadyStarted,
    #[error("invalid input: {detail}")]
    InvalidInput { detail: String },
    #[error("an internal error occurred: {detail}")]
    Internal { detail: String },
}

/// Strip filesystem-looking absolute path tokens out of a message.
///
/// Upstream messages are path-free by construction today; this is defence in
/// depth so a future sloppy `io::Error` cannot smuggle a path into the UI.
pub(crate) fn sanitize(detail: impl std::fmt::Display) -> String {
    let raw = detail.to_string();
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
            E::Config(m) => FlowwwError::Config {
                detail: sanitize(m),
            },
            E::Storage(e) => FlowwwError::Storage {
                detail: sanitize(e),
            },
            E::Security(e) => {
                let message = sanitize(&e);
                match e {
                    SecurityError::BadPairingPayload(_)
                    | SecurityError::PairingExpired
                    | SecurityError::BadPairingToken
                    | SecurityError::PairingMismatch => FlowwwError::PairingFailed {
                        detail: message.clone(),
                    },
                    _ => FlowwwError::Crypto { detail: message },
                }
            }
            E::Network(e) => FlowwwError::Network {
                detail: sanitize(e),
            },
            E::Transfer(e) => {
                if matches!(e, flowww_transfer::TransferError::Cancelled) {
                    FlowwwError::Transfer {
                        detail: "the transfer was cancelled".into(),
                    }
                } else {
                    FlowwwError::Transfer {
                        detail: sanitize(e),
                    }
                }
            }
            E::Discovery(e) => FlowwwError::Discovery {
                detail: sanitize(e),
            },
            E::Spatial(e) => FlowwwError::Internal {
                detail: sanitize(e),
            },
            E::Core(e) => FlowwwError::Protocol {
                detail: sanitize(e),
            },
            E::Protocol(e) => FlowwwError::Protocol {
                detail: sanitize(e),
            },
            E::Io(e) => FlowwwError::Internal {
                detail: sanitize(e),
            },
            E::UnknownPeer(m) => FlowwwError::UnknownPeer { detail: m },
            E::PeerUnavailable(m) => FlowwwError::PeerUnavailable { detail: m },
            E::OfferRejected(m) => FlowwwError::OfferRejected {
                detail: sanitize(m),
            },
            E::NoSpatialTarget => FlowwwError::Internal {
                detail: "no spatial target".into(),
            },
            E::NothingHeld => FlowwwError::Internal {
                detail: "no content is held".into(),
            },
            E::PairingBusy => FlowwwError::PairingBusy,
            E::NoPairing => FlowwwError::NoPairing,
            E::PairingFailed(m) => FlowwwError::PairingFailed {
                detail: sanitize(m),
            },
            E::UnexpectedMessage(m) => FlowwwError::UnexpectedMessage {
                detail: m.to_string(),
            },
            E::PeerError(m) => FlowwwError::PeerError {
                detail: sanitize(m),
            },
            E::Timeout => FlowwwError::Timeout,
            E::Closed => FlowwwError::Closed,
            E::ContentUnavailable(m) => FlowwwError::OfferRejected {
                detail: sanitize(m),
            },
            E::MissingRecord(m) => FlowwwError::Internal {
                detail: sanitize(m),
            },
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
            FlowwwError::UnknownPeer { detail } => assert_eq!(detail, "fw_test"),
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
