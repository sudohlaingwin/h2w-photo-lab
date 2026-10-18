package com.lumora.photoeditor.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import com.lumora.photoeditor.core.AppContainer
import com.lumora.photoeditor.engine.RawDecoder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lumora.photoeditor.data.LibraryImage
import com.lumora.photoeditor.data.PhotoDetails
import com.lumora.photoeditor.data.ProofMark
import com.lumora.photoeditor.data.ProofingGallery
import com.lumora.photoeditor.data.SortOrder
import com.lumora.photoeditor.data.keywordKeyOf
import com.lumora.photoeditor.data.loadPhotoDetails
import com.lumora.photoeditor.data.next
import com.lumora.photoeditor.engine.Preset
import com.lumora.photoeditor.ui.components.EmptyState
import com.lumora.photoeditor.ui.components.LumoraTextField
import com.lumora.photoeditor.ui.components.LumoraTopBar
import com.lumora.photoeditor.ui.components.LumoraDialog
import com.lumora.photoeditor.ui.components.LumoraDropdownMenu
import com.lumora.photoeditor.ui.components.LumoraDropdownMenuItem
import com.lumora.photoeditor.ui.components.LumoraOutlineButton
import com.lumora.photoeditor.ui.components.LumoraButton
import com.lumora.photoeditor.ui.components.LoadingState
import com.lumora.photoeditor.ui.components.LumoraChip
import com.lumora.photoeditor.ui.components.LumoraSlider
import com.lumora.photoeditor.ui.components.LumoraFab
import com.lumora.photoeditor.ui.components.LumoraIconButton
import com.lumora.photoeditor.ui.components.ThumbImage
import com.lumora.photoeditor.ui.components.LumoraTextButton
import com.lumora.photoeditor.ui.components.LumoraBottomSheet
import com.lumora.photoeditor.ui.components.LumoraSwitch
import com.lumora.photoeditor.data.LibraryFilter
import com.lumora.photoeditor.ui.state.LibraryViewModel
import kotlin.math.roundToInt
import com.lumora.photoeditor.ui.theme.Lumora
import com.lumora.photoeditor.ui.theme.LumoraIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    vm: LibraryViewModel,
    container: AppContainer,
    onOpenPhoto: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onBatchExport: (List<String>) -> Unit = {},
    /** Phase C#7 slice 2: open the contact-sheet builder for a gallery id. */
    onContactSheet: (String) -> Unit = {},
) {
    val colors = Lumora.colors
    val type = Lumora.type
    val context = LocalContext.current
    val images by vm.images.collectAsState()
    val filter by vm.filter.collectAsState()
    val query by vm.query.collectAsState()
    // Phase C#6 slice 1: keyword tagging/filtering (Lightroom culling parity).
    val keywordFilter by vm.keywordFilter.collectAsState()
    val allKeywords by vm.allKeywords.collectAsState()
    // Phase C#6 slice 2: smart collections (Lightroom parity) — saved
    // dynamic filters (keyword + min rating + favorites-only).
    val smartCollections by vm.smartCollections.collectAsState()
    val activeCollectionId by vm.activeSmartCollectionId.collectAsState()
    // Phase C#7 slice 1: proofing galleries (share/select workflow) — named
    // ordered photo sets with per-photo client marks (approved/rejected).
    val galleries by vm.galleries.collectAsState()
    val activeGalleryId by vm.activeGalleryId.collectAsState()
    // Filter-aware empty states need to know whether the library itself is
    // empty or whether only this filter/search matches nothing.
    val hasAnyPhotos by vm.hasAnyPhotos.collectAsState()
    val importing by vm.importing.collectAsState()
    val merging by vm.merging.collectAsState()
    val stacking by vm.stacking.collectAsState()
    val stitching by vm.stitching.collectAsState()
    val copiedEdits by container.copiedEdits.collectAsState()
    val sortOrder by vm.sortOrder.collectAsState()
    val gridColumns by vm.gridColumns.collectAsState()
    var showImport by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var contextMenuFor by remember { mutableStateOf<LibraryImage?>(null) }
    // Photo info dialog (Lightroom-parity Details): set from the photo menu.
    var detailsFor by remember { mutableStateOf<LibraryImage?>(null) }
    // transient confirmation chip ("Virtual copy created")
    var notice by remember { mutableStateOf<String?>(null) }
    // Multi-select (Lightroom parity): long-press enters selection mode.
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    val selectionMode = selectedIds.isNotEmpty()
    var showDeleteConfirm by remember { mutableStateOf(false) }
    // Batch-apply preset sheet (Lightroom "Sync settings" parity).
    var showApplyPreset by remember { mutableStateOf(false) }
    // Phase C#6 slice 2: create/edit dialog for a smart collection.
    // Non-null opens the dialog; id == null means "new collection".
    var collectionEditor by remember { mutableStateOf<CollectionEditorState?>(null) }
    // Phase C#7 slice 1: create/edit dialog for a proofing gallery.
    // Non-null opens the dialog; id == null means "new gallery", in which
    // case pendingPhotoIds are the selection it is created from.
    var galleryEditor by remember { mutableStateOf<GalleryEditorState?>(null) }
    // Phase C#7 slice 3: confirm dialog for the proofing round-trip
    // (apply a gallery's client marks to the library's ratings/favorites).
    // Holds the gallery being applied; null means "no dialog".
    var applyPicksFor by remember { mutableStateOf<ProofingGallery?>(null) }
    val scope = rememberCoroutineScope()

    BackHandler(enabled = selectionMode) { selectedIds = emptySet() }

    LaunchedEffect(notice) {
        if (notice != null) {
            kotlinx.coroutines.delay(2200)
            notice = null
        }
    }

    val haptics = LocalHapticFeedback.current
    // Pinch-to-zoom grid density (Lightroom parity): accumulate the transform's
    // zoom factor; when the gesture ends, step the column count once.
    var pinchAccum by remember { mutableStateOf(1f) }
    val gridTransform = rememberTransformableState { zoomChange, _, _ ->
        pinchAccum *= zoomChange
    }
    val pinchActive = gridTransform.isTransformInProgress
    LaunchedEffect(pinchActive) {
        if (!pinchActive && pinchAccum != 1f) {
            val acc = pinchAccum
            pinchAccum = 1f
            val cur = vm.gridColumns.value
            // Pinch in (acc > 1): fewer, larger cells. Pinch out: more, smaller.
            val next = when {
                acc >= 1.25f -> (cur - 1).coerceAtLeast(2)
                acc <= 1f / 1.25f -> (cur + 1).coerceAtMost(5)
                else -> cur
            }
            if (next != cur) {
                vm.setGridColumns(next)
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                notice = "Grid: $next columns"
            }
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 20),
    ) { uris ->
        if (uris.isNotEmpty()) vm.importUris(uris) {}
    }

    // RAW program Phase 1 slice 2 / Phase 2 slice 1: the system photo picker
    // (ImageOnly) does not reliably surface RAW files, so RAW import gets
    // its own document picker filtered to the RAW MIME types Android knows.
    // The MIME list is best-effort — providers honor what they honor — and
    // the editor routes by extension ([RawDecoder.isRaw]) into the native
    // LibRaw bridge (RawDecoder) when the photo opens, so proprietary
    // formats (CR2/CR3/NEF/ARW/RAF/ORF/RW2/...) decode through the same path
    // as DNG. Any non-RAW file picked here still imports fine — it just
    // opens through the normal BitmapFactory path.
    val rawPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) vm.importUris(listOf(uri)) {}
    }

    // Camera capture (Lightroom-parity import path): TakePicture needs no
    // CAMERA permission — the external camera app owns the capture. The
    // photo lands in the app's private files dir so the library row keeps
    // a stable URI after import; on success the editor opens directly on
    // the new photo.
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    var pendingCameraFile by remember { mutableStateOf<File?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { ok ->
        val uri = pendingCameraUri
        val file = pendingCameraFile
        pendingCameraUri = null
        pendingCameraFile = null
        if (ok && uri != null) {
            vm.importCameraPhoto(uri) { ids ->
                val first = ids.firstOrNull()
                if (first != null) {
                    showImport = false
                    onOpenPhoto(first)
                } else {
                    notice = "Could not import the photo"
                }
            }
        } else {
            // Cancelled capture: don't leave an empty file behind.
            try { if (file?.exists() == true) file.delete() } catch (_: Exception) { }
        }
    }
    fun launchCamera() {
        try {
            val dir = File(context.filesDir, "camera").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(dir, "H2W_$stamp.jpg")
            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file)
            pendingCameraUri = uri
            pendingCameraFile = file
            cameraLauncher.launch(uri)
        } catch (_: Exception) {
            notice = "Could not open the camera"
        }
    }

    // Hoisted with explicit @Composable types: a nested lambda literal inside
    // `navigationIcon = if (...) { { ... } } else null` loses the @Composable
    // annotation through conditional inference and fails compilation (and this
    // Material3 TopAppBar's navigationIcon is non-nullable, so null is out).
    val clearSelectionIcon: @Composable () -> Unit = {
        LumoraIconButton(
            icon = Icons.Default.Close,
            contentDescription = "Clear selection",
            onClick = { selectedIds = emptySet() },
        )
    }
    val emptyNavIcon: @Composable () -> Unit = {}
    val selectionNavIcon: @Composable () -> Unit =
        if (selectionMode) clearSelectionIcon else emptyNavIcon

    Scaffold(
        containerColor = colors.background,
        topBar = {
            Column {
                LumoraTopBar(
                    title = if (selectionMode) "${selectedIds.size} selected" else "Library",
                    subtitle = if (selectionMode) null else "H2W PHOTO LAB",
                    navigationIcon = selectionNavIcon,
                    actions = {
                        if (selectionMode) {
                            LumoraIconButton(
                                icon = LumoraIcons.IosShare,
                                contentDescription = "Export selected photos",
                                tint = colors.accent,
                                onClick = {
                                    // Grid order, not selection order: the
                                    // batch screen lists them as shown here.
                                    val ordered = images
                                        .filter { it.id in selectedIds }
                                        .map { it.id }
                                    selectedIds = emptySet()
                                    onBatchExport(ordered)
                                },
                            )
                            if (copiedEdits != null) {
                                LumoraIconButton(
                                    icon = LumoraIcons.ContentPaste,
                                    contentDescription = "Paste edits to selected photos",
                                    tint = colors.accent,
                                    onClick = {
                                        val ids = selectedIds
                                        vm.pasteEditsToMany(ids) { n ->
                                            notice = when {
                                                n == ids.size -> if (n == 1)
                                                    "Pasted edits to 1 photo"
                                                else
                                                    "Pasted edits to $n photos"
                                                n > 0 -> "Pasted edits to $n of ${ids.size} photos"
                                                else -> "Could not paste edits"
                                            }
                                            selectedIds = emptySet()
                                        }
                                    },
                                )
                            }
                            LumoraIconButton(
                                icon = LumoraIcons.Style,
                                contentDescription = "Apply preset",
                                tint = colors.accent,
                                onClick = { showApplyPreset = true },
                            )
                            // Phase C#7 slice 1: create a proofing gallery
                            // from the selection (share/select workflow).
                            LumoraIconButton(
                                icon = Icons.Default.Share,
                                contentDescription = "Create proofing gallery",
                                tint = colors.accent,
                                onClick = {
                                    // Grid order, not selection order.
                                    val ids = images
                                        .filter { it.id in selectedIds }
                                        .map { it.id }
                                    galleryEditor = GalleryEditorState(
                                        pendingPhotoIds = ids,
                                    )
                                },
                            )
                            if (activeGalleryId != null) {
                                // In a gallery: grow or shrink its member set
                                // (the photos themselves stay in the library).
                                LumoraIconButton(
                                    icon = Icons.Default.Add,
                                    contentDescription = "Add selected to gallery",
                                    tint = colors.accent,
                                    onClick = {
                                        val gid = activeGalleryId ?: return@LumoraIconButton
                                        val ids = images
                                            .filter { it.id in selectedIds }
                                            .map { it.id }
                                        vm.addPhotosToGallery(gid, ids) { n ->
                                            notice = if (n > 0)
                                                "Added $n photo${if (n == 1) "" else "s"} to the gallery"
                                            else "Those photos are already in the gallery"
                                            selectedIds = emptySet()
                                        }
                                    },
                                )
                                LumoraIconButton(
                                    icon = Icons.Default.Delete,
                                    contentDescription = "Remove selected from gallery",
                                    tint = colors.accent,
                                    onClick = {
                                        val gid = activeGalleryId ?: return@LumoraIconButton
                                        val ids = selectedIds
                                        vm.removePhotosFromGallery(gid, ids) { n ->
                                            notice = if (n > 0)
                                                "Removed $n photo${if (n == 1) "" else "s"} from the gallery"
                                            else "Nothing to remove"
                                            selectedIds = emptySet()
                                        }
                                    },
                                )
                            }
                            if (selectedIds.size >= 2) {
                                // Phase 4 "Merge to HDR": exposure-fuses 2+
                                // aligned bracketed frames into a new editable
                                // library photo (not AI — see mergeHdr docs).
                                LumoraIconButton(
                                    icon = LumoraIcons.Gradient,
                                    contentDescription = "Merge to HDR",
                                    tint = colors.accent,
                                    onClick = {
                                        // Grid order, not selection order.
                                        val ids = images
                                            .filter { it.id in selectedIds }
                                            .map { it.id }
                                        vm.mergeHdr(ids) { newId ->
                                            notice = if (newId != null)
                                                "HDR merge saved to your library"
                                            else
                                                "HDR merge failed — use aligned shots"
                                            selectedIds = emptySet()
                                        }
                                    },
                                )
                            }
                            if (selectedIds.size >= 2) {
                                // Phase 4 "Focus Stack": stacks 2+ aligned
                                // frames at different focus distances into one
                                // all-in-focus composite (not AI — see
                                // focusStack docs).
                                LumoraIconButton(
                                    icon = LumoraIcons.CenterFocusStrong,
                                    contentDescription = "Focus Stack",
                                    tint = colors.accent,
                                    onClick = {
                                        val ids = images
                                            .filter { it.id in selectedIds }
                                            .map { it.id }
                                        vm.focusStack(ids) { newId ->
                                            notice = if (newId != null)
                                                "Focus stack saved to your library"
                                            else
                                                "Focus stack failed — use aligned shots"
                                            selectedIds = emptySet()
                                        }
                                    },
                                )
                            }
                            if (selectedIds.size >= 2) {
                                // Phase 4 "Panorama": stitches 2+ overlapping
                                // frames shot left-to-right into one wide
                                // panorama (not AI — see panorama docs).
                                LumoraIconButton(
                                    icon = LumoraIcons.Panorama,
                                    contentDescription = "Stitch panorama",
                                    tint = colors.accent,
                                    onClick = {
                                        val ids = images
                                            .filter { it.id in selectedIds }
                                            .map { it.id }
                                        vm.panorama(ids) { newId ->
                                            notice = if (newId != null)
                                                "Panorama saved to your library"
                                            else
                                                "Panorama failed — use overlapping shots"
                                            selectedIds = emptySet()
                                        }
                                    },
                                )
                            }
                            LumoraIconButton(
                                icon = Icons.Default.Favorite,
                                contentDescription = "Add to favorites",
                                tint = colors.accent,
                                onClick = {
                                    val ids = selectedIds
                                    vm.favoriteMany(ids) { n ->
                                        notice = if (n > 0) "Added $n to favorites"
                                        else "Could not update favorites"
                                        selectedIds = emptySet()
                                    }
                                },
                            )
                            LumoraIconButton(
                                icon = Icons.Default.Delete,
                                contentDescription = "Delete selected",
                                tint = colors.danger,
                                onClick = { showDeleteConfirm = true },
                            )
                        } else {
                        LumoraIconButton(
                            icon = Icons.Default.Search,
                            contentDescription = "Search",
                            tint = if (searchOpen) colors.accent else colors.textSecondary,
                            onClick = { searchOpen = !searchOpen },
                        )
                        Box {
                            LumoraIconButton(
                                icon = LumoraIcons.Sort,
                                contentDescription = "Sort photos",
                                tint = if (sortMenu) colors.accent else colors.textSecondary,
                                onClick = { sortMenu = true },
                            )
                            LumoraDropdownMenu(
                                expanded = sortMenu,
                                onDismissRequest = { sortMenu = false },
                            ) {
                                SortOrder.values().forEach { order ->
                                    LumoraDropdownMenuItem(
                                        text = order.label,
                                        textColor = if (order == sortOrder) colors.accent
                                            else colors.textPrimary,
                                        icon = if (order == sortOrder) Icons.Default.Check
                                            else null,
                                        iconTint = colors.accent,
                                        stateDescription = if (order == sortOrder) "Selected"
                                            else null,
                                        onClick = { vm.setSortOrder(order); sortMenu = false },
                                    )
                                }
                            }
                        }
                        LumoraIconButton(
                            icon = Icons.Default.Settings,
                            contentDescription = "Settings",
                            onClick = onOpenSettings,
                        )
                        }
                    },
                )
                if (searchOpen) {
                    LumoraTextField(
                        value = query,
                        onValueChange = vm::search,
                        label = "Search photos",
                        placeholder = "Search photos…",
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LumoraChip(
                        selected = filter == LibraryFilter.ALL,
                        onClick = { vm.setFilter(LibraryFilter.ALL) },
                        label = { Text("All") })
                    LumoraChip(
                        selected = filter == LibraryFilter.FAVORITES,
                        onClick = { vm.setFilter(LibraryFilter.FAVORITES) },
                        label = { Text("Favorites") })
                    LumoraChip(
                        selected = filter == LibraryFilter.RATED,
                        onClick = { vm.setFilter(LibraryFilter.RATED) },
                        label = { Text("Rated") })
                    LumoraChip(
                        selected = filter == LibraryFilter.EDITED,
                        onClick = { vm.setFilter(LibraryFilter.EDITED) },
                        label = { Text("Edited") })
                    LumoraChip(
                        selected = filter == LibraryFilter.UNEDITED,
                        onClick = { vm.setFilter(LibraryFilter.UNEDITED) },
                        label = { Text("Unedited") })
                }
                // Phase C#6 slice 1: keyword filter chips. Shown only once at
                // least one keyword exists; tapping toggles the filter, which
                // AND-composes with the enum filter and the search query.
                if (allKeywords.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Keywords:",
                            style = type.caption,
                            color = colors.textSecondary,
                        )
                        for (kw in allKeywords) {
                            val key = keywordKeyOf(kw)
                            LumoraChip(
                                selected = keywordFilter == key,
                                onClick = {
                                    vm.setKeywordFilter(
                                        if (keywordFilter == key) null else kw)
                                },
                                label = { Text(kw) })
                        }
                    }
                }
                // Phase C#6 slice 2: smart collections (Lightroom parity) —
                // saved dynamic filters of keyword + min rating +
                // favorites-only. The row shows once there is at least one
                // collection or one keyword (either makes a collection
                // useful); tap toggles the filter, long-press edits it, the
                // "+" starts a new one. The collection's rules AND-compose
                // with the enum filter, the search query and the keyword
                // filter.
                if (smartCollections.isNotEmpty() || allKeywords.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Collections:",
                            style = type.caption,
                            color = colors.textSecondary,
                        )
                        for (coll in smartCollections) {
                            LumoraChip(
                                selected = activeCollectionId == coll.id,
                                onClick = {
                                    vm.setSmartCollection(
                                        if (activeCollectionId == coll.id) null
                                        else coll.id)
                                },
                                onLongClick = {
                                    collectionEditor = CollectionEditorState(
                                        id = coll.id,
                                        initialName = coll.name,
                                        initialKeyword = coll.keywordDisplay,
                                        initialMinRating = coll.minRating,
                                        initialFavoritesOnly = coll.favoritesOnly,
                                    )
                                },
                                onLongClickLabel = "Edit collection ${coll.name}",
                                label = { Text(coll.name) },
                            )
                        }
                        LumoraChip(
                            selected = false,
                            onClick = {
                                collectionEditor = CollectionEditorState()
                            },
                            label = {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = "New smart collection",
                                )
                            },
                        )
                    }
                }
                // Phase C#7 slice 1: proofing galleries (share/select
                // workflow). Tap a gallery chip to filter the grid to its
                // photos; long-press edits it; "+" starts a new (empty)
                // gallery. The chip shows live client-mark stats.
                if (galleries.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Galleries:",
                            style = type.caption,
                            color = colors.textSecondary,
                        )
                        for (gallery in galleries) {
                            val (approved, _, _) = gallery.stats()
                            LumoraChip(
                                selected = activeGalleryId == gallery.id,
                                onClick = {
                                    vm.setGallery(
                                        if (activeGalleryId == gallery.id) null
                                        else gallery.id)
                                },
                                onLongClick = {
                                    galleryEditor = GalleryEditorState(
                                        id = gallery.id,
                                        initialName = gallery.name,
                                        initialClient = gallery.clientName.orEmpty(),
                                    )
                                },
                                onLongClickLabel = "Edit gallery ${gallery.name}",
                                label = {
                                    Text(
                                        if (approved > 0)
                                            "${gallery.name} · $approved picked"
                                        else gallery.name
                                    )
                                },
                            )
                        }
                        LumoraChip(
                            selected = false,
                            onClick = {
                                galleryEditor = GalleryEditorState()
                            },
                            label = {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = "New proofing gallery",
                                )
                            },
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (!selectionMode) {
                LumoraFab(
                    onClick = { showImport = true },
                ) { Icon(Icons.Default.Add, "Import photos") }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (images.isEmpty()) {
                // Filter-aware empty states: the old branch always said
                // "No photos yet / Import photos", which is wrong when the
                // user has photos but the active filter/search matches none.
                if (!hasAnyPhotos) {
                    EmptyState(
                        title = "No photos yet",
                        body = "Import photos from your device to start editing. " +
                            "Your originals are never modified.",
                        actionLabel = "Import photos",
                        onAction = { showImport = true },
                        icon = LumoraIcons.Landscape,
                    )
                } else if (query.isNotBlank()) {
                    EmptyState(
                        title = "No matches",
                        body = "No photos match \"$query\".",
                        actionLabel = "Clear search",
                        onAction = { vm.search("") },
                        icon = Icons.Default.Search,
                    )
                } else if (keywordFilter != null) {
                    // Phase C#6 slice 1: the keyword filter composes with the
                    // enum filter, so it gets its own empty state + clear.
                    val kwLabel = allKeywords.firstOrNull {
                        keywordKeyOf(it) == keywordFilter
                    } ?: keywordFilter.orEmpty()
                    EmptyState(
                        title = "No photos tagged \"$kwLabel\"",
                        body = "Tag photos with keywords from the photo " +
                            "details dialog to find them here.",
                        actionLabel = "Clear keyword filter",
                        onAction = { vm.setKeywordFilter(null) },
                        icon = Icons.Default.Search,
                    )
                } else if (activeCollectionId != null) {
                    // Phase C#6 slice 2: an active collection that matches
                    // nothing gets its own empty state + clear (the
                    // collection composes with the enum filter and the
                    // keyword filter, like the keyword branch above).
                    val collName = smartCollections.firstOrNull {
                        it.id == activeCollectionId
                    }?.name ?: "collection"
                    EmptyState(
                        title = "No photos in \"$collName\"",
                        body = "Photos that match the collection's rules — " +
                            "keyword, rating, or favorite — appear here " +
                            "automatically.",
                        actionLabel = "Clear collection",
                        onAction = { vm.setSmartCollection(null) },
                        icon = Icons.Default.Search,
                    )
                } else if (activeGalleryId != null) {
                    // Phase C#7 slice 1: an active gallery with no members
                    // gets its own empty state + clear.
                    val galName = galleries.firstOrNull {
                        it.id == activeGalleryId
                    }?.name ?: "gallery"
                    EmptyState(
                        title = "No photos in \"$galName\"",
                        body = "Clear this filter, select photos, and use " +
                            "the gallery action to add them here for " +
                            "client review.",
                        actionLabel = "Clear gallery",
                        onAction = { vm.setGallery(null) },
                        icon = Icons.Default.Share,
                    )
                } else if (filter == LibraryFilter.FAVORITES) {
                    EmptyState(
                        title = "No favorites yet",
                        body = "Tap the star on any photo to mark it as a " +
                            "favorite and it will appear here.",
                        icon = Icons.Default.Star,
                    )
                } else if (filter == LibraryFilter.EDITED) {
                    EmptyState(
                        title = "No edited photos",
                        body = "Edit any photo and it will appear here.",
                        icon = LumoraIcons.Tune,
                    )
                } else if (filter == LibraryFilter.UNEDITED) {
                    EmptyState(
                        title = "No unedited photos",
                        body = "Every photo in your library has edits.",
                        icon = Icons.Default.CheckCircle,
                    )
                } else {
                    EmptyState(
                        title = "No rated photos",
                        body = "Rate a photo (1–5 stars) from its menu to " +
                            "see it in this view.",
                        icon = LumoraIcons.StarBorder,
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(gridColumns),
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                        // Pinch with two fingers to change grid density
                        // (transformable cooperates with the grid's scroll).
                        .transformable(gridTransform)
                        .semantics {
                            contentDescription =
                                "Photo grid, $gridColumns columns. Pinch to change grid size."
                        },
                ) {
                    // Phase C#7 slice 1: the active gallery's client marks
                    // drive per-cell badges; tapping a badge cycles the
                    // mark unmarked -> approved -> rejected -> unmarked.
                    val activeGallery = galleries.firstOrNull {
                        it.id == activeGalleryId
                    }
                    items(images, key = { it.id }) { img ->
                        val selected = img.id in selectedIds
                        val mark = activeGallery?.marks?.get(img.id)
                        PhotoCell(
                            img = img,
                            container = container,
                            selected = selected,
                            selectionMode = selectionMode,
                            proofMark = mark,
                            onCycleMark = activeGallery?.let { gallery ->
                                {
                                    vm.setProofMark(
                                        gallery.id, img.id, mark.next())
                                }
                            },
                            onOpen = {
                                if (selectionMode) {
                                    selectedIds =
                                        if (selected) selectedIds - img.id else selectedIds + img.id
                                } else {
                                    onOpenPhoto(img.id)
                                }
                            },
                            onLongPress = {
                                if (selectionMode) {
                                    selectedIds =
                                        if (selected) selectedIds - img.id else selectedIds + img.id
                                } else {
                                    contextMenuFor = img
                                }
                            },
                        )
                    }
                }
            }
            if (importing) {
                Box(Modifier.fillMaxSize().background(colors.scrim),
                    contentAlignment = Alignment.Center) {
                    LoadingState("Importing…")
                }
            }
            if (merging) {
                // Phase 4 HDR merge is running on the IO thread.
                Box(Modifier.fillMaxSize().background(colors.scrim),
                    contentAlignment = Alignment.Center) {
                    LoadingState("Merging HDR…")
                }
            }
            if (stacking) {
                // Phase 4 focus stack runs on the IO thread. It used to flip
                // the HDR `_merging` flag, so the wrong "Merging HDR…" label
                // showed while a stack ran; now it has its own busy state.
                Box(Modifier.fillMaxSize().background(colors.scrim),
                    contentAlignment = Alignment.Center) {
                    LoadingState("Stacking focus…")
                }
            }
            if (stitching) {
                // Phase 4 panorama stitch runs on the IO thread.
                Box(Modifier.fillMaxSize().background(colors.scrim),
                    contentAlignment = Alignment.Center) {
                    LoadingState("Stitching panorama…")
                }
            }
            // transient confirmation (virtual copy)
            if (notice != null) {
                Box(
                    Modifier.align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(colors.scrim)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text(notice!!, style = type.caption, color = Color.White)
                }
            }
        }
    }

    if (showImport) {
        ImportSheet(
            vm = vm,
            onSystemPicker = {
                showImport = false
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onRawImport = {
                showImport = false
                // RAW program Phase 2 slice 1: DNG + the proprietary formats
                // LibRaw 0.22.2 covers. Providers may only honor a subset —
                // routing is by extension anyway ([RawDecoder.isRaw]).
                rawPicker.launch(arrayOf(
                    "image/x-adobe-dng",
                    "image/x-canon-cr2", "image/x-canon-cr3",
                    "image/x-nikon-nef", "image/x-nikon-nrw",
                    "image/x-sony-arw", "image/x-sony-sr2", "image/x-sony-srf",
                    "image/x-fuji-raf", "image/x-olympus-orf",
                    "image/x-panasonic-rw2", "image/x-pentax-pef",
                    "image/x-leica-rwl", "image/x-sigma-x3f",
                    "image/x-hasselblad-3fr", "image/x-phaseone-iiq",
                ))
            },
            onTakePhoto = {
                showImport = false
                launchCamera()
            },
            onDismiss = { showImport = false },
        )
    }

    contextMenuFor?.let { img ->
        PhotoMenuSheet(
            img = img,
            onDismiss = { contextMenuFor = null },
            onRate = { vm.setRating(img.id, it); contextMenuFor = null },
            onFavorite = { vm.toggleFavorite(img.id); contextMenuFor = null },
            onDuplicate = {
                vm.duplicate(img.id) { newId ->
                    notice = if (newId != null) "Virtual copy created"
                    else "Could not create a copy"
                    contextMenuFor = null
                }
            },
            onDelete = { vm.delete(img.id); contextMenuFor = null },
            pasteAvailable = copiedEdits != null,
            onPaste = { vm.pasteEditsTo(img.id) { contextMenuFor = null } },
            onCopyEdits = {
                vm.copyEditsFrom(img.id) { ok ->
                    notice = if (ok) "Edits copied — paste onto any photo"
                    else "Could not copy edits"
                    contextMenuFor = null
                }
            },
            onDetails = { detailsFor = img; contextMenuFor = null },
            onSelect = { selectedIds = setOf(img.id); contextMenuFor = null },
        )
    }

    detailsFor?.let { img ->
        val kws by vm.keywordsFor(img.id).collectAsState()
        PhotoDetailsDialog(
            img = img,
            onDismiss = { detailsFor = null },
            keywords = kws,
            onAddKeyword = { vm.addKeyword(img.id, it) },
            onRemoveKeyword = { vm.removeKeyword(img.id, it) },
        )
    }

    if (showDeleteConfirm) {
        val n = selectedIds.size
        LumoraDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = "Delete $n photo${if (n == 1) "" else "s"}?",
            text = "They will be removed from your library. This cannot be undone.",
            confirmLabel = "Delete",
            confirmColor = colors.danger,
            onConfirm = {
                val ids = selectedIds
                showDeleteConfirm = false
                vm.deleteMany(ids) { removed ->
                    notice = if (removed > 0)
                        "Deleted $removed photo${if (removed == 1) "" else "s"}"
                    else "Could not delete"
                    selectedIds = emptySet()
                }
            },
        )
    }

    if (showApplyPreset) {
        ApplyPresetSheet(
            container = container,
            count = selectedIds.size,
            onDismiss = { showApplyPreset = false },
            onApply = { preset, strength ->
                val ids = selectedIds
                showApplyPreset = false
                vm.applyPresetToMany(ids, preset, strength) { n ->
                    notice = if (n > 0)
                        "Applied preset to $n photo${if (n == 1) "" else "s"}"
                    else "Could not apply preset"
                    selectedIds = emptySet()
                }
            },
        )
    }

    // Phase C#6 slice 2: create/edit a smart collection. Deleting only
    // removes the saved filter — never any photos — so the dialog's delete
    // needs no extra confirm step.
    if (collectionEditor != null) {
        val st = collectionEditor!!
        SmartCollectionDialog(
            initialName = st.initialName,
            initialKeyword = st.initialKeyword,
            initialMinRating = st.initialMinRating,
            initialFavoritesOnly = st.initialFavoritesOnly,
            allKeywords = allKeywords,
            showDelete = st.id != null,
            onDismiss = { collectionEditor = null },
            onSave = { name, kw, minR, fav ->
                collectionEditor = null
                if (st.id == null) {
                    vm.createSmartCollection(name, kw, minR, fav) { ok ->
                        notice = if (ok) "Collection saved"
                        else "Could not save collection"
                    }
                } else {
                    vm.updateSmartCollection(st.id, name, kw, minR, fav) { ok ->
                        notice = if (ok) "Collection updated"
                        else "Could not update collection"
                    }
                }
            },
            onDelete = {
                val id = st.id
                collectionEditor = null
                if (id != null) {
                    vm.deleteSmartCollection(id)
                    notice = "Collection deleted"
                }
            },
        )
    }

    // Phase C#7 slice 1: create/edit a proofing gallery. Creating from a
    // selection clears the selection afterwards; deleting only removes the
    // gallery — never any photos — so no extra confirm step.
    if (galleryEditor != null) {
        val st = galleryEditor!!
        val gallery = galleries.firstOrNull { it.id == st.id }
        ProofingGalleryDialog(
            initialName = st.initialName,
            initialClient = st.initialClient,
            summary = gallery?.summary,
            showDelete = st.id != null,
            hasPhotos = (gallery?.photoIds?.isNotEmpty() == true),
            hasMarks = gallery?.let {
                val (a, r, _) = it.stats()
                a + r > 0
            } == true,
            onDismiss = { galleryEditor = null },
            onContactSheet = st.id?.let { id ->
                {
                    galleryEditor = null
                    onContactSheet(id)
                }
            },
            // Phase C#7 slice 3 — proofing round-trip: the apply step is
            // confirm-gated because it rewrites library ratings/favorites.
            onApplyPicks = st.id?.let {
                {
                    galleryEditor = null
                    applyPicksFor = gallery
                }
            },
            onSave = { name, client ->
                galleryEditor = null
                if (st.id == null) {
                    val ids = st.pendingPhotoIds
                    vm.createProofingGallery(name, client, ids) { g ->
                        notice = if (g != null) {
                            selectedIds = emptySet()
                            "Gallery \"$name\" created with ${ids.size} " +
                                if (ids.size == 1) "photo" else "photos"
                        } else "Could not create gallery"
                    }
                } else {
                    vm.updateProofingGallery(st.id, name, client) { ok ->
                        notice = if (ok) "Gallery updated"
                        else "Could not update gallery"
                    }
                }
            },
            onDelete = {
                val id = st.id
                galleryEditor = null
                if (id != null) {
                    vm.deleteProofingGallery(id)
                    notice = "Gallery deleted"
                }
            },
        )
    }

    // Phase C#7 slice 3 — confirm the proofing round-trip: the client's
    // marks become the library's ratings/favorites, so this step is
    // explicit and undoable only by re-rating by hand.
    if (applyPicksFor != null) {
        val gallery = applyPicksFor!!
        ApplyPicksConfirmDialog(
            gallery = gallery,
            onDismiss = { applyPicksFor = null },
            onConfirm = {
                applyPicksFor = null
                vm.applyClientPicks(gallery.id) { result ->
                    notice = result?.describe() ?: "Gallery not found"
                }
            },
        )
    }
}

