package com.lumora.photoeditor.data

import com.lumora.photoeditor.engine.EditSnapshot
import com.lumora.photoeditor.engine.EditState
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * First-ever executed verification of SnapshotStore's crash-safety file
 * contract (2026-10-20): atomic snapshot writes, corrupt-skip reads, and the
 * eviction-composition loop take() relies on. Runs against the REAL shipped
 * SnapshotSidecarIo with real files in temp dirs, via the standalone JUnit
 * harness (Gradle's test executor is broken in this env).
 */
class SnapshotStoreIoTest {

    private lateinit var dir: File
    private lateinit var io: SnapshotSidecarIo

    @Before fun setUp() {
        dir = Files.createTempDirectory("snapshotsidecario").toFile()
        io = SnapshotSidecarIo(dir)
    }

    private fun snap(
        id: String = "s1",
        name: String = "Golden Hour",
        createdAt: Long = 1000L,
        state: EditState = EditState(),
    ) = EditSnapshot(id = id, name = name, createdAt = createdAt, state = state)

    // ------------------------------------------------------------------
    // write / read round-trip
    // ------------------------------------------------------------------

    @Test fun `writeSnapshot then readAll round-trips exactly`() {
        val s = snap(state = EditState().copy(light = EditState().light.copy(exposure = 1.5f)))
        io.writeSnapshot(s)
        val all = io.readAll()
        assertEquals(1, all.size)
        assertEquals(s, all[0])
    }

    @Test fun `snapshot file uses the id-dot-json layout`() {
        io.writeSnapshot(snap(id = "abc"))
        assertTrue(io.snapshotFile("abc").isFile)
    }

    @Test fun `no tmp file is left behind after a write`() {
        io.writeSnapshot(snap(id = "abc"))
        assertFalse(io.tmpFile("abc").exists())
        assertEquals(listOf("abc.json"), dir.list()!!.toList())
    }

    @Test fun `stale tmp from an interrupted write is replaced on the next write`() {
        // Simulates the crash mid-write the atomic path guards against:
        // a stale .tmp sits in the dir with garbage bytes.
        io.tmpFile("abc").writeText("{truncated")
        io.writeSnapshot(snap(id = "abc"))
        assertFalse(io.tmpFile("abc").exists())
        assertEquals(snap(id = "abc"), io.readSnapshot("abc"))
    }

    @Test fun `readAll ignores stale tmp files`() {
        io.tmpFile("ghost").writeText("""{"id":"ghost","name":"G","createdAt":1,"state":{}}""")
        io.writeSnapshot(snap(id = "real"))
        val all = io.readAll()
        assertEquals(listOf("real"), all.map { it.id })
    }

    @Test fun `readAll sorts newest-first`() {
        io.writeSnapshot(snap(id = "old", createdAt = 1L))
        io.writeSnapshot(snap(id = "new", createdAt = 3L))
        io.writeSnapshot(snap(id = "mid", createdAt = 2L, name = "Mid"))
        val all = io.readAll()
        assertEquals(3L, all[0].createdAt)
        assertEquals(listOf("new", "mid", "old"), all.map { it.id })
    }

    @Test fun `overwrite replaces instead of duplicating`() {
        io.writeSnapshot(snap(id = "abc", name = "One"))
        io.writeSnapshot(snap(id = "abc", name = "Two"))
        val all = io.readAll()
        assertEquals(1, all.size)
        assertEquals("Two", all[0].name)
    }

    // ------------------------------------------------------------------
    // corrupt handling
    // ------------------------------------------------------------------

    @Test fun `corrupt legacy json is skipped by readAll without throwing`() {
        io.snapshotFile("bad").writeText("{not valid json at all")
        io.writeSnapshot(snap(id = "good"))
        val all = io.readAll()
        assertEquals(listOf("good"), all.map { it.id })
    }

    @Test fun `readSnapshot returns null for a missing id`() {
        assertNull(io.readSnapshot("nope"))
    }

    @Test fun `readSnapshot returns null for a corrupt file`() {
        io.snapshotFile("bad").writeText("[1,2,3")
        assertNull(io.readSnapshot("bad"))
    }

    @Test fun `readSnapshot decodes a valid file`() {
        val s = snap(id = "abc")
        io.writeSnapshot(s)
        assertEquals(s, io.readSnapshot("abc"))
    }

    // ------------------------------------------------------------------
    // rename-equivalent (decode, copy name, atomic rewrite)
    // ------------------------------------------------------------------

    @Test fun `rewriting with a new name preserves id createdAt and state`() {
        val state = EditState().copy(light = EditState().light.copy(exposure = -0.75f))
        io.writeSnapshot(snap(id = "abc", name = "Old", createdAt = 4242L, state = state))
        val cur = io.readSnapshot("abc")!!
        io.writeSnapshot(cur.copy(name = "New"))
        val got = io.readSnapshot("abc")!!
        assertEquals("New", got.name)
        assertEquals("abc", got.id)
        assertEquals(4242L, got.createdAt)
        assertEquals(state, got.state)
    }

    // ------------------------------------------------------------------
    // delete
    // ------------------------------------------------------------------

    @Test fun `deleteSnapshot removes the file`() {
        io.writeSnapshot(snap(id = "abc"))
        io.deleteSnapshot("abc")
        assertFalse(io.snapshotFile("abc").exists())
        assertTrue(io.readAll().isEmpty())
    }

    @Test fun `deleteSnapshot of a missing id is a no-op`() {
        io.deleteSnapshot("nope") // must not throw
        assertTrue(io.readAll().isEmpty())
    }

    // ------------------------------------------------------------------
    // eviction composition (the exact loop take() runs)
    // ------------------------------------------------------------------

    @Test fun `cap loop evicts exactly the oldest beyond MAX_PER_PHOTO`() {
        repeat(12) { i -> io.writeSnapshot(snap(id = "s$i", createdAt = 1000L + i)) }
        val toEvict = io.readAll().snapshotsToEvict()
        assertEquals(2, toEvict.size)
        assertEquals(setOf("s0", "s1"), toEvict.map { it.id }.toSet())
        toEvict.forEach { io.deleteSnapshot(it.id) }
        val remaining = io.readAll()
        assertEquals(SnapshotStore.MAX_PER_PHOTO, remaining.size)
        assertEquals((11 downTo 2).map { "s$it" }, remaining.map { it.id })
    }

    @Test fun `eleven snapshots evict exactly the single oldest`() {
        repeat(11) { i -> io.writeSnapshot(snap(id = "s$i", createdAt = 1000L + i)) }
        val toEvict = io.readAll().snapshotsToEvict()
        assertEquals(listOf("s0"), toEvict.map { it.id })
    }
}
