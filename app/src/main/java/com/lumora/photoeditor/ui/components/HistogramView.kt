package com.lumora.photoeditor.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumora.photoeditor.ui.state.Histogram
import com.lumora.photoeditor.engine.SKIN_TONE_U
import com.lumora.photoeditor.engine.SKIN_TONE_V
import com.lumora.photoeditor.engine.Vectorscope
import com.lumora.photoeditor.engine.Waveform
import com.lumora.photoeditor.engine.RgbParade
import com.lumora.photoeditor.ui.theme.Lumora
import kotlin.math.roundToInt

/**
 * RGB histogram with luminance outline, drawn from the live render.
 *
 * Lightroom-style clipping warnings: a blue triangle in the top-left corner
 * lights up when shadows clip, a red triangle in the top-right when highlights
 * clip. The fractions are already computed from the live render ([Histogram.clippedShadows]
 * / [Histogram.clippedHighlights]) — this just surfaces them where the eye is.
 */
@Composable
fun HistogramView(
    histogram: Histogram,
    modifier: Modifier = Modifier,
    height: Dp = 72.dp,
) {
    val colors = Lumora.colors
    val shadowPct = (histogram.clippedShadows * 100).roundToInt()
    val highlightPct = (histogram.clippedHighlights * 100).roundToInt()
    val shadowClip = histogram.clippedShadows >= CLIP_WARN_FRACTION
    val highlightClip = histogram.clippedHighlights >= CLIP_WARN_FRACTION
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .semantics {
                contentDescription = buildString {
                    append("Histogram")
                    append(if (shadowClip) "; shadow clipping $shadowPct%" else "; no shadow clipping")
                    append(if (highlightClip) "; highlight clipping $highlightPct%" else "; no highlight clipping")
                }
            }
    ) {
        val bins = Histogram.BINS
        val w = size.width; val h = size.height
        val bw = w / bins
        fun peak(arr: IntArray): Int = (arr.maxOrNull() ?: 1).coerceAtLeast(1)

        fun drawChannel(arr: IntArray, color: Color, alpha: Float) {
            val p = peak(arr)
            val path = Path()
            path.moveTo(0f, h)
            for (i in 0 until bins) {
                val x = i * bw
                val y = h - (arr[i].toFloat() / p) * (h * 0.92f)
                path.lineTo(x, y)
            }
            path.lineTo(w, h)
            path.close()
            drawPath(path, color.copy(alpha = alpha))
        }

        // subtle grid
        for (i in 1..3) {
            val x = w * i / 4f
            drawLine(colors.divider.copy(alpha = 0.5f), Offset(x, 0f), Offset(x, h),
                strokeWidth = 1.dp.toPx())
        }
        drawChannel(histogram.r, Color(0xFFE5484D), 0.45f)
        drawChannel(histogram.g, Color(0xFF46A758), 0.45f)
        drawChannel(histogram.b, Color(0xFF3E8EDE), 0.45f)

        // luminance outline
        val lp = peak(histogram.lum)
        val path = Path()
        for (i in 0 until bins) {
            val x = i * bw + bw / 2
            val y = h - (histogram.lum[i].toFloat() / lp) * (h * 0.92f)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, Color.White.copy(alpha = 0.85f),
            style = Stroke(width = 1.5.dp.toPx()))

        // Clipping warning triangles (Lightroom style): top-left = shadows,
        // top-right = highlights. Lit in color when clipping exceeds the
        // threshold, faint outline otherwise.
        val tri = (h * 0.30f).coerceIn(8.dp.toPx(), 20.dp.toPx())
        val inset = 2.dp.toPx()
        fun cornerTriangle(x0: Float, active: Boolean, color: Color) {
            val p = Path().apply {
                moveTo(x0, inset)
                lineTo(x0 + tri, inset)
                lineTo(x0, inset + tri)
                close()
            }
            if (active) drawPath(p, color)
            else drawPath(p, colors.textSecondary.copy(alpha = 0.35f),
                style = Stroke(width = 1.dp.toPx()))
        }
        cornerTriangle(inset, shadowClip, Color(0xFF3E8EDE))          // shadows: blue
        cornerTriangle(w - inset - tri, highlightClip, Color(0xFFE5484D)) // highlights: red
    }
}

/** Fraction of pixels clipped before a corner triangle lights up. */
private const val CLIP_WARN_FRACTION = 0.005f

/**
 * Luma waveform monitor (Lightroom's waveform scope): one vertical bar per
 * image column, spanning that column's min..max luma, with a bright tick at
 * the column mean. Drawn from the live render, so it tracks every slider
 * drag. Quarter-tone grid lines give the 0/25/50/75/100% references.
 */
