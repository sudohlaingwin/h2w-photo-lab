package com.lumora.photoeditor.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * First-ever executed verification of engine/EditParams.kt — the pure
 * data-class semantics behind the whole non-destructive pipeline.
 *
 * Why this file matters: EditState is the single source of truth stored as
 * a JSON sidecar per library entry, the object the GL thread renders from
 * 60x/sec (EditState.DEFAULT is deliberately shared), and the thing
 * copy-settings paste moves between photos. A wrong sanitize rule leaks
 * image-specific AI state (wrong-photo LUTs, blemish spots sampled from
 * another photo's pixels) into the target; a wrong tone-curve blend makes
 * the Preset Strength slider lie; a wrong export-name sanitizer writes
 * unopenable MediaStore entries. None of this had any test.
 *
 * Harness note: EditParams.kt imports only kotlinx.serialization
 * (annotation-only here) and kotlin.math, so the standalone kotlinc 1.9.23
 * harness compiles it with the @Serializable stub — the same proven
 * pattern as the rest of this verification series.
 */
class EditParamsTest {

    // ------------------------------------------------------------------
    // EditState.isDefault / DEFAULT
    // ------------------------------------------------------------------

    @Test fun `default state reports isDefault`() {
        assertTrue(EditState.DEFAULT.isDefault())
        assertTrue(EditState().isDefault())
    }

    @Test fun `any deviation breaks isDefault`() {
        assertFalse(EditState(light = LightParams(exposure = 1f)).isDefault())
        assertFalse(EditState(color = ColorParams(bw = true)).isDefault())
        assertFalse(EditState(masks = listOf(Mask(id = "m", name = "x"))).isDefault())
    }

    // ------------------------------------------------------------------
    // sanitizedForPaste
    // ------------------------------------------------------------------

    @Test fun `sanitizedForPaste drops retouch spots and AI-derived states`() {
        val src = EditState(
            retouch = RetouchState(
                listOf(RetouchSpot(RetouchOp.HEAL, 0.1f, 0.2f, 0.3f, 0.4f, 0.05f))),
            aiRetouch = AiRetouchParams(
                enabled = true, weights = listOf(0.5f, 0.2f, 0.2f, 0.1f), strength = 0.8f),
            lookTransfer = LookTransferParams(
                srcStats = List(6) { 0.1f }, refStats = List(6) { 0.2f },
                refName = "ref.jpg", strength = 0.5f),
        )
        val out = src.sanitizedForPaste()
        // blemish positions were sampled from the SOURCE photo's pixels
        assertEquals(RetouchState(), out.retouch)
        // AI weights are image-specific: re-warping them onto another photo
        // would bake the wrong grade
        assertEquals(AiRetouchParams(), out.aiRetouch)
        assertEquals(LookTransferParams(), out.lookTransfer)
        assertFalse(out.lookTransfer.isActive())
    }

    @Test fun `sanitizedForPaste removes AI-only masks entirely`() {
        val aiTypes = listOf(
            MaskType.AI_SUBJECT, MaskType.AI_BACKGROUND, MaskType.AI_SKIN,
            MaskType.SKY_SELECT, MaskType.COLOR_RANGE, MaskType.LUMINANCE_RANGE,
            MaskType.DEPTH_RANGE,
        )
        val masks = aiTypes.mapIndexed { i, t ->
            Mask(id = "m$i", name = t.name,
                components = listOf(MaskComponent(id = "c$i", type = t)))
        }
        val out = EditState(masks = masks).sanitizedForPaste()
        assertTrue("AI rasters computed from the source photo must not travel", out.masks.isEmpty())
    }

    @Test fun `sanitizedForPaste keeps portable components and drops AI ones from mixed masks`() {
        val brush = MaskComponent(id = "b", type = MaskType.BRUSH)
        val grad = MaskComponent(id = "g", type = MaskType.LINEAR_GRADIENT, op = MaskOp.SUBTRACT)
        val radial = MaskComponent(id = "r", type = MaskType.RADIAL_GRADIENT)
        val ai = MaskComponent(id = "a", type = MaskType.AI_SUBJECT)
        val color = MaskComponent(id = "c", type = MaskType.COLOR_RANGE)
        val mask = Mask(
            id = "m", name = "Mixed", amount = 0.7f,
            components = listOf(brush, ai, grad, color, radial),
            adjust = LocalAdjust(exposure = 0.5f),
        )
        val out = EditState(masks = listOf(mask)).sanitizedForPaste()
        assertEquals(1, out.masks.size)
        val kept = out.masks[0]
        assertEquals(listOf("b", "g", "r"), kept.components.map { it.id })
        assertEquals(MaskOp.SUBTRACT, kept.components[1].op)
        // mask-level metadata travels with the portable parts
        assertEquals("Mixed", kept.name)
        assertEquals(0.5f, kept.adjust.exposure, 0f)
        assertEquals(0.7f, kept.amount, 0f)
    }

    @Test fun `sanitizedForPaste preserves every parametric adjustment`() {
        val src = EditState(
            light = LightParams(
                exposure = 1.5f,
                toneCurve = ToneCurve(listOf(
                    CurvePoint(0f, 0f), CurvePoint(0.5f, 0.6f), CurvePoint(1f, 1f)))),
            color = ColorParams(vibrance = 20f),
            grading = GradingParams(balance = -10f),
            calibration = CalibrationParams(shadowsTint = 5f),
            detail = DetailParams(sharpening = 50f),
            optics = OpticsParams(vignetteAmount = -30f),
            geometry = GeometryParams(rotate = 2f),
            crop = CropParams(left = 0.1f, top = 0.1f, right = 0.9f, bottom = 0.9f),
        )
        val out = src.sanitizedForPaste()
        assertEquals(src.light, out.light)
        assertEquals(src.color, out.color)
        assertEquals(src.grading, out.grading)
        assertEquals(src.calibration, out.calibration)
        assertEquals(src.detail, out.detail)
        assertEquals(src.optics, out.optics)
        assertEquals(src.geometry, out.geometry)
        assertEquals(src.crop, out.crop)
    }

    @Test fun `sanitizedForPaste never mutates the source state`() {
        val mask = Mask(id = "m", name = "Sky",
            components = listOf(MaskComponent(id = "c", type = MaskType.SKY_SELECT)))
        val src = EditState(
            masks = listOf(mask),
            retouch = RetouchState(listOf(RetouchSpot(RetouchOp.CLONE, 0.1f, 0.1f, 0.2f, 0.2f, 0.05f))),
        )
        val out = src.sanitizedForPaste()
        assertNotSame(src, out)
        // source still has everything (EditState is deeply immutable; the
        // shared DEFAULT instance the GL thread reads must never change)
        assertEquals(1, src.masks.size)
        assertEquals(1, src.retouch.spots.size)
        assertTrue(out.masks.isEmpty())
        assertTrue(out.retouch.spots.isEmpty())
    }

    // ------------------------------------------------------------------
    // ToneCurve
    // ------------------------------------------------------------------

    @Test fun `identity LUT is linear with exact endpoints`() {
        val lut = ToneCurve().toLut(5)
        assertEquals(5, lut.size)
        assertEquals(0f, lut[0], 0f)
        assertEquals(0.25f, lut[1], 1e-6f)
        assertEquals(0.5f, lut[2], 1e-6f)
        assertEquals(0.75f, lut[3], 1e-6f)
        assertEquals(1f, lut[4], 0f)
    }

    @Test fun `LUT output is clamped to the 0 to 1 range`() {
        val wild = ToneCurve(listOf(
            CurvePoint(0f, -1f), CurvePoint(0.5f, 2f), CurvePoint(1f, -0.5f)))
        val lut = wild.toLut(64)
        assertTrue(lut.all { it in 0f..1f })
    }

    @Test fun `default curve matches LINEAR preset`() {
        assertTrue(ToneCurve().matchesPreset(CurvePreset.LINEAR))
        assertEquals(CurvePreset.LINEAR, ToneCurve().activePreset())
    }

    @Test fun `named presets round-trip and custom curves have no active preset`() {
        val medium = ToneCurve(CurvePreset.MEDIUM_CONTRAST.points)
        assertTrue(medium.matchesPreset(CurvePreset.MEDIUM_CONTRAST))
        assertEquals(CurvePreset.MEDIUM_CONTRAST, medium.activePreset())
        val custom = ToneCurve(listOf(
            CurvePoint(0f, 0f), CurvePoint(0.5f, 0.6f), CurvePoint(1f, 1f)))
        assertNull(custom.activePreset())
        assertFalse(custom.matchesPreset(CurvePreset.LINEAR))
    }

    @Test fun `blendToneCurve endpoints are exact`() {
        val a = ToneCurve()
        val b = ToneCurve(CurvePreset.STRONG_CONTRAST.points)
        assertArrayEquals(a.toLut(17), blendToneCurve(a, b, 0f).toLut(17), 1e-5f)
        assertArrayEquals(b.toLut(17), blendToneCurve(a, b, 1f).toLut(17), 1e-5f)
    }

    @Test fun `blendToneCurve clamps t and honors the sample count`() {
        val a = ToneCurve()
        val b = ToneCurve(CurvePreset.STRONG_CONTRAST.points)
        assertArrayEquals(a.toLut(17), blendToneCurve(a, b, -1f).toLut(17), 1e-5f)
        assertArrayEquals(b.toLut(17), blendToneCurve(a, b, 2f).toLut(17), 1e-5f)
        assertEquals(9, blendToneCurve(a, b, 0.5f, samples = 9).points.size)
    }

    @Test fun `blendToneCurve at half strength is the honest midpoint`() {
        val a = ToneCurve()
        val b = ToneCurve(CurvePreset.STRONG_CONTRAST.points)
        // strong contrast's control point at x=0.25 is y=0.13; identity is
        // 0.25 there; the honest 50% blend is 0.19 — not a whole-curve snap
        val mid = blendToneCurve(a, b, 0.5f).toLut(17)
        assertEquals(0.19f, mid[4], 1e-5f) // x = 4/16 = 0.25
    }

    // ------------------------------------------------------------------
    // targetCurvePoint
    // ------------------------------------------------------------------

    @Test fun `targetCurvePoint grabs an existing point within the radius`() {
        val curve = ToneCurve(listOf(
            CurvePoint(0f, 0f), CurvePoint(0.5f, 0.5f), CurvePoint(1f, 1f)))
        val (pts, index) = targetCurvePoint(curve, 0.5f)
        assertEquals(1, index)
        assertEquals(3, pts.size)
        assertEquals(0.5f, pts[1].x, 1e-6f)
    }

    @Test fun `targetCurvePoint inserts at the current output so the image never jumps`() {
        val curve = ToneCurve() // identity
        val (pts, index) = targetCurvePoint(curve, 0.9f)
        assertEquals(1, index)
        assertEquals(3, pts.size)
        assertEquals(CurvePoint(0f, 0f), pts[0]) // endpoints preserved
        assertEquals(CurvePoint(1f, 1f), pts[2])
        assertTrue(pts[0].x < pts[1].x && pts[1].x < pts[2].x) // still sorted
        assertEquals(0.9f, pts[1].x, 0.01f)
        assertEquals(0.9f, pts[1].y, 0.01f) // inserted AT the curve's output
    }

    // ------------------------------------------------------------------
    // hueRangeFor
    // ------------------------------------------------------------------

    @Test fun `hueRangeFor classifies primaries and near-gray`() {
        assertEquals(HueRange.RED, hueRangeFor(1f, 0f, 0f))
        assertEquals(HueRange.GREEN, hueRangeFor(0f, 1f, 0f))
        assertEquals(HueRange.BLUE, hueRangeFor(0f, 0f, 1f))
        assertEquals(HueRange.YELLOW, hueRangeFor(1f, 1f, 0f))
        // near-gray: hue undefined, lands on Red by documented convention
        assertEquals(HueRange.RED, hueRangeFor(0.5f, 0.5f, 0.5f))
    }

    // ------------------------------------------------------------------
    // ColorParams / FxParams / LookTransferParams
    // ------------------------------------------------------------------

    @Test fun `color mixer lookups fall back to neutral`() {
        val c = ColorParams()
        assertEquals(HslAdjust(), c.forRange(HueRange.AQUA))
        assertEquals(0f, c.bwMixFor(HueRange.RED), 0f)
        val withEntry = ColorParams(mixer = mapOf("AQUA" to HslAdjust(hue = 10f)))
        assertEquals(HslAdjust(hue = 10f), withEntry.forRange(HueRange.AQUA))
    }

    @Test fun `fx isActive and coerced`() {
        assertFalse(FxParams().isActive())
        assertTrue(FxParams(leak = LightLeakStyle.SUNSET, leakAmount = 50f).isActive())
        assertFalse(FxParams(leak = LightLeakStyle.SUNSET, leakAmount = 0f).isActive())
        assertTrue(FxParams(dust = 30f).isActive())
        val coerced = FxParams(leakAmount = 150f, dust = -10f).coerced()
        assertEquals(100f, coerced.leakAmount, 0f)
        assertEquals(0f, coerced.dust, 0f)
    }

    @Test fun `lookTransfer isActive needs both 6-float stat sets`() {
        assertFalse(LookTransferParams().isActive())
        assertFalse(LookTransferParams(
            srcStats = List(6) { 0f }, refStats = List(5) { 0f }).isActive())
        assertTrue(LookTransferParams(
            srcStats = List(6) { 0f }, refStats = List(6) { 0f }).isActive())
    }

    // ------------------------------------------------------------------
    // Export naming / settings / watermark
    // ------------------------------------------------------------------

    @Test fun `sanitizeExportName strips illegal chars and control codes`() {
        assertEquals("abcdefghij", sanitizeExportName("a/b\\c:d*e?f\"g<h>i|j"))
        assertEquals("hidden", sanitizeExportName("...hidden"))
        assertEquals("My Photo 2024", sanitizeExportName("  My Photo 2024  "))
        assertEquals("", sanitizeExportName("   "))
        assertEquals("", sanitizeExportName("///"))
        assertEquals(120, sanitizeExportName("x".repeat(200)).length)
    }

    @Test fun `exportSettings validate catches bad quality and long edge`() {
        assertTrue(ExportSettings().validate().isEmpty())
        assertTrue(ExportSettings(quality = 0).validate().any { it.contains("Quality") })
        assertTrue(ExportSettings(quality = 101).validate().any { it.contains("Quality") })
        assertTrue(ExportSettings(longEdge = -1).validate().any { it.contains("Long edge") })
        assertTrue(ExportSettings(longEdge = 16001).validate().any { it.contains("Long edge") })
    }

    @Test fun `export mime types and extensions`() {
        assertEquals("image/jpeg", ExportSettings(format = ExportFormat.JPEG).mimeType())
        assertEquals("image/png", ExportSettings(format = ExportFormat.PNG).mimeType())
        assertEquals("image/webp", ExportSettings(format = ExportFormat.WEBP).mimeType())
        assertEquals("jpg", ExportSettings(format = ExportFormat.JPEG).extension())
        assertEquals("png", ExportSettings(format = ExportFormat.PNG).extension())
        assertEquals("webp", ExportSettings(format = ExportFormat.WEBP).extension())
    }

    @Test fun `galleryFileName prefers typed name then source stem then timestamp`() {
        assertEquals("My Shot.jpg",
            ExportSettings(fileName = "  My Shot  ").galleryFileName())
        assertEquals("IMG_2041.jpg", ExportSettings().galleryFileName("IMG_2041.jpg"))
        assertEquals("IMG_2041.png",
            ExportSettings(format = ExportFormat.PNG).galleryFileName("IMG_2041"))
        val auto = ExportSettings().galleryFileName(null)
        assertTrue(auto.startsWith("Lumora_") && auto.endsWith(".jpg"))
        // a blank source stem never yields a bare extension
        val auto2 = ExportSettings().galleryFileName("   ")
        assertTrue(auto2.startsWith("Lumora_") && auto2.endsWith(".jpg"))
    }

    @Test fun `watermark isActive needs enabled plus non-blank text`() {
        assertTrue(WatermarkSettings(enabled = true, text = "H2W").isActive())
        assertFalse(WatermarkSettings(enabled = true, text = "  ").isActive())
        assertFalse(WatermarkSettings(enabled = false, text = "H2W").isActive())
    }

    @Test fun `watermark validate catches out-of-range size and opacity`() {
        assertTrue(WatermarkSettings().validate().isEmpty())
        assertTrue(WatermarkSettings(sizeFrac = 0.5f).validate().any { it.contains("size") })
        assertTrue(WatermarkSettings(opacity = 2f).validate().any { it.contains("opacity") })
    }

    @Test fun `watermarkLayout anchors to the right edges`() {
        val wm = WatermarkSettings(enabled = true, text = "H2W",
            position = WatermarkPosition.BOTTOM_RIGHT)
        val l = watermarkLayout(1000, 500, wm)
        assertEquals(17.5f, l.textPx, 1e-4f) // 0.035 * 500
        assertEquals(970f, l.x, 1e-4f) // 1000 - 3%
        assertEquals(485f, l.baselineY, 1e-4f) // 500 - 3%
        assertEquals(WatermarkAlign.RIGHT, l.align)

        val top = watermarkLayout(1000, 500, wm.copy(position = WatermarkPosition.TOP_LEFT))
        assertEquals(30f, top.x, 1e-4f)
        assertEquals(27.25f, top.baselineY, 1e-3f)
        assertEquals(WatermarkAlign.LEFT, top.align)

        val center = watermarkLayout(1000, 500, wm.copy(position = WatermarkPosition.CENTER))
        assertEquals(500f, center.x, 1e-4f)
        assertEquals(WatermarkAlign.CENTER, center.align)
    }

    @Test fun `watermarkLayout enforces the 8px minimum text size`() {
        val wm = WatermarkSettings(enabled = true, text = "H2W",
            position = WatermarkPosition.BOTTOM_RIGHT, sizeFrac = 0.02f)
        assertEquals(8f, watermarkLayout(200, 100, wm).textPx, 0f) // 2px -> 8px floor
    }
}
