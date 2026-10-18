package com.lumora.photoeditor.export

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextUtils
import androidx.core.content.FileProvider
import com.lumora.photoeditor.core.AppContainer
import com.lumora.photoeditor.data.ProofMark
import com.lumora.photoeditor.data.ProofingGallery
import com.lumora.photoeditor.engine.EditState
import com.lumora.photoeditor.engine.ExportFormat
import com.lumora.photoeditor.engine.ExportSettings
import com.lumora.photoeditor.engine.GlEngine
import com.lumora.photoeditor.engine.MaskEngine
import com.lumora.photoeditor.engine.sanitizeExportName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.ceil

/**
 * Phase C#7 slice 2 — contact-sheet export for proofing galleries
 * (Lightroom "print contact sheet" parity, the share half of the
 * share/select workflow). Renders every gallery photo WITH its saved edits
 * through the same GPU pipeline as exports, tiles them onto one or more
 * JPEG sheets with a gallery header, per-photo captions, and optional
 * picked/rejected badges, then shares the file(s) with the client.
 *
 * A sheet page holds at most [PHOTOS_PER_SHEET_PAGE] photos so the composed
 * bitmap never grows into OOM territory; bigger galleries become numbered
 * pages shared together.
 */

/** Photos rendered onto a single sheet page (bitmap budget). */
const val PHOTOS_PER_SHEET_PAGE = 48

/** A gallery bigger than this refuses the sheet with a clear message. */
const val MAX_CONTACT_SHEET_PHOTOS = 480

/** Per-cell render long edge — proofing resolution, cheap on memory. */
const val CONTACT_SHEET_CELL_LONG_EDGE = 640

/** One composed cell: the photo plus its caption data. */
data class SheetCell(
    val imageId: String,
    val displayName: String,
    /** Rendered with the photo's saved edits; null = unreadable, drawn as a placeholder. */
    val bitmap: Bitmap?,
    val mark: ProofMark?,
    /** 1-based position in the gallery order. */
    val index: Int,
)

data class ContactSheetOptions(
    /** 0 = auto (3/4/5 by count); otherwise 2..6. */
    val columns: Int = 0,
    /** Draw picked/rejected badges on cells and counts in the header. */
    val showMarks: Boolean = true,
)

/** Pure — pinned by SelfTest check 66. */
fun contactSheetAutoColumns(photoCount: Int): Int = when {
    photoCount <= 9 -> 3
    photoCount <= 36 -> 4
    else -> 5
}

/** Pure — pinned by SelfTest check 66. */
fun contactSheetColumns(photoCount: Int, options: ContactSheetOptions): Int =
    if (options.columns == 0) contactSheetAutoColumns(photoCount)
    else options.columns.coerceIn(2, 6)

/** Pure — pinned by SelfTest check 66. */
fun contactSheetPages(photoCount: Int, columns: Int): Int =
    ceil(photoCount / PHOTOS_PER_SHEET_PAGE.toFloat()).toInt().coerceAtLeast(1)

data class ContactSheetLayout(
    val columns: Int,
    val rows: Int,
    val sheetW: Int,
    val sheetH: Int,
    /** Square photo area per cell. */
    val cellW: Int,
    val captionH: Int,
    val headerH: Int,
    val margin: Int,
    val gap: Int,
)

/**
 * Pixel layout for one sheet page holding [photoCountOnPage] photos.
 * Sheet width is fixed at 2400px; the height follows the row count, so a
 * full 48-photo page stays well inside a safe bitmap budget.
 * Pure — pinned by SelfTest check 66.
 */
fun contactSheetLayout(photoCountOnPage: Int, columns: Int): ContactSheetLayout {
    val cols = columns.coerceIn(2, 6)
    val rows = ceil(photoCountOnPage / cols.toFloat()).toInt().coerceAtLeast(1)
    val sheetW = 2400
    val margin = 72
    val gap = 36
    val headerH = 320
    val captionH = 100
    val cellW = (sheetW - margin * 2 - gap * (cols - 1)) / cols
    val sheetH = margin + headerH + gap + rows * (cellW + captionH) +
        (rows - 1) * gap + margin
    return ContactSheetLayout(cols, rows, sheetW, sheetH, cellW, captionH,
        headerH, margin, gap)
}

