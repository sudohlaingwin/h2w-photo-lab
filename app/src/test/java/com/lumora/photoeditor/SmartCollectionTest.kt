package com.lumora.photoeditor

import com.lumora.photoeditor.data.LibraryImage
import com.lumora.photoeditor.data.MAX_COLLECTION_NAME_LEN
import com.lumora.photoeditor.data.MAX_SMART_COLLECTIONS
import com.lumora.photoeditor.data.SmartCollection
import com.lumora.photoeditor.data.normalizeCollectionName
import com.lumora.photoeditor.data.sanitizeSmartCollection
import com.lumora.photoeditor.data.smartCollectionMatches
import org.junit.Assert.*
import org.junit.Test

/**
 * First-ever executed verification of the smart-collection rule engine
 * (data/SmartCollection.kt — Phase C#6 slice 2): a saved set of rules
 * (keyword + minimum rating + favorites-only) whose members are recomputed
 * on every library refresh.
 *
 * The in-app SelfTest check 64 pins the headline behavior on-device; these
 * tests pin the full contract at build time, including boundary and
 * AND-composition edges the SelfTest does not cover.
 */
class SmartCollectionTest {

    private fun img(
        id: String = "p1",
        rating: Int = 4,
        favorite: Boolean = true,
    ) = LibraryImage(
        id = id, contentUri = null, displayName = "a.jpg",
        dateAdded = 1L, width = 10, height = 10, folder = "f",
        rating = rating, favorite = favorite,
    )

    @Test
    fun normalizeTrimsAndCollapsesWhitespace() {
        assertEquals("Portfolio picks", normalizeCollectionName("  Portfolio   picks  "))
        assertEquals("a b c", normalizeCollectionName("a\tb\nc"))
        assertEquals("S", normalizeCollectionName("  S "))
    }

    @Test
    fun normalizeRejectsBlankAndOverlong() {
        assertNull(normalizeCollectionName(""))
        assertNull(normalizeCollectionName("   "))
        assertEquals(60, normalizeCollectionName("x".repeat(60))!!.length)
        assertNull(normalizeCollectionName("x".repeat(61)))
    }

    @Test
    fun sanitizeDropsUnusableAndClampsRating() {
        assertNull(sanitizeSmartCollection(SmartCollection(id = "b", name = "")))
        assertNull(sanitizeSmartCollection(SmartCollection(id = "b", name = "   ")))

        val hi = sanitizeSmartCollection(
            SmartCollection(id = "a", name = "  A  ", minRating = 9))
        assertEquals(SmartCollection(id = "a", name = "A", minRating = 5), hi)

        val lo = sanitizeSmartCollection(
            SmartCollection(id = "a", name = "A", minRating = -3))
        assertEquals(0, lo!!.minRating)
    }

    @Test
    fun sanitizeDropsOrphanKeywordKey() {
        // Key without its display form is dropped (both or neither).
        val orphan = sanitizeSmartCollection(
            SmartCollection(id = "c", name = "C",
                keywordKey = "sunset", keywordDisplay = null))
        assertEquals(SmartCollection(id = "c", name = "C"), orphan)

        // Display form without a key is dropped too.
        val displayOnly = sanitizeSmartCollection(
            SmartCollection(id = "d", name = "D", keywordDisplay = "Sunset"))
        assertEquals(SmartCollection(id = "d", name = "D"), displayOnly)

        // Both present: kept as-is.
        val both = sanitizeSmartCollection(
            SmartCollection(id = "e", name = "E",
                keywordKey = "sunset", keywordDisplay = "Sunset"))
        assertEquals("sunset", both!!.keywordKey)
        assertEquals("Sunset", both.keywordDisplay)
    }

    @Test
    fun hasRulesReflectsActiveRules() {
        assertFalse(SmartCollection(id = "x", name = "X").hasRules)
        assertTrue(SmartCollection(id = "x", name = "X", keywordKey = "k",
            keywordDisplay = "K").hasRules)
        assertTrue(SmartCollection(id = "x", name = "X", minRating = 3).hasRules)
        assertTrue(SmartCollection(id = "x", name = "X", favoritesOnly = true).hasRules)
    }

    @Test
    fun summaryDescribesActiveRules() {
        assertEquals(
            "Sunset · 3★+ · favorites",
            SmartCollection(id = "s", name = "S", keywordKey = "sunset",
                keywordDisplay = "Sunset", minRating = 3,
                favoritesOnly = true).summary)
        assertEquals("Sunset",
            SmartCollection(id = "s", name = "S", keywordKey = "sunset",
                keywordDisplay = "Sunset").summary)
        assertEquals("4★+",
            SmartCollection(id = "s", name = "S", minRating = 4).summary)
        assertEquals("favorites",
            SmartCollection(id = "s", name = "S", favoritesOnly = true).summary)
        assertEquals("", SmartCollection(id = "t", name = "T").summary)
    }

    @Test
    fun matchesFavoritesOnly() {
        val coll = SmartCollection(id = "f", name = "F", favoritesOnly = true)
        assertTrue(smartCollectionMatches(coll, img(favorite = true)) { _, _ -> false })
        assertFalse(smartCollectionMatches(coll, img(favorite = false)) { _, _ -> true })
    }

    @Test
    fun matchesMinRatingBoundary() {
        val coll = SmartCollection(id = "r", name = "R", minRating = 4)
        assertTrue(smartCollectionMatches(coll, img(rating = 4)) { _, _ -> true })
        assertTrue(smartCollectionMatches(coll, img(rating = 5)) { _, _ -> true })
        assertFalse(smartCollectionMatches(
            SmartCollection(id = "r", name = "R", minRating = 5),
            img(rating = 4)) { _, _ -> true })
    }

    @Test
    fun matchesKeywordDelegatesToLookup() {
        val coll = SmartCollection(id = "k", name = "K",
            keywordKey = "sunset", keywordDisplay = "Sunset")
        var seenKey = ""
        assertTrue(smartCollectionMatches(coll, img()) { _, key ->
            seenKey = key; key == "sunset"
        })
        assertEquals("sunset", seenKey)
        assertFalse(smartCollectionMatches(coll, img()) { _, key -> key == "beach" })
    }

    @Test
    fun matchesCombinesAllRulesWithAnd() {
        val coll = SmartCollection(id = "a", name = "A",
            keywordKey = "sunset", keywordDisplay = "Sunset",
            minRating = 4, favoritesOnly = true)
        val allPass = { _: String, _: String -> true }
        // Every rule holds -> match.
        assertTrue(smartCollectionMatches(coll, img(), allPass))
        // Any single rule failing -> no match.
        assertFalse(smartCollectionMatches(coll, img(favorite = false), allPass))
        assertFalse(smartCollectionMatches(coll, img(rating = 3), allPass))
        assertFalse(smartCollectionMatches(coll, img()) { _, _ -> false })
        // A rule-less collection matches everything (never a bare "all"
        // per hasRules, but the predicate itself is permissive).
        assertTrue(smartCollectionMatches(
            SmartCollection(id = "n", name = "N"), img()) { _, _ -> false })
    }

    @Test
    fun limitsAreSane() {
        assertEquals(60, MAX_COLLECTION_NAME_LEN)
        assertEquals(50, MAX_SMART_COLLECTIONS)
    }
}
