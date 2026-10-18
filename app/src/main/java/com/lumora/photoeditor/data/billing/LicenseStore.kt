package com.lumora.photoeditor.data.billing

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Phase D#9 slice 1 — persisted license/trial state. SharedPreferences,
 * observable, and never throwing: a storage hiccup degrades to a fresh
 * trial, never a crash on the settings screen.
 *
 * Honest scope, stated again: this records trial start and license keys
 * and reports the state. No store is connected, no prices exist, no
 * purchase flow runs — [activateKey] only stores a well-formed key issued
 * by the photographer's future store.
 */
class LicenseStore(context: Context) {

    private val prefs =
        context.getSharedPreferences("lumora_license", Context.MODE_PRIVATE)

    private val _status = MutableStateFlow(loadStatus())
    val status: StateFlow<LicenseStatus> = _status.asStateFlow()

    /** Stamps the trial start once, on first launch. Idempotent. */
    fun recordFirstLaunch() {
        runCatching {
            if (!prefs.contains(KEY_TRIAL_START)) {
                prefs.edit().putLong(KEY_TRIAL_START, System.currentTimeMillis()).apply()
                refresh()
            }
        }
    }

    /** Re-reads persisted state (e.g. when the settings screen opens). */
    fun refresh() {
        _status.value = loadStatus()
    }

    /**
     * Stores [raw] as the license key when it validates. Returns the
     * validation so the UI can show the exact reason a key was rejected.
     */
    fun activateKey(raw: String): KeyValidation {
        val validation = validateLicenseKey(raw)
        if (validation is KeyValidation.Valid) {
            runCatching {
                prefs.edit()
                    .putString(KEY_LICENSE, raw.trim().uppercase())
                    .apply()
                refresh()
            }
        }
        return validation
    }

    /** Removes the stored key (support / key-change flow). */
    fun clearLicense() {
        runCatching {
            prefs.edit().remove(KEY_LICENSE).apply()
            refresh()
        }
    }

    private fun loadStatus(): LicenseStatus = runCatching {
        evaluateLicense(
            trialStartMs = prefs.getLong(KEY_TRIAL_START, 0L),
            nowMs = System.currentTimeMillis(),
            licenseKey = prefs.getString(KEY_LICENSE, null),
        )
    }.getOrDefault(LicenseStatus.Trial(TRIAL_DAYS))

    companion object {
        private const val KEY_TRIAL_START = "trial_start_ms"
        private const val KEY_LICENSE = "license_key"
    }
}
