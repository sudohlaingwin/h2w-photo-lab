package com.lumora.photoeditor.data

import com.lumora.photoeditor.engine.EditState
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Blocking file I/O for edit sidecars — pure JVM (`java.io` only), zero
 * Android dependencies, so the atomic-save / corrupt-quarantine contract is
 * executable in a standalone harness. [EditStore] supplies the
 * Context-derived directory and coroutine dispatch; every policy decision
 * (file layout, atomic replace, corrupt quarantine, validate-before-write)
 * lives here and is pinned by EditStoreIoTest.
 *
 * The caller must ensure [dir] exists — EditStore creates it at construction.
 */
internal class EditSidecarIo(
    val dir: File,
    private val json: Json = sidecarJson(),
) {
    /** On-disk layout: "<id>.json", "<id>.json.tmp", "<id>.json.corrupt". */
    fun sidecarFile(imageId: String): File = File(dir, "$imageId.json")
    fun tmpFile(imageId: String): File = File(dir, "$imageId.json.tmp")
    fun corruptFile(imageId: String): File = File(dir, "$imageId.json.corrupt")

    fun encode(state: EditState): String = json.encodeToString(state)
    fun decode(raw: String): EditState = json.decodeFromString(raw)

    /**
     * Atomic save: the payload lands in a tmp file first, then replaces the
     * sidecar via rename. If rename fails (e.g. a stale destination), the
     * destination is deleted and the rename retried, so an interrupted
     * session never leaves a half-written sidecar behind.
     */
    fun save(imageId: String, state: EditState) {
        atomicWrite(sidecarFile(imageId), encode(state))
    }

    /**
     * Missing sidecar -> fresh [EditState]. Corrupt sidecar -> the bytes are
     * quarantined to "<id>.json.corrupt" for diagnosis and a fresh [EditState]
     * is returned, so the original image is never at risk.
     */
    fun load(imageId: String): EditState {
        val f = sidecarFile(imageId)
        if (!f.exists()) return EditState()
        return try {
            decode(f.readText())
        } catch (e: Exception) {
            // Corrupt sidecar: keep the original image safe, start fresh.
            // The corrupt file is preserved for diagnosis.
            f.renameTo(corruptFile(imageId))
            EditState()
        }
    }

    fun delete(imageId: String) {
        sidecarFile(imageId).delete()
    }

    /**
     * Copies one image's edit sidecar onto another id (virtual copies).
     * Missing source file is fine — [load] starts fresh anyway.
     * Returns false only on an I/O failure.
     */
    fun copySidecar(fromId: String, toId: String): Boolean = try {
        val src = sidecarFile(fromId)
        if (src.exists()) src.copyTo(sidecarFile(toId), overwrite = true)
        true
    } catch (_: Exception) {
        false
    }

    /** Raw sidecar read for the sync engine: null when none / unreadable. Never throws. */
    fun readRaw(imageId: String): String? = runCatching {
        sidecarFile(imageId).takeIf { it.isFile }?.readText()
    }.getOrNull()

    /**
     * Raw sidecar write for sync-apply: validates the payload by decoding it
     * as [EditState] FIRST — a corrupt remote payload must never clobber a
     * good local sidecar — then writes atomically like [save].
     * Returns false when the payload is invalid or the write fails. Never throws.
     */
    fun saveRaw(imageId: String, raw: String): Boolean {
        try {
            decode(raw)
        } catch (_: Throwable) {
            // Any decode failure — including an Error from a hostile remote
            // payload (e.g. OOM on a huge payload, StackOverflowError on
            // deeply nested JSON) — rejects the payload instead of escaping
            // applyPhotoEntry's "Never throws" contract and clobbering or
            // crashing the sync-apply path. The local sidecar is untouched.
            // Plain decode: no cancellation to rethrow (cf. cycle 6aee0f4).
            return false
        }
        return runCatching {
            atomicWrite(sidecarFile(imageId), raw)
            true
        }.getOrDefault(false)
    }

    /** Last-modified of the edit sidecar, 0 when there is none. */
    fun sidecarLastModified(imageId: String): Long =
        sidecarFile(imageId).takeIf { it.exists() }?.lastModified() ?: 0L

    private fun atomicWrite(dst: File, text: String) {
        val tmp = File(dir, "${dst.name}.tmp")
        tmp.writeText(text)
        // atomic replace so an interrupted session never corrupts the sidecar
        if (!tmp.renameTo(dst)) {
            dst.delete()
            tmp.renameTo(dst)
        }
    }
}

/** The exact Json config EditStore has always used for sidecars (pinned by EditSidecarTest). */
internal fun sidecarJson(): Json = Json {
    prettyPrint = false
    ignoreUnknownKeys = true
    encodeDefaults = true
}
