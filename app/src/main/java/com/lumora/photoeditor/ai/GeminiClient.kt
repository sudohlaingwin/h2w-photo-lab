package com.lumora.photoeditor.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Minimal Gemini API client using HttpURLConnection (no extra dependencies).
 * Sends the current photo (downscaled JPEG, base64) plus the current edit
 * settings, and asks Gemini for structured editing suggestions as JSON.
 */
class GeminiClient {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    sealed interface Result {
        data class Success(val suggestions: AiSuggestions, val rawText: String) : Result
        data class Failure(val message: String) : Result
    }

    /**
     * @param apiKey user's Gemini API key
     * @param imageBase64 downscaled JPEG, base64-encoded (no data: prefix)
     * @param settingsSummary human-readable summary of current edit settings
     * @param userQuestion optional free-text question from the user
     */
    suspend fun analyzePhoto(
        apiKey: String,
        imageBase64: String,
        settingsSummary: String,
        userQuestion: String? = null,
    ): Result = withContext(Dispatchers.IO) {
        try {
            val prompt = buildPrompt(settingsSummary, userQuestion)
            val request = GeminiRequest(
                contents = listOf(
                    GeminiContent(
                        parts = listOf(
                            GeminiPart(text = prompt),
                            GeminiPart(
                                inlineData = GeminiInlineData(
                                    mimeType = "image/jpeg",
                                    data = imageBase64,
                                ),
                            ),
                        ),
                    ),
                ),
            )
            val body = json.encodeToString(GeminiRequest.serializer(), request)

            val (code, responseText, _) = postWithFallback(apiKey, body)
            if (code !in 200..299) {
                if (code == 429) {
                    return@withContext Result.Failure(
                        "Gemini API error: Quota exceeded (429) — your free quota is used up. " +
                        "Check your plan at aistudio.google.com; free quota resets daily.")
                }
                val errMsg = try {
                    val err = json.decodeFromString(GeminiResponse.serializer(), responseText)
                    err.error?.message?.takeIf { it.isNotBlank() } ?: "HTTP $code"
                } catch (_: Exception) {
                    "HTTP $code"
                }
                return@withContext Result.Failure("Gemini API error: $errMsg")
            }

                val response = json.decodeFromString(GeminiResponse.serializer(), responseText)
                val text = response.candidates.firstOrNull()
                    ?.content?.parts?.firstOrNull()?.text
                    ?: return@withContext Result.Failure("Empty response from Gemini")

                val suggestions = try {
                    // The model may wrap JSON in markdown fences; strip them.
                    val cleaned = text.trim()
                        .removePrefix("```json").removePrefix("```")
                        .removeSuffix("```").trim()
                    json.decodeFromString(AiSuggestions.serializer(), cleaned)
                } catch (e: Exception) {
                    return@withContext Result.Failure(
                        "Could not parse AI suggestions: ${e.message?.take(120)}",
                    )
                }
                Result.Success(suggestions, text)
        } catch (e: Exception) {
            Result.Failure("Network error: ${e.message?.take(150) ?: "unknown"}")
        }
    }

    /**
     * Free-text chat without an image (follow-up questions).
     */
    suspend fun chat(
        apiKey: String,
        history: List<AiChatMessage>,
        settingsSummary: String,
    ): Result = withContext(Dispatchers.IO) {
        try {
            val parts = buildList {
                add(
                    GeminiPart(
                        text = "You are an expert photo-editing coach inside the H2W Photo Lab " +
                            "Android app. The user's current edit settings are:\n$settingsSummary\n\n" +
                            "Answer concisely and practically. When suggesting setting values, " +
                            "use the app's ranges: exposure -5..+5, contrast/highlights/shadows/" +
                            "whites/blacks/vibrance/saturation/temp/tint -100..+100, " +
                            "sharpenAmount 0..100, noiseReduction 0..100, vignette -100..+100.",
                    ),
                )
                history.takeLast(10).forEach { msg ->
                    // Gemini uses role "user"/"model"
                    add(GeminiPart(text = "[${msg.role}]: ${msg.text}"))
                }
            }
            // Simpler: send as a single user content with history inline.
            val request = GeminiRequest(
                contents = listOf(GeminiContent(parts = parts)),
                generationConfig = GeminiGenConfig(responseMimeType = "text/plain"),
            )
            val body = json.encodeToString(GeminiRequest.serializer(), request)
            val (code, responseText, _) = postWithFallback(apiKey, body)
            if (code !in 200..299) {
                if (code == 429) {
                    return@withContext Result.Failure(
                        "Gemini API error: Quota exceeded (429) — your free quota is used up. " +
                        "Check your plan at aistudio.google.com; free quota resets daily.")
                }
                val errMsg = try {
                    val err = json.decodeFromString(GeminiResponse.serializer(), responseText)
                    err.error?.message?.takeIf { it.isNotBlank() } ?: "HTTP $code"
                } catch (_: Exception) {
                    "HTTP $code"
                }
                return@withContext Result.Failure("Gemini API error: $errMsg")
            }
                val response = json.decodeFromString(GeminiResponse.serializer(), responseText)
                val text = response.candidates.firstOrNull()
                    ?.content?.parts?.firstOrNull()?.text
                    ?: return@withContext Result.Failure("Empty response from Gemini")
                // Wrap free-text reply as suggestions with only analysis filled.
                Result.Success(AiSuggestions(analysis = text), text)
        } catch (e: Exception) {
            Result.Failure("Network error: ${e.message?.take(150) ?: "unknown"}")
        }
    }

