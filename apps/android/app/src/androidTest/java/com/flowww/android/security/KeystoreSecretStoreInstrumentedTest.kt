package com.flowww.android.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.flowww.android.uniffi.FlowwwException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Real Android Keystore behaviour (not a mock): these tests run on a device
 * or emulator and exercise the AES-256-GCM round trip, tamper and
 * invalidation failure paths, and explicit reset.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreSecretStoreInstrumentedTest {

    private lateinit var directory: File
    private lateinit var store: KeystoreSecretStore
    private lateinit var alias: String

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        alias = "flowww-identity-test-" + UUID.randomUUID().toString()
        directory = File(context.filesDir, "flowww_node_test_$alias").also { it.mkdirs() }
        store = KeystoreSecretStore(directory, alias)
    }

    @After
    fun tearDown() {
        store.clearSecret()
        directory.deleteRecursively()
    }

    @Test
    fun missingRecordLoadsEmpty() {
        assertEquals(0, store.loadSecret().size)
    }

    @Test
    fun storedSeedRoundTrips() {
        val seed = ByteArray(32) { i -> (i + 1).toByte() }
        store.storeSecret(seed)
        assertTrue(seed.contentEquals(store.loadSecret()))
        assertTrue(File(directory, "identity.keystore").exists())
    }

    @Test
    fun persistedSeedIsNotPlaintext() {
        val seed = ByteArray(32) { i -> (i * 7 + 3).toByte() }
        store.storeSecret(seed)
        val onDisk = File(directory, "identity.keystore").readBytes()
        assertNotEquals(seed.contentToString(), onDisk.contentToString())
        // IV + GCM authentication tag must be present on top of the seed.
        assertTrue(onDisk.size > 32)
    }

    @Test
    fun tamperedRecordIsRejected() {
        val seed = ByteArray(32) { i -> (i + 5).toByte() }
        store.storeSecret(seed)
        val file = File(directory, "identity.keystore")
        val blob = file.readBytes().toMutableList()
        blob[blob.size - 1] = (blob.last() + 1).toByte()
        file.writeBytes(blob.toByteArray())
        assertFails { store.loadSecret() }
    }

    @Test
    fun truncatedRecordIsRejected() {
        val seed = ByteArray(32) { it.toByte() }
        store.storeSecret(seed)
        File(directory, "identity.keystore").writeBytes(byteArrayOf(1, 2, 3))
        assertFails { store.loadSecret() }
    }

    @Test
    fun clearSecretRemovesRecordAndKey() {
        val seed = ByteArray(32) { it.toByte() }
        store.storeSecret(seed)
        store.clearSecret()
        assertEquals(0, store.loadSecret().size)
        assertFalse(File(directory, "identity.keystore").exists())
    }

    @Test
    fun missingKeystoreKeyInvalidatesRecord() {
        // Simulates app reinstall or a backup restored without its Keystore
        // key: the ciphertext survives but the key is gone. Loading must fail
        // closed instead of regenerating an identity behind the user's back.
        val seed = ByteArray(32) { it.toByte() }
        store.storeSecret(seed)
        val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore")
            .apply { load(null) }
        keyStore.deleteEntry(alias)
        assertFails { store.loadSecret() }
    }

    @Test
    fun wrongSizeSecretIsRejectedOnStore() {
        assertFails { store.storeSecret(ByteArray(16)) }
    }

    private fun assertFails(block: () -> Unit) {
        try {
            block()
            fail("expected FlowwwException.Storage")
        } catch (expected: FlowwwException.Storage) {
            // expected: fail-closed identity path
        }
    }
}
