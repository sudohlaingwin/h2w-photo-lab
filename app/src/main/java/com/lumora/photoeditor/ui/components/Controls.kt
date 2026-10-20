package com.lumora.photoeditor.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.lumora.photoeditor.ui.theme.Lumora
import com.lumora.photoeditor.ui.theme.LumoraIcons
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Visual footprint of a round brush: the drawn circle's diameter is
 * proportional to [diameter] (brush size as a fraction of photo width,
 * 0.01..0.4) and its edge softness comes from [feather] (0 = hard edge,
 * 1 = fully soft). Sits beside the Size/Feather sliders so the user sees
 * the brush footprint while adjusting, instead of only after touching
 * the photo (Lightroom shows the brush the same way).
 */
@Composable
fun BrushPreview(
    diameter: Float,
    feather: Float,
    modifier: Modifier = Modifier,
    boxSize: Dp = 64.dp,
    /** Brush size (photo-width fraction) that fills the reference ring. */
    maxDiameter: Float = 0.4f,
) {
    val colors = Lumora.colors
    val desc = "Brush preview: ${(diameter * 100).roundToInt()} percent of photo " +
        "width, ${(feather * 100).roundToInt()} percent feather"
    Canvas(
        modifier = modifier
            .size(boxSize)
            .semantics { contentDescription = desc },
    ) {
        val maxR = size.minDimension / 2f
        // Faint reference ring at the largest supported brush: the
        // footprint reads against a fixed scale within each panel.
        drawCircle(
            color = colors.textTertiary.copy(alpha = 0.35f),
            radius = (maxR - 1.dp.toPx()).coerceAtLeast(1f),
            style = Stroke(width = 1.dp.toPx()),
        )
        val r = (maxR * (diameter / maxDiameter).coerceIn(0f, 1f))
            .coerceAtLeast(2.dp.toPx())
        // Soft outer falloff (what feather controls), then the solid core.
        drawCircle(color = colors.accent.copy(alpha = 0.28f), radius = r)
        val coreR = (r * (1f - feather)).coerceAtLeast(1.dp.toPx())
        drawCircle(color = colors.accent.copy(alpha = 0.85f), radius = coreR)
        // Exact brush center.
        drawCircle(color = Color.White, radius = 1.5.dp.toPx())
    }
}

/**
 * The signature Lumora adjustment row: label, live value, and a precision
 * slider. Double-tap the label to reset to [default]; long-press the value
 * pill to restore the value from before the last change.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LumoraSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float> = -100f..100f,
    default: Float = 0f,
    valueText: String? = null,
    onBegin: () -> Unit = {},
    onEnd: () -> Unit = {},
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val colors = Lumora.colors
    val type = Lumora.type
    val haptics = LocalHapticFeedback.current
    var dragging by remember { mutableStateOf(false) }

    // Per-setting revert (user preference 2026-10-17): long-pressing the
    // value pill restores the value this slider had BEFORE the user's last
    // change gesture — not the parameter default, and not global undo.
    // changeStartValue is captured when a change gesture begins; an outside
    // change while idle (undo, preset, auto) re-baselines it so a stale
    // value can never be restored.
    var changeStartValue by remember { mutableFloatStateOf(value) }
    var lastSeenValue by remember { mutableFloatStateOf(value) }
    if (!dragging && value != lastSeenValue) changeStartValue = value
    lastSeenValue = value

    // TalkBack support: the slider itself gets a spoken label + value, and
    // both the label and the slider expose the double-tap reset as an
    // accessibility action (TalkBack gestures never reach detectTapGestures).
    val resettable = value != default
    val resetLabel = "Reset $label to default"
    fun resetToDefault() {
        if (!resettable) return
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        changeStartValue = value
        onBegin()
        onValueChange(default)
        onEnd()
    }
    /**
     * Long-press on the value pill: restore the pre-change value as one
     * undoable step through the caller's gesture hooks. No-op when there
     * is nothing to restore, so no junk history entries are created.
     */
    fun revertToPreChange() {
        val target = changeStartValue
        if (target == value || !enabled) return
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        changeStartValue = target
        onBegin()
        onValueChange(target)
        onEnd()
    }
    val revertible = enabled && changeStartValue != value
    // User preference 2026-10-17: every adjustment slider shows its value as
    // a percentage of how far it is pushed (e.g. Exposure +35%). An explicit
    // valueText still wins for callers that already format their own readout.
    val spokenValue = valueText ?: formatPercent(value, valueRange, default)

    Column(modifier = modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = type.controlLabel,
                color = if (enabled) colors.textSecondary else colors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(4.dp))
                    .semantics {
                        if (resettable && enabled) {
                            role = Role.Button
                            onClick(label = resetLabel) { resetToDefault(); true }
                        }
                    }
                    .pointerInput(value, default) {
                        detectTapGestures(
                            onDoubleTap = {
                                if (resettable) resetToDefault()
                            }
                        )
                    }
                    .padding(vertical = 6.dp),
            )
            // Premium value pill: the percentage sits on a soft chip that picks
            // up the accent tint once the control leaves its default, so a
            // glance shows which adjustments are active. Long-press reverts
            // to the value before this slider's last change (per-setting
            // revert, user preference 2026-10-17) — a TalkBack custom action
            // mirrors it, since touch gestures never reach TalkBack users.
            val modified = value != default
            Box(
                modifier = Modifier
                    .padding(start = 8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (modified && enabled) colors.accent.copy(alpha = 0.14f)
                        else colors.panelElevated
                    )
                    .pointerInput(value, changeStartValue) {
                        detectTapGestures(
                            onLongPress = { if (revertible) revertToPreChange() },
                        )
                    }
                    .semantics {
                        if (revertible) {
                            customActions = listOf(
                                CustomAccessibilityAction(
                                    "Restore $label to previous value"
                                ) { revertToPreChange(); true },
                            )
                        }
                    }
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text(
                    text = spokenValue,
                    style = type.controlValue,
                    color = if (!enabled) colors.textTertiary
                    else if (modified) colors.accent else colors.textSecondary,
                    maxLines = 1,
                )
            }
        }
        Slider(
            value = value,
            onValueChange = {
                if (!dragging) {
                    dragging = true
                    // Still the pre-change value: recomposition from the
                    // caller's onValueChange below hasn't happened yet.
                    changeStartValue = value
                    onBegin()
                }
                onValueChange(it)
            },
            onValueChangeFinished = { dragging = false; onEnd() },
            valueRange = valueRange,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .semantics {
                    contentDescription = "$label, $spokenValue"
                    if (resettable && enabled) {
                        customActions = listOf(
                            CustomAccessibilityAction(resetLabel) {
                                resetToDefault()
                                true
                            }
                        )
                    }
                },
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = if (value == default) colors.textTertiary else colors.accent,
                inactiveTrackColor = colors.divider,
            ),
            thumb = lumoraSliderThumb(dragging),
            track = lumoraSliderTrack(valueRange, isDefault = value == default),
        )
    }
}

