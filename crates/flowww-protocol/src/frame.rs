use bytes::Bytes;
use sha2::{Digest, Sha256};
use uuid::Uuid;

use crate::{Envelope, ProtocolError, ProtocolResult};
use flowww_core::{ContentHash, TransferId, MAX_CHUNK_SIZE, MAX_CONTROL_FRAME};

pub const FRAME_MAGIC: [u8; 4] = *b"FLW1";
pub const FRAME_VERSION: u8 = 1;
/// magic + version + kind + flags + reserved + transfer id + index + length + sha256
pub const FRAME_HEADER_LEN: usize = 68;
/// Same ceiling as `flowww_core::MAX_CHUNK_SIZE`, repeated for wire validation.
pub const MAX_CHUNK_PAYLOAD: usize = MAX_CHUNK_SIZE as usize;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum FrameKind {
    Control = 1,
    Chunk = 2,
}

impl FrameKind {
    pub fn from_byte(value: u8) -> ProtocolResult<Self> {
        match value {
            1 => Ok(Self::Control),
            2 => Ok(Self::Chunk),
            other => Err(ProtocolError::Malformed(format!(
                "unknown frame kind {other}"
            ))),
        }
    }
}

/// Fixed-size binary frame header.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct FrameHeader {
    pub kind: FrameKind,
    pub flags: u8,
    pub transfer_id: [u8; 16],
    pub chunk_index: u64,
    pub payload_len: u32,
    pub digest: ContentHash,
}

impl FrameHeader {
    pub fn for_control(payload_len: u32, digest: ContentHash) -> Self {
        Self {
            kind: FrameKind::Control,
            flags: 0,
            transfer_id: [0u8; 16],
            chunk_index: 0,
            payload_len,
            digest,
        }
    }

    pub fn for_chunk(
        transfer_id: TransferId,
        chunk_index: u64,
        payload_len: u32,
        digest: ContentHash,
    ) -> Self {
        Self {
            kind: FrameKind::Chunk,
            flags: 0,
            transfer_id: *transfer_id.as_bytes(),
            chunk_index,
            payload_len,
            digest,
        }
    }

    pub fn encode(self) -> [u8; FRAME_HEADER_LEN] {
        let mut out = [0u8; FRAME_HEADER_LEN];
        out[0..4].copy_from_slice(&FRAME_MAGIC);
        out[4] = FRAME_VERSION;
        out[5] = self.kind as u8;
        out[6] = self.flags;
        out[7] = 0;
        out[8..24].copy_from_slice(&self.transfer_id);
        out[24..32].copy_from_slice(&self.chunk_index.to_be_bytes());
        out[32..36].copy_from_slice(&self.payload_len.to_be_bytes());
        out[36..68].copy_from_slice(&self.digest.0);
        out
    }

    pub fn decode(bytes: &[u8]) -> ProtocolResult<Self> {
        if bytes.len() < FRAME_HEADER_LEN {
            return Err(ProtocolError::Malformed("short frame header".into()));
        }
        if bytes[0..4] != FRAME_MAGIC {
            return Err(ProtocolError::Malformed("bad frame magic".into()));
        }
        if bytes[4] != FRAME_VERSION {
            return Err(ProtocolError::Malformed(format!(
                "unsupported frame version {}",
                bytes[4]
            )));
        }
        let kind = FrameKind::from_byte(bytes[5])?;
        let flags = bytes[6];
        if flags != 0 {
            return Err(ProtocolError::Malformed("unsupported frame flags".into()));
        }
        if bytes[7] != 0 {
            return Err(ProtocolError::Malformed(
                "reserved byte must be zero".into(),
            ));
        }
        let mut transfer_id = [0u8; 16];
        transfer_id.copy_from_slice(&bytes[8..24]);
        let chunk_index = u64::from_be_bytes(bytes[24..32].try_into().expect("fixed slice"));
        let payload_len = u32::from_be_bytes(bytes[32..36].try_into().expect("fixed slice"));
        let mut digest = [0u8; 32];
        digest.copy_from_slice(&bytes[36..68]);

        if kind == FrameKind::Control && payload_len as usize > MAX_CONTROL_FRAME {
            return Err(ProtocolError::TooLarge(MAX_CONTROL_FRAME));
        }
        if kind == FrameKind::Chunk {
            if payload_len as usize > MAX_CHUNK_PAYLOAD {
                return Err(ProtocolError::TooLarge(MAX_CHUNK_PAYLOAD));
            }
            if payload_len == 0 {
                return Err(ProtocolError::Malformed("empty chunk".into()));
            }
            if transfer_id == [0u8; 16] {
                return Err(ProtocolError::Malformed("chunk without transfer id".into()));
            }
        }

        Ok(Self {
            kind,
            flags,
            transfer_id,
            chunk_index,
            payload_len,
            digest: ContentHash(digest),
        })
    }

