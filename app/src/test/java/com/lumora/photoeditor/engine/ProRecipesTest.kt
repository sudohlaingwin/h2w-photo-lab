package com.lumora.photoeditor.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * First-ever executed verification of [ProRecipes] — the coordinated
 * multi-step pro editing workflows (Portrait / Landscape / Night /
 * Backlit) behind the "Pro Recipe" one-tap edits in the editor.
 *
 * Pins the observable contract:
 *  - recipe default sanity: mask strengths live in 0..1, deltas match the
 *    documented technique intent (portrait softens texture and stays warm,
 *    landscape dehazes, night keeps blacks deep and grade cool, backlit
 *    rescues highlights and lifts shadows hard);
 *  - each apply* adds its deltas onto the base EditState exactly;
 *  - out-of-range sums clamp to the documented slider ranges
 *    (exposure -5..5, everything else -100..100);
 *  - each recipe touches ONLY its documented fields (portrait leaves
 *    dehaze alone; night/backlit leave texture alone; backlit leaves
 *    saturation alone; only portrait touches the optics vignette);
 *  - base state is never mutated; applies are deterministic.
 *
 * Harness: hidden_files/prorecipes-harness/run.sh (kotlinc 1.9.23 +
 * JUnit 4.13.2 on the REAL engine/ProRecipes.kt + engine/EditParams.kt +
 * this REAL repo test; a signature-identical kotlinx.serialization
 * stub stands in for the annotation — Gradle's unit-test runner is
 * broken in this env).
 */
class ProRecipesTest {

    private val eps = 1e-6f

    private fun in01(v: Float, name: String) =
        assertTrue("$name=$v must be in 0..1", v in 0f..1f)

    // ------------------------------------------------------------------
    // Recipe default sanity — the technique intent in the KDoc.
    // ------------------------------------------------------------------

    @Test fun portraitMaskStrengthsInUnitRange() {
        val r = ProRecipes.PortraitRecipe()
        in01(r.skinSoftenStrength, "skinSoftenStrength")
        in01(r.eyeEnhanceStrength, "eyeEnhanceStrength")
        in01(r.teethWhitenStrength, "teethWhitenStrength")
        in01(r.vignetteStrength, "vignetteStrength")
    }

    @Test fun portraitTechniqueIntent() {
        val r = ProRecipes.PortraitRecipe()
        // Skin-friendly: soften micro-texture, keep clarity low, stay warm.
        assertTrue("texture=${r.texture} must be negative (soften)", r.texture < 0f)
        assertTrue("clarity=${r.clarity} must stay low for skin", r.clarity <= 10f)
        assertTrue("temp=${r.temp} must be warm, not orange", r.temp in 0f..12f)
        assertTrue("exposure=${r.exposure} must be a gentle lift", r.exposure in 0f..0.5f)
        assertTrue("highlights=${r.highlights} must tame, not clip", r.highlights <= 0f)
        assertTrue("shadows=${r.shadows} must open gently", r.shadows >= 0f)
    }

    @Test fun landscapeMaskStrengthsAndIntent() {
        val r = ProRecipes.LandscapeRecipe()
        in01(r.skyBoostStrength, "skyBoostStrength")
        in01(r.foregroundWarmth, "foregroundWarmth")
        assertTrue("dehaze=${r.dehaze} must be positive", r.dehaze > 0f)
        assertTrue("clarity=${r.clarity} must add bite", r.clarity > 10f)
        assertTrue("vibrance=${r.vibrance} must be strong", r.vibrance > 20f)
    }

    @Test fun nightTechniqueIntent() {
        val r = ProRecipes.NightRecipe()
        assertTrue("blacks=${r.blacks} must stay deep for mood", r.blacks < 0f)
        assertTrue("temp=${r.temp} must stay cool/cinematic", r.temp <= 0f)
        assertTrue("exposure=${r.exposure} must lift gently", r.exposure in 0f..0.5f)
    }

    @Test fun backlitTechniqueIntent() {
        val r = ProRecipes.BacklitRecipe()
        assertTrue("highlights=${r.highlights} must rescue hard", r.highlights <= -40f)
        assertTrue("shadows=${r.shadows} must lift hard", r.shadows >= 40f)
        assertTrue("temp=${r.temp} must warm the subject", r.temp > 0f)
    }

