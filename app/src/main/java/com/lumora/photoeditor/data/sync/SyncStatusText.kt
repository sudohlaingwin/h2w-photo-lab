package com.lumora.photoeditor.data.sync

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phase C#8 slice 2 — pure-Kotlin user-facing sync strings.
 *
 * Kept free of Android framework types (java.text/java.util only) so the
 * logic is unit-testable and pinned by SelfTest check 70. The UI renders
 * exactly these strings — no second copy of the wording may live in the
 * screens.
 */

/** "Never synced" / "Just now" / "5 min ago" / "3 hr ago" / absolute date. */
fun formatLastSync(lastSyncAt: Long, nowMs: Long = System.currentTimeMillis()): String {
    if (lastSyncAt <= 0L) return "Never synced"
    val age = nowMs - lastSyncAt
    if (age < 0) return "Never synced"
    return when {
        age < 60_000L -> "Just now"
        age < 3_600_000L -> {
            val m = (age / 60_000L).toInt()
            if (m == 1) "1 min ago" else "$m min ago"
        }
        age < 86_400_000L -> {
            val h = (age / 3_600_000L).toInt()
            if (h == 1) "1 hr ago" else "$h hr ago"
        }
        else -> SimpleDateFormat("MMM d, yyyy HH:mm", Locale.US).format(Date(lastSyncAt))
    }
}

/** One-line device id for the settings row, e.g. "a1b2c3d4". */
fun shortDeviceId(deviceId: String?): String =
    deviceId?.takeIf { it.isNotBlank() }?.take(8) ?: "—"

/**
 * Honest one-line result for a manual "Sync now" tap. Every outcome maps
 * to a plain sentence — a not-configured backend is a state, not an
 * error the user must dismiss, and failures name the reason.
 */
fun describeSyncOutcome(outcome: SyncRunOutcome): String = when (outcome) {
    SyncRunOutcome.NotConfigured ->
        "No cloud backend connected — nothing to sync yet."
    is SyncRunOutcome.Failed ->
        "Sync failed: ${outcome.reason}"
    is SyncRunOutcome.Ok -> when (val pull = outcome.pull) {
        SyncPullOutcome.NotConfigured ->
            "No cloud backend connected — nothing to sync yet."
        is SyncPullOutcome.Failed ->
            "Sync failed: ${pull.reason}"
        SyncPullOutcome.NoRemoteChanges ->
            "Synced — this device is up to date."
        is SyncPullOutcome.Applied -> {
            val p = pull.photosApplied
            val g = pull.galleriesApplied
            val skipped = pull.photosSkipped
            val parts = mutableListOf<String>()
            if (p > 0) parts += if (p == 1) "1 photo updated" else "$p photos updated"
            if (g > 0) parts += if (g == 1) "1 gallery updated" else "$g galleries updated"
            if (parts.isEmpty()) parts += "no changes"
            if (skipped > 0) parts += if (skipped == 1)
                "1 photo skipped (not on this device)"
            else "$skipped photos skipped (not on this device)"
            "Synced — " + parts.joinToString(", ") + "."
        }
    }
}