/**
 * Signature Lumora slider thumb: a white dot with a hairline dark ring that
 * grows while dragged. Shared by [LumoraSlider] and [LumoraCompactSlider] so
 * every slider in the app renders identically.
 */
@OptIn(ExperimentalMaterial3Api::class)
private fun lumoraSliderThumb(dragging: Boolean): @Composable (androidx.compose.material3.SliderState) -> Unit = {
    Canvas(modifier = Modifier.size(if (dragging) 20.dp else 14.dp)) {
        drawCircle(Color.White, radius = size.minDimension / 2)
        drawCircle(
            Color(0x33000000), radius = size.minDimension / 2,
            style = Stroke(1.dp.toPx()),
        )
    }
}

/**
 * Signature Lumora slider track: a 4dp rounded bar that renders neutral
 * ([LumoraColors.textTertiary]) at the default value and warm-amber accent
 * once adjusted, with a zero marker for bipolar ranges.
 */
@OptIn(ExperimentalMaterial3Api::class)
private fun lumoraSliderTrack(
    valueRange: ClosedFloatingPointRange<Float>,
    isDefault: Boolean,
): @Composable (SliderState) -> Unit = { sliderState ->
    val colors = Lumora.colors
    val frac = (sliderState.value - valueRange.start) /
        (valueRange.endInclusive - valueRange.start)
    Canvas(modifier = Modifier.fillMaxWidth().height(4.dp)) {
        val y = size.height / 2
        drawLine(
            colors.divider, Offset(0f, y), Offset(size.width, y),
            strokeWidth = size.height, cap = StrokeCap.Round,
        )
        // zero marker for bipolar ranges
        if (valueRange.start < 0f && valueRange.endInclusive > 0f) {
            val zx = size.width * (-valueRange.start) /
                (valueRange.endInclusive - valueRange.start)
            drawLine(
                colors.textTertiary.copy(alpha = 0.6f),
                Offset(zx, y - 5.dp.toPx()), Offset(zx, y + 5.dp.toPx()),
                strokeWidth = 1.5.dp.toPx(),
            )
        }
        val activeColor = if (isDefault) colors.textTertiary else colors.accent
        val endX = size.width * frac
        drawLine(
            activeColor, Offset(0f, y), Offset(endX, y),
            strokeWidth = size.height, cap = StrokeCap.Round,
        )
    }
}

