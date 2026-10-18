package com.lumora.photoeditor.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import com.lumora.photoeditor.ui.components.LumoraProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lumora.photoeditor.ai.AiChatMessage
import com.lumora.photoeditor.ai.AiSuggestionKeys
import com.lumora.photoeditor.ai.AiSuggestions
import com.lumora.photoeditor.ai.only
import com.lumora.photoeditor.ui.components.LumoraCard
import com.lumora.photoeditor.ui.components.LumoraCheckbox
import com.lumora.photoeditor.ui.components.LumoraIconButton
import com.lumora.photoeditor.ui.components.LumoraOutlineButton
import com.lumora.photoeditor.ui.components.LumoraButton
import com.lumora.photoeditor.ui.components.LumoraTextField
import com.lumora.photoeditor.ui.components.LumoraTextButton
import com.lumora.photoeditor.ui.theme.Lumora
import com.lumora.photoeditor.ui.theme.LumoraIcons

/**
 * AI Photo Assistant panel: chat with Gemini about the current photo.
 * - "Analyze photo" sends the downscaled image + current settings to Gemini
 * - Gemini returns structured suggestions; user can apply them with one tap
 * - Free-text follow-up questions supported
 * - API key is entered once and stored encrypted on-device
 */
@Composable
fun AiAssistantPanel(
    messages: List<AiChatMessage>,
    isLoading: Boolean,
    error: String?,
    pendingSuggestions: AiSuggestions?,
    hasApiKey: Boolean,
    onAnalyzePhoto: () -> Unit,
    onSendMessage: (String) -> Unit,
    onApplySuggestions: (AiSuggestions) -> Unit,
    onDismissSuggestions: () -> Unit,
    onClearError: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Lumora.colors.panel)
            .padding(12.dp),
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("✨", style = Lumora.type.screenTitle)
            Spacer(Modifier.width(8.dp))
            Text(
                "AI Photo Assistant",
                style = Lumora.type.screenTitle,
                color = Lumora.colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            LumoraIconButton(
                icon = Icons.Filled.Close,
                contentDescription = "Close AI assistant",
                onClick = onClose,
            )
        }

        if (!hasApiKey) {
            LumoraCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "No API key set.",
                        style = Lumora.type.body,
                        fontWeight = FontWeight.Bold,
                        color = Lumora.colors.textPrimary,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Go to Settings → AI Assistant to add your free Gemini API key.",
                        style = Lumora.type.caption,
                        color = Lumora.colors.textSecondary,
                    )
                }
            }
        }

        // Suggestion card (if pending)
        pendingSuggestions?.let { s ->
            SuggestionCard(
                suggestions = s,
                onApplyFiltered = onApplySuggestions,
                onDismiss = onDismissSuggestions,
            )
        }

        // Messages
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (messages.isEmpty() && hasApiKey) {
                item {
                    Text(
                        "Tap \"Analyze photo\" and I'll look at your image and " +
                            "suggest the best settings — or just ask me anything " +
                            "about photo editing.",
                        style = Lumora.type.body,
                        color = Lumora.colors.textSecondary,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                }
            }
            items(messages) { msg ->
                MessageBubble(msg)
            }
            if (isLoading) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        LumoraProgressIndicator(modifier = Modifier.size(28.dp))
                    }
                }
            }
        }

        error?.let { err ->
            LumoraCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                containerColor = Lumora.colors.danger.copy(alpha = 0.16f),
            ) {
                Row(
                    modifier = Modifier.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        err,
                        style = Lumora.type.caption,
                        color = Lumora.colors.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    LumoraTextButton(
                        onClick = onClearError,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = Lumora.colors.accent),
                    ) { Text("Dismiss") }
                }
            }
        }

        // Analyze button
        if (hasApiKey) {
            LumoraOutlineButton(
                onClick = onAnalyzePhoto,
                enabled = !isLoading,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = Lumora.colors.accent),
            ) {
                // Design-system icon (was a 📷 emoji glyph): decorative for
                // TalkBack — the label carries the meaning.
                Icon(LumoraIcons.PhotoCamera, contentDescription = null,
                    modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Analyze current photo")
            }
            Spacer(Modifier.height(8.dp))
        }

        // Input row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LumoraTextField(
                value = input,
                onValueChange = { input = it },
                label = "Ask about editing",
                placeholder = "Ask about editing…",
                modifier = Modifier.weight(1f),
                enabled = hasApiKey && !isLoading,
            )
            Spacer(Modifier.width(8.dp))
            LumoraButton(
                onClick = {
                    if (input.isNotBlank()) {
                        onSendMessage(input.trim())
                        input = ""
                    }
                },
                enabled = hasApiKey && !isLoading && input.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Lumora.colors.accent,
                    contentColor = Color.Black,
                    disabledContainerColor = Lumora.colors.divider,
                    disabledContentColor = Lumora.colors.textTertiary,
                ),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send message",
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(msg: AiChatMessage) {
    val isUser = msg.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            modifier = Modifier
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp, topEnd = 16.dp,
                        bottomStart = if (isUser) 16.dp else 4.dp,
                        bottomEnd = if (isUser) 4.dp else 16.dp,
                    ),
                )
                .background(
                    if (isUser) Lumora.colors.accentDim
                    else Lumora.colors.panelElevated,
                )
                .padding(10.dp)
                .fillMaxWidth(0.85f),
        ) {
            Text(
                msg.text,
                style = Lumora.type.body,
                color = Lumora.colors.textPrimary,
            )
        }
    }
}

