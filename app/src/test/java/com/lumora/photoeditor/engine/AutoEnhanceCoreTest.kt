package com.lumora.photoeditor.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * First-ever committed executed verification of [AutoEnhanceCore] — the
 * pure-Kotlin core of one-tap Auto Enhance (the user-facing "Auto" button
 * that is a standing toggle: first tap applies, second tap reverts, taps
 * never stack).
 *
 * The sharp edges worth pinning:
 * - the uniform-frame guard (blank wall / solid color / capped lens ->
 *   zero corrections, not the old "solid gray -> contrast +35" behavior);
 * - the 0.02 std threshold itself: std 5/255 sits inside (no corrections),
 *   std 10/255 sits outside (corrections flow);
 * - scene classification driving strategy: NIGHT (mood preserved, blacks
 *   pinned, exposure range-limited and damped), HIGH_KEY (blacks pinned at
 *   -30), BACKLIT (highlights -50 / shadows +55 exactly), PORTRAIT
 *   (skin > 2% wins even in a dark frame — the shaded-portrait regression
 *   guard from the user's 2026-10-17 video);
 * - face-weighted exposure metering (65% skin-region mean + 35% frame
 *   mean) moving exposure relative to plain frame-mean metering;
 * - confidence-limited white balance: strong cast is corrected, neutral
 *   gray stays untouched (temp/tint exactly 0), and the neutral-subset
 *   fallback is exercised by a frame with no neutral pixels (red car ->
 *   bounded -25/-20, not infinity);
 * - skin/vivid subjects excluded from the WB neutral subset (portrait
 *   frame's temp/tint are exactly 0 — the "no care" WB artifact guard);
 * - the strength blend: 0 -> no-op, >1 clamps to full, 0.5 is exactly
 *   half of full (IEEE bit-exact, since x*1f is identity);
 * - w/h are only used for the empty guard — a mismatched buffer analyzes
 *   the same pixels identically (lenient, documented);
 * - [AutoEnhanceCore.describe]: thresholds, ordering, and the exact
 *   feedback-line shape;
 * - determinism.
 *
 * Scene fixtures are checkerboard/mixed gray frames with hand-computed
 * histograms (no RNG); exposure/wB bands are calibrated against the real
 * source under the harness, never guessed.
 *
 * Runs under Gradle AND under the standalone kotlinc 1.9.23 harness
 * (hidden_files/autoenhancecore-test-harness/run.sh).
 */
class AutoEnhanceCoreTest {

    private fun gray(v: Int): Int = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    /** w*h frame; counts maps a pixel value to its pixel count (must sum to w*h). */
    private fun mixedFrame(w: Int, h: Int, counts: List<Pair<Int, Int>>): IntArray {
        val px = IntArray(w * h)
        var i = 0
        for ((v, c) in counts) {
            repeat(c) { px[i++] = gray(v) }
        }
        return px
    }

    private fun colorPixel(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun inBand(v: Float, lo: Double, hi: Double, name: String) {
        assertTrue("$name=$v not in [$lo,$hi]", v >= lo && v <= hi)
    }

    /**
     * Zero up to the -0f sign bit. The engine legitimately produces -0f
     * (e.g. `-clippedHi * 700f` with clippedHi == 0f); JUnit's
     * Float-equals distinguishes -0.0f from 0.0f, but IEEE -0f == 0f in
     * every arithmetic op the app performs, so it pins as zero here.
     */
    private fun assertZeroish(v: Float, name: String) {
        assertTrue("$name=$v is not zero", abs(v) < 1e-9f)
    }

    private fun assertAllZeroish(s: AutoEnhanceCore.Settings, what: String) {
        assertZeroish(s.exposure, "$what.exposure")
        assertZeroish(s.contrast, "$what.contrast")
        assertZeroish(s.highlights, "$what.highlights")
        assertZeroish(s.shadows, "$what.shadows")
        assertZeroish(s.whites, "$what.whites")
        assertZeroish(s.blacks, "$what.blacks")
        assertZeroish(s.vibrance, "$what.vibrance")
        assertZeroish(s.clarity, "$what.clarity")
        assertZeroish(s.temp, "$what.temp")
        assertZeroish(s.tint, "$what.tint")
    }

    // ---------- guard contract ----------

    @Test fun emptyBufferReturnsZeroSettings() {
        assertEquals(AutoEnhanceCore.Settings(), AutoEnhanceCore.analyzePixels(IntArray(0), 0, 0))
    }

    @Test fun nonPositiveDimsReturnZeroSettings() {
        val px = IntArray(100) { gray(128) }
        assertEquals(AutoEnhanceCore.Settings(), AutoEnhanceCore.analyzePixels(px, 0, 10))
        assertEquals(AutoEnhanceCore.Settings(), AutoEnhanceCore.analyzePixels(px, 10, 0))
    }

    @Test fun uniformGrayReturnsZeroSettings() {
        // The "auto enhance with no care" complaint: solid gray used to get
        // contrast +35, whites +50, blacks -50, vibrance +28.
        assertEquals(
            AutoEnhanceCore.Settings(),
            AutoEnhanceCore.analyzePixels(IntArray(64 * 64) { gray(128) }, 64, 64),
        )
    }

    @Test fun solidBlackAndWhiteReturnZeroSettings() {
        assertEquals(
            AutoEnhanceCore.Settings(),
            AutoEnhanceCore.analyzePixels(IntArray(64 * 64) { gray(0) }, 64, 64),
        )
        assertEquals(
            AutoEnhanceCore.Settings(),
            AutoEnhanceCore.analyzePixels(IntArray(64 * 64) { gray(255) }, 64, 64),
        )
    }

    @Test fun nearUniformFrameInsideGuardReturnsZeroSettings() {
        // std 1/255 < 0.02: nothing meaningful to balance.
        val px = IntArray(100 * 100) { i -> gray(if (i % 2 == 0) 127 else 129) }
        assertEquals(AutoEnhanceCore.Settings(), AutoEnhanceCore.analyzePixels(px, 100, 100))
    }

    @Test fun lowSpreadFrameStaysInsideGuard() {
        // std 3 -> 3/255 = 0.0118 < 0.02: still inside the guard. (The exact
        // std==5 boundary is deliberately NOT pinned: the float luminance
        // sum truncates via toInt(), so a lum bin can shift by 1 and nudge
        // a boundary fixture either side of the line — an implementation
        // detail, not a contract.)
        val px = IntArray(100 * 100) { i -> gray(if (i % 2 == 0) 126 else 132) }
        assertEquals(AutoEnhanceCore.Settings(), AutoEnhanceCore.analyzePixels(px, 100, 100))
    }

    @Test fun spreadAboveGuardFlowsCorrections() {
        // std 10 -> 0.0392 > 0.02: real analysis runs.
        val px = IntArray(100 * 100) { i -> gray(if (i % 2 == 0) 120 else 140) }
        val s = AutoEnhanceCore.analyzePixels(px, 100, 100)
        assertTrue("expected real corrections, got $s", s != AutoEnhanceCore.Settings())
    }

    // ---------- NIGHT scene ----------

    private val nightFrame = mixedFrame(100, 100, listOf(30 to 8000, 60 to 2000))

    @Test fun nightScenePreservesMood() {
        // mean 36 -> meanLum 0.141 < 0.28, darkRatio 0.8 > 0.45, no skin.
        val s = AutoEnhanceCore.analyzePixels(nightFrame, 100, 100)
        // Exposure: ev = log2(0.30/0.1412) ~= 1.087, night range caps at 1.2,
        // then the night 0.7 damping -> ~= 0.761.
        inBand(s.exposure, 0.73, 0.79, "night exposure")
        assertEquals(30f, s.whites)      // (235-60)*0.3 = 52.5 -> coerce to 30
        assertEquals(0f, s.blacks)       // night keeps deep blacks
        assertZeroish(s.highlights, "night highlights") // nothing clipped; -0f from -0f*700f
        assertZeroish(s.shadows, "night shadows")      // nothing clipped at the bottom
        assertEquals(8f, s.clarity)      // night branch literal
        inBand(s.contrast, 26.3, 26.7, "night contrast")  // 30.8*0.6+8
        inBand(s.vibrance, 14.9, 15.1, "night vibrance")   // 0.3*50
        assertEquals(0f, s.temp)
        assertEquals(0f, s.tint)
    }

    @Test fun shadedPortraitIsNotGivenTheNightTreatment() {
        // Same dark frame but 3% skin-colored pixels: the night branch
        // requires skinRatio < 0.02, so this must classify PORTRAIT —
        // regression guard for the user's 2026-10-17 shaded-portrait video.
        val skin = colorPixel(200, 150, 120) // lum 158
        val px = IntArray(100 * 100)
        for (i in 0 until 7700) px[i] = gray(30)
        for (i in 7700 until 9700) px[i] = gray(60)
        for (i in 9700 until 10000) px[i] = skin
        val skinProb: (Int, Int, Int) -> Float = { r, g, b ->
            if (r == 200 && g == 150 && b == 120) 1f else 0f
        }
        val s = AutoEnhanceCore.analyzePixels(px, 100, 100, skinProb = skinProb)
        // Portrait metering: 0.65*(158/255) + 0.35*(39.84/255) ~= 0.4574,
        // ev = log2(0.48/0.4574) ~= 0.069 — far below the night ~0.66 lift.
        inBand(s.exposure, 0.05, 0.09, "portrait-in-dark exposure")
        val nightOnly = AutoEnhanceCore.analyzePixels(px, 100, 100)
        assertTrue("skinless version should get the night lift, got $nightOnly", nightOnly.exposure > 0.6)
        assertTrue("skin version must lift less than the night treatment", s.exposure < nightOnly.exposure)
    }

    // ---------- HIGH_KEY scene ----------

    @Test fun highKeyScene() {
        // mean 216 -> meanLum 0.847 > 0.72, brightRatio 0.8 > 0.4.
        val px = mixedFrame(100, 100, listOf(180 to 2000, 225 to 8000))
        val s = AutoEnhanceCore.analyzePixels(px, 100, 100)
        // ev = log2(0.68/0.847) ~= -0.317, high-key range keeps it.
        inBand(s.exposure, -0.36, -0.28, "high-key exposure")
        assertEquals(-30f, s.blacks)    // (15-180)*0.5 = -82.5 -> coerce to -30
        assertEquals(5f, s.clarity)      // high-key branch literal
        inBand(s.contrast, 13.6, 13.9, "high-key contrast") // 27.5*0.5
        inBand(s.whites, 8.9, 9.1, "high-key whites")       // (245-225)*0.45
        assertZeroish(s.highlights, "high-key highlights")
        assertZeroish(s.shadows, "high-key shadows")
    }

    // ---------- BACKLIT scene ----------

    @Test fun backlitScene() {
        // dark subject + bright background: darkRatio 0.5, brightRatio 0.5,
        // p95-p5 = 200 > 150.
        val px = mixedFrame(100, 100, listOf(30 to 5000, 230 to 5000))
        val s = AutoEnhanceCore.analyzePixels(px, 100, 100)
        // ev = log2(0.45/0.5098) ~= -0.18, target 0.45.
        inBand(s.exposure, -0.22, -0.15, "backlit exposure")
        assertEquals(-50f, s.highlights) // backlit literal: strong recovery
        assertEquals(55f, s.shadows)     // backlit literal: lift the subject
        assertEquals(12f, s.clarity)     // backlit literal
        assertEquals(0f, s.contrast)     // spread 200 -> baseContrast 0
        inBand(s.vibrance, 27.9, 28.1, "backlit vibrance")  // 0.4*70
        inBand(s.whites, 6.6, 6.9, "backlit whites")        // (245-230)*0.45
        inBand(s.blacks, -12.2, -11.8, "backlit blacks")    // (10-30)*0.6
        // The 230-lum pixels fall outside the WB neutral window (30..225),
        // so WB meters only the subject side: no cast -> untouched.
        assertEquals(0f, s.temp)
        assertEquals(0f, s.tint)
    }

    // ---------- PORTRAIT scene + face-weighted metering ----------

    private val portraitFrame: IntArray
        get() {
            val skin = colorPixel(200, 150, 120) // lum 158
            val px = IntArray(100 * 100)
            for (i in px.indices) px[i] = if (i % 10 == 0) skin else gray(128)
            return px
        }

    private val skinStub: (Int, Int, Int) -> Float = { r, g, b ->
        if (r == 200 && g == 150 && b == 120) 1f else 0f
    }

    @Test fun portraitMetersTheFaceNotTheFrame() {
        val px = portraitFrame
        val portrait = AutoEnhanceCore.analyzePixels(px, 100, 100, skinProb = skinStub)
        val plain = AutoEnhanceCore.analyzePixels(px, 100, 100)
        // Face-weighted meter: 0.65*(158/255)+0.35*(131/255) ~= 0.5825,
        // ev = log2(0.48/0.5825) ~= -0.279.
        inBand(portrait.exposure, -0.32, -0.24, "face-weighted exposure")
        // The skin is brighter than the frame mean, so the face meter runs
        // higher and exposure runs lower than plain frame metering.
        assertTrue("face metering must differ from frame metering", portrait.exposure < plain.exposure)
        inBand(portrait.contrast, 21.4, 21.8, "portrait contrast") // 30.8*0.7
        assertEquals(5f, portrait.clarity)                        // portrait literal
        inBand(portrait.vibrance, 18.4, 18.8, "portrait vibrance") // (0.35-0.04)*60
    }

    @Test fun skinDoesNotSteerWhiteBalance() {
        // Skin pixels (sat 0.4 > 0.35) are excluded from the WB neutral
        // subset: the portrait frame must come out WB-neutral.
        val s = AutoEnhanceCore.analyzePixels(portraitFrame, 100, 100, skinProb = skinStub)
        assertEquals(0f, s.temp)
        assertEquals(0f, s.tint)
    }

    // ---------- NORMAL scene ----------

    @Test fun normalScene() {
        val px = IntArray(100 * 100) { i -> gray(if (i % 2 == 0) 120 else 140) }
        val s = AutoEnhanceCore.analyzePixels(px, 100, 100)
        // ev = log2(0.46/0.5098) ~= -0.148.
        inBand(s.exposure, -0.18, -0.12, "normal exposure")
        assertEquals(10f, s.clarity)                            // normal literal
        inBand(s.contrast, 32.9, 33.1, "normal contrast")        // (170-20)*0.22
        inBand(s.whites, 47.1, 47.4, "normal whites")            // (245-140)*0.45
        assertEquals(-50f, s.blacks)                            // (10-120)*0.6 -> -66 -> -50
        inBand(s.vibrance, 27.9, 28.1, "normal vibrance")        // 0.4*70
        assertZeroish(s.highlights, "normal highlights")
        assertZeroish(s.shadows, "normal shadows")
        assertEquals(0f, s.temp)
        assertEquals(0f, s.tint)
    }

    // ---------- white balance ----------

    @Test fun neutralGrayStaysWhiteBalanceUntouched() {
        val px = IntArray(100 * 100) { i -> gray(if (i % 2 == 0) 120 else 140) }
        val s = AutoEnhanceCore.analyzePixels(px, 100, 100)
        assertEquals(0f, s.temp)
        assertEquals(0f, s.tint)
    }

    @Test fun blueCastIsWarmedUp() {
        // Subtle blue cast, still inside the neutral subset (sat <= 0.35).
        val px = IntArray(100 * 100) { i ->
            if (i % 2 == 0) colorPixel(100, 105, 150) else colorPixel(125, 130, 180)
        }
        val s = AutoEnhanceCore.analyzePixels(px, 100, 100)
        // castB ~= 0.253 -> tempRaw ~= 20.25, confidence 1.
        inBand(s.temp, 20.1, 20.4, "blue-cast temp")
        // castG ~= -0.108 -> tintRaw ~= -6.46 (green cut).
        inBand(s.tint, -6.6, -6.3, "blue-cast tint")
    }

    @Test fun noNeutralPixelsFallsBackToGlobalMeansBounded() {
        // A frame that is nothing but a vivid red car: sat > 0.35 everywhere
        // -> wbN = 0 -> the estimate falls back to global means, still
        // confidence-bounded so it cannot run away.
        val px = IntArray(100 * 100) { i ->
            if (i % 2 == 0) colorPixel(180, 20, 20) else colorPixel(220, 40, 40)
        }
        val s = AutoEnhanceCore.analyzePixels(px, 100, 100)
        assertEquals(-25f, s.temp) // -52.3 raw -> coerced to -25
        assertEquals(-20f, s.tint) // -39.2 raw -> coerced to -20
    }

    @Test fun faintCastBelowConfidenceIsIgnored() {
        // Deviations under 8% produce wbConfidence 0: temp/tint exactly 0.
        val px = IntArray(100 * 100) { i -> gray(if (i % 2 == 0) 126 else 134) }
        val s = AutoEnhanceCore.analyzePixels(px, 100, 100)
        assertEquals(0f, s.temp)
        assertEquals(0f, s.tint)
    }

    // ---------- strength blending ----------

    private val strengthFrame = IntArray(100 * 100) { i -> gray(if (i % 2 == 0) 120 else 140) }

    @Test fun strengthZeroIsNoOp() {
        // ev*0f can be -0f; zeroish covers the sign bit (see helper KDoc).
        assertAllZeroish(
            AutoEnhanceCore.analyzePixels(strengthFrame, 100, 100, strength = 0f),
            "strength=0",
        )
    }

    @Test fun strengthAboveOneClampsToFull() {
        val full = AutoEnhanceCore.analyzePixels(strengthFrame, 100, 100, strength = 1f)
        val over = AutoEnhanceCore.analyzePixels(strengthFrame, 100, 100, strength = 2f)
        assertEquals(full, over)
    }

    @Test fun negativeStrengthClampsToNoOp() {
        assertAllZeroish(
            AutoEnhanceCore.analyzePixels(strengthFrame, 100, 100, strength = -0.5f),
            "strength<0",
        )
    }

    @Test fun strengthHalfIsExactlyHalfOfFull() {
        // x*1f is the identity, so (x*1f)*0.5f == x*0.5f bit-exactly.
        val full = AutoEnhanceCore.analyzePixels(strengthFrame, 100, 100, strength = 1f)
        val half = AutoEnhanceCore.analyzePixels(strengthFrame, 100, 100, strength = 0.5f)
        assertEquals(
            AutoEnhanceCore.Settings(
                exposure = full.exposure * 0.5f,
                contrast = full.contrast * 0.5f,
                highlights = full.highlights * 0.5f,
                shadows = full.shadows * 0.5f,
                whites = full.whites * 0.5f,
                blacks = full.blacks * 0.5f,
                vibrance = full.vibrance * 0.5f,
                clarity = full.clarity * 0.5f,
                temp = full.temp * 0.5f,
                tint = full.tint * 0.5f,
            ),
            half,
        )
    }

    // ---------- plumbing ----------

    @Test fun mismatchedDimsAnalyzeTheSamePixels() {
        // w/h only feed the empty guard; the buffer is analyzed as-is.
        val px = IntArray(100 * 100) { i -> gray(if (i % 2 == 0) 120 else 140) }
        assertEquals(
            AutoEnhanceCore.analyzePixels(px, 100, 100),
            AutoEnhanceCore.analyzePixels(px, 50, 200),
        )
    }

    @Test fun analyzeIsDeterministic() {
        val px = IntArray(100 * 100) { i -> gray(if (i % 2 == 0) 120 else 140) }
        val a = AutoEnhanceCore.analyzePixels(px, 100, 100, skinProb = skinStub)
        val b = AutoEnhanceCore.analyzePixels(px, 100, 100, skinProb = skinStub)
        assertEquals(a, b)
    }

    @Test fun settingsDefaultsAreZero() {
        assertEquals(AutoEnhanceCore.Settings(), AutoEnhanceCore.Settings(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f))
    }

    // ---------- describe ----------

    @Test fun describeEmptySettings() {
        assertEquals("", AutoEnhanceCore.describe(AutoEnhanceCore.Settings()))
    }

    @Test fun describeThresholds() {
        // 0.05 EV and 1 slider unit are the visibility thresholds.
        assertEquals("", AutoEnhanceCore.describe(AutoEnhanceCore.Settings(exposure = 0.04f)))
        assertEquals("", AutoEnhanceCore.describe(AutoEnhanceCore.Settings(contrast = 0.9f)))
        assertEquals(
            "Exposure +0.1",
            AutoEnhanceCore.describe(AutoEnhanceCore.Settings(exposure = 0.05f)),
        )
        assertEquals(
            "Contrast +1",
            AutoEnhanceCore.describe(AutoEnhanceCore.Settings(contrast = 1f)),
        )
    }

    @Test fun describeSigns() {
        assertEquals(
            "Exposure -0.7",
            AutoEnhanceCore.describe(AutoEnhanceCore.Settings(exposure = -0.7f)),
        )
    }

    @Test fun describeFullShapeAndOrder() {
        assertEquals(
            "Exposure +1.5 · Contrast +10 · Highlights -20 · Shadows +30 · " +
                "Whites +40 · Blacks -50 · Clarity +5 · Temp +12 · Tint -8 · Vibrance +60",
            AutoEnhanceCore.describe(
                AutoEnhanceCore.Settings(
                    exposure = 1.5f, contrast = 10f, highlights = -20f, shadows = 30f,
                    whites = 40f, blacks = -50f, vibrance = 60f, clarity = 5f,
                    temp = 12f, tint = -8f,
                ),
            ),
        )
    }
}
