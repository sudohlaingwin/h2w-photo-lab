package com.lumora.photoeditor.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Executed verification of [PromptEdit] — the pure-Kotlin offline keyword
 * interpreter behind pro-spec Phase 1 text-prompt editing ("dramatic sky",
 * "warm sunset", "remove blemishes").
 *
 * Pins the observable contract: the honest-null rule (unrecognized prompt
 * -> null, caller shows helpText), portrait-intent keyword blanking (the
 * portrait verb must not leak into slider effects), region detection incl.
 * the luminance-band regions, effect deltas and their local/global unit
 * mapping (EV vs /100), fold accumulation, and the human-readable summary
 * labels. No Android, no network, no LLM — deterministic string rules.
 *
 * Harness: hidden_files/promptedit-test-harness/run.sh (kotlinc 1.9.23 +
 * JUnit 4.13.2 on the REAL source + this REAL repo test — Gradle's
 * unit-test runner is broken in this env). EditParams.kt joins the compile
 * with a 5-line @Serializable stub (no serialization codegen needed).
 */
class PromptEditTest {

    private fun plan(text: String): PromptPlan =
        PromptEdit.interpret(text) ?: throw AssertionError("expected a plan for \"$text\"")

    // ---- honest-null contract -------------------------------------------------

    @Test fun blankPromptIsNull() {
        assertNull(PromptEdit.interpret(""))
        assertNull(PromptEdit.interpret("   "))
    }

    @Test fun gibberishIsNull() {
        assertNull(PromptEdit.interpret("xyzzy plugh frobnicate"))
    }

    @Test fun regionAloneWithNoEffectIsNull() {
        // "sky" names a region but asks for no change — guessing an edit
        // would be dishonest.
        assertNull(PromptEdit.interpret("sky"))
        assertNull(PromptEdit.interpret("the background"))
    }

    @Test fun eyesAloneWithNoVerbIsNull() {
        // Documented honesty rule: "eyes" alone is ambiguous, not guessed.
        assertNull(PromptEdit.interpret("eyes"))
    }

    @Test fun teethWhiteningStaysOut() {
        // No on-device teeth detector exists, so the intent stays unclaimed.
        assertNull(PromptEdit.interpret("whiten teeth"))
    }

    @Test fun helpTextOffersWorkingExamples() {
        assertTrue(PromptEdit.helpText.isNotBlank())
        assertTrue(PromptEdit.helpText.contains("dramatic sky"))
        assertTrue(PromptEdit.helpText.contains("remove blemishes"))
    }

    // ---- portrait intents -----------------------------------------------------

    @Test fun removeBlemishesIntent() {
        val p = plan("remove blemishes")
        assertEquals(PromptPortrait.RemoveBlemishes, p.portrait)
        assertNull(p.region)
        assertEquals(PromptGlobal(), p.global)
        assertEquals(LocalAdjust(), p.local)
        assertTrue(p.effectLabels.isEmpty())
        assertEquals("Blemishes: auto-detect + heal", p.summary)
    }

    @Test fun pimpleSynonymMapsToBlemishes() {
        assertEquals(PromptPortrait.RemoveBlemishes, plan("clean up the pimples").portrait)
    }

    @Test fun softenDarkCirclesDoesNotSoftenWholePhoto() {
        // THE leak-guard test: "soften" is blanked with the under-eye
        // phrase, so no global clarity -30 may leak in.
        val p = plan("soften dark circles")
        assertEquals(PromptPortrait.SoftenUnderEyes, p.portrait)
        assertEquals(0f, p.global.clarity)
        assertEquals(PromptGlobal(), p.global)
        assertEquals("Under-eyes: auto-detect + soften", p.summary)
        assertTrue(p.effectLabels.isEmpty())
    }

    @Test fun brightenEyesDoesNotBrightenWholePhoto() {
        // "brighten" is blanked with the eye verb: no +0.7 EV may leak in.
        val p = plan("brighten her eyes")
        assertEquals(PromptPortrait.BrightenEyes, p.portrait)
        assertEquals(0f, p.global.exposure)
        assertEquals(PromptGlobal(), p.global)
        assertEquals("Eyes: auto-locate + brighten", p.summary)
    }