/**
 * The compact Lumora slider: identical signature thumb/track styling to
 * [LumoraSlider] but without the label row — for single-row layouts like the
 * floating BrushQuickBar, where a label-above-slider stack would not fit.
 * Mirrors LumoraSlider's drag begin/end hooks and TalkBack contract: a spoken
 * description plus a reset accessibility action whenever [value] != [default].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LumoraCompactSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    default: Float,
    description: String,
    resetLabel: String,
    onReset: () -> Unit,
    onBegin: () -> Unit = {},
    onEnd: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = Lumora.colors
    var dragging by remember { mutableStateOf(false) }
    val resettable = value != default
    Slider(
        value = value,
        onValueChange = {
            if (!dragging) {
                dragging = true
                onBegin()
            }
            onValueChange(it)
        },
        onValueChangeFinished = {
            dragging = false
            onEnd()
        },
        valueRange = valueRange,
        modifier = modifier.semantics {
            contentDescription = description
            if (resettable) {
                customActions = listOf(
                    CustomAccessibilityAction(resetLabel) { onReset(); true },
                )
            }
        },
        colors = SliderDefaults.colors(
            thumbColor = Color.White,
            activeTrackColor = if (resettable) colors.accent else colors.textTertiary,
            inactiveTrackColor = colors.divider,
        ),
        thumb = lumoraSliderThumb(dragging),
        track = lumoraSliderTrack(valueRange, isDefault = !resettable),
    )
}

fun formatValue(value: Float, range: ClosedFloatingPointRange<Float>): String {
    val span = range.endInclusive - range.start
    return if (span > 20f) value.roundToInt().toString()
    // Explicit Locale.US: String.format follows the device default locale,
    // and on locales with a comma decimal separator (e.g. his Burmese-UI
    // vivo) a bare "%.2f" would render "1,50" in every slider readout.
    // These are machine-style readouts, not prose — always dot decimals.
    else String.format(Locale.US, "%.2f", value)
}

/**
 * Percentage readout for adjustment sliders (user preference 2026-10-17):
 * how far the value is pushed away from its default, as a share of the
 * larger side of the range. E.g. Exposure +1.75 EV on −5…+5 with default 0
 * reads "+35%"; a centered value reads "0%". Degenerate ranges read "0%"
 * instead of dividing by zero.
 */
fun formatPercent(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    default: Float = 0f,
): String {
    val span = maxOf(range.endInclusive - default, default - range.start, 0f)
    if (span <= 0f) return "0%"
    val pct = ((value - default) / span * 100f).roundToInt()
    return if (pct > 0) "+$pct%" else "$pct%"
}

/** Collapsible group of controls, rendered as a premium elevated card.
 *
 * Premium panels (user preference 2026-10-17): each settings section floats
 * as its own card on the panel background — 16dp corners, a hairline
 * border, and breathing margins — replacing the old blocky full-bleed
 * rows separated by harsh dividers. The title sits directly on the card
 * (uppercase, secondary text) with the rotating chevron; content gets a
 * soft fade with the expand/collapse motion.
 */
@Composable
fun CollapsibleSection(
    title: String,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    val colors = Lumora.colors
    val type = Lumora.type
    LumoraCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.border(
                width = 1.dp,
                color = colors.divider.copy(alpha = 0.45f),
                shape = RoundedCornerShape(16.dp),
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        role = Role.Button
                        stateDescription = if (expanded) "Expanded" else "Collapsed"
                        onClick(label = title) { expanded = !expanded; true }
                    }
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title.uppercase(),
                    style = type.sectionTitle,
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                // Premium expander: a chevron that rotates instead of the old
                // blocky "+"/"−" text glyph (user preference 2026-10-17).
                val chevronAngle by animateFloatAsState(
                    targetValue = if (expanded) 180f else 0f,
                    animationSpec = tween(durationMillis = 200),
                    label = "sectionChevron",
                )
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = colors.textTertiary,
                    modifier = Modifier
                        .size(20.dp)
                        .rotate(chevronAngle),
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(tween(150)),
                exit = shrinkVertically() + fadeOut(tween(120)),
            ) {
                Column(modifier = Modifier.padding(bottom = 10.dp)) { content() }
            }
        }
    }
}

/** Small segmented control for choosing modes (mask types, etc.). */
@Composable
fun <T> SegmentedControl(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Lumora.colors
    val type = Lumora.type
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(colors.panelElevated)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { (value, label) ->
            val isSel = value == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSel) colors.accentDim else Color.Transparent)
                    .semantics(mergeDescendants = true) {
                        role = Role.RadioButton
                        this.selected = isSel
                        onClick(label = label) { onSelect(value); true }
                    }
                    .clickable { onSelect(value) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = type.toolLabel,
                    color = if (isSel) colors.accent else colors.textSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Design-system selection chip. Replaces Material3's FilterChip wherever
 *  on-brand styling matters: warm-amber selected state, elevated neutral
 *  idle state, 40dp minimum touch height. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LumoraChip(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Optional long-press action (e.g. delete); announced via [onLongClickLabel]. */
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    label: @Composable () -> Unit,
) {
    val colors = Lumora.colors
    val shape = RoundedCornerShape(12.dp)
    val clickModifier = if (onLongClick != null) {
        Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick,
            onLongClickLabel = onLongClickLabel,
        )
    } else {
        Modifier.clickable { onClick() }
    }
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(if (selected) colors.accentDim else colors.panelElevated)
            // TalkBack (22:32 cycle): the bare clickable() below made the
            // chip announce as a generic "double-tap to activate" with no
            // toggle state — declare the checkbox role + selected state so
            // it reads "<label>, checkbox, checked/not checked".
            .semantics {
                role = Role.Checkbox
                this.selected = selected
            }
            .border(
                width = 1.dp,
                color = if (selected) colors.accent.copy(alpha = 0.55f) else colors.divider,
                shape = shape,
            )
            .semantics(mergeDescendants = true) {
                role = Role.Button
                this.selected = selected
            }
            .then(clickModifier)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        ProvideTextStyle(
            TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
                letterSpacing = 0.2.sp,
            ),
        ) {
            CompositionLocalProvider(
                LocalContentColor provides
                    if (selected) colors.accent else colors.textSecondary,
                content = label,
            )
        }
    }
}