    pub fn transfer_id(&self) -> Option<TransferId> {
        if self.transfer_id == [0u8; 16] {
            None
        } else {
            Some(TransferId::from_uuid(Uuid::from_bytes(self.transfer_id)))
        }
    }
}

/// A verified file chunk.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Chunk {
    pub transfer_id: TransferId,
    pub index: u64,
    pub payload: Bytes,
    pub digest: ContentHash,
}

impl Chunk {
    pub fn new(transfer_id: TransferId, index: u64, payload: Bytes) -> ProtocolResult<Self> {
        if payload.is_empty() {
            return Err(ProtocolError::Malformed("empty chunk".into()));
        }
        if payload.len() > MAX_CHUNK_PAYLOAD {
            return Err(ProtocolError::TooLarge(MAX_CHUNK_PAYLOAD));
        }
        let digest = ContentHash::from_slice(&Sha256::digest(&payload))
            .map_err(|e| ProtocolError::Malformed(e.to_string()))?;
        Ok(Self {
            transfer_id,
            index,
            payload,
            digest,
        })
    }

    pub fn verify(&self) -> ProtocolResult<()> {
        let digest = Sha256::digest(&self.payload);
        if digest.as_slice() != self.digest.0 {
            return Err(ProtocolError::Malformed("chunk digest mismatch".into()));
        }
        Ok(())
    }

