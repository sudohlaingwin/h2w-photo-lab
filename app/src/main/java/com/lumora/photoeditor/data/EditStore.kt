package com.lumora.photoeditor.data

import android.content.Context
import com.lumora.photoeditor.engine.EditState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Non-destructive edit storage: each image's [EditState] lives in a JSON
 * sidecar. The original image file is never modified by editing.
 */
class EditStore(context: Context) {

    private val dir = File(context.filesDir, "edits").also { it.mkdirs() }

    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun save(imageId: String, state: EditState): Unit = withContext(Dispatchers.IO) {
        val tmp = File(dir, "$imageId.json.tmp")
        val dst = File(dir, "$imageId.json")
        tmp.writeText(json.encodeToString(state))
        // atomic replace so an interrupted session never corrupts the sidecar
        if (!tmp.renameTo(dst)) {
            dst.delete()
            tmp.renameTo(dst)
        }
    }

    suspend fun load(imageId: String): EditState = withContext(Dispatchers.IO) {
        val f = File(dir, "$imageId.json")
        if (!f.exists()) return@withContext EditState()
        try {
            json.decodeFromString<EditState>(f.readText())
        } catch (e: Exception) {
            // Corrupt sidecar: keep the original image safe, start fresh.
            // The corrupt file is preserved for diagnosis.
            f.renameTo(File(dir, "$imageId.json.corrupt"))
            EditState()
        }
    }

    suspend fun delete(imageId: String): Unit = withContext(Dispatchers.IO) {
        File(dir, "$imageId.json").delete()
    }

    /**
     * Copies one image's edit sidecar onto another id (virtual copies).
     * Missing source file is fine — [load] starts fresh anyway.
     * Returns false only on an I/O failure.
     */
    suspend fun copySidecar(fromId: String, toId: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val src = File(dir, "$fromId.json")
                if (src.exists()) src.copyTo(File(dir, "$toId.json"), overwrite = true)
                true
            } catch (_: Exception) {
                false
            }
        }

    fun encode(state: EditState): String = json.encodeToString(state)
    fun decode(raw: String): EditState = json.decodeFromString(raw)

    /**
     * Phase C#8 slice 1 — raw sidecar read for the sync engine: returns the
     * stored EditState JSON, or null when there is none / it is unreadable.
     * Never throws.
     */
    suspend fun readRaw(imageId: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            File(dir, "$imageId.json").takeIf { it.isFile }?.readText()
        }.getOrNull()
    }

    /**
     * Phase C#8 slice 1 — raw sidecar write for sync-apply: validates the
     * payload by decoding it as [EditState] first (a corrupt remote payload
     * must never clobber a good local sidecar), then writes atomically like
     * [save]. Returns false when the payload is invalid or the write fails.
     * Never throws.
     */
    suspend fun saveRaw(imageId: String, raw: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                json.decodeFromString<EditState>(raw)
            } catch (_: Exception) {
                return@withContext false
            }
            val tmp = File(dir, "$imageId.json.tmp")
            val dst = File(dir, "$imageId.json")
            runCatching {
                tmp.writeText(raw)
                if (!tmp.renameTo(dst)) {
                    dst.delete()
                    tmp.renameTo(dst)
                }
                true
            }.getOrDefault(false)
        }

    /** Last-modified of the edit sidecar, 0 when there is none. */
    fun sidecarLastModified(imageId: String): Long =
        File(dir, "$imageId.json").takeIf { it.exists() }?.lastModified() ?: 0L
}