/**
 * Design-system switch: warm-amber checked styling (accent thumb on accentDim
 * track), neutral unchecked styling, with a TalkBack label and a spoken
 * On/Off state. Replaces raw Material3 [Switch] everywhere in the app.
 *
 * Accessibility note: applying `Modifier.semantics { contentDescription = .. }`
 * directly on a Switch *replaces* its toggleable semantics, silently dropping
 * the switch role and the toggle action for TalkBack. This merges the label
 * (plus a state description) with the switch's own semantics instead, so
 * TalkBack announces "<label>, Switch, On/Off".
 */
@Composable
fun LumoraSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    /** TalkBack label, e.g. "Add watermark". */
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = Lumora.colors
    val label = contentDescription
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = colors.accent,
            checkedTrackColor = colors.accentDim,
            uncheckedThumbColor = colors.textTertiary,
            uncheckedTrackColor = colors.panelElevated,
        ),
        modifier = modifier.semantics(mergeDescendants = true) {
            this.contentDescription = label
            stateDescription = if (checked) "On" else "Off"
        },
    )
}

/**
 * Design-system checkbox: warm-amber checked styling (accent box, light
 * checkmark), neutral unchecked styling, matching [LumoraSwitch] and the
 * [LumoraChip] selected state. Replaces raw Material3 [Checkbox] everywhere
 * in the app.
 *
 * Styling note: Material3's Checkbox with `onCheckedChange = null` renders
 * at 38% alpha (disabled colors), so display-only usages nested inside a
 * parent toggleable look washed out. This component pins the disabled
 * colors to the enabled ones, so a checkbox that only *renders* state
 * (e.g. the AI assistant's "tick the ones to apply" rows, where the Row
 * itself is the toggleable) looks identical to an interactive one.
 *
 * Semantics: when nested inside a parent toggleable (like those rows), pass
 * `contentDescription = null` and the parent's role/state carry the
 * announcement. When used standalone with its own [onCheckedChange], pass a
 * label and it announces "<label>, Checkbox, Checked/Not checked".
 */
@Composable
fun LumoraCheckbox(
    checked: Boolean,
    /** Null when the checkbox only renders state and a parent handles toggling. */
    onCheckedChange: ((Boolean) -> Unit)?,
    /** TalkBack label when standalone; null when nested in a parent toggleable. */
    contentDescription: String?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = Lumora.colors
    val label = contentDescription
    val boxColors = CheckboxDefaults.colors(
        checkedColor = colors.accent,
        checkmarkColor = colors.textPrimary,
        uncheckedColor = colors.textTertiary,
        // Display-only usages (onCheckedChange == null) must not wash out.
        disabledCheckedColor = colors.accent,
        disabledUncheckedColor = colors.textTertiary,
        disabledIndeterminateColor = colors.accent,
    )
    val semanticsModifier = if (label != null) {
        modifier.semantics(mergeDescendants = true) {
            this.contentDescription = label
            stateDescription = if (checked) "Checked" else "Not checked"
        }
    } else {
        modifier
    }
    Checkbox(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        colors = boxColors,
        modifier = semanticsModifier,
    )
}

/**
 * Design-system primary button: warm-amber filled, dark label text, 12dp
 * corners, 48dp minimum touch height. Replaces raw Material3 [Button]
 * everywhere in the app.
 *
 * The default label style is [LumoraType.controlLabel]-weight medium; an
 * explicit `style` on the caller's [Text] still wins, so existing labels
 * that already set their own color/style render unchanged. Pass [colors]
 * only when a one-off override is truly needed (the default already is the
 * on-brand amber).
 */
@Composable
fun LumoraButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(12.dp),
    colors: ButtonColors? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val c = Lumora.colors
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
        shape = shape,
        colors = colors ?: ButtonDefaults.buttonColors(
            containerColor = c.accent,
            contentColor = Color(0xFF17181A),
            disabledContainerColor = c.divider,
            disabledContentColor = c.textTertiary,
        ),
    ) {
        ProvideTextStyle(
            TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                letterSpacing = 0.2.sp,
            ),
        ) {
            // ProvideTextStyle's content slot is not a RowScope, so forward
            // the RowScope content lambda through a nested Row — keeps the
            // public RowScope receiver (callers may use RowScope modifiers)
            // without a receiver-type mismatch.
            Row(verticalAlignment = Alignment.CenterVertically) {
                content()
            }
        }
    }
}

/**
 * Design-system secondary button: hairline divider border, accent label,
 * 12dp corners, 48dp minimum touch height. Replaces raw Material3
 * [OutlinedButton] everywhere in the app.
 *
 * Like [LumoraButton], the content lambda passes through untouched, so
 * callers that already tint their own [Text]/[Icon] keep their exact look.
 */
