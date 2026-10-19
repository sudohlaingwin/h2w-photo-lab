package com.lumora.photoeditor.data

import android.content.Context
import com.lumora.photoeditor.engine.EditState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Non-destructive edit storage: each image's [EditState] lives in a JSON
 * sidecar. The original image file is never modified by editing.
 *
 * Thin Android/coroutine shell: the Context-derived directory and IO
 * dispatch live here; all file policy (atomic save, corrupt quarantine,
 * raw sync payloads) lives in [EditSidecarIo], which is pure JVM and
 * harness-verified by EditStoreIoTest.
 */
class EditStore(context: Context) {

    private val io = EditSidecarIo(File(context.filesDir, "edits").also { it.mkdirs() })

    suspend fun save(imageId: String, state: EditState): Unit = withContext(Dispatchers.IO) {
        io.save(imageId, state)
    }

    suspend fun load(imageId: String): EditState = withContext(Dispatchers.IO) {
        io.load(imageId)
    }

    suspend fun delete(imageId: String): Unit = withContext(Dispatchers.IO) {
        io.delete(imageId)
    }

    /**
     * Copies one image's edit sidecar onto another id (virtual copies).
     * Missing source file is fine — [load] starts fresh anyway.
     * Returns false only on an I/O failure.
     */
    suspend fun copySidecar(fromId: String, toId: String): Boolean =
        withContext(Dispatchers.IO) {
            io.copySidecar(fromId, toId)
        }

    fun encode(state: EditState): String = io.encode(state)
    fun decode(raw: String): EditState = io.decode(raw)

    /**
     * Phase C#8 slice 1 — raw sidecar read for the sync engine: returns the
     * stored EditState JSON, or null when there is none / it is unreadable.
     * Never throws.
     */
    suspend fun readRaw(imageId: String): String? = withContext(Dispatchers.IO) {
        io.readRaw(imageId)
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
            io.saveRaw(imageId, raw)
        }

    /** Last-modified of the edit sidecar, 0 when there is none. */
    fun sidecarLastModified(imageId: String): Long = io.sidecarLastModified(imageId)
}
