package com.lumora.photoeditor.engine

import org.junit.Assert.*
import org.junit.Test

/**
 * Executed verification for engine/SubjectSelect.kt (pure Kotlin, zero
 * imports — runs on the standalone kotlinc harness; Gradle's test workers
 * are broken in this env).
 *
 * This pins the observable contract of SubjectSelect.select(), the engine
 * behind the Mask panel's "Subject AI" / "Background AI" buttons and the
 * Auto Mask "Subject (auto)" plan: when it returns a mask, when it honestly
 * returns null instead of a wrong mask, and the shape of the mask it
 * returns. Only the public select() entry point is tested — the helpers
 * (smoothstep/boxBlur/erode/dilate) are private, so their behaviour is
 * pinned through select()'s observable output.
 */
class SubjectSelectTest {

    private val W = 64
    private val H = 64

    private fun argb(r: Int, g: Int, b: Int) =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun uniformFrame(r: Int, g: Int, b: Int, w: Int = W, h: Int = H) =
        IntArray(w * h) { argb(r, g, b) }

    /** Bright disk on a dark background, radius [rad], centered at (cx, cy). */
    private fun diskFrame(
        bgR: Int, bgG: Int, bgB: Int,
        fgR: Int, fgG: Int, fgB: Int,
        cx: Int, cy: Int, rad: Int,
        w: Int = W, h: Int = H
    ) = IntArray(w * h) { i ->
        val x = i % w; val y = i / w
        val dx = x - cx; val dy = y - cy
        if (dx * dx + dy * dy <= rad * rad) argb(fgR, fgG, fgB)
        else argb(bgR, bgG, bgB)
    }

    private fun centeredDisk() =
        diskFrame(60, 60, 60, 200, 200, 200, W / 2, H / 2, 12)

    @Test fun tooSmall_returnsNull() {
        assertNull(SubjectSelect.select(IntArray(24 * 23) { argb(0, 0, 0) }, 24, 23))
        assertNull(SubjectSelect.select(IntArray(23 * 24) { argb(0, 0, 0) }, 23, 24))
        assertNull(SubjectSelect.select(IntArray(0), 0, 0))
    }

    @Test fun mismatchedPixelCount_returnsNull() {
        assertNull(SubjectSelect.select(IntArray(100) { argb(0, 0, 0) }, 24, 24))
    }

    @Test fun uniformFrame_returnsNull() {
        assertNull(SubjectSelect.select(uniformFrame(120, 120, 120), W, H))
        assertNull(SubjectSelect.select(uniformFrame(10, 10, 10), W, H))
    }

    @Test fun smoothGradient_returnsNull() {
        // A smooth luminance gradient carries no edges: the edge-content
        // gate (0.03 bar) must reject it rather than return a wrong mask.
        val pixels = IntArray(W * H) { i ->
            val x = i % W
            val v = (255 * x / (W - 1))
            argb(v, v, v)
        }
        assertNull(SubjectSelect.select(pixels, W, H))
    }

    @Test fun centeredBrightDisk_selected() {
        val mask = SubjectSelect.select(centeredDisk(), W, H)
        assertNotNull("bright centered disk must be detected as a subject", mask)
    }

    @Test fun maskValues_inUnitRange() {
        val mask = SubjectSelect.select(centeredDisk(), W, H)!!
        for (v in mask) {
            assertTrue("mask value $v outside [0,1]", v in 0f..1f)
        }
    }

    @Test fun mask_centerHighBackgroundLow() {
        val mask = SubjectSelect.select(centeredDisk(), W, H)!!
        val center = mask[(H / 2) * W + (W / 2)]
        val corner = mask[0]
        assertTrue("subject center mask $center should be high", center > 0.5f)
        assertTrue("background corner mask $corner should be low", corner < 0.3f)
    }

    @Test fun mask_coverageWithinDeclaredBands() {
        // The doc contract: rejects specks (<3%) and whole-frame "subjects"
        // (>70%). A radius-12 disk on 64x64 covers ~11%, safely inside.
        val mask = SubjectSelect.select(centeredDisk(), W, H)!!
        val covered = mask.count { it > 0.5f }
        val frac = covered.toFloat() / (W * H)
        assertTrue("coverage $frac below 3% floor", frac >= 0.03f)
        assertTrue("coverage $frac above 70% ceiling", frac <= 0.70f)
    }

    @Test fun tinySpeck_rejected() {
        // A 2x2 bright dot on 64x64 is 0.1% of the frame: a speck, not a
        // subject — the honest answer is null, not a wrong mask.
        val pixels = uniformFrame(60, 60, 60)
        for (dy in 0..1) for (dx in 0..1) {
            pixels[(H / 2 + dy) * W + (W / 2 + dx)] = argb(220, 220, 220)
        }
        assertNull(SubjectSelect.select(pixels, W, H))
    }

    @Test fun wholeFrameSubject_rejected() {
        // Bright region filling ~83% of the frame is the frame itself, not
        // a subject (doc: >70% coverage returns null).
        val pixels = diskFrame(60, 60, 60, 200, 200, 200, W / 2, H / 2, 33)
        assertNull(SubjectSelect.select(pixels, W, H))
    }

    @Test fun skinToneSubject_selected() {
        // Exercises the YCrCb skin-boost path: a skin-colored subject on a
        // green background must be detected.
        val pixels = diskFrame(40, 140, 60, 208, 152, 120, W / 2, H / 2, 12)
        assertNotNull(
            "skin-tone subject must be detected",
            SubjectSelect.select(pixels, W, H)
        )
    }

    @Test fun offCenterSubject_stillSelected() {
        // The center prior "only breaks ties": a clearly salient subject
        // off-center must still be found.
        val pixels = diskFrame(60, 60, 60, 200, 200, 200, (W * 0.65).toInt(), H / 2, 12)
        assertNotNull(
            "off-center subject must still be detected",
            SubjectSelect.select(pixels, W, H)
        )
    }

    @Test fun select_isDeterministic() {
        val pixels = centeredDisk()
        val a = SubjectSelect.select(pixels, W, H)!!
        val b = SubjectSelect.select(pixels, W, H)!!
        assertArrayEquals(
            "select() must be deterministic across runs",
            a, b, 0f
        )
    }
}
