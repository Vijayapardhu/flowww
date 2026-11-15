package com.flowww.android.transfer

import com.flowww.android.data.local.PendingReceiveStore
import com.flowww.android.domain.models.FlowwwSettings
import com.flowww.android.domain.models.PendingReceive
import com.flowww.android.domain.models.PendingReceiveStatus
import com.flowww.android.domain.repository.SettingsRepository
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM coverage for the SAF receive committer: streaming write, digest
 * re-verification, deterministic dedup, fail-closed paths and process-death
 * recovery. No Android framework classes are needed because [DocumentWriter]
 * is faked here.
 */
class ReceiveCommitterTest {

    private lateinit var root: File
    private lateinit var sourceDir: File
    private lateinit var storeDir: File
    private lateinit var store: PendingReceiveStore
    private lateinit var settings: FakeSettingsRepository
    private lateinit var writer: FakeWriter

    @Before
    fun setUp() {
        root = Files.createTempDirectory("receive-test").toFile()
        sourceDir = File(root, "downloads").also { it.mkdirs() }
        storeDir = File(root, "pending_receives")
        store = PendingReceiveStore(storeDir)
        settings = FakeSettingsRepository("content://tree/root")
        writer = FakeWriter()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun newCommitter(
        scope: CoroutineScope,
        produce: () -> Result<DocumentWriter> = { Result.success(writer) },
    ): ReceiveCommitter = ReceiveCommitter(store, settings, { produce() }, sourceDir, scope)

    private fun seedFile(name: String, content: ByteArray): File =
        File(sourceDir, name).apply { writeBytes(content) }

    private fun digest(content: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(content)
            .joinToString("") { "%02x".format(it) }

    private fun enqueuePhoto(
        committer: ReceiveCommitter,
        id: String = "t1",
        sha: String? = digest(CONTENT),
        size: Long = CONTENT.size.toLong(),
    ) {
        committer.enqueue(
            transferId = id,
            fileName = "photo.jpg",
            mimeType = "image/jpeg",
            sizeBytes = size,
            expectedSha256 = sha,
            sourcePath = seedFile("photo.jpg", CONTENT).absolutePath,
        )
    }

    @Test
    fun verifiedFileIsCommittedAndSourceRemoved() = runTest {
        val committer = newCommitter(this)
        enqueuePhoto(committer)
        committer.processQueue()

        assertEquals(listOf("photo.jpg"), writer.createdNames)
        assertEquals("image/jpeg", writer.createdMimes.single())
        assertArrayEquals(CONTENT, writer.written.values.single())
        assertEquals("fake folder", writer.treeLabel())
        assertFalse(File(sourceDir, "photo.jpg").exists())
        assertTrue(store.entries.value.none { it.transferId == "t1" })
    }

    @Test
    fun digestMismatchFailsClosedAndDeletesPartialDocument() = runTest {
        val committer = newCommitter(this)
        enqueuePhoto(committer, sha = "00".repeat(32))
        committer.processQueue()

        val after = store.entries.value.single()
        assertEquals(PendingReceiveStatus.PENDING, after.status)
        assertEquals(1, after.attempts)
        assertTrue(after.error.orEmpty().contains("Verification failed", ignoreCase = true))
        // The partial document never survives a failed commit.
        assertEquals(1, writer.deleted.size)
        // The source file stays so a retry can still succeed.
        assertTrue(File(sourceDir, "photo.jpg").isFile)
    }

    @Test
    fun duplicateNameInDestinationGetsDeterministicSuffix() = runTest {
        writer = FakeWriter(existingNames = setOf("photo.jpg"))
        val committer = newCommitter(this)
        enqueuePhoto(committer)
        committer.processQueue()

        assertEquals("photo (1).jpg", writer.createdNames.single())
        assertFalse(File(sourceDir, "photo.jpg").exists())
    }

    @Test
    fun uniqueFileNameAvoidsCollisionsDeterministically() {
        assertEquals("report.pdf", uniqueFileName(emptySet(), "report.pdf"))
        assertEquals("report (1).pdf", uniqueFileName(setOf("report.pdf"), "report.pdf"))
        assertEquals(
            "report (2).pdf",
            uniqueFileName(setOf("report.pdf", "report (1).pdf"), "report.pdf")
        )
        assertEquals("notes", uniqueFileName(setOf("notes.txt"), "notes"))
        // Only the final dot is the extension, matching the Rust core's
        // unique_path so both sides name duplicates the same way.
        assertEquals(
            "archive.tar (1).gz",
            uniqueFileName(setOf("archive.tar.gz"), "archive.tar.gz")
        )
    }

    @Test
    fun sourceOutsideTheDownloadSandboxIsRejected() = runTest {
        val outsideDir = File(root, "outside").also { it.mkdirs() }
        val outside = File(outsideDir, "photo.jpg")
        outside.writeBytes(CONTENT)
        val committer = newCommitter(this)
        committer.enqueue(
            transferId = "t1",
            fileName = "photo.jpg",
            mimeType = "image/jpeg",
            sizeBytes = CONTENT.size.toLong(),
            expectedSha256 = digest(CONTENT),
            sourcePath = outside.absolutePath,
        )
        committer.processQueue()

        val after = store.entries.value.single()
        assertEquals(PendingReceiveStatus.FAILED, after.status)
        assertTrue(after.error.orEmpty().contains("sandbox", ignoreCase = true))
        assertTrue(writer.createdNames.isEmpty())
        // The foreign file is never touched by the committer.
        assertTrue(outside.isFile)
    }

    @Test
    fun siblingDirectoryWithASimilarNameIsStillOutsideTheSandbox() = runTest {
        // "downloads-backup" shares the prefix "downloads" but is a different
        // directory; the sandbox check must not accept it.
        val sibling = File(root, "downloads-backup").also { it.mkdirs() }
        val outside = File(sibling, "photo.jpg").apply { writeBytes(CONTENT) }
        val committer = newCommitter(this)
        committer.enqueue(
            transferId = "t1",
            fileName = "photo.jpg",
            mimeType = "image/jpeg",
            sizeBytes = CONTENT.size.toLong(),
            expectedSha256 = digest(CONTENT),
            sourcePath = outside.absolutePath,
        )
        committer.processQueue()

        val after = store.entries.value.single()
        assertEquals(PendingReceiveStatus.FAILED, after.status)
        assertTrue(after.error.orEmpty().contains("sandbox", ignoreCase = true))
        assertTrue(writer.createdNames.isEmpty())
        assertTrue(outside.isFile)
    }

    @Test
    fun twoEntriesWithTheSameNameGetDistinctTargets() = runTest {
        val committer = newCommitter(this)
        // Two different transfers that carry the same offered filename; the
        // Rust core stores them as "photo.jpg" and "photo (1).jpg".
        val first = seedFile("photo.jpg", CONTENT)
        val second = seedFile("photo (1).jpg", CONTENT)
        committer.enqueue(
            transferId = "t1",
            fileName = "photo.jpg",
            mimeType = "image/jpeg",
            sizeBytes = CONTENT.size.toLong(),
            expectedSha256 = digest(CONTENT),
            sourcePath = first.absolutePath,
        )
        committer.enqueue(
            transferId = "t2",
            fileName = "photo.jpg",
            mimeType = "image/jpeg",
            sizeBytes = CONTENT.size.toLong(),
            expectedSha256 = digest(CONTENT),
            sourcePath = second.absolutePath,
        )
        committer.processQueue()

        assertEquals(listOf("photo.jpg", "photo (1).jpg"), writer.createdNames)
        assertTrue(store.entries.value.isEmpty())
    }

    @Test
    fun missingSourceFailsWithoutFabricatingSuccess() = runTest {
        val committer = newCommitter(this)
        committer.enqueue(
            transferId = "t1",
            fileName = "gone.jpg",
            mimeType = "image/jpeg",
            sizeBytes = CONTENT.size.toLong(),
            expectedSha256 = digest(CONTENT),
            sourcePath = File(sourceDir, "gone.jpg").absolutePath,
        )
        committer.processQueue()

        val after = store.entries.value.single()
        assertEquals(PendingReceiveStatus.FAILED, after.status)
        assertTrue(after.error.orEmpty().contains("missing", ignoreCase = true))
        assertTrue(writer.createdNames.isEmpty())
    }

    @Test
    fun sizeMismatchFailsTheCommit() = runTest {
        val committer = newCommitter(this)
        enqueuePhoto(committer, size = CONTENT.size.toLong() + 7)
        committer.processQueue()

        val after = store.entries.value.single()
        assertEquals(PendingReceiveStatus.PENDING, after.status)
        assertTrue(after.error.orEmpty().contains("Size mismatch", ignoreCase = true))
        assertTrue(File(sourceDir, "photo.jpg").isFile)
        assertEquals(1, writer.deleted.size)
    }

    @Test
    fun destinationFailureIsRetriedThenCommits() = runTest {
        var failures = 0
        val flaky = object : DocumentWriter by writer {
            override fun createChild(displayName: String, mimeType: String): String {
                if (failures++ < 2) throw IOException("transient write error")
                return writer.createChild(displayName, mimeType)
            }
        }
        val committer = newCommitter(this) { Result.success(flaky) }
        enqueuePhoto(committer)

        // Two failing attempts stay queued; the third succeeds.
        repeat(2) { committer.processQueue() }
        assertEquals(PendingReceiveStatus.PENDING, store.entries.value.single().status)
        committer.processQueue()
        assertArrayEquals(CONTENT, writer.written.values.single())
        assertTrue(store.entries.value.none { it.transferId == "t1" })
        assertFalse(File(sourceDir, "photo.jpg").exists())
    }

    @Test
    fun terminalFailureStaysFailedUntilExplicitRetry() = runTest {
        val unavailable = Result.failure<DocumentWriter>(IOException("tree gone"))
        var produce: () -> Result<DocumentWriter> = { unavailable }
        val committer = newCommitter(this) { produce() }
        enqueuePhoto(committer)

        repeat(PendingReceive.MAX_AUTOMATIC_ATTEMPTS) { committer.processQueue() }
        val failed = store.entries.value.single()
        assertEquals(PendingReceiveStatus.FAILED, failed.status)
        assertEquals(PendingReceive.MAX_AUTOMATIC_ATTEMPTS, failed.attempts)

        // Explicit retry re-queues with a clean attempt budget.
        committer.retry("t1")
        val requeued = store.entries.value.single()
        assertEquals(PendingReceiveStatus.PENDING, requeued.status)
        assertEquals(0, requeued.attempts)

        // Destination becomes available; the retry commits for real.
        produce = { Result.success(writer) }
        committer.processQueue()
        assertArrayEquals(CONTENT, writer.written.values.single())
        assertFalse(File(sourceDir, "photo.jpg").exists())
    }

    @Test
    fun cancelRemovesEntryAndSource() = runTest {
        val committer = newCommitter(this)
        enqueuePhoto(committer)
        committer.cancel("t1")
        assertTrue(store.entries.value.isEmpty())
        assertFalse(File(sourceDir, "photo.jpg").exists())
    }

    @Test
    fun needsDestinationTracksQueueAndSetting() = runTest {
        val unset = FakeSettingsRepository(null)
        val committer = ReceiveCommitter(store, unset, { Result.success(writer) }, sourceDir, this)
        enqueuePhoto(committer)
        assertTrue(committer.needsDestination())

        unset.updateSetting("content://tree/root")
        assertFalse(committer.needsDestination())
    }

    @Test
    fun interruptedCommitResumesAsPendingAfterRestart() {
        val first = PendingReceiveStore(storeDir)
        first.upsert(
            PendingReceive(
                transferId = "t1",
                sourcePath = File(sourceDir, "photo.jpg").absolutePath,
                fileName = "photo.jpg",
                mimeType = "image/jpeg",
                sizeBytes = CONTENT.size.toLong(),
                expectedSha256 = digest(CONTENT),
                status = PendingReceiveStatus.COMMITTING,
                attempts = 1,
                createdAt = 1L,
                updatedAt = 1L
            )
        )
        val reopened = PendingReceiveStore(storeDir)
        assertEquals(PendingReceiveStatus.PENDING, reopened.entries.value.single().status)
    }

    @Test
    fun corruptStoreIsQuarantinedNotDestroyed() {
        val dir = File(root, "corrupt_store").also { it.mkdirs() }
        File(dir, "pending_receives.store").writeBytes(byteArrayOf(1, 2, 3))
        val reopened = PendingReceiveStore(dir)
        assertTrue(reopened.entries.value.isEmpty())
        assertTrue(File(dir, "corrupt").exists())
    }

    @Test
    fun enqueueIsIdempotentPerTransferId() = runTest {
        val committer = newCommitter(this)
        repeat(3) { enqueuePhoto(committer) }
        assertEquals(1, store.entries.value.count { it.transferId == "t1" })
    }

    private companion object {
        val CONTENT = "flowww receive payload".toByteArray()
    }
}

/** In-memory [DocumentWriter] recording every operation. */
private class FakeWriter(
    existingNames: Set<String> = emptySet(),
) : DocumentWriter {
    val createdNames: MutableList<String> = mutableListOf()
    val createdMimes: MutableList<String> = mutableListOf()
    val deleted: MutableList<String> = mutableListOf()
    val written: MutableMap<String, ByteArray> = mutableMapOf()
    private val names: MutableSet<String> = existingNames.toMutableSet()

    override fun existingNames(): Set<String> = names.toSet()

    override fun createChild(displayName: String, mimeType: String): String {
        createdNames += displayName
        createdMimes += mimeType
        names += displayName
        return "content://fake/tree/$displayName"
    }

    override fun openOutput(childUri: String): OutputStream {
        return object : OutputStream() {
            private val buffer = ByteArrayOutputStream()
            override fun write(byte: Int) = buffer.write(byte)
            override fun write(bytes: ByteArray, off: Int, len: Int) = buffer.write(bytes, off, len)
            override fun close() {
                written[childUri] = buffer.toByteArray()
            }
        }
    }

    override fun deleteChild(childUri: String): Boolean {
        deleted += childUri
        return true
    }

    override fun treeLabel(): String = "fake folder"
}

/** Settings stub with a mutable destination. */
private class FakeSettingsRepository(initialUri: String?) : SettingsRepository {
    private val _settings = MutableStateFlow(
        Result.success(FlowwwSettings(defaultSaveLocationUri = initialUri))
    )
    override val settings: StateFlow<Result<FlowwwSettings>> = _settings.asStateFlow()

    fun updateSetting(uri: String?) {
        _settings.value = Result.success(FlowwwSettings(defaultSaveLocationUri = uri))
    }

    override suspend fun update(transform: (FlowwwSettings) -> FlowwwSettings): Result<Unit> {
        _settings.value = Result.success(transform(_settings.value.getOrThrow()))
        return Result.success(Unit)
    }
}