@Composable
fun LumoraOutlineButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(12.dp),
    colors: ButtonColors? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val c = Lumora.colors
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
        shape = shape,
        border = BorderStroke(
            1.dp,
            if (enabled) c.divider else c.divider.copy(alpha = 0.5f),
        ),
        colors = colors ?: ButtonDefaults.outlinedButtonColors(
            contentColor = c.accent,
            disabledContentColor = c.textTertiary,
        ),
    ) {
        ProvideTextStyle(
            TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                letterSpacing = 0.2.sp,
            ),
        ) {
            // ProvideTextStyle's content slot is not a RowScope, so forward
            // the RowScope content lambda through a nested Row — keeps the
            // public RowScope receiver (callers may use RowScope modifiers)
            // without a receiver-type mismatch.
            Row(verticalAlignment = Alignment.CenterVertically) {
                content()
            }
        }
    }
}

/**
 * Design-system text button: on-brand warm-amber label by default, 48dp
 * minimum touch height (a11y), TalkBack role=Button on the whole node.
 * Replaces raw Material3 [TextButton] everywhere in the app.
 *
 * Like [LumoraButton], the content lambda passes through untouched, so
 * callers that already tint their own [Text]/[Icon] keep their exact look —
 * the accent default only applies when the content sets no color itself.
 */
@Composable
fun LumoraTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val c = Lumora.colors
    TextButton(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = 48.dp)
            .semantics { role = Role.Button },
        enabled = enabled,
        contentPadding = contentPadding,
        colors = colors ?: ButtonDefaults.textButtonColors(
            contentColor = c.accent,
            disabledContentColor = c.textTertiary,
        ),
    ) {
        ProvideTextStyle(
            TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                letterSpacing = 0.2.sp,
            ),
        ) {
            // Same Row-forwarding trick as LumoraButton: keeps the public
            // RowScope receiver (callers may use RowScope modifiers)
            // without a receiver-type mismatch.
            Row(verticalAlignment = Alignment.CenterVertically) {
                content()
            }
        }
    }
}

/**
 * Design-system text field: hairline divider border at rest, warm-amber
 * focused border + cursor, error tint when [isError]. Replaces raw Material3
 * [OutlinedTextField] everywhere in the app.
 *
 * The [label] doubles as the TalkBack name, so every call site passes one
 * (a visible label or a placeholder-only call still passes [label] for the
 * semantics). [isPassword] masks input with dots — used for the Gemini API
 * key field so a shoulder-surfing glance at Settings can't read the key.
 */
@Composable
fun LumoraTextField(
    value: String,
    onValueChange: (String) -> Unit,
    /** TalkBack label; shown as the floating label when [placeholder] is also given. */
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    singleLine: Boolean = true,
    isError: Boolean = false,
    isPassword: Boolean = false,
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    trailingIcon: @Composable (() -> Unit)? = null,
) {
    val c = Lumora.colors
    val error = c.accent.copy(red = 0.95f, green = 0.35f, blue = 0.30f)
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        label = { Text(label, color = c.textTertiary) },
        placeholder = placeholder?.let { { Text(it, color = c.textTertiary) } },
        singleLine = singleLine,
        isError = isError,
        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        trailingIcon = trailingIcon,
        textStyle = TextStyle(
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp,
            color = c.textPrimary,
        ),
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = c.textPrimary,
            unfocusedTextColor = c.textPrimary,
            disabledTextColor = c.textTertiary,
            cursorColor = c.accent,
            errorCursorColor = error,
            focusedBorderColor = c.accent,
            unfocusedBorderColor = c.divider,
            disabledBorderColor = c.divider.copy(alpha = 0.5f),
            errorBorderColor = error,
            focusedLabelColor = c.accent,
            unfocusedLabelColor = c.textTertiary,
            errorLabelColor = error,
            focusedPlaceholderColor = c.textTertiary,
            unfocusedPlaceholderColor = c.textTertiary,
            focusedTrailingIconColor = c.accent,
            unfocusedTrailingIconColor = c.textTertiary,
        ),
    )
}

/** A tappable tool tile for the bottom tool navigation. */
@Composable
fun ToolTile(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dot: Boolean = false,
    icon: @Composable () -> Unit = {},
) {
    val colors = Lumora.colors
    val type = Lumora.type
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            // One TalkBack node per tile: the icon's contentDescription would
            // duplicate the label text, so it is cleared, and the "has edits"
            // dot is silent pixels whose meaning moves into the state
            // description. The clickable must carry the onClickLabel — the
            // older pattern set onClick(label=...) in the semantics block
            // above, but a later clickable() replaces the action with an
            // unlabeled one, so TalkBack fell back to "double-tap to
            // activate".
            .semantics(mergeDescendants = true) {
                role = Role.Button
                if (selected || dot) {
                    stateDescription = listOfNotNull(
                        "Selected".takeIf { selected },
                        "Has edits".takeIf { dot },
                    ).joinToString(", ")
                }
            }
            .clickable(onClickLabel = label) { onClick() }
            .padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(26.dp).clearAndSetSemantics {},
        ) {
            icon()
            if (dot) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(7.dp)
                        .background(colors.accent, CircleShape),
                )
            }
        }
        Text(
            text = label,
            style = type.toolLabel,
            color = if (selected) colors.accent else colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(
            modifier = Modifier
                .size(width = 18.dp, height = 2.dp)
                .background(
                    if (selected) colors.accent else Color.Transparent,
                    RoundedCornerShape(1.dp)),
        )
    }
}

