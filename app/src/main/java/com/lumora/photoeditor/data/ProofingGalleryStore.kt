package com.lumora.photoeditor.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phase C#7 slice 1 — persistent store for proofing galleries.
 *
 * File-based like SmartCollectionStore/KeywordStore — a single JSON array at
 * filesDir/proofing_galleries.json — so the Room library DB needs no
 * migration (a schema bump without a tested migration would crash the app
 * on upgrade for every existing install; this sidesteps that entirely).
 *
 * All public ops run on IO, never throw (a corrupt file degrades to an
 * empty gallery list rather than breaking the library), and entries are
 * [sanitizeProofingGallery]-cleaned on load and on write.
 */
class ProofingGalleryStore(context: Context) {

    private val file = File(context.filesDir, "proofing_galleries.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val loaded = AtomicBoolean(false)

    /** Insertion order; guarded by [mutex]. */
    private val data = mutableListOf<ProofingGallery>()

    private val _galleries = MutableStateFlow<List<ProofingGallery>>(emptyList())
    val galleries: StateFlow<List<ProofingGallery>> = _galleries.asStateFlow()

    /** Loads the file once, on IO. Idempotent; safe to call on every op. */
    suspend fun ensureLoaded() {
        if (loaded.get()) return
        mutex.withLock {
            if (loaded.get()) return
            withContext(Dispatchers.IO) {
                runCatching {
                    if (file.isFile) {
                        val raw: List<ProofingGallery> =
                            json.decodeFromString(file.readText())
                        data += raw.mapNotNull { sanitizeProofingGallery(it) }
                            .take(MAX_PROOFING_GALLERIES)
                    }
                }.onFailure {
                    Log.e("ProofingGalleryStore",
                        "proofing_galleries.json unreadable, starting empty", it)
                }
            }
            loaded.set(true)
            _galleries.value = data.toList()
        }
    }

    /**
     * Creates a gallery. Returns the new entry, or null when the name is
     * unusable or the cap is reached. Never throws.
     */
    suspend fun add(
        name: String,
        clientName: String?,
        photoIds: List<String>,
    ): ProofingGallery? {
        val cleanName = normalizeGalleryName(name) ?: return null
        ensureLoaded()
        return mutex.withLock {
            if (data.size >= MAX_PROOFING_GALLERIES) return null
            val now = System.currentTimeMillis()
            val gallery = sanitizeProofingGallery(
                ProofingGallery(
                    id = UUID.randomUUID().toString(),
                    name = cleanName,
                    clientName = normalizeClientName(clientName),
                    createdAt = now,
                    photoIds = photoIds.distinct().take(MAX_GALLERY_PHOTOS),
                    updatedAt = now,
                )
            ) ?: return null
            data.add(gallery)
            persistLocked()
            _galleries.value = data.toList()
            gallery
        }
    }

    /**
     * Renames a gallery / edits its client name. Returns false when the id
     * is unknown or the new name is unusable. Never throws.
     */
    suspend fun update(id: String, name: String, clientName: String?): Boolean {
        val cleanName = normalizeGalleryName(name) ?: return false
        ensureLoaded()
        return mutex.withLock {
            val i = data.indexOfFirst { it.id == id }
            if (i < 0) return false
            val cur = data[i]
            data[i] = cur.copy(
                name = cleanName,
                clientName = normalizeClientName(clientName),
                updatedAt = System.currentTimeMillis(),
            )
            persistLocked()
            _galleries.value = data.toList()
            true
        }
    }

    /** Deletes a gallery; false when the id is unknown. Never throws. */
    suspend fun delete(id: String): Boolean {
        ensureLoaded()
        return mutex.withLock {
            val removed = data.removeAll { it.id == id }
            if (!removed) return false
            persistLocked()
            _galleries.value = data.toList()
            true
        }
    }

    /**
     * Phase C#8 slice 1 — sync-apply: inserts or replaces the gallery with
     * the same id, keeping the remote entry's own [ProofingGallery.updatedAt]
     * (the merge already decided it is newer; do NOT re-stamp it, or the
     * next merge would flap). The entry is [sanitizeProofingGallery]-cleaned
     * and the gallery cap is enforced. Returns false when the entry is
     * unusable or the cap blocks a brand-new id. Never throws.
     */
    suspend fun upsert(gallery: ProofingGallery): Boolean {
        val clean = sanitizeProofingGallery(gallery) ?: return false
        ensureLoaded()
        return mutex.withLock {
            val i = data.indexOfFirst { it.id == clean.id }
            if (i < 0) {
                if (data.size >= MAX_PROOFING_GALLERIES) return false
                data.add(clean)
            } else {
                data[i] = clean
            }
            persistLocked()
            _galleries.value = data.toList()
            true
        }
    }

    /**
     * Returns the current entry for [id], or null when unknown.
     * Never throws. Phase C#7 slice 3 (proofing round-trip).
     */
    suspend fun get(id: String): ProofingGallery? {
        ensureLoaded()
        return mutex.withLock { data.firstOrNull { it.id == id } }
    }

    /**
     * Appends photo ids (in order, skipping duplicates and the cap).
     * Returns how many were actually added. Never throws.
     */
    suspend fun addPhotos(id: String, photoIds: Collection<String>): Int {
        if (photoIds.isEmpty()) return 0
        ensureLoaded()
        return mutex.withLock {
            val i = data.indexOfFirst { it.id == id }
            if (i < 0) return 0
            val cur = data[i]
            val room = MAX_GALLERY_PHOTOS - cur.photoIds.size
            if (room <= 0) return 0
            val fresh = photoIds.distinct()
                .filter { it !in cur.photoIds }
                .take(room)
            if (fresh.isEmpty()) return 0
            data[i] = cur.copy(
                photoIds = cur.photoIds + fresh,
                updatedAt = System.currentTimeMillis(),
            )
            persistLocked()
            _galleries.value = data.toList()
            fresh.size
        }
    }

    /**
     * Removes photo ids from a gallery (marks for removed photos are
     * dropped). Does NOT delete the photos themselves. Returns how many
     * were actually removed. Never throws.
     */
    suspend fun removePhotos(id: String, photoIds: Collection<String>): Int {
        if (photoIds.isEmpty()) return 0
        ensureLoaded()
        return mutex.withLock {
            val i = data.indexOfFirst { it.id == id }
            if (i < 0) return 0
            val cur = data[i]
            val drop = photoIds.toSet()
            if (cur.photoIds.none { it in drop }) return 0
            val kept = cur.photoIds.filter { it !in drop }
            data[i] = cur.copy(
                photoIds = kept,
                marks = cur.marks.filterKeys { it !in drop },
                updatedAt = System.currentTimeMillis(),
            )
            persistLocked()
            _galleries.value = data.toList()
            cur.photoIds.size - kept.size
        }
    }

    /**
     * Sets the client's mark on one gallery photo; a null mark clears it.
     * Only members can be marked. Returns false when the id is unknown or
     * the photo is not a member. Never throws.
     */
    suspend fun setMark(
        id: String,
        photoId: String,
        mark: ProofMark?,
    ): Boolean {
        ensureLoaded()
        return mutex.withLock {
            val i = data.indexOfFirst { it.id == id }
            if (i < 0) return false
            val cur = data[i]
            if (photoId !in cur.photoIds) return false
            val next = if (mark == null) cur.marks - photoId
            else cur.marks + (photoId to mark)
            if (next == cur.marks) return false
            data[i] = cur.copy(
                marks = next,
                updatedAt = System.currentTimeMillis(),
            )
            persistLocked()
            _galleries.value = data.toList()
            true
        }
    }

    /**
     * Removes a deleted library photo from every gallery (members and
     * marks). Called from the repository's photo cleanup so galleries
     * never point at dead rows. Never throws.
     */
    suspend fun prunePhoto(photoId: String) {
        ensureLoaded()
        mutex.withLock {
            var dirty = false
            data.forEachIndexed { i, g ->
                if (photoId in g.photoIds) {
                    data[i] = g.copy(
                        photoIds = g.photoIds - photoId,
                        marks = g.marks - photoId,
                    )
                    dirty = true
                }
            }
            if (dirty) {
                persistLocked()
                _galleries.value = data.toList()
            }
        }
    }

    /**
     * Sync lookup by id; call [ensureLoaded] first (the repository's
     * [LibraryRepository.applyFilter] guarantees this).
     */
    fun galleryById(id: String): ProofingGallery? = data.firstOrNull { it.id == id }

    /** Writes the whole list; must hold [mutex]. Failures are logged, never thrown. */
    private suspend fun persistLocked() {
        withContext(Dispatchers.IO) {
            runCatching {
                file.writeText(json.encodeToString(data.toList()))
            }.onFailure { Log.e("ProofingGalleryStore", "failed to persist galleries", it) }
        }
    }
}