/**
 * Phase C#6 slice 2 — dialog state for the smart-collection editor.
 * `id == null` means "new collection".
 */
private data class CollectionEditorState(
    val id: String? = null,
    val initialName: String = "",
    val initialKeyword: String? = null,
    val initialMinRating: Int = 0,
    val initialFavoritesOnly: Boolean = false,
)

/**
 * Phase C#6 slice 2 — create/edit dialog for a smart collection.
 * Rules: optional keyword (single pick from the library's keywords),
 * minimum rating (Any / 1★+ … 5★), favorites-only switch. Save is disabled
 * until the name is non-blank and at least one rule is set; deleting only
 * removes the saved filter, never any photos.
 */
@Composable
private fun SmartCollectionDialog(
    initialName: String,
    initialKeyword: String?,
    initialMinRating: Int,
    initialFavoritesOnly: Boolean,
    allKeywords: List<String>,
    showDelete: Boolean,
    onDismiss: () -> Unit,
    onSave: (name: String, keyword: String?, minRating: Int, favoritesOnly: Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val colors = Lumora.colors
    val type = Lumora.type
    var name by remember { mutableStateOf(initialName) }
    var keyword by remember { mutableStateOf(initialKeyword) }
    var minRating by remember { mutableStateOf(initialMinRating.coerceIn(0, 5)) }
    var favoritesOnly by remember { mutableStateOf(initialFavoritesOnly) }
    val valid = name.isNotBlank() &&
        (keyword != null || minRating > 0 || favoritesOnly)
    LumoraDialog(
        onDismissRequest = onDismiss,
        title = if (showDelete) "Edit collection" else "New smart collection",
        confirmLabel = "Save",
        confirmEnabled = valid,
        onConfirm = { onSave(name.trim(), keyword, minRating, favoritesOnly) },
        dismissLabel = "Cancel",
        onDismiss = onDismiss,
        content = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                LumoraTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = "Collection name",
                    placeholder = "e.g. Portfolio picks",
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Keyword",
                    style = type.caption,
                    color = colors.textSecondary,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LumoraChip(
                        selected = keyword == null,
                        onClick = { keyword = null },
                        label = { Text("Any") },
                    )
                    for (kw in allKeywords) {
                        LumoraChip(
                            selected = keyword == kw,
                            onClick = {
                                keyword = if (keyword == kw) null else kw
                            },
                            label = { Text(kw) },
                        )
                    }
                }
                Text(
                    "Minimum rating",
                    style = type.caption,
                    color = colors.textSecondary,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LumoraChip(
                        selected = minRating == 0,
                        onClick = { minRating = 0 },
                        label = { Text("Any") },
                    )
                    for (r in 1..5) {
                        LumoraChip(
                            selected = minRating == r,
                            onClick = {
                                minRating = if (minRating == r) 0 else r
                            },
                            label = { Text("$r★+") },
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "Favorites only",
                        style = type.body,
                        color = colors.textPrimary,
                    )
                    LumoraSwitch(
                        checked = favoritesOnly,
                        onCheckedChange = { favoritesOnly = it },
                        contentDescription = "Favorites only",
                    )
                }
                if (showDelete) {
                    LumoraTextButton(
                        onClick = onDelete,
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text(
                            "Delete collection",
                            style = type.body,
                            color = colors.danger,
                        )
                    }
                }
            }
        },
    )
}

