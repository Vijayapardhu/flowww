//! Local SQLite persistence for devices, trusts, transfers and settings.

use rusqlite::{params, Connection, OptionalExtension};
use serde::{Deserialize, Serialize};
use std::path::Path;
use std::sync::{Arc, Mutex, MutexGuard};
use thiserror::Error;

use flowww_core::{
    unix_millis, Capability, Device, DeviceId, DeviceInfo, PairingState, Platform, TransferId,
    TransferRoute, TransferState,
};
use flowww_security::{Fingerprint, PairedPeer, PublicIdentity, TrustDirectory};

#[derive(Debug, Error)]
pub enum StorageError {
    #[error("database error: {0}")]
    Sqlite(#[from] rusqlite::Error),
    #[error("storage I/O error: {0}")]
    Io(#[from] std::io::Error),
    #[error("stored record is invalid: {0}")]
    Invalid(String),
}

pub type StorageResult<T> = Result<T, StorageError>;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum TransferDirection {
    Incoming,
    Outgoing,
}

impl TransferDirection {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Incoming => "incoming",
            Self::Outgoing => "outgoing",
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TransferRecord {
    pub id: TransferId,
    pub source_device: DeviceId,
    pub destination_device: DeviceId,
    pub filename: Option<String>,
    pub mime_type: Option<String>,
    pub size: u64,
    pub sha256: Option<String>,
    pub kind: String,
    pub status: TransferState,
    pub route: TransferRoute,
    pub direction: TransferDirection,
    pub bytes_transferred: u64,
    pub inline_content: Option<String>,
    pub error: Option<String>,
    pub created_at: u64,
    pub completed_at: Option<u64>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PairingRecord {
    pub id: String,
    pub device_id: DeviceId,
    pub trust_status: String,
    pub created_at: u64,
    pub revoked_at: Option<u64>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SpatialSessionRecord {
    pub id: String,
    pub source_device: Option<DeviceId>,
    pub controller_device: Option<DeviceId>,
    pub destination_device: Option<DeviceId>,
    pub content_reference: Option<String>,
    pub state: String,
    pub created_at: u64,
    pub expires_at: u64,
}

/// A thread-safe handle to the local database.
#[derive(Clone)]
pub struct Store {
    conn: Arc<Mutex<Connection>>,
}

impl Store {
    pub fn open(path: impl AsRef<Path>) -> StorageResult<Self> {
        let path = path.as_ref();
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent)?;
        }
        let conn = Connection::open(path)?;
        Self::from_connection(conn, true)
    }

    pub fn open_in_memory() -> StorageResult<Self> {
        let conn = Connection::open_in_memory()?;
        Self::from_connection(conn, false)
    }

    fn from_connection(conn: Connection, file_backed: bool) -> StorageResult<Self> {
        conn.busy_timeout(std::time::Duration::from_secs(5))?;
        conn.execute_batch("PRAGMA foreign_keys = ON;")?;
        if file_backed {
            // WAL keeps the UI responsive while a large transfer is writing history.
            let _ = conn.pragma_update(None, "journal_mode", "WAL");
        }
        let store = Self {
            conn: Arc::new(Mutex::new(conn)),
        };
        store.migrate()?;
        Ok(store)
    }

    fn lock(&self) -> MutexGuard<'_, Connection> {
        self.conn
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    fn migrate(&self) -> StorageResult<()> {
        self.lock().execute_batch(
            r#"
            CREATE TABLE IF NOT EXISTS devices (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                platform TEXT NOT NULL,
                protocol_version TEXT NOT NULL,
                capabilities TEXT NOT NULL DEFAULT '',
                public_key TEXT NOT NULL,
                fingerprint TEXT NOT NULL,
                tls_fingerprint TEXT NOT NULL DEFAULT '',
                trusted INTEGER NOT NULL DEFAULT 0,
                last_seen INTEGER,
                created_at INTEGER NOT NULL
            );

            CREATE TABLE IF NOT EXISTS transfers (
                id TEXT PRIMARY KEY,
                source_device TEXT NOT NULL,
                destination_device TEXT NOT NULL,
                filename TEXT,
                mime_type TEXT,
                size INTEGER NOT NULL DEFAULT 0,
                sha256 TEXT,
                kind TEXT NOT NULL DEFAULT 'other',
                status TEXT NOT NULL,
                route TEXT NOT NULL,
                direction TEXT NOT NULL,
                bytes_transferred INTEGER NOT NULL DEFAULT 0,
                inline_content TEXT,
                error TEXT,
                created_at INTEGER NOT NULL,
                completed_at INTEGER
            );

            CREATE TABLE IF NOT EXISTS pairings (
                id TEXT PRIMARY KEY,
                device_id TEXT NOT NULL,
                trust_status TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                revoked_at INTEGER
            );

            CREATE TABLE IF NOT EXISTS spatial_sessions (
                id TEXT PRIMARY KEY,
                source_device TEXT,
                controller_device TEXT,
                destination_device TEXT,
                content_reference TEXT,
                state TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                expires_at INTEGER NOT NULL
            );

            CREATE TABLE IF NOT EXISTS settings (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS authorization_nonces (
                nonce TEXT PRIMARY KEY,
                expires_at INTEGER NOT NULL
            );

            CREATE INDEX IF NOT EXISTS idx_devices_tls ON devices(tls_fingerprint);
            CREATE INDEX IF NOT EXISTS idx_transfers_created ON transfers(created_at DESC);
            CREATE INDEX IF NOT EXISTS idx_pairings_device ON pairings(device_id);
            "#,
        )?;
        Ok(())
    }

    // ----------------------------------------------------------------- devices

    /// Persist an explicitly paired device. Re-pairing is idempotent and
    /// refreshes the public key and TLS pin.
    pub fn save_pair(
        &self,
        device: &DeviceInfo,
        public_key: &str,
        tls_fingerprint: &Fingerprint,
        last_seen: Option<u64>,
    ) -> StorageResult<PairedPeer> {
        let identity = PublicIdentity::from_base64(public_key)
            .map_err(|e| StorageError::Invalid(e.to_string()))?;
        let fingerprint = identity.fingerprint();
        let capabilities = device
            .capabilities
            .iter()
            .map(|c| c.as_str().to_string())
            .collect::<Vec<_>>()
            .join(",");
        let now = unix_millis();
        let mut conn = self.lock();
        let tx = conn.transaction()?;
        tx.execute(
            r#"
            INSERT INTO devices
                (id, name, platform, protocol_version, capabilities, public_key,
                 fingerprint, tls_fingerprint, trusted, last_seen, created_at)
            VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, 1, ?9, ?10)
            ON CONFLICT(id) DO UPDATE SET
                name = excluded.name,
                platform = excluded.platform,
                protocol_version = excluded.protocol_version,
                capabilities = excluded.capabilities,
                public_key = excluded.public_key,
                fingerprint = excluded.fingerprint,
                tls_fingerprint = excluded.tls_fingerprint,
                trusted = 1,
                last_seen = excluded.last_seen
            "#,
            params![
                device.device_id.as_str(),
                device.device_name,
                device.platform.as_str(),
                device.protocol_version,
                capabilities,
                public_key,
                fingerprint.to_hex(),
                tls_fingerprint.to_hex(),
                last_seen.map(|v| v as i64),
                now as i64
            ],
        )?;
        tx.execute(
            r#"
            INSERT INTO pairings (id, device_id, trust_status, created_at)
            VALUES (?1, ?2, 'PAIRED', ?3)
            "#,
            params![
                format!("pair_{}", uuid::Uuid::new_v4().simple()),
                device.device_id.as_str(),
                now as i64
            ],
        )?;
        tx.commit()?;
        Ok(PairedPeer {
            device: device.clone(),
            public_key: public_key.to_string(),
            tls_fingerprint: *tls_fingerprint,
        })
    }

    pub fn touch_device(&self, device_id: &DeviceId, last_seen: u64) -> StorageResult<()> {
        self.lock().execute(
            "UPDATE devices SET last_seen = ?1 WHERE id = ?2",
            params![last_seen as i64, device_id.as_str()],
        )?;
        Ok(())
    }

    pub fn revoke_device(&self, device_id: &DeviceId) -> StorageResult<()> {
        let now = unix_millis() as i64;
        let mut conn = self.lock();
        let tx = conn.transaction()?;
        tx.execute(
            "UPDATE devices SET trusted = 0 WHERE id = ?1",
            params![device_id.as_str()],
        )?;
        tx.execute(
            "UPDATE pairings SET trust_status = 'REVOKED', revoked_at = ?1 WHERE device_id = ?2 AND revoked_at IS NULL",
            params![now, device_id.as_str()],
        )?;
        tx.commit()?;
        Ok(())
    }

    pub fn list_devices(&self) -> StorageResult<Vec<Device>> {
        let conn = self.lock();
        let mut statement = conn.prepare(
            r#"
            SELECT id, name, platform, protocol_version, capabilities, fingerprint,
                   trusted, last_seen, created_at
            FROM devices
            ORDER BY trusted DESC, last_seen DESC, name ASC
            "#,
        )?;
        let rows = statement.query_map([], |row| {
            let id: String = row.get(0)?;
            let name: String = row.get(1)?;
            let platform: String = row.get(2)?;
            let protocol_version: String = row.get(3)?;
            let capabilities: String = row.get(4)?;
            let fingerprint: String = row.get(5)?;
            let trusted: i64 = row.get(6)?;
            let last_seen: Option<i64> = row.get(7)?;
            let created_at: i64 = row.get(8)?;
            Ok(StoredDeviceRow {
                id,
                name,
                platform,
                protocol_version,
                capabilities,
                fingerprint,
                trusted,
                last_seen,
                created_at,
            })
        })?;
        let mut devices = Vec::new();
        for row in rows {
            devices.push(row?.into_device()?);
        }
        Ok(devices)
    }

    pub fn trusted_peer(&self, device_id: &DeviceId) -> StorageResult<Option<PairedPeer>> {
        self.query_peer(
            r#"
            SELECT id, name, platform, protocol_version, capabilities, public_key,
                   tls_fingerprint, trusted, last_seen, created_at
            FROM devices WHERE id = ?1 AND trusted = 1
            "#,
            params![device_id.as_str()],
        )
    }

    pub fn trusted_peer_by_tls(
        &self,
        fingerprint: &Fingerprint,
    ) -> StorageResult<Option<PairedPeer>> {
        self.query_peer(
            r#"
            SELECT id, name, platform, protocol_version, capabilities, public_key,
                   tls_fingerprint, trusted, last_seen, created_at
            FROM devices WHERE tls_fingerprint = ?1 AND trusted = 1
            "#,
            params![fingerprint.to_hex()],
        )
    }

    fn query_peer(
        &self,
        sql: &str,
        params: impl rusqlite::Params,
    ) -> StorageResult<Option<PairedPeer>> {
        let peer = self
            .lock()
            .query_row(sql, params, |row| {
                let id: String = row.get(0)?;
                let name: String = row.get(1)?;
                let platform: String = row.get(2)?;
                let protocol_version: String = row.get(3)?;
                let capabilities: String = row.get(4)?;
                let public_key: String = row.get(5)?;
                let tls_fingerprint: String = row.get(6)?;
                let last_seen: Option<i64> = row.get(8)?;
                let created_at: i64 = row.get(9)?;
                Ok(PairedPeerRow {
                    id,
                    name,
                    platform,
                    protocol_version,
                    capabilities,
                    public_key,
                    tls_fingerprint,
                    last_seen,
                    created_at,
                })
            })
            .optional()?;
        peer.map(|row| row.into_peer()).transpose()
    }

    pub fn pairing_records(&self) -> StorageResult<Vec<PairingRecord>> {
        let conn = self.lock();
        let mut statement = conn.prepare(
            "SELECT id, device_id, trust_status, created_at, revoked_at FROM pairings ORDER BY created_at DESC",
        )?;
        let rows = statement.query_map([], |row| {
            Ok(PairingRecord {
                id: row.get(0)?,
                device_id: DeviceId::new(row.get::<_, String>(1)?)
                    .map_err(|e| rusqlite::Error::InvalidParameterName(e.to_string()))?,
                trust_status: row.get(2)?,
                created_at: row.get::<_, i64>(3)? as u64,
                revoked_at: row.get::<_, Option<i64>>(4)?.map(|v| v as u64),
            })
        })?;
        let mut records = Vec::new();
        for row in rows {
            records.push(row?);
        }
        Ok(records)
    }

    // ---------------------------------------------------------------- transfers

    pub fn upsert_transfer(&self, record: &TransferRecord) -> StorageResult<()> {
        let mut conn = self.lock();
        let tx = conn.transaction()?;
        tx.execute(
            r#"
            INSERT INTO transfers
                (id, source_device, destination_device, filename, mime_type, size,
                 sha256, kind, status, route, direction, bytes_transferred,
                 inline_content, error, created_at, completed_at)
            VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14, ?15, ?16)
            ON CONFLICT(id) DO UPDATE SET
                status = excluded.status,
                route = excluded.route,
                bytes_transferred = excluded.bytes_transferred,
                error = excluded.error,
                completed_at = excluded.completed_at,
                filename = COALESCE(excluded.filename, transfers.filename),
                inline_content = COALESCE(excluded.inline_content, transfers.inline_content),
                sha256 = COALESCE(excluded.sha256, transfers.sha256)
            "#,
            params![
                record.id.to_string(),
                record.source_device.as_str(),
                record.destination_device.as_str(),
                record.filename,
                record.mime_type,
                record.size as i64,
                record.sha256,
                record.kind,
                record.status.as_str(),
                record.route.as_str(),
                record.direction.as_str(),
                record.bytes_transferred as i64,
                record.inline_content,
                record.error,
                record.created_at as i64,
                record.completed_at.map(|v| v as i64)
            ],
        )?;
        tx.commit()?;
        Ok(())
    }

    pub fn update_transfer(
        &self,
        id: TransferId,
        status: TransferState,
        bytes_transferred: u64,
        error: Option<&str>,
    ) -> StorageResult<()> {
        let completed_at = status.is_terminal().then(unix_millis);
        self.lock().execute(
            r#"
            UPDATE transfers
            SET status = ?1, bytes_transferred = ?2, error = ?3, completed_at = ?4
            WHERE id = ?5
            "#,
            params![
                status.as_str(),
                bytes_transferred as i64,
                error,
                completed_at.map(|v| v as i64),
                id.to_string()
            ],
        )?;
        Ok(())
    }

    pub fn get_transfer(&self, id: TransferId) -> StorageResult<Option<TransferRecord>> {
        self.lock()
            .query_row(
                "SELECT id, source_device, destination_device, filename, mime_type, size, sha256,
                        kind, status, route, direction, bytes_transferred, inline_content,
                        error, created_at, completed_at
                 FROM transfers WHERE id = ?1",
                params![id.to_string()],
                map_transfer_row,
            )
            .optional()?
            .transpose()
    }

    pub fn list_transfers(&self, limit: u32) -> StorageResult<Vec<TransferRecord>> {
        let conn = self.lock();
        let mut statement = conn.prepare(
            "SELECT id, source_device, destination_device, filename, mime_type, size, sha256,
                    kind, status, route, direction, bytes_transferred, inline_content,
                    error, created_at, completed_at
             FROM transfers ORDER BY created_at DESC LIMIT ?1",
        )?;
        let rows = statement.query_map(params![limit], map_transfer_row)?;
        let mut records = Vec::new();
        for row in rows {
            records.push(row??);
        }
        Ok(records)
    }

    // ----------------------------------------------------------------- settings

    pub fn set_setting(&self, key: &str, value: &str) -> StorageResult<()> {
        self.lock().execute(
            "INSERT INTO settings (key, value) VALUES (?1, ?2)
             ON CONFLICT(key) DO UPDATE SET value = excluded.value",
            params![key, value],
        )?;
        Ok(())
    }

    pub fn get_setting(&self, key: &str) -> StorageResult<Option<String>> {
        Ok(self
            .lock()
            .query_row(
                "SELECT value FROM settings WHERE key = ?1",
                params![key],
                |row| row.get(0),
            )
            .optional()?)
    }

    pub fn get_setting_or(&self, key: &str, default: &str) -> StorageResult<String> {
        Ok(self
            .get_setting(key)?
            .unwrap_or_else(|| default.to_string()))
    }

    // ---------------------------------------------------------------- nonces

    pub fn record_nonce(&self, nonce: &str, expires_at: u64) -> StorageResult<bool> {
        let conn = self.lock();
        conn.execute(
            "DELETE FROM authorization_nonces WHERE expires_at <= ?1",
            params![unix_millis() as i64],
        )?;
        let inserted = conn.execute(
            "INSERT OR IGNORE INTO authorization_nonces (nonce, expires_at) VALUES (?1, ?2)",
            params![nonce, expires_at as i64],
        )?;
        Ok(inserted == 1)
    }

    // ---------------------------------------------------------------- spatial

    pub fn upsert_spatial_session(&self, record: &SpatialSessionRecord) -> StorageResult<()> {
        self.lock().execute(
            r#"
            INSERT INTO spatial_sessions
                (id, source_device, controller_device, destination_device,
                 content_reference, state, created_at, expires_at)
            VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)
            ON CONFLICT(id) DO UPDATE SET
                source_device = excluded.source_device,
                controller_device = excluded.controller_device,
                destination_device = excluded.destination_device,
                content_reference = excluded.content_reference,
                state = excluded.state,
                expires_at = excluded.expires_at
            "#,
            params![
                record.id,
                record.source_device.as_ref().map(|d| d.as_str()),
                record.controller_device.as_ref().map(|d| d.as_str()),
                record.destination_device.as_ref().map(|d| d.as_str()),
                record.content_reference,
                record.state,
                record.created_at as i64,
                record.expires_at as i64
            ],
        )?;
        Ok(())
    }

    pub fn delete_expired_spatial_sessions(&self) -> StorageResult<usize> {
        Ok(self.lock().execute(
            "DELETE FROM spatial_sessions WHERE expires_at <= ?1",
            params![unix_millis() as i64],
        )?)
    }
}

impl TrustDirectory for Store {
    fn by_tls_fingerprint(&self, fingerprint: &Fingerprint) -> Option<PairedPeer> {
        self.trusted_peer_by_tls(fingerprint).ok().flatten()
    }

