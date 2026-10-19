package com.lumora.photoeditor.data.sync

import com.lumora.photoeditor.data.MAX_GALLERY_NAME_LEN
import com.lumora.photoeditor.data.ProofingGallery
import org.junit.Assert.*
import org.junit.Test

/**
 * First-ever executed verification of the cloud-sync foundation —
 * data/sync/SyncDocument.kt + data/sync/SyncMerge.kt (Phase C#8 slice 1),
 * pure Kotlin: photo-key identity, entry/document sanitizers, the
 * last-writer-wins merge plan, and the plan's describe() line.
 *
 * SelfTest check 69 already pins this contract inside the app (GL-bound,
 * unrunnable here), so this test runs the REAL sources on the JVM to
 * catch regressions the device-bound check cannot. Sync is the one
 * subsystem where a silent bug means DATA LOSS (a losing photo's edits
 * vanish on another device, or a tombstone wipes live state), so the
 * merge rules get pinned hard:
 *
 * - photoKeyOf: trims, deterministic, dimensions part of the key.
 * - sanitizeSyncPhotoEntry: blank keys dropped, rating clamped 0..5,
 *   keywords normalized/de-duplicated/capped, tombstones keep no payload,
 *   negative clocks coerced to 0.
 * - sanitizeSyncDocument: newer schemas rejected outright, blank deviceId
 *   rejected, duplicate keys keep the newest, 20,000-photo hard cap, bad
 *   galleries dropped.
 * - mergeSyncDocuments: null remote = first sync (upload everything);
 *   newer wins in either direction; exact ties keep local (no flapping);
 *   newer tombstones apply the clear, older tombstones lose to live data;
 *   galleries merge per-id on updatedAt with the same tie rule.
 */
class SyncMergeTest {

    // ---------- photoKeyOf ----------

    @Test fun photoKeyTrimsAndIsDeterministic() {
        assertEquals(
            photoKeyOf("IMG_1.jpg", 4000, 3000),
            photoKeyOf("  IMG_1.jpg ", 4000, 3000),
        )
    }

    @Test fun photoKeyDistinguishesNameAndDimensions() {
        assertNotEquals(
            photoKeyOf("IMG_1.jpg", 4000, 3000),
            photoKeyOf("IMG_2.jpg", 4000, 3000),
        )
        assertNotEquals(
            photoKeyOf("IMG_1.jpg", 4000, 3000),
            photoKeyOf("IMG_1.jpg", 3000, 4000),
        )
    }

    // ---------- sanitizeSyncPhotoEntry ----------

    @Test fun blankPhotoKeyIsDropped() {
        assertNull(sanitizeSyncPhotoEntry(SyncPhotoEntry(photoKey = "   ")))
    }

    @Test fun ratingIsClampedToZeroToFive() {
        val high = sanitizeSyncPhotoEntry(SyncPhotoEntry(photoKey = "k", rating = 9))
        val low = sanitizeSyncPhotoEntry(SyncPhotoEntry(photoKey = "k", rating = -2))
        assertEquals(5, high!!.rating)
        assertEquals(0, low!!.rating)
    }

    @Test fun keywordsAreNormalizedDedupedAndCapped() {
        val e = sanitizeSyncPhotoEntry(
            SyncPhotoEntry(
                photoKey = "k",
                keywords = listOf("  Sunset  ", "Sunset", "SUNSET", "   ", "beach"),
            )
        )!!
        // casing kept (display form), blanks dropped, exact dupes gone
        assertEquals(listOf("Sunset", "SUNSET", "beach"), e.keywords)

        val many = sanitizeSyncPhotoEntry(
            SyncPhotoEntry(
                photoKey = "k",
                keywords = (1..150).map { "tag$it" },
            )
        )!!
        assertEquals(100, many.keywords.size)
        assertEquals("tag1", many.keywords.first())
    }

