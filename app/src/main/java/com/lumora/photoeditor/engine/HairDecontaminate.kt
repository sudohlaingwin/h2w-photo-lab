package com.lumora.photoeditor.engine

import kotlin.math.max

/**
 * Hair / edge color decontamination (pro-spec Phase 3, Lightroom
 * "Decontaminate colors").
 *
 * The problem: a feathered mask edge blends subject and background color in
 * the source pixels. When the mask is then used to change the background
 * (inverted mask) or to grade the subject, the fringe pixels carry the OLD
 * background color into the result — the classic halo around hair.
 *
 * The fix here is a deterministic inward-fill despill, NOT AI and NOT a
 * neural matte: for every pixel whose effective selection is partial
 * (0 < mask < 255), the nearest fully-selected (mask == 255) pixel inside a
 * small radius donates its color, mixed in proportionally to how
 * contaminated the pixel is (1 - mask/255). Fully selected and fully
 * unselected pixels are never touched.
 *
 * Pure Kotlin, zero Android imports — harness-testable on the JVM.
 */
object HairDecontaminate {

    /**
     * Returns a copy of [src] with edge pixels despilled.
     *
     * @param src ARGB pixels of the source image, same resolution as [mask].
     * @param mask effective selection per pixel, 0..255 ints (post-inversion:
     *   255 = the side whose colors are kept).
     * @param strength 0..1 — how far edge pixels are pulled toward the
     *   interior color. 0 returns a copy unchanged.
     * @param radiusPx search radius for the nearest fully-selected donor.
     *   Kept small on purpose: hair needs 2..6 px at mask resolution.
     */
    fun despill(
        src: IntArray,
        w: Int,
        h: Int,
        mask: IntArray,
        strength: Float,
        radiusPx: Int,
    ): IntArray {
        require(src.size == w * h) { "src ${src.size} != ${w}x$h" }
        require(mask.size == w * h) { "mask ${mask.size} != ${w}x$h" }
        val out = src.copyOf()
        val s = strength.coerceIn(0f, 1f)
        if (s <= 0f) return out
        val r = radiusPx.coerceAtLeast(1)
        val offsets = spiralOffsets(r)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val m = mask[i] and 0xFF
                // Only the transition band can be contaminated; the interior
                // (255) and exterior (0) keep their exact colors.
                if (m == 0 || m == 255) continue
                var donor = -1
                for (k in offsets.indices step 2) {
                    val nx = x + offsets[k]
                    val ny = y + offsets[k + 1]
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    if ((mask[ny * w + nx] and 0xFF) == 255) {
                        donor = ny * w + nx
                        break
                    }
                }
                if (donor < 0) continue
                // Contamination is worst at the outer edge (m -> 0) and zero
                // at the interior (m -> 255).
                val t = (1f - m / 255f) * s
                out[i] = lerpArgb(src[i], src[donor], t)
            }
        }
        return out
    }

    /**
     * (dx, dy) pairs ordered by increasing Chebyshev distance from (0, 0),
     * ties broken deterministically by dx then dy. The first fully-selected
     * pixel found is therefore the nearest one (Chebyshev metric).
     */
    fun spiralOffsets(radius: Int): IntArray {
        val list = ArrayList<Int>()
        for (d in 0..radius) {
            for (dy in -d..d) {
                for (dx in -d..d) {
                    if (max(kotlin.math.abs(dx), kotlin.math.abs(dy)) == d) {
                        list.add(dx); list.add(dy)
                    }
                }
            }
        }
        return list.toIntArray()
    }

    private fun lerpArgb(a: Int, b: Int, t: Float): Int {
        val ar = (a ushr 16) and 0xFF; val ag = (a ushr 8) and 0xFF; val ab = a and 0xFF
        val br = (b ushr 16) and 0xFF; val bg = (b ushr 8) and 0xFF; val bb = b and 0xFF
        val r = (ar + (br - ar) * t + 0.5f).toInt().coerceIn(0, 255)
        val g = (ag + (bg - ag) * t + 0.5f).toInt().coerceIn(0, 255)
        val bl = (ab + (bb - ab) * t + 0.5f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }
}
