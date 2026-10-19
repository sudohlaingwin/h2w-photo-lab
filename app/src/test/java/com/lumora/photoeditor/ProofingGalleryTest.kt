package com.lumora.photoeditor

import com.lumora.photoeditor.data.ClientPicksResult
import com.lumora.photoeditor.data.MAX_CLIENT_NAME_LEN
import com.lumora.photoeditor.data.MAX_GALLERY_NAME_LEN
import com.lumora.photoeditor.data.MAX_GALLERY_PHOTOS
import com.lumora.photoeditor.data.MAX_PROOFING_GALLERIES
import com.lumora.photoeditor.data.ProofMark
import com.lumora.photoeditor.data.ProofingGallery
import com.lumora.photoeditor.data.clientPicksPlan
import com.lumora.photoeditor.data.normalizeClientName
import com.lumora.photoeditor.data.normalizeGalleryName
import com.lumora.photoeditor.data.next
import com.lumora.photoeditor.data.sanitizeProofingGallery
import org.junit.Assert.*
import org.junit.Test

/**
 * First-ever executed verification of the client-proofing gallery engine
 * (data/ProofingGallery.kt — Phase C#7): named, ordered photo sets with
 * client approve/reject marks, plus the Phase C#7 slice 3 round-trip that
 * writes marks back to the library as ratings/favorites.
 *
 * The in-app SelfTest checks 65/67 pin the headline behavior on-device;
 * these tests pin the full contract at build time, including the
 * sync-merge invariant (sanitize must preserve updatedAt — the merge
 * already decided the entry is newer, so re-stamping would flap) and the
 * photo-id cap, which the SelfTest does not cover.
 */
class ProofingGalleryTest {

    @Test
    fun markCyclesUnmarkedApprovedRejectedUnmarked() {
        assertEquals(ProofMark.APPROVED, (null as ProofMark?).next())
        assertEquals(ProofMark.REJECTED, ProofMark.APPROVED.next())
        assertNull(ProofMark.REJECTED.next())
    }

    @Test
    fun normalizeGalleryNameTrimsAndCollapses() {
        assertEquals("Wedding Smith", normalizeGalleryName("  Wedding   Smith  "))
        assertEquals("a b c", normalizeGalleryName("a\tb\nc"))
    }

    @Test
    fun normalizeGalleryNameRejectsBlankAndOverlong() {
        assertNull(normalizeGalleryName(""))
        assertNull(normalizeGalleryName("   "))
        assertEquals(60, normalizeGalleryName("x".repeat(60))!!.length)
        assertNull(normalizeGalleryName("x".repeat(61)))
    }

    @Test
    fun normalizeClientName() {
        assertNull(normalizeClientName(null))
        assertNull(normalizeClientName(""))
        assertNull(normalizeClientName("  "))
        assertNull(normalizeClientName("x".repeat(61)))
        assertEquals("Aye Aye", normalizeClientName(" Aye  Aye "))
        assertEquals(60, normalizeClientName("x".repeat(60))!!.length)
    }

    @Test
    fun sanitizeDedupesPreservesOrderAndDropsStaleMarks() {
        val g = ProofingGallery(
            id = "g", name = "  G  ", clientName = "  ",
            photoIds = listOf("p1", "p1", "p2"),
            marks = mapOf(
                "p1" to ProofMark.APPROVED,
                "p2" to ProofMark.REJECTED,
                "pX" to ProofMark.APPROVED, // stale: not a member
            ),
        )
        val s = sanitizeProofingGallery(g)
        assertNotNull(s)
        assertEquals("G", s!!.name)
        assertNull(s.clientName)
        assertEquals(listOf("p1", "p2"), s.photoIds)
        assertEquals(setOf("p1", "p2"), s.marks.keys)
    }

    @Test
    fun sanitizeCapsAtMaxPhotos() {
        val ids = (1..(MAX_GALLERY_PHOTOS + 5)).map { "p$it" }
        val s = sanitizeProofingGallery(ProofingGallery(id = "g", name = "G", photoIds = ids))
        assertNotNull(s)
        assertEquals(MAX_GALLERY_PHOTOS, s!!.photoIds.size)
        // Order preserved: the first MAX_GALLERY_PHOTOS survive.
        assertEquals(ids.take(MAX_GALLERY_PHOTOS), s.photoIds)
    }

