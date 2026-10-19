package com.lumora.photoeditor.engine

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Executed verification for the pure-math seams of [Geometry] that the
 * existing [com.lumora.photoeditor.GeometryTest] does not cover: rotation
 * math, flips, the perspective-taper sign convention (corrected 2026-10-19),
 * the guided-perspective numeric solvers, Lightroom "Constrain to Image",
 * out-of-bounds flags, and the crop-overlay hit-test helpers.
 *
 * All functions under test are pure Kotlin (kotlin.math only), so this test
 * is covered by the standalone kotlinc harness as well as Gradle.
 */
class GeometryMathTest {

    private fun assertNear(expected: Float, actual: Float, eps: Float = 1e-3f) {
        assertTrue("expected=$expected actual=$actual", abs(expected - actual) <= eps)
    }

    /** Applies a column-major 3x3 to (x, y). */
    private fun apply(m: FloatArray, x: Float, y: Float): Pt {
        val w = m[2] * x + m[5] * y + m[8]
        assertTrue("degenerate matrix", abs(w) > 1e-9f)
        return Pt((m[0] * x + m[3] * y + m[6]) / w, (m[1] * x + m[4] * y + m[7]) / w)
    }

    // ---- totalRotateDeg ----

    @Test fun totalRotateDeg_defaultIsZero() {
        assertNear(0f, Geometry.totalRotateDeg(GeometryParams()))
    }

    @Test fun totalRotateDeg_fineAndQuarterTurns() {
        assertNear(15f, Geometry.totalRotateDeg(GeometryParams(rotate = 15f)))
        assertNear(90f, Geometry.totalRotateDeg(GeometryParams(rotate90 = 1)))
        assertNear(180f, Geometry.totalRotateDeg(GeometryParams(rotate90 = 2)))
        assertNear(-75f, Geometry.totalRotateDeg(GeometryParams(rotate = 15f, rotate90 = -1)))
    }

    // ---- transformedQuad flips / rotation ----

    @Test fun transformedQuad_flipHMirrorsHorizontally() {
        val q = Geometry.transformedQuad(CropParams(), GeometryParams(flipH = true))
        // TL(0,0) -> (1,0); TR(1,0) -> (0,0); BL(0,1) -> (1,1); BR(1,1) -> (0,1)
        assertNear(1f, q[0].x); assertNear(0f, q[0].y)
        assertNear(0f, q[1].x); assertNear(0f, q[1].y)
        assertNear(1f, q[2].x); assertNear(1f, q[2].y)
        assertNear(0f, q[3].x); assertNear(1f, q[3].y)
    }

    @Test fun transformedQuad_flipVMirrorsVertically() {
        val q = Geometry.transformedQuad(CropParams(), GeometryParams(flipV = true))
        assertNear(0f, q[0].x); assertNear(1f, q[0].y)
        assertNear(1f, q[1].x); assertNear(1f, q[1].y)
        assertNear(0f, q[2].x); assertNear(0f, q[2].y)
        assertNear(1f, q[3].x); assertNear(0f, q[3].y)
    }

    @Test fun transformedQuad_rotate180InvertsCorners() {
        val q = Geometry.transformedQuad(CropParams(), GeometryParams(rotate = 180f))
        assertNear(1f, q[0].x, 1e-5f); assertNear(1f, q[0].y, 1e-5f)
        assertNear(0f, q[3].x, 1e-5f); assertNear(0f, q[3].y, 1e-5f)
    }

    @Test fun transformedQuad_doubleFlipIsRotate180() {
        // flipH then flipV = point reflection through the center = 180° turn
        val a = Geometry.transformedQuad(CropParams(), GeometryParams(flipH = true, flipV = true))
        val b = Geometry.transformedQuad(CropParams(), GeometryParams(rotate = 180f))
        a.forEachIndexed { i, p ->
            assertNear(b[i].x, p.x, 1e-5f)
            assertNear(b[i].y, p.y, 1e-5f)
        }
    }

    // ---- transformedQuad perspective taper (corrected sign convention) ----

    @Test fun transformedQuad_positiveVNarrowsQuadTopEdge() {
        // +V narrows the quad's top edge (magnifies the rendered top),
        // correcting verticals that converge toward the top.
        val q = Geometry.transformedQuad(CropParams(), GeometryParams(perspectiveV = 20f))
        assertNear(0.1f, q[0].x, 1e-6f)  // TL moves right
        assertNear(0.9f, q[1].x, 1e-6f)  // TR moves left
        assertNear(0f, q[0].y, 1e-6f)
        // zero perspective: top edge spans the full width
        val z = Geometry.transformedQuad(CropParams(), GeometryParams())
        assertTrue(q[0].x > z[0].x && q[1].x < z[1].x)
    }

