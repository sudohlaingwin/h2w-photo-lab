package com.lumora.photoeditor.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.nio.ByteOrder

/**
 * RAW program Phase 1 slice 2: DNG decoding through the native LibRaw
 * bridge (NativeLib.dngProbe / dngDecode / dngFreeBuffer).
 *
 * This is the Kotlin seam between the file system and the editor: [decodeFile]
 * turns a .dng path into the ARGB_8888 Bitmap the existing GPU pipeline
 * already knows how to render, so non-destructive edits apply on top of a
 * RAW decode exactly as they do on a JPEG.
 *
 * Slice-2 scope, stated plainly (full detail in native-lib.cpp): decode is
 * half sensor resolution, 8-bit sRGB, auto white balance. That matches the
 * app's 8-bit OpenGL ES 3.0 pipeline; full-res / 16-bit linear output needs
 * the float pipeline of a later slice.
 *
 * Phase 1 slice 3: lossy-JPEG-compressed DNGs (some Samsung Expert RAW
 * shots) get a real answer instead of a dead end. LibRaw cannot decode
 * their raw tiles (the NDK ships no libjpeg, so USE_JPEG is deliberately
 * not linked), but the camera embedded a full preview JPEG in the DNG —
 * the thumbnail IFD is independent of the tile decoder. [decodeFile] now
 * routes those files through [NativeLib.dngPreview] + BitmapFactory and
 * sets [usedEmbeddedPreview], so the UI can tell the user plainly: you are
 * editing the camera's embedded preview, not a fresh RAW decode. That is
 * the camera's own rendered image, not a fake or an upscale — the honest
 * alternative to refusing the file.
 *
 * Every public entry is defensive: nothing here throws. [decodeFile] returns
 * null on any failure and records the human-readable reason in [lastError]
 * so the UI can show it instead of a generic "could not decode" message.
 *
 * RAW program Phase 2 slice 1: the native LibRaw bridge sniffs the file's
 * magic bytes (not its extension), so every proprietary RAW format in
 * LibRaw 0.22.2's camera database decodes through the SAME path as DNG —
 * Canon CR2/CR3, Nikon NEF/NRW, Sony ARW/SR2/SRF, Fujifilm RAF, Olympus
 * ORF, Panasonic RW2, Pentax PEF, Leica RWL, Sigma X3F, Hasselblad 3FR,
 * Kodak DCR/KDC, Mamiya MEF, Leaf MOS, Phase One IIQ. [isRaw] is the single
 * extension gate for the whole family; the lossy-JPEG embedded-preview
 * fallback stays DNG-specific (only DNG tiles arrive lossy-JPEG-compressed,
 * and only those get the probe's LOSSYJPEG tag). Unsupported cameras or
 * codecs surface LibRaw's own error through the probe — never invented.
 */
object RawDecoder {

    /** Human-readable reason for the most recent [decodeFile] null. */
    var lastError: String? = null
        private set

    /**
     * Phase 1 slice 3: true when the most recent successful [decodeFile]
     * returned the camera's embedded preview (lossy-JPEG DNG fallback)
     * instead of a LibRaw decode. Reset on every [decodeFile] call.
     */
    var usedEmbeddedPreview: Boolean = false
        private set

    /** True for .dng file names (case-insensitive), false for null/other. */
    fun isDng(fileName: String?): Boolean =
        fileName?.endsWith(".dng", ignoreCase = true) == true

    /**
     * RAW program Phase 2 slice 1: the proprietary RAW extensions LibRaw
     * 0.22.2's camera database covers — the set [isRaw] recognizes.
     * Kept lowercase; matching is case-insensitive. Conservative on
     * purpose: only formats with proven LibRaw 0.22.2 support are listed.
     */
    val rawExtensions: Set<String> = setOf(
        "dng", // Adobe DNG (incl. phone RAW: Expert RAW, ProRAW, Leica, Pentax)
        "cr2", "cr3", // Canon
        "nef", "nrw", // Nikon (not HE/HE* lossy — LibRaw reports those honestly)
        "arw", "sr2", "srf", // Sony
        "raf", // Fujifilm
        "orf", // Olympus / OM System
        "rw2", // Panasonic / Leica (some)
        "rwl", // Leica
        "pef", // Pentax / Ricoh
        "3fr", // Hasselblad
        "dcr", "kdc", // Kodak
        "mef", // Mamiya
        "mos", // Leaf
        "x3f", // Sigma
        "iiq", // Phase One
    )

    /**
     * Phase 2 slice 1: true for any supported RAW extension
     * (case-insensitive), false for null/other. The single gate the editor
     * uses to route a photo into the native LibRaw decode path.
     */
    fun isRaw(fileName: String?): Boolean {
        val ext = fileName?.substringAfterLast('.', "")?.lowercase() ?: return false
        return ext.isNotEmpty() && ext in rawExtensions
    }

    /**
     * Phase 2 slice 1: the format label for a RAW file name — "CR2",
     * "NEF", "DNG", ... — or null when the file is not a recognized RAW.
     * Used by the UI caption so the user sees which format they opened.
     */
    fun rawFormatName(fileName: String?): String? {
        val ext = fileName?.substringAfterLast('.', "")?.lowercase()
        return if (!ext.isNullOrEmpty() && ext in rawExtensions) ext.uppercase() else null
    }