    fn by_device_id(&self, device_id: &DeviceId) -> Option<PairedPeer> {
        self.trusted_peer(device_id).ok().flatten()
    }
}

struct StoredDeviceRow {
    id: String,
    name: String,
    platform: String,
    protocol_version: String,
    capabilities: String,
    fingerprint: String,
    trusted: i64,
    last_seen: Option<i64>,
    created_at: i64,
}

impl StoredDeviceRow {
    fn into_device(self) -> Result<Device, rusqlite::Error> {
        let info = device_info_from_parts(
            &self.id,
            &self.name,
            &self.platform,
            &self.protocol_version,
            &self.capabilities,
        )?;
        Ok(Device {
            info,
            public_key_fingerprint: self.fingerprint,
            trusted: self.trusted != 0,
            last_seen_ms: self.last_seen.map(|v| v as u64),
            created_at_ms: self.created_at as u64,
        })
    }
}

struct PairedPeerRow {
    id: String,
    name: String,
    platform: String,
    protocol_version: String,
    capabilities: String,
    public_key: String,
    tls_fingerprint: String,
    #[allow(dead_code)]
    last_seen: Option<i64>,
    #[allow(dead_code)]
    created_at: i64,
}

impl PairedPeerRow {
    fn into_peer(self) -> StorageResult<PairedPeer> {
        let device = device_info_from_parts(
            &self.id,
            &self.name,
            &self.platform,
            &self.protocol_version,
            &self.capabilities,
        )
        .map_err(|e| StorageError::Invalid(e.to_string()))?;
        let tls_fingerprint = Fingerprint::parse(&self.tls_fingerprint)
            .map_err(|e| StorageError::Invalid(e.to_string()))?;
        Ok(PairedPeer {
            device,
            public_key: self.public_key,
            tls_fingerprint,
        })
    }
}

fn device_info_from_parts(
    id: &str,
    name: &str,
    platform: &str,
    protocol_version: &str,
    capabilities: &str,
) -> Result<DeviceInfo, rusqlite::Error> {
    let device_id =
        DeviceId::new(id).map_err(|e| rusqlite::Error::InvalidParameterName(e.to_string()))?;
    let platform = match platform {
        "windows" => Platform::Windows,
        "android" => Platform::Android,
        "web" => Platform::Web,
        "macos" => Platform::Macos,
        "linux" => Platform::Linux,
        _ => Platform::Unknown,
    };
    let capabilities = capabilities
        .split(',')
        .filter(|value| !value.is_empty())
        .filter_map(|value| value.parse::<Capability>().ok())
        .collect();
    Ok(DeviceInfo {
        device_id,
        device_name: name.to_string(),
        platform,
        protocol_version: protocol_version.to_string(),
        capabilities,
    })
}

fn map_transfer_row(
    row: &rusqlite::Row<'_>,
) -> rusqlite::Result<Result<TransferRecord, StorageError>> {
    let id = row.get::<_, String>(0)?;
    let status = row.get::<_, String>(8)?;
    let route = row.get::<_, String>(9)?;
    let direction = row.get::<_, String>(10)?;
    let source_device = row.get::<_, String>(1)?;
    let destination_device = row.get::<_, String>(2)?;
    let record = (|| -> Result<TransferRecord, StorageError> {
        Ok(TransferRecord {
            id: id
                .parse()
                .map_err(|_| StorageError::Invalid("transfer id".into()))?,
            source_device: DeviceId::new(source_device)
                .map_err(|e| StorageError::Invalid(e.to_string()))?,
            destination_device: DeviceId::new(destination_device)
                .map_err(|e| StorageError::Invalid(e.to_string()))?,
            filename: row.get(3)?,
            mime_type: row.get(4)?,
            size: row.get::<_, i64>(5)? as u64,
            sha256: row.get(6)?,
            kind: row.get(7)?,
            status: transfer_state_from_str(&status)
                .ok_or_else(|| StorageError::Invalid(format!("unknown status {status}")))?,
            route: transfer_route_from_str(&route)
                .ok_or_else(|| StorageError::Invalid(format!("unknown route {route}")))?,
            direction: match direction.as_str() {
                "incoming" => TransferDirection::Incoming,
                "outgoing" => TransferDirection::Outgoing,
                _ => return Err(StorageError::Invalid("unknown direction".into())),
            },
            bytes_transferred: row.get::<_, i64>(11)? as u64,
            inline_content: row.get(12)?,
            error: row.get(13)?,
            created_at: row.get::<_, i64>(14)? as u64,
            completed_at: row.get::<_, Option<i64>>(15)?.map(|v| v as u64),
        })
    })();
    Ok(record)
}

fn transfer_state_from_str(value: &str) -> Option<TransferState> {
    use TransferState::*;
    Some(match value {
        "CREATED" => Created,
        "OFFERED" => Offered,
        "ACCEPTED" => Accepted,
        "CONNECTING" => Connecting,
        "TRANSFERRING" => Transferring,
        "PAUSED" => Paused,
        "VERIFYING" => Verifying,
        "COMPLETED" => Completed,
        "CANCELLED" => Cancelled,
        "FAILED" => Failed,
        _ => return None,
    })
}

fn transfer_route_from_str(value: &str) -> Option<TransferRoute> {
    Some(match value {
        "direct" => TransferRoute::Direct,
        "phone_relay" => TransferRoute::PhoneRelay,
        "phone_to_desktop" => TransferRoute::PhoneToDesktop,
        _ => return None,
    })
}

/// Pairing state is stored as text; this alias documents intent for future callers.
pub fn pairing_state_label(state: PairingState) -> &'static str {
    state.as_str()
}

