package com.lumora.photoeditor.data

import android.content.Context
import com.lumora.photoeditor.engine.LensCorrection
import com.lumora.photoeditor.engine.LensProfiles

/**
 * Persists per-lens distortion/defringe corrections (see [LensProfiles]).
 * Small key-value store in SharedPreferences — crash-hardened like the other
 * stores: a failed read returns null, a failed write is logged, never thrown.
 */
class LensProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("lens_profiles", Context.MODE_PRIVATE)

    fun get(key: String): LensCorrection? = try {
        LensProfiles.parse(prefs.getString(key, null))
    } catch (e: Exception) {
        android.util.Log.w("LensProfileStore", "read failed for $key", e)
        null
    }

    fun put(key: String, correction: LensCorrection) = try {
        prefs.edit().putString(key, LensProfiles.format(correction)).apply()
    } catch (e: Exception) {
        android.util.Log.w("LensProfileStore", "write failed for $key", e)
    }

    fun remove(key: String) = try {
        prefs.edit().remove(key).apply()
    } catch (e: Exception) {
        android.util.Log.w("LensProfileStore", "remove failed for $key", e)
    }
}
