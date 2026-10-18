package com.lumora.photoeditor.data

import com.lumora.photoeditor.engine.*
import java.util.UUID

/**
 * Preset system. A preset is a full [EditState] delta; applying with
 * [strength] blends it over the current state. All built-ins modify real
 * editing parameters — never decorative overlays.
 */
object Presets {

    fun builtIn(): List<Preset> = listOf(
        Preset(
            id = "natural", name = "Natural", category = "Essential",
            state = EditState(
                light = LightParams(contrast = 10f, clarity = 5f),
                color = ColorParams(vibrance = 15f),
                detail = DetailParams(sharpening = 25f),
            ),
        ),
        Preset(
            id = "portrait", name = "Soft Portrait", category = "Portrait",
            state = EditState(
                light = LightParams(temp = 8f, clarity = -12f, texture = -8f, contrast = 5f),
                color = ColorParams(
                    vibrance = 10f,
                    mixer = mapOf(
                        HueRange.ORANGE.name to HslAdjust(saturation = 8f, luminance = 10f),
                        HueRange.RED.name to HslAdjust(luminance = 6f),
                    ),
                ),
                detail = DetailParams(sharpening = 20f),
                optics = OpticsParams(vignetteAmount = -15f, vignetteFeather = 60f),
            ),
        ),
        Preset(
            id = "cinematic", name = "Cinematic", category = "Cinematic",
            state = EditState(
                light = LightParams(
                    temp = -6f, contrast = 25f, highlights = -30f,
                    shadows = 15f, clarity = 10f,
                ),
                color = ColorParams(saturation = -10f, vibrance = 5f),
                grading = GradingParams(
                    shadows = GradeWheel(hueDeg = 195f, sat = 28f),
                    highlights = GradeWheel(hueDeg = 38f, sat = 22f),
                    balance = -10f, blending = 100f,
                ),
                optics = OpticsParams(grainAmount = 22f, grainSize = 30f),
            ),
        ),
        Preset(
            id = "film", name = "Faded Film", category = "Film",
            state = EditState(
                light = LightParams(contrast = -15f, blacks = 28f, highlights = -15f),
                color = ColorParams(saturation = -18f),
                grading = GradingParams(
                    shadows = GradeWheel(hueDeg = 30f, sat = 12f),
                    highlights = GradeWheel(hueDeg = 45f, sat = 14f),
                ),
                optics = OpticsParams(grainAmount = 38f, grainSize = 35f, grainRoughness = 60f),
            ),
        ),
        Preset(
            id = "cool", name = "Cool Tones", category = "Tones",
            state = EditState(
                light = LightParams(temp = -20f, tint = 6f),
                color = ColorParams(
                    mixer = mapOf(
                        HueRange.BLUE.name to HslAdjust(saturation = 12f),
                        HueRange.AQUA.name to HslAdjust(saturation = 10f),
                    ),
                ),
                grading = GradingParams(
                    midtones = GradeWheel(hueDeg = 210f, sat = 12f),
                    blending = 90f,
                ),
            ),
        ),
        Preset(
            id = "warm", name = "Warm Tones", category = "Tones",
            state = EditState(
                light = LightParams(temp = 22f, tint = -4f),
                color = ColorParams(vibrance = 12f),
                grading = GradingParams(
                    highlights = GradeWheel(hueDeg = 42f, sat = 18f),
                    shadows = GradeWheel(hueDeg = 28f, sat = 10f),
                ),
            ),
        ),
        Preset(
            id = "landscape", name = "Landscape Pop", category = "Landscape",
            state = EditState(
                light = LightParams(
                    clarity = 25f, dehaze = 15f, texture = 12f,
                    highlights = -15f, shadows = 10f,
                ),
                color = ColorParams(
                    vibrance = 25f,
                    mixer = mapOf(
                        HueRange.BLUE.name to HslAdjust(saturation = 15f),
                        HueRange.AQUA.name to HslAdjust(saturation = 12f),
                        HueRange.GREEN.name to HslAdjust(saturation = 12f, luminance = -6f),
                    ),
                ),
                detail = DetailParams(sharpening = 40f),
            ),
        ),
        Preset(
            id = "night", name = "Night Lift", category = "Night",
            state = EditState(
                light = LightParams(
                    exposure = 0.3f, shadows = 30f, blacks = -10f, temp = -8f, dehaze = 8f,
                ),
                color = ColorParams(vibrance = 8f),
                detail = DetailParams(luminanceNr = 30f, colorNr = 35f, sharpening = 15f),
                optics = OpticsParams(vignetteAmount = -20f),
            ),
        ),
        Preset(
            id = "street", name = "Street Contrast", category = "Street",
            state = EditState(
                light = LightParams(contrast = 30f, clarity = 15f, blacks = -12f, temp = 5f),
                color = ColorParams(saturation = -18f, vibrance = 5f),
                detail = DetailParams(sharpening = 30f),
                optics = OpticsParams(grainAmount = 25f, grainSize = 28f),
            ),
        ),
        Preset(
            id = "golden", name = "Golden Hour", category = "Tones",
            state = EditState(
                light = LightParams(temp = 30f, tint = -6f, exposure = 0.2f, highlights = -20f),
                color = ColorParams(
                    vibrance = 20f,
                    mixer = mapOf(
                        HueRange.ORANGE.name to HslAdjust(saturation = 15f, luminance = 8f),
                        HueRange.YELLOW.name to HslAdjust(saturation = 12f),
                    ),
                ),
                grading = GradingParams(
                    highlights = GradeWheel(hueDeg = 45f, sat = 25f),
                    shadows = GradeWheel(hueDeg = 25f, sat = 15f),
                    blending = 95f,
                ),
            ),
        ),
        Preset(
            id = "moody", name = "Moody Dark", category = "Cinematic",
            state = EditState(
                light = LightParams(
                    exposure = -0.4f, contrast = 35f, highlights = -40f,
                    shadows = -15f, blacks = -20f, clarity = 12f,
                ),
                color = ColorParams(saturation = -25f, vibrance = 8f),
                grading = GradingParams(
                    shadows = GradeWheel(hueDeg = 220f, sat = 20f),
                    midtones = GradeWheel(hueDeg = 200f, sat = 10f),
                    blending = 100f,
                ),
                optics = OpticsParams(vignetteAmount = -35f, vignetteFeather = 50f),
            ),
        ),
        Preset(
            id = "vintage", name = "Vintage", category = "Film",
            state = EditState(
                light = LightParams(contrast = -20f, highlights = -10f, shadows = 20f, blacks = 35f),
                color = ColorParams(saturation = -30f),
                grading = GradingParams(
                    shadows = GradeWheel(hueDeg = 35f, sat = 18f),
                    highlights = GradeWheel(hueDeg = 50f, sat = 20f),
                    blending = 100f,
                ),
                optics = OpticsParams(
                    grainAmount = 45f, grainSize = 40f, grainRoughness = 70f,
                    vignetteAmount = -25f,
                ),
            ),
        ),
        Preset(
            id = "bw_classic", name = "B&W Classic", category = "Black & White",
            state = EditState(
                light = LightParams(contrast = 15f, clarity = 10f),
                color = ColorParams(bw = true),
                detail = DetailParams(sharpening = 25f),
            ),
        ),
        Preset(
            id = "bw_drama", name = "B&W Drama", category = "Black & White",
            state = EditState(
                light = LightParams(
                    contrast = 45f, highlights = -25f, shadows = -10f,
                    blacks = -25f, whites = 15f, clarity = 20f,
                ),
                color = ColorParams(bw = true),
                detail = DetailParams(sharpening = 35f),
                optics = OpticsParams(vignetteAmount = -20f),
            ),
        ),
        Preset(
            id = "pastel", name = "Soft Pastel", category = "Tones",
            state = EditState(
                light = LightParams(
                    contrast = -25f, highlights = 15f, shadows = 25f,
                    blacks = 20f, clarity = -15f,
                ),
                color = ColorParams(saturation = -15f, vibrance = 10f),
                grading = GradingParams(
                    highlights = GradeWheel(hueDeg = 340f, sat = 10f),
                    shadows = GradeWheel(hueDeg = 200f, sat = 8f),
                    blending = 80f,
                ),
            ),
        ),
        Preset(
            id = "hdr", name = "HDR Punch", category = "Landscape",
            state = EditState(
                light = LightParams(
                    exposure = 0.1f, contrast = 20f, highlights = -45f,
                    shadows = 40f, whites = -10f, blacks = -10f,
                    clarity = 30f, dehaze = 20f, texture = 15f,
                ),
                color = ColorParams(vibrance = 30f, saturation = 5f),
                detail = DetailParams(sharpening = 45f),
            ),
        ),
        Preset(
            id = "sunset", name = "Vivid Sunset", category = "Landscape",
            state = EditState(
                light = LightParams(temp = 18f, contrast = 20f, highlights = -25f),
                color = ColorParams(
                    vibrance = 35f, saturation = 10f,
                    mixer = mapOf(
                        HueRange.RED.name to HslAdjust(saturation = 20f),
                        HueRange.ORANGE.name to HslAdjust(saturation = 18f),
                        HueRange.MAGENTA.name to HslAdjust(saturation = 15f),
                    ),
                ),
                grading = GradingParams(
                    highlights = GradeWheel(hueDeg = 20f, sat = 30f),
                    blending = 90f,
                ),
            ),
        ),
        Preset(
            id = "forest", name = "Deep Forest", category = "Landscape",
            state = EditState(
                light = LightParams(
                    temp = -10f, contrast = 15f, highlights = -20f,
                    shadows = 15f, dehaze = 10f, clarity = 15f,
                ),
                color = ColorParams(
                    vibrance = 20f,
                    mixer = mapOf(
                        HueRange.GREEN.name to HslAdjust(saturation = 20f, luminance = -8f),
                        HueRange.YELLOW.name to HslAdjust(hue = -10f, saturation = 10f),
                    ),
                ),
                detail = DetailParams(sharpening = 30f),
            ),
        ),
        Preset(
            id = "ocean", name = "Ocean Blue", category = "Tones",
            state = EditState(
                light = LightParams(temp = -25f, tint = 8f, contrast = 12f, dehaze = 12f),
                color = ColorParams(
                    vibrance = 18f,
                    mixer = mapOf(
                        HueRange.BLUE.name to HslAdjust(saturation = 20f, luminance = -5f),
                        HueRange.AQUA.name to HslAdjust(saturation = 18f),
                    ),
                ),
                grading = GradingParams(
                    shadows = GradeWheel(hueDeg = 210f, sat = 18f),
                    highlights = GradeWheel(hueDeg = 195f, sat = 12f),
                    blending = 95f,
                ),
            ),
        ),
        Preset(
            id = "autumn", name = "Autumn Warmth", category = "Tones",
            state = EditState(
                light = LightParams(temp = 25f, contrast = 12f, highlights = -15f),
                color = ColorParams(
                    vibrance = 22f,
                    mixer = mapOf(
                        HueRange.ORANGE.name to HslAdjust(saturation = 18f),
                        HueRange.YELLOW.name to HslAdjust(saturation = 15f),
                        HueRange.RED.name to HslAdjust(saturation = 12f),
                        HueRange.GREEN.name to HslAdjust(hue = 15f, saturation = -10f),
                    ),
                ),
            ),
        ),
        Preset(
            id = "winter", name = "Winter Frost", category = "Tones",
            state = EditState(
                light = LightParams(
                    temp = -30f, tint = 5f, exposure = 0.25f,
                    highlights = 10f, shadows = 10f, clarity = -8f,
                ),
                color = ColorParams(saturation = -12f, vibrance = 5f),
                grading = GradingParams(
                    highlights = GradeWheel(hueDeg = 210f, sat = 10f),
                    shadows = GradeWheel(hueDeg = 220f, sat = 14f),
                    blending = 85f,
                ),
            ),
        ),
        // ---- Creative Effects (new) ----
        Preset(
            id = "sunset_leak", name = "Sunset Leak", category = "Effects",
            state = EditState(
                light = LightParams(
                    temp = 25f, exposure = 0.3f, highlights = -20f,
                    blacks = 15f, contrast = -10f,
                ),
                color = ColorParams(vibrance = 25f, saturation = 10f),
                grading = GradingParams(
                    highlights = GradeWheel(hueDeg = 25f, sat = 45f),
                    midtones = GradeWheel(hueDeg = 30f, sat = 20f),
                    shadows = GradeWheel(hueDeg = 15f, sat = 15f),
                    blending = 100f,
                ),
                optics = OpticsParams(
                    vignetteAmount = -25f, vignetteFeather = 70f,
                    fx = FxParams(leak = LightLeakStyle.SUNSET, leakAmount = 55f),
                ),
            ),
        ),
        Preset(
            id = "golden_glow", name = "Golden Glow", category = "Effects",
            state = EditState(
                light = LightParams(
                    temp = 30f, tint = 8f, exposure = 0.4f,
                    highlights = -15f, shadows = 20f, clarity = -10f,
                ),
                color = ColorParams(
                    vibrance = 20f,
                    mixer = mapOf(
                        HueRange.YELLOW.name to HslAdjust(saturation = 15f, luminance = 10f),
                        HueRange.ORANGE.name to HslAdjust(saturation = 12f),
                    ),
                ),
                optics = OpticsParams(
                    vignetteAmount = -20f,
                    fx = FxParams(leak = LightLeakStyle.GOLDEN, leakAmount = 50f),
                ),
            ),
        ),
        Preset(
            id = "noir", name = "Noir", category = "Effects",
            state = EditState(
                light = LightParams(
                    contrast = 45f, highlights = -40f, shadows = -20f,
                    blacks = -30f, whites = 20f, clarity = 20f,
                ),
                color = ColorParams(bw = true),
                detail = DetailParams(sharpening = 30f),
                optics = OpticsParams(
                    grainAmount = 30f, grainSize = 35f,
                    vignetteAmount = -35f, vignetteFeather = 50f,
                ),
            ),
        ),
        Preset(
            id = "vintage_dust", name = "Vintage Dust", category = "Effects",
            state = EditState(
                light = LightParams(
                    contrast = -15f, blacks = 25f, highlights = -25f,
                    temp = 12f, clarity = -15f, dehaze = -10f,
                ),
                color = ColorParams(saturation = -25f, vibrance = 10f),
                grading = GradingParams(
                    shadows = GradeWheel(hueDeg = 40f, sat = 18f),
                    highlights = GradeWheel(hueDeg = 45f, sat = 12f),
                    blending = 90f,
                ),
                optics = OpticsParams(
                    grainAmount = 40f, grainSize = 40f, grainRoughness = 60f,
                    vignetteAmount = -30f,
                    fx = FxParams(dust = 45f),
                ),
            ),
        ),
        // ---- Thin-category fillers (Portrait / Night / Street / Essential / Cinematic) ----
        Preset(
            id = "studio_light", name = "Studio Light", category = "Portrait",
            state = EditState(
                light = LightParams(
                    exposure = 0.15f, contrast = 8f, highlights = -20f,
                    shadows = 15f, clarity = -10f, texture = -5f,
                ),
                color = ColorParams(
                    vibrance = 12f,
                    mixer = mapOf(
                        HueRange.ORANGE.name to HslAdjust(saturation = 8f, luminance = 8f),
                        HueRange.RED.name to HslAdjust(luminance = 5f),
                    ),
                ),
                detail = DetailParams(sharpening = 20f),
                optics = OpticsParams(vignetteAmount = -10f, vignetteFeather = 60f),
            ),
        ),
        Preset(
            id = "matte_portrait", name = "Matte Portrait", category = "Portrait",
            state = EditState(
                light = LightParams(
                    contrast = -12f, blacks = 22f, highlights = -10f, clarity = -8f,
                ),
                color = ColorParams(
                    saturation = -15f, vibrance = 8f,
                    mixer = mapOf(
                        HueRange.ORANGE.name to HslAdjust(luminance = 6f),
                    ),
                ),
                grading = GradingParams(
                    shadows = GradeWheel(hueDeg = 35f, sat = 12f),
                    highlights = GradeWheel(hueDeg = 50f, sat = 10f),
                    blending = 90f,
                ),
                optics = OpticsParams(grainAmount = 20f, grainSize = 30f),
            ),
        ),
        Preset(
            id = "neon_nights", name = "Neon Nights", category = "Night",
            state = EditState(
                light = LightParams(
                    exposure = 0.1f, shadows = 25f, blacks = -15f,
                    temp = -15f, clarity = 12f, dehaze = 10f,
                ),
                color = ColorParams(vibrance = 18f),
                grading = GradingParams(
                    shadows = GradeWheel(hueDeg = 200f, sat = 35f),
                    midtones = GradeWheel(hueDeg = 210f, sat = 15f),
                    highlights = GradeWheel(hueDeg = 35f, sat = 30f),
                    blending = 100f,
                ),
                detail = DetailParams(
                    luminanceNr = 25f, colorNr = 30f, sharpening = 15f),
                optics = OpticsParams(vignetteAmount = -25f),
            ),
        ),
        Preset(
            id = "urban_fade", name = "Urban Fade", category = "Street",
            state = EditState(
                light = LightParams(
                    contrast = -8f, blacks = 30f, highlights = -15f, clarity = 10f,
                ),
                color = ColorParams(saturation = -22f, vibrance = 5f),
                grading = GradingParams(
                    shadows = GradeWheel(hueDeg = 220f, sat = 12f),
                    blending = 85f,
                ),
                detail = DetailParams(sharpening = 30f),
                optics = OpticsParams(grainAmount = 30f, grainSize = 32f),
            ),
        ),
        Preset(
            id = "true_color", name = "True Color", category = "Essential",
            state = EditState(
                light = LightParams(contrast = 8f, clarity = 8f, dehaze = 5f),
                color = ColorParams(vibrance = 10f),
                detail = DetailParams(sharpening = 25f),
            ),
        ),
        Preset(
            id = "teal_orange", name = "Teal & Orange", category = "Cinematic",
            state = EditState(
                light = LightParams(
                    contrast = 30f, highlights = -25f, shadows = 10f, clarity = 12f,
                ),
                color = ColorParams(
                    saturation = -8f, vibrance = 10f,
                    mixer = mapOf(
                        HueRange.ORANGE.name to HslAdjust(saturation = 12f),
                        HueRange.BLUE.name to HslAdjust(saturation = 12f),
                    ),
                ),
                grading = GradingParams(
                    shadows = GradeWheel(hueDeg = 195f, sat = 30f),
                    highlights = GradeWheel(hueDeg = 30f, sat = 28f),
                    blending = 100f,
                ),
                optics = OpticsParams(grainAmount = 20f, grainSize = 30f),
            ),
        ),
        Preset(
            id = "bright_air", name = "Bright & Airy", category = "Portrait",
            state = EditState(
                light = LightParams(
                    exposure = 0.35f, contrast = -5f, highlights = -20f,
                    shadows = 35f, whites = 15f, blacks = 10f,
                    temp = 8f, tint = 4f, clarity = -10f, texture = -5f,
                ),
                color = ColorParams(
                    vibrance = 12f, saturation = 3f,
                    mixer = mapOf(
                        HueRange.ORANGE.name to HslAdjust(saturation = 6f, luminance = 8f),
                        HueRange.RED.name to HslAdjust(luminance = 5f),
                    ),
                ),
                detail = DetailParams(sharpening = 20f),
            ),
        ),
        Preset(
            id = "chrome", name = "Classic Chrome", category = "Film",
            state = EditState(
                light = LightParams(
                    contrast = 20f, highlights = -30f, shadows = 10f,
                    blacks = -10f, clarity = 12f,
                ),
                color = ColorParams(saturation = -15f, vibrance = 8f),
                grading = GradingParams(
                    shadows = GradeWheel(hueDeg = 30f, sat = 14f),
                    highlights = GradeWheel(hueDeg = 45f, sat = 12f),
                    blending = 95f,
                ),
                detail = DetailParams(sharpening = 28f),
                optics = OpticsParams(grainAmount = 30f, grainSize = 35f, grainRoughness = 55f),
            ),
        ),
        Preset(
            id = "bw_infra", name = "B&W Infrared", category = "Black & White",
            state = EditState(
                light = LightParams(
                    contrast = 40f, highlights = -30f, shadows = -15f,
                    whites = 20f, blacks = -20f, clarity = 25f,
                ),
                color = ColorParams(bw = true),
                detail = DetailParams(sharpening = 30f),
                optics = OpticsParams(
                    grainAmount = 25f, grainSize = 30f, vignetteAmount = -15f,
                ),
            ),
        ),
        Preset(
            id = "sunset_glow", name = "Sunset Glow", category = "Tones",
            state = EditState(
                light = LightParams(
                    exposure = 0.15f, contrast = 12f, highlights = -20f,
                    shadows = 20f, temp = 25f, tint = 5f, clarity = 8f,
                ),
                color = ColorParams(
                    vibrance = 12f,
                    mixer = mapOf(
                        HueRange.ORANGE.name to HslAdjust(saturation = 15f, luminance = 8f),
                        HueRange.RED.name to HslAdjust(saturation = 10f),
                        HueRange.YELLOW.name to HslAdjust(saturation = 8f),
                    ),
                ),
                grading = GradingParams(
                    shadows = GradeWheel(hueDeg = 20f, sat = 12f),
                    highlights = GradeWheel(hueDeg = 35f, sat = 25f),
                    blending = 90f,
                ),
                detail = DetailParams(sharpening = 22f),
            ),
        ),
        Preset(
            id = "alpine", name = "Alpine Crisp", category = "Landscape",
            state = EditState(
                light = LightParams(
                    temp = -12f, contrast = 15f, highlights = -30f,
                    shadows = 20f, clarity = 20f, texture = 15f, dehaze = 25f,
                ),
                color = ColorParams(
                    vibrance = 22f, saturation = 5f,
                    mixer = mapOf(
                        HueRange.BLUE.name to HslAdjust(saturation = 10f),
                        HueRange.AQUA.name to HslAdjust(saturation = 8f),
                    ),
                ),
                detail = DetailParams(sharpening = 30f),
            ),
        ),
        Preset(
            id = "neon_noir", name = "Neon Noir", category = "Night",
            state = EditState(
                light = LightParams(
                    exposure = 0.2f, contrast = 28f, highlights = -20f,
                    shadows = 10f, blacks = -20f, temp = -8f, tint = 8f,
                    clarity = 10f,
                ),
                color = ColorParams(vibrance = 15f, saturation = -5f),
                grading = GradingParams(
                    shadows = GradeWheel(hueDeg = 280f, sat = 25f),
                    midtones = GradeWheel(hueDeg = 230f, sat = 10f),
                    highlights = GradeWheel(hueDeg = 190f, sat = 25f),
                    blending = 100f,
                ),
                detail = DetailParams(sharpening = 25f, luminanceNr = 20f, colorNr = 25f),
                optics = OpticsParams(grainAmount = 25f, grainSize = 30f),
            ),
        ),
    )

