package com.lumora.photoeditor.data

import com.lumora.photoeditor.engine.EditSnapshot
import com.lumora.photoeditor.engine.EditState
import org.junit.Assert.*
import org.junit.Test

/**
 * First-ever executed verification of SnapshotStore's pure snapshot policy
 * (2026-10-20): the name gate shared by take()/rename() and the
 * MAX_PER_PHOTO eviction rule. A wrong eviction silently deletes the user's
 * saved snapshots; a broken name gate writes unnamed ones. Runs against the
 * REAL shipped helpers (extracted mechanically in the harness) plus the full
 * Gradle unit-test compile.
 */
class SnapshotStoreTest {

    // ------------------------------------------------------------------
    // cleanSnapshotName
    // ------------------------------------------------------------------

    @Test fun `name is returned trimmed`() {
        assertEquals("Golden Hour", cleanSnapshotName("  Golden Hour  "))
    }

    @Test fun `tabs and newlines are trimmed`() {
        assertEquals("Hi", cleanSnapshotName("\t\n Hi \r\n"))
    }

    @Test fun `empty name is rejected`() {
        assertNull(cleanSnapshotName(""))
    }

    @Test fun `blank-only name is rejected`() {
        assertNull(cleanSnapshotName("   "))
        assertNull(cleanSnapshotName("\t \n"))
    }

    @Test fun `inner spacing is preserved`() {
        assertEquals("My  cool  snap", cleanSnapshotName("  My  cool  snap "))
    }

    @Test fun `unicode name is kept verbatim`() {
        assertEquals("ညခင်း", cleanSnapshotName(" ညခင်း "))
    }

    // ------------------------------------------------------------------
    // snapshotsToEvict
    // ------------------------------------------------------------------

    private fun snaps(count: Int, newestFirst: Boolean = true): List<EditSnapshot> {
        val ids = (0 until count).map { "s$it" }
        val ordered = if (newestFirst) ids.reversed() else ids
        return ordered.mapIndexed { idx, id ->
            EditSnapshot(id = id, name = "N$idx", createdAt = 10_000L - idx, state = EditState())
        }
    }

    @Test fun `cap constant is ten`() {
        assertEquals(10, SnapshotStore.MAX_PER_PHOTO)
    }

    @Test fun `empty list evicts nothing`() {
        assertTrue(emptyList<EditSnapshot>().snapshotsToEvict().isEmpty())
    }

    @Test fun `under the cap evicts nothing`() {
        assertTrue(snaps(9).snapshotsToEvict().isEmpty())
    }

    @Test fun `exactly at the cap evicts nothing`() {
        assertTrue(snaps(10).snapshotsToEvict().isEmpty())
    }

    @Test fun `one over the cap evicts the single oldest`() {
        val evicted = snaps(11).snapshotsToEvict()
        assertEquals(listOf("s0"), evicted.map { it.id })
    }

    @Test fun `five over the cap evicts the five oldest in order`() {
        val evicted = snaps(15).snapshotsToEvict()
        assertEquals(listOf("s4", "s3", "s2", "s1", "s0"), evicted.map { it.id })
    }

    @Test fun `evicted entries are the tail of the sorted input`() {
        val list = snaps(13)
        val evicted = list.snapshotsToEvict()
        assertEquals(list.takeLast(3), evicted)
    }

    @Test fun `the newest snapshot is never evicted`() {
        // take() writes the new snapshot first, then evicts: it always has
        // the newest timestamp (id s12 at index 0 here).
        val list = snaps(13)
        val evicted = list.snapshotsToEvict()
        assertFalse(evicted.any { it.id == "s12" })
    }

    @Test fun `kept entries are exactly the newest ten`() {
        val list = snaps(14)
        val kept = list - list.snapshotsToEvict().toSet()
        assertEquals(10, kept.size)
        assertEquals((4..13).map { "s$it" }.toSet(), kept.map { it.id }.toSet())
    }
}
