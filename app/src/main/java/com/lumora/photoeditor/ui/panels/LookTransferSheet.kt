package com.lumora.photoeditor.ui.panels

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.lumora.photoeditor.ui.components.EmptyState
import com.lumora.photoeditor.ui.components.LumoraBottomSheet
import com.lumora.photoeditor.ui.components.ThumbImage
import com.lumora.photoeditor.ui.state.EditorViewModel
import com.lumora.photoeditor.ui.theme.Lumora
import com.lumora.photoeditor.ui.theme.LumoraIcons

/**
 * Reference-photo picker for look transfer (pro-spec Phase 1): the user
 * picks a library photo whose colors become a 3D-LUT grade on the current
 * photo. The currently open photo is excluded — matching a photo to itself
 * is always a no-op.
 */
@Composable
fun LookTransferSheet(
    vm: EditorViewModel,
    onDismiss: () -> Unit,
    onPick: (imageId: String) -> Unit,
) {
    val colors = Lumora.colors
    val type = Lumora.type
    val images by vm.libraryImages().collectAsState()
    val currentId = vm.currentImageId()
    val candidates = remember(images, currentId) {
        images.filter { it.id != currentId && it.contentUri != null }
    }

    LumoraBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
        ) {
            Text(
                "Match a photo's look",
                style = type.screenTitle,
                color = colors.textPrimary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            Text(
                "Pick a reference photo — its colors are matched onto this " +
                    "photo as one undoable grade. Works fully offline.",
                style = type.caption,
                color = colors.textSecondary,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(12.dp))
            if (candidates.isEmpty()) {
                EmptyState(
                    title = "No other photos yet",
                    body = "Import another photo into the library to use it as a color reference.",
                    icon = LumoraIcons.Landscape,
                    compact = true,
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(candidates, key = { it.id }) { img ->
                        ThumbImage(
                            model = img.contentUri,
                            contentDescription = "Use ${img.displayName} as color reference",
                            modifier = Modifier
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable(
                                    role = Role.Button,
                                    onClickLabel = "Use ${img.displayName} as color reference",
                                ) { onPick(img.id) },
                        )
                    }
                }
            }
        }
    }
}
