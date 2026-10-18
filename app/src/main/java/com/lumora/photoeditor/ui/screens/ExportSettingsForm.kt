package com.lumora.photoeditor.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.lumora.photoeditor.core.AppContainer
import com.lumora.photoeditor.engine.ExportFormat
import com.lumora.photoeditor.engine.ExportSettings
import com.lumora.photoeditor.engine.EXPORT_LONG_EDGE_OPTIONS
import com.lumora.photoeditor.engine.WatermarkPosition
import com.lumora.photoeditor.engine.WatermarkSettings
import com.lumora.photoeditor.engine.sanitizeExportName
import com.lumora.photoeditor.export.ExportPreset
import com.lumora.photoeditor.export.isFactoryExportPreset
import com.lumora.photoeditor.ui.components.LumoraButton
import com.lumora.photoeditor.ui.components.LumoraChip
import com.lumora.photoeditor.ui.components.LumoraDialog
import com.lumora.photoeditor.ui.components.LumoraSlider
import com.lumora.photoeditor.ui.components.LumoraSwitch
import com.lumora.photoeditor.ui.components.LumoraTextButton
import com.lumora.photoeditor.ui.components.LumoraTextField
import com.lumora.photoeditor.ui.components.SegmentedControl
import com.lumora.photoeditor.ui.theme.Lumora
import kotlin.math.roundToInt

/**
 * Mutable holder for the export settings form, shared by the single-photo
 * [ExportScreen] and the batch export screen so both offer identical
 * settings. Initial values come from the sticky export defaults
 * ([com.lumora.photoeditor.data.AppSettings]); [persistStickyDefaults]
 * writes the current form back there, which is what the Export screen has
 * always done on every export.
 */
class ExportFormState(container: AppContainer) {
    private val settings = container.settings

    var format by mutableStateOf(
        runCatching { ExportFormat.valueOf(settings.defaultExportFormat) }
            .getOrDefault(ExportFormat.JPEG))
    var quality by mutableStateOf(settings.defaultExportQuality.toFloat())
    var longEdge by mutableStateOf(
        // Engine-valid range (0 = original); the old fixed option list
        // silently reset the per-platform recipe sizes (1080/1600/1920)
        // to 0, so those recipes exported at full resolution.
        settings.defaultExportLongEdge.takeIf { it in 0..16000 } ?: 0)
    var keepMetadata by mutableStateOf(settings.defaultKeepMetadata)
    var embedColorProfile by mutableStateOf(settings.defaultEmbedColorProfile)
    var wmEnabled by mutableStateOf(settings.watermarkEnabled)
    var wmText by mutableStateOf(settings.watermarkText)
    var wmPos by mutableStateOf(
        runCatching { WatermarkPosition.valueOf(settings.watermarkPosition) }
            .getOrDefault(WatermarkPosition.BOTTOM_RIGHT))
    var wmSize by mutableStateOf(settings.watermarkSize)
    var wmOpacity by mutableStateOf(settings.watermarkOpacity)
    var fileName by mutableStateOf(sanitizeExportName(settings.exportFileName))

    /** Snapshot of the current on-screen export configuration. */
    fun buildSettings(): ExportSettings = ExportSettings(
        format = format,
        quality = quality.toInt(),
        longEdge = longEdge,
        keepMetadata = keepMetadata,
        embedColorProfile = embedColorProfile,
        fileName = fileName,
        watermark = WatermarkSettings(
            enabled = wmEnabled,
            text = wmText,
            position = wmPos,
            sizeFrac = wmSize,
            opacity = wmOpacity,
        ),
    )

    /** Applies a saved preset to every on-screen control, clamped to valid ranges. */
    fun applyPreset(preset: ExportPreset) {
        val s = preset.settings
        format = s.format
        quality = s.quality.coerceIn(1, 100).toFloat()
        longEdge = s.longEdge.takeIf { it in 0..16000 } ?: 0
        keepMetadata = s.keepMetadata
        embedColorProfile = s.embedColorProfile
        fileName = s.fileName
        wmEnabled = s.watermark.enabled
        wmText = s.watermark.text
        wmPos = s.watermark.position
        wmSize = s.watermark.sizeFrac.coerceIn(0.02f, 0.06f)
        wmOpacity = s.watermark.opacity.coerceIn(0.1f, 1f)
    }

    /**
     * Restores factory export defaults on screen and in the sticky prefs.
     * The watermark signature text is intentionally preserved (it is the
     * user's identity, not a setting); named export presets are untouched.
     */
    fun resetToDefaults() {
        settings.resetExportDefaults()
        format = ExportFormat.JPEG
        quality = 92f
        longEdge = 0
        keepMetadata = true
        embedColorProfile = true
        fileName = ""
        wmEnabled = false
        wmPos = WatermarkPosition.BOTTOM_RIGHT
        wmSize = 0.035f
        wmOpacity = 0.7f
    }

