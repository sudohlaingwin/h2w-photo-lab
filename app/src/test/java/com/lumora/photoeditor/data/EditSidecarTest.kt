package com.lumora.photoeditor.data

import com.lumora.photoeditor.engine.*
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

/**
 * First-ever executed verification of the EditState JSON sidecar round-trip
 * — the exact encode/decode contract EditStore.save/load rely on for every
 * photo's non-destructive edits, plus the Preset and EditSnapshot envelopes
 * that carry EditState inside PresetRepository and SnapshotStore.
 *
 * The Gradle unit-test runner is broken in this environment, so these run on
 * the standalone kotlinc 2.0.20 + kotlinx-serialization compiler-plugin
 * harness (hidden_files/sidecar-harness/run.sh) against the REAL
 * EditParams.kt / CropParams.kt sources.
 */
class EditSidecarTest {

    /** The exact Json config EditStore uses for sidecars. */
    private val sidecarJson = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** The exact Json config PresetRepository / SnapshotStore use. */
    private val envelopeJson = Json { ignoreUnknownKeys = true }

    // ------------------------------------------------------------------
    // A fully-exercised EditState: every serializable field off-default.
    // If any field is dropped or mangled by the codec, assertEquals fails.
    // ------------------------------------------------------------------

    private fun fullState(): EditState = EditState(
        light = LightParams(
            exposure = 1.25f, contrast = -20f, highlights = -65f, shadows = 40f,
            whites = 15f, blacks = -30f, temp = 12f, tint = -8f,
            clarity = 25f, texture = -10f, dehaze = 18f,
            toneCurve = ToneCurve(listOf(CurvePoint(0f, 0f), CurvePoint(0.3f, 0.35f), CurvePoint(1f, 1f))),
            redCurve = ToneCurve(listOf(CurvePoint(0f, 0.05f), CurvePoint(1f, 0.95f))),
            greenCurve = ToneCurve(),
            blueCurve = ToneCurve(listOf(CurvePoint(0f, 0f), CurvePoint(0.7f, 0.6f), CurvePoint(1f, 1f))),
        ),
        color = ColorParams(
            vibrance = 30f, saturation = -15f,
            mixer = mapOf(
                HueRange.RED.name to HslAdjust(hue = 10f, saturation = -20f, luminance = 5f),
                HueRange.BLUE.name to HslAdjust(hue = -15f, saturation = 25f, luminance = -10f),
            ),
            bw = true,
            bwMix = mapOf(HueRange.GREEN.name to 40f, HueRange.ORANGE.name to -25f),
        ),
        grading = GradingParams(
            shadows = GradeWheel(220f, 30f), midtones = GradeWheel(40f, 15f),
            highlights = GradeWheel(55f, 20f), global = GradeWheel(180f, 5f),
            balance = -20f, blending = 80f,
        ),
        calibration = CalibrationParams(
            shadowsTint = 10f,
            red = PrimaryAdjust(hue = 5f, sat = -5f),
            green = PrimaryAdjust(hue = -8f, sat = 12f),
            blue = PrimaryAdjust(hue = 15f, sat = -10f),
        ),
        detail = DetailParams(
            sharpening = 60f, radius = 1.5f, detail = 40f, masking = 70f,
            luminanceNr = 30f, luminanceDetail = 60f, colorNr = 40f,
            colorDetail = 55f, moire = 25f, deconv = 35f, deconvRadius = 1.2f,
        ),
        optics = OpticsParams(
            distortion = -12f, defringe = 50f,
            vignetteAmount = -30f, vignetteMidpoint = 40f, vignetteFeather = 60f,
            vignetteRoundness = 20f,
            grainAmount = 25f, grainSize = 40f, grainRoughness = 60f,
            lensBlurAmount = 45f, lensFocusX = 0.3f, lensFocusY = 0.7f,
            lensSize = 50f, lensFeather = 65f, lensShape = 1,
            fx = FxParams(leak = LightLeakStyle.SUNSET, leakAmount = 35f, dust = 20f, dustSeed = 42L),
        ),
        geometry = GeometryParams(
            rotate = 2.5f, rotate90 = 1, flipH = true, flipV = false,
            perspectiveV = -15f, perspectiveH = 10f, constrainCrop = false,
        ),
        masks = listOf(
            Mask(
                id = "m1", name = "Subject", enabled = true, inverted = false,
                amount = 0.8f, refineFeather = 0.3f, refineExpand = 0.1f,
                decontaminate = 0.5f, revision = 7L,
                components = listOf(
                    MaskComponent(
                        id = "c1", type = MaskType.BRUSH, op = MaskOp.ADD,
                        strokes = listOf(
                            BrushStroke(
                                points = listOf(BrushPoint(0.2f, 0.3f), BrushPoint(0.25f, 0.35f)),
                                size = 0.08f, feather = 0.5f, flow = 0.7f,
                                opacity = 0.9f, erase = false,
                                autoMask = true, autoMaskTolerance = 0.2f,
                            ),
                            BrushStroke(
                                points = listOf(BrushPoint(0.5f, 0.5f)),
                                size = 0.05f, feather = 0.3f, flow = 1f,
                                opacity = 1f, erase = true,
                            ),
                        ),
                        revision = 3L,
                    ),
                    MaskComponent(
                        id = "c2", type = MaskType.LINEAR_GRADIENT, op = MaskOp.SUBTRACT,
                        shape = listOf(0.1f, 0.1f, 0.9f, 0.9f), revision = 1L,
                    ),
                ),
                adjust = LocalAdjust(exposure = 0.5f, clarity = 20f, temp = -10f),
            ),
            Mask(
                id = "m2", name = "Sky", enabled = false, inverted = true,
                amount = 0.6f,
                components = listOf(
                    MaskComponent(
                        id = "c3", type = MaskType.RADIAL_GRADIENT, op = MaskOp.ADD,
                        shape = listOf(0.5f, 0.3f, 0.4f, 0.25f, 15f, 0.6f),
                    ),
                    MaskComponent(
                        id = "c4", type = MaskType.SKY_SELECT, op = MaskOp.INTERSECT,
                        aiKey = "sky:v1:abc123",
                    ),
                    MaskComponent(
                        id = "c5", type = MaskType.LUMINANCE_RANGE, op = MaskOp.ADD,
                        lumaLo = 0.6f, lumaHi = 1f, lumaFeather = 0.3f,
                    ),
                    MaskComponent(
                        id = "c6", type = MaskType.COLOR_RANGE, op = MaskOp.ADD,
                        colorR = 0.4f, colorG = 0.5f, colorB = 0.7f,
                        colorTolerance = 0.3f, colorFeather = 0.4f,
                        colorSamples = listOf(
                            ColorSample(0.42f, 0.52f, 0.68f),
                            ColorSample(0.35f, 0.45f, 0.75f),
                        ),
                    ),
                    MaskComponent(
                        id = "c7", type = MaskType.DEPTH_RANGE, op = MaskOp.SUBTRACT,
                        depthLo = 0.2f, depthHi = 0.8f, depthFeather = 0.5f,
                    ),
                ),
            ),
        ),
        retouch = RetouchState(
            spots = listOf(
                RetouchSpot(RetouchOp.HEAL, 0.3f, 0.4f, 0.35f, 0.45f, 0.05f),
                RetouchSpot(RetouchOp.CLONE, 0.6f, 0.6f, 0.65f, 0.65f, 0.08f, feather = 0.4f, strength = 0.7f),
                RetouchSpot(RetouchOp.SMOOTH_SKIN, 0.5f, 0.3f, 0.5f, 0.3f, 0.12f, textureKeep = 0.6f),
                RetouchSpot(RetouchOp.DODGE, 0.2f, 0.8f, 0.2f, 0.8f, 0.1f, range = RetouchRange.SHADOWS),
                RetouchSpot(RetouchOp.BURN, 0.8f, 0.2f, 0.8f, 0.2f, 0.1f, range = RetouchRange.HIGHLIGHTS),
                RetouchSpot(RetouchOp.RED_EYE, 0.45f, 0.25f, 0.45f, 0.25f, 0.03f),
            )
        ),
        crop = CropParams(left = 0.05f, top = 0.1f, right = 0.95f, bottom = 0.9f, aspect = 1.5f, locked = true),
        aiRetouch = AiRetouchParams(
            enabled = true,
            weights = listOf(0.1f, 0.4f, 0.3f, 0.2f),
            strength = 0.75f,
            clarityDelta = 12f, textureDelta = -8f, dehazeDelta = 10f,
            wbGains = listOf(1.05f, 1f, 0.95f),
        ),
        lookTransfer = LookTransferParams(
            srcStats = listOf(50f, 2f, -3f, 18f, 6f, 7f),
            refStats = listOf(55f, 5f, 8f, 20f, 9f, 6f),
            refName = "golden.jpg",
            strength = 0.6f,
        ),
    )

