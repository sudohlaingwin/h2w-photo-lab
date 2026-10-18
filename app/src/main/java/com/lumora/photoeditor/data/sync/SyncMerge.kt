package com.lumora.photoeditor.data.sync

import com.lumora.photoeditor.data.ProofingGallery

/**
 * Phase C#8 slice 1 — pure, deterministic merge of two sync documents.
 * No IO, no Android, no backend knowledge: given "mine" and "theirs",
 * compute exactly what to apply locally and what to push back. The rule
 * is last-writer-wins per photo key and per gallery id, by [updatedAt]
 * (millis); exact ties keep the local copy so a merge never flips state
 * on equal clocks. Tombstones are ordinary entries in this ordering: a
 * newer tombstone clears state, an older one loses to live data.
 * Pure — pinned by SelfTest check 69.
 */
data class SyncMergePlan(
    /** Remote entries newer than ours: apply to this device. */
    val photosToApply: List<SyncPhotoEntry> = emptyList(),
    /** Our entries newer than remote (or absent there): include in push. */
    val photosToUpload: List<SyncPhotoEntry> = emptyList(),
    /** Remote galleries newer than ours: upsert locally. */
    val galleriesToApply: List<ProofingGallery> = emptyList(),
    /** Our galleries newer than remote (or absent there): include in push. */
    val galleriesToUpload: List<ProofingGallery> = emptyList(),
) {
    /**
     * One-line merge summary for logs, e.g. "apply 3 photos, push 1 photo,
     * apply 1 gallery". Empty plan reports "already in sync".
     * Pure — pinned by check 69.
     */
    fun describe(): String {
        val parts = buildList {
            if (photosToApply.isNotEmpty())
                add("apply ${photosToApply.size} photo" +
                    if (photosToApply.size == 1) "" else "s")
            if (photosToUpload.isNotEmpty())
                add("push ${photosToUpload.size} photo" +
                    if (photosToUpload.size == 1) "" else "s")
            if (galleriesToApply.isNotEmpty())
                add("apply ${galleriesToApply.size} galler" +
                    if (galleriesToApply.size == 1) "y" else "ies")
            if (galleriesToUpload.isNotEmpty())
                add("push ${galleriesToUpload.size} galler" +
                    if (galleriesToUpload.size == 1) "y" else "ies")
        }
        return if (parts.isEmpty()) "already in sync"
        else parts.joinToString(", ")
    }
}

/**
 * Merges [local] (this device) with [remote] (the backend's latest).
 * Both documents should already be [sanitizeSyncDocument]-clean; unsanitary
 * input is sanitized here defensively and a null (unusable) remote means
 * "nothing to merge" rather than an error. Pure — pinned by check 69.
 */
fun mergeSyncDocuments(local: SyncDocument, remote: SyncDocument?): SyncMergePlan {
    val mine = sanitizeSyncDocument(local) ?: return SyncMergePlan()
    val theirs = remote?.let { sanitizeSyncDocument(it) } ?: return SyncMergePlan(
        photosToUpload = mine.photos,
        galleriesToUpload = mine.galleries,
    )

    val mineByKey = mine.photos.associateBy { it.photoKey }
    val theirsByKey = theirs.photos.associateBy { it.photoKey }
    val apply = mutableListOf<SyncPhotoEntry>()
    val upload = mutableListOf<SyncPhotoEntry>()
    for (key in mineByKey.keys + theirsByKey.keys) {
        val l = mineByKey[key]
        val r = theirsByKey[key]
        when {
            l == null && r != null -> apply.add(r)
            r == null && l != null -> upload.add(l)
            l != null && r != null -> when {
                r.updatedAt > l.updatedAt -> apply.add(r)
                l.updatedAt > r.updatedAt -> upload.add(l)
                // tie: keep local, push nothing — deterministic, no flapping
            }
        }
    }

    val mineG = mine.galleries.associateBy { it.id }
    val theirsG = theirs.galleries.associateBy { it.id }
    val applyG = mutableListOf<ProofingGallery>()
    val uploadG = mutableListOf<ProofingGallery>()
    for (id in mineG.keys + theirsG.keys) {
        val l = mineG[id]
        val r = theirsG[id]
        when {
            l == null && r != null -> applyG.add(r)
            r == null && l != null -> uploadG.add(l)
            l != null && r != null -> when {
                r.updatedAt > l.updatedAt -> applyG.add(r)
                l.updatedAt > r.updatedAt -> uploadG.add(l)
            }
        }
    }

    return SyncMergePlan(
        photosToApply = apply,
        photosToUpload = upload,
        galleriesToApply = applyG,
        galleriesToUpload = uploadG,
    )
}
