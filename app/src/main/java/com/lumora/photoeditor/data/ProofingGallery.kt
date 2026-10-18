package com.lumora.photoeditor.data

import kotlinx.serialization.Serializable

const val MAX_GALLERY_NAME_LEN = 60
const val MAX_CLIENT_NAME_LEN = 60
const val MAX_PROOFING_GALLERIES = 50
const val MAX_GALLERY_PHOTOS = 2000

/**
 * Phase C#7 slice 1 — client proofing galleries (share/select workflow for
 * working photographers): a named, ordered set of library photos handed to a
 * client for selection. The client marks each photo approved or rejected
 * (Lightroom proofing parity); the marks live on the gallery so the
 * photographer's own ratings/favorites stay untouched.
 */

/** The client's select mark on one gallery photo. */
@Serializable
enum class ProofMark { APPROVED, REJECTED }

/**
 * Cycles a mark through unmarked -> approved -> rejected -> unmarked.
 * Pure — pinned by SelfTest check 65.
 */
fun ProofMark?.next(): ProofMark? = when (this) {
    null -> ProofMark.APPROVED
    ProofMark.APPROVED -> ProofMark.REJECTED
    ProofMark.REJECTED -> null
}

@Serializable
data class ProofingGallery(
    val id: String,
    val name: String,
    /** Optional client the proofs are for (display only). */
    val clientName: String? = null,
    val createdAt: Long = 0L,
    /** Ordered member photo ids (library image ids). */
    val photoIds: List<String> = emptyList(),
    /** Client marks keyed by photo id; entries for non-members are dropped. */
    val marks: Map<String, ProofMark> = emptyMap(),
    /**
     * Phase C#8 slice 1 — last mutation clock (millis) for cloud-sync merge
     * (last-writer-wins per gallery). Bumped by every ProofingGalleryStore
     * mutation; defaults to 0 for pre-sync galleries (they lose ties to any
     * dated copy, which is the safe direction).
     */
    val updatedAt: Long = 0L,
) {
    /**
     * (approved, rejected, unmarked) over current members only — a stale
     * mark for a removed photo never inflates the counts.
     * Pure — pinned by SelfTest check 65.
     */
    fun stats(): Triple<Int, Int, Int> {
        var approved = 0
        var rejected = 0
        for (pid in photoIds) when (marks[pid]) {
            ProofMark.APPROVED -> approved++
            ProofMark.REJECTED -> rejected++
            null -> Unit
        }
        return Triple(approved, rejected, photoIds.size - approved - rejected)
    }

    /**
     * Short chip/dialog summary, e.g. "24 photos", "24 photos · 5 picked",
     * "24 photos · 5 picked · 2 rejected". Pure — pinned by check 65.
     */
    val summary: String
        get() {
            val (a, r, _) = stats()
            val base = "${photoIds.size} " +
                if (photoIds.size == 1) "photo" else "photos"
            val parts = buildList {
                if (a > 0) add("$a picked")
                if (r > 0) add("$r rejected")
            }
            return if (parts.isEmpty()) base
            else "$base · ${parts.joinToString(" · ")}"
        }
}

/**
 * Phase C#7 slice 3 — proofing round-trip: the resolved outcome of writing a
 * gallery's client marks back to the library as ratings/favorites.
 */
@Serializable
data class ClientPicksResult(
    /** Marked APPROVED: now 5 stars + favorite. */
    val approved: Int = 0,
    /** Marked REJECTED: now 1 star, unfavorited. */
    val rejected: Int = 0,
    /** Marked but no longer in the library (or a per-photo failure). */
    val skipped: Int = 0,
) {
    /**
     * Short result line for the confirmation toast, e.g.
     * "Client picks applied: 8 picked → 5★ + favorites · 3 rejected → 1★".
     * Pure — pinned by SelfTest check 67.
     */
    fun describe(): String {
        val parts = buildList {
            if (approved > 0) add("$approved picked → 5★ + favorite")
            if (rejected > 0) add("$rejected rejected → 1★")
            if (skipped > 0) add("$skipped skipped")
        }
        return if (parts.isEmpty()) "No client marks to apply"
        else "Client picks applied: ${parts.joinToString(" · ")}"
    }
}

/**
 * Phase C#7 slice 3 — the round-trip plan: which current member ids the
 * client's marks resolve to. Stale marks for removed photos are dropped;
 * unmarked members are simply absent (the library leaves them untouched).
 * Pure — pinned by SelfTest check 67.
 */
fun ProofingGallery.clientPicksPlan(): Pair<List<String>, List<String>> {
    val approved = mutableListOf<String>()
    val rejected = mutableListOf<String>()
    for (pid in photoIds) when (marks[pid]) {
        ProofMark.APPROVED -> approved.add(pid)
        ProofMark.REJECTED -> rejected.add(pid)
        null -> Unit
    }
    return approved to rejected
}

/**
 * Normalizes a gallery name (trim + collapse whitespace), or null when
 * unusable (blank or longer than [MAX_GALLERY_NAME_LEN]).
 * Pure — pinned by SelfTest check 65.
 */
fun normalizeGalleryName(raw: String): String? {
    val collapsed = raw.trim().replace(Regex("\\s+"), " ")
    if (collapsed.isEmpty() || collapsed.length > MAX_GALLERY_NAME_LEN) return null
    return collapsed
}

/**
 * Normalizes an optional client name: null when blank (no client), or null
 * when overlong. Pure — pinned by SelfTest check 65.
 */
fun normalizeClientName(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val collapsed = raw.trim().replace(Regex("\\s+"), " ")
    if (collapsed.length > MAX_CLIENT_NAME_LEN) return null
    return collapsed
}

/**
 * Sanitizes a stored/edited gallery: the name must normalize (else null —
 * callers drop such entries), the client name normalizes to null when
 * blank/overlong, photo ids are de-duplicated, capped at
 * [MAX_GALLERY_PHOTOS], and marks for non-members are dropped.
 * Pure — pinned by SelfTest check 65.
 */
fun sanitizeProofingGallery(g: ProofingGallery): ProofingGallery? {
    val name = normalizeGalleryName(g.name) ?: return null
    val ids = g.photoIds.distinct().take(MAX_GALLERY_PHOTOS)
    val idSet = ids.toSet()
    return g.copy(
        name = name,
        clientName = normalizeClientName(g.clientName),
        photoIds = ids,
        marks = g.marks.filterKeys { it in idSet },
    )
}
