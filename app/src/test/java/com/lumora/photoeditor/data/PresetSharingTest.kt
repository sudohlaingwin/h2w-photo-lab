package com.lumora.photoeditor.data

import com.lumora.photoeditor.engine.EditState
import com.lumora.photoeditor.engine.LightParams
import com.lumora.photoeditor.engine.Mask
import com.lumora.photoeditor.engine.MaskComponent
import com.lumora.photoeditor.engine.MaskType
import com.lumora.photoeditor.engine.Preset
import com.lumora.photoeditor.engine.RetouchOp
import com.lumora.photoeditor.engine.RetouchSpot
import com.lumora.photoeditor.engine.RetouchState
import org.junit.Assert.*
import org.junit.Test

/**
 * First-ever executed verification of [prepareImportedPreset] —
 * data/PresetSharing.kt, the pure-Kotlin normalization of a decoded preset
 * file into an importable custom preset (no Android imports).
 *
 * This is the outside-world entry point for shared presets (hand-editable
 * JSON): the contract pinned here is that hostile or sloppy input degrades
 * to "not importable" or a sanitized portable preset, never to a crash or
 * to image-specific work (retouch spots, AI mask rasters) leaking onto
 * other photos.
 */
class PresetSharingTest {

    private fun preset(
        id: String = "x",
        name: String = "Golden",
        category: String = "Warm",
        state: EditState = EditState(),
        strength: Float = 1f,
        builtIn: Boolean = true,
    ) = Preset(
        id = id, name = name, category = category, state = state,
        strength = strength, builtIn = builtIn,
    )

    @Test fun nullDecodedReturnsNull() {
        assertNull(prepareImportedPreset(null, emptySet()))
    }

    @Test fun blankNameReturnsNull() {
        assertNull(prepareImportedPreset(preset(name = ""), emptySet()))
        assertNull(prepareImportedPreset(preset(name = "   "), emptySet()))
    }

    @Test fun nameIsTrimmed() {
        val out = prepareImportedPreset(preset(name = "  Golden Hour  "), emptySet())!!
        assertEquals("Golden Hour", out.name)
    }

    @Test fun blankCategoryFallsBackToMyPresets() {
        val out = prepareImportedPreset(preset(category = "   "), emptySet())!!
        assertEquals("My Presets", out.category)
    }

    @Test fun categoryIsTrimmed() {
        val out = prepareImportedPreset(preset(category = "  Warm  "), emptySet())!!
        assertEquals("Warm", out.category)
    }

    @Test fun nameDedupAppendsTwoOnCollision() {
        val out = prepareImportedPreset(preset(name = "Golden"), setOf("Golden"))!!
        assertEquals("Golden (2)", out.name)
    }

    @Test fun nameDedupSkipsTakenSuffixes() {
        val out = prepareImportedPreset(
            preset(name = "Golden"), setOf("Golden", "Golden (2)"))!!
        assertEquals("Golden (3)", out.name)
    }

    @Test fun noCollisionKeepsName() {
        val out = prepareImportedPreset(preset(name = "Golden"), setOf("Other"))!!
        assertEquals("Golden", out.name)
    }

    @Test fun dedupIsCaseSensitive() {
        // Documented: existingNames are compared case-sensitively.
        val out = prepareImportedPreset(preset(name = "Golden"), setOf("golden"))!!
        assertEquals("Golden", out.name)
    }

    @Test fun existingSuffixedNameIsTreatedLiterally() {
        // Dedup does not parse the numeric suffix: "X (2)" collides verbatim,
        // so the disambiguated name is "X (2) (2)".
        val out = prepareImportedPreset(
            preset(name = "X (2)"), setOf("X", "X (2)"))!!
        assertEquals("X (2) (2)", out.name)
    }

    @Test fun strengthCoercedToUnitRange() {
        assertEquals(1f, prepareImportedPreset(preset(strength = 1.5f), emptySet())!!.strength)
        assertEquals(0f, prepareImportedPreset(preset(strength = -0.5f), emptySet())!!.strength)
        assertEquals(0.7f, prepareImportedPreset(preset(strength = 0.7f), emptySet())!!.strength)
    }

    @Test fun importIsAlwaysCustom() {
        val out = prepareImportedPreset(preset(builtIn = true), emptySet())!!
        assertFalse(out.builtIn)
    }

    @Test fun idIsFreshCustomId() {
        val out = prepareImportedPreset(preset(id = "built-in-golden"), emptySet())!!
        assertTrue(out.id.startsWith("custom-"))
        assertNotEquals("built-in-golden", out.id)
    }

    @Test fun thumbnailSeedIsNow() {
        val out = prepareImportedPreset(preset(), emptySet())!!
        assertTrue(out.thumbnailSeed > 0L)
    }

    @Test fun retouchSpotsDoNotTravelAcrossPhotos() {
        val spotty = EditState(retouch = RetouchState(spots = listOf(
            RetouchSpot(
                op = RetouchOp.HEAL, x = 0.5f, y = 0.5f,
                srcX = 0.6f, srcY = 0.6f, size = 0.05f,
            ),
        )))
        val out = prepareImportedPreset(preset(state = spotty), emptySet())!!
        assertTrue(out.state.retouch.spots.isEmpty())
    }

    @Test fun aiMaskComponentsDroppedBrushComponentKept() {
        val mixed = Mask(
            id = "m1", name = "m", components = listOf(
                MaskComponent(id = "c1", type = MaskType.BRUSH),
                MaskComponent(id = "c2", type = MaskType.AI_SUBJECT, aiKey = "k"),
                MaskComponent(id = "c3", type = MaskType.SKY_SELECT),
            ),
        )
        val out = prepareImportedPreset(
            preset(state = EditState(masks = listOf(mixed))), emptySet())!!
        assertEquals(
            listOf(MaskType.BRUSH),
            out.state.masks.single().components.map { it.type },
        )
    }

    @Test fun fullyAiMaskIsDroppedEntirely() {
        val aiOnly = Mask(
            id = "m1", name = "m", components = listOf(
                MaskComponent(id = "c2", type = MaskType.AI_SUBJECT, aiKey = "k"),
            ),
        )
        val out = prepareImportedPreset(
            preset(state = EditState(masks = listOf(aiOnly))), emptySet())!!
        assertTrue(out.state.masks.isEmpty())
    }

    @Test fun parametricDeltasSurviveImport() {
        val state = EditState(light = LightParams(exposure = 1.2f))
        val out = prepareImportedPreset(preset(state = state), emptySet())!!
        assertEquals(1.2f, out.state.light.exposure)
    }
}
