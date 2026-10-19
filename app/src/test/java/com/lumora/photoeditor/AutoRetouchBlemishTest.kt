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

    @Test fun `smooth luminance ramp yields nothing`() {
        // A linear brightness ramp has no local deviation from its own
        // background estimate: a box blur of a ramp is the ramp (edge
        // replication only distorts within the 8px scan margin). Auto
        // Retouch must never heal-stamp a smoothly shaded cheek or sky.
        val ramp = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            val v = 100 + (x + y) * 100 / (2 * (w - 1))
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        assertTrue(
            "ramp misdetected as ${BlemishDetect.detect(ramp, w, h).size} blemishes",
            BlemishDetect.detect(ramp, w, h).isEmpty())
    }

    @Test fun `spot fully outside the scan margin is ignored`() {
        // The scan grid starts at 8px from the edge (the background blur's
        // radius). A 2.5px spot centered at x=2 never touches a scan point,
        // so it must not be reported.
        val found = BlemishDetect.detect(
            image(spots = listOf(Triple(2f / w, 0.5f, 125)), spotR = 2.5f), w, h)
        assertTrue("edge spot should be ignored, got $found", found.isEmpty())
    }

    @Test fun `elongated blob is tiled by separated spots, never stacked`() {
        // Two overlapping discs form one 14px-wide dumbbell blob. The NMS
        // keeps accepted spots at least MIN_SEPARATION_PX (12px) apart, so
        // the blob gets tiled by non-overlapping heal stamps instead of a
        // stack of overlapping ones on the same pixels — the exact
        // budget-eating bug the flat-blob test guards against.
        val found = BlemishDetect.detect(
            image(spots = listOf(
                Triple(0.5f, 0.5f, 125),
                Triple(0.55f, 0.5f, 125),
            ), spotR = 4f),
            w, h, maxSpots = 20,
        )
        assertEquals("dumbbell tiling changed: $found", 2, found.size)
        for (a in found) for (b in found) {
            if (a === b) continue
            val dx = (a.x - b.x) * w
            val dy = (a.y - b.y) * h
            val dist = sqrt(dx * dx + dy * dy)
            assertTrue("accepted spots overlap (dist=$dist < 12px)", dist >= 12f - 1e-3f)
        }
    }

    @Test fun `maxSpots caps the result`() {
        val spots = (0 until 4).flatMap { cx ->
            (0 until 5).map { cy ->
                Triple((cx + 0.5f) / 4, (cy + 0.5f) / 5, 125)
            }
        }
        val found = BlemishDetect.detect(image(spots = spots, spotR = 4f), w, h, maxSpots = 8)
        assertEquals("expected exactly 8 spots, got ${found.size}", 8, found.size)
    }

    @Test fun `sensitivity is monotonic in thresholdScale`() {
        // thresholdScale is RetouchIntel's adaptive strictness knob: a lower
        // scale must never detect fewer spots than a higher one. Nominal
        // diffs 14 / 25 / 45 in both polarities span the default 12..60 band
        // so at least one strength flips across scales.
        val spots = listOf(14, 25, 45).flatMap { d ->
            listOf(
                Triple(0.25f * (d / 14f), 0.3f, 170 - d),
                Triple(0.25f * (d / 14f), 0.7f, 170 + d),
            )
        }
        val imageSpots = image(spots = spots, spotR = 6f)
        val lax = BlemishDetect.detect(imageSpots, w, h, thresholdScale = 0.5f)
        val normal = BlemishDetect.detect(imageSpots, w, h, thresholdScale = 1f)
        val strict = BlemishDetect.detect(imageSpots, w, h, thresholdScale = 2.5f)
        assertTrue("lax(${lax.size}) < normal(${normal.size})", lax.size >= normal.size)
        assertTrue("normal(${normal.size}) < strict(${strict.size})", normal.size >= strict.size)
        assertTrue("scale had no effect: $lax", lax.size > strict.size)
    }
}
