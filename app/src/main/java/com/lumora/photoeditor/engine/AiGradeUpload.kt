package com.lumora.photoeditor.engine

/**
 * Re-establishes the fused AI Pro Retouch 3D LUT on a [GlEngine].
 *
 * The fused LUT is engine-local GPU state (GlEngine.aiLutReady): it used to
 * be uploaded only inside the "AI Pro Retouch" apply tap, so every other
 * path that (re)builds an engine's assets silently dropped the grade while
 * the UI still showed it as applied —
 *  - photo reopen: EditState.aiRetouch.enabled survives in the sidecar, but
 *    the fresh engine has no LUT, so the preview rendered ungraded;
 *  - batch export: the headless engine never ran the apply path, so the
 *    exported file missed the grade the preview showed;
 *  - asset invalidation (GPU self-test, edited thumbnails, context loss):
 *    curves/masks/retouch are re-uploaded, the LUT was not.
 *
 * All re-upload sites share this helper so the bake → fuse → upload
 * sequence cannot drift between them. Must run on the GL thread.
 */
object AiGradeUpload {

    /** Outcome of [upload]. */
    data class Result(
        /** True when the grade will actually render on this engine. */
        val ready: Boolean,
        /**
         * True when a basis-bank bake was attempted: the bake replaces the
         * engine's source texture with the identity ramp (even when the
         * bake later fails), so the caller must re-upload the source
         * afterwards. The bank bakes once per process, so this only ever
         * happens on the first call.
         */
        val sourceStomped: Boolean,
    )

    /**
     * Uploads the LUT fused from [weights] (null = no grade: marks the LUT
     * unavailable so the shader forces the mix strength to 0 — a
     * half-initialized state can never darken the image by sampling an
     * unbound texture).
     *
     * [wbGains], when non-null, holds the per-photo white-balance gains
     * [gR, gG, gB] from [AiProRetouch.computeWbGains]; they are folded into
     * the fused LUT ([AiProRetouch.warpLutForWb]) so the cast is neutralized
     * before the creative grade, at zero extra shader cost. Sanitized here
     * (finite, 3 channels, sane range) because these values cross process
     * restarts via the sidecar — a corrupt sidecar must never warp the LUT
     * into garbage.
     */
    fun upload(
        engine: GlEngine,
        weights: List<Float>?,
        wbGains: List<Float>? = null,
    ): Result {
        if (weights == null ||
            weights.size != AiProRetouch.BASIS_COUNT ||
            !AiLutBank.available
        ) {
            // No GL work below touches the source texture.
            engine.uploadAiLut(null, 0)
            return Result(ready = false, sourceStomped = false)
        }
        // A bake attempt stomps the source texture even when the bake later
        // fails (the ramp renders progressively), so report it whenever a
        // bake could have run — not only on success.
        val bakeAttempted = AiLutBank.basisLuts() == null
        val bank =
            if (AiLutBank.ensureBaked(engine)) AiLutBank.basisLuts() else null
        if (bank == null || bank.size != AiProRetouch.BASIS_COUNT) {
            engine.uploadAiLut(null, 0)
            return Result(ready = false, sourceStomped = bakeAttempted)
        }
        return try {
            val fused = AiProRetouch.fuse(weights.toFloatArray(), bank)
            val final = sanitizeWbGains(wbGains)?.let { gains ->
                AiProRetouch.warpLutForWb(fused, gains, AiProRetouch.LUT_SIZE)
            } ?: fused
            engine.uploadAiLut(final, AiProRetouch.LUT_SIZE)
            Result(ready = true, sourceStomped = bakeAttempted)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            android.util.Log.w("AiGradeUpload", "fuse/upload failed", t)
            engine.uploadAiLut(null, 0)
            Result(ready = false, sourceStomped = bakeAttempted)
        }
    }

    /**
     * Sidecar-safe WB gains: exactly 3 finite gains inside a sane range.
     * Anything else (old sidecars predate the field and carry null; a
     * hand-edited sidecar could carry junk) means "no WB warp" rather than
     * a corrupted grade.
     */
    private fun sanitizeWbGains(wbGains: List<Float>?): FloatArray? {
        if (wbGains == null || wbGains.size != 3) return null
        val g = FloatArray(3)
        for (i in 0..2) {
            val v = wbGains[i]
            if (!v.isFinite()) return null
            g[i] = v.coerceIn(0.5f, 2f)
        }
        // All-identity gains warp to the identical LUT; skip the work.
        if (g[0] == 1f && g[1] == 1f && g[2] == 1f) return null
        return g
    }
}