    fun custom(name: String, state: EditState, category: String = "My Presets"): Preset =
        Preset(
            id = "custom-" + UUID.randomUUID().toString(),
            name = name, category = category, state = state,
            builtIn = false, thumbnailSeed = System.currentTimeMillis(),
        )

    /**
     * Applies [preset] over [current] with [strength] 0..1.
     * Numeric parameters interpolate; structural ones (curves, mixer maps,
     * masks) switch to the preset's once strength passes one half.
     */
    fun apply(current: EditState, preset: Preset, strength: Float): EditState {
        val t = strength.coerceIn(0f, 1f)
        return blend(current, preset.state, t)
    }

    fun blend(a: EditState, b: EditState, t: Float): EditState {
        if (t <= 0f) return a
        if (t >= 1f) return b
        fun f(x: Float, y: Float) = x + (y - x) * t
        val useB = t >= 0.5f
        return EditState(
            light = LightParams(
                exposure = f(a.light.exposure, b.light.exposure),
                contrast = f(a.light.contrast, b.light.contrast),
                highlights = f(a.light.highlights, b.light.highlights),
                shadows = f(a.light.shadows, b.light.shadows),
                whites = f(a.light.whites, b.light.whites),
                blacks = f(a.light.blacks, b.light.blacks),
                temp = f(a.light.temp, b.light.temp),
                tint = f(a.light.tint, b.light.tint),
                clarity = f(a.light.clarity, b.light.clarity),
                texture = f(a.light.texture, b.light.texture),
                dehaze = f(a.light.dehaze, b.light.dehaze),
                // Tone curves interpolate smoothly at partial strength (blendToneCurve
                // resamples both curves to one grid and lerps) instead of the
                // whole-curve useB switch: a 40% preset strength must fade the
                // curve in, not silently drop it until 50% then snap it on.
                toneCurve = blendToneCurve(a.light.toneCurve, b.light.toneCurve, t),
                redCurve = blendToneCurve(a.light.redCurve, b.light.redCurve, t),
                greenCurve = blendToneCurve(a.light.greenCurve, b.light.greenCurve, t),
                blueCurve = blendToneCurve(a.light.blueCurve, b.light.blueCurve, t),
            ),
            color = ColorParams(
                vibrance = f(a.color.vibrance, b.color.vibrance),
                saturation = f(a.color.saturation, b.color.saturation),
                mixer = blendMixer(a.color.mixer, b.color.mixer, t),
                // Structural switch: a partial blend must not silently drop
                // the B&W conversion (cycle 32) — useB matches masks/geometry.
                bw = if (useB) b.color.bw else a.color.bw,
                bwMix = blendFloatMap(a.color.bwMix, b.color.bwMix, t),
            ),
            grading = GradingParams(
                shadows = blendWheel(a.grading.shadows, b.grading.shadows, t),
                midtones = blendWheel(a.grading.midtones, b.grading.midtones, t),
                highlights = blendWheel(a.grading.highlights, b.grading.highlights, t),
                global = blendWheel(a.grading.global, b.grading.global, t),
                balance = f(a.grading.balance, b.grading.balance),
                blending = f(a.grading.blending, b.grading.blending),
            ),
            // Camera calibration (primaries + shadows tint) was missing from
            // blend entirely: the EditState() default (neutral zeros) was
            // used, so any preset applied at partial strength silently
            // discarded both the preset's and the current photo's calibration
            // (same bug class as the cycle-57 B&W/lens-blur fix). All seven
            // calibration fields are plain numeric shifts, so they
            // interpolate like the other sliders.
            calibration = CalibrationParams(
                shadowsTint = f(a.calibration.shadowsTint, b.calibration.shadowsTint),
                red = blendPrimary(a.calibration.red, b.calibration.red, t),
                green = blendPrimary(a.calibration.green, b.calibration.green, t),
                blue = blendPrimary(a.calibration.blue, b.calibration.blue, t),
            ),
            detail = DetailParams(
                sharpening = f(a.detail.sharpening, b.detail.sharpening),
                radius = f(a.detail.radius, b.detail.radius),
                detail = f(a.detail.detail, b.detail.detail),
                masking = f(a.detail.masking, b.detail.masking),
                luminanceNr = f(a.detail.luminanceNr, b.detail.luminanceNr),
                luminanceDetail = f(a.detail.luminanceDetail, b.detail.luminanceDetail),
                colorNr = f(a.detail.colorNr, b.detail.colorNr),
                colorDetail = f(a.detail.colorDetail, b.detail.colorDetail),
                moire = f(a.detail.moire, b.detail.moire),
                // Deconvolution sharpening (pro-spec Phase 3): partial
                // strength must fade these like every other detail field.
                deconv = f(a.detail.deconv, b.detail.deconv),
                deconvRadius = f(a.detail.deconvRadius, b.detail.deconvRadius),
            ),
            optics = OpticsParams(
                distortion = f(a.optics.distortion, b.optics.distortion),
                defringe = f(a.optics.defringe, b.optics.defringe),
                vignetteAmount = f(a.optics.vignetteAmount, b.optics.vignetteAmount),
                vignetteMidpoint = f(a.optics.vignetteMidpoint, b.optics.vignetteMidpoint),
                vignetteFeather = f(a.optics.vignetteFeather, b.optics.vignetteFeather),
                vignetteRoundness = f(a.optics.vignetteRoundness, b.optics.vignetteRoundness),
                grainAmount = f(a.optics.grainAmount, b.optics.grainAmount),
                grainSize = f(a.optics.grainSize, b.optics.grainSize),
                grainRoughness = f(a.optics.grainRoughness, b.optics.grainRoughness),
                // Lens blur (cycle 2) was missing from blend: partial-strength
                // preset application (incl. user-saved presets) silently
                // reset it to neutral. Numerics interpolate; shape switches.
                lensBlurAmount = f(a.optics.lensBlurAmount, b.optics.lensBlurAmount),
                lensFocusX = f(a.optics.lensFocusX, b.optics.lensFocusX),
                lensFocusY = f(a.optics.lensFocusY, b.optics.lensFocusY),
                lensSize = f(a.optics.lensSize, b.optics.lensSize),
                lensFeather = f(a.optics.lensFeather, b.optics.lensFeather),
                lensShape = if (useB) b.optics.lensShape else a.optics.lensShape,
                // Creative effects: numerics interpolate; the leak style and
                // dust seed are structural switches (same convention as
                // lensShape / B&W above).
                fx = FxParams(
                    leak = if (useB) b.optics.fx.leak else a.optics.fx.leak,
                    leakAmount = f(a.optics.fx.leakAmount, b.optics.fx.leakAmount),
                    dust = f(a.optics.fx.dust, b.optics.fx.dust),
                    dustSeed = if (useB) b.optics.fx.dustSeed else a.optics.fx.dustSeed,
                ),
            ),
            geometry = if (useB) b.geometry else a.geometry,
            masks = if (useB) b.masks else a.masks,
            retouch = if (useB) b.retouch else a.retouch,
            crop = if (useB) b.crop else a.crop,
            // Look transfer is a per-photo-pair grade (statistics baked for
            // THIS photo + its reference): presets never carry it, and
            // applying a preset must neither wipe the current photo's match
            // nor fabricate one from the preset's photo. Always keep a's.
            lookTransfer = a.lookTransfer,
        )
    }

