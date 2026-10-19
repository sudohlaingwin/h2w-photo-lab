package com.lumora.photoeditor.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * First-ever executed verification of [LensProfiles] — the pure-Kotlin
 * per-lens correction memory behind the Optics panel's Remember/Forget
 * feature (shipped 2026-10-17 for his Canon R50).
 *
 * Pins the documented contracts: model normalization (case folding,
 * separator collapsing, leading-separator trimming, Unicode letters),
 * key selection (lens preferred, "cam:"-prefixed body fallback, the two
 * namespaces can never collide), label behavior (raw EXIF, trimmed,
 * 48-char cap, verbatim case), the format/parse round-trip with range
 * coercion, malformed-input nulls, and the "meaningful profile" guard
 * that keeps zero-effect profiles from being remembered.
 */
class LensProfilesTest {

    // ---- normalizeLensModel ----

    @Test
    fun normalize_caseFoldsAndCollapsesSeparators() {
        assertEquals("vivo v60", LensProfiles.normalizeLensModel("Vivo V60"))
        assertEquals("vivo v60", LensProfiles.normalizeLensModel("VIVO  V60"))
    }

    @Test
    fun normalize_punctuationBecomesSingleSpaces() {
        assertEquals("canon rf50mm f 1 8 stm", LensProfiles.normalizeLensModel("Canon RF50mm f/1.8 STM"))
        assertEquals("ef24", LensProfiles.normalizeLensModel("  (EF24)  "))
    }

    @Test
    fun normalize_blankAndSeparatorOnlyAreNull() {
        assertNull(LensProfiles.normalizeLensModel(null))
        assertNull(LensProfiles.normalizeLensModel(""))
        assertNull(LensProfiles.normalizeLensModel("   "))
        assertNull(LensProfiles.normalizeLensModel("---"))
        assertNull(LensProfiles.normalizeLensModel(".../..."))
    }

    // ---- lensKeyFromExif ----

    @Test
    fun lensKey_prefersLensModel() {
        val key = LensProfiles.lensKeyFromExif("Canon RF50mm f/1.8 STM", "Canon EOS R50")
        assertEquals("lens:canon rf50mm f 1 8 stm", key)
    }

    @Test
    fun lensKey_fallsBackToCameraModelWithCamPrefix() {
        assertEquals("cam:vivo v60", LensProfiles.lensKeyFromExif(null, "vivo V60"))
        assertEquals("cam:vivo v60", LensProfiles.lensKeyFromExif("  ", "vivo V60"))
    }

    @Test
    fun lensKey_lensAndBodyNamespacesNeverCollide() {
        val asLens = LensProfiles.lensKeyFromExif("Canon EOS R50", null)
        val asBody = LensProfiles.lensKeyFromExif(null, "Canon EOS R50")
        assertEquals("lens:canon eos r50", asLens)
        assertEquals("cam:canon eos r50", asBody)
        assertTrue(asLens != asBody)
    }

    @Test
    fun lensKey_nullWhenNothingIdentifies() {
        assertNull(LensProfiles.lensKeyFromExif(null, null))
        assertNull(LensProfiles.lensKeyFromExif("", "  "))
    }

    // ---- lensLabelFromExif ----

    @Test
    fun lensLabel_prefersLensKeepsRawCase() {
        assertEquals(
            "Canon RF50mm f/1.8 STM",
            LensProfiles.lensLabelFromExif("Canon RF50mm f/1.8 STM", "Canon EOS R50")
        )
    }

    @Test
    fun lensLabel_fallsBackToCameraModel() {
        assertEquals("Canon EOS R50", LensProfiles.lensLabelFromExif(null, "Canon EOS R50"))
        assertEquals("vivo V60", LensProfiles.lensLabelFromExif("  ", "vivo V60"))
    }

    @Test
    fun lensLabel_trimsAndCapsAt48Chars() {
        val long = "A".repeat(60)
        val label = LensProfiles.lensLabelFromExif(long, null)
        assertEquals(48, label!!.length)
        assertEquals("A".repeat(48), label)
        assertEquals("vivo V60", LensProfiles.lensLabelFromExif("  vivo V60  ", null))
    }

    @Test
    fun lensLabel_nullWhenUnknown() {
        assertNull(LensProfiles.lensLabelFromExif(null, null))
        assertNull(LensProfiles.lensLabelFromExif("  ", ""))
    }

    // ---- format / parse ----

    @Test
    fun format_emitsCompactPair() {
        assertEquals("25.0,10.0", LensProfiles.format(LensCorrection(25f, 10f)))
        assertEquals("0.0,0.0", LensProfiles.format(LensCorrection(0f, 0f)))
        assertEquals("-33.5,72.25", LensProfiles.format(LensCorrection(-33.5f, 72.25f)))
    }

    @Test
    fun parse_roundTripsFormat() {
        listOf(
            LensCorrection(25f, 10f),
            LensCorrection(0f, 0f),
            LensCorrection(-100f, 100f),
            LensCorrection(-33.5f, 72.25f),
        ).forEach { c -> assertEquals(c, LensProfiles.parse(LensProfiles.format(c))) }
    }

    @Test
    fun parse_coercesOutOfRangeToSliderBounds() {
        assertEquals(LensCorrection(100f, 100f), LensProfiles.parse("200,150"))
        assertEquals(LensCorrection(-100f, 0f), LensProfiles.parse("-150,-5"))
    }

    @Test
    fun parse_toleratesSurroundingSpaces() {
        assertEquals(LensCorrection(25f, 10f), LensProfiles.parse(" 25 , 10 "))
    }

    @Test
    fun parse_malformedIsNull() {
        assertNull(LensProfiles.parse(null))
        assertNull(LensProfiles.parse(""))
        assertNull(LensProfiles.parse("   "))
        assertNull(LensProfiles.parse("25"))
        assertNull(LensProfiles.parse("25,10,5"))
        assertNull(LensProfiles.parse("abc,10"))
        assertNull(LensProfiles.parse("25,xyz"))
        assertNull(LensProfiles.parse("25,"))
    }

    // ---- isMeaningful ----

    @Test
    fun isMeaningful_zeroCorrectionIsNotWorthRemembering() {
        assertFalse(LensProfiles.isMeaningful(LensCorrection(0f, 0f)))
        assertTrue(LensProfiles.isMeaningful(LensCorrection(25f, 0f)))
        assertTrue(LensProfiles.isMeaningful(LensCorrection(0f, 5f)))
        assertTrue(LensProfiles.isMeaningful(LensCorrection(-0.001f, 0f)))
    }
}
