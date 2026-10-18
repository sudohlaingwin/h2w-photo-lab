package com.lumora.photoeditor.data

import com.lumora.photoeditor.engine.Preset
import java.util.UUID

/**
 * Normalizes a decoded preset file into an importable custom preset, or null
 * when the content is not a usable preset. Pure Kotlin (no Android imports):
 * the validation, name de-duplication and cross-photo sanitizing are
 * harness-testable without a device. [existingNames] are the current custom
 * preset names (case-sensitive).
 */
fun prepareImportedPreset(decoded: Preset?, existingNames: Set<String>): Preset? {
    val d = decoded ?: return null
    val name = d.name.trim().ifEmpty { return null }
    // An imported preset lands on other photos, so it gets the same
    // cross-photo policy as "Save current as preset": image-specific work
    // (retouch spots, AI-derived mask components) never comes along.
    val portable = d.state.sanitizedForPaste()
    val category = d.category.trim().ifEmpty { "My Presets" }
    var finalName = name
    var n = 2
    while (finalName in existingNames) {
        finalName = "$name ($n)"
        n++
    }
    return Preset(
        id = "custom-" + UUID.randomUUID().toString(),
        name = finalName,
        category = category,
        state = portable,
        strength = d.strength.coerceIn(0f, 1f),
        builtIn = false,
        thumbnailSeed = System.currentTimeMillis(),
    )
}