/** Pure — pinned by SelfTest check 66. */
fun markBadgeText(mark: ProofMark?): String? = when (mark) {
    ProofMark.APPROVED -> "PICKED"
    ProofMark.REJECTED -> "REJECTED"
    null -> null
}

/**
 * "Wedding — Smith" -> "Contact sheet - Wedding - Smith"; page suffix when
 * the gallery spans pages. Never blank. Pure — pinned by SelfTest check 66.
 */
fun contactSheetFileName(galleryName: String, page: Int, pages: Int): String {
    val clean = sanitizeExportName(galleryName.replace(Regex("\\s+"), " "))
        .ifBlank { "Gallery" }
    val stem = "Contact sheet - $clean"
    return if (pages > 1) "$stem - page $page of $pages" else stem
}

/**
 * Caption under a cell: "12. IMG_2041.jpg". Pure — pinned by SelfTest
 * check 66.
 */
fun sheetCellCaption(index: Int, displayName: String): String =
    "$index. ${displayName.ifBlank { "Photo" }}"

/** Outcome of a contact-sheet build. */
data class ContactSheetOutcome(
    val files: List<File>,
    val pages: Int,
    val rendered: Int,
    /** Photos that could not be rendered (drawn as placeholders). */
    val failed: Int,
)

/**
 * Renders every gallery photo with its saved edits and composes the sheet
 * page(s). Per-photo failures degrade to a labelled placeholder cell — one
 * unreadable file never kills the sheet. Runs on [Dispatchers.IO];
 * [onPhotoProgress] gets (done, total) from that background context.
 */
suspend fun buildContactSheets(
    container: AppContainer,
    gallery: ProofingGallery,
    options: ContactSheetOptions,
    glEngine: GlEngine,
    glHost: GlHost,
    onPhotoProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    shouldCancel: () -> Boolean = { false },
): Result<ContactSheetOutcome> = withContext(Dispatchers.IO) {
    val ids = gallery.photoIds
    if (ids.isEmpty()) return@withContext Result.failure(
        IllegalStateException("This gallery has no photos yet"))
    if (ids.size > MAX_CONTACT_SHEET_PHOTOS) return@withContext Result.failure(
        IllegalStateException(
            "Contact sheets cover up to $MAX_CONTACT_SHEET_PHOTOS photos " +
                "(${ids.size} here) — split the gallery first"))

    // Stand-in for the "preview" assets the render path swaps back in
    // afterwards. Contact-sheet rendering has no live preview session, so
    // these pixels are never shown (same recipe as batch export).
    val standIn = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    var rendered = 0
    var failed = 0
    try {
        val cells = ids.mapIndexed { i, id ->
            if (shouldCancel()) throw CancellationException()
            val cell = renderSheetCell(container, gallery, id, i + 1,
                glEngine, glHost, standIn)
            if (cell.bitmap != null) rendered++ else failed++
            onPhotoProgress(i + 1, ids.size)
            cell
        }
        if (shouldCancel()) throw CancellationException()

        val columns = contactSheetColumns(ids.size, options)
        val pages = contactSheetPages(ids.size, columns)
        val dateText = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
            .format(Date())
        val files = cells.chunked(PHOTOS_PER_SHEET_PAGE).mapIndexed { pageIdx, pageCells ->
            if (shouldCancel()) throw CancellationException()
            val layout = contactSheetLayout(pageCells.size, columns)
            val bmp = composeContactSheetPage(
                pageCells, layout, options,
                galleryName = gallery.name,
                clientName = gallery.clientName,
                metaLine = "$dateText · ${gallery.summary}",
                pageLine = if (pages > 1) "Page ${pageIdx + 1} of $pages" else null,
            )
            try {
                val file = File(container.context.cacheDir,
                    "contact_${UUID.randomUUID()}.jpg")
                FileOutputStream(file).use { out ->
                    if (!bmp.compress(Bitmap.CompressFormat.JPEG, 88, out))
                        throw IllegalStateException("Could not write the contact sheet")
                }
                file
            } finally {
                bmp.recycle()
                // This page's cell renders are done the moment the page is
                // composed: recycle them now instead of holding every photo
                // render in native memory until GC (a 480-photo sheet holds
                // ~780MB of cell bitmaps otherwise — OOM territory).
                pageCells.forEach { cell ->
                    runCatching {
                        cell.bitmap?.let { if (!it.isRecycled) it.recycle() }
                    }
                }
            }
        }
        // Name the files last: a crash mid-compose leaves no half-named files.
        val finalFiles = files.mapIndexed { pageIdx, file ->
            val target = File(file.parent,
                contactSheetFileName(gallery.name, pageIdx + 1, pages) + ".jpg")
            // renameTo can fail (e.g. a stale file with the same name);
            // keep the generated name rather than failing the whole sheet.
            if (runCatching { file.renameTo(target) }.getOrDefault(false) &&
                target.exists()
            ) target else file
        }
        Result.success(ContactSheetOutcome(finalFiles, pages, rendered, failed))
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        val msg = if (t is OutOfMemoryError)
            "Not enough memory to build the contact sheet — try fewer columns or close other apps"
        else t.message ?: "Could not build the contact sheet"
        Result.failure(IllegalStateException(msg, t))
    } finally {
        runCatching { if (!standIn.isRecycled) standIn.recycle() }
    }
}

