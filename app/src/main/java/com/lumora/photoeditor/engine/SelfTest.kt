package com.lumora.photoeditor.engine

import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.GLES30
import android.os.Build
import android.os.SystemClock
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs
import kotlin.math.roundToInt
import com.lumora.photoeditor.data.LibraryImage
import com.lumora.photoeditor.data.ClientPicksResult
import com.lumora.photoeditor.data.ProofMark
import com.lumora.photoeditor.data.ProofingGallery
import com.lumora.photoeditor.data.clientPicksPlan
import com.lumora.photoeditor.data.sync.SyncDocument
import com.lumora.photoeditor.data.sync.SyncPhotoEntry
import com.lumora.photoeditor.data.sync.SyncPullOutcome
import com.lumora.photoeditor.data.sync.SyncRunOutcome
import com.lumora.photoeditor.data.sync.describeSyncOutcome
import com.lumora.photoeditor.data.sync.formatLastSync
import com.lumora.photoeditor.data.sync.mergeSyncDocuments
import com.lumora.photoeditor.data.sync.photoKeyOf
import com.lumora.photoeditor.data.sync.sanitizeSyncDocument
import com.lumora.photoeditor.data.sync.sanitizeSyncPhotoEntry
import com.lumora.photoeditor.data.sync.shortDeviceId
import com.lumora.photoeditor.data.billing.KeyValidation
import com.lumora.photoeditor.data.billing.LicenseStatus
import com.lumora.photoeditor.data.billing.MILLIS_PER_DAY
import com.lumora.photoeditor.data.billing.TRIAL_DAYS
import com.lumora.photoeditor.data.billing.checkChar
import com.lumora.photoeditor.data.billing.evaluateLicense
import com.lumora.photoeditor.data.billing.isPro
import com.lumora.photoeditor.data.billing.licenseStatusText
import com.lumora.photoeditor.data.billing.validateLicenseKey
import com.lumora.photoeditor.data.SmartCollection
import com.lumora.photoeditor.data.formatLabelFor
import com.lumora.photoeditor.data.keywordKeyOf
import com.lumora.photoeditor.data.next
import com.lumora.photoeditor.data.normalizeClientName
import com.lumora.photoeditor.data.normalizeCollectionName
import com.lumora.photoeditor.data.normalizeGalleryName
import com.lumora.photoeditor.data.normalizeKeyword
import com.lumora.photoeditor.data.rawRowsMissingThumbs
import com.lumora.photoeditor.data.sanitizeProofingGallery
import com.lumora.photoeditor.data.sanitizeSmartCollection
import com.lumora.photoeditor.data.smartCollectionMatches
import com.lumora.photoeditor.export.applyWatermark
import com.lumora.photoeditor.export.ContactSheetOptions
import com.lumora.photoeditor.export.contactSheetAutoColumns
import com.lumora.photoeditor.export.contactSheetColumns
import com.lumora.photoeditor.export.contactSheetFileName
import com.lumora.photoeditor.export.contactSheetLayout
import com.lumora.photoeditor.export.contactSheetPages
import com.lumora.photoeditor.export.markBadgeText
import com.lumora.photoeditor.export.sheetCellCaption
import com.lumora.photoeditor.ui.components.formatPercent
import androidx.compose.ui.graphics.Color as UiColor
import com.lumora.photoeditor.ui.components.GradientDecision
import com.lumora.photoeditor.ui.components.resolveTrackGradient
import com.lumora.photoeditor.ui.components.sampleTrackGradient
import com.lumora.photoeditor.ui.components.sliderAnchorFrac
import com.lumora.photoeditor.ui.components.sliderDefaultTickVisible
import com.lumora.photoeditor.ui.components.sliderSemanticRampsForTest

/** One on-device GPU check, shown in the self-test dialog. */
data class SelfTestCheck(
    val name: String,
    val passed: Boolean,
    val detail: String,
)

/** Result of [GlEngine.runSelfTest]. */
data class SelfTestResult(
    val checks: List<SelfTestCheck>,
    val durationMs: Long,
) {
    val allPassed: Boolean get() = checks.isNotEmpty() && checks.all { it.passed }
}

/**
 * Plain-text report of the self-test, for copying to the clipboard so the
 * user can send the result back (the mask-shader verification loop).
 */
fun SelfTestResult.toReportText(): String {
    val sb = StringBuilder()
    sb.appendLine("H2W Photo Lab — GPU self-test report")
    sb.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} " +
        "(Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})")
    val stamp = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        .format(Date(System.currentTimeMillis()))
    sb.appendLine("Ran: $stamp")
    sb.appendLine()
    for (c in checks) {
        sb.appendLine("${if (c.passed) "PASS" else "FAIL"} — ${c.name}")
        if (c.detail.isNotBlank()) sb.appendLine("    ${c.detail.trim()}")
    }
    sb.appendLine()
    val passed = checks.count { it.passed }
    sb.appendLine("Result: $passed/${checks.size} checks passed (${durationMs} ms)")
    return sb.toString()
}

/**
 * On-device GPU self-test. Renders synthetic inputs through the real preview
 * pipeline and verifies the output pixels — covering the exact code paths the
 * Mali sampler-array fix touched (unrolled uMaskTex[0..3] sampling in both the
 * local-adjustment and overlay branches). A PASS here means mask rendering
 * works correctly on THIS phone's GPU.
 *
 * Must be called on the GL thread that owns the engine's context. Uses its own
 * scratch framebuffer so the preview surface is never disturbed. Temporarily
 * swaps the engine's source/mask/curve assets and restores the [preview]
 * source before returning; the caller must still call
 * `EditorViewModel.invalidateGpuAssets()` afterwards so curves, masks and
 * retouch are re-uploaded on the next preview frame.
 *
 * Also runs a static Mali-safety audit of the shipped GLSL sources (no GL
 * needed): dynamic sampler-array indexing, precision-on-sampler statements,
 * and unqualified samplers in ES 3.00 sources — all of which crash,
 * miscompile, or get rejected on several Mali drivers.
 *
 * Framebuffer gotcha: [GlEngine.setSource] rebuilds the blurred guide texture
 * and leaves framebuffer 0 bound, so every render below re-binds the scratch
 * FBO first. Without that re-bind the test would draw onto the live preview
 * surface and read back the wrong pixels — every verdict would be garbage.
 */