    // ------------------------------------------------------------------
    // Round-trip: nothing may be dropped or mangled by the codec.
    // ------------------------------------------------------------------

    @Test fun `full edit state survives sidecar round-trip`() {
        val state = fullState()
        val decoded = sidecarJson.decodeFromString<EditState>(sidecarJson.encodeToString(state))
        assertEquals(state, decoded)
    }

    @Test fun `default edit state survives sidecar round-trip`() {
        val decoded = sidecarJson.decodeFromString<EditState>(sidecarJson.encodeToString(EditState()))
        assertEquals(EditState(), decoded)
        assertTrue(decoded.isDefault())
    }

    @Test fun `encode then decode is idempotent on the wire bytes`() {
        val state = fullState()
        val once = sidecarJson.encodeToString(state)
        val twice = sidecarJson.encodeToString(sidecarJson.decodeFromString<EditState>(once))
        assertEquals(once, twice)
    }

    @Test fun `preset envelope survives round-trip`() {
        val preset = Preset(
            id = "p1", name = "My Look", category = "My Presets",
            state = fullState(), strength = 0.65f, builtIn = false, thumbnailSeed = 99L,
        )
        val decoded = envelopeJson.decodeFromString<Preset>(envelopeJson.encodeToString(preset))
        assertEquals(preset, decoded)
    }

