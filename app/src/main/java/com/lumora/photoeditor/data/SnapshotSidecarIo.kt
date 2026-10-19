package com.lumora.photoeditor.data

import com.lumora.photoeditor.engine.EditSnapshot
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Blocking file I/O for per-photo snapshots — pure JVM (`java.io` only),
 * zero Android dependencies, so the crash-safety file contract is executable
 * in a standalone harness. [SnapshotStore] supplies the Context-derived
 * per-photo directory and coroutine dispatch; every policy decision (file
 * layout, atomic replace, corrupt skip) lives here and is pinned by
 * SnapshotStoreIoTest.
 *
 * Crash-safety fix 2026-10-20: [writeSnapshot] is atomic (tmp + rename).
 * SnapshotStore used to write snapshot JSON with a plain `writeText` — a
 * process kill mid-write left a truncated `<id>.json` that [readAll]
 * silently dropped (mapNotNull), so the user's just-taken snapshot vanished
 * without a trace. Now a kill can only ever leave a stale `<id>.json.tmp`
 * behind, which [readAll] ignores by its extension filter, and the next
 * [writeSnapshot] replaces that stale tmp before renaming.
 *
 * The caller must ensure [dir] exists — SnapshotStore creates it at construction.
 */
internal class SnapshotSidecarIo(
    val dir: File,
    private val json: Json = sidecarJson(),
) {
    /** On-disk layout: "<snapshotId>.json"; interrupted writes use "<snapshotId>.json.tmp". */
    fun snapshotFile(id: String): File = File(dir, "$id.json")
    fun tmpFile(id: String): File = File(dir, "$id.json.tmp")

    fun encode(snap: EditSnapshot): String = json.encodeToString(snap)
    fun decode(raw: String): EditSnapshot = json.decodeFromString(raw)

    /**
     * Atomic snapshot write: the payload lands in a tmp file first, then
     * replaces `<id>.json` via rename. If the rename fails the destination is
     * deleted and the rename retried — an interrupted take()/rename() can
     * never leave a half-written `<id>.json` behind.
     */
    fun writeSnapshot(snap: EditSnapshot) {
        atomicWrite(snapshotFile(snap.id), encode(snap))
    }

    /**
     * Reads every snapshot in [dir], newest-first. Corrupt or unreadable
     * `<id>.json` files are skipped (never thrown, never surfaced) — the same
     * semantics SnapshotStore has always had — and stale `.tmp` files from
     * interrupted writes are excluded by the extension filter. Callers apply
     * [snapshotsToEvict] on the result to enforce the per-photo cap.
     */
    fun readAll(): List<EditSnapshot> =
        dir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { f ->
                try { decode(f.readText()) } catch (_: Exception) { null }
            }
            .orEmpty()
            .sortedByDescending { it.createdAt }

    fun deleteSnapshot(id: String) {
        snapshotFile(id).delete()
    }

    /** Decodes one snapshot file; null when missing or corrupt. Never throws. */
    fun readSnapshot(id: String): EditSnapshot? = runCatching {
        snapshotFile(id).takeIf { it.isFile }?.let { decode(it.readText()) }
    }.getOrNull()

    private fun atomicWrite(dst: File, text: String) {
        val tmp = File(dir, "${dst.name}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(dst)) {
            dst.delete()
            tmp.renameTo(dst)
        }
    }
}
