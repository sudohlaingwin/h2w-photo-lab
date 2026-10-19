package com.lumora.photoeditor.engine

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/**
 * First-ever executed verification of [SmartMask] (engine/AutoMagic.kt):
 * the adaptive region-fix recipes that turn a measured [SmartMask.RegionStats]
 * (brightness/saturation/contrast inside a mask) into corrective [LocalAdjust]
 * values — the way an experienced editor would fix that region on first sight.
 *
 * Pinned here:
 *  - [SmartMask.skyFix]: the three brightness recovery tiers and their exact
 *    boundaries (0.78 / 0.65 / 0.55), the dull-sky vs flat-sky saturation
 *    branches, and the documented conservative exposure cap (|exposure| <= 0.4);
 *  - [SmartMask.skinFix]: the harsh-contrast shadow lift (0.55 boundary),
 *    the dark-skin exposure lift (0.38 boundary), and that clarity/sharpness
 *    are pure passthrough of the [RetouchIntel.RetouchRecipe];
 *  - [SmartMask.backgroundFix]: the subject/background brightness-gap tiers
 *    and that the clarity softening applies even when no darkening is needed;
 *  - [SmartMask.subjectFix]: the dark-subject shadow/exposure tiers, the
 *    flat-subject clarity bump (0.25 boundary), and the documented +0.1
 *    exposure cap ("must not re-light" the masked subject);
 *  - the degenerate RegionStats(0.5, 0.3, 0.4) that [SmartMask.analyzeRegion]
 *    returns for tiny/empty masks is a near-no-op for every fix.
 *
 * Note: the gap in backgroundFix is a float subtraction, so the exact
 * 0.18/0.10 literal boundaries are float-fragile and are pinned with margins
 * instead (0.19/0.11/0.09). Direct literal comparisons (skyFix/skinFix/
 * subjectFix) are pinned exactly.
 *
 * Note: Gradle's unit-test runner is broken in this environment (the executor
 * dies before running any test), so this is executed via the standalone
 * kotlinc harness at hidden_files/smartmask-harness/run.sh on the REAL sources.
 */
class SmartMaskTest {

    private val recipe = RetouchIntel.RetouchRecipe(
        blemishThresholdScale = 1.0f,
        skinClarity = -20f,
        skinSharpness = -15f,
        healStrength = 0.8f,
        underEyeStrength = 0.5f,
        maxBlemishes = 30,
    )

    // ---- skyFix ----

    @Test
    fun skyFixBlownSkyPullsExposureAndHighlights() {
        val fix = SmartMask.skyFix(SmartMask.RegionStats(0.85f, 0.50f, 0.50f))
        assertEquals(-0.35f, fix.exposure, 1e-6f)
        assertEquals(-45f, fix.highlights, 1e-6f)
        assertEquals(0f, fix.saturation, 1e-6f)
        assertEquals(0f, fix.dehaze, 1e-6f)
        assertEquals(0f, fix.temp, 1e-6f)
    }

    @Test
    fun skyFixBrightSkySecondTier() {
        val fix = SmartMask.skyFix(SmartMask.RegionStats(0.70f, 0.50f, 0.50f))
        assertEquals(-0.18f, fix.exposure, 1e-6f)
        assertEquals(-28f, fix.highlights, 1e-6f)
    }

    @Test
    fun skyFixMildBrightSkyHighlightsOnly() {
        val fix = SmartMask.skyFix(SmartMask.RegionStats(0.60f, 0.50f, 0.50f))
        assertEquals(0f, fix.exposure, 1e-6f)
        assertEquals(-15f, fix.highlights, 1e-6f)
    }

    @Test
    fun skyFixBrightnessBoundary078TakesSecondTier() {
        // 0.78 is NOT > 0.78, so the top tier must not fire.
        val fix = SmartMask.skyFix(SmartMask.RegionStats(0.78f, 0.50f, 0.50f))
        assertEquals(-0.18f, fix.exposure, 1e-6f)
        assertEquals(-28f, fix.highlights, 1e-6f)
    }

