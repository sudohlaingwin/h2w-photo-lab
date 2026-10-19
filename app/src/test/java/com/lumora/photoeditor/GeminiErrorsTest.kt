package com.lumora.photoeditor

import com.lumora.photoeditor.ai.friendlyGeminiHttpError
import com.lumora.photoeditor.ai.friendlyNoGeminiModelFound
import org.junit.Assert.*
import org.junit.Test

/**
 * Executed verification for ai/GeminiErrors.kt (pure Kotlin, zero imports —
 * runs on the standalone kotlinc harness; Gradle's test workers are broken in
 * this env).
 *
 * This is the fix for the 2026-10-17 on-device video bug: Settings → AI
 * Assistant → Test Connection showed the raw clipped Gemini JSON error body
 * (HTTP 429 "You exceeded your current quota") mid-word-truncated on the
 * phone screen, which looked like an app bug. The contract under test:
 * callers must NEVER surface the raw API body — every HTTP failure maps to
 * a plain-language, actionable sentence, and the raw body stays out of the
 * UI entirely.
 */
class GeminiErrorsTest {

    // ---- The flagship fix: HTTP 429 must show the friendly quota sentence ----

    @Test fun http429MapsToExactFriendlyQuotaMessage() {
        // Exact pin: this is the literal sentence shown on his phone.
        assertEquals(
            "Quota exceeded (429): your Gemini free quota is used up. " +
                "Check your plan at aistudio.google.com — free quota resets daily.",
            friendlyGeminiHttpError(429))
    }

    @Test fun http429MessageContainsNoRawJsonMarkers() {
        val msg = friendlyGeminiHttpError(429)
        assertFalse("must not contain '{'", msg.contains('{'))
        assertFalse("must not contain '}'", msg.contains('}'))
        assertFalse("must not contain '\"'", msg.contains('"'))
        assertFalse("must not look like a JSON dump", msg.contains("error\":"))
    }

    // ---- Every branch maps to a non-blank, actionable sentence ----

    @Test fun http400IsBadRequestGuidance() {
        val msg = friendlyGeminiHttpError(400)
        assertTrue(msg, msg.contains("(400)"))
        assertTrue(msg, msg.contains("re-enter your key", ignoreCase = true))
    }

    @Test fun http401And403ShareTheKeyRejectedBranch() {
        val m401 = friendlyGeminiHttpError(401)
        val m403 = friendlyGeminiHttpError(403)
        // Same branch by design (each embeds its own code, so normalize first).
        assertEquals(m401.replace("401", "NNN"), m403.replace("403", "NNN"))
        assertTrue(m401, m401.contains("Key rejected", ignoreCase = true))
        assertTrue(m401, m401.contains("aistudio.google.com"))
    }

    @Test fun http404IsModelNotFoundNotKeyBlame() {
        val msg = friendlyGeminiHttpError(404)
        assertTrue(msg, msg.contains("Model not found"))
        // Must not tell the user their key is bad when only the model is gone.
        assertFalse(msg.contains("key", ignoreCase = true))
    }

    @Test fun http5xxMapsToServerProblemWithCodeEmbedded() {
        listOf(500, 502, 503).forEach { code ->
            val msg = friendlyGeminiHttpError(code)
            assertTrue(msg, msg.contains("HTTP $code"))
            assertTrue(msg, msg.contains("servers had a problem", ignoreCase = true))
        }
    }

    @Test fun unknownCodeFallsBackWithCodeEmbedded() {
        // Total mapping: no status code may ever surface a blank message.
        val msg = friendlyGeminiHttpError(418)
        assertTrue(msg.isNotBlank())
        assertTrue(msg, msg.contains("418"))
    }

    @Test fun noStatusCodeMapsToBlank() {
        listOf(400, 401, 403, 404, 429, 500, 502, 503, 418, 599, 200, -1).forEach { code ->
            assertTrue("code $code", friendlyGeminiHttpError(code).isNotBlank())
        }
    }

    @Test fun noMessageContainsRawJsonMarkers() {
        // Sweep: the whole point of the fix — no branch may ever read like
        // a clipped API JSON dump on his phone.
        listOf(400, 401, 403, 404, 429, 500, 503, 418).forEach { code ->
            val msg = friendlyGeminiHttpError(code)
            assertFalse("code $code contains '{'", msg.contains('{'))
            assertFalse("code $code contains '}'", msg.contains('}'))
            assertFalse("code $code contains '\"'", msg.contains('"'))
            assertFalse("code $code looks like JSON", msg.contains("error\":"))
        }
    }

    // ---- friendlyNoGeminiModelFound: all discovery candidates 404'd ----

    @Test fun noModelFoundMessageIsExactPin() {
        // Exact pin: the literal sentence shown when every candidate model 404s.
        assertEquals(
            "No working Gemini model found for this key — every model returned 404. " +
                "Check the key at aistudio.google.com, or try a new key.",
            friendlyNoGeminiModelFound())
    }

    @Test fun noModelFoundMentions404AndIsActionable() {
        val msg = friendlyNoGeminiModelFound()
        assertTrue(msg, msg.contains("404"))
        assertTrue(msg, msg.contains("aistudio.google.com"))
        assertFalse(msg.contains('{'))
    }
}