#[cfg(test)]
mod tests {
    use super::*;
    use flowww_core::{Capability, ContentId};
    use std::collections::BTreeSet;

    fn store() -> Store {
        Store::open_in_memory().unwrap()
    }

    fn device(id: &str) -> DeviceInfo {
        DeviceInfo::new(
            DeviceId::new(id).unwrap(),
            "Test peer",
            Platform::Windows,
            Capability::standard(),
        )
        .unwrap()
    }

    fn peer() -> (DeviceInfo, String, Fingerprint) {
        let identity = flowww_security::Identity::generate();
        (
            device("fw_peer_0001"),
            identity.public_key_base64(),
            Fingerprint([5u8; 32]),
        )
    }

    #[test]
    fn migration_is_idempotent() {
        let store = store();
        store.migrate().unwrap();
        store.migrate().unwrap();
    }

    #[test]
    fn pairing_is_persisted_and_revocable() {
        let store = store();
        let (device, key, tls) = peer();
        store.save_pair(&device, &key, &tls, None).unwrap();
        assert!(store.trusted_peer(&device.device_id).unwrap().is_some());
        assert!(store.trusted_peer_by_tls(&tls).unwrap().is_some());
        let devices = store.list_devices().unwrap();
        assert_eq!(devices.len(), 1);
        assert!(devices[0].trusted);

        store.revoke_device(&device.device_id).unwrap();
        assert!(store.trusted_peer(&device.device_id).unwrap().is_none());
        let records = store.pairing_records().unwrap();
        assert_eq!(records[0].trust_status, "REVOKED");
        assert!(records[0].revoked_at.is_some());
    }

