package com.lumora.photoeditor.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lumora.photoeditor.data.LibraryImage
import com.lumora.photoeditor.data.PhotoDetails
import com.lumora.photoeditor.data.loadPhotoDetails
import com.lumora.photoeditor.ui.components.ShimmerBox
import com.lumora.photoeditor.ui.components.LumoraBottomSheet
import com.lumora.photoeditor.ui.components.LumoraIconButton
import com.lumora.photoeditor.ui.components.LumoraTextField
import com.lumora.photoeditor.ui.theme.Lumora
import com.lumora.photoeditor.ui.theme.LumoraIcons

/**
 * Lightroom-parity photo info dialog: file, camera/EXIF and library data.
 * EXIF reads are best-effort — [loadPhotoDetails] never throws, so missing
 * values simply don't render. While loading, skeleton rows show (same
 * ShimmerBox pattern as the grid).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PhotoDetailsDialog(
    img: LibraryImage,
    onDismiss: () -> Unit,
    /** Phase C#6 slice 1: live keyword list for this photo (display form). */
    keywords: List<String> = emptyList(),
    onAddKeyword: (String) -> Unit = {},
    onRemoveKeyword: (String) -> Unit = {},
) {
    val colors = Lumora.colors
    val type = Lumora.type
    val context = LocalContext.current
    val details by produceState<PhotoDetails?>(
        initialValue = null,
        key1 = img.id,
    ) {
        value = loadPhotoDetails(context, img)
    }

    LumoraBottomSheet(
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                "Photo details",
                style = type.screenTitle,
                color = colors.textPrimary,
            )
            val d = details
            if (d == null) {
                Spacer(Modifier.height(12.dp))
                repeat(6) {
                    ShimmerBox(
                        Modifier
                            .fillMaxWidth()
                            .height(18.dp)
                            .clip(RoundedCornerShape(4.dp)),
                    )
                    Spacer(Modifier.height(10.dp))
                }
            } else {
                DetailSectionTitle("File")
                DetailRow("Name", d.displayName, maxLines = 2)
                d.format?.let { DetailRow("Format", it) }
                DetailRow("Folder", d.folder.ifBlank { "—" })
                DetailRow("Dimensions", d.dimensions)
                d.fileSize?.let { DetailRow("Size", it) }
                DetailRow("Added", d.addedDate)
                DetailRow("Edits", d.editCount.toString())

                if (d.camera != null || d.capturedDate != null || d.iso != null ||
                    d.aperture != null || d.shutter != null || d.focalLength != null
                ) {
                    DetailSectionTitle("Camera")
                    d.camera?.let { DetailRow("Camera", it) }
                    d.capturedDate?.let { DetailRow("Captured", it) }
                    d.iso?.let { DetailRow("ISO", it) }
                    d.aperture?.let { DetailRow("Aperture", it) }
                    d.shutter?.let { DetailRow("Shutter", it) }
                    d.focalLength?.let { DetailRow("Focal length", it) }
                }

                DetailSectionTitle("Library")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .semantics(mergeDescendants = true) {
                            contentDescription = if (d.rating > 0)
                                "Rated ${d.rating} of 5 stars"
                            else "Not rated"
                        },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Rating", style = type.caption, color = colors.textSecondary)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        for (i in 1..5) {
                            val filled = i <= d.rating
                            Icon(
                                if (filled) Icons.Default.Star else LumoraIcons.StarBorder,
                                contentDescription = null,
                                tint = if (filled) colors.accent else colors.textTertiary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
                if (d.favorite) {
                    DetailRow("Favorite", "Yes")
                }

                // Phase C#6 slice 1: keyword tagging (Lightroom culling
                // parity). Chips remove on tap; the field adds on Add/IME.
                DetailSectionTitle("Keywords")
                KeywordEditor(
                    keywords = keywords,
                    onAdd = onAddKeyword,
                    onRemove = onRemoveKeyword,
                )
            }
        }
    }
}

/**
 * Keyword tag editor: removable chips plus an add field. The field clears
 * after a successful add; blank/overlong input is ignored by the store, so
 * the field simply stays (the user sees nothing was added).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeywordEditor(
    keywords: List<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    val colors = Lumora.colors
    val type = Lumora.type
    var draft by remember { mutableStateOf("") }

    if (keywords.isNotEmpty()) {
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (kw in keywords) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = colors.panelElevated,
                    contentColor = colors.textPrimary,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 12.dp, end = 4.dp),
                    ) {
                        Text(
                            kw,
                            style = type.caption,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // 48dp minimum hit area (LumoraIconButton default): the old
                        // 28.dp IconButton was below the platform touch-target
                        // minimum. The visual glyph stays 16dp; only the
                        // tappable region grows to the platform minimum.
                        LumoraIconButton(
                            icon = Icons.Default.Close,
                            contentDescription = "Remove keyword $kw",
                            onClick = { onRemove(kw) },
                            tint = colors.textSecondary,
                            iconSize = 16.dp,
                        )
                    }
                }
            }
        }
    } else {
        Text(
            "No keywords yet — add some to find this photo faster.",
            style = type.caption,
            color = colors.textTertiary,
            modifier = Modifier.padding(vertical = 4.dp),
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LumoraTextField(
            value = draft,
            onValueChange = { draft = it },
            label = "Keyword",
            placeholder = "e.g. sunset, family",
            modifier = Modifier.weight(1f),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                onDone = {
                    if (draft.isNotBlank()) {
                        onAdd(draft)
                        draft = ""
                    }
                },
            ),
        )
        TextButton(onClick = {
            if (draft.isNotBlank()) {
                onAdd(draft)
                draft = ""
            }
        }) {
            Text("Add", color = colors.accent)
        }
    }
}

@Composable
private fun DetailSectionTitle(text: String) {
    val colors = Lumora.colors
    Text(
        text,
        style = Lumora.type.sectionTitle,
        color = colors.textSecondary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun DetailRow(label: String, value: String, maxLines: Int = 1) {
    val colors = Lumora.colors
    val type = Lumora.type
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, style = type.caption, color = colors.textSecondary)
        Spacer(Modifier.width(16.dp))
        Text(
            value,
            style = type.body,
            color = colors.textPrimary,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
