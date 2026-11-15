use base64::engine::general_purpose::{STANDARD, STANDARD_NO_PAD};
use base64::Engine;
use ed25519_dalek::{Signature, Signer, SigningKey, Verifier, VerifyingKey};
use rand::rngs::OsRng;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::fmt;
use std::fs;
use std::path::Path;
use std::str::FromStr;
use std::sync::Arc;
use subtle::ConstantTimeEq;
use zeroize::Zeroizing;

use crate::{SecurityError, SecurityResult};
use flowww_core::{unix_millis, DeviceInfo};

const IDENTITY_FILE_VERSION: u8 = 1;

/// SHA-256 of a public key or TLS certificate.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct Fingerprint(pub [u8; 32]);

impl Fingerprint {
    pub fn of(bytes: &[u8]) -> Self {
        let digest = Sha256::digest(bytes);
        let mut out = [0u8; 32];
        out.copy_from_slice(&digest);
        Self(out)
    }

    pub fn to_hex(self) -> String {
        hex::encode(self.0)
    }

    /// Human-comparable form: `ABCD EFGH IJKL …` in four groups.
    pub fn grouped(self) -> String {
        let hex = self.to_hex().to_ascii_uppercase();
        hex.as_bytes()
            .chunks(4)
            .take(4)
            .map(|chunk| String::from_utf8_lossy(chunk).to_string())
            .collect::<Vec<_>>()
            .join(" ")
    }

    pub fn short(self) -> String {
        let hex = self.to_hex();
        format!("{}…{}", &hex[..8], &hex[hex.len() - 4..])
    }

    pub fn parse(value: &str) -> SecurityResult<Self> {
        let bytes = hex::decode(value.trim()).map_err(|_| SecurityError::Encoding)?;
        if bytes.len() != 32 {
            return Err(SecurityError::Encoding);
        }
        let mut out = [0u8; 32];
        out.copy_from_slice(&bytes);
        Ok(Self(out))
    }

    pub fn equals(self, other: Self) -> bool {
        self.0.ct_eq(&other.0).into()
    }
}

impl fmt::Display for Fingerprint {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.to_hex())
    }
}

impl Serialize for Fingerprint {
    fn serialize<S: serde::Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        serializer.serialize_str(&self.to_hex())
    }
}

impl<'de> Deserialize<'de> for Fingerprint {
    fn deserialize<D: serde::Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        let value = String::deserialize(deserializer)?;
        Self::parse(&value).map_err(serde::de::Error::custom)
    }
}

/// Base64-encoded Ed25519 signature.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(transparent)]
pub struct SignatureB64(pub String);

impl SignatureB64 {
    pub fn from_bytes(bytes: &[u8]) -> Self {
        Self(STANDARD_NO_PAD.encode(bytes))
    }

    pub fn to_bytes(&self) -> SecurityResult<[u8; 64]> {
        let bytes = STANDARD_NO_PAD
            .decode(&self.0)
            .map_err(|_| SecurityError::Encoding)?;
        bytes.try_into().map_err(|_| SecurityError::Encoding)
    }
}

/// The public half of a device identity.
#[derive(Clone)]
pub struct PublicIdentity {
    verifying: VerifyingKey,
}

impl PublicIdentity {
    pub fn from_bytes(bytes: &[u8; 32]) -> SecurityResult<Self> {
        Ok(Self {
            verifying: VerifyingKey::from_bytes(bytes).map_err(|_| SecurityError::Encoding)?,
        })
    }

    pub fn from_base64(value: &str) -> SecurityResult<Self> {
        let decoded = STANDARD
            .decode(value.trim())
            .or_else(|_| STANDARD_NO_PAD.decode(value.trim()))
            .map_err(|_| SecurityError::Encoding)?;
        let bytes: [u8; 32] = decoded.try_into().map_err(|_| SecurityError::Encoding)?;
        Self::from_bytes(&bytes)
    }

    pub fn as_bytes(&self) -> [u8; 32] {
        self.verifying.to_bytes()
    }

    pub fn to_base64(&self) -> String {
        STANDARD.encode(self.verifying.to_bytes())
    }

    pub fn fingerprint(&self) -> Fingerprint {
        Fingerprint::of(&self.verifying.to_bytes())
    }

    pub fn verify(&self, message: &[u8], signature: &SignatureB64) -> SecurityResult<()> {
        let signature = Signature::from_bytes(&signature.to_bytes()?);
        self.verifying
            .verify(message, &signature)
            .map_err(|_| SecurityError::BadSignature)
    }
}

