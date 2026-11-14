use serde::{Deserialize, Serialize};
use std::fmt;

/// A transfer's lifecycle, exactly as specified by the FLW state model.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum TransferState {
    Created,
    Offered,
    Accepted,
    Connecting,
    Transferring,
    Paused,
    Verifying,
    Completed,
    Cancelled,
    Failed,
}

impl TransferState {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Created => "CREATED",
            Self::Offered => "OFFERED",
            Self::Accepted => "ACCEPTED",
            Self::Connecting => "CONNECTING",
            Self::Transferring => "TRANSFERRING",
            Self::Paused => "PAUSED",
            Self::Verifying => "VERIFYING",
            Self::Completed => "COMPLETED",
            Self::Cancelled => "CANCELLED",
            Self::Failed => "FAILED",
        }
    }

    pub fn is_terminal(self) -> bool {
        matches!(self, Self::Completed | Self::Cancelled | Self::Failed)
    }

    pub fn is_active(self) -> bool {
        matches!(
            self,
            Self::Offered
                | Self::Accepted
                | Self::Connecting
                | Self::Transferring
                | Self::Paused
                | Self::Verifying
        )
    }

    /// Validate a state transition.
    ///
    /// Resume is only legal from PAUSED or a recovered CONNECTING session; a
    /// terminal state can never silently move back to an active one.
    pub fn can_transition_to(self, next: Self) -> bool {
        use TransferState::*;
        match self {
            Created => matches!(next, Offered | Cancelled | Failed),
            Offered => matches!(next, Accepted | Cancelled | Failed),
            Accepted => matches!(next, Connecting | Transferring | Cancelled | Failed),
            Connecting => matches!(next, Transferring | Paused | Cancelled | Failed),
            Transferring => matches!(next, Paused | Verifying | Cancelled | Failed),
            Paused => matches!(next, Transferring | Cancelled | Failed),
            Verifying => matches!(next, Completed | Failed),
            Completed | Cancelled | Failed => false,
        }
    }
}

impl fmt::Display for TransferState {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.as_str())
    }
}

/// The three transfer routes described by the architecture.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum TransferRoute {
    /// Source to destination directly, controlled by the phone.
    Direct,
    /// Source to phone, phone to destination.
    PhoneRelay,
    /// Phone itself is the source.
    PhoneToDesktop,
}

impl TransferRoute {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Direct => "direct",
            Self::PhoneRelay => "phone_relay",
            Self::PhoneToDesktop => "phone_to_desktop",
        }
    }

    pub fn label(self) -> &'static str {
        match self {
            Self::Direct => "Direct",
            Self::PhoneRelay => "Phone relay",
            Self::PhoneToDesktop => "Phone to desktop",
        }
    }
}

impl fmt::Display for TransferRoute {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.as_str())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn happy_path_transitions() {
        use TransferState::*;
        let states = [
            Created,
            Offered,
            Accepted,
            Connecting,
            Transferring,
            Verifying,
            Completed,
        ];
        for pair in states.windows(2) {
            assert!(
                pair[0].can_transition_to(pair[1]),
                "{} -> {} should be legal",
                pair[0],
                pair[1]
            );
        }
    }

    #[test]
    fn terminal_states_are_final() {
        use TransferState::*;
        for terminal in [Completed, Cancelled, Failed] {
            assert!(terminal.is_terminal());
            assert!(!terminal.can_transition_to(Transferring));
        }
    }

    #[test]
    fn pause_and_resume_are_explicit() {
        use TransferState::*;
        assert!(Transferring.can_transition_to(Paused));
        assert!(Paused.can_transition_to(Transferring));
        assert!(!Offered.can_transition_to(Verifying));
    }
}