/** Top toolbar icon button with consistent sizing. */
@Composable
fun ToolbarButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = Lumora.colors
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(48.dp).semantics {
            contentDescription?.let { this.contentDescription = it }
        },
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = colors.textPrimary,
            disabledContentColor = colors.textTertiary.copy(alpha = 0.4f),
        ),
    ) { content() }
}

@Composable
fun EmptyState(
    title: String,
    body: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    /** Optional icon drawn in a soft accent medallion above the title. */
    icon: ImageVector? = null,
    /**
     * Compact variant: wraps content with a smaller medallion instead of
     * filling the screen — for bottom sheets and inline panels where the
     * full-screen variant would push the sheet open.
     */
    compact: Boolean = false,
    modifier: Modifier = Modifier,
    /**
     * Optional raw technical detail, rendered as a dimmed caption under the
     * action button — the UX-honesty pattern (friendly headline + plain
     * subline + diagnosable detail) shared with the GL preview fallback.
     * Null (the default) renders nothing, so existing callers are unchanged.
     */
    details: String? = null,
) {
    val colors = Lumora.colors
    val type = Lumora.type
    val medallion = if (compact) 64.dp else 96.dp
    val iconSize = if (compact) 28.dp else 42.dp
    Column(
        modifier = if (compact) modifier.fillMaxWidth().padding(24.dp)
                   else modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier.size(medallion)
                    .clip(CircleShape)
                    .background(colors.accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(iconSize),
                )
            }
            Spacer(Modifier.height(if (compact) 14.dp else 20.dp))
        }
        Text(title, style = type.screenTitle, color = colors.textPrimary)
        Spacer(Modifier.height(8.dp))
        Text(body, style = type.body, color = colors.textSecondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onAction,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.accent, contentColor = Color.Black),
                shape = RoundedCornerShape(10.dp),
            ) { Text(actionLabel) }
        }
        if (details != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                "Details: $details",
                style = type.caption,
                color = colors.textTertiary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

/**
 * Design-system progress indicator: accent-colored spinner (or determinate
 * ring when [progress] is given) with consistent stroke width and a TalkBack
 * announcement. Replaces raw Material3 [CircularProgressIndicator] everywhere
 * in the app.
 *
 * Callers keep passing their own size through [modifier] (e.g. `Modifier.size(18.dp)`);
 * only the styling is standardized. Pass [color] only when a one-off tint is
 * genuinely intended (the default is already the on-brand amber).
 */
@Composable
fun LumoraProgressIndicator(
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 2.dp,
    color: Color = Lumora.colors.accent,
    progress: (() -> Float)? = null,
    trackColor: Color = Lumora.colors.divider,
    contentDescription: String? = "Loading",
) {
    val described = if (contentDescription != null)
        modifier.semantics { this.contentDescription = contentDescription }
    else modifier
    if (progress != null) {
        CircularProgressIndicator(
            progress = progress,
            modifier = described,
            color = color,
            trackColor = trackColor,
            strokeWidth = strokeWidth,
        )
    } else {
        CircularProgressIndicator(
            modifier = described,
            color = color,
            strokeWidth = strokeWidth,
        )
    }
}

@Composable
fun LoadingState(label: String = "Loading…", modifier: Modifier = Modifier) {
    val colors = Lumora.colors
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            LumoraProgressIndicator(modifier = Modifier.size(36.dp), strokeWidth = 3.dp)
            Spacer(Modifier.height(12.dp))
            Text(label, style = Lumora.type.caption, color = colors.textSecondary)
        }
    }
}

/**
 * Skeleton placeholder with a diagonal shimmer sweep, used behind grid
 * thumbnails while they load (Lightroom shows content skeletons rather than
 * blank squares).
 */
@Composable
fun ShimmerBox(modifier: Modifier = Modifier) {
    val colors = Lumora.colors
    val shimmerColors = remember(colors) {
        listOf(
            colors.panelElevated,
            colors.textTertiary.copy(alpha = 0.38f),
            colors.panelElevated,
        )
    }
    val sweep = rememberInfiniteTransition(label = "shimmer")
    val t by sweep.animateFloat(
        initialValue = 0f,
        targetValue = 900f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerSweep",
    )
    val brush = remember(t) {
        Brush.linearGradient(
            colors = shimmerColors,
            start = Offset(t - 500f, t - 500f),
            end = Offset(t, t),
        )
    }
    Box(modifier.background(brush))
}

/**
 * Photo thumbnail image with skeleton loading and tap-to-retry error state.
 * [model] accepts anything Coil accepts (Uri, File, …). The shimmer is
 * hidden once the image is shown; the error state is drawn on top of the
 * placeholder so the cell never looks broken.
 */
