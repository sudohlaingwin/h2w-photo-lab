package com.lumora.photoeditor.engine

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
        "black frame ${(frameMeanLuma * 100).toInt()}% over $TRIP_STREAK reads; " +
            "source ${(sourceLuma * 100).toInt()}%; ev=$exposureEv; " +
            "aiGrade=$aiEnabled@${"%.2f".format(aiStrength)}; " +
            "overlay=$overlayIdx; maskView=$maskView"
}
