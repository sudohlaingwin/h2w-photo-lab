package com.lumora.photoeditor.engine

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Owns all OpenGL ES 3.0 resources for the Lumora pipeline. All methods must
 * be called on the thread that owns the current EGL context (the
 * GLSurfaceView GL thread). Preview and export share this engine; export
 * renders tiled into an FBO and reads pixels back on the same thread.
 */
class GlEngine {

    private var program = 0
    private var blurProgram = 0
    private val uniforms = mutableMapOf<String, Int>()
    /**
     * GL-thread-only per-frame uniform scratch buffers. render() and every
     * path through it (renderExport/renderThumbnail/renderLoupe) run
     * exclusively on the GL thread, and each GlEngine instance has its own
     * GL thread (one engine per GLSurfaceView), so reusing these buffers is
     * race-free. Every buffer is fully rewritten each frame before its
     * glUniform* call, so no stale data can leak across frames. This removes
     * ~15 small heap allocations per frame (the old code allocated fresh
     * FloatArrays/IntArrays for the HSL table, B&W mix, grade wheels and
     * mask/decon uniform blocks on every draw) — real GC churn while
     * dragging sliders at 60fps on the preview thread. A second pass also
     * removed the remaining per-frame allocations: HueRange.values() copies
     * (two per frame), the mask filter{}.take{} lists, the deconvolution
     * "amt|radius" key string, and the creative-effects overlay key string —
     * all replaced by cached fields compared/iterated without allocation.
     */
    private val hslScratch = FloatArray(8 * 3)
    private val bwMixScratch = FloatArray(8)
    private val maskAScratch = FloatArray(MAX_MASKS * 4)
    private val maskBScratch = FloatArray(MAX_MASKS * 4)
    private val maskCScratch = FloatArray(MAX_MASKS * 2)
    private val maskInvScratch = FloatArray(MAX_MASKS)
    private val deconScratch = FloatArray(MAX_MASKS)
    /** Shared 3-float scratch for the grade-wheel uploads (used immediately). */
    private val wheelScratch = FloatArray(3)
    /**
     * Shared 3x3 scratch for the loupe's NDC->UV matrix. renderLoupe runs
     * exclusively on the GL thread (like every other path through render())
     * and the matrix is fully rewritten on every call before use, so
     * reusing it is race-free and can never leak a stale transform.
     */
    private val loupeMatrixScratch = FloatArray(9)
    /** Texture-unit bindings for the mask sampler array (uMaskTex). */
    private val maskUnits = intArrayOf(4, 5, 6, 7)
    /** Texture-unit bindings for the decon despill sampler array (uDeconTex). */
    private val deconUnits = intArrayOf(11, 12, 13, 14)
    /**
     * Cached HueRange.values(): values() copies the enum array on every
     * call, and render() needs the ranges twice per frame.
     */
    private val hueRanges = HueRange.values()
    /**
     * Slot references for the masks uploaded this frame. render() walks the
     * mask list directly into the scratch uniform arrays instead of
     * filter{}.take{} (two list allocations per frame on the GL thread);
     * the decontamination pass below re-reads these slots. Indices
     * [0, maskCount) are fully rewritten each frame before use.
     */
    private val maskSlots = arrayOfNulls<Mask>(MAX_MASKS)
    /**
     * Cached blur-program uniform locations (uImage / uDir / uRadius).
     * [buildBlur] used to resolve all three via glGetUniformLocation up to
     * 12 times per source load — driver lookups are string-keyed and slow on
     * some Mali drivers, so they are resolved once at link time in [init],
     * the same pattern the main program's [cacheUniforms] uses. A cached -1
     * behaves identically to the old per-call lookup (silent no-op), so this
     * is behavior-neutral.
     */
    private var blurUImage = -1
    private var blurUDir = -1
    private var blurURadius = -1
    /** Cached "aPos" attrib locations, one per program handle (see [bindQuad]). */
    private val attribCache = mutableMapOf<Int, Int>()

    private var quadVbo = 0
    private var srcTexture = 0
    private var srcW = 0
    private var srcH = 0

    // blur chain
    private var blurTexA = 0
    private var blurTexB = 0
    private var blurFboA = 0
    private var blurFboB = 0
    private var blurW = 0
    private var blurH = 0

    private var curvesTex = 0
    private val maskTextures = IntArray(MAX_MASKS)
    /**
     * Hair decontamination despill textures (one per mask slot, GL_TEXTURE11
     * .. GL_TEXTURE14 — past the look-transfer 3D LUT on unit 10). Null
     * slots hold a 1x1 black texture so the shader can sample safely; the
     * [deconReady] flags gate the uDecon strengths so a slot without a real
     * despill bitmap can never pull pixels toward black.
     */
    private val deconTextures = IntArray(MAX_MASKS)
    private val deconReady = BooleanArray(MAX_MASKS)
    private var retouchTex = 0
    /**
     * AI Pro Retouch fused 3D LUT (GL_TEXTURE_3D, linear filtering gives
     * hardware trilinear). 0 = not uploaded yet; [aiLutReady] gates the
     * shader mix so an unbound texture can never darken the image.
     */
    private var aiLutTex = 0
    private var aiLutReady = false

    /**
     * Last successfully uploaded AI LUT (copy), so the GPU self-test can
     * save/restore the live preview's grade around its synthetic checks.
     * Null when no LUT is currently uploaded. Kept even though it costs
     * ~60KB (17^3*3 floats): without it the self-test would permanently
     * replace the user's baked grade with test data.
     */
    private var savedAiLut: FloatArray? = null
    private var savedAiLutN: Int = 0

    /**
     * Snapshot of the currently uploaded AI LUT for test save/restore, or
     * null when no LUT is uploaded. Returns a copy — mutating it is safe.
     */
    internal fun aiLutSnapshot(): Pair<FloatArray, Int>? =
        if (aiLutReady) savedAiLut?.copyOf()?.let { it to savedAiLutN } else null

    /**
     * Look transfer 3D LUT (GL_TEXTURE_3D on texture unit 10, past the AI
     * LUT on unit 9). Same gating contract as [aiLutReady]: without an
     * uploaded LUT the shader mix is forced to 0. Baked on the CPU from
     * Reinhard lαβ statistics — see [LookLutUpload].
     */
    private var lookLutTex = 0
    private var lookLutReady = false
    private var savedLookLut: FloatArray? = null
    private var savedLookLutN: Int = 0

