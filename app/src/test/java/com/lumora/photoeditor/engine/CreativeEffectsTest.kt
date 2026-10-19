package com.lumora.photoeditor.engine

import android.graphics.Bitmap
import org.junit.Assert.*
import org.junit.Test

/**
 * First-ever executed verification of [CreativeEffects] (engine/AutoMagic.kt):
 * the deterministic light-leak + film-dust overlay generators behind the
 * Optics panel's creative effects. Pure pixel math — no ML, no hidden state,
 * randomness only via the caller's seed — so the whole contract is pinnable:
 *  - [CreativeEffects.fxOverlay] gating: inactive params return null (so the
 *    GL layer binds its dummy texture); leak-only output keeps alpha at zero
 *    (the shader reads leak from rgb and dust from alpha, so the leak side
 *    must not share the channel);
 *  - light leak: brightest at the preset's UV corner, tint channels follow
 *    the preset's rgb weights, intensity scales the glow linearly;
 *  - film dust: fully reproducible for a fixed seed, pattern changes with
 *    the seed, speck count grows with density, background stays transparent.
 *
 * Note: Gradle's unit-test runner is broken in this environment (the executor
 * dies before running any test), so this is executed via the standalone
 * kotlinc harness at hidden_files/fx-harness/run.sh on the REAL sources.
 */
class CreativeEffectsTest {

