package com.flowww.android.security

import com.flowww.android.uniffi.FlowwwException
import com.flowww.android.uniffi.PlatformSecretStore
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Identity protection backed by the Android Keystore.
 *
 * The 32-byte identity seed is the only asset persisted here, and only as
 * AES-256-GCM ciphertext under a Keystore-held key that never leaves the
 * TEE/secure element boundary. The file layout is `[12-byte IV][ciphertext]`
 * written atomically to `identity.keystore` inside the app-private node
 * directory. `android:allowBackup="false"` in the manifest keeps the record
 * out of Auto Backup entirely.
 *
 * Failure contract (every failure is fatal to node start; Rust never
 * silently regenerates an identity):
 *
 * * Missing key but existing ciphertext (reinstalled app or restored backup
 *   without its Keystore key) → [FlowwwException.Storage].
 * * Truncated/tampered ciphertext or wrong key → GCM authentication failure
 *   → [FlowwwException.Storage].
 * * Explicit user-initiated [clearSecret] removes the Keystore entry and the
 *   ciphertext file; every pairing must be re-established afterwards.
 *
 * The Keystore key itself is never exported, logged or exposed over FFI;
 * the Rust core only ever receives the transient plaintext seed and keeps
 * it in zeroizing memory. All failures are raised as the typed FFI error
 * [FlowwwException.Storage] so Rust maps them to a fatal start failure.
 */
class KeystoreSecretStore(
    directory: File,
    internal val alias: String = DEFAULT_ALIAS,
) : PlatformSecretStore {

    private val ciphertextFile = File(directory, DEFAULT_FILE_NAME)

    override fun loadSecret(): ByteArray {
        if (!ciphertextFile.exists()) {
            return ByteArray(0)
        }
        val blob = try {
            ciphertextFile.readBytes()
        } catch (error: IOException) {
            throw identityError("identity record cannot be read")
        }
        if (blob.size <= IV_BYTES) {
            throw identityError("identity record is truncated")
        }
        val key = readKey() ?: throw identityError(
            "identity record exists but its Keystore key is missing — reset Flowww to re-pair"
        )
        val iv = blob.copyOfRange(0, IV_BYTES)
        val ciphertext = blob.copyOfRange(IV_BYTES, blob.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val plain = try {
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(ciphertext)
        } catch (error: AEADBadTagException) {
            throw identityError("identity record failed authentication")
        } catch (error: SecurityException) {
            throw identityError("identity record cannot be decrypted (key invalidated?)")
        }
        if (plain.size != SEED_BYTES) {
            plain.fill(0)
            throw identityError("identity record has an unexpected length")
        }
        return plain
    }

    override fun storeSecret(secret: ByteArray) {
        if (secret.size != SEED_BYTES) {
            throw identityError("identity seed must be $SEED_BYTES bytes")
        }
        directoryMkdirs()
        val key = readOrCreateKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(secret)
        secret.fill(0)
        val blob = iv + ciphertext
        try {
            writeAtomically(ciphertextFile, blob)
        } catch (error: IOException) {
            ciphertext.fill(0)
            throw identityError("identity record cannot be written")
        }
        ciphertext.fill(0)
    }

    override fun clearSecret() {
        runCatching {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            keyStore.deleteEntry(alias)
        }
        if (ciphertextFile.exists()) {
            runCatching { ciphertextFile.delete() }
        }
        val temp = File(ciphertextFile.parentFile, ciphertextFile.name + TEMP_SUFFIX)
        if (temp.exists()) {
            runCatching { temp.delete() }
        }
    }

    private fun directoryMkdirs() {
        val parent = ciphertextFile.parentFile
        if (parent.exists()) return
        if (!parent.mkdirs() && !parent.exists()) {
            throw identityError("identity directory cannot be created")
        }
    }

    private fun readOrCreateKey(): SecretKey {
        readKey()?.let { return it }
        val generator = KeyGenerator.getInstance(ALGORITHM, ANDROID_KEYSTORE)
        val spec = android.security.keystore.KeyGenParameterSpec.Builder(
            alias,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_BITS)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private fun readKey(): SecretKey? {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey
        } catch (error: KeyStoreException) {
            null
        } catch (error: UnrecoverableKeyException) {
            null
        } catch (error: IOException) {
            null
        } catch (error: java.security.GeneralSecurityException) {
            null
        }
    }

    private fun writeAtomically(target: File, blob: ByteArray) {
        val temp = File(target.parentFile, target.name + TEMP_SUFFIX)
        temp.writeBytes(blob)
        if (!temp.renameTo(target)) {
            // Some filesystems refuse same-volume rename from a directory that
            // is being deleted concurrently; fall back to a direct copy+delete
            // and never leave both halves behind on failure.
            try {
                target.writeBytes(blob)
            } finally {
                temp.delete()
            }
        }
    }

    private fun identityError(message: String): FlowwwException.Storage =
        FlowwwException.Storage(message)

    companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val DEFAULT_ALIAS = "flowww-identity-v1"
        const val DEFAULT_FILE_NAME = "identity.keystore"
        private const val TEMP_SUFFIX = ".tmp"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val ALGORITHM = android.security.keystore.KeyProperties.KEY_ALGORITHM_AES
        private const val IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private const val KEY_BITS = 256
        private const val SEED_BYTES = 32
    }
}
