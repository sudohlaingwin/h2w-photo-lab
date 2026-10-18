package com.lumora.photoeditor.data.sync

import android.content.Context
import android.util.Log
import com.lumora.photoeditor.data.EditStore
import com.lumora.photoeditor.data.LibraryImage
import com.lumora.photoeditor.data.LibraryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Phase C#8 slice 1 — sync orchestration against the pluggable [SyncBackend].
 *
 * The engine builds a [SyncDocument] from the library, pushes it, pulls the
 * remote latest, merges with [mergeSyncDocuments], and applies the remote-
 * newer entries. Every public op never throws: failures come back as
 * result objects with a reason, and a failed op never leaves local state
 * half-applied (each photo applies atomically through the stores'
 * never-throw APIs; a photo that fails to apply is counted, not retried
 * blindly).
 *
 * v1 scope, stated once more where the code lives: photo FILES do not
 * transfer — an entry applies only when a photo with the same [photoKeyOf]
 * already exists on this device; tombstones clear syncable state (settings,
 * rating, favorite, keywords) but never delete the library row or the
 * file; sync is a no-op until a real backend replaces [NotConfiguredBackend]
 * (the photographer's backend project is HIS decision — nothing here
 * invents credentials, client ids, or endpoints).
 */
class SyncEngine(
    context: Context,
    private val library: LibraryRepository,
    private val editStore: EditStore,
    backend: SyncBackend = NotConfiguredBackend,
) {
    private val stateFile = File(context.filesDir, "sync_state.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    @Volatile
    var backend: SyncBackend = backend
        private set

    private var state: SyncState = loadState()

    // ---------- state ----------

    @Serializable
    private data class SyncState(
        val deviceId: String = "",
        val backendId: String = "none",
        val enabled: Boolean = false,
        val lastSyncAt: Long = 0L,
        val lastPushAt: Long = 0L,
        val lastPullAt: Long = 0L,
        val lastError: String? = null,
    )

    private fun loadState(): SyncState = runCatching {
        if (stateFile.isFile) json.decodeFromString<SyncState>(stateFile.readText())
        else null
    }.getOrNull() ?: SyncState()

    private suspend fun persistState(s: SyncState) {
        state = s
        withContext(Dispatchers.IO) {
            runCatching {
                stateFile.writeText(json.encodeToString(s))
            }.onFailure { Log.e("SyncEngine", "failed to persist sync state", it) }
        }
    }

    /** Stable install id, generated once; local bookkeeping, not a credential. */
    suspend fun deviceId(): String = mutex.withLock {
        val cur = state
        if (cur.deviceId.isNotBlank()) return cur.deviceId
        val fresh = cur.copy(deviceId = UUID.randomUUID().toString())
        persistState(fresh)
        fresh.deviceId
    }

    /** Plugs in a real backend later; persists its id. Never throws. */
    suspend fun setBackend(next: SyncBackend) = mutex.withLock {
        backend = next
        persistState(state.copy(backendId = next.backendId, lastError = null))
    }

    /** Master switch for future auto-sync (WorkManager slice). Never throws. */
    suspend fun setEnabled(enabled: Boolean) = mutex.withLock {
        persistState(state.copy(enabled = enabled))
    }

    /** Snapshot of sync bookkeeping for future settings UI. Never throws. */
    suspend fun snapshot(): SyncSnapshot = mutex.withLock {
        SyncSnapshot(
            deviceId = state.deviceId.ifBlank { null },
            backendId = backend.backendId,
            backendName = backend.displayName,
            isConfigured = backend.isConfigured,
            enabled = state.enabled,
            lastSyncAt = state.lastSyncAt,
            lastError = state.lastError,
        )
    }

    // ---------- document build ----------

    /**
     * Builds this device's [SyncDocument] from the library: edit sidecars,
     * ratings/favorites, keywords, and proofing galleries. Best-effort per
     * photo — one unreadable sidecar never aborts the document. Never
     * throws.
     */
    suspend fun buildLocalDocument(): SyncDocument = runCatching {
        val id = deviceId()
        val photos = library.images.value.mapNotNull { img ->
            runCatching { entryFor(img) }.getOrNull()
        }
        val galleries = library.galleries.value
        sanitizeSyncDocument(
            SyncDocument(
                deviceId = id,
                exportedAt = System.currentTimeMillis(),
                photos = photos,
                galleries = galleries,
            ),
        ) ?: SyncDocument(deviceId = id)
    }.getOrElse {
        Log.e("SyncEngine", "buildLocalDocument failed", it)
        SyncDocument(deviceId = runCatching { deviceId() }.getOrDefault(""))
    }

    private suspend fun entryFor(img: LibraryImage): SyncPhotoEntry {
        val raw = editStore.readRaw(img.id)
        val sidecarTs = editStore.sidecarLastModified(img.id)
        val keywords = runCatching { library.keywordsFor(img.id).value }.getOrDefault(emptyList())
        return SyncPhotoEntry(
            photoKey = photoKeyOf(img.displayName, img.width, img.height),
            updatedAt = maxOf(img.lastEdited, sidecarTs),
            deleted = false,
            settingsJson = raw ?: "",
            rating = img.rating,
            favorite = img.favorite,
            keywords = keywords,
        )
    }

    // ---------- push / pull / sync ----------

    /** Pushes the local document. Never throws. */
    suspend fun pushNow(): SyncResult {
        val b = backend
        if (!b.isConfigured) return SyncResult.NotConfigured
        val doc = buildLocalDocument()
        return when (val r = runCatching { b.upload(doc) }.getOrElse {
            SyncResult.Failed(it.message ?: it.javaClass.simpleName)
        }) {
            is SyncResult.Ok -> {
                mutex.withLock {
                    persistState(state.copy(lastPushAt = r.pushedAt, lastError = null))
                }
                r
            }
            is SyncResult.Failed -> {
                mutex.withLock { persistState(state.copy(lastError = r.reason)) }
                r
            }
            SyncResult.NotConfigured -> SyncResult.NotConfigured
        }
    }

    /** Pulls the remote latest, merges, and applies remote-newer entries. Never throws. */
    suspend fun pullNow(): SyncPullOutcome {
        val b = backend
        if (!b.isConfigured) return SyncPullOutcome.NotConfigured
        val remote = when (val d = runCatching { b.downloadLatest() }.getOrElse {
            SyncDownload.Failed(it.message ?: it.javaClass.simpleName)
        }) {
            is SyncDownload.Doc -> d.document
            SyncDownload.Empty -> return SyncPullOutcome.NoRemoteChanges
            is SyncDownload.Failed -> {
                mutex.withLock { persistState(state.copy(lastError = d.reason)) }
                return SyncPullOutcome.Failed(d.reason)
            }
            SyncDownload.NotConfigured -> return SyncPullOutcome.NotConfigured
        }
        val plan = mergeSyncDocuments(buildLocalDocument(), remote)
        val applied = applyPlan(plan)
        mutex.withLock {
            persistState(state.copy(lastPullAt = System.currentTimeMillis(), lastError = null))
        }
        return SyncPullOutcome.Applied(
            photosApplied = applied.photos,
            photosSkipped = applied.photosSkipped,
            galleriesApplied = applied.galleries,
            summary = plan.describe(),
        )
    }

    /**
     * Full round: pull (apply remote-newer) then push the merged local
     * document, so the backend converges to the union. Never throws.
     */
    suspend fun syncNow(): SyncRunOutcome {
        val pull = pullNow()
        if (pull is SyncPullOutcome.NotConfigured) return SyncRunOutcome.NotConfigured
        if (pull is SyncPullOutcome.Failed) return SyncRunOutcome.Failed(pull.reason)
        return when (val push = pushNow()) {
            is SyncResult.Ok -> {
                mutex.withLock {
                    persistState(state.copy(lastSyncAt = System.currentTimeMillis()))
                }
                SyncRunOutcome.Ok(pull, push.pushedAt)
            }
            is SyncResult.Failed -> SyncRunOutcome.Failed(push.reason)
            SyncResult.NotConfigured -> SyncRunOutcome.NotConfigured
        }
    }

    // ---------- apply ----------

    private data class AppliedCounts(
        var photos: Int = 0,
        var photosSkipped: Int = 0,
        var galleries: Int = 0,
    )

    /**
     * Applies the merge plan to this device. Photos whose key has no local
     * match are skipped (v1: files do not transfer — documented). Gallery
     * ids are backend-stable so they upsert directly. Never throws.
     */
    private suspend fun applyPlan(plan: SyncMergePlan): AppliedCounts {
        val counts = AppliedCounts()
        if (plan.photosToApply.isNotEmpty()) {
            val keyToId = library.images.value.associateBy {
                photoKeyOf(it.displayName, it.width, it.height)
            }.mapValues { it.value.id }
            for (entry in plan.photosToApply) {
                val id = keyToId[entry.photoKey]
                if (id == null) {
                    counts.photosSkipped++
                    continue
                }
                if (runCatching { applyPhotoEntry(id, entry) }.getOrDefault(false)) {
                    counts.photos++
                } else {
                    counts.photosSkipped++
                }
            }
        }
        for (g in plan.galleriesToApply) {
            if (runCatching { library.importProofingGallery(g) }.getOrDefault(false)) {
                counts.galleries++
            }
        }
        return counts
    }

    /**
     * Applies one remote entry to one local photo: settings sidecar,
     * rating, favorite, keywords — or the tombstone reset. Returns false
     * when the entry could not be applied (e.g. corrupt settings payload).
     * Never throws.
     */
    private suspend fun applyPhotoEntry(id: String, entry: SyncPhotoEntry): Boolean {
        val img = library.byId(id) ?: return false
        if (entry.deleted) {
            editStore.delete(id)
            if (img.rating != 0) library.setRating(id, 0)
            if (img.favorite) library.setFavoriteMany(listOf(id), false)
            for (kw in library.keywordsFor(id).value) library.removeKeyword(id, kw)
            return true
        }
        if (entry.settingsJson.isNotEmpty()) {
            if (!editStore.saveRaw(id, entry.settingsJson)) return false
        } else {
            editStore.delete(id)
        }
        if (img.rating != entry.rating) library.setRating(id, entry.rating)
        if (img.favorite != entry.favorite) library.setFavoriteMany(listOf(id), entry.favorite)
        val remote = entry.keywords.toSet()
        val local = library.keywordsFor(id).value.toSet()
        for (kw in local - remote) library.removeKeyword(id, kw)
        for (kw in remote - local) library.addKeyword(id, kw)
        return true
    }
}

/** Read-only sync bookkeeping snapshot for future settings UI. */
data class SyncSnapshot(
    val deviceId: String?,
    val backendId: String,
    val backendName: String,
    val isConfigured: Boolean,
    val enabled: Boolean,
    val lastSyncAt: Long,
    val lastError: String?,
)

/** Outcome of [SyncEngine.pullNow]. */
sealed interface SyncPullOutcome {
    data class Applied(
        val photosApplied: Int,
        val photosSkipped: Int,
        val galleriesApplied: Int,
        val summary: String,
    ) : SyncPullOutcome

    data object NoRemoteChanges : SyncPullOutcome
    data class Failed(val reason: String) : SyncPullOutcome
    data object NotConfigured : SyncPullOutcome
}

/** Outcome of [SyncEngine.syncNow]. */
sealed interface SyncRunOutcome {
    data class Ok(val pull: SyncPullOutcome, val pushedAt: Long) : SyncRunOutcome
    data class Failed(val reason: String) : SyncRunOutcome
    data object NotConfigured : SyncRunOutcome
}