fun GlEngine.runSelfTest(preview: Bitmap?): SelfTestResult {
    val t0 = SystemClock.elapsedRealtime()
    val checks = mutableListOf<SelfTestCheck>()
    fun check(name: String, block: () -> Pair<Boolean, String>) {
        try {
            val (ok, detail) = block()
            checks += SelfTestCheck(name, ok, detail)
        } catch (t: Throwable) {
            checks += SelfTestCheck(name, false,
                "Error: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    val s = 128
    val ids = IntArray(2)
    GLES30.glGenTextures(1, ids, 0)
    GLES30.glGenFramebuffers(1, ids, 1)
    val testTex = ids[0]
    val testFbo = ids[1]
    // The self-test renders into its own FBO at s×s; the viewport must be
    // restored afterwards or the live preview keeps rendering into a 128px
    // corner (same bug class as the 2026-10-16 black-preview report).
    val savedVp = IntArray(4)
    GLES30.glGetIntegerv(GLES30.GL_VIEWPORT, savedVp, 0)
    try {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, testTex)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, s, s, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,
            GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,
            GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,
            GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,
            GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, testFbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER,
            GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, testTex, 0)

        val savedZoom = zoomMatrix.copyOf()
        val gray = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        gray.eraseColor(Color.rgb(128, 128, 128))
        // left half opaque white, right half opaque black
        val halfMask = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val halfPx = IntArray(s * s) { i ->
            if ((i % s) < s / 2) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        }
        halfMask.setPixels(halfPx, 0, s, 0, 0, s, s)
        val blackMask = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        blackMask.eraseColor(Color.BLACK)

        try {
            zoomMatrix = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
            setSource(gray)
            uploadCurves(EditState())
            uploadRetouch(null)
            val matrix = Geometry.uvMatrix(s, s, s, s, CropParams(), GeometryParams())

            /** Renders and returns pixels as px[y][x] with y=0 at the TOP. */
            fun renderOnce(
                maskBitmaps: List<Bitmap>,
                edit: EditState,
                opts: GlEngine.FrameOptions = GlEngine.FrameOptions(),
            ): Array<IntArray> {
                uploadMasks(maskBitmaps)
                // setSource() -> buildBlur() leaves framebuffer 0 bound, so the
                // scratch FBO must be re-bound here or the test renders onto the
                // live preview surface and reads back the wrong pixels.
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, testFbo)
                GLES30.glViewport(0, 0, s, s)
                render(edit, matrix, opts)
                val err = GLES30.glGetError()
                if (err != GLES30.GL_NO_ERROR) {
                    throw IllegalStateException(
                        "GL error 0x${err.toString(16)} after render")
                }
                val buf = ByteBuffer.allocateDirect(s * s * 4)
                    .order(ByteOrder.nativeOrder())
                GLES30.glReadPixels(0, 0, s, s, GLES30.GL_RGBA,
                    GLES30.GL_UNSIGNED_BYTE, buf)
                buf.position(0)
                val px = Array(s) { IntArray(s) }
                for (glY in 0 until s) {
                    for (x in 0 until s) {
                        val r = buf.get().toInt() and 0xFF
                        val g = buf.get().toInt() and 0xFF
                        val b = buf.get().toInt() and 0xFF
                        buf.get() // a
                        px[s - 1 - glY][x] = (r shl 16) or (g shl 8) or b
                    }
                }
                return px
            }

            fun regionMean(px: Array<IntArray>, x0: Int, x1: Int): Double {
                var sum = 0L
                var n = 0
                for (y in 24..104 step 4) {
                    for (x in x0..x1 step 4) {
                        sum += (px[y][x] shr 16) and 0xFF
                        n++
                    }
                }
                return sum.toDouble() / n
            }

            /** Fraction of sampled pixels in the x0..x1 band satisfying pred. */
            fun regionPassRate(
                px: Array<IntArray>, x0: Int, x1: Int, pred: (Int, Int, Int) -> Boolean,
            ): Double {
                var ok = 0
                var n = 0
                for (y in 24..104 step 4) {
                    for (x in x0..x1 step 4) {
                        val p = px[y][x]
                        if (pred((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)) ok++
                        n++
                    }
                }
                return ok.toDouble() / n
            }

            val noMask = emptyList<Mask>()
            fun testMask(id: String, adjust: LocalAdjust = LocalAdjust()) =
                Mask(id = id, name = "Self-test", adjust = adjust)

            // ---- check 0: static Mali-safety audit of the shipped GLSL ----
            // Pure CPU scan: flags dynamic sampler-array indexing,
            // precision-on-sampler statements, and unqualified samplers in
            // ES 3.00 sources (all crash/miscompile/reject on several Mali
            // drivers). Runs first so shader regressions are caught even
            // when the GL pipeline itself is fine.
            check("Shader source audit (Mali-safe)") {
                val issues = ShaderSafety.auditShipped()
                val detail = if (issues.isEmpty()) {
                    "4 shaders scanned — no dynamic sampler indexing, " +
                        "no precision-on-sampler statements, " +
                        "no unqualified samplers"
                } else {
                    issues.joinToString("; ") {
                        "${it.shaderName}:${it.lineNumber} [${it.rule}]"
                    }
                }
                issues.isEmpty() to detail
            }

            // ---- check 1: device & driver ----
            check("GPU & driver") {
                val renderer = GLES30.glGetString(GLES30.GL_RENDERER) ?: "unknown"
                val vendor = GLES30.glGetString(GLES30.GL_VENDOR) ?: "unknown"
                val version = GLES30.glGetString(GLES30.GL_VERSION) ?: "unknown"
                val major = Regex("OpenGL ES (\\d+)").find(version)
                    ?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val maliNote =
                    if (renderer.contains("Mali", ignoreCase = true))
                        " — Mali-safe shader path active" else ""
                (major >= 3) to "$renderer · $vendor · $version$maliNote"
            }

            // ---- check 2: pipeline identity sanity ----
            check("Render pipeline") {
                val px = renderOnce(emptyList(), EditState(masks = noMask))
                var min = 255
                var max = 0
                var sum = 0L
                var n = 0
                for (y in 48..80 step 2) {
                    for (x in 48..80 step 2) {
                        val v = (px[y][x] shr 16) and 0xFF
                        sum += v; n++
                        min = minOf(min, v); max = maxOf(max, v)
                    }
                }
                val mean = sum / n
                // unedited gray must come back essentially unchanged
                (mean in 100..156 && max - min < 48) to
                    "mean=$mean range=$min..$max (source was 128 gray)"
            }

            // ---- check 2b: 3D LUT "not ready" path must not blacken the frame ----
            // P0 regression pin (2026-10-17): with no AI/look 3D LUT uploaded,
            // the sampler3D uniforms must still be bound to a complete 3D
            // texture (the 1x1x1 dummy on units 9/10). The old code left the
            // uniforms at their default (unit 0's 2D source texture), and
            // Mali drivers resolved that sampler-type mismatch by blackening
            // the WHOLE frame — even though the strength gate skips the
            // texture() call. So this check forces both LUTs unavailable,
            // renders a neutral edit, and requires non-black output.
            check("3D LUT not-ready path (P0 pin)") {
                val savedAi = aiLutSnapshot()
                val savedLook = lookLutSnapshot()
                try {
                    uploadAiLut(null, 0)
                    uploadLookLut(null, 0)
                    val px = renderOnce(emptyList(), EditState(masks = noMask))
                    var sum = 0L; var n = 0; var black = 0
                    for (y in 48..80 step 2) {
                        for (x in 48..80 step 2) {
                            val p = px[y][x]
                            val v = ((p shr 16) and 0xFF) +
                                ((p shr 8) and 0xFF) + (p and 0xFF)
                            sum += v; n++
                            if (v < 24) black++
                        }
                    }
                    val mean = sum.toDouble() / n
                    // 128-gray source -> per-pixel RGB sum ~384; a blackened
                    // frame would average ~0. Both LUTs unavailable must not
                    // move the needle.
                    (mean > 200.0 && black == 0) to
                        "meanRGBsum=${mean.toInt()} blackPx=$black/$n " +
                            "(128-gray source, both 3D LUTs unavailable)"
                } finally {
                    if (savedAi != null) uploadAiLut(savedAi.first, savedAi.second)
                    else uploadAiLut(null, 0)
                    if (savedLook != null) uploadLookLut(savedLook.first, savedLook.second)
                    else uploadLookLut(null, 0)
                }
            }

            // ---- check 3: masked local adjustment via uMaskTex[0] ----
            // Exercises the unrolled sampler-array path in the adjustment
            // branch — the code the Mali fix rewrote. A +1 EV mask over the
            // left half must brighten only that half.
            check("Masked adjustment (slot 0)") {
                val px = renderOnce(
                    listOf(halfMask),
                    EditState(masks = listOf(testMask("t0", LocalAdjust(exposure = 100f)))),
                )
                val left = regionMean(px, 16, 48)
                val right = regionMean(px, 80, 112)
                (left > 200 && right in 100.0..156.0 && left - right > 60) to
                    "left=${left.toInt()} right=${right.toInt()} " +
                        "(expected bright left, gray right)"
            }

            // ---- check 4: mask overlay via uMaskTex[0] ----
            // The red overlay must appear exactly where the mask is white.
            check("Mask overlay (slot 0)") {
                val px = renderOnce(
                    listOf(halfMask), EditState(masks = listOf(testMask("t0"))),
                    GlEngine.FrameOptions(overlayMask = 0, overlayAlpha = 1f),
                )
                val redOnMask = regionPassRate(px, 16, 48) { r, g, b ->
                    r >= 190 && r - g >= 80 && r - b >= 80
                }
                val cleanOffMask = regionPassRate(px, 80, 112) { r, g, b ->
                    kotlin.math.abs(r - g) <= 24 && kotlin.math.abs(g - b) <= 24
                }
                (redOnMask >= 0.8 && cleanOffMask >= 0.8) to
                    "red on mask=${(redOnMask * 100).roundToInt()}% " +
                        "clean off mask=${(cleanOffMask * 100).roundToInt()}%"
            }

            // ---- check 5: mask overlay via uMaskTex[3] ----
            // Covers the unrolled else-branch — the slot the object-removal
            // live overlay uses (Cycle 44).
            check("Mask overlay (slot 3)") {
                val px = renderOnce(
                    listOf(blackMask, blackMask, blackMask, halfMask),
                    EditState(masks = List(4) { i -> testMask("t$i") }),
                    GlEngine.FrameOptions(overlayMask = 3, overlayAlpha = 1f),
                )
                val redOnMask = regionPassRate(px, 16, 48) { r, g, b ->
                    r >= 190 && r - g >= 80 && r - b >= 80
                }
                val cleanOffMask = regionPassRate(px, 80, 112) { r, g, b ->
                    kotlin.math.abs(r - g) <= 24 && kotlin.math.abs(g - b) <= 24
                }
                (redOnMask >= 0.8 && cleanOffMask >= 0.8) to
                    "red on mask=${(redOnMask * 100).roundToInt()}% " +
                        "clean off mask=${(cleanOffMask * 100).roundToInt()}%"
            }

            // ---- check 5b: mask overlay in the reserved removal slot ----
            // Mirrors the real object-removal render path (EditorViewModel
            // budgets MAX_MASKS-1 user masks and puts the removal mask right
            // after them, so overlayMask == uMaskCount == 3). Regression
            // test for the cycle-58 fix: the old `< uMaskCount` guard kept
            // the removal overlay from ever rendering, and the slot's
            // texture was never bound to its unit at draw time.
            check("Mask overlay (reserved removal slot)") {
                val px = renderOnce(
                    listOf(blackMask, blackMask, blackMask, halfMask),
                    EditState(masks = List(3) { i -> testMask("t$i") }),
                    GlEngine.FrameOptions(overlayMask = 3, overlayAlpha = 1f),
                )
                val redOnMask = regionPassRate(px, 16, 48) { r, g, b ->
                    r >= 190 && r - g >= 80 && r - b >= 80
                }
                val cleanOffMask = regionPassRate(px, 80, 112) { r, g, b ->
                    kotlin.math.abs(r - g) <= 24 && kotlin.math.abs(g - b) <= 24
                }
                (redOnMask >= 0.8 && cleanOffMask >= 0.8) to
                    "red on mask=${(redOnMask * 100).roundToInt()}% " +
                        "clean off mask=${(cleanOffMask * 100).roundToInt()}%"
            }

            // ---- check 6: lens blur (uBlur texture path) ----
            // Exercises the Snapseed-style bokeh branch added later than the
            // mask fix: radial blur with focus at the center. The source is a
            // sharp black/white stripe pattern; pixels at the focus point must
            // stay sharp (high contrast) while pixels far from focus are mixed
            // toward the blurred guide texture (low contrast). A broken uBlur
            // binding would fail this (all-sharp or all-gray output).
            check("Lens blur") {
                // vertical stripes: 4px black, 4px white
                val stripes = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
                val stripePx = IntArray(s * s) { i ->
                    if (((i % s) / 4) % 2 == 0) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
                }
                stripes.setPixels(stripePx, 0, s, 0, 0, s, s)
                try {
                    setSource(stripes)
                    val px = renderOnce(
                        emptyList(),
                        EditState(optics = OpticsParams(
                            lensBlurAmount = 100f,
                            lensFocusX = 0.5f, lensFocusY = 0.5f,
                            lensSize = 15f, lensFeather = 50f, lensShape = 0,
                        )),
                    )
                    // mean |v - 128| over the red channel: ~128 for sharp
                    // stripes, near 0 for fully blurred gray
                    fun contrast(x0: Int, x1: Int, y0: Int, y1: Int): Double {
                        var sum = 0L
                        var n = 0
                        for (y in y0..y1 step 2) {
                            for (x in x0..x1 step 2) {
                                sum += kotlin.math.abs(
                                    (((px[y][x] shr 16) and 0xFF) - 128))
                                n++
                            }
                        }
                        return sum.toDouble() / n
                    }
                    val center = contrast(56, 72, 56, 72)
                    val corner = contrast(8, 24, 8, 24)
                    (center > 70.0 && corner < 45.0) to
                        "center contrast=${center.toInt()} " +
                            "(expected > 70, sharp at focus) " +
                            "corner contrast=${corner.toInt()} " +
                            "(expected < 45, blurred away from focus)"
                } finally {
                    setSource(gray)
                    stripes.recycle()
                }
            }

            // ---- check 7: before/after split view (uSplitX) ----
            // With the divider at NDC x=0 and +2 EV applied, the left half must
            // show the raw source (gray 128) while the right half is brightened.
            check("Split view") {
                val px = renderOnce(
                    emptyList(),
                    EditState(light = LightParams(exposure = 2f)),
                    GlEngine.FrameOptions(splitX = 0f),
                )
                val left = regionMean(px, 16, 48)
                val right = regionMean(px, 80, 112)
                (left in 110.0..146.0 && right > 235.0 && right - left > 100.0) to
                    "left=${left.toInt()} (expected ~128, unedited) " +
                        "right=${right.toInt()} (expected ~255, +2 EV)"
            }

            // ---- check 8: creative FX overlay (uFxTex / uFxLeak / uFxDust) ----
            // Exercises the light-leak + film-dust branch added after the mask
            // fix. SUNSET leak at 100% is an additive warm glow whose center
            // reaches full intensity, so the frame's peak red must far exceed
            // the gray baseline (orientation-independent peak test). Film
            // dust at 100% must change at least some pixels vs the same frame
            // with dust off (deterministic seed, differential test). A broken
            // uFxTex binding or zeroed leak/dust uniforms fails both parts.
            check("Creative FX overlay") {
                val leakOn = EditState(optics = OpticsParams(
                    fx = FxParams(leak = LightLeakStyle.SUNSET, leakAmount = 100f)))
                val leakOff = EditState(optics = OpticsParams(
                    fx = FxParams(leak = LightLeakStyle.SUNSET, leakAmount = 0f)))
                val pxOn = renderOnce(emptyList(), leakOn)
                val pxBase = renderOnce(emptyList(), leakOff)
                var maxR = 0
                var maxRBase = 0
                for (y in 0 until s step 2) {
                    for (x in 0 until s step 2) {
                        maxR = maxOf(maxR, (pxOn[y][x] shr 16) and 0xFF)
                        maxRBase = maxOf(maxRBase, (pxBase[y][x] shr 16) and 0xFF)
                    }
                }
                val leakOk = maxR >= 200 && maxR - maxRBase > 60

                val dustOn = EditState(optics = OpticsParams(
                    fx = FxParams(dust = 100f, dustSeed = 7L)))
                val dustOff = EditState(optics = OpticsParams(
                    fx = FxParams(dust = 0f, dustSeed = 7L)))
                val pxDust = renderOnce(emptyList(), dustOn)
                val pxDustBase = renderOnce(emptyList(), dustOff)
                var changed = 0
                for (y in 0 until s) {
                    for (x in 0 until s) {
                        val p = pxDust[y][x]
                        val q = pxDustBase[y][x]
                        if (kotlin.math.abs(((p shr 16) and 0xFF) - ((q shr 16) and 0xFF)) >= 3 ||
                            kotlin.math.abs(((p shr 8) and 0xFF) - ((q shr 8) and 0xFF)) >= 3 ||
                            kotlin.math.abs((p and 0xFF) - (q and 0xFF)) >= 3) changed++
                    }
                }
                val dustOk = changed >= 20
                (leakOk && dustOk) to
                    "leak peakR=$maxR (baseline $maxRBase, need >=200, diff>60); " +
                        "dust changed pixels=$changed (need >=20)"
            }

            // ---- check 9: B&W mask check view (uMaskViewMode) ----
            // The B&W check view must show the mask alone: left half (mask
            // white) renders ~255 grayscale with no red tint, right half
            // (mask black) renders ~0. Differential part: the creative FX
            // overlay (SUNSET leak at 100%) must not contaminate the check
            // view — B&W + leak renders (near-)identically to B&W + leak off.
            check("Mask view modes (B&W)") {
                val bwOpts = GlEngine.FrameOptions(
                    overlayMask = 0, overlayAlpha = 1f, maskViewMode = 1)
                val plain = EditState(masks = listOf(testMask("t0")))
                val fxOn = plain.copy(optics = OpticsParams(
                    fx = FxParams(leak = LightLeakStyle.SUNSET,
                        leakAmount = 100f)))
                val pxPlain = renderOnce(listOf(halfMask), plain, bwOpts)
                val pxFx = renderOnce(listOf(halfMask), fxOn, bwOpts)
                val leftMean = regionMean(pxPlain, 16, 48)
                val rightMean = regionMean(pxPlain, 80, 112)
                val grayLeft = regionPassRate(pxPlain, 16, 48) { r, g, b ->
                    kotlin.math.abs(r - g) <= 12 &&
                        kotlin.math.abs(g - b) <= 12
                }
                val maskOk = leftMean > 235 && rightMean < 20 &&
                    grayLeft >= 0.9
                var changed = 0
                for (y in 0 until s) {
                    for (x in 0 until s) {
                        val p = pxPlain[y][x]
                        val q = pxFx[y][x]
                        if (kotlin.math.abs(
                                ((p shr 16) and 0xFF) -
                                    ((q shr 16) and 0xFF)) >= 2 ||
                            kotlin.math.abs(
                                ((p shr 8) and 0xFF) -
                                    ((q shr 8) and 0xFF)) >= 2 ||
                            kotlin.math.abs(
                                (p and 0xFF) - (q and 0xFF)) >= 2) changed++
                    }
                }
                (maskOk && changed <= 4) to
                    "left=${leftMean.toInt()} (need >235) " +
                        "right=${rightMean.toInt()} (need <20) " +
                        "gray=${(grayLeft * 100).roundToInt()}% (need >=90%) " +
                        "fx-changed=$changed (need <=4)"
            }

            // ---- check 10: B&W conversion (uBw / uBwMix) ----
            // The dedicated black-and-white tool (Cycle 32) had no on-device
            // GPU check. Two-color source: left half pure red, right half
            // pure green. With bw=true and a neutral mix, both halves must
            // come back gray with Rec.709 ordering — green much brighter
            // than red (a naive average desaturation would give both ~85).
            // Then a RED +100 mix must lift the red half while leaving the
            // green half alone (per-range isolation of the uBwMix loop).
            check("B&W conversion (uBw/uBwMix)") {
                val bwSrc = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
                val bwPx = IntArray(s * s) { i ->
                    if ((i % s) < s / 2) 0xFFFF0000.toInt() else 0xFF00FF00.toInt()
                }
                bwSrc.setPixels(bwPx, 0, s, 0, 0, s, s)
                try {
                    setSource(bwSrc)
                    val neutral = EditState(color = ColorParams(bw = true))
                    val mixed = EditState(color = ColorParams(
                        bw = true, bwMix = mapOf("RED" to 100f)))
                    val pxN = renderOnce(emptyList(), neutral)
                    val pxM = renderOnce(emptyList(), mixed)
                    val grayN = regionPassRate(pxN, 16, 48) { r, g, b ->
                        kotlin.math.abs(r - g) <= 8 &&
                            kotlin.math.abs(g - b) <= 8
                    }
                    // regionMean samples the red channel == luminance here.
                    val redLuma = regionMean(pxN, 16, 48)
                    val greenLuma = regionMean(pxN, 80, 112)
                    val redLumaM = regionMean(pxM, 16, 48)
                    val greenLumaM = regionMean(pxM, 80, 112)
                    val ok = grayN >= 0.9 &&
                        redLuma in 40.0..70.0 &&
                        greenLuma in 165.0..200.0 &&
                        greenLuma - redLuma > 80.0 &&
                        redLumaM - redLuma > 40.0 &&
                        kotlin.math.abs(greenLumaM - greenLuma) <= 15.0
                    ok to "gray=${(grayN * 100).roundToInt()}% (need >=90%) " +
                        "red=${redLuma.toInt()}->${redLumaM.toInt()} " +
                        "(need 40..70, mix lift >40) " +
                        "green=${greenLuma.toInt()}->${greenLumaM.toInt()} " +
                        "(need 165..200, mix delta <=15, green-red >80)"
                } finally {
                    try { setSource(gray) } catch (_: Throwable) { }
                    bwSrc.recycle()
                }
            }

            // ---- check 11: tone curve (uCurves texture path) ----
            // The LUT travels CPU -> 256x1 RGBA8 texture -> uCurves sampler;
            // render() only binds that texture, so uploadCurves() is part of
            // the path this check exercises on the phone's own driver.
            // Gray-128 source. A midtone-lift master curve
            // (0,0),(0.5,0.7),(1,1) must lift the output toward ~178, while
            // a RED-only lift must move red alone and leave green/blue at
            // ~128 (per-channel isolation of the .g/.b/.a LUT channels).
            check("Tone curve (uCurves)") {
                fun chMean(
                    px: Array<IntArray>, x0: Int, x1: Int, shift: Int,
                ): Double {
                    var sum = 0L
                    var n = 0
                    for (y in 24..104 step 4) {
                        for (x in x0..x1 step 4) {
                            sum += (px[y][x] shr shift) and 0xFF
                            n++
                        }
                    }
                    return sum.toDouble() / n
                }
                val liftPts = listOf(
                    CurvePoint(0f, 0f),
                    CurvePoint(0.5f, 0.7f),
                    CurvePoint(1f, 1f),
                )
                val liftMaster = EditState(
                    light = LightParams(toneCurve = ToneCurve(liftPts)))
                val liftRed = EditState(
                    light = LightParams(redCurve = ToneCurve(liftPts)))
                try {
                    uploadCurves(liftMaster)
                    val pxM = renderOnce(emptyList(), liftMaster)
                    val masterLift = chMean(pxM, 48, 80, 16)
                    // Master curve must move ALL channels together: with the
                    // old cross-channel selector bug the lift hit red only
                    // (green/blue stayed ~128, i.e. a red cast).
                    val masterG = chMean(pxM, 48, 80, 8)
                    val masterB = chMean(pxM, 48, 80, 0)
                    uploadCurves(liftRed)
                    val pxR = renderOnce(emptyList(), liftRed)
                    val rLift = chMean(pxR, 48, 80, 16)
                    val gStay = chMean(pxR, 48, 80, 8)
                    val bStay = chMean(pxR, 48, 80, 0)
                    val ok = masterLift in 160.0..195.0 &&
                        masterG in 160.0..195.0 &&
                        masterB in 160.0..195.0 &&
                        rLift in 160.0..195.0 &&
                        kotlin.math.abs(gStay - 128.0) <= 15.0 &&
                        kotlin.math.abs(bStay - 128.0) <= 15.0
                    ok to "master-lift r=${masterLift.toInt()} " +
                        "g=${masterG.toInt()} b=${masterB.toInt()} " +
                        "(need all 160..195, no red cast) red-only " +
                        "r=${rLift.toInt()} g=${gStay.toInt()} " +
                        "b=${bStay.toInt()} (need r 160..195, g/b ~128)"
                } finally {
                    try { uploadCurves(EditState()) } catch (_: Throwable) { }
                }
            }

            // ---- check 12: HSL color mixer (uHsl[8] uniform array) ----
            // The mixer loop indexes a uniform vec3 array with the loop
            // variable — Mali-safe (never a sampler), but it was the last
            // major shader path with no on-device verification. Two-color
            // source: left half pure red, right half pure green.
            // (1) RED hue +100 shifts reds by +0.09 hue (~32 deg, so red ->
            // orange and the green channel must rise from ~0 to ~138),
            // while the green half stays byte-identical (weight 0 outside
            // the red range). (2) GREEN saturation -100 grays the green
            // half (>=90% pixels gray, red channel ~128) while the red
            // half stays byte-identical (per-range isolation of the loop).
            check("HSL color mixer (uHsl)") {
                fun chMean(
                    px: Array<IntArray>, x0: Int, x1: Int, shift: Int,
                ): Double {
                    var sum = 0L
                    var n = 0
                    for (y in 24..104 step 4) {
                        for (x in x0..x1 step 4) {
                            sum += (px[y][x] shr shift) and 0xFF
                            n++
                        }
                    }
                    return sum.toDouble() / n
                }
                fun changedCount(
                    a: Array<IntArray>, b: Array<IntArray>, x0: Int, x1: Int,
                ): Int {
                    var c = 0
                    for (y in 0 until s) {
                        for (x in x0..x1) {
                            if (a[y][x] != b[y][x]) c++
                        }
                    }
                    return c
                }
                val hslSrc =
                    Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
                val hslPx = IntArray(s * s) { i ->
                    if ((i % s) < s / 2) 0xFFFF0000.toInt()
                    else 0xFF00FF00.toInt()
                }
                hslSrc.setPixels(hslPx, 0, s, 0, 0, s, s)
                try {
                    setSource(hslSrc)
                    val base = EditState()
                    val hueShift = EditState(color = ColorParams(
                        mixer = mapOf("RED" to HslAdjust(hue = 100f))))
                    val desat = EditState(color = ColorParams(
                        mixer = mapOf("GREEN" to HslAdjust(saturation = -100f))))
                    val pxBase = renderOnce(emptyList(), base)
                    val pxHue = renderOnce(emptyList(), hueShift)
                    val pxSat = renderOnce(emptyList(), desat)
                    // (1) red half gains green (~138), green half untouched
                    val redG = chMean(pxHue, 16, 48, 8)
                    val redR = chMean(pxHue, 16, 48, 16)
                    val greenUntouched =
                        changedCount(pxBase, pxHue, 80, 112) <= 4
                    // (2) green half grays, red half untouched
                    val graySat = regionPassRate(pxSat, 80, 112) { r, g, b ->
                        kotlin.math.abs(r - g) <= 8 &&
                            kotlin.math.abs(g - b) <= 8
                    }
                    val greenGrayMean = chMean(pxSat, 80, 112, 16)
                    val redUntouched =
                        changedCount(pxBase, pxSat, 16, 48) <= 4
                    val ok = redG in 100.0..175.0 && redR >= 235.0 &&
                        greenUntouched && graySat >= 0.9 &&
                        greenGrayMean in 105.0..155.0 && redUntouched
                    ok to "red->orange g=${redG.toInt()} (need 100..175) " +
                        "green-untouched=$greenUntouched " +
                        "green->gray=${(graySat * 100).roundToInt()}% " +
                        "mean=${greenGrayMean.toInt()} " +
                        "(need >=90%, ~128) red-untouched=$redUntouched"
                } finally {
                    try { setSource(gray) } catch (_: Throwable) { }
                    hslSrc.recycle()
                }
            }

            // ---- check 13: color grading wheels (uGradeS/M/H/G + strength) ----
            // The grade wheels' vec3 uniforms are uploaded from wheelToRgb()
            // (0/100 red -> pure (1,0,0)); the shader adds
            // (uGrade* - 0.5) * 2 * weight * uGradeStrength * 0.55.
            // On 128-gray (lum 0.5): wGs = wGh = 0, wGm = 1, so the SHADOWS
            // and HIGHLIGHTS wheels must change nothing while GLOBAL and
            // MIDTONES wheels tint the frame fully red; blending 50 must
            // scale the tint by exactly half. A broken upload (e.g. a
            // uniform missing from cacheUniforms, the cycle-36 bug class)
            // would leave the wheels at neutral (0.5,0.5,0.5) and fail here.
            check("Color grading wheels (uGrade)") {
                fun chMean(
                    px: Array<IntArray>, x0: Int, x1: Int, shift: Int,
                ): Double {
                    var sum = 0L
                    var n = 0
                    for (y in 24..104 step 4) {
                        for (x in x0..x1 step 4) {
                            sum += (px[y][x] shr shift) and 0xFF
                            n++
                        }
                    }
                    return sum.toDouble() / n
                }
                fun changedCount(
                    a: Array<IntArray>, b: Array<IntArray>, x0: Int, x1: Int,
                ): Int {
                    var c = 0
                    for (y in 0 until s) {
                        for (x in x0..x1) {
                            if (a[y][x] != b[y][x]) c++
                        }
                    }
                    return c
                }
                val red = GradeWheel(0f, 100f)
                val base = EditState()
                val globalRed = EditState(
                    grading = GradingParams(global = red))
                val midRed = EditState(
                    grading = GradingParams(midtones = red))
                val shadowsRed = EditState(
                    grading = GradingParams(shadows = red))
                val highlightsRed = EditState(
                    grading = GradingParams(highlights = red))
                val halfRed = EditState(
                    grading = GradingParams(global = red, blending = 50f))
                val pxBase = renderOnce(emptyList(), base)
                val pxGlobal = renderOnce(emptyList(), globalRed)
                val pxMid = renderOnce(emptyList(), midRed)
                val pxShad = renderOnce(emptyList(), shadowsRed)
                val pxHigh = renderOnce(emptyList(), highlightsRed)
                val pxHalf = renderOnce(emptyList(), halfRed)
                // (1)/(2) global + midtones: 0.5 + (1,-1,-1)*0.55 -> (255,0,0)
                val gR = chMean(pxGlobal, 16, 48, 16)
                val gG = chMean(pxGlobal, 16, 48, 8)
                val gB = chMean(pxGlobal, 16, 48, 0)
                val mR = chMean(pxMid, 16, 48, 16)
                val mG = chMean(pxMid, 16, 48, 8)
                val mB = chMean(pxMid, 16, 48, 0)
                // (3)/(4) shadows/highlights: zero weight at lum 0.5
                val shadUntouched =
                    changedCount(pxBase, pxShad, 16, 112) <= 4
                val highUntouched =
                    changedCount(pxBase, pxHigh, 16, 112) <= 4
                // (5) blending 50: 0.5 + (1,-1,-1)*0.5*0.55 -> (198,57,57)
                val hR = chMean(pxHalf, 16, 48, 16)
                val hG = chMean(pxHalf, 16, 48, 8)
                val hB = chMean(pxHalf, 16, 48, 0)
                val ok = gR >= 235.0 && gG <= 45.0 && gB <= 45.0 &&
                    mR >= 235.0 && mG <= 45.0 && mB <= 45.0 &&
                    shadUntouched && highUntouched &&
                    hR in 185.0..212.0 && hG in 40.0..75.0 &&
                    hB in 40.0..75.0
                ok to "global r=${gR.toInt()} g=${gG.toInt()} b=${gB.toInt()} " +
                    "(need r>=235 g/b<=45) mid r=${mR.toInt()} " +
                    "g=${mG.toInt()} b=${mB.toInt()} " +
                    "shadows-untouched=$shadUntouched " +
                    "highlights-untouched=$highUntouched " +
                    "half r=${hR.toInt()} g=${hG.toInt()} b=${hB.toInt()} " +
                    "(need r 185..212 g/b 40..75)"
            }

            // ---- check 14: vignette + film grain (uVignette / uGrain) ----
            // The last major "creative finishing" shader path with no
            // on-device coverage. Vignette math (defaults: midpoint 50,
            // feather 50, roundness 0): vd = |(uv-0.5)| * 1.42,
            // mask = smoothstep(0.45, 1.075, vd), c *= (1 - amt*mask*0.85).
            // At amount +100 the corner (5,5): vd=0.918, mask=0.842,
            // factor 0.285 -> 128-gray goes to ~36, while the center
            // (vd~0) stays 128. A broken uVignette upload would leave
            // corners untouched; a sign/flipped formula would fail the
            // darkening direction. Grain (amount 100, roughness 50):
            // gAmt = 0.16*mix(1,1-luma,0.35) = 0.132, offset std per
            // channel ~0.132*0.416*255 ~= 14 levels, mean unchanged (~128).
            // Two independent hash taps exercise uTime as well.
            check("Vignette + film grain (uVignette/uGrain)") {
                fun regionStats(
                    px: Array<IntArray>, x0: Int, x1: Int, y0: Int, y1: Int,
                ): Pair<Double, Double> {
                    var sum = 0L
                    var sum2 = 0L
                    var n = 0
                    for (y in y0..y1 step 2) {
                        for (x in x0..x1 step 2) {
                            val v = (px[y][x] shr 16) and 0xFF
                            sum += v; sum2 += v.toLong() * v; n++
                        }
                    }
                    val mean = sum.toDouble() / n
                    val variance = sum2.toDouble() / n - mean * mean
                    return mean to kotlin.math.sqrt(variance.coerceAtLeast(0.0))
                }
                val base = EditState()
                val vig = EditState(
                    optics = OpticsParams(vignetteAmount = 100f))
                val grain = EditState(
                    optics = OpticsParams(grainAmount = 100f))
                val pxBase = renderOnce(emptyList(), base)
                val pxVig = renderOnce(emptyList(), vig)
                val pxGrain = renderOnce(emptyList(), grain)
                val (centerMean, _) = regionStats(pxVig, 48, 80, 48, 80)
                val (cornerMean, _) = regionStats(pxVig, 2, 10, 2, 10)
                val (baseMean, baseStd) = regionStats(pxBase, 24, 104, 24, 104)
                val (grainMean, grainStd) =
                    regionStats(pxGrain, 24, 104, 24, 104)
                val ok = centerMean in 120.0..136.0 &&
                    cornerMean <= 70.0 &&
                    (centerMean - cornerMean) >= 40.0 &&
                    baseStd < 2.0 &&
                    grainMean in 115.0..141.0 &&
                    grainStd > 6.0
                ok to "vignette center=${centerMean.toInt()} " +
                    "(need 120..136) corner=${cornerMean.toInt()} " +
                    "(need <=70, delta>=40) base mean/std=" +
                    "${baseMean.toInt()}/${baseStd.toInt()} " +
                    "grain mean/std=${grainMean.toInt()}/${grainStd.toInt()} " +
                    "(need mean 115..141 std>6)"
            }

            // ---- check 15: light controls (uExposure..uBlacks) ----
            // The core Light panel block — the uniforms the Auto-light
            // button (Cycle 20) and Auto Enhance drive — had no on-device
            // coverage. Two-tone source: left half 32-gray, right half
            // 224-gray. Six isolated single-param renders, each proving
            // its uniform is uploaded AND routed to the right pixels:
            // exposure doubles both halves; contrast (+100 => x2 pivot
            // 0.5) crushes dark / blows out light; highlights -100 darkens
            // only the bright half (wH weight); shadows +100 lifts only
            // the dark half (wS weight); whites -100 trims only the bright
            // half (wW weight); blacks -100 deepens only the dark half
            // (wB weight). A missing cacheUniforms entry (the Cycle-36 bug
            // class) would leave every render at the baseline and fail.
            check("Light controls (uExposure..uBlacks)") {
                val toneSrc = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
                val tonePx = IntArray(s * s) { i ->
                    if ((i % s) < s / 2) 0xFF202020.toInt() else 0xFFE0E0E0.toInt()
                }
                toneSrc.setPixels(tonePx, 0, s, 0, 0, s, s)
                try {
                    setSource(toneSrc)
                    val base = EditState()
                    val exp = EditState(light = LightParams(exposure = 1f))
                    val con = EditState(light = LightParams(contrast = 100f))
                    val hi = EditState(light = LightParams(highlights = -100f))
                    val sh = EditState(light = LightParams(shadows = 100f))
                    val wh = EditState(light = LightParams(whites = -100f))
                    val bl = EditState(light = LightParams(blacks = -100f))
                    val pxB = renderOnce(emptyList(), base)
                    val pxE = renderOnce(emptyList(), exp)
                    val pxC = renderOnce(emptyList(), con)
                    val pxH = renderOnce(emptyList(), hi)
                    val pxS = renderOnce(emptyList(), sh)
                    val pxW = renderOnce(emptyList(), wh)
                    val pxK = renderOnce(emptyList(), bl)
                    val lB = regionMean(pxB, 8, 56)
                    val rB = regionMean(pxB, 72, 120)
                    val lE = regionMean(pxE, 8, 56)
                    val rE = regionMean(pxE, 72, 120)
                    val lC = regionMean(pxC, 8, 56)
                    val rC = regionMean(pxC, 72, 120)
                    val lH = regionMean(pxH, 8, 56)
                    val rH = regionMean(pxH, 72, 120)
                    val lS = regionMean(pxS, 8, 56)
                    val rS = regionMean(pxS, 72, 120)
                    val lW = regionMean(pxW, 8, 56)
                    val rW = regionMean(pxW, 72, 120)
                    val lK = regionMean(pxK, 8, 56)
                    val rK = regionMean(pxK, 72, 120)
                    val ok = lB in 24.0..40.0 && rB in 216.0..232.0 &&
                        lE in 52.0..76.0 && rE > 235.0 &&
                        lC < 32.0 && rC > 235.0 &&
                        lH in 24.0..40.0 && rH in 75.0..110.0 &&
                        lS in 64.0..90.0 && rS in 210.0..238.0 &&
                        lW in 24.0..40.0 && rW in 138.0..170.0 &&
                        lK in 10.0..29.0 && rK in 210.0..238.0
                    ok to "base=${lB.toInt()}/${rB.toInt()} " +
                        "(need 24..40 / 216..232) " +
                        "exp=${lE.toInt()}/${rE.toInt()} " +
                        "(need 52..76 / >235) " +
                        "con=${lC.toInt()}/${rC.toInt()} " +
                        "(need <32 / >235) " +
                        "hi=${lH.toInt()}/${rH.toInt()} " +
                        "(need 24..40 / 75..110) " +
                        "sh=${lS.toInt()}/${rS.toInt()} " +
                        "(need 64..90 / 210..238) " +
                        "wh=${lW.toInt()}/${rW.toInt()} " +
                        "(need 24..40 / 138..170) " +
                        "bl=${lK.toInt()}/${rK.toInt()} " +
                        "(need 10..29 / 210..238)"
                } finally {
                    try { setSource(gray) } catch (_: Throwable) { }
                    toneSrc.recycle()
                }
            }

            // ---- check 16: white balance (uTemp/uTint) ----
            // The White Balance sliders (Temp/Tint) — among the most-used
            // Color panel controls — had no on-device GPU self-test; checks
            // 0-15 covered masks, overlay, lens blur, split view, creative
            // FX, B&W, tone curve, HSL mixer, grading wheels, vignette+grain
            // and light controls. Four isolated single-param renders on the
            // 128-gray source (still set; no source swap needed):
            // temp +100 (uTemp=1.0): r*=1.42 -> ~182, b*=0.58 -> ~74,
            // g untouched -> ~128 (proves uTemp upload + correct channel
            // direction); temp -100 mirrors it; tint +100 is the magenta
            // direction (Lightroom convention: g*=0.70 -> ~90, r/b
            // untouched); tint -100 is the green direction (g*=1.30 ->
            // ~166). A missing cacheUniforms entry (the Cycle-36
            // bug class) would leave every render at the ~128 baseline and
            // fail all four bands.
            check("White balance (uTemp/uTint)") {
                fun channelMean(
                    px: Array<IntArray>, x0: Int, x1: Int, shift: Int,
                ): Double {
                    var sum = 0L
                    var n = 0
                    for (y in 24..104 step 4) {
                        for (x in x0..x1 step 4) {
                            sum += (px[y][x] shr shift) and 0xFF
                            n++
                        }
                    }
                    return sum.toDouble() / n
                }
                val warm = EditState(light = LightParams(temp = 100f))
                val cool = EditState(light = LightParams(temp = -100f))
                val tintP = EditState(light = LightParams(tint = 100f))
                val tintN = EditState(light = LightParams(tint = -100f))
                val pxW = renderOnce(emptyList(), warm)
                val pxC = renderOnce(emptyList(), cool)
                val pxP = renderOnce(emptyList(), tintP)
                val pxN = renderOnce(emptyList(), tintN)
                val wR = channelMean(pxW, 24, 104, 16)
                val wG = channelMean(pxW, 24, 104, 8)
                val wB = channelMean(pxW, 24, 104, 0)
                val cR = channelMean(pxC, 24, 104, 16)
                val cG = channelMean(pxC, 24, 104, 8)
                val cB = channelMean(pxC, 24, 104, 0)
                val pR = channelMean(pxP, 24, 104, 16)
                val pG = channelMean(pxP, 24, 104, 8)
                val pB = channelMean(pxP, 24, 104, 0)
                val nR = channelMean(pxN, 24, 104, 16)
                val nG = channelMean(pxN, 24, 104, 8)
                val nB = channelMean(pxN, 24, 104, 0)
                val ok = wR in 168.0..196.0 && wG in 110.0..146.0 &&
                    wB in 60.0..88.0 &&
                    cR in 60.0..88.0 && cG in 110.0..146.0 &&
                    cB in 168.0..196.0 &&
                    pR in 110.0..146.0 && pG in 78.0..102.0 &&
                    pB in 110.0..146.0 &&
                    nR in 110.0..146.0 && nG in 154.0..178.0 &&
                    nB in 110.0..146.0
                ok to "warm=${wR.toInt()}/${wG.toInt()}/${wB.toInt()} " +
                    "(need 168..196 / 110..146 / 60..88) " +
                    "cool=${cR.toInt()}/${cG.toInt()}/${cB.toInt()} " +
                    "(need 60..88 / 110..146 / 168..196) " +
                    "tint+=${pR.toInt()}/${pG.toInt()}/${pB.toInt()} " +
                    "(need 110..146 / 78..102 / 110..146) " +
                    "tint-=${nR.toInt()}/${nG.toInt()}/${nB.toInt()} " +
                    "(need 110..146 / 154..178 / 110..146)"
            }

            // ---- check 17: retouch overlay (uRetouch / uHasRetouch) ----
            // The retouch composite (heal/clone/skin overlays the user paints
            // — cycle 54's heal-fix area) had no on-device GPU self-test;
            // checks 0-16 covered masks, overlay, lens blur, split view,
            // creative FX, B&W, tone curve, HSL mixer, grading wheels,
            // vignette+grain, light controls and white balance. Synthetic
            // overlay: left half opaque red, right half transparent.
            // With one dummy spot (uHasRetouch=1) the left half must turn red
            // and the right stay gray (proves uRetouch upload + straight-
            // alpha composite); with no spots the still-bound overlay must
            // be ignored everywhere (proves the uHasRetouch flag gates the
            // branch — a stuck-1 flag, the Cycle-36 bug class, would paint
            // red on the left).
            check("Retouch overlay (uRetouch/uHasRetouch)") {
                val overlay =
                    Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
                val rtPx = IntArray(s * s) { i ->
                    if ((i % s) < s / 2) 0xFFFF0000.toInt() else 0x00000000
                }
                overlay.setPixels(rtPx, 0, s, 0, 0, s, s)
                try {
                    uploadRetouch(overlay)
                    val spot = RetouchSpot(
                        op = RetouchOp.CLONE,
                        x = 0.5f, y = 0.5f, srcX = 0.5f, srcY = 0.5f,
                        size = 0.2f,
                    )
                    val withSpot = EditState(
                        masks = noMask,
                        retouch = RetouchState(spots = listOf(spot)),
                    )
                    val pxOn = renderOnce(emptyList(), withSpot)
                    val pxOff = renderOnce(
                        emptyList(), EditState(masks = noMask))
                    val leftRed = regionPassRate(pxOn, 16, 48) { r, g, b ->
                        r >= 200 && g <= 60 && b <= 60
                    }
                    val rightGray = regionPassRate(pxOn, 80, 112) { r, g, b ->
                        kotlin.math.abs(r - g) <= 24 &&
                            kotlin.math.abs(g - b) <= 24
                    }
                    val ignored = regionPassRate(pxOff, 16, 48) { r, g, b ->
                        kotlin.math.abs(r - g) <= 24 &&
                            kotlin.math.abs(g - b) <= 24
                    }
                    (leftRed >= 0.9 && rightGray >= 0.9 && ignored >= 0.9) to
                        "flag on: red-left=${(leftRed * 100).roundToInt()}% " +
                            "gray-right=${(rightGray * 100).roundToInt()}% " +
                            "flag off: left stays gray " +
                            "${(ignored * 100).roundToInt()}%"
                } finally {
                    uploadRetouch(null)
                    overlay.recycle()
                }
            }

            // ---- check 18: saturation & vibrance (uSaturation / uVibrance) ----
            // The two most-used Color-panel sliders had no on-device GPU
            // self-test; checks 0-17 covered masks, overlay, lens blur, split
            // view, creative FX, B&W, tone curve, HSL mixer, grading wheels,
            // vignette+grain, light controls, white balance and retouch. The
            // 128-gray source is useless here (achromatic pixels are fixed
            // points of both ops), so a two-tone color source is used: left
            // half muted salmon (150,110,110), right half strong red
            // (170,50,50). sat+100 must push the strong red to the 255 clamp
            // and lift the salmon; sat-100 must collapse both to gray; vib+100
            // must boost the MUTED salmon more than the strong red (proves
            // the (1.15-sat) adaptive weighting, not just the upload). A
            // missing cacheUniforms entry (the Cycle-36 bug class) would leave
            // every render at baseline and fail all bands.
            check("Saturation & vibrance (uSaturation/uVibrance)") {
                val colorBmp =
                    Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
                val colorPx = IntArray(s * s) { i ->
                    if ((i % s) < s / 2) Color.rgb(150, 110, 110)
                    else Color.rgb(170, 50, 50)
                }
                colorBmp.setPixels(colorPx, 0, s, 0, 0, s, s)
                try {
                    setSource(colorBmp)
                    fun redMean(
                        px: Array<IntArray>, x0: Int, x1: Int,
                    ): Double {
                        var sum = 0L
                        var n = 0
                        for (y in 24..104 step 4) {
                            for (x in x0..x1 step 4) {
                                sum += (px[y][x] shr 16) and 0xFF
                                n++
                            }
                        }
                        return sum.toDouble() / n
                    }
                    val pxBase = renderOnce(emptyList(), EditState())
                    val pxSatP = renderOnce(emptyList(),
                        EditState(color = ColorParams(saturation = 100f)))
                    val pxSatN = renderOnce(emptyList(),
                        EditState(color = ColorParams(saturation = -100f)))
                    val pxVibP = renderOnce(emptyList(),
                        EditState(color = ColorParams(vibrance = 100f)))
                    val bL = redMean(pxBase, 16, 48)
                    val bR = redMean(pxBase, 80, 112)
                    val sPL = redMean(pxSatP, 16, 48)
                    val sPR = redMean(pxSatP, 80, 112)
                    val sNL = redMean(pxSatN, 16, 48)
                    val sNR = redMean(pxSatN, 80, 112)
                    val vPL = redMean(pxVibP, 16, 48)
                    val vPR = redMean(pxVibP, 80, 112)
                    // Bands from a standalone Kotlin harness of the exact
                    // shader formulas (sat/vib mixes on Rec.709 luma), ±16
                    // slack for hsl round-trip + readback jitter.
                    val ok = bL in 134.0..166.0 && bR in 154.0..186.0 &&
                        sPL in 165.0..197.0 && sPR >= 245.0 &&
                        sNL in 103.0..135.0 && sNR in 60.0..92.0 &&
                        vPL in 172.0..204.0 && vPR in 223.0..250.0
                    ok to "base=${bL.toInt()}/${bR.toInt()} " +
                        "(need 134..166/154..186) " +
                        "sat+=${sPL.toInt()}/${sPR.toInt()} " +
                        "(need 165..197/>=245) " +
                        "sat-=${sNL.toInt()}/${sNR.toInt()} " +
                        "(need 103..135/60..92) " +
                        "vib+=${vPL.toInt()}/${vPR.toInt()} " +
                        "(need 172..204/223..250)"
                } finally {
                    setSource(gray)
                    colorBmp.recycle()
                }
            }

            // ---- check 19: detail — clarity / texture / dehaze ----
            // The last high-traffic Light-panel block with no on-device
            // GPU self-test: uClarity / uTextureAmt / uDehaze (the
            // sharpen+NR block is the only one left). Clarity and texture
            // act on hp = c - blurC, so the source is a 4px-cell achromatic
            // checkerboard: the 1/8-scale gaussian guide averages every
            // cell to ~125, making hp = +/-25/255 deterministic without
            // edge-band hunting. Dehaze is saturation (1+dz*0.35) + contrast
            // (1+dz*0.30 about 0.5) on the two-tone salmon/red source.
            // A missing cacheUniforms entry (the Cycle-36 bug class) would
            // leave every render at baseline and fail all bands.
            check("Detail: clarity + texture + dehaze (uClarity/uTextureAmt/uDehaze)") {
                val cell = 4
                val checkBmp =
                    Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
                val cbPx = IntArray(s * s) { i ->
                    val dark = ((i % s) / cell + (i / s) / cell) % 2 == 0
                    if (dark) Color.rgb(100, 100, 100)
                    else Color.rgb(150, 150, 150)
                }
                checkBmp.setPixels(cbPx, 0, s, 0, 0, s, s)
                val dzBmp =
                    Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
                val dzPx = IntArray(s * s) { i ->
                    if ((i % s) < s / 2) Color.rgb(150, 110, 110)
                    else Color.rgb(170, 50, 50)
                }
                dzBmp.setPixels(dzPx, 0, s, 0, 0, s, s)
                try {
                    setSource(checkBmp)
                    fun setMean(px: Array<IntArray>, dark: Boolean): Double {
                        var sum = 0L
                        var n = 0
                        for (y in 8..120 step 2) {
                            for (x in 8..120 step 2) {
                                if ((((x / cell) + (y / cell)) % 2 == 0) == dark) {
                                    sum += (px[y][x] shr 16) and 0xFF
                                    n++
                                }
                            }
                        }
                        return sum.toDouble() / n
                    }
                    val pxBase = renderOnce(emptyList(), EditState())
                    val pxCla = renderOnce(emptyList(),
                        EditState(light = LightParams(clarity = 100f)))
                    val pxTex = renderOnce(emptyList(),
                        EditState(light = LightParams(texture = 100f)))
                    val bD = setMean(pxBase, true)
                    val bB = setMean(pxBase, false)
                    val cD = setMean(pxCla, true)
                    val cB = setMean(pxCla, false)
                    val tD = setMean(pxTex, true)
                    val tB = setMean(pxTex, false)
                    // Bands from a standalone Kotlin harness of the exact
                    // shader formulas (hp +/-25/255 around the 125 guide),
                    // +-14 slack for guide quantization + readback jitter.
                    val okCT = bD in 92.0..108.0 && bB in 142.0..158.0 &&
                        cD in 57.0..85.0 && cB in 165.0..193.0 &&
                        tD in 72.0..100.0 && tB in 150.0..178.0 &&
                        (cB - cD) >= 60.0 && (tB - tD) >= 60.0

                    setSource(dzBmp)
                    fun chanMean(
                        px: Array<IntArray>, x0: Int, x1: Int, shift: Int,
                    ): Double {
                        var sum = 0L
                        var n = 0
                        for (y in 24..104 step 4) {
                            for (x in x0..x1 step 4) {
                                sum += (px[y][x] shr shift) and 0xFF
                                n++
                            }
                        }
                        return sum.toDouble() / n
                    }
                    val pxDzB = renderOnce(emptyList(), EditState())
                    val pxDzP = renderOnce(emptyList(),
                        EditState(light = LightParams(dehaze = 100f)))
                    val pxDzN = renderOnce(emptyList(),
                        EditState(light = LightParams(dehaze = -100f)))
                    val dzBL = chanMean(pxDzB, 16, 48, 16)
                    val dzBR = chanMean(pxDzB, 80, 112, 16)
                    val dzPL = chanMean(pxDzP, 16, 48, 16)
                    val dzPR = chanMean(pxDzP, 80, 112, 16)
                    val dzPG = chanMean(pxDzP, 80, 112, 8)
                    val dzNR = chanMean(pxDzN, 80, 112, 16)
                    val dzNG = chanMean(pxDzN, 80, 112, 8)
                    // dehaze+100: salmon r->171, strong red r->225 g->15;
                    // dehaze-100: strong red r->134 g->79. Bands from the
                    // standalone harness (Rec.709 luma), +-14 slack.
                    val okDz = dzBL in 136.0..164.0 && dzBR in 156.0..184.0 &&
                        dzPL in 157.0..185.0 && dzPR in 211.0..239.0 &&
                        dzPG in 1.0..29.0 && dzNR in 120.0..148.0 &&
                        dzNG in 65.0..93.0

                    (okCT && okDz) to "checker base=${bD.toInt()}/${bB.toInt()} " +
                        "(need 92..108/142..158) " +
                        "clarity+=${cD.toInt()}/${cB.toInt()} " +
                        "(need 57..85/165..193, gap>=60) " +
                        "texture+=${tD.toInt()}/${tB.toInt()} " +
                        "(need 72..100/150..178, gap>=60) " +
                        "dehaze base=${dzBL.toInt()}/${dzBR.toInt()} " +
                        "(need 136..164/156..184) " +
                        "dehaze+=${dzPL.toInt()}/${dzPR.toInt()}/${dzPG.toInt()} " +
                        "(need 157..185/211..239/1..29) " +
                        "dehaze-=${dzNR.toInt()}/${dzNG.toInt()} " +
                        "(need 120..148/65..93)"
                } finally {
                    setSource(gray)
                    checkBmp.recycle()
                    dzBmp.recycle()
                }
            }

            // ---- check 20: sharpen + noise reduction ----
            // The last high-traffic Detail-panel block with no on-device
            // GPU self-test: uSharpen / uSharpMasking / uLumNr / uColorNr.
            // Sharpening adds edge * uSharpen * 2.2 (edge = luma - blurred
            // guide) with masking=0; masking=100 gates every edge on this
            // smooth checker (edgeGate=0 since |edge|=0.098 < 0.35), so the
            // masked render must equal baseline — a missing cacheUniforms
            // entry (the Cycle-36 bug class) would leave the sharpened
            // render at baseline too. Luminance NR pulls luma 85% toward the
            // guide (checker gap 50 -> ~8). Color NR needs chroma variance,
            // so it runs on a 2px red checker with an explicit colorNr=0
            // baseline (the neutral default is 25, not 0): colorNr=100 drags
            // the two reds apart (200->186 / 200->214) toward the mean
            // chroma — the differential proves the uniform uploads.
            check("Sharpen + noise reduction (uSharpen/uSharpMasking/uSharpRadius/uSharpDetail/uLumNr/uLumNrDetail/uColorNr/uColorNrDetail)") {
                val cell = 4
                val sharpBmp =
                    Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
                val shPx = IntArray(s * s) { i ->
                    val dark = ((i % s) / cell + (i / s) / cell) % 2 == 0
                    if (dark) Color.rgb(100, 100, 100)
                    else Color.rgb(150, 150, 150)
                }
                sharpBmp.setPixels(shPx, 0, s, 0, 0, s, s)
                val cc = 2
                val cnrBmp =
                    Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
                val cnrPx = IntArray(s * s) { i ->
                    if ((((i % s) / cc) + (i / s) / cc) % 2 == 0)
                        Color.rgb(200, 60, 60)
                    else Color.rgb(200, 100, 100)
                }
                cnrBmp.setPixels(cnrPx, 0, s, 0, 0, s, s)
                try {
                    setSource(sharpBmp)
                    fun cellMean(px: Array<IntArray>, dark: Boolean): Double {
                        var sum = 0L
                        var n = 0
                        for (y in 8..120 step 2) {
                            for (x in 8..120 step 2) {
                                if ((((x / cell) + (y / cell)) % 2 == 0) == dark) {
                                    sum += (px[y][x] shr 16) and 0xFF
                                    n++
                                }
                            }
                        }
                        return sum.toDouble() / n
                    }
                    val pxBase = renderOnce(emptyList(), EditState())
                    val pxSharp = renderOnce(emptyList(),
                        EditState(detail = DetailParams(sharpening = 100f)))
                    val pxMask = renderOnce(emptyList(),
                        EditState(detail = DetailParams(
                            sharpening = 100f, masking = 100f)))
                    val pxLum = renderOnce(emptyList(),
                        EditState(detail = DetailParams(luminanceNr = 100f)))
                    val bD = cellMean(pxBase, true)
                    val bB = cellMean(pxBase, false)
                    val sD = cellMean(pxSharp, true)
                    val sB = cellMean(pxSharp, false)
                    val mD = cellMean(pxMask, true)
                    val mB = cellMean(pxMask, false)
                    val nD = cellMean(pxLum, true)
                    val nB = cellMean(pxLum, false)
                    // sharpening +100: edge +/-25/255 * 2.2 = +/-55 levels
                    // -> 45/205; masking 100 gates it back to baseline.
                    val okSharp = bD in 92.0..108.0 && bB in 142.0..158.0 &&
                        sD in 31.0..59.0 && sB in 191.0..219.0 &&
                        (sB - sD) >= 120.0 &&
                        mD in 85.0..115.0 && mB in 135.0..165.0
                    // luminance NR 100: luma pulled 85% to the 125 guide
                    // -> 121/129, gap ~8 (stuck-0 uniform leaves gap ~50).
                    val okLum = nD in 105.0..137.0 && nB in 113.0..145.0 &&
                        (nB - nD) <= 24.0

                    setSource(cnrBmp)
                    fun redMean(px: Array<IntArray>, typeA: Boolean): Double {
                        var sum = 0L
                        var n = 0
                        for (y in 16..112 step 4) {
                            for (x in 16..112 step 4) {
                                if ((((x / cc) + (y / cc)) % 2 == 0) == typeA) {
                                    sum += (px[y][x] shr 16) and 0xFF
                                    n++
                                }
                            }
                        }
                        return sum.toDouble() / n
                    }
                    val pxC0 = renderOnce(emptyList(),
                        EditState(detail = DetailParams(colorNr = 0f)))
                    val pxC1 = renderOnce(emptyList(),
                        EditState(detail = DetailParams(colorNr = 100f)))
                    val rA0 = redMean(pxC0, true)
                    val rB0 = redMean(pxC0, false)
                    val rA1 = redMean(pxC1, true)
                    val rB1 = redMean(pxC1, false)
                    // color NR drags both reds toward the mean chroma:
                    // 200->186 / 200->214 (harness-verified).
                    val okCnr = rA0 in 192.0..208.0 && rB0 in 192.0..208.0 &&
                        (rB1 - rA1) >= 14.0 &&
                        rA1 in 172.0..200.0 && rB1 in 202.0..226.0

                    (okSharp && okLum && okCnr) to "base=${bD.toInt()}/${bB.toInt()} " +
                        "(need 92..108/142..158) sharp+=${sD.toInt()}/${sB.toInt()} " +
                        "(need 31..59/191..219, gap>=120) masked=${mD.toInt()}/${mB.toInt()} " +
                        "(need 85..115/135..165) lumNR=${nD.toInt()}/${nB.toInt()} " +
                        "(need 105..137/113..145, gap<=24) colorNR base=${rA0.toInt()}/${rB0.toInt()} " +
                        "(need 192..208) nr100=${rA1.toInt()}/${rB1.toInt()} (diff>=14)"
                } finally {
                    setSource(gray)
                    sharpBmp.recycle()
                    cnrBmp.recycle()
                }
            }

            // ---- check 21: optics distortion + defringe ----
            // The last two Optics-panel tools with no on-device coverage: a
            // stuck-0 uniform upload would leave both silently doing nothing
            // (the "tool does nothing" failure class). Distortion moves the
            // uImage sample position; defringe offsets the R/B channels.
            check("Optics: distortion + defringe (uDistortion/uDefringe)") {
                val gradBmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
                val gPx = IntArray(s * s) { i ->
                    val v = (i % s) * 2
                    Color.rgb(v, v, v)
                }
                gradBmp.setPixels(gPx, 0, s, 0, 0, s, s)
                // left pure red / right pure blue, edge at x=96
                val rbBmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
                val rbPx = IntArray(s * s) { i ->
                    if ((i % s) < 96) Color.rgb(255, 0, 0)
                    else Color.rgb(0, 0, 255)
                }
                rbBmp.setPixels(rbPx, 0, s, 0, 0, s, s)
                try {
                    setSource(gradBmp)
                    val pxD0 = renderOnce(emptyList(), EditState())
                    val pxDP = renderOnce(emptyList(),
                        EditState(optics = OpticsParams(distortion = 100f)))
                    val pxDM = renderOnce(emptyList(),
                        EditState(optics = OpticsParams(distortion = -100f)))
                    // probe x 28..36 (left of center): +distortion pulls the
                    // sample toward the darker left, -distortion toward the
                    // brighter right. Harness: base 63..64, +100 -> 50..52,
                    // -100 -> 75..76 (both uv-phase hypotheses); bands +-12,
                    // the >=7 differentials prove the uniform is live.
                    val d0 = regionMean(pxD0, 28, 36)
                    val dP = regionMean(pxDP, 28, 36)
                    val dM = regionMean(pxDM, 28, 36)
                    val okDist = d0 in 51.0..75.0 && dP in 38.0..62.0 &&
                        dM in 64.0..88.0 &&
                        (d0 - dP) >= 7.0 && (dM - d0) >= 7.0

                    setSource(rbBmp)
                    // red-channel edge position: first x in 88..102 with
                    // r < 200. Defringe shifts the R sample ~2 texels toward
                    // the blue side, so the red edge moves left; the shift
                    // is rigid, hence independent of readback sub-texel phase
                    // (harness: 96.0 -> 94.0 under both hypotheses).
                    fun redEdge(px: Array<IntArray>): Pair<Double, Int> {
                        var sum = 0.0
                        var n = 0
                        for (y in 24..104 step 4) {
                            for (x in 88..102) {
                                if (((px[y][x] shr 16) and 0xFF) < 200) {
                                    sum += x; n++; break
                                }
                            }
                        }
                        return (if (n > 0) sum / n else -1.0) to n
                    }
                    val (e0, n0) = redEdge(renderOnce(emptyList(), EditState()))
                    val (e1, n1) = redEdge(renderOnce(emptyList(),
                        EditState(optics = OpticsParams(defringe = 100f))))
                    val okDef = n0 >= 15 && n1 >= 15 && e0 in 94.0..98.0 &&
                        (e0 - e1) in 1.0..3.0

                    (okDist && okDef) to "distort base=${d0.toInt()} " +
                        "(need 51..75) +100=${dP.toInt()} " +
                        "(need 38..62, base-+>=7) -100=${dM.toInt()} " +
                        "(need 64..88, --base>=7) defringe edge=" +
                        "${"%.1f".format(e0)} (need 94..98) ->" +
                        "${"%.1f".format(e1)} (shift 1..3)"
                } finally {
                    setSource(gray)
                    gradBmp.recycle()
                    rbBmp.recycle()
                }
            }

            // ---- check 22: masked sharpness + dehaze (uMaskC) ----
            // Per-mask sharpness/dehaze are the last mask adjustments with no
            // on-device coverage (the slot-0 check only exercised uMaskA).
            check("Masked sharpness + dehaze (uMaskC)") {
                val cell = 4
                val cbBmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
                val cbPx = IntArray(s * s) { i ->
                    val dark = ((i % s) / cell + (i / s) / cell) % 2 == 0
                    if (dark) Color.rgb(100, 100, 100)
                    else Color.rgb(150, 150, 150)
                }
                cbBmp.setPixels(cbPx, 0, s, 0, 0, s, s)
                // left strong red (gets mask dehaze), right salmon (control)
                val dzBmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
                val dzPx = IntArray(s * s) { i ->
                    if ((i % s) < s / 2) Color.rgb(170, 50, 50)
                    else Color.rgb(150, 110, 110)
                }
                dzBmp.setPixels(dzPx, 0, s, 0, 0, s, s)
                try {
                    setSource(cbBmp)
                    fun setMean(
                        px: Array<IntArray>, dark: Boolean, left: Boolean,
                    ): Double {
                        var sum = 0L
                        var n = 0
                        val xs = if (left) 16..48 else 80..112
                        for (y in 8..120 step 2) for (x in xs step 2) {
                            if ((((x / cell) + (y / cell)) % 2 == 0) == dark) {
                                sum += (px[y][x] shr 16) and 0xFF
                                n++
                            }
                        }
                        return sum.toDouble() / n
                    }
                    val pxS = renderOnce(
                        listOf(halfMask),
                        EditState(masks = listOf(testMask("t0",
                            LocalAdjust(sharpness = 100f)))),
                    )
                    val sLD = setMean(pxS, true, true)
                    val sLB = setMean(pxS, false, true)
                    val sRD = setMean(pxS, true, false)
                    val sRB = setMean(pxS, false, false)
                    // harness: masked left dark 100-50=50 / bright 150+50=200
                    // (hp*2.0), right untouched 100/150. Bands +-14 like the
                    // clarity check; the >=60 gap differential proves uMaskC
                    // is live (stuck-0 leaves both gaps at 50).
                    val okSharp = sLD in 36.0..64.0 && sLB in 186.0..214.0 &&
                        sRD in 92.0..108.0 && sRB in 142.0..158.0 &&
                        (sLB - sLD) - (sRB - sRD) >= 60.0

                    setSource(dzBmp)
                    fun chanMean(
                        px: Array<IntArray>, x0: Int, x1: Int, shift: Int,
                    ): Double {
                        var sum = 0L
                        var n = 0
                        for (y in 24..104 step 4) for (x in x0..x1 step 4) {
                            sum += (px[y][x] shr shift) and 0xFF
                            n++
                        }
                        return sum.toDouble() / n
                    }
                    val pxZ = renderOnce(
                        listOf(halfMask),
                        EditState(masks = listOf(testMask("t0",
                            LocalAdjust(dehaze = 100f)))),
                    )
                    val zLR = chanMean(pxZ, 16, 48, 16)
                    val zLG = chanMean(pxZ, 16, 48, 8)
                    val zRR = chanMean(pxZ, 80, 112, 16)
                    val zRG = chanMean(pxZ, 80, 112, 8)
                    // harness: mask dehaze = mix(luma,c,1.35) on the left
                    // only -> strong red r 203 / g 41; salmon control
                    // unchanged 150/110. Bands +-14.
                    val okDz = zLR in 189.0..217.0 && zLG in 27.0..55.0 &&
                        zRR in 136.0..164.0 && zRG in 96.0..124.0

                    (okSharp && okDz) to "maskSharp L=${sLD.toInt()}/" +
                        "${sLB.toInt()} (need 36..64/186..214) " +
                        "R=${sRD.toInt()}/${sRB.toInt()} " +
                        "(need 92..108/142..158, gapDiff>=60) maskDehaze " +
                        "L=${zLR.toInt()}/${zLG.toInt()} " +
                        "(need 189..217/27..55) R=${zRR.toInt()}/${zRG.toInt()} " +
                        "(need 136..164/96..124)"
                } finally {
                    setSource(gray)
                    cbBmp.recycle()
                    dzBmp.recycle()
                }
            }

            // ---- check 23: clipping warning overlay (uClipMode) ----
            // The shadow/highlight clipping overlay is the last user-visible
            // uniform path with no on-device coverage — a stuck-0 uniform
            // would leave the toggle silently doing nothing. On a half-black
            // / half-white source: mode 1 paints shadow clipping blue
            // (mix(c, vec3(0.1,0.35,1.0), 0.75) -> (19,67,191)), mode 2
            // paints highlight clipping red (mix(c, vec3(1.0,0.15,0.1),
            // 0.75) -> (255,92,83)), mode 3 paints both, mode 0 leaves the
            // image untouched. Harness: solid colors render exactly, so the
            // bands only need to absorb readback jitter.
            check("Clipping warning overlay (uClipMode)") {
                val clipBmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
                val clipPx = IntArray(s * s) { i ->
                    if ((i % s) < s / 2) Color.rgb(0, 0, 0)
                    else Color.rgb(255, 255, 255)
                }
                clipBmp.setPixels(clipPx, 0, s, 0, 0, s, s)
                try {
                    setSource(clipBmp)
                    fun isBlack(r: Int, g: Int, b: Int) =
                        r < 25 && g < 25 && b < 25
                    fun isWhite(r: Int, g: Int, b: Int) =
                        r > 230 && g > 230 && b > 230
                    // shader: mix((0,0,0), (0.1,0.35,1.0), 0.75) -> 8-bit
                    // (19, 67, 191)
                    fun isShadowBlue(r: Int, g: Int, b: Int) =
                        r in 5..45 && g in 45..95 && b in 150..210
                    // shader: mix((1,1,1), (1.0,0.15,0.1), 0.75) -> 8-bit
                    // (255, 92, 83)
                    fun isHighlightRed(r: Int, g: Int, b: Int) =
                        r in 240..255 && g in 70..115 && b in 60..105

                    val px0 = renderOnce(emptyList(), EditState(),
                        GlEngine.FrameOptions(clipMode = 0))
                    val okBase = regionPassRate(px0, 16, 48, ::isBlack) >= 0.98 &&
                        regionPassRate(px0, 80, 112, ::isWhite) >= 0.98

                    val px1 = renderOnce(emptyList(), EditState(),
                        GlEngine.FrameOptions(clipMode = 1))
                    val okShadow = regionPassRate(px1, 16, 48,
                        ::isShadowBlue) >= 0.98 &&
                        regionPassRate(px1, 80, 112, ::isWhite) >= 0.98

                    val px2 = renderOnce(emptyList(), EditState(),
                        GlEngine.FrameOptions(clipMode = 2))
                    val okHi = regionPassRate(px2, 16, 48,
                        ::isBlack) >= 0.98 &&
                        regionPassRate(px2, 80, 112,
                            ::isHighlightRed) >= 0.98

                    val px3 = renderOnce(emptyList(), EditState(),
                        GlEngine.FrameOptions(clipMode = 3))
                    val okBoth = regionPassRate(px3, 16, 48,
                        ::isShadowBlue) >= 0.98 &&
                        regionPassRate(px3, 80, 112,
                            ::isHighlightRed) >= 0.98

                    (okBase && okShadow && okHi && okBoth) to
                        "mode0 black=${"%.2f".format(regionPassRate(px0, 16, 48, ::isBlack))} " +
                        "white=${"%.2f".format(regionPassRate(px0, 80, 112, ::isWhite))} " +
                        "(need >=0.98) mode1 blue=${"%.2f".format(regionPassRate(px1, 16, 48, ::isShadowBlue))} " +
                        "(need >=0.98) mode2 red=${"%.2f".format(regionPassRate(px2, 80, 112, ::isHighlightRed))} " +
                        "(need >=0.98) mode3 both=${"%.2f".format(regionPassRate(px3, 16, 48, ::isShadowBlue))}/" +
                        "${"%.2f".format(regionPassRate(px3, 80, 112, ::isHighlightRed))} " +
                        "(need >=0.98)"
                } finally {
                    setSource(gray)
                    clipBmp.recycle()
                }
            }

            // ---- check 24: viewport invariant across offscreen renders ----
            // Regression guard for the 2026-10-16 black-preview user report:
            // offscreen work (thumbnails via renderOffscreen, export tiles,
            // the blur pyramid via setSource) must leave the GL viewport
            // exactly as found. A stale small viewport makes the live
            // preview render as a tiny photo in the corner with the rest
            // black. This exercises the real public entry points, not
            // withSavedViewport itself.
            check("Viewport invariant (offscreen renders)") {
                fun viewportNow(): IntArray = IntArray(4).also {
                    GLES30.glGetIntegerv(GLES30.GL_VIEWPORT, it, 0)
                }
                val before = viewportNow()
                // The loupe renders through renderOffscreen (the preset /
                // library thumbnail path) at 64px and restores manually.
                val loupe = renderLoupe(EditState(), Pt(0.5f, 0.5f), 0.1f, 64)
                loupe.recycle()
                val afterLoupe = viewportNow()
                // Export tiles the framebuffer with offset viewports.
                val exported = renderExport(EditState(), matrix) {}
                exported.recycle()
                val afterExport = viewportNow()
                // setSource rebuilds the blur pyramid at ~1/8 scale — the
                // original leak in the black-preview report.
                setSource(gray)
                val afterBlur = viewportNow()
                // Re-bind the scratch FBO: the offscreen paths above leave
                // framebuffer 0 bound.
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, testFbo)
                val ok = afterLoupe.contentEquals(before) &&
                    afterExport.contentEquals(before) &&
                    afterBlur.contentEquals(before)
                ok to "before=${before.toList()} loupe=${afterLoupe.toList()} " +
                    "export=${afterExport.toList()} blur=${afterBlur.toList()}"
            }

            // ---- check 25: grain animation clock (uTime) ----
            // The last shader uniform with no on-device coverage: uTime
            // drives the film-grain hash offsets (fract(uTime) * 7.13 /
            // -fract(uTime) * 3.7). Two renders at the same clock must be
            // pixel-identical (deterministic — a driver feeding garbage or
            // failing to upload the uniform would break this), and two
            // renders at different clocks must differ (the animation is
            // actually wired into the grain). Grain amount 100 gives ~14
            // levels of per-channel noise, so a working clock flips
            // thousands of pixels in the sampled region.
            check("Grain animation clock (uTime)") {
                val grainy = EditState(
                    optics = OpticsParams(grainAmount = 100f))
                val pxT0a = renderOnce(emptyList(), grainy,
                    GlEngine.FrameOptions(timeSec = 0f))
                val pxT0b = renderOnce(emptyList(), grainy,
                    GlEngine.FrameOptions(timeSec = 0f))
                val pxT07 = renderOnce(emptyList(), grainy,
                    GlEngine.FrameOptions(timeSec = 0.7f))
                fun diffCount(a: Array<IntArray>, b: Array<IntArray>): Int {
                    var c = 0
                    for (y in 0 until s) {
                        for (x in 16..112) {
                            if (a[y][x] != b[y][x]) c++
                        }
                    }
                    return c
                }
                val sameClockDiff = diffCount(pxT0a, pxT0b)
                val movedClockDiff = diffCount(pxT0a, pxT07)
                val ok = sameClockDiff == 0 && movedClockDiff > 1000
                ok to "same-clock diff=$sameClockDiff (need 0) " +
                    "moved-clock diff=$movedClockDiff (need >1000)"
            }

            // ---- check 26: split view toggle-off (uSplitX disable path) ----
            // Guards the 2026-10-15 "stuck split" user report: GlEngine never
            // cached the uSplitX uniform location, so disabling the split was
            // a silent no-op (glUniform1f(-1, ...)) and the shader's default
            // 0.0 kept the divider rendered forever. Check 8 covers
            // split-ON rendering; this covers the OFF transition through the
            // same cached-uniform path the live preview uses. A regressed
            // (uncached) location would leave the left half unedited (~128)
            // and a visible seam, failing both assertions.
            check("Split view toggle-off (uSplitX disable)") {
                val bright = EditState(light = LightParams(exposure = 2f))
                val onPx = renderOnce(emptyList(), bright,
                    GlEngine.FrameOptions(splitX = 0f))
                val offPx = renderOnce(emptyList(), bright,
                    GlEngine.FrameOptions(splitX = -2f))
                val onLeft = regionMean(onPx, 16, 48)
                val offLeft = regionMean(offPx, 16, 48)
                val offRight = regionMean(offPx, 80, 112)
                val ok = onLeft in 110.0..146.0 &&
                    offLeft > 235.0 && offRight > 235.0 &&
                    abs(offLeft - offRight) < 20.0
                ok to "on-left=${onLeft.toInt()} (need ~128, split engaged) " +
                    "off-left=${offLeft.toInt()} off-right=${offRight.toInt()} " +
                    "(need both >235 and |diff|<20: no stuck half, no seam)"
            }

            // ---- check 27: heal blending (LaplaceHeal robust plane fit) ----
            // The 2026-10-16 "heal ghost blobs" user report: the Heal tool
            // left dark ghost blobs instead of removing blemishes. Root cause
            // was the non-robust plane fit in LaplaceHeal letting the blemish
            // itself drag the fitted tone down (fixed with a median/MAD
            // robust refit). This check exercises the REAL heal path on
            // device: a dark blemish on a flat-gray source is healed through
            // RetouchEngine.rasterize (the same overlay the UI uploads) and
            // composited by the preview shader. Asserts the healed disc
            // returns to the background tone and no dark pixel survives
            // (ghost-blob guard); the control frame with the retouch flag off
            // must still show the dark blob, proving the test is sensitive.
            check("Heal blending (LaplaceHeal robust fit)") {
                val blemish = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
                val blobC = s / 2; val blobR = 10
                val bPx = IntArray(s * s) { i ->
                    val x = i % s; val y = i / s
                    val dx = x - blobC; val dy = y - blobC
                    if (dx * dx + dy * dy <= blobR * blobR) 0xFF3C3C3C.toInt()
                    else 0xFF969696.toInt()
                }
                blemish.setPixels(bPx, 0, s, 0, 0, s, s)
                try {
                    setSource(blemish)
                    val spot = RetouchSpot(
                        op = RetouchOp.HEAL,
                        x = 0.5f, y = 0.5f,
                        srcX = 0.78f, srcY = 0.5f, // clean gray to the right
                        size = 0.3f,
                    )
                    val overlay = RetouchEngine.rasterize(
                        listOf(spot), s, s, blemish)
                    try {
                        uploadRetouch(overlay)
                        val healed = EditState(
                            masks = noMask,
                            retouch = RetouchState(spots = listOf(spot)),
                        )
                        val pxOn = renderOnce(emptyList(), healed)
                        val pxOff = renderOnce(
                            emptyList(), EditState(masks = noMask))
                        // disc sample inside the healed area (blob r=10,
                        // heal r~19)
                        fun discMean(px: Array<IntArray>): Double {
                            var sum = 0L; var n = 0
                            for (y in 58..70) for (x in 58..70) {
                                sum += (px[y][x] shr 16) and 0xFF; n++
                            }
                            return sum.toDouble() / n
                        }
                        fun discMin(px: Array<IntArray>): Int {
                            var m = 255
                            for (y in 56..72) for (x in 56..72) {
                                m = minOf(m, (px[y][x] shr 16) and 0xFF)
                            }
                            return m
                        }
                        val onMean = discMean(pxOn)
                        val onMin = discMin(pxOn)
                        val offMean = discMean(pxOff)
                        // background 150; healed must return to it (no dark
                        // blob, no over-brightening), and the control must
                        // still show the ~60 blob.
                        val ok = onMean in 115.0..185.0 && onMin > 95 &&
                            offMean < 100.0
                        ok to "healed-mean=${onMean.toInt()} (need 115..185) " +
                            "healed-min=$onMin (need >95: no ghost blob) " +
                            "control-mean=${offMean.toInt()} (need <100)"
                    } finally {
                        uploadRetouch(null)
                        overlay.recycle()
                    }
                } finally {
                    blemish.recycle()
                }
            }

            // ---- check 28: export tile stitching integrity (renderExport) ----
            // The export path (the file the user actually saves) is a
            // separate code path from the preview: tiled 2048px renders with
            // negative-offset viewports stitched into a full-size Bitmap.
            // Nothing on-device verified it until now. A 2100px-wide source
            // forces 2 tiles; the edit (exposure +2) must actually land in
            // the export, and the tile seam at x=2047/2048 must be invisible.
            // A viewport-arithmetic or readback-stride bug would drop the
            // edit, duplicate/shift a tile (seam jumps ~127 lum on the
            // gradient), or produce a mis-sized bitmap. Exposure is the
            // simplest monotone uniform: analytic gradient makes the seam
            // check exact.
            check("Export tile stitching (renderExport)") {
                val w = 2100; val h = 96
                val grad = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val gPx = IntArray(w * h) { i ->
                    val x = i % w
                    val lum = (60f + x * 130f / (w - 1)).toInt()
                    Color.rgb(lum, lum, lum)
                }
                grad.setPixels(gPx, 0, w, 0, 0, w, h)
                try {
                    setSource(grad)
                    uploadMasks(emptyList())
                    uploadRetouch(null)
                    val bright = EditState(light = LightParams(exposure = 2f))
                    val base = EditState(masks = noMask)
                    val matrix = Geometry.uvMatrix(
                        w, h, w, h, CropParams(), GeometryParams())
                    fun meanLum(b: Bitmap): Double {
                        var sum = 0L; var n = 0
                        for (y in 0 until h step 4) {
                            for (x in 0 until w step 16) {
                                sum += (b.getPixel(x, y) shr 16) and 0xFF
                                n++
                            }
                        }
                        return sum.toDouble() / n
                    }
                    uploadCurves(base)
                    val pxBase = renderExport(base, matrix) {}
                    try {
                        uploadCurves(bright)
                        val pxBright = renderExport(bright, matrix) {}
                        try {
                            val err = GLES30.glGetError()
                            if (err != GLES30.GL_NO_ERROR) {
                                throw IllegalStateException(
                                    "GL error 0x${err.toString(16)} " +
                                        "during renderExport")
                            }
                            val baseMean = meanLum(pxBase)
                            val brightMean = meanLum(pxBright)
                            val seamMax = (0 until h).maxOf { y ->
                                val a = (pxBase.getPixel(2047, y) shr 16) and 0xFF
                                val b = (pxBase.getPixel(2048, y) shr 16) and 0xFF
                                abs(a - b)
                            }
                            val dimsOk = pxBase.width == w &&
                                pxBase.height == h &&
                                pxBright.width == w && pxBright.height == h
                            // gradient is analytic: any seam bigger than a
                            // rounding step means the tiles were stitched
                            // wrong (a shifted/duplicated tile shows ~127).
                            val ok = dimsOk && brightMean - baseMean > 60.0 &&
                                seamMax < 16
                            ok to "dims=${pxBase.width}x${pxBase.height} " +
                                "(need ${w}x${h}); base-mean=" +
                                "${baseMean.toInt()} bright-mean=" +
                                "${brightMean.toInt()} (need +60: edit " +
                                "lands in export); seam-max=$seamMax (need <16)"
                        } finally {
                            pxBright.recycle()
                        }
                    } finally {
                        pxBase.recycle()
                    }
                } finally {
                    grad.recycle()
                }
            }

            check("Auto-enhance portrait metering (face-weighted)") {
                // On-device twin of the JVM regression test
                // portraitWithDarkBackground_exposureMetersOnSkin: the
                // 2026-10-16 fix meters portrait exposure 65% skin-region
                // mean + 35% frame mean instead of the bare frame mean, so a
                // dark background no longer pushes exposure up until faces
                // blow out. Uses the production AutoRetouch skin model, not a
                // stub. Runs on the GL thread only because this suite owns it;
                // the analyzed path itself is CPU.
                val rnd = kotlin.random.Random(17)
                val w = 200; val h = 200
                val px = IntArray(w * h) { i ->
                    val x = i % w; val y = i / w
                    val (r, g, b) = if (x in 20..180 && y in 40..140)
                        Triple(208 + rnd.nextInt(20), 158 + rnd.nextInt(15),
                            128 + rnd.nextInt(12))
                    else
                        Triple(38 + rnd.nextInt(6), 38 + rnd.nextInt(6),
                            40 + rnd.nextInt(6))
                    (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
                val portrait = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val exp: Float
                val darkExp: Float
                try {
                    portrait.setPixels(px, 0, w, 0, 0, w, h)
                    exp = AutoEnhance.analyze(portrait, 1f).exposure
                    // Sensitivity control: dark textured frame with NO skin
                    // must still frame-meter (exposure pushed up, > 0.3).
                    // If this passed while the portrait check passed, a
                    // regressed frame-meter would have to flip both verdicts.
                    val darkPx = IntArray(w * h) {
                        val v = 24 + rnd.nextInt(24)
                        (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                    }
                    val dark = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    try {
                        dark.setPixels(darkPx, 0, w, 0, 0, w, h)
                        darkExp = AutoEnhance.analyze(dark, 1f).exposure
                    } finally {
                        dark.recycle()
                    }
                } finally {
                    portrait.recycle()
                }
                val ok = exp < 0.15f && exp > -1.5f && darkExp > 0.3f
                ok to "dark-bg portrait exposure=$exp (need -1.5..0.15: face " +
                    "metered, not frame-metered); no-skin dark frame " +
                    "exposure=$darkExp (need >0.3: control still brightens)"
            }
            check("Watermark export layout (watermarkLayout)") {
                // Export watermark geometry had zero on-device coverage: a
                // regression (TOP/BOTTOM or LEFT/RIGHT swap, off-image
                // placement, misaligned Paint.Align) would stamp unreadable
                // or clipped text onto the saved photo. Pure CPU —
                // watermarkLayout has no Android drawing dependencies.
                val failures = mutableListOf<String>()
                fun expect(cond: Boolean, msg: String) { if (!cond) failures += msg }
                for ((w, h) in listOf(4000 to 3000, 3000 to 4000, 1080 to 1080)) {
                    val marginX = w * 0.03f
                    val textPx = (0.035f * h).coerceAtLeast(8f)
                    for (pos in WatermarkPosition.entries) {
                        val l = watermarkLayout(
                            w, h,
                            WatermarkSettings(enabled = true, text = "H2W", position = pos)
                        )
                        expect(abs(l.textPx - textPx) < 0.01f,
                            "$pos ${w}x$h textPx=${l.textPx} want $textPx")
                        expect(l.x in 0f..w.toFloat() && l.baselineY in 0f..h.toFloat(),
                            "$pos ${w}x$h off-image x=${l.x} y=${l.baselineY}")
                        // Cap-height box must stay inside the image
                        // (descenders reach ~0.3*textPx below the baseline).
                        expect(l.baselineY - 0.7f * l.textPx >= -0.01f &&
                            l.baselineY + 0.3f * l.textPx <= h + 0.01f,
                            "$pos ${w}x$h text bleeds off image")
                        when (pos) {
                            WatermarkPosition.BOTTOM_LEFT, WatermarkPosition.TOP_LEFT ->
                                expect(abs(l.x - marginX) < 0.01f &&
                                    l.align == WatermarkAlign.LEFT,
                                    "$pos x/align wrong: $l")
                            WatermarkPosition.BOTTOM_RIGHT, WatermarkPosition.TOP_RIGHT ->
                                expect(abs(l.x - (w - marginX)) < 0.01f &&
                                    l.align == WatermarkAlign.RIGHT,
                                    "$pos x/align wrong: $l")
                            WatermarkPosition.CENTER ->
                                expect(abs(l.x - w / 2f) < 0.01f &&
                                    l.align == WatermarkAlign.CENTER,
                                    "CENTER x/align wrong: $l")
                        }
                        when (pos) {
                            WatermarkPosition.BOTTOM_LEFT, WatermarkPosition.BOTTOM_RIGHT ->
                                expect(l.baselineY > h / 2f,
                                    "$pos baseline not in bottom half: ${l.baselineY}")
                            WatermarkPosition.TOP_LEFT, WatermarkPosition.TOP_RIGHT ->
                                expect(l.baselineY < h / 2f,
                                    "$pos baseline not in top half: ${l.baselineY}")
                            WatermarkPosition.CENTER ->
                                expect(abs(l.baselineY - (h / 2f + 0.35f * l.textPx)) < 0.01f,
                                    "CENTER baseline wrong: $l")
                        }
                    }
                }
                // Canonical hand-computed expectation: 4000x3000 BOTTOM_RIGHT
                // (3% insets, 3.5% text height).
                val canon = watermarkLayout(
                    4000, 3000,
                    WatermarkSettings(enabled = true, text = "H2W",
                        position = WatermarkPosition.BOTTOM_RIGHT)
                )
                expect(abs(canon.textPx - 105f) < 0.01f &&
                    abs(canon.x - 3880f) < 0.01f &&
                    abs(canon.baselineY - 2910f) < 0.01f &&
                    canon.align == WatermarkAlign.RIGHT,
                    "canonical 4000x3000 BOTTOM_RIGHT wrong: $canon")
                // Sensitivity controls: isActive gates drawing, validate flags
                // out-of-range config. If these passed vacuously the check
                // would be blind to the gate logic it is meant to guard.
                expect(!WatermarkSettings(enabled = true, text = "  ").isActive(),
                    "blank-text watermark considered active")
                expect(!WatermarkSettings(enabled = false, text = "H2W").isActive(),
                    "disabled watermark considered active")
                expect(WatermarkSettings(enabled = true, text = "H2W").isActive(),
                    "enabled watermark not active")
                expect(WatermarkSettings(sizeFrac = 0.01f).validate().isNotEmpty(),
                    "sizeFrac 0.01 not flagged")
                expect(WatermarkSettings(opacity = 1.5f).validate().isNotEmpty(),
                    "opacity 1.5 not flagged")
                expect(WatermarkSettings().validate().isEmpty(),
                    "default watermark settings flagged invalid")
                failures.isEmpty() to
                    if (failures.isEmpty())
                        "15 layouts (5 positions x 3 sizes) in-bounds, aligned, " +
                            "no bleed; canonical 4000x3000 BOTTOM_RIGHT " +
                            "textPx=105 x=3880 baseline=2910 align=RIGHT; " +
                            "isActive/validate gates hold"
                    else failures.take(5).joinToString("; ")
            }

            // ---- check 31: clone tone blending (seamless clone) ----
            // The Clone tool's full-disc tone match (smooth(dest) −
            // smooth(src) offset at full strength, blended by the feathered
            // alpha, added in cycle 134 and de-double-weighted later) had zero on-device coverage. A regression back to
            // exact-copy would paste visibly brighter/darker patches whenever
            // the clone source and destination lighting differ. This check
            // runs the REAL clone path on device: a bright textured dot is
            // cloned from a clean area onto a dark blemish, rasterized
            // through RetouchEngine.rasterize and composited by the preview
            // shader. Asserts the cloned center bends toward the destination
            // tone (not a pasted 230) while the dot's texture survives (not a
            // silent no-op); the retouch-off control must still show the
            // dark blob, proving sensitivity. Thresholds validated with the
            // standalone kotlinc harness on verbatim copies of the clone
            // math: tone-adaptive center=171, exact-copy=230, no-op=60.
            check("Clone tone blending (seamless clone)") {
                val scene = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
                val cX = (0.30f * s).toInt(); val cY = s / 2
                val sX = (0.78f * s).toInt()
                val sPx = IntArray(s * s) { i ->
                    val x = i % s; val y = i / s
                    val db = (x - cX) * (x - cX) + (y - cY) * (y - cY)
                    val dd = (x - sX) * (x - sX) + (y - cY) * (y - cY)
                    val lum = when {
                        db <= 100 -> 60   // dark blemish at the clone target
                        dd <= 9 -> 230     // bright textured dot at the source
                        else -> 120
                    }
                    (0xFF shl 24) or (lum shl 16) or (lum shl 8) or lum
                }
                scene.setPixels(sPx, 0, s, 0, 0, s, s)
                try {
                    setSource(scene)
                    val spot = RetouchSpot(
                        op = RetouchOp.CLONE,
                        x = 0.30f, y = 0.5f,
                        srcX = 0.78f, srcY = 0.5f,
                        size = 0.3f,
                    )
                    val overlay = RetouchEngine.rasterize(listOf(spot), s, s, scene)
                    try {
                        uploadRetouch(overlay)
                        val cloned = EditState(
                            masks = noMask,
                            retouch = RetouchState(spots = listOf(spot)),
                        )
                        val pxOn = renderOnce(emptyList(), cloned)
                        val pxOff = renderOnce(emptyList(), EditState(masks = noMask))
                        fun ch(px: Array<IntArray>, x: Int, y: Int) =
                            (px[y][x] shr 16) and 0xFF
                        fun centerMean(px: Array<IntArray>): Double {
                            var sum = 0L; var n = 0
                            for (y in cY - 1..cY + 1) for (x in cX - 1..cX + 1) {
                                sum += ch(px, x, y); n++
                            }
                            return sum.toDouble() / n
                        }
                        fun ringMean(px: Array<IntArray>): Double {
                            var sum = 0L; var n = 0
                            for (y in cY - 2..cY + 2) for (x in cX + 10..cX + 14) {
                                sum += ch(px, x, y); n++
                            }
                            return sum.toDouble() / n
                        }
                        val onCenter = centerMean(pxOn)
                        val onRing = ringMean(pxOn)
                        val offCenter = centerMean(pxOff)
                        // tone-adaptive bends the 230 dot toward the local
                        // destination tone (harness: 171); exact-copy would
                        // leave a pasted 230; a silent no-op would leave 60.
                        // The surviving center-ring contrast (harness: 64)
                        // proves the source texture was actually pasted.
                        val ok = onCenter in 120.0..205.0 &&
                            onCenter - onRing > 40.0 && offCenter < 100.0
                        ok to "clone-center=${onCenter.toInt()} (need 120..205) " +
                            "center-ring=${(onCenter - onRing).toInt()} " +
                            "(need >40: texture pasted) " +
                            "control-center=${offCenter.toInt()} (need <100)"
                    } finally {
                        uploadRetouch(null)
                        overlay.recycle()
                    }
                } finally {
                    scene.recycle()
                }
            }

            // ---- check 32: AI Pro Retouch 3D LUT render path (sampler3D) ----
            // The AI Pro Retouch grade travels a path no earlier check
            // touched: CPU FloatArray -> GL_TEXTURE_3D upload -> texture unit
            // 9 -> sampler3D hardware trilinear -> shader mix gated by
            // uAiLutStrength. A regression in any hop (wrong layout axis,
            // unbound unit, the strength gate stuck at 0, aiLutReady never
            // set) would ship the feature's button while photos silently
            // render ungraded. This check exercises the REAL path on
            // device with synthetic LUTs: an identity LUT at strength 1
            // must be (near-)identical to the LUT-off render, and a warm
            // LUT (+30 red / -30 blue per cell) must shift the image's red
            // mean up and blue mean down. The live preview's baked LUT is
            // snapshotted and restored, so the test can never corrupt a
            // user's real grade; aiLutSnapshot() returns null when none is
            // uploaded, in which case the engine is left cleared (its
            // pre-test state).
            check("AI Pro Retouch 3D LUT (sampler3D path)") {
                val saved = aiLutSnapshot()
                try {
                    setSource(gray)
                    uploadMasks(emptyList())
                    uploadRetouch(null)
                    uploadCurves(EditState())
                    val n = 9 // any n works for the shader; 9^3*3 is cheap
                    fun makeLut(rOff: Float, gOff: Float, bOff: Float): FloatArray {
                        val lut = FloatArray(n * n * n * 3)
                        for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
                            val o = (((r * n) + g) * n + b) * 3
                            lut[o] = (r / (n - 1f) + rOff).coerceIn(0f, 1f)
                            lut[o + 1] = (g / (n - 1f) + gOff).coerceIn(0f, 1f)
                            lut[o + 2] = (b / (n - 1f) + bOff).coerceIn(0f, 1f)
                        }
                        return lut
                    }
                    val on = EditState(
                        masks = noMask,
                        aiRetouch = AiRetouchParams(enabled = true, strength = 1f),
                    )
                    val off = EditState(masks = noMask) // strength 0: skip path
                    uploadAiLut(makeLut(0f, 0f, 0f), n)
                    val pxIdent = renderOnce(emptyList(), on)
                    val pxBase = renderOnce(emptyList(), off)
                    uploadAiLut(makeLut(30f / 255f, 0f, -30f / 255f), n)
                    val pxWarm = renderOnce(emptyList(), on)
                    fun maxDiff(a: Array<IntArray>, b: Array<IntArray>): Int {
                        var m = 0
                        for (y in 24..104 step 4) for (x in 24..104 step 4) {
                            val pa = a[y][x]; val pb = b[y][x]
                            m = maxOf(m,
                                kotlin.math.abs(((pa shr 16) and 0xFF) - ((pb shr 16) and 0xFF)),
                                kotlin.math.abs(((pa shr 8) and 0xFF) - ((pb shr 8) and 0xFF)),
                                kotlin.math.abs((pa and 0xFF) - (pb and 0xFF)))
                        }
                        return m
                    }
                    fun chMean(px: Array<IntArray>, shift: Int): Double {
                        var sum = 0L; var cnt = 0
                        for (y in 24..104 step 4) for (x in 24..104 step 4) {
                            sum += (px[y][x] shr shift) and 0xFF; cnt++
                        }
                        return sum.toDouble() / cnt
                    }
                    val dIdent = maxDiff(pxIdent, pxBase)
                    val redDelta = chMean(pxWarm, 16) - chMean(pxIdent, 16)
                    val blueDelta = chMean(pxWarm, 0) - chMean(pxIdent, 0)
                    // identity must be within RGB8 rounding of off; warm
                    // must move red up and blue down well past noise.
                    val ok = dIdent <= 3 && redDelta > 15.0 && blueDelta < -15.0
                    ok to "identity-vs-off maxDiff=$dIdent (need <=3) " +
                        "warm redDelta=${redDelta.toInt()} (need >15) " +
                        "blueDelta=${blueDelta.toInt()} (need <-15)"
                } finally {
                    if (saved != null) uploadAiLut(saved.first, saved.second)
                    else uploadAiLut(null, 0)
                }
            }
            // ---- check 33: AI grade bank bake + re-upload (AiGradeUpload) ----
            // Check 32 proved the sampler3D render path with hand-made LUTs.
            // This exercises the REAL production primitive every other path
            // now shares (preview re-upload, single export, batch export):
            // AiLutBank.ensureBaked — 5 bakeLut renders through the actual
            // shader on this driver, identity self-check included — then
            // AiProRetouch.fuse and uploadAiLut. A Mali-specific bake failure
            // (a flipped axis, a collapsed LUT) would otherwise surface only
            // as a silent "AI grade unavailable" when the user taps the
            // button. We fuse two maximally different weight vectors (pure
            // Portrait recipe vs pure Night recipe) and require the renders
            // to differ well past noise: that proves the bank baked DISTINCT
            // LUTs and fuse/upload select between them. Then we simulate a
            // lost LUT (uploadAiLut(null, 0) — what a fresh engine looks like
            // after photo reopen) and re-upload through the same helper to
            // prove the re-establish path the renderer relies on.
            check("AI Pro Retouch bank bake + re-upload") {
                val saved = aiLutSnapshot()
                try {
                    setSource(gray)
                    uploadMasks(emptyList())
                    uploadRetouch(null)
                    uploadCurves(EditState())
                    val on = EditState(
                        masks = noMask,
                        aiRetouch = AiRetouchParams(enabled = true, strength = 1f),
                    )
                    fun meanDiff(a: Array<IntArray>, b: Array<IntArray>): Double {
                        var sum = 0L; var cnt = 0
                        for (y in 24..104 step 4) for (x in 24..104 step 4) {
                            val pa = a[y][x]; val pb = b[y][x]
                            sum += kotlin.math.abs(((pa shr 16) and 0xFF) - ((pb shr 16) and 0xFF)) +
                                kotlin.math.abs(((pa shr 8) and 0xFF) - ((pb shr 8) and 0xFF)) +
                                kotlin.math.abs((pa and 0xFF) - (pb and 0xFF))
                            cnt++
                        }
                        return sum.toDouble() / cnt
                    }
                    // Pure Portrait (index 0) vs pure Night (index 2): the
                    // most different pair of basis grades.
                    val up1 = AiGradeUpload.upload(this, listOf(1f, 0f, 0f, 0f))
                    if (up1.sourceStomped) setSource(gray)
                    val pxPortrait = renderOnce(emptyList(), on)
                    // Simulate the lost LUT, then re-establish.
                    uploadAiLut(null, 0)
                    val up2 = AiGradeUpload.upload(this, listOf(0f, 0f, 1f, 0f))
                    if (up2.sourceStomped) setSource(gray)
                    val pxNight = renderOnce(emptyList(), on)
                    val diff = meanDiff(pxPortrait, pxNight)
                    val ok = up1.ready && up2.ready && diff > 4.0
                    ok to "ready=${up1.ready}/${up2.ready} " +
                        "portrait-vs-night meanDiff=${"%.1f".format(diff)} (need >4.0)"
                } finally {
                    if (saved != null) uploadAiLut(saved.first, saved.second)
                    else uploadAiLut(null, 0)
                }
            }

            // ---- check 34: camera calibration (uCalHue/uCalSat/uCalShadowTint) ----
            // The last render-path block with no on-device GPU self-test.
            // Scenarios are transcribed 1:1 from the shader into the
            // calibration-harness kotlinc harness; bands are the harness
            // predictions ±12 (the clarity-check slack convention):
            //  - shadows tint +100 on dark gray 60 -> (66,46,66): magenta
            //    cast in the shadows; -100 -> (53,73,53): green cast.
            //    The same +100 on bright gray 230 is a no-op (shadow weight
            //    1-smoothstep(0,0.5,~0.90) == 0), proving the tint only
            //    touches shadows — a missing/incorrect shTintW would tint
            //    the whole frame.
            //  - red primary hue +100 on pure red -> (255,127,0): the wR
            //    weight selects red; a swapped .x/.y selector would move
            //    green instead (the Cycle-38 tone-curve bug class).
            //  - green primary sat -100 on pure green -> (127,127,127).
            //  - red hue +100 leaves pure green untouched (wG isolation).
            check("Camera calibration (uCalHue/uCalSat/uCalShadowTint)") {
                val darkBmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
                darkBmp.eraseColor(Color.rgb(60, 60, 60))
                val redBmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
                redBmp.eraseColor(Color.rgb(255, 0, 0))
                val greenBmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
                greenBmp.eraseColor(Color.rgb(0, 255, 0))
                val brightBmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
                brightBmp.eraseColor(Color.rgb(230, 230, 230))
                try {
                    fun chanMean(px: Array<IntArray>, shift: Int): Double {
                        var sum = 0L
                        var n = 0
                        for (y in 24..104 step 4) {
                            for (x in 24..104 step 4) {
                                sum += (px[y][x] shr shift) and 0xFF
                                n++
                            }
                        }
                        return sum.toDouble() / n
                    }
                    fun rgb(px: Array<IntArray>): Triple<Int, Int, Int> =
                        Triple(chanMean(px, 16).toInt(), chanMean(px, 8).toInt(),
                            chanMean(px, 0).toInt())

                    setSource(darkBmp)
                    val pxTintP = renderOnce(emptyList(), EditState(
                        calibration = CalibrationParams(shadowsTint = 100f)))
                    val pxTintM = renderOnce(emptyList(), EditState(
                        calibration = CalibrationParams(shadowsTint = -100f)))
                    val pxIdent = renderOnce(emptyList(), EditState())
                    val (tr, tg, tb) = rgb(pxTintP)
                    val (mr, mg, mb) = rgb(pxTintM)
                    val (ir, ig, ib) = rgb(pxIdent)
                    val okTintP = tr in 54..78 && tg in 34..58 && tb in 54..78
                    val okTintM = mr in 41..65 && mg in 61..85 && mb in 41..65
                    val okIdent = ir in 54..66 && ig in 54..66 && ib in 54..66

                    setSource(brightBmp)
                    val pxBright = renderOnce(emptyList(), EditState(
                        calibration = CalibrationParams(shadowsTint = 100f)))
                    val (br, bg, bb) = rgb(pxBright)
                    val okBright = br in 218..242 && bg in 218..242 && bb in 218..242

                    setSource(redBmp)
                    val pxRedHue = renderOnce(emptyList(), EditState(
                        calibration = CalibrationParams(red = PrimaryAdjust(hue = 100f))))
                    val (hr, hg, hb) = rgb(pxRedHue)
                    val okRedHue = hr in 243..255 && hg in 115..139 && hb in 0..12

                    setSource(greenBmp)
                    val pxGreenHue = renderOnce(emptyList(), EditState(
                        calibration = CalibrationParams(red = PrimaryAdjust(hue = 100f))))
                    val (gr, gg, gb) = rgb(pxGreenHue)
                    val okGreenIso = gr == 0 && gg == 255 && gb == 0
                    val pxGreenSat = renderOnce(emptyList(), EditState(
                        calibration = CalibrationParams(green = PrimaryAdjust(sat = -100f))))
                    val (sr, sg, sb) = rgb(pxGreenSat)
                    val okGreenSat = sr in 115..139 && sg in 115..139 && sb in 115..139

                    val ok = okTintP && okTintM && okIdent && okBright &&
                        okRedHue && okGreenIso && okGreenSat
                    ok to "tint+100=$tr/$tg/$tb (need 54..78/34..58/54..78) " +
                        "tint-100=$mr/$mg/$mb (need 41..65/61..85/41..65) " +
                        "identity=$ir/$ig/$ib (need 54..66 each) " +
                        "bright+tint=$br/$bg/$bb (need 218..242, no-op) " +
                        "red hue+100=$hr/$hg/$hb (need 243..255/115..139/0..12) " +
                        "green untouched=$gr/$gg/$gb (need 0/255/0) " +
                        "green sat-100=$sr/$sg/$sb (need 115..139 each)"
                } finally {
                    setSource(gray)
                    darkBmp.recycle(); redBmp.recycle()
                    greenBmp.recycle(); brightBmp.recycle()
                }
            }

            // ---- check 35: detail guide tone consistency (blurAdj) ----
            // Guards the 2026-10-17 fix: the blurred detail guide is built
            // from the RAW source, so comparing it against the tone-adjusted
            // image injected a DC offset into clarity/texture/sharpening
            // (exposure +0.5 with clarity 50 lifted flat areas by ~30/255;
            // sharpening 25 added ~29/255 of global brightness) and made
            // lens-blur regions render at pre-adjustment brightness. The
            // guide now runs through the identical tone chain
            // (applyToneChain), so on a flat field clarity/sharpening must
            // not move the mean, at any exposure/WB.
            check("Detail guide tone consistency (blurAdj)") {
                val flatBmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
                flatBmp.eraseColor(Color.rgb(128, 128, 128))
                try {
                    fun lumMean(px: Array<IntArray>): Double {
                        var sum = 0L
                        var n = 0
                        for (y in 24..104 step 4) {
                            for (x in 24..104 step 4) {
                                val p = px[y][x]
                                sum += ((p shr 16) and 0xFF) * 2126L +
                                    ((p shr 8) and 0xFF) * 7152L +
                                    (p and 0xFF) * 722L
                                n++
                            }
                        }
                        return sum.toDouble() / n / 10000.0
                    }
                    setSource(flatBmp)
                    uploadCurves(EditState())
                    val expBase = EditState(light = LightParams(exposure = 0.5f))
                    val mBase = lumMean(renderOnce(emptyList(), expBase))
                    val mClarity = lumMean(renderOnce(emptyList(), expBase.copy(
                        light = expBase.light.copy(clarity = 100f))))
                    val mSharp = lumMean(renderOnce(emptyList(), expBase.copy(
                        detail = DetailParams(sharpening = 100f))))
                    val wbBase = EditState(light = LightParams(temp = 50f))
                    val mWbBase = lumMean(renderOnce(emptyList(), wbBase))
                    val mWbClarity = lumMean(renderOnce(emptyList(), wbBase.copy(
                        light = wbBase.light.copy(clarity = 100f))))
                    // The buggy math moved these means by +30..80/255; the
                    // fixed math keeps the flat-field mean. +-12 slack for
                    // guide quantization + readback jitter.
                    val okClarity = abs(mClarity - mBase) <= 12.0
                    val okSharp = abs(mSharp - mBase) <= 12.0
                    val okWb = abs(mWbClarity - mWbBase) <= 12.0
                    (okClarity && okSharp && okWb) to
                        "exp+0.5 base=${mBase.toInt()} clarity100=${mClarity.toInt()} " +
                        "(need |d|<=12) sharp100=${mSharp.toInt()} (need |d|<=12) " +
                        "wb+50 base=${mWbBase.toInt()} clarity100=${mWbClarity.toInt()} " +
                        "(need |d|<=12)"
                } finally {
                    setSource(gray)
                    flatBmp.recycle()
                }
            }

            // ---- check 36: inverted mask (uMaskInv) ----
            // Guards the 2026-10-17 fix: Mask.inverted used to multiply the
            // adjustment VALUES by -1 (a +1 EV "invert" darkened the masked
            // area by 1 EV) instead of applying the adjustments to the
            // inverse region. Inversion is now done shader-side on the mask
            // value (uMaskInv), so a +1 EV inverted left-half mask must
            // brighten the RIGHT half and leave the left half alone — and
            // the red overlay must tint the inverse region too.
            check("Inverted mask (uMaskInv)") {
                val invMask = Mask(
                    id = "tInv", name = "Self-test",
                    adjust = LocalAdjust(exposure = 100f), inverted = true,
                )
                val px = renderOnce(listOf(halfMask), EditState(masks = listOf(invMask)))
                val left = regionMean(px, 16, 48)
                val right = regionMean(px, 80, 112)
                val adjOk = right > 200 && left in 100.0..156.0 && right - left > 60
                val ov = renderOnce(
                    listOf(halfMask), EditState(masks = listOf(invMask)),
                    GlEngine.FrameOptions(overlayMask = 0, overlayAlpha = 1f),
                )
                val redOnInverse = regionPassRate(ov, 80, 112) { r, g, b ->
                    r >= 190 && r - g >= 80 && r - b >= 80
                }
                val cleanOnMask = regionPassRate(ov, 16, 48) { r, g, b ->
                    kotlin.math.abs(r - g) <= 24 && kotlin.math.abs(g - b) <= 24
                }
                val ovOk = redOnInverse >= 0.8 && cleanOnMask >= 0.8
                (adjOk && ovOk) to
                    "adj: left=${left.toInt()} (need 100..156) right=${right.toInt()} " +
                        "(need >200); overlay: red on inverse=${(redOnInverse * 100).roundToInt()}% " +
                        "clean on mask=${(cleanOnMask * 100).roundToInt()}% (need >=80% each)"
            }

            // ---- check 37: look transfer 3D LUT (sampler3D path, unit 10) ----
            // The look-transfer grade travels a path no earlier check
            // touched: CPU-baked FloatArray (ColorTransfer) -> GL_TEXTURE_3D
            // upload on texture unit 10 -> sampler3D hardware trilinear ->
            // shader mix gated by uLookLutStrength. A regression in any hop
            // (wrong unit, unbound sampler, the strength gate stuck at 0,
            // lookLutReady never set, uLookLut missing from the uniform
            // cache) would ship the Color panel's "Match a photo's look"
            // button while photos silently render unmatched. Exercises the
            // REAL path with synthetic LUTs: identity at strength 1 must be
            // (near-)identical to the LUT-off render, and a warm LUT must
            // shift red up / blue down. The live preview's LUT is
            // snapshotted and restored, so the test can never corrupt a
            // user's real match; lookLutSnapshot() returns null when none is
            // uploaded, in which case the engine is left cleared (its
            // pre-test state).
            check("Look transfer 3D LUT (sampler3D path)") {
                val saved = lookLutSnapshot()
                try {
                    setSource(gray)
                    uploadMasks(emptyList())
                    uploadRetouch(null)
                    uploadCurves(EditState())
                    val n = 9 // any n works for the shader; 9^3*3 is cheap
                    fun makeLut(rOff: Float, gOff: Float, bOff: Float): FloatArray {
                        val lut = FloatArray(n * n * n * 3)
                        for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
                            val o = (((r * n) + g) * n + b) * 3
                            lut[o] = (r / (n - 1f) + rOff).coerceIn(0f, 1f)
                            lut[o + 1] = (g / (n - 1f) + gOff).coerceIn(0f, 1f)
                            lut[o + 2] = (b / (n - 1f) + bOff).coerceIn(0f, 1f)
                        }
                        return lut
                    }
                    // isActive() needs 6-float stat lists; the contents don't
                    // matter here because the LUT is uploaded directly.
                    val stats = List(6) { 0f }
                    val on = EditState(
                        masks = noMask,
                        lookTransfer = LookTransferParams(
                            srcStats = stats, refStats = stats, strength = 1f),
                    )
                    val off = EditState(masks = noMask) // strength 0: skip path
                    uploadLookLut(makeLut(0f, 0f, 0f), n)
                    val pxIdent = renderOnce(emptyList(), on)
                    val pxBase = renderOnce(emptyList(), off)
                    uploadLookLut(makeLut(30f / 255f, 0f, -30f / 255f), n)
                    val pxWarm = renderOnce(emptyList(), on)
                    fun maxDiff(a: Array<IntArray>, b: Array<IntArray>): Int {
                        var m = 0
                        for (y in 24..104 step 4) for (x in 24..104 step 4) {
                            val pa = a[y][x]; val pb = b[y][x]
                            m = maxOf(m,
                                kotlin.math.abs(((pa shr 16) and 0xFF) - ((pb shr 16) and 0xFF)),
                                kotlin.math.abs(((pa shr 8) and 0xFF) - ((pb shr 8) and 0xFF)),
                                kotlin.math.abs((pa and 0xFF) - (pb and 0xFF)))
                        }
                        return m
                    }
                    fun chMean(px: Array<IntArray>, shift: Int): Double {
                        var sum = 0L; var cnt = 0
                        for (y in 24..104 step 4) for (x in 24..104 step 4) {
                            sum += (px[y][x] shr shift) and 0xFF; cnt++
                        }
                        return sum.toDouble() / cnt
                    }
                    val dIdent = maxDiff(pxIdent, pxBase)
                    val redDelta = chMean(pxWarm, 16) - chMean(pxIdent, 16)
                    val blueDelta = chMean(pxWarm, 0) - chMean(pxIdent, 0)
                    // identity must be within RGB8 rounding of off; warm
                    // must move red up and blue down well past noise.
                    val ok = dIdent <= 3 && redDelta > 15.0 && blueDelta < -15.0
                    ok to "identity-vs-off maxDiff=$dIdent (need <=3) " +
                        "warm redDelta=${redDelta.toInt()} (need >15) " +
                        "blueDelta=${blueDelta.toInt()} (need <-15)"
                } finally {
                    if (saved != null) uploadLookLut(saved.first, saved.second)
                    else uploadLookLut(null, 0)
                }
            }

            // ---- check 38: export multi-tile (2x2) stitching with a masked
            // local adjustment (renderExport) ----
            // Check 28 proved a 2-wide (2x1) export stitches cleanly, but
            // only along one vertical seam and with no masks. This closes
            // the remaining named gap: a 2x2 tile grid (a vertical AND a
            // horizontal seam) with an active local-adjustment mask,
            // proving mask uploads compose with per-tile negative-offset
            // viewports. A viewport/UV bug would duplicate/shift a tile
            // row (seam jumps ~100 lum on the analytic gradient) or drop
            // the mask edit from the file the user actually saves.
            check("Export multi-tile stitching + mask (renderExport)") {
                val w = 2100; val h = 2100
                val field = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val fPx = IntArray(w * h) { i ->
                    val x = i % w; val y = i / w
                    val lum = (40f + 110f * x / (w - 1) +
                        40f * y / (h - 1)).toInt()
                    Color.rgb(lum, lum, lum)
                }
                field.setPixels(fPx, 0, w, 0, 0, w, h)
                val whiteMask =
                    Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
                whiteMask.eraseColor(Color.WHITE)
                try {
                    setSource(field)
                    uploadRetouch(null)
                    uploadCurves(EditState())
                    val matrix = Geometry.uvMatrix(
                        w, h, w, h, CropParams(), GeometryParams())
                    val local = Mask(
                        id = "tStitch", name = "Self-test",
                        adjust = LocalAdjust(exposure = 100f))
                    val base = EditState(
                        light = LightParams(exposure = 1f), masks = noMask)
                    val masked = EditState(
                        light = LightParams(exposure = 1f),
                        masks = listOf(local))
                    uploadMasks(listOf(whiteMask))
                    val pxMasked = renderExport(masked, matrix) {}
                    try {
                        uploadMasks(emptyList())
                        val pxBase = renderExport(base, matrix) {}
                        try {
                            val err = GLES30.glGetError()
                            if (err != GLES30.GL_NO_ERROR) {
                                throw IllegalStateException(
                                    "GL error 0x${err.toString(16)} " +
                                        "during multi-tile renderExport")
                            }
                            fun meanLum(b: Bitmap): Double {
                                var sum = 0L; var n = 0
                                for (y in 0 until h step 16) {
                                    for (x in 0 until w step 16) {
                                        sum += (b.getPixel(x, y) shr 16) and
                                            0xFF
                                        n++
                                    }
                                }
                                return sum.toDouble() / n
                            }
                            val maskedMean = meanLum(pxMasked)
                            val baseMean = meanLum(pxBase)
                            // tile grid is 2x2 (2048px tiles): one vertical
                            // seam at x=2047/2048, one horizontal at
                            // y=2047/2048. Rows/cols within 2040..2056 are
                            // skipped where the two seams meet (corner
                            // readback can round either way legitimately).
                            var vSeam = 0
                            for (y in 0 until h step 8) {
                                if (y in 2040..2056) continue
                                val a = (pxMasked.getPixel(2047, y) shr 16) and
                                    0xFF
                                val b = (pxMasked.getPixel(2048, y) shr 16) and
                                    0xFF
                                vSeam = maxOf(vSeam, kotlin.math.abs(a - b))
                            }
                            var hSeam = 0
                            for (x in 0 until w step 8) {
                                if (x in 2040..2056) continue
                                val a = (pxMasked.getPixel(x, 2047) shr 16) and
                                    0xFF
                                val b = (pxMasked.getPixel(x, 2048) shr 16) and
                                    0xFF
                                hSeam = maxOf(hSeam, kotlin.math.abs(a - b))
                            }
                            val dimsOk = pxMasked.width == w &&
                                pxMasked.height == h &&
                                pxBase.width == w && pxBase.height == h
                            val maskLanded = maskedMean - baseMean > 40.0
                            val ok = dimsOk && maskLanded &&
                                vSeam < 16 && hSeam < 16
                            ok to "dims=${pxMasked.width}x${pxMasked.height} " +
                                "(need ${w}x${h}); masked-mean=" +
                                "${maskedMean.toInt()} base-mean=" +
                                "${baseMean.toInt()} (need +40: mask edit " +
                                "lands in export); v-seam=$vSeam h-seam=$hSeam " +
                                "(need <16 each)"
                        } finally {
                            pxBase.recycle()
                        }
                    } finally {
                        pxMasked.recycle()
                    }
                } finally {
                    field.recycle(); whiteMask.recycle()
                    uploadMasks(emptyList())
                }
            }

            // ---- check 39: retouch heal lands in the EXPORT (renderExport) ----
            // Checks 17 and 28 prove the retouch overlay uploads and the
            // Laplace heal blends — but both render through the PREVIEW path
            // (renderOnce). The file the user saves goes through renderExport,
            // a separate tiled pipeline fed by ExportManager: rasterize at
            // export resolution -> uploadRetouch -> renderExport. If any hop
            // dropped the overlay (wrong texture unit, stale uHasRetouch, a
            // tile-viewport interaction), the preview would show the healed
            // photo while the exported file kept the blemish — the exact
            // "heal silently does nothing" report from the user's error video.
            // A dark disc (the blemish) on mid gray, healed from a clean
            // patch, must come back to background tone IN THE EXPORTED
            // PIXELS; a control render without the spot must keep the disc;
            // a far-away clean region must not move (no global shift).
            check("Retouch heal in export (renderExport)") {
                val w = 1024; val h = 1024
                val bg = 0xFF969696.toInt() // 150
                val blob = 0xFF3C3C3C.toInt() // 60
                val bcx = (0.35f * w).toInt(); val bcy = h / 2
                val blobR = 40
                val field = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val fPx = IntArray(w * h) { i ->
                    val x = i % w; val y = i / w
                    val dx = x - bcx; val dy = y - bcy
                    if (dx * dx + dy * dy <= blobR * blobR) blob else bg
                }
                field.setPixels(fPx, 0, w, 0, 0, w, h)
                try {
                    setSource(field)
                    uploadCurves(EditState())
                    uploadMasks(emptyList())
                    // Same recipe as ExportManager step 2a: rasterize the
                    // spots at export resolution, then upload.
                    val spot = RetouchSpot(
                        op = RetouchOp.HEAL,
                        x = 0.35f, y = 0.5f,
                        srcX = 0.72f, srcY = 0.5f, // clean gray, well clear
                        size = 0.14f, // heal r ~72px covers the r=40 blob
                    )
                    val overlay = RetouchEngine.rasterize(
                        listOf(spot), w, h, field)
                    try {
                        uploadRetouch(overlay)
                        val matrix = Geometry.uvMatrix(
                            w, h, w, h, CropParams(), GeometryParams())
                        val withSpot = EditState(
                            masks = noMask,
                            retouch = RetouchState(spots = listOf(spot)),
                        )
                        val pxHealed = renderExport(withSpot, matrix) {}
                        try {
                            uploadRetouch(null)
                            val pxBase = renderExport(
                                EditState(masks = noMask), matrix) {}
                            try {
                                val err = GLES30.glGetError()
                                if (err != GLES30.GL_NO_ERROR) {
                                    throw IllegalStateException(
                                        "GL error 0x${err.toString(16)} " +
                                            "during retouch renderExport")
                                }
                                fun discMean(b: Bitmap): Double {
                                    var sum = 0L; var n = 0
                                    // deep inside the blob (r=40) and the
                                    // heal disc (r~72): full-strength zone
                                    for (y in bcy - 10..bcy + 10 step 2)
                                        for (x in bcx - 10..bcx + 10 step 2) {
                                            sum += (b.getPixel(x, y) shr 16) and
                                                0xFF
                                            n++
                                        }
                                    return sum.toDouble() / n
                                }
                                fun discMin(b: Bitmap): Int {
                                    var m = 255
                                    for (y in bcy - 18..bcy + 18 step 2)
                                        for (x in bcx - 18..bcx + 18 step 2)
                                            m = minOf(m,
                                                (b.getPixel(x, y) shr 16) and
                                                    0xFF)
                                    return m
                                }
                                fun cleanMean(b: Bitmap): Double {
                                    var sum = 0L; var n = 0
                                    for (y in bcy - 10..bcy + 10 step 2)
                                        for (x in 800..840 step 2) {
                                            sum += (b.getPixel(x, y) shr 16) and
                                                0xFF
                                            n++
                                        }
                                    return sum.toDouble() / n
                                }
                                val healedMean = discMean(pxHealed)
                                val healedMin = discMin(pxHealed)
                                val controlMean = discMean(pxBase)
                                val cleanShift = kotlin.math.abs(
                                    cleanMean(pxHealed) - cleanMean(pxBase))
                                val dimsOk = pxHealed.width == w &&
                                    pxHealed.height == h &&
                                    pxBase.width == w && pxBase.height == h
                                // background 150 (check-28 calibration):
                                // healed returns to it, control keeps the
                                // ~60 blob, clean area must not move.
                                val ok = dimsOk &&
                                    healedMean in 115.0..185.0 &&
                                    healedMin > 95 &&
                                    controlMean < 100.0 &&
                                    cleanShift < 12.0
                                ok to "dims=${pxHealed.width}x" +
                                    "${pxHealed.height} (need ${w}x${h}); " +
                                    "healed-mean=${healedMean.toInt()} " +
                                    "(need 115..185) healed-min=$healedMin " +
                                    "(need >95) control-mean=" +
                                    "${controlMean.toInt()} (need <100: " +
                                    "blemish still there without the spot) " +
                                    "clean-shift=${cleanShift.toInt()} " +
                                    "(need <12)"
                            } finally {
                                pxBase.recycle()
                            }
                        } finally {
                            pxHealed.recycle()
                        }
                    } finally {
                        overlay.recycle()
                    }
                } finally {
                    field.recycle()
                    uploadRetouch(null)
                    uploadMasks(emptyList())
                }
            }
            // ---- check 40: watermark stamp lands on the export bitmap ----
            // applyWatermark is pure-CPU Canvas drawing, so until now nothing
            // verified the stamp actually appears on export pixels: check 32
            // covers the layout math, and the export call site swallows stamp
            // failures (keeps the export, silently drops the signature). This
            // exercises the real production stamp path (now top-level so the
            // test can reach it) on a dark field and requires bright text
            // pixels exactly where watermarkLayout says the text goes — and
            // none anywhere else. Also locks in the two no-op contracts:
            // inactive watermark and immutable bitmap must not draw or throw.
            check("Watermark stamp in export bitmap") {
                val w = 512; val h = 512
                val bg = Color.rgb(40, 40, 40)
                val field = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                field.eraseColor(bg)
                try {
                    val wm = WatermarkSettings(
                        enabled = true, text = "H2W",
                        position = WatermarkPosition.BOTTOM_RIGHT,
                        sizeFrac = 0.035f, opacity = 1f,
                    )
                    applyWatermark(field, wm)
                    val layout = watermarkLayout(w, h, wm)
                    // RIGHT-aligned text ends at layout.x; the generous width
                    // (8x text height) covers "H2W" several times over, and
                    // the band covers cap height + descenders.
                    val rx0 = (layout.x - 8f * layout.textPx).toInt()
                        .coerceAtLeast(0)
                    val rx1 = layout.x.toInt().coerceAtMost(w - 1)
                    val ry0 = (layout.baselineY - 1.2f * layout.textPx).toInt()
                        .coerceAtLeast(0)
                    val ry1 = (layout.baselineY + 0.5f * layout.textPx).toInt()
                        .coerceAtMost(h - 1)
                    fun brightCount(x0: Int, y0: Int, x1: Int, y1: Int): Int {
                        var n = 0
                        for (y in y0..y1 step 2) for (x in x0..x1 step 2) {
                            if (((field.getPixel(x, y) shr 16) and 0xFF) > 150) n++
                        }
                        return n
                    }
                    val textBright = brightCount(rx0, ry0, rx1, ry1)
                    // control region far from every watermark position's text
                    val controlBright = brightCount(8, 8, 120, 120)
                    // inactive watermark must leave the bitmap untouched
                    val quiet = Bitmap.createBitmap(
                        w, h, Bitmap.Config.ARGB_8888)
                    quiet.eraseColor(bg)
                    val untouched = try {
                        applyWatermark(quiet, wm.copy(enabled = false))
                        quiet.getPixel(w / 2, h / 2) == bg &&
                            quiet.getPixel(rx1 - 4, ry1 - 4) == bg
                    } finally {
                        quiet.recycle()
                    }
                    // immutable bitmap: must no-op, never throw
                    val plain = Bitmap.createBitmap(
                        w, h, Bitmap.Config.ARGB_8888)
                    plain.eraseColor(bg)
                    val frozen = plain.copy(Bitmap.Config.ARGB_8888, false)
                    plain.recycle()
                    val frozeOk = try {
                        applyWatermark(frozen, wm)
                        frozen.getPixel(w - 20, h - 20) == bg
                    } catch (_: Throwable) {
                        false
                    } finally {
                        frozen.recycle()
                    }
                    val ok = textBright >= 20 && controlBright == 0 &&
                        untouched && frozeOk
                    ok to "text-bright=$textBright (need >=20) " +
                        "control-bright=$controlBright (need 0) " +
                        "inactive-noop=$untouched immutable-noop=$frozeOk"
                } finally {
                    field.recycle()
                }
            }
            // ---- check 41: moiré reduction kills chroma oscillation (renderExport) ----
            // Pro-spec Phase 3: the new Detail > Moiré slider must visibly
            // reduce false-color oscillation on a synthetic moiré field (rapid
            // chroma stripes on flat luma, like fabric/screen interference)
            // while leaving a flat control region untouched and preserving
            // luminance. Renders through renderExport (the saved-file path),
            // like checks 38/39, with color NR explicitly off so the delta is
            // the moiré stage alone.
            check("Moiré reduction kills chroma oscillation") {
                val w = 1024; val h = 1024
                // Pinkish / blueish stripes with (near-)identical luma ~144:
                // pure chroma oscillation at 2px period, flat luminance.
                val stripeA = 200 shl 16 or (120 shl 8) or 120 or
                    (0xFF shl 24)
                val stripeB = 120 shl 16 or (145 shl 8) or 200 or
                    (0xFF shl 24)
                val flat = 0xFF969696.toInt() // 150
                val field = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val fPx = IntArray(w * h) { i ->
                    val x = i % w
                    if (x < 512) {
                        if ((x / 2) % 2 == 0) stripeA else stripeB
                    } else flat
                }
                field.setPixels(fPx, 0, w, 0, 0, w, h)
                try {
                    setSource(field)
                    uploadCurves(EditState())
                    uploadMasks(emptyList())
                    val matrix = Geometry.uvMatrix(
                        w, h, w, h, CropParams(), GeometryParams())
                    val baseState = EditState(
                        masks = noMask,
                        detail = DetailParams(
                            colorNr = 0f, luminanceNr = 0f),
                    )
                    val pxBase = renderExport(baseState, matrix) {}
                    try {
                        val pxTreated = renderExport(
                            baseState.copy(
                                detail = baseState.detail.copy(moire = 100f)),
                            matrix) {}
                        try {
                            val err = GLES30.glGetError()
                            if (err != GLES30.GL_NO_ERROR) {
                                throw IllegalStateException(
                                    "GL error 0x${err.toString(16)} " +
                                        "during moire renderExport")
                            }
                            if (pxBase.width != w || pxBase.height != h ||
                                pxTreated.width != w || pxTreated.height != h
                            ) {
                                throw IllegalStateException(
                                    "moire render dims ${pxBase.width}x" +
                                        "${pxBase.height} / ${pxTreated.width}x" +
                                        "${pxTreated.height}, expected " +
                                        "${w}x$h")
                            }
                            // RMS chroma magnitude (length of rgb-luma) over
                            // a region; also mean luma.
                            fun chromaRms(b: Bitmap, x0: Int, x1: Int): Double {
                                var sumSq = 0.0; var n = 0
                                for (y in 300..700 step 4)
                                    for (x in x0..x1 step 4) {
                                        val p = b.getPixel(x, y)
                                        val r = (p shr 16) and 0xFF
                                        val g = (p shr 8) and 0xFF
                                        val bl = p and 0xFF
                                        val l = 0.299 * r + 0.587 * g +
                                            0.114 * bl
                                        val cr = r - l; val cg = g - l
                                        val cb = bl - l
                                        sumSq += cr * cr + cg * cg + cb * cb
                                        n++
                                    }
                                return kotlin.math.sqrt(sumSq / n)
                            }
                            fun lumaMean(b: Bitmap, x0: Int, x1: Int): Double {
                                var sum = 0L; var n = 0
                                for (y in 300..700 step 4)
                                    for (x in x0..x1 step 4) {
                                        val p = b.getPixel(x, y)
                                        val r = (p shr 16) and 0xFF
                                        val g = (p shr 8) and 0xFF
                                        val bl = p and 0xFF
                                        sum += (0.299 * r + 0.587 * g +
                                            0.114 * bl).toLong()
                                        n++
                                    }
                                return sum.toDouble() / n
                            }
                            // Well inside each half: the guide blur bleeds
                            // across the mid boundary.
                            val baseOsc = chromaRms(pxBase, 100, 400)
                            val treatedOsc = chromaRms(pxTreated, 100, 400)
                            val baseFlat = chromaRms(pxBase, 620, 920)
                            val treatedFlat = chromaRms(pxTreated, 620, 920)
                            val lumaShift = kotlin.math.abs(
                                lumaMean(pxTreated, 100, 400) -
                                    lumaMean(pxBase, 100, 400))
                            val ok = treatedOsc <= 0.6 * baseOsc &&
                                treatedFlat <= baseFlat + 5.0 &&
                                lumaShift <= 10.0
                            ok to "osc-rms base=${"%.1f".format(baseOsc)} " +
                                "treated=${"%.1f".format(treatedOsc)} " +
                                "(need <=60% of base) flat-rms base=" +
                                "${"%.1f".format(baseFlat)} treated=" +
                                "${"%.1f".format(treatedFlat)} " +
                                "(need <= base+5) luma-shift=" +
                                "${"%.1f".format(lumaShift)} (need <=10)"
                        } finally {
                            pxTreated.recycle()
                        }
                    } finally {
                        pxBase.recycle()
                    }
                } finally {
                    field.recycle()
                    uploadMasks(emptyList())
                }
            }

            // ---- check 42: deconvolution sharpens a blurred edge (renderExport) ----
            // Pro-spec Phase 3: the new Detail > Deconvolution slider inverts
            // mild capture blur with a Wiener inverse kernel. A synthetic
            // blurred step edge rendered through renderExport (the saved-file
            // path) must come out steeper than the untreated control, with
            // no global brightness shift (kernel sums to 1) and no GL error.
            check("Deconvolution sharpens blurred edge") {
                val w = 512; val h = 512
                // Sharp step edge blurred with a TRUE gaussian (sigma 1.5px,
                // built on the CPU): the Wiener kernel (radius 1.0) inverts
                // part of that blur, so the treated render must measure a
                // steeper edge than the control. A broad tanh profile would
                // NOT work here — its 10-90% width lives in the tails where
                // a 5x5 kernel cannot reach.
                val sigma = 1.5
                val kr = 6
                val gk = DoubleArray(2 * kr + 1) { i ->
                    val x = (i - kr).toDouble()
                    kotlin.math.exp(-x * x / (2 * sigma * sigma))
                }
                val gs = gk.sum()
                val fPx = IntArray(w * h) { i ->
                    val x = i % w
                    var v = 0.0
                    for (k in -kr..kr) {
                        val sx = (x + k).coerceIn(0, w - 1)
                        val step = if (sx < 256) 0.0 else 1.0
                        v += step * gk[k + kr] / gs
                    }
                    val b = (v * 255).toInt().coerceIn(0, 255)
                    (0xFF shl 24) or (b shl 16) or (b shl 8) or b
                }
                val field = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                field.setPixels(fPx, 0, w, 0, 0, w, h)
                try {
                    setSource(field)
                    uploadCurves(EditState())
                    uploadMasks(emptyList())
                    val matrix = Geometry.uvMatrix(
                        w, h, w, h, CropParams(), GeometryParams())
                    val baseState = EditState(
                        masks = noMask,
                        detail = DetailParams(
                            colorNr = 0f, luminanceNr = 0f),
                    )
                    val pxBase = renderExport(baseState, matrix) {}
                    try {
                        val pxTreated = renderExport(
                            baseState.copy(
                                detail = baseState.detail.copy(
                                    deconv = 100f, deconvRadius = 1.0f)),
                            matrix) {}
                        try {
                            val err = GLES30.glGetError()
                            if (err != GLES30.GL_NO_ERROR) {
                                throw IllegalStateException(
                                    "GL error 0x${err.toString(16)} " +
                                        "during deconv renderExport")
                            }
                            if (pxBase.width != w || pxBase.height != h ||
                                pxTreated.width != w || pxTreated.height != h
                            ) {
                                throw IllegalStateException(
                                    "deconv render dims ${pxBase.width}x" +
                                        "${pxBase.height} / ${pxTreated.width}x" +
                                        "${pxTreated.height}, expected " +
                                        "${w}x$h")
                            }
                            // 10-90% transition width of the edge at mid-height.
                            fun edgeWidth(b: Bitmap): Double {
                                val y = h / 2
                                fun at(x: Int): Double {
                                    val p = b.getPixel(x, y)
                                    return ((p shr 16) and 0xFF).toDouble()
                                }
                                fun cross(level: Double): Double {
                                    var x = 230
                                    while (x < 282 && at(x) < level) x++
                                    return x.toDouble()
                                }
                                return cross(229.5) - cross(25.5)
                            }
                            fun lumaMean(b: Bitmap): Double {
                                var sum = 0L; var n = 0
                                for (y in 100..400 step 8)
                                    for (x in 100..400 step 8) {
                                        val p = b.getPixel(x, y)
                                        sum += ((p shr 16) and 0xFF).toLong()
                                        n++
                                    }
                                return sum.toDouble() / n
                            }
                            val wBase = edgeWidth(pxBase)
                            val wTreated = edgeWidth(pxTreated)
                            val lumaShift = kotlin.math.abs(
                                lumaMean(pxTreated) - lumaMean(pxBase))
                            val ok = wTreated < wBase - 0.5 && lumaShift <= 8.0
                            ok to "edge-width base=${"%.1f".format(wBase)} " +
                                "treated=${"%.1f".format(wTreated)} " +
                                "(need < base-0.5) luma-shift=" +
                                "${"%.1f".format(lumaShift)} (need <=8)"
                        } finally {
                            pxTreated.recycle()
                        }
                    } finally {
                        pxBase.recycle()
                    }
                } finally {
                    field.recycle()
                    uploadMasks(emptyList())
                }
            }
            // ---- check 43: AI Pro Retouch white-balance pre-correction ----
            // Pro step 1: neutralize the illuminant cast inside the fused LUT
            // before the creative grade. Pure-CPU check over the real
            // AiProRetouch math: a synthetic green-cast (fluorescent-style)
            // gray gradient must yield wbGains that pull green down and lift
            // red/blue, and the warped identity LUT must map a cast pixel
            // back to neutral. A cast-free photo must yield null gains (the
            // honest "don't touch intentional warmth" guard), and identity
            // gains must reproduce the input LUT bit-for-bit.
            check("AI Pro Retouch white-balance correction") {
                val w = 160; val h = 160
                fun synth(greenCast: Boolean): IntArray {
                    return IntArray(w * h) { i ->
                        val y = i / w
                        val v = 70 + (120 * y / (h - 1))
                        val r = if (greenCast) (v * 0.85f) else v.toFloat()
                        val g = if (greenCast) (v * 1.15f) else v.toFloat()
                        val b = if (greenCast) (v * 0.90f) else v.toFloat()
                        val ri = r.toInt().coerceIn(0, 255)
                        val gi = g.toInt().coerceIn(0, 255)
                        val bi = b.toInt().coerceIn(0, 255)
                        (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
                    }
                }
                val n = 9
                fun identityLut(): FloatArray {
                    val lut = FloatArray(n * n * n * 3)
                    for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
                        val o = (((r * n) + g) * n + b) * 3
                        lut[o] = r / (n - 1f)
                        lut[o + 1] = g / (n - 1f)
                        lut[o + 2] = b / (n - 1f)
                    }
                    return lut
                }
                // Trilinear sample (mirrors AiProRetouch's private sampler).
                fun sample(lut: FloatArray, r: Float, g: Float, b: Float): FloatArray {
                    val sc = (n - 1).toFloat()
                    val fr = (r * sc).coerceIn(0f, sc)
                    val fg = (g * sc).coerceIn(0f, sc)
                    val fb = (b * sc).coerceIn(0f, sc)
                    val r0 = fr.toInt().coerceIn(0, n - 2)
                    val g0 = fg.toInt().coerceIn(0, n - 2)
                    val b0 = fb.toInt().coerceIn(0, n - 2)
                    val tr = fr - r0; val tg = fg - g0; val tb = fb - b0
                    fun cell(ri: Int, gi: Int, bi: Int, c: Int): Float =
                        lut[((((ri * n) + gi) * n + bi) * 3) + c]
                    return FloatArray(3) { c ->
                        val c00 = cell(r0, g0, b0, c) + (cell(r0 + 1, g0, b0, c) - cell(r0, g0, b0, c)) * tr
                        val c10 = cell(r0, g0 + 1, b0, c) + (cell(r0 + 1, g0 + 1, b0, c) - cell(r0, g0 + 1, b0, c)) * tr
                        val c01 = cell(r0, g0, b0 + 1, c) + (cell(r0 + 1, g0, b0 + 1, c) - cell(r0, g0, b0 + 1, c)) * tr
                        val c11 = cell(r0, g0 + 1, b0 + 1, c) + (cell(r0 + 1, g0 + 1, b0 + 1, c) - cell(r0, g0 + 1, b0 + 1, c)) * tr
                        val c0 = c00 + (c10 - c00) * tg
                        val c1 = c01 + (c11 - c01) * tg
                        c0 + (c1 - c0) * tb
                    }
                }
                val castPx = synth(greenCast = true)
                val castStats = AiProRetouch.computeStats(castPx, w, h)
                val gains = AiProRetouch.computeWbGains(castStats)
                val ident = identityLut()
                val identWarped = AiProRetouch.warpLutForWb(
                    ident, floatArrayOf(1f, 1f, 1f), n)
                var identErr = 0f
                for (i in ident.indices) {
                    identErr = maxOf(identErr, kotlin.math.abs(identWarped[i] - ident[i]))
                }
                // A mid-gray cast pixel through the WB-warped identity LUT.
                val v = 128
                val castColor = floatArrayOf(
                    (v * 0.85f).toInt() / 255f,
                    (v * 1.15f).toInt() / 255f,
                    (v * 0.90f).toInt() / 255f)
                var neutralErr = 1f
                var gainReport = "null"
                var ok = true
                val reasons = mutableListOf<String>()
                if (!castStats.wbReliable) {
                    ok = false; reasons += "wbReliable=false on cast photo"
                }
                if (gains == null) {
                    ok = false; reasons += "gains null on cast photo"
                } else {
                    gainReport = gains.joinToString("/") { "%.3f".format(it) }
                    if (!(gains[1] < 0.95f && gains[0] > 1.02f && gains[2] > 1.02f)) {
                        ok = false
                        reasons += "gains don't counter green cast ($gainReport)"
                    }
                    val warped = AiProRetouch.warpLutForWb(ident, gains, n)
                    val fixed = sample(warped, castColor[0], castColor[1], castColor[2])
                    neutralErr = maxOf(
                        kotlin.math.abs(fixed[0] - fixed[1]),
                        kotlin.math.abs(fixed[1] - fixed[2]))
                    if (neutralErr > 3f / 255f) {
                        ok = false
                        reasons += "cast pixel not neutralized (err=${"%.4f".format(neutralErr)})"
                    }
                }
                if (identErr != 0f) {
                    ok = false; reasons += "identity warp not bit-identical (err=$identErr)"
                }
                // Honesty guard: a cast-free photo must not be "corrected".
                val neutralStats = AiProRetouch.computeStats(synth(greenCast = false), w, h)
                val neutralGains = AiProRetouch.computeWbGains(neutralStats)
                if (neutralGains != null) {
                    ok = false; reasons += "gains non-null on neutral photo"
                }
                // Full decision path: analyze() must carry the gains.
                val blend = AiProRetouch.analyze(castPx, w, h)
                if (blend.wbGains == null) {
                    ok = false; reasons += "analyze() dropped wbGains"
                }
                val detail = if (reasons.isEmpty()) {
                    "gains=$gainReport neutralErr=${"%.4f".format(neutralErr)} " +
                        "identityErr=$identErr neutralPhoto=null OK"
                } else reasons.joinToString("; ")
                ok to detail
            }
            // ---- check 44: hair decontamination (edge despill) ----
            // Lightroom "Decontaminate colors": feathered edge pixels are
            // recolored from the selected interior so background changes
            // leave no halo around hair. Pure-CPU check over the real
            // HairDecontaminate math: a synthetic red-subject / blue-
            // background photo with a gradient mask band must pull band
            // pixels toward the interior color, leave interior/exterior
            // untouched, be identity at strength 0, and honor the donor
            // radius. The MaskEngine integration path is checked too
            // (off-switch, output geometry, inverted donor side).
            check("Hair decontamination despill") {
                val w = 12; val h = 12
                val red = (0xFF shl 24) or (0xFF shl 16)
                val blue = (0xFF shl 24) or 0xFF
                val purple = (0xFF shl 24) or (0x80 shl 16) or 0x80
                // mask: cols 0..3 = 255 (subject), cols 4..7 = 192/128/64/32
                // (contaminated band), cols 8..11 = 0 (background)
                val band = intArrayOf(192, 128, 64, 32)
                val mask = IntArray(w * h) { i ->
                    val x = i % w
                    when {
                        x < 4 -> 255
                        x < 8 -> band[x - 4]
                        else -> 0
                    }
                }
                val src = IntArray(w * h) { i ->
                    val x = i % w
                    when {
                        x < 4 -> red
                        x < 8 -> purple // fringe: half subject, half background
                        else -> blue
                    }
                }
                val reasons = mutableListOf<String>()
                val out = HairDecontaminate.despill(src, w, h, mask, 1f, 3)
                if (out[2] != red) reasons += "interior pixel changed"
                if (out[10] != blue) reasons += "exterior pixel changed"
                // x=5 (mask 128): t=(1-128/255)≈0.498 toward red
                val px = out[5]
                val r = (px shr 16) and 0xFF; val b = px and 0xFF
                if (r <= 0x80 || b >= 0x80) {
                    reasons += "band pixel not despilled (r=$r b=$b)"
                }
                if (r >= 0xFF) reasons += "band pixel snapped instead of blended"
                // strength 0 = identity
                if (!HairDecontaminate.despill(src, w, h, mask, 0f, 3).contentEquals(src)) {
                    reasons += "strength 0 not identity"
                }
                // x=7 (mask 32) is 4px from the interior: radius 3 must not
                // reach it, radius 5 must.
                if (HairDecontaminate.despill(src, w, h, mask, 1f, 3)[7] != purple) {
                    reasons += "radius 3 reached too far"
                }
                val px7 = HairDecontaminate.despill(src, w, h, mask, 1f, 5)[7]
                if (((px7 shr 16) and 0xFF) <= 0x80) {
                    reasons += "radius 5 did not despill outer band"
                }
                // MaskEngine integration: off-switch, geometry, inversion.
                val rasterBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                rasterBmp.setPixels(IntArray(w * h) { i ->
                    val v = mask[i] and 0xFF
                    (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                }, 0, w, 0, 0, w, h)
                val srcBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                srcBmp.setPixels(src, 0, w, 0, 0, w, h)
                val offMask = Mask(id = "t", name = "t", decontaminate = 0f)
                if (MaskEngine.despilledBitmap(offMask, rasterBmp, srcBmp) != null) {
                    reasons += "decontaminate=0 did not return null"
                }
                // Solid interior (all 255): despill touches nothing, so the
                // output must equal the scaled source exactly.
                val solidBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                solidBmp.setPixels(IntArray(w * h) { 0xFFFFFFFF.toInt() }, 0, w, 0, 0, w, h)
                val redBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                redBmp.setPixels(IntArray(w * h) { red }, 0, w, 0, 0, w, h)
                val solidOut = MaskEngine.despilledBitmap(
                    Mask(id = "t", name = "t", decontaminate = 1f, inverted = true),
                    solidBmp, redBmp)
                if (solidOut == null) {
                    reasons += "despilledBitmap returned null while enabled"
                } else {
                    if (solidOut.width != MaskEngine.MAX_RASTER ||
                        solidOut.height != MaskEngine.MAX_RASTER) {
                        reasons += "despill bitmap not MAX_RASTER square"
                    }
                    val probe = IntArray(1)
                    solidOut.getPixels(probe, 0, 1,
                        solidOut.width / 2, solidOut.height / 2, 1, 1)
                    if (probe[0] != red) {
                        reasons += "solid interior pixel altered (inverted path)"
                    }
                    solidOut.recycle()
                }
                rasterBmp.recycle(); srcBmp.recycle()
                solidBmp.recycle(); redBmp.recycle()
                val detail = if (reasons.isEmpty()) {
                    "band r=$r b=$b (was 128/128); interior/exterior untouched; " +
                        "strength-0 identity; radius honored; off-switch + inversion OK"
                } else reasons.joinToString("; ")
                (reasons.isEmpty()) to detail
            }
            // ---- check 45: HDR merge (Mertens exposure fusion) ----
            // Pro-spec Phase 4 "Merge to HDR": pure-CPU check over the real
            // HdrMerge math. Synthetic brackets: (a) three identical frames
            // must fuse to the exact input (equal weights normalize to 1/3);
            // (b) left-good/right-good pair must pick each side's
            // well-exposed frame (~128 both sides); (c) a dark+bright ramp
            // bracket must stay in range and opaque; (d) single frame is an
            // identity copy; (e) workingSize caps the long side.
            check("HDR merge exposure fusion") {
                val reasons = mutableListOf<String>()
                val w = 24; val h = 24; val n = w * h
                fun gray(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                fun rOf(px: Int) = (px shr 16) and 0xFF
                val ramp = IntArray(n) { i -> gray((i * 37 + 11) % 256) }
                val fusedSame = HdrMerge.fuse(listOf(ramp, ramp, ramp), w, h)
                if (!fusedSame.contentEquals(ramp)) {
                    val bad = fusedSame.indices.count { fusedSame[it] != ramp[it] }
                    reasons += "identical frames did not fuse to self ($bad px)"
                }
                val a = IntArray(n) { i -> gray(if ((i % w) < w / 2) 128 else 8) }
                val b = IntArray(n) { i -> gray(if ((i % w) < w / 2) 247 else 128) }
                val sel = HdrMerge.fuse(listOf(a, b), w, h)
                fun avg(x0: Int, x1: Int): Double {
                    var s = 0L; var c = 0
                    for (i in sel.indices) {
                        val x = i % w
                        if (x in x0 until x1) { s += rOf(sel[i]); c++ }
                    }
                    return s.toDouble() / c
                }
                val left = avg(2, w / 2 - 2); val right = avg(w / 2 + 2, w - 2)
                if (left !in 100.0..160.0) reasons += "left did not pick frame A (avg=$left)"
                if (right !in 100.0..160.0) reasons += "right did not pick frame B (avg=$right)"
                val dark = IntArray(n) { i -> gray((((i % w).toDouble() / (w - 1)) * 255 * 0.25).toInt()) }
                val bright = IntArray(n) { i ->
                    gray((((i % w).toDouble() / (w - 1)) * 255 * 4.0).toInt().coerceAtMost(255))
                }
                val fusedRamp = HdrMerge.fuse(listOf(dark, bright), w, h)
                if (fusedRamp.size != n) reasons += "wrong output size ${fusedRamp.size}"
                if (fusedRamp.any { (it ushr 24) != 0xFF }) reasons += "alpha not opaque"
                val midR = rOf(fusedRamp[(h / 2) * w + w / 2])
                if (midR !in 20..235) reasons += "midtone crushed/clipped (r=$midR)"
                val one = HdrMerge.fuse(listOf(ramp), w, h)
                if (!one.contentEquals(ramp) || one === ramp) {
                    reasons += "single frame not an identity copy"
                }
                val (sw, sh) = HdrMerge.workingSize(4000, 3000)
                if (sw != 1280 || sh != 960) reasons += "workingSize cap wrong ($sw x $sh)"
                val detail = if (reasons.isEmpty()) {
                    "identical->self exact; A/B selection avg " +
                        "${left.toInt()}/${right.toInt()} (~128); ramp midtone r=$midR; " +
                        "single-frame identity; 4000x3000 capped to 1280x960"
                } else reasons.joinToString("; ")
                (reasons.isEmpty()) to detail
            }
            // ---- check 46: focus stacking (modified-Laplacian + pyramids) ----
            // Pro-spec Phase 4 "Focus Stack": pure-CPU check over the real
            // FocusStack math. Synthetic pair: frame A sharp checkerboard on
            // the left / flat gray on the right, frame B mirrored. Inset
            // regions (away from the 4px measure-blur transition) must pick
            // the sharp frame's pixels; identical frames must stack to self
            // (within 1 LSB float rounding); single frame is an identity
            // copy; workingSize caps the long side.
            check("focus stacking sharp-frame selection") {
                val reasons = mutableListOf<String>()
                val w = 32; val h = 32; val n = w * h
                fun gray(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                fun rOf(px: Int) = (px shr 16) and 0xFF
                val a = IntArray(n) { i ->
                    val x = i % w
                    if (x < w / 2) gray(if (((i / w) + x) % 2 == 0) 0 else 255)
                    else gray(128)
                }
                val b = IntArray(n) { i ->
                    val x = i % w
                    if (x < w / 2) gray(128)
                    else gray(if (((i / w) + x) % 2 == 0) 0 else 255)
                }
                val out = FocusStack.stack(listOf(a, b), w, h)
                var badLeft = 0; var badRight = 0
                for (i in out.indices) {
                    val x = i % w
                    if (x in 2..(w / 2 - 7)) {
                        if (kotlin.math.abs(rOf(out[i]) - rOf(a[i])) > 14) badLeft++
                    }
                    if (x in (w / 2 + 7)..(w - 3)) {
                        if (kotlin.math.abs(rOf(out[i]) - rOf(b[i])) > 14) badRight++
                    }
                }
                if (badLeft > 0) reasons += "left region missed sharp frame A ($badLeft px)"
                if (badRight > 0) reasons += "right region missed sharp frame B ($badRight px)"
                if (out.any { (it ushr 24) != 0xFF }) reasons += "alpha not opaque"
                val ramp = IntArray(n) { i -> gray((i * 37 + 11) % 256) }
                val same = FocusStack.stack(listOf(ramp, ramp, ramp), w, h)
                val maxD = same.indices.maxOf { i ->
                    kotlin.math.abs(rOf(same[i]) - rOf(ramp[i]))
                }
                if (maxD > 1) reasons += "identical frames not self (maxD=$maxD)"
                val one = FocusStack.stack(listOf(ramp), w, h)
                if (!one.contentEquals(ramp) || one === ramp) {
                    reasons += "single frame not an identity copy"
                }
                val (sw, sh) = FocusStack.workingSize(4000, 3000)
                if (sw != 1280 || sh != 960) reasons += "workingSize cap wrong ($sw x $sh)"
                val detail = if (reasons.isEmpty()) {
                    "sharp A/B regions picked (0 bad px); identical->self " +
                        "maxD=$maxD LSB; single-frame identity; cap 1280x960"
                } else reasons.joinToString("; ")
                (reasons.isEmpty()) to detail
            }
            // ---- check 47: panorama stitch (translation match + composite) ----
            // Pro-spec Phase 4 "Panorama": pure-CPU check over the real
            // PanoramaStitch math. Synthetic scene 60px wide; frame A covers
            // x in [0,40), frame B covers x in [20,60) (20px overlap). (a) the
            // shift estimate must find dx=20; (b) the stitch must come out
            // 60px wide with no black borders; (c) the overlap blend must
            // reconstruct the scene (frames are exact crops of it); (d)
            // single frame is an identity copy; (e) empty list throws;
            // (f) workingSize caps the long side.
            check("panorama stitching alignment") {
                val reasons = mutableListOf<String>()
                val sw = 60; val sh = 24
                fun gray(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                fun rOf(px: Int) = (px shr 16) and 0xFF
                // Deterministic discriminative scene: vertical stripes with a
                // widening gradient so every column is unique.
                val scene = IntArray(sw * sh) { i ->
                    val x = i % sw; val y = i / sw
                    gray(((x * 37 + (x / 7) * 53 + y * 61 + 11) % 256))
                }
                val a = IntArray(40 * sh) { i -> scene[(i / 40) * sw + (i % 40)] }
                val b = IntArray(40 * sh) { i -> scene[(i / 40) * sw + 20 + (i % 40)] }
                val (mw, mh) = PanoramaStitch.matchSize(40, sh)
                val shift = PanoramaStitch.estimateShift(
                    PanoramaStitch.lumDown(a, 40, sh, mw, mh),
                    PanoramaStitch.lumDown(b, 40, sh, mw, mh), mw, mh)
                val expectDx = ((20f * mw) / 40f).roundToInt()
                if (kotlin.math.abs(shift.first - expectDx) > 1) {
                    reasons += "shift estimate ${shift.first} != ~$expectDx"
                }
                val res = PanoramaStitch.stitchFull(listOf(a, b), 40, sh)
                if (res.width != 60 || res.height != sh) {
                    reasons += "stitched size ${res.width}x${res.height} != 60x$sh"
                }
                if (res.pixels.size != res.width * res.height) {
                    reasons += "pixel count/size mismatch"
                }
                var bad = 0
                if (res.width == 60) {
                    for (i in res.pixels.indices) {
                        val x = i % 60; val y = i / 60
                        if (x in 4 until 56 && y in 2 until sh - 2) {
                            if (kotlin.math.abs(rOf(res.pixels[i]) - rOf(scene[y * sw + x])) > 8) bad++
                        }
                    }
                }
                if (bad > 0) reasons += "overlap reconstruction off ($bad px)"
                if (res.pixels.any { (it ushr 24) != 0xFF }) reasons += "alpha not opaque"
                val one = PanoramaStitch.stitch(listOf(a), 40, sh)
                if (!one.contentEquals(a) || one === a) {
                    reasons += "single frame not an identity copy"
                }
                var threw = false
                try { PanoramaStitch.stitch(emptyList(), 40, sh) }
                catch (_: IllegalArgumentException) { threw = true }
                if (!threw) reasons += "empty list did not throw"
                val (cw, ch) = PanoramaStitch.workingSize(4000, 3000)
                if (cw != 1600 || ch != 1200) reasons += "workingSize cap wrong ($cw x $ch)"
                val detail = if (reasons.isEmpty()) {
                    "shift dx=${shift.first} (~$expectDx); 60x24 composite, " +
                        "0 bad px; single-frame identity; empty->throws; cap 1600x1200"
                } else reasons.joinToString("; ")
                (reasons.isEmpty()) to detail
            }
            // ---- check 48: unbound sampler3D guard (P0 black-preview regression) ----
            // 2026-10-17 P0: when AI Pro Retouch (or Look Transfer) was enabled
            // in the edit state but no 3D LUT had been uploaded, uAiLut /
            // uLookLut kept their default (0), pointing the sampler3D at unit
            // 0's 2D source texture. Mali drivers resolve that type mismatch
            // by blackening the whole frame — the exact "editor preview fully
            // black" his vivo V60 showed on 2026-10-17 18:12, even though the
            // strength gate skips the texture() call. The fix always sets the
            // uniforms (9/10), binds a 1x1x1 dummy 3D texture when no LUT is
            // ready, and forces strength to 0 — so an enabled-but-unuploaded
            // grade must render IDENTICAL to the grade-off render. This check
            // replays that exact scenario for both LUTs: it FAILS on a pre-fix
            // Mali driver (black frame) and PASSES post-fix, guarding the fix
            // against future refactors of the texture-bind logic.
            check("unbound sampler3D guard (P0 regression)") {
                val savedAi = aiLutSnapshot()
                val savedLook = lookLutSnapshot()
                try {
                    uploadAiLut(null, 0)
                    uploadLookLut(null, 0)
                    setSource(gray)
                    uploadMasks(emptyList())
                    uploadRetouch(null)
                    uploadCurves(EditState())
                    val base = EditState(masks = noMask)
                    val aiOn = EditState(
                        masks = noMask,
                        aiRetouch = AiRetouchParams(enabled = true, strength = 1f),
                    )
                    val lookOn = EditState(
                        masks = noMask,
                        lookTransfer = LookTransferParams(
                            srcStats = List(6) { 0f },
                            refStats = List(6) { 0f },
                            strength = 1f,
                        ),
                    )
                    val pxBase = renderOnce(emptyList(), base)
                    val pxAi = renderOnce(emptyList(), aiOn)
                    val pxLook = renderOnce(emptyList(), lookOn)
                    fun maxDiff(a: Array<IntArray>, b: Array<IntArray>): Int {
                        var m = 0
                        for (y in 24..104 step 4) for (x in 24..104 step 4) {
                            val pa = a[y][x]; val pb = b[y][x]
                            m = maxOf(m,
                                kotlin.math.abs(((pa shr 16) and 0xFF) - ((pb shr 16) and 0xFF)),
                                kotlin.math.abs(((pa shr 8) and 0xFF) - ((pb shr 8) and 0xFF)),
                                kotlin.math.abs((pa and 0xFF) - (pb and 0xFF)))
                        }
                        return m
                    }
                    fun meanLuma(px: Array<IntArray>): Double {
                        var sum = 0L; var cnt = 0
                        for (y in 24..104 step 4) for (x in 24..104 step 4) {
                            val p = px[y][x]
                            sum += (((p shr 16) and 0xFF) * 299 +
                                ((p shr 8) and 0xFF) * 587 +
                                (p and 0xFF) * 114) / 1000
                            cnt++
                        }
                        return sum.toDouble() / cnt
                    }
                    val dAi = maxDiff(pxBase, pxAi)
                    val dLook = maxDiff(pxBase, pxLook)
                    val lumaBase = meanLuma(pxBase)
                    // Mid-gray source: a healthy render sits near 128 luma;
                    // pre-fix Mali blackened the frame (<10). Enabled-but-
                    // unuploaded must be (near-)identical to grade-off since
                    // the strength gate forces 0; allow 2 for driver rounding.
                    val ok = lumaBase > 100.0 && dAi <= 2 && dLook <= 2
                    ok to "base luma=${lumaBase.toInt()} (need >100) " +
                        "ai-vs-base maxDiff=$dAi (need <=2) " +
                        "look-vs-base maxDiff=$dLook (need <=2)"
                } finally {
                    if (savedAi != null) uploadAiLut(savedAi.first, savedAi.second)
                    else uploadAiLut(null, 0)
                    if (savedLook != null) uploadLookLut(savedLook.first, savedLook.second)
                    else uploadLookLut(null, 0)
                }
            }

            // ---- check 49: creative FX light-leak lands in multi-tile
            // export (renderExport) ----
            // Checks 8/9 prove the leak shader branch works in the preview
            // (renderOnce); check 39's "heal silently does nothing" history
            // proves preview coverage alone is not enough — the saved file
            // goes through renderExport, which re-invokes render() per
            // 2048px tile with only the FX key cached. A 2100x2100 field
            // forces 2x2 tiles, exercising that the uFxTex binding (unit 8)
            // and uFxLeak uniform survive across tiles. SUNSET at 100% is an
            // additive warm glow whose peak red must far exceed the gray
            // baseline (same peak test as check 8).
            check("Creative FX leak in multi-tile export (renderExport)") {
                val w = 2100; val h = 2100
                val field = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                field.eraseColor(Color.rgb(150, 150, 150))
                try {
                    setSource(field)
                    uploadCurves(EditState())
                    uploadMasks(emptyList())
                    uploadRetouch(null)
                    val matrix = Geometry.uvMatrix(
                        w, h, w, h, CropParams(), GeometryParams())
                    val leakOn = EditState(
                        masks = noMask,
                        optics = OpticsParams(fx = FxParams(
                            leak = LightLeakStyle.SUNSET, leakAmount = 100f)),
                    )
                    val leakOff = EditState(
                        masks = noMask,
                        optics = OpticsParams(fx = FxParams(
                            leak = LightLeakStyle.SUNSET, leakAmount = 0f)),
                    )
                    val pxOn = renderExport(leakOn, matrix) {}
                    try {
                        val pxOff = renderExport(leakOff, matrix) {}
                        try {
                            val err = GLES30.glGetError()
                            if (err != GLES30.GL_NO_ERROR) {
                                throw IllegalStateException(
                                    "GL error 0x${err.toString(16)} " +
                                        "during leak renderExport")
                            }
                            fun peakRed(b: Bitmap): Int {
                                var m = 0
                                // center 600px band covers the leak's glow
                                // core (see check 8); stepping keeps the read
                                // cheap on a 4.4MP export.
                                for (y in 750 until 1350 step 4)
                                    for (x in 750 until 1350 step 4) {
                                        val r = (b.getPixel(x, y) shr 16) and
                                            0xFF
                                        if (r > m) m = r
                                    }
                                return m
                            }
                            val peakOn = peakRed(pxOn)
                            val peakOff = peakRed(pxOff)
                            val dimsOk = pxOn.width == w && pxOn.height == h &&
                                pxOff.width == w && pxOff.height == h
                            val ok = dimsOk && peakOn >= 200 &&
                                peakOn - peakOff > 60
                            ok to "dims=${pxOn.width}x${pxOn.height} " +
                                "(need ${w}x${h}, 2x2 tiles); " +
                                "leak peakR=$peakOn (baseline $peakOff, " +
                                "need >=200, diff>60)"
                        } finally {
                            pxOff.recycle()
                        }
                    } finally {
                        pxOn.recycle()
                    }
                } finally {
                    field.recycle()
                    uploadMasks(emptyList())
                }
            }
            // ---- check 50: AI Pro Retouch LUT in multi-tile export ----
            // Checks 32/41 proved the sampler3D path in preview (renderOnce)
            // and check 48 proved the unbound-sampler guard there, but the
            // saved file goes through renderExport (per-tile re-invocation
            // of render()) — nothing verified the AI grade actually reaches
            // exported pixels. A binding lost between tiles would surface
            // only as "AI retouch works in preview, not in saved photos".
            check("AI Pro Retouch LUT in multi-tile export (renderExport)") {
                val saved = aiLutSnapshot()
                val w = 2100; val h = 2100
                val field = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                field.eraseColor(Color.rgb(150, 150, 150))
                try {
                    setSource(field)
                    uploadCurves(EditState())
                    uploadMasks(emptyList())
                    uploadRetouch(null)
                    val matrix = Geometry.uvMatrix(
                        w, h, w, h, CropParams(), GeometryParams())
                    val n = 9 // any n works for the shader; 9^3*3 is cheap
                    fun makeLut(rOff: Float, gOff: Float, bOff: Float): FloatArray {
                        val lut = FloatArray(n * n * n * 3)
                        for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
                            val o = (((r * n) + g) * n + b) * 3
                            lut[o] = (r / (n - 1f) + rOff).coerceIn(0f, 1f)
                            lut[o + 1] = (g / (n - 1f) + gOff).coerceIn(0f, 1f)
                            lut[o + 2] = (b / (n - 1f) + bOff).coerceIn(0f, 1f)
                        }
                        return lut
                    }
                    uploadAiLut(makeLut(30f / 255f, 0f, -30f / 255f), n)
                    val on = EditState(
                        masks = noMask,
                        aiRetouch = AiRetouchParams(enabled = true, strength = 1f),
                    )
                    val off = EditState(masks = noMask)
                    val pxOn = renderExport(on, matrix) {}
                    try {
                        val pxOff = renderExport(off, matrix) {}
                        try {
                            val err = GLES30.glGetError()
                            if (err != GLES30.GL_NO_ERROR) {
                                throw IllegalStateException(
                                    "GL error 0x${err.toString(16)} " +
                                        "during AI-LUT renderExport")
                            }
                            fun meanRed(b: Bitmap): Double {
                                var sum = 0L; var cnt = 0
                                for (y in 750 until 1350 step 4)
                                    for (x in 750 until 1350 step 4) {
                                        sum += (b.getPixel(x, y) shr 16) and 0xFF
                                        cnt++
                                    }
                                return sum.toDouble() / cnt
                            }
                            val redOn = meanRed(pxOn)
                            val redOff = meanRed(pxOff)
                            val dimsOk = pxOn.width == w && pxOn.height == h &&
                                pxOff.width == w && pxOff.height == h
                            // Warm LUT adds +30/255 red ≈ +30 in 8-bit; allow
                            // slack for quantization and tile seams.
                            val ok = dimsOk && redOn - redOff > 15.0
                            ok to "dims=${pxOn.width}x${pxOn.height} " +
                                "(need ${w}x${h}, 2x2 tiles); " +
                                "export meanR=${redOn.toInt()} (baseline " +
                                "${redOff.toInt()}, need diff>15)"
                        } finally {
                            pxOff.recycle()
                        }
                    } finally {
                        pxOn.recycle()
                    }
                } finally {
                    field.recycle()
                    uploadMasks(emptyList())
                    if (saved != null) uploadAiLut(saved.first, saved.second)
                    else uploadAiLut(null, 0)
                }
            }
            // ---- check 51: black-frame watchdog policy gates ----
            // The P0 watchdog (EditorViewModel.checkPreviewWatchdog) is the
            // safety net for the vivo V60 black-preview failure, but its trip
            // logic was an untested private method — a threshold typo or a
            // widened gate could silently disable it. WatchdogPolicy holds the
            // pure predicates; this check pins the thresholds, the streak
            // rule, and every false-positive gate.
            check("Black-frame watchdog policy gates (WatchdogPolicy)") {
                val fails = mutableListOf<String>()
                fun expect(cond: Boolean, what: String) {
                    if (!cond) fails += what
                }
                // False-positive gates: overlay/mask-check views repaint the
                // frame by design; exposure <= -3 EV is a deliberate blackout.
                expect(!WatchdogPolicy.suppressed(-1, 0, 0f), "normal frame must not be suppressed")
                expect(WatchdogPolicy.suppressed(0, 0, 0f), "mask overlay must suppress")
                expect(WatchdogPolicy.suppressed(-1, 1, 0f), "mask B&W view must suppress")
                expect(WatchdogPolicy.suppressed(-1, 0, -3f), "exposure -3 EV must suppress")
                expect(!WatchdogPolicy.suppressed(-1, 0, -2.99f), "exposure -2.99 EV must not suppress")
                // Black-frame predicate at the documented 0.012 trip line.
                expect(WatchdogPolicy.isBlackFrame(0.011f), "0.011 is black")
                expect(!WatchdogPolicy.isBlackFrame(0.012f), "0.012 is not black")
                expect(!WatchdogPolicy.isBlackFrame(0.5f), "0.5 is not black")
                // Dark sources legitimately render near-black: at/below 0.15.
                expect(WatchdogPolicy.isDarkSource(0.15f), "0.15 source is dark")
                expect(!WatchdogPolicy.isDarkSource(0.151f), "0.151 source is not dark")
                // Streak rule: trip on the 4th consecutive black frame.
                expect(!WatchdogPolicy.shouldTrip(3), "streak 3 must not trip")
                expect(WatchdogPolicy.shouldTrip(4), "streak 4 must trip")
                // End-to-end policy walk: 3 black frames, a bright one
                // (streak reset), then 4 black -> trip on the 8th read.
                var streak = 0
                val frames = floatArrayOf(0.005f, 0.004f, 0.006f, 0.4f, 0.005f, 0.004f, 0.006f, 0.005f)
                var tripAt = -1
                for (k in frames.indices) {
                    if (WatchdogPolicy.suppressed(-1, 0, 0f)) streak = 0
                    else if (WatchdogPolicy.isDarkSource(0.4f)) streak = 0
                    else if (WatchdogPolicy.isBlackFrame(frames[k])) streak++
                    else streak = 0
                    if (WatchdogPolicy.shouldTrip(streak)) { tripAt = k; break }
                }
                expect(tripAt == 7, "trip expected at read 7 (after reset), got $tripAt")
                // Diagnostic bundle shape matches the P0 fix's log line.
                val d = WatchdogPolicy.diagnostic(0.005f, 0.4f, 0.5f, true, 0.75f, -1, 0)
                expect(d.contains("black frame 0% over 4 reads"), "diag black-frame part: $d")
                expect(d.contains("source 40%"), "diag source part: $d")
                expect(d.contains("aiGrade=true@0.75"), "diag AI part: $d")
                // Null-preview blind-spot gate (P0 recurrence, 2026-10-18):
                // a photo-open editor with no software preview bitmap and
                // black frames must count as suspicious — loading and error
                // states stay silent (the spinner/banner explains them).
                expect(WatchdogPolicy.nullPreviewSuspicious(true, false, false),
                    "open photo, no bitmap, black frames must be suspicious")
                expect(!WatchdogPolicy.nullPreviewSuspicious(false, false, false),
                    "no open photo must not be suspicious")
                expect(!WatchdogPolicy.nullPreviewSuspicious(true, true, false),
                    "loading state must not be suspicious")
                expect(!WatchdogPolicy.nullPreviewSuspicious(true, false, true),
                    "error state must not be suspicious")
                val nd = WatchdogPolicy.nullPreviewDiagnostic(0.005f)
                expect(nd.contains("no software preview bitmap"), "null-preview diag: $nd")
                expect(nd.contains("over 4 reads"), "null-preview diag trip line: $nd")
                // The null-preview streak shares the black-frame trip line.
                expect(!WatchdogPolicy.shouldTrip(3), "null-preview streak 3 must not trip")
                expect(WatchdogPolicy.shouldTrip(4), "null-preview streak 4 must trip")
                (fails.isEmpty()) to (if (fails.isEmpty()) "all watchdog gates hold"
                else "FAIL: ${fails.joinToString("; ")}")
            }
            // ---- check 51b: dead-readback scopes policy ----
            // Mali glReadPixels quirk (2026-10-18): the readback feeding the
            // scopes can fail while the preview renders fine, freezing the
            // scopes at stale data (video bug #2: flat mini-histogram). The
            // ViewModel declares the scopes dead after
            // SCOPES_DEAD_AFTER_FAILURES consecutive failures and the UI
            // shows an honest placeholder. Pin the threshold: it must be
            // positive (a zero/negative would kill the scopes instantly) and
            // small enough to actually fire within a session.
            check("Dead-readback scopes policy (WatchdogPolicy)") {
                val n = WatchdogPolicy.SCOPES_DEAD_AFTER_FAILURES
                (n in 2..60) to
                    "SCOPES_DEAD_AFTER_FAILURES=$n must be in 2..60"
            }
            // ---- check 52: NDK JNI bridge smoke ----
            // Phase 0 of the approved RAW program (pro-premium-program.md):
            // proves the native .so built by externalNativeBuild/CMake is
            // packaged in the APK and loadable on-device, and that it was
            // compiled for this device's ABI (arm64-v8a for the vivo V60).
            // NativeLib.describe() never throws, so a broken native layer
            // surfaces as a failed check, not a crashed self-test.
            check("NDK JNI bridge smoke (NativeLib)") {
                val info = NativeLib.describe()
                val ok = NativeLib.isAvailable &&
                    info.contains("H2W native bridge OK") &&
                    info.contains("ABI=") && !info.contains("ABI=unknown")
                ok to if (ok) "bridge round-trip OK: $info"
                else "FAIL: $info"
            }
            // ---- check 54: LibRaw linked (RAW program Phase 1, slice 1) ----
            // Phase 1 vendors LibRaw 0.22.2 as libraw.so (pinned upstream
            // tarball, built from source by CMake — see
            // app/src/main/cpp/CMakeLists.txt). NativeLib.librawVersion()
            // returns the REAL libraw_version() string, so this check pins
            // that LibRaw actually compiled, linked, packaged, and loads
            // on-device. A missing/renamed libraw.so fails loudly here
            // instead of surfacing later as a mysterious RAW-decode failure.
            // NativeLib.describeRaw() never throws, so a broken native layer
            // surfaces as a failed check, not a crashed self-test.
            check("LibRaw linked (RAW Phase 1 slice 1)") {
                val info = NativeLib.describeRaw()
                val ok = NativeLib.isAvailable &&
                    info.startsWith("LibRaw 0.22.2")
                ok to if (ok) "libraw link+load OK: $info"
                else "FAIL: $info"
            }
            // ---- check 55: DNG decode bridge contract (RAW Phase 1, slice 2) ----
            // Pins the JNI decode bridge without needing a real DNG on the
            // phone: dngProbe/dngDecode must reject a missing path and a
            // garbage file with structured errors — never a crash, never a
            // JNI throw (check() would catch it and fail loudly). The
            // positive path (real DNG -> pixels) is proven by opening a .dng
            // in the editor; that needs his vivo V60 and a test.dng, which
            // is exactly what check 54's note asks him to try.
            check("DNG decode bridge rejects bad input (RAW Phase 1 slice 2)") {
                val missing = NativeLib.dngProbe("/no/such/file.dng")
                val garbage = File.createTempFile("h2w_probe", ".dng")
                    .also { it.writeBytes(ByteArray(256) { 0x41 }) }
                val garbageProbe: String
                val garbageDecode: ByteBuffer?
                try {
                    garbageProbe = NativeLib.dngProbe(garbage.absolutePath)
                    garbageDecode = NativeLib.dngDecode(garbage.absolutePath)
                    NativeLib.dngFreeBuffer(garbageDecode) // null-safe
                    // Foreign-buffer hardening: dngFreeBuffer must be a safe
                    // no-op for a direct buffer that did NOT come from
                    // dngDecode — including one too small to hold the 8-byte
                    // header (the native side must not read past the buffer's
                    // capacity). A native over-read would crash the whole
                    // process rather than fail a check, so the call itself is
                    // the test: it must return, not crash.
                    NativeLib.dngFreeBuffer(java.nio.ByteBuffer.allocateDirect(4))
                } finally {
                    garbage.delete()
                }
                val nullDecode = NativeLib.dngDecode("/no/such/file.dng")
                val ok = NativeLib.isAvailable &&
                    missing.startsWith("ERR") &&
                    garbageProbe.startsWith("ERR") &&
                    garbageDecode == null &&
                    nullDecode == null &&
                    RawDecoder.parseProbe("OK 4080 3064") == (4080 to 3064) &&
                    RawDecoder.parseProbe(missing) == null &&
                    RawDecoder.isDng("shot.DNG") &&
                    !RawDecoder.isDng("photo.jpg")
                ok to if (ok)
                    "bad-path probe: '$missing'; garbage probe: '$garbageProbe'; " +
                    "decodes null; parseProbe/isDng sane"
                else "FAIL: missing='$missing' garbage='$garbageProbe' " +
                    "garbageDecodeNull=${garbageDecode == null} " +
                    "nullDecodeNull=${nullDecode == null}"
            }
            // ---- check 56: embedded-preview bridge contract (RAW Phase 1, slice 3) ----
            // Pins the lossy-JPEG DNG fallback without needing a real DNG on
            // the phone: dngPreview must reject a missing path and a garbage
            // file with null — never a crash, never a JNI throw (check()
            // would catch it and fail loudly). The positive path (a real
            // lossy-JPEG DNG -> its embedded preview JPEG) needs his vivo
            // V60 and such a file; check 55's note already asks him for a
            // test.dng, and this check's note extends it to lossy-JPEG ones.
            check("Embedded-preview bridge rejects bad input (RAW Phase 1 slice 3)") {
                val garbage = File.createTempFile("h2w_prev", ".dng")
                    .also { it.writeBytes(ByteArray(256) { 0x41 }) }
                val garbagePreview: ByteArray?
                try {
                    garbagePreview = NativeLib.dngPreview(garbage.absolutePath)
                } finally {
                    garbage.delete()
                }
                val nullPreview = NativeLib.dngPreview("/no/such/file.dng")
                // decodeFile must leave usedEmbeddedPreview false after a
                // failed decode, regardless of any earlier successful
                // embedded-preview decode in this process.
                RawDecoder.decodeFile("/no/such/file.dng")
                val flagReset = !RawDecoder.usedEmbeddedPreview
                val ok = NativeLib.isAvailable &&
                    garbagePreview == null &&
                    nullPreview == null &&
                    RawDecoder.isLossyJpegProbe("ERR LOSSYJPEG unpack: lossy-JPEG compressed DNG") &&
                    !RawDecoder.isLossyJpegProbe("ERR open: no such file or directory") &&
                    !RawDecoder.isLossyJpegProbe("OK 4080 3064") &&
                    RawDecoder.parseProbe("ERR LOSSYJPEG unpack: x") == null &&
                    flagReset
                ok to if (ok)
                    "bad-path/garbage previews null; LOSSYJPEG probe tag parsed; " +
                    "flag resets false after failed decode"
                else "FAIL: garbagePreviewNull=${garbagePreview == null} " +
                    "nullPreviewNull=${nullPreview == null} flagReset=$flagReset"
            }
            // ---- check 57: RAW extension recognition (RAW Phase 2 slice 1) ----
            // Pure-Kotlin gate pins that the proprietary formats the native
            // LibRaw bridge sniffs are routed into the RAW decode path on
            // the phone: CR2/CR3, NEF, ARW, RAF, ORF, RW2, PEF, RWL, X3F,
            // 3FR, IIQ and friends. Negative cases (jpg, png, no extension,
            // null, double-dot trick) must NOT route — a renamed JPEG must
            // never hit the LibRaw path. Also pins rawFormatName for the UI
            // caption and that isDng still agrees with isRaw on .dng.
            check("RAW extension routing (RAW Phase 2 slice 1)") {
                val positives = listOf(
                    "IMG_0001.CR2", "shot.cr3", "DSC_1234.NEF", "photo.nrw",
                    "frame.ARW", "p.SR2", "q.srf", "Fuji.RAF", "P123.ORF",
                    "PANA.rw2", "LEI.RWL", "SDIM.PEF", "hassel.3fr",
                    "KODAK.dcr", "old.KDC", "mam.MEF", "leaf.MOS",
                    "sig.X3F", "phase.IIQ", "raw.DNG", "raw.dng",
                ).all { RawDecoder.isRaw(it) }
                val negatives = listOf(
                    "photo.jpg", "pic.PNG", "img.webp", "noext",
                    null, "IMG_0001.CR2.jpg", "cr2",
                ).none { RawDecoder.isRaw(it) }
                val formats = RawDecoder.rawFormatName("IMG_0001.CR2") == "CR2" &&
                    RawDecoder.rawFormatName("DSC.NEF") == "NEF" &&
                    RawDecoder.rawFormatName("a.dng") == "DNG" &&
                    RawDecoder.rawFormatName("x.jpg") == null &&
                    RawDecoder.rawFormatName(null) == null
                val dngAgree = RawDecoder.isDng("a.DNG") == RawDecoder.isRaw("a.DNG")
                val setSane = RawDecoder.rawExtensions.size >= 15 &&
                    "dng" in RawDecoder.rawExtensions &&
                    "jpg" !in RawDecoder.rawExtensions
                val ok = positives && negatives && formats && dngAgree && setSane
                ok to if (ok)
                    "21 RAW extensions route to LibRaw path; 8 negatives rejected; " +
                    "format labels correct; isDng/isRaw agree"
                else "FAIL: positives=$positives negatives=$negatives " +
                    "formats=$formats dngAgree=$dngAgree setSane=$setSane"
            }
            // ---- check 58: RAW thumbnail backfill selection (RAW Phase 2 slice 3) ----
            // Pure-Kotlin pin for the one-shot backfill that gives grid
            // thumbnails to RAW rows imported before slice 2's import-time
            // cache shipped. Only RAW rows missing a cached thumb are
            // selected, in library order, capped at maxItems; non-RAW rows,
            // already-cached rows, blank names, and maxItems<=0 never select.
            check("RAW backfill selection (RAW Phase 2 slice 3)") {
                fun row(id: String, name: String) = LibraryImage(
                    id = id, contentUri = "content://x/$id", displayName = name,
                    dateAdded = 0, width = 0, height = 0, folder = "Imports",
                )
                val rows = listOf(
                    row("a", "IMG_1.CR2"), row("b", "pic.jpg"),
                    row("c", "DSC.NEF"), row("d", ""),
                    row("e", "RAW.DNG"), row("f", "shot.ARW"),
                )
                val cached = setOf("c")
                val ids = rawRowsMissingThumbs(rows, 10) { cached.contains(it) }
                    .map { it.id }
                val capped = rawRowsMissingThumbs(rows, 2) { false }.map { it.id }
                val zero = rawRowsMissingThumbs(rows, 0) { false }
                val ok = ids == listOf("a", "e", "f") &&
                    capped == listOf("a", "c") &&
                    zero.isEmpty()
                ok to if (ok)
                    "selects only uncached RAW rows in order; cap honored"
                else "FAIL: ids=$ids capped=$capped zero=${zero.size}"
            }
            // ---- check 59: details-dialog format label (RAW Phase 2 slice 5) ----
            // Pure-Kotlin pin for formatLabelFor(): RAW names must resolve to
            // the same uppercase label the editor caption shows (rawFormatName
            // agreement), common raster formats map to their familiar names,
            // and unknowns (no/double extension, null, oddballs) resolve to
            // null so the Format row simply doesn't render instead of lying.
            check("Details format label (RAW Phase 2 slice 5)") {
                val raw = formatLabelFor("IMG_0001.CR2") == "CR2" &&
                    formatLabelFor("DSC_1234.NEF") == "NEF" &&
                    formatLabelFor("a.dng") == "DNG" &&
                    formatLabelFor("shot.ARW") == "ARW" &&
                    formatLabelFor("Fuji.RAF") == "RAF"
                val raster = formatLabelFor("pic.jpg") == "JPEG" &&
                    formatLabelFor("P.PNG") == "PNG" &&
                    formatLabelFor("v60.HEIC") == "HEIC" &&
                    formatLabelFor("shot.heif") == "HEIC" &&
                    formatLabelFor("a.webp") == "WEBP" &&
                    formatLabelFor("x.tif") == "TIFF" &&
                    formatLabelFor("y.TIFF") == "TIFF" &&
                    formatLabelFor("z.avif") == "AVIF" &&
                    formatLabelFor("a.gif") == "GIF" &&
                    formatLabelFor("b.bmp") == "BMP"
                val negatives = formatLabelFor(null) == null &&
                    formatLabelFor("noext") == null &&
                    formatLabelFor("cr2") == null &&
                    formatLabelFor("IMG_0001.CR2.jpg") == "JPEG" &&
                    formatLabelFor("mystery.xyz") == null
                val ok = raw && raster && negatives
                ok to if (ok)
                    "RAW/caption agreement + 10 raster formats; unknowns render nothing"
                else "FAIL: raw=$raw raster=$raster negatives=$negatives"
            }
            // ---- check 60: rawExif wire parser (RAW Phase 2 slice 6) ----
            // Pure-Kotlin pin for parseRawExif(): a well-formed native line
            // parses all seven fields; empty numerics degrade to the
            // "unknown" sentinels (never rendered); anything that isn't an
            // OK line (ERR, null/empty, wrong field count) is null. The JNI
            // side itself is pinned on-device by the details dialog on a
            // real proprietary RAW — ask him to open a CR2/NEF's Details
            // and confirm the Camera row fills in.
            check("RAW EXIF parser (RAW Phase 2 slice 6)") {
                val good = parseRawExif("OK\tCanon\tCanon EOS R5\t100\t0.008\t2.8\t24\t1760000000")
                val g = good != null && good.make == "Canon" &&
                    good.model == "Canon EOS R5" && good.iso == 100.0 &&
                    good.shutterSeconds == 0.008 && good.aperture == 2.8 &&
                    good.focalMm == 24.0 && good.epochSeconds == 1760000000L
                val partial = parseRawExif("OK\tNIKON CORPORATION\t\t\t\t\t\t")
                val p = partial != null && partial.make == "NIKON CORPORATION" &&
                    partial.model.isEmpty() && partial.iso <= 0 &&
                    partial.shutterSeconds <= 0 && partial.aperture <= 0 &&
                    partial.focalMm <= 0 && partial.epochSeconds <= 0
                val junk = parseRawExif("OK\ta\tb\tNaN\tx\ty\tz\tw")
                val j = junk != null && junk.make == "a" && junk.iso <= 0 &&
                    junk.epochSeconds <= 0
                val negatives = parseRawExif(null) == null &&
                    parseRawExif("") == null &&
                    parseRawExif("ERR open: foo") == null &&
                    parseRawExif("OK\tshort") == null &&
                    parseRawExif("ok\tCanon\tM\t1\t1\t1\t1\t1") == null
                val ok = g && p && j && negatives
                ok to if (ok)
                    "7-field parse + empty-field sentinels + junk numerics; 5 negatives null"
                else "FAIL: g=$g partial=$p junk=$j negatives=$negatives"
            }
            // ---- check 61: export file naming (single-export source stem) ----
            // Pure-Kotlin pin for ExportSettings.galleryFileName(sourceStem):
            // a typed name always wins; a blank name inherits the source
            // photo's stem with the extension stripped (so "IMG_2041.jpg"
            // exports as "IMG_2041.jpg", not "Lumora_<ts>.jpg"); a
            // null/blank/unusable stem degrades to the old timestamp name;
            // the zero-arg call keeps the old behavior exactly.
            check("Export file naming (source-stem fallback)") {
                val blank = ExportSettings()
                val typed = ExportSettings(fileName = "  My Sunset  ")
                val stemOk = blank.galleryFileName("IMG_2041.jpg") == "IMG_2041.jpg"
                val typedOk = typed.galleryFileName("IMG_2041.jpg") == "My Sunset.jpg"
                val rawOk = blank.galleryFileName("_DSF1234.RAF") == "_DSF1234.jpg"
                val extlessOk = blank.galleryFileName("photo") == "photo.jpg"
                val sanitOk = blank.galleryFileName("a/b:c?.dng") == "abc.jpg"
                val nullStemOk = blank.galleryFileName(null)
                    .matches(Regex("Lumora_\\d+\\.jpg"))
                val blankStemOk = blank.galleryFileName("   ")
                    .matches(Regex("Lumora_\\d+\\.jpg"))
                val dotfileOk = blank.galleryFileName(".jpg") == "jpg.jpg"
                val legacyOk = blank.galleryFileName()
                    .matches(Regex("Lumora_\\d+\\.jpg"))
                val pngOk = ExportSettings(format = ExportFormat.PNG)
                    .galleryFileName("pic.PNG") == "pic.png"
                val ok = stemOk && typedOk && rawOk && extlessOk && sanitOk &&
                    nullStemOk && blankStemOk && dotfileOk && legacyOk && pngOk
                ok to if (ok)
                    "typed wins; stem inherits + extension stripped; 5 fallback cases timestamp; zero-arg legacy"
                else "FAIL: stem=$stemOk typed=$typedOk raw=$rawOk extless=$extlessOk sanit=$sanitOk " +
                    "null=$nullStemOk blank=$blankStemOk dot=$dotfileOk legacy=$legacyOk png=$pngOk"
            }
            // Checks 41/48 proved the sampler3D path and the unbound-sampler
            // guard in preview, and check 50 proved the AI LUT (unit 9)
            // reaches exported pixels — but the LOOK LUT rides its own
            // sampler (uLookLut, texture unit 10, gated by lookLutReady) and
            // nothing verified IT reaches the saved file. A binding lost
            // between tiles would surface only as "look transfer works in
            // preview, not in saved photos".
            check("Look-transfer LUT in multi-tile export (renderExport)") {
                val saved = lookLutSnapshot()
                val w = 2100; val h = 2100
                val field = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                field.eraseColor(Color.rgb(150, 150, 150))
                try {
                    setSource(field)
                    uploadCurves(EditState())
                    uploadMasks(emptyList())
                    uploadRetouch(null)
                    val matrix = Geometry.uvMatrix(
                        w, h, w, h, CropParams(), GeometryParams())
                    val n = 9 // any n works for the shader; 9^3*3 is cheap
                    fun makeLut(rOff: Float, gOff: Float, bOff: Float): FloatArray {
                        val lut = FloatArray(n * n * n * 3)
                        for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
                            val o = (((r * n) + g) * n + b) * 3
                            lut[o] = (r / (n - 1f) + rOff).coerceIn(0f, 1f)
                            lut[o + 1] = (g / (n - 1f) + gOff).coerceIn(0f, 1f)
                            lut[o + 2] = (b / (n - 1f) + bOff).coerceIn(0f, 1f)
                        }
                        return lut
                    }
                    uploadLookLut(makeLut(-30f / 255f, 0f, 30f / 255f), n)
                    // isActive() needs 6 src + 6 ref stats; the values don't
                    // matter here because the LUT is uploaded directly.
                    val stats = listOf(0.5f, 0f, 0f, 1f, 1f, 1f)
                    val on = EditState(
                        masks = noMask,
                        lookTransfer = LookTransferParams(
                            srcStats = stats, refStats = stats,
                            refName = "self-test", strength = 1f),
                    )
                    val off = EditState(masks = noMask)
                    val pxOn = renderExport(on, matrix) {}
                    try {
                        val pxOff = renderExport(off, matrix) {}
                        try {
                            val err = GLES30.glGetError()
                            if (err != GLES30.GL_NO_ERROR) {
                                throw IllegalStateException(
                                    "GL error 0x${err.toString(16)} " +
                                        "during look-LUT renderExport")
                            }
                            fun meanBlue(b: Bitmap): Double {
                                var sum = 0L; var cnt = 0
                                for (y in 750 until 1350 step 4)
                                    for (x in 750 until 1350 step 4) {
                                        sum += b.getPixel(x, y) and 0xFF
                                        cnt++
                                    }
                                return sum.toDouble() / cnt
                            }
                            val blueOn = meanBlue(pxOn)
                            val blueOff = meanBlue(pxOff)
                            val dimsOk = pxOn.width == w && pxOn.height == h &&
                                pxOff.width == w && pxOff.height == h
                            // Cool LUT adds +30/255 blue ≈ +30 in 8-bit; allow
                            // slack for quantization and tile seams.
                            val ok = dimsOk && blueOn - blueOff > 15.0
                            ok to "dims=${pxOn.width}x${pxOn.height} " +
                                "(need ${w}x${h}, 2x2 tiles); " +
                                "export meanB=${blueOn.toInt()} (baseline " +
                                "${blueOff.toInt()}, need diff>15)"
                        } finally {
                            pxOff.recycle()
                        }
                    } finally {
                        pxOn.recycle()
                    }
                } finally {
                    field.recycle()
                    uploadMasks(emptyList())
                    if (saved != null) uploadLookLut(saved.first, saved.second)
                    else uploadLookLut(null, 0)
                }
            }
            // The slider percentage readout (user preference 2026-10-17)
            // drives every adjustment slider's value pill via
            // LumoraSlider; if this math regresses, every readout lies.
            // Pin the contract: share of the larger side of the range
            // away from the default, signed, rounded, "0%" when centered
            // or the range is degenerate.
            check("Slider percentage readout (formatPercent)") {
                val cases = listOf(
                    Triple(1.75f, -5f..5f, 0f) to "+35%",   // Exposure +1.75 EV
                    Triple(0f, -5f..5f, 0f) to "0%",        // centered
                    Triple(-2.5f, -5f..5f, 0f) to "-50%",
                    Triple(100f, 0f..100f, 0f) to "+100%",
                    Triple(2f, 0f..2f, 1f) to "+100%",     // saturation, default 1
                    Triple(1f, 0f..2f, 1f) to "0%",
                    Triple(0f, 0f..2f, 1f) to "-100%",
                    Triple(50f, 0f..100f, 50f) to "0%",    // feather, default 50
                    Triple(5f, 5f..5f, 5f) to "0%",        // degenerate range
                    Triple(1f, -3f..3f, 0f) to "+33%",     // rounding 33.3 -> 33
                )
                val bad = cases.filter { (input, want) ->
                    val (v, r, d) = input
                    formatPercent(v, r, d) != want
                }
                val ok = bad.isEmpty()
                ok to if (ok) "${cases.size} cases incl. non-zero defaults + degenerate range"
                else "FAIL: ${bad.take(3).joinToString { (input, want) ->
                    val (v, r, d) = input
                    "formatPercent($v, $r, $d)=${formatPercent(v, r, d)} want $want"
                }}"
            }
            // Premium slider Part 2 (2026-10-20): gradient keying is
            // identity-based and TERMINAL. A historyLabel hit never falls
            // through to the label table, and "Luminance"/"Hue" pin to
            // Neutral by design — not by accident of a missing table entry.
            check("Slider gradient keying (premium Part 2)") {
                val t1 = resolveTrackGradient(null, "Luminance NR", "Luminance", 0.5f) ==
                    GradientDecision.Neutral
                val t2 = resolveTrackGradient(null, "Luminance", "Luminance", 0.5f) ==
                    GradientDecision.Neutral
                val t3 = resolveTrackGradient(null, "Hue", "Hue", 0.5f) ==
                    GradientDecision.Neutral
                val t4 = resolveTrackGradient(null, null, "Temperature", 0.5f) is
                    GradientDecision.Stops
                // Near-miss label: exact-match lookup, never substring.
                val t5 = resolveTrackGradient(null, null, "Shadows tint", 0.5f) ==
                    GradientDecision.Neutral
                val t6 = resolveTrackGradient(null, "Temperature", "Temperature", 0.5f) is
                    GradientDecision.Stops
                // Explicit Tier-1 param wins over everything.
                val t7 = resolveTrackGradient(
                    GradientDecision.Neutral, "Temperature", "Temperature", 0.5f,
                ) == GradientDecision.Neutral
                // Key hit is terminal: must NOT fall through to the label
                // table even when the label would resolve.
                val t8 = resolveTrackGradient(null, "Luminance", "Temperature", 0.5f) ==
                    GradientDecision.Neutral
                val ok = t1 && t2 && t3 && t4 && t5 && t6 && t7 && t8
                ok to if (ok) "key/label/explicit precedence incl. terminal-key + Shadows-tint near-miss"
                else "FAIL: t1=$t1 t2=$t2 t3=$t3 t4=$t4 t5=$t5 t6=$t6 t7=$t7 t8=$t8"
            }
            // Stop-list construction invariants: every semantic ramp has
            // >= 2 stops, first at 0f, last at 1f, sorted; sampling hits
            // exact ends/mid on a centered 3-stop ramp, stays flat on a
            // single-color decision, and degrades gracefully on degenerate
            // input.
            check("Slider gradient construction (premium Part 2)") {
                val ramps = sliderSemanticRampsForTest()
                val shape = ramps.isNotEmpty() && ramps.all { s ->
                    val st = s.stops
                    st.size >= 2 && st.first().first == 0f && st.last().first == 1f &&
                        st.zipWithNext().all { (a, b) -> a.first <= b.first }
                }
                val ramp = listOf(0f to UiColor.Red, 0.5f to UiColor.Green, 1f to UiColor.Blue)
                val ends = sampleTrackGradient(ramp, 0f) == UiColor.Red &&
                    sampleTrackGradient(ramp, 1f) == UiColor.Blue
                val mid = sampleTrackGradient(ramp, 0.5f) == UiColor.Green
                val trueMid = sampleTrackGradient(
                    listOf(0f to UiColor.Black, 1f to UiColor.White), 0.5f,
                ) == UiColor(0.5f, 0.5f, 0.5f)
                val flat = listOf(0f to UiColor.Yellow, 1f to UiColor.Yellow)
                val flatOk = (0..10).all { i -> sampleTrackGradient(flat, i / 10f) == UiColor.Yellow }
                val degenerate = sampleTrackGradient(listOf(0f to UiColor.Cyan), 0.3f) == UiColor.Cyan
                val ok = shape && ends && mid && trueMid && flatOk && degenerate
                ok to if (ok) "${ramps.size} semantic ramps shaped; sampling end/mid/end + true midpoint + flat + degenerate"
                else "FAIL: shape=$shape ends=$ends mid=$mid trueMid=$trueMid flat=$flatOk degenerate=$degenerate"
            }
            // Active-segment anchoring + default-tick predicate: ONLY
            // crossing-zero ranges anchor at the default; unipolar ranges
            // (edge or interior default) anchor at 0. The tick shows for
            // interior defaults, hides at edges, and is suppressed wherever
            // it would coincide with the zero marker.
            check("Slider anchor + default tick (premium Part 2)") {
                val a1 = sliderAnchorFrac(-100f..100f, 0f) == 0.5f
                val a2 = sliderAnchorFrac(0f..100f, 100f) == 0f
                val a3 = sliderAnchorFrac(0f..100f, 50f) == 0f
                val a4 = sliderAnchorFrac(-5f..5f, 0f) == 0.5f
                val k1 = sliderDefaultTickVisible(0.5f, crossing = false, zeroFrac = 0f)
                val k2 = !sliderDefaultTickVisible(1f, crossing = false, zeroFrac = 0f)
                val k3 = !sliderDefaultTickVisible(0f, crossing = false, zeroFrac = 0f)
                val k4 = !sliderDefaultTickVisible(0.5f, crossing = true, zeroFrac = 0.5f)
                val k5 = sliderDefaultTickVisible(0.25f, crossing = true, zeroFrac = 0.5f)
                val ok = a1 && a2 && a3 && a4 && k1 && k2 && k3 && k4 && k5
                ok to if (ok) "crossing-only anchoring; tick interior/edge/zero-coincide"
                else "FAIL: a1=$a1 a2=$a2 a3=$a3 a4=$a4 k1=$k1 k2=$k2 k3=$k3 k4=$k4 k5=$k5"
            }
            // ---- check 62: keyword normalization (Phase C#6 slice 1) ----
            // Pure-Kotlin pin for normalizeKeyword()/keywordKeyOf(): display
            // form trims + collapses whitespace but keeps the user's casing;
            // blanks and >48-char input are rejected (null); matching is
            // case-insensitive via the canonical key, so "Sunset"/"SUNSET"/
            // "sunset" are one keyword.
            check("Keyword normalization (Phase C#6 slice 1)") {
                val display = normalizeKeyword("  Sunset  ") == "Sunset" &&
                    normalizeKeyword("a   b\tc") == "a b c" &&
                    normalizeKeyword("မိသားစု") == "မိသားစု"
                val reject = normalizeKeyword("") == null &&
                    normalizeKeyword("   ") == null &&
                    normalizeKeyword("x".repeat(48))?.length == 48 &&
                    normalizeKeyword("x".repeat(49)) == null
                val keys = keywordKeyOf("Sunset") == "sunset" &&
                    keywordKeyOf("SUNSET") == keywordKeyOf("sunset") &&
                    keywordKeyOf("a b") == "a b"
                val ok = display && reject && keys
                ok to if (ok)
                    "trim/collapse/keep-case + blank/overlong reject + case-insensitive keys"
                else "FAIL: display=$display reject=$reject keys=$keys"
            }

            // ---- check 63: AI training-data license policy (PPR10K verdict) ----
            // PPR10K verified 2026-10-18 (official repo README "Agreement"):
            // non-commercial research only, derived data included — a trained
            // model is derived data, so it can never ship in a commercial
            // app. FiveK stays research-only. The pin fails loudly if a
            // future worker ever "unblocks" either dataset.
            check("AI training-data license policy (PPR10K verdict)") {
                val ppr = AiTrainingPolicy.isBlocked("PPR10K") &&
                    AiTrainingPolicy.isBlocked("ppr10k") &&
                    AiTrainingPolicy.blockedEntry("PPR10K")
                        ?.reason.orEmpty().contains("derived data")
                val fivek = AiTrainingPolicy.isBlocked("FiveK") &&
                    AiTrainingPolicy.isBlocked("MIT-Adobe FiveK") &&
                    AiTrainingPolicy.isBlocked("mit adobe fivek")
                val open = !AiTrainingPolicy.isBlocked("user's own photo pairs") &&
                    !AiTrainingPolicy.isBlocked("") &&
                    AiTrainingPolicy.blockedEntry("COCO") == null
                val list = AiTrainingPolicy.BLOCKED.map { it.name }.toSet() ==
                    setOf("PPR10K", "MIT-Adobe FiveK")
                val ok = ppr && fivek && open && list
                ok to if (ok)
                    "PPR10K + FiveK blocked (aliases too), clean sources stay open"
                else "FAIL: ppr=$ppr fivek=$fivek open=$open list=$list"
            }

            // ---- check 64: smart collections (Phase C#6 slice 2) ----
            // Pure-Kotlin pin for the smart-collection rule engine:
            // normalizeCollectionName() trims/collapses whitespace and
            // rejects blanks/overlong names; sanitizeSmartCollection()
            // drops unusable entries, clamps minRating to 0..5, and drops a
            // keyword key without its display form; smartCollectionMatches()
            // AND-composes favorites-only + min rating + keyword (every rule
            // must hold); SmartCollection.summary describes the active rules.
            check("Smart collections (Phase C#6 slice 2)") {
                val names = normalizeCollectionName("  Portfolio   picks  ") ==
                    "Portfolio picks" &&
                    normalizeCollectionName("") == null &&
                    normalizeCollectionName("   ") == null &&
                    normalizeCollectionName("x".repeat(60))?.length == 60 &&
                    normalizeCollectionName("x".repeat(61)) == null
                val sanitize = sanitizeSmartCollection(
                    SmartCollection(id = "a", name = "  A  ", minRating = 9)
                ) == SmartCollection(id = "a", name = "A", minRating = 5) &&
                    sanitizeSmartCollection(
                        SmartCollection(id = "b", name = "")
                    ) == null &&
                    sanitizeSmartCollection(
                        SmartCollection(
                            id = "c", name = "C",
                            keywordKey = "sunset", keywordDisplay = null)
                    ) == SmartCollection(id = "c", name = "C")
                val summary =
                    SmartCollection(
                        id = "s", name = "S", keywordKey = "sunset",
                        keywordDisplay = "Sunset", minRating = 3,
                        favoritesOnly = true).summary ==
                    "Sunset · 3★+ · favorites" &&
                    SmartCollection(id = "t", name = "T").summary == ""
                val img = LibraryImage(
                    id = "p1", contentUri = null, displayName = "a.jpg",
                    dateAdded = 1L, width = 10, height = 10, folder = "f",
                    rating = 4, favorite = true)
                val fav = smartCollectionMatches(
                    SmartCollection(id = "f", name = "F", favoritesOnly = true),
                    img) { _, _ -> false } &&
                    !smartCollectionMatches(
                        SmartCollection(id = "f", name = "F", favoritesOnly = true),
                        img.copy(favorite = false)) { _, _ -> true }
                val rate = smartCollectionMatches(
                    SmartCollection(id = "r", name = "R", minRating = 4),
                    img) { _, _ -> true } &&
                    !smartCollectionMatches(
                        SmartCollection(id = "r", name = "R", minRating = 5),
                        img) { _, _ -> true }
                val kw = smartCollectionMatches(
                    SmartCollection(
                        id = "k", name = "K", keywordKey = "sunset",
                        keywordDisplay = "Sunset"),
                    img) { _, key -> key == "sunset" } &&
                    !smartCollectionMatches(
                        SmartCollection(
                            id = "k", name = "K", keywordKey = "beach",
                            keywordDisplay = "Beach"),
                        img) { _, key -> key == "sunset" }
                val ok = names && sanitize && summary && fav && rate && kw
                ok to if (ok)
                    "name normalize + sanitize clamp + summary + fav/rating/keyword AND rules"
                else "FAIL: names=$names sanitize=$sanitize summary=$summary " +
                    "fav=$fav rate=$rate kw=$kw"
            }

            // ---- check 65: proofing galleries (Phase C#7 slice 1) ----
            // Pure-Kotlin pin for the gallery rule engine:
            // normalizeGalleryName()/normalizeClientName() trim/collapse and
            // reject blanks/overlong values; sanitizeProofingGallery() drops
            // unusable entries, de-dupes + caps photo ids, and drops marks
            // for non-members; ProofingGallery.stats() counts only current
            // members; summary describes picks/rejects; ProofMark.next()
            // cycles unmarked -> approved -> rejected -> unmarked.
            check("Proofing galleries (Phase C#7 slice 1)") {
                val names = normalizeGalleryName("  Wedding   Smith  ") ==
                    "Wedding Smith" &&
                    normalizeGalleryName("") == null &&
                    normalizeGalleryName("   ") == null &&
                    normalizeGalleryName("x".repeat(60))?.length == 60 &&
                    normalizeGalleryName("x".repeat(61)) == null
                val clients = normalizeClientName(null) == null &&
                    normalizeClientName("  ") == null &&
                    normalizeClientName(" Aye  Aye ") == "Aye Aye" &&
                    normalizeClientName("x".repeat(61)) == null
                val g = ProofingGallery(
                    id = "g", name = "  G  ", clientName = "  ",
                    photoIds = listOf("p1", "p1", "p2"),
                    marks = mapOf(
                        "p1" to ProofMark.APPROVED,
                        "p2" to ProofMark.REJECTED,
                        "pX" to ProofMark.APPROVED, // stale: not a member
                    ),
                )
                val s = sanitizeProofingGallery(g)
                val sanitize = s != null &&
                    s.name == "G" &&
                    s.clientName == null &&
                    s.photoIds == listOf("p1", "p2") &&
                    s.marks.keys == setOf("p1", "p2") &&
                    sanitizeProofingGallery(g.copy(name = "")) == null
                val stats = s!!.stats() == Triple(1, 1, 0) &&
                    s.copy(marks = emptyMap()).stats() == Triple(0, 0, 2) &&
                    // a mark on a removed photo never counts
                    s.copy(photoIds = listOf("p1")).stats() == Triple(1, 0, 0)
                val summary = s.summary == "2 photos · 1 picked · 1 rejected" &&
                    ProofingGallery(id = "e", name = "E").summary == "0 photos" &&
                    ProofingGallery(id = "o", name = "O",
                        photoIds = listOf("p1")).summary == "1 photo"
                val cycle = (null as ProofMark?).next() == ProofMark.APPROVED &&
                    ProofMark.APPROVED.next() == ProofMark.REJECTED &&
                    ProofMark.REJECTED.next() == null
                val ok = names && clients && sanitize && stats && summary && cycle
                ok to if (ok)
                    "name/client normalize + sanitize dedupe/cap/stale-marks + " +
                        "member-only stats + summary + mark cycle"
                else "FAIL: names=$names clients=$clients sanitize=$sanitize " +
                    "stats=$stats summary=$summary cycle=$cycle"
            }

            // ---- check 66: proofing contact sheets (Phase C#7 slice 2) ----
            // Pure-Kotlin pin for the contact-sheet layout engine:
            // contactSheetAutoColumns() picks 3/4/5 by count;
            // contactSheetColumns() honors an explicit 2..6 choice (clamped);
            // contactSheetPages() splits into 48-photo pages;
            // contactSheetLayout() fixes the sheet at 2400px wide with a
            // deterministic grid; markBadgeText()/sheetCellCaption()/
            // contactSheetFileName() format captions and file names.
            check("Proofing contact sheets (Phase C#7 slice 2)") {
                val auto = contactSheetAutoColumns(1) == 3 &&
                    contactSheetAutoColumns(9) == 3 &&
                    contactSheetAutoColumns(10) == 4 &&
                    contactSheetAutoColumns(36) == 4 &&
                    contactSheetAutoColumns(37) == 5 &&
                    contactSheetAutoColumns(2000) == 5
                val explicit = contactSheetColumns(20,
                    ContactSheetOptions(columns = 3)) == 3 &&
                    contactSheetColumns(20,
                        ContactSheetOptions(columns = 9)) == 6 &&
                    contactSheetColumns(20,
                        ContactSheetOptions(columns = 1)) == 2 &&
                    contactSheetColumns(20, ContactSheetOptions()) == 4
                val pages = contactSheetPages(1, 5) == 1 &&
                    contactSheetPages(48, 5) == 1 &&
                    contactSheetPages(49, 5) == 2 &&
                    contactSheetPages(200, 3) == 5
                // 10 photos at 4 columns: rows=3, cellW=(2400-144-108)/4=537,
                // sheetH=72+320+36+3*(537+100)-2*36... computed by formula:
                // 72+320+36+3*637+2*36+72 = 2483
                val l = contactSheetLayout(10, 4)
                val layout = l.columns == 4 && l.rows == 3 &&
                    l.sheetW == 2400 && l.cellW == 537 && l.sheetH == 2483 &&
                    contactSheetLayout(1, 3).rows == 1
                val badges = markBadgeText(ProofMark.APPROVED) == "PICKED" &&
                    markBadgeText(ProofMark.REJECTED) == "REJECTED" &&
                    markBadgeText(null) == null
                val caption = sheetCellCaption(12, "IMG_2041.jpg") ==
                    "12. IMG_2041.jpg" &&
                    sheetCellCaption(1, "") == "1. Photo"
                val names = contactSheetFileName("Wedding — Smith", 1, 1) ==
                    "Contact sheet - Wedding — Smith" &&
                    contactSheetFileName("  A  B  ", 2, 3) ==
                    "Contact sheet - A B - page 2 of 3" &&
                    contactSheetFileName("   ", 1, 1) ==
                    "Contact sheet - Gallery"
                val ok = auto && explicit && pages && layout && badges &&
                    caption && names
                ok to if (ok)
                    "auto/explicit columns + page split + layout dims + " +
                        "badges + captions + file names"
                else "FAIL: auto=$auto explicit=$explicit pages=$pages " +
                    "layout=$layout badges=$badges caption=$caption names=$names"
            }

            // ---- check 67: proofing round-trip (Phase C#7 slice 3) ----
            // Pure-Kotlin pin for the client-picks round-trip plan:
            // clientPicksPlan() resolves approved/rejected ids over current
            // members only (stale marks for removed photos are dropped,
            // unmarked members are absent); ClientPicksResult.describe()
            // renders the result line for the confirmation toast.
            check("Proofing round-trip (Phase C#7 slice 3)") {
                val g = ProofingGallery(
                    id = "g", name = "G",
                    photoIds = listOf("p1", "p2", "p3"),
                    marks = mapOf(
                        "p1" to ProofMark.APPROVED,
                        "p2" to ProofMark.REJECTED,
                        "pX" to ProofMark.APPROVED, // stale: not a member
                    ),
                )
                val (approved, rejected) = g.clientPicksPlan()
                val plan = approved == listOf("p1") &&
                    rejected == listOf("p2")
                val empty = ProofingGallery(id = "e", name = "E",
                    photoIds = listOf("p1")).clientPicksPlan() ==
                    (emptyList<String>() to emptyList<String>())
                val d1 = ClientPicksResult(8, 3, 0).describe() ==
                    "Client picks applied: 8 picked → 5★ + favorite · " +
                    "3 rejected → 1★"
                val d2 = ClientPicksResult(0, 0, 2).describe() ==
                    "Client picks applied: 2 skipped"
                val d3 = ClientPicksResult().describe() ==
                    "No client marks to apply"
                val ok = plan && empty && d1 && d2 && d3
                ok to if (ok)
                    "member-only plan (stale marks dropped, unmarked absent) + " +
                        "result describe()"
                else "FAIL: plan=$plan empty=$empty d1=$d1 d2=$d2 d3=$d3"
            }

            // ---- check 68: Auto Denoise (Phase B#3, classical++) ----
            // Pure-Kotlin pin for the noise-floor estimator's mapping and
            // guards: clean sigmas -> null, known sigmas -> expected slider
            // targets, huge sigmas -> the conservative caps, describe().
            check("Auto Denoise mapping (Phase B#3)") {
                val clean = AutoDenoise.mapToSettings(0.5f, 0.5f) == null &&
                    AutoDenoise.mapToSettings(1.1f, 1.1f) == null
                val mid = AutoDenoise.mapToSettings(10f, 8f)
                val midOk = mid != null &&
                    mid.luminanceNr == 44f && mid.colorNr == 46f
                val caps = AutoDenoise.mapToSettings(1000f, 1000f)
                val capsOk = caps != null &&
                    caps.luminanceNr == 60f && caps.colorNr == 70f
                val tiny = AutoDenoise.mapToSettings(3f, 1f)
                val tinyOk = tiny != null &&
                    tiny.luminanceNr == 6f && tiny.colorNr == 0f
                // Flat frame: no flat-region ambiguity, must stay null.
                val flat = IntArray(64 * 64) { 0xFF808080.toInt() }
                val flatOk = AutoDenoise.analyzePixels(flat, 64, 64) == null
                // Too small to trust: must stay null.
                val small = IntArray(10 * 10) { 0xFF808080.toInt() }
                val smallOk = AutoDenoise.analyzePixels(small, 10, 10) == null
                val desc = AutoDenoise.describe(
                    AutoDenoise.Settings(44f, 46f)) == "Luminance 44 · Color 46"
                val ok = clean && midOk && capsOk && tinyOk &&
                    flatOk && smallOk && desc
                ok to if (ok)
                    "clean->null + sigma->44/46 + caps 60/70 + flat/small null + describe"
                else "FAIL: clean=$clean mid=$midOk caps=$capsOk " +
                    "tiny=$tinyOk flat=$flatOk small=$smallOk desc=$desc"
            }

            // ---- check 69: sync document + merge (Phase C#8 slice 1) ----
            // Pure-Kotlin pin for the cloud-sync foundation: photoKeyOf
            // determinism, entry/document sanitizers (bad keys dropped,
            // newer schema rejected, dup keys keep newest), and the
            // last-writer-wins merge (newer wins either direction, exact
            // ties keep local, tombstones obey the same clock, galleries
            // merge by id on updatedAt), plus the plan describe() line.
            check("Sync document + merge (Phase C#8 slice 1)") {
                val keyDet = photoKeyOf("IMG_1.jpg", 4000, 3000) ==
                    photoKeyOf("  IMG_1.jpg ", 4000, 3000) &&
                    photoKeyOf("IMG_1.jpg", 4000, 3000) !=
                    photoKeyOf("IMG_2.jpg", 4000, 3000)
                val badEntry = sanitizeSyncPhotoEntry(
                    SyncPhotoEntry(photoKey = "   ")) == null
                val tomb = sanitizeSyncPhotoEntry(
                    SyncPhotoEntry(photoKey = "k", deleted = true,
                        settingsJson = "x", rating = 5, favorite = true,
                        keywords = listOf("a")))
                val tombOk = tomb != null && tomb.settingsJson.isEmpty() &&
                    tomb.rating == 0 && !tomb.favorite &&
                    tomb.keywords.isEmpty()
                val future = sanitizeSyncDocument(
                    SyncDocument(schemaVersion = 999, deviceId = "d")) == null
                val noDevice = sanitizeSyncDocument(
                    SyncDocument(deviceId = "")) == null
                val dupDoc = sanitizeSyncDocument(SyncDocument(
                    deviceId = "d",
                    photos = listOf(
                        SyncPhotoEntry("k", updatedAt = 10),
                        SyncPhotoEntry("k", updatedAt = 20),
                    )))
                val dupOk = dupDoc != null && dupDoc.photos.size == 1 &&
                    dupDoc.photos[0].updatedAt == 20L

                val local = SyncDocument(deviceId = "a", photos = listOf(
                    SyncPhotoEntry("newer-local", updatedAt = 30),
                    SyncPhotoEntry("newer-remote", updatedAt = 10),
                    SyncPhotoEntry("tie", updatedAt = 50,
                        settingsJson = "local"),
                    SyncPhotoEntry("tomb-new", updatedAt = 60),
                    SyncPhotoEntry("tomb-old", updatedAt = 5),
                    SyncPhotoEntry("local-only", updatedAt = 7),
                ))
                val remote = SyncDocument(deviceId = "b", photos = listOf(
                    SyncPhotoEntry("newer-local", updatedAt = 20),
                    SyncPhotoEntry("newer-remote", updatedAt = 40),
                    SyncPhotoEntry("tie", updatedAt = 50,
                        settingsJson = "remote"),
                    SyncPhotoEntry("tomb-new", updatedAt = 70,
                        deleted = true),
                    SyncPhotoEntry("tomb-old", updatedAt = 3,
                        deleted = true),
                    SyncPhotoEntry("remote-only", updatedAt = 9),
                ), galleries = listOf(
                    ProofingGallery(id = "g1", name = "G",
                        photoIds = listOf("p1"), updatedAt = 100),
                ))
                val localG = SyncDocument(deviceId = "a", galleries = listOf(
                    ProofingGallery(id = "g1", name = "G",
                        photoIds = listOf("p1", "p2"), updatedAt = 50),
                    ProofingGallery(id = "g2", name = "H",
                        updatedAt = 60),
                ))
                val plan = mergeSyncDocuments(local, remote)
                val planG = mergeSyncDocuments(localG, remote)
                val applyKeys = plan.photosToApply.map { it.photoKey }.toSet()
                val uploadKeys = plan.photosToUpload.map { it.photoKey }.toSet()
                val mergeOk = applyKeys == setOf(
                    "newer-remote", "tomb-new", "remote-only") &&
                    uploadKeys == setOf(
                        "newer-local", "tomb-old", "local-only") &&
                    // tie keeps local: nothing to apply or upload
                    "tie" !in applyKeys && "tie" !in uploadKeys &&
                    // tombstone newer than live entry wins (apply);
                    // older tombstone loses (upload the live one)
                    plan.photosToApply.first { it.photoKey == "tomb-new" }
                        .deleted &&
                    !plan.photosToUpload.first { it.photoKey == "tomb-old" }
                        .deleted
                val galleryOk = planG.galleriesToApply.size == 1 &&
                    planG.galleriesToApply[0].id == "g1" &&
                    planG.galleriesToApply[0].photoIds == listOf("p1") &&
                    planG.galleriesToUpload.map { it.id } == listOf("g2")
                val desc = mergeSyncDocuments(
                    SyncDocument(deviceId = "a"),
                    SyncDocument(deviceId = "b"),
                ).describe() == "already in sync"
                val desc2 = plan.describe().contains("apply 3 photos") &&
                    plan.describe().contains("push 3 photos")
                val ok = keyDet && badEntry && tombOk && future &&
                    noDevice && dupOk && mergeOk && galleryOk &&
                    desc && desc2
                ok to if (ok)
                    "key determinism + sanitizers + LWW merge (ties keep " +
                        "local, tombstones obey clock) + gallery merge + describe"
                else "FAIL: key=$keyDet bad=$badEntry tomb=$tombOk " +
                    "future=$future noDev=$noDevice dup=$dupOk " +
                    "merge=$mergeOk gallery=$galleryOk desc=$desc/$desc2"
            }

            // ---- check 70: sync status text (Phase C#8 slice 2) ----
            // Pure-Kotlin pin for the user-facing sync strings: relative
            // last-sync wording, short device id, and the honest outcome
            // sentences (not-configured is a state, never an error).
            check("Sync status text (Phase C#8 slice 2)") {
                val now = 1_800_000_000_000L
                val rel = formatLastSync(0L, now) == "Never synced" &&
                    formatLastSync(now - 30_000L, now) == "Just now" &&
                    formatLastSync(now - 60_000L, now) == "1 min ago" &&
                    formatLastSync(now - 5 * 60_000L, now) == "5 min ago" &&
                    formatLastSync(now - 3_600_000L, now) == "1 hr ago" &&
                    formatLastSync(now - 2 * 3_600_000L, now) == "2 hr ago" &&
                    formatLastSync(now + 60_000L, now) == "Never synced" &&
                    Regex("[A-Z][a-z]{2} \\d{1,2}, \\d{4} \\d{2}:\\d{2}")
                        .matches(formatLastSync(now - 3 * 86_400_000L, now))
                val dev = shortDeviceId(null) == "—" &&
                    shortDeviceId("") == "—" &&
                    shortDeviceId("abcdef123456") == "abcdef12"
                val nc = SyncRunOutcome.NotConfigured
                val fail = SyncRunOutcome.Failed("boom")
                val upToDate = SyncRunOutcome.Ok(SyncPullOutcome.NoRemoteChanges, 1L)
                val applied = SyncRunOutcome.Ok(
                    SyncPullOutcome.Applied(
                        photosApplied = 2, photosSkipped = 1,
                        galleriesApplied = 0, summary = "",
                    ), 1L,
                )
                val empty = SyncRunOutcome.Ok(
                    SyncPullOutcome.Applied(0, 0, 0, ""), 1L,
                )
                val gal = SyncRunOutcome.Ok(
                    SyncPullOutcome.Applied(0, 0, 1, ""), 1L,
                )
                val descNc = describeSyncOutcome(nc)
                    .contains("No cloud backend connected")
                val descFail = describeSyncOutcome(fail) == "Sync failed: boom"
                val descUp = describeSyncOutcome(upToDate).contains("up to date")
                val descApp = describeSyncOutcome(applied).let {
                    it.contains("2 photos updated") &&
                        it.contains("1 photo skipped (not on this device)")
                }
                val descEmpty = describeSyncOutcome(empty).contains("no changes")
                val descGal = describeSyncOutcome(gal).contains("1 gallery updated")
                val ok = rel && dev && descNc && descFail && descUp &&
                    descApp && descEmpty && descGal
                ok to if (ok)
                    "relative last-sync + short device id + honest outcome " +
                        "sentences for all 6 outcome shapes"
                else "FAIL: rel=$rel dev=$dev nc=$descNc fail=$descFail " +
                    "up=$descUp app=$descApp empty=$descEmpty gal=$descGal"
            }

            // ---- check 71: license/trial state machine (Phase D#9 slice 1) ----
            // Pure-Kotlin pin for the monetization infrastructure: trial
            // day math, key shape + check digit (valid key built through
            // the public checkChar, never hardcoded), the isPro gating
            // hook, and the single status-text copy the UI renders.
            check("License/trial state machine (Phase D#9 slice 1)") {
                val now = 1_800_000_000_000L
                val day = MILLIS_PER_DAY
                val fresh = evaluateLicense(0L, now, null) is LicenseStatus.Trial
                val mid = evaluateLicense(now - 3 * day, now, null)
                val midOk = mid is LicenseStatus.Trial &&
                    mid.daysLeft == TRIAL_DAYS - 3
                val edge = evaluateLicense(now - TRIAL_DAYS * day, now, null)
                val edgeOk = edge == LicenseStatus.TrialExpired
                val over = evaluateLicense(now - (TRIAL_DAYS + 5) * day, now, null)
                val overOk = over == LicenseStatus.TrialExpired
                val blankKey = evaluateLicense(now - 3 * day, now, "  ")
                val blankOk = blankKey is LicenseStatus.Trial
                // Valid key constructed deterministically via checkChar.
                val b15 = "H2WPRO000000001"
                val good = (b15 + checkChar(b15)).chunked(4).joinToString("-")
                val goodOk = validateLicenseKey(good) is KeyValidation.Valid
                val goodLower = validateLicenseKey(good.lowercase()) is
                    KeyValidation.Valid
                val badShape = validateLicenseKey("ABC-123") is KeyValidation.Invalid
                val badAlpha = validateLicenseKey("H2W!-PRO0-0000-0000") is
                    KeyValidation.Invalid
                val wrongCheck = good.dropLast(1) +
                    if (good.last() == '0') "1" else "0"
                val badCheck = validateLicenseKey(wrongCheck) is KeyValidation.Invalid
                val lic = evaluateLicense(now - 3 * day, now, good)
                val licOk = lic is LicenseStatus.Licensed && lic.plan == "Pro"
                // A valid key outranks an expired trial.
                val licOver = evaluateLicense(now - (TRIAL_DAYS + 5) * day, now, good)
                val licOverOk = licOver is LicenseStatus.Licensed
                val gateOk = isPro(lic) && !isPro(mid) &&
                    !isPro(LicenseStatus.TrialExpired)
                val textOk =
                    licenseStatusText(LicenseStatus.Trial(1)) ==
                        "Pro trial · 1 day left" &&
                    licenseStatusText(LicenseStatus.Trial(5))
                        .contains("5 days left") &&
                    licenseStatusText(LicenseStatus.TrialExpired) == "Trial ended" &&
                    licenseStatusText(LicenseStatus.Licensed("Pro"))
                        .contains("Licensed")
                val ok = fresh && midOk && edgeOk && overOk && blankOk &&
                    goodOk && goodLower && badShape && badAlpha && badCheck &&
                    licOk && licOverOk && gateOk && textOk
                ok to if (ok)
                    "trial math + key shape/check-digit + license outranks " +
                        "trial + isPro gating hook + status text"
                else "FAIL: fresh=$fresh mid=$midOk edge=$edgeOk over=$overOk " +
                    "blank=$blankOk good=$goodOk lower=$goodLower " +
                    "shape=$badShape alpha=$badAlpha check=$badCheck " +
                    "lic=$licOk licOver=$licOverOk gate=$gateOk text=$textOk"
            }

            // ---- check 72: clone tone/texture co-registration ----
            // The Clone stamp's texture is bilinearly sampled at the exact
            // fractional source coordinate (x+dx, y+dy); its tone correction
            // smooth(dest) − smooth(src) must be evaluated at that SAME
            // fractional point, not at the floored integer pixel (cycle 136
            // fix, 2026-10-18: the integer index left a faint tone fringe
            // wherever the source patch crossed a hard edge). This pins the
            // fix on-device through the real compositeSpots path (the
            // straight-alpha overlay the preview uploads); the JVM-only
            // CloneToneMatchTest pins the same property off-device.
            check("Clone tone/texture co-registration") {
                val w = 200; val h = 100
                // Source half (x < 100): sharp step edge at x = 62 (60→200).
                // Dest half (x >= 100): flat 160.
                val img = IntArray(w * h) { i ->
                    val x = i % w
                    val v = if (x < 100) { if (x < 62) 60 else 200 } else 160
                    (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                }
                // Clone from (60.3, 50) to (140, 50): dx = −79.7, fraction .7.
                val spot = RetouchSpot(
                    op = RetouchOp.CLONE,
                    x = 0.7f, y = 0.5f,
                    srcX = 0.3015f, srcY = 0.5f,
                    size = 0.12f, feather = 0.5f, strength = 1f,
                )
                val overlay =
                    RetouchEngine.compositeSpots(listOf(spot), w, h, img)
                // Probe dest pixel (142, 50): disc interior (falloff == 1);
                // its source sample lands on the blur transition of the
                // source step edge, where floored vs bilinear tone sampling
                // differ most (~3 levels — the old code fails this).
                val px = 142; val py = 50
                val o = overlay[py * w + px]
                val alpha = (o ushr 24) and 0xFF
                // Reference tone fields via the real box blur
                // (translation-invariant: identical to the engine's region
                // blurs at these interior sample points, both >= blurR
                // inside their regions).
                val blurR = 6
                val fullBlur = InpaintingCore.boxBlurRgb(img, w, h, blurR)
                val trueFx = px + (0.3015f * w - 0.7f * w)
                val dRef = ((fullBlur[py * w + px] shr 16) and 0xFF).toDouble()
                fun refBilinear(fx: Float): Double {
                    val x0 = fx.toInt().coerceIn(0, w - 1)
                    val tx = (fx - x0).coerceIn(0f, 1f).toDouble()
                    val x1 = (x0 + 1).coerceIn(0, w - 1)
                    val v0 = ((fullBlur[py * w + x0] shr 16) and 0xFF).toDouble()
                    val v1 = ((fullBlur[py * w + x1] shr 16) and 0xFF).toDouble()
                    return v0 * (1 - tx) + v1 * tx
                }
                val sRef = refBilinear(trueFx)
                // Texture at the sample point is locally constant 200 (both
                // bilinear neighbours are 200), so the engine's texture
                // sample is exactly 200 regardless of rounding details.
                val expected =
                    (200.0 + (dRef - sRef)).roundToInt().coerceIn(0, 255)
                val actual = (o shr 16) and 0xFF
                val ok = alpha > 250 && abs(actual - expected) <= 2
                ok to "probe alpha=$alpha (need >250) actual=$actual " +
                    "expected=$expected (need |diff|<=2: tone correction " +
                    "co-registered with the stamped texture)"
            }
        } finally {
            // restore the real photo source so the preview keeps rendering it
            zoomMatrix = savedZoom
            if (preview != null) {
                try {
                    setSource(preview)
                } catch (_: Throwable) {
                    // caller re-uploads via invalidateGpuAssets on next frame
                }
            }
            gray.recycle(); halfMask.recycle(); blackMask.recycle()
        }
    } finally {
        GLES30.glViewport(savedVp[0], savedVp[1], savedVp[2], savedVp[3])
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glDeleteFramebuffers(1, intArrayOf(testFbo), 0)
        GLES30.glDeleteTextures(1, intArrayOf(testTex), 0)
    }
    return SelfTestResult(checks, SystemClock.elapsedRealtime() - t0)
}