    @Test fun tombstoneCarriesNoPayload() {
        val tomb = sanitizeSyncPhotoEntry(
            SyncPhotoEntry(
                photoKey = "k", deleted = true,
                settingsJson = "{\"exposure\":0.5}", rating = 5, favorite = true,
                keywords = listOf("a"),
            )
        )!!
        assertTrue(tomb.deleted)
        assertTrue(tomb.settingsJson.isEmpty())
        assertEquals(0, tomb.rating)
        assertFalse(tomb.favorite)
        assertTrue(tomb.keywords.isEmpty())
    }

    @Test fun negativeClockIsCoercedToZeroAndKeyTrimmed() {
        val e = sanitizeSyncPhotoEntry(
            SyncPhotoEntry(photoKey = "  k  ", updatedAt = -42)
        )!!
        assertEquals("k", e.photoKey)
        assertEquals(0L, e.updatedAt)
    }

    // ---------- sanitizeSyncDocument ----------

    @Test fun newerSchemaVersionIsRejected() {
        assertNull(
            sanitizeSyncDocument(
                SyncDocument(schemaVersion = SYNC_SCHEMA_VERSION + 1, deviceId = "d")
            )
        )
    }

    @Test fun blankDeviceIdIsRejected() {
        assertNull(sanitizeSyncDocument(SyncDocument(deviceId = "")))
    }

    @Test fun duplicatePhotoKeysKeepNewest() {
        val doc = sanitizeSyncDocument(
            SyncDocument(
                deviceId = "d",
                photos = listOf(
                    SyncPhotoEntry("k", updatedAt = 10, rating = 1),
                    SyncPhotoEntry("k", updatedAt = 20, rating = 3),
                ),
            )
        )!!
        assertEquals(1, doc.photos.size)
        assertEquals(20L, doc.photos[0].updatedAt)
        assertEquals(3, doc.photos[0].rating)
    }

    @Test fun photoCountIsHardCappedAtMaxSyncPhotos() {
        val doc = sanitizeSyncDocument(
            SyncDocument(
                deviceId = "d",
                photos = (1..(MAX_SYNC_PHOTOS + 5)).map {
                    SyncPhotoEntry("key$it", updatedAt = it.toLong())
                },
            )
        )!!
        assertEquals(MAX_SYNC_PHOTOS, doc.photos.size)
    }

    @Test fun badGalleriesAreDropped() {
        val doc = sanitizeSyncDocument(
            SyncDocument(
                deviceId = "d",
                galleries = listOf(
                    ProofingGallery(id = "bad", name = "   "),
                    ProofingGallery(id = "long", name = "x".repeat(MAX_GALLERY_NAME_LEN + 1)),
                    ProofingGallery(id = "good", name = "Wedding"),
                ),
            )
        )!!
        assertEquals(listOf("good"), doc.galleries.map { it.id })
    }

    // ---------- mergeSyncDocuments: photos ----------

    private fun photo(
        key: String,
        updatedAt: Long,
        deleted: Boolean = false,
        settingsJson: String = "",
    ) = SyncPhotoEntry(
        photoKey = key, updatedAt = updatedAt,
        deleted = deleted, settingsJson = settingsJson,
    )

    @Test fun nullRemoteUploadsEverythingLocalAndAppliesNothing() {
        val local = SyncDocument(
            deviceId = "a",
            photos = listOf(photo("p1", 7), photo("p2", 3)),
            galleries = listOf(ProofingGallery(id = "g", name = "G")),
        )
        val plan = mergeSyncDocuments(local, null)
        assertTrue(plan.photosToApply.isEmpty())
        assertTrue(plan.galleriesToApply.isEmpty())
        assertEquals(setOf("p1", "p2"), plan.photosToUpload.map { it.photoKey }.toSet())
        assertEquals(listOf("g"), plan.galleriesToUpload.map { it.id })
    }