/**
 * Phase C#7 slice 1 — dialog state for the proofing-gallery editor.
 * `id == null` means "new gallery"; pendingPhotoIds are the selection the
 * gallery is created from (empty when started from the "+" chip).
 */
private data class GalleryEditorState(
    val id: String? = null,
    val initialName: String = "",
    val initialClient: String = "",
    val pendingPhotoIds: List<String> = emptyList(),
)

/**
 * Phase C#7 slice 1 — create/edit dialog for a proofing gallery: name +
 * optional client. Save is disabled until the name is non-blank; deleting
 * only removes the gallery, never any photos. In edit mode the gallery's
 * live photo/mark summary is shown.
 */
@Composable
private fun ProofingGalleryDialog(
    initialName: String,
    initialClient: String,
    summary: String?,
    showDelete: Boolean,
    onDismiss: () -> Unit,
    onSave: (name: String, client: String?) -> Unit,
    onDelete: () -> Unit,
    /** Phase C#7 slice 2: open the contact-sheet builder; null when unavailable. */
    onContactSheet: (() -> Unit)? = null,
    hasPhotos: Boolean = false,
    /** Phase C#7 slice 3: apply the client's marks to the library; null when unavailable. */
    onApplyPicks: (() -> Unit)? = null,
    /** Phase C#7 slice 3: true when the gallery has at least one client mark. */
    hasMarks: Boolean = false,
) {
    val colors = Lumora.colors
    val type = Lumora.type
    var name by remember { mutableStateOf(initialName) }
    var client by remember { mutableStateOf(initialClient) }
    LumoraDialog(
        onDismissRequest = onDismiss,
        title = if (showDelete) "Edit gallery" else "New proofing gallery",
        confirmLabel = "Save",
        confirmEnabled = name.isNotBlank(),
        onConfirm = { onSave(name.trim(), client.trim().ifBlank { null }) },
        dismissLabel = "Cancel",
        onDismiss = onDismiss,
        content = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                LumoraTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = "Gallery name",
                    placeholder = "e.g. Wedding — Smith",
                    modifier = Modifier.fillMaxWidth(),
                )
                LumoraTextField(
                    value = client,
                    onValueChange = { client = it },
                    label = "Client (optional)",
                    placeholder = "Who is reviewing these?",
                    modifier = Modifier.fillMaxWidth(),
                )
                if (summary != null) {
                    Text(
                        summary,
                        style = type.caption,
                        color = colors.textSecondary,
                    )
                }
                Text(
                    "The client marks each photo picked or rejected; " +
                        "your ratings and favorites are untouched.",
                    style = type.caption,
                    color = colors.textTertiary,
                )
                if (showDelete) {
                    // Phase C#7 slice 3 — proofing round-trip: write the
                    // client's marks back to the library as ratings and
                    // favorites. Only offered when the client has actually
                    // marked something; the confirmation step lives with
                    // the caller.
                    if (onApplyPicks != null && hasMarks) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Start,
                        ) {
                            LumoraTextButton(onClick = onApplyPicks) {
                                Text(
                                    "Apply client picks…",
                                    style = type.body,
                                    color = colors.accent,
                                )
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Phase C#7 slice 2: contact-sheet export — the share
                        // half of the proofing workflow. Only meaningful for
                        // a gallery that already holds photos.
                        if (onContactSheet != null && hasPhotos) {
                            LumoraTextButton(onClick = onContactSheet) {
                                Text(
                                    "Contact sheet…",
                                    style = type.body,
                                    color = colors.accent,
                                )
                            }
                        } else {
                            Spacer(Modifier)
                        }
                        LumoraTextButton(onClick = onDelete) {
                            Text(
                                "Delete gallery",
                                style = type.body,
                                color = colors.danger,
                            )
                        }
                    }
                }
            }
        },
    )
}

