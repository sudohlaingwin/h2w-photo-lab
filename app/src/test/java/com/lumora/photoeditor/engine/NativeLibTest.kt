package com.lumora.photoeditor.engine

import org.junit.Assert.*
import org.junit.Test

/**
 * First-ever executed verification of [NativeLib]'s defensive-loading
 * contract — the thin JNI shim between the app and libnative-lib.so /
 * libraw.so. The source KDoc promises: if the .so is ever missing from a
 * build, [NativeLib.isAvailable] is false and [NativeLib.describe] /
 * [NativeLib.describeRaw] report the cause instead of throwing
 * UnsatisfiedLinkError into the caller.
 *
 * The whole contract executes on a plain JVM precisely BECAUSE the .so
 * can never load there: System.loadLibrary("native-lib") always throws
 * UnsatisfiedLinkError on the host, so the host run exercises exactly the
 * degraded branch a broken build would hit on-device. If someone removes
 * the try/catch around loadLibrary, the object initializer throws
 * ExceptionInInitializerError and every test here fails; if someone calls
 * bridgeInfo()/librawVersion() unconditionally in describe()/describeRaw(),
 * the UnsatisfiedLinkError escapes and the never-throw pins fail. Either
 * regression is a startup-crash bug caught here instead of on his phone.
 */
class NativeLibTest {

    @Test
    fun objectInitialization_neverThrowsWhenLibraryMissing() {
        // Touching the object at all runs the init block; reaching this
        // line proves the missing .so did not kill class initialization.
        val available = NativeLib.isAvailable
        assertFalse(
            "native-lib.so cannot load on a host JVM — the test " +
                "documents the degraded branch, not a working load",
            available,
        )
    }

    @Test
    fun isAvailable_isStableAcrossReads() {
        val first = NativeLib.isAvailable
        val second = NativeLib.isAvailable
        assertEquals(first, second)
        assertFalse(first)
    }

    @Test
    fun describe_neverThrows_reportsLoadFailure() {
        val msg = try {
            NativeLib.describe()
        } catch (t: Throwable) {
            fail("describe() must never throw, threw $t")
            return
        }
        assertTrue(
            "describe() must name the missing library, got: $msg",
            msg.startsWith("native-lib load failed:"),
        )
        // The underlying load error must ride along so a crash report or
        // the self-test dialog says WHY the bridge is down.
        assertTrue(
            "describe() must carry the load cause, got: $msg",
            msg.length > "native-lib load failed:".length,
        )
    }

    @Test
    fun describeRaw_neverThrows_reportsLibrawUnavailable() {
        val msg = try {
            NativeLib.describeRaw()
        } catch (t: Throwable) {
            fail("describeRaw() must never throw, threw $t")
            return
        }
        assertEquals(
            "libraw unavailable (native-lib failed to load)",
            msg,
        )
    }

    @Test
    fun describe_isRepeatableWithoutThrowing() {
        repeat(3) {
            NativeLib.describe()
            NativeLib.describeRaw()
        }
    }
}
