use rcgen::{CertificateParams, DnType, KeyPair};
use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::crypto::WebPkiSupportedAlgorithms;
use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer, ServerName, UnixTime};
use rustls::server::danger::{ClientCertVerified, ClientCertVerifier};
use rustls::{CertificateError, DigitallySignedStruct, DistinguishedName, SignatureScheme};
use std::fmt;
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Once};

use crate::NetworkError;
use flowww_security::{Fingerprint, SharedTrust};

static INSTALL_PROVIDER: Once = Once::new();

pub fn install_crypto_provider() {
    INSTALL_PROVIDER.call_once(|| {
        let _ = rustls::crypto::ring::default_provider().install_default();
    });
}

fn algorithms() -> WebPkiSupportedAlgorithms {
    install_crypto_provider();
    rustls::crypto::ring::default_provider().signature_verification_algorithms
}

/// A persistent self-signed TLS certificate for the device.
///
/// The certificate is a transport pin, not the device's identity. The Ed25519
/// identity is proven separately inside the encrypted channel.
#[derive(Clone)]
pub struct DeviceTls {
    cert_der: Vec<u8>,
    key_der: Vec<u8>,
    fingerprint: Fingerprint,
}

impl fmt::Debug for DeviceTls {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("DeviceTls")
            .field("fingerprint", &self.fingerprint.short())
            .finish()
    }
}

impl DeviceTls {
    pub fn load_or_create(dir: impl AsRef<Path>) -> Result<Self, NetworkError> {
        let dir = dir.as_ref();
        fs::create_dir_all(dir).map_err(|e| NetworkError::TlsConfig(e.to_string()))?;
        let cert_path = dir.join("tls-cert.der");
        let key_path = dir.join("tls-key.der");
        if cert_path.exists() && key_path.exists() {
            let cert_der =
                fs::read(&cert_path).map_err(|e| NetworkError::TlsConfig(e.to_string()))?;
            let key_der =
                fs::read(&key_path).map_err(|e| NetworkError::TlsConfig(e.to_string()))?;
            return Self::from_parts(cert_der, key_der);
        }
        let tls = Self::generate()?;
        fs::write(&cert_path, &tls.cert_der).map_err(|e| NetworkError::TlsConfig(e.to_string()))?;
        fs::write(&key_path, &tls.key_der).map_err(|e| NetworkError::TlsConfig(e.to_string()))?;
        restrict_key_permissions(&key_path);
        Ok(tls)
    }

    pub fn generate() -> Result<Self, NetworkError> {
        install_crypto_provider();
        let key_pair = KeyPair::generate().map_err(|e| NetworkError::TlsConfig(e.to_string()))?;
        let mut params = CertificateParams::new(vec!["flowww.local".to_string()])
            .map_err(|e| NetworkError::TlsConfig(e.to_string()))?;
        params
            .distinguished_name
            .push(DnType::CommonName, "flowww local device");
        params.is_ca = rcgen::IsCa::NoCa;
        let cert = params
            .self_signed(&key_pair)
            .map_err(|e| NetworkError::TlsConfig(e.to_string()))?;
        Self::from_parts(cert.der().to_vec(), key_pair.serialize_der())
    }

    pub fn from_parts(cert_der: Vec<u8>, key_der: Vec<u8>) -> Result<Self, NetworkError> {
        if cert_der.is_empty() || key_der.is_empty() {
            return Err(NetworkError::TlsConfig("empty certificate or key".into()));
        }
        let fingerprint = Fingerprint::of(&cert_der);
        Ok(Self {
            cert_der,
            key_der,
            fingerprint,
        })
    }

    pub fn fingerprint(&self) -> Fingerprint {
        self.fingerprint
    }

    pub fn certificate_der(&self) -> &[u8] {
        &self.cert_der
    }

    fn certificate(&self) -> CertificateDer<'static> {
        CertificateDer::from(self.cert_der.clone())
    }

    fn private_key(&self) -> PrivateKeyDer<'static> {
        PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(self.key_der.clone()))
    }

    /// Server configuration for normal paired connections.
    pub fn server_config(&self, trust: SharedTrust) -> Result<rustls::ServerConfig, NetworkError> {
        install_crypto_provider();
        let verifier = Arc::new(TrustedClientVerifier {
            trust,
            algorithms: algorithms(),
        });
        let config =
            rustls::ServerConfig::builder_with_protocol_versions(&[&rustls::version::TLS13])
                .with_client_cert_verifier(verifier)
                .with_single_cert(vec![self.certificate()], self.private_key())
                .map_err(|e| NetworkError::TlsConfig(e.to_string()))?;
        Ok(config)
    }

    /// Server configuration used only for the short-lived pairing channel.
    ///
    /// The client certificate is recorded and authenticated by the pairing
    /// transcript, so it cannot be known in advance.
    pub fn pairing_server_config(&self) -> Result<rustls::ServerConfig, NetworkError> {
        install_crypto_provider();
        let verifier = Arc::new(AnyClientCertVerifier {
            algorithms: algorithms(),
        });
        let config =
            rustls::ServerConfig::builder_with_protocol_versions(&[&rustls::version::TLS13])
                .with_client_cert_verifier(verifier)
                .with_single_cert(vec![self.certificate()], self.private_key())
                .map_err(|e| NetworkError::TlsConfig(e.to_string()))?;
        Ok(config)
    }

    /// Client configuration with a pinned peer certificate.
    pub fn client_config(
        &self,
        peer_pin: Fingerprint,
    ) -> Result<rustls::ClientConfig, NetworkError> {
        install_crypto_provider();
        let verifier = Arc::new(PinnedServerVerifier {
            expected: peer_pin,
            algorithms: algorithms(),
        });
        let config =
            rustls::ClientConfig::builder_with_protocol_versions(&[&rustls::version::TLS13])
                .dangerous()
                .with_custom_certificate_verifier(verifier)
                .with_client_auth_cert(vec![self.certificate()], self.private_key())
                .map_err(|e| NetworkError::TlsConfig(e.to_string()))?;
        Ok(config)
    }
}

