package com.lumora.photoeditor.engine

import org.junit.Assert.*
import org.junit.Test
import java.util.zip.CRC32
import java.util.zip.Inflater

/**
 * First-ever executed verification of [IccEmbed] — the pure-Kotlin ICC profile
 * embedding behind export color-profile tagging (java.util.zip only, so it
 * runs on a plain JVM harness).
 *
 * Pins the observable contract: profile sanity check, JPEG APP2 insertion
 * layout (marker/length/header/chunk-count/profile bytes), PNG iCCP chunk
 * layout (name/compression/deflated payload/CRC32), idempotency of both
 * embeds, and the never-throws "malformed input -> unchanged" guarantees.
 */
class IccEmbedTest {

    // ---------- fixtures ----------

    private fun validProfile(size: Int = 256): ByteArray {
        require(size >= 128)
        val p = ByteArray(size) { (it * 37 + 11).toByte() }
        p[0] = (size ushr 24).toByte(); p[1] = (size ushr 16).toByte()
        p[2] = (size ushr 8).toByte(); p[3] = size.toByte()
        p[36] = 'a'.code.toByte(); p[37] = 'c'.code.toByte()
        p[38] = 's'.code.toByte(); p[39] = 'p'.code.toByte()
        return p
    }

    private fun minimalJpeg(): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())

    private fun pngChunk(type: String, data: ByteArray): ByteArray {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        val crc = CRC32().also { it.update(typeBytes); it.update(data) }.value
        val out = ByteArray(12 + data.size)
        var p = 0
        fun putU32(v: Long) {
            out[p++] = (v ushr 24).toByte(); out[p++] = (v ushr 16).toByte()
            out[p++] = (v ushr 8).toByte(); out[p++] = v.toByte()
        }
        putU32(data.size.toLong())
        typeBytes.copyInto(out, p); p += 4
        data.copyInto(out, p); p += data.size
        putU32(crc)
        return out
    }

    private fun minimalPng(): ByteArray {
        val sig = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        )
        val ihdr = pngChunk("IHDR", ByteArray(13) { it.toByte() })
        val iend = pngChunk("IEND", ByteArray(0))
        return sig + ihdr + iend
    }

    private fun u32(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

    private fun inflate(src: ByteArray): ByteArray {
        val inf = Inflater()
        inf.setInput(src)
        val buf = ByteArray(1 shl 16)
        val n = inf.inflate(buf)
        inf.end()
        return buf.copyOf(n)
    }

    // ---------- profileLooksValid ----------

    @Test fun `profile too short is rejected`() {
        assertFalse(IccEmbed.profileLooksValid(ByteArray(127)))
        assertFalse(IccEmbed.profileLooksValid(ByteArray(0)))
    }

    @Test fun `profile at exactly 128 bytes with acsp is accepted`() {
        assertTrue(IccEmbed.profileLooksValid(validProfile(128)))
    }

    @Test fun `profile declared size must match actual size`() {
        val p = validProfile(256)
        p[0] = 0; p[1] = 0; p[2] = 0; p[3] = 128.toByte() // declares 128, is 256
        assertFalse(IccEmbed.profileLooksValid(p))
        val q = validProfile(256)
        q[3] = 1 // declares 257
        assertFalse(IccEmbed.profileLooksValid(q))
    }

    @Test fun `profile without acsp magic is rejected`() {
        val p = validProfile(256)
        p[36] = 'x'.code.toByte()
        assertFalse(IccEmbed.profileLooksValid(p))
    }

    // ---------- embedJpeg ----------

    @Test fun `embedJpeg non-jpeg returns input unchanged`() {
        val junk = "definitely not a jpeg".toByteArray()
        assertTrue(IccEmbed.embedJpeg(junk, validProfile()).contentEquals(junk))
        assertTrue(IccEmbed.embedJpeg(ByteArray(0), validProfile()).contentEquals(ByteArray(0)))
        assertTrue(IccEmbed.embedJpeg(byteArrayOf(0xFF.toByte()), validProfile())
            .contentEquals(byteArrayOf(0xFF.toByte())))
    }

    @Test fun `embedJpeg invalid profile returns input unchanged`() {
        val jpeg = minimalJpeg()
        assertTrue(IccEmbed.embedJpeg(jpeg, ByteArray(10)).contentEquals(jpeg))
    }

    @Test fun `embedJpeg oversize profile returns input unchanged`() {
        val jpeg = minimalJpeg()
        // 66000 > 65000 single-segment cap; must still pass profileLooksValid.
        val big = validProfile(66000)
        assertTrue(IccEmbed.profileLooksValid(big))
        assertTrue(IccEmbed.embedJpeg(jpeg, big).contentEquals(jpeg))
    }

    @Test fun `embedJpeg inserts well-formed APP2 right after SOI`() {
        val jpeg = minimalJpeg()
        val profile = validProfile(256)
        val out = IccEmbed.embedJpeg(jpeg, profile)

        val segSize = 2 + 2 + 12 + 2 + profile.size
        assertEquals(2 + segSize + jpeg.size - 2, out.size)

        // SOI preserved, APP2 marker immediately after.
        assertEquals(0xFF.toByte(), out[0]); assertEquals(0xD8.toByte(), out[1])
        assertEquals(0xFF.toByte(), out[2]); assertEquals(0xE2.toByte(), out[3])

        // Length field covers everything after the marker (two big-endian bytes).
        val segLen = segSize - 2
        val storedLen = ((out[4].toInt() and 0xFF) shl 8) or (out[5].toInt() and 0xFF)
        assertEquals(segLen, storedLen)

        // "ICC_PROFILE\0" header, chunk 1 of 1.
        val header = "ICC_PROFILE\u0000".toByteArray(Charsets.US_ASCII)
        assertTrue(out.copyOfRange(6, 18).contentEquals(header))
        assertEquals(1.toByte(), out[18])
        assertEquals(1.toByte(), out[19])

        // Profile bytes verbatim, then the untouched remainder of the JPEG.
        assertTrue(out.copyOfRange(20, 20 + profile.size).contentEquals(profile))
        assertTrue(out.copyOfRange(20 + profile.size, out.size)
            .contentEquals(jpeg.copyOfRange(2, jpeg.size)))
    }

    @Test fun `embedJpeg is idempotent`() {
        val jpeg = minimalJpeg()
        val once = IccEmbed.embedJpeg(jpeg, validProfile())
        assertFalse(once.contentEquals(jpeg)) // something was actually inserted
        assertTrue(IccEmbed.embedJpeg(once, validProfile()).contentEquals(once))
    }

    @Test fun `embedJpeg preserves a foreign APP2 segment`() {
        // An APP2 that is NOT an ICC segment must not block embedding.
        val foreign = byteArrayOf(
            0xFF.toByte(), 0xE2.toByte(), 0x00, 0x0A,
            'N'.code.toByte(), 'O'.code.toByte(), 'T'.code.toByte(),
            'I'.code.toByte(), 'C'.code.toByte(), 'C'.code.toByte(),
            0xFF.toByte(), 0xD9.toByte()
        )
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + foreign
        val out = IccEmbed.embedJpeg(jpeg, validProfile(128))
        assertEquals(0xE2.toByte(), out[3]) // our ICC APP2 went right after SOI
        // Foreign APP2 still present after our inserted segment.
        val tail = out.copyOfRange(out.size - foreign.size, out.size)
        assertTrue(tail.contentEquals(foreign))
    }

    // ---------- embedPng ----------

    @Test fun `embedPng non-png returns input unchanged`() {
        val junk = "not a png".toByteArray()
        assertTrue(IccEmbed.embedPng(junk, validProfile()).contentEquals(junk))
        assertTrue(IccEmbed.embedPng(ByteArray(0), validProfile()).contentEquals(ByteArray(0)))
    }

    @Test fun `embedPng invalid profile returns input unchanged`() {
        val png = minimalPng()
        assertTrue(IccEmbed.embedPng(png, ByteArray(10)).contentEquals(png))
    }

    @Test fun `embedPng inserts valid iCCP chunk right after IHDR`() {
        val png = minimalPng()
        val profile = validProfile(256)
        val out = IccEmbed.embedPng(png, profile)

        // IHDR ends at byte 33 in the minimal fixture (8 sig + 25 IHDR chunk).
        val ihdrEnd = 33
        assertTrue(out.copyOfRange(0, ihdrEnd).contentEquals(png.copyOfRange(0, ihdrEnd)))

        val dataLen = u32(out, ihdrEnd)
        assertTrue(dataLen > 6) // name + method + some deflated payload
        assertEquals("iCCP", String(out, ihdrEnd + 4, 4, Charsets.US_ASCII))

        val data = out.copyOfRange(ihdrEnd + 8, ihdrEnd + 8 + dataLen)
        assertEquals("sRGB", String(data, 0, 4, Charsets.US_ASCII))
        assertEquals(0.toByte(), data[4])
        assertEquals(0.toByte(), data[5]) // compression method: deflate

        // Deflated payload round-trips to the original profile.
        val deflated = data.copyOfRange(6, data.size)
        assertTrue(inflate(deflated).contentEquals(profile))

        // CRC32 over chunk type + data matches.
        val stored = u32(out, ihdrEnd + 8 + dataLen).toLong() and 0xFFFFFFFFL
        val computed = CRC32().also {
            it.update(out, ihdrEnd + 4, 4 + dataLen)
        }.value
        assertEquals(computed, stored)

        // Total size and the untouched remainder of the PNG.
        assertEquals(png.size + 12 + dataLen, out.size)
        assertTrue(out.copyOfRange(ihdrEnd + 12 + dataLen, out.size)
            .contentEquals(png.copyOfRange(ihdrEnd, png.size)))
    }

    @Test fun `embedPng is idempotent`() {
        val png = minimalPng()
        val once = IccEmbed.embedPng(png, validProfile())
        assertFalse(once.contentEquals(png))
        assertTrue(IccEmbed.embedPng(once, validProfile()).contentEquals(once))
    }

    @Test fun `embedPng malformed chunk length returns input unchanged`() {
        val sig = minimalPng().copyOfRange(0, 8)
        // Chunk claims a length far beyond the buffer.
        val bad = sig + pngChunkHeaderOnly("IHDR", 10_000)
        assertTrue(IccEmbed.embedPng(bad, validProfile()).contentEquals(bad))
    }

    @Test fun `embedPng without IHDR returns input unchanged`() {
        val sig = minimalPng().copyOfRange(0, 8)
        val noIhdr = sig + pngChunk("IEND", ByteArray(0))
        assertTrue(IccEmbed.embedPng(noIhdr, validProfile()).contentEquals(noIhdr))
    }

    private fun pngChunkHeaderOnly(type: String, len: Int): ByteArray {
        // length + type only (no data/CRC) — enough to trip the malformed guard.
        val out = ByteArray(8)
        out[0] = (len ushr 24).toByte(); out[1] = (len ushr 16).toByte()
        out[2] = (len ushr 8).toByte(); out[3] = len.toByte()
        type.toByteArray(Charsets.US_ASCII).copyInto(out, 4)
        return out
    }

    @Test fun `embedPng truncated input never throws`() {
        val sig = minimalPng().copyOfRange(0, 8)
        assertTrue(IccEmbed.embedPng(sig, validProfile()).contentEquals(sig))
        assertTrue(IccEmbed.embedPng(ByteArray(3), validProfile()).contentEquals(ByteArray(3)))
    }

    @Test fun `both embeds are deterministic`() {
        val profile = validProfile()
        val jpeg = minimalJpeg()
        val png = minimalPng()
        assertTrue(IccEmbed.embedJpeg(jpeg, profile).contentEquals(IccEmbed.embedJpeg(jpeg, profile)))
        assertTrue(IccEmbed.embedPng(png, profile).contentEquals(IccEmbed.embedPng(png, profile)))
    }
}
