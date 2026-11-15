package com.flowww.android.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentSanitizerTest {

    @Test
    fun sanitizeFilename_removesPathSeparators() {
        assertEquals("passwd", ContentSanitizer.sanitizeFilename("../../etc/passwd"))
        assertEquals("report.pdf", ContentSanitizer.sanitizeFilename("C:\\Users\\me\\report.pdf"))
        assertEquals("report.pdf", ContentSanitizer.sanitizeFilename("folder/report.pdf"))
    }

    @Test
    fun sanitizeFilename_removesDangerousCharacters() {
        assertEquals("file_name.txt", ContentSanitizer.sanitizeFilename("file:name.txt"))
        assertEquals("file_name.txt", ContentSanitizer.sanitizeFilename("file?name.txt"))
        assertEquals("file_name_.txt", ContentSanitizer.sanitizeFilename("file<name>.txt"))
        assertEquals("file_name_.txt", ContentSanitizer.sanitizeFilename("file>name>.txt"))
        assertEquals("file_name.txt", ContentSanitizer.sanitizeFilename("file|name.txt"))
        assertEquals("file_.txt", ContentSanitizer.sanitizeFilename("file*.txt"))
    }

    @Test
    fun sanitizeFilename_handlesReservedNames() {
        assertEquals("_CON.txt", ContentSanitizer.sanitizeFilename("CON.txt"))
        assertEquals("_NUL.txt", ContentSanitizer.sanitizeFilename("NUL.txt"))
        assertEquals("_PRN.txt", ContentSanitizer.sanitizeFilename("PRN.txt"))
    }

    @Test
    fun sanitizeFilename_limitsLength() {
        val longName = "a".repeat(500) + ".txt"
        val result = ContentSanitizer.sanitizeFilename(longName)
        assertTrue(result.length <= 180)
    }

    @Test
    fun sanitizeFilename_emptyReturnsDefault() {
        assertEquals("file", ContentSanitizer.sanitizeFilename(""))
        assertEquals("file", ContentSanitizer.sanitizeFilename("   "))
        assertEquals("file", ContentSanitizer.sanitizeFilename("..."))
    }

    @Test
    fun sanitizeFilename_trimsLeadingDots() {
        assertEquals("file.txt", ContentSanitizer.sanitizeFilename("...file.txt"))
    }

    @Test
    fun sanitizeDisplayName_truncatesLongNames() {
        val longName = "a".repeat(500)
        val result = ContentSanitizer.sanitizeDisplayName(longName)
        assertEquals(128, result.length)
    }

    @Test
    fun sanitizeDisplayName_preservesShortNames() {
        assertEquals("hello.txt", ContentSanitizer.sanitizeDisplayName("hello.txt"))
    }

    @Test
    fun isContentUri_acceptsContentUris() {
        assertTrue(ContentSanitizer.isContentUri("content://com.example.provider/file"))
        assertTrue(ContentSanitizer.isContentUri("content://media/external/images/1"))
    }

    @Test
    fun isContentUri_rejectsNonContentUris() {
        assertFalse(ContentSanitizer.isContentUri("file:///sdcard/file.txt"))
        assertFalse(ContentSanitizer.isContentUri("https://example.com/file"))
        assertFalse(ContentSanitizer.isContentUri(""))
        assertFalse(ContentSanitizer.isContentUri("file.txt"))
    }

    @Test
    fun isAllowedMimeType_acceptsAllowedTypes() {
        assertTrue(ContentSanitizer.isAllowedMimeType("image/png"))
        assertTrue(ContentSanitizer.isAllowedMimeType("image/jpeg"))
        assertTrue(ContentSanitizer.isAllowedMimeType("video/mp4"))
        assertTrue(ContentSanitizer.isAllowedMimeType("text/plain"))
        assertTrue(ContentSanitizer.isAllowedMimeType("application/pdf"))
        assertTrue(ContentSanitizer.isAllowedMimeType("application/octet-stream"))
        assertTrue(ContentSanitizer.isAllowedMimeType("text/html"))
        assertTrue(ContentSanitizer.isAllowedMimeType("text/csv"))
    }

    @Test
    fun isAllowedMimeType_rejectsDisallowedTypes() {
        assertFalse(ContentSanitizer.isAllowedMimeType("application/x-executable"))
        assertFalse(ContentSanitizer.isAllowedMimeType("application/x-msdownload"))
        assertFalse(ContentSanitizer.isAllowedMimeType(""))
        assertFalse(ContentSanitizer.isAllowedMimeType("audio/mpeg"))
    }

    @Test
    fun isWithinSizeLimit_acceptsValidSizes() {
        assertTrue(ContentSanitizer.isWithinSizeLimit(0L))
        assertTrue(ContentSanitizer.isWithinSizeLimit(1024L))
        assertTrue(ContentSanitizer.isWithinSizeLimit(100L * 1024 * 1024))
    }

    @Test
    fun isWithinSizeLimit_rejectsInvalidSizes() {
        assertFalse(ContentSanitizer.isWithinSizeLimit(-1L))
        assertFalse(ContentSanitizer.isWithinSizeLimit(101L * 1024 * 1024))
    }
}