@Composable
fun WaveformView(
    waveform: Waveform,
    modifier: Modifier = Modifier,
    height: Dp = 72.dp,
) {
    val colors = Lumora.colors
    val brightest = ((waveform.mean.maxOrNull() ?: 0) * 100) / 255
    val darkest = ((waveform.mean.minOrNull() ?: 0) * 100) / 255
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .semantics {
                contentDescription =
                    "Waveform monitor: brightest area $brightest percent, darkest area $darkest percent"
            }
    ) {
        val w = size.width; val h = size.height
        val cols = Waveform.COLS
        val cw = w / cols
        fun yOf(lum: Int) = h - (lum.coerceIn(0, 255) / 255f) * h

        // quarter-tone grid
        for (i in 0..4) {
            val y = h * i / 4f
            drawLine(colors.divider.copy(alpha = 0.5f), Offset(0f, y), Offset(w, y),
                strokeWidth = 1.dp.toPx())
        }

        val barW = (cw * 0.55f).coerceAtLeast(1f)
        for (c in 0 until cols) {
            val x = c * cw + cw / 2f
            // min..max envelope
            drawLine(
                Color.White.copy(alpha = 0.42f),
                Offset(x, yOf(waveform.max[c])),
                Offset(x, yOf(waveform.min[c])),
                strokeWidth = barW,
            )
            // mean tick
            val my = yOf(waveform.mean[c])
            drawLine(
                Color.White.copy(alpha = 0.95f),
                Offset(x - cw * 0.32f, my),
                Offset(x + cw * 0.32f, my),
                strokeWidth = 1.5.dp.toPx(),
            )
        }
    }
}

/**
 * Vectorscope (Lightroom's chroma scope): each pixel of the live render is
 * plotted by hue (angle) and saturation (radius). Neutral gray collapses to
 * the center dot; skin tones ride along the amber skin-tone line; patches
 * that spill past the 75% ring are over-saturated. Drawn from the live
 * render, so it tracks every slider drag.
 *
 * Graticule: outer ring = 100% chroma, inner ring = 75%, cross = the
 * u/v chroma axes, amber line = skin-tone reference. Each dot is tinted with
 * the average color of its chroma cell and sized by cell population (log
 * scale), so dominant hues read at a glance.
 */
@Composable
fun VectorscopeView(
    scope: Vectorscope,
    modifier: Modifier = Modifier,
    height: Dp = 168.dp,
) {
    val colors = Lumora.colors
    val grid = Vectorscope.GRID
    var total = 0L
    var saturated = 0L
    for (gy in 0 until grid) {
        for (gx in 0 until grid) {
            val n = scope.counts[gy * grid + gx].toLong()
            total += n
            if (n > 0) {
                val u = (gx + 0.5f) / grid * 2f - 1f
                val v = (gy + 0.5f) / grid * 2f - 1f
                if (u * u + v * v > 0.25f) saturated += n
            }
        }
    }
    val satPct = if (total > 0) (saturated * 100 / total).toInt() else 0
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .semantics {
                contentDescription =
                    "Vectorscope: $satPct percent of pixels highly saturated"
            }
    ) {
        val w = size.width; val h = size.height
        val cx = w / 2f; val cy = h / 2f
        val radius = (minOf(w, h) / 2f * 0.94f).coerceAtLeast(1f)
        fun ptOf(u: Float, v: Float) = Offset(cx + u * radius, cy - v * radius)

        // graticule
        val graticule = colors.divider.copy(alpha = 0.6f)
        drawCircle(graticule, radius = radius, center = Offset(cx, cy),
            style = Stroke(width = 1.dp.toPx()))
        drawCircle(graticule.copy(alpha = 0.45f), radius = radius * 0.75f,
            center = Offset(cx, cy), style = Stroke(width = 1.dp.toPx()))
        drawLine(graticule, Offset(cx - radius, cy), Offset(cx + radius, cy),
            strokeWidth = 1.dp.toPx())
        drawLine(graticule, Offset(cx, cy - radius), Offset(cx, cy + radius),
            strokeWidth = 1.dp.toPx())
        // skin-tone line: from center through the reference skin chroma
        val skinLen = kotlin.math.hypot(SKIN_TONE_U, SKIN_TONE_V)
        val su = SKIN_TONE_U / skinLen; val sv = SKIN_TONE_V / skinLen
        drawLine(
            Color(0xFFE8A33D).copy(alpha = 0.85f),
            Offset(cx, cy),
            ptOf(su, sv),
            strokeWidth = 1.5.dp.toPx(),
        )

        // population dots
        val cell = 2f * radius / grid
        var maxN = 1
        for (n in scope.counts) if (n > maxN) maxN = n
        val maxLog = kotlin.math.ln(maxN.toFloat() + 1f)
        for (gy in 0 until grid) {
            for (gx in 0 until grid) {
                val n = scope.counts[gy * grid + gx]
                if (n <= 0) continue
                val u = (gx + 0.5f) / grid * 2f - 1f
                val v = (gy + 0.5f) / grid * 2f - 1f
                val dotR = cell * 0.32f * (0.35f + 0.65f * kotlin.math.ln(n.toFloat() + 1f) / maxLog)
                drawCircle(
                    Color(scope.colorAt(gx, gy)).copy(alpha = 0.85f),
                    radius = dotR.coerceAtLeast(1f),
                    center = ptOf(u, v),
                )
            }
        }
    }
}

