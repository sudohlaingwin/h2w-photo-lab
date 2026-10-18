package com.lumora.photoeditor.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lumora.photoeditor.engine.CurvePoint
import com.lumora.photoeditor.engine.ToneCurve
import com.lumora.photoeditor.engine.activePreset
import com.lumora.photoeditor.ui.state.Histogram
import com.lumora.photoeditor.ui.theme.Lumora
import kotlin.math.abs
import com.lumora.photoeditor.ui.components.LumoraTextButton

/**
 * Interactive tone-curve editor: drag points, tap empty space to add,
 * double-tap a point to remove it (minimum 2 points remain).
 *
 * @param histogram optional live histogram drawn as a backdrop behind the
 *   curve (Lightroom parity — place points where the image data is).
 * @param histChannel which histogram channel to draw: 0 = luminance,
 *   1 = red, 2 = green, 3 = blue.
 * @param channelLabel human channel name used in the TalkBack description
 *   (e.g. "Master", "Red").
 * @param onReset when provided, a TalkBack custom accessibility action
 *   "Reset <channel> curve to linear" is offered, routed through the same
 *   undoable preset path as the visual preset chips (which double-tap
 *   gestures never reach for screen-reader users).
 */
@Composable
fun ToneCurveEditor(
    curve: ToneCurve,
    onChange: (ToneCurve) -> Unit,
    onBegin: () -> Unit = {},
    onEnd: () -> Unit = {},
    accent: Color = Lumora.colors.accent,
    histogram: Histogram? = null,
    histChannel: Int = 0,
    channelLabel: String = "Master",
    onReset: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    var activeIdx by remember { mutableStateOf<Int?>(null) }
    // local drag copy for 60fps feedback; committed on release
    var dragCurve by remember(curve) { mutableStateOf(curve) }
    val shown = if (activeIdx != null) dragCurve else curve
    // Spoken state mirrors the visual preset chips (LightPanel), so a
    // TalkBack user hears exactly which named preset is active — or, for a
    // hand-dragged curve, how many points it has.
    val preset = shown.activePreset()
    val curveDesc = if (preset != null) "$channelLabel channel, ${preset.label} preset"
    else "$channelLabel channel, custom curve, ${shown.points.size} points"

    Column(modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.6f)
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(12.dp))
                .semantics {
                    contentDescription = "Tone curve editor, $curveDesc"
                    if (onReset != null) {
                        customActions = listOf(
                            CustomAccessibilityAction(
                                "Reset $channelLabel curve to linear"
                            ) { onReset(); true }
                        )
                    }
                }
                .pointerInput(curve) {
                    detectTapGestures(
                        onDoubleTap = { offset ->
                            val size = this.size
                            val px = offset.x / size.width
                            val py = 1f - offset.y / size.height
                            val pts = curve.points.sortedBy { it.x }
                            val hit = pts.indexOfFirst {
                                abs(it.x - px) < 0.06f && abs(it.y - py) < 0.06f
                            }
                            if (hit >= 0 && pts.size > 2) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onBegin()
                                onChange(ToneCurve(pts.filterIndexed { i, _ -> i != hit }))
                                onEnd()
                            }
                        },
                        onTap = { offset ->
                            val size = this.size
                            val px = (offset.x / size.width).coerceIn(0f, 1f)
                            val py = (1f - offset.y / size.height).coerceIn(0f, 1f)
                            val pts = curve.points.sortedBy { it.x }
                            val near = pts.any {
                                abs(it.x - px) < 0.05f && abs(it.y - py) < 0.05f
                            }
                            if (!near) {
                                onBegin()
                                onChange(ToneCurve((pts + CurvePoint(px, py)).sortedBy { it.x }))
                                onEnd()
                            }
                        },
                    )
                }
        ) {
            CurveCanvas(
                curve = shown,
                activeIdx = activeIdx,
                accent = accent,
                histogram = histogram,
                histChannel = histChannel,
                modifier = Modifier.fillMaxSize(),
                onDragStart = { offset, size ->
                    val px = offset.x / size.width
                    val py = 1f - offset.y / size.height
                    val pts = curve.points.sortedBy { it.x }
                    val hit = pts.indexOfFirst {
                        abs(it.x - px) < 0.09f && abs(it.y - py) < 0.09f
                    }
                    if (hit >= 0) {
                        activeIdx = hit
                        dragCurve = ToneCurve(pts)
                        onBegin()
                        true
                    } else false
                },
                onDrag = { offset, size ->
                    val idx = activeIdx ?: return@CurveCanvas
                    val pts = dragCurve.points.sortedBy { it.x }.toMutableList()
                    if (idx >= pts.size) return@CurveCanvas
                    var nx = (offset.x / size.width).coerceIn(0f, 1f)
                    val ny = (1f - offset.y / size.height).coerceIn(0f, 1f)
                    // keep x-ordering (endpoints locked horizontally)
                    val lo = if (idx > 0) pts[idx - 1].x + 0.01f else 0f
                    val hi = if (idx < pts.size - 1) pts[idx + 1].x - 0.01f else 1f
                    nx = nx.coerceIn(lo, hi)
                    pts[idx] = CurvePoint(nx, ny)
                    dragCurve = ToneCurve(pts)
                },
                onDragEnd = {
                    activeIdx?.let {
                        onChange(dragCurve)
                        onEnd()
                    }
                    activeIdx = null
                },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LumoraTextButton(onClick = {
                onBegin()
                onChange(ToneCurve(listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f))))
                onEnd()
            }) {
                Text("Reset curve", style = Lumora.type.caption, color = Lumora.colors.textSecondary)
            }
        }
    }
}

