package com.lumora.photoeditor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Executed verification of the smart-collection contract
 * (data/SmartCollection.kt — Phase C#6 slice 2, Lightroom "smart collection"
 * parity): name normalization, the [SmartCollection.hasRules]/[summary]
 * display contract, the pure [smartCollectionMatches] predicate, and
 * [sanitizeSmartCollection]'s sanitize pipeline. KDoc claims these are
 * "pinned by SelfTest check 64", but SelfTest is GL-bound and unrunnable
 * here — this is the first executed verification of the real source.
 *
 * Runs on the plain-JVM harness: the only non-stdlib dependency is
 * kotlinx-serialization (@Serializable on [SmartCollection], compiled with the
 * real serialization plugin) plus a [LibraryImage] instance for the match
 * predicate (the harness extracts the real Room entity; only the
 * androidx.room.PrimaryKey annotation is stubbed).
 */
class SmartCollectionTest {

    // --- normalizeCollectionName ---

    @Test fun normalize_trims() {
        assertEquals("Sunset", normalizeCollectionName("  Sunset  "))
    }

    @Test fun normalize_collapsesInternalWhitespaceRuns() {
        assertEquals("Beach Day", normalizeCollectionName("Beach   Day"))
    }

    @Test fun normalize_collapsesTabsAndNewlines() {
        assertEquals("A B C", normalizeCollectionName("A\tB\nC"))
    }

    @Test fun normalize_keepsCasing() {
        assertEquals("SUNSET", normalizeCollectionName("SUNSET"))
    }

    @Test fun normalize_blankIsNull() {
        assertNull(normalizeCollectionName(""))
        assertNull(normalizeCollectionName("   "))
        assertNull(normalizeCollectionName("\t\n "))
    }

    @Test fun normalize_exactlySixtyCharsKept() {
        val name = "x".repeat(60)
        assertEquals(name, normalizeCollectionName(name))
    }

    @Test fun normalize_sixtyOneCharsIsNull() {
        assertNull(normalizeCollectionName("x".repeat(61)))
    }

    @Test fun normalize_lengthCheckedOnCollapsedForm() {
        // 62 raw chars collapse to 61 -> still unusable.
        assertNull(normalizeCollectionName("x".repeat(30) + "  " + "x".repeat(30)))
        // 60 raw chars collapse to 59 -> usable.
        assertEquals(
            "x".repeat(29) + " " + "x".repeat(29),
            normalizeCollectionName("x".repeat(29) + "  " + "x".repeat(29)),
        )
    }

    // --- hasRules ---

    @Test fun hasRules_bareCollectionIsFalse() {
        assertFalse(SmartCollection(id = "c1", name = "x").hasRules)
    }

    @Test fun hasRules_eachSingleRuleIsTrue() {
        assertTrue(SmartCollection(id = "c1", name = "x", keywordKey = "sunset").hasRules)
        assertTrue(SmartCollection(id = "c1", name = "x", minRating = 3).hasRules)
        assertTrue(SmartCollection(id = "c1", name = "x", favoritesOnly = true).hasRules)
    }

    // --- summary ---

    @Test fun summary_allThreeRulesInDocumentedOrder() {
        val c = SmartCollection(
            id = "c1", name = "x",
            keywordKey = "sunset", keywordDisplay = "Sunset",
            minRating = 3, favoritesOnly = true,
        )
        assertEquals("Sunset · 3★+ · favorites", c.summary)
    }

    @Test fun summary_eachRuleAlone() {
        assertEquals(
            "Sunset",
            SmartCollection(id = "c1", name = "x", keywordDisplay = "Sunset").summary,
        )
        assertEquals("3★+", SmartCollection(id = "c1", name = "x", minRating = 3).summary)
        assertEquals(
            "favorites",
            SmartCollection(id = "c1", name = "x", favoritesOnly = true).summary,
        )
    }

    @Test fun summary_keywordPlusRating() {
        assertEquals(
            "Sunset · 4★+",
            SmartCollection(
                id = "c1", name = "x",
                keywordDisplay = "Sunset", minRating = 4,
            ).summary,
        )
    }

    @Test fun summary_noRulesIsEmpty() {
        assertEquals("", SmartCollection(id = "c1", name = "x").summary)
    }

    // --- smartCollectionMatches ---

    private fun img(id: String = "img1", rating: Int = 0, favorite: Boolean = false) =
        LibraryImage(
            id = id, contentUri = null, displayName = "$id.jpg",
            dateAdded = 0L, width = 100, height = 100, folder = "DCIM",
            rating = rating, favorite = favorite,
        )

