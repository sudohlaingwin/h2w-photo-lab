package com.lumora.photoeditor.export

import com.lumora.photoeditor.engine.sanitizeExportName
import org.junit.Assert.*
import org.junit.Test

/**
 * First-ever executed verification of the batch-export distinct-name
 * contract ([perPhotoFileName] + [sanitizeExportName] as applied by
 * ExportSettings.galleryFileName downstream).
 *
 * Why it matters on his phone: batch export reuses one file-name setting
 * for every photo. If two photos ever resolved to the same gallery name,
 * the second export would silently overwrite/merge with the first in the
 * gallery — a data-loss-class bug for a photographer exporting a shoot.
 * The contract: with more than one photo, every photo's final stem is
 * distinct; a blank custom name falls back to each photo's own source
 * stem instead of a bare timestamp.
 */
class BatchExportNamingTest {

    @Test
    fun `blank base falls back to each photo's own source stem`() {
        assertEquals("IMG_2041", perPhotoFileName("", "IMG_2041.jpg", 0, 1))
        assertEquals("screenshot", perPhotoFileName("", "screenshot.png", 0, 1))
    }

    @Test
    fun `single photo gets no index suffix`() {
        assertEquals("Morning shoot", perPhotoFileName("Morning shoot", "IMG_2041.jpg", 0, 1))
        assertEquals("IMG_2041", perPhotoFileName("", "IMG_2041.jpg", 0, 1))
    }

    @Test
    fun `batch of three with blank base gets distinct names`() {
        val names = listOf("IMG_2041.jpg", "IMG_2042.jpg", "IMG_2043.jpg")
            .mapIndexed { i, n -> perPhotoFileName("", n, i, 3) }
        assertEquals(listOf("IMG_2041 1", "IMG_2042 2", "IMG_2043 3"), names)
        assertEquals(3, names.toSet().size)
    }

    @Test
    fun `identical source names in a batch still stay distinct`() {
        // Same file name from different folders — the realistic collision case.
        val names = (0..2).map { i -> perPhotoFileName("", "IMG_2041.jpg", i, 3) }
        assertEquals(listOf("IMG_2041 1", "IMG_2041 2", "IMG_2041 3"), names)
        assertEquals(3, names.toSet().size)
    }

    @Test
    fun `custom base gets index suffixes in a batch`() {
        val names = (0..1).map { i -> perPhotoFileName("Morning shoot", "IMG_2041.jpg", i, 2) }
        assertEquals(listOf("Morning shoot 1", "Morning shoot 2"), names)
    }

    @Test
    fun `index suffix counts from one not zero`() {
        assertTrue(perPhotoFileName("", "a.jpg", 0, 2).endsWith(" 1"))
        assertTrue(perPhotoFileName("", "a.jpg", 1, 2).endsWith(" 2"))
    }

    @Test
    fun `extensionless display name works`() {
        assertEquals("screenshot 1", perPhotoFileName("", "screenshot", 0, 2))
    }

    @Test
    fun `dot-only stem does not crash and stays distinct downstream`() {
        val raw = perPhotoFileName("", ".jpg", 0, 2)
        assertEquals(".jpg 1", raw)
        assertEquals("jpg 1", sanitizeExportName(raw))
    }

    @Test
    fun `blank display name and blank base stay distinct downstream`() {
        val raws = (0..1).map { i -> perPhotoFileName("", "", i, 2) }
        assertEquals(2, raws.toSet().size)
        val clean = raws.map { sanitizeExportName(it) }
        assertEquals(2, clean.toSet().size)
    }

    @Test
    fun `downstream sanitize keeps a full batch distinct`() {
        // The real user-facing contract: raw stem -> sanitizeExportName
        // (what galleryFileName applies) must never merge two photos.
        val raws = listOf("IMG_2041.jpg", "IMG_2041.jpg", "IMG_2041.jpg")
            .mapIndexed { i, n -> perPhotoFileName("", n, i, 3) }
        val clean = raws.map { sanitizeExportName(it) }
        assertEquals(listOf("IMG_2041 1", "IMG_2041 2", "IMG_2041 3"), clean)
        assertEquals(3, clean.toSet().size)
    }

    @Test
    fun `illegal chars pass through raw and are cleaned downstream`() {
        val raw = perPhotoFileName("a/b:c", "IMG_2041.jpg", 0, 2)
        assertEquals("a/b:c 1", raw)
        assertEquals("abc 1", sanitizeExportName(raw))
    }
}