@Composable
private fun CurveCanvas(
    curve: ToneCurve,
    activeIdx: Int?,
    accent: Color,
    histogram: Histogram?,
    histChannel: Int,
    modifier: Modifier = Modifier,
    onDragStart: (Offset, androidx.compose.ui.unit.IntSize) -> Boolean,
    onDrag: (Offset, androidx.compose.ui.unit.IntSize) -> Unit,
    onDragEnd: () -> Unit,
) {
    val colors = Lumora.colors
    var dragging by remember { mutableStateOf(false) }
    Canvas(
        modifier = modifier.pointerInput(curve) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull() ?: continue
                    val pos = change.position
                    when {
                        change.pressed && !dragging -> {
                            if (onDragStart(pos, size)) {
                                dragging = true
                                change.consume()
                            }
                        }
                        change.pressed && dragging -> {
                            onDrag(pos, size)
                            change.consume()
                        }
                        !change.pressed && dragging -> {
                            dragging = false
                            onDragEnd()
                        }
                    }
                }
            }
        }
    ) {
        val w = size.width; val h = size.height
        // panel + grid
        drawRect(colors.panelElevated)
        // histogram backdrop (Lightroom parity) — drawn first, behind everything
        if (histogram != null) {
            val bins = when (histChannel) {
                1 -> histogram.r
                2 -> histogram.g
                3 -> histogram.b
                else -> histogram.lum
            }
            val peak = (bins.maxOrNull() ?: 1).coerceAtLeast(1)
            val fill = when (histChannel) {
                1 -> Color(0xFFE5484D)
                2 -> Color(0xFF46A758)
                3 -> Color(0xFF3E8EDE)
                else -> Color.White
            }.copy(alpha = if (histChannel == 0) 0.13f else 0.18f)
            val hp = Path()
            val bw = w / bins.size
            hp.moveTo(0f, h)
            for (i in bins.indices) {
                hp.lineTo(i * bw, h - (bins[i].toFloat() / peak) * (h * 0.96f))
            }
            hp.lineTo(w, h)
            hp.close()
            drawPath(hp, fill)
        }
        for (i in 1..3) {
            val gx = w * i / 4f; val gy = h * i / 4f
            drawLine(colors.divider, Offset(gx, 0f), Offset(gx, h), 1.dp.toPx())
            drawLine(colors.divider, Offset(0f, gy), Offset(w, gy), 1.dp.toPx())
        }
        // diagonal reference
        drawLine(colors.textTertiary.copy(alpha = 0.35f), Offset(0f, h), Offset(w, 0f),
            strokeWidth = 1.dp.toPx(),
            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
        // curve
        val lut = curve.toLut(128)
        val path = Path()
        for (i in lut.indices) {
            val x = i / 127f * w
            val y = h - lut[i] * h
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, accent, style = Stroke(width = 2.5.dp.toPx()))
        // points
        curve.points.sortedBy { it.x }.forEachIndexed { i, p ->
            val cx = p.x * w; val cy = h - p.y * h
            val r = if (i == activeIdx) 8.dp.toPx() else 5.5.dp.toPx()
            drawCircle(Color.Black.copy(alpha = 0.45f), r + 2.dp.toPx(), Offset(cx, cy))
            drawCircle(Color.White, r, Offset(cx, cy))
            drawCircle(accent, r * 0.45f, Offset(cx, cy))
        }
    }
}
