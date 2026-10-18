package com.lumora.photoeditor.export

import com.lumora.photoeditor.engine.ExportFormat
import com.lumora.photoeditor.engine.ExportSettings
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * A named, reusable bundle of export settings (Lightroom parity — the Export
 * screen had no way to save a format/quality/size/watermark/file-name recipe).
 *
 * The whole [settings] snapshot is stored, including the watermark block and
 * file-name stem, so one tap restores an exact repeatable export recipe.
 */
@Serializable
data class ExportPreset(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val settings: ExportSettings = ExportSettings(),
)

/** Maximum number of export presets kept; the oldest is dropped past this. */
const val MAX_EXPORT_PRESETS = 32

/**
 * Cleans a user-typed preset name: trims, collapses internal whitespace, caps
 * at 40 chars. Returns "" when nothing usable remains (the caller refuses to
 * save such names).
 */
fun sanitizePresetName(raw: String): String =
    raw.trim().replace(Regex("\\s+"), " ").take(40)

/**
 * Derives a non-colliding display name: "Web upload", "Web upload (2)", …
 */
fun uniquePresetName(base: String, existing: Collection<String>): String {
    if (base !in existing) return base
    var i = 2
    while ("$base ($i)" in existing) i++
    return "$base ($i)"
}

/** Id prefix for the built-in recipes below; user-saved presets always carry random UUIDs. */
private const val FACTORY_ID_PREFIX = "factory:"

/**
 * True for a built-in recipe id — these ship with the app, are always
 * present, and can never be deleted or overwritten by user data.
 */
fun isFactoryExportPreset(id: String): Boolean = id.startsWith(FACTORY_ID_PREFIX)

/**
 * Built-in per-platform export recipes (Lightroom parity: the Export
 * screen's preset row used to start empty, forcing every user to
 * hand-configure Instagram/Facebook/web sizes before their first share).
 * Stable ids so they merge cleanly with the user's own saved presets and
 * can never collide with them (user presets get random UUIDs).
 */
fun factoryExportPresets(): List<ExportPreset> = listOf(
    ExportPreset(
        id = "${FACTORY_ID_PREFIX}instagram-post",
        name = "Instagram post",
        settings = ExportSettings(
            format = ExportFormat.JPEG, quality = 88, longEdge = 1080,
            keepMetadata = false,
        ),
    ),
    ExportPreset(
        id = "${FACTORY_ID_PREFIX}story",
        name = "Story / Reels",
        settings = ExportSettings(
            format = ExportFormat.JPEG, quality = 88, longEdge = 1920,
            keepMetadata = false,
        ),
    ),
    ExportPreset(
        id = "${FACTORY_ID_PREFIX}facebook",
        name = "Facebook",
        settings = ExportSettings(
            format = ExportFormat.JPEG, quality = 88, longEdge = 2048,
            keepMetadata = true,
        ),
    ),
    ExportPreset(
        id = "${FACTORY_ID_PREFIX}web",
        name = "Web (small)",
        settings = ExportSettings(
            format = ExportFormat.JPEG, quality = 80, longEdge = 1600,
            keepMetadata = false,
        ),
    ),
    ExportPreset(
        id = "${FACTORY_ID_PREFIX}full-res",
        name = "Full resolution",
        settings = ExportSettings(
            format = ExportFormat.JPEG, quality = 100, longEdge = 0,
            keepMetadata = true, embedColorProfile = true,
        ),
    ),
)

/**
 * Factory recipes first, then the user's own presets. Any stale persisted
 * copies carrying factory ids are dropped so the shipped recipes always
 * win — a corrupt user entry can never shadow or rename a built-in.
 */
fun mergeFactoryPresets(user: List<ExportPreset>): List<ExportPreset> =
    factoryExportPresets() + user.filterNot { isFactoryExportPreset(it.id) }