    private fun blendWheel(a: GradeWheel, b: GradeWheel, t: Float): GradeWheel {
        // interpolate hue along the shortest arc
        var dh = (b.hueDeg - a.hueDeg) % 360f
        if (dh > 180f) dh -= 360f
        if (dh < -180f) dh += 360f
        val h = (a.hueDeg + dh * t + 360f) % 360f
        return GradeWheel(h, a.sat + (b.sat - a.sat) * t)
    }

    private fun blendPrimary(a: PrimaryAdjust, b: PrimaryAdjust, t: Float): PrimaryAdjust =
        // PrimaryAdjust fields are -100..100 shift amounts (not wrapping
        // angles), so plain linear interpolation is correct.
        PrimaryAdjust(
            hue = a.hue + (b.hue - a.hue) * t,
            sat = a.sat + (b.sat - a.sat) * t,
        )

    private fun blendFloatMap(
        a: Map<String, Float>, b: Map<String, Float>, t: Float,
    ): Map<String, Float> {
        // Per-key numeric blend for String->Float maps (B&W channel mix).
        // Missing keys read as 0 (the neutral value), like blendMixer does
        // with HslAdjust() for the HSL mixer.
        val keys = a.keys + b.keys
        return keys.associateWith { k ->
            val x = a[k] ?: 0f
            val y = b[k] ?: 0f
            x + (y - x) * t
        }
    }

    private fun blendMixer(
        a: Map<String, HslAdjust>, b: Map<String, HslAdjust>, t: Float,
    ): Map<String, HslAdjust> {
        val keys = a.keys + b.keys
        return keys.associateWith { k ->
            val x = a[k] ?: HslAdjust()
            val y = b[k] ?: HslAdjust()
            HslAdjust(
                x.hue + (y.hue - x.hue) * t,
                x.saturation + (y.saturation - x.saturation) * t,
                x.luminance + (y.luminance - x.luminance) * t,
            )
        }
    }
}