    #[test]
    fn transfer_history_round_trips() {
        let store = store();
        let source = DeviceId::new("fw_source_01").unwrap();
        let destination = DeviceId::new("fw_dest_0001").unwrap();
        let record = TransferRecord {
            id: TransferId::new(),
            source_device: source,
            destination_device: destination,
            filename: Some("report.pdf".into()),
            mime_type: Some("application/pdf".into()),
            size: 2048,
            sha256: Some("00".repeat(32)),
            kind: "document".into(),
            status: TransferState::Completed,
            route: TransferRoute::Direct,
            direction: TransferDirection::Outgoing,
            bytes_transferred: 2048,
            inline_content: None,
            error: None,
            created_at: unix_millis(),
            completed_at: Some(unix_millis()),
        };
        store.upsert_transfer(&record).unwrap();
        let loaded = store.get_transfer(record.id).unwrap().unwrap();
        assert_eq!(loaded.filename.as_deref(), Some("report.pdf"));
        assert_eq!(loaded.status, TransferState::Completed);
        assert_eq!(store.list_transfers(10).unwrap().len(), 1);

        store
            .update_transfer(record.id, TransferState::Failed, 12, Some("network lost"))
            .unwrap();
        let loaded = store.get_transfer(record.id).unwrap().unwrap();
        assert_eq!(loaded.status, TransferState::Failed);
        assert_eq!(loaded.error.as_deref(), Some("network lost"));
    }

