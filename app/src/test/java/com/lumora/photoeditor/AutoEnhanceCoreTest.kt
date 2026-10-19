package com.lumora.photoeditor

import com.lumora.photoeditor.engine.AutoEnhanceCore
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class AutoEnhanceCoreTest {

    private fun gray(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    private fun assertZero(s: AutoEnhanceCore.Settings, what: String) {
        listOf(
            "exposure" to s.exposure, "contrast" to s.contrast,
            "highlights" to s.highlights, "shadows" to s.shadows,
            "whites" to s.whites, "blacks" to s.blacks,
            "vibrance" to s.vibrance, "clarity" to s.clarity,
            "temp" to s.temp, "tint" to s.tint,
        ).forEach { (name, v) ->
            assertTrue("$what: $name expected 0 but was $v", abs(v) < 1e-4f)
        }
    }

    @Test fun solidGray_returnsNoCorrections() {
        assertZero(
            AutoEnhanceCore.analyzePixels(IntArray(256 * 256) { gray(128) }, 256, 256),
            "solid gray")
    }

    @Test fun solidBlack_returnsNoCorrections() {
        assertZero(
            AutoEnhanceCore.analyzePixels(IntArray(64 * 64) { gray(0) }, 64, 64),
            "solid black")
    }

    @Test fun solidWhite_returnsNoCorrections() {
        assertZero(
            AutoEnhanceCore.analyzePixels(IntArray(64 * 64) { gray(255) }, 64, 64),
            "solid white")
    }

    @Test fun emptyInput_returnsNoCorrections() {
        assertZero(AutoEnhanceCore.analyzePixels(IntArray(0), 0, 0), "empty")
    }

    @Test fun realPhoto_returnsBoundedCorrections() {
        val rnd = java.util.Random(3)
        val photo = IntArray(200 * 200) { i ->
            val x = i % 200
            gray((x * 0.8f + rnd.nextFloat() * 40).toInt().coerceIn(0, 255))
        }
        val s = AutoEnhanceCore.analyzePixels(photo, 200, 200)
        assertTrue("exposure in range", s.exposure in -2.5f..2.5f)
        assertTrue("contrast in range", s.contrast in 0f..35f)
        assertTrue("whites in range", s.whites in -50f..50f)
        assertTrue("blacks in range", s.blacks in -50f..50f)
        assertTrue("vibrance in range", s.vibrance in 0f..40f)
        assertTrue("clarity in range", s.clarity in 0f..12f)
        assertTrue("non-trivial", s.contrast > 0f || s.whites != 0f || s.exposure != 0f)
    }

    @Test fun strengthZero_returnsNoCorrections() {
        val rnd = java.util.Random(3)
        val photo = IntArray(200 * 200) { i ->
            gray(((i % 200) * 0.8f + rnd.nextFloat() * 40).toInt().coerceIn(0, 255))
        }
        assertZero(
            AutoEnhanceCore.analyzePixels(photo, 200, 200, strength = 0f),
            "strength 0")
    }

    @Test fun nightScene_keepsDeepBlacks() {
        val rnd = java.util.Random(5)
        val night = IntArray(200 * 200) {
            gray(if (rnd.nextFloat() < 0.03f) 200 + rnd.nextInt(55) else rnd.nextInt(25))
        }
        val s = AutoEnhanceCore.analyzePixels(night, 200, 200)
        assertTrue("blacks untouched for night", abs(s.blacks) < 1e-4f)
        assertTrue("night exposure limited", s.exposure in -1.0f..1.2f)
    }

    /** Warm skin over a neutral room must not steer the white-balance estimate:
     *  there is no illuminant cast, so temp/tint stay ~0. The old global-mean
     *  gray world cooled this scene by ~-14 temp (the "no care" WB artifact). */
    @Test fun portraitWithNeutralBackground_noWbCorrection() {
        val rnd = java.util.Random(11)
        val portrait = IntArray(200 * 200) { i ->
            val x = i % 200; val y = i / 200
            val (r, g, b) = if (x in 20..180 && y in 10..190)
                Triple(208 + rnd.nextInt(20), 158 + rnd.nextInt(15), 128 + rnd.nextInt(12))
            else
                Triple(126 + rnd.nextInt(8), 126 + rnd.nextInt(8), 128 + rnd.nextInt(8))
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val skinStub = { r: Int, g: Int, b: Int ->
            if (r - g > 30 && g - b > 15) 1f else 0f
        }
        val s = AutoEnhanceCore.analyzePixels(portrait, 200, 200, 1f, skinStub)
        assertTrue("portrait temp near 0, was ${s.temp}", abs(s.temp) < 6f)
        assertTrue("portrait tint near 0, was ${s.tint}", abs(s.tint) < 6f)
    }

    /** A real illuminant cast is still corrected even when skin is present:
     *  the blue-shifted background (not the skin) drives the estimate. */
    @Test fun blueCastWithSkin_warmsFromBackground() {
        val rnd = java.util.Random(13)
        val blue = IntArray(200 * 200) { i ->
            val x = i % 200; val y = i / 200
            val (r, g, b) = if (x in 20..180 && y in 10..190)
                Triple(208 + rnd.nextInt(20), 158 + rnd.nextInt(15), 128 + rnd.nextInt(12))
            else
                Triple(120 + rnd.nextInt(8), 124 + rnd.nextInt(8), 148 + rnd.nextInt(8))
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val skinStub = { r: Int, g: Int, b: Int ->
            if (r - g > 30 && g - b > 15) 1f else 0f
        }
        val s = AutoEnhanceCore.analyzePixels(blue, 200, 200, 1f, skinStub)
        assertTrue("blue cast must be warmed, temp was ${s.temp}", s.temp > 3f)
    }

    /** Dark-background portrait: exposure must meter the face, not the frame.
     *  Whole-frame metering let the dark background drag the mean down and
     *  pushed +0.45 EV, blowing out the well-lit skin; face-weighted
     *  metering keeps exposure near zero/negative so the face stays intact. */
    @Test fun portraitWithDarkBackground_exposureMetersOnSkin() {
        val rnd = java.util.Random(17)
        val portrait = IntArray(200 * 200) { i ->
            val x = i % 200; val y = i / 200
            val (r, g, b) = if (x in 20..180 && y in 40..140)
                Triple(208 + rnd.nextInt(20), 158 + rnd.nextInt(15), 128 + rnd.nextInt(12))
            else
                Triple(38 + rnd.nextInt(6), 38 + rnd.nextInt(6), 40 + rnd.nextInt(6))
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val skinStub = { r: Int, g: Int, b: Int ->
            if (r - g > 30 && g - b > 15) 1f else 0f
        }
        val s = AutoEnhanceCore.analyzePixels(portrait, 200, 200, 1f, skinStub)
        assertTrue("dark-bg portrait must not push exposure up, was ${s.exposure}",
            s.exposure < 0.15f)
        assertTrue("dark-bg portrait must not crush exposure, was ${s.exposure}",
            s.exposure > -1.5f)
    }

    /** High-key scene (bright studio/airy landscape): exposure darkens
     *  toward the 0.68 target instead of pushing brighter, and blacks drop
     *  to the -30 floor to keep the airy look from graying out. */
    @Test fun highKeyScene_darkensTowardTargetAndDropsBlacks() {
        // Deterministic two-tone: 90% at 235, 10% at 180 (std >> uniform guard).
        val photo = IntArray(200 * 200) { i -> gray(if (i < 36000) 235 else 180) }
        val s = AutoEnhanceCore.analyzePixels(photo, 200, 200)
        assertTrue("high-key must darken, exposure was ${s.exposure}",
            s.exposure in -0.8f..-0.1f)
        assertTrue("high-key blacks must sit at the -30 floor, was ${s.blacks}",
            s.blacks == -30f)
        assertTrue("high-key contrast must stay gentle, was ${s.contrast}",
            s.contrast in 5f..20f)
    }

    /** Backlit scene (dark subject + bright background): the branch that
     *  pins highlights to -50 and shadows to +55 for subject recovery. */
    @Test fun backlitScene_recoversSubjectWithPinnedHighlightsShadows() {
        // Deterministic: 40% at 40, 60% at 230 -> darkRatio 0.4, brightRatio
        // 0.6, p95-p5 = 190 > 150.
        val photo = IntArray(200 * 200) { i -> gray(if (i < 16000) 40 else 230) }
        val s = AutoEnhanceCore.analyzePixels(photo, 200, 200)
        assertTrue("backlit highlights pinned to -50, was ${s.highlights}",
            s.highlights == -50f)
        assertTrue("backlit shadows pinned to +55, was ${s.shadows}",
            s.shadows == 55f)
        assertTrue("backlit clarity is 12, was ${s.clarity}", s.clarity == 12f)
        assertTrue("backlit exposure must not push up, was ${s.exposure}",
            s.exposure in -1f..0f)
    }
}