    /** Writes the current form into the sticky export defaults. */
    fun persistStickyDefaults() {
        settings.defaultExportQuality = quality.toInt()
        settings.defaultExportFormat = format.name
        settings.exportFileName = sanitizeExportName(fileName)
        settings.defaultExportLongEdge = longEdge
        settings.defaultKeepMetadata = keepMetadata
        settings.defaultEmbedColorProfile = embedColorProfile
        settings.watermarkEnabled = wmEnabled
        settings.watermarkText = wmText
        settings.watermarkPosition = wmPos.name
        settings.watermarkSize = wmSize
        settings.watermarkOpacity = wmOpacity
    }
}

@Composable
fun rememberExportFormState(container: AppContainer): ExportFormState =
    remember { ExportFormState(container) }

/**
 * The export settings form: presets, format, dimensions, file name,
 * metadata and watermark — the same controls on the single-photo export
 * screen and the batch export screen. Must live inside a column that
 * already provides 16dp horizontal padding (the sliders net out their own
 * 16dp side padding against it).
 */
@Composable
fun ExportSettingsForm(
    form: ExportFormState,
    container: AppContainer,
) {
    val colors = Lumora.colors
    val type = Lumora.type
    val exportPresets by container.settings.exportPresets.collectAsState()
    var showSaveDialog by remember { mutableStateOf(false) }
    var presetName by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<ExportPreset?>(null) }

    Text("PRESETS", style = type.sectionTitle, color = colors.textSecondary)
    Row(
        modifier = Modifier.fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        exportPresets.forEach { preset ->
            val factory = isFactoryExportPreset(preset.id)
            LumoraChip(
                selected = false,
                onClick = { form.applyPreset(preset) },
                // Built-in recipes are not deletable.
                onLongClick = if (factory) null else {
                    { deleteTarget = preset }
                },
                onLongClickLabel = if (factory) null else "Delete export preset",
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (factory) {
                            // Accent dot marks the built-in recipes; no
                            // emoji, no text clutter on the small chips.
                            androidx.compose.foundation.layout.Box(
                                Modifier.size(6.dp)
                                    .clip(CircleShape)
                                    .background(colors.accent)
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(preset.name)
                    }
                },
            )
        }
        LumoraChip(
            selected = false,
            onClick = { presetName = ""; showSaveDialog = true },
            label = { Text("+ Save current") },
        )
    }
    Text("Tap a preset to apply it. Long-press one of your own presets to delete it.",
        style = type.caption, color = colors.textTertiary)
    Text("FORMAT", style = type.sectionTitle, color = colors.textSecondary)
    SegmentedControl(
        options = listOf(
            ExportFormat.JPEG to "JPEG",
            ExportFormat.PNG to "PNG",
            ExportFormat.WEBP to "WebP"),
        selected = form.format,
        onSelect = { form.format = it },
        modifier = Modifier.fillMaxWidth(),
    )
    if (form.format != ExportFormat.PNG) {
        LumoraSlider(
            label = "Quality",
            value = form.quality,
            onValueChange = { form.quality = it },
            valueRange = 1f..100f,
            default = container.settings.defaultExportQuality.toFloat(),
            valueText = "${form.quality.roundToInt()}%",
            // LumoraSlider carries its own 16dp side padding; the parent
            // column already provides it, so net it out to keep the control
            // aligned with the rest.
            modifier = Modifier.fillMaxWidth()
                .padding(horizontal = -16.dp),
        )
        Text("Higher quality = larger file. 90–95 is visually lossless for most photos.",
            style = type.caption, color = colors.textTertiary)
    }
    Text("DIMENSIONS", style = type.sectionTitle, color = colors.textSecondary)
    // Chip row (not a fixed segmented control): the built-in per-platform
    // recipes use 1080/1600/1920, which the old 4-option control could not
    // represent — tapping "Instagram post" silently exported full-res.
    Row(
        modifier = Modifier.fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        EXPORT_LONG_EDGE_OPTIONS.forEach { px ->
            LumoraChip(
                selected = form.longEdge == px,
                onClick = { form.longEdge = px },
            ) { Text(if (px == 0) "Original" else "${px}px") }
        }
    }
    Text("Long edge is resized down; images are never upscaled.",
        style = type.caption, color = colors.textTertiary)
    Text("FILE NAME", style = type.sectionTitle, color = colors.textSecondary)
    LumoraTextField(
        value = form.fileName,
        onValueChange = { form.fileName = it },
        label = "File name",
        placeholder = "My edited photo",
        modifier = Modifier.fillMaxWidth(),
    )
    val ext = when (form.format) {
        ExportFormat.JPEG -> "jpg"
        ExportFormat.PNG -> "png"
        ExportFormat.WEBP -> "webp"
    }
    val resolvedName = sanitizeExportName(form.fileName)
        .ifEmpty { "Lumora_<timestamp>" }
    Text("Saved to your gallery as \"$resolvedName.$ext\".",
        style = type.caption, color = colors.textTertiary)
    Text("METADATA", style = type.sectionTitle, color = colors.textSecondary)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Keep EXIF metadata", style = type.controlLabel,
                color = colors.textPrimary)
            Text("Camera info and capture settings are preserved.",
                style = type.caption, color = colors.textTertiary)
        }
        LumoraSwitch(
            checked = form.keepMetadata,
            onCheckedChange = { form.keepMetadata = it },
            contentDescription = "Keep EXIF metadata",
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Embed color profile", style = type.controlLabel,
                color = colors.textPrimary)
            Text("Tags exports with the sRGB ICC profile for consistent colors.",
                style = type.caption, color = colors.textTertiary)
        }
        LumoraSwitch(
            checked = form.embedColorProfile,
            onCheckedChange = { form.embedColorProfile = it },
            contentDescription = "Embed color profile",
        )
    }
    Text("WATERMARK", style = type.sectionTitle, color = colors.textSecondary)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Add watermark", style = type.controlLabel,
                color = colors.textPrimary)
            Text("Stamp your signature on exported photos.",
                style = type.caption, color = colors.textTertiary)
        }
        LumoraSwitch(
            checked = form.wmEnabled,
            onCheckedChange = { form.wmEnabled = it },
            contentDescription = "Add watermark to exported photos",
        )
    }
    if (form.wmEnabled) {
        LumoraTextField(
            value = form.wmText,
            onValueChange = { form.wmText = it },
            label = "Watermark text",
            placeholder = "© Htay Hlaing Win",
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            WatermarkPosition.entries.forEach { pos ->
                LumoraChip(
                    selected = form.wmPos == pos,
                    onClick = { form.wmPos = pos },
                ) { Text(pos.label) }
            }
        }
        LumoraSlider(
            label = "Size",
            value = form.wmSize * 100f,
            onValueChange = { form.wmSize = it / 100f },
            valueRange = 2f..6f,
            default = 3.5f,
            valueText = "${(form.wmSize * 100f).roundToInt()}%",
            modifier = Modifier.fillMaxWidth()
                .padding(horizontal = -16.dp),
        )
        LumoraSlider(
            label = "Opacity",
            value = form.wmOpacity * 100f,
            onValueChange = { form.wmOpacity = it / 100f },
            valueRange = 10f..100f,
            default = 70f,
            valueText = "${(form.wmOpacity * 100f).roundToInt()}%",
            modifier = Modifier.fillMaxWidth()
                .padding(horizontal = -16.dp),
        )
    }
    Spacer(Modifier.height(8.dp))
    // Escape hatch for the sticky export defaults: one tap restores
    // factory settings without touching the saved watermark signature or
    // the named export presets.
    LumoraTextButton(
        onClick = { form.resetToDefaults() },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Reset export settings to defaults",
            color = colors.textSecondary,
            style = type.controlLabel)
    }

    if (showSaveDialog) {
        LumoraDialog(
            onDismissRequest = { showSaveDialog = false },
            title = "Save export preset",
            content = {
                LumoraTextField(
                    value = presetName,
                    onValueChange = { presetName = it },
                    label = "Preset name",
                    placeholder = "e.g. Web upload",
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmLabel = "Save",
            confirmEnabled = presetName.isNotBlank(),
            onConfirm = {
                container.settings.saveExportPreset(presetName, form.buildSettings())
                showSaveDialog = false
            },
        )
    }
    deleteTarget?.let { target ->
        LumoraDialog(
            onDismissRequest = { deleteTarget = null },
            title = "Delete export preset?",
            text = "\"${target.name}\" will be removed.",
            confirmLabel = "Delete",
            confirmColor = colors.danger,
            onConfirm = {
                container.settings.deleteExportPreset(target.id)
                deleteTarget = null
            },
        )
    }
}

/** MIME type label for an export format (share sheet). */
fun formatMimeTypeFor(f: ExportFormat): String = when (f) {
    ExportFormat.JPEG -> "image/jpeg"
    ExportFormat.PNG -> "image/png"
    ExportFormat.WEBP -> "image/webp"
}
