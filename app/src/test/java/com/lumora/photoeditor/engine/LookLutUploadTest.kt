package com.lumora.photoeditor.engine

import org.junit.Assert.*
import org.junit.Test

/**
 * First-ever executed verification of [LookLutUpload] — the never-throws
 * re-upload guard for the look-transfer 3D LUT (pro-spec Phase 1).
 *
 * Why this file earns a cycle: it sits directly on the P0 black-preview
 * root-cause area. Its contract is that the LUT a [GlEngine] holds can never
 * go stale in a way the shader reads as garbage —
 *  - an inactive [LookTransferParams] must mark the LUT unavailable with a
 *    single `uploadLookLut(null, 0)`, so no LUT from a previous photo leaks
 *    into the next preview/export;
 *  - an active transfer must deliver exactly the [ColorTransfer.bakeLut]
 *    output at [ColorTransfer.LUT_SIZE], or the GPU reads a rotated cube;
 *  - a bake/upload failure must degrade to "no look transfer" instead of
 *    throwing up the GL thread (only coroutine cancellation propagates).
 *
 * The test subclasses [GlEngine] with a recording fake, which is why
 * GlEngine and uploadLookLut are declared open (zero runtime change).
 *
 * Runs under Gradle AND under the standalone kotlinc 1.9.23 harness
 * (hidden_files/looklutupload-harness/run.sh), which compiles the REAL
 * ColorTransfer.kt + LookLutUpload.kt + this test against
 * signature-identical GlEngine/LookTransferParams/android.util.Log stubs.
 */
class LookLutUploadTest {

    private class FakeEngine : GlEngine() {
        data class Call(val lut: FloatArray?, val n: Int)
        val calls = mutableListOf<Call>()
        /** Upcoming uploadLookLut calls that throw after recording. */
        var failRemaining = 0
        var failWith: () -> Throwable = { RuntimeException("simulated upload failure") }
        override fun uploadLookLut(lut: FloatArray?, n: Int) {
            calls += Call(lut, n)
            if (failRemaining > 0) {
                failRemaining--
                throw failWith()
            }
        }
    }

    private fun activeParams() = LookTransferParams(
        srcStats = listOf(1f, 0.1f, -0.2f, 0.5f, 0.3f, 0.25f),
        refStats = listOf(2f, -0.3f, 0.4f, 0.8f, 0.5f, 0.6f),
        refName = "reference",
    )

    // ---------- inactive: mark unavailable, never bake ----------

    @Test fun inactiveDefaultParamsMarksUnavailableOnce() {
        val engine = FakeEngine()
        LookLutUpload.upload(engine, LookTransferParams())
        assertEquals(1, engine.calls.size)
        assertNull(engine.calls[0].lut)
        assertEquals(0, engine.calls[0].n)
    }

    @Test fun inactivePartialStatsMarksUnavailableOnce() {
        // srcStats complete but refStats short -> isActive() false.
        val engine = FakeEngine()
        LookLutUpload.upload(
            engine,
            LookTransferParams(
                srcStats = listOf(1f, 0.1f, -0.2f, 0.5f, 0.3f, 0.25f),
                refStats = listOf(2f, -0.3f, 0.4f, 0.8f, 0.5f),
            ),
        )
        assertEquals(1, engine.calls.size)
        assertNull(engine.calls[0].lut)
        assertEquals(0, engine.calls[0].n)
    }

    // ---------- active: the exact baked LUT, documented layout ----------

    @Test fun activeUploadsBakedLutWithDocumentedSize() {
        val engine = FakeEngine()
        val params = activeParams()
        LookLutUpload.upload(engine, params)
        assertEquals(1, engine.calls.size)
        val call = engine.calls[0]
        assertEquals(ColorTransfer.LUT_SIZE, call.n)
        val lut = call.lut
        assertNotNull(lut)
        assertEquals(ColorTransfer.LUT_SIZE * ColorTransfer.LUT_SIZE * ColorTransfer.LUT_SIZE * 3, lut!!.size)
        // The engine must receive exactly what bakeLut produced — any drift
        // between the two is a rotated/garbled cube on the GPU.
        val expected = ColorTransfer.bakeLut(
            params.srcStats.toFloatArray(),
            params.refStats.toFloatArray(),
            ColorTransfer.LUT_SIZE,
        )
        assertArrayEquals(expected, lut, 0f)
    }

    @Test fun activeLutStaysInGamut() {
        val engine = FakeEngine()
        LookLutUpload.upload(engine, activeParams())
        engine.calls[0].lut!!.forEach { assertTrue("lut out of gamut: $it", it in 0f..1f) }
    }

    @Test fun uploadIsDeterministic() {
        val first = FakeEngine().also { LookLutUpload.upload(it, activeParams()) }.calls[0].lut!!
        val second = FakeEngine().also { LookLutUpload.upload(it, activeParams()) }.calls[0].lut!!
        assertArrayEquals(first, second, 0f)
    }

    // ---------- never throws: failure degrades to "no look transfer" ----------

    @Test fun engineFailureMarksUnavailableInsteadOfThrowing() {
        val engine = FakeEngine().apply { failRemaining = 1 }
        // Must not throw: the catch degrades to "no look transfer".
        LookLutUpload.upload(engine, activeParams())
        assertEquals(2, engine.calls.size)
        // The failed upload, then the unavailable marker.
        assertNotNull(engine.calls[0].lut)
        assertEquals(ColorTransfer.LUT_SIZE, engine.calls[0].n)
        assertNull(engine.calls[1].lut)
        assertEquals(0, engine.calls[1].n)
    }

    @Test fun cancellationExceptionStillPropagates() {
        val engine = FakeEngine()
        val cancel = kotlinx.coroutines.CancellationException("test cancel")
        engine.failWith = { cancel }
        engine.failRemaining = 1
        try {
            LookLutUpload.upload(engine, activeParams())
            fail("CancellationException must propagate")
        } catch (t: Throwable) {
            assertSame(cancel, t)
        }
        // The unavailable marker must NOT have been sent after cancellation.
        assertEquals(1, engine.calls.size)
    }
}
