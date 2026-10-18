package com.lumora.photoeditor.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.lumora.photoeditor.LumoraApp

/**
 * Phase C#8 slice 2 — the background half of app-side sync-ready.
 *
 * Runs [SyncEngine.syncNow] off the UI thread on WorkManager's schedule
 * (see [SyncScheduler]). The engine itself never throws; the worker still
 * guards the container lookup. With [NotConfiguredBackend] (the only
 * backend today) syncNow returns NotConfigured and the worker reports
 * success — a clean no-op, never a fake "synced" badge and never a
 * retry storm against a backend that does not exist.
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val engine = (applicationContext as? LumoraApp)?.container?.syncEngine
            ?: return Result.failure(
                workDataOf(KEY_REASON to "app container unavailable")
            )
        return when (val outcome = engine.syncNow()) {
            SyncRunOutcome.NotConfigured ->
                Result.success(workDataOf(KEY_REASON to "not configured"))
            is SyncRunOutcome.Ok ->
                Result.success()
            is SyncRunOutcome.Failed ->
                // A real backend failure: surface it, don't retry blindly.
                Result.failure(workDataOf(KEY_REASON to outcome.reason))
        }
    }

    companion object {
        const val KEY_REASON = "reason"
    }
}
