package com.lumora.photoeditor.ui.preview

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.lumora.photoeditor.engine.*
import com.lumora.photoeditor.export.GlHost
import com.lumora.photoeditor.ui.components.LumoraButton
import com.lumora.photoeditor.ui.state.EditorViewModel
import com.lumora.photoeditor.ui.theme.Lumora
import com.lumora.photoeditor.ui.state.PendingFrame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class PreviewTouchMode { Zoom, Paint, Crop, Pick, FocusPoint, Straighten, Perspective, Split, CurveTarget }

/** Brush cursor position and radius in view pixels, drawn as an overlay ring.
 *  [uv] is the cursor in source-image UV (0..1), used by the loupe. */
data class BrushCursor(val xPx: Float, val yPx: Float, val radiusPx: Float, val uv: Pt)

/** Brush loupe frame: magnified bitmap of the pixels under the brush plus
 *  the cursor anchor in view pixels for positioning the overlay. */
data class LoupeFrame(val bitmap: Bitmap, val xPx: Float, val yPx: Float, val radiusPx: Float)

/** Loupe render target in px (square). 288px ≈ 330KB ARGB — cheap enough to
 *  re-render throttled while the finger moves. */
private const val LOUPE_SIZE_PX = 288

/** Best-effort bitmap recycle; never throws. */
private fun recycleQuietly(bmp: Bitmap) {
    runCatching { if (!bmp.isRecycled) bmp.recycle() }
}

/** One retouch spot mapped to view pixels, drawn as a pin marker ring. */
data class SpotMarker(val xPx: Float, val yPx: Float, val radiusPx: Float)

private enum class CropHandle { None, TL, TR, BL, BR, L, T, R, B, Move }

private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 8f

