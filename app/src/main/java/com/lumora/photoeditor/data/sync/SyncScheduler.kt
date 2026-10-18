package com.lumora.photoeditor.data.sync

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Phase C#8 slice 2 — owns the WorkManager schedule behind the
 * "Auto-sync" settings switch. Idempotent: enabling twice just updates
 * the same unique work; disabling cancels it. Every call never throws —
 * a WorkManager hiccup must never break the settings screen.
 */
object SyncScheduler {

    const val UNIQUE_WORK_NAME = "h2w_cloud_sync"
    const val REPEAT_HOURS = 6L

    fun setAutoSync(context: Context, enabled: Boolean) {
        runCatching {
            val wm = WorkManager.getInstance(context.applicationContext)
            if (enabled) {
                val request = PeriodicWorkRequestBuilder<SyncWorker>(
                    REPEAT_HOURS, TimeUnit.HOURS,
                ).setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                ).addTag(UNIQUE_WORK_NAME)
                    .build()
                wm.enqueueUniquePeriodicWork(
                    UNIQUE_WORK_NAME,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request,
                )
            } else {
                wm.cancelUniqueWork(UNIQUE_WORK_NAME)
            }
        }.onFailure { Log.w("SyncScheduler", "setAutoSync failed", it) }
    }

    /** Best-effort truth for UI; false when WorkManager can't answer. */
    fun isScheduled(context: Context): Boolean = runCatching {
        val infos = WorkManager.getInstance(context.applicationContext)
            .getWorkInfosForUniqueWork(UNIQUE_WORK_NAME).get()
        infos.any { !it.state.isFinished }
    }.getOrDefault(false)
}