    // ------------------------------------------------------------------
    // applyPortrait — exact deltas, vignette math, dehaze untouched.
    // ------------------------------------------------------------------

    @Test fun applyPortraitAddsDeltasExactlyFromZeroBase() {
        val r = ProRecipes.PortraitRecipe()
        val out = ProRecipes.applyPortrait(EditState(), r)
        assertEquals(r.exposure, out.light.exposure, eps)
        assertEquals(r.contrast, out.light.contrast, eps)
        assertEquals(r.highlights, out.light.highlights, eps)
        assertEquals(r.shadows, out.light.shadows, eps)
        assertEquals(r.whites, out.light.whites, eps)
        assertEquals(r.blacks, out.light.blacks, eps)
        assertEquals(r.temp, out.light.temp, eps)
        assertEquals(r.tint, out.light.tint, eps)
        assertEquals(r.clarity, out.light.clarity, eps)
        assertEquals(r.texture, out.light.texture, eps)
        assertEquals(r.vibrance, out.color.vibrance, eps)
        assertEquals(r.saturation, out.color.saturation, eps)
        // Vignette: strength * 40 darkening.
        assertEquals(-r.vignetteStrength * 40f, out.optics.vignetteAmount, eps)
    }

    @Test fun applyPortraitLeavesDehazeAlone() {
        // PortraitRecipe has no dehaze field; a base dehaze must survive.
        val base = EditState(light = LightParams(dehaze = 33f))
        val out = ProRecipes.applyPortrait(base)
        assertEquals(33f, out.light.dehaze, eps)
    }

    @Test fun applyPortraitClampsToSliderRanges() {
        val base = EditState(light = LightParams(exposure = 5f, contrast = 95f))
        val out = ProRecipes.applyPortrait(base)
        assertEquals(5f, out.light.exposure, eps)      // -5..5
        assertEquals(100f, out.light.contrast, eps)    // -100..100
    }

    // ------------------------------------------------------------------
    // applyLandscape — dehaze applied, optics untouched.
    // ------------------------------------------------------------------

    @Test fun applyLandscapeAddsDeltasExactlyFromZeroBase() {
        val r = ProRecipes.LandscapeRecipe()
        val out = ProRecipes.applyLandscape(EditState(), r)
        assertEquals(r.exposure, out.light.exposure, eps)
        assertEquals(r.contrast, out.light.contrast, eps)
        assertEquals(r.highlights, out.light.highlights, eps)
        assertEquals(r.shadows, out.light.shadows, eps)
        assertEquals(r.whites, out.light.whites, eps)
        assertEquals(r.blacks, out.light.blacks, eps)
        assertEquals(r.temp, out.light.temp, eps)
        assertEquals(r.tint, out.light.tint, eps)
        assertEquals(r.clarity, out.light.clarity, eps)
        assertEquals(r.texture, out.light.texture, eps)
        assertEquals(r.dehaze, out.light.dehaze, eps)
        assertEquals(r.vibrance, out.color.vibrance, eps)
        assertEquals(r.saturation, out.color.saturation, eps)
        assertEquals(0f, out.optics.vignetteAmount, eps) // no optics touch
    }

    @Test fun applyLandscapeClampsExposureAndContrast() {
        // exposure: 4.95 + 0.1 = 5.05 -> clamped to 5; contrast: 90 + 18 -> 100.
        val base = EditState(light = LightParams(exposure = 4.95f, contrast = 90f))
        val out = ProRecipes.applyLandscape(base)
        assertEquals(5f, out.light.exposure, eps)
        assertEquals(100f, out.light.contrast, eps)
    }

    // ------------------------------------------------------------------
    // applyNight — texture untouched, dehaze applied.
    // ------------------------------------------------------------------

    @Test fun applyNightAddsDeltasExactlyFromZeroBase() {
        val r = ProRecipes.NightRecipe()
        val out = ProRecipes.applyNight(EditState(), r)
        assertEquals(r.exposure, out.light.exposure, eps)
        assertEquals(r.contrast, out.light.contrast, eps)
        assertEquals(r.highlights, out.light.highlights, eps)
        assertEquals(r.shadows, out.light.shadows, eps)
        assertEquals(r.whites, out.light.whites, eps)
        assertEquals(r.blacks, out.light.blacks, eps)
        assertEquals(r.temp, out.light.temp, eps)
        assertEquals(r.tint, out.light.tint, eps)
        assertEquals(r.clarity, out.light.clarity, eps)
        assertEquals(r.dehaze, out.light.dehaze, eps)
        assertEquals(r.vibrance, out.color.vibrance, eps)
        assertEquals(r.saturation, out.color.saturation, eps)
    }

