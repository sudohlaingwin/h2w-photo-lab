package com.lumora.photoeditor.engine

import org.junit.Assert.*
import org.junit.Test

/**
 * First-ever executed verification of [AiTrainingPolicy] — the pure-Kotlin
 * training-data license policy behind the AI Pro Retouch Step 2 predictor
 * seam (zero imports, JVM-harnessable).
 *
 * Pins the observable contract: the block list's exact contents with their
 * evidence trails, case-insensitive [isBlocked] with alias resolution
 * (including the subtle alias-value-lowercase step that keeps the exact
 * canonical names "PPR10K"/"FiveK" blocked), [blockedEntry] returning the
 * same resolved entries, and the no-bypass invariant — no casing or spacing
 * variant of a blocked name escapes the block list.
 */
class AiTrainingPolicyTest {

    // ---------- BLOCKED list contents ----------

    @Test fun `block list has exactly the two pinned datasets`() {
        val names = AiTrainingPolicy.BLOCKED.map { it.name }
        assertEquals(listOf("PPR10K", "MIT-Adobe FiveK"), names)
    }

    @Test fun `every blocked entry has a one-sentence reason and a cited evidence trail`() {
        for (entry in AiTrainingPolicy.BLOCKED) {
            assertTrue("name blank for $entry", entry.name.isNotBlank())
            assertTrue("reason blank for ${entry.name}", entry.reason.isNotBlank())
            assertTrue("verified blank for ${entry.name}", entry.verified.isNotBlank())
        }
    }

    @Test fun `PPR10K entry names the non-commercial research restriction`() {
        val entry = AiTrainingPolicy.BLOCKED.first { it.name == "PPR10K" }
        assertTrue(entry.reason.contains("non-commercial", ignoreCase = true))
        assertTrue(entry.verified.contains("2026-10-18"))
    }

    @Test fun `FiveK entry names the research-only restriction and the no-ship rule`() {
        val entry = AiTrainingPolicy.BLOCKED.first { it.name == "MIT-Adobe FiveK" }
        assertTrue(entry.reason.contains("Research-only", ignoreCase = true))
        assertTrue(entry.reason.contains("never ship", ignoreCase = true))
        assertTrue(entry.verified.contains("2026-10-17"))
    }

    // ---------- isBlocked: canonical names and casing ----------

    @Test fun `exact canonical names are blocked`() {
        assertTrue(AiTrainingPolicy.isBlocked("PPR10K"))
        assertTrue(AiTrainingPolicy.isBlocked("MIT-Adobe FiveK"))
    }

    @Test fun `case variants of canonical names are blocked`() {
        for (variant in listOf("ppr10k", "Ppr10k", "PPR10k", "PpR10K", "fivek", "FIVEK", "FiveK",
            "mit-adobe-fivek", "MIT-ADOBE-FIVEK", "mit adobe fivek", "MIT ADOBE FIVEK")) {
            assertTrue("not blocked: '$variant'", AiTrainingPolicy.isBlocked(variant))
        }
    }

    @Test fun `surrounding whitespace does not evade the block`() {
        assertTrue(AiTrainingPolicy.isBlocked("  PPR10K  "))
        assertTrue(AiTrainingPolicy.isBlocked("\tFiveK\n"))
    }

    // ---------- isBlocked: aliases ----------

    @Test fun `known aliases resolve to blocked datasets`() {
        assertTrue(AiTrainingPolicy.isBlocked("fivek"))
        assertTrue(AiTrainingPolicy.isBlocked("mit adobe fivek"))
        assertTrue(AiTrainingPolicy.isBlocked("mit-adobe-fivek"))
        assertTrue(AiTrainingPolicy.isBlocked("ppr10k"))
    }

    // ---------- isBlocked: non-blocked datasets ----------

    @Test fun `unrelated datasets and empty input are not blocked`() {
        for (name in listOf("COCO", "ImageNet", "user photo pairs", "PPR10", "10K",
            "PPR 10K", "five", "", "   ")) {
            assertFalse("wrongly blocked: '$name'", AiTrainingPolicy.isBlocked(name))
        }
    }

    // ---------- blockedEntry ----------

    @Test fun `blockedEntry returns the matching entry for names and aliases`() {
        val ppr = AiTrainingPolicy.blockedEntry("ppr10k")
        assertNotNull(ppr)
        assertEquals("PPR10K", ppr!!.name)

        val five = AiTrainingPolicy.blockedEntry("FiveK")
        assertNotNull(five)
        assertEquals("MIT-Adobe FiveK", five!!.name)

        // Alias and canonical return the same entry.
        assertEquals(AiTrainingPolicy.blockedEntry("MIT-Adobe FiveK"),
            AiTrainingPolicy.blockedEntry("fivek"))
        assertEquals(AiTrainingPolicy.blockedEntry("PPR10K"),
            AiTrainingPolicy.blockedEntry("ppr10k"))
    }

    @Test fun `blockedEntry is null for non-blocked datasets`() {
        assertNull(AiTrainingPolicy.blockedEntry("COCO"))
        assertNull(AiTrainingPolicy.blockedEntry(""))
    }

    // ---------- policy invariants ----------

    @Test fun `every blocked name is blocked in every case, so no variant slips through`() {
        for (entry in AiTrainingPolicy.BLOCKED) {
            val name = entry.name
            assertTrue(name, AiTrainingPolicy.isBlocked(name))
            assertTrue(name, AiTrainingPolicy.isBlocked(name.uppercase()))
            assertTrue(name, AiTrainingPolicy.isBlocked(name.lowercase()))
            assertNotNull(name, AiTrainingPolicy.blockedEntry(name.lowercase()))
        }
    }

    @Test fun `isBlocked and blockedEntry agree everywhere`() {
        val probes = listOf("PPR10K", "ppr10k", "MIT-Adobe FiveK", "fivek", "FIVEK",
            "mit-adobe-fivek", "COCO", "", "  PPR10K  ")
        for (p in probes) {
            assertEquals("disagreement for '$p'",
                AiTrainingPolicy.isBlocked(p), AiTrainingPolicy.blockedEntry(p) != null)
        }
    }

    @Test fun `determinism`() {
        val probes = listOf("PPR10K", "fivek", "COCO")
        repeat(3) {
            for (p in probes) {
                assertEquals(AiTrainingPolicy.isBlocked(p), AiTrainingPolicy.isBlocked(p))
                assertEquals(AiTrainingPolicy.blockedEntry(p), AiTrainingPolicy.blockedEntry(p))
            }
        }
    }
}