    /**
     * Phase 1 slice 3: true when a [NativeLib.dngProbe] result is the
     * lossy-JPEG contract "ERR LOSSYJPEG <reason>". Pure function,
     * unit-tested.
     */
    fun isLossyJpegProbe(probe: String): Boolean =
        probe.startsWith("ERR LOSSYJPEG ")

    /**
     * Parses a [NativeLib.dngProbe] result: "OK <w> <h>" -> dimensions,
     * anything else ("ERR ...", "ERR LOSSYJPEG ...", garbage) -> null.
     * Pure function, unit-tested.
     */
    fun parseProbe(probe: String): Pair<Int, Int>? {
        val parts = probe.trim().split(Regex("\\s+"))
        if (parts.size != 3 || parts[0] != "OK") return null
        val w = parts[1].toIntOrNull() ?: return null
        val h = parts[2].toIntOrNull() ?: return null
        return if (w > 0 && h > 0) w to h else null
    }

    /**
     * Decodes the RAW file at [path] into an ARGB_8888 Bitmap, or null on any
     * failure (reason in [lastError]). Never throws — a missing native
     * bridge, a corrupt file, and even OutOfMemoryError all become null.
     * On success, [usedEmbeddedPreview] tells whether the bitmap is a
     * fresh RAW decode or the camera's embedded preview (slice 3).
     */
    fun decodeFile(path: String): Bitmap? {
        lastError = null
        usedEmbeddedPreview = false
        return try {
            decodeOrThrow(path)
        } catch (t: Throwable) {
            lastError = t.message ?: "RAW decode failed"
            usedEmbeddedPreview = false
            null
        }
    }

    /**
     * Builds the ARGB_8888 Bitmap from a native decode buffer:
     * [int width][int height][width*height RGBA8888 bytes] in native byte
     * order — the exact layout native-lib.cpp documents. Returns null when
     * the layout is corrupt. Pure function, unit-tested (rawdecode-harness).
     */
    fun bitmapFromBuffer(buf: java.nio.ByteBuffer): Bitmap? {
        buf.order(ByteOrder.nativeOrder())
        if (buf.remaining() < 8) return null
        val w = buf.int
        val h = buf.int
        val px = w.toLong() * h.toLong()
        if (w <= 0 || h <= 0 || buf.remaining().toLong() != px * 4) return null
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(buf)
        return bmp
    }

    private fun decodeOrThrow(path: String): Bitmap? {
        if (!NativeLib.isAvailable) {
            lastError = "Native RAW bridge unavailable on this device"
            return null
        }
        if (!File(path).isFile) {
            lastError = "RAW file not found"
            return null
        }
        val probe = NativeLib.dngProbe(path)
        if (parseProbe(probe) == null) {
            // Phase 1 slice 3: lossy-JPEG DNGs cannot be raw-decoded, but
            // they are not dead ends — the camera embedded a preview JPEG.
            if (isLossyJpegProbe(probe)) return decodeEmbeddedPreview(path)
            lastError = probe.removePrefix("ERR ").ifBlank { "Unreadable RAW file" }
            return null
        }
        val buf = NativeLib.dngDecode(path)
            ?: run { lastError = "RAW pixel decode failed"; return null }
        try {
            return bitmapFromBuffer(buf)
                ?: run { lastError = "Corrupt RAW decode buffer"; null }
        } finally {
            // The buffer is native malloc'd memory (see native-lib.cpp):
            // always release it once the pixels are copied out.
            NativeLib.dngFreeBuffer(buf)
        }
    }

    /**
     * Phase 1 slice 3 (generalized Phase 2): embedded-preview fallback for
     * any RAW file whose raw tiles LibRaw cannot unpack (lossy-JPEG DNGs
     * are the common case, but the native probe tags any
     * LIBRAW_FILE_UNSUPPORTED the same way). Extracts the camera's
     * embedded preview JPEG via [NativeLib.dngPreview] and decodes it with
     * BitmapFactory, guaranteeing an ARGB_8888 result for the GPU pipeline.
     * Sets [usedEmbeddedPreview] on success so the UI can label the photo
     * honestly; on failure records a specific reason in [lastError] and
     * returns null. Never throws.
     */
    private fun decodeEmbeddedPreview(path: String): Bitmap? {
        val jpeg = try {
            NativeLib.dngPreview(path)
        } catch (t: Throwable) {
            null
        }
        if (jpeg == null || jpeg.isEmpty()) {
            lastError = "LibRaw cannot decode the raw data " +
                "and this file has no readable embedded preview"
            return null
        }
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = try {
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
        } catch (t: Throwable) {
            null
        }
        if (decoded == null) {
            lastError = "The camera's embedded preview is not a readable image"
            return null
        }
        // BitmapFactory honors inPreferredConfig for JPEG, but guarantee the
        // contract defensively — the GL pipeline assumes ARGB_8888.
        if (decoded.config == Bitmap.Config.ARGB_8888) {
            usedEmbeddedPreview = true
            return decoded
        }
        val converted = try {
            decoded.copy(Bitmap.Config.ARGB_8888, false)
        } catch (t: Throwable) {
            null
        } finally {
            decoded.recycle()
        }
        if (converted == null) {
            lastError = "Could not prepare the camera's embedded preview"
            return null
        }
        usedEmbeddedPreview = true
        return converted
    }
}
