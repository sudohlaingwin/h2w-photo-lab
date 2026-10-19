package com.lumora.photoeditor.data.sync

import org.junit.Assert.*
import org.junit.Test

/**
 * First-ever executed verification of the user-facing sync strings —
 * data/sync/SyncStatusText.kt (Phase C#8 slice 2), pure Kotlin.
 *
 * These exact sentences are the contract the UI renders verbatim (the file's
 * own KDoc forbids a second copy of the wording in the screens), so this
 * test pins every sentence exactly: relative-time boundaries, the em-dash
 * device-id fallback, and every [describeSyncOutcome] branch including
 * singular/plural joining. A wording regression here is a user-visible
 * regression, so exact-string assertions are deliberate, not brittle.
 */
class SyncStatusTextTest {

    // ------------------------------------------------------------------
    // formatLastSync
    // ------------------------------------------------------------------

    @Test
    fun `zero and negative timestamps are Never synced`() {
        val now = 1_700_000_000_000L
        assertEquals("Never synced", formatLastSync(0L, now))
        assertEquals("Never synced", formatLastSync(-1L, now))
        assertEquals("Never synced", formatLastSync(Long.MIN_VALUE, now))
    }

    @Test
    fun `future timestamp is Never synced`() {
        val now = 1_700_000_000_000L
        assertEquals("Never synced", formatLastSync(now + 1L, now))
        assertEquals("Never synced", formatLastSync(now + 60_000L, now))
    }

    @Test
    fun `under a minute is Just now`() {
        val now = 1_700_000_000_000L
        assertEquals("Just now", formatLastSync(now, now))
        assertEquals("Just now", formatLastSync(now - 1L, now))
        assertEquals("Just now", formatLastSync(now - 59_999L, now))
    }

    @Test
    fun `minute boundaries`() {
        val now = 1_700_000_000_000L
        assertEquals("1 min ago", formatLastSync(now - 60_000L, now))
        assertEquals("1 min ago", formatLastSync(now - 119_999L, now))
        assertEquals("2 min ago", formatLastSync(now - 120_000L, now))
        assertEquals("59 min ago", formatLastSync(now - 3_599_999L, now))
    }

    @Test
    fun `hour boundaries`() {
        val now = 1_700_000_000_000L
        assertEquals("1 hr ago", formatLastSync(now - 3_600_000L, now))
        assertEquals("1 hr ago", formatLastSync(now - 7_199_999L, now))
        assertEquals("2 hr ago", formatLastSync(now - 7_200_000L, now))
        assertEquals("23 hr ago", formatLastSync(now - 86_399_999L, now))
    }

    @Test
    fun `singular one versus plural numeral wording`() {
        val now = 1_700_000_000_000L
        assertEquals("1 min ago", formatLastSync(now - 60_000L, now))
        assertEquals("2 min ago", formatLastSync(now - 120_000L, now))
        assertEquals("1 hr ago", formatLastSync(now - 3_600_000L, now))
        assertEquals("2 hr ago", formatLastSync(now - 7_200_000L, now))
    }

    @Test
    fun `day old renders absolute US date shape`() {
        val now = 1_700_000_000_000L
        val text = formatLastSync(now - 86_400_000L, now)
        assertTrue(
            "expected 'MMM d, yyyy HH:mm' shape, got: $text",
            text.matches(Regex("^[A-Z][a-z]{2} \\d{1,2}, \\d{4} \\d{2}:\\d{2}$"))
        )
    }

    @Test
    fun `absolute date reflects the sync time not now`() {
        // 2023-11-14T22:13:20Z: Nov 2023 in every timezone.
        val syncedAt = 1_700_000_000_000L
        val text = formatLastSync(syncedAt, syncedAt + 2 * 86_400_000L)
        assertTrue(
            "expected Nov 2023 date, got: $text",
            text.matches(Regex("^Nov \\d{1,2}, 2023 \\d{2}:\\d{2}$"))
        )
    }

    // ------------------------------------------------------------------
    // shortDeviceId
    // ------------------------------------------------------------------

