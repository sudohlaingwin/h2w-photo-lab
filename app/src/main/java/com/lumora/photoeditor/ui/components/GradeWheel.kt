package com.lumora.photoeditor.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lumora.photoeditor.engine.GradeWheel
import com.lumora.photoeditor.engine.ImageMath
import com.lumora.photoeditor.ui.theme.Lumora
import kotlin.math.*

/**
 * Professional 3-way color grading wheel. Drag around the ring to set hue,
 * drag toward/away from center to set saturation. Double-tap to reset.
 */
@Composable
fun GradeWheelControl(
    label: String,
    wheel: GradeWheel,
    onChange: (GradeWheel) -> Unit,
    onBegin: () -> Unit = {},
    onEnd: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = Lumora.colors
    val type = Lumora.type
    val haptics = LocalHapticFeedback.current
    var dragging by remember { mutableStateOf(false) }

    // Double-tap reset, also offered as a TalkBack custom action — the gesture is
    // undiscoverable to accessibility users (same pattern as LumoraSlider).
    fun resetWheel() {
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        onBegin(); onChange(GradeWheel()); onEnd()
    }
    // Same neutral threshold as the value readout below ("—").
    val neutral = wheel.sat < 1f
    val hueNorm = ((wheel.hueDeg % 360f) + 360f) % 360f
    val spokenValue = if (neutral) "neutral"
        else "hue ${hueNorm.roundToInt()} degrees, saturation ${wheel.sat.roundToInt()} percent"

    Column(modifier = modifier, horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
        androidx.compose.material3.Text(label, style = type.toolLabel, color = colors.textSecondary)
        Spacer(Modifier.height(4.dp))
        Canvas(
            modifier = Modifier
                .size(148.dp)
                .pointerInput(wheel) {
                    detectTapGestures(
                        onDoubleTap = { resetWheel() }
                    )
                }
                .pointerInput(wheel) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: continue
                            val center = Offset(size.width / 2f, size.height / 2f)
                            val pos = change.position - center
                            val radius = minOf(size.width, size.height) / 2f
                            when {
                                change.pressed && !dragging -> {
                                    dragging = true; onBegin()
                                    updateWheel(pos, radius, onChange)
                                    change.consume()
                                }
                                change.pressed && dragging -> {
                                    updateWheel(pos, radius, onChange)
                                    change.consume()
                                }
                                !change.pressed && dragging -> {
                                    dragging = false; onEnd()
                                }
                            }
                        }
                    }
                }
                .semantics {
                    contentDescription = "$label color grade, $spokenValue"
                    if (!neutral) {
                        customActions = listOf(
                            CustomAccessibilityAction("Reset $label to neutral") {
                                resetWheel(); true
                            }
                        )
                    }
                }
        ) {
            val radius = size.minDimension / 2f
            val ringW = 26.dp.toPx()
            // hue ring: 72 segments
            for (i in 0 until 72) {
                val a0 = i * 5f
                val rgb = ImageMath.hsl2rgb(a0 / 360f, 0.85f, 0.55f)
                drawArc(
                    color = Color(rgb[0], rgb[1], rgb[2]),
                    startAngle = a0 - 90f,
                    sweepAngle = 5.5f,
                    useCenter = false,
                    topLeft = Offset(ringW / 2, ringW / 2),
                    size = androidx.compose.ui.geometry.Size(
                        size.width - ringW, size.height - ringW),
                    style = Stroke(width = ringW),
                )
            }
            // inner disc = current selection
            val sel = ImageMath.hsl2rgb(
                ((wheel.hueDeg % 360f) + 360f) % 360f / 360f,
                (wheel.sat / 100f).coerceIn(0f, 1f), 0.5f)
            val innerR = radius - ringW - 6.dp.toPx()
            drawCircle(Color(sel[0], sel[1], sel[2]), innerR, Offset(radius, radius))
            drawCircle(colors.divider, innerR, Offset(radius, radius),
                style = Stroke(1.dp.toPx()))
            // crosshair at neutral
            drawLine(colors.textTertiary.copy(alpha = 0.5f),
                Offset(radius - 5.dp.toPx(), radius), Offset(radius + 5.dp.toPx(), radius),
                1.5.dp.toPx())
            drawLine(colors.textTertiary.copy(alpha = 0.5f),
                Offset(radius, radius - 5.dp.toPx()), Offset(radius, radius + 5.dp.toPx()),
                1.5.dp.toPx())
            // handle
            if (wheel.sat > 1f) {
                val ang = Math.toRadians(wheel.hueDeg.toDouble() - 90.0)
                val hr = innerR * (wheel.sat / 100f).coerceIn(0f, 1f)
                val hp = Offset(
                    radius + cos(ang).toFloat() * hr,
                    radius + sin(ang).toFloat() * hr)
                drawCircle(Color.White, 9.dp.toPx(), hp)
                drawCircle(Color.Black.copy(alpha = 0.35f), 9.dp.toPx(), hp,
                    style = Stroke(1.5.dp.toPx()))
            }
        }
        Spacer(Modifier.height(2.dp))
        // User law (2026-10-17): every adjustment value reads as a percentage.
        // The wheel was the one visible readout missing the % sign ("120° · 45"
        // reads as two bare numbers), and it showed raw hueDeg while TalkBack
        // announces the normalized hue — a preset/sidecar hue outside 0..360
        // would have shown e.g. "370°". hueNorm is already computed above.
        androidx.compose.material3.Text(
            text = if (wheel.sat < 1f) "—" else "${hueNorm.roundToInt()}° · ${wheel.sat.roundToInt()}%",
            style = type.controlValue,
            color = if (wheel.sat < 1f) colors.textTertiary else colors.textPrimary,
        )
    }
}

private fun updateWheel(pos: Offset, radius: Float, onChange: (GradeWheel) -> Unit) {
    val dist = sqrt(pos.x * pos.x + pos.y * pos.y)
    if (dist < 1f) return
    var deg = Math.toDegrees(atan2(pos.y.toDouble(), pos.x.toDouble())) + 90.0
    deg = (deg % 360 + 360) % 360
    // saturation from drag radius relative to the inner disc
    val sat = (dist / (radius * 0.60f) * 100f).coerceIn(0f, 100f)
    onChange(GradeWheel(deg.toFloat(), sat))
}