    pub fn offset(&self, chunk_size: u32) -> u64 {
        self.index.saturating_mul(chunk_size as u64)
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Frame {
    /// Boxed: control frames are large and rarely travel beside chunk frames.
    Control(Box<Envelope>),
    Chunk(Chunk),
}

impl Frame {
    pub fn encode(&self) -> ProtocolResult<Vec<u8>> {
        match self {
            Self::Control(envelope) => {
                let payload = envelope.encode()?;
                let digest = ContentHash::from_slice(&Sha256::digest(&payload))
                    .map_err(|e| ProtocolError::Malformed(e.to_string()))?;
                let header = FrameHeader::for_control(payload.len() as u32, digest);
                let mut out = Vec::with_capacity(FRAME_HEADER_LEN + payload.len());
                out.extend_from_slice(&header.encode());
                out.extend_from_slice(&payload);
                Ok(out)
            }
            Self::Chunk(chunk) => {
                chunk.verify()?;
                let header = FrameHeader::for_chunk(
                    chunk.transfer_id,
                    chunk.index,
                    chunk.payload.len() as u32,
                    chunk.digest,
                );
                let mut out = Vec::with_capacity(FRAME_HEADER_LEN + chunk.payload.len());
                out.extend_from_slice(&header.encode());
                out.extend_from_slice(&chunk.payload);
                Ok(out)
            }
        }
    }

    /// Rebuild a frame from a header and payload that were read from a stream.
    pub fn from_parts(header: FrameHeader, payload: Vec<u8>) -> ProtocolResult<Self> {
        if payload.len() != header.payload_len as usize {
            return Err(ProtocolError::Malformed("frame length mismatch".into()));
        }
        let digest = ContentHash::from_slice(&Sha256::digest(&payload))
            .map_err(|e| ProtocolError::Malformed(e.to_string()))?;
        if digest.0 != header.digest.0 {
            return Err(ProtocolError::Malformed("frame digest mismatch".into()));
        }
        match header.kind {
            FrameKind::Control => {
                let envelope = Envelope::decode(&payload)?;
                Ok(Self::Control(Box::new(envelope)))
            }
            FrameKind::Chunk => {
                let transfer_id = header
                    .transfer_id()
                    .ok_or_else(|| ProtocolError::Malformed("chunk without transfer id".into()))?;
                Ok(Self::Chunk(Chunk {
                    transfer_id,
                    index: header.chunk_index,
                    payload: Bytes::from(payload),
                    digest,
                }))
            }
        }
    }

    pub fn decode(bytes: &[u8]) -> ProtocolResult<Self> {
        if bytes.len() < FRAME_HEADER_LEN {
            return Err(ProtocolError::Malformed("short frame".into()));
        }
        let header = FrameHeader::decode(&bytes[..FRAME_HEADER_LEN])?;
        let expected = FRAME_HEADER_LEN + header.payload_len as usize;
        if bytes.len() != expected {
            return Err(ProtocolError::Malformed("frame length mismatch".into()));
        }
        Self::from_parts(header, bytes[FRAME_HEADER_LEN..].to_vec())
    }

    pub fn transfer_id(&self) -> Option<TransferId> {
        match self {
            Self::Control(_) => None,
            Self::Chunk(chunk) => Some(chunk.transfer_id),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::Message;
    use flowww_core::{Capability, DeviceId, DeviceInfo, Platform};
    use std::collections::BTreeSet;

    fn transfer_id() -> TransferId {
        TransferId::new()
    }

    fn hello() -> Envelope {
        Envelope::new(Message::Hello {
            device: DeviceInfo::new(
                DeviceId::new("fw_12345678").unwrap(),
                "Frame test",
                Platform::Windows,
                BTreeSet::<Capability>::new(),
            )
            .unwrap(),
            nonce: "ab".repeat(32),
        })
    }

    #[test]
    fn control_frame_round_trip() {
        let frame = Frame::Control(Box::new(hello()));
        let bytes = frame.encode().unwrap();
        assert!(bytes.len() > FRAME_HEADER_LEN);
        assert_eq!(Frame::decode(&bytes).unwrap(), frame);
    }

    #[test]
    fn chunk_frame_round_trip() {
        let chunk = Chunk::new(transfer_id(), 7, Bytes::from_static(b"hello flowww")).unwrap();
        let frame = Frame::Chunk(chunk);
        let bytes = frame.encode().unwrap();
        assert_eq!(Frame::decode(&bytes).unwrap(), frame);
    }

    #[test]
    fn tampered_chunk_is_rejected() {
        let chunk = Chunk::new(transfer_id(), 0, Bytes::from_static(b"payload")).unwrap();
        let mut bytes = Frame::Chunk(chunk).encode().unwrap();
        let last = bytes.len() - 1;
        bytes[last] ^= 0xff;
        assert!(Frame::decode(&bytes).is_err());
    }

    #[test]
    fn bad_magic_and_version_are_rejected() {
        let bytes = Frame::Control(Box::new(hello())).encode().unwrap();
        let mut bad_magic = bytes.clone();
        bad_magic[0] = b'X';
        assert!(Frame::decode(&bad_magic).is_err());
        let mut bad_version = bytes;
        bad_version[4] = 9;
        assert!(Frame::decode(&bad_version).is_err());
    }

    #[test]
    fn oversized_chunk_is_rejected() {
        let payload = Bytes::from(vec![0u8; MAX_CHUNK_PAYLOAD + 1]);
        assert!(Chunk::new(transfer_id(), 0, payload).is_err());
    }

    #[test]
    fn header_never_reads_past_its_bounds() {
        let header = FrameHeader::for_control(12, ContentHash([4u8; 32]));
        let encoded = header.encode();
        assert_eq!(FrameHeader::decode(&encoded).unwrap(), header);
        assert!(FrameHeader::decode(&encoded[..67]).is_err());
    }
}
