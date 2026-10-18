package com.lumora.photoeditor

import com.lumora.photoeditor.engine.CropParams
import com.lumora.photoeditor.engine.Geometry
import com.lumora.photoeditor.engine.GeometryParams
import com.lumora.photoeditor.engine.Pt
import com.lumora.photoeditor.ui.panels.applyAspect
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class GeometryTest {

    private fun assertNear(expected: Float, actual: Float, eps: Float = 1e-3f) {
        assertTrue("expected=$expected actual=$actual", abs(expected - actual) <= eps)
    }

    @Test fun homography_identity() {
        val pts = listOf(Pt(-1f, -1f), Pt(1f, -1f), Pt(-1f, 1f), Pt(1f, 1f))
        val h = Geometry.homography(pts, pts)
        // identity in column-major
        assertNear(1f, h[0]); assertNear(0f, h[1]); assertNear(0f, h[2])
        assertNear(0f, h[3]); assertNear(1f, h[4]); assertNear(0f, h[5])
        assertNear(0f, h[6]); assertNear(0f, h[7]); assertNear(1f, h[8])
    }

    @Test fun homography_translation() {
        val from = listOf(Pt(-1f, -1f), Pt(1f, -1f), Pt(-1f, 1f), Pt(1f, 1f))
        val to = from.map { Pt(it.x + 0.5f, it.y - 0.25f) }
        val h = Geometry.homography(from, to)
        // apply to (0,0) -> expect (0.5, -0.25)
        fun apply(x: Float, y: Float): Pt {
            val w = h[2] * x + h[5] * y + h[8]
            return Pt((h[0] * x + h[3] * y + h[6]) / w, (h[1] * x + h[4] * y + h[7]) / w)
        }
        val p = apply(0f, 0f)
        assertNear(0.5f, p.x); assertNear(-0.25f, p.y)
    }

    @Test fun invert3_roundTrip() {
        val from = listOf(Pt(-1f, -1f), Pt(1f, -1f), Pt(-1f, 1f), Pt(1f, 1f))
        val to = listOf(Pt(0f, 0f), Pt(0.8f, 0.1f), Pt(0.05f, 0.9f), Pt(0.85f, 1f))
        val h = Geometry.homography(from, to)
        val inv = Geometry.invert3(h)!!
        val id = Geometry.mul3(h, inv)
        assertNear(1f, id[0]); assertNear(0f, id[1]); assertNear(0f, id[3])
        assertNear(1f, id[4]); assertNear(1f, id[8])
        assertTrue(abs(id[6]) < 1e-3f && abs(id[7]) < 1e-3f)
    }

    @Test fun uvMatrix_identityCropMapsCenterToCenter() {
        // square image, square view, no crop/rotate: NDC center -> UV center
        val m = Geometry.uvMatrix(1000, 1000, 2000, 2000, CropParams(), GeometryParams())
        val inv = Geometry.invert3(m)!!
        // forward: uv = M * ndc ; check corner mapping instead via inverse:
        // NDC (0,0) should map to UV (0.5, 0.5)
        val w = m[2] * 0f + m[5] * 0f + m[8]
        val u = (m[0] * 0f + m[3] * 0f + m[6]) / w
        val v = (m[1] * 0f + m[4] * 0f + m[7]) / w
        assertNear(0.5f, u, 0.02f)
        assertNear(0.5f, v, 0.02f)
        assertNotNull(inv)
    }

    @Test fun touchToUv_centerOfView() {
        val m = Geometry.uvMatrix(1000, 1000, 2000, 2000, CropParams(), GeometryParams())
        val uv = Geometry.touchToUv(500f, 500f, 1000, 1000, m)
        assertNear(0.5f, uv.x, 0.02f)
        assertNear(0.5f, uv.y, 0.02f)
    }

    @Test fun transformedQuad_noOpIsUnitSquare() {
        val q = Geometry.transformedQuad(CropParams(), GeometryParams())
        assertNear(0f, q[0].x); assertNear(0f, q[0].y)
        assertNear(1f, q[1].x); assertNear(0f, q[1].y)
        assertNear(0f, q[2].x); assertNear(1f, q[2].y)
        assertNear(1f, q[3].x); assertNear(1f, q[3].y)
    }

    @Test fun transformedQuad_rotate90Swaps() {
        val q = Geometry.transformedQuad(CropParams(), GeometryParams(rotate = 90f))
        // center stays center
        val cx = q.map { it.x }.average()
        val cy = q.map { it.y }.average()
        assertNear(0.5f, cx.toFloat(), 1e-3f)
        assertNear(0.5f, cy.toFloat(), 1e-3f)
        // corners map to corners (rotated)
        val xs = q.map { it.x }.toSet()
        assertTrue(xs.all { abs(it) < 1e-3f || abs(it - 1f) < 1e-3f })
    }

    // ---- applyAspect (cycle 172: Lightroom-parity crop aspects) ----

    private fun rectOf(c: CropParams): FloatArray =
        floatArrayOf(c.right - c.left, c.bottom - c.top,
            (c.left + c.right) / 2f, (c.top + c.bottom) / 2f)

    @Test fun applyAspect_squareOnFullFrame() {
        val out = applyAspect(CropParams(), 1f)
        val (w, h, cx, cy) = rectOf(out)
        assertNear(0.5f, cx); assertNear(0.5f, cy)
        assertNear(w, h)
        assertNear(1f, out.aspect!!)
        assertTrue(out.left >= 0f && out.top >= 0f &&
            out.right <= 1f && out.bottom <= 1f)
    }

    @Test fun applyAspect_wideOnSquareLimitsHeight() {
        val out = applyAspect(CropParams(), 16f / 9f)
        val (w, h, cx, cy) = rectOf(out)
        assertNear(1f, w)
        assertNear(9f / 16f, h, 1e-4f)
        assertNear(16f / 9f, w / h, 1e-3f)
        assertNear(0.5f, cx); assertNear(0.5f, cy)
    }

    @Test fun applyAspect_portraitOnSquareLimitsWidth() {
        val out = applyAspect(CropParams(), 3f / 4f)
        val (w, h, cx, cy) = rectOf(out)
        assertNear(0.75f, w, 1e-4f)
        assertNear(1f, h)
        assertNear(3f / 4f, w / h, 1e-3f)
        assertNear(0.5f, cx); assertNear(0.5f, cy)
    }

    @Test fun applyAspect_fourFiveAndFiveSevenRatios() {
        listOf(4f / 5f to 0.8f, 5f / 7f to (5f / 7f)).forEach { (aspect, _) ->
            val out = applyAspect(CropParams(), aspect)
            val (w, h, cx, cy) = rectOf(out)
            assertNear(aspect, w / h, 1e-3f)
            assertTrue(out.left >= 0f && out.top >= 0f &&
                out.right <= 1f && out.bottom <= 1f)
            assertNear(0.5f, cx); assertNear(0.5f, cy)
        }
    }

    @Test fun applyAspect_centersOnCurrentRect() {
        val src = CropParams(left = 0.2f, top = 0.3f, right = 0.6f, bottom = 0.7f)
        val out = applyAspect(src, 1f)
        val (w, h, cx, cy) = rectOf(out)
        assertNear(0.4f, cx); assertNear(0.5f, cy)
        // 0.4 x 0.4 rect: square is limited by width
        assertNear(0.4f, w); assertNear(0.4f, h)
        assertNear(0.2f, out.left); assertNear(0.3f, out.top)
    }

    @Test fun applyAspect_freeClearsAspectKeepsRect() {
        val src = CropParams(left = 0.1f, top = 0.1f, right = 0.9f,
            bottom = 0.8f, aspect = 2f)
        val out = applyAspect(src, null)
        assertNull(out.aspect)
        assertNear(0.1f, out.left); assertNear(0.1f, out.top)
        assertNear(0.9f, out.right); assertNear(0.8f, out.bottom)
    }
}
