package com.lumora.photoeditor

import android.app.Application
import com.lumora.photoeditor.core.AppContainer
import com.lumora.photoeditor.data.sync.SyncScheduler

class LumoraApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Phase C#8 slice 2: re-assert the auto-sync schedule on every
        // start (idempotent UPDATE policy) so the persisted switch survives
        // WorkManager state wipes. Never throws (SyncScheduler guards).
        if (container.settings.syncAuto.value) {
            SyncScheduler.setAutoSync(this, true)
        }
        // Phase D#9 slice 1: stamp the license trial start once, on first
        // launch. Idempotent and never throwing (LicenseStore guards).
        container.licenseStore.recordFirstLaunch()
    }
}
