package com.lumora.photoeditor.engine

import android.util.Log
import kotlin.math.abs

/**
 * Android/GL side of AI Pro Retouch: bakes the basis 3D LUTs from the app's
 * own pro recipes ([ProRecipes]) through the real shader pipeline and fuses
 * per-photo LUTs from [AiProRetouch] blend weights.
 *
 * Every method must run on the GL thread (they touch [GlEngine] textures).
 * Baking stomps the engine's source texture; the caller (EditorViewModel)
 * restores it with engine.setSource(previewBitmap) and then
 * invalidateGpuAssets() — the same restore pattern refreshEditedThumbnail
 * uses. The bank is baked once per process and cached in memory
 * (4 x 17^3 x 3 floats ~= 235 KB).
 */
object AiLutBank {

    private const val TAG = "AiLutBank"

    private var basis: List<FloatArray>? = null

    /**
     * False after a failed identity-bake self-check: the feature stays off
     * rather than shipping a corrupted grade. Check [ensureBaked]'s log for
     * the cause.
     */
    var available: Boolean = true
        private set

    fun basisLuts(): List<FloatArray>? = basis

    /**
     * Bakes the 4 pro-recipe basis LUTs on first call (identity self-check
     * first), then returns the cached bank. Returns true when the bank is
     * usable. GL thread only.
     */
    fun ensureBaked(engine: GlEngine): Boolean {
        if (basis != null) return available
        if (!available) return false
        return try {
            val n = AiProRetouch.LUT_SIZE
            // Identity self-check: the all-default state must bake back to
            // (almost) the identity LUT. Every one of the n^3 cells has a
            // unique color, so any orientation/axis/layout bug in bakeLut()
            // shows up as a large maxErr here — on the real device, before
            // a corrupted LUT can ever reach a photo.
            val identity = engine.bakeLut(EditState(), n)
            var maxErr = 0f
            for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
                val o = (((r * n) + g) * n + b) * 3
                maxErr = maxOf(
                    maxErr,
                    abs(identity[o] - r / (n - 1f)),
                    abs(identity[o + 1] - g / (n - 1f)),
                    abs(identity[o + 2] - b / (n - 1f)),
                )
            }
            if (maxErr > 4f / 255f) {
                Log.e(TAG, "identity LUT bake failed (maxErr=$maxErr); AI Pro Retouch disabled")
                available = false
                return false
            }
            basis = listOf(
                engine.bakeLut(bakeState(portraitGrade()), n),
                engine.bakeLut(bakeState(landscapeGrade()), n),
                engine.bakeLut(bakeState(nightGrade()), n),
                engine.bakeLut(bakeState(backlitGrade()), n),
            )
            Log.i(TAG, "basis LUT bank baked (identity maxErr=$maxErr)")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "basis LUT bake failed", t)
            available = false
            false
        }
    }

    /** Pointwise (LUT-representable) part of a pro recipe. */
    private data class PointwiseGrade(
        val exposure: Float, val contrast: Float,
        val highlights: Float, val shadows: Float, val whites: Float, val blacks: Float,
        val temp: Float, val tint: Float,
        val vibrance: Float, val saturation: Float,
    )

    private fun portraitGrade(): PointwiseGrade {
        val r = ProRecipes.PortraitRecipe()
        return PointwiseGrade(r.exposure, r.contrast, r.highlights, r.shadows, r.whites,
            r.blacks, r.temp, r.tint, r.vibrance, r.saturation)
    }

    private fun landscapeGrade(): PointwiseGrade {
        val r = ProRecipes.LandscapeRecipe()
        return PointwiseGrade(r.exposure, r.contrast, r.highlights, r.shadows, r.whites,
            r.blacks, r.temp, r.tint, r.vibrance, r.saturation)
    }

    private fun nightGrade(): PointwiseGrade {
        val r = ProRecipes.NightRecipe()
        return PointwiseGrade(r.exposure, r.contrast, r.highlights, r.shadows, r.whites,
            r.blacks, r.temp, r.tint, r.vibrance, r.saturation)
    }

    private fun backlitGrade(): PointwiseGrade {
        val r = ProRecipes.BacklitRecipe()
        return PointwiseGrade(r.exposure, r.contrast, r.highlights, r.shadows, r.whites,
            r.blacks, r.temp, r.tint, r.vibrance, 0f)
    }

    /**
     * EditState carrying ONLY the pointwise grade: everything else stays at
     * its default so the bake captures exactly the recipe's color transform.
     * Neighborhood ops (clarity/texture/dehaze) are deliberately zero here —
     * they can't live in a 3D LUT and are applied as live parameters instead.
     */
    private fun bakeState(g: PointwiseGrade): EditState = EditState(
        light = LightParams(
            exposure = g.exposure, contrast = g.contrast,
            highlights = g.highlights, shadows = g.shadows,
            whites = g.whites, blacks = g.blacks,
            temp = g.temp, tint = g.tint,
        ),
        color = ColorParams(vibrance = g.vibrance, saturation = g.saturation),
    )
}