    @Test fun matches_favoritesOnlyGate() {
        val coll = SmartCollection(id = "c1", name = "x", favoritesOnly = true)
        assertTrue(smartCollectionMatches(coll, img(favorite = true)) { _, _ -> false })
        assertFalse(smartCollectionMatches(coll, img(favorite = false)) { _, _ -> false })
    }

    @Test fun matches_minRatingGateWithInclusiveBoundary() {
        val coll = SmartCollection(id = "c1", name = "x", minRating = 3)
        assertTrue(smartCollectionMatches(coll, img(rating = 3)) { _, _ -> false })
        assertTrue(smartCollectionMatches(coll, img(rating = 5)) { _, _ -> false })
        assertFalse(smartCollectionMatches(coll, img(rating = 2)) { _, _ -> false })
    }

    @Test fun matches_zeroMinRatingMeansNoMinimum() {
        val coll = SmartCollection(id = "c1", name = "x", minRating = 0)
        assertTrue(smartCollectionMatches(coll, img(rating = 0)) { _, _ -> false })
    }

    @Test fun matches_keywordGateDelegatesToLookup() {
        val coll = SmartCollection(id = "c1", name = "x", keywordKey = "sunset")
        var seen: Pair<String, String>? = null
        val hit = smartCollectionMatches(coll, img(id = "img7")) { imageId, key ->
            seen = imageId to key
            true
        }
        assertTrue(hit)
        // The canonical key reaches the lookup unchanged, with the image id.
        assertEquals("img7" to "sunset", seen)
        assertFalse(smartCollectionMatches(coll, img()) { _, _ -> false })
    }

    @Test fun matches_allGatesMustPass() {
        val coll = SmartCollection(
            id = "c1", name = "x",
            keywordKey = "sunset", minRating = 2, favoritesOnly = true,
        )
        val allPass = img(rating = 4, favorite = true)
        assertTrue(smartCollectionMatches(coll, allPass) { _, _ -> true })
        assertFalse(smartCollectionMatches(coll, img(rating = 1, favorite = true)) { _, _ -> true })
        assertFalse(smartCollectionMatches(coll, img(rating = 4, favorite = false)) { _, _ -> true })
        assertFalse(smartCollectionMatches(coll, allPass) { _, _ -> false })
    }

    @Test fun matches_noRulesMatchesEverything() {
        val coll = SmartCollection(id = "c1", name = "x")
        assertTrue(smartCollectionMatches(coll, img()) { _, _ -> false })
    }

    // --- sanitizeSmartCollection ---

    @Test fun sanitize_validCollectionNormalizesNameOnly() {
        val inColl = SmartCollection(
            id = "c1", name = "  Sunset  ",
            keywordKey = "sunset", keywordDisplay = "Sunset",
            minRating = 3, favoritesOnly = true,
        )
        val out = sanitizeSmartCollection(inColl)
        assertEquals(inColl.copy(name = "Sunset"), out)
    }

    @Test fun sanitize_blankNameIsNull() {
        assertNull(sanitizeSmartCollection(SmartCollection(id = "c1", name = "   ")))
    }

    @Test fun sanitize_overlongNameIsNull() {
        assertNull(sanitizeSmartCollection(SmartCollection(id = "c1", name = "x".repeat(61))))
    }

    @Test fun sanitize_minRatingClampedToZeroThroughFive() {
        assertEquals(
            0,
            sanitizeSmartCollection(SmartCollection(id = "c1", name = "x", minRating = -2))!!.minRating,
        )
        assertEquals(
            5,
            sanitizeSmartCollection(SmartCollection(id = "c1", name = "x", minRating = 9))!!.minRating,
        )
        assertEquals(
            3,
            sanitizeSmartCollection(SmartCollection(id = "c1", name = "x", minRating = 3))!!.minRating,
        )
    }

    @Test fun sanitize_keywordKeyWithoutDisplayDropsBoth() {
        assertNull(sanitizeSmartCollection(
            SmartCollection(id = "c1", name = "x", keywordKey = "sunset"),
        )!!.keywordKey)
        val blankDisplay = sanitizeSmartCollection(
            SmartCollection(id = "c1", name = "x", keywordKey = "sunset", keywordDisplay = "  "),
        )!!
        assertNull(blankDisplay.keywordKey)
        assertNull(blankDisplay.keywordDisplay)
    }

    @Test fun sanitize_keywordDisplayWithoutKeyDropsBoth() {
        val out = sanitizeSmartCollection(
            SmartCollection(id = "c1", name = "x", keywordDisplay = "Sunset"),
        )!!
        assertNull(out.keywordKey)
        assertNull(out.keywordDisplay)
    }

    @Test fun sanitize_idPreserved() {
        val out = sanitizeSmartCollection(SmartCollection(id = "keep-me", name = "x"))!!
        assertEquals("keep-me", out.id)
    }
}
