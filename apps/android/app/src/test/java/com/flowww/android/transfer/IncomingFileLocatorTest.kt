package com.flowww.android.transfer

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Locating the downloaded file of a finished transfer.
 *
 * The Rust core dedupes name collisions inside the download directory, so the
 * locator must pick the file whose digest matches the transfer — not merely
 * the file with the offered name.
 */
class IncomingFileLocatorTest {

    private lateinit var dir: File
    private lateinit var locator: IncomingFileLocator

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("locator-test").toFile()
        locator = IncomingFileLocator(dir)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun digest(content: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(content)
            .joinToString("") { "%02x".format(it) }

    private fun file(name: String, content: ByteArray): File =
        File(dir, name).apply { writeBytes(content) }

    @Test
    fun findsTheFileWithTheOfferedName() {
        val payload = "hello".toByteArray()
        val downloaded = file("photo.jpg", payload)
        assertEquals(downloaded, locator.resolve("photo.jpg", digest(payload)))
    }

    @Test
    fun resolvesTheDedupVariantByDigest() {
        val first = "first".toByteArray()
        val second = "second".toByteArray()
        file("photo.jpg", first)
        val dedup = file("photo (1).jpg", second)
        assertEquals(dedup, locator.resolve("photo.jpg", digest(second)))
    }

    @Test
    fun aDigestThatMatchesNoCandidateFallsBackToTheNewest() {
        val older = file("photo.jpg", "first".toByteArray())
        val newer = file("photo (1).jpg", "second".toByteArray())
        older.setLastModified(1_000_000L)
        newer.setLastModified(2_000_000L)
        // The locator hands the candidate to the committer, which re-checks
        // the digest against the bytes it writes and fails closed. Falling
        // back keeps the failure visible instead of dropping the file.
        assertEquals(newer, locator.resolve("photo.jpg", digest("third".toByteArray())))
    }

    @Test
    fun missingFileResolvesToNull() {
        assertNull(locator.resolve("nothing.jpg", null))
    }

    @Test
    fun withoutADigestTheNewestCandidateWins() {
        val older = file("photo.jpg", "older".toByteArray())
        val newer = file("photo (1).jpg", "newer".toByteArray())
        older.setLastModified(1_000_000L)
        newer.setLastModified(2_000_000L)
        assertEquals(newer, locator.resolve("photo.jpg", null))
    }

    @Test
    fun namesWithoutAnExtensionAreMatchedToo() {
        val payload = "data".toByteArray()
        val downloaded = file("README", payload)
        assertEquals(downloaded, locator.resolve("README", digest(payload)))
    }

    @Test
    fun onlyTheFinalDotCountsAsTheExtension() {
        val payload = "tarball".toByteArray()
        val downloaded = file("archive.tar.gz", payload)
        assertEquals(downloaded, locator.resolve("archive.tar.gz", digest(payload)))
    }
}
