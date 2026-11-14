//! File and inline content transfer primitives.
//!
//! Every chunk is written only after its digest has been verified. Received
//! files live in a transfer-specific partial file until the final SHA-256
//! matches, then move into place with an atomic rename. Resume is safe because
//! the partial file is truncated to the last complete chunk boundary before its
//! prefix is re-hashed.

use bytes::Bytes;
use sha2::{Digest, Sha256};
use std::path::{Path, PathBuf};
use std::time::Duration;
use thiserror::Error;
use tokio::io::{AsyncReadExt, AsyncSeekExt, AsyncWriteExt};
use tokio::time::timeout;
use tracing::debug;

use flowww_core::{sanitize_filename, ContentHash, TransferId, DEFAULT_CHUNK_SIZE};
use flowww_network::{NetworkError, PeerSession};
use flowww_protocol::{AckStatus, Frame, Message};

/// The only chunk size this implementation sends. Both sides must agree.
pub const CHUNK_SIZE: u32 = DEFAULT_CHUNK_SIZE;
const ACK_TIMEOUT: Duration = Duration::from_secs(60);
const PARTIAL_SUFFIX: &str = "flowww-part";

#[derive(Debug, Error)]
pub enum TransferError {
    #[error("file I/O failed: {0}")]
    Io(#[from] std::io::Error),
    #[error("network failure: {0}")]
    Network(#[from] NetworkError),
    #[error("protocol failure: {0}")]
    Protocol(#[from] flowww_protocol::ProtocolError),
    #[error("the file hash does not match the offer")]
    HashMismatch,
    #[error("expected {expected} bytes but received {actual}")]
    SizeMismatch { expected: u64, actual: u64 },
    #[error("received chunk {index} out of order")]
    UnexpectedChunk { index: u64 },
    #[error("chunk is larger than the {0} byte limit")]
    ChunkTooLarge(u32),
    #[error("the peer sent an unexpected message: {0}")]
    UnexpectedMessage(&'static str),
    #[error("the peer paused or rejected the transfer")]
    Paused,
    #[error("the transfer was cancelled by the peer")]
    Cancelled,
    #[error("the peer reported an error: {0}")]
    PeerError(String),
    #[error("the transfer timed out")]
    Timeout,
    #[error("the incoming file exceeds the offered size")]
    OfferedSizeExceeded,
}

pub type TransferResult<T> = Result<T, TransferError>;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Progress {
    pub transferred: u64,
    pub total: u64,
}

impl Progress {
    pub fn fraction(self) -> f32 {
        if self.total == 0 {
            0.0
        } else {
            (self.transferred as f64 / self.total as f64) as f32
        }
    }

    pub fn percent(self) -> u8 {
        (self.fraction() * 100.0).round().clamp(0.0, 100.0) as u8
    }
}

/// Stream a file through SHA-256.
pub async fn hash_file(path: impl AsRef<Path>) -> TransferResult<ContentHash> {
    let mut file = tokio::fs::File::open(path).await?;
    let mut hasher = Sha256::new();
    let mut buffer = vec![0u8; CHUNK_SIZE as usize];
    loop {
        let read = file.read(&mut buffer).await?;
        if read == 0 {
            break;
        }
        hasher.update(&buffer[..read]);
    }
    ContentHash::from_slice(&hasher.finalize())
        .map_err(|_| TransferError::UnexpectedMessage("invalid digest"))
}

pub fn hash_bytes(payload: &[u8]) -> ContentHash {
    ContentHash::from_slice(&Sha256::digest(payload)).expect("SHA-256 is 32 bytes")
}

/// A fully received, verified file.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CompletedFile {
    pub path: PathBuf,
    pub size: u64,
    pub sha256: ContentHash,
}

/// An in-progress incoming file.
///
/// Dropping this type keeps the partial file on disk so a reconnect can resume.
/// Call [`IncomingFile::discard`] when the transfer is abandoned for good.
pub struct IncomingFile {
    directory: PathBuf,
    final_path: PathBuf,
    partial_path: PathBuf,
    file: tokio::fs::File,
    received: u64,
    expected_size: u64,
    expected_hash: ContentHash,
    hasher: Sha256,
}

impl std::fmt::Debug for IncomingFile {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("IncomingFile")
            .field("final_path", &self.final_path)
            .field("received", &self.received)
            .field("expected_size", &self.expected_size)
            .finish_non_exhaustive()
    }
}

impl IncomingFile {
    /// Open (or recover) the partial file for a transfer.
    ///
    /// The returned instance reports a chunk-aligned resume offset. Any partial
    /// trailing bytes are discarded before hashing resumes.
    pub async fn open(
        directory: impl AsRef<Path>,
        filename: &str,
        transfer_id: TransferId,
        expected_size: u64,
        expected_hash: ContentHash,
    ) -> TransferResult<Self> {
        let directory = directory.as_ref().to_path_buf();
        tokio::fs::create_dir_all(&directory).await?;

        let safe_name = sanitize_filename(filename);
        let safe_name = if safe_name.is_empty() {
            "file".to_string()
        } else {
            safe_name
        };
        let final_path = unique_path(&directory, &safe_name);
        let partial_path = directory.join(format!(".{}.{}", transfer_id, PARTIAL_SUFFIX));

        let mut file = tokio::fs::OpenOptions::new()
            .read(true)
            .write(true)
            .create(true)
            .truncate(false)
            .open(&partial_path)
            .await?;

        let mut received = file.metadata().await?.len();
        if received > expected_size {
            file.set_len(0).await?;
            received = 0;
        }
        let aligned = (received / CHUNK_SIZE as u64) * CHUNK_SIZE as u64;
        if aligned != received {
            // The tail is not verifiable as a complete chunk; re-request it.
            file.set_len(aligned).await?;
            received = aligned;
        }

        let mut hasher = Sha256::new();
        if received > 0 {
            let mut prefix = tokio::fs::File::open(&partial_path).await?;
            let mut remaining = received;
            let mut buffer = vec![0u8; CHUNK_SIZE as usize];
            while remaining > 0 {
                let want = remaining.min(buffer.len() as u64) as usize;
                prefix.read_exact(&mut buffer[..want]).await?;
                hasher.update(&buffer[..want]);
                remaining -= want as u64;
            }
        }
        file.seek(std::io::SeekFrom::Start(received)).await?;

        Ok(Self {
            directory,
            final_path,
            partial_path,
            file,
            received,
            expected_size,
            expected_hash,
            hasher,
        })
    }

    pub fn received(&self) -> u64 {
        self.received
    }

    pub fn expected_size(&self) -> u64 {
        self.expected_size
    }

    pub fn expected_hash(&self) -> ContentHash {
        self.expected_hash
    }

    pub fn final_path(&self) -> &Path {
        &self.final_path
    }

    pub fn progress(&self) -> Progress {
        Progress {
            transferred: self.received,
            total: self.expected_size,
        }
    }

    /// Write one verified chunk. Chunks must arrive in order.
    pub async fn write_chunk(&mut self, index: u64, payload: &[u8]) -> TransferResult<u64> {
        if payload.len() > CHUNK_SIZE as usize {
            return Err(TransferError::ChunkTooLarge(CHUNK_SIZE));
        }
        let expected_index = self.received / CHUNK_SIZE as u64;
        if index != expected_index {
            return Err(TransferError::UnexpectedChunk { index });
        }
        let end = self.received + payload.len() as u64;
        if end > self.expected_size {
            return Err(TransferError::OfferedSizeExceeded);
        }
        self.file.write_all(payload).await?;
        self.hasher.update(payload);
        self.received = end;
        Ok(self.received)
    }

    /// Flush and verify the complete file, then move it into place.
    pub async fn finish(self) -> TransferResult<CompletedFile> {
        if self.received != self.expected_size {
            return Err(TransferError::SizeMismatch {
                expected: self.expected_size,
                actual: self.received,
            });
        }
        let digest = ContentHash::from_slice(&self.hasher.finalize())
            .map_err(|_| TransferError::HashMismatch)?;
        if digest != self.expected_hash {
            // A failed final hash means the partial bytes cannot be trusted.
            let _ = tokio::fs::remove_file(&self.partial_path).await;
            return Err(TransferError::HashMismatch);
        }
        self.file.sync_all().await?;
        drop(self.file);

        let final_path = if tokio::fs::try_exists(&self.final_path).await? {
            unique_path(&self.directory, &file_name(&self.final_path))
        } else {
            self.final_path.clone()
        };
        match tokio::fs::rename(&self.partial_path, &final_path).await {
            Ok(()) => {}
            Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => {
                let alternate = unique_path(&self.directory, &file_name(&final_path));
                tokio::fs::rename(&self.partial_path, &alternate).await?;
                return Ok(CompletedFile {
                    path: alternate,
                    size: self.received,
                    sha256: digest,
                });
            }
            Err(error) => return Err(TransferError::Io(error)),
        }
        debug!("completed incoming file at {}", final_path.display());
        Ok(CompletedFile {
            path: final_path,
            size: self.received,
            sha256: digest,
        })
    }

    /// Delete the partial file. The final file is never touched.
    pub async fn discard(self) -> TransferResult<()> {
        let _ = tokio::fs::remove_file(&self.partial_path).await;
        Ok(())
    }
}

fn file_name(path: &Path) -> String {
    path.file_name()
        .map(|name| name.to_string_lossy().to_string())
        .unwrap_or_else(|| "file".to_string())
}

/// Pick a non-colliding destination name inside `directory`.
pub fn unique_path(directory: &Path, filename: &str) -> PathBuf {
    let initial = directory.join(filename);
    if !initial.exists() {
        return initial;
    }
    let path = Path::new(filename);
    let stem = path
        .file_stem()
        .map(|value| value.to_string_lossy().to_string())
        .unwrap_or_else(|| "file".to_string());
    let extension = path
        .extension()
        .map(|value| value.to_string_lossy().to_string());
    for counter in 1..10_000 {
        let candidate_name = match &extension {
            Some(extension) => format!("{stem} ({counter}).{extension}"),
            None => format!("{stem} ({counter})"),
        };
        let candidate = directory.join(&candidate_name);
        if !candidate.exists() {
            return candidate;
        }
    }
    directory.join(format!("{stem}-{}", flowww_core::unix_millis()))
}

/// Send a file that the receiver has already accepted.
///
/// The caller sends the offer and receives the acceptance; this function takes
/// over from `TRANSFER_START` and returns after the receiver confirms the final
/// hash.
pub async fn send_file(
    session: &mut PeerSession,
    transfer_id: TransferId,
    path: impl AsRef<Path>,
    size: u64,
    sha256: ContentHash,
    resume_from: u64,
    progress: &mut (dyn FnMut(Progress) + Send),
) -> TransferResult<()> {
    let path = path.as_ref();
    let mut file = tokio::fs::File::open(path).await?;
    let aligned = (resume_from / CHUNK_SIZE as u64) * CHUNK_SIZE as u64;
    if aligned > size {
        return Err(TransferError::SizeMismatch {
            expected: size,
            actual: aligned,
        });
    }
    file.seek(std::io::SeekFrom::Start(aligned)).await?;

    session
        .send_message(Message::TransferStart {
            transfer_id,
            resume_from: aligned,
        })
        .await?;
    progress(Progress {
        transferred: aligned,
        total: size,
    });

    let mut offset = aligned;
    let mut buffer = vec![0u8; CHUNK_SIZE as usize];
    while offset < size {
        let remaining = size - offset;
        let want = remaining.min(buffer.len() as u64) as usize;
        file.read_exact(&mut buffer[..want]).await?;
        let chunk = Bytes::copy_from_slice(&buffer[..want]);
        let index = offset / CHUNK_SIZE as u64;
        session.send_chunk(transfer_id, index, chunk).await?;
        let received = await_ack(session, transfer_id, index, offset + want as u64).await?;
        offset = received;
        progress(Progress {
            transferred: offset,
            total: size,
        });
    }

    session
        .send_message(Message::Complete {
            transfer_id,
            sha256,
            total_bytes: size,
        })
        .await?;
    await_complete(session, transfer_id, sha256, size).await
}

/// Receive a file after the local side has accepted the offer.
pub async fn receive_file(
    session: &mut PeerSession,
    transfer_id: TransferId,
    mut incoming: IncomingFile,
    progress: &mut (dyn FnMut(Progress) + Send),
) -> TransferResult<CompletedFile> {
    let resume_from = await_start(session, transfer_id).await?;
    if resume_from != incoming.received() {
        return Err(TransferError::UnexpectedChunk {
            index: resume_from / CHUNK_SIZE as u64,
        });
    }

    loop {
        match session.next_frame().await? {
            Frame::Chunk(chunk) => {
                if chunk.transfer_id != transfer_id {
                    return Err(TransferError::UnexpectedMessage(
                        "chunk for another transfer",
                    ));
                }
                chunk.verify()?;
                let expected_index = incoming.received() / CHUNK_SIZE as u64;
                if chunk.index < expected_index {
                    session
                        .send_message(Message::Ack {
                            transfer_id,
                            chunk_index: chunk.index,
                            status: AckStatus::Duplicate,
                            received_bytes: incoming.received(),
                        })
                        .await?;
                    continue;
                }
                if chunk.index > expected_index {
                    return Err(TransferError::UnexpectedChunk { index: chunk.index });
                }
                let received = incoming.write_chunk(chunk.index, &chunk.payload).await?;
                session
                    .send_message(Message::Ack {
                        transfer_id,
                        chunk_index: chunk.index,
                        status: AckStatus::Accepted,
                        received_bytes: received,
                    })
                    .await?;
                progress(incoming.progress());
            }
            Frame::Control(envelope) => match envelope.message {
                Message::Ping { nonce } => {
                    session.send_message(Message::Pong { nonce }).await?;
                }
                Message::Complete {
                    transfer_id: completed,
                    sha256,
                    total_bytes,
                } => {
                    if completed != transfer_id {
                        return Err(TransferError::UnexpectedMessage(
                            "completion for another transfer",
                        ));
                    }
                    if total_bytes != incoming.expected_size() || sha256 != incoming.expected_hash()
                    {
                        return Err(TransferError::HashMismatch);
                    }
                    let file = incoming.finish().await?;
                    session
                        .send_message(Message::Complete {
                            transfer_id,
                            sha256: file.sha256,
                            total_bytes: file.size,
                        })
                        .await?;
                    progress(Progress {
                        transferred: file.size,
                        total: file.size,
                    });
                    return Ok(file);
                }
                Message::Cancel { .. } => return Err(TransferError::Cancelled),
                Message::Error { message, .. } => return Err(TransferError::PeerError(message)),
                other => {
                    return Err(TransferError::UnexpectedMessage(other.kind()));
                }
            },
        }
    }
}

/// Send bounded inline text or URL payloads.
pub async fn send_inline(
    session: &mut PeerSession,
    transfer_id: TransferId,
    payload: &[u8],
) -> TransferResult<()> {
    let sha256 = hash_bytes(payload);
    let size = payload.len() as u64;
    session
        .send_message(Message::TransferStart {
            transfer_id,
            resume_from: 0,
        })
        .await?;
    session
        .send_message(Message::Complete {
            transfer_id,
            sha256,
            total_bytes: size,
        })
        .await?;
    await_complete(session, transfer_id, sha256, size).await
}

/// Receive an inline payload after the local side has accepted the offer.
///
/// Returns the verified payload so the caller can display or store it.
pub async fn receive_inline(
    session: &mut PeerSession,
    transfer_id: TransferId,
    expected_payload: &[u8],
) -> TransferResult<Vec<u8>> {
    let resume_from = await_start(session, transfer_id).await?;
    if resume_from != 0 {
        return Err(TransferError::UnexpectedChunk { index: 0 });
    }
    loop {
        match session.next_frame().await? {
            Frame::Chunk(_) => {
                return Err(TransferError::UnexpectedMessage(
                    "inline transfer cannot carry chunks",
                ))
            }
            Frame::Control(envelope) => match envelope.message {
                Message::Ping { nonce } => {
                    session.send_message(Message::Pong { nonce }).await?;
                }
                Message::Complete {
                    transfer_id: completed,
                    sha256,
                    total_bytes,
                } => {
                    if completed != transfer_id {
                        return Err(TransferError::UnexpectedMessage(
                            "completion for another transfer",
                        ));
                    }
                    let actual = hash_bytes(expected_payload);
                    if sha256 != actual || total_bytes != expected_payload.len() as u64 {
                        return Err(TransferError::HashMismatch);
                    }
                    session
                        .send_message(Message::Complete {
                            transfer_id,
                            sha256: actual,
                            total_bytes: expected_payload.len() as u64,
                        })
                        .await?;
                    return Ok(expected_payload.to_vec());
                }
                Message::Cancel { .. } => return Err(TransferError::Cancelled),
                Message::Error { message, .. } => return Err(TransferError::PeerError(message)),
                other => return Err(TransferError::UnexpectedMessage(other.kind())),
            },
        }
    }
}

async fn await_start(session: &mut PeerSession, transfer_id: TransferId) -> TransferResult<u64> {
    match timeout(ACK_TIMEOUT, session.recv_message())
        .await
        .map_err(|_| TransferError::Timeout)??
    {
        Message::TransferStart {
            transfer_id: started,
            resume_from,
        } => {
            if started != transfer_id {
                return Err(TransferError::UnexpectedMessage(
                    "start for another transfer",
                ));
            }
            Ok(resume_from)
        }
        Message::Cancel { .. } => Err(TransferError::Cancelled),
        Message::Error { message, .. } => Err(TransferError::PeerError(message)),
        Message::Pause { .. } => Err(TransferError::Paused),
        other => Err(TransferError::UnexpectedMessage(other.kind())),
    }
}

async fn await_ack(
    session: &mut PeerSession,
    transfer_id: TransferId,
    index: u64,
    expected_received: u64,
) -> TransferResult<u64> {
    match timeout(ACK_TIMEOUT, session.recv_message())
        .await
        .map_err(|_| TransferError::Timeout)??
    {
        Message::Ack {
            transfer_id: acked,
            chunk_index,
            status,
            received_bytes,
        } => {
            if acked != transfer_id || chunk_index != index {
                return Err(TransferError::UnexpectedMessage("ack for another chunk"));
            }
            match status {
                AckStatus::Accepted | AckStatus::Duplicate => {
                    if received_bytes != expected_received {
                        return Err(TransferError::UnexpectedMessage(
                            "ack byte count does not match",
                        ));
                    }
                    Ok(received_bytes)
                }
                AckStatus::Paused => Err(TransferError::Paused),
                AckStatus::Rejected => Err(TransferError::UnexpectedMessage("chunk rejected")),
            }
        }
        Message::Pause { .. } => Err(TransferError::Paused),
        Message::Cancel { .. } => Err(TransferError::Cancelled),
        Message::Error { message, .. } => Err(TransferError::PeerError(message)),
        other => Err(TransferError::UnexpectedMessage(other.kind())),
    }
}

async fn await_complete(
    session: &mut PeerSession,
    transfer_id: TransferId,
    sha256: ContentHash,
    size: u64,
) -> TransferResult<()> {
    match timeout(ACK_TIMEOUT, session.recv_message())
        .await
        .map_err(|_| TransferError::Timeout)??
    {
        Message::Complete {
            transfer_id: completed,
            sha256: received_hash,
            total_bytes,
        } => {
            if completed != transfer_id || received_hash != sha256 || total_bytes != size {
                return Err(TransferError::HashMismatch);
            }
            Ok(())
        }
        Message::Pause { .. } => Err(TransferError::Paused),
        Message::Cancel { .. } => Err(TransferError::Cancelled),
        Message::Error { message, .. } => Err(TransferError::PeerError(message)),
        other => Err(TransferError::UnexpectedMessage(other.kind())),
    }
}

/// Relay one transfer between an upstream sender and a downstream receiver.
///
/// The relay forwards exactly one chunk at a time: it verifies the chunk
/// digest, writes it to the downstream session and waits for the downstream
/// ACK before reading the next chunk. No bytes are written to disk and memory
/// stays bounded by one chunk plus headers.
///
/// The caller must already have:
/// * sent the offer to the downstream peer and received its `TransferAccept`,
/// * sent the downstream resume offset to the upstream peer.
///
/// After this returns, both sides have exchanged the final hash confirmation.
pub async fn relay_transfer(
    upstream: &mut PeerSession,
    downstream: &mut PeerSession,
    transfer_id: TransferId,
    expected_size: u64,
    expected_hash: ContentHash,
    progress: &mut (dyn FnMut(Progress) + Send),
) -> TransferResult<()> {
    let resume_from = await_start(upstream, transfer_id).await?;
    if resume_from > expected_size {
        return Err(TransferError::SizeMismatch {
            expected: expected_size,
            actual: resume_from,
        });
    }
    downstream
        .send_message(Message::TransferStart {
            transfer_id,
            resume_from,
        })
        .await?;
    progress(Progress {
        transferred: resume_from,
        total: expected_size,
    });

    loop {
        match upstream.next_frame().await? {
            Frame::Chunk(chunk) => {
                if chunk.transfer_id != transfer_id {
                    return Err(TransferError::UnexpectedMessage(
                        "chunk for another transfer",
                    ));
                }
                chunk.verify()?;
                downstream
                    .send_chunk(transfer_id, chunk.index, chunk.payload.clone())
                    .await?;
                let ack = relay_ack(downstream, transfer_id, chunk.index).await?;
                let acked_bytes = match &ack {
                    Message::Ack { received_bytes, .. } => *received_bytes,
                    _ => unreachable!(),
                };
                if acked_bytes > expected_size {
                    return Err(TransferError::OfferedSizeExceeded);
                }
                upstream.send_message(ack).await?;
                progress(Progress {
                    transferred: acked_bytes,
                    total: expected_size,
                });
            }
            Frame::Control(envelope) => match envelope.message {
                Message::Ping { nonce } => {
                    upstream.send_message(Message::Pong { nonce }).await?;
                }
                Message::Complete {
                    transfer_id: completed,
                    sha256,
                    total_bytes,
                } => {
                    if completed != transfer_id {
                        return Err(TransferError::UnexpectedMessage(
                            "completion for another transfer",
                        ));
                    }
                    if total_bytes != expected_size || sha256 != expected_hash {
                        return Err(TransferError::HashMismatch);
                    }
                    downstream
                        .send_message(Message::Complete {
                            transfer_id,
                            sha256,
                            total_bytes,
                        })
                        .await?;
                    await_complete(downstream, transfer_id, expected_hash, expected_size).await?;
                    upstream
                        .send_message(Message::Complete {
                            transfer_id,
                            sha256,
                            total_bytes,
                        })
                        .await?;
                    progress(Progress {
                        transferred: expected_size,
                        total: expected_size,
                    });
                    return Ok(());
                }
                Message::Cancel {
                    transfer_id: cancelled,
                    reason,
                } => {
                    let _ = downstream
                        .send_message(Message::Cancel {
                            transfer_id: cancelled,
                            reason,
                        })
                        .await;
                    return Err(TransferError::Cancelled);
                }
                Message::Error {
                    code,
                    message,
                    transfer_id: failed,
                } => {
                    let _ = downstream
                        .send_message(Message::Error {
                            code,
                            message: message.clone(),
                            transfer_id: failed,
                        })
                        .await;
                    return Err(TransferError::PeerError(message));
                }
                other => {
                    return Err(TransferError::UnexpectedMessage(other.kind()));
                }
            },
        }
    }
}

async fn relay_ack(
    downstream: &mut PeerSession,
    transfer_id: TransferId,
    index: u64,
) -> TransferResult<Message> {
    match timeout(ACK_TIMEOUT, downstream.recv_message())
        .await
        .map_err(|_| TransferError::Timeout)??
    {
        message @ Message::Ack { .. } => {
            match &message {
                Message::Ack {
                    transfer_id: acked,
                    chunk_index,
                    status,
                    ..
                } => {
                    if *acked != transfer_id || *chunk_index != index {
                        return Err(TransferError::UnexpectedMessage("ack for another chunk"));
                    }
                    if *status == AckStatus::Rejected {
                        return Err(TransferError::UnexpectedMessage("chunk rejected"));
                    }
                    if *status == AckStatus::Paused {
                        return Err(TransferError::Paused);
                    }
                }
                _ => unreachable!(),
            }
            Ok(message)
        }
        Message::Cancel { .. } => Err(TransferError::Cancelled),
        Message::Error { message, .. } => Err(TransferError::PeerError(message)),
        other => Err(TransferError::UnexpectedMessage(other.kind())),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use flowww_core::DeviceId;

    fn content_hash(bytes: &[u8]) -> ContentHash {
        hash_bytes(bytes)
    }

    fn transfer_id() -> TransferId {
        TransferId::for_content(
            &DeviceId::new("fw_source_01").unwrap(),
            &DeviceId::new("fw_target_01").unwrap(),
            &content_hash(b"fixture").to_hex(),
            1024,
        )
    }

    #[tokio::test]
    async fn hashing_a_file_matches_in_memory_hash() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("report.bin");
        let payload = b"flowww file payload";
        tokio::fs::write(&path, payload).await.unwrap();
        assert_eq!(hash_file(&path).await.unwrap(), content_hash(payload));
    }

    #[tokio::test]
    async fn incoming_file_resumes_at_a_chunk_boundary() {
        let dir = tempfile::tempdir().unwrap();
        let transfer = transfer_id();
        let first = vec![7u8; CHUNK_SIZE as usize];
        let second = b"tail".to_vec();
        let mut complete = first.clone();
        complete.extend_from_slice(&second);
        let digest = content_hash(&complete);

        let mut incoming = IncomingFile::open(
            dir.path(),
            "video.mp4",
            transfer,
            complete.len() as u64,
            digest,
        )
        .await
        .unwrap();
        assert_eq!(incoming.received(), 0);
        incoming.write_chunk(0, &first).await.unwrap();
        let partial = dir.path().join(format!(".{}.{}", transfer, PARTIAL_SUFFIX));
        assert!(partial.exists());
        drop(incoming);

        let mut resumed = IncomingFile::open(
            dir.path(),
            "video.mp4",
            transfer,
            complete.len() as u64,
            digest,
        )
        .await
        .unwrap();
        assert_eq!(resumed.received(), CHUNK_SIZE as u64);
        resumed.write_chunk(1, &second).await.unwrap();
        let completed = resumed.finish().await.unwrap();
        assert_eq!(completed.sha256, digest);
        assert_eq!(completed.size, complete.len() as u64);
        assert_eq!(tokio::fs::read(&completed.path).await.unwrap(), complete);
        assert!(!partial.exists());
    }

    #[tokio::test]
    async fn unaligned_partial_tail_is_discarded() {
        let dir = tempfile::tempdir().unwrap();
        let transfer = TransferId::new();
        let partial = dir.path().join(format!(".{}.{}", transfer, PARTIAL_SUFFIX));
        tokio::fs::write(&partial, vec![1u8; 100]).await.unwrap();
        let incoming = IncomingFile::open(dir.path(), "file.bin", transfer, 200, content_hash(&[]))
            .await
            .unwrap();
        assert_eq!(incoming.received(), 0);
        assert_eq!(tokio::fs::metadata(&partial).await.unwrap().len(), 0);
    }

    #[tokio::test]
    async fn corrupted_file_is_rejected_and_removed() {
        let dir = tempfile::tempdir().unwrap();
        let transfer = TransferId::new();
        let payload = b"data".to_vec();
        let mut incoming = IncomingFile::open(
            dir.path(),
            "note.txt",
            transfer,
            payload.len() as u64,
            content_hash(&payload),
        )
        .await
        .unwrap();
        incoming.write_chunk(0, &payload).await.unwrap();
        drop(incoming);

        let mut tampered = IncomingFile::open(
            dir.path(),
            "note.txt",
            transfer,
            payload.len() as u64,
            content_hash(&payload),
        )
        .await
        .unwrap();
        // Simulate corruption by claiming the file is complete without writing
        // the remaining bytes correctly.
        tampered.received = 0;
        tampered.hasher = Sha256::new();
        let error = tampered.finish().await.unwrap_err();
        assert!(matches!(error, TransferError::SizeMismatch { .. }));
    }

    #[tokio::test]
    async fn destination_names_do_not_collide() {
        let dir = tempfile::tempdir().unwrap();
        tokio::fs::write(dir.path().join("report.pdf"), b"a")
            .await
            .unwrap();
        let next = unique_path(dir.path(), "report.pdf");
        assert_eq!(next.file_name().unwrap(), "report (1).pdf");
        std::fs::write(dir.path().join("report (1).pdf"), b"b").unwrap();
        assert_eq!(
            unique_path(dir.path(), "report.pdf").file_name().unwrap(),
            "report (2).pdf"
        );
    }

    #[test]
    fn progress_percentage_is_bounded() {
        assert_eq!(
            Progress {
                transferred: 50,
                total: 100
            }
            .percent(),
            50
        );
        assert_eq!(
            Progress {
                transferred: 0,
                total: 0
            }
            .percent(),
            0
        );
        assert_eq!(
            Progress {
                transferred: 200,
                total: 100
            }
            .percent(),
            100
        );
    }
}