    @Test fun `snapshot envelope survives round-trip`() {
        val snap = EditSnapshot(id = "s1", name = "Before sky fix", createdAt = 1729000000000L, state = fullState())
        val decoded = envelopeJson.decodeFromString<EditSnapshot>(envelopeJson.encodeToString(snap))
        assertEquals(snap, decoded)
    }

    @Test fun `sanitized paste state survives round-trip and stays sanitized`() {
        val sanitized = fullState().sanitizedForPaste()
        val decoded = sidecarJson.decodeFromString<EditState>(sidecarJson.encodeToString(sanitized))
        assertEquals(sanitized, decoded)
        // The paste contract itself must survive the wire, not just the data:
        assertTrue(decoded.retouch.spots.isEmpty())
        assertEquals(AiRetouchParams(), decoded.aiRetouch)
        assertEquals(LookTransferParams(), decoded.lookTransfer)
        assertTrue(decoded.masks.all { m ->
            m.components.all { it.type == MaskType.BRUSH || it.type == MaskType.LINEAR_GRADIENT || it.type == MaskType.RADIAL_GRADIENT }
        })
    }

    // ------------------------------------------------------------------
    // Evolution: sidecars written by older app versions must still load.
    // ------------------------------------------------------------------

    @Test fun `empty sidecar decodes to defaults`() {
        assertEquals(EditState(), sidecarJson.decodeFromString<EditState>("{}"))
    }

    @Test fun `old sidecar missing newer fields decodes with defaults`() {
        // Simulates a sidecar written before aiRetouch / lookTransfer / fx /
        // decontaminate / textureKeep / range / constrainCrop existed.
        val old = """{
            "light": {"exposure": 1.5, "toneCurve": {"points": [{"x": 0.0, "y": 0.0}, {"x": 1.0, "y": 1.0}]}},
            "color": {"vibrance": 20.0, "mixer": {"RED": {"hue": 5.0}}},
            "optics": {"grainAmount": 10.0},
            "geometry": {"rotate": 3.0},
            "retouch": {"spots": [{"op": "HEAL", "x": 0.3, "y": 0.4, "srcX": 0.35, "srcY": 0.45, "size": 0.05}]},
            "masks": [{"id": "m1", "name": "Old", "components": [{"id": "c1", "type": "BRUSH", "strokes": []}]}]
        }"""
        val decoded = sidecarJson.decodeFromString<EditState>(old)
        assertEquals(1.5f, decoded.light.exposure, 1e-6f)
        assertEquals(20f, decoded.color.vibrance, 1e-6f)
        assertEquals(5f, decoded.color.mixer["RED"]!!.hue, 1e-6f)
        assertEquals(10f, decoded.optics.grainAmount, 1e-6f)
        assertEquals(3f, decoded.geometry.rotate, 1e-6f)
        // Newer fields all fall back to their defaults:
        assertEquals(AiRetouchParams(), decoded.aiRetouch)
        assertEquals(LookTransferParams(), decoded.lookTransfer)
        assertEquals(FxParams(), decoded.optics.fx)
        assertEquals(0f, decoded.masks[0].decontaminate, 1e-6f)
        assertEquals(0.45f, decoded.retouch.spots[0].textureKeep, 1e-6f)
        assertEquals(RetouchRange.ALL, decoded.retouch.spots[0].range)
        assertTrue(decoded.geometry.constrainCrop)
        assertEquals(0.6f, decoded.retouch.spots[0].feather, 1e-6f)
        assertEquals(1f, decoded.retouch.spots[0].strength, 1e-6f)
    }

