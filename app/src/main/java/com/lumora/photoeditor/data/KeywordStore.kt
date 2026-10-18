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
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Photo keywords, pro-premium program Phase C#6 (keywords / smart
 * collections) slice 1: free-form tags per photo, Lightroom parity for the
 * culling workflow (ratings/favorites already exist; keywords complete the
 * trio).
 *
 * File-based like SnapshotStore/PresetRepository — a single JSON map at
 * filesDir/keywords.json — so the Room library DB needs no migration
 * (a schema bump without a tested migration would crash the app on upgrade
 * for every existing install; this sidesteps that entirely).
 *
 * Matching is case-insensitive ([keywordKeyOf]); the display form keeps the
 * user's original casing ("Sunset" stays "Sunset", "SUNSET" merges into it).
 * Caps keep the file small: [MAX_KEYWORD_LEN] chars per keyword,
 * [MAX_KEYWORDS_PER_PHOTO] per photo, [MAX_DISTINCT_KEYWORDS] library-wide.
 *
 * All public ops run on IO, never throw (a corrupt file degrades to an empty
 * keyword set rather than breaking the library), and [deleteAllFor] is the
 * synchronous cleanup hook for photo deletion.
 */
const val MAX_KEYWORD_LEN = 48
const val MAX_KEYWORDS_PER_PHOTO = 30
const val MAX_DISTINCT_KEYWORDS = 1000

/**
 * Normalizes a typed keyword to its display form, or null when it is
 * unusable (blank, or longer than [MAX_KEYWORD_LEN]). Trims, collapses
 * internal whitespace runs to a single space, keeps the user's casing.
 * Pure — pinned by SelfTest check 62.
 */
fun normalizeKeyword(raw: String): String? {
    val collapsed = raw.trim().replace(Regex("\\s+"), " ")
    if (collapsed.isEmpty() || collapsed.length > MAX_KEYWORD_LEN) return null
    return collapsed
}

/** Canonical match key for a display-form keyword (case-insensitive). Pure. */
fun keywordKeyOf(display: String): String =
    display.lowercase(Locale.ROOT)

class KeywordStore(context: Context) {

    private val file = File(context.filesDir, "keywords.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val loaded = AtomicBoolean(false)

    /** imageId -> sorted display-form keywords. Guarded by [mutex]. */
    private val data = mutableMapOf<String, MutableList<String>>()

    /** canonical key -> display form of first use (for the filter-chip row). */
    private val displayFor = mutableMapOf<String, String>()

    private val flows = mutableMapOf<String, MutableStateFlow<List<String>>>()
    private val _allKeywords = MutableStateFlow<List<String>>(emptyList())
    val allKeywords: StateFlow<List<String>> = _allKeywords.asStateFlow()

    /** Loads the file once, on IO. Idempotent; safe to call on every op. */
    suspend fun ensureLoaded() {
        if (loaded.get()) return
        mutex.withLock {
            if (loaded.get()) return
            withContext(Dispatchers.IO) {
                runCatching {
                    if (file.isFile) {
                        val raw: Map<String, List<String>> =
                            json.decodeFromString(file.readText())
                        for ((id, kws) in raw) {
                            val clean = kws.mapNotNull { normalizeKeyword(it) }
                                .distinctBy { keywordKeyOf(it) }
                                .take(MAX_KEYWORDS_PER_PHOTO)
                                .sortedBy { keywordKeyOf(it) }
                            if (clean.isNotEmpty()) {
                                data[id] = clean.toMutableList()
                                for (k in clean) displayFor.putIfAbsent(keywordKeyOf(k), k)
                            }
                        }
                    }
                }.onFailure { Log.e("KeywordStore", "keywords.json unreadable, starting empty", it) }
            }
            loaded.set(true)
            refreshAllKeywordsLocked()
            synchronized(flows) {
                for ((id, flow) in flows) flow.value = data[id].orEmpty()
            }
        }
    }

    fun keywordsFor(imageId: String): StateFlow<List<String>> =
        synchronized(flows) {
            flows.getOrPut(imageId) { MutableStateFlow(emptyList()) }
        }.asStateFlow()

    /** In-memory check for the library filter; call [ensureLoaded] first. */
    fun hasKeyword(imageId: String, canonicalKey: String): Boolean =
        data[imageId]?.any { keywordKeyOf(it) == canonicalKey } == true

    /**
     * Adds a keyword to a photo. Returns true when the photo's keyword set
     * changed (false for blank/overlong/duplicate input or a full photo).
     * Never throws.
     */
    suspend fun addKeyword(imageId: String, raw: String): Boolean {
        val display = normalizeKeyword(raw) ?: return false
        val key = keywordKeyOf(display)
        ensureLoaded()
        return mutex.withLock {
            val list = data.getOrPut(imageId) { mutableListOf() }
            if (list.any { keywordKeyOf(it) == key }) return false
            if (list.size >= MAX_KEYWORDS_PER_PHOTO) return false
            if (!displayFor.containsKey(key) && displayFor.size >= MAX_DISTINCT_KEYWORDS) return false
            list.add(display)
            list.sortBy { keywordKeyOf(it) }
            displayFor.putIfAbsent(key, display)
            persistLocked()
            emitLocked(imageId)
            refreshAllKeywordsLocked()
            true
        }
    }

    /** Removes a keyword (matched case-insensitively). Never throws. */
    suspend fun removeKeyword(imageId: String, keyword: String): Boolean {
        val key = keywordKeyOf(keyword.trim())
        if (key.isEmpty()) return false
        ensureLoaded()
        return mutex.withLock {
            val list = data[imageId] ?: return false
            val removed = list.removeAll { keywordKeyOf(it) == key }
            if (!removed) return false
            if (list.isEmpty()) data.remove(imageId)
            persistLocked()
            emitLocked(imageId)
            refreshAllKeywordsLocked()
            true
        }
    }

    /** Cleanup hook for photo deletion. Never throws. */
    suspend fun deleteAllFor(imageId: String) {
        ensureLoaded()
        mutex.withLock {
            if (data.remove(imageId) == null) return
            persistLocked()
            emitLocked(imageId)
            refreshAllKeywordsLocked()
        }
    }

    private fun emitLocked(imageId: String) {
        synchronized(flows) {
            flows[imageId]?.value = data[imageId].orEmpty()
        }
    }

    private fun refreshAllKeywordsLocked() {
        _allKeywords.value = displayFor.entries
            .sortedBy { it.key }
            .map { it.value }
    }

    /** Writes the whole map; must hold [mutex]. Failures are logged, never thrown. */
    private suspend fun persistLocked() {
        withContext(Dispatchers.IO) {
            runCatching {
                val snapshot: Map<String, List<String>> = data.mapValues { it.value.toList() }
                file.writeText(json.encodeToString(snapshot))
            }.onFailure { Log.e("KeywordStore", "failed to persist keywords", it) }
        }
    }
}
