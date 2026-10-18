package com.lumora.photoeditor.engine

/**
 * Pro-spec Phase 1: text-prompt editing, v1.
 *
 * Maps a plain-language edit request ("dramatic sky", "warm sunset") to a
 * mask + local adjustments — or to global adjustments when no region is
 * named. Offline keyword rules, no network, no LLM, no generative AI.
 *
 * Honest by construction:
 * - [interpret] returns null when no intent is recognized; the caller must
 *   say so (with [helpText]) instead of guessing.
 * - Every matched effect is listed in [PromptPlan.summary], so the user sees
 *   exactly what moved.
 * - Region masks reuse the same classical-CV rasters as the mask panel
 *   (sky select, subject select, skin, luminance bands) — a region the
 *   detector cannot isolate is reported, never faked.
 *
 * Pure Kotlin (no Android imports): JVM-testable. The ViewModel owns mask
 * raster construction and state application.
 */
sealed interface PromptRegion {
    data object Sky : PromptRegion
    data object Subject : PromptRegion
    data object Background : PromptRegion
    data object Skin : PromptRegion
    /** Select by brightness: [lo]..[hi] Rec.709 luma, 0..1. */
    data class Luminance(val lo: Float, val hi: Float, val feather: Float = 0.5f) : PromptRegion
}

/**
 * v2: portrait-retouch intents. Unlike [PromptRegion] these don't build a
 * mask — the ViewModel runs the classical-CV detectors (blemish /
 * under-eye) and applies retouch spots. A prompt may combine ONE portrait
 * intent with whole-photo slider effects ("remove blemishes and warm it
 * up"); the slider effects always go global for portrait plans.
 */
sealed interface PromptPortrait {
    data object RemoveBlemishes : PromptPortrait
    data object SoftenUnderEyes : PromptPortrait
    data object BrightenEyes : PromptPortrait
}

/**
 * Global-slider deltas, same units as the panels: exposure in EV (-5..5),
 * everything else -100..100 except [sharpening] (Detail 0..150).
 */
data class PromptGlobal(
    val exposure: Float = 0f,
    val contrast: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val temp: Float = 0f,
    val tint: Float = 0f,
    val clarity: Float = 0f,
    val dehaze: Float = 0f,
    val saturation: Float = 0f,
    val vibrance: Float = 0f,
    val sharpening: Float = 0f,
    val bw: Boolean = false,
)

data class PromptPlan(
    /** Null = whole-photo edit. */
    val region: PromptRegion?,
    /** Effect deltas applied to the new mask (when [region] != null). */
    val local: LocalAdjust,
    /** Effect deltas applied globally (when [region] == null, or for portrait plans). */
    val global: PromptGlobal,
    val maskName: String,
    /** Human-readable account of what matched, e.g. "Sky: dramatic — contrast +30, clarity +25". */
    val summary: String,
    /** v2: portrait-retouch intent; null for mask/global plans. */
    val portrait: PromptPortrait? = null,
    /** Labels of the slider effects folded into [global] (for result messages). */
    val effectLabels: List<String> = emptyList(),
)

object PromptEdit {

    /**
     * Interprets a free-text edit request. Returns null when no region and
     * no effect is recognized — the caller must show [helpText] instead of
     * inventing an edit.
     */
    fun interpret(text: String): PromptPlan? {
        val t = " ${text.lowercase().trim()} "
        if (t.isBlank()) return null
        // v2: portrait intents are matched first and consume their keywords,
        // so the portrait verb can't leak into slider effects ("brighten her
        // eyes" must not brighten the whole photo).
        val portraitHit = detectPortrait(t)
        val effects = detectEffects(portraitHit?.second ?: t)
        if (portraitHit == null && effects.isEmpty()) return null
        val portrait = portraitHit?.first
        if (portrait != null) {
            return PromptPlan(
                region = null,
                local = LocalAdjust(),
                global = effects.fold(PromptGlobal()) { g, e -> e.applyGlobal(g) },
                maskName = "",
                summary = portraitSummary(portrait) +
                    (if (effects.isNotEmpty())
                        " · Whole photo: " + effects.joinToString(", ") { it.label }
                    else ""),
                portrait = portrait,
                effectLabels = effects.map { it.label },
            )
        }
        val region = detectRegion(t)
        if (effects.isEmpty()) return null
        return if (region == null) {
            PromptPlan(
                region = null,
                local = LocalAdjust(),
                global = effects.fold(PromptGlobal()) { g, e -> e.applyGlobal(g) },
                maskName = "",
                summary = "Whole photo: " + effects.joinToString(", ") { it.label },
                effectLabels = effects.map { it.label },
            )
        } else {
            val local = effects.fold(LocalAdjust()) { a, e -> e.applyLocal(a) }
            PromptPlan(
                region = region,
                local = local,
                global = PromptGlobal(),
                maskName = "Prompt: ${regionName(region)}",
                summary = "${regionName(region)}: " + effects.joinToString(", ") { it.label },
                effectLabels = effects.map { it.label },
            )
        }
    }

