package com.lumora.photoeditor.data

import com.lumora.photoeditor.engine.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/**
 * First-ever executed verification of [Presets] — the catalog integrity of
 * the built-in presets and the blend contract behind the Preset panel's
 * Strength slider (Presets.apply / Presets.blend).
 *
 * Pure Kotlin: Presets.kt + EditParams.kt + CropParams.kt carry zero Android
 * imports, so this runs on the standalone kotlinc harness as well as Gradle.
 */
class PresetsTest {

    private fun approx(a: Float, b: Float, eps: Float = 1e-4f) = abs(a - b) <= eps

    /** Non-default "current photo" state used as the blend source. */
    private val cur = EditState(
        light = LightParams(exposure = -1f, contrast = 10f),
        color = ColorParams(vibrance = 5f),
        grading = GradingParams(shadows = GradeWheel(350f, 20f)),
        calibration = CalibrationParams(shadowsTint = -30f),
        detail = DetailParams(sharpening = 10f),
        optics = OpticsParams(grainAmount = 5f),
        crop = CropParams(0.1f, 0.1f, 0.9f, 0.9f),
        lookTransfer = LookTransferParams(List(6) { 1f }, List(6) { 2f }, "ref", 0.8f),
        aiRetouch = AiRetouchParams(enabled = true, strength = 0.7f),
    )

    private fun p(state: EditState) = Preset("t", "T", "Test", state)

    // ------------------------------------------------------------------
    // Built-in catalog integrity
    // ------------------------------------------------------------------

    @Test fun builtInHasThirtySevenPresets() {
        // Catalog count pin: 37 as of the current build (was 21 when the
        // preset complaint was first addressed).
        assertEquals(37, Presets.builtIn().size)
    }

