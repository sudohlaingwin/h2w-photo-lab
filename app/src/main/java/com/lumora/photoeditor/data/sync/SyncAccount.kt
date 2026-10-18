package com.lumora.photoeditor.data.sync

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Phase C#8 slice 2 — the account seam for cloud sync.
 *
 * The pro-premium program calls for "account sign-in via Google" on the
 * app side, but the backend project itself is the photographer's decision
 * and no OAuth client exists yet — so there is deliberately NO sign-in
 * button anywhere in the UI. This file is the honest seam: the shape a
 * future Google sign-in will fill (a signed-in account with an email),
 * persisted and observable, defaulting to [SignedOut]. Nothing here
 * pretends a sign-in happened.
 */
sealed interface SyncAccount {
    /** No account connected — the only state reachable today. */
    data object SignedOut : SyncAccount

    /** A real account, set only by a future sign-in flow. */
    data class SignedIn(val email: String, val displayName: String?) : SyncAccount
}

/**
 * Future hook for the photographer's chosen identity provider (Google
 * sign-in per the program brief). Implemented only when his backend
 * decision lands; until then the store stays [SyncAccount.SignedOut].
 */
interface SyncAccountProvider {
    /** Stable id, e.g. "google". */
    val providerId: String

    /** Human label for future settings UI. */
    val displayName: String

    /** Starts the provider's sign-in flow; never throws. */
    suspend fun signIn(): SyncAccount
}

/** Persisted, observable sync account. Every op never throws. */
class SyncAccountStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("lumora_sync_account", Context.MODE_PRIVATE)

    private val _account = MutableStateFlow(load())
    val account: StateFlow<SyncAccount> = _account.asStateFlow()

    private fun load(): SyncAccount {
        val email = prefs.getString("account_email", null)?.takeIf { it.isNotBlank() }
            ?: return SyncAccount.SignedOut
        return SyncAccount.SignedIn(
            email = email,
            displayName = prefs.getString("account_name", null)?.takeIf { it.isNotBlank() },
        )
    }

    /** Records a real sign-in. Called only by a future sign-in flow. */
    fun setSignedIn(email: String, displayName: String?) {
        val clean = email.trim()
        if (clean.isEmpty()) return
        runCatching {
            prefs.edit()
                .putString("account_email", clean)
                .putString("account_name", displayName?.trim()?.takeIf { it.isNotEmpty() })
                .apply()
        }
        _account.value = SyncAccount.SignedIn(clean, displayName?.trim()?.takeIf { it.isNotEmpty() })
    }

    fun signOut() {
        runCatching { prefs.edit().clear().apply() }
        _account.value = SyncAccount.SignedOut
    }
}
