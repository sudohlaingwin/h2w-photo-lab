package com.lumora.photoeditor.engine

import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Embeds an ICC color profile into already-encoded image bytes.
 *
 * JPEG: inserts a single APP2 segment ("ICC_PROFILE") right after SOI, per the
 * ICC spec for JPEG embedding. PNG: inserts an iCCP chunk right after IHDR,
 * per the PNG spec (profile name "sRGB", deflate compression).
 *
 * Pure Kotlin — the only imports are java.util.zip, so this file compiles and
 * runs on a plain JVM for the standalone harness. Every public function is
 * total: any malformed input (or a second call on already-tagged bytes)
 * returns the input unchanged, never throws. ExportManager additionally wraps
 * the call so a profile failure can never break an export.
 */
object IccEmbed {

    private val PNG_SIG = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )
    private val ICC_JPEG_HEADER = "ICC_PROFILE\u0000".toByteArray(Charsets.US_ASCII)

    /** Minimal sanity check for ICC profile bytes: header size + "acsp" magic. */
    fun profileLooksValid(profile: ByteArray): Boolean {
        if (profile.size < 128) return false
        val declared = ((profile[0].toInt() and 0xFF) shl 24) or
            ((profile[1].toInt() and 0xFF) shl 16) or
            ((profile[2].toInt() and 0xFF) shl 8) or
            (profile[3].toInt() and 0xFF)
        if (declared != profile.size) return false
        return profile[36] == 'a'.code.toByte() && profile[37] == 'c'.code.toByte() &&
            profile[38] == 's'.code.toByte() && profile[39] == 'p'.code.toByte()
    }

    /**
     * Returns [jpeg] with the ICC profile embedded, or [jpeg] unchanged when
     * it is not a JPEG, already carries an ICC APP2 segment, or anything is
     * off. Single APP2 segment (profiles up to ~64KB; ours is ~7KB).
     */
    fun embedJpeg(jpeg: ByteArray, profile: ByteArray): ByteArray {
        try {
            if (!profileLooksValid(profile)) return jpeg
            if (jpeg.size < 2 || jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) return jpeg
            if (profile.size > 65000) return jpeg
            if (hasJpegIcc(jpeg)) return jpeg

            val seg = ByteArray(2 + 2 + ICC_JPEG_HEADER.size + 2 + profile.size)
            var p = 0
            seg[p++] = 0xFF.toByte(); seg[p++] = 0xE2.toByte() // APP2
            val segLen = seg.size - 2 // length field covers everything after the marker
            seg[p++] = (segLen ushr 8).toByte(); seg[p++] = segLen.toByte()
            ICC_JPEG_HEADER.copyInto(seg, p); p += ICC_JPEG_HEADER.size
            seg[p++] = 1 // chunk sequence: 1 of 1
            seg[p++] = 1 // chunk count
            profile.copyInto(seg, p)

            val out = ByteArray(2 + seg.size + jpeg.size - 2)
            jpeg.copyInto(out, 0, 0, 2)          // SOI
            seg.copyInto(out, 2)                 // APP2/ICC
            jpeg.copyInto(out, 2 + seg.size, 2)  // rest
            return out
        } catch (_: Throwable) {
            // Totality includes allocation failures: an OutOfMemoryError
            // building the tagged copy must still return the input
            // unchanged (export proceeds untagged) rather than fail it.
            return jpeg
        }
    }

    /**
     * Returns [png] with an iCCP chunk (deflated profile) inserted right after
     * IHDR, or [png] unchanged when it is not a PNG, already has iCCP, or is
     * malformed.
     */
    fun embedPng(png: ByteArray, profile: ByteArray): ByteArray {
        try {
            if (!profileLooksValid(profile)) return png
            if (png.size < 8 || !png.copyOfRange(0, 8).contentEquals(PNG_SIG)) return png

            // Walk chunks to find the end of IHDR and rule out an existing iCCP.
            var pos = 8
            var ihdrEnd = -1
            while (pos + 8 <= png.size) {
                val len = u32(png, pos)
                if (len > png.size - pos - 12) return png // malformed
                val type = String(png, pos + 4, 4, Charsets.US_ASCII)
                if (type == "iCCP") return png // already tagged
                val next = pos + 12 + len
                if (type == "IHDR") ihdrEnd = next
                pos = next
                if (type == "IEND") break
            }
            if (ihdrEnd < 0) return png

            val deflated = deflate(profile)
            val name = "sRGB\u0000".toByteArray(Charsets.US_ASCII)
            val data = ByteArray(name.size + 1 + deflated.size)
            name.copyInto(data, 0)
            data[name.size] = 0 // compression method: deflate
            deflated.copyInto(data, name.size + 1)

            val typeBytes = "iCCP".toByteArray(Charsets.US_ASCII)
            val crc = CRC32().also { it.update(typeBytes); it.update(data) }.value
            val chunk = ByteArray(12 + data.size)
            var p = 0
            fun putU32(v: Long) {
                chunk[p++] = (v ushr 24).toByte(); chunk[p++] = (v ushr 16).toByte()
                chunk[p++] = (v ushr 8).toByte(); chunk[p++] = v.toByte()
            }
            putU32(data.size.toLong())
            typeBytes.copyInto(chunk, p); p += 4
            data.copyInto(chunk, p); p += data.size
            putU32(crc)

            val out = ByteArray(png.size + chunk.size)
            png.copyInto(out, 0, 0, ihdrEnd)
            chunk.copyInto(out, ihdrEnd)
            png.copyInto(out, ihdrEnd + chunk.size, ihdrEnd)
            return out
        } catch (_: Throwable) {
            // Same totality as embedJpeg: allocation failure returns the
            // input unchanged so the export proceeds untagged.
            return png
        }
    }

    /** True when the JPEG already contains an APP2 segment with the ICC header. */
    private fun hasJpegIcc(jpeg: ByteArray): Boolean {
        var pos = 2
        while (pos + 4 <= jpeg.size) {
            if (jpeg[pos] != 0xFF.toByte()) return false
            val marker = jpeg[pos + 1].toInt() and 0xFF
            // Standalone markers carry no length.
            if (marker == 0xD8 || marker == 0xD9 || marker == 0x01 ||
                (marker in 0xD0..0xD7)
            ) {
                pos += 2
                continue
            }
            if (marker == 0xDA) return false // SOS: entropy data follows, stop
            val len = ((jpeg[pos + 2].toInt() and 0xFF) shl 8) or
                (jpeg[pos + 3].toInt() and 0xFF)
            if (len < 2 || pos + 2 + len > jpeg.size) return false
            if (marker == 0xE2 && len >= 2 + ICC_JPEG_HEADER.size) {
                var match = true
                for (i in ICC_JPEG_HEADER.indices) {
                    if (jpeg[pos + 4 + i] != ICC_JPEG_HEADER[i]) { match = false; break }
                }
                if (match) return true
            }
            pos += 2 + len
        }
        return false
    }

    private fun u32(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

    private fun deflate(src: ByteArray): ByteArray {
        val d = Deflater(Deflater.DEFAULT_COMPRESSION)
        d.setInput(src)
        d.finish()
        val buf = ByteArray(src.size + 64)
        val n = d.deflate(buf)
        d.end()
        return buf.copyOf(n)
    }
}