    /** Snapshot of the currently uploaded look-transfer LUT (see [aiLutSnapshot]). */
    internal fun lookLutSnapshot(): Pair<FloatArray, Int>? =
        if (lookLutReady) savedLookLut?.copyOf()?.let { it to savedLookLutN } else null
    /** Creative-effects overlay texture (light leak rgb + film dust alpha). */
    private var fxTexture = 0
    /**
     * Last creative-effects parameters the overlay texture was baked for.
     * Compared field-by-field instead of the old per-frame key string
     * (uploadFxOverlay runs inside render(), every frame).
     */
    private var fxActive = false
    private var fxLeakStyle: LightLeakStyle? = null
    private var fxDustSeed = Long.MIN_VALUE
    private var fxDustOn = false

    /**
     * Cached deconvolution kernel (pro-spec Phase 3): recomputed only when
     * amount/radius change, not on every render() call during slider drags.
     */
    private var deconvKernel = FloatArray(DeconvKernel.TAPS)
    /**
     * Parameters the cached kernel was baked for. Compared field-by-field
     * (no per-frame key-string allocation on the GL thread); the NaN
     * initial values guarantee the first active frame bakes.
     */
    private var deconvBakedAmt = Float.NaN
    private var deconvBakedRadius = Float.NaN

    private var exportFbo = 0
    private var exportTex = 0

    /** NDC->NDC zoom/pan matrix (column-major 3x3); identity = fit. */
    var zoomMatrix: FloatArray = floatArrayOf(
        1f, 0f, 0f,
        0f, 1f, 0f,
        0f, 0f, 1f,
    )

    fun init() {
        // Handles may be recycled across re-init; drop any cached attrib
        // locations from a previous program generation.
        attribCache.clear()
        // EGL context loss invalidates all texture IDs; force dummy LUT re-creation.
        dummyLutTexId = 0
        program = linkProgram(Shaders.VERTEX, Shaders.FRAGMENT)
            ?: throw IllegalStateException("main program link failed")
        blurProgram = linkProgram(Shaders.BLUR_VERTEX, Shaders.BLUR_FRAGMENT)
            ?: throw IllegalStateException("blur program link failed")
        // Cache the blur program's uniform locations once (see field KDoc).
        blurUImage = GLES30.glGetUniformLocation(blurProgram, "uImage")
        blurUDir = GLES30.glGetUniformLocation(blurProgram, "uDir")
        blurURadius = GLES30.glGetUniformLocation(blurProgram, "uRadius")
        quadVbo = createQuad()
        curvesTex = createTexture()
        retouchTex = createTexture()
        fxTexture = createTexture()
        for (i in 0 until MAX_MASKS) maskTextures[i] = createTexture()
        for (i in 0 until MAX_MASKS) deconTextures[i] = createTexture()
        cacheUniforms()
    }

    // ---------------------------------------------------------------
    // Source image
    // ---------------------------------------------------------------

