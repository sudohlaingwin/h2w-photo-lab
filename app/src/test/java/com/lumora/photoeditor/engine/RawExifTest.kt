package com.lumora.photoeditor.engine

import org.junit.Assert.*
import org.junit.Test

/**
 * First-ever executed verification of [parseRawExif] — the pure-Kotlin
 * parser for the native `rawExif()` wire line behind the Details dialog's
 * proprietary RAW camera info (ISO, shutter, aperture, focal, capture
 * time), shipped 2026-10-17 for his Canon R50.
 *
 * The source KDoc promises: well-formed OK lines parse fully; anything
 * else returns null ("ERR ...", null/empty, wrong field count); garbage
 * numerics degrade to the -1.0 / 0L "unknown" sentinels instead of failing
 * the parse, so one bad field never hides the good ones. These pins lock
 * that contract: a corrupt native line must never crash the details UI or
 * leak a partial parse into the wrong fields.
 */
class RawExifTest {

    private val good =
        "OK\tCanon\tEOS R50\t100\t0.004\t2.8\t50\t1729200000"

    @Test
    fun wellFormedLine_parsesAllFields() {
        val info = parseRawExif(good)
        assertNotNull(info)
        info!!
        assertEquals("Canon", info.make)
        assertEquals("EOS R50", info.model)
        assertEquals(100.0, info.iso, 1e-9)
        assertEquals(0.004, info.shutterSeconds, 1e-12)
        assertEquals(2.8, info.aperture, 1e-9)
        assertEquals(50.0, info.focalMm, 1e-9)
        assertEquals(1729200000L, info.epochSeconds)
    }

    @Test
    fun null_empty_blank_notOkPrefix_returnNull() {
        assertNull(parseRawExif(null))
        assertNull(parseRawExif(""))
        assertNull(parseRawExif("   "))
        assertNull(parseRawExif("ERR\tno exif"))
        assertNull(parseRawExif("ERR no exif at all"))
        // Wrong prefix shape: space instead of tab after OK
        assertNull(parseRawExif("OK Canon\tEOS R50\t100\t0.004\t2.8\t50\t1729200000"))
        // Lowercase ok is not OK
        assertNull(parseRawExif("ok\tCanon\tEOS R50\t100\t0.004\t2.8\t50\t1729200000"))
        assertNull(parseRawExif("OKAY\tCanon\tEOS R50\t100\t0.004\t2.8\t50\t1729200000"))
    }

    @Test
    fun wrongFieldCount_returnsNull() {
        // 7 fields
        assertNull(parseRawExif("OK\tCanon\tEOS R50\t100\t0.004\t2.8\t50"))
        // 9 fields
        assertNull(parseRawExif("OK\tCanon\tEOS R50\t100\t0.004\t2.8\t50\t1729200000\textra"))
        // Bare OK with no fields at all
        assertNull(parseRawExif("OK"))
    }

    @Test
    fun garbageNumerics_degradeToUnknownSentinels_notNull() {
        val info = parseRawExif("OK\tSony\tA7 IV\tabc\t-\tNaN\tInfinity\txyz")
        assertNotNull(info)
        info!!
        assertEquals("Sony", info.make)
        assertEquals("A7 IV", info.model)
        assertEquals(-1.0, info.iso, 1e-9)
        assertEquals(-1.0, info.shutterSeconds, 1e-9)
        assertEquals(-1.0, info.aperture, 1e-9)   // NaN rejected
        assertEquals(-1.0, info.focalMm, 1e-9)    // Infinity rejected
        assertEquals(0L, info.epochSeconds)       // "xyz" rejected
    }

    @Test
    fun oneBadField_doesNotHideTheGoodOnes() {
        val info = parseRawExif("OK\tCanon\tEOS R50\tgarbage\t0.01\t1.8\t50\t1729200000")
        assertNotNull(info)
        info!!
        assertEquals(-1.0, info.iso, 1e-9)                 // bad field -> sentinel
        assertEquals(0.01, info.shutterSeconds, 1e-12)    // good fields survive
        assertEquals(1.8, info.aperture, 1e-9)
        assertEquals(50.0, info.focalMm, 1e-9)
        assertEquals(1729200000L, info.epochSeconds)
    }

    @Test
    fun negativeNumerics_areUnknown() {
        val info = parseRawExif("OK\tNikon\tZ6\t-100\t-0.5\t-2.8\t50\t1729200000")
        assertNotNull(info)
        info!!
        assertEquals(-1.0, info.iso, 1e-9)
        assertEquals(-1.0, info.shutterSeconds, 1e-9)
        assertEquals(-1.0, info.aperture, 1e-9)
    }

    @Test
    fun timestamp_zeroNegativeGarbage_degradeToZero() {
        assertEquals(0L, parseRawExif("OK\tCanon\tEOS R50\t100\t0.004\t2.8\t50\t0")!!.epochSeconds)
        assertEquals(0L, parseRawExif("OK\tCanon\tEOS R50\t100\t0.004\t2.8\t50\t-5")!!.epochSeconds)
        assertEquals(0L, parseRawExif("OK\tCanon\tEOS R50\t100\t0.004\t2.8\t50\tnone")!!.epochSeconds)
        assertEquals(1729200000L, parseRawExif("OK\tCanon\tEOS R50\t100\t0.004\t2.8\t50\t1729200000")!!.epochSeconds)
    }

    @Test
    fun whitespaceAroundNumerics_isTrimmed() {
        val info = parseRawExif("OK\tCanon\tEOS R50\t  100  \t 0.004\t2.8\t50\t 1729200000 ")
        assertNotNull(info)
        info!!
        assertEquals(100.0, info.iso, 1e-9)
        assertEquals(0.004, info.shutterSeconds, 1e-12)
        assertEquals(1729200000L, info.epochSeconds)
    }

    @Test
    fun zeroNumerics_areValidNotUnknown() {
        // 0 is a legal value (>= 0); the sentinel is -1.0 only.
        val info = parseRawExif("OK\tCanon\tEOS R50\t0\t0\t0\t0\t1729200000")
        assertNotNull(info)
        info!!
        assertEquals(0.0, info.iso, 1e-9)
        assertEquals(0.0, info.shutterSeconds, 1e-9)
    }

    @Test
    fun makeModel_preservedVerbatim() {
        val info = parseRawExif("OK\tCanon Inc.\tCanon EOS R50 m\t100\t0.004\t2.8\t50\t1729200000")
        assertNotNull(info)
        info!!
        assertEquals("Canon Inc.", info.make)
        assertEquals("Canon EOS R50 m", info.model)
    }

    @Test
    fun deterministic() {
        val a = parseRawExif(good)
        val b = parseRawExif(good)
        assertEquals(a, b)
    }
}