    @Test
    fun `null and blank ids show em dash`() {
        assertEquals("—", shortDeviceId(null))
        assertEquals("—", shortDeviceId(""))
        assertEquals("—", shortDeviceId("   "))
        assertEquals("—", shortDeviceId("\t"))
    }

    @Test
    fun `short ids kept whole`() {
        assertEquals("a1b2c3", shortDeviceId("a1b2c3"))
        assertEquals("x", shortDeviceId("x"))
    }

    @Test
    fun `exactly eight chars kept`() {
        assertEquals("abcdefgh", shortDeviceId("abcdefgh"))
    }

    @Test
    fun `long ids truncated to eight`() {
        assertEquals("abcdef12", shortDeviceId("abcdef123456"))
    }

    // ------------------------------------------------------------------
    // describeSyncOutcome
    // ------------------------------------------------------------------

    @Test
    fun `run not configured wording`() {
        assertEquals(
            "No cloud backend connected — nothing to sync yet.",
            describeSyncOutcome(SyncRunOutcome.NotConfigured)
        )
    }

    @Test
    fun `pull not configured wording`() {
        assertEquals(
            "No cloud backend connected — nothing to sync yet.",
            describeSyncOutcome(SyncRunOutcome.Ok(SyncPullOutcome.NotConfigured, 123L))
        )
    }

    @Test
    fun `run failure names the reason`() {
        assertEquals(
            "Sync failed: quota exceeded",
            describeSyncOutcome(SyncRunOutcome.Failed("quota exceeded"))
        )
    }

    @Test
    fun `pull failure names the reason`() {
        assertEquals(
            "Sync failed: network timeout",
            describeSyncOutcome(SyncRunOutcome.Ok(SyncPullOutcome.Failed("network timeout"), 0L))
        )
    }

    @Test
    fun `up to date wording`() {
        assertEquals(
            "Synced — this device is up to date.",
            describeSyncOutcome(SyncRunOutcome.Ok(SyncPullOutcome.NoRemoteChanges, 0L))
        )
    }

    @Test
    fun `single photo applied`() {
        assertEquals(
            "Synced — 1 photo updated.",
            describeSyncOutcome(SyncRunOutcome.Ok(SyncPullOutcome.Applied(1, 0, 0, ""), 0L))
        )
    }

    @Test
    fun `mixed counts joined with commas`() {
        assertEquals(
            "Synced — 2 photos updated, 2 galleries updated, 3 photos skipped (not on this device).",
            describeSyncOutcome(SyncRunOutcome.Ok(SyncPullOutcome.Applied(2, 3, 2, ""), 0L))
        )
    }

    @Test
    fun `zero applied is no changes`() {
        assertEquals(
            "Synced — no changes.",
            describeSyncOutcome(SyncRunOutcome.Ok(SyncPullOutcome.Applied(0, 0, 0, ""), 0L))
        )
    }

    @Test
    fun `gallery singular`() {
        assertEquals(
            "Synced — 1 gallery updated.",
            describeSyncOutcome(SyncRunOutcome.Ok(SyncPullOutcome.Applied(0, 0, 1, ""), 0L))
        )
    }

    @Test
    fun `photo skipped singular pairs with no changes`() {
        assertEquals(
            "Synced — no changes, 1 photo skipped (not on this device).",
            describeSyncOutcome(SyncRunOutcome.Ok(SyncPullOutcome.Applied(0, 1, 0, ""), 0L))
        )
    }

    @Test
    fun `photo and gallery singular pair`() {
        assertEquals(
            "Synced — 1 photo updated, 1 gallery updated.",
            describeSyncOutcome(SyncRunOutcome.Ok(SyncPullOutcome.Applied(1, 0, 1, ""), 0L))
        )
    }

    @Test
    fun `applied summary stays internal never leaks into the sentence`() {
        assertEquals(
            "Synced — 1 photo updated.",
            describeSyncOutcome(SyncRunOutcome.Ok(SyncPullOutcome.Applied(1, 0, 0, "internal debug"), 0L))
        )
    }
}
