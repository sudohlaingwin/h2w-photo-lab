package com.lumora.photoeditor.core

/**
 * Range-guard policy for the persisted theme-mode setting
 * (0 = system, 1 = dark, 2 = light).
 *
 * A stray pref value (backup restore, old build, hand-edited XML) must
 * never land unvalidated in the flow: the Settings selector would show no
 * selection and MainActivity's theme `when` would silently fall through to
 * the system default while prefs claimed otherwise (prefs/flow desync).
 *
 * The contract: init AND setter apply the SAME coercion. Both call sites in
 * [AppSettings] route through [coerce]; the harness test pins the mapping
 * and the routing.
 */
object ThemeModeGuard {
    const val MIN = 0
    const val MAX = 2

    /** Coerces any stored/passed value into the valid 0..2 range. */
    fun coerce(v: Int): Int = v.coerceIn(MIN, MAX)
}