    @Test fun `unknown keys are ignored at every nesting level`() {
        val withJunk = """{
            "light": {"exposure": 0.5, "futureSlider": 99},
            "masks": [{"id": "m1", "name": "X", "mystery": true,
                       "components": [{"id": "c1", "type": "BRUSH", "nope": [1, 2]}]}],
            "retouch": {"spots": []},
            "aiRetouch": {"enabled": false, "whatever": "x"},
            "topLevelJunk": {"a": 1}
        }"""
        val decoded = sidecarJson.decodeFromString<EditState>(withJunk)
        assertEquals(0.5f, decoded.light.exposure, 1e-6f)
        assertEquals(1, decoded.masks.size)
        assertEquals(MaskType.BRUSH, decoded.masks[0].components[0].type)
    }

    // ------------------------------------------------------------------
    // Failure contract: what EditStore.load's catch(Exception) relies on.
    // ------------------------------------------------------------------

    @Test fun `malformed json throws so the store can fall back to fresh state`() {
        try {
            sidecarJson.decodeFromString<EditState>("{not json")
            fail("expected SerializationException")
        } catch (e: SerializationException) {
            // EditStore.load catches this and starts fresh — pinned.
        }
    }

    @Test fun `unknown enum value fails decode (forward-compat risk, pinned)`() {
        // If a future build ever removes or renames a MaskType/RetouchOp,
        // old sidecars carrying it fail the WHOLE decode (ignoreUnknownKeys
        // does not cover enum names) and EditStore.load falls back to a
        // fresh EditState — all edits for that photo are lost. Pinned so a
        // future change here is a conscious product decision, not an
        // accident. Mitigation if it ever matters: a lenient enum
        // deserializer that drops unknown components instead of failing.
        val future = """{"masks": [{"id": "m1", "name": "X",
            "components": [{"id": "c1", "type": "MAGIC_WAND_V2"}]}]}"""
        try {
            sidecarJson.decodeFromString<EditState>(future)
            fail("expected SerializationException for unknown enum")
        } catch (e: SerializationException) {
            // pinned current behavior
        }
    }

    // ------------------------------------------------------------------
    // Value fidelity: floats and nullables must not drift on the wire.
    // ------------------------------------------------------------------

    @Test fun `nullable and float edge values survive`() {
        val state = EditState(
            crop = CropParams(aspect = null, locked = true),
            aiRetouch = AiRetouchParams(wbGains = null),
            masks = listOf(
                Mask(id = "m", name = "n", components = listOf(
                    MaskComponent(id = "c", type = MaskType.SKY_SELECT, aiKey = null)
                ))
            ),
            light = LightParams(exposure = 0.1f, temp = -0.3f, dehaze = 99.99f),
            optics = OpticsParams(fx = FxParams(dustSeed = Long.MAX_VALUE)),
        )
        val decoded = sidecarJson.decodeFromString<EditState>(sidecarJson.encodeToString(state))
        assertEquals(state, decoded)
        assertNull(decoded.crop.aspect)
        assertNull(decoded.aiRetouch.wbGains)
        assertNull(decoded.masks[0].components[0].aiKey)
        assertEquals(Long.MAX_VALUE, decoded.optics.fx.dustSeed)
    }

    @Test fun `all mask types and retouch ops survive by name`() {
        for (t in MaskType.entries) {
            val json = """{"id": "m", "name": "n", "components": [{"id": "c", "type": "$t"}]}"""
            assertEquals(t, sidecarJson.decodeFromString<Mask>(json).components[0].type)
        }
        for (op in RetouchOp.entries) {
            val json = """{"spots": [{"op": "$op", "x": 0.1, "y": 0.1, "srcX": 0.2, "srcY": 0.2, "size": 0.05}]}"""
            assertEquals(op, sidecarJson.decodeFromString<RetouchState>(json).spots[0].op)
        }
        for (style in LightLeakStyle.entries) {
            val json = """{"leak": "$style"}"""
            assertEquals(style, sidecarJson.decodeFromString<FxParams>(json).leak)
        }
    }
}
