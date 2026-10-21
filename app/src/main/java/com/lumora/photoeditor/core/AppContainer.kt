package com.lumora.photoeditor.core

import android.content.Context
import androidx.room.Room
import com.lumora.photoeditor.data.EditStore
import com.lumora.photoeditor.data.EditedThumbs
import com.lumora.photoeditor.data.LensProfileStore
import com.lumora.photoeditor.data.LibraryDb
import com.lumora.photoeditor.data.LibraryRepository
import com.lumora.photoeditor.data.PresetRepository
import com.lumora.photoeditor.data.sync.SyncAccountStore
import com.lumora.photoeditor.data.sync.SyncEngine
import com.lumora.photoeditor.data.billing.LicenseStore
import com.lumora.photoeditor.data.RawThumbs
import com.lumora.photoeditor.data.SnapshotStore
import com.lumora.photoeditor.engine.AiMaskFormat
import com.lumora.photoeditor.engine.AiMaskStore
import com.lumora.photoeditor.export.ExportManager
import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.flow.MutableStateFlow

/** Manual dependency container (no DI framework — explicit and debuggable). */
class AppContainer(val context: Context) {

    val db: LibraryDb by lazy {
        Room.databaseBuilder(context, LibraryDb::class.java, "lumora-library.db").build()
    }

    val editStore: EditStore by lazy { EditStore(context) }

    val editedThumbs: EditedThumbs by lazy { EditedThumbs(context) }

    /**
     * RAW program Phase 2 slice 2: import-time embedded-preview JPEGs for
     * RAW library photos (Coil cannot decode RAW), shown by the grid.
     */
    val rawThumbs: RawThumbs by lazy { RawThumbs(File(context.filesDir, "rawthumbs")) }

    /**
     * Bumped whenever an edited thumbnail is captured, cleared, or
     * invalidated, so the library grid re-checks thumbnail validity.
     */
    val thumbRevision = MutableStateFlow(0L)

    val library: LibraryRepository by lazy {
        LibraryRepository(context, db.library(), editStore, snapshots)
    }

    /**
     * Phase C#8 slice 1 — cloud-sync engine (app-side sync-ready). Ships
     * with NotConfiguredBackend: every sync op honestly reports "not
     * configured" until the photographer plugs in a real backend (his
     * backend project is his decision — no credentials invented here).
     */
    val syncEngine: SyncEngine by lazy {
        SyncEngine(context, library, editStore)
    }

    /**
     * Phase D#9 slice 1 — license/trial state machine (monetization
     * infrastructure). Nothing is paywalled in this slice, no store is
     * connected, no prices are set; [isPro][com.lumora.photoeditor.data.billing.isPro]
     * is the gating hook future paywalled features will call.
     */
    val licenseStore: LicenseStore by lazy {
        LicenseStore(context)
    }

    /**
     * Phase C#8 slice 2 — the account seam for cloud sync. Ships
     * [SyncAccount.SignedOut]: there is deliberately no sign-in UI until
     * the photographer's backend decision lands (no credentials invented).
     */
    val syncAccounts: SyncAccountStore by lazy {
        SyncAccountStore(context)
    }

    val presets: PresetRepository by lazy { PresetRepository(context) }

    val snapshots: SnapshotStore by lazy { SnapshotStore(context) }

    val aiMasks: AiMaskStore by lazy {
        object : AiMaskStore {
            private fun file(key: String) =
                File(context.filesDir, "ai_masks/$key.png").also { it.parentFile?.mkdirs() }

            override fun save(key: String, bitmap: Bitmap) {
                // Crash-hardened: a failed mask persist must never take the
                // app down (disk-full / OOM while the user waits on AI work).
                try {
                    val w = bitmap.width; val h = bitmap.height
                    if (w <= 0 || h <= 0 || bitmap.isRecycled) return
                    val n = w * h
                    val px = IntArray(n)
                    if (bitmap.config == Bitmap.Config.ALPHA_8) {
                        // Raw alpha bytes — no getPixels() conversion
                        // ambiguity; the value IS the byte.
                        val buf = java.nio.ByteBuffer.allocate(n)
                        bitmap.copyPixelsToBuffer(buf)
                        val bytes = buf.array()
                        for (i in 0 until n) {
                            px[i] = AiMaskFormat.pack(bytes[i].toInt() and 0xFF)
                        }
                    } else {
                        bitmap.getPixels(px, 0, w, 0, 0, w, h)
                        for (i in 0 until n) {
                            px[i] = AiMaskFormat.pack((px[i] ushr 24) and 0xFF)
                        }
                    }
                    // NOTE: AiMaskFormat keeps the value in the PNG's ALPHA
                    // channel (white RGB); load() recovers it via
                    // copy(ALPHA_8), which keeps alpha and drops color.
                    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    try {
                        out.setPixels(px, 0, w, 0, 0, w, h)
                        FileOutputStream(file(key)).use { stream ->
                            out.compress(Bitmap.CompressFormat.PNG, 100, stream)
                        }
                    } finally {
                        out.recycle()
                    }
                } catch (e: OutOfMemoryError) {
                    android.util.Log.w("AiMaskStore", "save OOM for $key")
                } catch (t: Throwable) {
                    // Throwable after the OOM catch: any other Error must also
                    // degrade to a log, never take the app down (see comment
                    // above the try).
                    android.util.Log.w("AiMaskStore", "save failed for $key", t)
                }
            }

            override fun load(key: String): Bitmap? {
                val f = file(key)
                if (!f.exists()) return null
                return try {
                    // ALPHA_8 keeps the PNG's alpha channel (where save()
                    // stored the mask value) and drops color.
                    android.graphics.BitmapFactory.decodeFile(f.absolutePath)
                        ?.copy(Bitmap.Config.ALPHA_8, false)
                } catch (e: OutOfMemoryError) {
                    android.util.Log.w("AiMaskStore", "load OOM for $key")
                    null
                } catch (t: Throwable) {
                    // Throwable after the OOM catch: any other Error degrades
                    // to "no cached mask" instead of crashing the editor.
                    android.util.Log.w("AiMaskStore", "load failed for $key", t)
                    null
                }
            }

            override fun delete(key: String) {
                file(key).delete()
            }
        }
    }

    val exports: ExportManager by lazy { ExportManager(context) }

    val settings: AppSettings by lazy { AppSettings(context) }

    /** Per-lens distortion/defringe memory (Phase A#2 slice 2). */
    val lensProfiles: LensProfileStore by lazy { LensProfileStore(context) }

    /**
     * Session clipboard for copy/paste edits: JSON-encoded [EditState],
     * shared between the editor and the library. Cleared on process death.
     */
    val copiedEdits = MutableStateFlow<String?>(null)

    /**
     * Editor sessions keyed by image id, shared between the editor and the
     * export screen so export always uses the live edit state.
     */
    private val editorSessions =
        java.util.Collections.synchronizedMap(mutableMapOf<String, Any>())

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> editorSession(imageId: String, create: () -> T): T =
        editorSessions.getOrPut(imageId, create) as T

    fun closeEditorSession(imageId: String) {
        (editorSessions.remove(imageId) as? androidx.lifecycle.ViewModel)?.let {
            // The VM is lifecycle-managed by the UI; just drop our reference.
        }
    }
}