@Composable
private fun SuggestionCard(
    suggestions: AiSuggestions,
    onApplyFiltered: (AiSuggestions) -> Unit,
    onDismiss: () -> Unit,
) {
    LumoraCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("✨", style = Lumora.type.controlLabel)
                Spacer(Modifier.width(6.dp))
                Text(
                    "Suggested settings",
                    fontWeight = FontWeight.Medium,
                    style = Lumora.type.controlLabel,
                    color = Lumora.colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                LumoraTextButton(
                    onClick = onDismiss,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = Lumora.colors.accent),
                ) { Text("Dismiss") }
            }
            if (suggestions.analysis.isNotBlank()) {
                Text(
                    suggestions.analysis,
                    style = Lumora.type.body,
                    color = Lumora.colors.textPrimary,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
            // Show non-null suggestion values as selectable rows: the user ticks
            // which suggestions to apply instead of all-or-nothing.
            val rows = buildList {
                suggestions.exposure?.let {
                    add(Triple(AiSuggestionKeys.EXPOSURE, "Exposure", formatFloat(it))) }
                suggestions.contrast?.let {
                    add(Triple(AiSuggestionKeys.CONTRAST, "Contrast", formatInt(it))) }
                suggestions.highlights?.let {
                    add(Triple(AiSuggestionKeys.HIGHLIGHTS, "Highlights", formatInt(it))) }
                suggestions.shadows?.let {
                    add(Triple(AiSuggestionKeys.SHADOWS, "Shadows", formatInt(it))) }
                suggestions.whites?.let {
                    add(Triple(AiSuggestionKeys.WHITES, "Whites", formatInt(it))) }
                suggestions.blacks?.let {
                    add(Triple(AiSuggestionKeys.BLACKS, "Blacks", formatInt(it))) }
                suggestions.vibrance?.let {
                    add(Triple(AiSuggestionKeys.VIBRANCE, "Vibrance", formatInt(it))) }
                suggestions.saturation?.let {
                    add(Triple(AiSuggestionKeys.SATURATION, "Saturation", formatInt(it))) }
                suggestions.temp?.let {
                    add(Triple(AiSuggestionKeys.TEMP, "Temp", formatInt(it))) }
                suggestions.tint?.let {
                    add(Triple(AiSuggestionKeys.TINT, "Tint", formatInt(it))) }
                suggestions.sharpenAmount?.let {
                    add(Triple(AiSuggestionKeys.SHARPEN, "Sharpen", formatInt(it))) }
                suggestions.noiseReduction?.let {
                    add(Triple(AiSuggestionKeys.NOISE_REDUCTION, "Noise reduction",
                        formatInt(it))) }
                suggestions.vignette?.let {
                    add(Triple(AiSuggestionKeys.VIGNETTE, "Vignette", formatInt(it))) }
            }
            var checked by remember(suggestions) {
                mutableStateOf(rows.map { it.first }.toSet())
            }
            if (rows.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Tick the ones to apply",
                        style = Lumora.type.caption,
                        color = Lumora.colors.textSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    LumoraTextButton(
                        onClick = {
                            checked = if (checked.size == rows.size) emptySet()
                            else rows.map { it.first }.toSet()
                        },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = Lumora.colors.accent),
                    ) {
                        Text(if (checked.size == rows.size) "None" else "All")
                    }
                }
                rows.forEach { (key, label, value) ->
                    val isChecked = key in checked
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = isChecked,
                                role = Role.Checkbox,
                                onValueChange = { on ->
                                    checked = if (on) checked + key else checked - key
                                },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Display-only: the Row above is the toggleable, so the
                        // box just renders state (LumoraCheckbox pins the
                        // disabled colors so it doesn't wash out at 38%).
                        LumoraCheckbox(
                            checked = isChecked,
                            onCheckedChange = null,
                            contentDescription = null,
                        )
                        Text(
                            "$label: $value",
                            style = Lumora.type.caption,
                            fontWeight = FontWeight.Medium,
                            color = if (isChecked) Lumora.colors.textPrimary
                            else Lumora.colors.textSecondary,
                        )
                    }
                }
            }
            if (suggestions.reasoning.isNotBlank()) {
                Text(
                    suggestions.reasoning,
                    style = Lumora.type.caption,
                    color = Lumora.colors.textSecondary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            val applyCount = checked.size
            LumoraButton(
                onClick = { onApplyFiltered(suggestions.only(checked)) },
                enabled = applyCount > 0,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Lumora.colors.accent,
                    contentColor = Color.Black,
                ),
            ) {
                Text(
                    if (applyCount == rows.size) "Apply all to photo"
                    else "Apply $applyCount selected"
                )
            }
        }
    }
}

private fun formatFloat(v: Float): String =
    (if (v > 0) "+" else "") + "%.2f".format(v)

private fun formatInt(v: Float): String {
    val i = v.toInt()
    return (if (i > 0) "+" else "") + i.toString()
}
