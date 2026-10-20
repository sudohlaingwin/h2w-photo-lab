package com.lumora.photoeditor.data.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Executed verification of the NotConfiguredBackend honesty contract.
 *
 * Cloud sync is Phase LAST and the backend is the photographer's own
 * decision (his Drive project or his own server). Until a real backend is
 * wired, the engine runs on [NotConfiguredBackend] — the contract pinned
 * here: every operation must report "not configured" instead of
 * pretending to sync. A fake "synced" badge or a swallowed silent failure
 * would be the same lie class as a faked AI feature; this test makes that
 * lie impossible to ship silently.
 *
 * Runs on the standalone kotlinc harness (Gradle's unit-test workers are
 * broken in this env) against the REAL SyncBackend.kt / SyncDocument.kt.
 */
class NotConfiguredBackendTest {

    @Test fun isNotConfigured() {
        assertFalse("the default backend must never claim to be configured",
            NotConfiguredBackend.isConfigured)
    }

    @Test fun hasStableIdentity() {
        assertEquals("none", NotConfiguredBackend.backendId)
        assertTrue("displayName must exist for future UI, never shown while unconfigured",
            NotConfiguredBackend.displayName.isNotBlank())
    }

    @Test fun uploadReportsNotConfiguredAndNeverThrows() {
        val result = runBlocking {
            NotConfiguredBackend.upload(SyncDocument(deviceId = "harness"))
        }
        assertTrue("upload must report NotConfigured, not Ok and not Failed",
            result is SyncResult.NotConfigured)
    }

    @Test fun uploadOfEmptyDocumentAlsoReportsNotConfigured() {
        val result = runBlocking { NotConfiguredBackend.upload(SyncDocument()) }
        assertTrue(result is SyncResult.NotConfigured)
    }

    @Test fun downloadLatestReportsNotConfiguredAndNeverThrows() {
        val result = runBlocking { NotConfiguredBackend.downloadLatest() }
        assertTrue("downloadLatest must report NotConfigured, not Empty and not Failed",
            result is SyncDownload.NotConfigured)
    }

    @Test fun outcomesAreRepeatable() {
        repeat(3) {
            assertTrue(runBlocking {
                NotConfiguredBackend.upload(SyncDocument(deviceId = "harness"))
            } is SyncResult.NotConfigured)
            assertTrue(runBlocking { NotConfiguredBackend.downloadLatest() }
                is SyncDownload.NotConfigured)
        }
    }
}