private suspend fun renderSheetCell(
    container: AppContainer,
    gallery: ProofingGallery,
    id: String,
    index: Int,
    glEngine: GlEngine,
    glHost: GlHost,
    standIn: Bitmap,
): SheetCell {
    val img = try {
        container.library.byId(id)
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        null
    }
    val name = img?.displayName ?: "Photo $index"
    val uri = try {
        img?.contentUri?.let { android.net.Uri.parse(it) }
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        null
    }
    val mark = gallery.marks[id]
    if (uri == null) return SheetCell(id, name, null, mark, index)
    return try {
        val state: EditState = container.editStore.load(id)
        // Export masks at small size — the sheet shows the edited photo,
        // exactly like the library grid, so masks must apply.
        val masks = try {
            withContext(Dispatchers.Default) {
                state.masks.filter { it.enabled }.take(GlEngine.MAX_MASKS)
                    .map { m ->
                        val base = MaskEngine.rasterize(m, container.aiMasks)
                        Bitmap.createScaledBitmap(base, 512, 512, true)
                            .also { base.recycle() }
                    }
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            emptyList()
        }
        try {
            val bmp = container.exports.renderBitmap(
                ExportManager.Job(
                    sourceUri = uri,
                    state = state,
                    settings = ExportSettings(
                        format = ExportFormat.JPEG,
                        quality = 88,
                        keepMetadata = false,
                        embedColorProfile = false,
                        watermark = com.lumora.photoeditor.engine.WatermarkSettings(),
                    ),
                    previewBitmap = standIn,
                    previewMasks = emptyList(),
                    previewRetouch = null,
                    exportMasks = masks,
                    retouchSpots = state.retouch.spots,
                    glEngine = glEngine,
                    glHost = glHost,
                ),
                longEdge = CONTACT_SHEET_CELL_LONG_EDGE,
            ).getOrNull()
            SheetCell(id, name, bmp, mark, index)
        } finally {
            masks.forEach { runCatching { if (!it.isRecycled) it.recycle() } }
        }
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        SheetCell(id, name, null, mark, index)
    }
}

/**
 * Draws one sheet page: header (gallery, client, date, counts, page line),
 * then the photo grid with captions and optional picked/rejected badges.
 * The caller owns the returned bitmap.
 */
fun composeContactSheetPage(
    cells: List<SheetCell>,
    layout: ContactSheetLayout,
    options: ContactSheetOptions,
    galleryName: String,
    clientName: String?,
    metaLine: String,
    pageLine: String?,
): Bitmap {
    val bmp = Bitmap.createBitmap(layout.sheetW, layout.sheetH, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val paper = 0xFFFAFAF9.toInt()
    val cellBg = 0xFFE8E8E6.toInt()
    val ink = 0xFF1C1917.toInt()
    val inkSoft = 0xFF78716C.toInt()
    val pickedBg = 0xFF15803D.toInt()
    val rejectedBg = 0xFFB91C1C.toInt()
    c.drawColor(paper)

    fun textPaint(size: Float, bold: Boolean, color: Int): android.text.TextPaint =
        android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            this.color = color
        }

    // ---- header ----
    var y = layout.margin.toFloat()
    val titlePaint = textPaint(64f, true, ink)
    val title = if (galleryName.isBlank()) "Contact sheet" else galleryName
    c.drawText(
        TextUtils.ellipsize(title, titlePaint, layout.sheetW - layout.margin * 2f,
            TextUtils.TruncateAt.END).toString(),
        layout.margin.toFloat(), y + 64f, titlePaint)
    y += 96f
    val subPaint = textPaint(44f, false, inkSoft)
    val clientLine = if (clientName.isNullOrBlank()) metaLine
    else "For $clientName · $metaLine"
    c.drawText(
        TextUtils.ellipsize(clientLine, subPaint, layout.sheetW - layout.margin * 2f,
            TextUtils.TruncateAt.END).toString(),
        layout.margin.toFloat(), y + 44f, subPaint)
    y += 76f
    val footPaint = textPaint(40f, false, inkSoft)
    c.drawText("H2W Photo Lab", layout.margin.toFloat(), y + 40f, footPaint)
    if (pageLine != null) {
        val w = footPaint.measureText(pageLine)
        c.drawText(pageLine, layout.sheetW - layout.margin - w, y + 40f, footPaint)
    }
    y += 52f + layout.gap

    // ---- grid ----
    val capPaint = textPaint(44f, false, ink)
    val badgePaint = textPaint(36f, true, 0xFFFFFFFF.toInt())
    val badgeBg = Paint(Paint.ANTI_ALIAS_FLAG)
    val cellRect = RectF()
    for ((row, chunk) in cells.chunked(layout.columns).withIndex()) {
        val rowY = y + row * (layout.cellW + layout.captionH + layout.gap)
        for ((col, cell) in chunk.withIndex()) {
            val x = layout.margin + col * (layout.cellW + layout.gap)
            // photo area
            cellRect.set(x.toFloat(), rowY, (x + layout.cellW).toFloat(),
                rowY + layout.cellW)
            c.drawRect(cellRect, Paint().apply { color = cellBg })
            val src = cell.bitmap
            if (src != null && !src.isRecycled && src.width > 0 && src.height > 0) {
                // fit-center
                val scale = minOf(cellRect.width() / src.width,
                    cellRect.height() / src.height)
                val dw = src.width * scale
                val dh = src.height * scale
                val dx = cellRect.left + (cellRect.width() - dw) / 2f
                val dy = cellRect.top + (cellRect.height() - dh) / 2f
                c.drawBitmap(src, null,
                    RectF(dx, dy, dx + dw, dy + dh), Paint(Paint.FILTER_BITMAP_FLAG))
            } else {
                // placeholder for an unreadable photo — never a blank hole
                val ph = textPaint(40f, false, inkSoft)
                val label = "Unavailable"
                c.drawText(label, cellRect.centerX() - ph.measureText(label) / 2f,
                    cellRect.centerY() + 14f, ph)
            }
            // caption
            val capY = rowY + layout.cellW
            val caption = sheetCellCaption(cell.index, cell.displayName)
            var capW = layout.cellW.toFloat()
            val badge = if (options.showMarks) markBadgeText(cell.mark) else null
            if (badge != null) {
                val bw = badgePaint.measureText(badge) + 48f
                capW -= bw + 24f
                badgeBg.color = if (cell.mark == ProofMark.APPROVED) pickedBg else rejectedBg
                val bx = x + layout.cellW - bw
                val by = capY + 14f
                c.drawRoundRect(RectF(bx, by, bx + bw, by + 64f), 32f, 32f, badgeBg)
                c.drawText(badge, bx + 24f, by + 45f, badgePaint)
            }
            c.drawText(
                TextUtils.ellipsize(caption, capPaint, capW,
                    TextUtils.TruncateAt.END).toString(),
                x.toFloat(), capY + 62f, capPaint)
        }
    }
    // The cell bitmaps are owned by the caller: buildContactSheets recycles
    // each page's cells right after the page is composed (before encoding);
    // do not recycle them here.
    return bmp
}

/** Share intent for contact-sheet file(s): SEND or SEND_MULTIPLE. Never throws. */
fun contactSheetShareIntent(context: Context, files: List<File>): Intent? {
    return try {
        val uris = files.map { file ->
            FileProvider.getUriForFile(context,
                "${context.packageName}.fileprovider", file)
        }
        if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uris[0])
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "image/jpeg"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    } catch (_: Exception) {
        null
    }
}
