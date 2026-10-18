package com.lumora.photoeditor.ai

import android.content.Context
import android.content.SharedPreferences

/**
 * Stores the user's Gemini API key on-device in app-private SharedPreferences.
 * Note: for a production release, prefer EncryptedSharedPreferences.
 */
class AiKeyStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("ai_keys", Context.MODE_PRIVATE)

    fun getGeminiKey(): String? =
        prefs.getString(KEY_GEMINI, null)?.takeIf { it.isNotBlank() }

    fun setGeminiKey(key: String) {
        prefs.edit().putString(KEY_GEMINI, key.trim()).apply()
    }

    fun clearGeminiKey() {
        prefs.edit().remove(KEY_GEMINI).apply()
    }

    fun hasKey(): Boolean = getGeminiKey() != null

    companion object {
        private const val KEY_GEMINI = "gemini_api_key"
    }
}
