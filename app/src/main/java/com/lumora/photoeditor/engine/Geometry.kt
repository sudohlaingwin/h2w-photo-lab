package com.lumora.photoeditor.engine

import kotlin.math.*

/** 2D point. */
data class Pt(val x: Float, val y: Float)

/**
 * Computes the 3x3 homography (column-major FloatArray, for GLSL mat3) that
 * maps clip-space quad corners (-1..1) to source-image UVs, applying crop,
 * rotation, flips and keystone perspective with aspect-correct fit into the
 * view. Areas outside the mapped quad are letterboxed (shader discards).
 */
object Geometry {

    /**
     * @param viewW viewH size of the preview surface in pixels
     * @param srcW srcH source image size in pixels
     */
    fun uvMatrix(
        viewW: Int, viewH: Int, srcW: Int, srcH: Int,
        crop: CropParams, geo: GeometryParams,
    ): FloatArray {
        // Keystoned quad in source UV (a true trapezoid when perspective
        // is non-zero).
        val quad = transformedQuad(crop, geo)
        // The un-keystoned quad: the NDC corners are fitted to THIS rect so
        // the photo keeps filling the view, and the homography then warps it
        // projectively onto the keystoned quad. Fitting the keystoned
        // quad's own bounding box instead would force the homography to be
        // affine (it is uniquely determined by the 4 fitted corners) and
        // silently cancel the keystone — that is why the old perspective
        // sliders had no visible effect. With perspective at 0, quad ==
        // base and this is bit-identical to the old construction.
        val base = transformedQuad(
            crop, geo.copy(perspectiveV = 0f, perspectiveH = 0f))
        // to display pixels
        val pts = base.map { Pt(it.x * srcW, it.y * srcH) }
        val minX = pts.minOf { it.x }; val maxX = pts.maxOf { it.x }
        val minY = pts.minOf { it.y }; val maxY = pts.maxOf { it.y }
        val bw = max(maxX - minX, 1f); val bh = max(maxY - minY, 1f)
        val s = min(viewW / bw, viewH / bh)
        val cx = (minX + maxX) / 2f; val cy = (minY + maxY) / 2f
        // NDC corners (y up) for the fitted quad
        fun toNdc(p: Pt): Pt {
            val vx = (p.x - cx) * s + viewW / 2f
            val vy = (p.y - cy) * s + viewH / 2f
            return Pt(vx / viewW * 2f - 1f, 1f - vy / viewH * 2f)
        }
        val ndc = base.map { toNdc(Pt(it.x * srcW, it.y * srcH)) }
        // We want H: ndc -> uv. Solve with correspondences ndc[i] -> quad[i].
        return homography(ndc, quad)
    }

    /** Maps a view touch point (px) to source UV via the NDC->UV matrix. */
    fun touchToUv(
        touchX: Float, touchY: Float, viewW: Int, viewH: Int, uvMat: FloatArray,
    ): Pt {
        val nx = touchX / viewW * 2f - 1f
        val ny = 1f - touchY / viewH * 2f
        val x = uvMat[0] * nx + uvMat[3] * ny + uvMat[6]
        val y = uvMat[1] * nx + uvMat[4] * ny + uvMat[7]
        val w = uvMat[2] * nx + uvMat[5] * ny + uvMat[8]
        if (abs(w) < 1e-9f) return Pt(-1f, -1f)
        return Pt(x / w, y / w)
    }

    /** Total rotation in degrees: fine-tune slider plus quarter turns. */
    fun totalRotateDeg(geo: GeometryParams): Float = geo.rotate + 90f * geo.rotate90

