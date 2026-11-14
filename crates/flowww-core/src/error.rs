use thiserror::Error;

#[derive(Debug, Error)]
pub enum CoreError {
    #[error("device id must start with fw_ and contain 8-64 safe characters")]
    InvalidDeviceId,
    #[error("invalid content: {0}")]
    InvalidContent(String),
    #[error("invalid protocol value: {0}")]
    InvalidValue(String),
    #[error("operation is not valid in state {0}")]
    InvalidTransition(String),
    #[error("the session has expired")]
    Expired,
}

pub type CoreResult<T> = Result<T, CoreError>;