@Composable
fun ThumbImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    allowRetry: Boolean = true,
    // Optional Coil cache key override. Needed when the model is a File that
    // gets overwritten in place (edited thumbnails): without it, Coil's
    // path-keyed cache can serve the previous bytes after a re-edit.
    cacheKey: String? = null,
) {
    val colors = Lumora.colors
    var phase by remember(model) { mutableStateOf(0) } // 0 loading, 1 ok, 2 error
    var retry by remember(model) { mutableStateOf(0) }
    if (model == null) {
        ShimmerBox(modifier)
        return
    }
    Box(modifier) {
        if (phase != 1) ShimmerBox(Modifier.fillMaxSize())
        key(retry) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(model)
                    .apply {
                        cacheKey?.let { k ->
                            memoryCacheKey(k)
                            diskCacheKey(k)
                        }
                    }
                    .crossfade(true)
                    .build(),
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                onLoading = { phase = 0 },
                onSuccess = { phase = 1 },
                onError = { phase = 2 },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (phase == 2) {
            Column(
                modifier = Modifier.fillMaxSize()
                    .then(if (allowRetry) Modifier.clickable(
                        // TalkBack (22:32 cycle): role + labeled action so
                        // the retry area announces as "Retry, button" instead
                        // of a bare "double-tap to activate".
                        role = Role.Button,
                        onClickLabel = "Retry",
                    ) {
                        retry += 1
                        phase = 0
                    } else Modifier),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = LumoraIcons.Error,
                    contentDescription = null,
                    tint = colors.textTertiary,
                    modifier = Modifier.size(28.dp),
                )
                if (allowRetry) {
                    Spacer(Modifier.height(4.dp))
                    Text("Tap to retry", style = Lumora.type.caption,
                        color = colors.textTertiary)
                }
            }
        }
    }
}

/**
 * Design-system dialog for standard confirm/dismiss flows: branded 20dp
 * corners (matches the developer popup), [Lumora.type.screenTitle] title in
 * primary text, body in [Lumora.type.body] secondary text, and text buttons
 * tinted accent — or [Lumora.colors.danger] for destructive confirms.
 * Replaces ad-hoc Material3 [AlertDialog] styling at the standard call
 * sites (reset, delete, save/rename prompts).
 *
 * Bespoke dialogs keep their own layouts: the developer popup uses a
 * [BasicAlertDialog] with a photo background, and the GPU self-test report
 * needs a custom two-action footer — those stay off this component.
 *
 * TalkBack announces the title as the dialog heading, the body text, then
 * the action buttons; the confirm and dismiss labels must therefore read
 * as standalone actions ("Delete" / "Cancel").
 */
@Composable
fun LumoraDialog(
    onDismissRequest: () -> Unit,
    title: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    /** Plain body copy. Ignored when [content] is provided. */
    text: String? = null,
    /** Custom body (text field, star row, …). Overrides [text]. */
    content: (@Composable () -> Unit)? = null,
    confirmLabel: String = "OK",
    confirmColor: Color = Lumora.colors.accent,
    confirmEnabled: Boolean = true,
    /** Set to null to hide the dismiss action (e.g. single-action dialogs). */
    dismissLabel: String? = "Cancel",
    onDismiss: (() -> Unit)? = null,
) {
    val c = Lumora.colors
    val type = Lumora.type
    AlertDialog(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        containerColor = c.panel,
        title = {
            Text(title, style = type.screenTitle, color = c.textPrimary)
        },
        text = if (content == null && text == null) null else {
            {
                if (content != null) content()
                else Text(
                    text!!, style = type.body, color = c.textSecondary,
                )
            }
        },
        confirmButton = {
            LumoraTextButton(onClick = onConfirm, enabled = confirmEnabled) {
                Text(
                    confirmLabel,
                    color = if (confirmEnabled) confirmColor else c.textTertiary,
                    style = type.body,
                )
            }
        },
        dismissButton = dismissLabel?.let { label ->
            {
                LumoraTextButton(onClick = { onDismiss?.invoke() ?: onDismissRequest() }) {
                    Text(label, color = c.textSecondary, style = type.body)
                }
            }
        },
    )
}

/**
 * Design-system bottom sheet. Wraps Material3 [ModalBottomSheet] with the
 * branded panel surface ([Lumora.colors.panel]) and a divider-tinted drag
 * handle, so every sheet in the app (history, snapshots, import, photo
 * details, preset/mask menus, AI assistant) shares one look. (The AI
 * assistant sheet previously rendered in the default M3 surface with no
 * branded handle — now it comes from here too.)
 *
 * TalkBack: the drag handle is decorative; the sheet's content carries the
 * announcements, so keep rows and titles labeled as usual.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LumoraBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Lumora.colors
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        containerColor = c.panel,
        dragHandle = { BottomSheetDefaults.DragHandle(color = c.divider) },
        content = content,
    )
}

/**
 * Design-system dropdown menu. Wraps Material3 [DropdownMenu] with the Lumora
 * surface ([Lumora.colors.panelElevated]) so every popover menu in the app
 * shares one background; rows use [LumoraDropdownMenuItem]. (The library sort
 * menu previously rendered in the default M3 surface while every other menu
 * hand-rolled panelElevated — now they all come from here.)
 *
 * TalkBack: a menu row is announced by its text; its leading icon is always
 * decorative. Selection state (e.g. the active sort order) belongs in the
 * row's [LumoraDropdownMenuItem.stateDescription], not in a content
 * description on the icon, which screen readers would announce redundantly
 * next to the row text.
 */
