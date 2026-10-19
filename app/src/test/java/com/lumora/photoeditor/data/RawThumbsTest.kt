package com.lumora.photoeditor.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * First-ever executed verification of [RawThumbs] — the on-disk cache of
 * embedded RAW preview JPEGs shown by the library grid.
 *
 * The class KDoc designed this class for the plain-JVM harness
 * (constructor takes the directory, not a Context; "nothing here throws")
 * but no test ever executed that contract. The real source file is compiled
 * by the standalone JVM harness (hidden_files/rawthumbs-harness/run.sh).
 *
 * What it guards:
 *  1. Id sanitization — hostile ids (path traversal, slashes, punctuation)
 *     collapse to alphanumerics/dash/underscore; the canonical-path guard
 *     keeps the resolved file inside the store directory no matter what.
 *  2. Empty-after-sanitize ids fall back to "x" (never an empty filename).
 *  3. save() refuses empty byte arrays (returns false, writes nothing).
 *  4. save()/exists()/clear() round-trip; save() returns false (never
 *     throws) when the bytes cannot be written; clear() of a missing id
 *     never throws.
 */
class RawThumbsTest {

    private val tmp: File = Files.createTempDirectory("rawthumbs-test").toFile()
    private val store = RawThumbs(File(tmp, "thumbs"))

    @After
    fun tearDown() {
        tmp.setWritable(true)
        tmp.walkBottomUp().forEach { it.delete() }
    }

    // ---- thumbFile: id sanitization + canonical-path guard ----

    @Test fun `plain id maps to jpg under root`() {
        val f = store.thumbFile("abc123-_x")
        assertEquals("abc123-_x.jpg", f.name)
        assertTrue(f.canonicalPath.startsWith(storeRoot().canonicalPath + File.separator))
    }

    @Test fun `path traversal id stays inside root`() {
        // "../../etc/passwd" -> slashes/dots stripped -> "etcpasswd"
        val f = store.thumbFile("../../etc/passwd")
        assertEquals("etcpasswd.jpg", f.name)
        assertTrue(f.canonicalPath.startsWith(storeRoot().canonicalPath + File.separator))
    }

    @Test fun `absolute path id stays inside root`() {
        val f = store.thumbFile("/data/data/evil")
        assertEquals("datadataevil.jpg", f.name)
        assertTrue(f.canonicalPath.startsWith(storeRoot().canonicalPath + File.separator))
    }

    @Test fun `punctuation-only id falls back to x`() {
        val f = store.thumbFile("!!!")
        assertEquals("x.jpg", f.name)
    }

    @Test fun `empty id falls back to x`() {
        assertEquals("x.jpg", store.thumbFile("").name)
    }

    @Test fun `unicode letters survive sanitization`() {
        // isLetterOrDigit keeps Unicode letters/digits; the file stays in-root.
        val f = store.thumbFile("héllo_世界-1")
        assertEquals("héllo_世界-1.jpg", f.name)
        assertTrue(f.canonicalPath.startsWith(storeRoot().canonicalPath + File.separator))
    }

    @Test fun `sanitization is deterministic for hostile lookalikes`() {
        // "a/b" and "a\\b" both sanitize to "ab" — documented collision,
        // deterministic by construction.
        assertEquals(store.thumbFile("a/b"), store.thumbFile("a\\b"))
    }

    // ---- save / exists / clear ----

    @Test fun `save refuses empty bytes`() {
        assertFalse(store.save("id1", ByteArray(0)))
        assertFalse(store.exists("id1"))
    }

    @Test fun `save exists clear round trip`() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        assertTrue(store.save("photo-7", bytes))
        assertTrue(store.exists("photo-7"))
        assertTrue(store.thumbFile("photo-7").readBytes().contentEquals(bytes))
        store.clear("photo-7")
        assertFalse(store.exists("photo-7"))
    }

    @Test fun `save when store root is unusable returns false never throws`() {
        // A regular file where the store directory should be: mkdirs() is a
        // no-op and every write fails — exercises the defensive catch
        // deterministically, without depending on OS permission semantics
        // (this harness runs as root, which ignores chmod bits).
        val blocker = File(tmp, "blocker").apply { writeText("not a dir") }
        val broken = RawThumbs(blocker)
        assertFalse(broken.save("id9", byteArrayOf(1, 2, 3)))
        assertFalse(broken.exists("id9"))
        broken.clear("id9") // must not throw
    }

    @Test fun `clear of missing id never throws`() {
        store.clear("never-saved")
        assertFalse(store.exists("never-saved"))
    }

    @Test fun `exists of missing id is false`() {
        assertFalse(store.exists("ghost-id"))
    }

    /** The store's root dir — resolved the same way the class does. */
    private fun storeRoot(): File = File(tmp, "thumbs")
}
