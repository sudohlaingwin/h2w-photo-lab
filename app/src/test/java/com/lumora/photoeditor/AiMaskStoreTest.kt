package com.lumora.photoeditor

import com.lumora.photoeditor.engine.AiMaskFormat
import org.junit.Assert.*
import org.junit.Test

/**
 * Regression test for the AI mask store pixel-format bug.
 *
 * Mask rasters carry the value in the ALPHA channel, and load() recovers it
 * with Bitmap.copy(ALPHA_8) — which keeps alpha and drops color. The old
 * save() wrote the value into gray RGB with alpha=0xFF, so every stored AI
 * mask (Subject / Background / Skin / Sky / Color / Luminance range) loaded
 * back as 255 everywhere and applied at full strength to the whole image.
 *
 * These tests pin the contract: pack() must put the value in alpha (never in
 * RGB), and unpack(pack(a)) must round-trip exactly.
 */
class AiMaskStoreTest {

    @Test
    fun pack_roundTrips_allValues() {
        for (a in 0..255) {
            assertEquals("round-trip failed for $a", a, AiMaskFormat.unpack(AiMaskFormat.pack(a)))
        }
    }

    @Test
    fun pack_storesValueInAlphaOnly() {
        val px = AiMaskFormat.pack(80)
        // Alpha byte carries the value ...
        assertEquals(80, (px ushr 24) and 0xFF)
        // ... and RGB stays white so nothing leaks into color channels.
        assertEquals(0x00FFFFFF, px and 0x00FFFFFF)
    }

    @Test
    fun pack_clampsOutOfRange() {
        assertEquals(0, AiMaskFormat.unpack(AiMaskFormat.pack(-5)))
        assertEquals(255, AiMaskFormat.unpack(AiMaskFormat.pack(300)))
    }

    @Test
    fun unpack_readsAlphaChannel() {
        // Simulates what the PNG decoder hands back for a stored pixel:
        // value in alpha, white RGB.
        val decoded = (123 shl 24) or 0x00FFFFFF
        assertEquals(123, AiMaskFormat.unpack(decoded))
    }

    @Test
    fun oldGrayFormat_wouldLoadAsFullWhite() {
        // Documents the old bug: value baked into gray RGB with alpha=0xFF.
        // copy(ALPHA_8) keeps only alpha -> the mask reads as 255 everywhere,
        // regardless of the true value. This is what the fix eliminates.
        val oldPixel = (0xFF shl 24) or (80 shl 16) or (80 shl 8) or 80
        assertEquals(
            "old format must read back as full-white (the bug)",
            255, AiMaskFormat.unpack(oldPixel)
        )
    }
}