    /** Shown when a prompt cannot be interpreted — honest, with working examples. */
    val helpText: String =
        "I couldn't match that to an edit. Try e.g. \"dramatic sky\", " +
            "\"warm sunset\", \"brighten the face\", \"soften the skin\", " +
            "\"darken the background\", \"lift the shadows\", \"make it pop\", " +
            "\"remove blemishes\", \"soften dark circles\" or \"brighten the eyes\"."

    /** Shown when a region was understood but the detector couldn't isolate it. */
    fun regionMissMessage(region: PromptRegion): String = when (region) {
        PromptRegion.Sky -> "Couldn't find a clear sky in this photo — the edit was not applied. Try a brush or gradient mask."
        PromptRegion.Subject -> "Couldn't find a clear subject — the edit was not applied. Try a brush mask."
        PromptRegion.Background -> "Couldn't separate a background — the edit was not applied. Try a brush mask."
        PromptRegion.Skin -> "No skin tones detected — the edit was not applied."
        is PromptRegion.Luminance -> "Couldn't build that tonal selection — the edit was not applied."
    }

    private fun regionName(region: PromptRegion): String = when (region) {
        PromptRegion.Sky -> "Sky"
        PromptRegion.Subject -> "Subject"
        PromptRegion.Background -> "Background"
        PromptRegion.Skin -> "Skin"
        is PromptRegion.Luminance ->
            if (region.hi <= 0.5f) "Shadows" else "Highlights"
    }

    private fun portraitSummary(p: PromptPortrait): String = when (p) {
        PromptPortrait.RemoveBlemishes -> "Blemishes: auto-detect + heal"
        PromptPortrait.SoftenUnderEyes -> "Under-eyes: auto-detect + soften"
        PromptPortrait.BrightenEyes -> "Eyes: auto-locate + brighten"
    }

    /**
     * v2 portrait-intent detection. Returns the intent plus the prompt text
     * with the intent's keywords blanked, so [detectEffects] can't re-read
     * the portrait verb as a slider effect ("soften dark circles" must not
     * soften the whole photo; "brighten her eyes" must not add +0.7 EV
     * globally).
     *
     * Honesty rules: "eyes" alone (no verb) is ambiguous — reported, not
     * guessed. Teeth whitening has no on-device detector, so it stays out.
     */
    private fun detectPortrait(t: String): Pair<PromptPortrait, String>? {
        // Blemishes: the region word alone is an unambiguous removal intent;
        // its verbs ("remove", "heal", "clean", …) are not slider effects.
        firstWord(t, "blemish", "pimple", "acne", "zit", "blackhead", "whitehead", "spot")?.let { w ->
            return PromptPortrait.RemoveBlemishes to blankWord(t, w)
        }
        // Under-eyes: phrase first (it contains "eye", which must not win).
        val ueHit = firstPhrase(t, "under eye", "under-eye", "dark circle", "eye bag")
            ?: firstWord(t, "eyebag")
        if (ueHit != null) {
            var s = blankWord(t, ueHit)
            firstWord(s, "soften", "soft", "reduce", "lighten", "fix", "remove")?.let { s = blankWord(s, it) }
            return PromptPortrait.SoftenUnderEyes to s
        }
        // Eyes need an explicit verb — "eyes" alone could mean anything.
        val eye = firstWord(t, "eye", "eyes", "iris", "irises")
        if (eye != null) {
            val verb = firstWord(t, "brighten", "brighter", "bright", "enhance", "pop", "sharpen", "whiten")
            if (verb != null) {
                var s = blankWord(t, eye)
                s = blankWord(s, verb)
                return PromptPortrait.BrightenEyes to s
            }
        }
        return null
    }