    /**
     * Discovers which models this API key can actually use via GET /v1beta/models,
     * instead of relying on hardcoded model names (Google deprecates flash models
     * periodically — e.g. gemini-2.5-flash and gemini-1.5-flash both 404'd in Oct 2026).
     * Returns model names (without the "models/" prefix) that support generateContent,
     * flash models first, newest first. Falls back to [MODELS] when the query fails.
     * Result is cached per API key for the session.
     */
    suspend fun resolveModels(apiKey: String): List<String> = withContext(Dispatchers.IO) {
        cachedModels?.let { (cachedKey, models) ->
            if (cachedKey == apiKey) return@withContext models
        }
        val discovered = try {
            val url = URL("$BASE_URL/models")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("x-goog-api-key", apiKey)
                connectTimeout = 15_000
                readTimeout = 15_000
            }
            try {
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.let {
                    BufferedReader(InputStreamReader(it, StandardCharsets.UTF_8))
                        .use { r -> r.readText() }
                } ?: ""
                if (code !in 200..299) return@withContext MODELS.also {
                    cachedModels = apiKey to it
                }
                val parsed = try {
                    json.decodeFromString(ModelListResponse.serializer(), text)
                } catch (_: Exception) {
                    return@withContext MODELS.also { cachedModels = apiKey to it }
                }
                val usable = parsed.models
                    .filter { it.supportedGenerationMethods.contains("generateContent") }
                    .map { it.name.removePrefix("models/") }
                    .filter { it.isNotBlank() }
                    .distinct()
                if (usable.isEmpty()) MODELS
                else {
                    val flash = usable.filter { it.contains("flash", ignoreCase = true) }
                        .sortedDescending()
                    val rest = (usable - flash.toSet()).sortedDescending()
                    (flash + rest).distinct()
                }
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            MODELS
        }
        cachedModels = apiKey to discovered
        discovered
    }

    /**
     * POSTs to generateContent, trying each model in [MODELS] order.
     * Falls through on 404 (model deprecated/unavailable) to the next.
     * Returns (httpCode, responseText, modelUsed).
     */
    private suspend fun postWithFallback(apiKey: String, body: String): Triple<Int, String, String> {
        // Discovered models first (what the key can actually use), hardcoded list as backup.
        val models = (resolveModels(apiKey) + MODELS).distinct()
        var lastCode = 404
        var lastText = ""
        var lastModel = models.first()
        for (model in models) {
            lastModel = model
            try {
                val url = URL("$BASE_URL/models/$model:generateContent")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("x-goog-api-key", apiKey)
                    connectTimeout = 30_000
                    readTimeout = 60_000
                    doOutput = true
                }
                try {
                    OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use {
                        it.write(body)
                    }
                    val code = conn.responseCode
                    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                    val responseText = stream?.let { s ->
                        BufferedReader(InputStreamReader(s, StandardCharsets.UTF_8))
                            .use { it.readText() }
                    } ?: ""
                    // 404 = model not available → try next model.
                    // Other errors (401 bad key, 429 quota) → return immediately.
                    if (code == 404) {
                        lastCode = code; lastText = responseText
                        continue
                    }
                    return Triple(code, responseText, model)
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                // Network failure on this model → try next.
                lastText = e.message ?: "network error"
            }
        }
        return Triple(lastCode, lastText, lastModel)
    }

    private fun buildPrompt(settingsSummary: String, userQuestion: String?): String {
        val q = userQuestion?.takeIf { it.isNotBlank() }?.let { "\nUser question: $it" } ?: ""
        return """
You are an expert photo-editing coach inside the H2W Photo Lab Android app.
Analyze the attached photo and the user's current edit settings below, then suggest improved settings.

Current settings:
$settingsSummary
$q

Look at the photo's exposure, contrast, white balance, colors, and sharpness.
Suggest specific improved values. Only include fields you want to change; omit the rest (null = keep current).

Ranges:
- exposure: -5.0 to +5.0 (stops)
- contrast, highlights, shadows, whites, blacks, vibrance, saturation, temp, tint: -100 to +100
- sharpenAmount, noiseReduction: 0 to 100
- vignette: -100 to +100

Respond with ONLY a JSON object matching this schema (no markdown, no extra text):
{
  "analysis": "2-3 sentence analysis of what's wrong or good in the photo",
  "exposure": null, "contrast": null, "highlights": null, "shadows": null,
  "whites": null, "blacks": null, "vibrance": null, "saturation": null,
  "temp": null, "tint": null, "sharpenAmount": null, "noiseReduction": null,
  "vignette": null,
  "reasoning": "1-2 sentences explaining why these changes help"
}
        """.trimIndent()
    }

    companion object {
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
        // Fallback model list used when the ListModels discovery query fails
        // (offline, bad key, etc.). The live path is resolveModels(), which asks
        // the API which models the key can actually use — hardcoded names rot
        // quickly (gemini-2.5-flash and gemini-1.5-flash both 404'd in Oct 2026).
        val MODELS = listOf("gemini-2.0-flash", "gemini-1.5-flash")
        private const val MODEL = "gemini-2.0-flash"

        /** Per-key cache of discovered models for the session. */
        @Volatile
        private var cachedModels: Pair<String, List<String>>? = null
    }
}