/**
 * GPU-accelerated photo preview. Owns the [GlEngine] on its GL thread and
 * renders [PendingFrame]s produced by the [EditorViewModel].
 *
 * Touch: pinch-zoom + pan + double-tap zoom cycle in [PreviewTouchMode.Zoom];
 * in [PreviewTouchMode.Paint] touches are converted to image-UV stroke
 * segments for mask/retouch painting.
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun EditorGlView(
    vm: EditorViewModel,
    touchMode: PreviewTouchMode,
    onPaintStart: (startUv: Pt) -> Unit = {},
    onPaintSegment: (fromUv: Pt, toUv: Pt) -> Unit = { _, _ -> },
    onPaintEnd: () -> Unit = {},
    onCropStart: () -> Unit = {},
    onCropDrag: (rect: CropParams) -> Unit = {},
    onCropEnd: () -> Unit = {},
    /** Fired (on the main thread) with the averaged 0..1 RGB of a small patch
     *  around the tap point while in [PreviewTouchMode.Pick]. */
    onPickColor: (r: Float, g: Float, b: Float) -> Unit = { _, _, _ -> },
    /** Fired with the two tapped points (source UV) when the user finishes a
     *  guided-straighten line while in [PreviewTouchMode.Straighten]. */
    onStraighten: (p1: Pt, p2: Pt) -> Unit = { _, _ -> },
    /** Fired with the two tapped points (source UV) when the user finishes a
     *  guided-perspective line while in [PreviewTouchMode.Perspective]. */
    onPerspective: (p1: Pt, p2: Pt) -> Unit = { _, _ -> },
    /** Focus gesture callbacks (fired on the main thread) while in
     *  [PreviewTouchMode.FocusPoint]: the user taps/drags to place the
     *  lens-blur focus point on the photo. */
    onFocusStart: () -> Unit = {},
    onFocusDrag: (uv: Pt) -> Unit = {},
    onFocusEnd: () -> Unit = {},
    /** Targeted-adjustment callbacks (fired on the main thread) while in
     *  [PreviewTouchMode.CurveTarget]: DOWN samples the tone under the
     *  finger ([onCurveSample] carries the averaged patch RGB, like
     *  [onPickColor]); vertical drag reports total finger travel in px
     *  since DOWN ([onCurveDrag], +down) plus the view height for
     *  normalization; UP/CANCEL ends the gesture. */
    onCurveSample: (r: Float, g: Float, b: Float) -> Unit = { _, _, _ -> },
    onCurveDrag: (dyPx: Float, heightPx: Float) -> Unit = { _, _ -> },
    onCurveEnd: () -> Unit = {},
    onGlHost: (GlHost) -> Unit = {},
    /** Brush radius in UV units (fraction of image width) for the on-preview
     *  cursor ring; null hides the ring. */
    cursorRadiusUv: Float? = null,
    /** Fired with the divider's NDC x (-1..1) while the user drags the
     *  before/after split divider in [PreviewTouchMode.Split]. */
    onSplitDrag: (ndcX: Float) -> Unit = {},
    /** NDC x of the split divider for the overlay; null hides it. */
    splitXNdc: Float? = null,
    /** Retouch spots in image UV to draw as pin markers (Lightroom parity);
     *  null hides them. */
    retouchSpotUvs: List<RetouchSpot>? = null,
    /** Clone-tool source pick in image UV (null = none picked / tool not
     *  active); drawn as a distinct amber pin so the user can see exactly
     *  where the clone texture is sampled from. */
    cloneSourceUv: Pt? = null,
    /** Clone brush diameter in image-width fraction (same units as
     *  RetouchSpot.size), used to size the source pin. */
    cloneSourceDiameterUv: Float? = null,
    /** Crop overlay grid style (AppSettings CROP_OVERLAY_*): which
     *  composition guide the crop overlay draws. */
    cropOverlayStyle: Int = 0,
    /** Fired (on the main thread) with true when the crop quad has any
     *  corner outside the photo — those corners render black in
     *  preview/export. The host uses it to surface the warning in the
     *  tool hint chip. */
    onCropOob: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val frame by vm.pendingFrame.collectAsState()
    val previewBitmap by vm.previewBitmap.collectAsState()
    // Black-frame watchdog (P0, 2026-10-17): trips when the GPU renders
    // near-black frames for a bright photo — the UI then shows this same
    // software fallback instead of a dead black screen. Retry is manual so
    // a persistently broken GPU keeps the working fallback, never a loop.
    val watchdogMsg by vm.previewWatchdog.collectAsState()
    var glFailed by remember { mutableStateOf<String?>(null) }
    var cursor by remember { mutableStateOf<BrushCursor?>(null) }
    /** Brush loupe frame (magnified pixels under the brush), or null when
     *  hidden. The bitmap is owned here: recycled on replace/dispose. */
    var loupe by remember { mutableStateOf<LoupeFrame?>(null) }
    /** Retouch spot pins in view pixels, drawn as marker rings. */
    var spotMarkers by remember { mutableStateOf<List<SpotMarker>?>(null) }
    /** Clone-tool source pin in view pixels: amber ring + crosshair, so the
     *  clone source is visually distinct from the white retouch-spot pins. */
    var cloneMarker by remember { mutableStateOf<SpotMarker?>(null) }
    /** Crop quad corners in view pixels (TL, TR, BL, BR), drawn as the
     *  crop overlay (frame + composition guide) while in crop mode. */
    var cropCorners by remember { mutableStateOf<List<Offset>?>(null) }
    /** Per-corner out-of-bounds flags (TL, TR, BL, BR — same order as
     *  [cropCorners]); true where the rotated/perspectived quad leaves the
     *  photo. Those corners render black in preview and export, so the
     *  overlay marks them with an amber warning. */
    var cropOob by remember { mutableStateOf<List<Boolean>?>(null) }
    /** Full photo quad in view pixels (TL, TR, BL, BR), drawn as the
     *  alignment grid (thirds + center cross) while in guided-straighten
     *  mode so the user can judge level before drawing the guide line. */
    var alignGrid by remember { mutableStateOf<List<Offset>?>(null) }
    /** Lens-blur focus point in view pixels, drawn as a crosshair marker
     *  while the user places it on the photo. */
    var focusMarker by remember { mutableStateOf<Offset?>(null) }
    /** Guided-straighten tap points in view pixels: one dot after the first
     *  tap, a line after the second. */
    var straightenPts by remember { mutableStateOf<List<Offset>?>(null) }
    /** Same for the guided-perspective two-tap line. */
    var perspectivePts by remember { mutableStateOf<List<Offset>?>(null) }
    val glHost = remember {
        object : GlHost {
            var view: GLView? = null
            override fun queueGl(task: () -> Unit) {
                view?.queueEvent(task)
            }
        }
    }
    // Pause/resume the GLSurfaceView render thread with the host lifecycle.
    // Without this the GL thread kept rendering after the user backgrounded
    // the app (battery drain) and could touch a destroyed surface on return
    // (EGL_BAD_SURFACE spam / occasional crash on Mali GPUs).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(glHost, lifecycleOwner) {
        onGlHost(glHost)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> glHost.view?.onPause()
                Lifecycle.Event.ON_RESUME -> glHost.view?.onResume()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // Stop the render thread when the editor leaves the composition
            // (e.g. back to the library) so it cannot outlive its surface.
            glHost.view?.onPause()
        }
    }

    // Recycle the loupe bitmap when the preview leaves the composition.
    // (Replacements are recycled in the onLoupe callback below.)
    val loupeRef = rememberUpdatedState(loupe)
    DisposableEffect(Unit) {
        onDispose {
            loupeRef.value?.bitmap?.let(::recycleQuietly)
        }
    }
    // Software fallback if GL initialization fails (prevents crash), or if
    // the black-frame watchdog tripped (GPU renders black: show the unedited
    // photo instead of a dead screen; the user's edits are safe).
    val fallbackMsg = glFailed?.let { "GPU preview unavailable: $it" }
        ?: watchdogMsg?.let {
            "Preview watchdog: the GPU frame went black, so you're seeing " +
                "the unedited photo. Your edits are safe.\n$it"
        }
    if (fallbackMsg != null) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Lumora.colors.background),
            contentAlignment = Alignment.Center,
        ) {
            val bmp = previewBitmap
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "Photo preview",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            }
            // Always show the reason so it can be diagnosed. Design-system
            // chip (caption + danger on a scrim) so it stays readable over
            // any photo — or centered on the empty background when no frame
            // ever rendered.
            Column(
                modifier = Modifier
                    .align(if (bmp != null) Alignment.BottomCenter else Alignment.Center)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = fallbackMsg,
                    style = Lumora.type.caption,
                    color = Lumora.colors.danger,
                    modifier = Modifier
                        .background(Lumora.colors.scrim, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
                if (watchdogMsg != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    LumoraButton(
                        onClick = { vm.retryGpuPreview() },
                    ) {
                        Text("Retry GPU preview")
                    }
                }
            }
        }
        return
    }

    Box(modifier) {
        AndroidView(
            modifier = modifier.fillMaxSize(),
            factory = { ctx ->
                GLView(ctx).also { view ->
                    glHost.view = view
                    view.setVm(vm)
                    vm.glEngineRef = view.engine
                    view.onGlError = { err -> glFailed = err }
                }
            },
            update = { view ->
                if (view.touchMode != touchMode) {
                    view.touchMode = touchMode
                    // zoom/pan would confuse crop-handle mapping; reset on entry
                    if (touchMode == PreviewTouchMode.Crop) view.resetZoom()
                }
                // Robust view-size sync: AndroidView's GLSurfaceView can resize
                // without onSurfaceChanged firing promptly when the bottom panel
                // changes height (e.g. entering Presets). If the View's actual
                // size differs from what the ViewModel last used for uvMatrix,
                // push the correct size now. This fixes the "tiny image stuck
                // in corner" bug seen when switching tools.
                val vw = view.width
                val vh = view.height
                if (vw > 0 && vh > 0) {
                    vm.onViewSize(vw, vh)
                }
                view.onPaintStart = onPaintStart
                view.onPaintSegment = onPaintSegment
                view.onPaintEnd = onPaintEnd
                view.onCropStart = onCropStart
                view.onCropDrag = onCropDrag
                view.onCropEnd = onCropEnd
                view.onPickColor = onPickColor
                view.onStraighten = onStraighten
                view.onStraightenOverlay = { straightenPts = it }
                view.onPerspective = onPerspective
                view.onPerspectiveOverlay = { perspectivePts = it }
                if (touchMode != PreviewTouchMode.Straighten) {
                    straightenPts = null
                    view.straightenP1 = null
                }
                if (touchMode != PreviewTouchMode.Perspective) {
                    perspectivePts = null
                    view.perspectiveP1 = null
                }
                view.cursorRadiusUv = cursorRadiusUv ?: 0f
                view.onCursor = { cursor = it }
                // Brush loupe: accept frames only in paint mode; the bitmap
                // is owned here and recycled on replace/reject/dispose.
                view.loupeEnabled = touchMode == PreviewTouchMode.Paint
                view.onLoupe = { f ->
                    val accepted = if (touchMode == PreviewTouchMode.Paint) f else null
                    val old = loupe
                    loupe = accepted
                    if (old !== accepted) old?.bitmap?.let(::recycleQuietly)
                    if (accepted == null && f !== old) f?.bitmap?.let(::recycleQuietly)
                }
                if (cursorRadiusUv == null || touchMode != PreviewTouchMode.Paint) cursor = null
                view.onRetouchSpots = { spotMarkers = it }
                view.retouchSpotUvs = retouchSpotUvs
                view.reportRetouchSpots()
                view.onCloneSource = { cloneMarker = it }
                view.cloneSourceUv = cloneSourceUv
                view.cloneSourceDiameterUv = cloneSourceDiameterUv ?: 0f
                view.reportCloneSource()
                view.cropOverlayEnabled = touchMode == PreviewTouchMode.Crop
                view.onCropOverlay = { cropCorners = it }
                view.onCropOverlayBounds = { flags ->
                    cropOob = flags
                    onCropOob(flags?.any { it } == true)
                }
                if (touchMode != PreviewTouchMode.Crop && (cropCorners != null || cropOob != null)) {
                    cropCorners = null
                    cropOob = null
                    onCropOob(false)
                }
                view.alignGridEnabled = touchMode == PreviewTouchMode.Straighten
                view.onAlignGrid = { alignGrid = it }
                if (touchMode != PreviewTouchMode.Straighten) alignGrid = null
                view.reportAlignGrid()
                view.onFocusStart = onFocusStart
                view.onFocusDrag = onFocusDrag
                view.onFocusEnd = onFocusEnd
                view.onCurveSample = onCurveSample
                view.onCurveDrag = onCurveDrag
                view.onCurveEnd = onCurveEnd
                view.focusMarkerEnabled = touchMode == PreviewTouchMode.FocusPoint
                view.onFocusMarker = { focusMarker = it }
                if (touchMode != PreviewTouchMode.FocusPoint) focusMarker = null
                view.reportFocusMarker()
                view.onSplitDrag = onSplitDrag
                view.setPendingFrame(frame)
            },
        )
        // Brush-size cursor ring: white ring with a dark halo so it stays
        // visible on any photo, plus a center dot marking the touch point.
        cursor?.let { c ->
            Canvas(Modifier.fillMaxSize()) {
                val center = Offset(c.xPx, c.yPx)
                drawCircle(
                    color = Color.Black.copy(alpha = 0.55f),
                    center = center,
                    radius = c.radiusPx + 2f,
                    style = Stroke(width = 4f),
                )
                drawCircle(
                    color = Color.White,
                    center = center,
                    radius = c.radiusPx,
                    style = Stroke(width = 2f),
                )
                drawCircle(color = Color.White, radius = 3f, center = center)
            }
        }
        // Brush loupe: circular magnifier of the pixels under the brush,
        // anchored above the finger so the finger never covers it. The bitmap
        // is rendered through the full pipeline on the GL thread, so it
        // shows exactly what the preview shows (edits + in-progress stroke).
        loupe?.let { l ->
            val bmp = l.bitmap
            if (bmp.isRecycled) return@let
            Canvas(Modifier.fillMaxSize()) {
                val dPx = 112.dp.toPx()
                val r = dPx / 2f
                val cxp = l.xPx.coerceIn(r, (size.width - r).coerceAtLeast(r))
                val cyp = (l.yPx - l.radiusPx - r - 20.dp.toPx())
                    .coerceIn(r, (size.height - r).coerceAtLeast(r))
                val img = bmp.asImageBitmap()
                val oval = Path().apply {
                    addOval(Rect(Offset(cxp - r, cyp - r), Size(dPx, dPx)))
                }
                clipPath(oval) {
                    drawImage(
                        image = img,
                        dstOffset = IntOffset((cxp - r).roundToInt(), (cyp - r).roundToInt()),
                        dstSize = IntSize(dPx.roundToInt(), dPx.roundToInt()),
                    )
                }
                // ring: dark halo + white, matching the cursor-ring language
                drawCircle(
                    color = Color.Black.copy(alpha = 0.55f),
                    center = Offset(cxp, cyp), radius = r + 2f,
                    style = Stroke(width = 7f),
                )
                drawCircle(
                    color = Color.White, center = Offset(cxp, cyp), radius = r,
                    style = Stroke(width = 3f),
                )
                // crosshair at the exact brush center
                val ch = r * 0.30f
                drawLine(Color.White, Offset(cxp - ch, cyp), Offset(cxp + ch, cyp),
                    strokeWidth = 2f)
                drawLine(Color.White, Offset(cxp, cyp - ch), Offset(cxp, cyp + ch),
                    strokeWidth = 2f)
            }
        }
        // Retouch spot pins: a ring per spot at its true on-screen size,
        // white with a dark halo so it reads on any photo, plus a center
        // dot. This is what makes the "Erase spot" mode usable — the spots
        // are visible targets instead of blind guesses.
        spotMarkers?.let { markers ->
            Canvas(Modifier.fillMaxSize()) {
                markers.forEach { m ->
                    val center = Offset(m.xPx, m.yPx)
                    val r = m.radiusPx.coerceAtLeast(5f)
                    drawCircle(
                        color = Color.Black.copy(alpha = 0.55f),
                        center = center, radius = r + 2f, style = Stroke(width = 4f),
                    )
                    drawCircle(
                        color = Color.White.copy(alpha = 0.9f),
                        center = center, radius = r, style = Stroke(width = 2f),
                    )
                    drawCircle(color = Color.White.copy(alpha = 0.9f), radius = 2.5f, center = center)
                }
            }
        }
        // Clone-source pin: amber ring + crosshair at the picked source
        // point, sized to the current clone brush so the user sees exactly
        // which area the clone texture is sampled from. Deliberately amber
        // (not white) so it can't be confused with retouch-spot pins.
        cloneMarker?.let { m ->
            Canvas(Modifier.fillMaxSize()) {
                val center = Offset(m.xPx, m.yPx)
                val r = m.radiusPx.coerceAtLeast(8f)
                val amber = Color(0xFFFFB300)
                drawCircle(
                    color = Color.Black.copy(alpha = 0.55f),
                    center = center, radius = r + 2f, style = Stroke(width = 4f),
                )
                drawCircle(
                    color = amber,
                    center = center, radius = r, style = Stroke(width = 2.5f),
                )
                val c = r * 0.45f
                drawLine(
                    color = amber,
                    start = Offset(center.x - c, center.y),
                    end = Offset(center.x + c, center.y),
                    strokeWidth = 2.5f,
                )
                drawLine(
                    color = amber,
                    start = Offset(center.x, center.y - c),
                    end = Offset(center.x, center.y + c),
                    strokeWidth = 2.5f,
                )
            }
        }
        // Lens-blur focus marker: white crosshair + center dot at the focus
        // point while the user places it, so the drag target is visible
        // (like the radial-mask handle). White with a dark halo so it reads
        // on any photo.
        focusMarker?.let { m ->
            Canvas(Modifier.fillMaxSize()) {
                val r = 14f
                drawCircle(
                    color = Color.Black.copy(alpha = 0.55f),
                    radius = r + 2f, center = m, style = Stroke(width = 4f),
                )
                drawCircle(
                    color = Color.White, radius = r, center = m,
                    style = Stroke(width = 2f),
                )
                val c = r * 0.55f
                drawLine(Color.White, Offset(m.x - c, m.y), Offset(m.x + c, m.y), strokeWidth = 2f)
                drawLine(Color.White, Offset(m.x, m.y - c), Offset(m.x, m.y + c), strokeWidth = 2f)
                drawCircle(color = Color.White, radius = 3f, center = m)
            }
        }
        // Crop overlay: dimmed area outside the crop quad, composition
        // guide (thirds/grid/diagonal/golden), white frame and corner
        // ticks — Lightroom/Snapseed style.
        // q order: TL, TR, BL, BR (matches Geometry.transformedQuad).
        cropCorners?.let { q ->
            Canvas(Modifier.fillMaxSize()) {
                fun lerp(a: Offset, b: Offset, t: Float) =
                    Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
                // Out-of-bounds warning: with "Constrain to image" off,
                // rotation/perspective can push the quad past the photo
                // edges; those corners render black in preview and export.
                // The frame turns amber and each offending corner gets a
                // warning triangle so the problem is visible before export.
                val oob = cropOob?.takeIf { it.size == 4 } ?: List(4) { false }
                val anyOob = oob.any { it }
                val warn = Color(0xFFFFB300)
                val frameColor = if (anyOob) warn else Color.White
                // dim everything outside the quad
                val dim = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(Rect(Offset.Zero, size))
                    moveTo(q[0].x, q[0].y)
                    lineTo(q[1].x, q[1].y)
                    lineTo(q[3].x, q[3].y)
                    lineTo(q[2].x, q[2].y)
                    close()
                }
                drawPath(dim, Color.Black.copy(alpha = 0.45f))
                // Composition guide: interpolate along the quad's edges so
                // it stays correct when the crop is rotated/perspectived.
                // q order: TL, TR, BL, BR.
                val grid = Color.White.copy(alpha = 0.75f)
                fun vLine(t: Float) =
                    drawLine(grid, lerp(q[0], q[1], t), lerp(q[2], q[3], t), strokeWidth = 1f)
                fun hLine(t: Float) =
                    drawLine(grid, lerp(q[0], q[2], t), lerp(q[1], q[3], t), strokeWidth = 1f)
                when (cropOverlayStyle) {
                    1 -> { // Grid: quarters
                        listOf(0.25f, 0.5f, 0.75f).forEach { t -> vLine(t); hLine(t) }
                    }
                    2 -> { // Diagonal: both diagonals
                        drawLine(grid, q[0], q[3], strokeWidth = 1f)
                        drawLine(grid, q[1], q[2], strokeWidth = 1f)
                    }
                    3 -> { // Golden ratio: 1/phi and 1 - 1/phi
                        listOf(0.381966f, 0.618034f).forEach { t -> vLine(t); hLine(t) }
                    }
                    else -> { // Thirds (default)
                        listOf(1f / 3f, 2f / 3f).forEach { t -> vLine(t); hLine(t) }
                    }
                }
                // frame: dark halo then white
                val framePath = Path().apply {
                    moveTo(q[0].x, q[0].y)
                    lineTo(q[1].x, q[1].y)
                    lineTo(q[3].x, q[3].y)
                    lineTo(q[2].x, q[2].y)
                    close()
                }
                drawPath(framePath, Color.Black.copy(alpha = 0.55f), style = Stroke(5f))
                drawPath(framePath, frameColor, style = Stroke(2f))
                // corner ticks: short L segments from each corner along
                // both edges, white (amber on out-of-bounds corners) with
                // a dark halo
                fun tick(from: Offset, toward: Offset, color: Color) {
                    val dx = toward.x - from.x; val dy = toward.y - from.y
                    val len = hypot(dx, dy)
                    if (len < 1f) return
                    val end = Offset(from.x + dx / len * 30f, from.y + dy / len * 30f)
                    drawLine(Color.Black.copy(alpha = 0.55f), from, end, strokeWidth = 7f)
                    drawLine(color, from, end, strokeWidth = 4f)
                }
                fun tickColor(i: Int) = if (oob[i]) warn else Color.White
                tick(q[0], q[1], tickColor(0)); tick(q[0], q[2], tickColor(0))
                tick(q[1], q[0], tickColor(1)); tick(q[1], q[3], tickColor(1))
                tick(q[2], q[0], tickColor(2)); tick(q[2], q[3], tickColor(2))
                tick(q[3], q[1], tickColor(3)); tick(q[3], q[2], tickColor(3))
                // warning triangles on out-of-bounds corners, set slightly
                // inside the quad so they stay visible at the view edge
                if (anyOob) {
                    val center = Offset(
                        (q[0].x + q[1].x + q[2].x + q[3].x) / 4f,
                        (q[0].y + q[1].y + q[2].y + q[3].y) / 4f,
                    )
                    oob.forEachIndexed { i, bad ->
                        if (!bad) return@forEachIndexed
                        val d = center - q[i]
                        val len = d.getDistance()
                        val pos = if (len > 1f) q[i] + d / len * 44f else q[i]
                        val s = 22f
                        val tri = Path().apply {
                            moveTo(pos.x, pos.y - s)
                            lineTo(pos.x - s * 0.9f, pos.y + s * 0.65f)
                            lineTo(pos.x + s * 0.9f, pos.y + s * 0.65f)
                            close()
                        }
                        drawPath(tri, Color.Black.copy(alpha = 0.55f), style = Stroke(8f))
                        drawPath(tri, warn)
                    }
                }
            }
        }
        // Straighten alignment grid: rule-of-thirds + a slightly stronger
        // center cross over the displayed photo while the user picks the
        // two guide points — the Lightroom-while-rotating treatment. q
        // order: TL, TR, BL, BR (same as the crop overlay; grid lines
        // interpolate along the quad edges so they stay correct under
        // rotation/perspective). Deliberately NO dimming here: the photo
        // must stay fully visible to judge its lines, so the grid is thin
        // white with a dark halo instead.
        if (touchMode == PreviewTouchMode.Straighten) {
            alignGrid?.let { q ->
                Canvas(Modifier.fillMaxSize()) {
                    fun lerp(a: Offset, b: Offset, t: Float) =
                        Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
                    val halo = Color.Black.copy(alpha = 0.45f)
                    val thirds = Color.White.copy(alpha = 0.7f)
                    listOf(1f / 3f, 2f / 3f).forEach { t ->
                        listOf(
                            lerp(q[0], q[1], t) to lerp(q[2], q[3], t),
                            lerp(q[0], q[2], t) to lerp(q[1], q[3], t),
                        ).forEach { (a, b) ->
                            drawLine(halo, a, b, strokeWidth = 3f)
                            drawLine(thirds, a, b, strokeWidth = 1.5f)
                        }
                    }
                    // center cross: the primary leveling reference
                    val cross = Color.White.copy(alpha = 0.9f)
                    listOf(
                        lerp(q[0], q[1], 0.5f) to lerp(q[2], q[3], 0.5f),
                        lerp(q[0], q[2], 0.5f) to lerp(q[1], q[3], 0.5f),
                    ).forEach { (a, b) ->
                        drawLine(halo, a, b, strokeWidth = 3.5f)
                        drawLine(cross, a, b, strokeWidth = 2f)
                    }
                }
            }
        }
        // Guided-line overlay (straighten or perspective): a dot on the first
        // tap, then the line the user drew. White with a dark halo so it
        // reads on any photo. The inactive mode's points are always null
        // (cleared above when the mode is exited), so the coalesced value
        // shows exactly the active guide.
        (straightenPts ?: perspectivePts)?.let { pts ->
            Canvas(Modifier.fillMaxSize()) {
                if (pts.size == 2) {
                    drawLine(Color.Black.copy(alpha = 0.55f), pts[0], pts[1], strokeWidth = 5f)
                    drawLine(Color.White, pts[0], pts[1], strokeWidth = 2.5f)
                }
                pts.forEach { p ->
                    drawCircle(Color.Black.copy(alpha = 0.55f), radius = 10f, center = p)
                    drawCircle(Color.White, radius = 6f, center = p)
                }
            }
        }
        // Before/after split divider: vertical line with a grab handle,
        // white with a dark halo so it reads on any photo. Drawn only while
        // the preview is in split-drag mode.
        if (touchMode == PreviewTouchMode.Split) {
            splitXNdc?.let { sx ->
                Canvas(Modifier.fillMaxSize()) {
                    val x = (sx * 0.5f + 0.5f) * size.width
                    drawLine(
                        Color.Black.copy(alpha = 0.55f),
                        Offset(x, 0f), Offset(x, size.height), strokeWidth = 5f,
                    )
                    drawLine(
                        Color.White,
                        Offset(x, 0f), Offset(x, size.height), strokeWidth = 2f,
                    )
                    val c = Offset(x, size.height / 2f)
                    drawCircle(Color.Black.copy(alpha = 0.55f), radius = 30f, center = c)
                    drawCircle(Color.White, radius = 24f, center = c)
                    // left/right chevrons inside the handle
                    val s = 9f
                    listOf(-1f, 1f).forEach { dir ->
                        val bx = c.x + dir * 4f
                        drawLine(
                            Color.Black,
                            Offset(bx + dir * -s * 0.5f, c.y - s),
                            Offset(bx + dir * s * 0.5f, c.y),
                            strokeWidth = 5f,
                        )
                        drawLine(
                            Color.Black,
                            Offset(bx + dir * s * 0.5f, c.y),
                            Offset(bx + dir * -s * 0.5f, c.y + s),
                            strokeWidth = 5f,
                        )
                    }
                }
            }
        }
    }
}

