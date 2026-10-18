package com.lumora.photoeditor.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lumora.photoeditor.core.AppContainer
import com.lumora.photoeditor.ui.screens.BatchExportScreen
import com.lumora.photoeditor.ui.screens.EditorScreen
import com.lumora.photoeditor.ui.screens.ExportScreen
import com.lumora.photoeditor.ui.screens.LibraryScreen
import com.lumora.photoeditor.ui.screens.OnboardingScreen
import com.lumora.photoeditor.ui.screens.ProofingExportScreen
import com.lumora.photoeditor.ui.screens.SettingsScreen
import com.lumora.photoeditor.ui.state.EditorViewModel
import com.lumora.photoeditor.ui.state.LibraryViewModel

object Routes {
    const val ONBOARDING = "onboarding"
    const val LIBRARY = "library"
    const val EDITOR = "editor/{imageId}"
    const val EXPORT = "export/{imageId}"
    const val BATCH_EXPORT = "batch_export/{ids}"
    const val CONTACT_SHEET = "contact_sheet/{galleryId}"
    const val SETTINGS = "settings"

    fun editor(imageId: String) = "editor/$imageId"
    fun export(imageId: String) = "export/$imageId"
    /**
     * Batch export route. IDs are UUIDs (hex + hyphens, URL-safe), joined
     * with commas.
     */
    fun batchExport(ids: List<String>) = "batch_export/" + ids.joinToString(",")
    /**
     * Proofing contact-sheet route. Gallery ids are UUIDs (URL-safe).
     */
    fun contactSheet(galleryId: String) = "contact_sheet/$galleryId"
}

@Composable
fun LumoraNav(
    container: AppContainer,
    /** URIs of photos shared into the app via ACTION_SEND; null when none pending. */
    sharedUris: List<Uri>?,
    /** Clears the pending shared URIs once the nav layer has taken ownership. */
    onSharedConsumed: () -> Unit,
) {
    val nav = rememberNavController()
    // EditorViewModel is scoped to the editor destination via the back stack entry
    // First launch opens the welcome tour; afterwards the library is home.
    NavHost(
        navController = nav,
        startDestination = if (container.settings.onboardingSeen) Routes.LIBRARY
            else Routes.ONBOARDING,
    ) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(onDone = {
                container.settings.onboardingSeen = true
                // Replay from Settings just steps back; the first-run flow
                // lands on the library and clears the tour from the stack.
                val cameFromSettings =
                    nav.previousBackStackEntry?.destination?.route == Routes.SETTINGS
                if (cameFromSettings) {
                    nav.popBackStack()
                } else {
                    nav.navigate(Routes.LIBRARY) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                }
            })
        }
        composable(Routes.LIBRARY) {
            val vm = remember { LibraryViewModel(container) }
            // Import photos shared from another app ("Share to H2W Photo Lab")
            // and jump straight into the editor for the first one.
            val shareHandled = remember { mutableStateOf(false) }
            LaunchedEffect(sharedUris) {
                val uris = sharedUris
                if (!uris.isNullOrEmpty() && !shareHandled.value) {
                    shareHandled.value = true
                    onSharedConsumed()
                    vm.importSharedUris(uris) { ids ->
                        if (ids.isNotEmpty()) nav.navigate(Routes.editor(ids.first()))
                    }
                }
            }
            LibraryScreen(
                vm = vm,
                container = container,
                onOpenPhoto = { id -> nav.navigate(Routes.editor(id)) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onBatchExport = { ids ->
                    if (ids.isNotEmpty()) nav.navigate(Routes.batchExport(ids))
                },
                // Phase C#7 slice 2: contact-sheet export for a proofing gallery.
                onContactSheet = { galleryId ->
                    nav.navigate(Routes.contactSheet(galleryId))
                },
            )
        }
        composable(
            Routes.EDITOR,
            arguments = listOf(navArgument("imageId") { type = NavType.StringType }),
        ) { entry ->
            val imageId = entry.arguments?.getString("imageId") ?: return@composable
            val vm = remember(imageId) {
                container.editorSession(imageId) { EditorViewModel(container) }
            }
            EditorScreen(
                vm = vm,
                container = container,
                imageId = imageId,
                onBack = {
                    container.closeEditorSession(imageId)
                    nav.popBackStack()
                },
                onExport = { nav.navigate(Routes.export(imageId)) },
            )
        }
        composable(
            Routes.EXPORT,
            arguments = listOf(navArgument("imageId") { type = NavType.StringType }),
        ) { entry ->
            val imageId = entry.arguments?.getString("imageId") ?: return@composable
            // Shared live session with the editor screen.
            val vm = remember(imageId) {
                container.editorSession(imageId) { EditorViewModel(container) }
            }
            ExportScreen(vm = vm, imageId = imageId, onBack = { nav.popBackStack() })
        }
        composable(
            Routes.BATCH_EXPORT,
            arguments = listOf(navArgument("ids") { type = NavType.StringType }),
        ) { entry ->
            val ids = entry.arguments?.getString("ids")
                ?.split(",")?.filter { it.isNotBlank() }.orEmpty()
            BatchExportScreen(
                ids = ids,
                container = container,
                onBack = { nav.popBackStack() },
            )
        }
        composable(
            Routes.CONTACT_SHEET,
            arguments = listOf(navArgument("galleryId") { type = NavType.StringType }),
        ) { entry ->
            val galleryId = entry.arguments?.getString("galleryId")
                ?: return@composable
            // Phase C#7 slice 2: contact-sheet export for a proofing gallery.
            ProofingExportScreen(
                galleryId = galleryId,
                container = container,
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                container = container,
                onBack = { nav.popBackStack() },
                onReplayTour = { nav.navigate(Routes.ONBOARDING) },
            )
        }
    }
}