    @Test fun portraitPlusGlobalEffectCombines() {
        // "remove blemishes and warm it up": one portrait intent + a
        // whole-photo slider effect.
        val p = plan("remove blemishes and warm it up")
        assertEquals(PromptPortrait.RemoveBlemishes, p.portrait)
        assertNull(p.region)
        assertEquals(20f, p.global.temp)
        assertEquals(listOf("warm"), p.effectLabels)
        assertTrue(p.summary.contains("Blemishes: auto-detect + heal"))
        assertTrue(p.summary.contains("Whole photo: warm"))
    }

    // ---- region detection -----------------------------------------------------

    @Test fun dramaticSkyIsLocal() {
        val p = plan("dramatic sky")
        assertEquals(PromptRegion.Sky, p.region)
        assertEquals(30f, p.local.contrast)
        assertEquals(25f, p.local.clarity)
        assertEquals(15f, p.local.dehaze)
        assertEquals(10f, p.local.saturation)
        // Local plans leave the global sliders untouched.
        assertEquals(PromptGlobal(), p.global)
        assertEquals("Prompt: Sky", p.maskName)
        assertEquals("Sky: dramatic", p.summary)
        assertEquals(listOf("dramatic"), p.effectLabels)
    }

    @Test fun naturalPhrasingStillMatches() {
        val p = plan("make the sky more dramatic")
        assertEquals(PromptRegion.Sky, p.region)
        assertEquals(30f, p.local.contrast)
    }

    @Test fun matchingIsCaseInsensitive() {
        val p = plan("DRAMATIC SKY")
        assertEquals(PromptRegion.Sky, p.region)
        assertEquals(listOf("dramatic"), p.effectLabels)
    }

    @Test fun punctuationDoesNotBreakWordBoundaries() {
        val p = plan("dramatic sky!")
        assertEquals(PromptRegion.Sky, p.region)
    }

    @Test fun brightenFaceIsSubjectLocal() {
        val p = plan("brighten the face")
        assertEquals(PromptRegion.Subject, p.region)
        // Global-path exposure is EV; the mask path stores EV*100.
        assertEquals(70f, p.local.exposure)
        assertEquals(12f, p.local.shadows)
        assertEquals("Prompt: Subject", p.maskName)
    }

    @Test fun softenSkinIsLocal() {
        val p = plan("soften the skin")
        assertEquals(PromptRegion.Skin, p.region)
        assertEquals(-30f, p.local.clarity)
        assertEquals("Prompt: Skin", p.maskName)
    }

    @Test fun darkenBackgroundIsLocal() {
        val p = plan("darken the background")
        assertEquals(PromptRegion.Background, p.region)
        assertEquals(-70f, p.local.exposure)
    }

    @Test fun liftTheShadowsSelectsShadowBand() {
        // "lift" is an effect AND "shadows" is a region: both apply.
        val p = plan("lift the shadows")
        assertEquals(PromptRegion.Luminance(0f, 0.38f), p.region)
        assertEquals(70f, p.local.exposure)
        assertEquals(12f, p.local.shadows)
        assertEquals("Prompt: Shadows", p.maskName)
        assertEquals("Shadows: brighten", p.summary)
    }

    @Test fun recoverHighlightsSelectsHighlightBand() {
        val p = plan("tame the highlights")
        assertEquals(PromptRegion.Luminance(0.62f, 1f), p.region)
        assertEquals(-40f, p.local.highlights)
        assertEquals("Prompt: Highlights", p.maskName)
    }

    @Test fun luminanceFeatherDefaultsToHalf() {
        val r = plan("darken the shadows").region as PromptRegion.Luminance
        assertEquals(0.5f, r.feather)
    }

    // ---- global effects -------------------------------------------------------

