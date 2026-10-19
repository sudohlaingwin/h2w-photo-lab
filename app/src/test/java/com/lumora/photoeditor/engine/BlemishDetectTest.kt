package com.lumora.photoeditor.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Executed verification of [BlemishDetect] — the pure-Kotlin blemish detector
 * behind Auto Retouch's "Detecting…" phase (the user-facing retouch track his
 * "plastic face / not excellent retouch" complaints sit on).
 *
 * Pins the observable contract: guard shape, both-polarity |diff| band
 * (12..60, threshold-scaled), strict 5x5 local-extremum requirement,
 * thresholdScale adaptive profiling (incl. 0.5..2.5 coercion), NMS collapse of
 * one flat blob to one heal spot, maxSpots cap, and UV/radius output mapping.
 *
 * Harness: hidden_files/blemishdetect-test-harness/run.sh (kotlinc 1.9.23 +
 * JUnit 4.13.2 on the REAL source + this REAL repo test — Gradle's unit-test
 * runner is broken in this env).
 */
class BlemishDetectTest {

    private fun argb(v: Int): Int = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    private fun frame(w: Int, h: Int, bg: Int): IntArray = IntArray(w * h) { argb(bg) }

    /** Filled disc of luma [v], radius [r], centered ([cx],[cy]). */
    private fun disc(p: IntArray, w: Int, cx: Int, cy: Int, r: Int, v: Int) {
        val h = p.size / w
        for (dy in -r..r) for (dx in -r..r) {
            if (dx * dx + dy * dy <= r * r) {
                val x = cx + dx
                val y = cy + dy
                if (x in 0 until w && y in 0 until h) p[y * w + x] = argb(v)
            }
        }
    }

    // ---- guard contract -------------------------------------------------

    @Test fun tooNarrowFrameReturnsEmpty() {
        assertTrue(BlemishDetect.detect(frame(15, 64, 160), 15, 64).isEmpty())
    }

    @Test fun tooShortFrameReturnsEmpty() {
        assertTrue(BlemishDetect.detect(frame(64, 15, 160), 64, 15).isEmpty())
    }

    @Test fun shortPixelBufferReturnsEmpty() {
        // pixels smaller than w*h must not be read out of bounds.
        assertTrue(BlemishDetect.detect(IntArray(64 * 64 - 1) { argb(160) }, 64, 64).isEmpty())
    }

    @Test fun zeroMaxSpotsReturnsEmpty() {
        val p = frame(64, 64, 160)
        disc(p, 64, 32, 32, 3, 130)
        assertTrue(BlemishDetect.detect(p, 64, 64, maxSpots = 0).isEmpty())
        assertTrue(BlemishDetect.detect(p, 64, 64, maxSpots = -1).isEmpty())
    }

    // ---- clean frames: no false positives --------------------------------

    @Test fun uniformFrameHasNoBlemishes() {
        assertTrue(BlemishDetect.detect(frame(64, 64, 160), 64, 64).isEmpty())
    }

    @Test fun smoothRampHasNoBlemishes() {
        // A linear gradient has ~zero local curvature, so |lum - bg| stays
        // far below the 12 threshold everywhere.
        val p = IntArray(64 * 64) { i ->
            val x = i % 64
            argb(120 + x * 40 / 63)
        }
        assertTrue(BlemishDetect.detect(p, 64, 64).isEmpty())
    }

    // ---- core detection: both polarities ----------------------------------

    @Test fun darkSpotDetectedOnce() {
        val p = frame(64, 64, 160)
        disc(p, 64, 32, 32, 3, 130) // contrast 30 -> |diff| ~27, in band
        val spots = BlemishDetect.detect(p, 64, 64)
        assertEquals(1, spots.size)
        val s = spots[0]
        assertTrue("x=${s.x}", s.x in 0.49f..0.51f)
        assertTrue("y=${s.y}", s.y in 0.49f..0.51f)
        assertTrue("radius=${s.radius}", s.radius in 0.008f..0.03f)
    }

    @Test fun brightSpotDetectedOnce() {
        val p = frame(64, 64, 120)
        disc(p, 64, 32, 32, 3, 150) // bright whitehead, |diff| ~27
        val spots = BlemishDetect.detect(p, 64, 64)
        assertEquals(1, spots.size)
        val s = spots[0]
        assertTrue("x=${s.x}", s.x in 0.49f..0.51f)
        assertTrue("y=${s.y}", s.y in 0.49f..0.51f)
    }

    // ---- |diff| band edges ------------------------------------------------

    @Test fun subThresholdSpotIgnored() {
        val p = frame(64, 64, 160)
        disc(p, 64, 32, 32, 3, 152) // contrast 8 -> |diff| ~7 < 12
        assertTrue(BlemishDetect.detect(p, 64, 64).isEmpty())
    }

