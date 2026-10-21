package com.lumora.photoeditor.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Versioned backup document for the user's custom presets.
 *
 * Custom presets live in app-private storage, which Android wipes on
 * uninstall — before the one final reinstall (stable-signing fix) the user
 * needs a one-tap way to save them all and bring them back. The Gemini API
 * key already has this; presets did not.
 *
 * Pure Kotlin, zero Android: the envelope is built and parsed with the
 * kotlinx.serialization JSON *runtime* only (no @Serializable classes, no
 * compiler plugin), so this file is JVM-harness testable. Each preset
 * travels as its opaque stored JSON document — validation, id freshness
 * and name de-duplication stay in [PresetRepository.importPreset], the
 * single choke point, so the backup path can never smuggle in a corrupt
 * or colliding preset.
 *
 * Envelope:
 * {"app":"h2w-photo-lab","kind":"preset-backup","version":1,
 *  "presets":[ {...}, {...} ]}
 * A bare JSON array of preset objects is also accepted on read (forward
 * tolerance for hand-made backups).
 */
internal object PresetBackupDoc {
    const val VERSION = 1

    /**
     * Wraps raw preset JSON documents in the versioned envelope.
     * Returns null when there is nothing to back up. Never throws.
     */
    fun wrap(rawDocs: List<String>): String? {
        if (rawDocs.isEmpty()) return null
        return try {
            val arr = buildJsonArray {
                rawDocs.forEach { add(Json.parseToJsonElement(it)) }
            }
            buildJsonObject {
                put("app", "h2w-photo-lab")
                put("kind", "preset-backup")
                put("version", VERSION)
                put("presets", arr)
            }.toString()
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Extracts the raw preset JSON documents from a backup document.
     * Non-object array entries are skipped; anything malformed yields an
     * empty list. Never throws.
     */
    fun unwrap(content: String): List<String> {
        return try {
            val root = Json.parseToJsonElement(content)
            val arr: JsonArray = when {
                root is JsonObject && root["presets"] is JsonArray ->
                    root["presets"] as JsonArray
                root is JsonArray -> root
                else -> return emptyList()
            }
            arr.mapNotNull { (it as? JsonObject)?.toString() }
        } catch (_: Throwable) {
            emptyList()
        }
    }
}
