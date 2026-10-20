package com.lumora.photoeditor.engine

import kotlin.math.roundToInt

/**
 * Pure trip policy for the editor's black-frame watchdog (P0, 2026-10-17).
 *
 * The watchdog converts ANY black-frame failure (the editor preview rendered
 * fully black on the user's vivo V60) into a working software preview plus a
 * diagnostic bundle, instead of a dead black screen. The stateful parts —
 * streak counting, bitmap tracking, source-luma measurement — live in
 * EditorViewModel; everything here is a pure predicate/constant, so the
 * thresholds and false-positive gates are unit-testable (see SelfTest check
 * 51 and the standalone harness) and can't silently drift when the caller
 * is refactored.
 *
 * Pure Kotlin, no Android imports — unit-testable on the JVM.
 */
object WatchdogPolicy {

    /** Consecutive near-black frames before the watchdog trips. */
    const val TRIP_STREAK = 4

    /** Mean frame luma below this counts as a black frame. */
    const val BLACK_FRAME_LUMA = 0.012f

    /**
     * Sources at/below this mean luma legitimately render near-black
     * (night shots), so a black frame is not suspicious.
     */
    const val DARK_SOURCE_LUMA = 0.15f

    /** Exposure at/below this EV is a deliberate blackout, not a failure. */
    const val BLACKOUT_EXPOSURE_EV = -3f

    /**
     * False-positive gates. Mask-overlay and B&W mask-check views repaint
     * the frame by design, and exposure <= -3 EV deliberately blacks out
     * the image — in all three cases the watchdog must stay silent.
     */
    fun suppressed(overlayIdx: Int, maskView: Int, exposureEv: Float): Boolean =
        overlayIdx >= 0 || maskView != 0 || exposureEv <= BLACKOUT_EXPOSURE_EV

    /** True when a frame from a bright source is suspiciously dark. */
    fun isBlackFrame(frameMeanLuma: Float): Boolean =
        frameMeanLuma < BLACK_FRAME_LUMA

    /** True when the source photo itself is dark enough to justify a black frame. */
    fun isDarkSource(sourceMeanLuma: Float): Boolean =
        sourceMeanLuma <= DARK_SOURCE_LUMA

    /** Trips once the caller has seen [TRIP_STREAK] consecutive black frames. */
    fun shouldTrip(streak: Int): Boolean = streak >= TRIP_STREAK

    /**
     * Null-preview blind-spot gate (P0 recurrence, 2026-10-18): the
     * watchdog used to silently return when the software preview bitmap
     * was null, so a photo-open editor whose bitmap never materialized
     * showed a dead black screen with no watchdog UI and no diagnostic.
     * The gate only counts when a photo is actually open — the loading
     * shimmer and the error banner already explain a black screen, so
     * those states stay silent.
     */
    fun nullPreviewSuspicious(photoOpen: Boolean, loading: Boolean, error: Boolean): Boolean =
        photoOpen && !loading && !error

    /** Diagnostic bundle for the null-preview trip; same log shape as [diagnostic]. */
    fun nullPreviewDiagnostic(frameMeanLuma: Float): String =
        "no software preview bitmap for the open photo; " +
            "GPU frame ${(frameMeanLuma * 100).roundToInt()}% over $TRIP_STREAK reads"

    /** The diagnostic bundle attached to a trip; same shape as the P0 fix logged. */
    fun diagnostic(
        frameMeanLuma: Float,
        sourceLuma: Float,
        exposureEv: Float,
        aiEnabled: Boolean,
        aiStrength: Float,
        overlayIdx: Int,
        maskView: Int,
    ): String =
        "black frame ${(frameMeanLuma * 100).roundToInt()}% over $TRIP_STREAK reads; " +
            "source ${(sourceLuma * 100).roundToInt()}%; ev=$exposureEv; " +
            "aiGrade=$aiEnabled@${"%.2f".format(aiStrength)}; " +
            "overlay=$overlayIdx; maskView=$maskView"

    /**
     * Consecutive failed GPU readbacks after which the scopes are declared
     * dead for the session (Mali glReadPixels quirk, 2026-10-18): on the
     * user's vivo V60 the readback fails while the preview itself renders
     * fine, so the histogram/waveform/vectorscope/RGB parade freeze — on
     * first open they sit at empty() and the mini-histogram renders a flat
     * line (video bug #2). Past this many failures the UI shows an honest
     * "unavailable" placeholder instead of a misleading flat/stale scope.
     * A later successful readback revives them. ~450ms between readbacks,
     * so 6 ≈ 2.7s: quick enough to matter, long enough to ride out a
     * transient hiccup.
     */
    const val SCOPES_DEAD_AFTER_FAILURES = 6
}