    @Test fun newerWinsInEitherDirectionAndTiesKeepLocal() {
        val local = SyncDocument(
            deviceId = "a",
            photos = listOf(
                photo("newer-local", 30),
                photo("newer-remote", 10),
                photo("tie", 50, settingsJson = "local"),
                photo("local-only", 7),
            ),
        )
        val remote = SyncDocument(
            deviceId = "b",
            photos = listOf(
                photo("newer-local", 20),
                photo("newer-remote", 40),
                photo("tie", 50, settingsJson = "remote"),
                photo("remote-only", 9),
            ),
        )
        val plan = mergeSyncDocuments(local, remote)
        assertEquals(
            setOf("newer-remote", "remote-only"),
            plan.photosToApply.map { it.photoKey }.toSet(),
        )
        assertEquals(
            setOf("newer-local", "local-only"),
            plan.photosToUpload.map { it.photoKey }.toSet(),
        )
        // exact tie: no apply, no upload, no flapping — and the REMOTE
        // copy must not leak in
        val tieApply = plan.photosToApply.filter { it.photoKey == "tie" }
        val tieUpload = plan.photosToUpload.filter { it.photoKey == "tie" }
        assertTrue(tieApply.isEmpty())
        assertTrue(tieUpload.isEmpty())
        // the winning remote entry is the REMOTE object (payload travels)
        assertEquals(
            "newer-remote",
            plan.photosToApply.first { it.photoKey == "newer-remote" }.photoKey,
        )
    }

    @Test fun tombstonesObeyTheSameClock() {
        val local = SyncDocument(
            deviceId = "a",
            photos = listOf(
                photo("tomb-new", 60),          // live locally...
                photo("tomb-old", 5),           // ...live and newer here
            ),
        )
        val remote = SyncDocument(
            deviceId = "b",
            photos = listOf(
                photo("tomb-new", 70, deleted = true),  // newer tombstone: clear wins
                photo("tomb-old", 3, deleted = true),   // older tombstone: loses
            ),
        )
        val plan = mergeSyncDocuments(local, remote)
        val appliedTomb = plan.photosToApply.first { it.photoKey == "tomb-new" }
        assertTrue(appliedTomb.deleted)
        val uploadedLive = plan.photosToUpload.first { it.photoKey == "tomb-old" }
        assertFalse(uploadedLive.deleted)
    }

    // ---------- mergeSyncDocuments: galleries ----------

    private fun gallery(id: String, updatedAt: Long, name: String = id) =
        ProofingGallery(id = id, name = name, updatedAt = updatedAt)

    @Test fun galleriesMergePerIdOnUpdatedAtWithTieKeepingLocal() {
        val local = SyncDocument(
            deviceId = "a",
            galleries = listOf(
                gallery("remote-newer", 50),
                gallery("local-newer", 80),
                gallery("tie", 60),
                gallery("local-only", 10),
            ),
        )
        val remote = SyncDocument(
            deviceId = "b",
            galleries = listOf(
                gallery("remote-newer", 100),
                gallery("local-newer", 70),
                gallery("tie", 60),
                gallery("remote-only", 20),
            ),
        )
        val plan = mergeSyncDocuments(local, remote)
        assertEquals(
            setOf("remote-newer", "remote-only"),
            plan.galleriesToApply.map { it.id }.toSet(),
        )
        assertEquals(
            setOf("local-newer", "local-only"),
            plan.galleriesToUpload.map { it.id }.toSet(),
        )
    }

    // ---------- describe() ----------

    @Test fun describeReportsAlreadyInSyncWhenEmpty() {
        val plan = mergeSyncDocuments(
            SyncDocument(deviceId = "a"),
            SyncDocument(deviceId = "b"),
        )
        assertEquals("already in sync", plan.describe())
        assertEquals("already in sync", SyncMergePlan().describe())
    }

    @Test fun describePluralizesAndJoinsParts() {
        val plan = SyncMergePlan(
            photosToApply = listOf(photo("a", 1)),
            photosToUpload = listOf(photo("b", 1), photo("c", 1)),
            galleriesToApply = listOf(gallery("g", 1)),
            galleriesToUpload = listOf(gallery("h", 1), gallery("i", 1)),
        )
        assertEquals(
            "apply 1 photo, push 2 photos, apply 1 gallery, push 2 galleries",
            plan.describe(),
        )
    }
}
