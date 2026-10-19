package com.lumora.photoeditor.export

import com.lumora.photoeditor.engine.*
import org.junit.Assert.*
import org.junit.Test

/**
 * First-ever executed verification of the export-preset layer:
 * [sanitizePresetName], [uniquePresetName], [isFactoryExportPreset],
 * [factoryExportPresets], [mergeFactoryPresets] (ExportPresets.kt), plus the
 * pure export helpers they lean on in EditParams.kt
 * ([ExportSettings.validate], [galleryFileName], [mimeType], [extension],
 * [sanitizeExportName], [WatermarkSettings.validate], [watermarkLayout]).
 *
 * Export recipes are the phase-4 "per-platform export recipes" track and the
 * Export screen's first tap after every edit — a corrupt factory list or a
 * hostile file name here reaches every share. Pure Kotlin (serialization
 * annotations compile inert on the harness; no Android imports), so this runs
 * on the standalone kotlinc harness as well as Gradle.
 */
class ExportPresetsTest {

    // --- sanitizePresetName ---

    @Test
    fun sanitizePresetName_trimsAndCollapses() =
        assertEquals("Web upload", sanitizePresetName("  Web   upload  "))

    @Test
    fun sanitizePresetName_collapsesTabsAndNewlines() =
        assertEquals("Story Reels", sanitizePresetName("\tStory\nReels "))

    @Test
    fun sanitizePresetName_blankGivesEmpty() =
        assertEquals("", sanitizePresetName("  \n\t "))

    @Test
    fun sanitizePresetName_capsAt40Chars() {
        val clean = sanitizePresetName("a".repeat(50))
        assertEquals(40, clean.length)
        assertEquals("a".repeat(40), clean)
    }

    @Test
    fun sanitizePresetName_keepsNormalNames() =
        assertEquals("Instagram post", sanitizePresetName("Instagram post"))

    // --- uniquePresetName ---

    @Test
    fun uniquePresetName_returnsBaseWhenFree() =
        assertEquals("X", uniquePresetName("X", emptyList()))

    @Test
    fun uniquePresetName_appendsTwo() =
        assertEquals("X (2)", uniquePresetName("X", listOf("X")))

    @Test
    fun uniquePresetName_skipsTakenSuffixes() =
        assertEquals("X (3)", uniquePresetName("X", listOf("X", "X (2)")))

    @Test
    fun uniquePresetName_findsFirstFreeGap() =
        assertEquals("X (3)", uniquePresetName("X", listOf("X", "X (2)", "X (4)")))

    @Test
    fun uniquePresetName_treatsPrefixedBaseLiterally() =
        assertEquals("X (2) (2)", uniquePresetName("X (2)", listOf("X (2)")))

    // --- isFactoryExportPreset ---

    @Test
    fun isFactoryExportPreset_matchesPrefix() =
        assertTrue(isFactoryExportPreset("factory:instagram-post"))

    @Test
    fun isFactoryExportPreset_rejectsUuids() =
        assertFalse(isFactoryExportPreset("custom-9f3a2b1c-0000-4000-8000"))

    @Test
    fun isFactoryExportPreset_rejectsSubstringMatch() =
        assertFalse(isFactoryExportPreset("xfactory:instagram-post"))

    // --- factoryExportPresets catalog integrity ---

    @Test
    fun factoryPresets_fiveRecipes() =
        assertEquals(5, factoryExportPresets().size)

    @Test
    fun factoryPresets_uniqueIdsAndNames() {
        val all = factoryExportPresets()
        assertEquals(all.size, all.map { it.id }.toSet().size)
        assertEquals(all.size, all.map { it.name }.toSet().size)
        assertTrue(all.all { it.name.isNotBlank() })
    }

    @Test
    fun factoryPresets_allCarryFactoryIds() =
        assertTrue(factoryExportPresets().all { isFactoryExportPreset(it.id) })

    @Test
    fun factoryPresets_settingsAllValid() =
        assertTrue(factoryExportPresets().all { it.settings.validate().isEmpty() })

