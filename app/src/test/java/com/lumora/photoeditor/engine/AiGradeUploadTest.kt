package com.lumora.photoeditor.engine

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters

/**
 * First-ever executed verification of [AiGradeUpload] — the shared helper
 * that re-establishes the fused AI Pro Retouch 3D LUT on a [GlEngine] for
 * photo reopen, batch export, and asset invalidation.
 *
 * It sits directly on the P0 black-preview root-cause area: a stale or
 * half-initialized LUT sampler blackens the frame on Mali GPUs, so the
 * contracts pinned here are the ones that must never drift:
 *  - "no grade" is always an explicit unavailable marker
 *    (`uploadAiLut(null, 0)`), never a stale LUT left bound;
 *  - the uploaded LUT is exactly the bake → fuse → warp sequence, so the
 *    preview and the export can never disagree;
 *  - WB gains crossing process restarts via the sidecar are sanitized —
 *    a corrupt sidecar degrades to "no warp", never a corrupted grade;
 *  - a failed bake disables the feature rather than shipping a bad LUT;
 *  - cancellation still propagates off the GL thread.
 *
 * The bank ([AiLutBank]) caches per process, so the tests reset its private
 * state via reflection where a pristine bank is required and run in
 * declared order ([FixMethodOrder]): the bake-failure tests that
 * permanently disable the feature run last.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class AiGradeUploadTest {

    private data class Upload(val lut: FloatArray?, val n: Int)

    /**
     * Recording fake: identity ramp on the first bake (passes AiLutBank's
     * self-check exactly), four constant basis LUTs on bakes 2..5.
     */
    private class RecEngine(
        var bakeThrows: Boolean = false,
        var throwOnUpload: Throwable? = null,
    ) : GlEngine() {
        val uploads = mutableListOf<Upload>()
        var bakeCalls = 0
        val bakedBanks = mutableListOf<FloatArray>()

        override fun uploadAiLut(lut: FloatArray?, n: Int) {
            throwOnUpload?.let { throw it }
            uploads.add(Upload(lut?.copyOf(), n))
        }

        override fun bakeLut(state: EditState, n: Int): FloatArray {
            bakeCalls++
            if (bakeThrows) throw RuntimeException("bake boom")
            if (bakeCalls == 1) {
                val out = FloatArray(n * n * n * 3)
                for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
                    val o = (((r * n) + g) * n + b) * 3
                    out[o] = r / (n - 1f)
                    out[o + 1] = g / (n - 1f)
                    out[o + 2] = b / (n - 1f)
                }
                return out
            }
            // Spatially varying ramp per basis (values in 0..0.8) so the WB
            // warp's resampling is observable in tests.
            val k = bakeCalls - 2 // 0..3
            val out = FloatArray(n * n * n * 3)
            for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
                val o = (((r * n) + g) * n + b) * 3
                val v = ((r + g + b) / (3f * (n - 1f))) * (0.2f + 0.2f * k)
                out[o] = v; out[o + 1] = v; out[o + 2] = v
            }
            bakedBanks.add(out)
            return out
        }
    }

    /** Resets AiLutBank's process-cached bank so a test starts pristine. */
    private fun resetBank() {
        val cls = AiLutBank::class.java
        cls.getDeclaredField("basis").apply { isAccessible = true }.set(AiLutBank, null)
        cls.getDeclaredField("available").apply { isAccessible = true }.set(AiLutBank, true)
    }

    private val validWeights = listOf(0.4f, 0.3f, 0.2f, 0.1f)

    @Test
    fun t01_nullWeights_unavailableMarker_noBake() {
        val e = RecEngine()
        val r = AiGradeUpload.upload(e, null)
        assertFalse(r.ready)
        assertFalse(r.sourceStomped)
        assertEquals(1, e.uploads.size)
        assertNull(e.uploads[0].lut)
        assertEquals(0, e.uploads[0].n)
        assertEquals(0, e.bakeCalls) // no GL bake work below touches the source
    }

    @Test
    fun t02_wrongSizeWeights_unavailableMarker_noBake() {
        val e = RecEngine()
        val r = AiGradeUpload.upload(e, listOf(0.25f, 0.25f, 0.25f))
        assertFalse(r.ready)
        assertFalse(r.sourceStomped)
        assertEquals(1, e.uploads.size)
        assertNull(e.uploads[0].lut)
        assertEquals(0, e.bakeCalls)
    }

    @Test
    fun t03_validWeights_firstCall_bakesFusesUploads() {
        resetBank()
        val e = RecEngine()
        val r = AiGradeUpload.upload(e, validWeights)
        assertTrue(r.ready)
        assertTrue(r.sourceStomped) // first bake attempt stomps the source texture
        assertEquals(5, e.bakeCalls) // identity self-check + 4 basis bakes
        assertEquals(4, e.bakedBanks.size)
        assertEquals(1, e.uploads.size)
        val up = e.uploads[0]
        assertEquals(AiProRetouch.LUT_SIZE, up.n)
        assertNotNull(up.lut)
        // The uploaded LUT is EXACTLY the fuse of the baked bank — preview
        // and export share this sequence, so they can never disagree.
        val expected = AiProRetouch.fuse(validWeights.toFloatArray(), e.bakedBanks)
        assertArrayEquals(expected, up.lut!!, 0f)
        assertTrue(up.lut!!.all { it in 0f..1f }) // stays in 0..1 gamut
    }

    @Test
    fun t04_validWeights_secondCall_usesCachedBank() {
        // Bank baked by t03 is cached: no re-bake, no source stomp.
        val e = RecEngine()
        val r = AiGradeUpload.upload(e, validWeights)
        assertTrue(r.ready)
        assertFalse(r.sourceStomped)
        assertEquals(0, e.bakeCalls)
        assertEquals(1, e.uploads.size)
        assertNotNull(e.uploads[0].lut)
    }

    @Test
    fun t05_pureWeight_selectsBasisLut() {
        val e = RecEngine()
        val r = AiGradeUpload.upload(e, listOf(1f, 0f, 0f, 0f))
        assertTrue(r.ready)
        // Weight 1.0 on basis 0 fuses to basis 0 exactly (pure weighted sum).
        val bank = AiLutBank.basisLuts()!!
        assertArrayEquals(bank[0], e.uploads[0].lut!!, 0f)
    }

    @Test
    fun t06_wbGains_warpedIntoLut() {
        val e = RecEngine()
        val gains = listOf(1.2f, 1.0f, 0.9f)
        val r = AiGradeUpload.upload(e, validWeights, gains)
        assertTrue(r.ready)
        val bank = AiLutBank.basisLuts()!!
        val fused = AiProRetouch.fuse(validWeights.toFloatArray(), bank)
        val expected = AiProRetouch.warpLutForWb(
            fused, floatArrayOf(1.2f, 1.0f, 0.9f), AiProRetouch.LUT_SIZE,
        )
        assertArrayEquals(expected, e.uploads[0].lut!!, 0f)
        // The warp actually changed the grade (not silently dropped).
        assertFalse(fused.contentEquals(e.uploads[0].lut!!))
    }

    @Test
    fun t07_wbGains_invalid_skipped() {
        val e = RecEngine()
        val bank = AiLutBank.basisLuts()!!
        val fused = AiProRetouch.fuse(validWeights.toFloatArray(), bank)
        // NaN gain: corrupt sidecar degrades to "no warp", never a bad grade.
        var r = AiGradeUpload.upload(e, validWeights, listOf(Float.NaN, 1f, 1f))
        assertTrue(r.ready)
        assertArrayEquals(fused, e.uploads.last().lut!!, 0f)
        // Wrong channel count: same "no warp" degradation.
        r = AiGradeUpload.upload(e, validWeights, listOf(1.2f, 0.9f))
        assertTrue(r.ready)
        assertArrayEquals(fused, e.uploads.last().lut!!, 0f)
        // Null gains: no warp requested.
        r = AiGradeUpload.upload(e, validWeights, null)
        assertTrue(r.ready)
        assertArrayEquals(fused, e.uploads.last().lut!!, 0f)
    }

    @Test
    fun t08_wbGains_identity_skipped() {
        val e = RecEngine()
        val r = AiGradeUpload.upload(e, validWeights, listOf(1f, 1f, 1f))
        assertTrue(r.ready)
        // All-identity gains warp to the identical LUT; the work is skipped.
        val bank = AiLutBank.basisLuts()!!
        val fused = AiProRetouch.fuse(validWeights.toFloatArray(), bank)
        assertArrayEquals(fused, e.uploads[0].lut!!, 0f)
    }

    @Test
    fun t09_cancellation_propagates() {
        val e = RecEngine(throwOnUpload = CancellationException("cancel"))
        try {
            AiGradeUpload.upload(e, validWeights)
            org.junit.Assert.fail("expected CancellationException")
        } catch (ce: CancellationException) {
            // expected: never swallowed on the GL thread
        }
        // The unavailable marker is NOT sent after cancellation (the caller
        // is tearing down; a marker write would be a lie about engine state).
        assertTrue(e.uploads.isEmpty())
    }

    @Test
    fun t10_bakeFailure_disablesFeature() {
        resetBank()
        val e = RecEngine(bakeThrows = true)
        val r = AiGradeUpload.upload(e, validWeights)
        // A failed bake disables the feature rather than shipping a bad LUT.
        assertFalse(AiLutBank.available)
        assertFalse(r.ready)
        assertTrue(r.sourceStomped) // the attempt still stomped the source
        assertEquals(1, e.uploads.size)
        assertNull(e.uploads[0].lut)
        assertEquals(0, e.uploads[0].n)
    }

    @Test
    fun t11_disabledBank_degradesToUnavailable() {
        // available == false after t10: no bake attempted, explicit marker.
        val e = RecEngine()
        val r = AiGradeUpload.upload(e, validWeights)
        assertFalse(r.ready)
        assertFalse(r.sourceStomped)
        assertEquals(0, e.bakeCalls)
        assertEquals(1, e.uploads.size)
        assertNull(e.uploads[0].lut)
        assertEquals(0, e.uploads[0].n)
    }
}