    @Test
    fun skyFixBrightnessBoundary065TakesThirdTier() {
        val fix = SmartMask.skyFix(SmartMask.RegionStats(0.65f, 0.50f, 0.50f))
        assertEquals(0f, fix.exposure, 1e-6f)
        assertEquals(-15f, fix.highlights, 1e-6f)
    }

    @Test
    fun skyFixBrightnessBoundary055NoRecovery() {
        val fix = SmartMask.skyFix(SmartMask.RegionStats(0.55f, 0.50f, 0.50f))
        assertEquals(0f, fix.exposure, 1e-6f)
        assertEquals(0f, fix.highlights, 1e-6f)
    }

    @Test
    fun skyFixDullSkyAddsDepthAndCoolPush() {
        val fix = SmartMask.skyFix(SmartMask.RegionStats(0.50f, 0.10f, 0.50f))
        assertEquals(18f, fix.saturation, 1e-6f)
        assertEquals(14f, fix.dehaze, 1e-6f)
        assertEquals(-6f, fix.temp, 1e-6f)
        assertEquals(0f, fix.exposure, 1e-6f)
        assertEquals(0f, fix.highlights, 1e-6f)
    }

    @Test
    fun skyFixFlatSkyGetsDehazeWithoutCoolPush() {
        val fix = SmartMask.skyFix(SmartMask.RegionStats(0.50f, 0.40f, 0.20f))
        assertEquals(10f, fix.dehaze, 1e-6f)
        assertEquals(8f, fix.saturation, 1e-6f)
        assertEquals(0f, fix.temp, 1e-6f)
    }

    @Test
    fun skyFixSaturationBoundary022TakesContrastBranch() {
        val fix = SmartMask.skyFix(SmartMask.RegionStats(0.50f, 0.22f, 0.20f))
        assertEquals(10f, fix.dehaze, 1e-6f)
        assertEquals(8f, fix.saturation, 1e-6f)
        assertEquals(0f, fix.temp, 1e-6f)
    }

    @Test
    fun skyFixNormalSkyIsIdentity() {
        assertEquals(LocalAdjust(), SmartMask.skyFix(SmartMask.RegionStats(0.50f, 0.40f, 0.50f)))
    }

    @Test
    fun skyFixBlownAndDullCombinesBothBranches() {
        val fix = SmartMask.skyFix(SmartMask.RegionStats(0.85f, 0.10f, 0.50f))
        assertEquals(-0.35f, fix.exposure, 1e-6f)
        assertEquals(-45f, fix.highlights, 1e-6f)
        assertEquals(18f, fix.saturation, 1e-6f)
        assertEquals(14f, fix.dehaze, 1e-6f)
        assertEquals(-6f, fix.temp, 1e-6f)
    }

    @Test
    fun skyFixExposureNeverExceedsConservativeCap() {
        for (i in 0..20) {
            val fix = SmartMask.skyFix(SmartMask.RegionStats(i / 20f, 0.50f, 0.50f))
            assertTrue("brightness=${i / 20f} exposure=${fix.exposure}",
                abs(fix.exposure) <= 0.4f)
        }
    }

    // ---- skinFix ----

    @Test
    fun skinFixHarshContrastLiftsShadows() {
        val fix = SmartMask.skinFix(SmartMask.RegionStats(0.50f, 0.30f, 0.60f), recipe)
        assertEquals(18f, fix.shadows, 1e-6f)
        assertEquals(0f, fix.exposure, 1e-6f)
        assertEquals(-20f, fix.clarity, 1e-6f)
        assertEquals(-15f, fix.sharpness, 1e-6f)
    }

    @Test
    fun skinFixContrastBoundary055NoLift() {
        val fix = SmartMask.skinFix(SmartMask.RegionStats(0.50f, 0.30f, 0.55f), recipe)
        assertEquals(0f, fix.shadows, 1e-6f)
    }