    /**
     * Lightroom "Constrain to Image": shrinks [crop] about its own center to
     * the largest rectangle with the same aspect ratio whose transformed
     * quad ([transformedQuad]) lies entirely inside the source-image [0,1]^2
     * bounds — so rotated / keystoned photos never render black corners.
     *
     * The map from crop scale to quad corner is linear in the scale factor
     * (rotation/flips are about the crop center, and the keystone taper
     * terms scale with the rect's own width/height), so feasibility is
     * monotone and a binary search finds the maximal inscribed scale.
     * Returns [crop] unchanged when it already fits (idempotent) or when the
     * rect is degenerate. Deterministic geometry, no machine learning.
     */
    fun constrainToImage(crop: CropParams, geo: GeometryParams): CropParams {
        val w = crop.right - crop.left; val h = crop.bottom - crop.top
        if (w <= 0f || h <= 0f) return crop
        if (quadInsideImage(crop, geo)) return crop
        var lo = 0f; var hi = 1f
        repeat(40) {
            val mid = (lo + hi) / 2f
            if (quadInsideImage(scaleAboutCenter(crop, mid), geo)) lo = mid else hi = mid
        }
        return scaleAboutCenter(crop, lo)
    }

    /** True when every transformed quad corner is inside [0,1]^2 (with tolerance). */
    private fun quadInsideImage(crop: CropParams, geo: GeometryParams, eps: Float = 1e-4f): Boolean =
        transformedQuad(crop, geo).all {
            it.x >= -eps && it.x <= 1f + eps && it.y >= -eps && it.y <= 1f + eps
        }

    /** Per-corner flags for the transformed crop quad (TL, TR, BL, BR —
     *  same order as [transformedQuad]): true where the corner lies outside
     *  the source image [0,1]^2 (same tolerance as [quadInsideImage]).
     *  The crop overlay uses these to warn about out-of-bounds corners,
     *  which render black in preview and export. */
    fun quadOutOfBounds(crop: CropParams, geo: GeometryParams, eps: Float = 1e-4f): List<Boolean> =
        transformedQuad(crop, geo).map {
            it.x < -eps || it.x > 1f + eps || it.y < -eps || it.y > 1f + eps
        }

