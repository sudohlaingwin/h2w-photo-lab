package com.lumora.photoeditor.data

import android.content.ContentUris
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import com.lumora.photoeditor.engine.NativeLib
import com.lumora.photoeditor.engine.RawDecoder

data class ImportCandidate(
    val uri: Uri,
    val displayName: String,
    val dateAdded: Long,
    val width: Int,
    val height: Int,
)

/** Library grid sort order (Lightroom parity — the grid previously only showed newest-first). */
enum class SortOrder(val label: String) {
    NEWEST("Newest first"),
    OLDEST("Oldest first"),
    NAME("Name (A–Z)"),
    RATING("Highest rated"),
    EDITED("Recently edited"),
    ;

    companion object {
        fun fromName(name: String): SortOrder =
            values().firstOrNull { it.name == name } ?: NEWEST
    }
}

/** Library grid filter (Lightroom parity). EDITED/UNEDITED key off lastEdited,
 *  which is bumped only by real edit operations (never by rating/favorite). */
enum class LibraryFilter { ALL, FAVORITES, RATED, EDITED, UNEDITED }

/**
 * RAW program Phase 2 slice 3: pure selection for the thumbnail backfill.
 * Returns the RAW rows that have no cached embedded-preview thumbnail yet
 * (RAWs imported before slice 2's import-time cache shipped), in library
 * order, capped at [maxItems] so one run can never stall startup — leftover
 * rows are picked up on the next app start. Skips blank names, non-RAW
 * files, and rows [hasThumb] already covers. Pure — pinned by SelfTest
 * check 58.
 */
fun rawRowsMissingThumbs(
    images: List<LibraryImage>,
    maxItems: Int,
    hasThumb: (String) -> Boolean,
): List<LibraryImage> {
    if (maxItems <= 0) return emptyList()
    val out = ArrayList<LibraryImage>(maxItems.coerceAtMost(64))
    for (img in images) {
        if (out.size >= maxItems) break
        if (img.displayName.isBlank()) continue
        if (!RawDecoder.isRaw(img.displayName)) continue
        if (runCatching { hasThumb(img.id) }.getOrDefault(true)) continue
        out.add(img)
    }
    return out
}

/**
 * Library: imported images (Room) + MediaStore scan for the import picker.
 * Originals are referenced, never copied or modified.
 */
