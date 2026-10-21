package com.lumora.photoeditor.core

import android.content.Context
import android.content.SharedPreferences
import com.lumora.photoeditor.engine.ExportSettings
import com.lumora.photoeditor.export.ExportPreset
import com.lumora.photoeditor.export.MAX_EXPORT_PRESETS
import com.lumora.photoeditor.export.isFactoryExportPreset
import com.lumora.photoeditor.export.mergeFactoryPresets
import com.lumora.photoeditor.export.sanitizePresetName
import com.lumora.photoeditor.export.uniquePresetName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Simple observable app settings (backed by SharedPreferences). */
class AppSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("lumora_settings", Context.MODE_PRIVATE)

    // Range-guarded like every sibling setting: a stray value (backup
    // restore, old build) must never land unvalidated in the flow — the
    // Settings selector and the GL preview path only understand 0/1.
    private val _previewQuality = MutableStateFlow(
        prefs.getInt("preview_quality", 1).coerceIn(0, 1))
    val previewQuality: StateFlow<Int> = _previewQuality.asStateFlow() // 0 balanced, 1 high

    /** Mask view mode: 0 = off, 1 = red overlay, 2 = black & white.
     *  Migrates once from the old boolean "mask_overlay" pref. */
    private val _maskViewMode = MutableStateFlow(loadMaskViewMode())
    val maskViewMode: StateFlow<Int> = _maskViewMode.asStateFlow()

    /** Backwards-compatible switch view of the mode (true when not Off) —
     *  used by Settings. Toggling on restores the red overlay. */
    private val _showMaskOverlay = MutableStateFlow(_maskViewMode.value != 0)
    val showMaskOverlay: StateFlow<Boolean> = _showMaskOverlay.asStateFlow()

    private val _showRetouchSpots = MutableStateFlow(prefs.getBoolean("retouch_spots", true))
    val showRetouchSpots: StateFlow<Boolean> = _showRetouchSpots.asStateFlow()

    /** Crop overlay grid style: 0 = thirds, 1 = grid, 2 = diagonal,
     *  3 = golden ratio. Drawn on the Compose canvas over the crop quad
     *  (Lightroom parity — the "O" overlay cycle). */
    private val _cropOverlayStyle = MutableStateFlow(
        prefs.getInt("crop_overlay_style", CROP_OVERLAY_THIRDS).coerceIn(0, 3))
    val cropOverlayStyle: StateFlow<Int> = _cropOverlayStyle.asStateFlow()

    fun setCropOverlayStyle(style: Int) {
        val v = style.coerceIn(0, 3)
        prefs.edit().putInt("crop_overlay_style", v).apply()
        _cropOverlayStyle.value = v
    }

    /** Composition grid over the preview in any tool mode (rule of thirds,
     *  drawn on the same displayed-photo quad as the straighten alignment
     *  grid). Crop mode keeps its own overlay (with dimming + frame), so
     *  the general grid suppresses itself there; persisted like the crop
     *  overlay style so the preference survives restarts. */
    private val _showGrid = MutableStateFlow(prefs.getBoolean("show_grid", false))
    val showGrid: StateFlow<Boolean> = _showGrid.asStateFlow()

    fun setShowGrid(v: Boolean) {
        prefs.edit().putBoolean("show_grid", v).apply()
        _showGrid.value = v
    }

    companion object {
        const val CROP_OVERLAY_THIRDS = 0
        const val CROP_OVERLAY_GRID = 1
        const val CROP_OVERLAY_DIAGONAL = 2
        const val CROP_OVERLAY_GOLDEN = 3
        val CROP_OVERLAY_LABELS = listOf("Thirds", "Grid", "Diagonal", "Golden")
    }

    private val _haptics = MutableStateFlow(prefs.getBoolean("haptics", true))
    val haptics: StateFlow<Boolean> = _haptics.asStateFlow()

    /** 0 = system, 1 = dark, 2 = light — range-guarded like every
     *  sibling setting: a stray pref value (backup restore, old build) must
     *  never land unvalidated in the flow, or the Settings selector would
     *  show no selection and the MainActivity theme `when` would silently
     *  fall through to system default. Coercion lives in [ThemeModeGuard] so
     *  init and setter can never disagree. */
    private val _themeMode = MutableStateFlow(
        ThemeModeGuard.coerce(prefs.getInt("theme_mode", 0)))
    val themeMode: StateFlow<Int> = _themeMode.asStateFlow()

    fun setThemeMode(v: Int) {
        val t = ThemeModeGuard.coerce(v)
        prefs.edit().putInt("theme_mode", t).apply()
        _themeMode.value = t
    }

    // Range-guarded on BOTH sides: the setter coerces, and the getter
    // coerces too, because a stray stored value (backup restore, old
    // build) must never land unvalidated in the UI flow — the export
    // form seeds its quality slider straight from this getter, and
    // Bitmap.compress misbehaves outside 1..100. Same principle as
    // ThemeModeGuard, applied to every range-guarded var below.
    var defaultExportQuality: Int
        get() = prefs.getInt("export_quality", 92).coerceIn(1, 100)
        set(v) { prefs.edit().putInt("export_quality", v.coerceIn(1, 100)).apply() }

    var defaultExportFormat: String
        get() = prefs.getString("export_format", "JPEG") ?: "JPEG"
        set(v) { prefs.edit().putString("export_format", v).apply() }

    // --- Export file name (persisted so the user types it once, like the watermark) ---
    var exportFileName: String
        get() = prefs.getString("export_file_name", "") ?: ""
        set(v) { prefs.edit().putString("export_file_name", v.take(120)).apply() }

    // --- More export defaults (persisted like quality/format/file name, so the
    // user doesn't re-pick them on every export) ---
    // Getter coerced like the setter (see defaultExportQuality): a stray
    // long-edge value must never reach the form unvalidated. The range
    // truth lives HERE — call sites must not re-guard it (that drifted
    // once already: the form duplicated this range inline).
    var defaultExportLongEdge: Int
        get() = prefs.getInt("export_long_edge", 0).takeIf { it in 0..16000 } ?: 0
        set(v) {
            // Any engine-valid size (0 = original); the old fixed list
            // silently dropped the per-platform recipe sizes (1080/1600/
            // 1920) back to 0, so those recipes never actually resized.
            prefs.edit().putInt(
                "export_long_edge",
                v.takeIf { it in 0..16000 } ?: 0
            ).apply()
        }

    var defaultKeepMetadata: Boolean
        get() = prefs.getBoolean("export_keep_metadata", true)
        set(v) { prefs.edit().putBoolean("export_keep_metadata", v).apply() }

    var defaultEmbedColorProfile: Boolean
        get() = prefs.getBoolean("export_embed_color_profile", true)
        set(v) { prefs.edit().putBoolean("export_embed_color_profile", v).apply() }

    /**
     * Restores every sticky export default to its factory value (JPEG,
     * quality 92, original dimensions, EXIF + ICC on, watermark off with
     * default placement/size/opacity, empty file name).
     *
     * Deliberately preserved: the saved watermark *signature text* (it is
     * the user's identity, not a setting) and the named export presets.
     */
    fun resetExportDefaults() {
        prefs.edit()
            .putInt("export_quality", 92)
            .putString("export_format", "JPEG")
            .putString("export_file_name", "")
            .putInt("export_long_edge", 0)
            .putBoolean("export_keep_metadata", true)
            .putBoolean("export_embed_color_profile", true)
            .putBoolean("watermark_enabled", false)
            .putString("watermark_position", "BOTTOM_RIGHT")
            .putFloat("watermark_size", 0.035f)
            .putFloat("watermark_opacity", 0.7f)
            .apply()
    }

    // --- Export watermark (persisted so the user types their signature once) ---
    var watermarkEnabled: Boolean
        get() = prefs.getBoolean("watermark_enabled", false)
        set(v) { prefs.edit().putBoolean("watermark_enabled", v).apply() }

    var watermarkText: String
        get() = prefs.getString("watermark_text", "") ?: ""
        set(v) { prefs.edit().putString("watermark_text", v.take(80)).apply() }

    var watermarkPosition: String
        get() = prefs.getString("watermark_position", "BOTTOM_RIGHT") ?: "BOTTOM_RIGHT"
        set(v) { prefs.edit().putString("watermark_position", v).apply() }

    // Getter coerced like the setter: WatermarkSettings.validate()
    // rejects sizeFrac outside 0.02..0.06, so a stray stored value must
    // never reach the export screen unvalidated.
    var watermarkSize: Float
        get() = prefs.getFloat("watermark_size", 0.035f).coerceIn(0.02f, 0.06f)
        set(v) { prefs.edit().putFloat("watermark_size", v.coerceIn(0.02f, 0.06f)).apply() }

    // Getter coerced like the setter for the same reason (opacity is
    // blended 0..1 in the compositor).
    var watermarkOpacity: Float
        get() = prefs.getFloat("watermark_opacity", 0.7f).coerceIn(0.1f, 1f)
        set(v) { prefs.edit().putFloat("watermark_opacity", v.coerceIn(0.1f, 1f)).apply() }

    // --- Library grid sort order (persisted; see SortOrder in LibraryRepository) ---
    var librarySortOrderName: String
        get() = prefs.getString("library_sort_order", "NEWEST") ?: "NEWEST"
        set(v) { prefs.edit().putString("library_sort_order", v).apply() }

    // --- Library grid density (persisted; Lightroom-style pinch zoom) ---
    private val _libraryGridColumns =
        MutableStateFlow(prefs.getInt("library_grid_columns", 3).coerceIn(2, 5))
    val libraryGridColumns: StateFlow<Int> = _libraryGridColumns.asStateFlow()

    /** Library grid density in columns (2..5); driven by pinch on the grid or Settings. */
    fun setLibraryGridColumns(v: Int) {
        val c = v.coerceIn(2, 5)
        prefs.edit().putInt("library_grid_columns", c).apply()
        _libraryGridColumns.value = c
    }

    fun setPreviewQuality(v: Int) {
        val q = v.coerceIn(0, 1)
        prefs.edit().putInt("preview_quality", q).apply()
        _previewQuality.value = q
    }

    fun setMaskViewMode(v: Int) {
        val m = v.coerceIn(0, 2)
        prefs.edit().putInt("mask_view_mode", m).apply()
        _maskViewMode.value = m
        _showMaskOverlay.value = m != 0
    }

    /** Mask overlay opacity (red / B&W mask view): 0.05 = barely visible,
     *  1 = fully opaque. Persisted like the view mode.
     *
     *  2026-10-18: the old 0.55 default hid the photo while refining fine
     *  edges (on-device video review: "far too opaque"), so the default is
     *  now 0.35. One-time migration: installs still sitting on the untouched
     *  old default move to 0.35; anyone who deliberately moved the slider
     *  keeps their own value. */
    private val _maskOverlayAlpha = MutableStateFlow(
        migrateOverlayAlpha(prefs.getFloat("mask_overlay_alpha", -1f)).coerceIn(0.05f, 1f))
    val maskOverlayAlpha: StateFlow<Float> = _maskOverlayAlpha.asStateFlow()

    private fun migrateOverlayAlpha(stored: Float): Float {
        // -1 = never set; exactly 0.55 = the pre-2026-10-18 default, never
        // touched by the user (we only ever wrote the slider value verbatim).
        // The pure decision lives in MaskOverlayMigration.kt (harness-tested).
        val migrated = migrateMaskOverlayAlpha(stored)
        if (migrated != stored) prefs.edit().putFloat("mask_overlay_alpha", migrated).apply()
        return migrated
    }

    fun setMaskOverlayAlpha(v: Float) {
        val a = v.coerceIn(0.05f, 1f)
        prefs.edit().putFloat("mask_overlay_alpha", a).apply()
        _maskOverlayAlpha.value = a
    }

    private fun loadMaskViewMode(): Int {        if (prefs.contains("mask_view_mode")) {
            return prefs.getInt("mask_view_mode", 1).coerceIn(0, 2)
        }
        val m = if (prefs.getBoolean("mask_overlay", true)) 1 else 0
        prefs.edit().putInt("mask_view_mode", m).apply()
        return m
    }

    fun setShowMaskOverlay(v: Boolean) {
        // Off -> red overlay (the historic default); B&W stays reachable from the Mask panel.
        setMaskViewMode(if (v) 1 else 0)
    }

    fun setShowRetouchSpots(v: Boolean) {
        prefs.edit().putBoolean("retouch_spots", v).apply()
        _showRetouchSpots.value = v
    }

    fun setHaptics(v: Boolean) {
        prefs.edit().putBoolean("haptics", v).apply()
        _haptics.value = v
    }

    // --- Cloud sync (Phase C#8): master switch for the WorkManager
    // auto-sync schedule. The switch is honest on its own: with no backend
    // configured the scheduled runs are clean no-ops (see SyncWorker). ---
    private val _syncAuto = MutableStateFlow(prefs.getBoolean("sync_auto", false))
    val syncAuto: StateFlow<Boolean> = _syncAuto.asStateFlow()

    fun setSyncAuto(v: Boolean) {
        prefs.edit().putBoolean("sync_auto", v).apply()
        _syncAuto.value = v
    }

    // --- First-run onboarding (welcome tour; shown once, replayable from Settings) ---
    var onboardingSeen: Boolean
        get() = prefs.getBoolean("onboarding_seen", false)
        set(v) { prefs.edit().putBoolean("onboarding_seen", v).apply() }

    // --- Export presets (named bundles of ExportSettings; Lightroom parity) ---
    private val presetJson = Json { ignoreUnknownKeys = true }

    private fun loadExportPresets(): List<ExportPreset> = try {
        val user: List<ExportPreset> = presetJson.decodeFromString(
            prefs.getString("export_presets", "[]") ?: "[]")
        // Built-in per-platform recipes (Instagram/Facebook/web/…) always
        // lead the list; stale persisted factory copies are dropped so the
        // shipped recipes win.
        mergeFactoryPresets(user)
    } catch (_: Exception) {
        // Corrupt JSON degrades to just the built-in recipes, never a crash.
        mergeFactoryPresets(emptyList())
    }

    private val _exportPresets = MutableStateFlow(loadExportPresets())
    val exportPresets: StateFlow<List<ExportPreset>> = _exportPresets.asStateFlow()

    /** Saves the current export configuration under [name]; null when the name is unusable. */
    fun saveExportPreset(name: String, settings: ExportSettings): ExportPreset? {
        val clean = sanitizePresetName(name)
        if (clean.isEmpty()) return null
        val preset = ExportPreset(
            name = uniquePresetName(clean, _exportPresets.value.map { it.name }),
            settings = settings,
        )
        // The cap applies to the user's own presets only — the built-in
        // recipes are always kept (capping the merged list could evict them).
        val users = (_exportPresets.value.filterNot { isFactoryExportPreset(it.id) } + preset)
            .takeLast(MAX_EXPORT_PRESETS)
        val next = mergeFactoryPresets(users)
        _exportPresets.value = next
        persistExportPresets(next)
        return preset
    }

    fun deleteExportPreset(id: String) {
        // Built-in recipes are not deletable; the Export screen also hides
        // the long-press delete gesture for them, this is defense in depth.
        if (isFactoryExportPreset(id)) return
        val next = _exportPresets.value.filterNot { it.id == id }
        if (next.size == _exportPresets.value.size) return
        _exportPresets.value = next
        persistExportPresets(next)
    }

    private fun persistExportPresets(list: List<ExportPreset>) {
        try {
            prefs.edit()
                .putString("export_presets", presetJson.encodeToString(list))
                .apply()
        } catch (_: Exception) {
            /* keep the in-memory list; persistence retries on the next change */
        }
    }
}
