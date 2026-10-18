package com.lumora.photoeditor

import com.lumora.photoeditor.engine.LaplaceHeal
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/**
 * Regression tests for the Heal tool's Laplace seamless cloning.
 *
 * Guards the bug where RetouchEngine passed the (initially transparent)
 * retouch overlay as the heal source image, making HEAL a silent no-op:
 * the overlay must receive visible, alpha-covered pixels.
 */
class LaplaceHealTest {

    private val w = 64
    private val h = 64

    /** Smooth vertical gradient image, fully opaque. */
    private fun gradient(): IntArray = IntArray(w * h) { i ->
        val y = i / w
        val v = (40 + y * 2).coerceIn(0, 255)
        (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }

    private fun alpha(px: Int) = (px ushr 24) and 0xFF
    private fun lum(px: Int) =
        0.2126f * ((px shr 16) and 0xFF) + 0.7152f * ((px shr 8) and 0xFF) + 0.0722f * (px and 0xFF)

    /** Straight-alpha "over" composite of one overlay pixel onto the base. */
    private fun composite(base: Int, over: Int): Int {
        val a = alpha(over) / 255f
        if (a <= 0f) return base
        fun ch(shift: Int): Int {
            val b = ((base shr shift) and 0xFF).toFloat()
            val o = ((over shr shift) and 0xFF).toFloat()
            return (b * (1f - a) + o * a).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    @Test fun heal_writesVisibleOverlayPixels() {
        val image = gradient()
        // dark blemish in the middle
        for (y in 28..36) for (x in 28..36) {
            val dx = x - 32; val dy = y - 32
            if (dx * dx + dy * dy <= 16) image[y * w + x] = (0xFF shl 24) or (5 shl 16) or (5 shl 8) or 5
        }
        val out = IntArray(w * h) // transparent overlay, as in the app
        LaplaceHeal.heal(image, out, w, h, 32f, 32f, 10f, 18f, 0f, 0.6f, 1f)

        var covered = 0
        for (p in out) if (alpha(p) > 10) covered++
        assertTrue("heal must cover overlay pixels, covered=$covered", covered > 50)

        // outside the disc the overlay must stay transparent
        assertEquals(0, alpha(out[0]))
        assertEquals(0, alpha(out[(h - 1) * w + (w - 1)]))
    }

    @Test fun heal_removesBlemishTowardSurroundingTone() {
        val image = gradient()
        for (y in 28..36) for (x in 28..36) {
            val dx = x - 32; val dy = y - 32
            if (dx * dx + dy * dy <= 16) image[y * w + x] = (0xFF shl 24) // black blob
        }
        val out = IntArray(w * h)
        LaplaceHeal.heal(image, out, w, h, 32f, 32f, 10f, 20f, 0f, 0.6f, 1f)

        val before = lum(image[32 * w + 32])
        val after = lum(composite(image[32 * w + 32], out[32 * w + 32]))
        val surrounding = lum(image[32 * w + 52]) // clean area at same height
        assertTrue(
            "healed center should move toward surrounding tone " +
                "(before=$before after=$after surrounding=$surrounding)",
            abs(after - surrounding) < abs(before - surrounding),
        )
    }

    @Test fun heal_boundaryIsSeamless() {
        val image = gradient()
        val out = IntArray(w * h)
        // heal a clean area onto itself: result must be ~identical everywhere
        LaplaceHeal.heal(image, out, w, h, 32f, 32f, 12f, 0f, 0f, 0.6f, 1f)
        var maxDiff = 0f
        for (i in image.indices) {
            val c = composite(image[i], out[i])
            maxDiff = maxOf(maxDiff, abs(lum(c) - lum(image[i])))
        }
        assertTrue("self-heal must be seamless, maxDiff=$maxDiff", maxDiff < 3f)
    }

    @Test fun heal_zeroStrengthIsNoOp() {
        val image = gradient()
        val out = IntArray(w * h)
        LaplaceHeal.heal(image, out, w, h, 32f, 32f, 10f, 18f, 0f, 0.6f, 0f)
        assertTrue("strength=0 must leave the overlay untouched", out.all { it == 0 })
    }

    @Test fun heal_guardsBadSizes() {
        val out = IntArray(w * h)
        // must not throw on undersized arrays
        LaplaceHeal.heal(IntArray(10), out, w, h, 32f, 32f, 10f, 0f, 0f)
        LaplaceHeal.heal(gradient(), IntArray(10), w, h, 32f, 32f, 10f, 0f, 0f)
        assertTrue(out.all { it == 0 })
    }

    @Test fun heal_overlayWriteRoundsToNearest_noDarkBias() {
        // Regression: the overlay write truncated the float healed color
        // (.toInt()) instead of rounding to nearest. On a non-planar tone
        // field the healed value is genuinely fractional, so truncation
        // biased every healed pixel down — measured mean -0.47 levels over
        // the disc interior on a mild quadratic gradient, a faint darkening
        // inside every heal stroke on smooth gradients/skin. Rounding is
        // unbiased (same rationale as RetouchEngine.blendInto).
        val img = IntArray(w * h) { i ->
            val y = i / w
            val v = (40 + 2 * y + y * y / 256.0).toInt().coerceIn(0, 255)
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        val out = IntArray(w * h)
        LaplaceHeal.heal(img, out, w, h, 32f, 32f, 10f, 0f, 7f, 0.6f, 1f)
        var sum = 0.0; var n = 0
        for (y in 0 until h) for (x in 0 until w) {
            val dx = x - 32f; val dy = y - 32f
            if (dx * dx + dy * dy > 4f * 4f) continue // interior: mask == 1
            val o = out[y * w + x]
            assertEquals("alpha must be opaque in the disc interior", 255, alpha(o))
            sum += ((o shr 16) and 0xFF) - ((img[y * w + x] shr 16) and 0xFF)
            n++
        }
        assertTrue("interior sampled", n > 40)
        val meanBias = sum / n
        assertTrue(
            "heal must not systematically darken the disc (meanBias=$meanBias)",
            abs(meanBias) < 0.3,
        )
    }
}
