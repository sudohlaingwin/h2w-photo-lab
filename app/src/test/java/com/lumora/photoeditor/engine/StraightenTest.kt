package com.lumora.photoeditor.engine

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.random.Random

/**
 * Executed verification for engine/Straighten.kt (pure Kotlin, zero imports —
 * runs on the standalone kotlinc harness; Gradle's test workers are broken
 * in this env).
 *
 * This pins the math behind the Geometry panel's automatic straighten
 * (backlog #3, crop UX): Sobel edge votes into a ±45° orientation histogram
 * find the horizon angle, and [Straighten.levelRotation] folds the correction
 * into quarter turns + a ±45° slider residual, accounting for flips. An
 * earlier ad-hoc run (hidden_files/autostraight-harness/) established these
 * expectations; this file makes them a committed regression contract.
 */
class StraightenTest {

    // ---- Synthetic-image helpers ----

    /**
     * Synthetic ARGB image with a SOFT boundary line through the center at
     * [thetaDeg] (y-down degrees, undirected) — like a real photographed
     * horizon, the transition spans a few pixels so the image gradient is
     * genuinely perpendicular to the line (a 1px crisp step would alias into
     * horizontal stair-treads and bias any gradient method toward 0).
     */
    private fun tiltedHorizon(
        w: Int, h: Int, thetaDeg: Double, noise: Int = 0, rng: Random = Random(7)
    ): IntArray {
        val px = IntArray(w * h)
        val m = tan(Math.toRadians(thetaDeg))
        val norm = sqrt(1 + m * m)
        val cx = w / 2.0; val cy = h / 2.0
        for (y in 0 until h) for (x in 0 until w) {
            val d = ((y - cy) - m * (x - cx)) / norm // signed px distance to the line
            // soft transition over +/-3px: white above the line, black below
            val t = ((-d + 3.0) / 6.0).coerceIn(0.0, 1.0)
            val s = t * t * (3 - 2 * t) // smoothstep
            var v = (20 + (235 - 20) * s).toInt()
            if (noise > 0) v = (v + rng.nextInt(-noise, noise + 1)).coerceIn(0, 255)
            px[y * w + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        return px
    }

    private fun noiseImage(w: Int, h: Int, rng: Random = Random(3)): IntArray {
        val px = IntArray(w * h)
        for (i in px.indices) {
            val v = rng.nextInt(256)
            px[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        return px
    }

    // ---- Input validation ----

    @Test fun rejectsEmptyInput() {
        assertNull(Straighten.detectHorizon(IntArray(0), 0, 0))
    }

    @Test fun rejectsTinyImage() {
        assertNull(Straighten.detectHorizon(IntArray(16) { 0xFF808080.toInt() }, 4, 4))
    }

    @Test fun rejectsUndersizedBuffer() {
        assertNull(Straighten.detectHorizon(IntArray(100), 32, 32))
    }

    // ---- Horizon detection ----

    @Test fun detectsPositiveTilt() {
        val l = Straighten.detectHorizon(tiltedHorizon(400, 300, 15.0, noise = 6), 400, 300)
        assertNotNull(l)
        l!!
        assertEquals(15f, l.angleDeg, 1.5f)
        assertTrue("confidence ${l.confidence}", l.confidence in 0.3f..1f)
    }

    @Test fun detectsNegativeTilt() {
        val l = Straighten.detectHorizon(tiltedHorizon(400, 300, -22.0, noise = 6), 400, 300)
        assertNotNull(l)
        l!!
        assertEquals(-22f, l.angleDeg, 1.5f)
    }

    @Test fun detectsSmallTilt() {
        // Sub-degree precision matters: a 5° tilt must still be found.
        val l = Straighten.detectHorizon(tiltedHorizon(400, 300, 5.0, noise = 4), 400, 300)
        assertNotNull(l)
        l!!
        assertEquals(5f, l.angleDeg, 1.5f)
    }

    @Test fun levelHorizonGivesNearZeroAngle() {
        val l = Straighten.detectHorizon(tiltedHorizon(400, 300, 0.5, noise = 6), 400, 300)
        assertNotNull(l)
        l!!
        assertTrue("angle ${l.angleDeg}", abs(l.angleDeg) < 1.5f)
    }

    @Test fun noisyHorizonStillFound() {
        val l = Straighten.detectHorizon(tiltedHorizon(400, 300, -12.0, noise = 25), 400, 300)
        assertNotNull(l)
        l!!
        assertEquals(-12f, l.angleDeg, 2.5f)
    }

    // ---- Honest no-guess cases ----

    @Test fun verticalLineOutsideWindowReturnsNull() {
        assertNull(Straighten.detectHorizon(tiltedHorizon(400, 300, 88.0, noise = 4), 400, 300))
    }

    @Test fun steepDiagonalReturnsNull() {
        assertNull(Straighten.detectHorizon(tiltedHorizon(400, 300, 60.0, noise = 4), 400, 300))
    }

    @Test fun pureNoiseReturnsNull() {
        assertNull(Straighten.detectHorizon(noiseImage(400, 300), 400, 300))
    }

    // ---- levelRotation: fold into quarter turns + residual ----

    @Test fun levelRotationCancelsDetectedAngle() {
        val r = Straighten.levelRotation(15f, flipH = false, flipV = false)
        assertTrue("rotate ${r.rotate}", abs(15f + r.rotate) < 0.01f)
        assertEquals(0, r.quarterTurns)
    }

    @Test fun flipsNegateCorrectionDirection() {
        var r = Straighten.levelRotation(15f, flipH = true, flipV = false)
        assertTrue("flipH $r", abs(r.rotate - 15f) < 0.01f && r.quarterTurns == 0)
        r = Straighten.levelRotation(15f, flipH = false, flipV = true)
        assertTrue("flipV $r", abs(r.rotate - 15f) < 0.01f)
        // Double flip restores the original correction direction.
        r = Straighten.levelRotation(15f, flipH = true, flipV = true)
        assertTrue("double flip $r", abs(r.rotate + 15f) < 0.01f)
    }

    @Test fun quarterTurnFoldKeepsResidualWithin45() {
        var r = Straighten.levelRotation(80f, flipH = false, flipV = false)
        assertEquals(-1, r.quarterTurns)
        assertTrue("rotate ${r.rotate}", abs(r.rotate - 10f) < 0.01f)
        r = Straighten.levelRotation(-80f, flipH = false, flipV = false)
        assertEquals(1, r.quarterTurns)
        assertTrue("rotate ${r.rotate}", abs(r.rotate + 10f) < 0.01f)
    }
}