@Composable
fun LumoraDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier.background(Lumora.colors.panelElevated),
        content = content,
    )
}

/**
 * Design-system dropdown-menu row: [Lumora.type.body] label, optional
 * decorative leading glyph, optional trailing slot (e.g. the "N ★" rating
 * badge), 48dp minimum touch target (M3 default). Disabled rows dim to
 * [Lumora.colors.textTertiary].
 */
@Composable
fun ColumnScope.LumoraDropdownMenuItem(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** Decorative leading glyph — announced via [text], never separately. */
    icon: ImageVector? = null,
    iconTint: Color = Lumora.colors.textSecondary,
    textColor: Color = Lumora.colors.textPrimary,
    /** TalkBack state, e.g. "Selected" for the active sort order. */
    stateDescription: String? = null,
    /** Optional trailing slot, e.g. the "N ★" rating badge. */
    trailingContent: (@Composable () -> Unit)? = null,
) {
    val c = Lumora.colors
    val type = Lumora.type
    val fg = if (enabled) textColor else c.textTertiary
    DropdownMenuItem(
        text = { Text(text, color = fg, style = type.body) },
        onClick = onClick,
        // The param shadows the semantics property; qualify the LHS (same
        // lesson as LumoraSwitch's contentDescription).
        modifier = modifier.then(
            if (stateDescription != null)
                Modifier.semantics { this.stateDescription = stateDescription }
            else Modifier
        ),
        enabled = enabled,
        leadingIcon = icon?.let { iv ->
            {
                Icon(
                    iv, contentDescription = null,
                    tint = if (enabled) iconTint else c.textTertiary,
                )
            }
        },
        trailingIcon = trailingContent,
    )
}

/**
 * Design-system floating action button. Wraps Material3 [FloatingActionButton]
 * with the Lumora identity (warm-amber [Lumora.colors.accent] container, dark
 * content, circular shape) so both app FABs — the library's "import photos"
 * and the editor's AI-assistant shortcut — look like one app instead of two.
 * (The AI shortcut previously rendered in the default M3 primaryContainer
 * while the library used accent — now they share this component.)
 *
 * TalkBack: the wrapped FAB already carries role=Button via its onClick;
 * pass [accessibilityLabel] when the content alone would not announce well
 * (e.g. an emoji glyph like the ✨ assistant shortcut).
 */
@Composable
fun LumoraFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accessibilityLabel: String? = null,
    content: @Composable () -> Unit,
) {
    FloatingActionButton(
        onClick = onClick,
        modifier = if (accessibilityLabel != null)
            modifier.semantics { contentDescription = accessibilityLabel }
        else modifier,
        containerColor = Lumora.colors.accent,
        contentColor = Color.Black,
        shape = CircleShape,
        content = content,
    )
}

/**
 * Design-system icon button. Wraps Material3 [IconButton] with the Lumora
 * palette and a guaranteed 48dp minimum touch target (WCAG/A11y) — the last
 * un-consolidated control after the button/dialog/menu/progress/FAB sweeps.
 * The [contentDescription] label announces on the button; the glyph itself is
 * decorative. Disabled buttons dim to [Lumora.colors.textTertiary].
 *
 * Set [minTouchTarget] false only for intentionally small targets such as the
 * color-swatch remove-x; everything else gets the 48dp minimum.
 */
@Composable
fun LumoraIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = Lumora.colors.textSecondary,
    iconSize: Dp = 24.dp,
    minTouchTarget: Boolean = true,
) {
    IconButton(
        onClick = onClick,
        modifier = if (minTouchTarget)
            modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
        else modifier,
        enabled = enabled,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (enabled) tint else Lumora.colors.textTertiary,
            modifier = Modifier.size(iconSize),
        )
    }
}

/**
 * Standard screen top bar: [Lumora.colors.background] surface with the app's title
 * treatment. [subtitle] renders as an accent eyebrow above the title (the Library
 * screen's "H2W PHOTO LAB" treatment). All full screens share this so the top bar
 * never drifts back to the default Material3 surface.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LumoraTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = Lumora.colors
    val type = Lumora.type
    TopAppBar(
        title = {
            Column {
                if (subtitle != null) {
                    Text(subtitle, style = type.toolLabel, color = colors.accent)
                }
                Text(
                    title,
                    style = type.screenTitle,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        navigationIcon = navigationIcon,
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background),
        modifier = modifier,
    )
}

/**
 * Elevated content card with the standard [Lumora.colors.panelElevated] surface and
 * 12.dp corners. For [onBackground]/state surfaces (errors, warnings), pass
 * [containerColor]; the MaskPanel error card keeps its own red surface via params.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LumoraCard(
    modifier: Modifier = Modifier,
    containerColor: Color = Lumora.colors.panelElevated,
    shape: Shape = RoundedCornerShape(12.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = shape,
    ) {
        content()
    }
}
