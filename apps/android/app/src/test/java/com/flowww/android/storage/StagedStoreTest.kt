package com.flowww.android.storage

import com.flowww.android.content.ContentLifecycleState
import com.flowww.android.content.ContentType
import com.flowww.android.content.StagedContent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StagedStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = folder.newFolder("staged_store")
    }

    private fun content(
        id: String,
        contentType: ContentType = ContentType.TEXT,
        contentReference: String = "payload-$id",
        expiresAt: Long = Long.MAX_VALUE,
        size: Long? = 10L
    ) = StagedContent(
        contentId = id,
        contentType = contentType,
        lifecycleState = ContentLifecycleState.STAGED,
        displayName = "name-$id",
        mimeType = "text/plain",
        size = size,
        sourceApplication = null,
        createdAt = System.currentTimeMillis(),
        expiresAt = expiresAt,
        contentReference = contentReference,
        authorizationContext = null
    )

    @Test
    fun insertAndAllRoundTrip() = runTest {
        val store = StagedStore(dir)
        store.insert(content("a"))
        store.insert(content("b"))

        assertEquals(2, store.all().size)
        assertNotNull(store.get("a"))

        assertNotNull(store.remove("a"))
        assertEquals(listOf("b"), store.all().map { it.contentId })
        assertNull(store.get("a"))
    }

    @Test
    fun expireRemovesExpiredItemsOnly() = runTest {
        val store = StagedStore(dir)
        store.insert(content("live", expiresAt = Long.MAX_VALUE))
        store.insert(content("dead", expiresAt = 1L))

        assertEquals(1, store.expire())
        assertEquals(listOf("live"), store.all().map { it.contentId })
    }

    @Test
    fun stageBlobStreamsContentAndDedupesIdenticalBytes() = runTest {
        val store = StagedStore(dir)
        val bytes = ByteArray(2048) { (it % 251).toByte() }

        val first = store.stageBlob(
            displayName = "sample.bin",
            mimeType = "application/octet-stream",
            sourceApplication = "test",
            contentType = ContentType.FILE,
            content = bytes.inputStream()
        )
        assertNotNull(first)
        assertFalse(first!!.duplicate)
        assertEquals(bytes.size.toLong(), first.item.size)

        val second = store.stageBlob(
            displayName = "sample-copy.bin",
            mimeType = "application/octet-stream",
            sourceApplication = "test",
            contentType = ContentType.FILE,
            content = bytes.inputStream()
        )
        assertNotNull(second)
        assertTrue("Identical payload must dedupe", second!!.duplicate)
        assertEquals(first.item.contentId, second.item.contentId)
        assertEquals(1, store.all().size)
    }

    @Test
    fun stageBlobReportsProgressAndWritesAtomically() = runTest {
        val store = StagedStore(dir)
        val bytes = ByteArray(700_000) { (it % 256).toByte() }
        var lastProgress = 0L

        val result = store.stageBlob(
            displayName = "large.bin",
            mimeType = "application/octet-stream",
            sourceApplication = "test",
            contentType = ContentType.FILE,
            content = bytes.inputStream(),
            onProgress = { lastProgress = it }
        )

        assertNotNull(result)
        assertEquals(bytes.size.toLong(), result!!.item.size)
        assertEquals(bytes.size.toLong(), lastProgress)
        // No .part/.tmp artifacts left behind on success.
        assertTrue(store.blobDir.listFiles().orEmpty().none { it.name.endsWith(".part") })
        assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    }

    @Test
    fun stageTextDedupesIdenticalPayload() = runTest {
        val store = StagedStore(dir)

        val first = store.stageText("hello flowww", ContentType.TEXT, "test")
        val second = store.stageText("hello flowww", ContentType.TEXT, "test")

        assertNotNull(first)
        assertNotNull(second)
        assertFalse(first!!.duplicate)
        assertTrue(second!!.duplicate)
        assertEquals(first.item.contentId, second.item.contentId)
        assertEquals(1, store.all().size)
    }

    @Test
    fun cleanupOrphansRemovesUnreferencedBlobs() = runTest {
        val store = StagedStore(dir)
        val bytes = byteArrayOf(1, 2, 3, 4)
        store.stageBlob(
            displayName = "kept.bin",
            mimeType = "application/octet-stream",
            sourceApplication = "test",
            contentType = ContentType.FILE,
            content = bytes.inputStream()
        )
        val orphan = File(store.blobDir, "flowww_orphan_123.bin").apply { writeBytes(bytes) }

        assertEquals(1, store.cleanupOrphans())
        assertFalse(orphan.exists())
        // Referenced blob survives.
        assertEquals(1, store.all().size)
    }

    @Test
    fun corruptMetadataIsQuarantinedNotDestroyed() = runTest {
        val store = StagedStore(dir)
        store.insert(content("corrupt-me", size = 10L))
        val meta = File(dir, "corrupt-me.staged")
        assertTrue(meta.isFile)
        meta.writeBytes("not-a-serialized-object".toByteArray())

        // Re-opening the store must quarantine the corrupt file.
        val reopened = StagedStore(dir)
        assertTrue(reopened.all().isEmpty())
        val quarantine = File(dir, "corrupt")
        assertTrue(
            "corrupt metadata should be moved to corrupt/",
            quarantine.listFiles()?.any { it.name == "corrupt-me.staged" } == true
        )
    }

    @Test
    fun blobStateReportsMissingForDeletedBlob() = runTest {
        val store = StagedStore(dir)
        val item = content("blob-check", contentType = ContentType.FILE, contentReference = "Z:/no/such/blob.bin")

        assertEquals(BlobState.MISSING, store.blobState(item))
    }

    @Test
    fun blobStateAvailableForTextPayload() = runTest {
        val store = StagedStore(dir)
        val item = content("text-item", contentType = ContentType.TEXT, contentReference = "inline payload")

        assertEquals(BlobState.AVAILABLE, store.blobState(item))
    }

    @Test
    fun usageBytesCountsInlineAndBlobPayloads() = runTest {
        val store = StagedStore(dir)
        val bytes = ByteArray(512) { 7 }
        store.stageBlob(
            displayName = "usage.bin",
            mimeType = "application/octet-stream",
            sourceApplication = "test",
            contentType = ContentType.FILE,
            content = bytes.inputStream()
        )
        store.stageText("hello", ContentType.TEXT, "test")

        assertEquals(bytes.size.toLong() + "hello".length, store.usageBytes())
    }

    @Test
    fun metadataIsWrittenAtomicallyWithoutTmpLeftovers() = runTest {
        val store = StagedStore(dir)
        store.insert(content("atomic"))

        assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
        assertTrue(File(dir, "atomic.staged").isFile)
    }
}
