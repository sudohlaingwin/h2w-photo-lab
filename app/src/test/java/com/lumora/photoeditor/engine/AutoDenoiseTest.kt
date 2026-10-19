package com.lumora.photoeditor.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs

/**
 * First-ever committed executed verification of [AutoDenoise] — the
 * deterministic classical++ noise-floor estimator behind the Detail panel's
 * one-tap Auto Denoise (Phase A#3 "AI denoise" ships this; 2026-10-18 check
 * found no commercial-licensed denoise weights, so this is the shipped path).
 *
 * Method recap: for each interior pixel the local luma gradient is measured;
 * pixels at or below the 20th gradient percentile are "flat enough"; on those,
 * |pixel - local 3x3 mean| is collected for luminance and for chroma
 * (r-luma / b-luma, worst of the two), and MAD x1.4826 is the noise sigma.
 * Sigmas map conservatively to Detail NR slider units: luma starts at sigma
 * ~2, chroma (safer) at ~1.5, capped 60/70 — auto never smears on its own.
 *
 * The sharp edges worth pinning:
 * - the honest-null contract (clean, too-small, too-few-flat-pixels all
 *   return null, so a tap on a clean photo touches nothing);
 * - the 3x3-local-mean design that tracks slow gradients (a smooth ramp has
 *   zero residual, so skies/vignette don't read as noise);
 * - the 20th-percentile gate excluding sharp edges from the noise population
 *   (a hard edge must not inflate the sigma estimate to the caps);
 * - the exact mapToSettings formula, floors, caps, rounding, and the
 *   double-null-guard shape (clean threshold first, mapped-zero second);
 * - scaleComp coercion (values < 1 mean "no compensation", not attenuation)
 *   and its multiplicative effect;
 * - determinism (same pixels -> identical Settings, data-class equality).
 *
 * Noise fixtures use java.util.Random with fixed seeds; the MAD estimator's
 * ~5% sampling variance is absorbed in bands calibrated against the real
 * source under the harness, never in bit-exact pixel assertions.
 *
 * Runs under Gradle AND under the standalone kotlinc 1.9.23 harness
 * (hidden_files/autodenoise-test-harness/run.sh).
 */
class AutoDenoiseTest {

    private val GRAY128 = 0xFF808080.toInt()

    private fun frame(w: Int, h: Int, pixel: (x: Int, y: Int) -> Int): IntArray =
        IntArray(w * h) { i -> pixel(i % w, i / w) }