impl fmt::Debug for PublicIdentity {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("PublicIdentity")
            .field("fingerprint", &self.fingerprint().short())
            .finish()
    }
}

/// Abstraction over platform secret storage.
///
/// The CLI/daemon use `FileSecretStore`, which stores the key in a user-only
/// directory. Windows and Android shells can supply a DPAPI/Keystore-backed
/// implementation without changing any protocol code.
pub trait SecretStore: Send + Sync {
    fn load(&self) -> SecurityResult<Option<Zeroizing<[u8; 32]>>>;
    fn store(&self, secret: &[u8; 32]) -> SecurityResult<()>;
}

/// A file-backed secret store with restrictive permissions where supported.
pub struct FileSecretStore {
    path: std::path::PathBuf,
}

impl FileSecretStore {
    pub fn new(path: impl AsRef<Path>) -> Self {
        Self {
            path: path.as_ref().to_path_buf(),
        }
    }
}

impl SecretStore for FileSecretStore {
    fn load(&self) -> SecurityResult<Option<Zeroizing<[u8; 32]>>> {
        if !self.path.exists() {
            return Ok(None);
        }
        let raw = fs::read_to_string(&self.path).map_err(|e| SecurityError::Io(e.to_string()))?;
        let file: StoredIdentity =
            serde_json::from_str(&raw).map_err(|_| SecurityError::Encoding)?;
        if file.version != IDENTITY_FILE_VERSION {
            return Err(SecurityError::Encoding);
        }
        let decoded = STANDARD
            .decode(file.secret_key)
            .map_err(|_| SecurityError::Encoding)?;
        let bytes: [u8; 32] = decoded.try_into().map_err(|_| SecurityError::Encoding)?;
        Ok(Some(Zeroizing::new(bytes)))
    }

    fn store(&self, secret: &[u8; 32]) -> SecurityResult<()> {
        if let Some(parent) = self.path.parent() {
            fs::create_dir_all(parent).map_err(|e| SecurityError::Io(e.to_string()))?;
        }
        let file = StoredIdentity {
            version: IDENTITY_FILE_VERSION,
            secret_key: STANDARD.encode(secret),
            warning: "flowww device identity. Never share this file.".into(),
        };
        let serialized =
            serde_json::to_vec_pretty(&file).map_err(|e| SecurityError::Io(e.to_string()))?;
        // Never expose a partially written identity after a process or power
        // failure. The identity is created once and then treated as immutable
        // by the node, so a same-directory rename is sufficient here.
        let temporary = self.path.with_extension("json.tmp");
        fs::write(&temporary, serialized).map_err(|e| SecurityError::Io(e.to_string()))?;
        restrict_permissions(&temporary)?;
        fs::rename(&temporary, &self.path).map_err(|e| SecurityError::Io(e.to_string()))?;
        restrict_permissions(&self.path)?;
        Ok(())
    }
}

fn restrict_permissions(path: &Path) -> SecurityResult<()> {
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let mut permissions = fs::metadata(path)
            .map_err(|e| SecurityError::Io(e.to_string()))?
            .permissions();
        permissions.set_mode(0o600);
        fs::set_permissions(path, permissions).map_err(|e| SecurityError::Io(e.to_string()))?;
    }
    #[cfg(not(unix))]
    {
        // On Windows the file lives under the user's roaming profile. A shell
        // integration can wrap this store with DPAPI; the protocol does not
        // depend on the storage mechanism.
        let _ = path;
    }
    Ok(())
}

#[derive(Serialize, Deserialize)]
struct StoredIdentity {
    version: u8,
    secret_key: String,
    warning: String,
}

/// A device's private identity. Not `Serialize`, not `Clone`, and zeroized on drop.
pub struct Identity {
    signing: SigningKey,
}

impl Identity {
    pub fn generate() -> Self {
        Self {
            signing: SigningKey::generate(&mut OsRng),
        }
    }

    pub fn from_secret(secret: &[u8; 32]) -> Self {
        Self {
            signing: SigningKey::from_bytes(secret),
        }
    }

    pub fn load_or_create(store: &dyn SecretStore) -> SecurityResult<Self> {
        if let Some(secret) = store.load()? {
            return Ok(Self::from_secret(&secret));
        }
        let identity = Self::generate();
        store.store(&identity.secret_bytes())?;
        Ok(identity)
    }

    pub fn secret_bytes(&self) -> [u8; 32] {
        self.signing.to_bytes()
    }

    pub fn public_identity(&self) -> PublicIdentity {
        PublicIdentity {
            verifying: self.signing.verifying_key(),
        }
    }

    pub fn public_key_base64(&self) -> String {
        self.public_identity().to_base64()
    }

