package com.lumora.photoeditor.engine

/**
 * Re-establishes the look-transfer 3D LUT on a [GlEngine].
 *
 * Mirrors [AiGradeUpload] but is simpler: the look-transfer LUT is baked on
 * the CPU from stored lαβ statistics ([ColorTransfer.bakeLut]) — no shader
 * bake, so nothing stomps the source texture and the bake can run anywhere
 * (the GL-thread requirement is only for the texture upload itself).
 *
 * Call sites: the preview frame loop in EditorGlView (revision-tracked),
 * ExportManager's GL block (single + batch export on headless engines).
 * Passing an inactive [LookTransferParams] marks the LUT unavailable so the
 * shader forces the mix strength to 0 — a stale LUT from a previous photo
 * can never leak into the next export.
 */
object LookLutUpload {

    /**
     * Uploads the LUT baked from [params] (no-op marks unavailable when
     * inactive). Never throws: a bake/upload failure degrades to "no look
     * transfer" rather than a broken frame.
     */
    fun upload(engine: GlEngine, params: LookTransferParams) {
        if (!params.isActive()) {
            engine.uploadLookLut(null, 0)
            return
        }
        try {
            engine.uploadLookLut(
                ColorTransfer.bakeLut(
                    params.srcStats.toFloatArray(),
                    params.refStats.toFloatArray(),
                    ColorTransfer.LUT_SIZE,
                ),
                ColorTransfer.LUT_SIZE,
            )
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            android.util.Log.w("LookLutUpload", "bake/upload failed", t)
            engine.uploadLookLut(null, 0)
        }
    }
}
