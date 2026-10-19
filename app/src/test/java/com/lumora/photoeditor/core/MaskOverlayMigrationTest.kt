package com.lumora.photoeditor.core

import org.junit.Assert.*
import org.junit.Test

/**
 * Executed verification for core/MaskOverlayMigration.kt (pure Kotlin, zero
 * imports — runs on the standalone kotlinc harness; Gradle's test workers
 * are broken in this env).
 *
 * This pins the 2026-10-18 on-device video-bug-#6 fix: the mask red overlay
 * rendered "far too opaque" at the old 0.55 default, so the default became
 * 0.35 and existing installs migrate on first read. The contract under test:
 * untouched installs (fallback -1f) and installs still on the old 0.55
 * default move to 0.35; any deliberate user value is kept verbatim.
 */
class MaskOverlayMigrationTest {

    // ---- Untouched installs get the new default ----

    @Test fun untouchedInstallFallbackMigratesToNewDefault() {
        // -1f is the getFloat() fallback AppSettings passes for "never set".
        assertEquals(0.35f, migrateMaskOverlayAlpha(-1f), 0.0f)
    }

    @Test fun oldDefaultReadbackMigratesToNewDefault() {
        // Pre-2026-10-18 code read prefs.getFloat(KEY, 0.55f): an untouched
        // install either never wrote (now reads -1f) or still holds 0.55f.
        assertEquals(0.35f, migrateMaskOverlayAlpha(0.55f), 0.0f)
    }

    // ---- The float-equality edge: a slider parked at exactly 55% ----

    @Test fun sliderRoundtripAt55PercentIsBitIdenticalToOldDefault() {
        // The slider writes its value verbatim; 55f/100f is correctly rounded
        // to the same float as the 0.55f literal, so it also migrates. This
        // pins the deliberate trade-off: exactly-55% is indistinguishable
        // from "untouched" and moves to 0.35 too.
        val sliderValue = 55f / 100f
        assertEquals(0.55f, sliderValue, 0.0f)
        assertEquals(0.35f, migrateMaskOverlayAlpha(sliderValue), 0.0f)
    }

    @Test fun nearMissNeighborsArePreserved() {
        // Equality is exact, not fuzzy: 54.9% and 55.1% stay untouched.
        assertEquals(0.549f, migrateMaskOverlayAlpha(0.549f), 0.0f)
        assertEquals(0.551f, migrateMaskOverlayAlpha(0.551f), 0.0f)
    }

    // ---- Deliberate user values are kept verbatim ----

    @Test fun userValuesPreserved() {
        assertEquals(0.8f, migrateMaskOverlayAlpha(0.8f), 0.0f)
        assertEquals(1.0f, migrateMaskOverlayAlpha(1.0f), 0.0f)
        assertEquals(0.05f, migrateMaskOverlayAlpha(0.05f), 0.0f)
        assertEquals(0.2f, migrateMaskOverlayAlpha(0.2f), 0.0f)
    }

    @Test fun alreadyMigratedValueIsIdempotent() {
        assertEquals(0.35f, migrateMaskOverlayAlpha(0.35f), 0.0f)
    }

    @Test fun otherNegativeSentinelsAlsoMigrate() {
        // Defensive: any negative can only come from a missing/odd stored
        // value, never from the slider (setter coerces to 0.05..1).
        assertEquals(0.35f, migrateMaskOverlayAlpha(-0.5f), 0.0f)
    }
}