/**
 * Phase C#7 slice 3 — confirmation for the proofing round-trip. The client's
 * marks are about to become the photographer's own library ratings and
 * favorites, so the exact outcome is spelled out before anything changes.
 * The gallery's own marks are left untouched (the client's verdict stays
 * on the record); unmarked members are never touched either.
 */
@Composable
private fun ApplyPicksConfirmDialog(
    gallery: ProofingGallery,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val colors = Lumora.colors
    val type = Lumora.type
    val (approved, rejected, unmarked) = gallery.stats()
    LumoraDialog(
        onDismissRequest = onDismiss,
        title = "Apply client picks?",
        confirmLabel = "Apply",
        onConfirm = onConfirm,
        dismissLabel = "Cancel",
        onDismiss = onDismiss,
        content = {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "The client's marks on \"${gallery.name}\" become your " +
                        "library ratings:",
                    style = type.body,
                    color = colors.textPrimary,
                )
                if (approved > 0) Text(
                    "$approved picked → 5★ + favorite",
                    style = type.body,
                    color = colors.textPrimary,
                )
                if (rejected > 0) Text(
                    "$rejected rejected → 1★",
                    style = type.body,
                    color = colors.textPrimary,
                )
                if (unmarked > 0) Text(
                    "$unmarked unmarked — left untouched",
                    style = type.body,
                    color = colors.textSecondary,
                )
                Text(
                    "The gallery keeps the client's marks as the record.",
                    style = type.caption,
                    color = colors.textTertiary,
                )
            }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhotoCell(
    img: LibraryImage,
    container: AppContainer,
    selected: Boolean,
    selectionMode: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    // Phase C#7 slice 1: client select mark shown only while a proofing
    // gallery is active. onCycleMark cycles the mark (unmarked -> approved
    // -> rejected -> unmarked); null means "no gallery mode".
    proofMark: ProofMark? = null,
    onCycleMark: (() -> Unit)? = null,
) {
    val colors = Lumora.colors
    val uri = remember(img.contentUri) {
        img.contentUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
    }
    // Edited thumbnail (Lightroom parity): show the GPU-rendered edited
    // preview when it is still valid for the current sidecar revision;
    // otherwise fall back to the original. Re-checked whenever a thumbnail
    // is captured, cleared, or invalidated (container.thumbRevision).
    val thumbBump by container.thumbRevision.collectAsState()
    // Sidecar mtime doubles as the cache key for the edited thumbnail: the
    // thumbnail JPEG is overwritten in place at a stable path, so the key
    // must change whenever the edit revision changes or Coil would serve
    // the previous bytes from its path-keyed cache.
    val sidecarMtime = remember(img.id, thumbBump) {
        container.editStore.sidecarLastModified(img.id)
    }
    val editedThumb by produceState<java.io.File?>(
        initialValue = null, img.id, thumbBump,
    ) {
        value = if (img.editCount <= 0) null else withContext(Dispatchers.IO) {
            container.editedThumbs.validThumb(img.id, sidecarMtime)
        }
    }
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.panel)
            // Phase C#7 slice 1: a client-marked photo gets a colored ring
            // so picks/rejects scan at a glance while culling.
            .then(
                when (proofMark) {
                    ProofMark.APPROVED -> Modifier.border(
                        2.dp, Color(0xFF66BB6A), RoundedCornerShape(10.dp))
                    ProofMark.REJECTED -> Modifier.border(
                        2.dp, Color(0xFFEF5350), RoundedCornerShape(10.dp))
                    null -> Modifier
                }
            )
            // One TalkBack node per tile: merge the photo name, badges and
            // selection state into the clickable, with labeled tap/long-press
            // actions (ToolTile parity — see Controls.kt).
            .semantics(mergeDescendants = true) {}
            .combinedClickable(
                role = Role.Button,
                onClickLabel = "Open photo",
                onLongClickLabel = "Select photo",
                onClick = onOpen,
                onLongClick = onLongPress,
            ),
    ) {
        // RAW program Phase 2 slice 2: RAW originals get the import-time
        // embedded-preview thumbnail — Coil cannot decode CR2/NEF/DNG, so
        // without this the cell stayed blank. Edited previews still win;
        // the plain URI stays the last resort. A single stat() per cell;
        // the file is written once at import and deleted with the photo.
        val rawThumb = remember(img.id, thumbBump) {
            container.rawThumbs.thumbFile(img.id).takeIf { it.isFile }
        }
        val model = editedThumb ?: rawThumb ?: uri
        ThumbImage(
            model = model,
            contentDescription = img.displayName,
            modifier = Modifier.fillMaxSize(),
            cacheKey = editedThumb?.let { "${img.id}-$sidecarMtime" }
                ?: rawThumb?.let { "${img.id}-raw" },
        )
        // Legibility scrims: badge pills and rating dots sit on top of the
        // photo itself, so give them a soft gradient scrim — otherwise the
        // white star vanishes on a bright sky and the accent dots vanish on
        // a light floor. Top scrim only where badges live; bottom scrim
        // only when the rating row is present.
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(46.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0x66000000), Color.Transparent)
                    )
                ),
        )
        if (selectionMode && selected) {
            // Scan-at-a-glance selection tint: the 28dp check circle alone
            // is too small to spot at 4–5 columns. A soft full-cell accent
            // wash marks selected photos without covering the badges —
            // badges are composed after this overlay and stay bright.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Lumora.colors.accent.copy(alpha = 0.16f)),
            )
        }
        // badges — one consistent pill style: 8dp rounded dark pill so the
        // star/edit/RAW marks read as one visual family instead of mixed
        // circles, bare icons and rounded rects.
        Row(
            modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (img.favorite) {
                Box(
                    Modifier.clip(RoundedCornerShape(8.dp))
                        .background(Color(0xAA000000))
                        .padding(horizontal = 6.dp, vertical = 5.dp)
                ) {
                    Icon(Icons.Default.Star, "Favorite", tint = Lumora.colors.accent,
                        modifier = Modifier.size(14.dp))
                }
            }
            if (img.editCount > 0) {
                Box(
                    Modifier.clip(RoundedCornerShape(8.dp))
                        .background(Color(0xAA000000))
                        .padding(horizontal = 6.dp, vertical = 5.dp)
                ) {
                    Icon(Icons.Default.Edit, "Edited", tint = Color.White,
                        modifier = Modifier.size(12.dp))
                }
            }
            // RAW program Phase 2 slice 2: Lightroom-parity RAW badge so
            // RAW imports are recognizable in the grid at a glance.
            if (RawDecoder.isRaw(img.displayName)) {
                Box(
                    Modifier.clip(RoundedCornerShape(8.dp))
                        .background(Color(0xAA000000))
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                ) {
                    Text("RAW", style = Lumora.type.toolLabel, color = Color.White)
                }
            }
        }
        if (img.rating > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(34.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color(0x73000000))
                        )
                    ),
            )
            Row(
                modifier = Modifier.align(Alignment.BottomStart).padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                repeat(img.rating) {
                    Box(Modifier.size(5.dp).clip(CircleShape)
                        .background(Lumora.colors.accent))
                }
            }
        }
        if (selectionMode) {
            Box(
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(28.dp)
                    .clip(CircleShape)
                    .background(if (selected) Lumora.colors.accent else Color(0x88000000))
                    .border(
                        2.dp,
                        if (selected) Lumora.colors.accent else Color.White,
                        CircleShape,
                    )
                    .semantics {
                        contentDescription = if (selected) "Selected" else "Not selected"
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Icon(
                        Icons.Default.Check, null, tint = Color.Black,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        // Phase C#7 slice 1: client-mark badge (gallery mode only). Tapping
        // cycles unmarked -> approved -> rejected -> unmarked.
        if (onCycleMark != null) {
            val badgeLabel = when (proofMark) {
                ProofMark.APPROVED -> "Client picked"
                ProofMark.REJECTED -> "Client rejected"
                null -> "Not marked"
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Color(0xAA000000))
                    .clickable(
                        role = Role.Button,
                        onClickLabel = "Cycle client mark",
                        onClick = onCycleMark,
                    )
                    .semantics { contentDescription = "$badgeLabel. Tap to change." },
                contentAlignment = Alignment.Center,
            ) {
                when (proofMark) {
                    ProofMark.APPROVED -> Icon(
                        Icons.Default.CheckCircle, null,
                        tint = Color(0xFF66BB6A),
                        modifier = Modifier.size(20.dp),
                    )
                    ProofMark.REJECTED -> Icon(
                        Icons.Default.Close, null,
                        tint = Color(0xFFEF5350),
                        modifier = Modifier.size(20.dp),
                    )
                    // Unmarked: an empty ring (RadioButtonUnchecked lives
                    // in the extended icon set, which this app does not
                    // ship — drawn instead).
                    null -> Box(
                        Modifier.size(20.dp)
                            .border(2.dp, Color.White, CircleShape)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportSheet(
    vm: LibraryViewModel,
    onSystemPicker: () -> Unit,
    onRawImport: () -> Unit,
    onTakePhoto: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = Lumora.colors
    val type = Lumora.type
    val candidates by vm.candidates.collectAsState()
    val selected = remember { mutableStateOf(setOf<Uri>()) }
    val scope = rememberCoroutineScope()
    var imported by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(Unit) { vm.loadCandidates() }

    LumoraBottomSheet(
        onDismissRequest = onDismiss,
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text("Import photos", style = type.screenTitle, color = colors.textPrimary,
                modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(4.dp))
            Text(
                if (candidates.isEmpty())
                    "No device photos found — use the system picker."
                else "${selected.value.size} selected",
                style = type.caption, color = colors.textSecondary,
                modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(12.dp))

            // Camera capture (Lightroom-parity import path): snap a photo and
            // jump straight into the editor with it.
            LumoraOutlineButton(
                onClick = onTakePhoto,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = colors.accent),
            ) {
                Icon(LumoraIcons.PhotoCamera, "Take a photo",
                    modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Take a photo")
            }
            Spacer(Modifier.height(8.dp))
            // RAW program Phase 2 slice 1: dedicated RAW import — the
            // photo picker hides RAW files, so this opens a document picker
            // filtered to RAW formats (DNG + proprietary: CR2/CR3/NEF/ARW/
            // RAF/ORF/RW2/...). The editor decodes them through the native
            // LibRaw bridge.
            LumoraOutlineButton(
                onClick = onRawImport,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = colors.accent),
            ) {
                Text("Import RAW photo")
            }
            Spacer(Modifier.height(8.dp))
            if (candidates.isEmpty()) {
                LumoraButton(
                    onClick = onSystemPicker,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.accent, contentColor = Color.Black),
                    shape = RoundedCornerShape(10.dp),
                ) { Text("Open system photo picker") }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp)
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(candidates, key = { it.uri }) { c ->
                        val sel = c.uri in selected.value
                        Box(
                            Modifier.aspectRatio(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(colors.panelElevated)
                                // Merge the photo name + "Selected" badge into
                                // one TalkBack node with a labeled toggle action.
                                .semantics(mergeDescendants = true) {}
                                .clickable(
                                    onClickLabel = if (sel) "Deselect photo"
                                    else "Select photo",
                                    role = Role.Button,
                                ) {
                                    selected.value = if (sel) selected.value - c.uri
                                    else selected.value + c.uri
                                },
                        ) {
                            ThumbImage(
                                model = c.uri,
                                contentDescription = c.displayName,
                                modifier = Modifier.fillMaxSize(),
                            )
                            if (sel) {
                                Box(Modifier.fillMaxSize()
                                    .background(colors.accent.copy(alpha = 0.25f)))
                                Icon(Icons.Default.CheckCircle, "Selected",
                                    tint = colors.accent,
                                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LumoraOutlineButton(
                        onClick = onSystemPicker,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                    ) { Text("System picker", color = colors.textPrimary) }
                    LumoraButton(
                        onClick = {
                            scope.launch {
                                vm.importUris(selected.value.toList()) { n ->
                                    imported = n
                                    onDismiss()
                                }
                            }
                        },
                        enabled = selected.value.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.accent, contentColor = Color.Black,
                            disabledContainerColor = colors.divider),
                        shape = RoundedCornerShape(10.dp),
                    ) { Text("Import (${selected.value.size})") }
                }
            }
            imported?.let {
                LaunchedEffect(it) { /* snackbar handled by caller if needed */ }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhotoMenuSheet(
    img: LibraryImage,
    onDismiss: () -> Unit,
    onRate: (Int) -> Unit,
    onFavorite: () -> Unit,
    onDelete: () -> Unit,
    pasteAvailable: Boolean = false,
    onPaste: () -> Unit = {},
    onCopyEdits: () -> Unit = {},
    onDuplicate: () -> Unit = {},
    onDetails: () -> Unit = {},
    onSelect: () -> Unit = {},
) {
    val colors = Lumora.colors
    val type = Lumora.type
    var confirmDelete by remember { mutableStateOf(false) }
    LumoraBottomSheet(
        onDismissRequest = onDismiss,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text(img.displayName, style = type.body, color = colors.textPrimary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${img.width} × ${img.height}",
                style = type.caption, color = colors.textTertiary)
            Spacer(Modifier.height(16.dp))
            Text("Rating", style = type.sectionTitle, color = colors.textSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(vertical = 8.dp)) {
                for (i in 1..5) {
                    LumoraIconButton(
                        icon = if (i <= img.rating) Icons.Default.Star else LumoraIcons.StarBorder,
                        contentDescription = "Rate $i",
                        onClick = { onRate(if (img.rating == i) 0 else i) },
                        tint = if (i <= img.rating) colors.accent else colors.textTertiary,
                    )
                }
            }
            HorizontalDivider(color = colors.divider)
            LumoraTextButton(onClick = onSelect, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, null, tint = colors.textSecondary)
                    Spacer(Modifier.width(12.dp))
                    Text("Select", color = colors.textPrimary)
                }
            }
            LumoraTextButton(onClick = onFavorite, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (img.favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        null, tint = colors.textSecondary)
                    Spacer(Modifier.width(12.dp))
                    Text(if (img.favorite) "Remove from favorites" else "Add to favorites",
                        color = colors.textPrimary)
                }
            }
            LumoraTextButton(onClick = onCopyEdits, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(LumoraIcons.ContentCopy, null, tint = colors.textSecondary)
                    Spacer(Modifier.width(12.dp))
                    Text("Copy edits", color = colors.textPrimary)
                }
            }
            if (pasteAvailable) {
                LumoraTextButton(onClick = onPaste, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(LumoraIcons.ContentPaste, null, tint = colors.textSecondary)
                        Spacer(Modifier.width(12.dp))
                        Text("Paste edits", color = colors.textPrimary)
                    }
                }
            }
            LumoraTextButton(onClick = onDuplicate, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(LumoraIcons.ContentCopy, null, tint = colors.textSecondary)
                    Spacer(Modifier.width(12.dp))
                    Text("Create virtual copy", color = colors.textPrimary)
                }
            }
            LumoraTextButton(onClick = onDetails, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, null, tint = colors.textSecondary)
                    Spacer(Modifier.width(12.dp))
                    Text("Details", color = colors.textPrimary)
                }
            }
            LumoraTextButton(
                onClick = { if (confirmDelete) onDelete() else confirmDelete = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Delete, null, tint = colors.danger)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        if (confirmDelete) "Tap again to confirm delete" else "Remove from library",
                        color = colors.danger)
                }
            }
        }
    }
}

