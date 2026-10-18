package com.lumora.photoeditor.engine

/**
 * Pro Recipes — professional editing workflows encoded as intelligent
 * multi-step pipelines. Based on widely-taught professional techniques
 * (Lightroom portrait/landscape workflows, frequency-separation retouching,
 * color-grading theory).
 *
 * Unlike basic Auto Enhance (single sliders), each recipe applies a
 * COORDINATED set of adjustments + auto-masks, the way a pro would work:
 * analyze → mask → correct → grade → finish.
 *
 * Scene detection comes from AutoEnhance; each recipe is tuned for its scene.
 */
object ProRecipes {

    /**
     * Pro Portrait recipe:
     * 1. Flattering warm WB (not orange)
     * 2. Soft open shadows, gentle highlights (skin-friendly)
     * 3. Skin-tone-aware vibrance (boost background, protect skin)
     * 4. Subtle clarity (not on skin)
     * 5. Returns mask specs for skin softening + eye enhancement
     *    (caller creates the actual masks via AutoRetouch.skinMask)
     */
    data class PortraitRecipe(
        val exposure: Float = 0.25f,
        val contrast: Float = 12f,
        val highlights: Float = -25f,
        val shadows: Float = 30f,
        val whites: Float = 10f,
        val blacks: Float = -8f,
        val temp: Float = 6f,      // Warm but natural
        val tint: Float = 3f,      // Hint of magenta for healthy skin
        val vibrance: Float = 18f,
        val saturation: Float = -5f, // Slight desat for filmic skin
        val clarity: Float = 6f,    // Low — skin-friendly
        val texture: Float = -8f,   // Soften micro-texture globally
        // Mask-based steps (0..1 strength, 0 = skip)
        val skinSoftenStrength: Float = 0.55f,
        val eyeEnhanceStrength: Float = 0.5f,
        val teethWhitenStrength: Float = 0.3f,
        val vignetteStrength: Float = 0.25f,
    )

    /**
     * Pro Landscape recipe:
     * 1. Dehaze for atmosphere and depth
     * 2. Sky enhancement (via luminance mask — caller applies)
     * 3. Strong vibrance + controlled saturation
     * 4. Clarity + texture for detail
     * 5. Punchy but natural contrast
     */
    data class LandscapeRecipe(
        val exposure: Float = 0.1f,
        val contrast: Float = 18f,
        val highlights: Float = -35f,
        val shadows: Float = 25f,
        val whites: Float = 15f,
        val blacks: Float = -12f,
        val temp: Float = -2f,
        val tint: Float = 0f,
        val vibrance: Float = 30f,
        val saturation: Float = 8f,
        val clarity: Float = 18f,
        val texture: Float = 12f,
        val dehaze: Float = 20f,
        // Sky mask enhancement (caller creates luminance/sky mask)
        val skyBoostStrength: Float = 0.6f,
        val foregroundWarmth: Float = 0.2f,
    )

    /**
     * Pro Night recipe: preserve mood, control noise, cinematic grade.
     */
    data class NightRecipe(
        val exposure: Float = 0.35f,
        val contrast: Float = 15f,
        val highlights: Float = -20f,
        val shadows: Float = 20f,
        val whites: Float = 5f,
        val blacks: Float = -15f,  // Deep blacks for mood
        val temp: Float = -5f,      // Cool cinematic
        val tint: Float = 4f,
        val vibrance: Float = 15f,
        val saturation: Float = -8f,
        val clarity: Float = 10f,
        val dehaze: Float = 8f,
    )

    /**
     * Pro Backlit recipe: rescue subject, control background.
     */
    data class BacklitRecipe(
        val exposure: Float = 0.4f,
        val contrast: Float = 10f,
        val highlights: Float = -55f, // Strong highlight recovery
        val shadows: Float = 55f,     // Strong shadow lift
        val whites: Float = -10f,
        val blacks: Float = 5f,
        val temp: Float = 8f,  // Warm the subject
        val tint: Float = 2f,
        val vibrance: Float = 22f,
        val clarity: Float = 12f,
        val dehaze: Float = 10f,
    )

