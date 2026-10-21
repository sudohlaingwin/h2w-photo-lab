package com.lumora.photoeditor.data

import android.content.Context
import com.lumora.photoeditor.engine.LensCorrection
import com.lumora.photoeditor.engine.LensProfiles

/**
 * Persists per-lens distortion/defringe corrections (see [LensProfiles]).
 * Small key-value store in SharedPreferences — crash-hardened like the other
 * stores: a failed read returns null, a failed write is logged, never thrown.
 * The catches are Throwable, not just Exception, so an Error (e.g. OOM while
 * parsing a corrupt value) degrades the same way instead of escaping to the
 * caller. Plain synchronous functions — no coroutine cancellation to rethrow.
 */
class LensProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("lens_profiles", Context.MODE_PRIVATE)

    fun get(key: String): LensCorrection? = try {
        LensProfiles.parse(prefs.getString(key, null))
    } catch (t: Throwable) {
        android.util.Log.w("LensProfileStore", "read failed for $key", t)
        null
    }

    fun put(key: String, correction: LensCorrection) = try {
        prefs.edit().putString(key, LensProfiles.format(correction)).apply()
    } catch (t: Throwable) {
        android.util.Log.w("LensProfileStore", "write failed for $key", t)
    }

    fun remove(key: String) = try {
        prefs.edit().remove(key).apply()
    } catch (t: Throwable) {
        android.util.Log.w("LensProfileStore", "remove failed for $key", t)
    }
}