    private fun pixels(bmp: Bitmap): IntArray {
        val out = IntArray(bmp.width * bmp.height)
        bmp.getPixels(out, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return out
    }

    private fun alpha(p: Int) = (p ushr 24) and 0xFF
    private fun red(p: Int) = (p shr 16) and 0xFF
    private fun green(p: Int) = (p shr 8) and 0xFF
    private fun blue(p: Int) = p and 0xFF
    private fun brightness(p: Int) = red(p) + green(p) + blue(p)

    /** Max brightness over the 5x5 block centered at (cx, cy). */
    private fun blockMax(px: IntArray, w: Int, cx: Int, cy: Int): Int {
        var m = 0
        for (dy in -2..2) for (dx in -2..2) {
            m = maxOf(m, brightness(px[(cy + dy) * w + (cx + dx)]))
        }
        return m
    }

    // ------------------------------------------------------------------
    // fxOverlay gating
    // ------------------------------------------------------------------

    @Test fun fxOverlay_defaultParams_returnsNull() {
        assertNull(CreativeEffects.fxOverlay(64, 64, FxParams()))
    }

    @Test fun fxOverlay_leakNoneWithAmount_returnsNull() {
        assertNull(CreativeEffects.fxOverlay(64, 64, FxParams(leak = LightLeakStyle.NONE, leakAmount = 50f)))
    }

    @Test fun fxOverlay_leakZeroAmount_returnsNull() {
        assertNull(
            CreativeEffects.fxOverlay(
                64, 64,
                FxParams(leak = LightLeakStyle.SUNSET, leakAmount = 0f, dust = 0f),
            )
        )
    }

    @Test fun fxOverlay_negativeAmountCoercedToInactive_returnsNull() {
        // coerced() clamps leakAmount to 0..100 before isActive() is checked.
        assertNull(
            CreativeEffects.fxOverlay(
                64, 64,
                FxParams(leak = LightLeakStyle.SUNSET, leakAmount = -20f, dust = 0f),
            )
        )
    }

    // ------------------------------------------------------------------
    // fxOverlay: leak-only (SUNSET, amount 50, dust 0)
    // ------------------------------------------------------------------

    private fun sunsetOverlay(): Bitmap =
        CreativeEffects.fxOverlay(
            64, 64,
            FxParams(leak = LightLeakStyle.SUNSET, leakAmount = 50f, dust = 0f),
        ) ?: error("expected non-null overlay for active leak")

    @Test fun fxOverlay_leakOnly_returnsRequestedSize() {
        val bmp = sunsetOverlay()
        assertEquals(64, bmp.width)
        assertEquals(64, bmp.height)
    }

    @Test fun fxOverlay_leakOnly_alphaChannelStaysZero() {
        // Contract: the shader reads leak from rgb and dust from alpha, so a
        // leak-only overlay must not write the alpha (dust) channel at all.
        val px = pixels(sunsetOverlay())
        assertTrue("leak must not touch the alpha channel", px.all { alpha(it) == 0 })
    }

    @Test fun fxOverlay_leakOnly_glowPresent() {
        val px = pixels(sunsetOverlay())
        assertTrue("leak overlay must carry rgb glow", px.any { brightness(it) > 0 })
    }

    @Test fun fxOverlay_leakOnly_brightestAtPresetCorner() {
        // SUNSET sits at UV (0.85, 0.15) -> pixel ~(54, 10) on 64x64.
        val px = pixels(sunsetOverlay())
        val corner = blockMax(px, 64, 54, 10)
        val center = brightness(px[32 * 64 + 32])
        assertTrue("corner $corner must beat center $center", corner > center * 2)
    }

    @Test fun fxOverlay_leakOnly_sunsetTintProportions() {
        // SUNSET weights are r=1.0, g=0.45, b=0.15 -> r > g > b at the corner.
        val px = pixels(sunsetOverlay())
        val p = px[10 * 64 + 54]
        assertTrue("r=${red(p)} must exceed g=${green(p)}", red(p) > green(p))
        assertTrue("g=${green(p)} must exceed b=${blue(p)}", green(p) > blue(p))
    }

    @Test fun fxOverlay_leakOnly_tealTintBlueDominant() {
        // TEAL weights are r=0.25, g=0.85, b=0.9 at UV (0.85, 0.85) -> ~(54, 54).
        val bmp = CreativeEffects.fxOverlay(
            64, 64,
            FxParams(leak = LightLeakStyle.TEAL, leakAmount = 50f, dust = 0f),
        ) ?: error("expected non-null overlay for active leak")
        val p = pixels(bmp)[54 * 64 + 54]
        assertTrue("b=${blue(p)} must exceed r=${red(p)}", blue(p) > red(p))
    }

    // ------------------------------------------------------------------
    // fxOverlay: dust-only (dust 50, seed 7)
    // ------------------------------------------------------------------

    private fun dustOverlay(seed: Long = 7L): Bitmap =
        CreativeEffects.fxOverlay(
            64, 64,
            FxParams(leak = LightLeakStyle.NONE, dust = 50f, dustSeed = seed),
        ) ?: error("expected non-null overlay for active dust")

    @Test fun fxOverlay_dustOnly_deterministicForFixedSeed() {
        assertArrayEquals(pixels(dustOverlay(7L)), pixels(dustOverlay(7L)))
    }

    @Test fun fxOverlay_dustOnly_seedChangesPattern() {
        assertFalse(
            "different seeds must give different dust",
            pixels(dustOverlay(7L)).contentEquals(pixels(dustOverlay(8L))),
        )
    }

    @Test fun fxOverlay_dustOnly_specksCarryAlpha() {
        val px = pixels(dustOverlay())
        assertTrue("dust specks must write the alpha channel", px.any { alpha(it) > 0 })
    }

    @Test fun fxOverlay_dustOnly_backgroundStaysTransparent() {
        val px = pixels(dustOverlay())
        val transparent = px.count { it == 0 }
        assertTrue(
            "most of the overlay must stay transparent black, was $transparent/${px.size}",
            transparent > px.size / 2,
        )
    }

    // ------------------------------------------------------------------
    // filmDustOverlay
    // ------------------------------------------------------------------

    @Test fun filmDustOverlay_densityScalesSpeckCount() {
        val sparse = pixels(CreativeEffects.filmDustOverlay(128, 128, 7L, 0.05f))
        val dense = pixels(CreativeEffects.filmDustOverlay(128, 128, 7L, 0.5f))
        val sparseN = sparse.count { it != 0 }
        val denseN = dense.count { it != 0 }
        assertTrue("dense ($denseN) must beat sparse ($sparseN)", denseN > sparseN)
    }

    @Test fun filmDustOverlay_deterministic() {
        assertArrayEquals(
            pixels(CreativeEffects.filmDustOverlay(64, 64, 42L, 0.5f)),
            pixels(CreativeEffects.filmDustOverlay(64, 64, 42L, 0.5f)),
        )
    }

    // ------------------------------------------------------------------
    // lightLeakOverlay
    // ------------------------------------------------------------------

    @Test fun lightLeakOverlay_opaque_fullAlphaEverywhere() {
        val px = pixels(CreativeEffects.lightLeakOverlay(64, 64, CreativeEffects.LightLeak.SUNSET, 1f))
        assertTrue("standalone leak overlay must be fully opaque", px.all { alpha(it) == 255 })
    }

    @Test fun lightLeakOverlay_cornerBrighterThanCenter() {
        val px = pixels(CreativeEffects.lightLeakOverlay(64, 64, CreativeEffects.LightLeak.SUNSET, 1f))
        val corner = blockMax(px, 64, 54, 10)
        val center = brightness(px[32 * 64 + 32])
        assertTrue("corner $corner must beat center $center", corner > center * 2)
    }

    @Test fun lightLeakOverlay_intensityScalesLinearly() {
        // Glow is linear in intensity (before int truncation); use the green
        // channel (unclipped at the corner) for a clean ratio.
        val full = pixels(CreativeEffects.lightLeakOverlay(64, 64, CreativeEffects.LightLeak.SUNSET, 1f))
        val half = pixels(CreativeEffects.lightLeakOverlay(64, 64, CreativeEffects.LightLeak.SUNSET, 0.5f))
        val gFull = green(full[10 * 64 + 54]).toDouble()
        val gHalf = green(half[10 * 64 + 54]).toDouble()
        val ratio = gHalf / gFull
        assertTrue("half-intensity ratio $ratio must be ~0.5", ratio in 0.45..0.55)
    }

    @Test fun lightLeakOverlay_zeroIntensity_noGlow() {
        val px = pixels(CreativeEffects.lightLeakOverlay(64, 64, CreativeEffects.LightLeak.SUNSET, 0f))
        assertTrue("zero intensity must leave rgb black", px.all { brightness(it) == 0 })
    }

    // ------------------------------------------------------------------
    // Combined leak + dust
    // ------------------------------------------------------------------

    @Test fun fxOverlay_leakAndDust_bothChannelsPresent() {
        val bmp = CreativeEffects.fxOverlay(
            64, 64,
            FxParams(leak = LightLeakStyle.SUNSET, leakAmount = 50f, dust = 50f, dustSeed = 7L),
        ) ?: error("expected non-null overlay for leak + dust")
        val px = pixels(bmp)
        assertTrue("leak rgb must be present", px.any { brightness(it) > 0 })
        assertTrue("dust alpha must be present", px.any { alpha(it) > 0 })
    }
}
