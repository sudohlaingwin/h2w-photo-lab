package com.lumora.photoeditor.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * First-ever executed verification of the mask-painting core
 * ([MaskEngine.paintStroke]/[MaskEngine.paintBrushStrokes]) and the
 * AI-mask pixel-format contract ([AiMaskFormat]).
 *
 * These pin the Lightroom-parity brush semantics the masking UX depends
 * on: Flow as the per-stamp build-up rate, Opacity as the ceiling paint
 * can never exceed (and 1-opacity as the floor erase can never drive
 * below), the erase-never-creates-density invariant, and pro auto-mask
 * edge gating. Pure JVM (no android.* calls) so it also runs under
 * Gradle; the Bitmap-bound raster paths are pinned in the harness-only
 * companion test (see hidden_files/maskengine-test-harness).
 */
class MaskEngineTest {

    private fun stroke(
        vararg pts: Pair<Float, Float>,
        size: Float = 0.2f,
        feather: Float = 0f,
        flow: Float = 1f,
        opacity: Float = 1f,
        erase: Boolean = false,
        autoMask: Boolean = false,
        tolerance: Float = 0.14f,
    ) = BrushStroke(
        points = pts.map { BrushPoint(it.first, it.second) },
        size = size, feather = feather, flow = flow, opacity = opacity,
        erase = erase, autoMask = autoMask, autoMaskTolerance = tolerance,
    )

    // ------------------------------------------------------------------
    // paintStroke basics
    // ------------------------------------------------------------------

    @Test
    fun paintStroke_emptyPoints_isNoOp() {
        val acc = FloatArray(64 * 64) { 0.7f }
        val before = acc.copyOf()
        MaskEngine.paintStroke(acc, 64, 64, stroke())
        assertArrayEquals(before, acc, 0f)
    }

    @Test
    fun paintStroke_subPixelRadius_isNoOp() {
        val acc = FloatArray(64 * 64)
        // radius = 0.0001 * 64 = 0.0064px < 0.5px floor
        MaskEngine.paintStroke(acc, 64, 64, stroke(0.5f to 0.5f, size = 0.0001f))
        assertTrue(acc.all { it == 0f })
    }

    @Test
    fun paintStroke_singleStamp_centerIsFullDensity_edgeIsZero() {
        val acc = FloatArray(64 * 64)
        MaskEngine.paintStroke(acc, 64, 64, stroke(0.5f to 0.5f, size = 0.25f))
        assertEquals(1f, acc[32 * 64 + 32], 1e-6f)
        // radius = 16px: pixel 20px away from center is outside the stamp
        assertEquals(0f, acc[32 * 64 + 52], 0f)
        // but a pixel well inside the stamp has density
        assertTrue(acc[32 * 64 + 40] > 0.5f)
    }

    @Test
    fun paintStroke_opacityIsACeiling_repeatedStrokesNeverExceedIt() {
        val acc = FloatArray(64 * 64)
        val s = stroke(0.5f to 0.5f, size = 0.25f, flow = 1f, opacity = 0.5f)
        val comp = MaskComponent(id = "c", type = MaskType.BRUSH, strokes = List(10) { s })
        MaskEngine.paintBrushStrokes(acc, 64, 64, comp)
        assertEquals(0.5f, acc.max(), 1e-6f)
    }

    @Test
    fun paintStroke_flowIsTheBuildUpRate() {
        val acc = FloatArray(64 * 64)
        val s = stroke(0.5f to 0.5f, size = 0.25f, feather = 0f, flow = 0.25f, opacity = 1f)
        MaskEngine.paintStroke(acc, 64, 64, s)
        assertEquals(0.25f, acc[32 * 64 + 32], 1e-6f)
        MaskEngine.paintStroke(acc, 64, 64, s)
        // 1 - (1 - 0.25) * (1 - 0.25) = 0.4375
        assertEquals(0.4375f, acc[32 * 64 + 32], 1e-6f)
    }

