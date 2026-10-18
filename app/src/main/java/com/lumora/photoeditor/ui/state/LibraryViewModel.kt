package com.lumora.photoeditor.ui.state

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumora.photoeditor.core.AppContainer
import com.lumora.photoeditor.data.ImportCandidate
import com.lumora.photoeditor.data.LibraryImage
import com.lumora.photoeditor.data.Presets
import com.lumora.photoeditor.data.LibraryFilter
import com.lumora.photoeditor.data.SortOrder
import com.lumora.photoeditor.data.SmartCollection
import com.lumora.photoeditor.data.ProofingGallery
import com.lumora.photoeditor.data.ProofMark
import com.lumora.photoeditor.data.ClientPicksResult
import com.lumora.photoeditor.data.keywordKeyOf
import com.lumora.photoeditor.data.normalizeKeyword
import com.lumora.photoeditor.engine.Preset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LibraryViewModel(private val container: AppContainer) : ViewModel() {

    val images: StateFlow<List<LibraryImage>> = container.library.images
    /** True when the library has any photos, regardless of filter/search. */
    val hasAnyPhotos: StateFlow<Boolean> = container.library.hasAnyPhotos
    private val _filter = MutableStateFlow(LibraryFilter.ALL)
    val filter = _filter.asStateFlow()
    private val _query = MutableStateFlow("")
    val query = _query.asStateFlow()

    private val _sortOrder =
        MutableStateFlow(SortOrder.fromName(container.settings.librarySortOrderName))
    val sortOrder = _sortOrder.asStateFlow()

    private val _importing = MutableStateFlow(false)
    val importing = _importing.asStateFlow()

    private val _candidates = MutableStateFlow<List<ImportCandidate>>(emptyList())
    val candidates = _candidates.asStateFlow()

    init {
        viewModelScope.launch {
            container.library.setSortOrder(_sortOrder.value)
            container.library.refresh()
        }
        // RAW program Phase 2 slice 3: one-shot backfill of grid thumbnails
        // for RAW photos imported before the import-time thumbnail cache
        // existed. Runs once per process on IO, never blocks the grid — the
        // cells recompose when thumbRevision bumps and pick up the new
        // thumbnails without a manual refresh. Never throws.
        viewModelScope.launch {
            val cached = try {
                withContext(Dispatchers.IO) { container.library.backfillRawThumbs() }
            } catch (_: Exception) {
                0
            }
            if (cached > 0) container.thumbRevision.value++
        }
    }

    fun setSortOrder(order: SortOrder) {
        if (_sortOrder.value == order) return
        _sortOrder.value = order
        container.settings.librarySortOrderName = order.name
        viewModelScope.launch { container.library.setSortOrder(order) }
    }

    /** Library grid density (2..5 columns); persisted in AppSettings. */
    val gridColumns = container.settings.libraryGridColumns

    fun setGridColumns(n: Int) = container.settings.setLibraryGridColumns(n)

    fun setFilter(f: LibraryFilter) {
        if (_filter.value == f) return
        _filter.value = f
        viewModelScope.launch { container.library.setFilter(f) }
    }

    /**
     * Phase C#6 slice 1 — keyword filter for the grid (Lightroom culling
     * parity). The repository ANDs it with the enum filter and the search
     * query; [keywordKey] is the canonical (case-insensitive) form, null
     * clears the filter.
     */
    private val _keywordFilter = MutableStateFlow<String?>(null)
    val keywordFilter = _keywordFilter.asStateFlow()

    val allKeywords: StateFlow<List<String>> = container.library.allKeywords

    fun keywordsFor(imageId: String): StateFlow<List<String>> =
        container.library.keywordsFor(imageId)

    fun setKeywordFilter(keyword: String?) {
        val key = keyword?.let {
            normalizeKeyword(it)?.let(::keywordKeyOf)
        }
        if (_keywordFilter.value == key) return
        _keywordFilter.value = key
        viewModelScope.launch { container.library.setKeywordFilter(keyword) }
    }

    fun addKeyword(imageId: String, raw: String) {
        viewModelScope.launch { container.library.addKeyword(imageId, raw) }
    }

    fun removeKeyword(imageId: String, keyword: String) {
        viewModelScope.launch { container.library.removeKeyword(imageId, keyword) }
    }

    /**
     * Phase C#6 slice 2 — smart collections (Lightroom parity): saved
     * dynamic filters (keyword + min rating + favorites-only) whose
     * members stay live as photos are rated, favorited, or tagged.
     */
    val smartCollections: StateFlow<List<SmartCollection>> =
        container.library.smartCollections

    private val _activeSmartCollectionId = MutableStateFlow<String?>(null)
    val activeSmartCollectionId = _activeSmartCollectionId.asStateFlow()

    fun setSmartCollection(id: String?) {
        if (_activeSmartCollectionId.value == id) return
        _activeSmartCollectionId.value = id
        viewModelScope.launch { container.library.setSmartCollectionId(id) }
    }

    fun createSmartCollection(
        name: String,
        keyword: String?,
        minRating: Int,
        favoritesOnly: Boolean,
        onDone: (Boolean) -> Unit,
    ) {
        viewModelScope.launch {
            val ok = try {
                container.library.createSmartCollection(
                    name, keyword, minRating, favoritesOnly)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                false
            }
            onDone(ok)
        }
    }

    fun updateSmartCollection(
        id: String,
        name: String,
        keyword: String?,
        minRating: Int,
        favoritesOnly: Boolean,
        onDone: (Boolean) -> Unit,
    ) {
        viewModelScope.launch {
            val ok = try {
                container.library.updateSmartCollection(
                    id, name, keyword, minRating, favoritesOnly)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                false
            }
            onDone(ok)
        }
    }

    fun deleteSmartCollection(id: String) {
        viewModelScope.launch {
            val removed = try {
                container.library.deleteSmartCollection(id)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                false
            }
            // The repository already cleared its own active id; keep the
            // ViewModel's selection in sync so the chip row and the grid
            // never disagree.
            if (removed && _activeSmartCollectionId.value == id)
                _activeSmartCollectionId.value = null
        }
    }

    /**
     * Phase C#7 slice 1 — proofing galleries (share/select workflow):
     * named ordered photo sets with per-photo client marks. onDone
     * receives the created gallery (null when the name was unusable or
     * the cap was reached).
     */
    val galleries: StateFlow<List<ProofingGallery>> =
        container.library.galleries

    private val _activeGalleryId = MutableStateFlow<String?>(null)
    val activeGalleryId = _activeGalleryId.asStateFlow()

    fun setGallery(id: String?) {
        if (_activeGalleryId.value == id) return
        _activeGalleryId.value = id
        viewModelScope.launch { container.library.setGalleryId(id) }
    }

    fun createProofingGallery(
        name: String,
        clientName: String?,
        photoIds: List<String>,
        onDone: (ProofingGallery?) -> Unit,
    ) {
        viewModelScope.launch {
            val gallery = try {
                container.library.createProofingGallery(name, clientName, photoIds)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                null
            }
            if (gallery != null) {
                _activeGalleryId.value = gallery.id
                viewModelScope.launch { container.library.setGalleryId(gallery.id) }
            }
            onDone(gallery)
        }
    }

    fun updateProofingGallery(
        id: String,
        name: String,
        clientName: String?,
        onDone: (Boolean) -> Unit,
    ) {
        viewModelScope.launch {
            val ok = try {
                container.library.updateProofingGallery(id, name, clientName)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                false
            }
            onDone(ok)
        }
    }

    fun deleteProofingGallery(id: String) {
        viewModelScope.launch {
            val removed = try {
                container.library.deleteProofingGallery(id)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                false
            }
            if (removed && _activeGalleryId.value == id)
                _activeGalleryId.value = null
        }
    }

    fun addPhotosToGallery(id: String, photoIds: Collection<String>, onDone: (Int) -> Unit) {
        viewModelScope.launch {
            val n = try {
                container.library.addPhotosToGallery(id, photoIds)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                0
            }
            onDone(n)
        }
    }

    fun removePhotosFromGallery(
        id: String,
        photoIds: Collection<String>,
        onDone: (Int) -> Unit,
    ) {
        viewModelScope.launch {
            val n = try {
                container.library.removePhotosFromGallery(id, photoIds)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                0
            }
            onDone(n)
        }
    }

    /** Cycles a gallery photo's client mark; the galleries flow drives the badges. */
    fun setProofMark(galleryId: String, photoId: String, mark: ProofMark?) {
        viewModelScope.launch {
            try {
                container.library.setProofMark(galleryId, photoId, mark)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
            }
        }
    }

    /**
     * Phase C#7 slice 3 — proofing round-trip: applies a gallery's client
     * marks to the library's ratings/favorites. onDone gets the counts,
     * or null when the gallery is unknown.
     */
    fun applyClientPicks(galleryId: String, onDone: (ClientPicksResult?) -> Unit) {
        viewModelScope.launch {
            val result = try {
                container.library.applyClientPicks(galleryId)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                null
            }
            onDone(result)
        }
    }

    fun search(q: String) {
        _query.value = q
        viewModelScope.launch { container.library.search(q) }
    }

    fun loadCandidates() {
        viewModelScope.launch { _candidates.value = container.library.mediaStoreImages() }
    }

    fun importUris(uris: List<Uri>, onDone: (Int) -> Unit) {
        viewModelScope.launch {
            _importing.value = true
            val n = container.library.importUris(uris)
            _importing.value = false
            onDone(n)
        }
    }

    /**
     * Imports photos shared from another app (ACTION_SEND). onDone receives the
     * new library row ids in order; empty when nothing could be imported.
     */
    fun importSharedUris(uris: List<Uri>, onDone: (List<String>) -> Unit) {
        viewModelScope.launch {
            _importing.value = true
            val ids = try {
                container.library.importUrisWithIds(uris)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                emptyList()
            }
            _importing.value = false
            onDone(ids)
        }
    }

    /**
     * Imports a freshly captured camera photo (TakePicture output URI).
     * onDone receives the new library row ids in order; empty when the
     * import failed. A bad capture can never crash the app.
     */
    fun importCameraPhoto(uri: Uri, onDone: (List<String>) -> Unit) {
        viewModelScope.launch {
            _importing.value = true
            val ids = try {
                container.library.importUrisWithIds(listOf(uri))
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                emptyList()
            }
            _importing.value = false
            onDone(ids)
        }
    }

    private val _merging = MutableStateFlow(false)
    val merging: StateFlow<Boolean> = _merging.asStateFlow()

    /**
     * Phase 4 "Merge to HDR" (Lightroom parity) for the selection-mode action
     * bar: fuses 2+ selected photos into one exposure-fused composite via
     * [LibraryRepository.mergeHdrFrames] and onDone gets the new library row
     * id, or null on failure. Honest scope: aligned bracketed frames only,
     * no deghost pass (see repository docs). Never throws.
     */
    fun mergeHdr(ids: List<String>, onDone: (String?) -> Unit) {
        val ordered = ids.toList()
        if (ordered.size < 2) { onDone(null); return }
        viewModelScope.launch {
            _merging.value = true
            val newId = try {
                container.library.mergeHdrFrames(ordered)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                null
            }
            _merging.value = false
            onDone(newId)
        }
    }

    private val _stacking = MutableStateFlow(false)
    val stacking: StateFlow<Boolean> = _stacking.asStateFlow()

    /**
     * Phase 4 "Focus Stack" (Photoshop parity) for the selection-mode action
     * bar: stacks 2+ selected photos into one all-in-focus composite via
     * [LibraryRepository.focusStackFrames] and onDone gets the new library
     * row id, or null on failure. Honest scope: aligned frames at different
     * focus distances only, no alignment pass (see repository docs).
     * Never throws.
     *
     * Uses its own [_stacking] busy flag (not [_merging]): the two jobs are
     * independent and the library shows a distinct "Stacking focus…" label
     * while a stack runs.
     */
    fun focusStack(ids: List<String>, onDone: (String?) -> Unit) {
        val ordered = ids.toList()
        if (ordered.size < 2) { onDone(null); return }
        viewModelScope.launch {
            _stacking.value = true
            val newId = try {
                container.library.focusStackFrames(ordered)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                null
            }
            _stacking.value = false
            onDone(newId)
        }
    }

    private val _stitching = MutableStateFlow(false)
    val stitching: StateFlow<Boolean> = _stitching.asStateFlow()

    /**
     * Phase 4 "Panorama" (Lightroom Photomerge parity) for the selection-mode
     * action bar: stitches 2+ selected photos (left-to-right, 20-50%
     * overlap) into one wide panorama via
     * [LibraryRepository.panoramaStitchFrames] and onDone gets the new
     * library row id, or null on failure. Honest scope: translation-only
     * alignment, no warp/deghost (see repository docs). Never throws.
     */
    fun panorama(ids: List<String>, onDone: (String?) -> Unit) {
        val ordered = ids.toList()
        if (ordered.size < 2) { onDone(null); return }
        viewModelScope.launch {
            _stitching.value = true
            val newId = try {
                container.library.panoramaStitchFrames(ordered)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                null
            }
            _stitching.value = false
            onDone(newId)
        }
    }

    fun setRating(id: String, rating: Int) {
        viewModelScope.launch { container.library.setRating(id, rating) }
    }

    fun toggleFavorite(id: String) {
        viewModelScope.launch { container.library.toggleFavorite(id) }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            try {
                container.library.delete(id)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                // cleanupPhotoFiles documents that it may throw (Room/file
                // I/O); a failing single delete must degrade to a no-op,
                // never an app crash. Same policy as deleteMany below.
                Log.w("LibraryViewModel", "delete failed for photo $id", t)
            }
        }
    }

    /** Batch delete for multi-select; onDone receives the number removed. */
    fun deleteMany(ids: Set<String>, onDone: (Int) -> Unit) {
        if (ids.isEmpty()) { onDone(0); return }
        viewModelScope.launch {
            val n = try { container.library.deleteMany(ids) } catch (t: Throwable) {
                if (t is CancellationException) throw t
                0
            }
            onDone(n)
        }
    }

    /** Batch favorite for multi-select; onDone receives the number updated. */
    fun favoriteMany(ids: Set<String>, onDone: (Int) -> Unit) {
        if (ids.isEmpty()) { onDone(0); return }
        viewModelScope.launch {
            val n = try { container.library.setFavoriteMany(ids, true) } catch (t: Throwable) {
                if (t is CancellationException) throw t
                0
            }
            onDone(n)
        }
    }

    /**
     * Batch-apply a preset to selected photos (Lightroom "Sync settings" parity).
     * The preset blends over each photo's current edits with [strength] via
     * [Presets.apply] — the exact same semantics as applying a preset in the
     * editor panel — so partial strengths fade the look in, and each photo's
     * history records "Apply preset: <name>". Per-photo try/catch: one corrupt
     * sidecar must not abort the rest of the batch. onDone receives the number
     * of photos updated.
     */
    fun applyPresetToMany(
        ids: Set<String>,
        preset: Preset,
        strength: Float,
        onDone: (Int) -> Unit,
    ) {
        if (ids.isEmpty()) { onDone(0); return }
        viewModelScope.launch {
            var n = 0
            for (id in ids) {
                try {
                    val current = container.editStore.load(id)
                    val next = Presets.apply(current, preset, strength)
                    container.editStore.save(id, next)
                    container.library.recordEdit(
                        id, "Apply preset: ${preset.name}",
                        container.editStore.encode(next),
                    )
                    n++
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // One bad sidecar must not abort the rest of the batch.
                }
            }
            // Sidecars changed behind the grid's back: any cached edited
            // thumbnails are now stale — bump so the grid re-checks.
            if (n > 0) container.thumbRevision.value++
            onDone(n)
        }
    }

    /** Creates a virtual copy of a library photo; onDone gets the new id or null. */
    fun duplicate(id: String, onDone: (String?) -> Unit) {
        viewModelScope.launch { onDone(container.library.duplicateImage(id)) }
    }

    /**
     * Paste-settings onto a library photo: writes the clipboard edit state
     * (minus image-specific retouch/AI-mask work) to that photo's sidecar and
     * records a "Paste edits" history entry.
     */
    fun pasteEditsTo(imageId: String, onDone: (Boolean) -> Unit) {
        val raw = container.copiedEdits.value ?: run { onDone(false); return }
        viewModelScope.launch {
            val ok = applyCopiedEdits(raw, imageId)
            // The sidecar changed behind the editor's back: any cached
            // edited thumbnail is now stale — bump so the grid re-checks.
            if (ok) container.thumbRevision.value++
            onDone(ok)
        }
    }

    /**
     * Batch paste-settings onto every selected photo (Lightroom "Sync
     * settings" parity for the selection-mode action bar). onDone gets the
     * number of photos that pasted successfully.
     */
    fun pasteEditsToMany(ids: Set<String>, onDone: (Int) -> Unit) {
        val raw = container.copiedEdits.value ?: run { onDone(0); return }
        viewModelScope.launch {
            var n = 0
            for (id in ids) {
                if (applyCopiedEdits(raw, id)) n++
            }
            // Sidecars changed behind the grid's back: bump once so every
            // edited thumbnail is re-checked (same pattern as deleteMany).
            if (n > 0) container.thumbRevision.value++
            onDone(n)
        }
    }

    /**
     * Copy-settings from a library photo: snapshots that photo's sidecar edit
     * state into the session clipboard, so it can be pasted onto other
     * photos without opening the editor first (the editor's "Copy edits",
     * single-photo "Paste edits", and multi-select batch paste all share
     * this clipboard — Lightroom "Copy settings" parity). Paste sanitizes
     * at paste time, so the raw state is copied. Never throws; onDone gets
     * success (a photo with no edits still copies a valid default state).
     */
    fun copyEditsFrom(imageId: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = try {
                container.copiedEdits.value =
                    container.editStore.encode(container.editStore.load(imageId))
                true
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                false
            }
            onDone(ok)
        }
    }

    /**
     * Shared core for pasteEditsTo / pasteEditsToMany: writes the clipboard
     * edit state (minus image-specific retouch/AI-mask work) to the photo's
     * sidecar and records a "Paste edits" history entry. Never throws.
     */
    private suspend fun applyCopiedEdits(raw: String, imageId: String): Boolean {
        return try {
            val state = container.editStore.decode(raw).sanitizedForPaste()
            container.editStore.save(imageId, state)
            container.library.recordEdit(
                imageId, "Paste edits", container.editStore.encode(state))
            true
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            false
        }
    }
}