class LibraryRepository(
    private val context: Context,
    private val dao: LibraryDao,
    private val editStore: EditStore,
    private val snapshots: SnapshotStore,
) {
    private companion object {
        const val TAG = "LibraryRepository"
    }
    private val _images = MutableStateFlow<List<LibraryImage>>(emptyList())
    val images: StateFlow<List<LibraryImage>> = _images.asStateFlow()
    // Whether the library contains any photos at all, regardless of the
    // active filter/search. Refreshed alongside [refresh], which every
    // mutation path calls — so this stays exact without an extra query.
    private val _hasAnyPhotos = MutableStateFlow(false)
    val hasAnyPhotos: StateFlow<Boolean> = _hasAnyPhotos.asStateFlow()

    /** Current sort order; the ViewModel owns persistence, this just re-sorts. */
    var sortOrder: SortOrder = SortOrder.NEWEST
        private set

    /** Current grid filter; refresh() and friends always re-apply it, so an
     *  active filter survives edits/imports/deletes (previously refresh()
     *  silently reset every filter back to ALL). */
    var filter: LibraryFilter = LibraryFilter.ALL
        private set

    /**
     * Current grid search query. The repository owns it (alongside [filter])
     * so that library mutations — rating, favoriting, imports, deletes,
     * batch ops, paste-edits — preserve an active search: they all funnel
     * through [refresh] -> [applyFilter], which re-applies the query. The
     * filter and the query compose as AND (Lightroom parity); previously a
     * search ignored the active filter, and any mutation silently discarded
     * the search results while the search box still showed the query.
     */
    var query: String = ""
        private set

    private var lastRaw: List<LibraryImage> = emptyList()

    private val editedThumbs by lazy { EditedThumbs(context) }

    /**
     * RAW program Phase 2 slice 2: import-time embedded-preview cache so
     * RAW photos get real grid thumbnails (Coil cannot decode RAW files).
     */
    private val rawThumbs by lazy { RawThumbs(File(context.filesDir, "rawthumbs")) }

    /**
     * Phase C#6 slice 1 — photo keywords (Lightroom culling parity). The
     * store is file-based (no Room migration risk); the repository owns the
     * active keyword filter, which AND-composes with [filter] and [query]
     * exactly like the search box does.
     */
    private val keywordStore = KeywordStore(context)
    val allKeywords: StateFlow<List<String>> = keywordStore.allKeywords

    /** Canonical (case-insensitive) keyword the grid is filtered to, or null. */
    var keywordFilter: String? = null
        private set

    /** Filter the grid to photos carrying [keyword] (null clears). */
    suspend fun setKeywordFilter(keyword: String?) {
        keywordFilter = normalizeKeyword(keyword ?: "")?.let { keywordKeyOf(it) }
        applyFilter()
    }

    fun keywordsFor(imageId: String): StateFlow<List<String>> =
        keywordStore.keywordsFor(imageId)

    suspend fun addKeyword(imageId: String, raw: String): Boolean =
        keywordStore.addKeyword(imageId, raw).also { if (it) applyFilter() }

    suspend fun removeKeyword(imageId: String, keyword: String): Boolean =
        keywordStore.removeKeyword(imageId, keyword).also { if (it) applyFilter() }

    /**
     * Phase C#6 slice 2 — smart collections (Lightroom parity): saved
     * dynamic filters combining keyword + min rating + favorites-only.
     * The store is file-based (no Room migration risk); the repository owns
     * the active collection id, which AND-composes with [filter], [query]
     * and [keywordFilter] exactly like the keyword filter does.
     */
    private val smartCollectionStore = SmartCollectionStore(context)
    val smartCollections: StateFlow<List<SmartCollection>> =
        smartCollectionStore.collections

    /** Id of the active smart collection, or null. */
    var smartCollectionId: String? = null
        private set

    /** Activate a smart collection (null clears) and re-filter the grid. */
    suspend fun setSmartCollectionId(id: String?) {
        smartCollectionId = id
        smartCollectionStore.ensureLoaded()
        applyFilter()
    }

    suspend fun createSmartCollection(
        name: String,
        keywordDisplay: String?,
        minRating: Int,
        favoritesOnly: Boolean,
    ): Boolean {
        smartCollectionStore.ensureLoaded()
        return smartCollectionStore.add(
            name, keywordDisplay, minRating, favoritesOnly) != null
    }

    /**
     * Renames / re-rules a collection. The grid re-filters so an edited
     * collection's members update immediately (collections are live).
     */
    suspend fun updateSmartCollection(
        id: String,
        name: String,
        keywordDisplay: String?,
        minRating: Int,
        favoritesOnly: Boolean,
    ): Boolean =
        smartCollectionStore.update(id, name, keywordDisplay, minRating, favoritesOnly)
            .also { if (it) applyFilter() }

    /**
     * Deletes a collection. When the deleted one was active, the grid
     * filter clears back to the plain filters (an id dangling at a deleted
     * collection would show an empty grid forever).
     */
    suspend fun deleteSmartCollection(id: String): Boolean {
        val removed = smartCollectionStore.delete(id)
        if (removed && smartCollectionId == id) {
            smartCollectionId = null
            applyFilter()
        }
        return removed
    }

    /**
     * Phase C#7 slice 1 — proofing galleries (share/select workflow for
     * working photographers): named ordered photo sets with per-photo
     * client marks (approved/rejected). File-based store (no Room
     * migration risk); the repository owns the active gallery id, which
     * AND-composes with [filter], [query], [keywordFilter] and the smart
     * collection exactly like those do.
     */
    private val proofingGalleryStore = ProofingGalleryStore(context)
    val galleries: StateFlow<List<ProofingGallery>> =
        proofingGalleryStore.galleries

    /** Id of the active proofing gallery, or null. */
    var galleryId: String? = null
        private set

    /** Activate a gallery (null clears) and re-filter the grid. */
    suspend fun setGalleryId(id: String?) {
        galleryId = id
        proofingGalleryStore.ensureLoaded()
        applyFilter()
    }

    /**
     * Creates a gallery from photo ids (grid order). Returns the created
     * gallery, or null when the name is unusable or the cap is reached.
     */
    suspend fun createProofingGallery(
        name: String,
        clientName: String?,
        photoIds: List<String>,
    ): ProofingGallery? {
        proofingGalleryStore.ensureLoaded()
        return proofingGalleryStore.add(name, clientName, photoIds)
    }

    /** Renames a gallery / edits its client name. */
    suspend fun updateProofingGallery(
        id: String,
        name: String,
        clientName: String?,
    ): Boolean =
        proofingGalleryStore.update(id, name, clientName)

    /**
     * Deletes a gallery. When the deleted one was active, the grid filter
     * clears back to the plain filters (a dangling id would show an empty
     * grid forever). The photos themselves are untouched.
     */
    suspend fun deleteProofingGallery(id: String): Boolean {
        val removed = proofingGalleryStore.delete(id)
        if (removed && galleryId == id) {
            galleryId = null
            applyFilter()
        }
        return removed
    }

    /**
     * Phase C#8 slice 1 — sync-apply for one remote gallery: inserts or
     * replaces by id (the merge already decided the remote copy is newer).
     * Re-filters when the upserted gallery is the active one so the grid
     * reflects the new membership immediately.
     */
    suspend fun importProofingGallery(gallery: ProofingGallery): Boolean {
        val ok = proofingGalleryStore.upsert(gallery)
        if (ok && galleryId == gallery.id) applyFilter()
        return ok
    }

    /**
     * Adds photos to a gallery. Returns how many were added. Re-filters
     * the grid so an active gallery shows new members immediately.
     */
    suspend fun addPhotosToGallery(id: String, photoIds: Collection<String>): Int =
        proofingGalleryStore.addPhotos(id, photoIds)
            .also { if (it > 0) applyFilter() }

    /**
     * Removes photos from a gallery (the photos stay in the library).
     * Returns how many were removed. Re-filters the grid when active.
     */
    suspend fun removePhotosFromGallery(
        id: String,
        photoIds: Collection<String>,
    ): Int =
        proofingGalleryStore.removePhotos(id, photoIds)
            .also { if (it > 0) applyFilter() }

    /**
     * Sets the client's mark on one gallery photo (null clears it).
     * Membership is unchanged, so the grid needs no re-filter — the
     * galleries flow emission drives the cell badges.
     */
    suspend fun setProofMark(
        id: String,
        photoId: String,
        mark: ProofMark?,
    ): Boolean = proofingGalleryStore.setMark(id, photoId, mark)

    /**
     * Phase C#7 slice 3 — proofing round-trip: writes a gallery's client
     * marks back to the library as the photographer's own ratings and
     * favorites (approved -> 5 stars + favorite, rejected -> 1 star and
     * unfavorited). Unmarked members are untouched; marks for photos no
     * longer in the library are skipped. Per-photo failures never abort
     * the batch (same policy as [deleteMany]/[setFavoriteMany]).
     * Returns the counts actually applied, or null when the gallery id
     * is unknown. Never throws.
     */
    suspend fun applyClientPicks(galleryId: String): ClientPicksResult? =
        withContext(Dispatchers.IO) {
            val (approvedIds, rejectedIds) =
                proofingGalleryStore.get(galleryId)?.clientPicksPlan()
                    ?: return@withContext null
            var approved = 0
            var rejected = 0
            var skipped = 0
            for (id in approvedIds) {
                val ok = runCatching {
                    if (dao.byId(id) == null) return@runCatching false
                    dao.setRating(id, 5)
                    dao.setFavorite(id, true)
                    true
                }.getOrDefault(false)
                if (ok) approved++ else skipped++
            }
            for (id in rejectedIds) {
                val ok = runCatching {
                    if (dao.byId(id) == null) return@runCatching false
                    dao.setRating(id, 1)
                    dao.setFavorite(id, false)
                    true
                }.getOrDefault(false)
                if (ok) rejected++ else skipped++
            }
            refresh()
            ClientPicksResult(approved, rejected, skipped)
        }

    private fun emitSorted(raw: List<LibraryImage>) {
        lastRaw = raw
        _images.value = when (sortOrder) {
            SortOrder.NEWEST -> raw.sortedByDescending { it.dateAdded }
            SortOrder.OLDEST -> raw.sortedBy { it.dateAdded }
            SortOrder.NAME -> raw.sortedBy { it.displayName.lowercase() }
            SortOrder.RATING ->
                raw.sortedWith(compareByDescending<LibraryImage> { it.rating }
                    .thenByDescending { it.dateAdded })
            SortOrder.EDITED ->
                raw.sortedWith(compareByDescending<LibraryImage> { it.lastEdited }
                    .thenByDescending { it.dateAdded })
        }
    }

    /** Switch sort order and re-emit the current list; call from a coroutine. */
    suspend fun setSortOrder(order: SortOrder) {
        sortOrder = order
        emitSorted(lastRaw)
    }

    suspend fun refresh() {
        _hasAnyPhotos.value = dao.imageCount() > 0
        applyFilter()
    }

    /** Re-emit the current list under the active filter AND the active
     *  search query AND the active keyword filter AND the active smart
     *  collection AND the active proofing gallery (all constraints must
     *  hold). */
    private suspend fun applyFilter() {
        keywordStore.ensureLoaded()
        smartCollectionStore.ensureLoaded()
        proofingGalleryStore.ensureLoaded()
        val base = when (filter) {
            LibraryFilter.ALL -> dao.allImages()
            LibraryFilter.FAVORITES -> dao.favorites()
            LibraryFilter.RATED -> dao.byRating(1)
            LibraryFilter.EDITED -> dao.edited()
            LibraryFilter.UNEDITED -> dao.unedited()
        }.filter { matchesKeywordFilter(it) && matchesSmartCollection(it) && matchesGallery(it) }
        val combined =
            if (query.isBlank()) base
            // Escape LIKE wildcards so a typed %, _ or \ matches literally
            // (LibraryDao.search declares ESCAPE '\'; see escapeLikePattern).
            else dao.search(escapeLikePattern(query)).filter { matchesActiveFilter(it) }
        emitSorted(combined)
    }

    /** In-memory keyword predicate (the store is pre-loaded by applyFilter). */
    private fun matchesKeywordFilter(img: LibraryImage): Boolean {
        val kw = keywordFilter ?: return true
        return keywordStore.hasKeyword(img.id, kw)
    }

    /**
     * In-memory smart-collection predicate (the store is pre-loaded by
     * applyFilter). A collection whose id no longer resolves (deleted
     * behind the grid's back) passes everything — it can never leave the
     * grid stuck empty; deletion already clears the active id anyway.
     */
    private fun matchesSmartCollection(img: LibraryImage): Boolean {
        val id = smartCollectionId ?: return true
        val coll = smartCollectionStore.collectionById(id) ?: return true
        return smartCollectionMatches(coll, img) { imageId, kw ->
            keywordStore.hasKeyword(imageId, kw)
        }
    }

    /**
     * In-memory proofing-gallery predicate (the store is pre-loaded by
     * applyFilter). A gallery whose id no longer resolves (deleted behind
     * the grid's back) passes everything — it can never leave the grid
     * stuck empty; deletion already clears the active id anyway.
     */
    private fun matchesGallery(img: LibraryImage): Boolean {
        val id = galleryId ?: return true
        val gallery = proofingGalleryStore.galleryById(id) ?: return true
        return img.id in gallery.photoIds
    }

    /** In-memory equivalent of the DAO filter queries, for AND-composing a
     *  search result set with the active filter (dao.search has no filter
     *  parameter, and the sets are small — one user-typed query). */
    private fun matchesActiveFilter(img: LibraryImage): Boolean = when (filter) {
        LibraryFilter.ALL -> true
        LibraryFilter.FAVORITES -> img.favorite
        LibraryFilter.RATED -> img.rating >= 1
        LibraryFilter.EDITED -> img.lastEdited > 0
        LibraryFilter.UNEDITED -> img.lastEdited == 0L
    } && matchesKeywordFilter(img) && matchesSmartCollection(img) && matchesGallery(img)

    /** Switch the grid filter and re-emit; call from a coroutine. */
    suspend fun setFilter(f: LibraryFilter) {
        filter = f
        applyFilter()
    }

    suspend fun search(q: String) {
        query = q
        applyFilter()
    }

    suspend fun byId(id: String): LibraryImage? = dao.byId(id)

    /** MediaStore images for the import sheet, newest first. */
    suspend fun mediaStoreImages(limit: Int = 500): List<ImportCandidate> =
        withContext(Dispatchers.IO) {
            val out = mutableListOf<ImportCandidate>()
            val proj = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.WIDTH,
                MediaStore.Images.Media.HEIGHT,
            )
            try {
                context.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    proj, null, null,
                    "${MediaStore.Images.Media.DATE_ADDED} DESC",
                )?.use { c ->
                    val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                    val dateCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                    val wCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
                    val hCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
                    while (c.moveToNext() && out.size < limit) {
                        val id = c.getLong(idCol)
                        out += ImportCandidate(
                            uri = ContentUris.withAppendedId(
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
                            displayName = c.getString(nameCol) ?: "IMG_$id",
                            dateAdded = c.getLong(dateCol),
                            width = c.getInt(wCol),
                            height = c.getInt(hCol),
                        )
                    }
                }
            } catch (e: SecurityException) {
                // No media permission: the system photo picker still works.
            }
            out
        }

    /** Imports picked URIs into the library, taking persistable permission. */
    suspend fun importUris(uris: List<Uri>): Int = importUrisWithIds(uris).size

    /**
     * Result of an import batch: how many photos landed in the library and
     * how many URIs were unreadable (revoked share, broken provider, corrupt
     * file). The count split is what the UI reports — a partial batch must
     * never look like a silent full success.
     */
    data class ImportResult(val imported: Int, val skipped: Int)

    /**
     * Imports the given URIs and reports both counts. Never throws except
     * for [CancellationException] — [importUrisWithIds] already isolates
     * per-URI failures, so a total throw here is the guarded fallback.
     */
    suspend fun importUrisResult(uris: List<Uri>): ImportResult {
        val ids = try {
            importUrisWithIds(uris)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.w(TAG, "import batch failed", t)
            emptyList()
        }
        return ImportResult(ids.size, (uris.size - ids.size).coerceAtLeast(0))
    }

    /**
     * Imports the given URIs and returns the new library row ids, in input
     * order. Per-URI isolation: one unreadable/corrupt URI (a revoked share,
     * a broken provider, a DB hiccup) only skips that single photo — it can
     * never abort the batch or crash the app. Callers keep the "in order"
     * contract for the photos that did import.
     */
    suspend fun importUrisWithIds(uris: List<Uri>): List<String> = withContext(Dispatchers.IO) {
        val ids = mutableListOf<String>()
        var skipped = 0
        for (uri in uris) {
            val id = try {
                importOneUri(uri)
            } catch (t: Throwable) {
                // Cancellation stays cooperative; anything else only costs
                // that one photo, never the batch and never the process.
                if (t is CancellationException) throw t
                Log.w(TAG, "import skipped unreadable photo: $uri", t)
                null
            }
            if (id != null) ids.add(id) else skipped++
        }
        refresh()
        if (skipped > 0) Log.i(TAG, "import finished: ${ids.size} imported, $skipped skipped")
        ids
    }

    /**
     * Imports a single URI and returns the new library row id. Does not
     * throw except for [CancellationException].
     */
    private suspend fun importOneUri(uri: Uri): String = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) { /* picker/share URIs may not support it */ }
        val name = queryDisplayName(uri) ?: "Imported photo"
        val (w, h) = querySize(uri)
        val id = UUID.randomUUID().toString()
        dao.upsertImage(
            LibraryImage(
                id = id,
                contentUri = uri.toString(),
                displayName = name,
                dateAdded = System.currentTimeMillis() / 1000,
                width = w, height = h,
                folder = "Imports",
            )
        )
        // RAW program Phase 2 slice 2: give RAW imports a real grid
        // thumbnail. Coil cannot decode CR2/NEF/DNG/..., so the cell
        // would stay blank. The camera's embedded preview JPEG is pulled
        // through the native bridge (unpack_thumb — fast, no demosaic)
        // once here, at import time on IO, and cached on disk keyed by
        // the row id. Best-effort: any failure just means no thumbnail,
        // never a failed import.
        if (RawDecoder.isRaw(name)) extractRawThumb(uri, id)
        id
    }

    /**
     * Phase 2 slice 2: stages the RAW stream into a temp file (the native
     * bridge needs a real path), extracts the camera's embedded preview
     * JPEG and caches it for the library grid. Never throws.
     */
    private fun extractRawThumb(uri: Uri, imageId: String) {
        if (!NativeLib.isAvailable) return
        val tmp = File(
            context.cacheDir,
            "h2w_rawthumb_${imageId.take(8)}_${UUID.randomUUID()}.tmp",
        )
        try {
            context.contentResolver.openInputStream(uri)?.use { ins ->
                tmp.outputStream().use { out -> ins.copyTo(out) }
            } ?: return
            val jpeg = NativeLib.dngPreview(tmp.absolutePath)
            if (jpeg != null && jpeg.isNotEmpty()) rawThumbs.save(imageId, jpeg)
        } catch (_: Exception) {
            // best-effort only — the grid falls back to the blank cell
        } finally {
            try {
                tmp.delete()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * RAW program Phase 2 slice 3: one-shot backfill of embedded-preview
     * thumbnails for RAW rows imported before slice 2 shipped its
     * import-time thumbnail cache — those grid cells would otherwise stay
     * blank forever (Coil cannot decode CR2/NEF/DNG/...). Runs on IO and
     * never throws; returns how many NEW thumbnails were cached (0 when
     * there is nothing to do). Best-effort per row: a deleted photo, an
     * unreadable stream, or a missing native bridge just skips that row.
     */
    suspend fun backfillRawThumbs(maxItems: Int = 25): Int =
        withContext(Dispatchers.IO) {
            if (!NativeLib.isAvailable) return@withContext 0
            val missing = try {
                rawRowsMissingThumbs(dao.allImages(), maxItems) { id ->
                    rawThumbs.exists(id)
                }
            } catch (_: Exception) {
                return@withContext 0
            }
            var cached = 0
            for (img in missing) {
                val uri = img.contentUri?.let {
                    runCatching { Uri.parse(it) }.getOrNull()
                } ?: continue
                try {
                    extractRawThumb(uri, img.id)
                    if (rawThumbs.exists(img.id)) cached++
                } catch (_: Exception) {
                    // per-row best-effort — keep going with the rest
                }
            }
            cached
        }

    /**
     * Display name for [uri], or null when the provider has none. Total:
     * a broken or hostile provider (SecurityException, unknown URL scheme,
     * ...) only costs the name — the import falls back to "Imported photo"
     * instead of dying.
     */
    private fun queryDisplayName(uri: Uri): String? {
        try {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    if (i >= 0) return c.getString(i)
                }
            }
        } catch (_: Exception) { }
        return uri.lastPathSegment
    }

    private fun querySize(uri: Uri): Pair<Int, Int> {
        return try {
            context.contentResolver.openInputStream(uri)?.use { ins ->
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(ins, null, opts)
                (opts.outWidth.takeIf { it > 0 } ?: 0) to (opts.outHeight.takeIf { it > 0 } ?: 0)
            } ?: (0 to 0)
        } catch (_: Exception) { 0 to 0 }
    }

    suspend fun setRating(id: String, rating: Int) {
        dao.setRating(id, rating.coerceIn(0, 5)); refresh()
    }

    suspend fun toggleFavorite(id: String) {
        val img = dao.byId(id) ?: return
        dao.setFavorite(id, !img.favorite); refresh()
    }

    suspend fun delete(id: String) {
        cleanupPhotoFiles(id)
        refresh()
    }

    /**
     * Shared per-photo cleanup for single and batch delete: library row and
     * its history, the edit sidecar, the edited-grid thumbnail and the named
     * snapshots (files + in-memory list). A single-photo delete used to skip
     * snapshots entirely (storage leak), and batch delete removed the files
     * without clearing the live snapshot list — both now go through
     * [SnapshotStore.deleteAllFor]. May throw (callers decide the policy);
     * [SnapshotStore.deleteAllFor] itself never throws.
     */
    private suspend fun cleanupPhotoFiles(id: String) {
        dao.deleteImage(id)
        dao.clearHistory(id)
        editStore.delete(id)
        editedThumbs.clear(id)
        // RAW program Phase 2 slice 2: don't leak the import-time RAW
        // embedded-preview thumbnail with the deleted photo.
        rawThumbs.clear(id)
        snapshots.deleteAllFor(id)
        // Phase C#6 slice 1: keywords die with the photo (no orphan rows).
        keywordStore.deleteAllFor(id)
        // Phase C#7 slice 1: the photo leaves every proofing gallery too
        // (no dead member ids or stale client marks).
        proofingGalleryStore.prunePhoto(id)
    }

    /**
     * Batch delete for multi-select (Lightroom parity). Each row is removed
     * with the same cleanup as [delete]; a single failing row never aborts
     * the batch. Returns the number of rows actually removed.
     */
    suspend fun deleteMany(ids: Collection<String>): Int = withContext(Dispatchers.IO) {
        var removed = 0
        ids.forEach { id ->
            runCatching {
                cleanupPhotoFiles(id)
                removed++
            }
        }
        refresh()
        removed
    }

    /**
     * Batch favorite for multi-select. Returns the number of rows updated.
     */
    suspend fun setFavoriteMany(ids: Collection<String>, favorite: Boolean): Int =
        withContext(Dispatchers.IO) {
            var updated = 0
            ids.forEach { id ->
                runCatching { dao.setFavorite(id, favorite); updated++ }
            }
            refresh()
            updated
        }

    /**
     * Lightroom-style virtual copy: a new library entry pointing at the same
     * source photo, with an independent copy of the current edits. Ratings,
     * favorites and history start fresh — the copy is a clean slate carrying
     * the same look. Returns the new id, or null when the source is gone or
     * the copy could not be created.
     */
    suspend fun duplicateImage(id: String): String? = withContext(Dispatchers.IO) {
        try {
            val src = dao.byId(id) ?: return@withContext null
            if (src.contentUri.isNullOrBlank()) return@withContext null
            val newId = UUID.randomUUID().toString()
            dao.upsertImage(
                src.copy(
                    id = newId,
                    displayName = nextCopyName(src.displayName),
                    dateAdded = System.currentTimeMillis() / 1000,
                    rating = 0,
                    favorite = false,
                    lastEdited = 0L,
                    editCount = 0,
                )
            )
            // A failed sidecar copy degrades to a fresh edit state — the row
            // itself is still a valid virtual copy.
            editStore.copySidecar(id, newId)
            refresh()
            newId
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Phase 4 "Merge to HDR" (Lightroom parity): fuses 2+ library photos —
     * typically aligned bracketed exposures — into one exposure-fused
     * composite via [com.lumora.photoeditor.engine.HdrMerge], then adds the
     * result to the library as its own editable row ("HDR merges" folder, as
     * a JPEG in app-private storage exposed through FileProvider, the same
     * route camera captures take). Honest scope — the engine is Mertens
     * exposure fusion, not AI: frames must be aligned (tripod/static scene),
     * there is no deghost pass, and the composite is built at a capped
     * working resolution (see HdrMerge). Frames are decoded downscaled to
     * that cap, normalized to the first frame's size, and recycled promptly
     * so three 12MP sources never sit in memory at once. Never throws;
     * returns the new row id, or null when fewer than 2 usable frames decode
     * or the composite cannot be written.
     */
    suspend fun mergeHdrFrames(ids: List<String>): String? =
        withContext(Dispatchers.IO) {
            try {
                if (ids.size < 2) return@withContext null
                val frames = mutableListOf<IntArray>()
                var w = 0
                var h = 0
                for (id in ids) {
                    val row = dao.byId(id) ?: continue
                    val uri = row.contentUri?.let { Uri.parse(it) } ?: continue
                    val bmp = decodeDownscaledForHdr(
                        uri, com.lumora.photoeditor.engine.HdrMerge.DEFAULT_MAX_DIM)
                        ?: continue
                    val refW = if (w > 0) w else bmp.width
                    val refH = if (h > 0) h else bmp.height
                    val sized =
                        if (bmp.width == refW && bmp.height == refH) bmp
                        else android.graphics.Bitmap.createScaledBitmap(
                            bmp, refW, refH, true)
                    val px = IntArray(refW * refH)
                    sized.getPixels(px, 0, refW, 0, 0, refW, refH)
                    if (sized !== bmp && !sized.isRecycled) sized.recycle()
                    if (!bmp.isRecycled) bmp.recycle()
                    if (w == 0) { w = refW; h = refH }
                    frames.add(px)
                }
                if (frames.size < 2 || w <= 0 || h <= 0) return@withContext null
                val fused =
                    com.lumora.photoeditor.engine.HdrMerge.fuse(frames, w, h)
                val (fw, fh) =
                    com.lumora.photoeditor.engine.HdrMerge.workingSize(w, h)
                val out = android.graphics.Bitmap.createBitmap(
                    fw, fh, android.graphics.Bitmap.Config.ARGB_8888)
                out.setPixels(fused, 0, fw, 0, 0, fw, fh)
                val dir = File(context.filesDir, "hdr").apply { mkdirs() }
                val stamp = java.text.SimpleDateFormat(
                    "yyyyMMdd_HHmmss", java.util.Locale.US)
                    .format(java.util.Date())
                val file = File(dir, "H2W_hdr_$stamp.jpg")
                try {
                    file.outputStream().use { os ->
                        if (!out.compress(
                                android.graphics.Bitmap.CompressFormat.JPEG,
                                95, os)) return@withContext null
                    }
                } finally {
                    if (!out.isRecycled) out.recycle()
                }
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    context, "${context.packageName}.fileprovider", file)
                val newId = UUID.randomUUID().toString()
                dao.upsertImage(
                    LibraryImage(
                        id = newId,
                        contentUri = uri.toString(),
                        displayName = "HDR Merge (${frames.size} frames)",
                        dateAdded = System.currentTimeMillis() / 1000,
                        width = fw, height = fh,
                        folder = "HDR merges",
                    )
                )
                refresh()
                newId
            } catch (t: Throwable) {
                // OutOfMemoryError is an Error, not an Exception: on a
                // memory-constrained phone large multi-frame composites must
                // degrade to a null result, never crash the app.
                // CancellationException must not be swallowed (it is an
                // Exception subclass) or structured concurrency breaks.
                if (t is CancellationException) throw t
                null
            }
        }

    /**
     * Phase 4 "Focus Stack" (Photoshop parity): stacks 2+ library photos —
     * typically frames shot at different focus distances — into one
     * all-in-focus composite via [com.lumora.photoeditor.engine.FocusStack],
     * then adds the result to the library as its own editable row ("Focus
     * stacks" folder, same FileProvider JPEG route as HDR merges). Honest
     * scope — the engine is modified-Laplacian focus measure plus
     * multi-resolution pyramid blending, not AI: frames must be aligned
     * (tripod/static scene), there is no alignment pass, and the composite
     * is built at a capped working resolution (see FocusStack). Never
     * throws; returns the new row id, or null when fewer than 2 usable
     * frames decode or the composite cannot be written.
     */
    suspend fun focusStackFrames(ids: List<String>): String? =
        withContext(Dispatchers.IO) {
            try {
                if (ids.size < 2) return@withContext null
                val frames = mutableListOf<IntArray>()
                var w = 0
                var h = 0
                for (id in ids) {
                    val row = dao.byId(id) ?: continue
                    val uri = row.contentUri?.let { Uri.parse(it) } ?: continue
                    val bmp = decodeDownscaledForHdr(
                        uri, com.lumora.photoeditor.engine.FocusStack.DEFAULT_MAX_DIM)
                        ?: continue
                    val refW = if (w > 0) w else bmp.width
                    val refH = if (h > 0) h else bmp.height
                    val sized =
                        if (bmp.width == refW && bmp.height == refH) bmp
                        else android.graphics.Bitmap.createScaledBitmap(
                            bmp, refW, refH, true)
                    val px = IntArray(refW * refH)
                    sized.getPixels(px, 0, refW, 0, 0, refW, refH)
                    if (sized !== bmp && !sized.isRecycled) sized.recycle()
                    if (!bmp.isRecycled) bmp.recycle()
                    if (w == 0) { w = refW; h = refH }
                    frames.add(px)
                }
                if (frames.size < 2 || w <= 0 || h <= 0) return@withContext null
                val stacked =
                    com.lumora.photoeditor.engine.FocusStack.stack(frames, w, h)
                val (fw, fh) =
                    com.lumora.photoeditor.engine.FocusStack.workingSize(w, h)
                val out = android.graphics.Bitmap.createBitmap(
                    fw, fh, android.graphics.Bitmap.Config.ARGB_8888)
                out.setPixels(stacked, 0, fw, 0, 0, fw, fh)
                val dir = File(context.filesDir, "focusstack").apply { mkdirs() }
                val stamp = java.text.SimpleDateFormat(
                    "yyyyMMdd_HHmmss", java.util.Locale.US)
                    .format(java.util.Date())
                val file = File(dir, "H2W_stack_$stamp.jpg")
                try {
                    file.outputStream().use { os ->
                        if (!out.compress(
                                android.graphics.Bitmap.CompressFormat.JPEG,
                                95, os)) return@withContext null
                    }
                } finally {
                    if (!out.isRecycled) out.recycle()
                }
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    context, "${context.packageName}.fileprovider", file)
                val newId = UUID.randomUUID().toString()
                dao.upsertImage(
                    LibraryImage(
                        id = newId,
                        contentUri = uri.toString(),
                        displayName = "Focus Stack (${frames.size} frames)",
                        dateAdded = System.currentTimeMillis() / 1000,
                        width = fw, height = fh,
                        folder = "Focus stacks",
                    )
                )
                refresh()
                newId
            } catch (t: Throwable) {
                // OutOfMemoryError is an Error, not an Exception: on a
                // memory-constrained phone large multi-frame composites must
                // degrade to a null result, never crash the app.
                // CancellationException must not be swallowed (it is an
                // Exception subclass) or structured concurrency breaks.
                if (t is CancellationException) throw t
                null
            }
        }

    /**
     * Phase 4 "Panorama" (Lightroom Photomerge parity): stitches 2+ library
     * photos — frames shot left-to-right in selection order with 20-50%
     * overlap — into one wide panorama via
     * [com.lumora.photoeditor.engine.PanoramaStitch], then adds the result to
     * the library as its own editable row ("Panoramas" folder, same
     * FileProvider JPEG route as HDR merges). Honest scope — the engine is a
     * translation-only alignment plus cross-fade composite, not AI: no
     * rotation/perspective warp, no lens-distortion correction, no
     * deghosting (see engine docs). Never throws; returns the new row id, or
     * null when fewer than 2 usable frames decode or the composite cannot be
     * written.
     */
    suspend fun panoramaStitchFrames(ids: List<String>): String? =
        withContext(Dispatchers.IO) {
            try {
                if (ids.size < 2) return@withContext null
                val frames = mutableListOf<IntArray>()
                var w = 0
                var h = 0
                for (id in ids) {
                    val row = dao.byId(id) ?: continue
                    val uri = row.contentUri?.let { Uri.parse(it) } ?: continue
                    val bmp = decodeDownscaledForHdr(
                        uri, com.lumora.photoeditor.engine.PanoramaStitch.DEFAULT_MAX_DIM)
                        ?: continue
                    val refW = if (w > 0) w else bmp.width
                    val refH = if (h > 0) h else bmp.height
                    val sized =
                        if (bmp.width == refW && bmp.height == refH) bmp
                        else android.graphics.Bitmap.createScaledBitmap(
                            bmp, refW, refH, true)
                    val px = IntArray(refW * refH)
                    sized.getPixels(px, 0, refW, 0, 0, refW, refH)
                    if (sized !== bmp && !sized.isRecycled) sized.recycle()
                    if (!bmp.isRecycled) bmp.recycle()
                    if (w == 0) { w = refW; h = refH }
                    frames.add(px)
                }
                if (frames.size < 2 || w <= 0 || h <= 0) return@withContext null
                val result =
                    com.lumora.photoeditor.engine.PanoramaStitch.stitchFull(frames, w, h)
                val (fw, fh) = result.width to result.height
                if (fw <= 0 || fh <= 0 || result.pixels.size != fw * fh)
                    return@withContext null
                val out = android.graphics.Bitmap.createBitmap(
                    fw, fh, android.graphics.Bitmap.Config.ARGB_8888)
                out.setPixels(result.pixels, 0, fw, 0, 0, fw, fh)
                val dir = File(context.filesDir, "panoramas").apply { mkdirs() }
                val stamp = java.text.SimpleDateFormat(
                    "yyyyMMdd_HHmmss", java.util.Locale.US)
                    .format(java.util.Date())
                val file = File(dir, "H2W_pano_$stamp.jpg")
                try {
                    file.outputStream().use { os ->
                        if (!out.compress(
                                android.graphics.Bitmap.CompressFormat.JPEG,
                                95, os)) return@withContext null
                    }
                } finally {
                    if (!out.isRecycled) out.recycle()
                }
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    context, "${context.packageName}.fileprovider", file)
                val newId = UUID.randomUUID().toString()
                dao.upsertImage(
                    LibraryImage(
                        id = newId,
                        contentUri = uri.toString(),
                        displayName = "Panorama (${frames.size} frames)",
                        dateAdded = System.currentTimeMillis() / 1000,
                        width = fw, height = fh,
                        folder = "Panoramas",
                    )
                )
                refresh()
                newId
            } catch (t: Throwable) {
                // OutOfMemoryError is an Error, not an Exception: on a
                // memory-constrained phone large multi-frame composites must
                // degrade to a null result, never crash the app.
                // CancellationException must not be swallowed (it is an
                // Exception subclass) or structured concurrency breaks.
                if (t is CancellationException) throw t
                null
            }
        }

    /**
     * Decodes [uri] downscaled so its longest side is at most [maxEdge] px
     * (matches HdrMerge's working cap so decode and fuse agree on memory
     * bounds). Null on any failure.
     */
    private fun decodeDownscaledForHdr(uri: Uri, maxEdge: Int): android.graphics.Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { ins ->
                BitmapFactory.decodeStream(ins, null, bounds)
            }
            val bw = bounds.outWidth
            val bh = bounds.outHeight
            if (bw <= 0 || bh <= 0) return null
            var sample = 1
            while (maxOf(bw, bh) / (sample * 2) >= maxEdge) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
            }
            context.contentResolver.openInputStream(uri)?.use { ins ->
                BitmapFactory.decodeStream(ins, null, opts)
            }
        } catch (t: Throwable) {
            // Decode can throw OutOfMemoryError on big originals; the
            // caller's composite path degrades to a null result.
            if (t is CancellationException) throw t
            null
        }
    }

    suspend fun recordEdit(id: String, label: String, stateJson: String) {
        dao.addHistory(HistoryEntry(imageId = id, timestamp = System.currentTimeMillis(),
            label = label, stateJson = stateJson))
        dao.touchEdited(id, System.currentTimeMillis())
        refresh()
    }

    suspend fun history(id: String): List<HistoryEntry> = dao.history(id)

    suspend fun clearHistory(id: String) {
        dao.clearHistory(id)
    }
}