    /** Uploads the source bitmap and (re)builds the blurred guide texture. */
    fun setSource(bitmap: Bitmap) {
        if (srcTexture == 0) srcTexture = createTexture()
        srcW = bitmap.width; srcH = bitmap.height
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, srcTexture)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        setTexParams()
        buildBlur(bitmap)
    }

    /**
     * Runs [block] with the GL viewport saved and restored. Offscreen work
     * (blur pyramid, thumbnails, export tiles, self-test) must never leak
     * its viewport: the main preview relies on the viewport matching the
     * surface, and a stale small viewport renders the photo as a tiny
     * thumbnail in the corner with the rest black (the 2026-10-16
     * "black preview" user report).
     */
    private fun <T> withSavedViewport(block: () -> T): T {
        val vp = IntArray(4)
        GLES30.glGetIntegerv(GLES30.GL_VIEWPORT, vp, 0)
        return try {
            block()
        } finally {
            GLES30.glViewport(vp[0], vp[1], vp[2], vp[3])
        }
    }

    private fun buildBlur(bitmap: Bitmap) {
        withSavedViewport {
        // guide texture at ~1/8 scale (min 48px), then separable gaussian
        val w = (bitmap.width / 8).coerceAtLeast(48)
        val h = (bitmap.height / 8).coerceAtLeast(48)
        if (blurTexA == 0) {
            intArrayOf(0, 0).also { ids ->
                GLES30.glGenTextures(2, ids, 0)
                blurTexA = ids[0]; blurTexB = ids[1]
            }
            intArrayOf(0, 0).also { ids ->
                GLES30.glGenFramebuffers(2, ids, 0)
                blurFboA = ids[0]; blurFboB = ids[1]
            }
        }
        blurW = w; blurH = h
        for ((tex, fbo) in listOf(blurTexA to blurFboA, blurTexB to blurFboB)) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
            setTexParams()
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, tex, 0)
        }
        // pass 1: downscale copy of source into A
        GLES30.glUseProgram(blurProgram)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, blurFboA)
        GLES30.glViewport(0, 0, w, h)
        bindQuad(blurProgram)
        // simple copy via blur shader with radius 0
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, srcTexture)
        GLES30.glUniform1i(blurUImage, 0)
        GLES30.glUniform2f(blurUDir, 0f, 0f)
        GLES30.glUniform1f(blurURadius, 0f)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        // passes 2..5: separable gaussian, radius scaled to guide size
        val radius = (w / 90f).coerceIn(1.5f, 6f)
        var ping = true
        repeat(2) {
            for ((dirX, dirY) in listOf(1f to 0f, 0f to 1f)) {
                val (readTex, readFbo, writeFbo) =
                    if (ping) Triple(blurTexA, blurFboA, blurFboB)
                    else Triple(blurTexB, blurFboB, blurFboA)
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, writeFbo)
                GLES30.glViewport(0, 0, w, h)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, readTex)
                GLES30.glUniform2f(blurUDir, dirX, dirY)
                GLES30.glUniform1f(blurURadius, radius)
                GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
                ping = !ping
            }
        }
        // ensure final result lives in blurTexA
        if (!ping) {
            // result is in B; copy B->A
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, blurFboA)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, blurTexB)
            GLES30.glUniform2f(blurUDir, 0f, 0f)
            GLES30.glUniform1f(blurURadius, 0f)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        }
    }

    // ---------------------------------------------------------------
    // Curves LUT + masks + retouch uploads
    // ---------------------------------------------------------------

    fun uploadCurves(state: EditState) {
        val master = state.light.toneCurve.toLut(256)
        val r = state.light.redCurve.toLut(256)
        val g = state.light.greenCurve.toLut(256)
        val b = state.light.blueCurve.toLut(256)
        val buf = ByteBuffer.allocateDirect(256 * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until 256) {
            buf.put((master[i] * 255f).toInt().coerceIn(0, 255).toByte())
            buf.put((r[i] * 255f).toInt().coerceIn(0, 255).toByte())
            buf.put((g[i] * 255f).toInt().coerceIn(0, 255).toByte())
            buf.put((b[i] * 255f).toInt().coerceIn(0, 255).toByte())
        }
        buf.position(0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, curvesTex)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, 256, 1, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    }

    /** Grayscale mask bitmaps (0..255). Missing slots are filled with black. */
    fun uploadMasks(masks: List<Bitmap>) {
        for (i in 0 until MAX_MASKS) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskTextures[i])
            val bmp = masks.getOrNull(i)
            if (bmp != null) {
                GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
            } else {
                // 2x2 black
                val black = ByteBuffer.allocateDirect(16)
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, 2, 2, 0,
                    GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, black)
            }
            setTexParams()
        }
    }

    /**
     * Hair decontamination textures, aligned 1:1 with the [uploadMasks]
     * slots. Null entries bind a 1x1 black texture and clear the slot's
     * [deconReady] flag, which forces that slot's uDecon strength to 0 in
     * [render] — a failed despill can therefore never darken the image, it
     * just disables the feature for that mask.
     */
    fun uploadMaskDecon(decon: List<Bitmap?>) {
        for (i in 0 until MAX_MASKS) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, deconTextures[i])
            val bmp = decon.getOrNull(i)
            if (bmp != null) {
                GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
                deconReady[i] = true
            } else {
                val black = ByteBuffer.allocateDirect(4)
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, 1, 1, 0,
                    GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, black)
                deconReady[i] = false
            }
            setTexParams()
        }
    }

    fun uploadRetouch(bitmap: Bitmap?) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, retouchTex)
        if (bitmap != null) {
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        } else {
            val t = ByteBuffer.allocateDirect(4)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, 1, 1, 0,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, t)
        }
        setTexParams()
    }

    /**
     * Uploads a fused AI Pro Retouch 3D LUT: [lut] holds n^3*3 floats in
     * 0..1, x (red) fastest, i.e. index (((r*n)+g)*n+b)*3 — the same layout
     * [bakeLut] produces and the shader's `texture(uAiLut, rgb)` expects.
     * Stored as RGB8; sampler3D linear filtering gives hardware trilinear.
     * Pass null (or a wrongly-sized array) to mark the LUT unavailable:
     * [render] then forces the mix strength to 0, because sampling an
     * unbound texture would return black and darken the image.
     * Must run on the GL thread.
     */
    fun uploadAiLut(lut: FloatArray?, n: Int) {
        if (lut == null || n <= 0 || lut.size != n * n * n * 3) {
            aiLutReady = false
            savedAiLut = null
            savedAiLutN = 0
            return
        }
        if (aiLutTex == 0) aiLutTex = createTexture()
        val bytes = ByteBuffer.allocateDirect(lut.size).order(ByteOrder.nativeOrder())
        for (v in lut) {
            bytes.put((v.coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255).toByte())
        }
        bytes.position(0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE9)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, aiLutTex)
        GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB8,
            n, n, n, 0, GLES30.GL_RGB, GLES30.GL_UNSIGNED_BYTE, bytes)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        aiLutReady = true
        savedAiLut = lut.copyOf()
        savedAiLutN = n
    }

    /**
     * Uploads a look-transfer 3D LUT: [lut] holds n^3*3 floats in 0..1, x
     * (red) fastest — the layout [ColorTransfer.bakeLut] produces and the
     * shader's `texture(uLookLut, rgb)` expects. Stored as RGB8 on texture
     * unit 10; sampler3D linear filtering gives hardware trilinear. Pass
     * null (or a wrongly-sized array) to mark the LUT unavailable: [render]
     * then forces the mix strength to 0, because sampling an unbound
     * texture would return black and darken the image. Must run on the GL
     * thread.
     */
    fun uploadLookLut(lut: FloatArray?, n: Int) {
        if (lut == null || n <= 0 || lut.size != n * n * n * 3) {
            lookLutReady = false
            savedLookLut = null
            savedLookLutN = 0
            return
        }
        if (lookLutTex == 0) lookLutTex = createTexture()
        val bytes = ByteBuffer.allocateDirect(lut.size).order(ByteOrder.nativeOrder())
        for (v in lut) {
            bytes.put((v.coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255).toByte())
        }
        bytes.position(0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE10)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lookLutTex)
        GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB8,
            n, n, n, 0, GLES30.GL_RGB, GLES30.GL_UNSIGNED_BYTE, bytes)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        lookLutReady = true
        savedLookLut = lut.copyOf()
        savedLookLutN = n
    }

    /**
     * Bakes the pointwise color transform of [state] into a 3D LUT with [n]
     * cells per axis, by rendering an identity ramp through the real shader
     * pipeline. The baked LUT therefore matches the GPU math exactly — there
     * is no CPU duplicate of the color pipeline to diverge.
     *
     * ONLY pointwise ops may be nonzero in [state] (exposure, white balance,
     * parametric tone, contrast, tone curves, HSL mixer, vibrance/saturation,
     * color grading, calibration). Neighborhood ops (clarity, texture,
     * dehaze, sharpening, noise reduction) cannot be represented in a 3D LUT
     * and must be zero — [AiLutBank] applies those as live parameters.
     *
     * STOMPS the engine's source texture (the ramp replaces the photo): the
     * caller must call setSource(previewBitmap) afterwards and then
     * invalidateGpuAssets(), the same restore pattern refreshEditedThumbnail
     * uses. Must run on the GL thread.
     */
    fun bakeLut(state: EditState, n: Int): FloatArray {
        val w = n * n
        val px = IntArray(w * n)
        for (y in 0 until n) {
            for (x in 0 until w) {
                val r = (x % n) * 255 / (n - 1)
                val g = ((x / n) % n) * 255 / (n - 1)
                val b = y * 255 / (n - 1)
                px[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        val ramp = Bitmap.createBitmap(px, w, n, Bitmap.Config.ARGB_8888)
        setSource(ramp)
        uploadCurves(state)
        // Column-major NDC(-1..1) -> UV(0..1) identity: exact 1:1 texel mapping.
        val identity = floatArrayOf(
            0.5f, 0f, 0f,
            0f, 0.5f, 0f,
            0.5f, 0.5f, 1f,
        )
        val out = renderOffscreen(state, identity, w, n)
        ramp.recycle()
        val opx = IntArray(w * n)
        out.getPixels(opx, 0, w, 0, 0, w, n)
        out.recycle()
        // Orientation: texture t=0 is the bitmap's top row (GLUtils upload
        // convention) and renderOffscreen flips Y on readback, so the
        // top-down output pixel (x, y) is the transform of the top-down ramp
        // pixel (x, n-1-y) — hence the input blue index is n-1-y. The
        // identity-bake self-check in AiLutBank verifies this empirically on
        // the real device and disables the feature if it ever drifts.
        val lut = FloatArray(n * n * n * 3)
        for (y in 0 until n) {
            for (x in 0 until w) {
                val p = opx[y * w + x]
                val rIdx = x % n
                val gIdx = (x / n) % n
                val bIdx = (n - 1 - y)
                val o = (((rIdx * n) + gIdx) * n + bIdx) * 3
                lut[o] = ((p shr 16) and 0xFF) / 255f
                lut[o + 1] = ((p shr 8) and 0xFF) / 255f
                lut[o + 2] = (p and 0xFF) / 255f
            }
        }
        return lut
    }

    /**
     * Uploads the creative-effects overlay (light-leak rgb + film-dust alpha)
     * to texture unit 8. The bitmap is regenerated only when the leak style,
     * dust presence or dust seed changes — amount sliders ride as uniforms,
     * so dragging them never stalls the GL thread. Inactive -> 1x1
     * transparent texture + zeroed uniforms (shader branch skips the sample).
     * Must run on the GL thread (called from render()).
     */
    fun uploadFxOverlay(fx: FxParams) {
        val active = fx.isActive()
        val leakOn = active && fx.leak != LightLeakStyle.NONE && fx.leakAmount > 0f
        val dustOn = active && fx.dust > 0f
        // Field-by-field signature comparison: exactly the semantics of the
        // old "leak|seed|dust" key string ("off" when inactive), without
        // allocating it on every frame.
        val leakStyle = if (leakOn) fx.leak else null
        val sigChanged = active != fxActive ||
            (active && (leakStyle != fxLeakStyle || fx.dustSeed != fxDustSeed ||
                dustOn != fxDustOn))
        GLES30.glActiveTexture(GLES30.GL_TEXTURE8)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fxTexture)
        if (sigChanged) {
            fxActive = active
            fxLeakStyle = leakStyle
            fxDustSeed = fx.dustSeed
            fxDustOn = dustOn
            if (active) {
                val bmp = CreativeEffects.fxOverlay(256, 256, fx)
                if (bmp != null) {
                    GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
                    bmp.recycle()
                }
            } else {
                val t = ByteBuffer.allocateDirect(4)
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, 1, 1, 0,
                    GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, t)
            }
            setTexParams()
        }
        GLES30.glUniform1i(u("uFxTex"), 8)
        // Inline clamps (replacing the old per-frame coerced() copy):
        // amounts ride the UI sliders at 0..100, so this only guards
        // against out-of-range state the way coerced() did.
        GLES30.glUniform1f(u("uFxLeak"), if (leakOn) fx.leakAmount.coerceIn(0f, 100f) / 100f else 0f)
        GLES30.glUniform1f(u("uFxDust"), if (dustOn) fx.dust.coerceIn(0f, 100f) / 100f else 0f)
    }

    // ---------------------------------------------------------------
    // Frame rendering
    // ---------------------------------------------------------------

    data class FrameOptions(
        val clipMode: Int = 0,
        val overlayMask: Int = -1,
        val overlayAlpha: Float = 0.55f,
        /** Mask view mode: 0 = red tint overlay, 1 = black & white mask view. */
        val maskViewMode: Int = 0,
        val timeSec: Float = 0f,
        /** NDC x of the before|after split divider; < -1.5 disables the split. */
        val splitX: Float = -2f,
    )

    /**
     * Renders into the currently bound framebuffer with the given viewport.
     * [uvMatrix] maps clip-space quad -> source UV (column-major 3x3).
     *
     * [maskUniforms] optionally overrides the mask list driving the uMaskA/B/C
     * adjustment uniforms and uMaskCount. It defaults to all enabled masks.
     * The preview passes the budgeted list (see EditorViewModel's render
     * path) so the uniforms stay aligned with the uploaded textures when a
     * texture slot is reserved — e.g. the Remove tool's live red overlay
     * occupies the last uMaskTex slot, in which case only 3 masks' rasters
     * were uploaded and deriving 4 masks from [state] would bind the 4th
     * mask's adjustments to the removal overlay's shape.
     */
    fun render(
        state: EditState,
        uvMatrix: FloatArray,
        opts: FrameOptions = FrameOptions(),
        maskUniforms: List<Mask>? = null,
    ) {
        GLES30.glUseProgram(program)
        bindQuad(program)
        GLES30.glUniformMatrix3fv(u("uUvMatrix"), 1, false, uvMatrix, 0)
        GLES30.glUniformMatrix3fv(u("uZoom"), 1, false, zoomMatrix, 0)

        bindTex(0, srcTexture, "uImage")
        bindTex(1, blurTexA, "uBlur")
        bindTex(2, retouchTex, "uRetouch")
        GLES30.glUniform1f(u("uHasRetouch"), if (state.retouch.spots.isEmpty()) 0f else 1f)
        bindTex(3, curvesTex, "uCurves")

        // AI Pro Retouch 3D LUT on texture unit 9 (past masks 4..7 and FX 8).
        // aiLutReady gates everything: without an uploaded LUT the strength
        // is forced to 0, so a half-initialized state can never darken the
        // image by sampling an unbound texture.
        // P0 FIX (2026-10-17): the sampler uniform MUST always be set to 9,
        // even when the LUT isn't ready. Previously the else branch bound
        // texture 0 but left uAiLut at its default (0), so the sampler3D
        // pointed at unit 0's 2D source texture — a sampler-type mismatch
        // that Mali drivers resolve by blackening the entire frame, even
        // though the strength gate skips the actual texture() call.
        // A 1x1x1 dummy 3D texture is bound when not ready so the sampler
        // always sees a complete, type-correct texture.
        if (aiLutReady) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE9)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, aiLutTex)
        } else {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE9)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, dummyLutTex())
        }
        GLES30.glUniform1i(u("uAiLut"), 9)
        val aiStrength =
            if (aiLutReady && state.aiRetouch.enabled) state.aiRetouch.strength.coerceIn(0f, 1f)
            else 0f
        GLES30.glUniform1f(u("uAiLutStrength"), aiStrength)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)

        // Look transfer 3D LUT on texture unit 10 (past AI LUT on 9, FX on 8,
        // masks on 4..7). lookLutReady gates everything: without an uploaded
        // LUT the strength is forced to 0, so a half-initialized state can
        // never darken the image by sampling an unbound texture.
        // P0 FIX (2026-10-17): same sampler-uniform rule as the AI LUT above
        // — uLookLut is always set to 10 and a dummy 3D texture is bound
        // when not ready, so Mali never sees a sampler3D/2D type mismatch.
        if (lookLutReady) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE10)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lookLutTex)
        } else {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE10)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, dummyLutTex())
        }
        GLES30.glUniform1i(u("uLookLut"), 10)
        val lookStrength =
            if (lookLutReady && state.lookTransfer.isActive()) state.lookTransfer.strength.coerceIn(0f, 1f)
            else 0f
        GLES30.glUniform1f(u("uLookLutStrength"), lookStrength)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)

        val l = state.light
        GLES30.glUniform1f(u("uExposure"), l.exposure)
        GLES30.glUniform1f(u("uContrast"), l.contrast / 100f)
        GLES30.glUniform1f(u("uHighlights"), l.highlights / 100f)
        GLES30.glUniform1f(u("uShadows"), l.shadows / 100f)
        GLES30.glUniform1f(u("uWhites"), l.whites / 100f)
        GLES30.glUniform1f(u("uBlacks"), l.blacks / 100f)
        GLES30.glUniform1f(u("uTemp"), l.temp / 100f)
        GLES30.glUniform1f(u("uTint"), l.tint / 100f)
        GLES30.glUniform1f(u("uClarity"), l.clarity / 100f)
        GLES30.glUniform1f(u("uTextureAmt"), l.texture / 100f)
        GLES30.glUniform1f(u("uDehaze"), l.dehaze / 100f)

        // Per-frame scratch (see field docs): fully rewritten every frame.
        // Indexed loop over the cached enum array — HueRange.values()
        // allocates a fresh array per call, and forEachIndexed's capturing
        // lambda would allocate one object per frame too.
        val hslArr = hslScratch
        for (i in hueRanges.indices) {
            val a = state.color.forRange(hueRanges[i])
            hslArr[i * 3] = a.hue / 100f
            hslArr[i * 3 + 1] = a.saturation / 100f
            hslArr[i * 3 + 2] = a.luminance / 100f
        }
        GLES30.glUniform3fv(u("uHsl"), 8, hslArr, 0)
        GLES30.glUniform1f(u("uVibrance"), state.color.vibrance / 100f)
        GLES30.glUniform1f(u("uSaturation"), state.color.saturation / 100f)
        GLES30.glUniform1f(u("uBw"), if (state.color.bw) 1f else 0f)
        // Per-frame scratch (see field docs): fully rewritten every frame.
        val bwMixArr = bwMixScratch
        for (i in hueRanges.indices) {
            bwMixArr[i] = state.color.bwMixFor(hueRanges[i]) / 100f
        }
        GLES30.glUniform1fv(u("uBwMix"), 8, bwMixArr, 0)

        val gp = state.grading
        // Non-allocating grade-wheel uploads: wheelToRgbInto writes into the
        // shared wheelScratch and each result is consumed by its glUniform3f
        // immediately, so one 3-float buffer serves all four wheels.
        wheelToRgbInto(gp.shadows, wheelScratch).let { c ->
            GLES30.glUniform3f(u("uGradeS"), c[0], c[1], c[2])
        }
        wheelToRgbInto(gp.midtones, wheelScratch).let { c ->
            GLES30.glUniform3f(u("uGradeM"), c[0], c[1], c[2])
        }
        wheelToRgbInto(gp.highlights, wheelScratch).let { c ->
            GLES30.glUniform3f(u("uGradeH"), c[0], c[1], c[2])
        }
        wheelToRgbInto(gp.global, wheelScratch).let { c ->
            GLES30.glUniform3f(u("uGradeG"), c[0], c[1], c[2])
        }
        GLES30.glUniform1f(u("uGradeBalance"), gp.balance / 100f)
        GLES30.glUniform1f(u("uGradeStrength"), gp.blending / 100f)

        // Lightroom Camera Calibration.
        val cal = state.calibration
        GLES30.glUniform3f(u("uCalHue"), cal.red.hue, cal.green.hue, cal.blue.hue)
        GLES30.glUniform3f(u("uCalSat"), cal.red.sat, cal.green.sat, cal.blue.sat)
        GLES30.glUniform1f(u("uCalShadowTint"), cal.shadowsTint / 100f)

        val d = state.detail
        GLES30.glUniform1f(u("uSharpen"), d.sharpening / 100f)
        GLES30.glUniform1f(u("uSharpMasking"), d.masking / 100f)
        GLES30.glUniform1f(u("uSharpRadius"), d.radius)
        GLES30.glUniform1f(u("uSharpDetail"), d.detail / 100f)
        GLES30.glUniform1f(u("uLumNr"), d.luminanceNr / 100f)
        GLES30.glUniform1f(u("uLumNrDetail"), d.luminanceDetail / 100f)
        GLES30.glUniform1f(u("uColorNr"), d.colorNr / 100f)
        GLES30.glUniform1f(u("uColorNrDetail"), d.colorDetail / 100f)
        GLES30.glUniform1f(u("uMoire"), d.moire / 100f)
        // Deconvolution sharpening (pro-spec Phase 3): Wiener inverse of a
        // gaussian capture blur, applied to the source fetch. The kernel is
        // blended with identity by amount on the CPU, so the shader just
        // convolves; the gate skips all 25 taps when the amount is 0.
        val deconvActive = d.deconv > 0.05f
        GLES30.glUniform1f(u("uDeconv"), if (deconvActive) 1f else 0f)
        GLES30.glUniform2f(
            u("uTexel"),
            if (srcW > 0) 1f / srcW else 0f,
            if (srcH > 0) 1f / srcH else 0f,
        )
        if (deconvActive) {
            // Direct field comparison: the old "${amt}|${radius}" key string
            // allocated once per frame while deconvolution was active.
            if (d.deconv != deconvBakedAmt || d.deconvRadius != deconvBakedRadius) {
                deconvKernel = DeconvKernel.kernel25(d.deconv / 100f, d.deconvRadius)
                deconvBakedAmt = d.deconv
                deconvBakedRadius = d.deconvRadius
            }
            GLES30.glUniform1fv(u("uDeconvK"), DeconvKernel.TAPS, deconvKernel, 0)
        }

        val o = state.optics
        GLES30.glUniform1f(u("uDistortion"), o.distortion / 100f)
        GLES30.glUniform1f(u("uDefringe"), o.defringe / 100f)
        GLES30.glUniform4f(u("uVignette"), o.vignetteAmount / 100f,
            o.vignetteMidpoint / 100f, o.vignetteFeather / 100f, o.vignetteRoundness / 100f)
        GLES30.glUniform3f(u("uGrain"), o.grainAmount / 100f, o.grainSize / 100f, o.grainRoughness / 100f)
        GLES30.glUniform4f(u("uLensBlur"), o.lensBlurAmount / 100f,
            o.lensFocusX, o.lensFocusY, o.lensSize / 100f)
        GLES30.glUniform2f(u("uLensBlur2"), o.lensFeather / 100f, o.lensShape.toFloat())
        GLES30.glUniform1f(u("uTime"), opts.timeSec)

        // Creative effects overlay (light leak + film dust); texture unit 8
        // sits past the mask units (4..7). Allocation-free: the old
        // `o.fx.coerced()` built a copy on the GL thread every frame;
        // uploadFxOverlay now clamps inline at the uniform sites and
        // CreativeEffects.fxOverlay coerces internally on regeneration.
        uploadFxOverlay(o.fx)

        // Per-frame scratch (see field docs): fully rewritten every frame.
        val aArr = maskAScratch
        val bArr = maskBScratch
        val cArr = maskCScratch
        // Mask inversion (Lightroom "Invert"): the mask raster itself is
        // inverted shader-side via uMaskInv — the adjustments stay as the
        // user set them and apply to the inverse region. (The old code
        // multiplied the adjustment values by -1, which darkened the masked
        // area instead of adjusting the area outside it.)
        val invArr = maskInvScratch
        // Allocation-free mask walk: the old
        // (maskUniforms ?: state.masks.filter { it.enabled }).take(MAX_MASKS)
        // allocated two lists per frame on the GL thread. Indexed access
        // into the source list fills the scratch arrays directly — a
        // supplied maskUniforms is used as-is, state.masks is filtered to
        // enabled masks, capped at MAX_MASKS: exactly the old semantics.
        // Each uploaded mask is recorded in maskSlots for the
        // decontamination pass below.
        val srcMasks = maskUniforms ?: state.masks
        val filterEnabled = maskUniforms == null
        var maskCount = 0
        var si = 0
        while (si < srcMasks.size && maskCount < MAX_MASKS) {
            val m = srcMasks[si++]
            if (filterEnabled && !m.enabled) continue
            val i = maskCount
            val s = m.amount.coerceIn(0f, 1f)
            invArr[i] = if (m.inverted) 1f else 0f
            val adj = m.adjust
            aArr[i * 4] = adj.exposure / 100f * s
            aArr[i * 4 + 1] = adj.contrast / 100f * s
            aArr[i * 4 + 2] = adj.highlights / 100f * s
            aArr[i * 4 + 3] = adj.shadows / 100f * s
            bArr[i * 4] = adj.temp / 100f * s
            bArr[i * 4 + 1] = adj.tint / 100f * s
            bArr[i * 4 + 2] = adj.saturation / 100f * s
            bArr[i * 4 + 3] = adj.clarity / 100f * s
            cArr[i * 2] = adj.sharpness / 100f * s
            cArr[i * 2 + 1] = adj.dehaze / 100f * s
            GLES30.glActiveTexture(GLES30.GL_TEXTURE4 + i)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskTextures[i])
            maskSlots[i] = m
            maskCount++
        }
        GLES30.glUniform1i(u("uMaskCount"), maskCount)
        // The Remove tool's live overlay reserves the slot right after the
        // last user mask (opts.overlayMask == masks.size): bind its texture
        // to the matching unit so the overlay samples the removal mask
        // instead of whatever unit was left bound there.
        if (opts.overlayMask in maskCount until MAX_MASKS) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE4 + opts.overlayMask)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskTextures[opts.overlayMask])
        }
        val loc = u("uMaskTex")
        if (loc >= 0) GLES30.glUniform1iv(loc, MAX_MASKS, maskUnits, 0)
        GLES30.glUniform4fv(u("uMaskA"), MAX_MASKS, aArr, 0)
        GLES30.glUniform4fv(u("uMaskB"), MAX_MASKS, bArr, 0)
        GLES30.glUniform2fv(u("uMaskC"), MAX_MASKS, cArr, 0)
        GLES30.glUniform1fv(u("uMaskInv"), MAX_MASKS, invArr, 0)
        // Hair decontamination (Lightroom "Decontaminate colors"): despill
        // textures ride units 11..14 (past the look-transfer 3D LUT on 10).
        // The strength is gated by deconReady — a slot whose despill bitmap
        // failed to build uploads as black, so its strength is forced to 0
        // here and the slot renders exactly as if the feature were off.
        val deArr = deconScratch
        // Indexed re-read of the masks uploaded above (maskSlots) instead
        // of iterating the filter{}.take{} list again — no allocation.
        for (i in 0 until maskCount) {
            val m = maskSlots[i] ?: continue // unreachable: slot written in the walk above
            deArr[i] = if (deconReady[i]) m.decontaminate.coerceIn(0f, 1f) else 0f
            GLES30.glActiveTexture(GLES30.GL_TEXTURE11 + i)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, deconTextures[i])
        }
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        val deconLoc = u("uDeconTex")
        if (deconLoc >= 0) GLES30.glUniform1iv(deconLoc, MAX_MASKS, deconUnits, 0)
        GLES30.glUniform1fv(u("uDecon"), MAX_MASKS, deArr, 0)

        GLES30.glUniform1i(u("uClipMode"), opts.clipMode)
        GLES30.glUniform1i(u("uOverlayMask"), opts.overlayMask)
        GLES30.glUniform1f(u("uOverlayAlpha"), opts.overlayAlpha)
        GLES30.glUniform1i(u("uMaskViewMode"), opts.maskViewMode)
        GLES30.glUniform1f(u("uSplitX"), opts.splitX)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    // ---------------------------------------------------------------
    // Tiled full-resolution export
    // ---------------------------------------------------------------

    /**
     * Renders [state] at full [srcW]x[srcH] into tiles and stitches the
     * result into a Bitmap. Runs entirely on the GL thread.
     */
    fun renderExport(state: EditState, uvMatrix: FloatArray, onProgress: (Float) -> Unit): Bitmap {
        return withSavedViewport {
        val tile = 2048
        if (exportTex == 0) {
            intArrayOf(0).also { GLES30.glGenTextures(1, it, 0); exportTex = it[0] }
            intArrayOf(0).also { GLES30.glGenFramebuffers(1, it, 0); exportFbo = it[0] }
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, exportTex)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, tile, tile, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        setTexParams()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, exportFbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D, exportTex, 0)

        val out = Bitmap.createBitmap(srcW, srcH, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(tile * tile)
        val buf = ByteBuffer.allocateDirect(tile * tile * 4).order(ByteOrder.nativeOrder())
        val tilesX = (srcW + tile - 1) / tile
        val tilesY = (srcH + tile - 1) / tile
        var done = 0
        // UV matrix maps clip quad -> source UV; for a tile we render the tile's
        // sub-quad by adjusting the vertex positions. Simplest: reuse the same
        // matrix but scissor the viewport per tile with an offset projection.
        for (ty in 0 until tilesY) {
            for (tx in 0 until tilesX) {
                val tw = minOf(tile, srcW - tx * tile)
                val th = minOf(tile, srcH - ty * tile)
                // Render the tile region: map NDC sub-rect to the tile's pixels.
                // We achieve this by scaling/translating clip coords via a bias matrix
                // premultiplied into the UV matrix on the CPU side is complex;
                // instead render full frame into the tile FBO with a viewport offset:
                // viewport maps NDC [-1,1] -> pixels; offsetting the viewport by
                // (-tx*tile, -ty*tile) places the correct sub-rect into the tile.
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, exportFbo)
                GLES30.glViewport(-tx * tile, -ty * tile, srcW, srcH)
                render(state, uvMatrix)
                // read back the tw x th region (bottom-left origin)
                buf.position(0)
                GLES30.glReadPixels(0, 0, tw, th, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
                buf.position(0)
                for (y in 0 until th) {
                    for (x in 0 until tw) {
                        val r = buf.get().toInt() and 0xFF
                        val g = buf.get().toInt() and 0xFF
                        val b = buf.get().toInt() and 0xFF
                        buf.get()
                        // flip Y (GL origin bottom-left)
                        pixels[(th - 1 - y) * tile + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    }
                }
                out.setPixels(pixels, 0, tile, tx * tile, ty * tile, tw, th)
                done++
                onProgress(done.toFloat() / (tilesX * tilesY))
            }
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        out
        }
    }

    /**
     * Renders [state] over [src] into a small bitmap. GL-thread only.
     * Temporarily swaps the engine's source; the caller must restore it.
     */
    fun renderThumbnail(state: EditState, src: Bitmap, size: Int = 192): Bitmap {
        setSource(src)
        uploadCurves(state)
        uploadMasks(emptyList())
        uploadRetouch(null)
        val w = size
        val h = (size.toFloat() * src.height / src.width).toInt().coerceAtLeast(1)
        val matrix = Geometry.uvMatrix(w, h, src.width, src.height, CropParams(), GeometryParams())
        return renderOffscreen(state, matrix, w, h)
    }

    /**
     * Renders the FULL edit pipeline ([state] with its real crop, [masks]
     * and [retouch] overlay) over a downscaled [src] into a small bitmap.
     * Used for the library grid's "edited" thumbnails. GL-thread only;
     * temporarily swaps the engine's source — the caller must restore it
     * (setSource + uploadMasks + uploadRetouch, then invalidateGpuAssets).
     *
     * Masks and the retouch overlay are sampled in image-UV space, so
     * preview-resolution assets stay correctly aligned on the small target.
     */
    fun renderEditedThumbnail(
        state: EditState,
        src: Bitmap,
        masks: List<Bitmap>,
        retouch: Bitmap?,
        decon: List<Bitmap?> = emptyList(),
        size: Int = 384,
    ): Bitmap {
        setSource(src)
        uploadCurves(state)
        uploadMasks(masks)
        uploadMaskDecon(decon)
        uploadRetouch(retouch)
        val w = size
        val h = (size.toFloat() * src.height / src.width).toInt().coerceAtLeast(1)
        val matrix = Geometry.uvMatrix(w, h, src.width, src.height, state.crop, state.geometry)
        return renderOffscreen(state, matrix, w, h)
    }

    /**
     * Renders [state] with [matrix] into an offscreen w×h bitmap and reads
     * it back. GL-thread only. The caller must have set the source and
     * uploaded curves/masks/retouch, and must restore them afterwards.
     */
    private fun renderOffscreen(
        state: EditState,
        matrix: FloatArray,
        w: Int,
        h: Int,
        opts: FrameOptions = FrameOptions(timeSec = 0f),
    ): Bitmap {
        return withSavedViewport {
        val savedZoom = zoomMatrix
        zoomMatrix = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        if (exportTex == 0) {
            intArrayOf(0).also { GLES30.glGenTextures(1, it, 0); exportTex = it[0] }
            intArrayOf(0).also { GLES30.glGenFramebuffers(1, it, 0); exportFbo = it[0] }
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, exportTex)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        setTexParams()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, exportFbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D, exportTex, 0)
        GLES30.glViewport(0, 0, w, h)
        render(state, matrix, opts)
        val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        buf.position(0)
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val r = buf.get().toInt() and 0xFF
            val g = buf.get().toInt() and 0xFF
            val b = buf.get().toInt() and 0xFF
            buf.get()
            px[(h - 1 - y) * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        zoomMatrix = savedZoom
        Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        }
    }

    /**
     * Renders a magnified loupe of the source-image neighborhood around [uv]
     * (source-image UV, 0..1) through the full edit pipeline. GL-thread only.
     *
     * Reuses the engine's already-uploaded assets (source, curves, masks,
     * retouch, FX) — call it on the GL thread while the preview's assets are
     * current and the bitmap shows exactly the pixels under the finger,
     * including the in-progress retouch stroke. The viewport is saved and
     * restored by renderOffscreen (withSavedViewport), so the on-screen
     * preview is unaffected — do not add another save/restore here.
     *
     * The loupe is square; [brushRadiusUv] (fraction of image width) sizes
     * the window so the brush and its surroundings stay visible.
     */
    fun renderLoupe(
        state: EditState,
        uv: Pt,
        brushRadiusUv: Float,
        sizePx: Int = 288,
        opts: FrameOptions = FrameOptions(timeSec = 0f),
    ): Bitmap {
        // Window half-width in UV: ~2 brush diameters across, clamped so the
        // window never leaves the image.
        val half = brushRadiusUv.coerceIn(0.015f, 0.45f) * 2f
        val cx = if (half >= 0.5f) 0.5f else uv.x.coerceIn(half, 1f - half)
        val cy = if (half >= 0.5f) 0.5f else uv.y.coerceIn(half, 1f - half)
        // Column-major 3x3 mapping NDC(-1..1) -> source UV: the same
        // convention the preview's touchToUv inverts, so the window centers
        // exactly on the touch point. Written into the shared GL-thread-only
        // scratch (fully rewritten on every call — see field docs), so the
        // loupe allocates nothing on the GL thread while painting.
        val matrix = loupeMatrixScratch
        matrix[0] = half; matrix[1] = 0f; matrix[2] = 0f
        matrix[3] = 0f; matrix[4] = half; matrix[5] = 0f
        matrix[6] = cx; matrix[7] = cy; matrix[8] = 1f
        // No viewport save/restore here: renderOffscreen wraps the whole
        // render in withSavedViewport, so the save/restore that used to
        // live here was redundant (two extra driver calls plus an IntArray
        // on every loupe frame while painting).
        return renderOffscreen(state, matrix, sizePx, sizePx, opts)
    }

    fun release() {
        // Best-effort cleanup; context teardown handles the rest.
        if (program != 0) GLES30.glDeleteProgram(program)
        if (blurProgram != 0) GLES30.glDeleteProgram(blurProgram)
    }

    // ---------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------

    /**
     * Resolves a main-program uniform location. Names are pre-cached in
     * [cacheUniforms]; as a safety net, an uncached name is resolved on first
     * use and remembered. This matters: `glUniform*(location = -1, …)` is a
     * SILENT no-op in OpenGL ES, so a shader uniform whose name never gets
     * cached would make its control appear in the UI but change nothing —
     * exactly what happened to the Camera Calibration uniforms (uCalHue /
     * uCalSat / uCalShadowTint) until this guard existed.
     */
    private fun u(name: String): Int =
        uniforms.getOrPut(name) { GLES30.glGetUniformLocation(program, name) }

    private fun cacheUniforms() {
        // Uniform names live in [UniformRegistry.FRAGMENT] — the single
        // source of truth, covered by UniformRegistryTest so a bound-but-
        // undeclared uniform (silent glUniform no-op) fails the build.
        for (n in UniformRegistry.FRAGMENT) {
            uniforms[n] = GLES30.glGetUniformLocation(program, n)
        }
    }

    private fun bindTex(unit: Int, tex: Int, uniform: String) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        GLES30.glUniform1i(u(uniform), unit)
    }

    private fun bindQuad(prog: Int) {
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadVbo)
        // The "aPos" attribute index is constant per program; resolving it
        // once per program handle avoids a per-draw driver string lookup.
        // Cached -1 behaves exactly like the old lookup (silently disabled).
        val loc = attribCache.getOrPut(prog) { GLES30.glGetAttribLocation(prog, "aPos") }
        GLES30.glEnableVertexAttribArray(loc)
        GLES30.glVertexAttribPointer(loc, 2, GLES30.GL_FLOAT, false, 0, 0)
    }

    private fun setTexParams() {
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    }

    /**
     * Lazily creates a 1x1x1 black 3D texture used as the sampler-safe
     * fallback for the AI/Look 3D LUT slots when no LUT is uploaded.
     * Binding texture 0 (or leaving the sampler uniform at its default)
     * creates a sampler3D/2D type mismatch that Mali drivers resolve by
     * blackening the frame (P0 2026-10-17). Must run on the GL thread.
     */
    private var dummyLutTexId = 0
    private fun dummyLutTex(): Int {
        if (dummyLutTexId == 0) {
            dummyLutTexId = createTexture()
            // Note: the caller has already made the target unit active and
            // will bind the returned id as GL_TEXTURE_3D immediately.
            val black = byteArrayOf(0, 0, 0)
            val bb = ByteBuffer.allocateDirect(3).order(ByteOrder.nativeOrder())
            bb.put(black); bb.position(0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, dummyLutTexId)
            GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB8,
                1, 1, 1, 0, GLES30.GL_RGB, GLES30.GL_UNSIGNED_BYTE, bb)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)
        }
        return dummyLutTexId
    }

    private fun createTexture(): Int {
        val ids = intArrayOf(0)
        GLES30.glGenTextures(1, ids, 0)
        return ids[0]
    }

    private fun createQuad(): Int {
        val verts = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val bb = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder())
        val fb = bb.asFloatBuffer()
        fb.put(verts); fb.position(0)
        val ids = intArrayOf(0)
        GLES30.glGenBuffers(1, ids, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, ids[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, verts.size * 4, fb, GLES30.GL_STATIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        return ids[0]
    }

    private fun compile(type: Int, src: String): Int {
        val sh = GLES30.glCreateShader(type)
        GLES30.glShaderSource(sh, src)
        GLES30.glCompileShader(sh)
        val status = intArrayOf(0)
        GLES30.glGetShaderiv(sh, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(sh)
            GLES30.glDeleteShader(sh)
            throw IllegalStateException("shader compile failed: $log")
        }
        return sh
    }

    private fun linkProgram(vs: String, fs: String): Int? {
        val prog = GLES30.glCreateProgram()
        GLES30.glAttachShader(prog, compile(GLES30.GL_VERTEX_SHADER, vs))
        GLES30.glAttachShader(prog, compile(GLES30.GL_FRAGMENT_SHADER, fs))
        GLES30.glLinkProgram(prog)
        val status = intArrayOf(0)
        GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            android.util.Log.e("GlEngine", "link failed: " + GLES30.glGetProgramInfoLog(prog))
            return null
        }
        return prog
    }

    companion object {
        const val MAX_MASKS = 4

        /** Grading wheel (hue deg, sat 0..100) -> linear-ish RGB color. */
        fun wheelToRgb(w: GradeWheel): FloatArray {
            val s = (w.sat / 100f).coerceIn(0f, 1f)
            if (s <= 0f) return floatArrayOf(0.5f, 0.5f, 0.5f)
            return ImageMath.hsl2rgb(((w.hueDeg % 360f) + 360f) % 360f / 360f, s, 0.5f)
        }

        /**
         * Non-allocating twin of [wheelToRgb]: writes the RGB color into
         * [out] (size ≥ 3) and returns it. Used for the per-frame grade-wheel
         * uniform uploads so the preview thread never allocates on a draw.
         */
        fun wheelToRgbInto(w: GradeWheel, out: FloatArray): FloatArray {
            val s = (w.sat / 100f).coerceIn(0f, 1f)
            if (s <= 0f) {
                out[0] = 0.5f; out[1] = 0.5f; out[2] = 0.5f
                return out
            }
            ImageMath.hsl2rgbInto(((w.hueDeg % 360f) + 360f) % 360f / 360f, s, 0.5f, out)
            return out
        }
    }

}
