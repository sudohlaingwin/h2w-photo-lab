package com.lumora.photoeditor

import com.lumora.photoeditor.engine.EditState
import com.lumora.photoeditor.engine.ProRecipes
import org.junit.Assert.*
import org.junit.Test

/**
 * Executed verification for the Pro Recipes engine (pure Kotlin, zero
 * Android imports — runs on the standalone kotlinc harness; Gradle's test
 * workers are broken in this env).
 *
 * Locks the one-tap pro-recipe behavior the user judges on his phone:
 * exact delta application per scene, range clamping (a recipe must never
 * push a slider past its Lightroom range), input immutability (recipes are
 * additive deltas, never in-place edits), and custom-recipe overrides.
 */
class ProRecipesTest {

    private val base = EditState()

    @Test
    fun portrait_appliesDocumentedDeltas() {
        val out = ProRecipes.applyPortrait(base)
        assertEquals(0.25f, out.light.exposure, 1e-6f)
        assertEquals(12f, out.light.contrast, 1e-6f)
        assertEquals(-25f, out.light.highlights, 1e-6f)
        assertEquals(30f, out.light.shadows, 1e-6f)
        assertEquals(10f, out.light.whites, 1e-6f)
        assertEquals(-8f, out.light.blacks, 1e-6f)
        assertEquals(6f, out.light.temp, 1e-6f)
        assertEquals(3f, out.light.tint, 1e-6f)
        assertEquals(6f, out.light.clarity, 1e-6f)
        assertEquals(-8f, out.light.texture, 1e-6f)
        assertEquals(18f, out.color.vibrance, 1e-6f)
        assertEquals(-5f, out.color.saturation, 1e-6f)
        // vignette: 0 - 0.25 * 40
        assertEquals(-10f, out.optics.vignetteAmount, 1e-6f)
        // untouched channels stay at default
        assertEquals(0f, out.light.dehaze, 1e-6f)
        assertEquals(0f, out.optics.distortion, 1e-6f)
    }

    @Test
    fun landscape_appliesDocumentedDeltas() {
        val out = ProRecipes.applyLandscape(base)
        assertEquals(0.1f, out.light.exposure, 1e-6f)
        assertEquals(18f, out.light.contrast, 1e-6f)
        assertEquals(-35f, out.light.highlights, 1e-6f)
        assertEquals(25f, out.light.shadows, 1e-6f)
        assertEquals(15f, out.light.whites, 1e-6f)
        assertEquals(-12f, out.light.blacks, 1e-6f)
        assertEquals(-2f, out.light.temp, 1e-6f)
        assertEquals(0f, out.light.tint, 1e-6f)
        assertEquals(18f, out.light.clarity, 1e-6f)
        assertEquals(12f, out.light.texture, 1e-6f)
        assertEquals(20f, out.light.dehaze, 1e-6f)
        assertEquals(30f, out.color.vibrance, 1e-6f)
        assertEquals(8f, out.color.saturation, 1e-6f)
        // landscape recipe has no vignette step
        assertEquals(0f, out.optics.vignetteAmount, 1e-6f)
    }

    @Test
    fun night_preservesMoodWithDeepBlacks() {
        val out = ProRecipes.applyNight(base)
        assertEquals(0.35f, out.light.exposure, 1e-6f)
        assertEquals(-15f, out.light.blacks, 1e-6f)
        assertEquals(-5f, out.light.temp, 1e-6f) // cool cinematic
        assertEquals(4f, out.light.tint, 1e-6f)
        assertEquals(15f, out.color.vibrance, 1e-6f)
        assertEquals(-8f, out.color.saturation, 1e-6f)
        assertEquals(10f, out.light.clarity, 1e-6f)
        assertEquals(8f, out.light.dehaze, 1e-6f)
    }

    @Test
    fun backlit_rescuesSubjectWithoutTouchingSaturation() {
        val out = ProRecipes.applyBacklit(base)
        assertEquals(0.4f, out.light.exposure, 1e-6f)
        assertEquals(-55f, out.light.highlights, 1e-6f) // strong recovery
        assertEquals(55f, out.light.shadows, 1e-6f)     // strong lift
        assertEquals(22f, out.color.vibrance, 1e-6f)
        // BacklitRecipe carries no saturation field: must stay untouched
        assertEquals(0f, out.color.saturation, 1e-6f)
        assertEquals(10f, out.light.dehaze, 1e-6f)
        assertEquals(0f, out.light.texture, 1e-6f)
    }

    @Test
    fun recipes_clampToSliderRanges() {
        val extreme = base.copy(
            light = base.light.copy(exposure = 5f, contrast = 100f, highlights = -100f),
            color = base.color.copy(vibrance = 100f),
            optics = base.optics.copy(vignetteAmount = -100f),
        )
        val p = ProRecipes.applyPortrait(extreme)
        assertEquals(5f, p.light.exposure, 1e-6f)      // 5 + 0.25 clamped
        assertEquals(100f, p.light.contrast, 1e-6f)    // 100 + 12 clamped
        assertEquals(100f, p.color.vibrance, 1e-6f)    // 100 + 18 clamped
        assertEquals(-100f, p.optics.vignetteAmount, 1e-6f) // -100 - 10 clamped

        val b = ProRecipes.applyBacklit(extreme)
        assertEquals(-100f, b.light.highlights, 1e-6f) // -100 + -55 clamped
        assertEquals(5f, b.light.exposure, 1e-6f)      // 5 + 0.4 clamped
    }

    @Test
    fun recipes_doNotMutateInput() {
        val before = base.copy()
        ProRecipes.applyPortrait(base)
        ProRecipes.applyLandscape(base)
        ProRecipes.applyNight(base)
        ProRecipes.applyBacklit(base)
        assertEquals(before, base)
    }

    @Test
    fun customRecipe_overridesAreHonored() {
        val out = ProRecipes.applyPortrait(base, ProRecipes.PortraitRecipe(exposure = 1f, vignetteStrength = 0f))
        assertEquals(1f, out.light.exposure, 1e-6f)
        assertEquals(0f, out.optics.vignetteAmount, 1e-6f) // 0 - 0*40
        // other defaults still apply
        assertEquals(12f, out.light.contrast, 1e-6f)
    }

    @Test
    fun recipeDefaults_areWithinSliderRanges() {
        // A recipe's own deltas must never exceed what one application can add.
        val pr = ProRecipes.PortraitRecipe()
        assertTrue(pr.exposure in -5f..5f)
        listOf(pr.contrast, pr.highlights, pr.shadows, pr.whites, pr.blacks,
            pr.temp, pr.tint, pr.clarity, pr.texture, pr.vibrance, pr.saturation
        ).forEach { assertTrue("$it in -100..100", it in -100f..100f) }
        val lr = ProRecipes.LandscapeRecipe()
        assertTrue(lr.dehaze in -100f..100f)
        val nr = ProRecipes.NightRecipe()
        assertTrue(nr.exposure in -5f..5f)
        val br = ProRecipes.BacklitRecipe()
        assertTrue(br.shadows in -100f..100f)
    }
}