    @Test
    fun skinFixDarkSkinGetsGentleLift() {
        val fix = SmartMask.skinFix(SmartMask.RegionStats(0.30f, 0.30f, 0.40f), recipe)
        assertEquals(0.12f, fix.exposure, 1e-6f)
        assertEquals(0f, fix.shadows, 1e-6f)
    }

    @Test
    fun skinFixBrightnessBoundary038NoLift() {
        val fix = SmartMask.skinFix(SmartMask.RegionStats(0.38f, 0.30f, 0.40f), recipe)
        assertEquals(0f, fix.exposure, 1e-6f)
    }

    @Test
    fun skinFixHarshAndDarkCombines() {
        val fix = SmartMask.skinFix(SmartMask.RegionStats(0.30f, 0.30f, 0.60f), recipe)
        assertEquals(18f, fix.shadows, 1e-6f)
        assertEquals(0.12f, fix.exposure, 1e-6f)
    }

    @Test
    fun skinFixPassesRecipeClarityThrough() {
        val mild = recipe.copy(skinClarity = -8f, skinSharpness = -4f)
        val fix = SmartMask.skinFix(SmartMask.RegionStats(0.50f, 0.30f, 0.40f), mild)
        assertEquals(-8f, fix.clarity, 1e-6f)
        assertEquals(-4f, fix.sharpness, 1e-6f)
    }

    @Test
    fun skinFixLeavesUnrelatedFieldsAlone() {
        val fix = SmartMask.skinFix(SmartMask.RegionStats(0.50f, 0.30f, 0.40f), recipe)
        assertEquals(0f, fix.contrast, 1e-6f)
        assertEquals(0f, fix.highlights, 1e-6f)
        assertEquals(0f, fix.temp, 1e-6f)
        assertEquals(0f, fix.tint, 1e-6f)
        assertEquals(0f, fix.saturation, 1e-6f)
        assertEquals(0f, fix.dehaze, 1e-6f)
    }

    // ---- backgroundFix ----

    @Test
    fun backgroundFixMuchBrighterBackgroundDarkened() {
        val fix = SmartMask.backgroundFix(SmartMask.RegionStats(0.80f, 0.30f, 0.40f), 0.50f)
        assertEquals(-0.30f, fix.exposure, 1e-6f)
        assertEquals(-25f, fix.highlights, 1e-6f)
        assertEquals(-12f, fix.clarity, 1e-6f)
    }

    @Test
    fun backgroundFixSomewhatBrighterBackgroundSecondTier() {
        val fix = SmartMask.backgroundFix(SmartMask.RegionStats(0.65f, 0.30f, 0.40f), 0.50f)
        assertEquals(-0.15f, fix.exposure, 1e-6f)
        assertEquals(-12f, fix.highlights, 1e-6f)
    }

    @Test
    fun backgroundFixEvenBrightnessOnlySoftens() {
        val fix = SmartMask.backgroundFix(SmartMask.RegionStats(0.55f, 0.30f, 0.40f), 0.50f)
        assertEquals(0f, fix.exposure, 1e-6f)
        assertEquals(0f, fix.highlights, 1e-6f)
        assertEquals(-12f, fix.clarity, 1e-6f)
    }

    @Test
    fun backgroundFixDarkerBackgroundOnlySoftens() {
        val fix = SmartMask.backgroundFix(SmartMask.RegionStats(0.40f, 0.30f, 0.40f), 0.60f)
        assertEquals(0f, fix.exposure, 1e-6f)
        assertEquals(0f, fix.highlights, 1e-6f)
        assertEquals(-12f, fix.clarity, 1e-6f)
    }

    @Test
    fun backgroundFixGapTiers() {
        // gap = bg - subject; exact 0.18/0.10 boundaries are float-subtraction
        // fragile, so tiers are pinned with margins.
        assertEquals(-0.30f,
            SmartMask.backgroundFix(SmartMask.RegionStats(0.69f, 0.3f, 0.4f), 0.50f).exposure, 1e-6f)
        assertEquals(-0.15f,
            SmartMask.backgroundFix(SmartMask.RegionStats(0.61f, 0.3f, 0.4f), 0.50f).exposure, 1e-6f)
        assertEquals(0f,
            SmartMask.backgroundFix(SmartMask.RegionStats(0.59f, 0.3f, 0.4f), 0.50f).exposure, 1e-6f)
    }