    @Test
    fun paintStroke_zeroFlowOrZeroOpacity_paintsNothing() {
        val acc = FloatArray(64 * 64)
        MaskEngine.paintStroke(
            acc, 64, 64, stroke(0.5f to 0.5f, size = 0.25f, flow = 0f, opacity = 1f))
        MaskEngine.paintStroke(
            acc, 64, 64, stroke(0.5f to 0.5f, size = 0.25f, flow = 1f, opacity = 0f))
        assertTrue(acc.all { it == 0f })
    }

    // ------------------------------------------------------------------
    // Erase semantics: floor, never below (1 - opacity), never creates.
    // ------------------------------------------------------------------

    @Test
    fun erase_neverDrivesBelowOneMinusOpacity() {
        val acc = FloatArray(64 * 64)
        // paint to 0.75 with a high-opacity paint stroke
        MaskEngine.paintStroke(
            acc, 64, 64, stroke(0.5f to 0.5f, size = 0.25f, opacity = 0.75f))
        assertEquals(0.75f, acc[32 * 64 + 32], 1e-6f)
        // erase with opacity 0.5: floor = 0.5
        MaskEngine.paintStroke(
            acc, 64, 64, stroke(0.5f to 0.5f, size = 0.25f, opacity = 0.5f, erase = true))
        assertEquals(0.5f, acc[32 * 64 + 32], 1e-6f)
    }

    @Test
    fun erase_fullOpacity_clearsPaintedDensity() {
        val acc = FloatArray(64 * 64)
        MaskEngine.paintStroke(
            acc, 64, 64, stroke(0.5f to 0.5f, size = 0.25f, opacity = 0.75f))
        MaskEngine.paintStroke(
            acc, 64, 64, stroke(0.5f to 0.5f, size = 0.25f, opacity = 1f, erase = true))
        assertEquals(0f, acc[32 * 64 + 32], 1e-6f)
    }

    @Test
    fun erase_overUnpaintedArea_stampsNoFloorDisc() {
        // Regression contract: erase must never RAISE pixels. The old
        // (acc * (1-f)).coerceAtLeast(eraseFloor) stamped a hard-edged
        // (1-opacity) disc over untouched areas.
        val acc = FloatArray(64 * 64)
        MaskEngine.paintStroke(
            acc, 64, 64, stroke(0.5f to 0.5f, size = 0.25f, opacity = 0.5f, erase = true))
        assertTrue(acc.all { it == 0f })
    }

    @Test
    fun erase_belowFloorPixel_isUntouched() {
        val acc = FloatArray(64 * 64)
        acc[32 * 64 + 32] = 0.2f // already below the 0.5 erase floor
        MaskEngine.paintStroke(
            acc, 64, 64, stroke(0.5f to 0.5f, size = 0.25f, opacity = 0.5f, erase = true))
        assertEquals(0.2f, acc[32 * 64 + 32], 1e-6f)
    }

    // ------------------------------------------------------------------
    // Pro auto-mask: edge-aware gating by luminance similarity.
    // ------------------------------------------------------------------

    private fun splitLuminance(w: Int, h: Int, left: Float, right: Float): FloatArray {
        return FloatArray(w * h) { i -> if (i % w < w / 2) left else right }
    }

    @Test
    fun autoMask_suppressesPaintAcrossStrongLuminanceEdge() {
        val w = 64; val h = 64
        val lum = splitLuminance(w, h, 0.2f, 0.8f)
        val acc = FloatArray(w * h)
        // center (16,32) sits in the 0.2 half; radius 32px reaches into 0.8 half
        MaskEngine.paintStroke(
            acc, w, h,
            stroke(0.25f to 0.5f, size = 0.5f, autoMask = true, tolerance = 0.14f),
            srcLum = lum,
        )
        // same-tone side paints fully
        assertEquals(1f, acc[32 * w + 20], 1e-5f)
        // across-the-edge pixel is fully gated
        assertEquals(0f, acc[32 * w + 44], 0f)
    }

