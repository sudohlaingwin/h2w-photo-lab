package com.lumora.photoeditor.data.sync

/**
 * Phase C#8 slice 1 — the pluggable backend seam. A real cloud backend is
 * the photographer's own decision (his Drive project or his own server);
 * this interface is everything such a backend must implement, and nothing
 * more: store one JSON document, return the latest one. No credentials,
 * no client ids, no endpoints are invented here — until a real backend is
 * configured the engine runs on [NotConfiguredBackend] and every sync op
 * honestly reports "not configured" instead of pretending.
 */
sealed interface SyncResult {
    /** The document reached the backend. */
    data class Ok(val pushedAt: Long) : SyncResult

    /** The backend (or transport) failed; nothing was lost locally. */
    data class Failed(val reason: String) : SyncResult

    /** No backend is configured — sync is a no-op by design, not an error. */
    data object NotConfigured : SyncResult
}

sealed interface SyncDownload {
    /** The latest remote document. */
    data class Doc(val document: SyncDocument) : SyncDownload

    /** The backend is configured but nothing has been uploaded yet. */
    data object Empty : SyncDownload

    /** The fetch failed; local state is untouched. */
    data class Failed(val reason: String) : SyncDownload

    /** No backend is configured — sync is a no-op by design, not an error. */
    data object NotConfigured : SyncDownload
}

interface SyncBackend {
    /** Stable id persisted in sync state (e.g. "gdrive", "custom"). */
    val backendId: String

    /** Human label for future settings UI (never shown while unconfigured). */
    val displayName: String

    /** False until the photographer wires a real backend. */
    val isConfigured: Boolean

    /** Uploads the document, replacing the remote latest. Never throws. */
    suspend fun upload(doc: SyncDocument): SyncResult

    /** Fetches the latest remote document. Never throws. */
    suspend fun downloadLatest(): SyncDownload
}

/**
 * The honest default: there is nowhere to sync to, so every operation
 * says so. The engine treats this as a clean no-op — never an error the
 * user must dismiss, never a fake "synced" badge.
 */
object NotConfiguredBackend : SyncBackend {
    override val backendId: String = "none"
    override val displayName: String = "Not configured"
    override val isConfigured: Boolean = false

    override suspend fun upload(doc: SyncDocument): SyncResult =
        SyncResult.NotConfigured

    override suspend fun downloadLatest(): SyncDownload =
        SyncDownload.NotConfigured
}