    #[test]
    fn settings_and_nonces_work() {
        let store = store();
        store.set_setting("auto_accept", "true").unwrap();
        assert_eq!(
            store.get_setting_or("auto_accept", "false").unwrap(),
            "true"
        );
        let expiry = unix_millis() + 60_000;
        assert!(store.record_nonce("nonce-1", expiry).unwrap());
        assert!(!store.record_nonce("nonce-1", expiry).unwrap());
    }

    #[test]
    fn trust_directory_is_usable() {
        let store = store();
        let (device, key, tls) = peer();
        store.save_pair(&device, &key, &tls, None).unwrap();
        assert!(TrustDirectory::by_device_id(&store, &device.device_id).is_some());
        assert!(TrustDirectory::by_tls_fingerprint(&store, &tls).is_some());
    }

    #[test]
    fn spatial_session_round_trip_and_expiry() {
        let store = store();
        let record = SpatialSessionRecord {
            id: "spatial-1".into(),
            source_device: Some(DeviceId::new("fw_source_01").unwrap()),
            controller_device: Some(DeviceId::new("fw_phone_001").unwrap()),
            destination_device: None,
            content_reference: Some(ContentId::new().0),
            state: "HOLDING".into(),
            created_at: unix_millis(),
            expires_at: unix_millis() + 60_000,
        };
        store.upsert_spatial_session(&record).unwrap();
        let expired = SpatialSessionRecord {
            expires_at: unix_millis().saturating_sub(1),
            ..record
        };
        store.upsert_spatial_session(&expired).unwrap();
        assert_eq!(store.delete_expired_spatial_sessions().unwrap(), 1);
        assert_eq!(
            BTreeSet::<Capability>::new().len(),
            0 // keeps imports honest for future capability filtering
        );
    }
}
