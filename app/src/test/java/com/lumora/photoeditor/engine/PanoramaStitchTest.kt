package com.lumora.photoeditor.engine

import org.junit.Assert.*
import org.junit.Test

/**
 * First-ever executed verification of [PanoramaStitch] — the deterministic
 * translation-only panorama stitcher behind pro-spec Phase 4 ("Photomerge"
 * parity): coarse-to-fine zero-mean SAD shift estimation, linear cross-fade
 * compositing, and auto-crop to the largest fully-covered rectangle.
 *
 * Pure Kotlin, zero Android imports — runs on the JVM harness.
 */
class PanoramaStitchTest {

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Deterministic pseudo-random byte from (x, y) — textured, aperiodic
     *  well beyond the frame sizes used here. */
    private fun noise(x: Int, y: Int): Int {
        var h = x * 1103515245 + y * 12345 + 42
        h = h xor (h ushr 16)
        h *= -0x7a0b8c55 // 0x85ebca6b as signed Int — bits only
        h = h xor (h ushr 13)
        return h and 0xFF
    }

    private fun gray(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    /** Wide noise scene; frame A = scene[0, w), frame B = scene[dx, dx+w). */
    private fun sceneFrame(x0: Int, y0: Int, w: Int, h: Int): IntArray =
        IntArray(w * h) { i -> gray(noise(x0 + i % w, y0 + i / w)) }

    // ------------------------------------------------------------------
    // workingSize
    // ------------------------------------------------------------------

    @Test fun `workingSize leaves smaller frames untouched`() {
        assertEquals(800 to 600, PanoramaStitch.workingSize(800, 600))
        assertEquals(1600 to 900, PanoramaStitch.workingSize(1600, 900))
    }

    @Test fun `workingSize downscales preserving aspect`() {
        assertEquals(1600 to 1200, PanoramaStitch.workingSize(4000, 3000))
        assertEquals(1600 to 900, PanoramaStitch.workingSize(3200, 1800))
    }

    @Test fun `workingSize non-positive maxDim disables the cap`() {
        assertEquals(4000 to 3000, PanoramaStitch.workingSize(4000, 3000, 0))
        assertEquals(4000 to 3000, PanoramaStitch.workingSize(4000, 3000, -5))
    }

    @Test fun `workingSize never collapses below 1px`() {
        assertEquals(1 to 1, PanoramaStitch.workingSize(3, 2, 1))
    }

    // ------------------------------------------------------------------
    // matchSize
    // ------------------------------------------------------------------

    @Test fun `matchSize leaves small frames untouched`() {
        assertEquals(100 to 60, PanoramaStitch.matchSize(100, 60))
        assertEquals(320 to 200, PanoramaStitch.matchSize(320, 200))
    }

    @Test fun `matchSize downscales to the 320 long side`() {
        assertEquals(320 to 240, PanoramaStitch.matchSize(1600, 1200))
    }

    @Test fun `matchSize floors the short side at 8px`() {
        assertEquals(320 to 8, PanoramaStitch.matchSize(400, 1))
    }

    // ------------------------------------------------------------------
    // lumDown
    // ------------------------------------------------------------------

    @Test fun `lumDown output size matches the target`() {
        val out = PanoramaStitch.lumDown(IntArray(100 * 60) { gray(128) }, 100, 60, 50, 30)
        assertEquals(50 * 30, out.size)
    }

    @Test fun `lumDown luminance values are correct`() {
        fun lumOf(p: Int) =
            PanoramaStitch.lumDown(intArrayOf(p), 1, 1, 1, 1)[0]
        assertEquals(1.0f, lumOf(0xFFFFFFFF.toInt()), 0.002f)
        assertEquals(0.0f, lumOf(0xFF000000.toInt()), 0.0f)
        assertEquals(0.299f, lumOf(0xFFFF0000.toInt()), 0.002f)
        assertEquals(0.587f, lumOf(0xFF00FF00.toInt()), 0.002f)
        assertEquals(0.114f, lumOf(0xFF0000FF.toInt()), 0.002f)
    }

    // ------------------------------------------------------------------
    // estimateShift
    // ------------------------------------------------------------------

    @Test fun `estimateShift finds a known horizontal offset`() {
        val (w, h, dx) = Triple(100, 60, 40)
        val a = sceneFrame(0, 0, w, h)
        val b = sceneFrame(dx, 0, w, h)
        val la = PanoramaStitch.lumDown(a, w, h, w, h)
        val lb = PanoramaStitch.lumDown(b, w, h, w, h)
        assertEquals(dx to 0, PanoramaStitch.estimateShift(la, lb, w, h))
    }

    @Test fun `estimateShift finds a known vertical offset`() {
        val (w, h, dx, dy) = listOf(100, 60, 40, 3)
        val a = sceneFrame(0, 0, w, h)
        val b = sceneFrame(dx, dy, w, h)
        val la = PanoramaStitch.lumDown(a, w, h, w, h)
        val lb = PanoramaStitch.lumDown(b, w, h, w, h)
        assertEquals(dx to dy, PanoramaStitch.estimateShift(la, lb, w, h))
    }

    @Test fun `estimateShift falls back on flat frames`() {
        val (w, h) = 32 to 32
        val flat = FloatArray(w * h) { 0.5f }
        // 25%-overlap guess: (w * 0.75, 0)
        assertEquals(24 to 0, PanoramaStitch.estimateShift(flat, flat, w, h))
    }

    // ------------------------------------------------------------------
    // stitchFull guards
    // ------------------------------------------------------------------

    @Test(expected = IllegalArgumentException::class)
    fun `stitchFull rejects an empty frame list`() {
        PanoramaStitch.stitchFull(emptyList(), 100, 60)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `stitchFull rejects mismatched frame sizes`() {
        PanoramaStitch.stitchFull(
            listOf(IntArray(100 * 60), IntArray(50)), 100, 60)
    }

    @Test fun `single frame returns an unchanged copy`() {
        val f = IntArray(100 * 60) { gray(it and 0xFF) }
        val r = PanoramaStitch.stitchFull(listOf(f), 100, 60)
        assertEquals(100, r.width)
        assertEquals(60, r.height)
        assertArrayEquals(f, r.pixels)
        assertNotSame(f, r.pixels)
    }

    @Test fun `stitch matches stitchFull pixels`() {
        val (w, h, dx) = Triple(100, 60, 40)
        val frames = listOf(
            sceneFrame(0, 0, w, h),
            sceneFrame(dx, 0, w, h),
        )
        assertArrayEquals(
            PanoramaStitch.stitchFull(frames, w, h).pixels,
            PanoramaStitch.stitch(frames, w, h),
        )
    }

    // ------------------------------------------------------------------
    // stitchFull composition — the money tests
    // ------------------------------------------------------------------

    @Test fun `two frames stitch into the expected panorama`() {
        val (w, h, dx) = Triple(100, 60, 40)
        val frames = listOf(
            sceneFrame(0, 0, w, h),
            sceneFrame(dx, 0, w, h),
        )
        val r = PanoramaStitch.stitchFull(frames, w, h)
        assertEquals(140, r.width)
        assertEquals(60, r.height)
        assertEquals(140 * 60, r.pixels.size)

        // A-only region (left): frame A's content, untouched.
        assertEquals(gray(noise(20, 30)), r.pixels[30 * 140 + 20])
        // B-only region (right): frame B's content, untouched.
        assertEquals(gray(noise(120, 30)), r.pixels[30 * 140 + 120])
        // Overlap region: both frames agree here, so the cross-fade
        // must reproduce the scene value (within float rounding).
        val overlap = r.pixels[30 * 140 + 60]
        val want = noise(60, 30)
        assertEquals(0xFF, overlap ushr 24)
        assertTrue(kotlin.math.abs(((overlap shr 16) and 0xFF) - want) <= 1)
        assertTrue(kotlin.math.abs(((overlap shr 8) and 0xFF) - want) <= 1)
        assertTrue(kotlin.math.abs((overlap and 0xFF) - want) <= 1)

        // Everything is forced opaque.
        assertTrue(r.pixels.all { (it ushr 24) == 0xFF })
    }

    @Test fun `cross-fade blends disagreeing overlap content`() {
        val (w, h, dx) = Triple(100, 60, 40)
        val a = sceneFrame(0, 0, w, h)
        val b = sceneFrame(dx, 0, w, h)
        // Disagree only OUTSIDE the matcher's sampling strips
        // (A's right 20 cols / B's cols 40..60) so the shift still locks.
        for (y in 0 until h) {
            for (x in 40 until 80) a[y * w + x] = 0xFFFF0000.toInt() // red
            for (x in 0 until 40) b[y * w + x] = 0xFF0000FF.toInt() // blue
        }
        val r = PanoramaStitch.stitchFull(listOf(a, b), w, h)
        assertEquals(140, r.width)

        // Canvas (60, 30): A=red weight 1, B=blue weight (60-40+1)/60 = 0.35.
        // R = 255/1.35 ~= 189, B = 255*0.35/1.35 ~= 66, G = 0.
        val p = r.pixels[30 * 140 + 60]
        val pr = (p shr 16) and 0xFF
        val pg = (p shr 8) and 0xFF
        val pb = p and 0xFF
        assertTrue("red channel $pr out of range", pr in 186..192)
        assertEquals(0, pg)
        assertTrue("blue channel $pb out of range", pb in 63..69)
    }

    @Test fun `three frames chain their offsets`() {
        val (w, h, dx) = Triple(100, 60, 40)
        val frames = listOf(
            sceneFrame(0, 0, w, h),
            sceneFrame(dx, 0, w, h),
            sceneFrame(2 * dx, 0, w, h),
        )
        val r = PanoramaStitch.stitchFull(frames, w, h)
        assertEquals(180, r.width)
        assertEquals(60, r.height)
        // C-only region (right): third frame's content, untouched.
        assertEquals(gray(noise(160, 30)), r.pixels[30 * 180 + 160])
    }

    @Test fun `canvas width is capped for memory safety`() {
        val (w, h) = 32 to 32
        // Flat frames -> 0.75w fallback shift -> canvas would be 6008 wide.
        val frames = List(250) { IntArray(w * h) { 0xFF808080.toInt() } }
        val r = PanoramaStitch.stitchFull(frames, w, h)
        assertEquals(PanoramaStitch.MAX_CANVAS_WIDTH, r.width)
        assertEquals(6000, r.width)
        assertEquals(32, r.height)
        assertEquals(0xFF808080.toInt(), r.pixels[0])
        assertEquals(0xFF808080.toInt(), r.pixels.last())
    }

    @Test fun `stitchFull is deterministic`() {
        val (w, h, dx) = Triple(100, 60, 40)
        val frames = listOf(
            sceneFrame(0, 0, w, h),
            sceneFrame(dx, 0, w, h),
        )
        assertArrayEquals(
            PanoramaStitch.stitchFull(frames, w, h).pixels,
            PanoramaStitch.stitchFull(frames, w, h).pixels,
        )
    }

    // ------------------------------------------------------------------
    // autoCrop
    // ------------------------------------------------------------------

    @Test fun `autoCrop returns the same array when fully covered`() {
        val canvas = IntArray(4 * 3) { gray(it) }
        val cover = IntArray(4 * 3) { 1 }
        assertSame(canvas, PanoramaStitch.autoCrop(canvas, cover, 4, 3))
    }

    @Test fun `autoCrop trims an uncovered top row`() {
        val (w, h) = 4 to 3
        val canvas = IntArray(w * h) { gray(10 * it) }
        val cover = IntArray(w * h) { i -> if (i / w == 0) 0 else 1 }
        val out = PanoramaStitch.autoCrop(canvas, cover, w, h)
        assertEquals(w * 2, out.size)
        assertEquals(canvas[1 * w + 0], out[0])
        assertEquals(canvas[1 * w + 3], out[3])
        assertEquals(canvas[2 * w + 1], out[w + 1])
    }

    @Test fun `autoCrop trims rows then columns`() {
        val (w, h) = 4 to 4
        val canvas = IntArray(w * h) { gray(10 * it) }
        val cover = IntArray(w * h) { 1 }
        for (x in 0 until w) cover[0 * w + x] = 0      // row 0 uncovered
        for (x in 0 until w) cover[3 * w + x] = 0      // row 3 uncovered
        cover[0 * w + 3] = 0                           // col-3 hole, discarded row
        val out = PanoramaStitch.autoCrop(canvas, cover, w, h)
        // Rows 1..2 kept. The column phase then provably no-ops: every kept
        // row is fully covered across the whole width, so x0=0/x1=w-1 by
        // construction (the x-trim loops always break on first iteration).
        // Pinned here so a future "fix" can't silently change output dims.
        assertEquals(4 * 2, out.size)
        assertEquals(canvas[1 * w + 0], out[0])
        assertEquals(canvas[1 * w + 3], out[3])
        assertEquals(canvas[2 * w + 1], out[w + 1])
    }

    @Test fun `autoCrop returns the original when nothing is fully covered`() {
        val (w, h) = 4 to 4
        val canvas = IntArray(w * h) { gray(it) }
        // A single zero column kills every row's full coverage
        // (documented row-major contract) -> null bounds -> original.
        val cover = IntArray(w * h) { i -> if (i % w == 0) 0 else 1 }
        assertSame(canvas, PanoramaStitch.autoCrop(canvas, cover, w, h))
        // All-zero coverage -> also degenerate -> original.
        assertSame(canvas, PanoramaStitch.autoCrop(canvas, IntArray(w * h), w, h))
    }
}