    /** Gaussian noise fixture; lumaOnly puts the noise on all channels, else on R only. */
    private fun noisyFlat(
        w: Int, h: Int, sigma: Float, seed: Long, lumaOnly: Boolean,
    ): IntArray {
        val rnd = Random(seed)
        return frame(w, h) { _, _ ->
            val n = (rnd.nextGaussian() * sigma).toInt()
            val base = 128
            val r = if (lumaOnly) base + n else base + n
            val g = if (lumaOnly) base + n else base
            val b = if (lumaOnly) base + n else base
            (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or
                    (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
        }
    }

    /** Smooth horizontal ramp: constant gradient, so the 3x3 local mean tracks it exactly. */
    private fun smoothRamp(w: Int, h: Int): IntArray =
        frame(w, h) { x, _ ->
            val v = (x * 255 / (w - 1)).coerceIn(0, 255)
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }

    // ---------- analyzePixels: guard contract ----------

    @Test fun analyzeEmptyReturnsNull() {
        assertNull(AutoDenoise.analyzePixels(IntArray(0), 0, 0))
    }

    @Test fun analyzeTooNarrowReturnsNull() {
        assertNull(AutoDenoise.analyzePixels(IntArray(7 * 64) { GRAY128 }, 7, 64))
    }

    @Test fun analyzeTooShortReturnsNull() {
        assertNull(AutoDenoise.analyzePixels(IntArray(64 * 7) { GRAY128 }, 64, 7))
    }

    @Test fun analyzeMismatchedSizeReturnsNull() {
        assertNull(AutoDenoise.analyzePixels(IntArray(100) { GRAY128 }, 8, 8))
    }

    // ---------- analyzePixels: honest-null contract ----------

    @Test fun uniformFrameReturnsNull() {
        assertNull(AutoDenoise.analyzePixels(IntArray(64 * 64) { GRAY128 }, 64, 64))
    }

    @Test fun smoothRampReturnsNull() {
        // The documented design point: the local 3x3 mean tracks slow
        // brightness gradients, so a ramp leaves no residual and reads clean.
        assertNull(AutoDenoise.analyzePixels(smoothRamp(32, 32), 32, 32))
    }

    @Test fun tinyNoisyFrameReturnsNull() {
        // 16x16 -> 14x14 interior = 196 gradients, ~20% flat = ~40 samples
        // < MIN_SAMPLES(64): the estimate can't be trusted, so null.
        assertNull(AutoDenoise.analyzePixels(noisyFlat(16, 16, 10f, 7L, true), 16, 16))
    }

    @Test fun lowNoiseFrameReturnsNull() {
        // sigma 1.0 -> estimated sigma ~0.94 < CLEAN_SIGMA(1.2): nothing touched.
        assertNull(AutoDenoise.analyzePixels(noisyFlat(64, 64, 1.0f, 11L, true), 64, 64))
    }

    // ---------- analyzePixels: noisy frames ----------

    @Test fun lumaNoiseSuggestsLuminanceNr() {
        // sigma 10 -> estimated sigma ~9.4 (half-normal MAD on the 8/9-variance
        // residual) -> lumNr (9.4-2)/18*100 ~= 41. Band absorbs estimator noise.
        val s = AutoDenoise.analyzePixels(noisyFlat(64, 64, 10f, 42L, true), 64, 64)
        assertTrue("expected non-null, got null", s != null)
        s!!
        assertTrue("luminanceNr ${s.luminanceNr} out of band", s.luminanceNr in 32f..50f)
        assertTrue("colorNr ${s.colorNr} should stay small", s.colorNr <= 12f)
    }

    @Test fun chromaOnlyNoiseSuggestsColorNr() {
        // R-only noise: cr = r - y moves by 0.7874n -> colorNr ~= 42;
        // luma moves only by 0.2126n -> lumNr rounds to ~0.
        val s = AutoDenoise.analyzePixels(noisyFlat(64, 64, 10f, 43L, false), 64, 64)
        assertTrue("expected non-null, got null", s != null)
        s!!
        assertTrue("colorNr ${s.colorNr} out of band", s.colorNr in 30f..55f)
        assertTrue("luminanceNr ${s.luminanceNr} should stay small", s.luminanceNr <= 15f)
    }

    @Test fun sharpEdgeDoesNotInflateEstimate() {
        // Hard 100->200 step down the middle with mild sigma-3 noise: the
        // 20th-percentile gate must exclude the edge from the noise
        // population. Without exclusion the sigma would blow out to the caps.
        val rnd = Random(99L)
        val px = frame(64, 64) { x, _ ->
            val n = (rnd.nextGaussian() * 3f).toInt()
            val v = (if (x < 32) 100 else 200) + n
            val c = v.coerceIn(0, 255)
            (0xFF shl 24) or (c shl 16) or (c shl 8) or c
        }
        val s = AutoDenoise.analyzePixels(px, 64, 64)
        assertTrue("expected non-null, got null", s != null)
        s!!
        assertTrue("edge inflated lumNr to ${s.luminanceNr}", s.luminanceNr in 1f..12f)
        assertTrue("lumNr ${s.luminanceNr} hit the cap: edge leaked in", s.luminanceNr < 60f)
    }

    // ---------- analyzePixels: scaleComp ----------

    @Test fun scaleCompBelowOneCoercesToOne() {
        val px = noisyFlat(64, 64, 8f, 5L, true)
        val a = AutoDenoise.analyzePixels(px, 64, 64, 0.5f)
        val b = AutoDenoise.analyzePixels(px, 64, 64, 1f)
        assertTrue("expected non-null", a != null && b != null)
        assertEquals(b, a) // max(1f, scaleComp): 0.5 must behave exactly like 1.0
    }

    @Test fun scaleCompScalesTheEstimate() {
        // Same pixels, comp=2 doubles the sigmas -> strictly higher NR.
        val px = noisyFlat(64, 64, 5f, 6L, true)
        val a = AutoDenoise.analyzePixels(px, 64, 64, 1f)
        val b = AutoDenoise.analyzePixels(px, 64, 64, 2f)
        assertTrue("expected non-null", a != null && b != null)
        assertTrue(
            "comp=2 lumNr ${b!!.luminanceNr} not above comp=1 ${a!!.luminanceNr}",
            b.luminanceNr > a.luminanceNr,
        )
    }

    @Test fun analyzeIsDeterministic() {
        val px = noisyFlat(48, 48, 7f, 8L, true)
        val a = AutoDenoise.analyzePixels(px, 48, 48)
        val b = AutoDenoise.analyzePixels(px, 48, 48)
        assertEquals(a, b)
    }

    // ---------- mapToSettings: the exact mapping ----------

    @Test fun mapCleanSigmasReturnNull() {
        assertNull(AutoDenoise.mapToSettings(1.0f, 1.0f))
        assertNull(AutoDenoise.mapToSettings(1.19f, 1.19f))
        assertNull(AutoDenoise.mapToSettings(0f, 0f))
        assertNull(AutoDenoise.mapToSettings(-3f, -3f))
    }

    @Test fun mapAtCleanBoundaryReturnsNull() {
        // sigma exactly 1.2 passes the clean gate but maps to 0/0, and the
        // second guard (both < 0.5) returns null: the double-null shape.
        assertNull(AutoDenoise.mapToSettings(1.2f, 0f))
    }

    @Test fun mapFloorBehavior() {
        // sigma 2.0 is exactly LUM_FLOOR -> maps to 0 -> null (no luma NR).
        assertNull(AutoDenoise.mapToSettings(2.0f, 0f))
        // sigma 3.0 -> (3-2)/18*100 = 5.55 -> 6; chroma (3-1.5)/14*100 = 10.7 -> 11.
        assertEquals(AutoDenoise.Settings(6f, 11f), AutoDenoise.mapToSettings(3f, 3f))
    }

    @Test fun mapCaps() {
        // Auto must never smear on its own: 200 sigma hits the 60/70 caps.
        assertEquals(AutoDenoise.Settings(60f, 70f), AutoDenoise.mapToSettings(200f, 200f))
    }

    @Test fun mapRoundingHalfUp() {
        // sigma 2.99 -> (0.99)/18*100 = 5.5 -> Kotlin roundToInt (half up) -> 6.
        val s = AutoDenoise.mapToSettings(2.99f, 0f)
        assertTrue("expected non-null", s != null)
        assertEquals(6f, s!!.luminanceNr, 0f)
        assertEquals(0f, s.colorNr, 0f)
    }

    @Test fun mapIsMonotone() {
        val low = AutoDenoise.mapToSettings(10f, 10f)!!
        val high = AutoDenoise.mapToSettings(20f, 20f)!!
        assertTrue(low.luminanceNr < high.luminanceNr)
        assertTrue(low.colorNr < high.colorNr)
    }

    @Test fun mapChromaFloorLowerThanLuma() {
        // Same sigma just above 1.5: chroma responds, luma (floor 2.0) stays 0.
        val s = AutoDenoise.mapToSettings(1.8f, 1.8f)
        assertTrue("expected non-null", s != null)
        assertEquals(0f, s!!.luminanceNr, 0f)
        assertTrue("colorNr ${s.colorNr} should respond", s.colorNr > 0.5f)
    }

    // ---------- describe ----------

    @Test fun describeBoth() {
        assertEquals(
            "Luminance 44 · Color 42",
            AutoDenoise.describe(AutoDenoise.Settings(44f, 42f)),
        )
    }

    @Test fun describeLuminanceOnly() {
        assertEquals("Luminance 44", AutoDenoise.describe(AutoDenoise.Settings(44f, 0f)))
    }

    @Test fun describeColorOnly() {
        assertEquals("Color 42", AutoDenoise.describe(AutoDenoise.Settings(0f, 42f)))
    }

    @Test fun describeEmpty() {
        assertEquals("", AutoDenoise.describe(AutoDenoise.Settings(0f, 0f)))
        assertEquals("", AutoDenoise.describe(AutoDenoise.Settings(0.4f, 0.4f)))
    }

    // ---------- plumbing ----------

    @Test fun settingsDefaultsAreZero() {
        assertEquals(AutoDenoise.Settings(0f, 0f), AutoDenoise.Settings())
    }
}