/**
 * Batch-apply preset sheet for library multi-select (Lightroom "Sync
 * settings" parity). Picks one preset (+ strength) and applies it to every
 * selected photo with [LibraryViewModel.applyPresetToMany].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ApplyPresetSheet(
    container: AppContainer,
    count: Int,
    onDismiss: () -> Unit,
    onApply: (Preset, Float) -> Unit,
) {
    val colors = Lumora.colors
    val type = Lumora.type
    val custom by container.presets.custom.collectAsState()
    val categories = remember(custom) { container.presets.categories() }
    val presetsByCat = remember(custom) {
        container.presets.all().groupBy { it.category }
    }
    var selectedPreset by remember { mutableStateOf<Preset?>(null) }
    var strength by remember { mutableStateOf(1f) }

    // Saved user presets live on disk; load them when the sheet opens so
    // "My Presets" is always offered (same pattern as the editor panel).
    LaunchedEffect(Unit) { container.presets.refresh() }

    LumoraBottomSheet(
        onDismissRequest = onDismiss,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text("Apply preset", style = type.screenTitle, color = colors.textPrimary)
            Text(
                "Blends the preset over all $count selected photos.",
                style = type.caption, color = colors.textTertiary,
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                categories.forEach { cat ->
                    val list = presetsByCat[cat].orEmpty()
                    if (list.isNotEmpty()) {
                        item(key = "cat-$cat") {
                            Text(
                                cat,
                                style = type.sectionTitle,
                                color = colors.textSecondary,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        }
                        items(list, key = { it.id }) { p ->
                            val sel = selectedPreset?.id == p.id
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (sel) colors.accent.copy(alpha = 0.18f)
                                        else Color.Transparent,
                                    )
                                    .semantics {
                                        contentDescription =
                                            "Preset ${p.name}, " +
                                            if (sel) "selected" else "not selected"
                                        role = Role.Button
                                    }
                                    // Labeled action (ToolTile parity): an
                                    // unlabeled clickable() makes TalkBack fall
                                    // back to "double-tap to activate".
                                    .clickable(
                                        onClickLabel = "Apply preset ${p.name}",
                                    ) { selectedPreset = p }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    p.name,
                                    style = type.body,
                                    color = if (sel) colors.accent
                                        else colors.textPrimary,
                                    modifier = Modifier.weight(1f),
                                )
                                if (sel) {
                                    Icon(Icons.Default.Check, null, tint = colors.accent)
                                }
                            }
                        }
                    }
                }
            }
            LumoraSlider(
                label = "Strength",
                value = strength * 100f,
                onValueChange = { strength = it / 100f },
                valueRange = 10f..100f,
                default = 100f,
                valueText = "${(strength * 100).roundToInt()}%",
            )
            Spacer(Modifier.height(8.dp))
            LumoraButton(
                onClick = { selectedPreset?.let { onApply(it, strength) } },
                enabled = selectedPreset != null,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.accent, contentColor = Color.Black,
                    disabledContainerColor = colors.divider,
                ),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text("Apply to $count photo${if (count == 1) "" else "s"}")
            }
        }
    }
}