    @Test
    fun autoMask_withoutSourceLuminance_paintsLikeNormalBrush() {
        val w = 64; val h = 64
        val acc = FloatArray(w * h)
        MaskEngine.paintStroke(
            acc, w, h,
            stroke(0.25f to 0.5f, size = 0.5f, autoMask = true),
            srcLum = null, // no luminance available -> gating skipped
        )
        assertTrue(acc[32 * w + 44] > 0.9f)
    }

    @Test
    fun autoMask_withinTolerance_paintsFully() {
        val w = 64; val h = 64
        val lum = splitLuminance(w, h, 0.2f, 0.3f) // dLum 0.1 < tolerance 0.5
        val acc = FloatArray(w * h)
        MaskEngine.paintStroke(
            acc, w, h,
            stroke(0.25f to 0.5f, size = 0.5f, autoMask = true, tolerance = 0.5f),
            srcLum = lum,
        )
        assertTrue(acc[32 * w + 44] > 0.9f)
    }

    // ------------------------------------------------------------------
    // Polylines and accumulator equivalence.
    // ------------------------------------------------------------------

    @Test
    fun paintStroke_polyline_stampsAlongWholePath() {
        val acc = FloatArray(64 * 64)
        MaskEngine.paintStroke(
            acc, 64, 64, stroke(0.25f to 0.5f, 0.75f to 0.5f, size = 0.1f))
        // midpoint of the path is painted
        assertTrue(acc[32 * 64 + 32] > 0.9f)
        // and the stroke start too
        assertTrue(acc[32 * 64 + 16] > 0.9f)
    }

    @Test
    fun paintBrushStrokes_equalsSequentialPaintStrokeCalls() {
        val comp = MaskComponent(
            id = "c", type = MaskType.BRUSH, strokes = listOf(
                stroke(0.3f to 0.4f, size = 0.2f, flow = 0.6f, opacity = 0.8f),
                stroke(0.7f to 0.6f, 0.75f to 0.65f, size = 0.15f, feather = 0.5f,
                    flow = 0.4f, opacity = 0.9f),
            ),
        )
        val a1 = FloatArray(64 * 64)
        MaskEngine.paintBrushStrokes(a1, 64, 64, comp)
        val a2 = FloatArray(64 * 64)
        for (s in comp.strokes) MaskEngine.paintStroke(a2, 64, 64, s)
        assertArrayEquals(a1, a2, 0f)
    }

    @Test
    fun paintStroke_isDeterministic() {
        val s = stroke(0.3f to 0.7f, 0.6f to 0.2f, size = 0.2f,
            feather = 0.4f, flow = 0.7f, opacity = 0.85f, autoMask = true,
            tolerance = 0.2f)
        val lum = splitLuminance(64, 64, 0.3f, 0.7f)
        val a1 = FloatArray(64 * 64); val a2 = FloatArray(64 * 64)
        MaskEngine.paintStroke(a1, 64, 64, s, lum)
        MaskEngine.paintStroke(a2, 64, 64, s, lum)
        assertArrayEquals(a1, a2, 0f)
    }

    // ------------------------------------------------------------------
    // AiMaskFormat: the alpha-channel pixel contract.
    // ------------------------------------------------------------------

    @Test
    fun aiMaskFormat_packUnpack_roundTrips() {
        for (a in 0..255) {
            assertEquals("a=$a", a, AiMaskFormat.unpack(AiMaskFormat.pack(a)))
        }
    }

    @Test
    fun aiMaskFormat_pack_clampsOutOfRange() {
        assertEquals(0, AiMaskFormat.unpack(AiMaskFormat.pack(-5)))
        assertEquals(255, AiMaskFormat.unpack(AiMaskFormat.pack(999)))
    }

    @Test
    fun aiMaskFormat_unpack_readsAlphaOnly_ignoresRgb() {
        // The documented failure mode: value written into gray RGB with
        // alpha=0xFF loads back as 255 everywhere.
        val px = (200 shl 24) or 0x00123456
        assertEquals(200, AiMaskFormat.unpack(px))
        assertEquals(0, AiMaskFormat.unpack(0x00ABCDEF))
    }
}