fn restrict_key_permissions(path: &PathBuf) {
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        if let Ok(metadata) = fs::metadata(path) {
            let mut permissions = metadata.permissions();
            permissions.set_mode(0o600);
            let _ = fs::set_permissions(path, permissions);
        }
    }
    #[cfg(not(unix))]
    {
        let _ = path;
    }
}

#[derive(Clone)]
struct PinnedServerVerifier {
    expected: Fingerprint,
    algorithms: WebPkiSupportedAlgorithms,
}

impl fmt::Debug for PinnedServerVerifier {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("PinnedServerVerifier")
            .field("expected", &self.expected.short())
            .finish()
    }
}

impl ServerCertVerifier for PinnedServerVerifier {
    fn verify_server_cert(
        &self,
        end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp_response: &[u8],
        _now: UnixTime,
    ) -> Result<ServerCertVerified, rustls::Error> {
        let observed = Fingerprint::of(end_entity.as_ref());
        if observed.equals(self.expected) {
            Ok(ServerCertVerified::assertion())
        } else {
            Err(rustls::Error::InvalidCertificate(
                CertificateError::ApplicationVerificationFailure,
            ))
        }
    }

    fn verify_tls12_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls12_signature(message, cert, dss, &self.algorithms)
    }

    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls13_signature(message, cert, dss, &self.algorithms)
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.algorithms.supported_schemes()
    }
}

#[derive(Clone)]
struct TrustedClientVerifier {
    trust: SharedTrust,
    algorithms: WebPkiSupportedAlgorithms,
}

impl fmt::Debug for TrustedClientVerifier {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("TrustedClientVerifier").finish()
    }
}

impl ClientCertVerifier for TrustedClientVerifier {
    fn root_hint_subjects(&self) -> &[DistinguishedName] {
        &[]
    }

    fn verify_client_cert(
        &self,
        end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _now: UnixTime,
    ) -> Result<ClientCertVerified, rustls::Error> {
        let fingerprint = Fingerprint::of(end_entity.as_ref());
        if self.trust.by_tls_fingerprint(&fingerprint).is_some() {
            Ok(ClientCertVerified::assertion())
        } else {
            Err(rustls::Error::InvalidCertificate(
                CertificateError::ApplicationVerificationFailure,
            ))
        }
    }

    fn verify_tls12_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls12_signature(message, cert, dss, &self.algorithms)
    }

    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls13_signature(message, cert, dss, &self.algorithms)
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.algorithms.supported_schemes()
    }

    fn offer_client_auth(&self) -> bool {
        true
    }

    fn client_auth_mandatory(&self) -> bool {
        true
    }
}

#[derive(Clone)]
struct AnyClientCertVerifier {
    algorithms: WebPkiSupportedAlgorithms,
}

impl fmt::Debug for AnyClientCertVerifier {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("AnyClientCertVerifier").finish()
    }
}

impl ClientCertVerifier for AnyClientCertVerifier {
    fn root_hint_subjects(&self) -> &[DistinguishedName] {
        &[]
    }

    fn verify_client_cert(
        &self,
        end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _now: UnixTime,
    ) -> Result<ClientCertVerified, rustls::Error> {
        if end_entity.as_ref().is_empty() {
            Err(rustls::Error::InvalidCertificate(
                CertificateError::BadEncoding,
            ))
        } else {
            Ok(ClientCertVerified::assertion())
        }
    }

    fn verify_tls12_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls12_signature(message, cert, dss, &self.algorithms)
    }

    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls13_signature(message, cert, dss, &self.algorithms)
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.algorithms.supported_schemes()
    }

    fn offer_client_auth(&self) -> bool {
        true
    }

    fn client_auth_mandatory(&self) -> bool {
        true
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn certificate_generation_is_stable_within_an_instance() {
        let tls = DeviceTls::generate().unwrap();
        assert_eq!(tls.fingerprint(), Fingerprint::of(tls.certificate_der()));
        let loaded = DeviceTls::from_parts(tls.cert_der.clone(), tls.key_der.clone()).unwrap();
        assert_eq!(loaded.fingerprint(), tls.fingerprint());
    }

    #[test]
    fn tls_files_round_trip() {
        let dir = tempfile::tempdir().unwrap();
        let first = DeviceTls::load_or_create(dir.path()).unwrap();
        let second = DeviceTls::load_or_create(dir.path()).unwrap();
        assert_eq!(first.fingerprint(), second.fingerprint());
    }

    #[test]
    fn configs_build_without_panicking() {
        let tls = DeviceTls::generate().unwrap();
        let trust: SharedTrust = Arc::new(flowww_security::MemoryTrust::new());
        let _server = tls.server_config(trust).unwrap();
        let _pairing = tls.pairing_server_config().unwrap();
        let _client = tls.client_config(Fingerprint([2u8; 32])).unwrap();
    }
}
