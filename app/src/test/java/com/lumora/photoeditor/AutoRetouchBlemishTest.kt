package com.lumora.photoeditor

import com.lumora.photoeditor.engine.BlemishDetect
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Regression tests for [BlemishDetect] (Auto Retouch blemish detection).
 *
 * Guards two real quality bugs:
 *  1. Only dark spots were detected — bright blemishes (whiteheads, bright
 *     dust specks, shiny highlights) were silently ignored by Auto Retouch.
 *  2. A single flat-bottomed blob emitted one candidate per scan-grid
 *     point and ate the whole maxSpots budget with overlapping heal stamps
 *     on the same blob (no non-maximum suppression).
 */
class AutoRetouchBlemishTest {

    private val w = 128
    private val h = 128

    /** Uniform background with hard-edged discs: (cxFrac, cyFrac, value). */
    private fun image(
        bg: Int = 170,
        spots: List<Triple<Float, Float, Int>> = emptyList(),
        spotR: Float = 6f,
    ): IntArray = IntArray(w * h) { i ->
        val x = (i % w).toFloat(); val y = (i / w).toFloat()
        var v = bg
        for ((fx, fy, sv) in spots) {
            val dx = x - fx * w; val dy = y - fy * h
            if (sqrt(dx * dx + dy * dy) <= spotR) v = sv
        }
        (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }

    @Test fun `detects dark spot`() {
        val found = BlemishDetect.detect(
            image(spots = listOf(Triple(0.5f, 0.5f, 125))), w, h)
        assertTrue("dark spot not detected", found.isNotEmpty())
        val b = found.first()
        assertTrue("x=${b.x} far from 0.5", abs(b.x - 0.5f) < 0.06f)
        assertTrue("y=${b.y} far from 0.5", abs(b.y - 0.5f) < 0.06f)
        // The bg blur (r=8) partially absorbs the 6px blob, so the measured
        // score is ~28 rather than the nominal 45: r ~= 0.019. Same physics
        // as the old code (formula unchanged) — just assert a sane radius.
        assertTrue("radius=${b.radius} unexpected", b.radius in 0.015f..0.025f)
    }

    @Test fun `detects bright spot`() {
        val found = BlemishDetect.detect(
            image(spots = listOf(Triple(0.5f, 0.5f, 215))), w, h)
        assertTrue("bright spot not detected", found.isNotEmpty())
        val b = found.first()
        assertTrue("x=${b.x} far from 0.5", abs(b.x - 0.5f) < 0.06f)
        assertTrue("y=${b.y} far from 0.5", abs(b.y - 0.5f) < 0.06f)
    }

    @Test fun `uniform image yields nothing`() {
        assertTrue(BlemishDetect.detect(image(), w, h).isEmpty())
    }

    @Test fun `flat blob yields exactly one spot`() {
        // Hard-edged flat disc: every interior scan-grid point passes the
        // local-extremum test, so without NMS this emits many candidates.
        val found = BlemishDetect.detect(
            image(spots = listOf(Triple(0.5f, 0.5f, 125))), w, h, maxSpots = 20)
        assertEquals("one blob should give one heal spot, got ${found.size}", 1, found.size)
    }

    @Test fun `two separate spots of opposite polarity both detected`() {
        val found = BlemishDetect.detect(
            image(spots = listOf(
                Triple(0.3f, 0.5f, 125),  // dark
                Triple(0.7f, 0.5f, 215),  // bright
            ), spotR = 5f),
            w, h,
        )
        assertEquals("expected 2 spots, got ${found.size}", 2, found.size)
    }

    @Test fun `degenerate input yields nothing`() {
        assertTrue(BlemishDetect.detect(IntArray(0), 0, 0).isEmpty())
        assertTrue(BlemishDetect.detect(image(), w, h, maxSpots = 0).isEmpty())
        assertTrue(BlemishDetect.detect(IntArray(w * h), 4, 4).isEmpty())
    }
}