    @Test fun transformedQuad_positiveHLengthensQuadLeftEdge() {
        // +H lengthens the quad's left edge (minifies the rendered left).
        val q = Geometry.transformedQuad(CropParams(), GeometryParams(perspectiveH = 20f))
        assertNear(-0.1f, q[0].y, 1e-6f) // TL moves up
        assertNear(1.1f, q[2].y, 1e-6f)  // BL moves down
        assertNear(0f, q[0].x, 1e-6f)
    }

    @Test fun transformedQuad_negativeVWidensQuadTopEdge() {
        val q = Geometry.transformedQuad(CropParams(), GeometryParams(perspectiveV = -20f))
        assertNear(-0.1f, q[0].x, 1e-6f)
        assertNear(1.1f, q[1].x, 1e-6f)
    }

    // ---- quadOutOfBounds ----

    @Test fun quadOutOfBounds_defaultAllInside() {
        assertEquals(
            listOf(false, false, false, false),
            Geometry.quadOutOfBounds(CropParams(), GeometryParams()),
        )
    }

    @Test fun quadOutOfBounds_extremeKeystoneFlagsCorners() {
        val flags = Geometry.quadOutOfBounds(CropParams(), GeometryParams(perspectiveH = 100f))
        // TL.y = -0.5 and BL.y = 1.5 are outside [0,1]
        assertTrue(flags[0])
        assertTrue(flags[2])
        assertFalse(flags[1])
        assertFalse(flags[3])
    }

    // ---- distToSegment ----

    @Test fun distToSegment_perpendicular() {
        assertNear(1f, Geometry.distToSegment(0.5f, 1f, Pt(0f, 0f), Pt(1f, 0f)))
    }

    @Test fun distToSegment_onSegmentIsZero() {
        assertNear(0f, Geometry.distToSegment(0.25f, 0f, Pt(0f, 0f), Pt(1f, 0f)), 1e-6f)
    }

    @Test fun distToSegment_beyondEndpointUsesEndpoint() {
        assertNear(2f, Geometry.distToSegment(3f, 0f, Pt(0f, 0f), Pt(1f, 0f)))
        assertNear(sqrt(2f), Geometry.distToSegment(2f, 1f, Pt(0f, 0f), Pt(1f, 0f)))
    }

    @Test fun distToSegment_degenerateIsPointDistance() {
        assertNear(sqrt(8f), Geometry.distToSegment(2f, 2f, Pt(0f, 0f), Pt(0f, 0f)))
    }

    // ---- pointInQuad ----

    private val unitSquare = listOf(Pt(0f, 0f), Pt(1f, 0f), Pt(0f, 1f), Pt(1f, 1f))

    @Test fun pointInQuad_centerIsInside() {
        assertTrue(Geometry.pointInQuad(0.5f, 0.5f, unitSquare))
    }

    @Test fun pointInQuad_outsideIsOutside() {
        assertFalse(Geometry.pointInQuad(2f, 0.5f, unitSquare))
        assertFalse(Geometry.pointInQuad(0.5f, -0.5f, unitSquare))
    }

    @Test fun pointInQuad_edgeAndCornerCountAsInside() {
        assertTrue(Geometry.pointInQuad(0.5f, 0f, unitSquare))
        assertTrue(Geometry.pointInQuad(0f, 0f, unitSquare))
    }

    @Test fun pointInQuad_reflectedQuadStillWorks() {
        // flipH reverses winding; a consistent sign must still hold
        val q = Geometry.transformedQuad(CropParams(), GeometryParams(flipH = true))
        assertTrue(Geometry.pointInQuad(0.5f, 0.5f, q))
        assertFalse(Geometry.pointInQuad(2f, 2f, q))
    }

    @Test fun pointInQuad_wrongArityIsFalse() {
        assertFalse(Geometry.pointInQuad(0.5f, 0.5f, unitSquare.take(3)))
    }

    @Test fun pointInQuad_rotatedQuadCenterIsInside() {
        val q = Geometry.transformedQuad(CropParams(), GeometryParams(rotate = 30f))
        assertTrue(Geometry.pointInQuad(0.5f, 0.5f, q))
        assertFalse(Geometry.pointInQuad(0.01f, 0.01f, q))
    }

    // ---- constrainToImage ----

    @Test fun constrainToImage_identityUnchanged() {
        val full = CropParams()
        assertEquals(full, Geometry.constrainToImage(full, GeometryParams()))
    }

    @Test fun constrainToImage_rotate45ShrinksByOneOverSqrt2() {
        val geo = GeometryParams(rotate = 45f)
        val c = Geometry.constrainToImage(CropParams(), geo)
        val s = ((c.right - c.left) + (c.bottom - c.top)) / 2f
        assertNear(1f / sqrt(2f), s, 1e-3f)
        // rotated quad now fits inside the frame
        assertTrue(Geometry.quadOutOfBounds(c, geo).none { it })
        // maximal: a corner touches the frame edge
        val q = Geometry.transformedQuad(c, geo)
        assertTrue(q.any {
            abs(it.x) < 2e-3f || abs(it.x - 1f) < 2e-3f ||
                    abs(it.y) < 2e-3f || abs(it.y - 1f) < 2e-3f
        })
    }