    /** First matching word (word-boundary, optional plural), or null. */
    private fun firstWord(t: String, vararg words: String): String? {
        for (w in words) {
            if (Regex("\\b${Regex.escape(w)}(s|es)?\\b").containsMatchIn(t)) return w
        }
        return null
    }

    /** First matching multi-word phrase (word-boundary, optional plural), or null. */
    private fun firstPhrase(t: String, vararg phrases: String): String? {
        for (p in phrases) {
            if (phraseRegex(p).containsMatchIn(t)) return p
        }
        return null
    }

    /** Blanks every occurrence of the word/phrase (word-boundary, plural-aware) with spaces. */
    private fun blankWord(t: String, w: String): String =
        (if (w.contains(' ')) phraseRegex(w)
        else Regex("\\b${Regex.escape(w)}(s|es)?\\b"))
            .replace(t) { " ".repeat(it.value.length) }

    /** `\bword1\s+word2s?\b`-style regex for a (possibly multi-word) phrase. */
    private fun phraseRegex(p: String): Regex {
        val body = p.split(' ').joinToString("\\s+") { Regex.escape(it) } + "(s|es)?\\b"
        return Regex("\\b$body")
    }

    private fun detectRegion(t: String): PromptRegion? {
        if (has(t, "sky", "skies", "cloud", "cloudy", "clouds")) return PromptRegion.Sky
        if (has(t, "skin", "complexion", "pores")) return PromptRegion.Skin
        if (has(
                t, "face", "faces", "person", "people", "subject", "portrait",
                "selfie", "man", "woman", "child", "baby", "model",
            )
        ) return PromptRegion.Subject
        if (has(t, "background", "backdrop", "behind")) return PromptRegion.Background
        if (has(t, "shadow", "shadows", "shade", "dark areas", "darks")) {
            return PromptRegion.Luminance(lo = 0f, hi = 0.38f)
        }
        if (has(t, "highlight", "highlights", "bright areas", "whites")) {
            return PromptRegion.Luminance(lo = 0.62f, hi = 1f)
        }
        return null
    }

    /**
     * One recognized effect: the label shown in the summary plus the
     * parameter deltas. Deltas are in slider units (-100..100) except
     * [exposureEv] (-5..5 EV, global path only — mask exposure is /100 of an EV).
     */
    private data class Effect(
        val label: String,
        val exposure: Float = 0f,
        val contrast: Float = 0f,
        val highlights: Float = 0f,
        val shadows: Float = 0f,
        val temp: Float = 0f,
        val tint: Float = 0f,
        val clarity: Float = 0f,
        val dehaze: Float = 0f,
        val saturation: Float = 0f,
        val vibrance: Float = 0f,
        val sharpness: Float = 0f,
        val bw: Boolean = false,
        /** Global-path exposure in EV; local path scales it down (/100). */
        val exposureEv: Float = 0f,
    ) {
        fun applyLocal(a: LocalAdjust): LocalAdjust = a.copy(
            // Mask exposure is -100..100 where /100 = EV (GlEngine mask
            // array), so an EV delta scales by 100 here.
            exposure = (a.exposure + exposure + exposureEv * 100f).coerceIn(-100f, 100f),
            contrast = (a.contrast + contrast).coerceIn(-100f, 100f),
            highlights = (a.highlights + highlights).coerceIn(-100f, 100f),
            shadows = (a.shadows + shadows).coerceIn(-100f, 100f),
            temp = (a.temp + temp).coerceIn(-100f, 100f),
            tint = (a.tint + tint).coerceIn(-100f, 100f),
            saturation = (a.saturation + saturation - if (bw) 100f else 0f)
                .coerceIn(-100f, 100f),
            clarity = (a.clarity + clarity).coerceIn(-100f, 100f),
            sharpness = (a.sharpness + sharpness).coerceIn(-100f, 100f),
            dehaze = (a.dehaze + dehaze).coerceIn(-100f, 100f),
        )

        fun applyGlobal(g: PromptGlobal): PromptGlobal = g.copy(
            exposure = (g.exposure + exposureEv + exposure / 100f).coerceIn(-5f, 5f),
            contrast = (g.contrast + contrast).coerceIn(-100f, 100f),
            highlights = (g.highlights + highlights).coerceIn(-100f, 100f),
            shadows = (g.shadows + shadows).coerceIn(-100f, 100f),
            temp = (g.temp + temp).coerceIn(-100f, 100f),
            tint = (g.tint + tint).coerceIn(-100f, 100f),
            clarity = (g.clarity + clarity).coerceIn(-100f, 100f),
            dehaze = (g.dehaze + dehaze).coerceIn(-100f, 100f),
            saturation = (g.saturation + saturation - if (bw) 100f else 0f)
                .coerceIn(-100f, 100f),
            vibrance = (g.vibrance + vibrance).coerceIn(-100f, 100f),
            sharpening = (g.sharpening + sharpness).coerceIn(0f, 150f),
            bw = g.bw || bw,
        )
    }

