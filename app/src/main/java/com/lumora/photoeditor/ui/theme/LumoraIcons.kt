package com.lumora.photoeditor.ui.theme

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The Material "filled" icons the app needs that only exist in the
 * `material-icons-extended` artifact (the other icons the app uses ship in
 * `material-icons-core`). Vendoring just these drops the extended ~87MB
 * (classes) dependency entirely, cutting ~10-14MB of dex out of the APK.
 *
 * Icon artwork: Material Design Icons by Google, licensed under the
 * Apache License 2.0 (https://github.com/material-icons/material-icons).
 * Path data taken from the official baseline SVGs, unmodified.
 */
object LumoraIcons {

    private fun icon(name: String, vararg pathData: String): ImageVector {
        var b = ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        )
        // Multiple paths fill independently and composite, exactly like the
        // separate <path> elements of the source SVG (e.g. PhotoCamera's
        // body + lens circle).
        for (p in pathData) {
            b = b.addPath(pathData = PathParser().parsePathString(p).toNodes())
        }
        return b.build()
    }

    /** Mask tool: paintbrush. */
    val Brush: ImageVector by lazy {
        icon(
            "Brush",
            "M7 14c-1.66 0-3 1.34-3 3c0 1.31-1.16 2-2 2c.92 1.22 2.49 2 4 2c2.21 0 4-1.79 4-4c0-1.66-1.34-3-3-3zm13.71-9.37l-1.34-1.34a.996.996 0 0 0-1.41 0L9 12.25L11.75 15l8.96-8.96a.996.996 0 0 0 0-1.41z",
        )
    }

    /** Retouch tool: bandage / healing. */
    val Healing: ImageVector by lazy {
        icon(
            "Healing",
            "M17.73 12.02l3.98-3.98a.996.996 0 0 0 0-1.41l-4.34-4.34a.996.996 0 0 0-1.41 0l-3.98 3.98L8 2.29a1.001 1.001 0 0 0-1.41 0L2.25 6.63a.996.996 0 0 0 0 1.41l3.98 3.98L2.25 16a.996.996 0 0 0 0 1.41l4.34 4.34c.39.39 1.02.39 1.41 0l3.98-3.98l3.98 3.98c.2.2.45.29.71.29c.26 0 .51-.1.71-.29l4.34-4.34a.996.996 0 0 0 0-1.41l-3.99-3.98zM12 9c.55 0 1 .45 1 1s-.45 1-1 1s-1-.45-1-1s.45-1 1-1zm-4.71 1.96L3.66 7.34l3.63-3.63l3.62 3.62l-3.62 3.63zM10 13c-.55 0-1-.45-1-1s.45-1 1-1s1 .45 1 1s-.45 1-1 1zm2 2c-.55 0-1-.45-1-1s.45-1 1-1s1 .45 1 1s-.45 1-1 1zm2-4c.55 0 1 .45 1 1s-.45 1-1 1s-1-.45-1-1s.45-1 1-1zm2.66 9.34l-3.63-3.62l3.63-3.63l3.62 3.62l-3.62 3.63z",
        )
    }

    /** Hold-to-compare with original. */
    val CompareArrows: ImageVector by lazy {
        icon(
            "CompareArrows",
            "M9.01 14H2v2h7.01v3L13 15l-3.99-4v3zm5.98-1v-3H22V8h-7.01V5L11 9l3.99 4z",
        )
    }

    /** Optics tool. */
    val Adjust: ImageVector by lazy {
        icon(
            "Adjust",
            "M12 2C6.49 2 2 6.49 2 12s4.49 10 10 10s10-4.49 10-10S17.51 2 12 2zm0 18c-4.41 0-8-3.59-8-8s3.59-8 8-8s8 3.59 8 8s-3.59 8-8 8zm3-8c0 1.66-1.34 3-3 3s-3-1.34-3-3s1.34-3 3-3s3 1.34 3 3z",
        )
    }

    /** Linear-gradient mask type. */
    val Gradient: ImageVector by lazy {
        icon(
            "Gradient",
            "M11 9h2v2h-2zm-2 2h2v2H9zm4 0h2v2h-2zm2-2h2v2h-2zM7 9h2v2H7zm12-6H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zM9 18H7v-2h2v2zm4 0h-2v-2h2v2zm4 0h-2v-2h2v2zm2-7h-2v2h2v2h-2v-2h-2v2h-2v-2h-2v2H9v-2H7v2H5v-2h2v-2H5V5h14v6z",
        )
    }

    /** Detail tool. */
    val Grain: ImageVector by lazy {
        icon(
            "Grain",
            "M10 12c-1.1 0-2 .9-2 2s.9 2 2 2s2-.9 2-2s-.9-2-2-2zM6 8c-1.1 0-2 .9-2 2s.9 2 2 2s2-.9 2-2s-.9-2-2-2zm0 8c-1.1 0-2 .9-2 2s.9 2 2 2s2-.9 2-2s-.9-2-2-2zm12-8c1.1 0 2-.9 2-2s-.9-2-2-2s-2 .9-2 2s.9 2 2 2zm-4 8c-1.1 0-2 .9-2 2s.9 2 2 2s2-.9 2-2s-.9-2-2-2zm4-4c-1.1 0-2 .9-2 2s.9 2 2 2s2-.9 2-2s-.9-2-2-2zm-4-4c-1.1 0-2 .9-2 2s.9 2 2 2s2-.9 2-2s-.9-2-2-2zm-4-4c-1.1 0-2 .9-2 2s.9 2 2 2s2-.9 2-2s-.9-2-2-2z",
        )
    }

    /** Export (iOS-style share). */
    val IosShare: ImageVector by lazy {
        icon(
            "IosShare",
            "M16 5l-1.42 1.42l-1.59-1.59V16h-1.98V4.83L9.42 6.42L8 5l4-4l4 4zm4 5v11c0 1.1-.9 2-2 2H6a2 2 0 0 1-2-2V10c0-1.11.89-2 2-2h3v2H6v11h12V10h-3V8h3a2 2 0 0 1 2 2z",
        )
    }

    /** Import a shared preset file. Path data verbatim from the official
     *  Material baseline file_download 24px SVG (the fill="none" background
     *  rect dropped, same as the PhotoCamera lens-circle handling). */
    val FileDownload: ImageVector by lazy {
        icon(
            "FileDownload",
            "M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z",
        )
    }

    /** Editor toolbar: undo. */
    val Undo: ImageVector by lazy {
        icon(
            "Undo",
            "M12.5 8c-2.65 0-5.05.99-6.9 2.6L2 7v9h9l-3.62-3.62c1.39-1.16 3.16-1.88 5.12-1.88c3.54 0 6.55 2.31 7.6 5.5l2.37-.78C21.08 11.03 17.15 8 12.5 8z",
        )
    }

    /** Editor toolbar: redo. */
    val Redo: ImageVector by lazy {
        icon(
            "Redo",
            "M18.4 10.6C16.55 8.99 14.15 8 11.5 8c-4.65 0-8.58 3.03-9.96 7.22L3.9 16a8.002 8.002 0 0 1 7.6-5.5c1.95 0 3.73.72 5.12 1.88L13 16h9V7l-3.6 3.6z",
        )
    }

    /** Light tool. */
    val Tune: ImageVector by lazy {
        icon(
            "Tune",
            "M3 17v2h6v-2H3zM3 5v2h10V5H3zm10 16v-2h8v-2h-8v-2h-2v6h2zM7 9v2H3v2h4v2h2V9H7zm14 4v-2H11v2h10zm-6-4h2V7h4V5h-4V3h-2v6z",
        )
    }

    /** Color tool. */
    val Palette: ImageVector by lazy {
        icon(
            "Palette",
            "M12 2C6.49 2 2 6.49 2 12s4.49 10 10 10a2.5 2.5 0 0 0 2.5-2.5c0-.61-.23-1.2-.64-1.67a.528.528 0 0 1-.13-.33c0-.28.22-.5.5-.5H16c3.31 0 6-2.69 6-6c0-4.96-4.49-9-10-9zm5.5 11c-.83 0-1.5-.67-1.5-1.5s.67-1.5 1.5-1.5s1.5.67 1.5 1.5s-.67 1.5-1.5 1.5zm-3-4c-.83 0-1.5-.67-1.5-1.5S13.67 6 14.5 6s1.5.67 1.5 1.5S15.33 9 14.5 9zM5 11.5c0-.83.67-1.5 1.5-1.5s1.5.67 1.5 1.5S7.33 13 6.5 13S5 12.33 5 11.5zm6-4c0 .83-.67 1.5-1.5 1.5S8 8.33 8 7.5S8.67 6 9.5 6s1.5.67 1.5 1.5z",
        )
    }

    /** Grade tool. */
    val DonutLarge: ImageVector by lazy {
        icon(
            "DonutLarge",
            "M11 5.08V2c-5 .5-9 4.81-9 10s4 9.5 9 10v-3.08c-3-.48-6-3.4-6-6.92s3-6.44 6-6.92zM18.97 11H22c-.47-5-4-8.53-9-9v3.08C16 5.51 18.54 8 18.97 11zM13 18.92V22c5-.47 8.53-4 9-9h-3.03c-.43 3-2.97 5.49-5.97 5.92z",
        )
    }

    /** Geometry tool. */
    val Crop: ImageVector by lazy {
        icon(
            "Crop",
            "M17 15h2V7c0-1.1-.9-2-2-2H9v2h8v8zM7 17V1H5v4H1v2h4v10c0 1.1.9 2 2 2h10v4h2v-4h4v-2H7z",
        )
    }

    /** Preset tool. */
    val Style: ImageVector by lazy {
        icon(
            "Style",
            "M2.53 19.65l1.34.56v-9.03l-2.43 5.86c-.41 1.02.08 2.19 1.09 2.61zm19.5-3.7L17.07 3.98a2.013 2.013 0 0 0-1.81-1.23c-.26 0-.53.04-.79.15L7.1 5.95a1.999 1.999 0 0 0-1.08 2.6l4.96 11.97a1.998 1.998 0 0 0 2.6 1.08l7.36-3.05a1.994 1.994 0 0 0 1.09-2.6zM7.88 8.75c-.55 0-1-.45-1-1s.45-1 1-1s1 .45 1 1s-.45 1-1 1zm-2 11c0 1.1.9 2 2 2h1.45l-3.45-8.34v6.34z",
        )
    }

    /** White-balance eyedropper. */
    val Colorize: ImageVector by lazy {
        icon(
            "Colorize",
            "M20.71 5.63l-2.34-2.34a.996.996 0 0 0-1.41 0l-3.12 3.12l-1.93-1.91l-1.41 1.41l1.42 1.42L3 16.25V21h4.75l8.92-8.92l1.42 1.42l1.41-1.41l-1.92-1.92l3.12-3.12c.4-.4.4-1.03.01-1.42zM6.92 19L5 17.08l8.06-8.06l1.92 1.92L6.92 19z",
        )
    }

    /** Mask: show. */
    val Visibility: ImageVector by lazy {
        icon(
            "Visibility",
            "M12 4.5C7 4.5 2.73 7.61 1 12c1.73 4.39 6 7.5 11 7.5s9.27-3.11 11-7.5c-1.73-4.39-6-7.5-11-7.5zM12 17c-2.76 0-5-2.24-5-5s2.24-5 5-5s5 2.24 5 5s-2.24 5-5 5zm0-8c-1.66 0-3 1.34-3 3s1.34 3 3 3s3-1.34 3-3s-1.34-3-3-3z",
        )
    }

    /** Mask: hide. */
    val VisibilityOff: ImageVector by lazy {
        icon(
            "VisibilityOff",
            "M12 7c2.76 0 5 2.24 5 5c0 .65-.13 1.26-.36 1.83l2.92 2.92c1.51-1.26 2.7-2.89 3.43-4.75c-1.73-4.39-6-7.5-11-7.5c-1.4 0-2.74.25-3.98.7l2.16 2.16C10.74 7.13 11.35 7 12 7zM2 4.27l2.28 2.28l.46.46A11.804 11.804 0 0 0 1 12c1.73 4.39 6 7.5 11 7.5c1.55 0 3.03-.3 4.38-.84l.42.42L19.73 22L21 20.73L3.27 3L2 4.27zM7.53 9.8l1.55 1.55c-.05.21-.08.43-.08.65c0 1.66 1.34 3 3 3c.22 0 .44-.03.65-.08l1.55 1.55c-.67.33-1.41.53-2.2.53c-2.76 0-5-2.24-5-5c0-.79.2-1.53.53-2.2zm4.31-.78l3.15 3.15l.02-.16c0-1.66-1.34-3-3-3l-.17.01z",
        )
    }

    /** Mask type: none. */
    val RadioButtonUnchecked: ImageVector by lazy {
        icon(
            "RadioButtonUnchecked",
            "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10s10-4.48 10-10S17.52 2 12 2zm0 18c-4.42 0-8-3.58-8-8s3.58-8 8-8s8 3.58 8 8s-3.58 8-8 8z",
        )
    }

    /** Sky auto-mask. */
    val Cloud: ImageVector by lazy {
        icon(
            "Cloud",
            "M19.35 10.04A7.49 7.49 0 0 0 12 4C9.11 4 6.6 5.64 5.35 8.04A5.994 5.994 0 0 0 0 14c0 3.31 2.69 6 6 6h13c2.76 0 5-2.24 5-5c0-2.64-2.05-4.78-4.65-4.96z",
        )
    }

    /** AI background mask type. */
    val Landscape: ImageVector by lazy {
        icon(
            "Landscape",
            "M14,6l-4.22,5.63l1.25,1.67L14,9.33L15.75,11.67L18.25,8L14,6zM4,4h16c1.1,0,2,0.9,2,2v12c0,1.1-0.9,2-2,2H4c-1.1,0-2-0.9-2-2V6C2,4.9,2.9,4,4,4zM20,18H4V8l4.2,5.6L9.45,12l2.8,3.73L14,13.33L15.75,15.67L19.95,10L20,18z",
        )
    }

    /** Copy edits. */
    val ContentCopy: ImageVector by lazy {
        icon(
            "ContentCopy",
            "M16 1H4c-1.1 0-2 .9-2 2v14h2V3h12V1zm3 4H8c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h11c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm0 16H8V7h11v14z",
        )
    }

    /** Paste edits. */
    val ContentPaste: ImageVector by lazy {
        icon(
            "ContentPaste",
            "M19 2h-4.18C14.4.84 13.3 0 12 0c-1.3 0-2.4.84-2.82 2H5c-1.1 0-2 .9-2 2v16c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm-7 0c.55 0 1 .45 1 1s-.45 1-1 1s-1-.45-1-1s.45-1 1-1zm7 18H5V4h2v3h10V4h2v16z",
        )
    }

    /** Invert mask. */
    val InvertColors: ImageVector by lazy {
        icon(
            "InvertColors",
            "M12 4.81V19c-3.31 0-6-2.63-6-5.87c0-1.56.62-3.03 1.75-4.14L12 4.81M6.35 7.56C4.9 8.99 4 10.96 4 13.13C4 17.48 7.58 21 12 21s8-3.52 8-7.87c0-2.17-.9-4.14-2.35-5.57L12 2L6.35 7.56z",
        )
    }

    /** Export error state. */
    val Error: ImageVector by lazy {
        icon(
            "Error",
            "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10s10-4.48 10-10S17.52 2 12 2zm1 15h-2v-2h2v2zm0-4h-2V7h2v6z",
        )
    }

    /** Library rating: unrated star. */
    val StarBorder: ImageVector by lazy {
        icon(
            "StarBorder",
            "M22 9.24l-7.19-.62L12 2L9.19 8.63L2 9.24l5.46 4.73L5.82 21L12 17.27L18.18 21l-1.63-7.03L22 9.24zM12 15.4l-3.76 2.27l1-4.28l-3.32-2.88l4.38-.38L12 6.1l1.71 4.04l4.38.38l-3.32 2.88l1 4.28L12 15.4z",
        )
    }

    /** Edit-history panel (two-tone baseline clock/arrow). */
    val History: ImageVector by lazy {
        icon(
            "History",
            "M13 3c-4.97 0-9 4.03-9 9H1l3.89 3.89.07.14L9 12H6c0-3.87 3.13-7 7-7s7 3.13 7 7-3.13 7-7 7c-1.93 0-3.68-.79-4.94-2.06l-1.42 1.42C8.27 19.99 10.51 21 13 21c4.97 0 9-4.03 9-9s-4.03-9-9-9zm-1 5v5l4.25 2.52.77-1.28-3.52-2.09V8z",
        )
    }

    /** Library sort order. */
    val Sort: ImageVector by lazy {
        icon(
            "Sort",
            "M3 18h6v-2H3v2zM3 6v2h18V6H3zm0 7h12v-2H3v2z",
        )
    }

    /** Mask menu: subtract a component from the mask (minus bar). */
    val Remove: ImageVector by lazy {
        icon(
            "Remove",
            "M19 13H5v-2h14v2z",
        )
    }

    /** Mask menu: intersect with another selection (focus corners). */
    val CenterFocusStrong: ImageVector by lazy {
        icon(
            "CenterFocusStrong",
            "M12 8c-2.21 0-4 1.79-4 4s1.79 4 4 4s4-1.79 4-4s-1.79-4-4-4zm-7 7H3v4c0 1.1.9 2 2 2h4v-2H5v-4zM5 5h4V3H5c-1.1 0-2 .9-2 2v4h2V5zm14-2h-4v2h4v4h2V5c0-1.1-.9-2-2-2zm0 16h-4v2h4c1.1 0 2-.9 2-2v-4h-2v4z",
        )
    }

    /**
     * Library import: camera. Official Material baseline photo_camera
     * (filled), Apache 2.0 — path data verbatim from
     * https://github.com/google/material-design-icons (master,
     * src/image/photo_camera/materialicons/24px.svg). The SVG's <circle>
     * lens element is expanded to its spec-equivalent arc path (verified
     * pixel-identical to an analytic disk by rasterization); the fill="none"
     * background rect is dropped as it paints nothing.
     */
    val PhotoCamera: ImageVector by lazy {
        icon(
            "PhotoCamera",
            "M9 2L7.17 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2h-3.17L15 2H9zm3 15c-2.76 0-5-2.24-5-5s2.24-5 5-5 5 2.24 5 5-2.24 5-5 5z",
            "M8.8 12a3.2 3.2 0 1 0 6.4 0a3.2 3.2 0 1 0-6.4 0z",
        )
    }

    /** Library selection action: panorama stitch (Photomerge parity).
     *  Path data verbatim from the official Material baseline panorama
     *  24px SVG (material-design-icons, Apache License 2.0), unmodified. */
    val Panorama: ImageVector by lazy {
        icon(
            "Panorama",
            "M23 18V6c0-1.1-.9-2-2-2H3c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h18c1.1 0 2-.9 2-2zM8.5 12.5l2.5 3.01L14.5 11l4.5 6H5l3.5-4.5z",
        )
    }

}
