package com.lumora.photoeditor.ui.state

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumora.photoeditor.ai.AiAssistantUiState
import com.lumora.photoeditor.ai.AiChatMessage
import com.lumora.photoeditor.ai.AiKeyStore
import com.lumora.photoeditor.ai.AiSuggestions
import com.lumora.photoeditor.ai.GeminiClient
import com.lumora.photoeditor.core.AppContainer
import com.lumora.photoeditor.data.HistoryEntry
import com.lumora.photoeditor.data.LibraryImage
import com.lumora.photoeditor.engine.*
import com.lumora.photoeditor.export.ExportManager
import com.lumora.photoeditor.export.GlHost
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

data class Histogram(
    val r: IntArray, val g: IntArray, val b: IntArray, val lum: IntArray,
    val clippedShadows: Float, val clippedHighlights: Float,
) {
    companion object {
        const val BINS = 64
        fun empty() = Histogram(IntArray(BINS), IntArray(BINS), IntArray(BINS), IntArray(BINS), 0f, 0f)
    }
}

/** Everything the GL thread needs for one frame (immutable, thread-safe). */
data class PendingFrame(
    val state: EditState,
    val showOriginal: Boolean,
    val uvMatrix: FloatArray,
    val masks: List<Bitmap>,
    /**
     * Mask models aligned with [masks] (excluding any reserved-slot overlay
     * bitmap such as the Remove tool's live overlay) — drives the shader's
     * mask adjustment uniforms so they can't drift out of alignment with
     * the uploaded textures (see GlEngine.render).
     */
    val maskUniforms: List<Mask>,
    /**
     * Hair decontamination (despilled RGB) bitmaps aligned 1:1 with [masks]
     * (including the reserved-slot overlay position, which is always null).
     * Null per slot = feature off for that mask — GlEngine binds the
     * placeholder texture and forces that slot's uDecon strength to 0.
     */
    val maskDecon: List<Bitmap?> = emptyList(),
    val retouch: Bitmap?,
    val curvesRev: Long,
    val masksRev: Long,
    val retouchRev: Long,
    val clipMode: Int,
    val overlayMaskIndex: Int,
    /** Mask view mode for the overlay: 0 = red tint, 1 = black & white. */
    val maskViewMode: Int = 0,
    /** Mask overlay opacity (red/B&W view), 0.05..1. */
    val overlayAlpha: Float = 0.55f,
    val timeSec: Float,
    val frameId: Long,
    /** Bump to force the GL thread to re-upload all GPU assets. */
    val assetsToken: Long = 0L,
    /** NDC x of the before|after split divider; < -1.5 disables the split. */
    val splitX: Float = -2f,
)

sealed interface ExportUiState {
    data object Idle : ExportUiState
    data class Running(val progress: Float) : ExportUiState
    data class Done(val uri: Uri, val settings: ExportSettings) : ExportUiState
    data class Error(val message: String) : ExportUiState
}

sealed interface AiUiState {
    data object Idle : AiUiState
    data class Working(val label: String) : AiUiState
    /**
     * @param recoverLabel optional one-tap recovery label (e.g. "Paint a
     *   mask") shown on the error card; the UI wires it to a manual-mask
     *   recovery so an AI failure is a detour, never a dead end. Null means
     *   the card offers Dismiss only, as before.
     */
    data class Error(val message: String, val recoverLabel: String? = null) : AiUiState
}

class EditorViewModel(private val container: AppContainer) : ViewModel() {

    /**
     * Uniform crash guard for every fire-and-forget editor job (crash-risk
     * hardening cycle; mirrors LibraryViewModel). An uncaught throw inside
     * any viewModelScope child cancels the whole scope — potentially taking
     * down the app — so every launch in this ViewModel goes through here: a
     * pathological bitmap/decoder/file throw is warn-logged and the scope
     * survives. Callers that need a result keep their own try/catch; this
     * is the backstop. Cancellation is never swallowed
     * (CoroutineExceptionHandler ignores CancellationException by contract).
     */
    private val scopeGuard = CoroutineExceptionHandler { _, t ->
        android.util.Log.w("EditorViewModel", "editor job failed", t)
    }

    private fun guardLaunch(block: suspend CoroutineScope.() -> Unit) =
        viewModelScope.launch(scopeGuard, block = block)

    private fun guardLaunch(dispatcher: CoroutineDispatcher, block: suspend CoroutineScope.() -> Unit) =
        viewModelScope.launch(scopeGuard + dispatcher, block = block)

    private val _imageId = MutableStateFlow<String?>(null)
    val imageId = _imageId.asStateFlow()

    private val _libraryImage = MutableStateFlow<LibraryImage?>(null)
    val libraryImage = _libraryImage.asStateFlow()