    pub fn fingerprint(&self) -> Fingerprint {
        self.public_identity().fingerprint()
    }

    pub fn sign(&self, message: &[u8]) -> SignatureB64 {
        SignatureB64::from_bytes(&self.signing.sign(message).to_bytes())
    }
}

impl fmt::Debug for Identity {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("Identity")
            .field("fingerprint", &self.fingerprint().short())
            .finish_non_exhaustive()
    }
}

/// A signed, self-contained identity record.
///
/// The signature covers the canonical JSON of the unsigned fields. It is
/// exchanged during pairing and stored locally next to the trust record.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct IdentityDocument {
    pub device: DeviceInfo,
    pub public_key: String,
    pub created_at: u64,
    pub signature: SignatureB64,
}

#[derive(Serialize)]
struct UnsignedDocument<'a> {
    device: &'a DeviceInfo,
    public_key: &'a str,
    created_at: u64,
}

impl IdentityDocument {
    pub fn create(identity: &Identity, device: DeviceInfo) -> SecurityResult<Self> {
        let public_key = identity.public_key_base64();
        let created_at = unix_millis();
        let unsigned = UnsignedDocument {
            device: &device,
            public_key: &public_key,
            created_at,
        };
        let bytes = serde_json::to_vec(&unsigned).map_err(|_| SecurityError::Encoding)?;
        Ok(Self {
            device,
            public_key,
            created_at,
            signature: identity.sign(&bytes),
        })
    }

    pub fn verify(&self) -> SecurityResult<PublicIdentity> {
        let public = PublicIdentity::from_base64(&self.public_key)?;
        let unsigned = UnsignedDocument {
            device: &self.device,
            public_key: &self.public_key,
            created_at: self.created_at,
        };
        let bytes = serde_json::to_vec(&unsigned).map_err(|_| SecurityError::Encoding)?;
        public.verify(&bytes, &self.signature)?;
        Ok(public)
    }

    pub fn fingerprint(&self) -> SecurityResult<Fingerprint> {
        Ok(self.verify()?.fingerprint())
    }
}

/// An `Arc<dyn SecretStore>` alias keeps shell code uncluttered.
pub type SharedSecretStore = Arc<dyn SecretStore>;

impl FromStr for Fingerprint {
    type Err = SecurityError;
    fn from_str(s: &str) -> Result<Self, Self::Err> {
        Self::parse(s)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use flowww_core::{Capability, DeviceId, Platform};
    use std::collections::BTreeSet;

    #[test]
    fn sign_and_verify_round_trip() {
        let identity = Identity::generate();
        let message = b"flowww";
        let signature = identity.sign(message);
        identity
            .public_identity()
            .verify(message, &signature)
            .unwrap();
        assert!(identity
            .public_identity()
            .verify(b"different", &signature)
            .is_err());
    }

    #[test]
    fn fingerprints_are_stable_and_comparable() {
        let identity = Identity::generate();
        let fp = identity.fingerprint();
        let parsed = Fingerprint::parse(&fp.to_hex()).unwrap();
        assert!(fp.equals(parsed));
        assert_eq!(fp.grouped().split(' ').count(), 4);
    }

    #[test]
    fn identity_document_detects_tampering() {
        let identity = Identity::generate();
        let device = DeviceInfo::new(
            DeviceId::new("fw_12345678").unwrap(),
            "Studio",
            Platform::Windows,
            BTreeSet::<Capability>::new(),
        )
        .unwrap();
        let mut doc = IdentityDocument::create(&identity, device).unwrap();
        doc.verify().unwrap();
        doc.device.device_name = "Impostor".into();
        assert!(doc.verify().is_err());
    }

    #[test]
    fn file_store_round_trips() {
        let dir = tempfile::tempdir().unwrap();
        let store = FileSecretStore::new(dir.path().join("identity.json"));
        let identity = Identity::load_or_create(&store).unwrap();
        let again = Identity::load_or_create(&store).unwrap();
        assert_eq!(
            identity.public_identity().as_bytes(),
            again.public_identity().as_bytes()
        );
    }

    #[test]
    fn corrupted_identity_is_rejected_instead_of_regenerated() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("identity.json");
        let store = FileSecretStore::new(&path);
        let original = Identity::load_or_create(&store).unwrap();
        fs::write(&path, b"not an identity document").unwrap();
        assert!(Identity::load_or_create(&store).is_err());
        // The original value remains available to the test for comparison; a
        // corrupt file must never silently create a replacement identity.
        assert!(!original.public_key_base64().is_empty());
    }
}
