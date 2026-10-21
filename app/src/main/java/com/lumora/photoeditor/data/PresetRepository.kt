package com.lumora.photoeditor.data

import android.content.Context
import com.lumora.photoeditor.engine.Preset
import com.lumora.photoeditor.engine.sanitizeExportName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** Built-in presets plus user-saved custom presets (JSON files). */
class PresetRepository(private val context: Context) {

    private val dir = File(context.filesDir, "presets").also { it.mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }

    private val _custom = MutableStateFlow<List<Preset>>(emptyList())
    val custom: StateFlow<List<Preset>> = _custom.asStateFlow()

    val builtIn: List<Preset> = Presets.builtIn()

    suspend fun refresh() = withContext(Dispatchers.IO) {
        _custom.value = dir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { f ->
                try { json.decodeFromString<Preset>(f.readText()) } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    null
                }
            }.orEmpty().sortedBy { it.name }
    }

    suspend fun saveCustom(preset: Preset) = withContext(Dispatchers.IO) {
        File(dir, "${preset.id}.json").writeText(json.encodeToString(preset))
        refresh()
    }

    suspend fun deleteCustom(id: String) = withContext(Dispatchers.IO) {
        File(dir, "$id.json").delete()
        refresh()
    }

    /** Renames a user-saved preset in place (same id, so thumbnails and order
     *  keys stay valid; [refresh] re-sorts by name). No-op for blank names,
     *  missing files, or built-ins (safety: built-ins are fixed). */
    suspend fun renameCustom(id: String, newName: String) = withContext(Dispatchers.IO) {
        val clean = newName.trim().ifEmpty { return@withContext }
        val f = File(dir, "$id.json")
        val cur = try { json.decodeFromString<Preset>(f.readText()) } catch (t: Throwable) {
            if (t is CancellationException) throw t
            null
        } ?: return@withContext
        if (cur.builtIn) return@withContext
        f.writeText(json.encodeToString(cur.copy(name = clean)))
        refresh()
    }

    fun all(): List<Preset> = builtIn + _custom.value

    fun categories(): List<String> = all().map { it.category }.distinct()

    /**
     * Imports a preset from shared JSON text (e.g. picked with the system
     * file picker). Returns the imported preset, or null when the content is
     * not a usable preset. Never throws. The preset always lands in the
     * user's own collection (fresh id, builtIn=false).
     */
    suspend fun importPreset(fileContent: String): Preset? = withContext(Dispatchers.IO) {
        val decoded = try {
            json.decodeFromString<Preset>(fileContent)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            null
        }
        val prepared = prepareImportedPreset(decoded, _custom.value.map { it.name }.toSet())
            ?: return@withContext null
        saveCustom(prepared)
        prepared
    }

    // NOTE: the import-preparation contract lives in the top-level
    // [prepareImportedPreset] (PresetSharing.kt) — harness-pinned pure
    // Kotlin. Do NOT add a member with the same name here: it would shadow
    // the contract and silently drop the cross-photo sanitizing (retouch
    // spots / AI-derived masks must never ride along), the strength clamp,
    // the blank-category default, and the UUID ids (timestamp ids collide
    // when a backup restores several presets inside one millisecond).

    /**
     * Backs up ALL custom presets into one versioned JSON document (see
     * [PresetBackupDoc]). Custom presets live in app-private storage, which
     * Android wipes on uninstall — this is the user's escape hatch before a
     * reinstall (the one final reinstall for the stable-signing fix would
     * otherwise delete every custom preset with no way back). Returns null
     * when there is nothing to back up. Never throws.
     */
    suspend fun exportBackupJson(): String? = withContext(Dispatchers.IO) {
        val docs = _custom.value.mapNotNull { p ->
            try {
                File(dir, "${p.id}.json").readText().takeIf { it.isNotBlank() }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                null
            }
        }
        PresetBackupDoc.wrap(docs)
    }

    /**
     * Restores custom presets from a backup document produced by
     * [exportBackupJson] (a bare JSON array of preset objects is also
     * accepted). Every item goes through [importPreset]'s validation, so a
     * corrupt entry is skipped — never fatal — and restored presets always
     * get fresh ids plus de-duplicated names, so restoring twice (or into
     * a collection that already has them) can never collide. Returns the
     * number of presets imported. Never throws.
     */
    suspend fun importBackupJson(content: String): Int = withContext(Dispatchers.IO) {
        val docs = PresetBackupDoc.unwrap(content)
        var count = 0
        for (d in docs) {
            if (importPreset(d) != null) count++
        }
        count
    }

    /**
     * Writes [preset] as a shareable JSON file under the FileProvider-served
     * cache "shared/" path. Returns the file, or null on any I/O failure.
     * Stale share files (>24h) are pruned so the cache dir cannot grow
     * without bound.
     */
    suspend fun exportShareFile(preset: Preset): File? = withContext(Dispatchers.IO) {
        try {
            val shareDir = File(context.cacheDir, "shared/presets").also { it.mkdirs() }
            val cutoff = System.currentTimeMillis() - 86_400_000L
            shareDir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
            val stem = sanitizeExportName(preset.name).ifEmpty { "preset" }
            val f = File(shareDir, "$stem.json")
            f.writeText(json.encodeToString(preset))
            f
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            null
        }
    }
}
