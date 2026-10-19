package com.lumora.photoeditor.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * First-ever executed verification of [AiLutBank] — the process-cached bank
 * of the 4 pro-recipe basis 3D LUTs behind AI Pro Retouch.
 *
 * It sits directly on the P0 black-preview root-cause area: a corrupted or
 * half-baked basis LUT blackens the frame on Mali GPUs, so the contracts
 * pinned here are the ones that must never drift:
 *  - the identity self-check gates the whole feature: a bake that cannot
 *    reproduce the identity LUT (maxErr > 4/255) disables AI Pro Retouch
 *    permanently for the process instead of shipping a bad grade;
 *  - a throwing bakeLut never propagates — it degrades to "unavailable";
 *  - the bank bakes exactly once per process (1 identity + 4 basis bakes),
 *    then serves the cache;
 *  - the 4 basis bakes carry ONLY the recipes' pointwise grade
 *    (exposure/contrast/levels/temp/tint/vibrance/saturation); neighborhood
 *    ops (clarity/texture/dehaze) stay at defaults because a 3D LUT cannot
 *    represent them. (Pinned behavior: the backlit bake forces saturation to
 *    0 rather than the recipe's value.)
 *
 * The bank caches per process ([AiLutBank] is an object), so @Before resets
 * its private state via reflection for a pristine bank per test.
 */
class AiLutBankTest {

    /** Recording fake: exact identity ramp on bake #1, distinct constant banks after. */
    private class RecEngine(
        var identityCorrupt: Boolean = false,
        var throwOnIdentity: Boolean = false,
        var throwOnBasis: Boolean = false,
    ) : GlEngine() {
        var bakeCalls = 0
        val bakedStates = mutableListOf<EditState>()

        override fun bakeLut(state: EditState, n: Int): FloatArray {
            bakeCalls++
            bakedStates.add(state)
            val isIdentity = bakeCalls == 1
            if (isIdentity && throwOnIdentity) throw RuntimeException("identity boom")
            if (!isIdentity && throwOnBasis) throw RuntimeException("basis boom")
            val out = FloatArray(n * n * n * 3)
            if (isIdentity) {
                for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
                    val o = (((r * n) + g) * n + b) * 3
                    out[o] = r / (n - 1f)
                    out[o + 1] = g / (n - 1f)
                    out[o + 2] = b / (n - 1f)
                }
                if (identityCorrupt) out[0] += 0.5f // maxErr 0.5 >> 4/255 threshold
            } else {
                out.fill(bakeCalls.toFloat()) // distinct bank per basis
            }
            return out
        }
    }

    /** Resets AiLutBank's process-cached bank so each test starts pristine. */
    @Before
    fun resetBank() {
        val cls = AiLutBank::class.java
        cls.getDeclaredField("basis").apply { isAccessible = true }.set(AiLutBank, null)
        cls.getDeclaredField("available").apply { isAccessible = true }.set(AiLutBank, true)
    }

    @Test
    fun t01_pristine_availableTrue_bankNull() {
        assertTrue(AiLutBank.available)
        assertNull(AiLutBank.basisLuts())
    }

    @Test
    fun t02_identityPass_bakesFourBanks() {
        val e = RecEngine()
        assertTrue(AiLutBank.ensureBaked(e))
        assertTrue(AiLutBank.available)
        val banks = AiLutBank.basisLuts()
        assertNotNull(banks)
        assertEquals(4, banks!!.size)
        val n = AiProRetouch.LUT_SIZE
        banks.forEach { assertEquals(n * n * n * 3, it.size) }
        assertEquals(5, e.bakeCalls) // 1 identity self-check + 4 basis bakes
    }

    @Test
    fun t03_cached_secondCallDoesNotRebake() {
        val e = RecEngine()
        assertTrue(AiLutBank.ensureBaked(e))
        assertTrue(AiLutBank.ensureBaked(e))
        assertEquals(5, e.bakeCalls)
    }

    @Test
    fun t04_identityFail_disablesPermanently() {
        val e = RecEngine(identityCorrupt = true)
        assertFalse(AiLutBank.ensureBaked(e))
        assertFalse(AiLutBank.available)
        assertNull(AiLutBank.basisLuts())
        // Permanent: a fresh good engine is never even touched again.
        val good = RecEngine()
        assertFalse(AiLutBank.ensureBaked(good))
        assertEquals(0, good.bakeCalls)
        assertFalse(AiLutBank.available)
    }

    @Test
    fun t05_identityBakeThrows_disablesWithoutThrow() {
        val e = RecEngine(throwOnIdentity = true)
        assertFalse(AiLutBank.ensureBaked(e))
        assertFalse(AiLutBank.available)
        assertNull(AiLutBank.basisLuts())
    }

    @Test
    fun t06_basisBakeThrows_disables() {
        val e = RecEngine(throwOnBasis = true)
        assertFalse(AiLutBank.ensureBaked(e))
        assertFalse(AiLutBank.available)
        assertNull(AiLutBank.basisLuts())
    }

    /** Builds the expected pointwise-only bake state for one recipe's grade. */
    private fun gradeOf(
        exposure: Float, contrast: Float, highlights: Float, shadows: Float,
        whites: Float, blacks: Float, temp: Float, tint: Float,
        vibrance: Float, saturation: Float,
    ) = EditState(
        light = LightParams(
            exposure = exposure, contrast = contrast,
            highlights = highlights, shadows = shadows,
            whites = whites, blacks = blacks,
            temp = temp, tint = tint,
        ),
        color = ColorParams(vibrance = vibrance, saturation = saturation),
    )

    @Test
    fun t07_basisStates_carryOnlyPointwiseGrade() {
        val e = RecEngine()
        assertTrue(AiLutBank.ensureBaked(e))
        assertEquals(5, e.bakedStates.size)
        assertEquals(EditState(), e.bakedStates[0]) // self-check uses the default state
        val pr = ProRecipes.PortraitRecipe()
        val lr = ProRecipes.LandscapeRecipe()
        val nr = ProRecipes.NightRecipe()
        val br = ProRecipes.BacklitRecipe()
        val expectedStates = listOf(
            gradeOf(pr.exposure, pr.contrast, pr.highlights, pr.shadows, pr.whites,
                pr.blacks, pr.temp, pr.tint, pr.vibrance, pr.saturation),
            gradeOf(lr.exposure, lr.contrast, lr.highlights, lr.shadows, lr.whites,
                lr.blacks, lr.temp, lr.tint, lr.vibrance, lr.saturation),
            gradeOf(nr.exposure, nr.contrast, nr.highlights, nr.shadows, nr.whites,
                nr.blacks, nr.temp, nr.tint, nr.vibrance, nr.saturation),
            // Pinned behavior: the backlit bake forces saturation to 0
            // instead of the recipe's value.
            gradeOf(br.exposure, br.contrast, br.highlights, br.shadows, br.whites,
                br.blacks, br.temp, br.tint, br.vibrance, 0f),
        )
        for (i in expectedStates.indices) {
            assertEquals(
                "basis $i must carry only the recipe's pointwise grade",
                expectedStates[i], e.bakedStates[i + 1],
            )
        }
    }

    @Test
    fun t08_basisBanks_distinctPerRecipe() {
        val e = RecEngine()
        assertTrue(AiLutBank.ensureBaked(e))
        val banks = AiLutBank.basisLuts()!!
        for (i in banks.indices) for (j in i + 1 until banks.size) {
            assertFalse("basis banks $i and $j must differ", banks[i].contentEquals(banks[j]))
        }
    }
}
