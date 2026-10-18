package com.lumora.photoeditor

import com.lumora.photoeditor.data.Presets
import com.lumora.photoeditor.engine.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class StateAndPresetTest {

    @Test fun editState_defaultIsDefault() {
        assertTrue(EditState().isDefault())
        assertFalse(EditState().copy(light = LightParams(exposure = 1f)).isDefault())
    }

    @Test fun toneCurve_defaultIsIdentity() {
        val lut = ToneCurve().toLut(256)
        for (i in lut.indices) {
            assertTrue(abs(lut[i] - i / 255f) < 1e-3f)
        }
    }

    @Test fun toneCurve_passesThroughControlPoints() {
        val curve = ToneCurve(listOf(
            CurvePoint(0f, 0f), CurvePoint(0.25f, 0.4f),
            CurvePoint(0.75f, 0.6f), CurvePoint(1f, 1f)))
        val lut = curve.toLut(256)
        assertTrue(abs(lut[64] - 0.4f) < 0.02f)   // x=0.25
        assertTrue(abs(lut[191] - 0.6f) < 0.03f)  // x=0.75
    }

    @Test fun toneCurve_monotonicForSaneInput() {
        val curve = ToneCurve(listOf(
            CurvePoint(0f, 0f), CurvePoint(0.3f, 0.5f), CurvePoint(1f, 1f)))
        val lut = curve.toLut(256)
        for (i in 1 until lut.size) {
            assertTrue("LUT not monotonic at $i", lut[i] >= lut[i - 1] - 1e-4f)
        }
    }

    @Test fun preset_blendEndpoints() {
        val a = EditState()
        val b = Presets.builtIn().first { it.id == "cinematic" }.state
        val t0 = Presets.blend(a, b, 0f)
        val t1 = Presets.blend(a, b, 1f)
        assertEquals(a, t0)
        assertEquals(b, t1)
    }

    @Test fun preset_blendHalfway() {
        val a = EditState()
        val b = EditState(light = LightParams(exposure = 2f, contrast = 40f))
        val mid = Presets.blend(a, b, 0.5f)
        assertTrue(abs(mid.light.exposure - 1f) < 1e-5f)
        assertTrue(abs(mid.light.contrast - 20f) < 1e-5f)
    }

    @Test fun preset_applyStrengthZeroKeepsCurrent() {
        val current = EditState(light = LightParams(exposure = 0.5f))
        val p = Presets.builtIn().first { it.id == "night" }
        val out = Presets.apply(current, p, 0f)
        assertEquals(current, out)
    }

    @Test fun preset_applyFullReplacesNumeric() {
        val current = EditState()
        val p = Presets.builtIn().first { it.id == "night" }
        val out = Presets.apply(current, p, 1f)
        assertTrue(abs(out.light.exposure - 0.3f) < 1e-5f)
        assertTrue(abs(out.light.shadows - 30f) < 1e-5f)
    }

    @Test fun preset_wheelHueWrapsShortestArc() {
        val a = EditState()
        val b = EditState(grading = GradingParams(shadows = GradeWheel(350f, 50f)))
        val mid = Presets.blend(a, b, 0.5f)
        // 0 -> 350 should go backwards through 355, not forward through 175
        val hue = mid.grading.shadows.hueDeg
        assertTrue("hue=$hue should be near 355", hue > 340f || hue < 20f)
    }

    @Test fun builtIns_coverRequiredCategories() {
        val ids = Presets.builtIn().map { it.id }.toSet()
        for (need in listOf("natural", "portrait", "cinematic", "film", "cool",
            "warm", "landscape", "night", "street")) {
            assertTrue("missing preset $need", need in ids)
        }
    }

    @Test fun builtIns_modifyRealParams() {
        // every built-in must change at least one real parameter
        for (p in Presets.builtIn()) {
            assertFalse("${p.id} changes nothing", p.state.isDefault())
        }
    }

    @Test fun exportSettings_validation() {
        assertTrue(ExportSettings().validate().isEmpty())
        assertTrue(ExportSettings(quality = 0).validate().isNotEmpty())
        assertTrue(ExportSettings(quality = 101).validate().isNotEmpty())
        assertTrue(ExportSettings(longEdge = -5).validate().isNotEmpty())
        assertTrue(ExportSettings(longEdge = 20000).validate().isNotEmpty())
    }

    @Test fun exportSettings_mimeAndExtension() {
        assertEquals("image/jpeg", ExportSettings(format = ExportFormat.JPEG).mimeType())
        assertEquals("png", ExportSettings(format = ExportFormat.PNG).extension())
        assertEquals("webp", ExportSettings(format = ExportFormat.WEBP).extension())
    }

    @Test fun maskModel_defaults() {
        val m = Mask(id = "1", name = "Test")
        assertTrue(m.isEmpty())
        assertTrue(m.enabled)
        assertFalse(m.inverted)
    }
}