    @Test fun overThresholdSpotIgnored() {
        // |diff| ~81 > 60: a deviation this strong is structure (edge/text),
        // not a healable blemish — the band contract.
        val p = frame(64, 64, 160)
        disc(p, 64, 32, 32, 3, 70)
        assertTrue(BlemishDetect.detect(p, 64, 64).isEmpty())
    }

    // ---- thresholdScale: RetouchIntel adaptive profiling ------------------

    @Test fun stricterScaleSuppressesBorderlineSpot() {
        val p = frame(64, 64, 160)
        disc(p, 64, 32, 32, 3, 145) // contrast 15 -> |diff| ~13.5
        assertEquals(1, BlemishDetect.detect(p, 64, 64, thresholdScale = 1f).size)
        // scale 1.5 -> acceptance band 18..90: the spot drops out.
        assertTrue(BlemishDetect.detect(p, 64, 64, thresholdScale = 1.5f).isEmpty())
    }

    @Test fun sensitiveScaleCatchesSubtleSpot() {
        val p = frame(64, 64, 160)
        disc(p, 64, 32, 32, 3, 150) // contrast 10 -> |diff| ~9.7
        assertTrue(BlemishDetect.detect(p, 64, 64, thresholdScale = 1f).isEmpty())
        // scale 0.5 -> band 6..30: the subtle spot is caught.
        assertEquals(1, BlemishDetect.detect(p, 64, 64, thresholdScale = 0.5f).size)
    }

    @Test fun scaleCoercedToHalfAndTwoAndHalf() {
        val p = frame(64, 64, 160)
        disc(p, 64, 32, 32, 3, 130) // contrast 30 -> |diff| ~27
        // 10.0 coerces to 2.5 -> band 30..150 -> spot just misses, same as explicit 2.5.
        assertTrue(BlemishDetect.detect(p, 64, 64, thresholdScale = 10f).isEmpty())
        assertTrue(BlemishDetect.detect(p, 64, 64, thresholdScale = 2.5f).isEmpty())
        val q = frame(64, 64, 160)
        disc(q, 64, 32, 32, 3, 150) // contrast 10 -> |diff| ~9.7
        // 0.1 coerces to 0.5 -> band 6..30 -> caught, same as explicit 0.5.
        assertEquals(
            BlemishDetect.detect(q, 64, 64, thresholdScale = 0.5f).size,
            BlemishDetect.detect(q, 64, 64, thresholdScale = 0.1f).size,
        )
    }

    // ---- non-maximum suppression ------------------------------------------

    @Test fun flatBlobCollapsesToOneSpot() {
        // A r=7 uniform dark blob puts several scan-grid points inside it;
        // NMS must emit exactly ONE heal spot (the old code ate the whole
        // maxSpots budget with overlapping stamps on the same blob).
        val p = frame(64, 64, 160)
        disc(p, 64, 32, 32, 7, 130)
        val spots = BlemishDetect.detect(p, 64, 64)
        assertEquals("spots=$spots", 1, spots.size)
    }

    @Test fun twoSeparatedSpotsBothDetected() {
        val p = frame(64, 64, 160)
        disc(p, 64, 20, 20, 3, 130)
        disc(p, 64, 44, 44, 3, 130) // 34px apart > 12px min separation
        val spots = BlemishDetect.detect(p, 64, 64)
        assertEquals(2, spots.size)
    }

    @Test fun maxSpotsCapsOutput() {
        val p = frame(96, 96, 160)
        for (gy in listOf(16, 48, 80)) for (gx in listOf(16, 48, 80)) {
            disc(p, 96, gx, gy, 3, 130)
        }
        assertEquals(9, BlemishDetect.detect(p, 96, 96, maxSpots = 25).size)
        assertEquals(4, BlemishDetect.detect(p, 96, 96, maxSpots = 4).size)
    }

    // ---- scan margins & determinism ----------------------------------------

    @Test fun minimalFrameScansItsSinglePoint() {
        // 20x20: scan grid has exactly one point (8,8) — the margins contract.
        val p = frame(20, 20, 160)
        disc(p, 20, 8, 8, 2, 130)
        val spots = BlemishDetect.detect(p, 20, 20)
        assertEquals(1, spots.size)
    }

    @Test fun detectionIsDeterministic() {
        val p = frame(64, 64, 160)
        disc(p, 64, 32, 32, 3, 130)
        disc(p, 64, 20, 44, 3, 150)
        val a = BlemishDetect.detect(p, 64, 64)
        val b = BlemishDetect.detect(p, 64, 64)
        assertEquals(a.size, b.size)
        for (i in a.indices) {
            assertEquals(a[i].x, b[i].x)
            assertEquals(a[i].y, b[i].y)
            assertEquals(a[i].radius, b[i].radius)
        }
    }
}
