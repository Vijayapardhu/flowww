package com.flowww.android

import com.flowww.android.security.ContentSanitizer
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContentSanitizerTest {

    @Test
    fun sanitizeFilename_removesPathSeparators() {
        assertEquals("passwd", ContentSanitizer.sanitizeFilename("../../etc/passwd"))
        assertEquals("report.pdf", ContentSanitizer.sanitizeFilename("C:\\Users\\me\\report.pdf"))
    }

    @Test
    fun sanitizeFilename_removesDangerousCharacters() {
        assertEquals("file_name.txt", ContentSanitizer.sanitizeFilename("file:name.txt"))
        assertEquals("file_name.txt", ContentSanitizer.sanitizeFilename("file?name.txt"))
        assertEquals("file_name.txt", ContentSanitizer.sanitizeFilename("file<name>.txt"))
        assertEquals("file_name.txt", ContentSanitizer.sanitizeFilename("file>name.txt"))
        assertEquals("file_name.txt", ContentSanitizer.sanitizeFilename("file|name.txt"))
        assertEquals("file_name.txt", ContentSanitizer.sanitizeFilename("file*.txt"))
    }

    @Test
    fun sanitizeFilename_handlesReservedNames() {
        assertEquals("_CON.txt", ContentSanitizer.sanitizeFilename("CON.txt"))
        assertEquals("_NUL.txt", ContentSanitizeFilenameWrapper("NUL.txt"))
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
    }

    @Test
    fun isContentUri_validates() {
        assertTrue(ContentSanitizer.isContentUri("content://com.example.provider/file"))
        assertFalse(ContentSanitizer.isContentUri("file:///sdcard/file.txt"))
        assertFalse(ContentSanitizer.isContentUri("https://example.com/file"))
    }

    @Test
    fun isAllowedMimeType_validates() {
        assertTrue(ContentSanitizer.isAllowedMimeType("image/png"))
        assertTrue(ContentSanitizer.isAllowedMimeType("video/mp4"))
        assertTrue(ContentSanitizer.isAllowedMimeType("text/plain"))
        assertTrue(ContentSanitizer.isAllowedMimeType("application/pdf"))
        assertTrue(ContentSanitizer.isAllowedMimeType("application/octet-stream"))
        assertFalse(ContentSanitizer.isAllowedMimeType("application/x-executable"))
        assertFalse(ContentSanitizer.isAllowedMimeType(""))
    }

    @Test
    fun isWithinSizeLimit_validates() {
        assertTrue(ContentSanitizer.isWithinSizeLimit(0L))
        assertTrue(ContentSanitizer.isWithinSizeLimit(1024L))
        assertTrue(ContentSanitizer.isWithinSizeLimit(100L * 1024 * 1024))
        assertFalse(ContentSanitizer.isWithinSizeLimit(101L * 1024 * 1024))
        assertFalse(ContentSanitizer.isWithinSizeLimit(-1L))
    }
}

object ContentSanitizeFilenameWrapper {
    fun sanitizeFilename(input: String) = ContentSanitizer.sanitizeFilename(input)
}
