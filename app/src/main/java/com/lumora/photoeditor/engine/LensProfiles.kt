package com.lumora.photoeditor.engine

/**
 * Per-lens correction memory — pro-premium program Phase A#2, slice 2.
 *
 * Slice 1 measured optical vignetting from the photo itself. Slice 2 tackles
 * distortion and chromatic aberration, which we deliberately do NOT guess: the
 * app has no licensed lens-correction database and fabricating numbers per
 * lens would be fake data. Instead the app detects the lens from EXIF and
 * remembers the distortion/defringe values the USER dials in for that lens,
 * auto-applying them to later photos shot with the same lens. The user's own
 * corrections become the profile — honest, personal, and undoable.
 *
 * Lens model comes from ExifInterface.TAG_LENS_MODEL (falls back to
 * TAG_MODEL, the camera body) at photo-open time. Keys are normalized so
 * "Vivo V60", "vivo v60" and "VIVO  V60" all map to the same profile.
 *
 * Fully pure Kotlin so the real code is unit-testable with a standalone
 * kotlinc harness; the app wires it through
 * [com.lumora.photoeditor.ui.state.EditorViewModel] and
 * [com.lumora.photoeditor.data.LensProfileStore].
 */
data class LensCorrection(
    /** -100..100 slider units, mirrors OpticsParams.distortion. */
    val distortion: Float,
    /** 0..100 slider units, mirrors OpticsParams.defringe. */
    val defringe: Float,
)

object LensProfiles {
    /**
     * Stable storage key for a lens. Prefers the EXIF lens model, falls back
     * to the camera body model prefixed with "cam:" so a body and a lens can
     * never collide. Returns null when neither identifies a lens.
     */
    fun lensKeyFromExif(lensModel: String?, cameraModel: String?): String? {
        val lens = normalizeLensModel(lensModel)
        if (lens != null) return "lens:$lens"
        val cam = normalizeLensModel(cameraModel)
        if (cam != null) return "cam:$cam"
        return null
    }

    /**
     * Human label for the panel: the raw EXIF string, trimmed and capped at
     * 48 characters. Falls back to the camera model; null when unknown.
     */
    fun lensLabelFromExif(lensModel: String?, cameraModel: String?): String? {
        val raw = lensModel?.trim()?.takeIf { it.isNotBlank() }
            ?: cameraModel?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
        return raw.take(48)
    }

    /**
     * Normalizes a lens/body model string: lowercase, non-alphanumeric runs
     * become single spaces, trimmed. Null for blank input.
     */
    fun normalizeLensModel(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val sb = StringBuilder(raw.length)
        var lastWasSpace = true // trims leading separators too
        for (ch in raw.lowercase()) {
            if (ch.isLetterOrDigit()) {
                sb.append(ch)
                lastWasSpace = false
            } else if (!lastWasSpace) {
                sb.append(' ')
                lastWasSpace = true
            }
        }
        val out = sb.toString().trim()
        return out.ifEmpty { null }
    }

    /** Compact "distortion,defringe" serialization for the profile store. */
    fun format(c: LensCorrection): String = "${c.distortion},${c.defringe}"

    /** Inverse of [format]; null for malformed input, values coerced to range. */
    fun parse(s: String?): LensCorrection? {
        if (s.isNullOrBlank()) return null
        val parts = s.split(',')
        if (parts.size != 2) return null
        val d = parts[0].trim().toFloatOrNull() ?: return null
        val f = parts[1].trim().toFloatOrNull() ?: return null
        return LensCorrection(
            distortion = d.coerceIn(-100f, 100f),
            defringe = f.coerceIn(0f, 100f),
        )
    }

    /** A stored profile is only worth remembering if it actually changes something. */
    fun isMeaningful(c: LensCorrection): Boolean =
        c.distortion != 0f || c.defringe != 0f
}
