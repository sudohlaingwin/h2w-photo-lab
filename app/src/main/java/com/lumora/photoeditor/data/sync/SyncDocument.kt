package com.lumora.photoeditor.data.sync

import com.lumora.photoeditor.data.ProofingGallery
import com.lumora.photoeditor.data.normalizeKeyword
import com.lumora.photoeditor.data.sanitizeProofingGallery
import kotlinx.serialization.Serializable

/**
 * Phase C#8 slice 1 — cloud-sync app-side foundation: the syncable document
 * model.
 *
 * What syncs (v1 scope, honest): per-photo edit state + rating/favorite +
 * keywords, and proofing galleries (membership + client marks). The document
 * is the backend-agnostic wire format: any future backend (his Drive
 * project, his own server — HIS decision, never invented here) only needs
 * to store and return this JSON.
 *
 * v1 limits, stated plainly so no future slice can quietly pretend
 * otherwise:
 * - Photo FILES do not transfer: an entry only applies to a photo that
 *   already exists on this device (matched by [photoKeyOf]). File upload/
 *   download is a later slice.
 * - [photoKeyOf] is displayName + dimensions, NOT a content hash: the same
 *   original imported on two devices matches when name and dimensions
 *   match. True content-hash identity is a later slice; the merge logic
 *   is key-agnostic, so upgrading the key changes nothing else.
 * - Tombstones propagate edit/metadata state (settings reset, rating and
 *   favorite cleared, keywords removed) but never delete the library
 *   row or the file. Gallery deletes do not propagate yet.
 * - Sync is disabled until a real backend plugs into [SyncBackend]; the
 *   engine ships with [NotConfiguredBackend] so nothing ever pretends to
 *   sync when there is nowhere to sync to.
 */
const val SYNC_SCHEMA_VERSION = 1

/** Hard cap so one corrupt/huge remote document cannot OOM the merge. */
const val MAX_SYNC_PHOTOS = 20000

/**
 * v1 photo identity across devices: the original file's display name plus
 * its pixel dimensions. Deterministic and computable on-device with no
 * network, no new DB columns (Room migrations are deliberately avoided in
 * this codebase), and no file reads. See the v1-limits note above: a
 * content-hash key is the planned upgrade; [SyncMerge] never sees this
 * function, only its output, so the swap is mechanical.
 * Pure.
 */
fun photoKeyOf(displayName: String, width: Int, height: Int): String =
    "${displayName.trim()}|${width}x$height"

/**
 * One photo's syncable state. Device-local row ids never leave the device —
 * only the cross-device [photoKey] travels. [updatedAt] is millis (the max
 * of the library row's lastEdited and the edit sidecar's last-modified, so
 * both an edit and a metadata touch move the clock).
 */
@Serializable
data class SyncPhotoEntry(
    val photoKey: String,
    val updatedAt: Long = 0L,
    /** Tombstone: the photo's syncable state was cleared on the source. */
    val deleted: Boolean = false,
    val settingsVersion: Int = SYNC_SCHEMA_VERSION,
    /** Opaque EditState JSON sidecar bytes (format-versioned by the app). */
    val settingsJson: String = "",
    val rating: Int = 0,
    val favorite: Boolean = false,
    val keywords: List<String> = emptyList(),
)

/**
 * The whole syncable snapshot one device exports. [deviceId] is a random
 * install id (local bookkeeping, not a credential); it only exists so a
 * backend can tell "my own upload" from another device's.
 */
@Serializable
data class SyncDocument(
    val schemaVersion: Int = SYNC_SCHEMA_VERSION,
    val deviceId: String = "",
    val exportedAt: Long = 0L,
    val photos: List<SyncPhotoEntry> = emptyList(),
    val galleries: List<ProofingGallery> = emptyList(),
)

/**
 * Sanitizes one entry: blank keys are dropped (null), ratings clamped,
 * keywords normalized/de-duplicated/capped, tombstones keep no payload.
 * Pure.
 */
fun sanitizeSyncPhotoEntry(e: SyncPhotoEntry): SyncPhotoEntry? {
    if (e.photoKey.isBlank()) return null
    val keywords = e.keywords
        .mapNotNull { normalizeKeyword(it) }
        .distinct()
        .take(100)
    return if (e.deleted) {
        e.copy(
            photoKey = e.photoKey.trim(),
            updatedAt = e.updatedAt.coerceAtLeast(0L),
            settingsJson = "",
            rating = 0,
            favorite = false,
            keywords = emptyList(),
        )
    } else {
        e.copy(
            photoKey = e.photoKey.trim(),
            updatedAt = e.updatedAt.coerceAtLeast(0L),
            rating = e.rating.coerceIn(0, 5),
            keywords = keywords,
        )
    }
}

/**
 * Sanitizes a document for merge/upload: rejects documents from a NEWER
 * schema (we must not merge what we cannot understand), drops bad entries
 * and galleries, de-duplicates photos by key keeping the newest.
 * Returns null when the document is unusable. Pure — pinned by SelfTest
 * check 69.
 */
fun sanitizeSyncDocument(doc: SyncDocument): SyncDocument? {
    if (doc.schemaVersion > SYNC_SCHEMA_VERSION) return null
    if (doc.deviceId.isBlank()) return null
    val photos = doc.photos
        .mapNotNull { sanitizeSyncPhotoEntry(it) }
        .groupBy { it.photoKey }
        .map { (_, versions) -> versions.maxByOrNull { it.updatedAt }!! }
        .take(MAX_SYNC_PHOTOS)
    val galleries = doc.galleries
        .mapNotNull { sanitizeProofingGallery(it) }
        .distinctBy { it.id }
    return doc.copy(
        photos = photos,
        galleries = galleries,
    )
}
