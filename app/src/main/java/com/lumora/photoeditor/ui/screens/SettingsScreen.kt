package com.lumora.photoeditor.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.lumora.photoeditor.BuildConfig
import com.lumora.photoeditor.R
import com.lumora.photoeditor.core.AppContainer
import com.lumora.photoeditor.ui.components.LumoraButton
import com.lumora.photoeditor.ui.components.LumoraOutlineButton
import com.lumora.photoeditor.ui.components.LumoraSwitch
import com.lumora.photoeditor.ui.components.LumoraTextField
import com.lumora.photoeditor.ui.components.LumoraTopBar
import com.lumora.photoeditor.ui.components.SegmentedControl
import com.lumora.photoeditor.ui.components.ToolbarButton
import com.lumora.photoeditor.ui.components.LumoraTextButton
import com.lumora.photoeditor.ui.components.LumoraProgressIndicator
import com.lumora.photoeditor.ui.theme.Lumora
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onReplayTour: () -> Unit = {},
) {
    val colors = Lumora.colors
    val type = Lumora.type
    val settings = container.settings
    val themeMode by settings.themeMode.collectAsState()
    val previewQuality by settings.previewQuality.collectAsState()
    val showOverlay by settings.showMaskOverlay.collectAsState()
    val haptics by settings.haptics.collectAsState()
    val gridColumns by settings.libraryGridColumns.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var cacheSize by remember { mutableStateOf("…") }

    LaunchedEffect(Unit) {
        cacheSize = withContext(Dispatchers.IO) {
            val bytes = container.context.cacheDir.walkTopDown().filter { it.isFile }
                .sumOf { it.length() }
            // Locale.US: machine-style size readout — a bare "%.1f" would
            // render "1,2 MB" on comma-decimal locales (his Burmese-UI vivo).
            when {
                bytes > 1_000_000 -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
                bytes > 1_000 -> String.format(Locale.US, "%.0f KB", bytes / 1_000.0)
                else -> "$bytes B"
            }
        }
    }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            LumoraTopBar(
                title = "Settings",
                navigationIcon = {
                    ToolbarButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            SettingGroup("Appearance") {
                Text("Theme", style = type.controlLabel, color = colors.textPrimary)
                Spacer(Modifier.height(8.dp))
                SegmentedControl(
                    options = listOf(0 to "System", 1 to "Dark", 2 to "Light"),
                    selected = themeMode,
                    onSelect = settings::setThemeMode,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            SettingGroup("Pro / License") {
                LicenseSettingsSection(container = container)
            }
            SettingGroup("AI Assistant") {
                AiKeySettings(container = container)
            }
            SettingGroup("Cloud sync") {
                SyncSettingsSection(container = container)
            }
            SettingGroup("Preview") {                Text("Preview quality", style = type.controlLabel, color = colors.textPrimary)
                Spacer(Modifier.height(8.dp))
                SegmentedControl(
                    options = listOf(0 to "Balanced", 1 to "High"),
                    selected = previewQuality,
                    onSelect = settings::setPreviewQuality,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Text("Balanced renders faster on older devices.",
                    style = type.caption, color = colors.textTertiary)
                Spacer(Modifier.height(12.dp))
                SwitchRow("Show mask overlay",
                    "Tint the selected mask in red while editing.",
                    showOverlay, settings::setShowMaskOverlay)
                SwitchRow("Haptic feedback",
                    "Subtle vibration on slider resets and tool changes.",
                    haptics, settings::setHaptics)
            }
            SettingGroup("Library") {
                Text("Grid columns", style = type.controlLabel, color = colors.textPrimary)
                Spacer(Modifier.height(8.dp))
                SegmentedControl(
                    options = listOf(2 to "2", 3 to "3", 4 to "4", 5 to "5"),
                    selected = gridColumns,
                    onSelect = settings::setLibraryGridColumns,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Text("How many photo columns the library grid shows. You can " +
                    "also pinch the grid itself to zoom in and out.",
                    style = type.caption, color = colors.textTertiary)
            }
            SettingGroup("Storage") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Temporary files", style = type.controlLabel,
                            color = colors.textPrimary)
                        Text("$cacheSize of export/render cache",
                            style = type.caption, color = colors.textTertiary)
                    }
                    LumoraOutlineButton(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                container.context.cacheDir.deleteRecursively()
                                container.context.cacheDir.mkdirs()
                                cacheSize = "0 B"
                            }
                        },
                        shape = RoundedCornerShape(10.dp),
                    ) { Text("Clear", color = colors.textPrimary) }
                }
                Spacer(Modifier.height(8.dp))
                Text("Your photos and edits are never deleted by clearing the cache. " +
                    "Originals stay untouched in your gallery.",
                    style = type.caption, color = colors.textTertiary)
            }
            SettingGroup("About") {
                Text("H2W Photo Lab 1.0.0", style = type.controlLabel, color = colors.textPrimary)
                // Build fingerprint (P0 recurrence, 2026-10-18): the exact
                // build on the phone, so a stale pre-fix APK can be told
                // apart from the current one when diagnosing the black
                // preview. Baked at build time from the git SHA + date.
                // Tap-to-copy (2026-10-19): he reports the build over chat,
                // so one tap copies the full fingerprint to the clipboard
                // instead of him retyping the git SHA from the screen.
                val buildInfo =
                    "H2W Photo Lab 1.0.0 · Build ${BuildConfig.BUILD_DATE} · ${BuildConfig.GIT_SHA}"
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(
                            role = Role.Button,
                            onClickLabel = "Copy build info",
                            onClick = {
                                val cm = context.getSystemService(
                                    Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(
                                    ClipData.newPlainText("H2W Photo Lab build", buildInfo))
                                Toast.makeText(context, "Build info copied",
                                    Toast.LENGTH_SHORT).show()
                            },
                        )
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Build ${BuildConfig.BUILD_DATE} · ${BuildConfig.GIT_SHA}",
                        style = type.caption, color = colors.textTertiary,
                        modifier = Modifier.weight(1f))
                    Text("Copy", style = type.caption, color = colors.accent)
                }
                Text("Non-destructive photo editor. Originals are never modified; " +
                    "edits live in sidecar files and can be undone any time.",
                    style = type.caption, color = colors.textTertiary)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Welcome tour", style = type.controlLabel,
                            color = colors.textPrimary)
                        Text("Replay the first-run introduction.",
                            style = type.caption, color = colors.textTertiary)
                    }
                    LumoraOutlineButton(
                        onClick = onReplayTour,
                        shape = RoundedCornerShape(10.dp),
                    ) { Text("Replay", color = colors.textPrimary) }
                }
            }
            DeveloperRow()
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun DeveloperRow() {
    val colors = Lumora.colors
    val type = Lumora.type
    var showPopup by remember { mutableStateOf(false) }

    // Compact tappable row
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.panel)
            // Labeled role + action (22:32 cycle): a bare clickable() made
            // TalkBack fall back to a generic "double-tap to activate"
            // over the row's text, hiding what the row actually does.
            .clickable(
                role = Role.Button,
                onClickLabel = "View developer profile",
                onClick = { showPopup = true },
            )
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(R.drawable.developer_bg),
            contentDescription = "Htay Hlaing Win",
            modifier = Modifier.size(48.dp).clip(CircleShape),
            contentScale = ContentScale.Crop,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("DEVELOPER", style = Lumora.type.sectionTitle, color = colors.textSecondary)
            Spacer(Modifier.height(2.dp))
            Text("Htay Hlaing Win", style = type.controlLabel, color = colors.textPrimary)
        }
        Text("View", style = type.body, color = colors.accent)
    }

    // Animated popup dialog
    if (showPopup) {
        DeveloperPopup(onDismiss = { showPopup = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeveloperPopup(onDismiss: () -> Unit) {
    val colors = Lumora.colors
    val type = Lumora.type
    val context = LocalContext.current

    // Scale + fade entrance animation
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    val scale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.85f,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 320f),
        label = "popupScale",
    )
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(220),
        label = "popupAlpha",
    )

    BasicAlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
            this.alpha = alpha
        },
    ) {
        Box(
            Modifier.widthIn(max = 340.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(colors.panel),
        ) {
            // Background photo with reduced opacity
            Image(
                painter = painterResource(R.drawable.developer_bg),
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop,
                alpha = 0.16f,
            )
            Column(
                Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(
                    painter = painterResource(R.drawable.developer_bg),
                    contentDescription = "Htay Hlaing Win",
                    modifier = Modifier.size(96.dp).clip(CircleShape),
                    contentScale = ContentScale.Crop,
                )
                Spacer(Modifier.height(12.dp))
                Text("Htay Hlaing Win",
                    style = type.screenTitle, color = colors.textPrimary)
                Spacer(Modifier.height(4.dp))
                Text("App Developer",
                    style = type.body, color = colors.textSecondary)
                Spacer(Modifier.height(16.dp))
                // Facebook button
                LumoraButton(
                    onClick = {
                        val intent = Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://www.facebook.com/share/1HdRKfwsMs/"),
                        )
                        // Devices without a browser (restricted profiles)
                        // throw here — show a notice instead of crashing
                        // out of the developer popup.
                        try {
                            context.startActivity(intent)
                        } catch (_: Exception) {
                            Toast.makeText(context,
                                "No app available to open the link",
                                Toast.LENGTH_LONG).show()
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF1877F2),
                        contentColor = Color.White,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_facebook),
                        contentDescription = "Facebook",
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Facebook Profile")
                }
                Spacer(Modifier.height(8.dp))
                LumoraTextButton(onClick = onDismiss) {
                    Text("Close", color = colors.textSecondary)
                }
            }
        }
    }
}