    /**
     * Documented contract on EXPORT_LONG_EDGE_OPTIONS: it includes every size
     * the built-in recipes use, so tapping a recipe always lands on a
     * selectable value. A recipe with an off-list size would show a phantom
     * size in the Export screen's size row.
     */
    @Test
    fun factoryPresets_longEdgesAreSelectable() {
        val options = EXPORT_LONG_EDGE_OPTIONS
        assertTrue(factoryExportPresets().all { it.settings.longEdge in options })
    }

    // --- mergeFactoryPresets ---

    @Test
    fun mergeFactoryPresets_emptyUserIsJustFactory() =
        assertEquals(factoryExportPresets(), mergeFactoryPresets(emptyList()))

    @Test
    fun mergeFactoryPresets_factoryLeadsAndUserOrderKept() {
        val mine1 = ExportPreset(id = "custom-1", name = "Mine 1", settings = ExportSettings())
        val mine2 = ExportPreset(id = "custom-2", name = "Mine 2", settings = ExportSettings())
        val merged = mergeFactoryPresets(listOf(mine1, mine2))
        assertEquals(5 + 2, merged.size)
        assertEquals(factoryExportPresets().map { it.id }, merged.take(5).map { it.id })
        assertEquals(listOf("Mine 1", "Mine 2"), merged.drop(5).map { it.name })
    }

    /**
     * A stale persisted copy carrying a factory id (from an older build or a
     * corrupt write) can never shadow or rename the shipped recipe — the
     * shipped recipe always wins and the user data survives untouched.
     */
    @Test
    fun mergeFactoryPresets_dropsStaleFactoryCopies() {
        val stale = ExportPreset(
            id = "factory:instagram-post", name = "HACKED",
            settings = ExportSettings(quality = 1),
        )
        val mine = ExportPreset(id = "custom-1", name = "Mine", settings = ExportSettings())
        val merged = mergeFactoryPresets(listOf(stale, mine))
        assertEquals(6, merged.size)
        assertFalse(merged.any { it.name == "HACKED" })
        val insta = merged.first { it.id == "factory:instagram-post" }
        assertEquals("Instagram post", insta.name)
        assertEquals(88, insta.settings.quality)
        assertEquals("Mine", merged.last().name)
    }

    @Test
    fun maxExportPresets_is32() = assertEquals(32, MAX_EXPORT_PRESETS)

    // --- ExportSettings.validate ---

    @Test
    fun validate_defaultIsClean() =
        assertTrue(ExportSettings().validate().isEmpty())

    @Test
    fun validate_qualityBounds() {
        assertTrue(ExportSettings(quality = 0).validate().isNotEmpty())
        assertTrue(ExportSettings(quality = 101).validate().isNotEmpty())
        assertTrue(ExportSettings(quality = 1).validate().isEmpty())
        assertTrue(ExportSettings(quality = 100).validate().isEmpty())
    }

    @Test
    fun validate_longEdgeBounds() {
        assertTrue(ExportSettings(longEdge = -1).validate().isNotEmpty())
        assertTrue(ExportSettings(longEdge = 16001).validate().isNotEmpty())
        assertTrue(ExportSettings(longEdge = 0).validate().isEmpty())
        assertTrue(ExportSettings(longEdge = 16000).validate().isEmpty())
    }

    @Test
    fun validate_watermarkErrorsPropagate() {
        assertTrue(ExportSettings(watermark = WatermarkSettings(opacity = 5f)).validate().isNotEmpty())
        assertTrue(ExportSettings(watermark = WatermarkSettings(sizeFrac = 0.5f)).validate().isNotEmpty())
        assertTrue(ExportSettings().validate().isEmpty())
    }

    // --- galleryFileName ---

    @Test
    fun galleryFileName_usesTypedStem() =
        assertEquals("my shot.jpg", ExportSettings(fileName = "my shot").galleryFileName())

    @Test
    fun galleryFileName_sanitizesSeparators() =
        assertEquals("evil.jpg", ExportSettings(fileName = "../evil").galleryFileName())

    @Test
    fun galleryFileName_usesSourceStemWhenBlank() =
        assertEquals("IMG_2041.jpg", ExportSettings().galleryFileName("IMG_2041"))

    @Test
    fun galleryFileName_stripsSourceExtension() =
        assertEquals("IMG_2041.jpg", ExportSettings().galleryFileName("IMG_2041.CR3"))

