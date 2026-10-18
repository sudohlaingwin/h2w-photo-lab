package com.lumora.photoeditor.engine

/**
 * Single source of truth for the main fragment program's uniform names.
 *
 * Background: [GlEngine.cacheUniforms] pre-resolves uniform locations right
 * after linking, and [GlEngine.u] lazily resolves any other name on demand.
 * A uniform that is *set* via `u("uFoo")` but never *declared* in
 * [Shaders.FRAGMENT] resolves to location -1, and its `glUniform*` call
 * becomes a silent no-op — the slider moves but the preview never changes.
 * That is exactly what happened with `uCalHue`/`uCalSat`/`uCalShadowTint`
 * (camera calibration shipped dead until they were added to the cache
 * list). Keeping the list in exactly one place, and covering it with
 * [UniformRegistryTest], means that bug class can never ship silently
 * again: the test fails the build if a registered name has no declaration
 * in the shipped GLSL.
 *
 * Pure Kotlin, no Android imports — unit-testable on the JVM.
 */
object UniformRegistry {

    /** Every uniform the main fragment program ([Shaders.FRAGMENT]) binds. */
    val FRAGMENT: List<String> = listOf(
        "uUvMatrix", "uZoom", "uImage", "uBlur", "uRetouch", "uHasRetouch", "uCurves",
        // AI Pro Retouch image-adaptive 3D LUT (see Shaders.FRAGMENT).
        "uAiLut", "uAiLutStrength",
        // Look transfer reference-photo 3D LUT (see Shaders.FRAGMENT).
        "uLookLut", "uLookLutStrength",
        "uExposure", "uContrast", "uHighlights", "uShadows", "uWhites", "uBlacks",
        "uTemp", "uTint", "uClarity", "uTextureAmt", "uDehaze",
        "uHsl", "uVibrance", "uSaturation", "uBw", "uBwMix",
        "uGradeS", "uGradeM", "uGradeH", "uGradeG", "uGradeBalance", "uGradeStrength",
        "uSharpen", "uSharpMasking", "uSharpRadius", "uSharpDetail",
        "uLumNr", "uLumNrDetail", "uColorNr", "uColorNrDetail", "uMoire",
        // Deconvolution sharpening (pro-spec Phase 3): Wiener inverse
        // kernel + source-texel size. Set in GlEngine.render.
        "uDeconv", "uDeconvK", "uTexel",
        "uDistortion", "uDefringe", "uVignette", "uGrain", "uLensBlur", "uLensBlur2", "uTime",
        "uMaskCount", "uMaskTex", "uMaskA", "uMaskB", "uMaskC",
        // Mask inversion flag per slot (Lightroom "Invert"): adjustments
        // apply to the inverse region instead of the mask. Set in
        // GlEngine.render; consumed in Shaders.FRAGMENT's unrolled mask
        // blocks and the mask overlay.
        "uMaskInv",
        // Hair decontamination (Lightroom "Decontaminate colors"): per-slot
        // strength + despilled RGB textures on units 11..14. Set in
        // GlEngine.render / uploadMaskDecon; consumed in the unrolled mask
        // blocks of Shaders.FRAGMENT.
        "uDecon", "uDeconTex",
        "uClipMode", "uOverlayMask", "uOverlayAlpha", "uMaskViewMode", "uSplitX",
        "uFxTex", "uFxLeak", "uFxDust",
        // Camera Calibration (Lightroom panel): shadows tint + RGB
        // primary hue/sat. These were set via u() but missing from the
        // old inline cache list, so their glUniform calls hit location -1
        // — silent no-ops until this registry made the list exhaustive.
        "uCalHue", "uCalSat", "uCalShadowTint",
    )
}
