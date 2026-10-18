package com.lumora.photoeditor.data

import kotlinx.serialization.Serializable

const val MAX_COLLECTION_NAME_LEN = 60
const val MAX_SMART_COLLECTIONS = 50

/**
 * Normalizes a smart-collection name to its display form, or null when it
 * is unusable (blank, or longer than [MAX_COLLECTION_NAME_LEN]). Trims,
 * collapses internal whitespace runs to a single space. Pure — pinned by
 * SelfTest check 64.
 */
fun normalizeCollectionName(raw: String): String? {
    val collapsed = raw.trim().replace(Regex("\\s+"), " ")
    if (collapsed.isEmpty() || collapsed.length > MAX_COLLECTION_NAME_LEN) return null
    return collapsed
}

/**
 * Phase C#6 slice 2 — smart collections (Lightroom "smart collection"
 * parity): a saved set of rules — keyword + minimum rating + favorites-only —
 * whose members are recomputed every time the library grid refreshes.
 * Rating a photo, favoriting it, or tagging it with a keyword moves it into
 * and out of collections automatically, so collections are never stale.
 *
 * [keywordKey] is the canonical (case-insensitive) keyword from
 * [keywordKeyOf], null = any keyword. [keywordDisplay] is the display form
 * for the UI (kept even if no photo currently carries the keyword).
 * [minRating] is 0..5, where 0 means "no minimum".
 */
@Serializable
data class SmartCollection(
    val id: String,
    val name: String,
    val keywordKey: String? = null,
    val keywordDisplay: String? = null,
    val minRating: Int = 0,
    val favoritesOnly: Boolean = false,
) {
    /** True when the collection carries at least one rule (never a bare "all"). */
    val hasRules: Boolean
        get() = keywordKey != null || minRating > 0 || favoritesOnly

    /** Short human-readable rule summary for chips/dialogs, e.g.
     *  "Sunset · 3★+ · favorites". Pure — pinned by SelfTest check 64. */
    val summary: String
        get() = buildList {
            keywordDisplay?.let { add(it) }
            if (minRating > 0) add("${minRating}★+")
            if (favoritesOnly) add("favorites")
        }.joinToString(" · ")
}

/**
 * Pure match predicate: does [img] satisfy [coll]'s rules? [hasKeyword]
 * is the case-insensitive keyword lookup (imageId, canonicalKey).
 * Pinned by SelfTest check 64.
 */
fun smartCollectionMatches(
    coll: SmartCollection,
    img: LibraryImage,
    hasKeyword: (imageId: String, canonicalKey: String) -> Boolean,
): Boolean {
    if (coll.favoritesOnly && !img.favorite) return false
    if (coll.minRating > 0 && img.rating < coll.minRating) return false
    val kw = coll.keywordKey
    if (kw != null && !hasKeyword(img.id, kw)) return false
    return true
}

/**
 * Sanitizes a stored/edited collection: name falls back to null when
 * unusable (callers drop such entries), minRating clamps to 0..5, and a
 * keyword key without its display form is dropped (both or neither).
 * Pure — pinned by SelfTest check 64.
 */
fun sanitizeSmartCollection(coll: SmartCollection): SmartCollection? {
    val name = normalizeCollectionName(coll.name) ?: return null
    val (kwKey, kwDisplay) = if (coll.keywordKey != null && !coll.keywordDisplay.isNullOrBlank())
        coll.keywordKey to coll.keywordDisplay else null to null
    return coll.copy(
        name = name,
        keywordKey = kwKey,
        keywordDisplay = kwDisplay,
        minRating = coll.minRating.coerceIn(0, 5),
    )
}
