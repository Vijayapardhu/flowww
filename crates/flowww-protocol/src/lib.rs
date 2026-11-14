//! FLW/1.0 wire protocol.
//!
//! Control messages are bounded JSON envelopes; file data travels in binary
//! frames with a fixed header and a per-chunk SHA-256 digest. Nothing in this
//! crate performs I/O, which makes the format easy to fuzz and test.

pub mod envelope;
pub mod frame;
pub mod message;

pub use envelope::{Envelope, ProtocolError, ProtocolResult};
pub use frame::{
    Chunk, Frame, FrameHeader, FrameKind, FRAME_HEADER_LEN, FRAME_MAGIC, FRAME_VERSION,
    MAX_CHUNK_PAYLOAD,
};
pub use message::{AckStatus, ErrorCode, Message, MAX_ERROR_LENGTH, MAX_REJECT_REASON};
