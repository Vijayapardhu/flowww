# Platform Key Storage

## Status

Rust node identity and TLS material are now protected by platform secret
stores on both Android and Windows:

- **Android:** `KeystoreSecretStore` (AES-256/GCM key in the Android
  Keystore) implements `flowww_security::SecretStore`. The node identity seed
  is only loaded through the `CallbackSecretStore` FFI adapter; it never
  appears in UniFFI DTOs, events, errors, or logs. Committed
  `a39bdfb`.
- **Windows:** `DpapiSecretStore` (CryptProtectData/CryptUnprotectData, user
  scope) implements `flowww_security::SecretStore`. The legacy plaintext
  `identity.json` is migrated once and then deleted; `tls-key.der` is
  migrated to `tls-key.dpapi`. Committed `9888581`.

## How it works

`NodeConfig::with_secret_store(Arc<dyn SecretStore>)` injects the platform
store into the Rust node. The `SecretStore` trait (`load`/`store`/`clear`,
`Vec<u8>`) is the boundary: Rust owns the protocol and crypto; Kotlin (Android)
or the Windows agent owns the platform storage. No plaintext private-key
files persist on disk.

### Android flow

1. `NodeBridge` is constructed with a `KeystoreSecretStore` (lazy, created
   once per process).
2. `startWithSecretStore` passes the store to `NodeConfig` as
   `CallbackSecretStore`.
3. On first start, the Keystore key is generated inside the TEE; the identity
   seed is encrypted and stored. On subsequent starts, the seed is decrypted
   by the Keystore and held only in runtime memory.
4. `resetIdentity(dataDir, store?)` clears the Keystore record and deletes
   all identity/TLS/trust files.

### Windows flow

1. `DpapiSecretStore::new(data_dir.join("identity.dpapi"))` wraps
   `CryptProtectData`/`CryptUnprotectData` (user scope).
2. On first start with no existing `identity.dpapi`, the legacy
   `identity.json` is migrated once: plaintext is read, the store encrypts it,
   then `identity.json` is deleted.
3. `tls-key.der` is migrated to `tls-key.dpapi` the same way.
4. `flowww_security::dpapi_protect`/`dpapi_unprotect` are exported for the
   network crate.

## Security properties

- Platform-protected at rest: the seed never exists in plaintext on disk.
- Decrypted only during node initialization; held in runtime memory only.
- Never placed in UniFFI DTOs, events, errors, or logs.
- Fails closed on corruption, invalidated keys, or profile changes.
- No silent identity regeneration; reset and re-pair are explicit.