private class GLView(context: Context) : GLSurfaceView(context) {

    val engine = GlEngine()
    /** Set when GL initialization fails; UI can fall back to software rendering. */
    @Volatile var glError: String? = null
        private set
    /** Called on the GL thread when a fatal GL error occurs. */
    var onGlError: (String) -> Unit = {}
    private fun setGlError(msg: String) {
        if (glError == null) {
            glError = msg
            onGlError(msg)
        }
    }
    var touchMode: PreviewTouchMode = PreviewTouchMode.Zoom
    var onPaintStart: (Pt) -> Unit = {}
    var onPaintSegment: (Pt, Pt) -> Unit = { _, _ -> }
    /** Fired with the split divider's NDC x while in [PreviewTouchMode.Split]. */
    var onSplitDrag: (Float) -> Unit = {}
    var onPaintEnd: () -> Unit = {}
    var onCropStart: () -> Unit = {}
    var onCropDrag: (CropParams) -> Unit = {}
    var onCropEnd: () -> Unit = {}
    /** Called on the UI thread with the averaged 0..1 RGB of a small patch
     *  around the tap point while in [PreviewTouchMode.Pick]. */
    var onPickColor: (r: Float, g: Float, b: Float) -> Unit = { _, _, _ -> }
    /** Brush radius in UV units (fraction of image width); <= 0 disables the cursor ring. */
    @Volatile var cursorRadiusUv: Float = 0f
    /** Called on the UI thread with the brush cursor in view pixels, or null
     *  when the finger lifts / leaves the image. */
    var onCursor: (BrushCursor?) -> Unit = {}
    /** Brush loupe: a magnified bitmap of the pixels under the brush,
     *  anchored at the cursor's view position. Posted on the UI thread;
     *  null hides the loupe. Enabled only in paint mode via [loupeEnabled]. */
    var onLoupe: (LoupeFrame?) -> Unit = {}
    @Volatile var loupeEnabled: Boolean = false
    private var lastLoupeMs: Long = 0L
    private var lastLoupeUv: Pt? = null
    /** Retouch spots in image UV to draw as pin markers; null hides the pins. */
    var retouchSpotUvs: List<RetouchSpot>? = null
    /** Called on the UI thread with the pin positions in view pixels. */
    var onRetouchSpots: (List<SpotMarker>?) -> Unit = {}
    private var lastSpotsKey: Int? = null
    /** Clone-tool source pick in image UV; null hides the source pin. */
    var cloneSourceUv: Pt? = null
    /** Clone brush diameter in image-width fraction (same units as
     *  RetouchSpot.size); <= 0 hides the source pin. */
    var cloneSourceDiameterUv: Float = 0f
    /** Called on the UI thread with the clone-source pin in view pixels. */
    var onCloneSource: (SpotMarker?) -> Unit = {}
    private var lastCloneKey: Int? = null
    /** True while in crop mode: [onCropOverlay] reports the crop quad. */
    @Volatile var cropOverlayEnabled = false
    /** Called on the UI thread with the crop quad corners in view pixels
     *  (TL, TR, BL, BR), or null when the overlay should hide. */
    var onCropOverlay: (List<Offset>?) -> Unit = {}
    /** Called on the UI thread with per-corner out-of-bounds flags for the
     *  crop quad (TL, TR, BL, BR; null when the overlay is off). */
    var onCropOverlayBounds: (List<Boolean>?) -> Unit = {}
    /** True while in guided-straighten mode: [onAlignGrid] reports the
     *  displayed photo quad so the composable can draw the leveling grid. */
    @Volatile var alignGridEnabled = false
    /** Called on the UI thread with the displayed photo quad corners in
     *  view pixels (TL, TR, BL, BR), or null when the grid should hide. */
    var onAlignGrid: (List<Offset>?) -> Unit = {}
    /** True while placing the lens-blur focus point: [onFocusMarker]
     *  reports the focus point's view position. */
    @Volatile var focusMarkerEnabled = false
    /** Called on the UI thread with the lens-blur focus point in view
     *  pixels, or null when the marker should hide. */
    var onFocusMarker: (Offset?) -> Unit = {}
    /** Focus gesture callbacks (touch thread) while in
     *  [PreviewTouchMode.FocusPoint]. */
    var onFocusStart: () -> Unit = {}
    var onFocusDrag: (Pt) -> Unit = {}
    var onFocusEnd: () -> Unit = {}
    /** Tone-curve targeted adjustment (touch thread only for the drag
     *  state; callbacks fire on the main thread). */
    var onCurveSample: (r: Float, g: Float, b: Float) -> Unit = { _, _, _ -> }
    var onCurveDrag: (dyPx: Float, heightPx: Float) -> Unit = { _, _ -> }
    var onCurveEnd: () -> Unit = {}
    private var curveDragging = false
    private var curveDownY = 0f

