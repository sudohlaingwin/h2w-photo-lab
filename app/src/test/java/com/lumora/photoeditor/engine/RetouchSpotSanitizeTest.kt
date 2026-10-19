package com.lumora.photoeditor.engine

import org.junit.Assert.*
import org.junit.Test

/**
 * First-ever executed verification that retouch spots with non-finite
 * parameters (NaN/±Inf from touch math or an AI detector dividing by zero)
 * are SKIPPED, never painted.
 *
 * Before the guard, such a spot silently painted a BLACK disc: NaN blend
 * weights round to 0 in the pack sites, so the photo was corrupted with no
 * crash and no error — the worst kind of defect in a retouch tool. Every
 * brush op routes through [RetouchEngine.compositeInto], so the single
 * guard there covers heal, clone, skin smooth, dodge/burn, eye, teeth,
 * under-eye, and red-eye.
 */
class RetouchSpotSanitizeTest {

    private fun flatGray(w: Int, h: Int, v: Int): IntArray =
        IntArray(w * h) { (0xFF shl 24) or (v shl 16) or (v shl 8) or v }

    private fun spot(
        op: RetouchOp = RetouchOp.HEAL,
        x: Float = 0.5f, y: Float = 0.5f,
        srcX: Float = 0.7f, srcY: Float = 0.5f,
        size: Float = 0.3f, feather: Float = 0.7f,
        strength: Float = 1f, textureKeep: Float = 0.45f,
    ) = RetouchSpot(
        op = op, x = x, y = y, srcX = srcX, srcY = srcY,
        size = size, feather = feather, strength = strength,
        textureKeep = textureKeep,
    )

    /** Runs one spot through the real composite path; returns (overlay, composited). */
    private fun runSpot(s: RetouchSpot, w: Int, h: Int, src: IntArray): Pair<IntArray, IntArray> {
        val comp = src.copyOf()
        val overlay = RetouchEngine.compositeInto(listOf(s), w, h, comp)
        return overlay to comp
    }

    private fun assertSkipped(s: RetouchSpot, opName: String) {
        val w = 24; val h = 24
        val src = flatGray(w, h, 128)
        val (overlay, comp) = runSpot(s, w, h, src)
        assertTrue("$opName: overlay must stay transparent", overlay.all { it == 0 })
        assertArrayEquals("$opName: composite must be untouched", src, comp)
    }

    @Test fun nanX_isSkipped() = assertSkipped(spot(x = Float.NaN), "NaN x")

    @Test fun nanY_isSkipped() = assertSkipped(spot(y = Float.NaN), "NaN y")

    @Test fun nanSize_isSkipped() = assertSkipped(spot(size = Float.NaN), "NaN size")

    @Test fun infiniteSize_isSkipped() =
        assertSkipped(spot(size = Float.POSITIVE_INFINITY), "+Inf size")

    @Test fun nanStrength_isSkipped() =
        assertSkipped(spot(strength = Float.NaN), "NaN strength")

    @Test fun nanSrcX_isSkipped() =
        assertSkipped(spot(srcX = Float.NaN), "NaN srcX (heal)")

    @Test fun nanSrcY_isSkipped() =
        assertSkipped(spot(srcY = Float.NaN), "NaN srcY (heal)")

    @Test fun nanFeather_isSkipped() =
        assertSkipped(spot(feather = Float.NaN), "NaN feather")

    @Test fun nanTextureKeep_isSkipped() =
        assertSkipped(spot(op = RetouchOp.SMOOTH_SKIN, textureKeep = Float.NaN),
            "NaN textureKeep (skin)")

    @Test fun nanSpot_skippedForEveryOp() {
        // The guard sits in compositeInto, so it covers every op — spot-check
        // the ops with the heaviest per-op kernels (clone tone-match, skin
        // bilateral, dodge gain) for NaN coordinates.
        for (op in listOf(RetouchOp.CLONE, RetouchOp.SMOOTH_SKIN, RetouchOp.DODGE,
            RetouchOp.BURN, RetouchOp.EYE_ENHANCE, RetouchOp.RED_EYE)) {
            assertSkipped(spot(op = op, x = Float.NaN, y = Float.NaN), "NaN xy ($op)")
        }
    }

    @Test fun validSpot_stillApplies() {
        // Regression net: the guard must not swallow real work. A sane HEAL
        // spot writes a feathered disc into the overlay.
        val w = 24; val h = 24
        val src = flatGray(w, h, 128)
        val (overlay, _) = runSpot(spot(), w, h, src)
        assertTrue("valid spot must write into the overlay",
            overlay.any { ((it ushr 24) and 0xFF) > 0 })
    }

    @Test fun offImage_validSpot_isSkipped() {
        // Sanity: an off-image but otherwise finite spot stays a no-op too.
        val w = 24; val h = 24
        val src = flatGray(w, h, 128)
        val (overlay, comp) = runSpot(spot(x = 5f, y = 5f, srcX = 5f, srcY = 5f), w, h, src)
        assertTrue("off-image overlay must stay transparent", overlay.all { it == 0 })
        assertArrayEquals("off-image composite must be untouched", src, comp)
    }
}