    /**
     * Applies a portrait pro recipe as EditState deltas.
     * Returns the modified EditState (caller handles masks separately).
     */
    fun applyPortrait(base: EditState, r: PortraitRecipe = PortraitRecipe()): EditState {
        return base.copy(
            light = base.light.copy(
                exposure = (base.light.exposure + r.exposure).coerceIn(-5f, 5f),
                contrast = (base.light.contrast + r.contrast).coerceIn(-100f, 100f),
                highlights = (base.light.highlights + r.highlights).coerceIn(-100f, 100f),
                shadows = (base.light.shadows + r.shadows).coerceIn(-100f, 100f),
                whites = (base.light.whites + r.whites).coerceIn(-100f, 100f),
                blacks = (base.light.blacks + r.blacks).coerceIn(-100f, 100f),
                temp = (base.light.temp + r.temp).coerceIn(-100f, 100f),
                tint = (base.light.tint + r.tint).coerceIn(-100f, 100f),
                clarity = (base.light.clarity + r.clarity).coerceIn(-100f, 100f),
                texture = (base.light.texture + r.texture).coerceIn(-100f, 100f),
            ),
            color = base.color.copy(
                vibrance = (base.color.vibrance + r.vibrance).coerceIn(-100f, 100f),
                saturation = (base.color.saturation + r.saturation).coerceIn(-100f, 100f),
            ),
            optics = base.optics.copy(
                vignetteAmount = (base.optics.vignetteAmount - r.vignetteStrength * 40f)
                    .coerceIn(-100f, 100f),
            ),
        )
    }

    fun applyLandscape(base: EditState, r: LandscapeRecipe = LandscapeRecipe()): EditState {
        return base.copy(
            light = base.light.copy(
                exposure = (base.light.exposure + r.exposure).coerceIn(-5f, 5f),
                contrast = (base.light.contrast + r.contrast).coerceIn(-100f, 100f),
                highlights = (base.light.highlights + r.highlights).coerceIn(-100f, 100f),
                shadows = (base.light.shadows + r.shadows).coerceIn(-100f, 100f),
                whites = (base.light.whites + r.whites).coerceIn(-100f, 100f),
                blacks = (base.light.blacks + r.blacks).coerceIn(-100f, 100f),
                temp = (base.light.temp + r.temp).coerceIn(-100f, 100f),
                tint = (base.light.tint + r.tint).coerceIn(-100f, 100f),
                clarity = (base.light.clarity + r.clarity).coerceIn(-100f, 100f),
                texture = (base.light.texture + r.texture).coerceIn(-100f, 100f),
                dehaze = (base.light.dehaze + r.dehaze).coerceIn(-100f, 100f),
            ),
            color = base.color.copy(
                vibrance = (base.color.vibrance + r.vibrance).coerceIn(-100f, 100f),
                saturation = (base.color.saturation + r.saturation).coerceIn(-100f, 100f),
            ),
        )
    }

    fun applyNight(base: EditState, r: NightRecipe = NightRecipe()): EditState {
        return base.copy(
            light = base.light.copy(
                exposure = (base.light.exposure + r.exposure).coerceIn(-5f, 5f),
                contrast = (base.light.contrast + r.contrast).coerceIn(-100f, 100f),
                highlights = (base.light.highlights + r.highlights).coerceIn(-100f, 100f),
                shadows = (base.light.shadows + r.shadows).coerceIn(-100f, 100f),
                whites = (base.light.whites + r.whites).coerceIn(-100f, 100f),
                blacks = (base.light.blacks + r.blacks).coerceIn(-100f, 100f),
                temp = (base.light.temp + r.temp).coerceIn(-100f, 100f),
                tint = (base.light.tint + r.tint).coerceIn(-100f, 100f),
                clarity = (base.light.clarity + r.clarity).coerceIn(-100f, 100f),
                dehaze = (base.light.dehaze + r.dehaze).coerceIn(-100f, 100f),
            ),
            color = base.color.copy(
                vibrance = (base.color.vibrance + r.vibrance).coerceIn(-100f, 100f),
                saturation = (base.color.saturation + r.saturation).coerceIn(-100f, 100f),
            ),
        )
    }

    fun applyBacklit(base: EditState, r: BacklitRecipe = BacklitRecipe()): EditState {
        return base.copy(
            light = base.light.copy(
                exposure = (base.light.exposure + r.exposure).coerceIn(-5f, 5f),
                contrast = (base.light.contrast + r.contrast).coerceIn(-100f, 100f),
                highlights = (base.light.highlights + r.highlights).coerceIn(-100f, 100f),
                shadows = (base.light.shadows + r.shadows).coerceIn(-100f, 100f),
                whites = (base.light.whites + r.whites).coerceIn(-100f, 100f),
                blacks = (base.light.blacks + r.blacks).coerceIn(-100f, 100f),
                temp = (base.light.temp + r.temp).coerceIn(-100f, 100f),
                tint = (base.light.tint + r.tint).coerceIn(-100f, 100f),
                clarity = (base.light.clarity + r.clarity).coerceIn(-100f, 100f),
                dehaze = (base.light.dehaze + r.dehaze).coerceIn(-100f, 100f),
            ),
            color = base.color.copy(
                vibrance = (base.color.vibrance + r.vibrance).coerceIn(-100f, 100f),
            ),
        )
    }
}