    private var vm: EditorViewModel? = null
    @Volatile private var pending: PendingFrame? = null

    // zoom state (NDC units)
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private var focusX = 0f
    private var focusY = 0f

    private var lastFrameId = -1L
    private var lastCurvesRev = -1L
    private var lastMasksRev = Long.MIN_VALUE
    private var lastRetouchRev = Long.MIN_VALUE
    private var lastAssetsToken = Long.MIN_VALUE
    // AI Pro Retouch LUT identity last uploaded to the engine (null = no
    // grade). The fused LUT is engine-local GPU state like the curves/mask/
    // retouch textures, so it is re-uploaded on change or invalidation —
    // otherwise a grade saved in the sidecar silently rendered as "no grade"
    // after photo reopen, surface recreation, or the self-test, while the
    // panel still showed it as applied.
    // Allocation-free revision tracking (2026-10-18): the old single
    // `weights + wbGains` concat key built a boxed-Float ArrayList on the GL
    // thread every frame. The two component lists are compared structurally
    // instead — same trigger semantics, zero allocation. Null/empty wbGains
    // are normalized to null ("no gains") so a null<->empty flip never
    // triggers a spurious re-upload.
    private var lastAiWeights: List<Float>? = null
    private var lastAiWbGains: List<Float>? = null
    // Look transfer LUT identity last uploaded (null = no match). Same
    // re-upload contract as the AI grade: photo reopen, surface recreation,
    // self-test, or asset invalidation must not silently drop the match
    // while the Color panel still shows it as applied. Same allocation-free
    // component comparison as the AI grade above (the old
    // `srcStats + refStats` concat allocated per frame).
    private var lastLookSrc: List<Float>? = null
    private var lastLookRef: List<Float>? = null
    private var lastSource: Any? = null
    private var lastHistMs = 0L
    private var readBuf: ByteBuffer? = null
    /**
     * Black-frame watchdog flag (P0 2026-10-17): set when a new source
     * bitmap is uploaded; the next completed render samples the
     * framebuffer and falls back to software preview if it's all black.
     */
    private var watchdogPending = false

