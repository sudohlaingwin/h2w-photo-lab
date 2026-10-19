package com.lumora.photoeditor.data

import com.lumora.photoeditor.engine.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

/**
 * First-ever executed verification of the edit-sidecar FILE contract — the
 * crash-safety behavior EditStore.save/load have always promised in KDoc but
 * no harness ever executed: atomic save (no half-written sidecar survives an
 * interrupted write), corrupt quarantine (bad bytes are preserved aside, the
 * photo's edits reset instead of crashing), validate-before-write on the sync
 * path, and the exact on-disk naming layout.
 *
 * Runs against the REAL EditSidecarIo.kt + REAL EditParams.kt/CropParams.kt
 * (kotlinc 2.0.20 + serialization plugin) on throwaway temp directories —
 * the Gradle unit-test runner is broken in this env.
 */
class EditStoreIoTest {

    private lateinit var dir: java.io.File
    private lateinit var io: EditSidecarIo

    @Before fun setUp() {
        dir = Files.createTempDirectory("editsidecario").toFile()
        io = EditSidecarIo(dir)
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    private fun edited(): EditState =
        EditState(light = LightParams(exposure = 1.25f, contrast = -20f))

    // ------------------------------------------------------------------
    // On-disk layout
    // ------------------------------------------------------------------

    @Test fun `sidecar tmp and corrupt names are pinned`() {
        assertEquals("abc.json", io.sidecarFile("abc").name)
        assertEquals("abc.json.tmp", io.tmpFile("abc").name)
        assertEquals("abc.json.corrupt", io.corruptFile("abc").name)
        assertEquals(dir, io.sidecarFile("abc").parentFile)
    }

    // ------------------------------------------------------------------
    // save / load
    // ------------------------------------------------------------------

    @Test fun `save then load round-trips the edit state`() {
        val state = edited()
        io.save("p1", state)
        assertEquals(state, io.load("p1"))
    }

    @Test fun `save leaves no tmp file behind`() {
        io.save("p1", edited())
        assertFalse(io.tmpFile("p1").exists())
    }

    @Test fun `save overwrites the previous sidecar`() {
        io.save("p1", edited())
        val newer = EditState(light = LightParams(exposure = -0.5f))
        io.save("p1", newer)
        assertEquals(newer, io.load("p1"))
    }

    @Test fun `stale tmp from an interrupted save is replaced on the next save`() {
        // Simulate a crash between tmp.writeText and renameTo: garbage tmp
        // left behind, no sidecar yet.
        io.tmpFile("p1").writeText("half-written garbage")
        val state = edited()
        io.save("p1", state)
        assertEquals(state, io.load("p1"))
        assertFalse(io.tmpFile("p1").exists())
    }

    @Test fun `load of a missing sidecar returns a fresh default state`() {
        assertEquals(EditState(), io.load("never-saved"))
    }

    @Test fun `load of a corrupt sidecar quarantines the bytes and returns fresh`() {
        val garbage = "{ this is not valid json at all"
        io.sidecarFile("p1").writeText(garbage)
        assertEquals(EditState(), io.load("p1"))
        // Original sidecar is gone; the bad bytes are preserved for diagnosis.
        assertFalse(io.sidecarFile("p1").exists())
        assertTrue(io.corruptFile("p1").exists())
        assertEquals(garbage, io.corruptFile("p1").readText())
    }

    @Test fun `delete removes the sidecar`() {
        io.save("p1", edited())
        io.delete("p1")
        assertFalse(io.sidecarFile("p1").exists())
        assertEquals(EditState(), io.load("p1"))
    }

    // ------------------------------------------------------------------
    // copySidecar (virtual copies)
    // ------------------------------------------------------------------

    @Test fun `copySidecar copies the exact bytes`() {
        val state = edited()
        io.save("a", state)
        assertTrue(io.copySidecar("a", "b"))
        assertEquals(state, io.load("b"))
        assertEquals(io.sidecarFile("a").readBytes().toList(), io.sidecarFile("b").readBytes().toList())
    }

    @Test fun `copySidecar with a missing source is fine and returns true`() {
        assertTrue(io.copySidecar("missing", "b"))
        assertFalse(io.sidecarFile("b").exists())
    }

    // ------------------------------------------------------------------
    // readRaw / saveRaw (sync path)
    // ------------------------------------------------------------------

    @Test fun `readRaw of a missing sidecar is null`() {
        assertNull(io.readRaw("never-saved"))
    }

    @Test fun `readRaw returns the exact stored bytes`() {
        io.save("p1", edited())
        assertEquals(io.sidecarFile("p1").readText(), io.readRaw("p1"))
    }

    @Test fun `saveRaw with an invalid payload is rejected and never clobbers`() {
        val good = edited()
        io.save("p1", good)
        val before = io.sidecarFile("p1").readText()
        assertFalse(io.saveRaw("p1", "{ not valid json"))
        // The good local sidecar is untouched.
        assertEquals(before, io.sidecarFile("p1").readText())
        assertEquals(good, io.load("p1"))
    }

    @Test fun `saveRaw with an invalid payload creates no file`() {
        assertFalse(io.saveRaw("fresh", "garbage{{{"))
        assertFalse(io.sidecarFile("fresh").exists())
    }

    @Test fun `saveRaw with a valid payload writes it atomically`() {
        val state = EditState(color = ColorParams(saturation = 30f))
        val raw = io.encode(state)
        assertTrue(io.saveRaw("p1", raw))
        assertEquals(state, io.load("p1"))
        assertFalse(io.tmpFile("p1").exists())
    }

    @Test fun `saveRaw never throws on an unreadable directory`() {
        // A path that cannot be created as a directory: every write fails.
        val bad = EditSidecarIo(java.io.File(dir, "not-a-dir/sub"))
        assertFalse(bad.saveRaw("p1", bad.encode(edited())))
        assertNull(bad.readRaw("p1"))
    }

    // ------------------------------------------------------------------
    // sidecarLastModified / encode-decode wiring
    // ------------------------------------------------------------------

    @Test fun `sidecarLastModified is 0 when there is no sidecar`() {
        assertEquals(0L, io.sidecarLastModified("never-saved"))
    }

    @Test fun `sidecarLastModified is positive after a save`() {
        io.save("p1", edited())
        assertTrue(io.sidecarLastModified("p1") > 0L)
    }

    @Test fun `encode and decode use the same config as the io path`() {
        val state = edited()
        // If the extracted sidecarJson() ever drifted from the config the
        // files are written with, this parity check fails.
        io.save("p1", state)
        assertEquals(state, io.decode(io.sidecarFile("p1").readText()))
        assertEquals(state, io.decode(io.encode(state)))
    }
}