    @Test fun builtInIdsAreUnique() {
        val ids = Presets.builtIn().map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test fun builtInNamesAreUnique() {
        val names = Presets.builtIn().map { it.name }
        assertEquals(names.size, names.toSet().size)
    }

    @Test fun builtInMetadataIsNonBlank() {
        for (preset in Presets.builtIn()) {
            assertTrue("blank id", preset.id.isNotBlank())
            assertTrue("blank name", preset.name.isNotBlank())
            assertTrue("blank category", preset.category.isNotBlank())
        }
    }

    @Test fun builtInListIsDeterministic() {
        assertEquals(
            Presets.builtIn().map { it.id },
            Presets.builtIn().map { it.id },
        )
    }

    @Test fun builtInsAreMarkedBuiltIn() {
        assertTrue(Presets.builtIn().all { it.builtIn })
    }

    @Test fun builtInsNeverCarryImagePairState() {
        // Presets are parametric deltas shared across photos: an active
        // look-transfer match (statistics of THIS photo + ITS reference) or
        // AI Pro Retouch weights (derived from THIS photo's pixels) must
        // never ship inside a built-in.
        for (preset in Presets.builtIn()) {
            assertFalse("${preset.id} lookTransfer", preset.state.lookTransfer.isActive())
            assertFalse("${preset.id} aiRetouch", preset.state.aiRetouch.enabled)
        }
    }

    // ------------------------------------------------------------------
    // apply() / blend() contract
    // ------------------------------------------------------------------

    @Test fun applyStrengthZeroReturnsCurrent() {
        assertEquals(cur, Presets.apply(cur, p(EditState(light = LightParams(exposure = 5f))), 0f))
    }

    @Test fun applyStrengthOneReturnsPresetStateKeepingPhotoLookTransfer() {
        val preset = p(EditState(light = LightParams(exposure = 5f)))
        val out = Presets.apply(cur, preset, 1f)
        // Full-strength apply = the preset's state, EXCEPT the photo's own
        // look-transfer match, which presets must never wipe (documented in
        // Presets.blend: "Always keep a's").
        assertEquals(preset.state.copy(lookTransfer = cur.lookTransfer), out)
        assertEquals(cur.lookTransfer, out.lookTransfer)
        assertTrue(out.lookTransfer.isActive())
    }

    @Test fun strengthIsClamped() {
        val preset = p(EditState(light = LightParams(exposure = 5f)))
        assertEquals(Presets.apply(cur, preset, 0f), Presets.apply(cur, preset, -3f))
        assertEquals(Presets.apply(cur, preset, 1f), Presets.apply(cur, preset, 2f))
    }

    @Test fun numericsInterpolateAtHalfStrength() {
        val out = Presets.apply(cur, p(EditState(light = LightParams(exposure = 1f))), 0.5f)
        assertTrue(approx(0f, out.light.exposure))       // (-1 + 1) / 2
        assertTrue(approx(5f, out.detail.sharpening))   // (10 + 0) / 2
        assertTrue(approx(2.5f, out.optics.grainAmount)) // (5 + 0) / 2
    }

    @Test fun toneCurveFadesInSmoothlyNotSnaps() {
        val lifted = ToneCurve(listOf(CurvePoint(0f, 0f), CurvePoint(0.5f, 0.7f), CurvePoint(1f, 1f)))
        val out = Presets.apply(cur, p(EditState(light = LightParams(toneCurve = lifted))), 0.4f)
        // 17-sample resampled lerp: at x=0.5 -> 0.6*identity + 0.4*lifted = 0.58.
        val mid = out.light.toneCurve.toLut(17)[8]
        assertTrue("curve snapped or vanished: $mid", mid > 0.5f && mid < 0.7f)
    }

    @Test fun structuralParamsSwitchAtHalfStrength() {
        val preset = p(EditState(color = ColorParams(bw = true), crop = CropParams()))
        val low = Presets.apply(cur, preset, 0.4f)
        val high = Presets.apply(cur, preset, 0.6f)
        // B&W is a whole-switch (a half-applied conversion is not meaningful).
        assertFalse(low.color.bw)
        assertTrue(high.color.bw)
        // Crop is structural too.
        assertEquals(cur.crop, low.crop)
        assertEquals(CropParams(), high.crop)
    }

    @Test fun blendWheelTakesShortestHueArc() {
        // 350 -> 10 must travel +20 deg (through 0), not -340.
        val preset = p(EditState(grading = GradingParams(shadows = GradeWheel(10f, 20f))))
        val out = Presets.apply(cur, preset, 0.5f)
        assertTrue(approx(0f, out.grading.shadows.hueDeg, 0.5f))
        assertTrue(approx(20f, out.grading.shadows.sat))
    }

    @Test fun calibrationInterpolatesLikeOtherNumerics() {
        val out = Presets.apply(
            cur, p(EditState(calibration = CalibrationParams(shadowsTint = 30f))), 0.5f)
        assertTrue(approx(0f, out.calibration.shadowsTint)) // (-30 + 30) / 2
    }

    @Test fun lookTransferKeptAtPartialStrength() {
        val out = Presets.apply(cur, p(EditState(light = LightParams(exposure = 5f))), 0.5f)
        assertEquals(cur.lookTransfer, out.lookTransfer)
        assertTrue(out.lookTransfer.isActive())
    }

    @Test fun lookTransferNeverFabricatedFromPreset() {
        val active = LookTransferParams(List(6) { 3f }, List(6) { 4f }, "other", 1f)
        val out = Presets.apply(EditState.DEFAULT, p(EditState(lookTransfer = active)), 0.6f)
        assertFalse(out.lookTransfer.isActive())
    }

    @Test fun aiRetouchPartialStrengthResetsToDefault() {
        // Current behavior, pinned deliberately: blend() does not carry
        // aiRetouch, so a partial-strength preset application resets it to
        // defaults. (Tension: this silently drops an active AI Pro Retouch
        // grade — candidate for a future fix; logged, not changed here.)
        val out = Presets.apply(cur, p(EditState(light = LightParams(exposure = 5f))), 0.5f)
        assertEquals(AiRetouchParams(), out.aiRetouch)
    }

    @Test fun customPresetMetadata() {
        val c = Presets.custom("Mine", EditState(), "Portraits")
        assertTrue(c.id.startsWith("custom-"))
        assertFalse(c.builtIn)
        assertEquals("Mine", c.name)
        assertEquals("Portraits", c.category)
        assertEquals("My Presets", Presets.custom("X", EditState()).category)
    }
}
