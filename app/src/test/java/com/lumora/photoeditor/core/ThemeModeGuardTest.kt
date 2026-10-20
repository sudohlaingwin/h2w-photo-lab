package com.lumora.photoeditor.core

import org.junit.Assert.*
import org.junit.Test

/**
 * First-ever executed verification of the theme-mode range-guard contract.
 *
 * Background: a stray "theme_mode" pref value (backup restore, old build)
 * once reached the settings flow uncoerced while the setter coerced —
 * prefs/flow desync: the Settings selector showed no selection and
 * MainActivity's theme `when` silently fell through to the system default.
 * The fix routes BOTH the init read and the setter through
 * [ThemeModeGuard.coerce]; this pins the mapping on the real source and
 * (via the harness's structural pins) the routing in [AppSettings].
 */
class ThemeModeGuardTest {

    @Test fun validModesPassThroughUnchanged() {
        assertEquals(0, ThemeModeGuard.coerce(0)) // system
        assertEquals(1, ThemeModeGuard.coerce(1)) // dark
        assertEquals(2, ThemeModeGuard.coerce(2)) // light
    }

    @Test fun negativeStrayValuesClampToSystem() {
        assertEquals(0, ThemeModeGuard.coerce(-1))
        assertEquals(0, ThemeModeGuard.coerce(-100))
        assertEquals(0, ThemeModeGuard.coerce(Int.MIN_VALUE))
    }

    @Test fun overRangeStrayValuesClampToLight() {
        assertEquals(2, ThemeModeGuard.coerce(3))
        assertEquals(2, ThemeModeGuard.coerce(99))
        assertEquals(2, ThemeModeGuard.coerce(Int.MAX_VALUE))
    }

    @Test fun boundsMatchDocumentedRange() {
        assertEquals(0, ThemeModeGuard.MIN)
        assertEquals(2, ThemeModeGuard.MAX)
        // Every value inside the documented range is a fixed point.
        for (v in ThemeModeGuard.MIN..ThemeModeGuard.MAX) {
            assertEquals(v, ThemeModeGuard.coerce(v))
        }
    }

    @Test fun coercionIsIdempotent() {
        // Setter-then-init agreement depends on this: coercing twice must
        // equal coercing once, so the written pref and the flow always agree.
        val strays = intArrayOf(Int.MIN_VALUE, -7, -1, 0, 1, 2, 3, 42, Int.MAX_VALUE)
        for (v in strays) {
            val once = ThemeModeGuard.coerce(v)
            assertEquals(once, ThemeModeGuard.coerce(once))
        }
    }
}