    @Test fun constrainToImage_degenerateUnchanged() {
        val d = CropParams(left = 0.5f, top = 0.5f, right = 0.5f, bottom = 0.5f)
        assertEquals(d, Geometry.constrainToImage(d, GeometryParams(rotate = 30f)))
    }

    @Test fun constrainToImage_preservesAspectAndCenter() {
        val lb = CropParams(left = 0f, top = 0.21875f, right = 1f, bottom = 0.78125f)
        val geo = GeometryParams(rotate = 20f)
        val c = Geometry.constrainToImage(lb, geo)
        val w0 = lb.right - lb.left; val h0 = lb.bottom - lb.top
        val w1 = c.right - c.left; val h1 = c.bottom - c.top
        assertTrue(w1 < w0)
        assertNear(w0 / h0, w1 / h1, 1e-4f)
        assertNear(0.5f, (c.left + c.right) / 2f, 1e-6f)
        assertNear(0.5f, (c.top + c.bottom) / 2f, 1e-6f)
        assertTrue(Geometry.quadOutOfBounds(c, geo).none { it })
    }

    @Test fun constrainToImage_quarterTurnShrinksOffCenterCrop() {
        // tall strip offset from center: a 90° turn about its own center
        // pushes the quad's x corners past the frame edge
        val tall = CropParams(left = 0.4f, top = 0f, right = 1f, bottom = 1f)
        val geo = GeometryParams(rotate90 = 1)
        assertTrue(Geometry.quadOutOfBounds(tall, geo).any { it })
        val c = Geometry.constrainToImage(tall, geo)
        assertTrue(Geometry.quadOutOfBounds(c, geo).none { it })
        assertTrue((c.right - c.left) < (tall.right - tall.left))
        // aspect and center preserved
        assertNear(
            (tall.right - tall.left) / (tall.bottom - tall.top),
            (c.right - c.left) / (c.bottom - c.top), 1e-4f,
        )
        assertNear(0.7f, (c.left + c.right) / 2f, 1e-6f)
        assertNear(0.5f, (c.top + c.bottom) / 2f, 1e-6f)
    }

    // ---- solvePerspectiveV / solvePerspectiveH (round-trip through the full pipeline) ----

    @Test fun solvePerspectiveV_recoversKnownTaper() {
        val geo = GeometryParams(perspectiveV = 30f)
        val m = Geometry.uvMatrix(1000, 1000, 2000, 2000, CropParams(), geo)
        // an off-center line that is vertical on screen under the +30 taper
        val u1 = apply(m, -0.2f, -0.5f)
        val u2 = apply(m, -0.2f, 0.5f)
        val solved = Geometry.solvePerspectiveV(
            u1, u2, 1000, 1000, 2000, 2000,
            CropParams(), geo.copy(perspectiveV = 0f),
        )
        assertNotNull(solved)
        assertNear(30f, solved!!, 1f)
    }

    @Test fun solvePerspectiveV_degenerateSegmentIsNull() {
        val u = Pt(0.4f, 0.6f)
        assertNull(
            Geometry.solvePerspectiveV(
                u, u, 1000, 1000, 2000, 2000,
                CropParams(), GeometryParams(),
            )
        )
    }

    @Test fun solvePerspectiveV_centerSymmetricLineIsNull() {
        // a guide through the frame's vertical center carries no information
        assertNull(
            Geometry.solvePerspectiveV(
                Pt(0.5f, 0.3f), Pt(0.5f, 0.7f),
                1000, 1000, 2000, 2000,
                CropParams(), GeometryParams(),
            )
        )
    }

    @Test fun solvePerspectiveH_recoversKnownTaper() {
        val geo = GeometryParams(perspectiveH = 30f)
        val m = Geometry.uvMatrix(1000, 1000, 2000, 2000, CropParams(), geo)
        val u1 = apply(m, -0.5f, 0.2f)
        val u2 = apply(m, 0.5f, 0.2f)
        val solved = Geometry.solvePerspectiveH(
            u1, u2, 1000, 1000, 2000, 2000,
            CropParams(), geo.copy(perspectiveH = 0f),
        )
        assertNotNull(solved)
        assertNear(30f, solved!!, 1f)
    }

    @Test fun solvePerspectiveH_degenerateSegmentIsNull() {
        val u = Pt(0.4f, 0.6f)
        assertNull(
            Geometry.solvePerspectiveH(
                u, u, 1000, 1000, 2000, 2000,
                CropParams(), GeometryParams(),
            )
        )
    }

    @Test fun solvePerspectiveH_centerSymmetricLineIsNull() {
        assertNull(
            Geometry.solvePerspectiveH(
                Pt(0.3f, 0.5f), Pt(0.7f, 0.5f),
                1000, 1000, 2000, 2000,
                CropParams(), GeometryParams(),
            )
        )
    }
}
