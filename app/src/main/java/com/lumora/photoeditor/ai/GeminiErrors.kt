package com.lumora.photoeditor.ai

/**
 * Plain-language explanations for Gemini test-connection HTTP failures.
 *
 * The API's raw JSON error bodies get clipped mid-word on a phone screen and
 * look like an app bug, so callers must never show them directly. This maps
 * the status code to a sentence the user can act on; the raw body stays out
 * of the UI entirely.
 */
internal fun friendlyGeminiHttpError(code: Int): String = when (code) {
    400 -> "Bad request (400): Gemini rejected the test message. " +
        "Try again — if it repeats, re-enter your key."
    401, 403 -> "Key rejected (HTTP $code): this Gemini API key isn't accepted. " +
        "Check it under Settings → AI Assistant, or get a fresh key at aistudio.google.com."
    404 -> "Model not found (404): Gemini no longer offers this model."
    429 -> "Quota exceeded (429): your Gemini free quota is used up. " +
        "Check your plan at aistudio.google.com — free quota resets daily."
    in 500..599 -> "Gemini's servers had a problem (HTTP $code). " +
        "Wait a minute and try again."
    else -> "Connection test failed (HTTP $code). " +
        "Check your network and try again."
}

/**
 * Message when the model-discovery loop tried every candidate model and all
 * of them 404'd: the key is alive, but nothing Gemini offers matches it.
 */
internal fun friendlyNoGeminiModelFound(): String =
    "No working Gemini model found for this key — every model returned 404. " +
        "Check the key at aistudio.google.com, or try a new key."
