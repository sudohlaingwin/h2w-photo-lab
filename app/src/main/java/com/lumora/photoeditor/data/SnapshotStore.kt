package com.lumora.photoeditor.data

import android.content.Context
import com.lumora.photoeditor.engine.EditSnapshot
import com.lumora.photoeditor.engine.EditState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Named per-photo snapshots (Lightroom parity): full EditState bookmarks
 * stored as JSON under filesDir/snapshots/<imageId>/<snapshotId>.json.
 *
 * File-based like PresetRepository (no DB migration risk). Capped at
 * [MAX_PER_PHOTO] snapshots per photo — taking a new one drops the oldest.
 * All suspend ops run on IO and never throw out of this class (callers are
 * still free to guard); [deleteAllFor] is the synchronous cleanup hook for
 * photo deletion and must be called from an IO context.
 */
class SnapshotStore(context: Context) {

    companion object {
        const val MAX_PER_PHOTO = 10
    }

    private val root = File(context.filesDir, "snapshots").also { it.mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }

    /** Per-photo live lists, keyed by image id. */
    private val flows = mutableMapOf<String, MutableStateFlow<List<EditSnapshot>>>()

    fun snapshots(imageId: String): StateFlow<List<EditSnapshot>> =
        synchronized(flows) {
            flows.getOrPut(imageId) { MutableStateFlow(emptyList()) }
        }.asStateFlow()

    private fun dirFor(imageId: String): File = File(root, imageId).also { it.mkdirs() }

    private fun readAll(dir: File): List<EditSnapshot> =
        dir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { f ->
                try { json.decodeFromString<EditSnapshot>(f.readText()) }
                catch (_: Exception) { null }
            }
            .orEmpty()
            .sortedByDescending { it.createdAt }

    suspend fun refresh(imageId: String) = withContext(Dispatchers.IO) {
        val list = readAll(dirFor(imageId))
        synchronized(flows) {
            flows.getOrPut(imageId) { MutableStateFlow(emptyList()) }
        }.value = list
    }

    /**
     * Takes a snapshot of [state] under [name]. Returns the snapshot, or null
     * on blank name / I/O failure. Oldest snapshots beyond the cap are
     * dropped (never the one just taken — it has the newest timestamp).
     */
    suspend fun take(imageId: String, name: String, state: EditState): EditSnapshot? =
        withContext(Dispatchers.IO) {
            val clean = name.trim().ifEmpty { return@withContext null }
            val snap = EditSnapshot(
                id = UUID.randomUUID().toString(),
                name = clean,
                createdAt = System.currentTimeMillis(),
                state = state,
            )
            val dir = dirFor(imageId)
            try {
                File(dir, "${snap.id}.json").writeText(json.encodeToString(snap))
                readAll(dir).drop(MAX_PER_PHOTO).forEach { old ->
                    File(dir, "${old.id}.json").delete()
                }
            } catch (_: Exception) {
                return@withContext null
            }
            refresh(imageId)
            snap
        }

    suspend fun delete(imageId: String, snapshotId: String) = withContext(Dispatchers.IO) {
        File(dirFor(imageId), "$snapshotId.json").delete()
        refresh(imageId)
    }

    /** No-op on blank names or missing files. */
    suspend fun rename(imageId: String, snapshotId: String, name: String) =
        withContext(Dispatchers.IO) {
            val clean = name.trim().ifEmpty { return@withContext }
            val f = File(dirFor(imageId), "$snapshotId.json")
            val cur = try { json.decodeFromString<EditSnapshot>(f.readText()) }
            catch (_: Exception) { null } ?: return@withContext
            try {
                f.writeText(json.encodeToString(cur.copy(name = clean)))
            } catch (_: Exception) {
                return@withContext
            }
            refresh(imageId)
        }

    /**
     * Removes every snapshot of a deleted photo. Call from an IO context;
     * never throws.
     */
    fun deleteAllFor(imageId: String) {
        try {
            File(root, imageId).deleteRecursively()
            synchronized(flows) { flows[imageId] }?.value = emptyList()
        } catch (_: Exception) {
            // best-effort cleanup
        }
    }
}