    @Test fun applyNightLeavesTextureAlone() {
        // NightRecipe has no texture field; a base texture must survive.
        val base = EditState(light = LightParams(texture = 7f))
        val out = ProRecipes.applyNight(base)
        assertEquals(7f, out.light.texture, eps)
    }

    // ------------------------------------------------------------------
    // applyBacklit — saturation untouched, strong rescue sums.
    // ------------------------------------------------------------------

    @Test fun applyBacklitAddsDeltasExactlyFromZeroBase() {
        val r = ProRecipes.BacklitRecipe()
        val out = ProRecipes.applyBacklit(EditState(), r)
        assertEquals(r.exposure, out.light.exposure, eps)
        assertEquals(r.contrast, out.light.contrast, eps)
        assertEquals(r.highlights, out.light.highlights, eps)
        assertEquals(r.shadows, out.light.shadows, eps)
        assertEquals(r.whites, out.light.whites, eps)
        assertEquals(r.blacks, out.light.blacks, eps)
        assertEquals(r.temp, out.light.temp, eps)
        assertEquals(r.tint, out.light.tint, eps)
        assertEquals(r.clarity, out.light.clarity, eps)
        assertEquals(r.dehaze, out.light.dehaze, eps)
        assertEquals(r.vibrance, out.color.vibrance, eps)
        assertEquals(0f, out.color.saturation, eps) // saturation not touched
    }

    @Test fun applyBacklitRescuesWithoutClampingInNormalRange() {
        // -30 + -55 = -85 stays inside -100..100: no silent clamp.
        val base = EditState(light = LightParams(highlights = -30f, shadows = 20f))
        val out = ProRecipes.applyBacklit(base)
        assertEquals(-85f, out.light.highlights, eps)
        assertEquals(75f, out.light.shadows, eps)
    }

    @Test fun applyBacklitLeavesTextureAlone() {
        val base = EditState(light = LightParams(texture = -12f))
        val out = ProRecipes.applyBacklit(base)
        assertEquals(-12f, out.light.texture, eps)
    }

    // ------------------------------------------------------------------
    // Structural guarantees.
    // ------------------------------------------------------------------

    @Test fun appliesDoNotMutateTheBaseState() {
        val base = EditState()
        val baseCopy = base.copy()
        ProRecipes.applyPortrait(base)
        ProRecipes.applyLandscape(base)
        ProRecipes.applyNight(base)
        ProRecipes.applyBacklit(base)
        assertEquals(baseCopy, base)
    }

    @Test fun appliesAreDeterministic() {
        val base = EditState(light = LightParams(exposure = 0.3f))
        assertEquals(ProRecipes.applyPortrait(base), ProRecipes.applyPortrait(base))
        assertEquals(ProRecipes.applyLandscape(base), ProRecipes.applyLandscape(base))
        assertEquals(ProRecipes.applyNight(base), ProRecipes.applyNight(base))
        assertEquals(ProRecipes.applyBacklit(base), ProRecipes.applyBacklit(base))
    }

    @Test fun customRecipeOverridesDefaults() {
        val out = ProRecipes.applyPortrait(EditState(), ProRecipes.PortraitRecipe(exposure = 1f))
        assertEquals(1f, out.light.exposure, eps)
        // Other fields still use recipe defaults.
        assertEquals(ProRecipes.PortraitRecipe().contrast, out.light.contrast, eps)
    }

    @Test fun stackedAppliesClampRatherThanExplode() {
        // Two stacked night applies must clamp, never exceed slider range.
        val once = ProRecipes.applyNight(EditState())
        val twice = ProRecipes.applyNight(once)
        assertTrue(twice.light.exposure <= 5f)
        assertTrue(twice.light.contrast <= 100f)
        assertTrue(twice.light.dehaze <= 100f)
    }
}
