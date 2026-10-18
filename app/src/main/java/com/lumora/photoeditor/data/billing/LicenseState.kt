package com.lumora.photoeditor.data.billing

/**
 * Phase D#9 slice 1 — monetization infrastructure: the license/trial state
 * machine. Infrastructure ONLY: nothing is paywalled in this slice, no
 * store is connected, no prices exist (pricing is the photographer's
 * business decision). [isPro] is the single gating hook future paywalled
 * features will call; until then every feature is unlocked and the paywall
 * screen says so plainly instead of pretending a purchase is possible.
 *
 * Zero Android imports on purpose: the whole state machine is deterministic
 * pure Kotlin, unit-testable with the standalone kotlinc harness
 * (see AGENTS.md) and pinned by SelfTest check 71.
 */
const val TRIAL_DAYS = 14

const val MILLIS_PER_DAY = 86_400_000L

/** The license state the UI renders and future gating consults. */
sealed interface LicenseStatus {
    /** Trial running — every feature unlocked. */
    data class Trial(val daysLeft: Int) : LicenseStatus

    /** Trial ran out and no license key is stored. */
    data object TrialExpired : LicenseStatus

    /** A valid license key is stored. */
    data class Licensed(val plan: String) : LicenseStatus
}

/**
 * Derives the status from persisted state. [trialStartMs] is the
 * first-launch timestamp (0/unset = trial not started yet, full trial
 * shown). [licenseKey] wins over the trial whenever it validates.
 */
fun evaluateLicense(
    trialStartMs: Long,
    nowMs: Long,
    licenseKey: String?,
): LicenseStatus {
    val key = licenseKey?.takeIf { it.isNotBlank() }
    if (key != null && validateLicenseKey(key) is KeyValidation.Valid) {
        return LicenseStatus.Licensed(plan = "Pro")
    }
    if (trialStartMs <= 0L || nowMs < trialStartMs) {
        return LicenseStatus.Trial(TRIAL_DAYS)
    }
    val elapsedDays = (nowMs - trialStartMs) / MILLIS_PER_DAY
    val left = (TRIAL_DAYS - elapsedDays).toInt()
    return if (left > 0) LicenseStatus.Trial(left) else LicenseStatus.TrialExpired
}

/**
 * The one-line gating hook. Future paywalled features call exactly this;
 * in slice 1 nothing calls it yet (nothing is gated).
 */
fun isPro(status: LicenseStatus): Boolean = status is LicenseStatus.Licensed

/** One-line status for the Settings row — no second copy may live in UI. */
fun licenseStatusText(status: LicenseStatus): String = when (status) {
    is LicenseStatus.Trial ->
        if (status.daysLeft == 1) "Pro trial · 1 day left"
        else "Pro trial · ${status.daysLeft} days left"
    LicenseStatus.TrialExpired -> "Trial ended"
    is LicenseStatus.Licensed -> "Licensed · ${status.plan}"
}

/**
 * The Pro feature list shown on the paywall skeleton. Every entry names a
 * feature that really exists in this build — never a promise of a
 * feature that doesn't.
 */
val PRO_FEATURES: List<String> = listOf(
    "AI Pro Retouch — one-tap pro grade",
    "Batch export with per-platform recipes",
    "Client proofing galleries + contact sheets",
    "RAW & proprietary-format support",
    "Cloud sync (once your backend connects)",
)

/** License-key validation outcome. */
sealed interface KeyValidation {
    data object Valid : KeyValidation
    data class Invalid(val reason: String) : KeyValidation
}

private const val KEY_ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"

/**
 * Validates a license key of the form XXXX-XXXX-XXXX-XXXX (A–Z, 0–9).
 * The last character is a weighted-mod-36 check digit over the first 15,
 * so typos are caught without any server: a key is either well-formed or
 * it isn't. Keys themselves are issued by the photographer's future
 * store — this only checks the shape, never "buys" anything.
 */
fun validateLicenseKey(raw: String): KeyValidation {
    val cleaned = raw.trim().uppercase()
    val parts = cleaned.split('-')
    if (parts.size != 4 || parts.any { it.length != 4 }) {
        return KeyValidation.Invalid("Key must look like XXXX-XXXX-XXXX-XXXX.")
    }
    val body = parts.joinToString("")
    if (body.any { it !in KEY_ALPHABET }) {
        return KeyValidation.Invalid("Key uses only A–Z and 0–9.")
    }
    val expected = checkChar(body.substring(0, 15))
    return if (body[15] == expected) KeyValidation.Valid
    else KeyValidation.Invalid("That key doesn't check out — please retype it.")
}

/**
 * The check digit for a 15-char key body: weighted sum of alphabet
 * indices mod 36. Public so SelfTest (and the future key issuer) can
 * construct valid keys deterministically.
 */
fun checkChar(fifteen: String): Char {
    require(fifteen.length == 15) { "key body must be 15 chars" }
    var sum = 0
    fifteen.forEachIndexed { i, c ->
        sum += KEY_ALPHABET.indexOf(c).coerceAtLeast(0) * (i + 1)
    }
    return KEY_ALPHABET[sum % KEY_ALPHABET.length]
}
