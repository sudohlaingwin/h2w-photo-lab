package com.lumora.photoeditor.data

import org.junit.Assert.*
import org.junit.Test

/**
 * First-ever executed verification of the keyword contract —
 * data/KeywordStore.kt's pure functions (Phase C#6 slice 1).
 *
 * [normalizeKeyword]/[keywordKeyOf] are the shared contract used by the
 * store itself, the sync document sanitizer (SyncDocument.kt), and the
 * library UI (LibraryViewModel/LibraryScreen). [sanitizeKeywordList] is the
 * exact pipeline [KeywordStore.ensureLoaded] applies to keywords read from
 * disk, and [canAddKeyword] is the exact gate [KeywordStore.addKeyword]
 * applies. SelfTest check 62 already pins normalization inside the app
 * (GL-bound, unrunnable here), so this test runs the REAL sources on the
 * JVM to prove the rules hold outside the app too.
 */
class KeywordStoreTest {

    // ---- normalizeKeyword ----

    @Test fun `trims leading and trailing whitespace`() {
        assertEquals("sunset", normalizeKeyword("  sunset "))
    }

    @Test fun `collapses internal whitespace runs including tabs and newlines`() {
        assertEquals("golden hour shoot", normalizeKeyword("golden\thour  shoot\n"))
    }

    @Test fun `keeps user casing`() {
        assertEquals("Sunset", normalizeKeyword("Sunset"))
        assertEquals("SUNSET", normalizeKeyword("SUNSET"))
    }

    @Test fun `blank input normalizes to null`() {
        assertNull(normalizeKeyword(""))
        assertNull(normalizeKeyword("   "))
        assertNull(normalizeKeyword("\t\n "))
    }

    @Test fun `exactly MAX_KEYWORD_LEN chars is kept`() {
        val maxed = "k".repeat(MAX_KEYWORD_LEN)
        assertEquals(48, MAX_KEYWORD_LEN)
        assertEquals(maxed, normalizeKeyword(maxed))
    }

    @Test fun `overlong input normalizes to null`() {
        assertNull(normalizeKeyword("k".repeat(MAX_KEYWORD_LEN + 1)))
    }

    @Test fun `length check runs on the collapsed form`() {
        // 50 spaces collapse to one, so this is short — not overlong.
        assertEquals("a b", normalizeKeyword("a" + " ".repeat(50) + "b"))
    }

    // ---- keywordKeyOf ----

    @Test fun `canonical key is case-insensitive`() {
        assertEquals("sunset", keywordKeyOf("Sunset"))
        assertEquals("sunset", keywordKeyOf("SUNSET"))
        assertEquals("sunset", keywordKeyOf("sunset"))
    }

    @Test fun `canonical key uses ROOT locale not the device locale`() {
        // Under a Turkish default locale "I".lowercase() would be "ı";
        // Locale.ROOT keeps the match key stable across devices.
        assertEquals("i", keywordKeyOf("I"))
        assertEquals("kiev", keywordKeyOf("KIEV"))
    }

    // ---- sanitizeKeywordList (the ensureLoaded pipeline) ----

    @Test fun `drops blanks and overlong entries`() {
        val out = sanitizeKeywordList(listOf("beach", "", "   ", "k".repeat(49)))
        assertEquals(listOf("beach"), out)
    }

    @Test fun `dedups case-insensitively first occurrence wins`() {
        val out = sanitizeKeywordList(listOf("SUNSET", "sunset", "Sunset"))
        assertEquals(listOf("SUNSET"), out)
    }

    @Test fun `sorts by canonical key`() {
        val out = sanitizeKeywordList(listOf("banana", "Apple", "cherry"))
        assertEquals(listOf("Apple", "banana", "cherry"), out)
    }

    @Test fun `caps at MAX_KEYWORDS_PER_PHOTO keeping the first distinct`() {
        // take(30) runs BEFORE sortedBy: the survivors are the first 30
        // distinct entries, then sorted.
        val raw = (35 downTo 1).map { "k%02d".format(it) } // k35..k01
        val out = sanitizeKeywordList(raw)
        assertEquals(30, out.size)
        assertEquals("k06", out.first())
        assertEquals("k35", out.last())
        assertFalse(out.contains("k05"))
    }

    @Test fun `empty input gives empty output`() {
        assertTrue(sanitizeKeywordList(emptyList()).isEmpty())
    }

    // ---- canAddKeyword (the addKeyword gate) ----

    @Test fun `fresh keyword may join an empty photo`() {
        assertTrue(canAddKeyword(emptyList(), "Sunset"))
    }

    @Test fun `case-insensitive duplicate is rejected`() {
        assertFalse(canAddKeyword(listOf("Sunset"), "SUNSET"))
        assertFalse(canAddKeyword(listOf("Sunset"), "sunset"))
    }

    @Test fun `different keyword is accepted`() {
        assertTrue(canAddKeyword(listOf("Sunset"), "beach"))
    }

    @Test fun `full photo rejects new keywords`() {
        val full = (1..MAX_KEYWORDS_PER_PHOTO).map { "k$it" }
        assertEquals(30, MAX_KEYWORDS_PER_PHOTO)
        assertFalse(canAddKeyword(full, "one more"))
    }

    @Test fun `photo one short of the cap accepts one more`() {
        val almost = (1 until MAX_KEYWORDS_PER_PHOTO).map { "k$it" }
        assertTrue(canAddKeyword(almost, "one more"))
    }
}