    // white-balance pick state (touch thread only)
    private var pickDownX = 0f
    private var pickDownY = 0f
    private var pickMoved = false
    // lens-blur focus placement state (touch thread only)
    private var focusDragging = false
    /** State for a guided two-tap line (touch thread only). The straighten
     *  and perspective guides share the exact same tap interaction, so they
     *  each own a slot; the composable addresses the public delegating vars
     *  below to clear a half-finished line when the mode is exited. */
    private class GuideSlot {
        var p1: Pt? = null
        var onGuide: (p1: Pt, p2: Pt) -> Unit = { _, _ -> }
        var onOverlay: (pts: List<Offset>?) -> Unit = {}
        var downX = 0f
        var downY = 0f
        var moved = false
    }
    private val straightenSlot = GuideSlot()
    private val perspectiveSlot = GuideSlot()
    var straightenP1: Pt?
        get() = straightenSlot.p1
        set(v) { straightenSlot.p1 = v }
    var onStraighten: (p1: Pt, p2: Pt) -> Unit
        get() = straightenSlot.onGuide
        set(v) { straightenSlot.onGuide = v }
    var onStraightenOverlay: (pts: List<Offset>?) -> Unit
        get() = straightenSlot.onOverlay
        set(v) { straightenSlot.onOverlay = v }
    var perspectiveP1: Pt?
        get() = perspectiveSlot.p1
        set(v) { perspectiveSlot.p1 = v }
    var onPerspective: (p1: Pt, p2: Pt) -> Unit
        get() = perspectiveSlot.onGuide
        set(v) { perspectiveSlot.onGuide = v }
    var onPerspectiveOverlay: (pts: List<Offset>?) -> Unit
        get() = perspectiveSlot.onOverlay
        set(v) { perspectiveSlot.onOverlay = v }
    private val touchSlopPx = ViewConfiguration.get(context).scaledTouchSlop

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                focusX = detector.focusX / width * 2f - 1f
                focusY = 1f - detector.focusY / height * 2f
                return true
            }
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val prev = zoom
                zoom = (zoom * detector.scaleFactor).coerceIn(MIN_ZOOM, MAX_ZOOM)
                // keep focus point stable: adjust pan
                val f = 1f / prev - 1f / zoom
                panX += focusX * f
                panY += focusY * f
                if (zoom <= 1.01f) { zoom = 1f; panX = 0f; panY = 0f }
                clampPan()
                applyZoom()
                return true
            }
        })

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                zoom = when {
                    zoom < 1.5f -> 2.5f
                    zoom < 4f -> 5f
                    else -> 1f
                }
                if (zoom == 1f) { panX = 0f; panY = 0f }
                else {
                    focusX = e.x / width * 2f - 1f
                    focusY = 1f - e.y / height * 2f
                }
                clampPan()
                applyZoom()
                return true
            }
        })

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var painting = false
    private var lastPaintUv: Pt? = null

    // crop-drag state
    private var cropping = false
    private var cropHandle = CropHandle.None
    private var cropStart: CropParams? = null
    private var cropStartUv = Pt(0f, 0f)

    init {
        setEGLContextClientVersion(3)
        setRenderer(Renderer())
        renderMode = RENDERMODE_WHEN_DIRTY
        setOnTouchListener { _, event ->
            handleTouch(event)
            true
        }
    }

    fun setVm(vm: EditorViewModel) { this.vm = vm }

    /** The View's onSizeChanged fires reliably on the UI thread whenever the
     *  layout size changes (e.g. bottom panel height changes when switching
     *  tools). This is more dependable than GLSurfaceView.onSurfaceChanged
     *  for keeping the ViewModel's viewW/viewH in sync, fixing the "tiny
     *  image stuck in corner" bug. */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0 && (w != oldw || h != oldh)) {
            vm?.onViewSize(w, h)
        }
    }

    /** Reports a guided line's tap points (view pixels) for the overlay:
     *  one dot after the first tap, both endpoints once the pair is
     *  complete, null when there is no partial line. */
    private fun reportGuideOverlay(slot: GuideSlot, mode: PreviewTouchMode) {
        val p1 = slot.p1
        if (touchMode != mode || p1 == null) {
            slot.onOverlay(null)
            return
        }
        val v1 = uvToViewPx(p1)
        slot.onOverlay(if (v1 == null) null else listOf(Offset(v1.x, v1.y)))
    }

    fun setPendingFrame(frame: PendingFrame?) {
        pending = frame
        reportCropOverlay()
        reportAlignGrid()
        reportFocusMarker()
        queueEvent { requestRender() }
    }

    /** Maps the current crop quad (with rotation/perspective applied) to
     *  view pixels and reports it for the crop overlay. Called on the UI
     *  thread from [setPendingFrame], which follows every crop change. */
    private fun reportCropOverlay() {
        if (!cropOverlayEnabled) {
            onCropOverlay(null)
            onCropOverlayBounds(null)
            return
        }
        val frame = pending
        if (frame == null || width <= 0 || height <= 0) {
            onCropOverlay(null)
            onCropOverlayBounds(null)
            return
        }
        val quad = Geometry.transformedQuad(frame.state.crop, frame.state.geometry)
        val px = quad.mapNotNull { uvToViewPx(it) }
        onCropOverlay(
            if (px.size == 4) px.map { Offset(it.x, it.y) } else null
        )
        onCropOverlayBounds(
            if (px.size == 4) Geometry.quadOutOfBounds(frame.state.crop, frame.state.geometry)
            else null
        )
    }

    /** Maps the displayed photo quad (crop rect with rotation/perspective
     *  applied, same mapping as [reportCropOverlay]) to view pixels and
     *  reports it for the straighten alignment grid. Called on the UI
     *  thread from [setPendingFrame] and from the composable's update block
     *  so the grid appears immediately when straighten mode is entered,
     *  without waiting for a frame change. */
    fun reportAlignGrid() {
        if (!alignGridEnabled) {
            onAlignGrid(null)
            return
        }
        val frame = pending
        if (frame == null || width <= 0 || height <= 0) {
            onAlignGrid(null)
            return
        }
        val quad = Geometry.transformedQuad(frame.state.crop, frame.state.geometry)
        val px = quad.mapNotNull { uvToViewPx(it) }
        onAlignGrid(
            if (px.size == 4) px.map { Offset(it.x, it.y) } else null
        )
    }

    /** Maps the current lens-blur focus point to view pixels and reports it
     *  for the focus marker overlay. Called on the UI thread from
     *  [setPendingFrame] and from the composable's update block, so the
     *  marker appears immediately on mode entry and tracks the drag live. */
    fun reportFocusMarker() {
        if (!focusMarkerEnabled) {
            onFocusMarker(null)
            return
        }
        val frame = pending
        if (frame == null || width <= 0 || height <= 0) {
            onFocusMarker(null)
            return
        }
        val o = frame.state.optics
        val v = uvToViewPx(Pt(o.lensFocusX.coerceIn(0f, 1f),
            o.lensFocusY.coerceIn(0f, 1f)))
        onFocusMarker(v?.let { Offset(it.x, it.y) })
    }

    private fun clampPan() {
        // keep at least part of the image visible; loose clamp in NDC
        val range = 1f - 1f / zoom
        panX = panX.coerceIn(-range - 0.5f, range + 0.5f)
        panY = panY.coerceIn(-range - 0.5f, range + 0.5f)
    }

    private fun zoomMatrix(): FloatArray {
        val s = 1f / zoom
        val tx = focusX * (1f - s) + panX
        val ty = focusY * (1f - s) + panY
        return floatArrayOf(s, 0f, 0f, 0f, s, 0f, tx, ty, 1f)
    }

    /** Clears pinch zoom/pan; used when entering crop mode. */
    fun resetZoom() {
        zoom = 1f; panX = 0f; panY = 0f
        focusX = 0f; focusY = 0f
        applyZoom()
    }

    private fun applyZoom() {
        queueEvent {
            engine.zoomMatrix = zoomMatrix()
            requestRender()
        }
    }

    /** Touch NDC -> source image UV using the latest frame matrices. */
    private fun touchToUv(x: Float, y: Float): Pt {
        val frame = pending ?: return Pt(-1f, -1f)
        val nx = x / width * 2f - 1f
        val ny = 1f - y / height * 2f
        val combined = Geometry.mul3(frame.uvMatrix, zoomMatrix())
        val inv = Geometry.invert3(combined) ?: return Pt(-1f, -1f)
        val w = inv[2] * nx + inv[5] * ny + inv[8]
        if (w == 0f) return Pt(-1f, -1f)
        return Pt(
            (inv[0] * nx + inv[3] * ny + inv[6]) / w,
            (inv[1] * nx + inv[4] * ny + inv[7]) / w,
        )
    }

    /** Source-image UV -> view pixels. Exact inverse of [touchToUv]:
     *  touchToUv computes uv = inv(combined) * ndc, so ndc = combined * uv. */
    private fun uvToViewPx(uv: Pt): Pt? {
        val frame = pending ?: return null
        val m = Geometry.mul3(frame.uvMatrix, zoomMatrix())
        val w = m[2] * uv.x + m[5] * uv.y + m[8]
        if (abs(w) < 1e-9f) return null
        val nx = (m[0] * uv.x + m[3] * uv.y + m[6]) / w
        val ny = (m[1] * uv.x + m[4] * uv.y + m[7]) / w
        return Pt((nx + 1f) * 0.5f * width, (1f - ny) * 0.5f * height)
    }

    /** Reports the brush cursor for a paint touch UV (view pixels), or null.
     *  While the loupe is enabled a throttled magnifier render follows the
     *  cursor so the user can place the brush precisely (Lightroom parity). */
    private fun reportCursor(uv: Pt) {
        val rUv = cursorRadiusUv
        if (rUv <= 0f || uv.x !in 0f..1f || uv.y !in 0f..1f) {
            clearCursor()
            return
        }
        val c = uvToViewPx(uv)
        // measure the on-screen radius from a second point offset by the
        // brush radius in UV; no clamping needed, the mapping extrapolates
        val e = c?.let { uvToViewPx(Pt(uv.x + rUv, uv.y)) }
        if (c == null || e == null) {
            clearCursor()
            return
        }
        onCursor(BrushCursor(c.x, c.y, hypot(e.x - c.x, e.y - c.y), uv))
        requestLoupe(uv, rUv, c.x, c.y, hypot(e.x - c.x, e.y - c.y))
    }

    /** Hides the cursor ring and the loupe. */
    private fun clearCursor() {
        onCursor(null)
        lastLoupeUv = null
        post { onLoupe?.invoke(null) }
    }

    /**
     * Throttled brush loupe: renders a magnified view of the pixels under
     * the brush on the GL thread and posts it back for the overlay. The
     * render reuses the preview's already-uploaded GPU assets, so the loupe
     * shows exactly what the preview shows — including the in-progress
     * stroke — at a fraction of the cost of a full-frame render.
     */
    private fun requestLoupe(uv: Pt, rUv: Float, xPx: Float, yPx: Float, radiusPx: Float) {
        if (!loupeEnabled) return
        val now = SystemClock.uptimeMillis()
        val last = lastLoupeUv
        val moved = last == null || hypot(uv.x - last.x, uv.y - last.y) > 0.008f
        if (!moved && now - lastLoupeMs < 150) return
        lastLoupeMs = now
        lastLoupeUv = uv
        val v = vm ?: return
        val frame = pending
        val st = frame?.state ?: v.state.value
        val opts = GlEngine.FrameOptions(
            clipMode = frame?.clipMode ?: 0,
            overlayMask = frame?.overlayMaskIndex ?: -1,
            overlayAlpha = frame?.overlayAlpha ?: 0.55f,
            maskViewMode = frame?.maskViewMode ?: 0,
            timeSec = 0f,
        )
        queueEvent {
            try {
                val bmp = engine.renderLoupe(st, uv, rUv, LOUPE_SIZE_PX, opts)
                post { onLoupe?.invoke(LoupeFrame(bmp, xPx, yPx, radiusPx)) }
            } catch (t: Throwable) {
                // A failed loupe must never disturb painting; the overlay
                // simply keeps its previous frame.
                android.util.Log.w("EditorGlView", "loupe render failed", t)
            }
        }
    }

    /**
     * Maps the current retouch spots to view-pixel pin markers (same
     * projection as [reportCursor], so pins track zoom/pan exactly). Called
     * on the UI thread from the composable's update block, which re-runs
     * after every edit and every frame change. Skips work when nothing
     * relevant changed (spots list identity + zoom/pan).
     */
    fun reportRetouchSpots() {
        val spots = retouchSpotUvs
        if (spots == null) {
            if (lastSpotsKey != null) {
                lastSpotsKey = null
                onRetouchSpots(null)
            }
            return
        }
        if (width <= 0 || height <= 0 || pending == null) return
        val key = 31 * (31 * (31 * spots.hashCode() + zoom.hashCode()) + panX.hashCode()) +
                panY.hashCode()
        if (key == lastSpotsKey) return
        lastSpotsKey = key
        val markers = spots.mapNotNull { s ->
            val c = uvToViewPx(Pt(s.x, s.y)) ?: return@mapNotNull null
            // measure the on-screen radius the same way reportCursor does:
            // a second UV point offset by the spot radius in image-width units
            val e = uvToViewPx(Pt(s.x + s.size / 2f, s.y)) ?: return@mapNotNull null
            SpotMarker(c.x, c.y, hypot(e.x - c.x, e.y - c.y))
        }
        onRetouchSpots(markers)
    }

    /**
     * Maps the clone-tool source pick to a view-pixel pin marker (same
     * projection as [reportCursor]/[reportRetouchSpots], so the pin tracks
     * zoom/pan exactly). Called on the UI thread from the composable's
     * update block. Skips work when nothing relevant changed (source UV +
     * brush size + zoom/pan).
     */
    fun reportCloneSource() {
        val uv = cloneSourceUv
        val d = cloneSourceDiameterUv
        if (uv == null || d <= 0f) {
            if (lastCloneKey != null) {
                lastCloneKey = null
                onCloneSource(null)
            }
            return
        }
        if (width <= 0 || height <= 0 || pending == null) return
        val key = 31 * (31 * (31 * (31 * uv.hashCode() + d.hashCode()) +
                zoom.hashCode()) + panX.hashCode()) + panY.hashCode()
        if (key == lastCloneKey) return
        lastCloneKey = key
        val c = uvToViewPx(uv)
        // measure the on-screen radius the same way reportCursor does:
        // a second UV point offset by the brush radius (diameter / 2) in
        // image-width units
        val e = uvToViewPx(Pt(uv.x + d / 2f, uv.y))
        onCloneSource(
            if (c != null && e != null) SpotMarker(c.x, c.y, hypot(e.x - c.x, e.y - c.y))
            else null
        )
    }

    /**
     * Reads a small patch around the given view-pixel tap point and reports
     * the averaged 0..1 RGB on the UI thread. The patch smooths out sensor
     * noise so solvers get a stable sample. [onSample] receives the result;
     * [isValid] is re-checked on the UI thread before delivery so a stale
     * readback (e.g. the finger already lifted) is dropped silently.
     */
    private fun samplePatch(
        x: Float, y: Float,
        onSample: (r: Float, g: Float, b: Float) -> Unit,
        isValid: () -> Boolean = { true },
    ) {
        val w = width; val h = height
        if (w <= 0 || h <= 0) return
        val p = 16
        val x0 = (x - p / 2f).roundToInt().coerceIn(0, max(0, w - p))
        val y0 = (y - p / 2f).roundToInt().coerceIn(0, max(0, h - p))
        val pw = min(p, w - x0); val ph = min(p, h - y0)
        if (pw <= 0 || ph <= 0) return
        queueEvent {
            try {
                val buf = ByteBuffer.allocateDirect(pw * ph * 4)
                    .order(ByteOrder.nativeOrder())
                // GL origin is bottom-left: flip y.
                GLES30.glReadPixels(x0, h - y0 - ph, pw, ph,
                    GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
                var sr = 0L; var sg = 0L; var sb = 0L
                for (i in 0 until pw * ph) {
                    sr += buf.get(i * 4).toInt() and 0xFF
                    sg += buf.get(i * 4 + 1).toInt() and 0xFF
                    sb += buf.get(i * 4 + 2).toInt() and 0xFF
                }
                val n = (pw * ph).toFloat()
                val r = sr / n / 255f; val g = sg / n / 255f; val b = sb / n / 255f
                post { if (isValid()) onSample(r, g, b) }
            } catch (e: Exception) {
                android.util.Log.e("EditorGlView", "WB pick readback failed", e)
            }
        }
    }

    /** Pick the crop handle nearest to the touch UV: corners, edges, or whole-rect move. */
    private fun cropHandleFor(crop: CropParams, u: Float, v: Float): CropHandle {
        val cornerTol = 0.06f
        val corners = arrayOf(
            CropHandle.TL to Pt(crop.left, crop.top),
            CropHandle.TR to Pt(crop.right, crop.top),
            CropHandle.BL to Pt(crop.left, crop.bottom),
            CropHandle.BR to Pt(crop.right, crop.bottom),
        )
        var best = CropHandle.None
        var bestD = Float.MAX_VALUE
        for ((h, p) in corners) {
            val d = max(abs(u - p.x), abs(v - p.y))
            if (d < cornerTol && d < bestD) { best = h; bestD = d }
        }
        if (best != CropHandle.None) return best
        val edgeTol = 0.03f
        if (abs(u - crop.left) < edgeTol && v >= crop.top && v <= crop.bottom) return CropHandle.L
        if (abs(u - crop.right) < edgeTol && v >= crop.top && v <= crop.bottom) return CropHandle.R
        if (abs(v - crop.top) < edgeTol && u >= crop.left && u <= crop.right) return CropHandle.T
        if (abs(v - crop.bottom) < edgeTol && u >= crop.left && u <= crop.right) return CropHandle.B
        if (u >= crop.left && u <= crop.right && v >= crop.top && v <= crop.bottom) return CropHandle.Move
        return CropHandle.None
    }

    /** Resize or move the crop rect from a drag handle; honors the aspect lock. */
    private fun cropForDrag(start: CropParams, handle: CropHandle, u: Float, v: Float): CropParams {
        val min = 0.02f
        var l = start.left; var t = start.top; var r = start.right; var b = start.bottom
        val aspect = start.aspect
        when (handle) {
            CropHandle.TL -> {
                l = u.coerceIn(0f, r - min); t = v.coerceIn(0f, b - min)
                if (aspect != null) t = (b - (r - l) / aspect).coerceIn(0f, b - min)
            }
            CropHandle.TR -> {
                r = u.coerceIn(l + min, 1f); t = v.coerceIn(0f, b - min)
                if (aspect != null) t = (b - (r - l) / aspect).coerceIn(0f, b - min)
            }
            CropHandle.BL -> {
                l = u.coerceIn(0f, r - min); b = v.coerceIn(t + min, 1f)
                if (aspect != null) b = (t + (r - l) / aspect).coerceIn(t + min, 1f)
            }
            CropHandle.BR -> {
                r = u.coerceIn(l + min, 1f); b = v.coerceIn(t + min, 1f)
                if (aspect != null) b = (t + (r - l) / aspect).coerceIn(t + min, 1f)
            }
            CropHandle.L -> {
                l = u.coerceIn(0f, r - min)
                if (aspect != null) {
                    val h = (r - l) / aspect; val cy = (t + b) / 2f
                    t = (cy - h / 2f).coerceIn(0f, 1f)
                    b = (t + h).coerceIn(t + min, 1f)
                }
            }
            CropHandle.R -> {
                r = u.coerceIn(l + min, 1f)
                if (aspect != null) {
                    val h = (r - l) / aspect; val cy = (t + b) / 2f
                    t = (cy - h / 2f).coerceIn(0f, 1f)
                    b = (t + h).coerceIn(t + min, 1f)
                }
            }
            CropHandle.T -> {
                t = v.coerceIn(0f, b - min)
                if (aspect != null) {
                    val w = (b - t) * aspect; val cx = (l + r) / 2f
                    l = (cx - w / 2f).coerceIn(0f, 1f)
                    r = (l + w).coerceIn(l + min, 1f)
                }
            }
            CropHandle.B -> {
                b = v.coerceIn(t + min, 1f)
                if (aspect != null) {
                    val w = (b - t) * aspect; val cx = (l + r) / 2f
                    l = (cx - w / 2f).coerceIn(0f, 1f)
                    r = (l + w).coerceIn(l + min, 1f)
                }
            }
            CropHandle.Move -> {
                val dx = u - cropStartUv.x; val dy = v - cropStartUv.y
                val w = r - l; val h = b - t
                l = (start.left + dx).coerceIn(0f, 1f - w)
                t = (start.top + dy).coerceIn(0f, 1f - h)
                r = l + w; b = t + h
            }
            CropHandle.None -> {}
        }
        return start.copy(left = l, top = t, right = r, bottom = b)
    }

    private fun handleTouch(event: MotionEvent) {
        // Before/after split: drag the divider horizontally (NDC x -1..1).
        // Multi-touch is ignored so a pinch never moves the divider.
        if (touchMode == PreviewTouchMode.Split) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    val w = width.toFloat()
                    if (w > 0f && event.pointerCount == 1) {
                        onSplitDrag((event.x / w) * 2f - 1f)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { /* no-op */ }
            }
            return
        }
        // Guided lines (straighten and perspective): two clean taps define a
        // line that should be level (straighten) or vertical (perspective);
        // a drag disqualifies, like the WB pick. Zoom/pan stay disabled.
        val guideSlot = when (touchMode) {
            PreviewTouchMode.Straighten -> straightenSlot
            PreviewTouchMode.Perspective -> perspectiveSlot
            else -> null
        }
        if (guideSlot != null) {
            val mode = touchMode
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    guideSlot.downX = event.x
                    guideSlot.downY = event.y
                    guideSlot.moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!guideSlot.moved &&
                        hypot(event.x - guideSlot.downX, event.y - guideSlot.downY) > touchSlopPx
                    ) guideSlot.moved = true
                }
                MotionEvent.ACTION_UP -> {
                    if (!guideSlot.moved) {
                        val uv = touchToUv(event.x, event.y)
                            .let { Pt(it.x.coerceIn(0f, 1f), it.y.coerceIn(0f, 1f)) }
                        val p1 = guideSlot.p1
                        if (p1 == null) {
                            guideSlot.p1 = uv
                            reportGuideOverlay(guideSlot, mode)
                        } else {
                            guideSlot.p1 = null
                            // show the finished line for the frame(s) until
                            // the editor exits the guide mode
                            val v1 = uvToViewPx(p1); val v2 = uvToViewPx(uv)
                            if (v1 != null && v2 != null) {
                                guideSlot.onOverlay(
                                    listOf(Offset(v1.x, v1.y), Offset(v2.x, v2.y)))
                            }
                            guideSlot.onGuide(p1, uv)
                        }
                    }
                }
                MotionEvent.ACTION_CANCEL -> { /* no-op */ }
            }
            return
        }
        // White-balance eyedropper: a clean tap (no drag) samples a small
        // patch around the tap point; zoom/pan gestures stay disabled.
        if (touchMode == PreviewTouchMode.Pick) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pickDownX = event.x; pickDownY = event.y; pickMoved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!pickMoved &&
                        hypot(event.x - pickDownX, event.y - pickDownY) > touchSlopPx
                    ) pickMoved = true
                }
                MotionEvent.ACTION_UP -> {
                    if (!pickMoved) samplePatch(event.x, event.y,
                        { r, g, b -> onPickColor(r, g, b) })
                }
                MotionEvent.ACTION_CANCEL -> { /* no-op */ }
            }
            return
        }
        // Lens-blur focus placement: tap or drag moves the focus point;
        // zoom/pan gestures stay disabled.
        if (touchMode == PreviewTouchMode.FocusPoint) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    focusDragging = true
                    onFocusStart()
                    val uv = touchToUv(event.x, event.y)
                    onFocusDrag(Pt(uv.x.coerceIn(0f, 1f), uv.y.coerceIn(0f, 1f)))
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!focusDragging) return
                    val uv = touchToUv(event.x, event.y)
                    onFocusDrag(Pt(uv.x.coerceIn(0f, 1f), uv.y.coerceIn(0f, 1f)))
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (focusDragging) {
                        focusDragging = false
                        onFocusEnd()
                    }
                }
            }
            return
        }
        // Tone-curve targeted adjustment (Lightroom parity): DOWN samples
        // the tone under the finger, vertical drag bends the curve point
        // for that tone (up = lift, down = drop); zoom/pan stay disabled.
        if (touchMode == PreviewTouchMode.CurveTarget) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    curveDragging = true
                    curveDownY = event.y
                    samplePatch(
                        event.x, event.y,
                        { r, g, b -> onCurveSample(r, g, b) },
                        { curveDragging && touchMode == PreviewTouchMode.CurveTarget },
                    )
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!curveDragging) return
                    onCurveDrag(event.y - curveDownY, height.toFloat())
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (curveDragging) {
                        curveDragging = false
                        onCurveEnd()
                    }
                }
            }
            return
        }
        if (touchMode == PreviewTouchMode.Paint) {
            // Picsart-style: two-finger pinch zooms even while in paint/retouch
            // mode; single finger paints. Route multi-touch to the scale detector.
            scaleDetector.onTouchEvent(event)
            if (scaleDetector.isInProgress) {
                // Cancel any in-progress paint stroke when pinch begins
                if (painting) {
                    painting = false
                    lastPaintUv = null
                    onPaintEnd()
                    clearCursor()
                }
                return
            }
            // A second finger going down mid-stroke: end the stroke, let the
            // upcoming pinch take over (scale detector already saw the event).
            if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN && painting) {
                painting = false
                lastPaintUv = null
                onPaintEnd()
                clearCursor()
                return
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    painting = true
                    val uv = touchToUv(event.x, event.y)
                    lastPaintUv = uv
                    onPaintStart(uv)
                    reportCursor(uv)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!painting) return
                    val uv = touchToUv(event.x, event.y)
                    val last = lastPaintUv
                    if (last != null && (uv.x != last.x || uv.y != last.y)) {
                        onPaintSegment(last, uv)
                        lastPaintUv = uv
                    }
                    reportCursor(uv)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    painting = false
                    lastPaintUv = null
                    onPaintEnd()
                    clearCursor()
                }
            }
            return
        }
        if (touchMode == PreviewTouchMode.Crop) {
            // Picsart/Lightroom-style: two-finger pinch zooms in crop mode for
            // precise handle placement; single finger drags the crop handles.
            // Route multi-touch to the scale detector first.
            scaleDetector.onTouchEvent(event)
            if (scaleDetector.isInProgress) {
                // Cancel any in-progress crop drag when pinch begins; the
                // drag is closed as one undo entry like a normal drag end.
                if (cropping) {
                    cropping = false
                    cropStart = null
                    cropHandle = CropHandle.None
                    onCropEnd()
                }
                return
            }
            // A second finger going down mid-drag: end the drag, let the
            // upcoming pinch take over (scale detector already saw the event).
            if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN && cropping) {
                cropping = false
                cropStart = null
                cropHandle = CropHandle.None
                onCropEnd()
                return
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val crop = pending?.state?.crop ?: return
                    val uv = touchToUv(event.x, event.y)
                    if (uv.x !in 0f..1f || uv.y !in 0f..1f) {
                        cropHandle = CropHandle.None
                        return
                    }
                    cropHandle = cropHandleFor(crop, uv.x, uv.y)
                    if (cropHandle == CropHandle.None) return
                    cropStart = crop
                    cropStartUv = uv
                    cropping = true
                    onCropStart()
                }
                MotionEvent.ACTION_MOVE -> {
                    val s = cropStart
                    if (!cropping || s == null) return
                    val uv = touchToUv(event.x, event.y)
                    onCropDrag(cropForDrag(s, cropHandle,
                        uv.x.coerceIn(0f, 1f), uv.y.coerceIn(0f, 1f)))
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (cropping) {
                        cropping = false
                        cropStart = null
                        cropHandle = CropHandle.None
                        onCropEnd()
                    }
                }
            }
            return
        }
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x; lastTouchY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && event.pointerCount == 1 && zoom > 1.01f) {
                    val dx = (event.x - lastTouchX) / width * 2f
                    val dy = -(event.y - lastTouchY) / height * 2f
                    panX += dx / zoom
                    panY += dy / zoom
                    clampPan()
                    applyZoom()
                    lastTouchX = event.x; lastTouchY = event.y
                }
            }
        }
    }

    private inner class Renderer : GLSurfaceView.Renderer {
        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            try {
                engine.init()
            } catch (e: Exception) {
                setGlError(e.message ?: "GL initialization failed")
                android.util.Log.e("EditorGlView", "GL init failed", e)
                return
            }
            // EGL context loss invalidates all GL objects; force full re-upload
            // by resetting the change-detection sentinels.
            lastFrameId = -1L
            lastCurvesRev = -1L
            lastMasksRev = Long.MIN_VALUE
            lastRetouchRev = Long.MIN_VALUE
            lastAssetsToken = Long.MIN_VALUE
            lastAiWeights = null
            lastAiWbGains = null
            lastLookSrc = null
            lastLookRef = null
            lastSource = null
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            vm?.let { v ->
                try {
                    v.previewBitmap.value?.let { bmp ->
                        engine.setSource(bmp)
                        lastSource = bmp
                    }
                } catch (e: Exception) {
                    android.util.Log.e("EditorGlView", "setSource failed", e)
                }
            }
        }

        override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
            GLES30.glViewport(0, 0, w, h)
            vm?.onViewSize(w, h)
            queueEvent { requestRender() }
        }

        override fun onDrawFrame(gl: GL10?) {
            if (glError != null) return
            try {
                drawFrameInternal()
            } catch (e: Exception) {
                android.util.Log.e("EditorGlView", "onDrawFrame failed", e)
                setGlError(e.message ?: "Render failed")
            }
        }

        private fun drawFrameInternal() {
            val frame = pending ?: return
            val v = vm ?: return
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            // Defense in depth: offscreen work (thumbnails, export, blur
            // pyramid, self-test) must restore the viewport, but if any path
            // ever leaks it, the preview would render as a tiny thumbnail in
            // the corner (2026-10-16 user report). Reassert the full surface
            // viewport on every preview frame so the preview self-heals.
            val vw = width; val vh = height
            if (vw > 0 && vh > 0) GLES30.glViewport(0, 0, vw, vh)

            val bmp = v.previewBitmap.value
            val assetsInvalidated = frame.assetsToken != lastAssetsToken
            // AI Pro Retouch LUT is engine-local GPU state like the
            // curves/mask/retouch textures: re-establish the fused grade when
            // its identity changes (apply / clear / photo opened with a saved
            // grade / compare toggle) or assets were invalidated. Without
            // this, a grade saved in the sidecar silently rendered as "no
            // grade" after photo reopen, surface recreation, or the
            // self-test, while the panel still showed it as applied. Runs
            // BEFORE the asset uploads below: a first-ever bank bake stomps
            // the source and curves textures, and the uploads below then
            // restore everything correctly.
            // Shared default instance (EditState.DEFAULT): the old
            // `EditState()` built ~a dozen objects on the GL thread every
            // frame while "show original" was held. The state is deeply
            // immutable and render() only reads it, so sharing is safe.
            val effState = if (frame.showOriginal) EditState.DEFAULT else frame.state
            val ai = effState.aiRetouch
            // The revision key covers weights AND wbGains: both are baked
            // into the uploaded LUT, so a change to either must re-upload.
            // Allocation-free: the state's own lists are compared
            // structurally — the old `weights + wbGains` concat allocated a
            // boxed-Float ArrayList on the GL thread every frame.
            val aiW: List<Float>? =
                if (ai.enabled && ai.weights.size == AiProRetouch.BASIS_COUNT) ai.weights else null
            val aiG: List<Float>? = aiW?.let { ai.wbGains?.takeIf { g -> g.isNotEmpty() } }
            // The intensity rides as a live uniform every frame, so only the
            // LUT upload needs this revision tracking — a structural list
            // compare per frame costs nothing when nothing changed.
            var aiStomped = false
            if (assetsInvalidated || aiW != lastAiWeights || aiG != lastAiWbGains) {
                aiStomped = AiGradeUpload.upload(
                    engine,
                    ai.weights.takeIf {
                        ai.enabled && it.size == AiProRetouch.BASIS_COUNT
                    },
                    ai.wbGains,
                ).sourceStomped
                lastAiWeights = aiW
                lastAiWbGains = aiG
            }
            // Look transfer LUT: same revision tracking. The bake is pure
            // CPU (no source stomping), so no restore dance is needed — the
            // LUT is fully determined by the 12 stored lαβ statistics.
            // Allocation-free like the AI grade: compare the two stat lists
            // directly instead of the old `srcStats + refStats` concat.
            val look = effState.lookTransfer
            val lookS: List<Float>? = if (look.isActive()) look.srcStats else null
            val lookR: List<Float>? = if (look.isActive()) look.refStats else null
            if (assetsInvalidated || lookS != lastLookSrc || lookR != lastLookRef) {
                LookLutUpload.upload(engine, look)
                lastLookSrc = lookS
                lastLookRef = lookR
            }
            if (bmp != null && (bmp !== lastSource || aiStomped)) {
                engine.setSource(bmp)
                lastSource = bmp
                // Black-frame watchdog: arm after every source upload. The
                // next completed render reads back a few framebuffer pixels;
                // if they're all black while the source is non-black, the
                // GPU path is broken (P0 2026-10-17: sampler3D/2D mismatch
                // on Mali) and we fall back to the software preview instead
                // of showing a black screen.
                watchdogPending = true
            }
            if (frame.curvesRev != lastCurvesRev || assetsInvalidated || aiStomped) {
                engine.uploadCurves(frame.state)
                lastCurvesRev = frame.curvesRev
            }
            if (frame.masksRev != lastMasksRev || assetsInvalidated) {
                engine.uploadMasks(frame.masks)
                engine.uploadMaskDecon(frame.maskDecon)
                lastMasksRev = frame.masksRev
            }
            if (frame.retouchRev != lastRetouchRev || assetsInvalidated) {
                engine.uploadRetouch(frame.retouch)
                lastRetouchRev = frame.retouchRev
            }
            lastAssetsToken = frame.assetsToken

            val state = effState
            engine.render(
                state, frame.uvMatrix,
                GlEngine.FrameOptions(
                    clipMode = frame.clipMode,
                    overlayMask = frame.overlayMaskIndex,
                    overlayAlpha = frame.overlayAlpha,
                    maskViewMode = frame.maskViewMode,
                    timeSec = frame.timeSec,
                    splitX = frame.splitX,
                ),
                maskUniforms = frame.maskUniforms,
            )
            lastFrameId = frame.frameId

            // Black-frame watchdog: after the first render following a
            // source upload, sample a few framebuffer pixels. If they're
            // all black while the source bitmap is non-black, the GPU
            // render path is broken — route to the software preview
            // fallback (the glFailed UI) with the failed stage named.
            if (watchdogPending) {
                watchdogPending = false
                checkBlackFrame(bmp, frame, effState)
            }

            // throttled histogram readback
            val now = System.currentTimeMillis()
            if (now - lastHistMs > 450) {
                lastHistMs = now
                readHistogram()
            }
        }

        /**
         * Black-frame watchdog (P0 2026-10-17): samples a 3x3 grid of
         * framebuffer pixels after the first render following a source
         * upload. Trips only when the GPU clearly dropped the image:
         * every sample landing on the photo is pure black while the
         * source bitmap is bright at (at least) one of those same spots.
         *
         * False-positive gates (mirroring the ViewModel's continuous
         * watchdog): a mask overlay / B&W mask-check view repaints the
         * frame by design, and exposure <= -3 EV is a deliberate
         * blackout — a black frame is expected in all three cases and
         * is never a GPU failure.
         *
         * Each sample is mapped back to source UV through the frame's
         * combined matrix (the CPU mirror of the GPU mapping used by
         * [touchToUv]), so zooming into a dark corner or letterbox bars
         * can't scare the watchdog: only samples landing on the photo
         * count, and the source is judged at those exact spots instead
         * of a single center pixel. Runs on the GL thread; never throws.
         *
         * @param bmp the source bitmap that was just uploaded (null-safe).
         */
        private fun checkBlackFrame(
            bmp: Any?, frame: PendingFrame, state: EditState,
        ) {
            try {
                // Intentional blackouts — never a GPU failure.
                if (frame.overlayMaskIndex >= 0 || frame.maskViewMode != 0) return
                if (state.light.exposure <= -3f) return
                val w = width; val h = height
                if (w <= 0 || h <= 0) return
                val bitmap = bmp as? android.graphics.Bitmap
                if (bitmap == null || bitmap.isRecycled ||
                    bitmap.width <= 0 || bitmap.height <= 0) return
                // NDC -> source UV, same math as touchToUv. A null inverse
                // means the mapping is degenerate — can't judge, stay quiet.
                val inv = try {
                    Geometry.invert3(Geometry.mul3(frame.uvMatrix, engine.zoomMatrix))
                } catch (t: Throwable) { null }
                val buf = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
                var inImage = 0
                var brightSource = false
                for (gy in 0..2) {
                    for (gx in 0..2) {
                        val x = (w * (gx + 1) / 4).coerceIn(0, w - 1)
                        // glReadPixels y is from the bottom; the UV mapping
                        // below takes y from the top like touchToUv.
                        val yGl = (h * (gy + 1) / 4).coerceIn(0, h - 1)
                        buf.position(0)
                        GLES30.glReadPixels(x, yGl, 1, 1,
                            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
                        val r = buf.get(0).toInt() and 0xFF
                        val g = buf.get(1).toInt() and 0xFF
                        val b = buf.get(2).toInt() and 0xFF
                        if (r != 0 || g != 0 || b != 0) return // live pixel: GPU fine
                        // Framebuffer pixel is black — legitimate only if
                        // the source is dark at this exact spot. Samples
                        // landing outside the photo (letterbox bars) are
                        // skipped: they can't judge the render.
                        val uv = ndcToSourceUv(x, h - yGl, w, h, inv) ?: continue
                        inImage++
                        val sx = (uv.x * bitmap.width).toInt()
                            .coerceIn(0, bitmap.width - 1)
                        val sy = (uv.y * bitmap.height).toInt()
                            .coerceIn(0, bitmap.height - 1)
                        val px = bitmap.getPixel(sx, sy)
                        val luma = 0.2126f * ((px shr 16) and 0xFF) +
                            0.7152f * ((px shr 8) and 0xFF) +
                            0.0722f * (px and 0xFF)
                        // > 16/255: clearly non-black, robust against JPEG
                        // noise and near-black night scenes.
                        if (luma > 16f) brightSource = true
                    }
                }
                if (inImage == 0) return // no sample landed on the photo
                if (!brightSource) return // genuinely dark photo — not a bug
                android.util.Log.e("EditorGlView",
                    "Black-frame watchdog tripped: framebuffer black on-photo, source bright")
                setGlError("GPU preview failed at render — showing software preview")
            } catch (t: Throwable) {
                // Watchdog must never break rendering; log and continue.
                android.util.Log.w("EditorGlView", "Watchdog check failed", t)
            }
        }

        /**
         * View pixel (y from the top) -> source-image UV through an
         * NDC->UV inverse matrix, or null when the point maps outside
         * the photo (letterbox bars) or no matrix was supplied. Same
         * convention as [touchToUv].
         */
        private fun ndcToSourceUv(
            xPx: Int, yTopPx: Int, w: Int, h: Int, inv: FloatArray?,
        ): Pt? {
            if (inv == null || w <= 0 || h <= 0) return null
            val nx = xPx / w.toFloat() * 2f - 1f
            val ny = 1f - yTopPx / h.toFloat() * 2f
            val iw = inv[2] * nx + inv[5] * ny + inv[8]
            if (iw == 0f) return null
            val u = (inv[0] * nx + inv[3] * ny + inv[6]) / iw
            val v = (inv[1] * nx + inv[4] * ny + inv[7]) / iw
            return if (u in 0f..1f && v in 0f..1f) Pt(u, v) else null
        }

        private fun readHistogram() {
            val v = vm ?: return
            val w = width; val h = height
            if (w <= 0 || h <= 0) return
            val cap = w * h * 4
            var buf = readBuf
            if (buf == null || buf.capacity() < cap) {
                buf = ByteBuffer.allocateDirect(cap).order(ByteOrder.nativeOrder())
                readBuf = buf
            }
            buf.position(0)
            GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
            v.onFrameBytes(buf, w, h)
        }
    }
}