    @Test
    fun galleryFileName_fallsBackToLumoraTimestamp() {
        val name = ExportSettings().galleryFileName(null)
        assertTrue(name.startsWith("Lumora_"))
        assertTrue(name.endsWith(".jpg"))
    }

    @Test
    fun galleryFileName_extensionFollowsFormat() {
        assertEquals("x.png", ExportSettings(format = ExportFormat.PNG, fileName = "x").galleryFileName())
        assertEquals("x.webp", ExportSettings(format = ExportFormat.WEBP, fileName = "x").galleryFileName())
        assertEquals("x.jpg", ExportSettings(format = ExportFormat.JPEG, fileName = "x").galleryFileName())
    }

    // --- mimeType / extension ---

    @Test
    fun mimeType_mapping() {
        assertEquals("image/jpeg", ExportSettings(format = ExportFormat.JPEG).mimeType())
        assertEquals("image/png", ExportSettings(format = ExportFormat.PNG).mimeType())
        assertEquals("image/webp", ExportSettings(format = ExportFormat.WEBP).mimeType())
    }

    @Test
    fun extension_mapping() {
        assertEquals("jpg", ExportSettings(format = ExportFormat.JPEG).extension())
        assertEquals("png", ExportSettings(format = ExportFormat.PNG).extension())
        assertEquals("webp", ExportSettings(format = ExportFormat.WEBP).extension())
    }

    // --- sanitizeExportName ---

    @Test
    fun sanitizeExportName_dropsIllegalChars() =
        assertEquals("evil", sanitizeExportName("../evil"))

    @Test
    fun sanitizeExportName_dropsControlChars() =
        assertEquals("ab", sanitizeExportName("a\u0000b"))

    @Test
    fun sanitizeExportName_stripsLeadingDots() =
        assertEquals("x", sanitizeExportName("...x"))

    @Test
    fun sanitizeExportName_blankGivesEmpty() =
        assertEquals("", sanitizeExportName("  "))

    @Test
    fun sanitizeExportName_capsAt120Chars() =
        assertEquals(120, sanitizeExportName("a".repeat(200)).length)

    // --- WatermarkSettings.validate ---

    @Test
    fun watermarkValidate_defaultIsClean() =
        assertTrue(WatermarkSettings().validate().isEmpty())

    @Test
    fun watermarkValidate_rejectsOutOfRange() {
        assertTrue(WatermarkSettings(opacity = 5f).validate().isNotEmpty())
        assertTrue(WatermarkSettings(sizeFrac = 0.5f).validate().isNotEmpty())
    }

    @Test
    fun watermarkIsActive_needsEnabledAndText() {
        assertFalse(WatermarkSettings().isActive())
        assertFalse(WatermarkSettings(enabled = true).isActive())
        assertTrue(WatermarkSettings(enabled = true, text = "H2W").isActive())
    }

    // --- watermarkLayout ---

    @Test
    fun watermarkLayout_bottomRight() {
        val l = watermarkLayout(1000, 500, WatermarkSettings(position = WatermarkPosition.BOTTOM_RIGHT))
        assertEquals(WatermarkAlign.RIGHT, l.align)
        // Inset 3% from the right edge.
        assertEquals(1000f - 1000f * 0.03f, l.x, 1e-4f)
        assertEquals(500f - 500f * 0.03f, l.baselineY, 1e-4f)
        // 0.035 * 500 = 17.5, above the 8px floor.
        assertEquals(17.5f, l.textPx, 1e-4f)
    }

    @Test
    fun watermarkLayout_topLeftBaselineInsideMargin() {
        val l = watermarkLayout(1000, 500, WatermarkSettings(position = WatermarkPosition.TOP_LEFT))
        assertEquals(WatermarkAlign.LEFT, l.align)
        assertEquals(1000 * 0.03f, l.x, 1e-4f)
        // Baseline sits ~0.7*textPx below the top margin: inside the frame.
        assertTrue(l.baselineY > 500 * 0.03f)
    }

    @Test
    fun watermarkLayout_center() {
        val l = watermarkLayout(1000, 500, WatermarkSettings(position = WatermarkPosition.CENTER))
        assertEquals(WatermarkAlign.CENTER, l.align)
        assertEquals(500f, l.x, 1e-4f)
        assertEquals(250f + l.textPx * 0.35f, l.baselineY, 1e-4f)
    }
}
