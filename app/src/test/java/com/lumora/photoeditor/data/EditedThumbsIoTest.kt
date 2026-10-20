package com.lumora.photoeditor.data

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * First-ever executed verification of the edited-thumbnail cache contract
 * (2026-10-20): the library grid shows an edited thumbnail ONLY while its
 * `.rev` sidecar-revision matches the edit sidecar's current mtime. A stale
 * rev, a missing rev, or a corrupt rev must all degrade to "show the
 * original" — never a stale edit in the grid — and nothing here may throw.
 *
 * Runs against the REAL shipped [EditedThumbsIo] with real files in temp
 * dirs, via the standalone JUnit harness (Gradle's test executor is broken
 * in this env).
 */
class EditedThumbsIoTest {

    private lateinit var dir: File
    private lateinit var io: EditedThumbsIo

    @Before fun setUp() {
        dir = Files.createTempDirectory("editedthumbsio").toFile()
        io = EditedThumbsIo(dir)
    }

    private val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3)

    // ------------------------------------------------------------------
    // fresh write / read
    // ------------------------------------------------------------------

    @Test fun `save then validThumb with matching mtime returns the exact bytes`() {
        assertTrue(io.save("img1", bytes, 123456789L))
        val f = io.validThumb("img1", 123456789L)
        assertNotNull(f)
        assertArrayEquals(bytes, f!!.readBytes())
    }

    @Test fun `save pins the mtime exactly — including 0`() {
        assertTrue(io.save("zero", bytes, 0L))
        assertNotNull(io.validThumb("zero", 0L))
        // …and a missing rev must not accidentally equal 0
        assertNull(io.validThumb("nobody", 0L))
    }

    @Test fun `thumbFile names the jpeg inside the store dir`() {
        val f = io.thumbFile("abc")
        assertEquals("abc.jpg", f.name)
        assertEquals(dir.canonicalFile, f.parentFile.canonicalFile)
    }

    // ------------------------------------------------------------------
    // the core contract: never a stale edit
    // ------------------------------------------------------------------

    @Test fun `validThumb with a stale mtime returns null — never a stale edit`() {
        assertTrue(io.save("img1", bytes, 100L))
        assertNull(io.validThumb("img1", 101L)) // edit happened elsewhere
        assertNull(io.validThumb("img1", 99L))  // clock skew the other way
    }

    @Test fun `re-saving under a new mtime retires the old revision`() {
        assertTrue(io.save("img1", bytes, 100L))
        val newer = bytes + 9
        assertTrue(io.save("img1", newer, 200L))
        assertNull(io.validThumb("img1", 100L)) // old edit must not resurface
        val f = io.validThumb("img1", 200L)
        assertNotNull(f)
        assertArrayEquals(newer, f!!.readBytes())
    }

    @Test fun `validThumb with nothing saved returns null`() {
        assertNull(io.validThumb("ghost", 1L))
    }

    @Test fun `validThumb with a corrupt rev returns null and does not throw`() {
        io.thumbFile("img1").writeBytes(bytes)
        File(dir, "img1.rev").writeText("not-a-number")
        assertNull(io.validThumb("img1", 1L))
    }

    @Test fun `validThumb with a missing rev returns null`() {
        io.thumbFile("img1").writeBytes(bytes) // thumb landed, rev write failed
        assertNull(io.validThumb("img1", 1L))
    }

    // ------------------------------------------------------------------
    // clear / failure handling
    // ------------------------------------------------------------------

    @Test fun `clear removes thumb and rev — validThumb null afterwards`() {
        assertTrue(io.save("img1", bytes, 100L))
        assertNotNull(io.validThumb("img1", 100L))
        io.clear("img1")
        assertFalse(io.thumbFile("img1").exists())
        assertFalse(File(dir, "img1.rev").exists())
        assertNull(io.validThumb("img1", 100L))
    }

    @Test fun `clear on a missing id does not throw`() {
        io.clear("ghost")
    }

    @Test fun `save refuses empty bytes and writes nothing`() {
        assertFalse(io.save("img1", ByteArray(0), 100L))
        assertFalse(io.thumbFile("img1").exists())
        assertNull(io.validThumb("img1", 100L))
    }

}