    /** Distance from point (px, py) to the segment a -> b. All coordinates
     *  must be in the same space (the crop overlay hit-tests in view
     *  pixels). Pure math, no Android dependency — covered by the
     *  standalone kotlinc harness. */
    fun distToSegment(px: Float, py: Float, a: Pt, b: Pt): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        if (len2 < 1e-9f) return hypot(px - a.x, py - a.y)
        val t = (((px - a.x) * dx + (py - a.y) * dy) / len2).coerceIn(0f, 1f)
        return hypot(px - (a.x + t * dx), py - (a.y + t * dy))
    }

    /** Point-in-convex-quad test. [quad] must be in TL, TR, BL, BR order
     *  (the order [transformedQuad] returns), so polygon order is
     *  0 -> 1 -> 3 -> 2. Rotation, flips and the perspective taper all
     *  preserve convexity, so a consistent cross-product sign means inside;
     *  points exactly on an edge count as inside. Pure math, no Android
     *  dependency — covered by the standalone kotlinc harness. */
    fun pointInQuad(px: Float, py: Float, quad: List<Pt>): Boolean {
        if (quad.size != 4) return false
        val o = listOf(quad[0], quad[1], quad[3], quad[2])
        var sign = 0
        for (i in o.indices) {
            val a = o[i]
            val b = o[(i + 1) % o.size]
            val cross = (b.x - a.x) * (py - a.y) - (b.y - a.y) * (px - a.x)
            val s = when {
                cross > 1e-4f -> 1
                cross < -1e-4f -> -1
                else -> continue
            }
            if (sign == 0) sign = s else if (sign != s) return false
        }
        return true
    }

    private fun scaleAboutCenter(crop: CropParams, s: Float): CropParams {
        val cx = (crop.left + crop.right) / 2f; val cy = (crop.top + crop.bottom) / 2f
        val hw = (crop.right - crop.left) / 2f * s; val hh = (crop.bottom - crop.top) / 2f * s
        return crop.copy(left = cx - hw, top = cy - hh, right = cx + hw, bottom = cy + hh)
    }

    /** Crop rect corners with rotation / flips / keystone applied, in source UV. */
    fun transformedQuad(crop: CropParams, geo: GeometryParams): List<Pt> {
        val l = crop.left; val t = crop.top; val r = crop.right; val b = crop.bottom
        var q = listOf(Pt(l, t), Pt(r, t), Pt(l, b), Pt(r, b)) // TL TR BL BR
        val cx = (l + r) / 2f; val cy = (t + b) / 2f
        // rotate about center
        val rad = Math.toRadians(totalRotateDeg(geo).toDouble()).toFloat()
        val cos = cos(rad); val sin = sin(rad)
        q = q.map {
            val dx = it.x - cx; val dy = it.y - cy
            Pt(cx + dx * cos - dy * sin, cy + dx * sin + dy * cos)
        }
        if (geo.flipH) q = q.map { Pt(2 * cx - it.x, it.y) }
        if (geo.flipV) q = q.map { Pt(it.x, 2 * cy - it.y) }
        // Keystone perspective: taper opposite edges so the quad becomes a
        // true trapezoid (projective warp). +V widens the top relative to
        // the bottom (corrects verticals converging toward the top when the
        // camera is tilted up); +H lengthens the left edge relative to the
        // right. NOTE: the old code shifted both edges the same way, which
        // is a uniform scale about the center — the bounding-box fit below
        // cancelled it, so the old sliders were a visual no-op.
        val pv = geo.perspectiveV / 100f; val ph = geo.perspectiveH / 100f
        val w = r - l; val h = b - t
        q = listOf(
            Pt(q[0].x - pv * w * 0.5f, q[0].y - ph * h * 0.5f), // TL
            Pt(q[1].x + pv * w * 0.5f, q[1].y + ph * h * 0.5f), // TR
            Pt(q[2].x + pv * w * 0.5f, q[2].y + ph * h * 0.5f), // BL
            Pt(q[3].x - pv * w * 0.5f, q[3].y - ph * h * 0.5f), // BR
        )
        return q
    }

    /**
     * Solves the vertical-keystone amount (perspectiveV, -100..100) that
     * makes the source-UV segment u1->u2 appear vertical on screen. The solve
     * is numeric over the full render pipeline (taper + aspect fit +
     * homography), so rotation/flips are accounted for automatically.
     *
     * Returns null when the segment is degenerate (the two taps coincide)
     * or the line cannot be made vertical within the slider range — the
     * caller should keep the guide active for another try. Coarse-scan
     * points that are degenerate at extreme tapers are skipped rather than
     * aborting the solve. Deterministic pixel math, no machine learning.
     */
    fun solvePerspectiveV(
        u1: Pt, u2: Pt,
        viewW: Int, viewH: Int, srcW: Int, srcH: Int,
        crop: CropParams, geo: GeometryParams,
    ): Float? {
        if (hypot(u2.x - u1.x, u2.y - u1.y) < 1e-4f) return null
        // On-screen x separation of the two endpoints for a candidate
        // perspectiveV fraction (-1..1); zero = the line reads vertical.
        fun screenDx(pvFrac: Float): Float {
            val m = uvMatrix(
                viewW, viewH, srcW, srcH, crop,
                geo.copy(perspectiveV = (pvFrac * 100f).coerceIn(-100f, 100f)),
            )
            val inv = invert3(m) ?: return Float.NaN
            fun ndcX(u: Pt): Float {
                val w = inv[2] * u.x + inv[5] * u.y + inv[8]
                if (abs(w) < 1e-9f) return Float.NaN
                return (inv[0] * u.x + inv[3] * u.y + inv[6]) / w
            }
            val a = ndcX(u1); val b = ndcX(u2)
            return if (a.isNaN() || b.isNaN()) Float.NaN else a - b
        }
        if (screenDx(0f).isNaN()) return null
        // Coarse global scan (the separation is smooth in pv, but scan first
        // so a local ternary refinement can't get stuck on a wrong lobe).
        // Also measures how much the line responds to pv at all: a guide
        // drawn through the frame's center of symmetry stays vertical for
        // every pv, so it carries no information — treat it as unusable.
        var best = 0f; var bestAbs = abs(screenDx(0f)); var maxAbs = bestAbs
        val n = 40
        for (i in 0..n) {
            val pv = -1f + 2f * i / n
            val v = abs(screenDx(pv))
            // Extreme tapers can make a scan point degenerate (NaN) for
            // lines near the frame edges — skip it instead of aborting the
            // whole solve; the answer usually lies in the sane region.
            if (v.isNaN()) continue
            if (v < bestAbs) { bestAbs = v; best = pv }
            if (v > maxAbs) maxAbs = v
        }
        if (maxAbs - bestAbs < 0.001f) return null
        // Ternary refinement around the best candidate.
        var lo = (best - 2f / n).coerceIn(-1f, 1f)
        var hi = (best + 2f / n).coerceIn(-1f, 1f)
        repeat(24) {
            val m1 = lo + (hi - lo) / 3f; val m2 = hi - (hi - lo) / 3f
            val a1 = abs(screenDx(m1)); val a2 = abs(screenDx(m2))
            if (a1.isNaN() || a2.isNaN()) return null
            if (a1 < a2) hi = m2 else lo = m1
        }
        val pv = (lo + hi) / 2f
        // Residual gate: the endpoints must agree on screen x within ~0.2%
        // of the view width, otherwise the guide line wasn't usable.
        if (abs(screenDx(pv)) > 0.002f) return null
        return (pv * 100f).coerceIn(-100f, 100f)
    }

    /**
     * Solves the horizontal-keystone amount (perspectiveH, -100..100) that
     * makes the source-UV segment u1->u2 appear horizontal on screen. The
     * solve is numeric over the full render pipeline (taper + aspect fit +
     * homography), so rotation/flips are accounted for automatically.
     *
     * Returns null when the segment is degenerate (the two taps coincide)
     * or the line cannot be made horizontal within the slider range — the
     * caller should keep the guide active for another try. Coarse-scan
     * points that are degenerate at extreme tapers are skipped rather than
     * aborting the solve. Deterministic pixel math, no machine learning.
     */
    fun solvePerspectiveH(
        u1: Pt, u2: Pt,
        viewW: Int, viewH: Int, srcW: Int, srcH: Int,
        crop: CropParams, geo: GeometryParams,
    ): Float? {
        if (hypot(u2.x - u1.x, u2.y - u1.y) < 1e-4f) return null
        // On-screen y separation of the two endpoints for a candidate
        // perspectiveH fraction (-1..1); zero = the line reads horizontal.
        fun screenDy(phFrac: Float): Float {
            val m = uvMatrix(
                viewW, viewH, srcW, srcH, crop,
                geo.copy(perspectiveH = (phFrac * 100f).coerceIn(-100f, 100f)),
            )
            val inv = invert3(m) ?: return Float.NaN
            fun ndcY(u: Pt): Float {
                val w = inv[2] * u.x + inv[5] * u.y + inv[8]
                if (abs(w) < 1e-9f) return Float.NaN
                return (inv[1] * u.x + inv[4] * u.y + inv[7]) / w
            }
            val a = ndcY(u1); val b = ndcY(u2)
            return if (a.isNaN() || b.isNaN()) Float.NaN else a - b
        }
        if (screenDy(0f).isNaN()) return null
        // Coarse global scan (the separation is smooth in ph, but scan first
        // so a local ternary refinement can't get stuck on a wrong lobe).
        // Also measures how much the line responds to ph at all: a guide
        // drawn through the frame's center of symmetry stays horizontal for
        // every ph, so it carries no information — treat it as unusable.
        var best = 0f; var bestAbs = abs(screenDy(0f)); var maxAbs = bestAbs
        val n = 40
        for (i in 0..n) {
            val ph = -1f + 2f * i / n
            val v = abs(screenDy(ph))
            // Extreme tapers can make a scan point degenerate (NaN) for
            // lines near the frame edges — skip it instead of aborting the
            // whole solve; the answer usually lies in the sane region.
            if (v.isNaN()) continue
            if (v < bestAbs) { bestAbs = v; best = ph }
            if (v > maxAbs) maxAbs = v
        }
        if (maxAbs - bestAbs < 0.001f) return null
        // Ternary refinement around the best candidate.
        var lo = (best - 2f / n).coerceIn(-1f, 1f)
        var hi = (best + 2f / n).coerceIn(-1f, 1f)
        repeat(24) {
            val m1 = lo + (hi - lo) / 3f; val m2 = hi - (hi - lo) / 3f
            val a1 = abs(screenDy(m1)); val a2 = abs(screenDy(m2))
            if (a1.isNaN() || a2.isNaN()) return null
            if (a1 < a2) hi = m2 else lo = m1
        }
        val ph = (lo + hi) / 2f
        // Residual gate: the endpoints must agree on screen y within ~0.2%
        // of the view height, otherwise the guide line wasn't usable.
        if (abs(screenDy(ph)) > 0.002f) return null
        return (ph * 100f).coerceIn(-100f, 100f)
    }

    /** Solves the 3x3 homography mapping [from] -> [to] (4 point pairs), column-major. */
    fun homography(from: List<Pt>, to: List<Pt>): FloatArray {
        require(from.size == 4 && to.size == 4)
        // Build 8x8 system Ah = b for h = [h11,h12,h13,h21,h22,h23,h31,h32], h33 = 1
        val a = Array(8) { DoubleArray(8) }
        val b = DoubleArray(8)
        for (i in 0 until 4) {
            val (x, y) = from[i]; val (u, v) = to[i]
            val r1 = i * 2; val r2 = i * 2 + 1
            a[r1][0] = x.toDouble(); a[r1][1] = y.toDouble(); a[r1][2] = 1.0
            a[r1][6] = (-u * x).toDouble(); a[r1][7] = (-u * y).toDouble()
            b[r1] = u.toDouble()
            a[r2][3] = x.toDouble(); a[r2][4] = y.toDouble(); a[r2][5] = 1.0
            a[r2][6] = (-v * x).toDouble(); a[r2][7] = (-v * y).toDouble()
            b[r2] = v.toDouble()
        }
        val h = solve(a, b)
        // column-major: [h11,h21,h31, h12,h22,h32, h13,h23,h33]
        return floatArrayOf(
            h[0].toFloat(), h[3].toFloat(), h[6].toFloat(),
            h[1].toFloat(), h[4].toFloat(), h[7].toFloat(),
            h[2].toFloat(), h[5].toFloat(), 1f,
        )
    }

    /** Column-major 3x3 multiply: returns a*b. */
    fun mul3(a: FloatArray, b: FloatArray): FloatArray {
        val o = FloatArray(9)
        for (c in 0 until 3) for (r in 0 until 3) {
            o[c * 3 + r] = a[r] * b[c * 3] + a[3 + r] * b[c * 3 + 1] + a[6 + r] * b[c * 3 + 2]
        }
        return o
    }

    fun invert3(m: FloatArray): FloatArray? {
        // m column-major
        val a = m[0]; val b = m[3]; val c = m[6]
        val d = m[1]; val e = m[4]; val f = m[7]
        val g = m[2]; val h = m[5]; val i = m[8]
        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        if (abs(det) < 1e-12f) return null
        val inv = 1f / det
        // adjugate transposed, stored column-major:
        // col0 = (ei-fh, fg-di, dh-eg), col1 = (ch-bi, ai-cg, bg-ah), col2 = (bf-ce, cd-af, ae-bd)
        return floatArrayOf(
            (e * i - f * h) * inv, (f * g - d * i) * inv, (d * h - e * g) * inv,
            (c * h - b * i) * inv, (a * i - c * g) * inv, (b * g - a * h) * inv,
            (b * f - c * e) * inv, (c * d - a * f) * inv, (a * e - b * d) * inv,
        )
    }

    private fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray {
        val n = b.size
        val m = Array(n) { r -> DoubleArray(n + 1) { c -> if (c < n) a[r][c] else b[r] } }
        for (col in 0 until n) {
            var piv = col
            for (row in col + 1 until n) if (abs(m[row][col]) > abs(m[piv][col])) piv = row
            val tmp = m[col]; m[col] = m[piv]; m[piv] = tmp
            val div = m[col][col]
            if (abs(div) < 1e-12) continue
            for (c in col..n) m[col][c] /= div
            for (row in 0 until n) {
                if (row == col) continue
                val f = m[row][col]
                for (c in col..n) m[row][c] -= f * m[col][c]
            }
        }
        return DoubleArray(n) { m[it][n] }
    }
}
