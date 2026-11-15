//! Platform-protected secret store.
//!
//! Android supplies a Keystore-backed implementation; the desktop app supplies
//! a DPAPI-backed store directly in Rust (see `flowww-node`). Rust receives
//! the 32-byte identity seed only transiently and keeps it inside zeroizing
//! memory. No DLL/FFI DTO, event, error or log line ever carries the seed;
//! the platform-side key that protects it (e.g. the Keystore AES key) never
//! crosses this boundary in either direction.

use std::sync::Arc;

use flowww_security::{SecretStore, SecurityError, SecurityResult};
use zeroize::Zeroizing;

use crate::error::FlowwwError;

/// Platform secret store implemented by native code (Android Keystore).
///
/// Contract:
///
/// * `load_secret` returns the stored 32-byte seed, or an empty vector when
///   no record exists. It must throw on any authenticity failure (wrong key,
///   truncated record, permanently invalidated entry) — Rust maps every
///   error to a fatal start failure and never silently regenerates.
/// * `store_secret` takes exactly 32 bytes, persists them encrypted under
///   the platform boundary and must not retain a plaintext copy.
/// * `clear_secret` removes the record. Used only by explicit identity reset.
#[uniffi::export(foreign)]
pub trait PlatformSecretStore: Send + Sync {
    fn load_secret(&self) -> Result<Vec<u8>, FlowwwError>;
    fn store_secret(&self, secret: Vec<u8>) -> Result<(), FlowwwError>;
    fn clear_secret(&self) -> Result<(), FlowwwError>;
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex;

    /// In-memory stand-in for the platform callback. Exercises the adapter
    /// contract on the host; the real Keystore path is covered by Android
    /// instrumentation tests.
    struct MemoryStore {
        slot: Mutex<Vec<u8>>,
        store_fails: bool,
    }

    impl MemoryStore {
        fn new() -> Self {
            Self {
                slot: Mutex::new(Vec::new()),
                store_fails: false,
            }
        }

        fn failing() -> Self {
            Self {
                slot: Mutex::new(Vec::new()),
                store_fails: true,
            }
        }
    }

    impl PlatformSecretStore for MemoryStore {
        fn load_secret(&self) -> Result<Vec<u8>, FlowwwError> {
            Ok(self.slot.lock().unwrap_or_else(|p| p.into_inner()).clone())
        }

        fn store_secret(&self, secret: Vec<u8>) -> Result<(), FlowwwError> {
            if self.store_fails {
                return Err(FlowwwError::Storage {
                    detail: "rejected".into(),
                });
            }
            *self.slot.lock().unwrap_or_else(|p| p.into_inner()) = secret;
            Ok(())
        }

        fn clear_secret(&self) -> Result<(), FlowwwError> {
            self.slot.lock().unwrap_or_else(|p| p.into_inner()).clear();
            Ok(())
        }
    }

    #[test]
    fn missing_record_maps_to_none() {
        let store = CallbackSecretStore(Arc::new(MemoryStore::new()));
        assert!(store.load().unwrap().is_none());
    }

    #[test]
    fn round_trip_survives() {
        let store = CallbackSecretStore(Arc::new(MemoryStore::new()));
        let secret = [0x5au8; 32];
        store.store(&secret).unwrap();
        let loaded = store.load().unwrap().unwrap();
        assert_eq!(*loaded, secret);
    }

    #[test]
    fn wrong_length_record_is_rejected() {
        let memory = Arc::new(MemoryStore::new());
        memory.slot.lock().unwrap().extend_from_slice(&[1u8; 16]);
        let store = CallbackSecretStore(memory);
        assert!(store.load().is_err());
    }

    #[test]
    fn store_failure_is_fatal_not_silent() {
        let store = CallbackSecretStore(Arc::new(MemoryStore::failing()));
        assert!(store.store(&[7u8; 32]).is_err());
    }
}

/// Adapts a platform callback to [`SecretStore`]. The seed is zeroized as
/// soon as the identity is built.
pub(crate) struct CallbackSecretStore(pub Arc<dyn PlatformSecretStore>);

impl SecretStore for CallbackSecretStore {
    fn load(&self) -> SecurityResult<Option<Zeroizing<[u8; 32]>>> {
        let bytes = self.0.load_secret().map_err(|_| SecurityError::Encoding)?;
        if bytes.is_empty() {
            return Ok(None);
        }
        let array: [u8; 32] = bytes.try_into().map_err(|_| SecurityError::Encoding)?;
        Ok(Some(Zeroizing::new(array)))
    }

    fn store(&self, secret: &[u8; 32]) -> SecurityResult<()> {
        self.0
            .store_secret(secret.to_vec())
            .map_err(|_| SecurityError::Io("platform secret store rejected the write".into()))?;
        Ok(())
    }
}
