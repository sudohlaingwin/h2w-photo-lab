package com.lumora.photoeditor.data

import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.BeforeClass
import org.junit.Test
import java.util.TimeZone

/**
 * First-ever executed verification of the Details-dialog metadata
 * formatting contract (PhotoDetailsFormat.kt) — every string the library
 * "Details" dialog renders for file/capture/camera rows.
 *
 * Date rendering uses the device default timezone; the class pins UTC for
 * determinism and restores the previous default afterwards so the rest of
 * the suite is unaffected.
 */
class PhotoDetailsFormatTest {

    companion object {
        private var savedTz: TimeZone? = null

        @BeforeClass
        @JvmStatic
        fun pinUtc() {
            savedTz = TimeZone.getDefault()
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        }

        @AfterClass
        @JvmStatic
        fun restoreTz() {
            TimeZone.setDefault(savedTz)
        }
    }

    // ---- formatShutter ----

    @Test fun shutter_subSecond_rendersAsFraction() {
        assertEquals("1/125", formatShutter(0.008))
        assertEquals("1/250", formatShutter(0.004))
        assertEquals("1/8000", formatShutter(0.000125))
        assertEquals("1/2", formatShutter(0.5))
        assertEquals("1/3", formatShutter(1.0 / 3))
    }

    @Test fun shutter_justUnderOneSecond_rendersOneOverOne() {
        // Documents the existing edge: anything < 1.0 takes the fraction
        // branch, so 0.9999999s reads "1/1", not "1.0s".
        assertEquals("1/1", formatShutter(0.9999999))
    }

    @Test fun shutter_oneSecondAndUp_rendersDecimalSeconds() {
        assertEquals("1s", formatShutter(1.0))
        assertEquals("2.5s", formatShutter(2.5))
        assertEquals("30s", formatShutter(30.0))
    }

    // ---- parseRational ----

    @Test fun rational_parsesSlashAndPlainForms() {
        assertEquals(2.8, parseRational("28/10")!!, 1e-9)
        assertEquals(0.008, parseRational("1/125")!!, 1e-12)
        assertEquals(1.5, parseRational("3/2")!!, 1e-9)
        assertEquals(2.8, parseRational("2.8")!!, 1e-9)
        assertEquals(2.8, parseRational("  28/10  ")!!, 1e-9)
    }

    @Test fun rational_rejectsBlankZeroDenominatorAndGarbage() {
        assertNull(parseRational(null))
        assertNull(parseRational(""))
        assertNull(parseRational("   "))
        assertNull(parseRational("0/0"))
        assertNull(parseRational("5/0"))
        assertNull(parseRational("abc"))
    }

    @Test fun rational_ignoresExtraSlashParts() {
        // Documents existing behavior: only the first two parts are used.
        assertEquals(0.5, parseRational("1/2/3")!!, 1e-9)
    }

    // ---- parseExifDate (UTC) ----

    @Test fun exifDate_parsesExifTimestamp() {
        assertEquals("Oct 16, 2026, 5:42 AM", parseExifDate("2026:10:16 05:42:18"))
        assertEquals("Jan 5, 2026, 12:00 AM", parseExifDate("2026:01:05 00:00:00"))
    }

    @Test fun exifDate_rejectsGarbage() {
        assertNull(parseExifDate("garbage"))
        assertNull(parseExifDate(""))
        assertNull(parseExifDate("2026-10-16 05:42:18"))
    }

    // ---- mergeMakeModel ----

    @Test fun makeModel_dedupsMakePrefix() {
        assertEquals("Canon EOS R5", mergeMakeModel("Canon", "Canon EOS R5"))
        assertEquals("Canon EOS R5", mergeMakeModel("canon", "Canon EOS R5"))
        assertEquals("Canon", mergeMakeModel("Canon", "Canon"))
    }

    @Test fun makeModel_joinsOtherwise() {
        assertEquals("NIKON CORPORATION Z 8", mergeMakeModel("NIKON CORPORATION", "Z 8"))
        assertEquals("Canon EOS R5", mergeMakeModel("  Canon  ", " EOS R5 "))
    }

    @Test fun makeModel_handlesBlanks() {
        assertNull(mergeMakeModel("", ""))
        assertNull(mergeMakeModel("   ", "  "))
        assertEquals("Canon", mergeMakeModel("Canon", ""))
        assertEquals("EOS R5", mergeMakeModel("", "EOS R5"))
    }

    // ---- formatBytes ----

    @Test fun bytes_formatsAcrossMagnitudes() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("999 B", formatBytes(999))
        assertEquals("1 KB", formatBytes(1000))
        assertEquals("2 KB", formatBytes(1500)) // %.0f rounds half-up
        assertEquals("1000 KB", formatBytes(999999)) // %.0f of 999.999
        assertEquals("1.0 MB", formatBytes(1_000_000))
        assertEquals("2.5 MB", formatBytes(2_500_000))
        assertEquals("1.0 GB", formatBytes(1_000_000_000))
        assertEquals("2.5 GB", formatBytes(2_500_000_000))
    }

    // ---- formatExifEpoch / formatEpochSeconds (UTC) ----

    @Test fun epoch_formatsLikeExifCaptureDate() {
        assertEquals("Jan 1, 1970, 12:00 AM", formatExifEpoch(0))
        assertEquals("Sep 9, 2001, 1:46 AM", formatExifEpoch(1_000_000_000))
    }

    @Test fun epochSeconds_formatsDateOnly() {
        assertEquals("Jan 1, 1970", formatEpochSeconds(0))
        assertEquals("Sep 9, 2001", formatEpochSeconds(1_000_000_000))
    }

    // ---- formatLabelFor (fake RAW resolver) ----

    private val fakeRaw: (String?) -> String? = { name ->
        if (name?.endsWith(".cr2", ignoreCase = true) == true) "CR2" else null
    }

    @Test fun formatLabel_resolverWinsForRaw() {
        assertEquals("CR2", formatLabelFor("photo.CR2", fakeRaw))
        assertEquals("CR2", formatLabelFor("photo.cr2", fakeRaw))
    }

    @Test fun formatLabel_mapsRasterExtensions() {
        assertEquals("JPEG", formatLabelFor("photo.jpg", fakeRaw))
        assertEquals("JPEG", formatLabelFor("photo.JPEG", fakeRaw))
        assertEquals("JPEG", formatLabelFor("photo.jpeg", fakeRaw))
        assertEquals("PNG", formatLabelFor("photo.png", fakeRaw))
        assertEquals("HEIC", formatLabelFor("photo.heic", fakeRaw))
        assertEquals("HEIC", formatLabelFor("photo.HEIF", fakeRaw))
        assertEquals("WEBP", formatLabelFor("photo.webp", fakeRaw))
        assertEquals("GIF", formatLabelFor("photo.gif", fakeRaw))
        assertEquals("BMP", formatLabelFor("photo.bmp", fakeRaw))
        assertEquals("TIFF", formatLabelFor("photo.tif", fakeRaw))
        assertEquals("TIFF", formatLabelFor("photo.tiff", fakeRaw))
        assertEquals("AVIF", formatLabelFor("photo.avif", fakeRaw))
    }

    @Test fun formatLabel_unknownYieldsNull() {
        assertNull(formatLabelFor("photo.dng", fakeRaw)) // RAW labels come only from the resolver
        assertNull(formatLabelFor("photo.xyz", fakeRaw))
        assertNull(formatLabelFor("noextension", fakeRaw))
        assertNull(formatLabelFor(null, fakeRaw))
    }
}