    @Test
    fun sanitizeRejectsBlankName() {
        assertNull(sanitizeProofingGallery(
            ProofingGallery(id = "g", name = "", photoIds = listOf("p1"))))
        assertNull(sanitizeProofingGallery(
            ProofingGallery(id = "g", name = "   ")))
    }

    @Test
    fun sanitizePreservesUpdatedAt() {
        // Sync-merge invariant (Phase C#8 slice 1): the merge already decided
        // this entry is newer, so sanitize must NOT re-stamp updatedAt —
        // otherwise the next merge would flap.
        val s = sanitizeProofingGallery(
            ProofingGallery(id = "g", name = "G",
                photoIds = listOf("p1"), updatedAt = 123456789L))
        assertEquals(123456789L, s!!.updatedAt)
    }

    @Test
    fun statsCountsMembersOnly() {
        val s = sanitizeProofingGallery(ProofingGallery(
            id = "g", name = "G",
            photoIds = listOf("p1", "p1", "p2"),
            marks = mapOf(
                "p1" to ProofMark.APPROVED,
                "p2" to ProofMark.REJECTED,
                "pX" to ProofMark.APPROVED,
            ),
        ))!!
        assertEquals(Triple(1, 1, 0), s.stats())
        assertEquals(Triple(0, 0, 2), s.copy(marks = emptyMap()).stats())
        // A mark on a removed photo never counts.
        assertEquals(Triple(1, 0, 0), s.copy(photoIds = listOf("p1")).stats())
    }

    @Test
    fun summaryFormats() {
        assertEquals("0 photos", ProofingGallery(id = "e", name = "E").summary)
        assertEquals("1 photo",
            ProofingGallery(id = "o", name = "O", photoIds = listOf("p1")).summary)
        assertEquals("2 photos",
            ProofingGallery(id = "t", name = "T",
                photoIds = listOf("p1", "p2")).summary)
        assertEquals("2 photos · 1 picked",
            ProofingGallery(id = "a", name = "A",
                photoIds = listOf("p1", "p2"),
                marks = mapOf("p1" to ProofMark.APPROVED)).summary)
        assertEquals("2 photos · 1 rejected",
            ProofingGallery(id = "r", name = "R",
                photoIds = listOf("p1", "p2"),
                marks = mapOf("p2" to ProofMark.REJECTED)).summary)
        assertEquals("2 photos · 1 picked · 1 rejected",
            ProofingGallery(id = "b", name = "B",
                photoIds = listOf("p1", "p2"),
                marks = mapOf("p1" to ProofMark.APPROVED,
                    "p2" to ProofMark.REJECTED)).summary)
    }

    @Test
    fun clientPicksPlanResolvesMembersInOrder() {
        val g = ProofingGallery(
            id = "g", name = "G",
            photoIds = listOf("p1", "p2", "p3"),
            marks = mapOf(
                "p1" to ProofMark.APPROVED,
                "p2" to ProofMark.REJECTED,
                "pX" to ProofMark.APPROVED, // stale: not a member
            ),
        )
        val (approved, rejected) = g.clientPicksPlan()
        assertEquals(listOf("p1"), approved)
        assertEquals(listOf("p2"), rejected)

        // Unmarked members are simply absent.
        val empty = ProofingGallery(id = "e", name = "E",
            photoIds = listOf("p1")).clientPicksPlan()
        assertEquals(emptyList<String>() to emptyList<String>(), empty)
    }

    @Test
    fun clientPicksResultDescribe() {
        assertEquals(
            "Client picks applied: 8 picked → 5★ + favorite · 3 rejected → 1★",
            ClientPicksResult(8, 3, 0).describe())
        assertEquals(
            "Client picks applied: 2 skipped",
            ClientPicksResult(0, 0, 2).describe())
        assertEquals(
            "Client picks applied: 5 picked → 5★ + favorite",
            ClientPicksResult(5, 0, 0).describe())
        assertEquals(
            "No client marks to apply",
            ClientPicksResult().describe())
    }

    @Test
    fun limitsAreSane() {
        assertEquals(60, MAX_GALLERY_NAME_LEN)
        assertEquals(60, MAX_CLIENT_NAME_LEN)
        assertEquals(50, MAX_PROOFING_GALLERIES)
        assertEquals(2000, MAX_GALLERY_PHOTOS)
    }
}
