package com.lumora.photoeditor.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * First-ever executed verification of [applyAspect] — the crop-geometry math
 * behind the Geometry panel's aspect presets (backlog #3, crop UX).
 *
 * The real source files are compiled by the standalone JVM harness
 * (hidden_files/geometryaspect-harness/run.sh).
 */
class ApplyAspectTest {

    private val eps = 1e-5f

    @Test fun `null aspect keeps rect and clears flag`() {
        val crop = CropParams(0.2f, 0.3f, 0.8f, 0.7f, aspect = 1.5f, locked = true)
        val out = applyAspect(crop, null)
        assertEquals(0.2f, out.left, eps)
        assertEquals(0.3f, out.top, eps)
        assertEquals(0.8f, out.right, eps)
        assertEquals(0.7f, out.bottom, eps)
        assertNull(out.aspect)
        assertEquals(true, out.locked)
    }

    @Test fun `square on full image fills image`() {
        val out = applyAspect(CropParams(), 1f)
        assertEquals(0f, out.left, eps)
        assertEquals(0f, out.top, eps)
        assertEquals(1f, out.right, eps)
        assertEquals(1f, out.bottom, eps)
        assertEquals(1f, out.aspect ?: -1f, eps)
    }

    @Test fun `16x9 on square letterboxes vertically`() {
        val out = applyAspect(CropParams(), 16f / 9f)
        assertEquals(0f, out.left, eps)
        assertEquals(1f, out.right, eps)
        assertEquals(0.21875f, out.top, eps)
        assertEquals(0.78125f, out.bottom, eps)
    }

    @Test fun `9x16 on square pillars horizontally`() {
        val out = applyAspect(CropParams(), 9f / 16f)
        assertEquals(0.21875f, out.left, eps)
        assertEquals(0.78125f, out.right, eps)
        assertEquals(0f, out.top, eps)
        assertEquals(1f, out.bottom, eps)
    }

    @Test fun `aspect wider than crop keeps height and narrows width`() {
        // crop is 2:1 (cw=1, ch=0.5); target 16:9 -> h=0.5, w=0.5*16/9
        val out = applyAspect(CropParams(top = 0.25f, bottom = 0.75f), 16f / 9f)
        assertEquals(0.25f, out.top, eps)
        assertEquals(0.75f, out.bottom, eps)
        assertEquals((1f - 0.8888889f) / 2f, out.left, eps)
        assertEquals(1f - (1f - 0.8888889f) / 2f, out.right, eps)
    }

    @Test fun `square aspect inside small centered crop`() {
        // cw=0.6, ch=0.4, center (0.5,0.5): 1:1 -> h=0.4, w=0.4
        val out = applyAspect(CropParams(0.2f, 0.3f, 0.8f, 0.7f), 1f)
        assertEquals(0.3f, out.left, eps)
        assertEquals(0.7f, out.right, eps)
        assertEquals(0.3f, out.top, eps)
        assertEquals(0.7f, out.bottom, eps)
    }

    @Test fun `fitted rect stays centered on off-center crop`() {
        // crop (0,0)-(0.6,0.6), center (0.3,0.3); 1:1 keeps it unchanged
        val out = applyAspect(CropParams(right = 0.6f, bottom = 0.6f), 1f)
        assertEquals(0f, out.left, eps)
        assertEquals(0.6f, out.right, eps)
        assertEquals(0f, out.top, eps)
        assertEquals(0.6f, out.bottom, eps)
    }

    @Test fun `aspect flag is set to the requested aspect`() {
        val out = applyAspect(CropParams(), 4f / 3f)
        assertEquals(4f / 3f, out.aspect ?: 0f, eps)
    }

    @Test fun `output rect always stays inside 0-1`() {
        val cases = listOf(
            CropParams(0.9f, 0f, 1f, 1f) to 0.1f,
            CropParams(0f, 0.9f, 1f, 1f) to 10f,
            CropParams(0f, 0f, 0.4f, 1f) to 0.05f,
            CropParams(0f, 0f, 1f, 0.2f) to 20f,
        )
        for ((crop, aspect) in cases) {
            val out = applyAspect(crop, aspect)
            for ((name, v) in listOf("left" to out.left, "top" to out.top, "right" to out.right, "bottom" to out.bottom)) {
                assertEquals("aspect=$aspect $name", v.coerceIn(0f, 1f), v, eps)
            }
        }
    }
}
