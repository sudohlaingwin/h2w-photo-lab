package com.lumora.photoeditor.engine

import org.junit.Assert.*
import org.junit.Test

/**
 * Executed verification for engine/WatchdogPolicy.kt (pure Kotlin, zero
 * imports — runs on the standalone kotlinc harness; Gradle's test workers
 * are broken in this env).
 *
 * This pins the P0 black-preview trip contract: the policy is the last line
 * of defence between the user and a dead black editor preview on his
 * vivo V60 (2026-10-17, recurred 2026-10-18). The trip thresholds, the
 * false-positive gates (mask overlay, B&W mask view, deliberate -3 EV
 * blackout, dark sources), the null-preview blind-spot gate (2026-10-18
 * recurrence fix), the scopes-dead threshold (Mali glReadPixels quirk),
 * and the diagnostic string shapes are all pinned here so no refactor of
 * EditorViewModel can silently change when the watchdog fires.
 */
class WatchdogPolicyTest {

    // ---- Constants ----

    @Test fun tripStreakIsFour() {
        assertEquals(4, WatchdogPolicy.TRIP_STREAK)
    }

    @Test fun blackFrameLumaThreshold() {
        assertEquals(0.012f, WatchdogPolicy.BLACK_FRAME_LUMA, 0.0f)
    }

    @Test fun darkSourceLumaThreshold() {
        assertEquals(0.15f, WatchdogPolicy.DARK_SOURCE_LUMA, 0.0f)
    }

    @Test fun blackoutExposureThreshold() {
        assertEquals(-3f, WatchdogPolicy.BLACKOUT_EXPOSURE_EV, 0.0f)
    }

    @Test fun scopesDeadAfterSixFailures() {
        // ~450ms between readbacks, so 6 ≈ 2.7s: quick enough to matter,
        // long enough to ride out a transient hiccup.
        assertEquals(6, WatchdogPolicy.SCOPES_DEAD_AFTER_FAILURES)
    }

    // ---- Suppression gates ----

    @Test fun normalFrameIsNotSuppressed() {
        assertFalse(WatchdogPolicy.suppressed(-1, 0, 0f))
    }

    @Test fun maskOverlaySuppresses() {
        assertTrue(WatchdogPolicy.suppressed(0, 0, 0f))
        assertTrue(WatchdogPolicy.suppressed(2, 0, 0f))
    }

    @Test fun maskBlackWhiteViewSuppresses() {
        assertTrue(WatchdogPolicy.suppressed(-1, 1, 0f))
    }

    @Test fun deliberateBlackoutExposureSuppresses() {
        assertTrue(WatchdogPolicy.suppressed(-1, 0, -3f))
        assertTrue(WatchdogPolicy.suppressed(-1, 0, -10f))
    }

    @Test fun justAboveBlackoutDoesNotSuppress() {
        assertFalse(WatchdogPolicy.suppressed(-1, 0, -2.99f))
        assertFalse(WatchdogPolicy.suppressed(-1, 0, 1.5f))
    }

    // ---- Black-frame detection ----

    @Test fun clearlyDarkFrameIsBlack() {
        assertTrue(WatchdogPolicy.isBlackFrame(0f))
        assertTrue(WatchdogPolicy.isBlackFrame(0.0119f))
    }

    @Test fun thresholdBoundaryIsNotBlack() {
        // Strict <: the 0.012 line itself is a bright-enough frame.
        assertFalse(WatchdogPolicy.isBlackFrame(0.012f))
        assertFalse(WatchdogPolicy.isBlackFrame(0.02f))
    }

    // ---- Dark-source gate (night shots must never trip) ----

    @Test fun darkSourceIsDark() {
        assertTrue(WatchdogPolicy.isDarkSource(0f))
        assertTrue(WatchdogPolicy.isDarkSource(0.15f))
    }

    @Test fun justAboveDarkSourceLineIsNotDark() {
        assertFalse(WatchdogPolicy.isDarkSource(0.1501f))
        assertFalse(WatchdogPolicy.isDarkSource(0.9f))
    }

    @Test fun darkSourceAtMinus3EvStaysAboveTripLine() {
        // Threshold margin from the doc comment: at -3 EV a 0.15-luma
        // source renders ~0.019, comfortably above the 0.012 trip line —
        // so exposure and darkness gates agree instead of fighting.
        val rendered = 0.15f * Math.pow(2.0, -3.0).toFloat()
        assertTrue(rendered > WatchdogPolicy.BLACK_FRAME_LUMA)
    }

    // ---- Trip streak ----

    @Test fun shortStreakDoesNotTrip() {
        assertFalse(WatchdogPolicy.shouldTrip(0))
        assertFalse(WatchdogPolicy.shouldTrip(3))
    }

    @Test fun fullStreakTrips() {
        assertTrue(WatchdogPolicy.shouldTrip(4))
        assertTrue(WatchdogPolicy.shouldTrip(10))
    }

    // ---- Null-preview blind-spot gate (P0 recurrence, 2026-10-18) ----

    @Test fun openPhotoWithNoLoadingOrErrorIsSuspicious() {
        assertTrue(WatchdogPolicy.nullPreviewSuspicious(photoOpen = true, loading = false, error = false))
    }

    @Test fun loadingShimmerStaysSilent() {
        assertFalse(WatchdogPolicy.nullPreviewSuspicious(photoOpen = true, loading = true, error = false))
    }

    @Test fun errorBannerStaysSilent() {
        assertFalse(WatchdogPolicy.nullPreviewSuspicious(photoOpen = true, loading = false, error = true))
    }

    @Test fun noOpenPhotoStaysSilent() {
        assertFalse(WatchdogPolicy.nullPreviewSuspicious(photoOpen = false, loading = false, error = false))
    }

    // ---- Diagnostic string shapes ----

    @Test fun diagnosticStringIsExact() {
        val d = WatchdogPolicy.diagnostic(0.005f, 0.4f, 0.5f, true, 0.75f, -1, 0)
        assertEquals(
            "black frame 0% over 4 reads; source 40%; ev=0.5; aiGrade=true@0.75; overlay=-1; maskView=0",
            d
        )
    }

    @Test fun diagnosticRoundsFrameLumaDownToInt() {
        val d = WatchdogPolicy.diagnostic(0.0119f, 1f, -1f, false, 0f, 3, 0)
        assertTrue(d.contains("black frame 1% over 4 reads"))
        assertTrue(d.contains("overlay=3"))
    }

    @Test fun nullPreviewDiagnosticShape() {
        val d = WatchdogPolicy.nullPreviewDiagnostic(0.005f)
        assertEquals("no software preview bitmap for the open photo; GPU frame 0% over 4 reads", d)
    }
}