/**
 * RGB parade (Lightroom's per-channel scope): three mini-waveforms — one per
 * color channel — stacked vertically in channel colors. Where the luma
 * waveform collapses R/G/B into one brightness trace, the parade reveals
 * channel clipping: a channel pinned at the top while the others sag is a
 * color cast or a clipped channel the waveform alone can't name. Drawn from
 * the live render, so it tracks every slider drag.
 *
 * Semantics carry a per-channel clipping summary for accessibility, matching
 * the WaveformView/VectorscopeView convention.
 */
@Composable
fun RgbParadeView(
    parade: RgbParade,
    modifier: Modifier = Modifier,
    height: Dp = 108.dp,
) {
    val colors = Lumora.colors
    val cols = RgbParade.COLS
    val chanColors = listOf(Color(0xFFE5484D), Color(0xFF46C46A), Color(0xFF3E8EDE))
    val chanNames = listOf("red", "green", "blue")
    // per-channel clipped-at-top fraction (mean envelope near 255)
    val clipPct = chanNames.indices.map { k ->
        val pinned = parade.mean[k].count { it >= 250 }
        (pinned * 100 / cols.coerceAtLeast(1)).coerceIn(0, 100)
    }
    val summary = chanNames.indices.joinToString(", ") { k ->
        "${chanNames[k]} ${clipPct[k]} percent clipped"
    }
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .semantics {
                contentDescription = "RGB parade monitor: $summary"
            }
    ) {
        val w = size.width; val h = size.height
        val cw = w / cols
        val bandH = h / 3f
        for (k in 0..2) {
            val top = k * bandH
            fun yOf(v: Int) = top + bandH - (v.coerceIn(0, 255) / 255f) * bandH
            // channel label zone divider (faint)
            drawLine(
                colors.divider.copy(alpha = 0.35f),
                Offset(0f, top), Offset(w, top),
                strokeWidth = 1.dp.toPx(),
            )
            val barW = (cw * 0.55f).coerceAtLeast(1f)
            for (c in 0 until cols) {
                val x = c * cw + cw / 2f
                // min..max envelope in the channel color
                drawLine(
                    chanColors[k].copy(alpha = 0.42f),
                    Offset(x, yOf(parade.max[k][c])),
                    Offset(x, yOf(parade.min[k][c])),
                    strokeWidth = barW,
                )
                // mean tick, brighter
                val my = yOf(parade.mean[k][c])
                drawLine(
                    chanColors[k].copy(alpha = 0.95f),
                    Offset(x - cw * 0.32f, my),
                    Offset(x + cw * 0.32f, my),
                    strokeWidth = 1.5.dp.toPx(),
                )
            }
        }
        // bottom baseline
        drawLine(
            colors.divider.copy(alpha = 0.35f),
            Offset(0f, h), Offset(w, h),
            strokeWidth = 1.dp.toPx(),
        )
    }
}

/**
 * Honest placeholder shown instead of a scope when the GPU readback feeding
 * it is dead (Mali glReadPixels quirk, 2026-10-18 — see
 * [WatchdogPolicy.SCOPES_DEAD_AFTER_FAILURES]). A frozen or flat scope is
 * worse than none: it reads as real data. Same footprint as the scope it
 * replaces so the layout doesn't jump.
 */
@Composable
fun ScopesUnavailable(
    modifier: Modifier = Modifier,
    height: Dp = 72.dp,
) {
    val colors = Lumora.colors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .semantics {
                contentDescription = "Scopes unavailable on this device"
            },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            "Scopes unavailable on this device",
            style = TextStyle(
                color = colors.textSecondary,
                fontSize = 12.sp,
            ),
        )
    }
}