    @Test fun warmSunsetIsGlobal() {
        val p = plan("warm sunset")
        assertNull(p.region)
        // "warm" (+20) and "sunset" (+22) stack: the interpreter sums
        // effect temps, it does not take the last one.
        assertEquals(42f, p.global.temp)
        assertEquals(12f, p.global.saturation)
        assertEquals(LocalAdjust(), p.local)
        assertEquals("", p.maskName)
        assertEquals("Whole photo: warm, sunset", p.summary)
        assertEquals(listOf("warm", "sunset"), p.effectLabels)
    }

    @Test fun brightenAloneIsGlobalExposure() {
        val p = plan("brighten it up")
        assertNull(p.region)
        assertEquals(0.7f, p.global.exposure)
        assertEquals(12f, p.global.shadows)
    }

    @Test fun blackAndWhiteIsGlobalNotHighlightBand() {
        // "white" (singular) is NOT a highlight-region keyword — only
        // "whites" (plural) matches — so the B&W effect lands as a GLOBAL
        // edit, not a local highlight-band edit. Pin the real behavior.
        val p = plan("make it black and white")
        assertNull(p.region)
        assertTrue(p.global.bw)
        assertEquals(-100f, p.global.saturation)
        assertEquals(LocalAdjust(), p.local)
        assertEquals("", p.maskName)
        assertEquals("Whole photo: black & white", p.summary)
    }

    @Test fun grayscaleAloneIsGlobalBlackAndWhite() {
        val p = plan("make it grayscale")
        assertNull(p.region)
        assertTrue(p.global.bw)
        assertEquals(-100f, p.global.saturation)
    }

    @Test fun monochromeSkyIsLocalBlackAndWhite() {
        val p = plan("monochrome sky")
        assertEquals(PromptRegion.Sky, p.region)
        assertEquals(-100f, p.local.saturation)
    }

    @Test fun popStacksThreeDeltas() {
        val p = plan("make it pop")
        assertEquals(15f, p.global.contrast)
        assertEquals(15f, p.global.saturation)
        assertEquals(10f, p.global.clarity)
    }

    @Test fun effectsAccumulateInDetectionOrder() {
        val p = plan("dramatic vivid sky")
        assertEquals(PromptRegion.Sky, p.region)
        assertEquals(30f, p.local.contrast)          // dramatic only
        assertEquals(10f + 25f, p.local.saturation) // dramatic + vivid
        // LocalAdjust has no vibrance channel: the vivid effect's vibrance
        // component is dropped on mask paths (global path keeps it).
        // Pinned as the real behavior, not the assumed one.
        assertEquals(listOf("dramatic", "vivid"), p.effectLabels)
        assertEquals("Sky: dramatic, vivid", p.summary)
    }

    @Test fun sharpenMapsToSharpeningAndClarity() {
        val p = plan("sharpen the photo")
        assertEquals(35f, p.global.sharpening)
        assertEquals(12f, p.global.clarity)
    }

    @Test fun dreamyAndMuted() {
        val dreamy = plan("dreamy")
        assertEquals(-20f, dreamy.global.dehaze)
        assertEquals(-15f, dreamy.global.clarity)
        val muted = plan("muted tones")
        assertEquals(-20f, muted.global.saturation)
        assertEquals(-15f, muted.global.contrast)
    }

    // ---- region-miss honesty --------------------------------------------------

    @Test fun regionMissMessagesAreDistinctAndHonest() {
        val msgs = listOf(
            PromptEdit.regionMissMessage(PromptRegion.Sky),
            PromptEdit.regionMissMessage(PromptRegion.Subject),
            PromptEdit.regionMissMessage(PromptRegion.Background),
            PromptEdit.regionMissMessage(PromptRegion.Skin),
            PromptEdit.regionMissMessage(PromptRegion.Luminance(0f, 0.38f)),
        )
        assertEquals(5, msgs.toSet().size)
        for (m in msgs) {
            assertTrue(m.isNotBlank())
            assertTrue("miss message must say the edit was not applied: $m",
                m.contains("not applied"))
        }
        assertTrue(msgs[0].contains("sky", ignoreCase = true))
        assertTrue(msgs[3].contains("skin", ignoreCase = true))
    }
}
