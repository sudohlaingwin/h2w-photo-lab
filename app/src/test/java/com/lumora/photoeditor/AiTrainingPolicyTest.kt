package com.lumora.photoeditor

import com.lumora.photoeditor.engine.AiTrainingPolicy
import org.junit.Assert.*
import org.junit.Test

/**
 * First-ever executed verification of the AI training-data license policy
 * (engine/AiTrainingPolicy.kt) — the machine-readable version of the
 * Step-2 dataset decision: PPR10K and MIT-Adobe FiveK must never be
 * downloaded, trained on, or shipped, including derived data (a trained
 * model counts as derived data under PPR10K's agreement).
 *
 * The in-app SelfTest check 63 pins the headline behavior on-device; these
 * tests pin the full contract at build time so a future worker cannot
 * silently "unblock" a dataset, weaken an alias, or widen the block list
 * without a test failing first.
 */
class AiTrainingPolicyTest {

    @Test
    fun ppr10kIsBlockedInAllForms() {
        assertTrue(AiTrainingPolicy.isBlocked("PPR10K"))
        assertTrue(AiTrainingPolicy.isBlocked("ppr10k"))
        assertTrue(AiTrainingPolicy.isBlocked("Ppr10K"))
        assertTrue(AiTrainingPolicy.isBlocked("  ppr10k  "))
    }

    @Test
    fun fivekIsBlockedViaAliases() {
        assertTrue(AiTrainingPolicy.isBlocked("MIT-Adobe FiveK"))
        assertTrue(AiTrainingPolicy.isBlocked("FiveK"))
        assertTrue(AiTrainingPolicy.isBlocked("fivek"))
        assertTrue(AiTrainingPolicy.isBlocked("mit adobe fivek"))
        assertTrue(AiTrainingPolicy.isBlocked("mit-adobe-fivek"))
        assertTrue(AiTrainingPolicy.isBlocked("  FiveK "))
    }

    @Test
    fun cleanSourcesStayOpen() {
        assertFalse(AiTrainingPolicy.isBlocked("user's own photo pairs"))
        assertFalse(AiTrainingPolicy.isBlocked("COCO"))
        assertFalse(AiTrainingPolicy.isBlocked("ImageNet"))
        assertFalse(AiTrainingPolicy.isBlocked("random-dataset-xyz"))
        assertFalse(AiTrainingPolicy.isBlocked(""))
        assertFalse(AiTrainingPolicy.isBlocked("   "))
    }

    @Test
    fun blockedEntryReturnsEvidenceTrail() {
        val ppr = AiTrainingPolicy.blockedEntry("PPR10K")
        assertNotNull(ppr)
        assertEquals("PPR10K", ppr!!.name)
        assertTrue(ppr.reason.contains("derived data"))
        assertTrue(ppr.verified.isNotBlank())

        val fivek = AiTrainingPolicy.blockedEntry("fivek")
        assertNotNull(fivek)
        assertEquals("MIT-Adobe FiveK", fivek!!.name)
        assertTrue(fivek.reason.isNotBlank())
        assertTrue(fivek.verified.isNotBlank())
    }

    @Test
    fun blockedEntryNullForCleanSources() {
        assertNull(AiTrainingPolicy.blockedEntry("COCO"))
        assertNull(AiTrainingPolicy.blockedEntry("user's own photo pairs"))
        assertNull(AiTrainingPolicy.blockedEntry(""))
    }

    @Test
    fun blockListIsExactlyTheTwoKnownDatasets() {
        // Pins against BOTH a silent "unblock" and a silent widening of the
        // list: the set must stay exactly these two until a verified license
        // review says otherwise (the SelfTest pin must move with it).
        assertEquals(
            setOf("PPR10K", "MIT-Adobe FiveK"),
            AiTrainingPolicy.BLOCKED.map { it.name }.toSet()
        )
    }

    @Test
    fun everyBlockedEntryHasReasonAndVerification() {
        assertTrue(AiTrainingPolicy.BLOCKED.isNotEmpty())
        for (entry in AiTrainingPolicy.BLOCKED) {
            assertTrue(entry.name.isNotBlank())
            assertTrue("reason blank for ${entry.name}", entry.reason.isNotBlank())
            assertTrue("verified blank for ${entry.name}", entry.verified.isNotBlank())
        }
    }
}
