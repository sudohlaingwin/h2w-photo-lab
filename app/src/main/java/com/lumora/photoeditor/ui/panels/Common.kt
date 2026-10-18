package com.lumora.photoeditor.ui.panels

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lumora.photoeditor.engine.EditState
import com.lumora.photoeditor.ui.components.CollapsibleSection
import com.lumora.photoeditor.ui.components.LumoraSlider
import com.lumora.photoeditor.ui.components.LumoraTextButton
import com.lumora.photoeditor.ui.state.EditorViewModel
import com.lumora.photoeditor.ui.theme.Lumora

/**
 * Binds a slider to one numeric field of [EditState] with proper
 * gesture coalescing for undo (one history entry per drag).
 */
@Composable
fun ParamSlider(
    vm: EditorViewModel,
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float> = -100f..100f,
    historyLabel: String = label,
    update: (EditState, Float) -> EditState,
) {
    LumoraSlider(
        label = label,
        value = value,
        valueRange = range,
        onBegin = { vm.beginGesture() },
        onValueChange = { v -> vm.update(gesture = true) { s -> update(s, v) } },
        onEnd = { vm.endGesture(historyLabel) },
    )
}

/** Scrollable container for a tool panel. */
@Composable
fun PanelColumn(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 360.dp)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
    ) {
        content()
        Spacer(Modifier.height(12.dp))
    }
}

/** Shorthand for a Lightroom-style section inside a panel. */
@Composable
fun PanelSection(
    title: String,
    initiallyExpanded: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) = CollapsibleSection(title, initiallyExpanded = initiallyExpanded, content = content)

/**
 * Lightroom-style panel header: panel title plus an optional "Reset"
 * affordance that restores this panel's parameters to defaults.
 * The reset button only appears when the panel is actually modified,
 * and the reset goes through the undo history so it can be undone.
 */
@Composable
fun PanelHeader(
    title: String,
    showReset: Boolean,
    resetLabel: String,
    onReset: () -> Unit,
    actionLabel: String? = null,
    actionContentDescription: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = Lumora.type.sectionTitle,
            color = Lumora.colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        if (onAction != null && actionLabel != null) {
            LumoraTextButton(
                onClick = onAction,
                modifier = Modifier
                    .heightIn(min = 40.dp)
                    .semantics { contentDescription = actionContentDescription ?: actionLabel },
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                Text(actionLabel, style = Lumora.type.controlLabel,
                    color = Lumora.colors.accent)
            }
        }
        if (showReset) {
            LumoraTextButton(
                onClick = onReset,
                modifier = Modifier
                    .heightIn(min = 40.dp)
                    .semantics { contentDescription = resetLabel },
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                Text("Reset", style = Lumora.type.controlLabel,
                    color = Lumora.colors.accent)
            }
        }
    }
}