    // ---- subjectFix ----

    @Test
    fun subjectFixDarkSubjectLifted() {
        val fix = SmartMask.subjectFix(SmartMask.RegionStats(0.30f, 0.30f, 0.50f))
        assertEquals(10f, fix.clarity, 1e-6f)
        assertEquals(15f, fix.shadows, 1e-6f)
        assertEquals(0.10f, fix.exposure, 1e-6f)
    }

    @Test
    fun subjectFixMidDarkSubjectShadowLiftOnly() {
        val fix = SmartMask.subjectFix(SmartMask.RegionStats(0.40f, 0.30f, 0.50f))
        assertEquals(10f, fix.clarity, 1e-6f)
        assertEquals(10f, fix.shadows, 1e-6f)
        assertEquals(0f, fix.exposure, 1e-6f)
    }

    @Test
    fun subjectFixBrightnessBoundary035TakesSecondTier() {
        val fix = SmartMask.subjectFix(SmartMask.RegionStats(0.35f, 0.30f, 0.50f))
        assertEquals(10f, fix.shadows, 1e-6f)
        assertEquals(0f, fix.exposure, 1e-6f)
    }

    @Test
    fun subjectFixBrightnessBoundary045NoLift() {
        val fix = SmartMask.subjectFix(SmartMask.RegionStats(0.45f, 0.30f, 0.50f))
        assertEquals(0f, fix.shadows, 1e-6f)
        assertEquals(0f, fix.exposure, 1e-6f)
    }

    @Test
    fun subjectFixNormalSubjectGetsBaseClarity() {
        val fix = SmartMask.subjectFix(SmartMask.RegionStats(0.60f, 0.30f, 0.50f))
        assertEquals(10f, fix.clarity, 1e-6f)
        assertEquals(0f, fix.shadows, 1e-6f)
        assertEquals(0f, fix.exposure, 1e-6f)
    }

    @Test
    fun subjectFixFlatSubjectGetsExtraClarity() {
        val fix = SmartMask.subjectFix(SmartMask.RegionStats(0.60f, 0.30f, 0.20f))
        assertEquals(16f, fix.clarity, 1e-6f)
    }

    @Test
    fun subjectFixContrastBoundary025KeepsBaseClarity() {
        val fix = SmartMask.subjectFix(SmartMask.RegionStats(0.60f, 0.30f, 0.25f))
        assertEquals(10f, fix.clarity, 1e-6f)
    }

    @Test
    fun subjectFixExposureNeverExceedsCap() {
        // Documented: "Never more than +0.1 exposure ... must not re-light it."
        for (i in 0..20) {
            val fix = SmartMask.subjectFix(SmartMask.RegionStats(i / 20f, 0.30f, 0.50f))
            assertTrue("brightness=${i / 20f} exposure=${fix.exposure}",
                fix.exposure <= 0.10f + 1e-6f)
        }
    }

    // ---- degenerate stats ----

    @Test
    fun degenerateStatsAreNearNoOp() {
        // analyzeRegion's tiny/empty-mask fallback must not trigger corrections.
        val neutral = SmartMask.RegionStats(0.5f, 0.3f, 0.4f)
        assertEquals(LocalAdjust(), SmartMask.skyFix(neutral))
        assertEquals(LocalAdjust(clarity = 10f), SmartMask.subjectFix(neutral))
        assertEquals(LocalAdjust(clarity = -12f),
            SmartMask.backgroundFix(neutral, 0.5f))
        val skin = SmartMask.skinFix(neutral, recipe)
        assertEquals(0f, skin.shadows, 1e-6f)
        assertEquals(0f, skin.exposure, 1e-6f)
        assertEquals(-20f, skin.clarity, 1e-6f)
        assertEquals(-15f, skin.sharpness, 1e-6f)
    }
}
