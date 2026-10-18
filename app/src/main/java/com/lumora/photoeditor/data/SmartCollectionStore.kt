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
 * Phase C#6 slice 2 — persistent store for smart collections.
 *
 * File-based like KeywordStore — a single JSON array at
 * filesDir/smart_collections.json — so the Room library DB needs no
 * migration (a schema bump without a tested migration would crash the app
 * on upgrade for every existing install; this sidesteps that entirely).
 *
 * All public ops run on IO, never throw (a corrupt file degrades to an
 * empty collection list rather than breaking the library), and entries
 * are [sanitizeSmartCollection]-cleaned on load and on write.
 */
class SmartCollectionStore(context: Context) {

    private val file = File(context.filesDir, "smart_collections.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val loaded = AtomicBoolean(false)

    /** Insertion order; guarded by [mutex]. */
    private val data = mutableListOf<SmartCollection>()

    private val _collections = MutableStateFlow<List<SmartCollection>>(emptyList())
    val collections: StateFlow<List<SmartCollection>> = _collections.asStateFlow()

    /** Loads the file once, on IO. Idempotent; safe to call on every op. */
    suspend fun ensureLoaded() {
        if (loaded.get()) return
        mutex.withLock {
            if (loaded.get()) return
            withContext(Dispatchers.IO) {
                runCatching {
                    if (file.isFile) {
                        val raw: List<SmartCollection> =
                            json.decodeFromString(file.readText())
                        data += raw.mapNotNull { sanitizeSmartCollection(it) }
                            .take(MAX_SMART_COLLECTIONS)
                    }
                }.onFailure {
                    Log.e("SmartCollectionStore",
                        "smart_collections.json unreadable, starting empty", it)
                }
            }
            loaded.set(true)
            _collections.value = data.toList()
        }
    }

    /**
     * Creates a collection. Returns the new entry, or null when the name
     * is unusable, the rules are empty, or the cap is reached.
     * [keywordDisplay] is normalized here (null/blank = no keyword rule).
     * Never throws.
     */
    suspend fun add(
        name: String,
        keywordDisplay: String?,
        minRating: Int,
        favoritesOnly: Boolean,
    ): SmartCollection? {
        val cleanName = normalizeCollectionName(name) ?: return null
        val kwDisplay = keywordDisplay?.let { normalizeKeyword(it) }
        val kwKey = kwDisplay?.let { keywordKeyOf(it) }
        ensureLoaded()
        return mutex.withLock {
            if (data.size >= MAX_SMART_COLLECTIONS) return null
            val coll = SmartCollection(
                id = UUID.randomUUID().toString(),
                name = cleanName,
                keywordKey = kwKey,
                keywordDisplay = kwDisplay,
                minRating = minRating.coerceIn(0, 5),
                favoritesOnly = favoritesOnly,
            )
            if (!coll.hasRules) return null
            data.add(coll)
            persistLocked()
            _collections.value = data.toList()
            coll
        }
    }

    /**
     * Replaces a collection's name and rules. Returns false when the id is
     * unknown, the new name is unusable, or the new rules are empty.
     * Never throws.
     */
    suspend fun update(
        id: String,
        name: String,
        keywordDisplay: String?,
        minRating: Int,
        favoritesOnly: Boolean,
    ): Boolean {
        val cleanName = normalizeCollectionName(name) ?: return false
        val kwDisplay = keywordDisplay?.let { normalizeKeyword(it) }
        val kwKey = kwDisplay?.let { keywordKeyOf(it) }
        ensureLoaded()
        return mutex.withLock {
            val i = data.indexOfFirst { it.id == id }
            if (i < 0) return false
            val next = SmartCollection(
                id = id,
                name = cleanName,
                keywordKey = kwKey,
                keywordDisplay = kwDisplay,
                minRating = minRating.coerceIn(0, 5),
                favoritesOnly = favoritesOnly,
            )
            if (!next.hasRules) return false
            data[i] = next
            persistLocked()
            _collections.value = data.toList()
            true
        }
    }

    /** Deletes a collection; false when the id is unknown. Never throws. */
    suspend fun delete(id: String): Boolean {
        ensureLoaded()
        return mutex.withLock {
            val removed = data.removeAll { it.id == id }
            if (!removed) return false
            persistLocked()
            _collections.value = data.toList()
            true
        }
    }

    /**
     * Sync lookup by id; call [ensureLoaded] first (the repository's
     * [LibraryRepository.applyFilter] guarantees this).
     */
    fun collectionById(id: String): SmartCollection? = data.firstOrNull { it.id == id }

    /** Writes the whole list; must hold [mutex]. Failures are logged, never thrown. */
    private suspend fun persistLocked() {
        withContext(Dispatchers.IO) {
            runCatching {
                file.writeText(json.encodeToString(data.toList()))
            }.onFailure { Log.e("SmartCollectionStore", "failed to persist collections", it) }
        }
    }
}