    private fun detectEffects(t: String): List<Effect> {
        val out = mutableListOf<Effect>()
        if (has(t, "dramatic", "drama")) {
            out += Effect("dramatic", contrast = 30f, clarity = 25f, dehaze = 15f, saturation = 10f)
        }
        if (has(t, "moody")) {
            out += Effect("moody", exposureEv = -0.4f, shadows = -15f, saturation = -10f)
        }
        if (has(t, "brighten", "brighter", "bright", "brightening", "lift", "lifting")) {
            out += Effect("brighten", exposureEv = 0.7f, shadows = 12f)
        }
        if (has(t, "darken", "darker", "darkening")) {
            out += Effect("darken", exposureEv = -0.7f)
        }
        if (has(t, "warm", "warmer", "warming", "warmth", "golden")) {
            out += Effect("warm", temp = 20f)
        }
        if (has(t, "cool", "cooler", "cooling")) {
            out += Effect("cool", temp = -20f)
        }
        if (has(t, "vivid", "vibrant", "saturated", "punchy", "colorful", "colourful")) {
            out += Effect("vivid", saturation = 25f, vibrance = 15f)
        }
        if (has(t, "pop")) {
            out += Effect("pop", contrast = 15f, saturation = 15f, clarity = 10f)
        }
        if (has(t, "muted", "mute", "fade", "faded", "pastel")) {
            out += Effect("faded", saturation = -20f, contrast = -15f)
        }
        if (has(t, "soft", "soften", "softer", "softening", "smooth", "smoother", "smoothing", "glow", "glowing")) {
            out += Effect("soft", clarity = -30f)
        }
        if (has(t, "sharp", "sharper", "sharpen", "sharpening", "crisp", "detail", "detailed")) {
            out += Effect("sharp", sharpness = 35f, clarity = 12f)
        }
        if (has(t, "hazy", "haze")) {
            out += Effect("dehaze", dehaze = 30f)
        }
        if (has(t, "dreamy")) {
            out += Effect("dreamy", dehaze = -20f, clarity = -15f)
        }
        if (has(t, "sunset", "sunrise")) {
            out += Effect("sunset", temp = 22f, saturation = 12f)
        }
        if (has(t, "black and white", "monochrome", "grayscale", "grey scale", "b&w", "bw")) {
            out += Effect("black & white", bw = true)
        }
        if (has(t, "contrast", "contrasty") && out.none { it.label == "dramatic" }) {
            out += Effect("contrast", contrast = 20f)
        }
        if (has(t, "recover", "tame", "pull back") && has(t, "highlight", "highlights", "sky")) {
            out += Effect("highlight recovery", highlights = -40f)
        }
        // v1 scope: no negation handling ("less contrast", "not too warm").
        // Documented limitation — never guessed.
        return out
    }

    /** Word-boundary match against any of [words] in the padded text [t]. */
    private fun has(t: String, vararg words: String): Boolean {
        for (w in words) {
            if (w.contains(' ')) {
                if (t.contains(" $w ")) return true
            } else {
                if (Regex("\\b${Regex.escape(w)}s?\\b").containsMatchIn(t)) return true
            }
        }
        return false
    }
}