    /**
     * Live star rating (0..5) of the open photo, sourced from the library
     * repository (Lightroom parity: rate from the editor, not just the
     * grid). Updates if the rating changes elsewhere while editing.
     */
    val rating: StateFlow<Int> = combine(container.library.images, _imageId) { imgs, id ->
        id?.let { imgs.firstOrNull { it.id == id }?.rating } ?: 0
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /**
     * Rates the open photo 0..5. Best-effort: a DB failure leaves the
     * previous rating in place rather than surfacing an error mid-edit.
     */
    fun setRating(rating: Int) {
        val id = _imageId.value ?: return
        guardLaunch {
            try {
                container.library.setRating(id, rating.coerceIn(0, 5))
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                /* keep previous rating */
            }
        }
    }

    /**
     * Lightroom-style "Create Version" from inside the editor: a new library
     * entry pointing at the same source photo, carrying the EXACT edits
     * currently on screen. The debounced autosave is flushed first (same
     * pattern as refreshEditedThumbnail) so the copy never carries a
     * ~1s-stale snapshot of a just-dragged slider. onDone receives the new
     * library id, or null when the photo is gone or the copy failed. Never
     * throws; a failure simply reports null.
     */
    fun duplicateCurrent(onDone: (String?) -> Unit) {
        val id = _imageId.value ?: run { onDone(null); return }
        guardLaunch {
            val newId = try {
                saveJob?.cancelAndJoin()
                container.editStore.save(id, _state.value)
                container.library.duplicateImage(id)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                null
            }
            onDone(newId)
        }
    }

    /**
     * Library photos for the look-transfer reference picker. The currently
     * open photo is filtered out by the picker UI (matching a photo to
     * itself is a no-op).
     */
    fun libraryImages(): StateFlow<List<LibraryImage>> = container.library.images

    /** Id of the photo currently open in the editor (null when none). */
    fun currentImageId(): String? = _imageId.value

    private val _preview = MutableStateFlow<Bitmap?>(null)
    val previewBitmap = _preview.asStateFlow()

    /**
     * RAW program Phase 1 slice 3: true when the currently open photo was
     * loaded from the camera's embedded preview (lossy-JPEG DNG fallback)
     * instead of a fresh LibRaw decode. The editor screen shows a small
     * honest caption in that case — edits apply to the preview, not RAW.
     * Reset on every open().
     */
    // RAW program Phase 2 slice 4: format label (e.g. "DNG", "NEF", "CR2")
    // of the camera's embedded preview currently shown in the editor —
    // null when the photo was truly raw-decoded or is not a RAW at all.
    // Any format LibRaw cannot unpack (lossy-JPEG DNGs, Nikon HE*, ...) now
    // falls back to the embedded preview, so the caption must name the
    // actual format instead of hardcoding "lossy-JPEG DNG".
    private val _embeddedPreviewFormat = MutableStateFlow<String?>(null)
    val embeddedPreviewFormat = _embeddedPreviewFormat.asStateFlow()

    /**
     * Private working copy of the live preview bitmap for background workers.
     * MUST be called on the Main thread. Background retouch/auto workers must
     * never read [_preview] directly: the UI thread may recycle it (undo, new
     * photo, wavelet/remove result swap) while the worker is mid-read, which
     * crashes the app (uncaught IllegalStateException / OutOfMemoryError on
     * Dispatchers.Default). The caller owns the returned copy and must
     * recycle() it when done. Returns null when no photo is loaded or the
     * copy failed (recycled source / OOM) — callers degrade gracefully.
     */
    private fun snapshotPreview(): Bitmap? {
        val bmp = _preview.value ?: return null
        return try {
            if (bmp.isRecycled) null else bmp.copy(Bitmap.Config.ARGB_8888, false)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            null
        }
    }

    private val _loading = MutableStateFlow(true)
    val loading = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    /**
     * Raw technical detail behind [_error], when the failure message itself
     * is exception jargon. Shown as a dimmed "Details:" caption under the
     * friendly message (same UX-honesty pattern as the GL preview fallback),
     * so the screen never dumps a raw stack-trace line at the user while
     * staying diagnosable. Null when there is nothing technical to show.
     */
    private val _errorDetail = MutableStateFlow<String?>(null)
    val errorDetail = _errorDetail.asStateFlow()

    private val _state = MutableStateFlow(EditState())
    val state = _state.asStateFlow()

    private val undoStack = ArrayDeque<EditState>()
    private val redoStack = ArrayDeque<EditState>()
    private val _canUndo = MutableStateFlow(false)
    val canUndo = _canUndo.asStateFlow()
    private val _canRedo = MutableStateFlow(false)
    val canRedo = _canRedo.asStateFlow()
    private var gestureSnapshot: EditState? = null

    /**
     * Baseline for idempotent auto operations (Auto Enhance, Pro Auto).
     * The first tap in a session snapshots the pre-auto state; every later
     * tap re-applies its deltas onto that snapshot instead of the current
     * state. Without this, repeated taps stack: the user's error video
     * (2026-10-17) shows "Pro Auto" tapped 8 times driving exposure
     * 0.35 -> 0.70 -> 1.05 -> ... -> 2.80 and blowing the photo out.
     *
     * Any committed edit ([update]) invalidates the baseline so the next
     * auto tap snapshots the fresh state (auto -> preset -> auto must not
     * discard the preset); the auto paths re-pin it after their own update
     * when the state was untouched in the meantime.
     *
     * Main thread only. Also cleared on photo open, reset, undo and redo.
     */
    private var autoBaseline: EditState? = null
    /**
     * True while an Auto Enhance / Pro Auto result is the live state — i.e.
     * the last auto tap applied and nothing (manual edit, undo, photo open)
     * has invalidated it since. Drives the toggle: a second tap while true
     * reverts to [autoBaseline] instead of re-applying.
     */
    private val _autoEnhanceApplied = MutableStateFlow(false)
    val autoEnhanceApplied: StateFlow<Boolean> = _autoEnhanceApplied.asStateFlow()
    private val _proAutoApplied = MutableStateFlow(false)
    val proAutoApplied: StateFlow<Boolean> = _proAutoApplied.asStateFlow()
    private val _autoRetouchApplied = MutableStateFlow(false)
    val autoRetouchApplied: StateFlow<Boolean> = _autoRetouchApplied.asStateFlow()
    /** In-flight auto jobs, so a re-tap cancels the stale analysis first. */
    private var autoEnhanceJob: Job? = null
    private var proAutoJob: Job? = null
    private var autoRetouchJob: Job? = null
    private var autoDenoiseJob: Job? = null
    private var aiProRetouchJob: Job? = null
    /**
     * True while an AI Pro Retouch grade is the live state — i.e. the last
     * tap applied and nothing (manual edit, undo, photo open) has
     * invalidated it since. Drives the toggle: a second tap while true
     * reverts to [autoBaseline] instead of re-applying.
     */
    private val _aiProRetouchApplied = MutableStateFlow(false)
    val aiProRetouchApplied: StateFlow<Boolean> = _aiProRetouchApplied.asStateFlow()
    /**
     * Photo-change race guard (extends the 13:52 coverage to every
     * photo-analysis path). Analysis runs on Dispatchers.Default while the
     * user may open another photo; each path captures [tapPhotoId] on the
     * main thread before launching and bails in its apply step when the
     * open photo changed, so one photo's analysis can never land on
     * another photo's state. Null-safe: matches the 13:52 inline check
     * semantics exactly ([_libraryImage] id equality).
     */
    private fun stalePhoto(tapPhotoId: String?): Boolean =
        _libraryImage.value?.id != tapPhotoId
    /** Guards [applyAutoSkinMask] against double-tap races. */
    private val skinMaskInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    private val _histogram = MutableStateFlow(Histogram.empty())
    val histogram = _histogram.asStateFlow()

    /**
     * True while the GPU readback feeding the scopes is healthy. On the
     * user's vivo V60 glReadPixels can fail on the default framebuffer
     * while the preview renders fine (Mali quirk, 2026-10-18): the scopes
     * then freeze — on first open at empty(), rendering a flat line (video
     * bug #2). The GL thread reports failures via [noteReadbackFailure];
     * after [WatchdogPolicy.SCOPES_DEAD_AFTER_FAILURES] consecutive
     * failures the UI shows an honest "unavailable" placeholder instead of
     * a misleading flat/stale scope. A later successful readback revives.
     */
    private val _scopesLive = MutableStateFlow(true)
    val scopesLive = _scopesLive.asStateFlow()

    /** Consecutive failed GPU readbacks; GL thread only ([onFrameBytes] /
     *  [noteReadbackFailure]). Reset on every successful readback and on
     *  photo open. */
    @Volatile
    private var readbackFailures = 0

    /** Called by the GL thread when [onFrameBytes]' readback failed its GL
     *  error check (see EditorGlView.readHistogram). */
    fun noteReadbackFailure() {
        if (++readbackFailures >= WatchdogPolicy.SCOPES_DEAD_AFTER_FAILURES) {
            _scopesLive.value = false
        }
    }

    /** Live luma waveform (per-column min/max/mean), fed from the same frame bytes as the histogram. */
    private val _waveform = MutableStateFlow(Waveform.empty())
    val waveform = _waveform.asStateFlow()

    /** Live vectorscope (chroma distribution), fed from the same frame bytes as the waveform. */
    private val _vectorscope = MutableStateFlow(Vectorscope.empty())
    val vectorscope = _vectorscope.asStateFlow()

    /** Live RGB parade (per-channel column envelopes), fed from the same frame bytes. */
    private val _rgbParade = MutableStateFlow(RgbParade.empty())
    val rgbParade = _rgbParade.asStateFlow()

    private val _exportUi = MutableStateFlow<ExportUiState>(ExportUiState.Idle)
    val exportUi = _exportUi.asStateFlow()

    private val _aiUi = MutableStateFlow<AiUiState>(AiUiState.Idle)
    val aiUi = _aiUi.asStateFlow()

    private val _clipMode = MutableStateFlow(0)
    val clipMode = _clipMode.asStateFlow()

    private val _selectedMaskId = MutableStateFlow<String?>(null)
    val selectedMaskId = _selectedMaskId.asStateFlow()

    private val _showOriginal = MutableStateFlow(false)
    val showOriginal = _showOriginal.asStateFlow()
    /** Before/after split divider position in NDC x (-1..1); null = split off. */
    private val _splitX = MutableStateFlow<Float?>(null)
    val splitX = _splitX.asStateFlow()
    /** When true, the preview shows the FULL image (crop not applied to uvMatrix)
     *  so the crop overlay rect is interactive. Set by the UI on entering/exiting
     *  crop mode. The committed s.crop is still the source of truth. */
    private var cropPreviewMode = false

    // ---- edit history (persisted per photo, newest first) ----
    private val _historyEntries = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val historyEntries = _historyEntries.asStateFlow()

    // ---- render plumbing ----
    private val _pendingFrame = MutableStateFlow<PendingFrame?>(null)
    val pendingFrame = _pendingFrame.asStateFlow()
    private var frameId = 0L
    private var viewW = 0
    private var viewH = 0
    private var renderJob: Job? = null

    private val maskCache = mutableMapOf<String, Pair<Long, Bitmap>>()
    /**
     * Hair decontamination textures, keyed by mask id -> (revision, bitmap?).
     * Null bitmap = the mask has decontaminate == 0 (feature off) or the
     * despill failed — both mean "bind the placeholder texture" in
     * GlEngine.uploadMaskDecon. Aligned with [maskCache] by revision.
     */
    private val deconCache = mutableMapOf<String, Pair<Long, Bitmap?>>()
    private var retouchCache: Pair<Int, Bitmap?>? = null

    /**
     * CPU rasterization must never take down the app: an OOM (large exports,
     * big brushes) or a recycled source bitmap during a slider drag would
     * otherwise propagate out of the render coroutine and crash the editor.
     * Callers keep showing the last good layer (or an empty mask) so the
     * preview stays live. Catches Throwable on purpose — OutOfMemoryError
     * is the main thing we are defending against.
     */
    private fun <T> rasterizeSafely(label: String, block: () -> T): T? = try {
        block()
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        android.util.Log.w("EditorVM", "rasterize failed ($label)", t)
        null
    }

    /**
     * Positional fallback for a failed mask raster. The shader samples `.r`
     * of the mask texture, so an all-zero mask is a no-op, and keeping one
     * bitmap per position preserves alignment with the enabled-mask list
     * (GlEngine.uploadMasks binds by index). 1x1 keeps the allocation trivial
     * and is never recycled.
     */
    private val emptyMaskBitmap: Bitmap by lazy {
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    }
    private var curvesRev = 0L
    private var lastCurvesKey = ""
    private var assetsToken = 0L
    private var saveJob: Job? = null

    /** Forces the GL thread to re-upload curves/masks/retouch on next frame. */
    fun invalidateGpuAssets() {
        assetsToken++
        requestRender()
    }

    /**
     * Runs the on-device GPU self-test (engine/SelfTest.kt) on the GL thread
     * and delivers the result on the main thread. The test temporarily
     * re-renders with synthetic inputs, so GPU assets are invalidated
     * afterwards to restore the preview.
     */
    fun runGpuSelfTest(onDone: (SelfTestResult) -> Unit) {
        val host = sharedGlHost
        val engine = glEngineRef
        if (host == null || engine == null) {
            onDone(
                SelfTestResult(
                    listOf(
                        SelfTestCheck(
                            "GPU self-test", false,
                            "Renderer not ready — open a photo and try again.",
                        ),
                    ),
                    0,
                ),
            )
            return
        }
        guardLaunch(Dispatchers.Default) {
            val previewBmp = _preview.value
            val done = CompletableDeferred<SelfTestResult>()
            host.queueGl {
                try {
                    done.complete(engine.runSelfTest(previewBmp))
                } catch (t: Throwable) {
                    done.completeExceptionally(t)
                }
            }
            val result = try {
                done.await()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                SelfTestResult(
                    listOf(
                        SelfTestCheck(
                            "GPU self-test", false,
                            "Error: ${t.message ?: t.javaClass.simpleName}",
                        ),
                    ),
                    0,
                )
            }
            // The test stomped GPU assets; force re-upload on the next frame.
            invalidateGpuAssets()
            withContext(Dispatchers.Main) { onDone(result) }
        }
    }

    /**
     * Captures a GPU-rendered "edited" thumbnail for the library grid
     * (Lightroom parity: the grid shows the edited photo, not the original).
     * Runs the full pipeline — parametric edits, crop, masks, retouch — at
     * 384px on the GL thread, then stores it keyed to the sidecar revision
     * so a stale thumbnail can never be shown.
     *
     * Safe on any exit path: any failure (GL not ready yet, surface gone)
     * simply leaves the grid showing the original. Callers should still
     * runCatching {} and proceed — back navigation must never wait on this.
     * Returns true when the thumbnail state is now in sync (captured, or
     * cleared because the photo is back to its original).
     */
    suspend fun refreshEditedThumbnail(): Boolean = withContext(Dispatchers.Default) {
        val id = _imageId.value ?: return@withContext false
        val state = _state.value
        try {
            // Flush the debounced autosave first so the stored revision is exact.
            saveJob?.cancelAndJoin()
            container.editStore.save(id, state)
            val mtime = container.editStore.sidecarLastModified(id)
            if (state.isDefault()) {
                // Back to the original: no thumbnail needed; drop any stale one.
                container.editedThumbs.clear(id)
                container.thumbRevision.value++
                return@withContext true
            }
            val host = sharedGlHost ?: return@withContext false
            val engine = glEngineRef ?: return@withContext false
            val src = _preview.value ?: return@withContext false
            val masks = currentMaskBitmaps()
            val decon = currentDeconBitmaps()
            val retouch = retouchCache?.second
            val small = Bitmap.createScaledBitmap(
                src, 384,
                (384f * src.height / src.width).toInt().coerceAtLeast(1), true)
            val done = CompletableDeferred<Bitmap>()
            host.queueGl {
                try {
                    val bmp = engine.renderEditedThumbnail(state, small, masks, retouch, decon)
                    // restore the live preview assets the thumbnail stomped
                    engine.setSource(src)
                    engine.uploadMasks(masks)
                    engine.uploadMaskDecon(decon)
                    engine.uploadRetouch(retouch)
                    done.complete(bmp)
                } catch (t: Throwable) {
                    done.completeExceptionally(t)
                }
            }
            val bmp = try {
                // Never hang the exit if the GL thread is already gone.
                withTimeout(4000L) { done.await() }
            } catch (e: TimeoutCancellationException) {
                // Timeout waiting on the GL thread: degrade to "thumbnail
                // not updated". Any other CancellationException is real job
                // cancellation and must propagate — withTimeout only throws
                // TimeoutCancellationException for the timeout case.
                return@withContext false
            } finally {
                small.recycle()
            }
            // The thumbnail re-render stomped GPU assets; force re-upload.
            invalidateGpuAssets()
            val ok = container.editedThumbs.save(id, bmp, mtime)
            bmp.recycle()
            if (ok) container.thumbRevision.value++
            ok
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            false
        }
    }

    // ------------------------------------------------------------------
    // Session
    // ------------------------------------------------------------------

    fun open(imageId: String) {
        if (_imageId.value == imageId && _preview.value != null) return
        _imageId.value = imageId
        // Fresh photo, fresh readback health: the new photo's own
        // readbacks decide whether the scopes are live.
        readbackFailures = 0
        _scopesLive.value = true
        _loading.value = true
        _error.value = null
        _errorDetail.value = null
        _embeddedPreviewFormat.value = null
        guardLaunch {
            try {
                val img = container.library.byId(imageId)
                    ?: throw IllegalStateException("Photo not found in library")
                _libraryImage.value = img
                // A newly opened photo invalidates any in-flight auto
                // analysis: their results belong to the previous photo and
                // must never be applied here (photo-change race; each apply
                // path also re-checks the photo id on the main thread before
                // applying).
                autoEnhanceJob?.cancel()
                proAutoJob?.cancel()
                autoRetouchJob?.cancel()
                autoDenoiseJob?.cancel()
                aiProRetouchJob?.cancel()
                val uri = Uri.parse(img.contentUri)
                val bmp = withContext(Dispatchers.IO) { decodePreview(uri) }
                    ?: throw IllegalStateException("Could not decode this image")
                _preview.value = bmp
                val saved = container.editStore.load(imageId)
                _state.value = saved
                clearAutoBaseline()
                undoStack.clear(); redoStack.clear(); syncUndoRedo()
                refreshHistory()
                refreshSnapshots()
                requestRender()
                // Per-lens correction memory (Phase A#2 slice 2): identify the
                // lens from EXIF and auto-apply the user's remembered
                // distortion/defringe for it, but only when those sliders are
                // still at defaults — manual edits always win.
                _lensLabel.value = null
                _lensProfile.value = null
                lensKey = null
                val (lKey, lLabel) = withContext(Dispatchers.IO) {
                    readLensIdentity(uri)
                }
                lensKey = lKey
                _lensLabel.value = lLabel
                if (lKey != null) {
                    val remembered = container.lensProfiles.get(lKey)
                    _lensProfile.value = remembered
                    val so2 = saved.optics
                    if (remembered != null && LensProfiles.isMeaningful(remembered) &&
                        so2.distortion == 0f && so2.defringe == 0f &&
                        lensProfileSessions.add(imageId)
                    ) {
                        update("Lens profile") { s ->
                            s.copy(optics = s.optics.copy(
                                distortion = remembered.distortion,
                                defringe = remembered.defringe,
                            ))
                        }
                    }
                }
                // Auto lens correction (pro-premium program Phase A#2, slice 1):
                // measure optical vignetting once per photo per session and lift
                // the corners, but only when the user hasn't touched the vignette
                // sliders — manual edits always win over the auto estimate.
                _autoLensApplied.value = false
                autoLensEstimate = null
                val so = saved.optics
                if (so.vignetteAmount == 0f && so.vignetteMidpoint == 50f &&
                    so.vignetteFeather == 50f && so.vignetteRoundness == 0f &&
                    autoLensSessions.add(imageId)
                ) {
                    autoLensCorrect()
                }
            } catch (t: Throwable) {
                // Cancellation must propagate so the launch can actually be
                // cancelled; everything else becomes an error notice instead
                // of an app crash. OutOfMemoryError is a real risk here:
                // decodePreview allocates the full preview bitmap on this
                // thread (huge panoramas on low-RAM phones) — same graceful
                // pattern as ExportManager.
                if (t is CancellationException) throw t
                // UX honesty: the screen used to show t.message verbatim,
                // which for decode/IO failures is exception jargon
                // ("java.io.FileNotFoundException: ..."). Classify into a
                // plain-language message; keep the raw text as the dimmed
                // Details caption only when it is actually technical.
                // A message that is already plain language is shown as-is,
                // so no information is lost in the common readable case.
                val raw = t.message?.takeIf { it.isNotBlank() }
                val technical = raw?.takeIf { looksTechnical(it) }?.take(220)
                _error.value = when {
                    t is OutOfMemoryError ->
                        "Not enough memory to open this photo — try closing other apps and retry"
                    technical == null -> raw ?: "Failed to open photo"
                    t is FileNotFoundException ->
                        "This photo's file couldn't be found — it may have been moved or deleted."
                    t is SecurityException ->
                        "The app can't access this photo — its permission may have been revoked."
                    else ->
                        "This photo couldn't be opened. It may be corrupt or in a format the app can't read."
                }
                _errorDetail.value = technical
            } finally {
                _loading.value = false
            }
        }
    }

    /**
     * True when a throwable's message reads as exception jargon rather than
     * something a user can act on — class names, package prefixes, or a
     * multi-line stack trace. Such messages belong in the dimmed Details
     * caption, never as the headline body.
     */
    private fun looksTechnical(msg: String): Boolean =
        msg.contains("Exception") || msg.contains("Error") ||
            msg.contains('\n') || msg.contains("java.") || msg.contains("android.")

    private fun decodePreview(uri: Uri, maxDim: Int = 2048): Bitmap? {
        // RAW program Phase 1 slice 2 / Phase 2 slice 1: RAW files cannot go
        // through BitmapFactory — route them through the native LibRaw bridge
        // (RawDecoder), which returns the ARGB_8888 bitmap the GPU pipeline
        // already renders. Non-destructive edits apply on top unchanged.
        if (isRawUri(uri)) return decodeRawPreview(uri, maxDim)
        val resolver = container.context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: return null
        // bake EXIF orientation
        var orient = ExifInterface.ORIENTATION_NORMAL
        try {
            resolver.openInputStream(uri)?.use {
                orient = ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        } catch (_: Exception) { }
        if (orient == ExifInterface.ORIENTATION_NORMAL) return bmp
        val m = android.graphics.Matrix()
        when (orient) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            // Mirrored rotations (front-camera / mirrored captures): previously
            // fell through to `else -> return bmp`, leaving the preview
            // un-oriented. Mirrors ExportManager.applyOrientation exactly.
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return bmp
        }
        // The rotated copy is a second full-preview-size allocation: on a
        // low-memory device it can throw OutOfMemoryError. open()'s outer
        // Throwable catch already turns that into the friendly OOM notice,
        // but the decoded source would leak (never recycled, never stored)
        // exactly when memory is scarcest — recycle it before rethrowing.
        return try {
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                .also { bmp.recycle() }
        } catch (t: Throwable) {
            if (!bmp.isRecycled) bmp.recycle()
            throw t
        }
    }

    /**
     * Resolves the display name of a library URI (works for MediaStore,
     * Downloads, and file-provider URIs); falls back to the path segment.
     * Never throws.
     */
    private fun displayNameFor(uri: Uri): String? {
        return try {
            container.context.contentResolver.query(
                uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: uri.lastPathSegment
        } catch (_: Exception) {
            uri.lastPathSegment
        }
    }

    /**
     * True when the library URI points at a RAW file (any format in
     * [RawDecoder.rawExtensions], Phase 2 slice 1).
     */
    private fun isRawUri(uri: Uri): Boolean = RawDecoder.isRaw(displayNameFor(uri))

    /**
     * RAW program Phase 1 slice 2 / Phase 2 slice 1: decodes a RAW library
     * photo (DNG or proprietary — LibRaw sniffs the format, the extension
     * only decides routing) for the editor preview. LibRaw needs a real
     * file path, so the content:// stream is staged into a temp file first
     * (deleted in finally).
     *
     * Throws IllegalStateException with RawDecoder's specific reason on
     * failure (e.g. the lossy-JPEG DNG message, or LibRaw's camera-support
     * error) so the caller surfaces it instead of a generic
     * "could not decode". The bridge decodes at half sensor resolution;
     * the result is capped to [maxDim] like the BitmapFactory path so huge
     * RAWs stay inside the preview heap budget.
     *
     * Phase 1 slice 3 (generalized Phase 2 slice 4): RAWs LibRaw cannot
     * unpack fall back to the camera's embedded preview inside
     * RawDecoder — this function surfaces that via [_embeddedPreviewFormat]
     * (the actual format label, e.g. "DNG" / "NEF") so the UI can label
     * the photo honestly instead of hardcoding "lossy-JPEG DNG".
     */
    private fun decodeRawPreview(uri: Uri, maxDim: Int = 2048): Bitmap? {
        val ctx = container.context
        // UUID suffix: two RAW opens in the same millisecond must never share
        // a temp path, or one decode would clobber the other's input.
        val tmp = File(ctx.cacheDir, "h2w_raw_${UUID.randomUUID()}.raw")
        try {
            ctx.contentResolver.openInputStream(uri)?.use { ins ->
                tmp.outputStream().use { out -> ins.copyTo(out) }
            } ?: throw IllegalStateException("Could not read the RAW file")
            val bmp = RawDecoder.decodeFile(tmp.absolutePath)
                ?: throw IllegalStateException(
                    RawDecoder.lastError ?: "Could not decode this RAW")
            _embeddedPreviewFormat.value =
                if (RawDecoder.usedEmbeddedPreview) {
                    RawDecoder.rawFormatName(displayNameFor(uri)) ?: "RAW"
                } else {
                    null
                }
            if (maxOf(bmp.width, bmp.height) <= maxDim) return bmp
            val scale = maxDim / maxOf(bmp.width, bmp.height).toFloat()
            val sw = (bmp.width * scale).toInt().coerceAtLeast(1)
            val sh = (bmp.height * scale).toInt().coerceAtLeast(1)
            // Same leak as the BitmapFactory path above: if the downscale
            // allocation OOMs, open()'s catch shows the friendly notice but
            // the full-size LibRaw decode would leak — recycle it first.
            return try {
                Bitmap.createScaledBitmap(bmp, sw, sh, true)
                    .also { bmp.recycle() }
            } catch (t: Throwable) {
                if (!bmp.isRecycled) bmp.recycle()
                throw t
            }
        } finally {
            try { tmp.delete() } catch (_: Exception) { }
        }
    }

    // ------------------------------------------------------------------
    // Edit state + undo/redo
    // ------------------------------------------------------------------

    /**
     * Applies a state transform. When [gesture] is true (slider drag, brush
     * stroke), the pre-gesture snapshot is pushed once via [beginGesture].
     */
    fun update(label: String? = null, gesture: Boolean = false, transform: (EditState) -> EditState) {
        // Any committed edit ends the auto-operation session: the pre-auto
        // baseline is gone, so a later toggle-off could never restore it.
        // Clear the applied flags together with the baseline — leaving a flag
        // set with no baseline produced the Stale dead-end (e.g. AI Pro
        // Retouch -> preset -> tap again reported Stale and flipped to a
        // re-apply instead of offering a clean fresh apply). The auto paths
        // re-pin baseline + flag right after their own update() when the
        // state was untouched meanwhile.
        clearAutoBaseline()
        if (!gesture) {
            // Abandoned-gesture guard: if a previous gesture never reached
            // endGesture() (its UI was torn down mid-drag before the release
            // callback could fire), its snapshot is stale. Fold it into the
            // undo stack now: otherwise a later endGesture() would push the
            // outdated snapshot on top of newer committed edits, and undo
            // would silently jump back past them. No-op on every normal path
            // (the snapshot is null there).
            gestureSnapshot?.let { stale ->
                gestureSnapshot = null
                if (stale != _state.value) {
                    undoStack.addLast(stale)
                    if (undoStack.size > 60) undoStack.removeFirst()
                }
            }
            pushUndo()
            redoStack.clear()
        }
        val old = _state.value
        var next = transform(old)
        // Lightroom "Constrain to Image": a geometry change (rotate, quarter
        // turns, keystone) or a committed crop change auto-shrinks the crop to
        // the largest inscribed rectangle so the photo never renders black
        // corners. Skipped for crop gestures (interactive crop drags /
        // aspect chips) so the rect doesn't fight the user's finger — those
        // are constrained once, on release, in endGesture().
        if (next.geometry.constrainCrop &&
            (next.geometry != old.geometry || (!gesture && next.crop != old.crop))) {
            val constrained = Geometry.constrainToImage(next.crop, next.geometry)
            if (constrained != next.crop) next = next.copy(crop = constrained)
        }
        _state.value = next
        syncUndoRedo()
        requestRender()
        if (!gesture) {
            // A committed (non-gesture) edit ends any incremental paint
            // session: the mask cache holds the final raster from here.
            synchronized(paintLock) { paintAcc = null }
            scheduleAutosave()
            label?.let { recordHistory(it) }
        }
    }

    fun beginGesture() {
        if (gestureSnapshot == null) gestureSnapshot = _state.value
    }

    fun endGesture(label: String) {
        val snap = gestureSnapshot
        gestureSnapshot = null
        // The paint gesture is over: drop the incremental accumulator and
        // free its ~1MB. The mask cache keeps serving the final raster.
        synchronized(paintLock) { paintAcc = null }
        var final = _state.value
        // Crop gestures (interactive crop drags, aspect chips) skip the
        // constrain in update() so the rect tracks the finger; apply it once
        // on release when only the crop changed during the gesture.
        if (snap != null && final.geometry.constrainCrop &&
            final.geometry == snap.geometry && final.crop != snap.crop) {
            val constrained = Geometry.constrainToImage(final.crop, final.geometry)
            if (constrained != final.crop) {
                final = final.copy(crop = constrained)
                _state.value = final
                requestRender()
            }
        }
        if (snap != null && snap != final) {
            undoStack.addLast(snap)
            if (undoStack.size > 60) undoStack.removeFirst()
            redoStack.clear()
            syncUndoRedo()
            scheduleAutosave()
            recordHistory(label)
        }
    }

    private fun pushUndo() {
        undoStack.addLast(_state.value)
        if (undoStack.size > 60) undoStack.removeFirst()
    }

    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        clearAutoBaseline()
        redoStack.addLast(_state.value)
        _state.value = prev
        syncUndoRedo(); requestRender(); scheduleAutosave()
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        clearAutoBaseline()
        undoStack.addLast(_state.value)
        _state.value = next
        syncUndoRedo(); requestRender(); scheduleAutosave()
    }

    private fun syncUndoRedo() {
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = redoStack.isNotEmpty()
    }

    fun resetAll() {
        clearAutoBaseline()
        update("Reset all") { EditState() }
    }

    /** Copy-settings: snapshots the current edit state into the session clipboard. */
    fun copyEdits() {
        container.copiedEdits.value = container.editStore.encode(_state.value)
    }

    /**
     * Paste-settings: replaces the current edit state with the clipboard copy.
     * Image-specific work (retouch spots, AI-derived masks) is dropped by
     * [EditState.sanitizedForPaste]. Undoable, one history entry.
     * @return false when the clipboard is empty or the payload is corrupt.
     * A Throwable (not just Exception) during decode is also a plain false:
     * an Error — e.g. OOM on a corrupt/oversized payload — on this tap must
     * not crash the editor; the library-side paste path already catches
     * Throwable, and this matches it. No coroutine runs here, so there is
     * no cancellation to rethrow.
     */
    fun pasteEdits(): Boolean {
        val raw = container.copiedEdits.value ?: return false
        val pasted = try {
            container.editStore.decode(raw).sanitizedForPaste()
        } catch (t: Throwable) {
            android.util.Log.w("EditorViewModel", "pasteEdits decode failed", t)
            return false
        }
        update("Paste edits") { pasted }
        return true
    }

    private fun recordHistory(label: String) {
        val id = _imageId.value ?: return
        val json = container.editStore.encode(_state.value)
        guardLaunch {
            // Best-effort persistence: a DB hiccup must never crash the editor.
            try {
                container.library.recordEdit(id, label, json)
                _historyEntries.value = container.library.history(id)
            } catch (e: Exception) {
                if (e is CancellationException) throw e // don't swallow coroutine cancellation
                android.util.Log.w("EditorViewModel", "recordHistory failed", e)
            }
        }
    }

    /** Reloads the persisted edit history for the open photo. */
    fun refreshHistory() {
        val id = _imageId.value ?: return
        guardLaunch {
            try {
                _historyEntries.value = container.library.history(id)
            } catch (e: Exception) {
                if (e is CancellationException) throw e // don't swallow coroutine cancellation
                android.util.Log.w("EditorViewModel", "refreshHistory failed", e)
            }
        }
    }

    /**
     * Restores a previous edit step from history. Undoable via
     * update("Restore: ..."), and the restore itself is recorded as a new
     * history step so nothing is silently lost. A Throwable (not just
     * Exception) during decode is a plain false — an Error on this tap
     * must leave the history dialog open for another try instead of
     * crashing the editor; same contract as [pasteEdits] above, and no
     * coroutine runs here so there is no cancellation to rethrow.
     */
    fun restoreHistory(entry: HistoryEntry): Boolean {
        val state = try {
            container.editStore.decode(entry.stateJson)
        } catch (t: Throwable) {
            android.util.Log.w("EditorViewModel", "restoreHistory decode failed", t)
            return false
        }
        update("Restore: ${entry.label}") { state }
        return true
    }

    /** Clears the history list (the current edits are NOT touched). */
    fun clearHistory() {
        val id = _imageId.value ?: return
        guardLaunch {
            try {
                container.library.clearHistory(id)
                _historyEntries.value = emptyList()
            } catch (e: Exception) {
                if (e is CancellationException) throw e // don't swallow coroutine cancellation
                android.util.Log.w("EditorViewModel", "clearHistory failed", e)
            }
        }
    }

    // ------------------------------------------------------------------
    // Snapshots (Lightroom parity: named full-state bookmarks per photo)
    // ------------------------------------------------------------------

    /** Live list of this photo's named snapshots, newest first. */
    fun snapshots(): StateFlow<List<EditSnapshot>> =
        container.snapshots.snapshots(_imageId.value ?: "")

    /** Reloads this photo's snapshots (called on open + after mutations). */
    fun refreshSnapshots() {
        val id = _imageId.value ?: return
        guardLaunch {
            try {
                container.snapshots.refresh(id)
            } catch (e: Exception) {
                if (e is CancellationException) throw e // don't swallow coroutine cancellation
                android.util.Log.w("EditorViewModel", "refreshSnapshots failed", e)
            }
        }
    }

    /** Saves the current full edit state as a named snapshot. */
    fun takeSnapshot(name: String, onDone: (Boolean) -> Unit) {
        val id = _imageId.value ?: run { onDone(false); return }
        val state = _state.value
        guardLaunch {
            val ok = try {
                container.snapshots.take(id, name, state) != null
            } catch (t: Throwable) {
                // Throwable, not just Exception: an Error (e.g. OOM) during
                // the save must still report onDone(false) below instead of
                // leaving the snapshot UI waiting forever. Cancellation is
                // still rethrown; matches duplicateCurrent/runGpuSelfTest.
                if (t is CancellationException) throw t
                android.util.Log.w("EditorViewModel", "takeSnapshot failed", t)
                false
            }
            onDone(ok)
        }
    }

    /**
     * Applies a snapshot: replaces the whole edit state (valid — snapshots
     * are per-photo). Undoable via update(), and recorded in History like
     * every other restore path.
     */
    fun applySnapshot(snap: EditSnapshot) {
        update("Snapshot: ${snap.name}") { snap.state }
    }

    fun deleteSnapshot(snapshotId: String) {
        val id = _imageId.value ?: return
        guardLaunch {
            try {
                container.snapshots.delete(id, snapshotId)
            } catch (e: Exception) {
                if (e is CancellationException) throw e // don't swallow coroutine cancellation
                android.util.Log.w("EditorViewModel", "deleteSnapshot failed", e)
            }
        }
    }

    fun renameSnapshot(snapshotId: String, name: String) {
        val id = _imageId.value ?: return
        guardLaunch {
            try {
                container.snapshots.rename(id, snapshotId, name)
            } catch (e: Exception) {
                if (e is CancellationException) throw e // don't swallow coroutine cancellation
                android.util.Log.w("EditorViewModel", "renameSnapshot failed", e)
            }
        }
    }

    private fun scheduleAutosave() {
        saveJob?.cancel()
        val id = _imageId.value ?: return
        val snapshot = _state.value
        saveJob = guardLaunch {
            delay(900)
            // Best-effort: autosave fires after every edit, so it must never
            // crash the editor (e.g. disk full, serialization edge case).
            // The next edit reschedules and retries automatically.
            try {
                container.editStore.save(id, snapshot)
                container.library.byId(id)?.let {
                    // touch without spamming history
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e // don't swallow coroutine cancellation
                android.util.Log.w("EditorViewModel", "autosave failed", e)
            }
        }
    }

    // ------------------------------------------------------------------
    // Render scheduling
    // ------------------------------------------------------------------

    fun onViewSize(w: Int, h: Int) {
        if (w != viewW || h != viewH) {
            viewW = w; viewH = h
            requestRender()
        }
    }

    fun setClipMode(mode: Int) {
        _clipMode.value = mode
        requestRender()
    }

    fun setShowOriginal(show: Boolean) {
        _showOriginal.value = show
        requestRender()
    }

    /** Called by the UI when entering/exiting the crop tool. In crop preview
     *  mode the uvMatrix ignores s.crop so the full image stays visible under
     *  the interactive crop-rect overlay. */
    fun setCropPreviewMode(enabled: Boolean) {
        if (cropPreviewMode != enabled) {
            cropPreviewMode = enabled
            requestRender()
        }
    }

    /** Toggle the before/after split view (divider starts centered). */
    fun toggleSplit() {
        // Root cause of the 2026-10-15 "stuck split" user report: GlEngine
        // never cached the uSplitX uniform location, so glUniform1f(-1, ...)
        // was a silent no-op and the shader's default 0.0 kept the split
        // permanently on at screen center (preview AND export). With the
        // uniform cached, the toggle works as designed.
        _splitX.value = if (_splitX.value != null) null else 0f
        requestRender()
    }

    /** Explicitly enable/disable the split view; enabling keeps the last position. */
    fun setSplitEnabled(enabled: Boolean) {
        if (enabled) {
            if (_splitX.value == null) {
                _splitX.value = 0f
                requestRender()
            }
        } else if (_splitX.value != null) {
            _splitX.value = null
            requestRender()
        }
    }

    /** Move the split divider; [ndcX] is NDC x in -1..1. */
    fun setSplitX(ndcX: Float) {
        if (_splitX.value == null) return
        val v = ndcX.coerceIn(-0.95f, 0.95f)
        if (_splitX.value != v) {
            _splitX.value = v
            requestRender()
        }
    }

    /**
     * White-balance eyedropper: neutralizes the tapped displayed color by
     * solving new temperature/tint (undoable, one history entry). Returns
     * false when the sample was too dark to trust — nothing is changed.
     */
    fun applyWhiteBalancePick(r: Float, g: Float, b: Float): Boolean {
        val l = _state.value.light
        val (t, ti) = ImageMath.pickWhiteBalance(r, g, b, l.temp / 100f, l.tint / 100f)
            ?: return false
        update("White balance picker") { s ->
            s.copy(light = s.light.copy(
                temp = (t * 100f).coerceIn(-100f, 100f),
                tint = (ti * 100f).coerceIn(-100f, 100f),
            ))
        }
        return true
    }

    /**
     * Guided perspective: solves the vertical-perspective amount that makes
     * the user-drawn source-UV line read vertical on screen (undoable, one
     * history entry). Returns false — keeping the guide active for another
     * try — when the line is degenerate or unusable, or when the solver
     * itself fails (an Error is logged and reported as a failed guide,
     * never a crash); nothing is changed.
     * Deterministic pixel math, no machine learning.
     */
    fun applyPerspectiveGuide(p1: Pt, p2: Pt): Boolean {
        val bmp = _preview.value ?: return false
        val s = _state.value
        val vw = viewW.coerceAtLeast(1); val vh = viewH.coerceAtLeast(1)
        val sol = try {
            Geometry.solvePerspectiveV(p1, p2, vw, vh, bmp.width, bmp.height, s.crop, s.geometry)
        } catch (e: Throwable) {
            if (e is CancellationException) throw e // don't swallow coroutine cancellation
            android.util.Log.e("EditorViewModel", "applyPerspectiveGuide failed", e)
            return false
        }
        if (sol == null) return false
        update("Guided perspective") { st ->
            st.copy(geometry = st.geometry.copy(perspectiveV = sol))
        }
        return true
    }

    /**
     * Guided horizontal perspective: solves the horizontal-perspective amount
     * that makes the user-drawn source-UV line read horizontal on screen
     * (undoable, one history entry). Returns false — keeping the guide active
     * for another try — when the line is degenerate or unusable, or when the
     * solver itself fails (an Error is logged and reported as a failed
     * guide, never a crash); nothing is changed. Deterministic pixel math,
     * no machine learning.
     */
    fun applyPerspectiveGuideH(p1: Pt, p2: Pt): Boolean {
        val bmp = _preview.value ?: return false
        val s = _state.value
        val vw = viewW.coerceAtLeast(1); val vh = viewH.coerceAtLeast(1)
        val sol = try {
            Geometry.solvePerspectiveH(p1, p2, vw, vh, bmp.width, bmp.height, s.crop, s.geometry)
        } catch (e: Throwable) {
            if (e is CancellationException) throw e // don't swallow coroutine cancellation
            android.util.Log.e("EditorViewModel", "applyPerspectiveGuideH failed", e)
            return false
        }
        if (sol == null) return false
        update("Guided perspective") { st ->
            st.copy(geometry = st.geometry.copy(perspectiveH = sol))
        }
        return true
    }

    /**
     * Auto straighten: detects the horizon with deterministic Sobel/edge-vote
     * analysis ([Straighten], no machine learning) and levels the photo, as
     * one undoable "Auto straighten" edit. Replaces rotate/rotate90 like the
     * guided straighten does; flips are accounted for. [onNoHorizon] fires on
     * Main when no confident horizon was found — nothing is changed, so the
     * UI can say so honestly instead of guessing. [onError] fires on Main
     * when the analysis itself fails (an Error, e.g. OOM in the working
     * buffers): it must stay distinct from [onNoHorizon], because reporting
     * "no clear horizon" for a failed analysis would mislead the user into
     * drawing a line for a photo whose horizon was never actually checked.
     */
    fun autoStraighten(
        onNoHorizon: () -> Unit = {},
        onError: () -> Unit = {},
    ) {
        val bmp = _preview.value ?: return
        // Photo-change race: the horizon belongs to the photo open at tap
        // time; a mid-analysis photo switch must not rotate the new photo.
        val tapPhotoId = _libraryImage.value?.id
        guardLaunch(Dispatchers.Default) {
            val w = bmp.width; val h = bmp.height
            if (w <= 0 || h <= 0) return@guardLaunch
            // Both the pixel-buffer allocation and getPixels can throw on
            // constrained devices (OutOfMemoryError); the analysis is
            // best-effort, so a failed read quietly skips straightening
            // instead of crashing the editor.
            val px = try {
                val buf = IntArray(w * h)
                bmp.getPixels(buf, 0, w, 0, 0, w, h)
                buf
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                return@guardLaunch
            }
            var level: Straighten.AutoLevel? = null
            var analysisFailed = false
            try {
                level = Straighten.detectHorizon(px, w, h)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t // don't swallow coroutine cancellation
                // An Error (e.g. OOM in the working buffers) must not escape
                // to scopeGuard, which only logs: the tap would silently do
                // nothing. It also must not masquerade as "no horizon" —
                // see the [onError] contract above.
                level = null
                analysisFailed = true
            }
            withContext(Dispatchers.Main) {
                // Stale: stay silent — onNoHorizon/onError would misreport
                // on the newly opened photo.
                if (stalePhoto(tapPhotoId)) return@withContext
                val l = level
                if (analysisFailed) {
                    onError()
                } else if (l == null) {
                    onNoHorizon()
                } else {
                    val g = _state.value.geometry
                    val r = Straighten.levelRotation(l.angleDeg, g.flipH, g.flipV)
                    update("Auto straighten") { s ->
                        s.copy(geometry = s.geometry.copy(rotate = r.rotate, rotate90 = r.quarterTurns))
                    }
                }
            }
        }
    }

    /**
     * Applies a white-balance scene preset (Daylight, Cloudy, Shade,
     * Tungsten, Fluorescent, Flash) as one undoable edit. Sets absolute
     * temp/tint, like Lightroom's WB presets — no EditState change needed,
     * so copy/paste and saved edits keep working unchanged.
     */
    fun applyWbPreset(preset: ImageMath.WbPreset) {
        update("White balance: ${preset.label}") { s ->
            s.copy(light = s.light.copy(temp = preset.temp * 100f, tint = preset.tint * 100f))
        }
    }

    /**
     * Lens-blur focus placement: the user taps/drags on the photo to move
     * the focus point. Down begins the gesture, drags update it live, up
     * ends it — the whole gesture is ONE undoable "Lens blur focus" entry
     * (same pattern as crop drags), and it is recorded in History.
     */
    fun focusPointStart() { beginGesture() }

    fun focusPointDrag(uv: Pt) {
        update(gesture = true) { s ->
            s.copy(optics = s.optics.copy(
                lensFocusX = uv.x.coerceIn(0f, 1f),
                lensFocusY = uv.y.coerceIn(0f, 1f)))
        }
    }

    fun focusPointEnd() { endGesture("Lens blur focus") }

    /**
     * Tone-curve targeted adjustment (Lightroom's TAT): [targetY] is the
     * sampled on-screen tone (0..1 — luminance for master, channel value
     * for R/G/B), [channel] is 0=master 1=R 2=G 3=B. Grabs (or inserts)
     * the curve point controlling that tone via [targetCurvePoint] and
     * opens ONE undoable gesture; [curveTargetDrag] then bends it.
     */
    fun curveTargetStart(channel: Int, targetY: Float) {
        val ch = channel.coerceIn(0, 3)
        val (pts, idx) = targetCurvePoint(curveForChannel(ch, _state.value), targetY)
        beginGesture()
        update(gesture = true) { s -> s.withCurveChannel(ch, ToneCurve(pts)) }
        curveTarget = CurveTargetGrab(ch, idx, pts[idx].y)
    }

    /**
     * Bends the grabbed curve point: [dyFrac] is +up in curve units (0..1),
     * applied absolutely from the grab y so finger jitter can't drift the
     * point. The point's x never moves (endpoints stay pinned at 0/1).
     */
    fun curveTargetDrag(dyFrac: Float) {
        val t = curveTarget ?: return
        update(gesture = true) { s ->
            val pts = curveForChannel(t.channel, s).points.sortedBy { it.x }
            if (t.index !in pts.indices) return@update s
            val np = pts.toMutableList()
            np[t.index] = np[t.index].copy(y = (t.startY + dyFrac).coerceIn(0f, 1f))
            s.withCurveChannel(t.channel, ToneCurve(np))
        }
    }

    /** Ends the targeted-adjustment gesture; safe no-op if never started. */
    fun curveTargetEnd() {
        if (curveTarget == null) return
        curveTarget = null
        endGesture("Tone curve target")
    }

    private fun curveForChannel(channel: Int, s: EditState): ToneCurve = when (channel) {
        1 -> s.light.redCurve
        2 -> s.light.greenCurve
        3 -> s.light.blueCurve
        else -> s.light.toneCurve
    }

    private fun EditState.withCurveChannel(channel: Int, curve: ToneCurve): EditState {
        val nl = when (channel) {
            1 -> light.copy(redCurve = curve)
            2 -> light.copy(greenCurve = curve)
            3 -> light.copy(blueCurve = curve)
            else -> light.copy(toneCurve = curve)
        }
        return copy(light = nl)
    }

    /** Grabbed curve point for the targeted-adjustment gesture. */
    private data class CurveTargetGrab(val channel: Int, val index: Int, val startY: Float)
    private var curveTarget: CurveTargetGrab? = null

    /**
     * Gray-world auto white balance: averages the source photo on a
     * background thread ([ImageMath.autoWhiteBalance]), then applies the
     * solved temp/tint as one undoable "Auto white balance" edit.
     * Deterministic pixel math on a 96px downscale; no machine learning.
     * Reads the source bitmap (not the rendered preview), so the result is
     * absolute — it replaces, never stacks on, the existing WB settings.
     */
    fun autoWhiteBalance() {
        // Private snapshot: the worker reads pixels on Dispatchers.Default
        // while the UI thread may recycle the live preview bitmap.
        val bmp = snapshotPreview() ?: return
        // Photo-change race: the solved WB belongs to the photo open at tap
        // time; a mid-analysis photo switch must not re-tint the new photo.
        val tapPhotoId = _libraryImage.value?.id
        guardLaunch(Dispatchers.Default) {
            try {
                val w = bmp.width; val h = bmp.height
                if (w <= 0 || h <= 0) return@guardLaunch
                val sw = 96
                val sh = (96L * h / w).toInt().coerceAtLeast(1)
                val small = Bitmap.createScaledBitmap(bmp, sw, sh, true)
                val px = IntArray(sw * sh)
                small.getPixels(px, 0, sw, 0, 0, sw, sh)
                small.recycle()
                var sr = 0L; var sg = 0L; var sb = 0L
                for (p in px) {
                    sr += (p shr 16) and 0xFF
                    sg += (p shr 8) and 0xFF
                    sb += p and 0xFF
                }
                val n = px.size.toFloat()
                val sol = ImageMath.autoWhiteBalance(sr / n / 255f, sg / n / 255f, sb / n / 255f)
                    ?: return@guardLaunch
                withContext(Dispatchers.Main) {
                    if (stalePhoto(tapPhotoId)) return@withContext
                    update("Auto white balance") { s ->
                        s.copy(light = s.light.copy(temp = sol.first * 100f, tint = sol.second * 100f))
                    }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                android.util.Log.e("EditorViewModel", "autoWhiteBalance failed", t)
            } finally {
                if (!bmp.isRecycled) bmp.recycle()
            }
        }
    }

    private fun curvesKey(s: EditState): String =
        s.light.toneCurve.points.toString() + s.light.redCurve.points.toString() +
            s.light.greenCurve.points.toString() + s.light.blueCurve.points.toString()

    /**
     * Automatic lens correction (pro-premium program Phase A#2, slice 1):
     * optical-vignette removal. Measures radial corner falloff on a 96px
     * downscale ([AutoLens.analyzeVignette]) and applies the solved negative
     * vignette amount as one undoable "Auto lens correction" edit — so the
     * user can always undo it, and the panel Reset restores the defaults.
     * Deterministic pixel math; no machine learning, no lens database needed
     * for this slice. Reads the preview snapshot like [autoWhiteBalance].
     */
    private val _autoLensApplied = MutableStateFlow(false)
    val autoLensApplied: StateFlow<Boolean> = _autoLensApplied.asStateFlow()
    private var autoLensEstimate: VignetteEstimate? = null
    private val autoLensSessions = mutableSetOf<String>()

    /**
     * Per-lens correction memory (pro-premium program Phase A#2, slice 2).
     * The lens is identified from EXIF at photo-open time; the app remembers
     * the distortion/defringe the user dialed in for that lens and
     * auto-applies it to later photos from the same lens — one undoable
     * "Lens profile" edit, only when those sliders are still at defaults.
     * Manual edits always win; we never invent correction numbers.
     */
    private val _lensLabel = MutableStateFlow<String?>(null)
    val lensLabel: StateFlow<String?> = _lensLabel.asStateFlow()
    private val _lensProfile = MutableStateFlow<LensCorrection?>(null)
    val lensProfile: StateFlow<LensCorrection?> = _lensProfile.asStateFlow()
    private var lensKey: String? = null
    private val lensProfileSessions = mutableSetOf<String>()

    fun autoLensCorrect() {
        // Private snapshot: the worker reads pixels on Dispatchers.Default
        // while the UI thread may recycle the live preview bitmap.
        val bmp = snapshotPreview() ?: return
        // Photo-change race: the vignette estimate belongs to the photo
        // open at tap time; a mid-analysis photo switch must not apply it
        // to the new photo (openPhoto itself calls this per photo).
        val tapPhotoId = _libraryImage.value?.id
        guardLaunch(Dispatchers.Default) {
            try {
                val w = bmp.width; val h = bmp.height
                if (w <= 0 || h <= 0) return@guardLaunch
                val sw = 96
                val sh = (96L * h / w).toInt().coerceAtLeast(1)
                val small = Bitmap.createScaledBitmap(bmp, sw, sh, true)
                val px = IntArray(sw * sh)
                small.getPixels(px, 0, sw, 0, 0, sw, sh)
                small.recycle()
                val est = AutoLens.analyzeVignette(px, sw, sh) ?: return@guardLaunch
                withContext(Dispatchers.Main) {
                    if (stalePhoto(tapPhotoId)) return@withContext
                    autoLensEstimate = est
                    _autoLensApplied.value = true
                    update("Auto lens correction") { s ->
                        s.copy(optics = s.optics.copy(
                            vignetteAmount = est.amount,
                            vignetteMidpoint = est.midpoint,
                            vignetteFeather = est.feather,
                        ))
                    }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                android.util.Log.e("EditorViewModel", "autoLensCorrect failed", t)
            } finally {
                if (!bmp.isRecycled) bmp.recycle()
            }
        }
    }

    /**
     * Reads the lens identity from EXIF: (storage key, display label).
     * Prefers TAG_LENS_MODEL, falls back to the camera body (TAG_MODEL).
     * Crash-hardened — EXIF reads on arbitrary content URIs fail often.
     */
    private fun readLensIdentity(uri: android.net.Uri): Pair<String?, String?> {
        return try {
            container.context.contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                val lensModel = exif.getAttribute(ExifInterface.TAG_LENS_MODEL)
                val cameraModel = exif.getAttribute(ExifInterface.TAG_MODEL)
                LensProfiles.lensKeyFromExif(lensModel, cameraModel) to
                    LensProfiles.lensLabelFromExif(lensModel, cameraModel)
            } ?: (null to null)
        } catch (_: Exception) {
            null to null
        }
    }

    /**
     * Remembers the current distortion/defringe as this lens's profile.
     * Called from the Optics panel's "Remember" button — explicit user
     * consent, so the values are the user's own, never invented.
     */
    fun rememberLensProfile() {
        val key = lensKey ?: return
        val o = _state.value.optics
        val c = LensCorrection(
            distortion = o.distortion.coerceIn(-100f, 100f),
            defringe = o.defringe.coerceIn(0f, 100f),
        )
        if (!LensProfiles.isMeaningful(c)) return // nothing worth remembering
        container.lensProfiles.put(key, c)
        _lensProfile.value = c
    }

    /** Forgets the remembered profile for the current lens. */
    fun forgetLensProfile() {
        val key = lensKey ?: return
        container.lensProfiles.remove(key)
        _lensProfile.value = null
    }

    fun requestRender() {
        renderJob?.cancel()
        val bmp = _preview.value ?: return
        renderJob = guardLaunch(Dispatchers.Default) {
            // Race guard (bitmap lifetime, 2026-10-21): the live preview
            // bitmap may be recycled and replaced on the Main thread
            // (undo removal, wavelet/retouch result swap, new photo) while
            // this background render is mid-flight. Reading a recycled
            // bitmap throws inside buildMaskLuminance/despilledBitmap and
            // the frame is dropped noisily via scopeGuard. Every swap site
            // calls requestRender() again on Main, so a fresh job for the
            // new bitmap is always in flight — dropping the stale one here
            // is safe and silent. bmp.isRecycled is thread-safe to call.
            if (bmp.isRecycled || _preview.value !== bmp) return@guardLaunch
            val s = _state.value
            val vw = viewW.coerceAtLeast(1); val vh = viewH.coerceAtLeast(1)
            // In crop preview mode, ignore s.crop in the matrix so the full
            // image stays visible under the interactive crop-rect overlay.
            val cropForMatrix = if (cropPreviewMode) CropParams() else s.crop
            val uv = Geometry.uvMatrix(vw, vh, bmp.width, bmp.height, cropForMatrix, s.geometry)
            // masks (cached by revision)
            // While the Remove tool is active, reserve the last mask texture
            // slot for its live red overlay so the user sees exactly what
            // will be removed before tapping Remove.
            val removeOverlayActive = _removeToolActive.value
            val maskSlotBudget =
                if (removeOverlayActive) GlEngine.MAX_MASKS - 1 else GlEngine.MAX_MASKS
            val enabled = s.masks.filter { it.enabled }.take(maskSlotBudget)
            // Pro auto-mask: if any stroke wants edge-aware painting, build a
            // 512px source luminance field once and share it across rasters.
            val needsAutoMask = enabled.any { m ->
                m.components.any { c -> c.strokes.any { it.autoMask } }
            }
            val autoMaskLum: FloatArray? = if (needsAutoMask) {
                buildMaskLuminance(bmp, MaskEngine.MAX_RASTER)
            } else null
            val maskBmps = enabled.map { m ->
                val cached = maskCache[m.id]
                if (cached != null && cached.first == m.revision) cached.second
                else {
                    val raster = rasterizeSafely("mask") {
                        MaskEngine.rasterize(m, container.aiMasks, autoMaskLum,
                            brushAccFor(m))
                    }
                    if (raster != null) {
                        maskCache[m.id] = m.revision to raster
                        // drop stale entries
                        val live = s.masks.map { it.id }.toSet()
                        maskCache.keys.filter { it !in live }.forEach { maskCache.remove(it) }
                        raster
                    } else {
                        // Keep the stale raster when there is one (better than
                        // a blind spot); otherwise the empty-mask fallback so
                        // the list stays aligned with the enabled masks.
                        cached?.second ?: emptyMaskBitmap
                    }
                }
            }
            // Hair decontamination textures (aligned 1:1 with maskBmps):
            // despilled RGB at raster resolution, cached by mask revision.
            // Runs on the same Default-dispatcher frame as the rasterize
            // above; a failure degrades to null (feature off for that slot),
            // never a crash and never a darkened image (GlEngine gates the
            // shader strength on the upload result).
            val deconBmps: List<Bitmap?> = enabled.mapIndexed { idx, m ->
                if (m.decontaminate <= 0f) {
                    deconCache.remove(m.id)
                    null
                } else {
                    val cached = deconCache[m.id]
                    if (cached != null && cached.first == m.revision) cached.second
                    else {
                        val deconBmp = rasterizeSafely("decontaminate") {
                            MaskEngine.despilledBitmap(m, maskBmps[idx], bmp)
                        }
                        if (deconBmp != null) {
                            deconCache[m.id] = m.revision to deconBmp
                            val live = s.masks.map { it.id }.toSet()
                            deconCache.keys.filter { it !in live }.forEach { deconCache.remove(it) }
                            deconBmp
                        } else cached?.second
                    }
                }
            }
            // retouch overlay (cached by content hash)
            val spots = s.retouch.spots
            val spotsHash = spots.hashCode()
            val retouchBmp: Bitmap? = if (spots.isEmpty()) null else {
                val cached = retouchCache
                if (cached != null && cached.first == spotsHash) cached.second
                else {
                    val raster = rasterizeSafely("retouch") {
                        RetouchEngine.rasterize(spots, bmp.width, bmp.height, bmp)
                    }
                    if (raster != null) {
                        retouchCache = spotsHash to raster
                        raster
                    } else cached?.second // keep showing the previous overlay
                }
            }
            val ck = curvesKey(s)
            if (ck != lastCurvesKey) { lastCurvesKey = ck; curvesRev++ }
            // Remove-tool live overlay: cached copy of the removal mask in
            // the reserved last slot; its revision rides inside masksRev so
            // the GL thread re-uploads exactly when strokes change.
            val removalBmp = if (removeOverlayActive) removalOverlayBitmap() else null
            val allMaskBmps = if (removalBmp != null) maskBmps + removalBmp else maskBmps
            // Decontamination textures stay aligned with allMaskBmps; the
            // reserved Remove-overlay slot has no despill (always null).
            val allDeconBmps: List<Bitmap?> =
                if (removalBmp != null) deconBmps + null else deconBmps
            val removalRev = if (removalBmp != null) _removalMaskRevision.value else 0L
            val masksRev = enabled.sumOf { it.revision } + removalRev
            val removalSlot = if (removalBmp != null) allMaskBmps.lastIndex else -1
            val overlayIdx = _selectedMaskId.value?.let { id ->
                enabled.indexOfFirst { it.id == id }
            } ?: -1
            val overlayMaskIndex = when {
                removalSlot >= 0 -> removalSlot
                container.settings.maskViewMode.value != 0 -> overlayIdx
                else -> -1
            }
            // The Remove tool's hint promises a red area; keep its overlay red.
            // Settings mode 1 = red overlay -> shader 0; 2 = B&W -> shader 1
            // (mode 0 = off can't reach here: overlayMaskIndex would be -1).
            val maskViewMode = if (removalSlot >= 0) 0
                else (container.settings.maskViewMode.value - 1).coerceIn(0, 1)
            // Stash for the black-frame watchdog: overlay and mask-check
            // modes intentionally repaint the frame, so a black frame is
            // only suspicious when both are off.
            lastOverlayIdx = overlayMaskIndex
            lastMaskView = maskViewMode
            _pendingFrame.value = PendingFrame(
                state = s,
                showOriginal = _showOriginal.value,
                uvMatrix = uv,
                masks = allMaskBmps,
                maskUniforms = enabled,
                maskDecon = allDeconBmps,
                retouch = retouchBmp,
                curvesRev = curvesRev,
                masksRev = masksRev,
                retouchRev = spotsHash.toLong(),
                clipMode = _clipMode.value,
                overlayMaskIndex = overlayMaskIndex,
                maskViewMode = maskViewMode,
                overlayAlpha = container.settings.maskOverlayAlpha.value,
                timeSec = (System.currentTimeMillis() % 100000) / 1000f,
                frameId = ++frameId,
                assetsToken = assetsToken,
                splitX = _splitX.value ?: -2f,
            )
        }
    }

    /**
     * Called by the GL renderer with the raw framebuffer bytes (RGBA).
     * Sampling happens off the GL thread with a stride, so this stays cheap.
     */
    fun onFrameBytes(buf: java.nio.ByteBuffer, w: Int, h: Int) {
        // A successful readback revives the scopes (see noteReadbackFailure).
        readbackFailures = 0
        if (!_scopesLive.value) _scopesLive.value = true
        // copy the sampled bytes; the GL thread reuses its buffer
        val stepPx = maxOf(1, (w * h) / 32768)
        val count = (w * h + stepPx - 1) / stepPx
        val sample = ByteArray(count * 3)
        var n = 0
        var i = 0
        var lumaSum = 0L
        while (i < w * h && n < count) {
            val o = i * 4
            val r = buf.get(o).toInt() and 0xFF
            val g = buf.get(o + 1).toInt() and 0xFF
            val b = buf.get(o + 2).toInt() and 0xFF
            sample[n * 3] = r.toByte()
            sample[n * 3 + 1] = g.toByte()
            sample[n * 3 + 2] = b.toByte()
            lumaSum += r + g + b
            n++; i += stepPx
        }
        // Black-frame watchdog: mean frame luma from the same samples (free).
        // Throttled upstream to ~1 readback per 450ms, so 4 consecutive black
        // reads ≈ 2s of a dead preview before tripping.
        if (n > 0) checkPreviewWatchdog(lumaSum / (n * 3f * 255f))
        // Waveform: per-column luma envelope from the same buffer (cheap:
        // <= 16k absolute reads, buffer position untouched).
        _waveform.value = waveformFromRgba(buf, w, h)
        // Vectorscope: chroma distribution from the same buffer (cheap:
        // <= 24.6k strided reads, buffer position untouched).
        _vectorscope.value = vectorscopeFromRgba(buf, w, h)
        // RGB parade: per-channel column envelopes from the same buffer
        // (cheap: <= 49k absolute reads, buffer position untouched).
        _rgbParade.value = rgbParadeFromRgba(buf, w, h)
        guardLaunch(Dispatchers.Default) {
            _histogram.value = computeHistogram(sample, n)
        }
    }

    // ---------------------------------------------------------------
    // Black-frame watchdog (P0, 2026-10-17): the editor preview rendered
    // fully black on the user's vivo V60 — black histogram too, panels all
    // working, no "GPU preview unavailable" fallback. The GPU root cause is
    // not reproducible from here, so instead of another blind guess the
    // watchdog converts ANY black-frame failure into a working software
    // preview plus a diagnostic bundle, rather than a dead black screen.
    // ---------------------------------------------------------------

    /** Non-null once the watchdog trips; the UI then shows the software fallback. */
    private val _previewWatchdog = MutableStateFlow<String?>(null)
    val previewWatchdog: StateFlow<String?> = _previewWatchdog.asStateFlow()

    /** Last overlay/mask-view mode emitted by requestRender (see stash above). */
    private var lastOverlayIdx = -1
    private var lastMaskView = 0
    private var blackFrameStreak = 0
    /** Consecutive black frames while the software preview bitmap is null
     *  (the blind-spot path above); shares the trip line with [blackFrameStreak]. */
    private var nullPreviewStreak = 0
    private var lastWatchdogBmp: Bitmap? = null
    /** Mean luma of the source photo; -1 = not measured yet for this bitmap. */
    private var lastSourceLuma = -1f

    /**
     * Runs on the GL thread from [onFrameBytes] (~1 call per 450ms).
     * Trips after 4 consecutive near-black frames while the source photo is
     * bright and no deliberate blackout is dialed in. False-positive gates:
     * night/dark sources (source luma), exposure <= -3 EV (deliberate
     * blackout), mask overlay / B&W mask-check view (they repaint the frame
     * by design). Threshold margins: at -3 EV a 0.15-luma source still
     * renders ~0.019, comfortably above the 0.012 trip line.
     */
    private fun checkPreviewWatchdog(frameMean: Float) {
        if (_previewWatchdog.value != null) return // already tripped; retry clears
        val bmp = _preview.value
        if (bmp == null) {
            // Blind-spot close (P0 recurrence, 2026-10-18): this used to
            // silently reset the streak and return, so a photo-open editor
            // whose software bitmap never materialized showed a dead black
            // screen with no watchdog UI and no diagnostic. A loaded photo
            // with no preview bitmap across consecutive black frames now
            // trips the watchdog with its own diagnostic — the user gets
            // the fallback UI and Retry instead of silence. Loading and
            // error states stay silent: the spinner or the error banner
            // already explains the screen.
            if (!WatchdogPolicy.nullPreviewSuspicious(
                    photoOpen = _libraryImage.value != null,
                    loading = _loading.value,
                    error = _error.value != null,
                )
            ) {
                nullPreviewStreak = 0
                blackFrameStreak = 0
                return
            }
            if (WatchdogPolicy.isBlackFrame(frameMean)) nullPreviewStreak++
            else nullPreviewStreak = 0
            blackFrameStreak = 0
            if (WatchdogPolicy.shouldTrip(nullPreviewStreak)) {
                val diag = WatchdogPolicy.nullPreviewDiagnostic(frameMean)
                android.util.Log.e("PreviewWatchdog", "TRIPPED (null preview): $diag")
                _previewWatchdog.value = diag
                nullPreviewStreak = 0
            }
            return
        }
        nullPreviewStreak = 0
        val s = _state.value
        // Trip gates live in WatchdogPolicy (pure, check-51 tested); this
        // method only owns the streak/bitmap state.
        if (WatchdogPolicy.suppressed(lastOverlayIdx, lastMaskView, s.light.exposure)) {
            blackFrameStreak = 0
            return
        }
        if (bmp !== lastWatchdogBmp) {
            lastWatchdogBmp = bmp
            lastSourceLuma = -1f
            guardLaunch(Dispatchers.Default) {
                lastSourceLuma = ImageMath.meanLuma(bmp)
            }
        }
        val srcLuma = lastSourceLuma
        if (srcLuma < 0f || WatchdogPolicy.isDarkSource(srcLuma)) {
            // Source not measured yet, or the photo itself is dark — a
            // near-black frame is legitimate either way.
            blackFrameStreak = 0
            return
        }
        if (WatchdogPolicy.isBlackFrame(frameMean)) blackFrameStreak++
        else blackFrameStreak = 0
        if (WatchdogPolicy.shouldTrip(blackFrameStreak)) {
            val ai = s.aiRetouch
            val diag = WatchdogPolicy.diagnostic(
                frameMeanLuma = frameMean, sourceLuma = srcLuma,
                exposureEv = s.light.exposure, aiEnabled = ai.enabled,
                aiStrength = ai.strength, overlayIdx = lastOverlayIdx,
                maskView = lastMaskView,
            )
            android.util.Log.e("PreviewWatchdog", "TRIPPED: $diag")
            _previewWatchdog.value = diag
            blackFrameStreak = 0
        }
    }

    /**
     * Clears a watchdog trip and re-establishes GPU assets for a fresh
     * preview attempt. If the GPU is still broken the watchdog trips again
     * after ~2s — a persistent failure keeps the working software fallback,
     * never a dead screen.
     */
    fun retryGpuPreview() {
        _previewWatchdog.value = null
        blackFrameStreak = 0
        nullPreviewStreak = 0
        lastWatchdogBmp = null
        lastSourceLuma = -1f
        invalidateGpuAssets()
        requestRender()
    }

    private fun computeHistogram(sample: ByteArray, n: Int): Histogram {
        val bins = Histogram.BINS
        val r = IntArray(bins); val g = IntArray(bins); val b = IntArray(bins); val l = IntArray(bins)
        var clipS = 0; var clipH = 0
        for (i in 0 until n) {
            val rr = sample[i * 3].toInt() and 0xFF
            val gg = sample[i * 3 + 1].toInt() and 0xFF
            val bb = sample[i * 3 + 2].toInt() and 0xFF
            r[rr * bins / 256]++; g[gg * bins / 256]++; b[bb * bins / 256]++
            val lum = (0.2126f * rr + 0.7152f * gg + 0.0722f * bb).toInt().coerceIn(0, 255)
            l[lum * bins / 256]++
            if (lum <= 3) clipS++
            if (lum >= 252) clipH++
        }
        return Histogram(r, g, b, l,
            if (n > 0) clipS.toFloat() / n else 0f,
            if (n > 0) clipH.toFloat() / n else 0f)
    }

    // ------------------------------------------------------------------
    // Masks
    // ------------------------------------------------------------------

    fun addMask(type: MaskType, name: String): String {
        val id = UUID.randomUUID().toString()
        val comp = MaskComponent(
            id = UUID.randomUUID().toString(),
            type = type,
            shape = defaultShape(type),
        )
        update("Add mask") { s ->
            s.copy(masks = s.masks + Mask(id = id, name = name, components = listOf(comp),
                revision = System.nanoTime()))
        }
        _selectedMaskId.value = id
        return id
    }

    private fun defaultShape(type: MaskType): List<Float> = when (type) {
        MaskType.LINEAR_GRADIENT -> listOf(0.5f, 0.05f, 0.5f, 0.95f)
        MaskType.RADIAL_GRADIENT -> listOf(0.5f, 0.5f, 0.35f, 0.35f, 0f, 0.5f)
        else -> emptyList()
    }

    fun selectMask(id: String?) {
        _selectedMaskId.value = id
        requestRender() // overlay highlight follows selection
    }

    fun selectedMask(): Mask? = _state.value.masks.find { it.id == _selectedMaskId.value }

    fun updateMask(id: String, label: String = "Edit mask", transform: (Mask) -> Mask) {
        update(label) { s ->
            s.copy(masks = s.masks.map { m ->
                if (m.id == id) transform(m).copy(revision = System.nanoTime()) else m
            })
        }
    }

    fun deleteMask(id: String) {
        update("Delete mask") { s -> s.copy(masks = s.masks.filter { it.id != id }) }
        if (_selectedMaskId.value == id) _selectedMaskId.value = null
    }

    fun duplicateMask(id: String) {
        update("Duplicate mask") { s ->
            val src = s.masks.find { it.id == id } ?: return@update s
            val copy = src.copy(id = UUID.randomUUID().toString(),
                name = src.name + " copy", revision = System.nanoTime())
            s.copy(masks = s.masks + copy)
        }
    }

    fun toggleMask(id: String) {
        update("Toggle mask") { s ->
            s.copy(masks = s.masks.map {
                if (it.id == id) it.copy(enabled = !it.enabled, revision = System.nanoTime()) else it
            })
        }
    }

    /** Mask view mode: 0 = off, 1 = red overlay, 2 = black & white.
     *  Changing it re-renders immediately so it works while painting. */
    val maskViewMode: StateFlow<Int> = container.settings.maskViewMode

    fun setMaskViewMode(mode: Int) {
        container.settings.setMaskViewMode(mode)
        requestRender()
    }

    /** Mask overlay opacity (red/B&W view), persisted. Changing it
     *  re-renders immediately so it works while painting. */
    val maskOverlayAlpha: StateFlow<Float> = container.settings.maskOverlayAlpha

    fun setMaskOverlayAlpha(alpha: Float) {
        container.settings.setMaskOverlayAlpha(alpha)
        requestRender()
    }

    /** Crop overlay grid style (0 = thirds … 3 = golden ratio). Drawn by
     *  the Compose overlay canvas, so no GL re-render is needed on change. */
    val cropOverlayStyle: StateFlow<Int> = container.settings.cropOverlayStyle

    fun setCropOverlayStyle(style: Int) {
        container.settings.setCropOverlayStyle(style)
    }

    /** Pin markers on every retouch spot — a Compose overlay (not GL), so no
     *  re-render is needed when toggling. Makes the "Erase spot" mode
     *  usable: spots are visible targets to tap instead of blind guesses. */
    val showRetouchSpots: StateFlow<Boolean> = container.settings.showRetouchSpots

    fun toggleRetouchSpots() {
        container.settings.setShowRetouchSpots(!container.settings.showRetouchSpots.value)
    }

    // ------------------------------------------------------------------
    // Incremental mask-brush painting.
    //
    // Painting a mask brush appends one 2-point stroke per move segment;
    // re-stamping every stroke of the gesture on every segment is O(n^2)
    // over a long stroke and visibly lags. Instead each new stroke is
    // painted ONCE into a persistent accumulator ([paintAcc]), and the
    // render path reuses it — but ONLY when it provably equals a full
    // re-paint: same mask + component, same source bitmap, stroke count
    // matches, and the tail stroke is the identical object (strokes are
    // append-only, so any undo/redo/replace changes the count or the tail
    // reference). Any doubt → null → the exact old full-rasterize path,
    // so pixels are never wrong, only occasionally slower for one frame.
    // Guarded by [paintLock]: painted on Main (addBrushStroke), read on
    // Default (requestRender); the render path copies the array under the
    // lock so a render never blocks the painter.
    // ------------------------------------------------------------------
    private val paintLock = Any()
    private var paintAcc: PaintAcc? = null

    private class PaintAcc(
        val maskId: String,
        val compId: String,
        val acc: FloatArray,
        var strokeCount: Int,
        var lastStroke: BrushStroke?,
        val src: Bitmap?,
        var srcLum: FloatArray?,
    )

    /** Luminance field for auto-mask strokes, built once per accumulator. */
    private fun PaintAcc.lumFor(stroke: BrushStroke): FloatArray? {
        if (!stroke.autoMask) return null
        var lum = srcLum
        if (lum == null) {
            lum = src?.let { buildMaskLuminance(it) }
            srcLum = lum
        }
        return lum
    }

    /**
     * Returns a copy of the incremental paint accumulator for [m]'s active
     * brush component, or null when it can't be proven equal to a full
     * re-paint (the caller then uses the exact old rasterize path).
     */
    private fun brushAccFor(m: Mask): MaskEngine.BrushAcc? {
        val bmp = _preview.value ?: return null
        synchronized(paintLock) {
            val cur = paintAcc ?: return null
            if (cur.maskId != m.id || cur.src !== bmp) return null
            val comp = m.components.find { it.id == cur.compId } ?: return null
            if (comp.type != MaskType.BRUSH) return null
            if (comp.strokes.size != cur.strokeCount) return null
            if (cur.strokeCount > 0 &&
                comp.strokes[cur.strokeCount - 1] !== cur.lastStroke) return null
            if (cur.acc.size != MaskEngine.MAX_RASTER * MaskEngine.MAX_RASTER) return null
            return MaskEngine.BrushAcc(cur.compId, cur.acc.copyOf())
        }
    }

    fun addBrushStroke(maskId: String, compId: String, stroke: BrushStroke) {
        update(gesture = true) { s ->
            s.copy(masks = s.masks.map { m ->
                if (m.id != maskId) return@map m
                m.copy(
                    components = m.components.map { c ->
                        if (c.id != compId) c
                        else c.copy(strokes = c.strokes + stroke,
                            revision = System.nanoTime())
                    },
                    revision = System.nanoTime(),
                )
            })
        }
        // Incremental: paint just the new stroke into the persistent
        // accumulator — O(1) stamp work per segment instead of re-stamping
        // the whole gesture. Falls back to a one-time O(n) rebuild whenever
        // the accumulator can't prove it matches (undo mid-gesture, etc.).
        synchronized(paintLock) {
            val bmp = _preview.value
            val comp = _state.value.masks.find { it.id == maskId }
                ?.components?.find { it.id == compId }
            val cur = paintAcc
            if (cur != null && comp != null && comp.type == MaskType.BRUSH &&
                cur.maskId == maskId && cur.compId == compId && cur.src === bmp &&
                cur.strokeCount == comp.strokes.size - 1 &&
                (cur.strokeCount == 0 ||
                    comp.strokes[cur.strokeCount - 1] === cur.lastStroke)
            ) {
                MaskEngine.paintStroke(cur.acc, MaskEngine.MAX_RASTER,
                    MaskEngine.MAX_RASTER, stroke, cur.lumFor(stroke))
                cur.strokeCount++
                cur.lastStroke = stroke
            } else if (comp != null && comp.type == MaskType.BRUSH && bmp != null) {
                val w = MaskEngine.MAX_RASTER
                val acc = FloatArray(w * w)
                val lum = if (comp.strokes.any { it.autoMask })
                    buildMaskLuminance(bmp) else null
                MaskEngine.paintBrushStrokes(acc, w, w, comp, lum)
                paintAcc = PaintAcc(maskId, compId, acc, comp.strokes.size,
                    comp.strokes.lastOrNull(), bmp, lum)
            } else {
                paintAcc = null
            }
        }
    }

    fun addBrushComponent(maskId: String, erase: Boolean): String {
        val compId = UUID.randomUUID().toString()
        update("Add brush") { s ->
            s.copy(masks = s.masks.map { m ->
                if (m.id != maskId) return@map m
                m.copy(
                    components = m.components + MaskComponent(
                        id = compId, type = MaskType.BRUSH,
                        op = if (erase) MaskOp.SUBTRACT else MaskOp.ADD),
                    revision = System.nanoTime())
            })
        }
        return compId
    }

    fun invertMask(id: String) {
        update("Invert mask") { s ->
            s.copy(masks = s.masks.map {
                if (it.id == id) it.copy(inverted = !it.inverted, revision = System.nanoTime()) else it
            })
        }
    }

    /**
     * Appends a non-brush component (gradient) to an existing mask with the
     * given combine op (ADD/SUBTRACT/INTERSECT) — Lightroom-style mask
     * combining. Returns the new component id, or null when the mask is gone.
     */
    fun addComponentToMask(maskId: String, type: MaskType, op: MaskOp): String? {
        if (_state.value.masks.none { it.id == maskId }) return null
        val compId = UUID.randomUUID().toString()
        val label = when (op) {
            MaskOp.SUBTRACT -> "Subtract from mask"
            MaskOp.INTERSECT -> "Intersect with mask"
            MaskOp.ADD -> "Add to mask"
        }
        update(label) { s ->
            s.copy(masks = s.masks.map { m ->
                if (m.id != maskId) m else m.copy(
                    components = m.components + MaskComponent(
                        id = compId, type = type, op = op, shape = defaultShape(type)),
                    revision = System.nanoTime())
            })
        }
        return compId
    }

    /**
     * Persists an AI-mask working raster and releases the caller's copy,
     * even if the persist is cancelled mid-flight.
     *
     * [AiMaskStore.save] persists its own PNG copy and never retains the
     * passed bitmap, so the working raster is always the caller's to
     * release — one preview-sized bitmap per mask op. The old inline
     * pattern (save, then recycle on the next line) skipped the recycle
     * when the coroutine was cancelled inside the IO dispatch — and the
     * debounced refresh*Raster paths cancel their predecessor on every
     * slider tick, so that window was hit routinely, leaking the raster
     * each time. The finally here survives cancellation: the bitmap is
     * released and the CancellationException still propagates to the
     * caller's handler, so cancellation semantics are unchanged.
     */
    private suspend fun saveAiRasterAndRelease(key: String, raster: Bitmap) {
        try {
            withContext(Dispatchers.IO) { container.aiMasks.save(key, raster) }
        } finally {
            // save() never retains the bitmap — always correct, including
            // on the throw path (save() itself swallows IO/OOM failures).
            if (!raster.isRecycled) raster.recycle()
        }
    }

    /**
     * Like [runAiMask] but appends the computed raster (sky / luminance) as a
     * SUBTRACT/INTERSECT component of an existing mask instead of creating a
     * new one.
     */
    fun runAiComponent(maskId: String, kind: MaskType, op: MaskOp, label: String) {
        val bmp = _preview.value ?: return
        if (_state.value.masks.none { it.id == maskId }) return
        guardLaunch {
            _aiUi.value = AiUiState.Working(label)
            try {
                val raster: Bitmap = when (kind) {
                    MaskType.SKY_SELECT -> ImageMath.selectSkyAuto(bmp)
                    MaskType.LUMINANCE_RANGE ->
                        ImageMath.luminanceRangeBitmap(bmp, 0f, 1f, 0.5f)
                    MaskType.DEPTH_RANGE ->
                        ImageMath.depthRangeBitmap(bmp, 0f, 1f, 0.5f)
                    else -> throw IllegalArgumentException("Not a raster AI mask type")
                }
                val key = "ai_${UUID.randomUUID()}"
                saveAiRasterAndRelease(key, raster)
                val historyLabel = when (op) {
                    MaskOp.SUBTRACT -> "Subtract from mask"
                    MaskOp.INTERSECT -> "Intersect with mask"
                    MaskOp.ADD -> "Add to mask"
                }
                updateMask(maskId, historyLabel) { m ->
                    m.copy(components = m.components + MaskComponent(
                        id = UUID.randomUUID().toString(), type = kind, op = op,
                        aiKey = key))
                }
                _aiUi.value = AiUiState.Idle
            } catch (e: Throwable) {
                if (e is CancellationException) throw e // don't swallow coroutine cancellation
                _aiUi.value = AiUiState.Error(
                    e.message ?: "Could not build the mask — try again.")
            }
        }
    }

    // ------------------------------------------------------------------
    // AI masks
    // ------------------------------------------------------------------

    fun runAiMask(kind: MaskType, label: String, errorHint: String? = null) {
        val bmp = _preview.value ?: return
        guardLaunch {
            _aiUi.value = AiUiState.Working(label)
            try {
                val raster: Bitmap = when (kind) {
                    MaskType.SKY_SELECT -> ImageMath.selectSkyAuto(bmp)
                    MaskType.LUMINANCE_RANGE ->
                        ImageMath.luminanceRangeBitmap(bmp, 0f, 1f, 0.5f)
                    MaskType.DEPTH_RANGE ->
                        ImageMath.depthRangeBitmap(bmp, 0f, 1f, 0.5f)
                    MaskType.AI_SUBJECT, MaskType.AI_BACKGROUND ->
                        AutoMask.subjectMask(bmp) ?: throw IllegalStateException(
                            "No clear subject found — try the brush or " +
                                "gradient mask instead.")
                    else -> throw IllegalArgumentException("Not an AI mask type")
                }
                val key = "ai_${UUID.randomUUID()}"
                saveAiRasterAndRelease(key, raster)
                val maskId = addMask(kind, label)
                // "Background AI" selects everything that is NOT the subject:
                // the same stored raster, inverted shader-side (the mask
                // pipeline already supports inversion — see the Auto Mask
                // "Background auto" plan below).
                val inverted = kind == MaskType.AI_BACKGROUND
                updateMask(maskId, "AI mask") { m ->
                    m.copy(
                        components = listOf(MaskComponent(
                            id = UUID.randomUUID().toString(), type = kind, aiKey = key)),
                        inverted = inverted,
                    )
                }
                _aiUi.value = AiUiState.Idle
            } catch (e: Throwable) {
                if (e is CancellationException) throw e // don't swallow coroutine cancellation
                // The failure messages already point at manual masks, so back
                // the card with a one-tap "Paint a mask" recovery instead of
                // a Dismiss-only dead end (see MaskPanel's Error branch).
                _aiUi.value = AiUiState.Error(
                    e.message ?: errorHint ?:
                        "AI segmentation unavailable — use brush or gradient masks instead.",
                    recoverLabel = "Paint a mask")
            }
        }
    }

    private var lumaRefreshJob: Job? = null

    /**
     * Updates a luminance-range component's band and re-renders its stored
     * raster (debounced during slider drags so the UI stays smooth; call
     * [refreshLuminanceRaster] on gesture end for the immediate final pass).
     * State update is cheap and synchronous; the bitmap rebuild runs on
     * Dispatchers.Default from the unedited source bitmap.
     */
    fun updateLuminanceRange(
        maskId: String, compId: String, lo: Float, hi: Float, feather: Float,
    ) {
        val cLo = lo.coerceIn(0f, 1f); val cHi = hi.coerceIn(0f, 1f)
        val (flo, fhi) = if (cLo <= cHi) cLo to cHi else cHi to cLo
        update(gesture = true) { s ->
            s.copy(masks = s.masks.map { m ->
                if (m.id != maskId) m else m.copy(
                    components = m.components.map { c ->
                        if (c.id != compId) c
                        else c.copy(lumaLo = flo, lumaHi = fhi,
                            lumaFeather = feather.coerceIn(0f, 1f),
                            revision = System.nanoTime())
                    },
                    revision = System.nanoTime())
            })
        }
        lumaRefreshJob?.cancel()
        lumaRefreshJob = guardLaunch {
            delay(90)
            refreshLuminanceRaster(maskId, compId)
        }
    }

    /**
     * Adjusts a RADIAL_GRADIENT component's edge softness (shape[5], 0..1).
     * The radial raster is analytic, so no bitmap rebuild is needed — the
     * revision bump invalidates the mask cache and the preview re-renders
     * live. Call between beginGesture()/endGesture() for one undo entry.
     */
    fun updateGradientFeather(maskId: String, compId: String, feather: Float) {
        val f = feather.coerceIn(0f, 1f)
        update(gesture = true) { s ->
            s.copy(masks = s.masks.map { m ->
                if (m.id != maskId) m else m.copy(
                    components = m.components.map { c ->
                        if (c.id != compId || c.type != MaskType.RADIAL_GRADIENT) c
                        else if (c.shape.size < 6) c
                        else c.copy(
                            shape = c.shape.toMutableList().also { it[5] = f },
                            revision = System.nanoTime())
                    },
                    revision = System.nanoTime())
            })
        }
    }

    /**
     * Changes how one component combines into its mask (Lightroom parity —
     * previously the op was fixed at creation time). The first component is
     * always the base (ADD): a SUBTRACT/INTERSECT base would composite to an
     * empty mask (see MaskEngine), so that request is coerced to ADD.
     * Undoable ("Add to mask"/"Subtract from mask"/"Intersect with mask")
     * like every other mask edit.
     */
    fun setComponentOp(maskId: String, compId: String, op: MaskOp) {
        val mask = _state.value.masks.find { it.id == maskId } ?: return
        val index = mask.components.indexOfFirst { it.id == compId }
        if (index < 0) return
        val effective = if (index == 0) MaskOp.ADD else op
        if (mask.components[index].op == effective) return
        val label = when (effective) {
            MaskOp.SUBTRACT -> "Subtract from mask"
            MaskOp.INTERSECT -> "Intersect with mask"
            MaskOp.ADD -> "Add to mask"
        }
        update(label) { s ->
            s.copy(masks = s.masks.map { m ->
                if (m.id != maskId) m else m.copy(
                    components = m.components.map { c ->
                        if (c.id != compId) c
                        else c.copy(op = effective, revision = System.nanoTime())
                    },
                    revision = System.nanoTime())
            })
        }
    }

    /**
     * Deletes a single component from a mask (Lightroom parity — previously
     * only whole masks could be deleted). Never deletes the last component
     * (delete the mask itself instead). When the base component is removed,
     * the new first component is coerced to ADD so the mask can't collapse
     * to an empty composite. Returns true when something was removed.
     */
    fun deleteComponent(maskId: String, compId: String): Boolean {
        val mask = _state.value.masks.find { it.id == maskId } ?: return false
        if (mask.components.none { it.id == compId }) return false
        if (mask.components.size <= 1) return false
        update("Delete mask component") { s ->
            s.copy(masks = s.masks.map { m ->
                if (m.id != maskId) m else {
                    val remaining = m.components.filterNot { it.id == compId }
                    val fixed = remaining.mapIndexed { i, c ->
                        if (i == 0 && c.op != MaskOp.ADD)
                            c.copy(op = MaskOp.ADD, revision = System.nanoTime())
                        else c
                    }
                    m.copy(components = fixed, revision = System.nanoTime())
                }
            })
        }
        return true
    }

    /**
     * Resizes/repositions a RADIAL_GRADIENT ellipse during the on-canvas
     * drag. Preserves the component's rotation and feather — only the
     * center + radii move — so adjusting the ellipse never snaps a tuned
     * feather back to the default. Gesture-friendly like its siblings.
     */
    fun updateRadialGeometry(
        maskId: String, compId: String, cx: Float, cy: Float, rx: Float, ry: Float,
    ) {
        update(gesture = true) { s ->
            s.copy(masks = s.masks.map { m ->
                if (m.id != maskId) m else m.copy(
                    components = m.components.map { c ->
                        if (c.id != compId || c.type != MaskType.RADIAL_GRADIENT) c
                        else if (c.shape.size < 6) c
                        else c.copy(
                            shape = listOf(cx, cy, rx, ry, c.shape[4], c.shape[5]),
                            revision = System.nanoTime())
                    },
                    revision = System.nanoTime())
            })
        }
    }

    /** Rebuilds a luminance-range component's stored raster from its current
     *  band settings and re-renders the preview. Safe to call any time. */
    fun refreshLuminanceRaster(maskId: String, compId: String) {
        lumaRefreshJob?.cancel()
        guardLaunch {
            try {
                val bmp = _preview.value ?: return@guardLaunch
                val comp = _state.value.masks.find { it.id == maskId }
                    ?.components?.find { it.id == compId } ?: return@guardLaunch
                val key = comp.aiKey ?: return@guardLaunch
                val raster = withContext(Dispatchers.Default) {
                    ImageMath.luminanceRangeBitmap(
                        bmp, comp.lumaLo, comp.lumaHi, comp.lumaFeather)
                }
                saveAiRasterAndRelease(key, raster)
                requestRender()
            } catch (t: Throwable) {
                android.util.Log.w("EditorVM", "luminance refresh failed", t)
            }
        }
    }

    private var depthRefreshJob: Job? = null

    /**
     * Updates a depth-range component's band and re-renders its stored
     * raster (debounced during slider drags so the UI stays smooth; call
     * [refreshDepthRaster] on gesture end for the immediate final pass).
     * State update is cheap and synchronous; the bitmap rebuild runs on
     * Dispatchers.Default from the unedited source bitmap.
     */
    fun updateDepthRange(
        maskId: String, compId: String, lo: Float, hi: Float, feather: Float,
    ) {
        val cLo = lo.coerceIn(0f, 1f); val cHi = hi.coerceIn(0f, 1f)
        val (flo, fhi) = if (cLo <= cHi) cLo to cHi else cHi to cLo
        update(gesture = true) { s ->
            s.copy(masks = s.masks.map { m ->
                if (m.id != maskId) m else m.copy(
                    components = m.components.map { c ->
                        if (c.id != compId) c
                        else c.copy(depthLo = flo, depthHi = fhi,
                            depthFeather = feather.coerceIn(0f, 1f),
                            revision = System.nanoTime())
                    },
                    revision = System.nanoTime())
            })
        }
        depthRefreshJob?.cancel()
        depthRefreshJob = guardLaunch {
            delay(90)
            refreshDepthRaster(maskId, compId)
        }
    }

    /** Rebuilds a depth-range component's stored raster from its current
     *  band settings and re-renders the preview. Safe to call any time. */
    fun refreshDepthRaster(maskId: String, compId: String) {
        depthRefreshJob?.cancel()
        guardLaunch {
            try {
                val bmp = _preview.value ?: return@guardLaunch
                val comp = _state.value.masks.find { it.id == maskId }
                    ?.components?.find { it.id == compId } ?: return@guardLaunch
                val key = comp.aiKey ?: return@guardLaunch
                val raster = withContext(Dispatchers.Default) {
                    ImageMath.depthRangeBitmap(
                        bmp, comp.depthLo, comp.depthHi, comp.depthFeather)
                }
                saveAiRasterAndRelease(key, raster)
                requestRender()
            } catch (t: Throwable) {
                android.util.Log.w("EditorVM", "depth refresh failed", t)
            }
        }
    }



    fun clearAiError() { _aiUi.value = AiUiState.Idle }

    // ------------------------------------------------------------------
    // Color-range masks
    // ------------------------------------------------------------------

    /** Default color-range tolerance/feather for a freshly picked color. */
    private val defaultColorTolerance = 0.45f
    private val defaultColorFeather = 0.5f
    /** Max extra refine swatches on one color-range component (primary + these). */
    private val maxColorSamples = 8

    /**
     * Creates a new COLOR_RANGE mask from a user-picked color (0..1 RGB,
     * e.g. from the eyedropper pick mode). Rasterized from the source bitmap
     * with the default tolerance; the user refines via Tolerance/Feather
     * sliders or repicks the color. Shows Working/Error UI like runAiMask.
     */
    fun addColorRangeMask(r: Float, g: Float, b: Float) {
        val bmp = _preview.value ?: return
        guardLaunch {
            _aiUi.value = AiUiState.Working("Color range")
            try {
                val raster = ImageMath.colorRangeBitmap(
                    bmp, r, g, b, defaultColorTolerance, defaultColorFeather)
                val key = "ai_${UUID.randomUUID()}"
                saveAiRasterAndRelease(key, raster)
                val maskId = addMask(MaskType.COLOR_RANGE, "Color range")
                updateMask(maskId, "Color range mask") { m ->
                    m.copy(components = listOf(MaskComponent(
                        id = UUID.randomUUID().toString(),
                        type = MaskType.COLOR_RANGE, aiKey = key,
                        colorR = r.coerceIn(0f, 1f),
                        colorG = g.coerceIn(0f, 1f),
                        colorB = b.coerceIn(0f, 1f),
                        colorTolerance = defaultColorTolerance,
                        colorFeather = defaultColorFeather)))
                }
                _aiUi.value = AiUiState.Idle
            } catch (e: Throwable) {
                if (e is CancellationException) throw e // don't swallow coroutine cancellation
                _aiUi.value = AiUiState.Error(
                    "Could not build the color mask — try again.")
            }
        }
    }

    /**
     * Like [addColorRangeMask] but appends the sampled color range as a
     * SUBTRACT/INTERSECT component of an existing mask instead of creating a
     * new one.
     */
    fun addColorRangeComponent(maskId: String, op: MaskOp, r: Float, g: Float, b: Float) {
        val bmp = _preview.value ?: return
        if (_state.value.masks.none { it.id == maskId }) return
        guardLaunch {
            _aiUi.value = AiUiState.Working("Color range")
            try {
                val raster = ImageMath.colorRangeBitmap(
                    bmp, r, g, b, defaultColorTolerance, defaultColorFeather)
                val key = "ai_${UUID.randomUUID()}"
                saveAiRasterAndRelease(key, raster)
                val historyLabel = when (op) {
                    MaskOp.SUBTRACT -> "Subtract from mask"
                    MaskOp.INTERSECT -> "Intersect with mask"
                    MaskOp.ADD -> "Add to mask"
                }
                updateMask(maskId, historyLabel) { m ->
                    m.copy(components = m.components + MaskComponent(
                        id = UUID.randomUUID().toString(),
                        type = MaskType.COLOR_RANGE, op = op, aiKey = key,
                        colorR = r.coerceIn(0f, 1f),
                        colorG = g.coerceIn(0f, 1f),
                        colorB = b.coerceIn(0f, 1f),
                        colorTolerance = defaultColorTolerance,
                        colorFeather = defaultColorFeather))
                }
                _aiUi.value = AiUiState.Idle
            } catch (e: Throwable) {
                if (e is CancellationException) throw e // don't swallow coroutine cancellation
                _aiUi.value = AiUiState.Error(
                    "Could not build the color mask — try again.")
            }
        }
    }

    private var colorRefreshJob: Job? = null

    /**
     * Updates a color-range component's target color / tolerance / feather
     * and re-renders its stored raster (debounced during slider drags so the
     * UI stays smooth; call [refreshColorRaster] on gesture end for the
     * immediate final pass). Null fields keep their current values.
     */
    fun updateColorRange(
        maskId: String, compId: String,
        r: Float? = null, g: Float? = null, b: Float? = null,
        tolerance: Float? = null, feather: Float? = null,
    ) {
        update(gesture = true) { s ->
            s.copy(masks = s.masks.map { m ->
                if (m.id != maskId) m else m.copy(
                    components = m.components.map { c ->
                        if (c.id != compId) c
                        else c.copy(
                            colorR = r?.coerceIn(0f, 1f) ?: c.colorR,
                            colorG = g?.coerceIn(0f, 1f) ?: c.colorG,
                            colorB = b?.coerceIn(0f, 1f) ?: c.colorB,
                            colorTolerance = tolerance?.coerceIn(0f, 1f)
                                ?: c.colorTolerance,
                            colorFeather = feather?.coerceIn(0f, 1f)
                                ?: c.colorFeather,
                            revision = System.nanoTime())
                    },
                    revision = System.nanoTime())
            })
        }
        colorRefreshJob?.cancel()
        colorRefreshJob = guardLaunch {
            delay(90)
            refreshColorRaster(maskId, compId)
        }
    }

    /** Rebuilds a color-range component's stored raster from its current
     *  settings and re-renders the preview. Safe to call any time. */
    fun refreshColorRaster(maskId: String, compId: String) {
        colorRefreshJob?.cancel()
        guardLaunch {
            try {
                val bmp = _preview.value ?: return@guardLaunch
                val comp = _state.value.masks.find { it.id == maskId }
                    ?.components?.find { it.id == compId } ?: return@guardLaunch
                val key = comp.aiKey ?: return@guardLaunch
                val raster = withContext(Dispatchers.Default) {
                    ImageMath.colorRangeBitmapSamples(
                        bmp, colorTargetsOf(comp),
                        comp.colorTolerance, comp.colorFeather)
                }
                saveAiRasterAndRelease(key, raster)
                requestRender()
            } catch (t: Throwable) {
                android.util.Log.w("EditorVM", "color range refresh failed", t)
            }
        }
    }

    /** Union target list for a color-range component: primary target first,
     *  then extra refine swatches. Empty extra list = primary only. */
    private fun colorTargetsOf(comp: MaskComponent): List<ColorSample> =
        listOf(ColorSample(comp.colorR, comp.colorG, comp.colorB)) + comp.colorSamples

    /**
     * Adds an extra refine swatch to a color-range component (Lightroom
     * "add color swatch"): the selection becomes the union of the primary
     * target and all swatches. One undo entry per tap. Refreshes the raster
     * immediately (a tap is not a drag, so no debounce needed).
     */
    fun addColorSample(maskId: String, compId: String, r: Float, g: Float, b: Float) {
        update("Add color swatch") { s ->
            s.copy(masks = s.masks.map { m ->
                if (m.id != maskId) m else m.copy(
                    components = m.components.map { c ->
                        if (c.id != compId || c.type != MaskType.COLOR_RANGE) c
                        else {
                            val next = (c.colorSamples + ColorSample(
                                r.coerceIn(0f, 1f), g.coerceIn(0f, 1f),
                                b.coerceIn(0f, 1f))).takeLast(maxColorSamples)
                            c.copy(colorSamples = next, revision = System.nanoTime())
                        }
                    },
                    revision = System.nanoTime())
            })
        }
        refreshColorRaster(maskId, compId)
    }

    /** Removes one refine swatch (by index into [MaskComponent.colorSamples]).
     *  The primary target can never be removed. One undo entry per tap. */
    fun removeColorSample(maskId: String, compId: String, index: Int) {
        update("Remove color swatch") { s ->
            s.copy(masks = s.masks.map { m ->
                if (m.id != maskId) m else m.copy(
                    components = m.components.map { c ->
                        if (c.id != compId || c.type != MaskType.COLOR_RANGE) c
                        else c.copy(
                            colorSamples = c.colorSamples.filterIndexed { i, _ -> i != index },
                            revision = System.nanoTime())
                    },
                    revision = System.nanoTime())
            })
        }
        refreshColorRaster(maskId, compId)
    }

    // ------------------------------------------------------------------
    // Retouch
    // ------------------------------------------------------------------

    fun addRetouchSpot(spot: RetouchSpot) {
        update("Retouch") { s -> s.copy(retouch = s.retouch.copy(spots = s.retouch.spots + spot)) }
    }

    /**
     * Adds a spot as part of an in-progress retouch stroke (drag painting).
     * Uses gesture mode so the whole stroke is ONE undo entry instead of one
     * per spot; the caller must wrap the stroke in beginGesture()/endGesture().
     */
    fun addRetouchSpotStroke(spot: RetouchSpot) {
        update(gesture = true) { s -> s.copy(retouch = s.retouch.copy(spots = s.retouch.spots + spot)) }
    }

    /**
     * Tone-matched automatic heal source for the tap at ([uvX],[uvY]), or null
     * when no trustworthy candidate exists (caller falls back to a fixed
     * offset). Reads the current preview bitmap; any failure degrades to null.
     */
    fun healAutoSource(uvX: Float, uvY: Float, size: Float): Pair<Float, Float>? {
        val bmp = _preview.value ?: return null
        return try {
            RetouchEngine.findHealSource(bmp, uvX, uvY, size)
        } catch (t: Throwable) {
            // OOM on the full-res pixel probe degrades to null so the
            // caller falls back to its fixed source offset.
            if (t is CancellationException) throw t
            null
        }
    }

    fun undoRetouchSpot() {
        update("Undo retouch") { s ->
            s.copy(retouch = s.retouch.copy(spots = s.retouch.spots.dropLast(1)))
        }
    }

    fun clearRetouch() {
        update("Clear retouch") { s -> s.copy(retouch = RetouchState()) }
    }

    /**
     * Removes the retouch spot nearest to (x, y) (normalized image UV) whose
     * hit radius contains the tap. One undo entry; returns true when a spot
     * was actually removed.
     */
    fun removeRetouchSpotNear(x: Float, y: Float): Boolean {
        val spots = _state.value.retouch.spots
        var best = -1
        var bestD = Float.MAX_VALUE
        spots.forEachIndexed { i, s ->
            val d = kotlin.math.hypot(s.x - x, s.y - y)
            val hit = maxOf(s.size * 0.8f, 0.035f)
            if (d <= hit && d < bestD) { best = i; bestD = d }
        }
        if (best < 0) return false
        update("Remove retouch") { st ->
            st.copy(retouch = st.retouch.copy(
                spots = st.retouch.spots.filterIndexed { i, _ -> i != best }))
        }
        return true
    }

    // ------------------------------------------------------------------
    // Export
    // ------------------------------------------------------------------

    fun export(settings: ExportSettings, glHost: GlHost) {
        val img = _libraryImage.value ?: return
        val preview = _preview.value ?: return
        val uri = Uri.parse(img.contentUri)
        _exportUi.value = ExportUiState.Running(0f)
        guardLaunch {
            // export-res masks (1024) + retouch at decode size handled inside.
            // Rasterizing can throw (OOM at high export sizes); fail with a
            // clear message instead of crashing the editor out from under
            // the user. Masks are dropped from a failed export rather than
            // silently degraded — an export must reflect the real edits.
            val exportMasks = try {
                withContext(Dispatchers.Default) {
                    _state.value.masks.filter { it.enabled }.take(GlEngine.MAX_MASKS)
                        .map { m ->
                            // rasterize at higher res by scaling the 512 raster
                            val base = maskCache[m.id]?.second ?: MaskEngine.rasterize(m, container.aiMasks)
                            Bitmap.createScaledBitmap(base, 1024, 1024, true)
                        }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _exportUi.value = ExportUiState.Error(
                    if (t is OutOfMemoryError)
                        "Not enough memory to prepare the export — try a smaller export size or close other apps"
                    else "Could not prepare masks for export (${t.message ?: "error"})"
                )
                return@guardLaunch
            }
            // Hair decontamination textures for the export render. The despill
            // bitmap is low-frequency by construction (inward-filled interior
            // color), so the preview-resolution texture from the live frame
            // path samples faithfully at any export size via linear filtering.
            // A stale revision (export pressed mid-gesture) is rebuilt on the
            // spot from the preview bitmap; rasterizeSafely never throws, so
            // the worst case is a null slot — feature off for that mask in
            // this export, never a crash or a darkened image.
            val exportDecon: List<Bitmap?> = withContext(Dispatchers.Default) {
                _state.value.masks.filter { it.enabled }.take(GlEngine.MAX_MASKS)
                    .map { m ->
                        if (m.decontaminate <= 0f) null
                        else {
                            val cached = deconCache[m.id]
                            if (cached != null && cached.first == m.revision) cached.second
                            else {
                                val raster = maskCache[m.id]?.second
                                val built = if (raster != null) {
                                    rasterizeSafely("decontaminate") {
                                        MaskEngine.despilledBitmap(m, raster, preview)
                                    }
                                } else null
                                if (built != null) deconCache[m.id] = m.revision to built
                                built ?: cached?.second
                            }
                        }
                    }
            }
            try {
                val result = container.exports.export(
                    ExportManager.Job(
                        sourceUri = uri,
                        state = _state.value,
                        settings = settings,
                        previewBitmap = preview,
                        previewMasks = currentMaskBitmaps(),
                        previewDecon = currentDeconBitmaps(),
                        previewRetouch = retouchCache?.second,
                        exportMasks = exportMasks,
                        exportDecon = exportDecon,
                        retouchSpots = _state.value.retouch.spots,
                        glEngine = glEngineRef ?: return@guardLaunch run {
                            _exportUi.value = ExportUiState.Error("Renderer not ready")
                        },
                        glHost = glHost,
                    )
                ) { p -> _exportUi.value = ExportUiState.Running(p) }
                result
                    .onSuccess { _exportUi.value = ExportUiState.Done(it, settings) }
                    .onFailure {
                        _exportUi.value = ExportUiState.Error(
                            it.message ?: "Export failed")
                    }
            } finally {
                // The export-resolution masks are job-local scratch (fresh
                // 1024px copies; the cache bitmaps they were scaled from are
                // untouched). The engine re-uploaded the preview assets
                // inside the GL task, so release the native memory now
                // instead of waiting for GC — batch export does the same
                // per photo. Decon bitmaps are NOT recycled: rebuilt ones
                // were stored into deconCache above and stay live.
                exportMasks.forEach {
                    runCatching { if (!it.isRecycled) it.recycle() }
                }
            }
        }
    }

    private fun currentMaskBitmaps(): List<Bitmap> =
        _state.value.masks.filter { it.enabled }.take(GlEngine.MAX_MASKS)
            .mapNotNull { maskCache[it.id]?.second }

    /**
     * Hair decontamination bitmaps aligned with [currentMaskBitmaps]: the
     * same drop-missing-raster filter keeps the two lists in lockstep, and
     * null per slot means "feature off" (GlEngine binds the placeholder and
     * forces that slot's uDecon to 0).
     */
    private fun currentDeconBitmaps(): List<Bitmap?> =
        _state.value.masks.filter { it.enabled }.take(GlEngine.MAX_MASKS)
            .filter { maskCache[it.id]?.second != null }
            .map { deconCache[it.id]?.second }

    /** Set by the GL view when the engine is ready. */
    var glEngineRef: GlEngine? = null

    /** Shared GL host for export (set by the editor screen). */
    var sharedGlHost: GlHost? = null

    fun resetExportUi() { _exportUi.value = ExportUiState.Idle }

    // ---- AI Photo Assistant (Gemini) ----

    private val aiKeyStore by lazy { AiKeyStore(container.context) }
    private val geminiClient by lazy { GeminiClient() }

    private val _aiAssistant = MutableStateFlow(
        AiAssistantUiState(hasApiKey = aiKeyStore.hasKey()),
    )
    val aiAssistant = _aiAssistant.asStateFlow()

    fun saveAiApiKey(key: String) {
        aiKeyStore.setGeminiKey(key)
        _aiAssistant.update { it.copy(hasApiKey = true, error = null) }
    }

    fun clearAiApiKey() {
        aiKeyStore.clearGeminiKey()
        _aiAssistant.update { it.copy(hasApiKey = false, error = null, pendingSuggestions = null) }
    }

    /** Re-checks whether an API key is stored (call when returning from Settings). */
    fun refreshAiKeyStatus() {
        val has = aiKeyStore.hasKey()
        if (_aiAssistant.value.hasApiKey != has) {
            _aiAssistant.update { it.copy(hasApiKey = has) }
        }
    }

    /**
     * Tests the API key with a minimal text-only request.
     * Calls [onResult] with (success, message).
     */
    fun testAiConnection(onResult: (Boolean, String) -> Unit) {
        val key = aiKeyStore.getGeminiKey()
        if (key == null) {
            onResult(false, "No API key saved")
            return
        }
        guardLaunch(Dispatchers.IO) {
            try {
                // Discover which models this key can actually use; fall back to
                // the hardcoded list when discovery fails. 404 = deprecated, try next.
                val models = (geminiClient.resolveModels(key) +
                    com.lumora.photoeditor.ai.GeminiClient.MODELS).distinct()
                var ok = false
                var msg = ""
                var usedModel = ""
                for (model in models) {
                    try {
                        val url = java.net.URL(
                            "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
                        )
                        val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                            requestMethod = "POST"
                            setRequestProperty("Content-Type", "application/json; charset=utf-8")
                            setRequestProperty("x-goog-api-key", key)
                            connectTimeout = 15_000
                            readTimeout = 15_000
                            doOutput = true
                        }
                        val body = """{"contents":[{"parts":[{"text":"Say OK"}]}]}"""
                        java.io.OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use {
                            it.write(body)
                        }
                        val code = conn.responseCode
                        conn.disconnect()
                        if (code in 200..299) {
                            ok = true; usedModel = model
                            break
                        } else if (code == 404) {
                            continue // deprecated model, try next
                        } else {
                            // Friendly plain-language message: the raw
                            // API JSON body gets clipped mid-word on a
                            // phone screen and looks like an app bug.
                            msg = com.lumora.photoeditor.ai.friendlyGeminiHttpError(code)
                            break
                        }
                    } catch (t: Throwable) {
                        // Throwable, not just Exception: an Error on one
                        // model attempt must fall through to the next model,
                        // not abort discovery (the outer catch reports).
                        if (t is CancellationException) throw t
                        msg = "Network error: ${t.message?.take(100)}"
                    }
                }
                withContext(Dispatchers.Main) {
                    if (ok) onResult(true, "Connected! Gemini is ready. ($usedModel)")
                    else onResult(false, msg.ifBlank {
                        com.lumora.photoeditor.ai.friendlyNoGeminiModelFound()
                    })
                }
            } catch (t: Throwable) {
                // Throwable, not just Exception: an Error during discovery
                // must still fire onResult(false) instead of leaving the
                // "Testing…" spinner stuck (scopeGuard would only log it).
                if (t is CancellationException) throw t
                withContext(Dispatchers.Main) {
                    onResult(false, "Network error: ${t.message?.take(100)}")
                }
            }
        }
    }

    fun clearAiAssistantError() {
        _aiAssistant.update { it.copy(error = null) }
    }

    fun dismissAiSuggestions() {
        _aiAssistant.update { it.copy(pendingSuggestions = null) }
    }

    /** Human-readable summary of current settings for the AI prompt. */
    private fun buildSettingsSummary(): String {
        val s = _state.value
        return buildString {
            appendLine("Light: exposure=${fmt(s.light.exposure)}, contrast=${s.light.contrast.toInt()}, " +
                "highlights=${s.light.highlights.toInt()}, shadows=${s.light.shadows.toInt()}, " +
                "whites=${s.light.whites.toInt()}, blacks=${s.light.blacks.toInt()}, " +
                "temp=${s.light.temp.toInt()}, tint=${s.light.tint.toInt()}")
            appendLine("Color: vibrance=${s.color.vibrance.toInt()}, saturation=${s.color.saturation.toInt()}")
            appendLine("Detail: sharpen=${s.detail.sharpening.toInt()}, luminanceNR=${s.detail.luminanceNr.toInt()}")
            appendLine("Optics: vignette=${s.optics.vignetteAmount.toInt()}")
        }.trim()
    }

    private fun fmt(v: Float): String =
        // Locale.US: this summary is fed to the AI assistant as a structured
        // prompt — a comma-decimal device locale must never produce
        // "exposure=+0,35" where the model expects "+0.35".
        (if (v > 0) "+" else "") + "%.2f".format(Locale.US, v)

    /**
     * Downscales the current preview bitmap to max 768px, JPEG-compresses,
     * base64-encodes. Uses _preview directly (already in memory) instead of
     * re-decoding from URI (which can fail on permission/URI issues).
     * Returns null when no image is loaded.
     */
    private fun encodeImageForAi(): String? {
        val bmp = _preview.value ?: return null
        return try {
            val w = bmp.width; val h = bmp.height
            if (w <= 0 || h <= 0) return null
            val scale = minOf(1f, 768f / maxOf(w, h))
            val sw = maxOf(1, (w * scale).toInt())
            val sh = maxOf(1, (h * scale).toInt())
            val small = if (sw != w || sh != h) {
                Bitmap.createScaledBitmap(bmp, sw, sh, true)
            } else bmp
            val out = ByteArrayOutputStream()
            small.compress(Bitmap.CompressFormat.JPEG, 80, out)
            if (small != bmp) small.recycle()
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (t: Throwable) {
            // Allocates a scaled bitmap + JPEG + base64 buffers: an
            // OutOfMemoryError must degrade to null (the caller clears the
            // loading state and shows "Could not load image") instead of
            // escaping to scopeGuard, which only warn-logs and would leave
            // the AI assistant stuck on its loading spinner forever.
            if (t is CancellationException) throw t
            null
        }
    }

    /** Sends the current photo + settings to Gemini and shows suggestions. */
    fun analyzePhotoWithAi() {
        val key = aiKeyStore.getGeminiKey()
        if (key == null) {
            _aiAssistant.update { it.copy(error = "Add your Gemini API key first.") }
            return
        }
        if (_aiAssistant.value.isLoading) return
        _aiAssistant.update { it.copy(isLoading = true, error = null, pendingSuggestions = null) }
        guardLaunch {
            val base64 = encodeImageForAi()
            if (base64 == null) {
                _aiAssistant.update { it.copy(isLoading = false, error = "Could not load image.") }
                return@guardLaunch
            }
            val userMsg = AiChatMessage("user", "📷 Analyze my current photo")
            _aiAssistant.update { it.copy(messages = it.messages + userMsg) }
            when (val r = geminiClient.analyzePhoto(key, base64, buildSettingsSummary())) {
                is GeminiClient.Result.Success -> {
                    val s = r.suggestions
                    val replyText = buildString {
                        if (s.analysis.isNotBlank()) append(s.analysis)
                        if (s.reasoning.isNotBlank()) {
                            if (isNotEmpty()) append("\n\n")
                            append(s.reasoning)
                        }
                        if (isEmpty()) append("Here are my suggested settings.")
                    }
                    _aiAssistant.update {
                        it.copy(
                            isLoading = false,
                            messages = it.messages + AiChatMessage("model", replyText),
                            pendingSuggestions = s,
                        )
                    }
                }
                is GeminiClient.Result.Failure -> {
                    _aiAssistant.update {
                        it.copy(
                            isLoading = false,
                            error = r.message,
                            messages = it.messages +
                                AiChatMessage("model", "Sorry, I couldn't analyze the photo. ${r.message}"),
                        )
                    }
                }
            }
        }
    }

    /** Free-text follow-up question to Gemini. */
    fun sendAiChatMessage(text: String) {
        val key = aiKeyStore.getGeminiKey()
        if (key == null) {
            _aiAssistant.update { it.copy(error = "Add your Gemini API key first.") }
            return
        }
        if (_aiAssistant.value.isLoading || text.isBlank()) return
        _aiAssistant.update {
            it.copy(
                isLoading = true, error = null,
                messages = it.messages + AiChatMessage("user", text),
            )
        }
        guardLaunch {
            val history = _aiAssistant.value.messages
            when (val r = geminiClient.chat(key, history, buildSettingsSummary())) {
                is GeminiClient.Result.Success -> {
                    _aiAssistant.update {
                        it.copy(
                            isLoading = false,
                            messages = it.messages + AiChatMessage("model", r.rawText),
                        )
                    }
                }
                is GeminiClient.Result.Failure -> {
                    _aiAssistant.update {
                        it.copy(isLoading = false, error = r.message)
                    }
                }
            }
        }
    }

    /** Applies AI-suggested settings to the current photo (single undo step). */
    fun applyAiSuggestions(s: AiSuggestions) {        // Gemini sometimes ignores the documented ranges (e.g. exposure 999 or
        // contrast 500). Clamp everything to the real slider ranges so a wild
        // model output can never push EditState into out-of-range territory.
        fun Float?.clamped(or: Float, range: ClosedFloatingPointRange<Float>): Float =
            (this ?: or).coerceIn(range)
        update("AI suggestions") { st ->
            st.copy(
                light = st.light.copy(
                    exposure = s.exposure.clamped(st.light.exposure, -5f..5f),
                    contrast = s.contrast.clamped(st.light.contrast, -100f..100f),
                    highlights = s.highlights.clamped(st.light.highlights, -100f..100f),
                    shadows = s.shadows.clamped(st.light.shadows, -100f..100f),
                    whites = s.whites.clamped(st.light.whites, -100f..100f),
                    blacks = s.blacks.clamped(st.light.blacks, -100f..100f),
                    temp = s.temp.clamped(st.light.temp, -100f..100f),
                    tint = s.tint.clamped(st.light.tint, -100f..100f),
                ),
                color = st.color.copy(
                    vibrance = s.vibrance.clamped(st.color.vibrance, -100f..100f),
                    saturation = s.saturation.clamped(st.color.saturation, -100f..100f),
                ),
                detail = st.detail.copy(
                    sharpening = s.sharpenAmount.clamped(st.detail.sharpening, 0f..150f),
                    luminanceNr = s.noiseReduction.clamped(st.detail.luminanceNr, 0f..100f),
                ),
                optics = st.optics.copy(
                    vignetteAmount = s.vignette.clamped(st.optics.vignetteAmount, -100f..100f),
                ),
            )
        }
        _aiAssistant.update {
            it.copy(
                pendingSuggestions = null,
                messages = it.messages +
                    AiChatMessage("model", "✅ Applied! Tweak any slider further if you like."),
            )
        }
    }

    // ---- Auto Magic (one-tap enhance / retouch) ----

    /**
     * What an Auto Enhance tap did. The UI renders one caption per outcome —
     * a sealed type instead of nullable-String soup, so "toggle off" and
     * "photo already balanced" can never be confused.
     */
    sealed interface AutoEnhanceOutcome {
        /** Analysis crashed or the preview was gone. */
        data object Failed : AutoEnhanceOutcome
        /** The photo was already balanced: nothing was pushed to undo. */
        data object NoChange : AutoEnhanceOutcome
        /** First tap applied the correction ([summary] describes it). */
        data class Applied(val summary: String) : AutoEnhanceOutcome
        /** Second tap: reverted to the exact pre-auto state. */
        data object Reverted : AutoEnhanceOutcome
        /**
         * The user edited after the auto apply, so the pre-auto snapshot is
         * gone — the toggle has nothing to restore (Undo still works).
         */
        data object Stale : AutoEnhanceOutcome
    }

    /** Same contract for Pro Auto (scene name instead of a summary). */
    sealed interface ProAutoOutcome {
        data object Failed : ProAutoOutcome
        data class Applied(val sceneName: String) : ProAutoOutcome
        data object Reverted : ProAutoOutcome
        data object Stale : ProAutoOutcome
    }

    /**
     * Returns the state an auto operation applies its deltas onto: the
     * pre-auto snapshot from the first tap this session, captured lazily.
     * Makes Auto Enhance / Pro Auto idempotent — re-tapping recomputes the
     * same result instead of stacking deltas. Main thread only.
     */
    private fun autoBase(): EditState {
        val cur = _state.value
        return autoBaseline ?: cur.also { autoBaseline = cur }
    }

    /** Drops the auto baseline (photo change, reset, undo/redo). */
    private fun clearAutoBaseline() {
        autoBaseline = null
        // The pre-auto snapshot is gone, so the toggle has nothing to
        // revert to: the applied flags die with it.
        _autoEnhanceApplied.value = false
        _proAutoApplied.value = false
        _autoRetouchApplied.value = false
        _aiProRetouchApplied.value = false
    }

    /**
     * One-tap Auto Enhance: analyzes histogram, applies auto levels + AWB.
     *
     * A true TOGGLE (user decision 2026-10-17): the first tap applies the
     * best result; the second tap reverts to the exact pre-auto state
     * (မူလအတိုင်း), as one undoable edit — repeated taps can never stack
     * brighter. [onDone] reports what happened so the UI can confirm it
     * instead of staying silent. Always invoked on the main thread.
     */
    fun applyAutoEnhance(onDone: (AutoEnhanceOutcome) -> Unit = {}) {
        // Toggle-off: restore the pinned pre-auto snapshot. Captured before
        // update(), which clears the baseline itself.
        if (_autoEnhanceApplied.value) {
            val base = autoBaseline
            _autoEnhanceApplied.value = false
            if (base != null) {
                update("Auto Enhance off") { _ -> base }
                onDone(AutoEnhanceOutcome.Reverted)
            } else {
                onDone(AutoEnhanceOutcome.Stale)
            }
            return
        }
        // Private snapshot: analyze() reads pixels on Dispatchers.Default
        // while the UI thread may recycle the live preview bitmap. Was
        // unguarded: a recycled bitmap here crashed the app outright.
        val bmp = snapshotPreview() ?: run { onDone(AutoEnhanceOutcome.Failed); return }
        // Capture on the main thread before the background analysis: every
        // tap re-applies onto the pre-auto state, so tapping Auto twice
        // can't stack the correction (idempotent).
        val base = autoBase()
        // Photo-change race: capture which photo this tap belongs to. The
        // main-thread apply block below bails if the user opened another
        // photo while the analysis ran, so this photo's correction can never
        // land on the new photo's state.
        val tapPhotoId = _libraryImage.value?.id
        autoEnhanceJob?.cancel()
        autoEnhanceJob = guardLaunch(Dispatchers.Default) {
            try {
                val auto = AutoEnhance.analyze(bmp, strength = 1f)
                withContext(Dispatchers.Main) {
                    if (_libraryImage.value?.id != tapPhotoId) {
                        onDone(AutoEnhanceOutcome.NoChange)
                        return@withContext
                    }
                    val summary = AutoEnhanceCore.describe(auto)
                    if (summary.isNotEmpty()) {
                        val currentAtApply = _state.value
                        // Apply onto the baseline, not the current state.
                        val next = base.copy(
                            light = base.light.copy(
                                exposure = (base.light.exposure + auto.exposure).coerceIn(-5f, 5f),
                                contrast = (base.light.contrast + auto.contrast).coerceIn(-100f, 100f),
                                highlights = (base.light.highlights + auto.highlights).coerceIn(-100f, 100f),
                                shadows = (base.light.shadows + auto.shadows).coerceIn(-100f, 100f),
                                whites = (base.light.whites + auto.whites).coerceIn(-100f, 100f),
                                blacks = (base.light.blacks + auto.blacks).coerceIn(-100f, 100f),
                                clarity = (base.light.clarity + auto.clarity).coerceIn(-100f, 100f),
                                temp = (base.light.temp + auto.temp).coerceIn(-100f, 100f),
                                tint = (base.light.tint + auto.tint).coerceIn(-100f, 100f),
                            ),
                            color = base.color.copy(
                                vibrance = (base.color.vibrance + auto.vibrance).coerceIn(-100f, 100f),
                            ),
                        )
                        // Skip the undo entry + history spam when a re-tap
                        // recomputes the identical state.
                        if (next != currentAtApply) {
                            update("Auto Enhance") { _ -> next }
                        }
                        // Re-pin the baseline only when the state wasn't
                        // touched by the user between tap and apply: still
                        // the tap-time state, or already the auto result
                        // (idempotent re-tap). Otherwise update() left it
                        // cleared, so the next tap snapshots fresh.
                        if (currentAtApply == base || currentAtApply == next) {
                            autoBaseline = base
                            _autoEnhanceApplied.value = true
                        }
                        onDone(AutoEnhanceOutcome.Applied(summary))
                    } else {
                        onDone(AutoEnhanceOutcome.NoChange)
                    }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                android.util.Log.w("EditorVM", "autoEnhance failed", t)
                withContext(Dispatchers.Main) { onDone(AutoEnhanceOutcome.Failed) }
            } finally {
                if (!bmp.isRecycled) bmp.recycle()
            }
        }
    }

    /** Outcome of one-tap Auto Denoise (classical++, no machine learning). */
    sealed interface AutoDenoiseOutcome {
        data class Applied(val summary: String) : AutoDenoiseOutcome
        data object NoChange : AutoDenoiseOutcome
        data object Failed : AutoDenoiseOutcome
    }

    /**
     * One-tap Auto Denoise: analyzes the photo's noise floor and sets the
     * Detail noise-reduction sliders to suggested values.
     *
     * Deterministic pixel math via [AutoDenoise] (pro-premium Phase B#3,
     * classical++ — no license-clean on-device denoise model exists, so no
     * AI is claimed). Applies ABSOLUTE slider targets as one undoable
     * "Auto denoise" edit: repeated taps converge on the same settings and
     * can never stack. Always invoked on the main thread.
     */
    fun autoDenoise(onDone: (AutoDenoiseOutcome) -> Unit = {}) {
        // Same recycled-bitmap guard as applyAutoEnhance: analyze() reads
        // pixels on Dispatchers.Default while the UI thread may recycle
        // the live preview bitmap.
        val bmp = snapshotPreview() ?: run { onDone(AutoDenoiseOutcome.Failed); return }
        // Photo-change race: the noise estimate belongs to the photo open
        // at tap time; a mid-analysis photo switch must not overwrite the
        // new photo's NR sliders.
        val tapPhotoId = _libraryImage.value?.id
        autoDenoiseJob?.cancel()
        autoDenoiseJob = guardLaunch(Dispatchers.Default) {
            try {
                val origArea = (bmp.width * bmp.height).toFloat()
                // Downsample to max 256px for speed, like AutoEnhance.
                val scale = kotlin.math.min(1f, 256f / kotlin.math.max(bmp.width, bmp.height))
                val sw = kotlin.math.max(1, (bmp.width * scale).toInt())
                val sh = kotlin.math.max(1, (bmp.height * scale).toInt())
                val small = android.graphics.Bitmap.createScaledBitmap(bmp, sw, sh, true)
                val pixels = IntArray(sw * sh)
                small.getPixels(pixels, 0, sw, 0, 0, sw, sh)
                if (small !== bmp) small.recycle()
                // Downsampling averages uncorrelated noise away; undo that
                // attenuation so sigmas are in full-res-equivalent units.
                val scaleComp = kotlin.math.sqrt(origArea / (sw * sh).toFloat())
                val settings = AutoDenoise.analyzePixels(pixels, sw, sh, scaleComp)
                withContext(Dispatchers.Main) {
                    // Stale: NoChange (not Failed) — nothing was applied and
                    // there is no error to report; the caption is transient.
                    if (stalePhoto(tapPhotoId)) {
                        onDone(AutoDenoiseOutcome.NoChange)
                        return@withContext
                    }
                    if (settings != null) {
                        val current = _state.value
                        val next = current.copy(
                            detail = current.detail.copy(
                                luminanceNr = settings.luminanceNr,
                                colorNr = settings.colorNr,
                            ),
                        )
                        // Skip the undo entry + history spam when a re-tap
                        // recomputes the identical settings (idempotent).
                        if (next != current) {
                            update("Auto denoise") { _ -> next }
                        }
                        onDone(AutoDenoiseOutcome.Applied(AutoDenoise.describe(settings)))
                    } else {
                        onDone(AutoDenoiseOutcome.NoChange)
                    }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                android.util.Log.w("EditorVM", "autoDenoise failed", t)
                withContext(Dispatchers.Main) { onDone(AutoDenoiseOutcome.Failed) }
            } finally {
                if (!bmp.isRecycled) bmp.recycle()
            }
        }
    }

    /**
     * Pro Auto: scene-aware professional recipe. Detects the scene type and
     * applies a coordinated pro editing workflow (like a pro photographer).
     *
     * A true TOGGLE (user decision 2026-10-17), same contract as
     * [applyAutoEnhance]: first tap applies the recipe, second tap reverts
     * to the exact pre-auto state, never stacking.
     *
     * Returns the detected scene name for UI feedback.
     */
    fun applyProAuto(onDone: (ProAutoOutcome) -> Unit = {}) {
        // Toggle-off: restore the pinned pre-auto snapshot (recipe + the
        // auto-created portrait skin mask both live in the EditState).
        if (_proAutoApplied.value) {
            val base = autoBaseline
            _proAutoApplied.value = false
            if (base != null) {
                update("Pro Auto off") { _ -> base }
                onDone(ProAutoOutcome.Reverted)
            } else {
                onDone(ProAutoOutcome.Stale)
            }
            return
        }
        // Private snapshot: detectScene() reads pixels on Dispatchers.Default
        // while the UI thread may recycle the live preview bitmap. Was
        // unguarded: a recycled bitmap here crashed the app outright.
        val bmp = snapshotPreview() ?: run { onDone(ProAutoOutcome.Failed); return }
        // Capture on the main thread before the background analysis: the
        // recipe is applied onto the pre-auto state, so tapping Pro Auto
        // repeatedly recomputes the same result instead of stacking the
        // recipe's deltas (user video 2026-10-17: 8 taps -> +2.80 exposure).
        val base = autoBase()
        // Photo-change race: capture which photo this tap belongs to; the
        // main-thread apply block bails if a different photo is open by
        // then, so this photo's recipe (and its auto skin mask) can never
        // land on the new photo's state.
        val tapPhotoId = _libraryImage.value?.id
        proAutoJob?.cancel()
        proAutoJob = guardLaunch(Dispatchers.Default) {
            try {
                // Detect scene using AutoEnhance analysis
                val scene = detectScene(bmp)
                val sceneName = when (scene) {
                    0 -> "Portrait"
                    1 -> "Landscape"
                    2 -> "Night"
                    3 -> "Backlit"
                    else -> "Photo"
                }

                withContext(Dispatchers.Main) {
                    if (_libraryImage.value?.id != tapPhotoId) {
                        onDone(ProAutoOutcome.Failed)
                        return@withContext
                    }
                    val currentAtApply = _state.value
                    val next = when (scene) {
                        0 -> ProRecipes.applyPortrait(base)
                        1 -> ProRecipes.applyLandscape(base)
                        2 -> ProRecipes.applyNight(base)
                        3 -> ProRecipes.applyBacklit(base)
                        else -> ProRecipes.applyLandscape(base)
                    }
                    // Skip the undo entry + history spam when a re-tap
                    // recomputes the identical state.
                    if (next != currentAtApply) {
                        update("Pro Auto ($sceneName)") { _ -> next }
                    }
                    // Re-pin the baseline only when the state wasn't touched
                    // by the user between tap and apply (see Auto Enhance).
                    if (currentAtApply == base || currentAtApply == next) {
                        autoBaseline = base
                        _proAutoApplied.value = true
                    }
                    // For portraits: auto-create skin softening mask. The mask
                    // job does its own update() (which clears the baseline),
                    // so hand it the pre-auto state to re-pin afterwards —
                    // the toggle must revert recipe + mask together.
                    if (scene == 0) {
                        applyAutoSkinMask(repinBaseline = base)
                    }
                    requestRender()
                    onDone(ProAutoOutcome.Applied(sceneName))
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                android.util.Log.w("EditorVM", "proAuto failed", t)
                withContext(Dispatchers.Main) { onDone(ProAutoOutcome.Failed) }
            } finally {
                if (!bmp.isRecycled) bmp.recycle()
            }
        }
    }

    /** Outcome of one-tap AI Pro Retouch (image-adaptive pro grade). */
    sealed interface AiProRetouchOutcome {
        data class Applied(val summary: String) : AiProRetouchOutcome
        data object Reverted : AiProRetouchOutcome
        data object Stale : AiProRetouchOutcome
        data object NoChange : AiProRetouchOutcome
        data object Failed : AiProRetouchOutcome
    }

    /**
     * AI Pro Retouch — one-tap image-adaptive pro grade.
     *
     * Analyzes the SOURCE photo's condition (exposure, cast, scene) with
     * [AiProRetouch], blends the baked pro-recipe LUT bank into one
     * image-adaptive 3D LUT, and applies it on the GPU as the base grade;
     * the blended neighborhood ops (clarity/texture/dehaze) ride as ordinary
     * light params so they stay tweakable. Everything lands in a single
     * undoable "AI Pro Retouch" history entry.
     *
     * A true TOGGLE (user decision 2026-10-17, same contract as Auto Enhance
     * / Pro Auto / Auto Retouch): the first tap applies the best result; the
     * second tap reverts to the exact pre-auto state (မူလအတိုင်း), as one
     * undoable edit — repeated taps can never stack brighter. [onDone]
     * reports what happened so the UI can confirm it instead of staying
     * silent. Always invoked on the main thread.
     *
     * Honest no-op: when the photo is blank or already balanced
     * ([AiProRetouch.LutBlend.strength] == 0) nothing is pushed to undo and
     * [onDone] reports [AiProRetouchOutcome.NoChange] instead of pretending
     * work happened.
     */
    fun applyAiProRetouch(onDone: (AiProRetouchOutcome) -> Unit = {}) {
        // Toggle-off: restore the pinned pre-auto snapshot. Captured before
        // update(), which clears the baseline itself.
        if (_aiProRetouchApplied.value) {
            val base = autoBaseline
            _aiProRetouchApplied.value = false
            if (base != null) {
                update("AI Pro Retouch off") { _ -> base }
                onDone(AiProRetouchOutcome.Reverted)
            } else {
                onDone(AiProRetouchOutcome.Stale)
            }
            return
        }
        val bmp = snapshotPreview() ?: run { onDone(AiProRetouchOutcome.Failed); return }
        // Capture on the main thread before the background analysis: every
        // tap re-applies onto the pre-auto state, so re-tapping can't stack
        // the clarity/texture/dehaze deltas (idempotent).
        val base = autoBase()
        // Photo-change race: the fused LUT belongs to this photo; the
        // main-thread apply block bails if another photo is open by then.
        val tapPhotoId = _libraryImage.value?.id
        aiProRetouchJob?.cancel()
        aiProRetouchJob = guardLaunch(Dispatchers.Default) {
            try {
                val w = bmp.width; val h = bmp.height
                val scale = minOf(1f, 160f / maxOf(w, h))
                val sw = maxOf(1, (w * scale).toInt())
                val sh = maxOf(1, (h * scale).toInt())
                val small = android.graphics.Bitmap.createScaledBitmap(bmp, sw, sh, true)
                val pixels = IntArray(sw * sh)
                small.getPixels(pixels, 0, sw, 0, 0, sw, sh)
                if (small != bmp) small.recycle()
                val blend = AiProRetouch.analyze(pixels, sw, sh)
                if (blend.strength <= 0f) {
                    withContext(Dispatchers.Main) { onDone(AiProRetouchOutcome.NoChange) }
                    return@guardLaunch
                }
                if (!AiLutBank.available) {
                    withContext(Dispatchers.Main) { onDone(AiProRetouchOutcome.Failed) }
                    return@guardLaunch
                }
                // GL thread first: bake the bank once, fuse this photo's LUT,
                // upload it. Only when that succeeds do we touch EditState —
                // so a GL failure can never leave the state claiming a grade
                // whose LUT never uploaded.
                val host = sharedGlHost
                val engine = glEngineRef
                val previewBmp = _preview.value
                if (host == null || engine == null || previewBmp == null) {
                    withContext(Dispatchers.Main) { onDone(AiProRetouchOutcome.Failed) }
                    return@guardLaunch
                }
                val done = CompletableDeferred<Boolean>()
                host.queueGl {
                    try {
                        // Shared re-upload primitive (engine/AiGradeUpload):
                        // bakes the bank once per process, fuses this photo's
                        // LUT from the analyzed weights, uploads it. Only
                        // when that succeeds do we touch EditState — so a GL
                        // failure can never leave the state claiming a grade
                        // whose LUT never uploaded.
                        val up = AiGradeUpload.upload(
                            engine,
                            blend.weights.toList(),
                            blend.wbGains?.toList(),
                        )
                        if (up.sourceStomped) {
                            // The bake replaced the source texture with the
                            // identity ramp — restore the photo (masks/curves/
                            // retouch are re-uploaded by invalidateGpuAssets
                            // below).
                            engine.setSource(previewBmp)
                        }
                        done.complete(up.ready)
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        done.complete(false)
                    }
                }
                val uploaded = try {
                    withTimeout(15000L) { done.await() }
                } catch (e: TimeoutCancellationException) {
                    // GPU bake timed out: degrade gracefully. External
                    // cancellation must still propagate.
                    false
                }
                // Re-upload all GPU assets (the bake replaced the source).
                invalidateGpuAssets()
                withContext(Dispatchers.Main) {
                    if (!uploaded) {
                        onDone(AiProRetouchOutcome.Failed)
                        return@withContext
                    }
                    if (_libraryImage.value?.id != tapPhotoId) {
                        // The user opened a different photo mid-bake: the
                        // fused LUT belongs to the previous photo. Drop it
                        // and apply nothing (the LUT slot stays gated off
                        // for the new photo's state, so no stale grade can
                        // leak into its preview).
                        onDone(AiProRetouchOutcome.NoChange)
                        return@withContext
                    }
                    val weights = blend.weights.toList()
                    val spatial = blend.spatial
                    val currentAtApply = _state.value
                    // Apply onto the baseline, not the current state:
                    // re-tapping recomputes the identical grade instead of
                    // stacking the neighborhood deltas.
                    val next = base.copy(
                        light = base.light.copy(
                            clarity = (base.light.clarity + spatial.clarity).coerceIn(-100f, 100f),
                            texture = (base.light.texture + spatial.texture).coerceIn(-100f, 100f),
                            dehaze = (base.light.dehaze + spatial.dehaze).coerceIn(-100f, 100f),
                        ),
                        aiRetouch = AiRetouchParams(
                            enabled = true,
                            weights = weights,
                            strength = blend.strength,
                            clarityDelta = spatial.clarity,
                            textureDelta = spatial.texture,
                            dehazeDelta = spatial.dehaze,
                            wbGains = blend.wbGains?.toList(),
                        ),
                    )
                    // Skip the undo entry + history spam when a re-tap
                    // recomputes the identical state.
                    if (next != currentAtApply) {
                        update("AI Pro Retouch") { _ -> next }
                    }
                    // Re-pin the baseline only when the state wasn't touched
                    // by the user between tap and apply (same rule as Auto
                    // Enhance): still the tap-time state, or already the auto
                    // result (idempotent re-tap). Otherwise update() left it
                    // cleared, so the next tap snapshots fresh.
                    if (currentAtApply == base || currentAtApply == next) {
                        autoBaseline = base
                        _aiProRetouchApplied.value = true
                    }
                    val pct = (blend.strength * 100).roundToInt()
                    val wbNote = if (blend.wbGains != null) " + white balance" else ""
                    onDone(AiProRetouchOutcome.Applied("${blend.sceneName} grade · $pct%$wbNote"))
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                android.util.Log.w("EditorVM", "aiProRetouch failed", t)
                withContext(Dispatchers.Main) { onDone(AiProRetouchOutcome.Failed) }
            } finally {
                if (!bmp.isRecycled) bmp.recycle()
            }
        }
    }

    /**
     * Live intensity 0..1 for the already-applied grade. No LUT re-upload
     * needed — the strength is a per-frame shader uniform.
     */
    fun setAiProRetouchStrength(strength: Float) {
        val cur = _state.value.aiRetouch
        if (!cur.enabled) return
        update(null, gesture = true) { s ->
            s.copy(aiRetouch = cur.copy(strength = strength.coerceIn(0f, 1f)))
        }
    }

    /**
     * Look transfer (pro-spec Phase 1): match this photo's colors to a
     * reference library photo's colors. Deterministic Reinhard lαβ
     * statistical transfer — no ML, no fake AI, fully offline.
     *
     * Only the 12 lαβ statistics are stored in EditState; the GPU LUT is
     * re-baked from them by the preview frame loop ([LookLutUpload]) and
     * the export path, so the match survives photo reopen, surface
     * recreation and batch export with no reference-photo access needed
     * after this tap. One undoable "Match look" entry.
     *
     * Honesty guard: when the two photos' colors already match (transfer
     * ~identity), reports that instead of adding a no-op undo entry.
     */
    fun applyLookTransfer(refImageId: String, onDone: (String?) -> Unit = {}) {
        val bmp = snapshotPreview() ?: run { onDone(null); return }
        guardLaunch(Dispatchers.Default) {
            try {
                val ref = container.library.byId(refImageId)
                val refUri = ref?.contentUri?.let { Uri.parse(it) }
                if (refUri == null) {
                    withContext(Dispatchers.Main) { onDone(null) }
                    return@guardLaunch
                }
                val refBmp = decodeDownscaled(refUri, 320)
                if (refBmp == null) {
                    withContext(Dispatchers.Main) { onDone(null) }
                    return@guardLaunch
                }
                try {
                    val srcStats = statsOf(bmp)
                    val refStats = statsOf(refBmp)
                    // No-op honesty guard: a transfer that changes nothing
                    // must not create an undo entry or claim a match.
                    val maxDev = ColorTransfer.maxDeviationFromIdentity(
                        srcStats, refStats)
                    if (maxDev < 4f / 255f) {
                        withContext(Dispatchers.Main) {
                            onDone("Already similar colors — no changes needed")
                        }
                        return@guardLaunch
                    }
                    withContext(Dispatchers.Main) {
                        update("Match look") { s ->
                            s.copy(
                                lookTransfer = LookTransferParams(
                                    srcStats = srcStats.toList(),
                                    refStats = refStats.toList(),
                                    refName = ref.displayName,
                                    strength = 1f,
                                ),
                            )
                        }
                        onDone("Matched \"${ref.displayName}\"")
                    }
                } finally {
                    if (!refBmp.isRecycled) refBmp.recycle()
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                android.util.Log.w("EditorVM", "lookTransfer failed", t)
                withContext(Dispatchers.Main) { onDone(null) }
            } finally {
                if (!bmp.isRecycled) bmp.recycle()
            }
        }
    }

    /** Removes the look-transfer match. One undoable entry. */
    fun clearLookTransfer() {
        if (!_state.value.lookTransfer.isActive()) return
        update("Clear look transfer") { s ->
            s.copy(lookTransfer = LookTransferParams())
        }
    }

    /**
     * Live strength 0..1 for the active match. No LUT re-bake needed — the
     * strength is a per-frame shader uniform.
     */
    fun setLookTransferStrength(strength: Float) {
        val cur = _state.value.lookTransfer
        if (!cur.isActive()) return
        update(null, gesture = true) { s ->
            s.copy(lookTransfer = cur.copy(strength = strength.coerceIn(0f, 1f)))
        }
    }

    /**
     * Decodes [uri] downscaled so its longest side is ~[maxEdge] px.
     * Statistics don't need full resolution; this keeps the transfer fast
     * and OOM-safe on large reference photos. Null on any failure.
     */
    private fun decodeDownscaled(uri: Uri, maxEdge: Int): android.graphics.Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            container.context.contentResolver.openInputStream(uri)?.use { ins ->
                BitmapFactory.decodeStream(ins, null, bounds)
            }
            val w = bounds.outWidth; val h = bounds.outHeight
            if (w <= 0 || h <= 0) return null
            var sample = 1
            while (maxOf(w, h) / (sample * 2) >= maxEdge) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
            }
            container.context.contentResolver.openInputStream(uri)?.use { ins ->
                BitmapFactory.decodeStream(ins, null, opts)
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            null
        }
    }

    /** lαβ statistics of [bmp], downscaled for speed. Pure math on a copy. */
    private fun statsOf(bmp: android.graphics.Bitmap): FloatArray {
        val w = bmp.width; val h = bmp.height
        val scale = minOf(1f, 320f / maxOf(w, h))
        val sw = maxOf(1, (w * scale).toInt())
        val sh = maxOf(1, (h * scale).toInt())
        val small = android.graphics.Bitmap.createScaledBitmap(bmp, sw, sh, true)
        return try {
            val pixels = IntArray(sw * sh)
            small.getPixels(pixels, 0, sw, 0, 0, sw, sh)
            ColorTransfer.analyze(pixels, sw, sh)
        } finally {
            if (small !== bmp && !small.isRecycled) small.recycle()
        }
    }

    /**
     * Detects scene type: 0=portrait, 1=landscape/normal, 2=night, 3=backlit.
     * Uses skin ratio, luminance distribution.
     */
    private fun detectScene(bmp: android.graphics.Bitmap): Int {
        return try {
            val w = bmp.width; val h = bmp.height
            val scale = minOf(1f, 128f / maxOf(w, h))
            val sw = maxOf(1, (w * scale).toInt())
            val sh = maxOf(1, (h * scale).toInt())
            val small = android.graphics.Bitmap.createScaledBitmap(bmp, sw, sh, true)
            val pixels = IntArray(sw * sh)
            small.getPixels(pixels, 0, sw, 0, 0, sw, sh)
            if (small != bmp) small.recycle()

            var skinCount = 0
            var darkCount = 0
            var brightCount = 0
            var sumLum = 0f
            for (p in pixels) {
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                if (AutoRetouch.skinProbability(r, g, b) > 0.5f) skinCount++
                val lum = 0.2126f * r + 0.7152f * g + 0.0722f * b
                sumLum += lum
                if (lum < 40) darkCount++
                if (lum > 215) brightCount++
            }
            val n = pixels.size.toFloat()
            val skinRatio = skinCount / n
            val darkRatio = darkCount / n
            val brightRatio = brightCount / n
            val meanLum = sumLum / n / 255f

            when {
                // Night: truly dark AND no detectable face. A shaded or
                // underexposed daylight portrait is a portrait, not a night
                // scene — the Night recipe's cool cinematic grade (+ the
                // stacked exposure of Auto + Pro Auto) ruins faces.
                // (User video 2026-10-17: daylight shaded portrait with a
                // clear face was classified Night and blown out.)
                meanLum < 0.28f && darkRatio > 0.45f && skinRatio < 0.02f -> 2 // Night
                darkRatio > 0.3f && brightRatio > 0.25f -> 3 // Backlit
                // Portrait: any meaningful face presence wins, even a weak
                // signal in a dark frame. A genuine night portrait also edits
                // better as a portrait (flattering WB, skin mask) than as a
                // cool cinematic grade.
                skinRatio > 0.02f -> 0 // Portrait
                else -> 1 // Landscape/Normal
            }
        } catch (_: Exception) {
            1
        }
    }

    /** Creates a skin-softening mask for portraits (called by Pro Auto). */
    private fun applyAutoSkinMask(repinBaseline: EditState? = null) {
        // Dedupe: re-tapping Pro Auto must not stack a second
        // "Skin soften pro" mask, and two rapid taps must not both pass
        // the check before either commits (in-flight claim).
        if (_state.value.masks.any { m ->
                m.components.any { it.type == MaskType.AI_SKIN }
            }) return
        if (!skinMaskInFlight.compareAndSet(false, true)) return
        // Private snapshot: skinMask() reads pixels on Dispatchers.Default
        // while the UI thread may recycle the live preview bitmap.
        val bmp = snapshotPreview() ?: run { skinMaskInFlight.set(false); return }
        // Photo-change race: called on the main thread (from Pro Auto's
        // guarded apply block), but the skin analysis below runs long —
        // the photo can still change before this job applies.
        val tapPhotoId = _libraryImage.value?.id
        guardLaunch(Dispatchers.Default) {
            try {
                val skinMask = try {
                    AutoRetouch.skinMask(bmp, feather = 0.5f)
                } catch (t: Throwable) { if (t is CancellationException) throw t; null }
                // skinMask is an independent bitmap; the snapshot can go now.
                if (!bmp.isRecycled) bmp.recycle()
                if (skinMask != null) {
                    // Check if meaningful skin area found
                    val sw = skinMask.width; val sh = skinMask.height
                    val px = IntArray(sw * sh)
                    skinMask.getPixels(px, 0, sw, 0, 0, sw, sh)
                    var skinPx = 0
                    for (p in px) if ((p ushr 24) > 80) skinPx++
                    if (skinPx.toFloat() / (sw * sh) < 0.02f) {
                        skinMask.recycle()
                        return@guardLaunch
                    }
                    // Stale: drop the raster before it is stored or
                    // attached — it belongs to the previous photo, and the
                    // baseline re-pin below must not resurrect it either.
                    if (stalePhoto(tapPhotoId)) {
                        skinMask.recycle()
                        return@guardLaunch
                    }
                    val maskId = java.util.UUID.randomUUID().toString()
                    val compId = java.util.UUID.randomUUID().toString()
                    val aiKey = "skin_pro_${java.util.UUID.randomUUID()}"
                    saveAiRasterAndRelease(aiKey, skinMask)
                    withContext(Dispatchers.Main) {
                        val skinComp = MaskComponent(
                            id = compId,
                            type = MaskType.AI_SKIN, // dedicated skin-raster type (aiKey lookup)
                            aiKey = aiKey,
                            revision = System.nanoTime(),
                        )
                        val mask = Mask(
                            id = maskId,
                            name = "Skin soften pro",
                            components = listOf(skinComp),
                            adjust = LocalAdjust(
                                clarity = -35f,
                                sharpness = -20f,
                            ),
                            revision = System.nanoTime(),
                        )
                        // Only re-pin when the baseline is still the pro-auto
                        // one: if the user edited after the recipe, their
                        // edit cleared it (null) and the toggle must NOT
                        // resurrect a stale snapshot over their work.
                        val pinnedBefore = autoBaseline
                        update("Pro skin mask") { s ->
                            s.copy(masks = s.masks + mask)
                        }
                        // The update() above cleared the auto baseline (and the
                        // applied flags); the Pro Auto toggle must still
                        // revert recipe + mask to the exact pre-auto state,
                        // so re-pin both.
                        if (repinBaseline != null && pinnedBefore == repinBaseline) {
                            autoBaseline = repinBaseline
                            _proAutoApplied.value = true
                        }
                        requestRender()
                    }
                }
            } finally {
                skinMaskInFlight.set(false)
            }
        }
    }

    /**
     * One-tap Auto Retouch Pro — Photoshop-grade adaptive retouch.
     *
     * Unlike a fixed recipe, this profiles the photo FIRST (noise, contrast,
     * brightness → condition) via [RetouchIntel], then adapts every decision
     * the way an experienced retoucher would:
     * - Blemish detection thresholds adapt to the condition (strict on
     *   noisy/harsh photos where grain mimics spots, sensitive on clean
     *   soft light for subtle blemishes).
     * - Heal sources are tone-matched ([RetouchEngine.findHealSourcePx]
     *   annulus analysis) instead of a blind diagonal offset — the healed
     *   patch blends into the SURROUNDING tone like Photoshop's healing.
     * - Skin-smoothing strength adapts to measured noise (stronger for
     *   high-ISO grain, gentle on clean skin to preserve pores).
     * - Under-eye brightening adapts to the condition (conservative in low
     *   light to avoid blowing out lifted shadows).
     *
     * This is adaptive heuristic intelligence, not a trained neural network:
     * it analyzes each photo's actual condition and adjusts its approach.
     */
    /** Outcome of one-tap Auto Retouch (true toggle, user decision 2026-10-17). */
    sealed interface AutoRetouchOutcome {
        data class Applied(
            val blemishCount: Int,
            val skinFound: Boolean,
            val underEyeCount: Int,
            val conditionLabel: String,
        ) : AutoRetouchOutcome
        data object Reverted : AutoRetouchOutcome
        data object Stale : AutoRetouchOutcome
        data object NoChange : AutoRetouchOutcome
        data object Failed : AutoRetouchOutcome
    }

    fun applyAutoRetouch(onDone: (AutoRetouchOutcome) -> Unit = {}) {
        // Toggle-off: restore the pinned pre-auto snapshot — heal and
        // under-eye spots plus the auto skin mask all live in the
        // EditState, so one restore reverts everything together. Same
        // contract as applyAutoEnhance / applyProAuto (user decision
        // 2026-10-17): first tap applies, second tap reverts to the exact
        // pre-auto state, never stacking.
        if (_autoRetouchApplied.value) {
            val base = autoBaseline
            _autoRetouchApplied.value = false
            if (base != null) {
                update("Auto Retouch off") { _ -> base }
                onDone(AutoRetouchOutcome.Reverted)
            } else {
                onDone(AutoRetouchOutcome.Stale)
            }
            return
        }
        // Private snapshot: the detectors read pixels on Dispatchers.Default
        // while the UI thread may recycle the live preview bitmap. Was
        // unguarded: a recycled bitmap here crashed the app outright.
        val bmp = snapshotPreview() ?: run { onDone(AutoRetouchOutcome.Failed); return }
        // Capture on the main thread before the background analysis: the
        // result is applied onto the pre-auto state, so repeated taps
        // recompute the same result instead of stacking more heal spots and
        // another skin mask on top (the old behavior).
        val base = autoBase()
        // Photo-change race: capture which photo this tap belongs to. The
        // main-thread apply block bails if the user opened another photo
        // while the detectors ran, so this photo's blemish spots can never
        // land on the new photo's state.
        val tapPhotoId = _libraryImage.value?.id
        autoRetouchJob?.cancel()
        autoRetouchJob = guardLaunch(Dispatchers.Default) {
            try {
            // 0. Profile the photo — the "retoucher's assessment" step.
            //    Every later decision adapts to this.
            val profile = try {
                RetouchIntel.analyze(bmp)
            } catch (t: Throwable) { if (t is CancellationException) throw t; null }
            val safeProfile = profile ?: RetouchIntel.PhotoProfile(
                0.3f, 0.4f, 0.5f, RetouchIntel.Condition.NORMAL)
            val recipe = RetouchIntel.recipeFor(safeProfile)
            val conditionLabel = RetouchIntel.conditionLabel(safeProfile.condition)

            // 1. Detect blemishes with ADAPTIVE thresholds
            val blemishes = try {
                AutoRetouch.detectBlemishes(
                    bmp,
                    maxSpots = recipe.maxBlemishes,
                    thresholdScale = recipe.blemishThresholdScale,
                )
            } catch (t: Throwable) { if (t is CancellationException) throw t; emptyList() }

            // 2. Detect skin
            val skinMask = try {
                AutoRetouch.skinMask(bmp, feather = 0.5f)
            } catch (t: Throwable) { if (t is CancellationException) throw t; null }

            // 3. Detect under-eye dark circles (pro portrait retouch)
            val underEyes = try {
                AutoRetouch.detectUnderEyes(bmp)
            } catch (t: Throwable) { if (t is CancellationException) throw t; emptyList() }

            // 4. Tone-matched heal sources for every blemish (same picker the
            //    manual HEAL tool uses — probes 8 angles x 3 rings for the
            //    cleanest tone-matching patch). One shared pixel array for
            //    all spots (no per-spot full-frame copy). Null entries fall
            //    back to the diagonal offset below.
            //    NOTE: bmp must stay alive through this block — recycled after.
            val blemishSources: List<Pair<Float, Float>?> = try {
                val scale = kotlin.math.min(1f, 1024f / kotlin.math.max(bmp.width, bmp.height))
                val sw = kotlin.math.max(64, (bmp.width * scale).toInt())
                val sh = kotlin.math.max(64, (bmp.height * scale).toInt())
                val small = Bitmap.createScaledBitmap(bmp, sw, sh, true)
                val px = IntArray(sw * sh)
                small.getPixels(px, 0, sw, 0, 0, sw, sh)
                if (small != bmp) small.recycle()
                blemishes.map { b ->
                    try {
                        RetouchEngine.findHealSourcePx(px, sw, sh, b.x, b.y, b.radius * 2.2f)
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        null
                    }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                List(blemishes.size) { null }
            }

            // All detectors copied what they need (skinMask is an independent
            // bitmap); the snapshot can go now.
            if (!bmp.isRecycled) bmp.recycle()
            // Check if meaningful skin area found (>2% of mask)
            var skinFound = false
            if (skinMask != null) {
                val sw = skinMask.width; val sh = skinMask.height
                val px = IntArray(sw * sh)
                skinMask.getPixels(px, 0, sw, 0, 0, sw, sh)
                var skinPx = 0
                for (p in px) if ((p ushr 24) > 80) skinPx++
                skinFound = skinPx.toFloat() / (sw * sh) > 0.02f
                if (!skinFound) skinMask.recycle()
            }

            withContext(Dispatchers.Main) {
                if (_libraryImage.value?.id != tapPhotoId) {
                    // The user opened a different photo while the detectors
                    // ran: never stamp this photo's blemish spots onto the
                    // new photo. The skin raster was never saved, so just
                    // release it and stay silent.
                    if (skinFound && skinMask != null && !skinMask.isRecycled) {
                        skinMask.recycle()
                    }
                    onDone(AutoRetouchOutcome.NoChange)
                    return@withContext
                }
                // Build the spots as pure data, then apply EVERYTHING onto
                // the pre-auto baseline in ONE undoable update: heal spots,
                // under-eye spots and the skin mask together. Applying onto
                // the baseline (not the live state) is what makes repeated
                // taps idempotent instead of stacking.
                val newSpots = ArrayList<RetouchSpot>(blemishes.size + underEyes.size)
                for ((i, b) in blemishes.withIndex()) {
                    // Tone-matched source solved above; fallback steers
                    // away from the nearest horizontal edge (mirrors the
                    // manual heal fallback from cycle 14).
                    val size = b.radius * 2.2f
                    val solved = blemishSources.getOrNull(i)
                    val srcX: Float
                    val srcY: Float
                    if (solved != null) {
                        srcX = solved.first
                        srcY = solved.second
                    } else {
                        val dir = if (b.x > 0.75f) -1f else 1f
                        srcX = (b.x + dir * b.radius * 3f).coerceIn(0f, 1f)
                        srcY = (b.y - b.radius * 3f).coerceIn(0f, 1f)
                    }
                    newSpots.add(RetouchSpot(
                        op = RetouchOp.HEAL,
                        x = b.x, y = b.y,
                        srcX = srcX, srcY = srcY,
                        size = size,
                        feather = 0.7f,
                        strength = recipe.healStrength,
                    ))
                }
                // Pro: brighten under-eye dark circles if found
                // (strength adapts to photo condition)
                for (ue in underEyes) {
                    newSpots.add(RetouchSpot(
                        op = RetouchOp.UNDER_EYE,
                        x = ue.x, y = ue.y,
                        srcX = ue.x, srcY = ue.y,
                        size = ue.radius * 2f,
                        feather = 0.8f,
                        strength = recipe.underEyeStrength,
                    ))
                }
                // Skin-softening mask if skin found
                var skinMaskToAdd: Mask? = null
                if (skinFound && skinMask != null) {
                    val aiKey = "skin_${UUID.randomUUID()}"
                    // Save the skin raster to the AI mask store
                    saveAiRasterAndRelease(aiKey, skinMask)
                    val skinComp = MaskComponent(
                        id = UUID.randomUUID().toString(),
                        type = MaskType.AI_SKIN,  // dedicated skin-raster type (aiKey lookup)
                        aiKey = aiKey,
                        revision = System.nanoTime(),
                    )
                    skinMaskToAdd = Mask(
                        id = UUID.randomUUID().toString(),
                        name = "Skin soften auto",
                        components = listOf(skinComp),
                        adjust = LocalAdjust(
                            clarity = recipe.skinClarity,
                            sharpness = recipe.skinSharpness,
                        ),
                        revision = System.nanoTime(),
                    )
                }

                if (newSpots.isEmpty() && skinMaskToAdd == null) {
                    // Nothing detected: don't touch the state, don't pin —
                    // a later tap retries fresh.
                    onDone(AutoRetouchOutcome.NoChange)
                } else {
                    val currentAtApply = _state.value
                    var next = base.copy(
                        retouch = base.retouch.copy(spots = base.retouch.spots + newSpots),
                    )
                    if (skinMaskToAdd != null) {
                        next = next.copy(masks = next.masks + skinMaskToAdd)
                    }
                    // Skip the undo entry + history spam when a re-tap
                    // recomputes the identical state.
                    if (next != currentAtApply) {
                        update("Auto retouch") { _ -> next }
                    }
                    // Re-pin the baseline only when the state wasn't touched
                    // by the user between tap and apply (see Auto Enhance).
                    if (currentAtApply == base || currentAtApply == next) {
                        autoBaseline = base
                        _autoRetouchApplied.value = true
                    }
                    requestRender()
                    onDone(AutoRetouchOutcome.Applied(
                        blemishCount = blemishes.size,
                        skinFound = skinFound,
                        underEyeCount = underEyes.size,
                        conditionLabel = conditionLabel,
                    ))
                }
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            android.util.Log.w("EditorVM", "autoRetouch failed", t)
            withContext(Dispatchers.Main) { onDone(AutoRetouchOutcome.Failed) }
        } finally {
            if (!bmp.isRecycled) bmp.recycle()
        }
    }
    }

    /**
     * Professional beauty retouch looks — one tap, sellable results.
     *
     * Each style orchestrates the full pro workflow with expert settings:
     * adaptive blemish healing, tone-matched sources, under-eye brightening,
     * eye enhancement (estimated from under-eye geometry), and skin work.
     * Styles build on [RetouchIntel]'s photo profiling, then apply
     * style-specific intensity — like a retoucher choosing their approach
     * for Natural vs Fashion Editorial.
     *
     * Styles:
     * - NATURAL: "no-makeup" look. Gentle heal, light smooth, subtle eyes.
     * - BEAUTY: classic beauty retouch. Full blemish removal, medium smooth,
     *   bright eyes, even skin tone.
     * - FASHION: editorial drama. Stronger smooth, defined eyes, polished skin.
     * - GLAMOUR: maximum polish. Everything at pro intensity.
     *
     * Eye positions are estimated geometrically from detected under-eyes
     * (eye sits above the under-eye shadow) — heuristic, not landmark ML.
     */
    enum class BeautyStyle {
        NATURAL, BEAUTY, FASHION, GLAMOUR
    }

    fun applyBeautyLook(
        style: BeautyStyle,
        onDone: (summary: String) -> Unit = {},
    ) {
        val bmp = snapshotPreview() ?: run { onDone("No photo"); return }
        // Photo-change race: every spot/mask below is positioned in the
        // analyzed photo's coordinates — a mid-analysis photo switch must
        // not stamp them onto the new photo.
        val tapPhotoId = _libraryImage.value?.id
        guardLaunch(Dispatchers.Default) {
            // Profile the photo, then apply style intensity.
            val profile = try { RetouchIntel.analyze(bmp) }
                catch (t: Throwable) { if (t is CancellationException) throw t; null }
            val base = RetouchIntel.recipeFor(profile ?: RetouchIntel.PhotoProfile(
                0.3f, 0.4f, 0.5f, RetouchIntel.Condition.NORMAL))

            // Style intensity — the "retoucher's choice" per look.
            // (thresholdScale < 1 = more sensitive detection)
            data class StyleIntensity(
                val threshMul: Float,
                val healMul: Float,
                val skinMul: Float,
                val eyeMul: Float,
                val maxSpots: Int,
            )
            val intensity = when (style) {
                BeautyStyle.NATURAL -> StyleIntensity(1.1f, 0.85f, 0.6f, 0.5f, 12)
                BeautyStyle.BEAUTY -> StyleIntensity(1.0f, 0.95f, 1.0f, 0.8f, 20)
                BeautyStyle.FASHION -> StyleIntensity(0.9f, 1.0f, 1.3f, 1.0f, 25)
                BeautyStyle.GLAMOUR -> StyleIntensity(0.85f, 1.0f, 1.5f, 1.0f, 30)
            }
            val (threshMul, healMul, skinMul, eyeMul, maxSpots) = intensity

            // Detect with style-adapted sensitivity.
            val blemishes = try {
                AutoRetouch.detectBlemishes(
                    bmp, maxSpots = maxSpots,
                    thresholdScale = base.blemishThresholdScale * threshMul,
                )
            } catch (t: Throwable) { if (t is CancellationException) throw t; emptyList() }
            val skinMask = try { AutoRetouch.skinMask(bmp, feather = 0.5f) }
                catch (t: Throwable) { if (t is CancellationException) throw t; null }
            val underEyes = try { AutoRetouch.detectUnderEyes(bmp) }
                catch (t: Throwable) { if (t is CancellationException) throw t; emptyList() }

            // Tone-matched heal sources.
            val blemishSources: List<Pair<Float, Float>?> = try {
                val sc = kotlin.math.min(1f, 1024f / kotlin.math.max(bmp.width, bmp.height))
                val sw = kotlin.math.max(64, (bmp.width * sc).toInt())
                val sh = kotlin.math.max(64, (bmp.height * sc).toInt())
                val small = Bitmap.createScaledBitmap(bmp, sw, sh, true)
                val px = IntArray(sw * sh)
                small.getPixels(px, 0, sw, 0, 0, sw, sh)
                if (small != bmp) small.recycle()
                blemishes.map { b ->
                    try { RetouchEngine.findHealSourcePx(px, sw, sh, b.x, b.y, b.radius * 2.2f) }
                    catch (t: Throwable) { if (t is CancellationException) throw t; null }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                List(blemishes.size) { null }
            }
            if (!bmp.isRecycled) bmp.recycle()

            // Check skin.
            var skinFound = false
            if (skinMask != null) {
                val sw = skinMask.width; val sh = skinMask.height
                val px = IntArray(sw * sh)
                skinMask.getPixels(px, 0, sw, 0, 0, sw, sh)
                var skinPx = 0
                for (p in px) if ((p ushr 24) > 80) skinPx++
                skinFound = skinPx.toFloat() / (sw * sh) > 0.02f
                if (!skinFound) skinMask.recycle()
            }

            withContext(Dispatchers.Main) {
                // Stale: recycle the analyzed raster, reset the caller's
                // spinner via onDone, but apply nothing — the blemish
                // coordinates belong to the previous photo.
                if (stalePhoto(tapPhotoId)) {
                    skinMask?.let { if (!it.isRecycled) it.recycle() }
                    onDone("Photo changed — beauty look not applied")
                    return@withContext
                }
                var eyeCount = 0
                if (blemishes.isNotEmpty() || underEyes.isNotEmpty()) {
                    beginGesture()
                    // Heal blemishes.
                    for ((i, b) in blemishes.withIndex()) {
                        val size = b.radius * 2.2f
                        val solved = blemishSources.getOrNull(i)
                        val srcX: Float; val srcY: Float
                        if (solved != null) { srcX = solved.first; srcY = solved.second }
                        else {
                            val dir = if (b.x > 0.75f) -1f else 1f
                            srcX = (b.x + dir * b.radius * 3f).coerceIn(0f, 1f)
                            srcY = (b.y - b.radius * 3f).coerceIn(0f, 1f)
                        }
                        update(gesture = true) { s ->
                            s.copy(retouch = s.retouch.copy(spots = s.retouch.spots + RetouchSpot(
                                op = RetouchOp.HEAL, x = b.x, y = b.y,
                                srcX = srcX, srcY = srcY, size = size,
                                feather = 0.7f,
                                strength = (base.healStrength * healMul).coerceIn(0f, 1f),
                            )))
                        }
                    }
                    // Under-eyes + estimated eye enhancement.
                    for (ue in underEyes) {
                        update(gesture = true) { s ->
                            s.copy(retouch = s.retouch.copy(spots = s.retouch.spots + RetouchSpot(
                                op = RetouchOp.UNDER_EYE, x = ue.x, y = ue.y,
                                srcX = ue.x, srcY = ue.y,
                                size = ue.radius * 2f, feather = 0.8f,
                                strength = (base.underEyeStrength * eyeMul).coerceIn(0f, 1f),
                            )))
                        }
                        // Eye sits above the under-eye shadow: estimate iris
                        // position geometrically for pro eye pop.
                        // Conservative: only for BEAUTY and stronger.
                        if (style != BeautyStyle.NATURAL) {
                            val eyeY = (ue.y - ue.radius * 1.6f).coerceIn(0f, 1f)
                            update(gesture = true) { s ->
                                s.copy(retouch = s.retouch.copy(spots = s.retouch.spots + RetouchSpot(
                                    op = RetouchOp.EYE_ENHANCE,
                                    x = ue.x, y = eyeY,
                                    srcX = ue.x, srcY = eyeY,
                                    size = ue.radius * 1.8f, feather = 0.75f,
                                    strength = (0.55f * eyeMul).coerceIn(0f, 1f),
                                )))
                            }
                            eyeCount++
                        }
                    }
                    endGesture("Beauty ${style.name.lowercase()}")
                }

                // Skin work with style intensity.
                if (skinFound && skinMask != null) {
                    val aiKey = "beauty_${UUID.randomUUID()}"
                    saveAiRasterAndRelease(aiKey, skinMask)
                    // Stronger styles also even skin tone (shadows lift).
                    val toneEven = if (style == BeautyStyle.FASHION || style == BeautyStyle.GLAMOUR) 12f else 0f
                    update("Beauty skin ${style.name.lowercase()}") { s ->
                        s.copy(masks = s.masks + Mask(
                            id = UUID.randomUUID().toString(),
                            name = "${style.name.lowercase().replaceFirstChar { it.uppercase() }} skin",
                            components = listOf(MaskComponent(
                                id = UUID.randomUUID().toString(),
                                type = MaskType.AI_SKIN, aiKey = aiKey,
                                revision = System.nanoTime(),
                            )),
                            adjust = LocalAdjust(
                                clarity = (base.skinClarity * skinMul).coerceIn(-100f, 0f),
                                sharpness = (base.skinSharpness * skinMul).coerceIn(-100f, 0f),
                                shadows = toneEven,
                            ),
                            revision = System.nanoTime(),
                        ))
                    }
                    requestRender()
                }

                val styleName = style.name.lowercase().replaceFirstChar { it.uppercase() }
                onDone(buildString {
                    append("$styleName: healed ${blemishes.size}")
                    if (underEyes.isNotEmpty()) append(", ${underEyes.size} under-eyes")
                    if (eyeCount > 0) append(", $eyeCount eyes enhanced")
                    if (skinFound) append(", skin polished")
                })
            }
        }
    }

    /**
     * Returns a NEW ALPHA_8 bitmap whose alpha is (255 − src alpha).
     * Used to measure the complement region (background) of a skin mask.
     * The caller owns and must recycle the returned bitmap.
     */
    private fun invertMaskAlpha(src: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            px[i] = (255 - ((px[i] ushr 24) and 0xFF)) shl 24
        }
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    /**
     * Pro-spec Phase 1: text-prompt editing, v1 (rule-based, offline).
     *
     * Interprets a plain-language request ("dramatic sky") via
     * [PromptEdit] and applies it in ONE undoable edit: the matched region
     * becomes a real mask with local adjustments, or — when no region is
     * named — the deltas go to the global light/color/detail sliders.
     *
     * Honest by construction: unrecognized prompts return [PromptEdit.helpText]
     * and apply nothing; a region the detector cannot isolate returns
     * [PromptEdit.regionMissMessage] and applies nothing. No guessing.
     *
     * [onResult] is invoked with the summary of what was applied (or why not).
     */
    fun applyPromptEdit(prompt: String, onResult: (String) -> Unit) {
        val plan = PromptEdit.interpret(prompt)
        if (plan == null) {
            onResult(PromptEdit.helpText)
            return
        }
        // v2 portrait path: detector-driven retouch spots, not masks.
        if (plan.portrait != null) {
            applyPortraitPrompt(plan, prompt, onResult)
            return
        }
        // Photo-change race: the mask raster below is built from the
        // analyzed photo's pixels — a mid-analysis photo switch must not
        // attach it (or the global deltas) to the new photo.
        val tapPhotoId = _libraryImage.value?.id
        guardLaunch(Dispatchers.Default) {
            // Caller-owned preview copy (see snapshotPreview): it MUST be
            // recycled when done. Every text-prompt edit used to leak one
            // full preview-sized bitmap here — no recycle() on any path —
            // so repeated prompt edits accumulated them toward an OOM on
            // large photos. The `finally` below covers the happy path, the
            // region-miss / stale-photo early returns, and the catch.
            val bmp = snapshotPreview()
            if (bmp == null) {
                onResult("Load a photo first, then describe the edit.")
                return@guardLaunch
            }
            try {
                val region = plan.region
                val maskSpec = region?.let { buildPromptMask(it, bmp) }
                if (region != null && maskSpec == null) {
                    onResult(PromptEdit.regionMissMessage(region))
                    return@guardLaunch
                }
                // Save the mask raster before the state update so the new
                // mask's aiKey resolves on the first render.
                val aiKey = maskSpec?.let {
                    val key = "prompt_${UUID.randomUUID()}"
                    saveAiRasterAndRelease(key, it.raster)
                    key
                }
                val maskId = UUID.randomUUID().toString()
                // Stale: the raster was analyzed from the previous photo;
                // attaching it here would mask the wrong image.
                if (stalePhoto(tapPhotoId)) {
                    onResult("Photo changed — edit not applied")
                    return@guardLaunch
                }
                update("Prompt: $prompt") { s ->
                    var next = applyPromptGlobal(s, plan.global)
                    if (maskSpec != null && aiKey != null) {
                        next = next.copy(masks = next.masks + Mask(
                            id = maskId,
                            name = plan.maskName,
                            components = listOf(MaskComponent(
                                id = UUID.randomUUID().toString(),
                                type = maskSpec.type,
                                aiKey = aiKey,
                                lumaLo = maskSpec.lumaLo,
                                lumaHi = maskSpec.lumaHi,
                                lumaFeather = maskSpec.lumaFeather,
                                revision = System.nanoTime(),
                            )),
                            adjust = plan.local,
                            inverted = maskSpec.inverted,
                            revision = System.nanoTime(),
                        ))
                    }
                    next
                }
                if (maskSpec != null) _selectedMaskId.value = maskId
                onResult(plan.summary)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                onResult("Couldn't apply that edit — ${t.message ?: "try again"}.")
            } finally {
                if (!bmp.isRecycled) bmp.recycle()
            }
        }
    }

    /**
     * v2 portrait path: runs the classical-CV detectors (blemish / under-eye)
     * and applies retouch spots — one undoable gesture, honest counts, never
     * faked. A detector that finds nothing reports instead of applying
     * anything; slider effects in the same prompt still go global.
     * The preview snapshot is a caller-owned copy — recycled in [finally].
     */
    private fun applyPortraitPrompt(
        plan: PromptPlan,
        prompt: String,
        onResult: (String) -> Unit,
    ) {
        // Photo-change race: the detected spots are positioned in the
        // analyzed photo's coordinates — a mid-analysis photo switch must
        // not stamp them onto the new photo.
        val tapPhotoId = _libraryImage.value?.id
        guardLaunch(Dispatchers.Default) {
            // Defensive: the caller routes here only when plan.portrait is
            // non-null, but this function must never NPE on a caller change —
            // capture once and fail with an honest message instead.
            val portrait = plan.portrait
            if (portrait == null) {
                onResult("Couldn't apply that edit — no portrait intent found.")
                return@guardLaunch
            }
            val bmp = snapshotPreview()
            if (bmp == null) {
                onResult("Load a photo first, then describe the edit.")
                return@guardLaunch
            }
            try {
                val spots = try {
                    buildPortraitSpots(portrait, bmp)
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    emptyList<RetouchSpot>()
                }
                val hasGlobal = plan.global != PromptGlobal()
                if (spots.isEmpty() && !hasGlobal) {
                    onResult(portraitMissMessage(portrait))
                    return@guardLaunch
                }
                if (stalePhoto(tapPhotoId)) {
                    onResult("Photo changed — edit not applied")
                    return@guardLaunch
                }
                beginGesture()
                try {
                    if (spots.isNotEmpty()) {
                        update(gesture = true) { s ->
                            s.copy(retouch = s.retouch.copy(
                                spots = s.retouch.spots + spots))
                        }
                    }
                    if (hasGlobal) {
                        update(gesture = true) { s -> applyPromptGlobal(s, plan.global) }
                    }
                } finally {
                    endGesture("Prompt: $prompt")
                }
                onResult(portraitResultMessage(portrait, spots.size, plan.effectLabels))
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                onResult("Couldn't apply that edit — ${t.message ?: "try again"}.")
            } finally {
                if (!bmp.isRecycled) bmp.recycle()
            }
        }
    }

    /** Detector-driven retouch spots for a portrait intent (empty = nothing found). */
    private fun buildPortraitSpots(
        portrait: PromptPortrait,
        bmp: android.graphics.Bitmap,
    ): List<RetouchSpot> = when (portrait) {
        PromptPortrait.RemoveBlemishes -> {
            val found = AutoRetouch.detectBlemishes(bmp, maxSpots = 20)
            found.map { b ->
                val src = RetouchEngine.findHealSource(bmp, b.x, b.y, b.radius * 2.2f)
                // Geometric fallback (same as the beauty flow): nudge the
                // source sideways/up so the heal never samples the blemish.
                val (sx, sy) = src ?: run {
                    val dir = if (b.x > 0.75f) -1f else 1f
                    Pair(
                        (b.x + dir * b.radius * 3f).coerceIn(0f, 1f),
                        (b.y - b.radius * 3f).coerceIn(0f, 1f),
                    )
                }
                RetouchSpot(
                    op = RetouchOp.HEAL, x = b.x, y = b.y,
                    srcX = sx, srcY = sy,
                    size = b.radius * 2.2f, feather = 0.7f, strength = 0.95f,
                )
            }
        }
        PromptPortrait.SoftenUnderEyes -> {
            AutoRetouch.detectUnderEyes(bmp).map { ue ->
                RetouchSpot(
                    op = RetouchOp.UNDER_EYE, x = ue.x, y = ue.y,
                    srcX = ue.x, srcY = ue.y,
                    size = ue.radius * 2f, feather = 0.8f, strength = 0.9f,
                )
            }
        }
        PromptPortrait.BrightenEyes -> {
            // No dedicated eye detector on-device: estimate the iris
            // geometrically from the under-eye shadow (the same estimate the
            // beauty flow uses) and say so in the result message.
            AutoRetouch.detectUnderEyes(bmp).map { ue ->
                val eyeY = (ue.y - ue.radius * 1.6f).coerceIn(0f, 1f)
                RetouchSpot(
                    op = RetouchOp.EYE_ENHANCE, x = ue.x, y = eyeY,
                    srcX = ue.x, srcY = eyeY,
                    size = ue.radius * 1.8f, feather = 0.75f, strength = 0.55f,
                )
            }
        }
    }

    /** Honest "detector found nothing" report — nothing was applied. */
    private fun portraitMissMessage(portrait: PromptPortrait): String = when (portrait) {
        PromptPortrait.RemoveBlemishes -> "No blemishes found — nothing applied."
        PromptPortrait.SoftenUnderEyes -> "No under-eye shadows found — nothing applied."
        PromptPortrait.BrightenEyes -> "Couldn't locate the eyes — nothing applied."
    }

    /** Counted result: what the detectors found and what was applied. */
    private fun portraitResultMessage(
        portrait: PromptPortrait,
        n: Int,
        effectLabels: List<String>,
    ): String {
        val extra = if (effectLabels.isNotEmpty())
            " · also applied: ${effectLabels.joinToString(", ")}" else ""
        val plural = if (n == 1) "" else "s"
        val base = when (portrait) {
            PromptPortrait.RemoveBlemishes ->
                if (n == 0) "No blemishes found." else "Healed $n blemish${if (n == 1) "" else "es"}."
            PromptPortrait.SoftenUnderEyes ->
                if (n == 0) "No under-eye shadows found." else "Softened $n under-eye shadow$plural."
            PromptPortrait.BrightenEyes ->
                if (n == 0) "Couldn't locate the eyes."
                else "Brightened $n eye$plural (located via under-eye shadows)."
        }
        return base + extra
    }

    /** A prompt region's raster + mask plumbing, built on the same
     *  classical-CV detectors the mask panel uses. Null = no confident
     *  region (caller reports, never fakes). */
    private data class PromptMaskSpec(
        val raster: Bitmap,
        val type: MaskType,
        val inverted: Boolean = false,
        val lumaLo: Float = 0f,
        val lumaHi: Float = 1f,
        val lumaFeather: Float = 0.5f,
    )

    private fun buildPromptMask(region: PromptRegion, bmp: Bitmap): PromptMaskSpec? =
        when (region) {
            PromptRegion.Sky ->
                PromptMaskSpec(ImageMath.selectSkyAuto(bmp), MaskType.SKY_SELECT)
            PromptRegion.Subject -> {
                val raster = AutoMask.subjectMask(bmp, feather = 0.6f) ?: return null
                PromptMaskSpec(raster, MaskType.AI_SUBJECT)
            }
            PromptRegion.Background -> {
                val raster = AutoMask.subjectMask(bmp, feather = 0.6f) ?: return null
                PromptMaskSpec(raster, MaskType.AI_BACKGROUND, inverted = true)
            }
            PromptRegion.Skin ->
                PromptMaskSpec(AutoRetouch.skinMask(bmp, feather = 0.6f), MaskType.AI_SKIN)
            is PromptRegion.Luminance -> PromptMaskSpec(
                raster = ImageMath.luminanceRangeBitmap(bmp, region.lo, region.hi, region.feather),
                type = MaskType.LUMINANCE_RANGE,
                lumaLo = region.lo, lumaHi = region.hi, lumaFeather = region.feather,
            )
        }

    /** Applies a [PromptGlobal] delta set onto the global params, clamped. */
    private fun applyPromptGlobal(s: EditState, g: PromptGlobal): EditState {
        if (g == PromptGlobal()) return s
        val l = s.light
        val c = s.color
        val d = s.detail
        return s.copy(
            light = l.copy(
                exposure = (l.exposure + g.exposure).coerceIn(-5f, 5f),
                contrast = (l.contrast + g.contrast).coerceIn(-100f, 100f),
                highlights = (l.highlights + g.highlights).coerceIn(-100f, 100f),
                shadows = (l.shadows + g.shadows).coerceIn(-100f, 100f),
                temp = (l.temp + g.temp).coerceIn(-100f, 100f),
                tint = (l.tint + g.tint).coerceIn(-100f, 100f),
                clarity = (l.clarity + g.clarity).coerceIn(-100f, 100f),
                dehaze = (l.dehaze + g.dehaze).coerceIn(-100f, 100f),
            ),
            color = c.copy(
                saturation = (c.saturation + g.saturation).coerceIn(-100f, 100f),
                vibrance = (c.vibrance + g.vibrance).coerceIn(-100f, 100f),
                bw = c.bw || g.bw,
            ),
            detail = d.copy(
                sharpening = (d.sharpening + g.sharpening).coerceIn(0f, 150f),
            ),
        )
    }

    /**
     * One-tap Auto Mask Pro — intelligent masking with automatic fixes.
     *
     * Analyzes the photo, isolates key regions (skin / subject / sky /
     * background),
     * then [SmartMask] analyzes EACH region's condition and applies
     * corrective adjustments automatically — like an editor fixing what
     * they see:
     * - Blown-out sky → exposure/highlights pulled back, detail recovered
     * - Dull gray sky → dehaze + saturation for depth
     * - Skin → adaptive smoothing ([RetouchIntel]) + harsh-shadow lift
     * - Main subject (non-portraits) → gentle clarity pop + shadow lift
     * - Bright distracting background → darkened for subject focus
     *
     * Corrections are conservative (fix problems, don't impose a look).
     * The user can still refine every slider after.
     *
     * @param onDone called with the list of mask names created (for UI feedback).
     */
    fun applyAutoMask(onDone: (created: List<String>) -> Unit = {}) {
        val bmp = snapshotPreview() ?: run { onDone(emptyList()); return }
        // Photo-change race: every raster below describes the analyzed
        // photo — a mid-analysis photo switch must not attach its masks
        // to the new photo.
        val tapPhotoId = _libraryImage.value?.id
        guardLaunch(Dispatchers.Default) {
            val analysis = try {
                AutoMask.analyze(bmp)
            } catch (t: Throwable) { if (t is CancellationException) throw t; null }

            // Photo profile for adaptive skin recipe.
            val profile = try {
                RetouchIntel.analyze(bmp)
            } catch (t: Throwable) { if (t is CancellationException) throw t; null }
            val recipe = RetouchIntel.recipeFor(profile ?: RetouchIntel.PhotoProfile(
                0.3f, 0.4f, 0.5f, RetouchIntel.Condition.NORMAL))

            // Build mask rasters + SMART adjustments for what was found.
            data class MaskPlan(
                val name: String,
                val type: MaskType,
                val raster: Bitmap,
                val adjust: LocalAdjust,
                val regionBrightness: Float, // for background-vs-subject compare
                /** Measured stats of the background region (subject isolation only). */
                val bgStats: SmartMask.RegionStats? = null,
            )
            val toCreate = mutableListOf<MaskPlan>()
            var subjectBrightness = 0.5f
            if (analysis != null) {
                if (analysis.isPortrait) {
                    try {
                        val skinBmp = AutoRetouch.skinMask(bmp, feather = 0.6f)
                        val stats = try {
                            SmartMask.analyzeRegion(bmp, skinBmp)
                        } catch (t: Throwable) {
                            if (t is CancellationException) throw t
                            SmartMask.RegionStats(0.5f, 0.3f, 0.4f)
                        }
                        // Background = everything that is NOT the subject:
                        // measure the real backdrop pixels (inverted skin
                        // raster) so the Background auto smart-fix reacts to
                        // the actual scene instead of a brightness estimate.
                        // Null on failure — the caller falls back to the
                        // rough estimate below.
                        val bgStats = try {
                            val inv = invertMaskAlpha(skinBmp)
                            try {
                                SmartMask.analyzeRegion(bmp, inv)
                            } finally {
                                if (!inv.isRecycled) inv.recycle()
                            }
                        } catch (t: Throwable) {
                            if (t is CancellationException) throw t
                            null
                        }
                        subjectBrightness = stats.brightness
                        toCreate.add(MaskPlan(
                            "Skin auto", MaskType.AI_SKIN, skinBmp,
                            SmartMask.skinFix(stats, recipe),
                            stats.brightness,
                            bgStats,
                        ))
                    } catch (t: Throwable) { if (t is CancellationException) throw t }
                }
                if (!analysis.isPortrait) {
                    // Non-portrait photos (pets, flowers, products, cars...):
                    // try the classical-CV subject selector. Portraits keep
                    // the more reliable skin path above; this covers
                    // everything else. Returns null (no mask) when no
                    // confident subject exists — never a wrong mask.
                    try {
                        val subjBmp = AutoMask.subjectMask(bmp, feather = 0.6f)
                        if (subjBmp != null) {
                            val stats = try {
                                SmartMask.analyzeRegion(bmp, subjBmp)
                            } catch (t: Throwable) {
                                if (t is CancellationException) throw t
                                SmartMask.RegionStats(0.5f, 0.3f, 0.4f)
                            }
                            val bgStats = try {
                                val inv = invertMaskAlpha(subjBmp)
                                try {
                                    SmartMask.analyzeRegion(bmp, inv)
                                } finally {
                                    if (!inv.isRecycled) inv.recycle()
                                }
                            } catch (t: Throwable) {
                                if (t is CancellationException) throw t
                                null
                            }
                            subjectBrightness = stats.brightness
                            toCreate.add(MaskPlan(
                                "Subject auto", MaskType.AI_SUBJECT, subjBmp,
                                SmartMask.subjectFix(stats),
                                stats.brightness,
                                bgStats,
                            ))
                        }
                    } catch (t: Throwable) { if (t is CancellationException) throw t }
                }
                if (analysis.hasSky) {
                    try {
                        val skyBmp = AutoMask.skyMask(bmp, feather = 0.6f)
                        val stats = try {
                            SmartMask.analyzeRegion(bmp, skyBmp)
                        } catch (t: Throwable) {
                            if (t is CancellationException) throw t
                            SmartMask.RegionStats(0.6f, 0.3f, 0.4f)
                        }
                        toCreate.add(MaskPlan(
                            "Sky auto", MaskType.SKY_SELECT, skyBmp,
                            SmartMask.skyFix(stats),
                            stats.brightness,
                        ))
                    } catch (t: Throwable) { if (t is CancellationException) throw t }
                }
            }
            if (!bmp.isRecycled) bmp.recycle()

            withContext(Dispatchers.Main) {
                // Stale: recycle every analyzed raster and create nothing —
                // the masks describe the previous photo. The spinner still
                // resets via onDone(emptyList()).
                if (stalePhoto(tapPhotoId)) {
                    for (plan in toCreate) {
                        if (!plan.raster.isRecycled) plan.raster.recycle()
                    }
                    onDone(emptyList())
                    return@withContext
                }
                val names = mutableListOf<String>()
                for (plan in toCreate) {
                    val aiKey = "automask_${UUID.randomUUID()}"
                    saveAiRasterAndRelease(aiKey, plan.raster)
                    val mask = Mask(
                        id = UUID.randomUUID().toString(),
                        name = plan.name,
                        components = listOf(
                            MaskComponent(
                                id = UUID.randomUUID().toString(),
                                type = plan.type,
                                aiKey = aiKey,
                                revision = System.nanoTime(),
                            )
                        ),
                        // Smart auto-fix — user can still refine every slider.
                        adjust = plan.adjust,
                        revision = System.nanoTime(),
                    )
                    update("Auto mask: ${plan.name}") { s -> s.copy(masks = s.masks + mask) }
                    names.add(plan.name)
                    // Portraits and subject photos get subject/background
                    // separation for free: the SAME stored raster, inverted,
                    // selects everything that is not the subject
                    // (Lightroom's "Select Background" parity). Shares the
                    // aiKey — no extra analysis, no extra bitmap saved.
                    // Inversion is applied shader-side for all mask types,
                    // so this needs no engine change.
                    // The background ALSO gets a smart fix: darken if it's
                    // brighter than the subject (distracting).
                    if (plan.type == MaskType.AI_SKIN || plan.type == MaskType.AI_SUBJECT) {
                        // Background region stats: measured from the real
                        // background pixels (inverted skin raster) when
                        // available; falls back to a rough complement estimate
                        // if that analysis failed.
                        val bgStats = plan.bgStats ?: SmartMask.RegionStats(
                            brightness = (1f - subjectBrightness * 0.5f).coerceIn(0f, 1f),
                            saturation = 0.3f, contrast = 0.4f,
                        )
                        val bgMask = Mask(
                            id = UUID.randomUUID().toString(),
                            name = "Background auto",
                            inverted = true,
                            components = listOf(
                                MaskComponent(
                                    id = UUID.randomUUID().toString(),
                                    type = plan.type,
                                    aiKey = aiKey,
                                    revision = System.nanoTime(),
                                )
                            ),
                            adjust = SmartMask.backgroundFix(bgStats, subjectBrightness),
                            revision = System.nanoTime(),
                        )
                        update("Auto mask: Background auto") { s ->
                            s.copy(masks = s.masks + bgMask)
                        }
                        names.add("Background auto")
                    }
                }
                if (names.isNotEmpty()) requestRender()
                onDone(names)
            }
        }
    }

    // ---- Wavelet Skin Retouch (GIMP-inspired à trous decomposition) ----

    /**
     * Applies professional wavelet-based skin retouching.
     * Uses à trous wavelet decomposition (5 scales) to separate skin texture
     * from blemishes and tone, then selectively smooths:
     * - [textureKeep] 0..1: keep fine detail (pores). 1 = keep all.
     * - [blemishSmooth] 0..1: smooth mid scales (blemishes, blotchiness).
     * - [toneEven] 0..1: even out large-scale skin tone.
     *
     * The retouch is gated by a skin-tone mask (YCrCb ellipsoid + feather):
     * only skin regions are retouched — hair, eyes, lips, clothing and
     * backgrounds come back bit-identical to the source.
     *
     * [onSkinCoverage] receives the fraction of the frame detected as skin
     * (0..1) so the UI can warn when no skin was found (near no-op run).
     *
     * Runs on Dispatchers.Default; result is undoable via the preview bitmap
     * stack. Calls [onDone] on the main thread when complete.
     */
    fun applyWaveletSkinRetouch(
        textureKeep: Float = 1f,
        blemishSmooth: Float = 0.6f,
        toneEven: Float = 0.3f,
        onDone: () -> Unit = {},
        onSkinCoverage: ((Float) -> Unit)? = null,
    ) {
        // Private snapshot: the worker reads pixels on Dispatchers.Default
        // while the UI thread may recycle the live preview bitmap.
        val bmp = snapshotPreview() ?: run { onDone(); return }
        // Photo-change race: the retouched result is the analyzed photo —
        // a mid-analysis photo switch must not replace the new photo's
        // preview bitmap with it.
        val tapPhotoId = _libraryImage.value?.id
        guardLaunch(Dispatchers.Default) {
            try {
                val w = bmp.width; val h = bmp.height
                // Work at max 1024px for performance; scale back up if needed
                val maxDim = 1024
                val scale = minOf(1f, maxDim.toFloat() / maxOf(w, h))
                val sw = maxOf(1, (w * scale).toInt())
                val sh = maxOf(1, (h * scale).toInt())
                val small = if (sw != w || sh != h) {
                    Bitmap.createScaledBitmap(bmp, sw, sh, true)
                } else bmp

                val px = IntArray(sw * sh)
                small.getPixels(px, 0, sw, 0, 0, sw, sh)
                if (small != bmp) small.recycle()
                // Pixels are now in px; bmp is our private snapshot (never the
                // live preview), so recycle it promptly instead of holding a
                // full-res copy for the whole wavelet run.
                if (!bmp.isRecycled) bmp.recycle()

                // Skin-region gate: build a per-pixel skin mask from the working
                // pixels (YCrCb ellipsoid probability, round-half-up packed,
                // feathered so edges blend invisibly). Non-skin pixels come
                // back bit-identical — wavelet retouching no longer smooths
                // hair, eyes, clothing or backgrounds.
                val skinMask: FloatArray = run {
                    val prob = FloatArray(sw * sh)
                    var skinny = 0
                    for (i in px.indices) {
                        val p = px[i]
                        val s = com.lumora.photoeditor.engine.AiProRetouch
                            .skinProbability((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
                        prob[i] = s
                        if (s > 0.2f) skinny++
                    }
                    val coverage = if (prob.isNotEmpty()) skinny.toFloat() / prob.size else 0f
                    withContext(Dispatchers.Main) { onSkinCoverage?.invoke(coverage) }
                    com.lumora.photoeditor.engine.FastBlur.boxBlurFloat(prob, sw, sh, 3)
                }

                val retouched = com.lumora.photoeditor.engine.WaveletDecompose
                    .retouchSkin(px, sw, sh, textureKeep, blemishSmooth, toneEven, 5, skinMask)

                // Scale back to original if we downscaled
                val resultBmp = if (sw != w || sh != h) {
                    val tmp = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
                    tmp.setPixels(retouched, 0, sw, 0, 0, sw, sh)
                    val upscaled = Bitmap.createScaledBitmap(tmp, w, h, true)
                    tmp.recycle()
                    upscaled
                } else {
                    val tmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    tmp.setPixels(retouched, 0, w, 0, 0, w, h)
                    tmp
                }

                withContext(Dispatchers.Main) {
                    // Stale: recycle the previous photo's result, reset the
                    // spinner, and touch nothing — the new photo's preview
                    // and undo stack belong to it alone.
                    if (stalePhoto(tapPhotoId)) {
                        if (!resultBmp.isRecycled) resultBmp.recycle()
                        onDone()
                        return@withContext
                    }
                    // Push current to bitmap undo stack (shared with Remove tool)
                    _preview.value?.let { cur ->
                        if (!cur.isRecycled) {
                            val backup = cur.copy(cur.config ?: Bitmap.Config.ARGB_8888, true)
                            synchronized(removalUndoStack) {
                                removalUndoStack.addLast(backup)
                                while (removalUndoStack.size > 5) {
                                    removalUndoStack.removeFirst().recycle()
                                }
                            }
                        }
                    }
                    _preview.value?.recycle()
                    _preview.value = resultBmp
                    requestRender()
                    onDone()
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                // If the failure happened before the snapshot was recycled
                // above, release it now to avoid leaking a full-res copy.
                if (!bmp.isRecycled) bmp.recycle()
                withContext(Dispatchers.Main) { onDone() }
            }
        }
    }

    // ---- Remove Object (manual brush + inpainting) ----

    /** Bitmap mask for the Remove tool (white = area to remove). */
    private var removalMask: Bitmap? = null
    private val removalMaskLock = Any()
    /** Cheap "mask has any paint" flag — avoids a full pixel scan per frame. */
    @Volatile private var removalMaskNonEmpty = false
    private val _removalMaskRevision = MutableStateFlow(0L)
    val removalMaskRevision = _removalMaskRevision.asStateFlow()

    /** True while the Remove retouch tool is selected: the preview shows a
     *  live red overlay of the removal mask via the existing GL
     *  mask-overlay path (constant-indexed sampler — Mali-safe). */
    private val _removeToolActive = MutableStateFlow(false)

    /** Cached immutable copy of the removal mask for GL upload. Old copies
     *  are never recycled while a frame may reference them (same policy as
     *  maskCache); they are dropped and left to GC. */
    private var removalOverlayCache: Pair<Long, Bitmap>? = null

    fun setRemoveToolActive(active: Boolean) {
        if (_removeToolActive.value == active) return
        _removeToolActive.value = active
        requestRender()
    }

    /** Thread-safe copy of the removal mask, or null when empty/missing. */
    private fun removalMaskCopyLocked(): Bitmap? {
        val m = synchronized(removalMaskLock) { removalMask } ?: return null
        return try {
            m.copy(Bitmap.Config.ALPHA_8, false)
        } catch (t: Throwable) {
            // OOM on the mask copy degrades to "no overlay" for this frame.
            if (t is CancellationException) throw t
            null
        }
    }

    /** Overlay bitmap for the current removal revision (cached by revision). */
    private fun removalOverlayBitmap(): Bitmap? {
        synchronized(removalMaskLock) {
            if (!removalMaskNonEmpty) {
                removalOverlayCache = null
                return null
            }
            val rev = _removalMaskRevision.value
            val cached = removalOverlayCache
            if (cached != null && cached.first == rev) return cached.second
            val copy = try {
                removalMask?.copy(Bitmap.Config.ALPHA_8, false)
            } catch (t: Throwable) {
                // OOM on the overlay copy: skip the overlay this revision.
                if (t is CancellationException) throw t
                null
            } ?: return null
            removalOverlayCache = rev to copy
            return copy
        }
    }

    /** Pre-removal bitmaps for undo (stack). */
    private val removalUndoStack = ArrayDeque<Bitmap>()

    private val _isRemoving = MutableStateFlow(false)
    val isRemoving = _isRemoving.asStateFlow()

    /**
     * Paints a segment into the removal mask.
     * @param from,to UV coordinates 0..1
     * @param radiusUv brush radius in UV (fraction of image width)
     */
    fun paintRemovalMask(from: Pt, to: Pt, radiusUv: Float) {
        val base = _preview.value ?: return
        val w = base.width; val h = base.height
        synchronized(removalMaskLock) {
            var mask = removalMask
            if (mask == null || mask.width != w || mask.height != h) {
                mask?.recycle()
                mask = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
                mask.eraseColor(0)
                removalMask = mask
            }
            // Draw line segment with round caps
            val canvas = android.graphics.Canvas(mask)
            val paint = android.graphics.Paint().apply {
                color = 0xFFFFFFFF.toInt()
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = (radiusUv * 2f * w).coerceAtLeast(4f)
                strokeCap = android.graphics.Paint.Cap.ROUND
                isAntiAlias = true
            }
            canvas.drawLine(from.x * w, from.y * h, to.x * w, to.y * h, paint)
            // Dot at start (for taps)
            canvas.drawCircle(from.x * w, from.y * h, paint.strokeWidth / 2f, paint.apply {
                style = android.graphics.Paint.Style.FILL
            })
            removalMaskNonEmpty = true
        }
        _removalMaskRevision.value = System.nanoTime()
        requestRender()
    }

    fun clearRemovalMask() {
        synchronized(removalMaskLock) {
            removalMask?.eraseColor(0)
            removalMaskNonEmpty = false
            removalOverlayCache = null
        }
        _removalMaskRevision.value = System.nanoTime()
        requestRender()
    }

    fun hasRemovalMask(): Boolean {
        val m = removalMask ?: return false
        // Quick check: any non-zero pixel?
        val w = m.width; val h = m.height
        val step = kotlin.math.max(1, (w * h / 1000))
        // Sample sparsely
        var i = 0
        val all = IntArray(w * h)
        m.getPixels(all, 0, w, 0, 0, w, h)
        while (i < all.size) {
            if ((all[i] ushr 24) > 50) return true
            i += step
        }
        return false
    }

    /**
     * Runs inpainting to remove the masked area. The pre-removal bitmap is
     * pushed to the undo stack. Calls [onDone] with success flag.
     */
    fun applyRemoval(onDone: (Boolean) -> Unit = {}) {
        // Private snapshot: the worker reads the base bitmap's pixels on
        // Dispatchers.Default while the UI thread may recycle the live
        // preview bitmap (undo / new photo). Never hand the live bitmap to
        // the worker. The snapshot doubles as the pre-removal undo backup —
        // one copy instead of two.
        val base = snapshotPreview() ?: run { onDone(false); return }
        // Work on a thread-safe copy: the live mask can be repainted on the
        // UI thread while inpainting runs.
        val mask = removalMaskCopyLocked() ?: run { base.recycle(); onDone(false); return }
        if (!hasRemovalMask()) { base.recycle(); onDone(false); return }
        if (_isRemoving.value) { base.recycle(); return }

        // Photo-change race: the inpainted result is the analyzed photo —
        // a mid-analysis photo switch must not replace the new photo's
        // preview with it, nor push the old snapshot into its undo stack.
        val tapPhotoId = _libraryImage.value?.id
        _isRemoving.value = true
        guardLaunch(Dispatchers.Default) {
            try {
                val result = Inpainting.removeObject(base, mask) { _ -> }
                withContext(Dispatchers.Main) {
                    // Stale: recycle the previous photo's result, reset the
                    // flag, and touch nothing — the new photo's preview,
                    // undo stack, and removal mask belong to it alone.
                    if (stalePhoto(tapPhotoId)) {
                        if (result != null && !result.isRecycled) result.recycle()
                        if (!base.isRecycled) base.recycle()
                        _isRemoving.value = false
                        onDone(false)
                        return@withContext
                    }
                    if (result != null && result != base) {
                        synchronized(removalUndoStack) {
                            removalUndoStack.addLast(base)
                            while (removalUndoStack.size > 5) {
                                removalUndoStack.removeFirst().recycle()
                            }
                        }
                        _preview.value = result
                        // Clear the mask after successful removal
                        synchronized(removalMaskLock) {
                            removalMask?.eraseColor(0)
                            removalMaskNonEmpty = false
                            removalOverlayCache = null
                        }
                        _removalMaskRevision.value = System.nanoTime()
                        requestRender()
                        onDone(true)
                    } else {
                        base.recycle()
                        onDone(false)
                    }
                    _isRemoving.value = false
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                base.recycle()
                withContext(Dispatchers.Main) {
                    _isRemoving.value = false
                    onDone(false)
                }
            }
        }
    }

    /** Undoes the last removal (restores pre-removal bitmap). */
    fun undoRemoval(): Boolean {
        val bmp = synchronized(removalUndoStack) {
            if (removalUndoStack.isEmpty()) null else removalUndoStack.removeLast()
        } ?: return false
        _preview.value?.recycle()
        _preview.value = bmp
        requestRender()
        return true
    }

    fun canUndoRemoval(): Boolean = synchronized(removalUndoStack) {
        removalUndoStack.isNotEmpty()
    }

    override fun onCleared() {
        maskCache.values.forEach { it.second.recycle() }
        retouchCache?.second?.recycle()
        removalOverlayCache = null
        super.onCleared()
    }
}