@Composable
private fun SettingGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    val colors = Lumora.colors
    Column(
        Modifier.fillMaxWidth()
            .background(colors.panel, RoundedCornerShape(14.dp))
            .padding(16.dp),
    ) {
        Text(title.uppercase(), style = Lumora.type.sectionTitle, color = colors.textSecondary)
        Spacer(Modifier.height(12.dp))
        content()
    }
}

/**
 * Gemini API key settings for the AI Photo Assistant.
 * The key is stored app-private on this device only.
 */
@Composable
private fun AiKeySettings(container: AppContainer) {
    val colors = Lumora.colors
    val type = Lumora.type
    val context = LocalContext.current
    val keyStore = remember { com.lumora.photoeditor.ai.AiKeyStore(context) }
    var keyInput by remember { mutableStateOf("") }
    var hasKey by remember { mutableStateOf(keyStore.hasKey()) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testOk by remember { mutableStateOf(false) }
    var backupMsg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // Reinstalling the app wipes the app-private key store (observed 2026-10-17:
    // the key field was empty after a fresh APK install). These launchers let the
    // user back the key up to a file of their choosing and restore it in one tap
    // — written only on an explicit tap, and the file is plain text by design
    // (the caption below says so honestly).
    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        backupMsg = try {
            val key = keyStore.getGeminiKey()
            if (key == null) {
                "No key saved to back up"
            } else {
                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(key.toByteArray(Charsets.UTF_8))
                } ?: throw IllegalStateException("Could not open file")
                "Key backed up — keep the file private."
            }
        } catch (e: Exception) {
            "Backup failed: ${e.message?.take(80)}"
        }
    }
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        backupMsg = try {
            val text = context.contentResolver.openInputStream(uri)
                ?.bufferedReader()?.readText().orEmpty()
            val key = text.lines().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            if (key.isBlank()) {
                "That file has no key in it"
            } else {
                keyStore.setGeminiKey(key)
                keyInput = ""
                hasKey = true
                testResult = null
                "Key restored — tap Test Connection to verify."
            }
        } catch (e: Exception) {
            "Restore failed: ${e.message?.take(80)}"
        }
    }

    Text(
        "Connect Gemini to get AI editing suggestions in the editor. " +
            "Get a free key at aistudio.google.com.",
        style = type.caption, color = colors.textTertiary,
    )
    Spacer(Modifier.height(8.dp))

    if (hasKey) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "✓ API key saved",
                style = type.controlLabel, color = colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            LumoraTextButton(onClick = {
                keyStore.clearGeminiKey()
                hasKey = false
                testResult = null
            }) { Text("Remove", color = colors.textSecondary) }
        }
    } else {
        LumoraTextField(
            value = keyInput,
            onValueChange = { keyInput = it; testResult = null },
            label = "Gemini API key",
            placeholder = "Paste Gemini API key (AIza…)",
            isPassword = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        LumoraButton(
            onClick = {
                if (keyInput.isNotBlank()) {
                    keyStore.setGeminiKey(keyInput.trim())
                    keyInput = ""
                    hasKey = true
                    testResult = null
                }
            },
            enabled = keyInput.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save API Key") }
        Spacer(Modifier.height(8.dp))
        LumoraOutlineButton(
            onClick = { backupMsg = null; restoreLauncher.launch(arrayOf("text/plain")) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Restore key from backup file") }
    }

    if (hasKey) {
        Spacer(Modifier.height(8.dp))
        LumoraOutlineButton(
            onClick = {
                testing = true
                testResult = null
                // Minimal test via raw HTTP (no ViewModel here)
                scope.launch(Dispatchers.IO) {
                    val result = try {
                        val key = keyStore.getGeminiKey()
                        if (key == null) false to "No key saved"
                        else {
                            // Discover which models this key can actually use;
                            // fall back to the hardcoded list when discovery fails.
                            // 404 means deprecated, try next.
                            val geminiClient = com.lumora.photoeditor.ai.GeminiClient()
                            val models = (geminiClient.resolveModels(key) +
                                com.lumora.photoeditor.ai.GeminiClient.MODELS).distinct()
                            var ok = false
                            var msg = ""
                            var usedModel = ""
                            for (model in models) {
                                try {
                                    val url = java.net.URL(
                                        "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
                                    )
                                    val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                                        requestMethod = "POST"
                                        setRequestProperty("Content-Type", "application/json; charset=utf-8")
                                        setRequestProperty("x-goog-api-key", key)
                                        connectTimeout = 15_000
                                        readTimeout = 15_000
                                        doOutput = true
                                    }
                                    conn.outputStream.bufferedWriter(Charsets.UTF_8).use {
                                        it.write("""{"contents":[{"parts":[{"text":"Say OK"}]}]}""")
                                    }
                                    val code = conn.responseCode
                                    conn.disconnect()
                                    if (code in 200..299) {
                                        ok = true; usedModel = model
                                        break
                                    } else if (code == 404) {
                                        continue // deprecated model, try next
                                    } else {
                                        // Friendly plain-language message: the raw
                                        // API JSON body gets clipped mid-word on a
                                        // phone screen and looks like an app bug.
                                        msg = com.lumora.photoeditor.ai.friendlyGeminiHttpError(code)
                                        break
                                    }
                                } catch (e: Exception) {
                                    if (e is CancellationException) throw e // don't swallow coroutine cancellation
                                    msg = "Network error: ${e.message?.take(80)}"
                                }
                            }
                            if (ok) true to "Connected! Gemini is ready ($usedModel)"
                            else false to msg.ifBlank {
                                com.lumora.photoeditor.ai.friendlyNoGeminiModelFound()
                            }
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e // don't swallow coroutine cancellation
                        false to "Network error: ${e.message?.take(80)}"
                    }
                    withContext(Dispatchers.Main) {
                        testing = false
                        testOk = result.first
                        testResult = result.second
                    }
                }
            },
            enabled = !testing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (testing) {
                LumoraProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Testing…")
            } else {
                Text("Test Connection")
            }
        }
        Spacer(Modifier.height(8.dp))
        LumoraOutlineButton(
            onClick = { backupMsg = null; backupLauncher.launch("h2w-gemini-key.txt") },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Back up key to a file") }
    }

    testResult?.let {
        Spacer(Modifier.height(4.dp))
        Text(
            it,
            style = type.caption,
            color = if (testOk) colors.textPrimary else colors.danger,
        )
    }
    backupMsg?.let {
        Spacer(Modifier.height(4.dp))
        Text(it, style = type.caption, color = colors.textPrimary)
    }
    Spacer(Modifier.height(4.dp))
    Text(
        "The AI assistant bubble appears in the editor only after a key is " +
            "saved. " +
            "Reinstalling the app clears the saved key — back it up so you can " +
            "restore it here in one tap. The backup file is plain text: keep it private.",
        style = type.caption, color = colors.textTertiary,
    )
}

@Composable
private fun LicenseSettingsSection(container: AppContainer) {
    val colors = Lumora.colors
    val type = Lumora.type
    val store = container.licenseStore
    val status by store.status.collectAsState()
    var showPaywall by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { store.refresh() }

    StatusRow(
        "Status",
        com.lumora.photoeditor.data.billing.licenseStatusText(status),
    )
    Spacer(Modifier.height(4.dp))
    Text(
        when (status) {
            is com.lumora.photoeditor.data.billing.LicenseStatus.Trial ->
                "The trial covers every feature — nothing is locked. " +
                    "The Pro screen is ready for the future store."
            com.lumora.photoeditor.data.billing.LicenseStatus.TrialExpired ->
                "The trial has ended. Activate a license key to go Pro."
            is com.lumora.photoeditor.data.billing.LicenseStatus.Licensed ->
                "Your Pro license is active."
        },
        style = type.caption,
        color = colors.textTertiary,
    )
    Spacer(Modifier.height(8.dp))
    LumoraOutlineButton(
        onClick = { showPaywall = true },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("View Pro plans…")
    }
    if (showPaywall) {
        PaywallDialog(store = store, onDismiss = { showPaywall = false })
    }
}

@Composable
private fun SyncSettingsSection(container: AppContainer) {
    val colors = Lumora.colors
    val type = Lumora.type
    val settings = container.settings
    val engine = container.syncEngine
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val autoSync by settings.syncAuto.collectAsState()
    val account by container.syncAccounts.account.collectAsState()
    var snapshot by remember {
        mutableStateOf<com.lumora.photoeditor.data.sync.SyncSnapshot?>(null)
    }
    var syncing by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    suspend fun refreshSnapshot() {
        // deviceId() generates the stable install id once, so the row
        // never shows a blank id after the first open.
        engine.deviceId()
        snapshot = engine.snapshot()
    }

    LaunchedEffect(Unit) { refreshSnapshot() }

    StatusRow("Backend", snapshot?.backendName ?: "…")
    StatusRow("Account", when (val a = account) {
        com.lumora.photoeditor.data.sync.SyncAccount.SignedOut -> "Not signed in"
        is com.lumora.photoeditor.data.sync.SyncAccount.SignedIn -> a.email
    })
    StatusRow(
        "This device",
        com.lumora.photoeditor.data.sync.shortDeviceId(snapshot?.deviceId),
    )
    StatusRow(
        "Last sync",
        snapshot?.let { com.lumora.photoeditor.data.sync.formatLastSync(it.lastSyncAt) } ?: "…",
    )
    val lastError = snapshot?.lastError
    if (lastError != null) {
        Spacer(Modifier.height(4.dp))
        Text("Last error: $lastError", style = type.caption, color = colors.danger)
    }
    Spacer(Modifier.height(8.dp))
    SwitchRow(
        "Auto-sync",
        "Sync every 6 hours when connected. No backend is configured yet, " +
            "so scheduled runs are no-ops.",
        autoSync,
        onChange = {
            settings.setSyncAuto(it)
            com.lumora.photoeditor.data.sync.SyncScheduler.setAutoSync(context, it)
        },
    )
    Spacer(Modifier.height(8.dp))
    LumoraOutlineButton(
        onClick = {
            notice = null
            syncing = true
            scope.launch {
                try {
                    notice = com.lumora.photoeditor.data.sync
                        .describeSyncOutcome(engine.syncNow())
                    refreshSnapshot()
                } finally {
                    syncing = false
                }
            }
        },
        enabled = !syncing,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(if (syncing) "Syncing…" else "Sync now")
    }
    if (notice != null) {
        Spacer(Modifier.height(4.dp))
        Text(notice!!, style = type.caption, color = colors.textSecondary)
    }
    Spacer(Modifier.height(4.dp))
    Text(
        "Cloud sync needs your backend — a Google Drive project or your " +
            "own server. The app is sync-ready; the backend choice is yours.",
        style = type.caption, color = colors.textTertiary,
    )
}

@Composable
private fun StatusRow(label: String, value: String) {
    val colors = Lumora.colors
    val type = Lumora.type
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = type.caption,
            color = colors.textTertiary,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = type.body, color = colors.textPrimary)
    }
}

@Composable
private fun SwitchRow(
    title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit,
) {
    val colors = Lumora.colors
    val type = Lumora.type
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = type.controlLabel, color = colors.textPrimary)
            Text(subtitle, style = type.caption, color = colors.textTertiary)
        }
        LumoraSwitch(
            checked = checked,
            onCheckedChange = onChange,
            contentDescription = title,
        )
    }
}
