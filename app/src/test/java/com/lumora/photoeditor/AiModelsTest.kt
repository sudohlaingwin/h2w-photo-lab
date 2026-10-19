package com.lumora.photoeditor

import com.lumora.photoeditor.ai.AiAssistantUiState
import com.lumora.photoeditor.ai.AiChatMessage
import com.lumora.photoeditor.ai.AiSuggestionKeys
import com.lumora.photoeditor.ai.AiSuggestions
import com.lumora.photoeditor.ai.GeminiCandidate
import com.lumora.photoeditor.ai.GeminiContent
import com.lumora.photoeditor.ai.GeminiError
import com.lumora.photoeditor.ai.GeminiGenConfig
import com.lumora.photoeditor.ai.GeminiInlineData
import com.lumora.photoeditor.ai.GeminiPart
import com.lumora.photoeditor.ai.GeminiRequest
import com.lumora.photoeditor.ai.GeminiResponse
import com.lumora.photoeditor.ai.ModelInfo
import com.lumora.photoeditor.ai.ModelListResponse
import com.lumora.photoeditor.ai.only
import kotlinx.serialization.SerialName
import org.junit.Assert.*
import org.junit.Test

/**
 * Executed verification for ai/AiModels.kt (pure Kotlin + kotlinx annotations —
 * runs on the standalone kotlinc harness; Gradle's test workers are broken in
 * this env).
 *
 * Locks two contracts a silent regression would break on his phone:
 *
 * 1. [AiSuggestions.only] — the suggestion card lets the user tick which AI
 *    suggestions to apply instead of all-or-nothing; applyAiSuggestions
 *    treats null as "keep current", so `only` must null exactly the
 *    un-ticked fields and keep exactly the ticked ones.
 * 2. The Gemini REST wire contract — @SerialName values are the exact JSON
 *    keys Google's API expects (mixed convention: camelCase `generationConfig`
 *    but snake_case `inline_data`/`mime_type` is deliberate, not a typo).
 *    A rename here 400s every AI request. The serialization compiler plugin
 *    cannot run in this harness, so serial names are pinned via the
 *    runtime-retained annotations instead of Json.encodeToString.
 */
class AiModelsTest {

    // ---------- only() contract ----------

    private fun full(): AiSuggestions = AiSuggestions(
        analysis = "a",
        exposure = 0.5f, contrast = 10f, highlights = -20f, shadows = 30f,
        whites = 5f, blacks = -5f,
        vibrance = 15f, saturation = -10f, temp = 42f, tint = 3f,
        sharpenAmount = 25f, noiseReduction = 40f, vignette = -12f,
        reasoning = "r",
    )

    private fun keptFields(s: AiSuggestions): List<String> = listOf(
        "exposure" to s.exposure, "contrast" to s.contrast,
        "highlights" to s.highlights, "shadows" to s.shadows,
        "whites" to s.whites, "blacks" to s.blacks,
        "vibrance" to s.vibrance, "saturation" to s.saturation,
        "temp" to s.temp, "tint" to s.tint,
        "sharpenAmount" to s.sharpenAmount,
        "noiseReduction" to s.noiseReduction, "vignette" to s.vignette,
    ).filter { it.second != null }.map { it.first }

    @Test fun only_emptyKeys_nullsEveryNullableField() {
        val out = full().only(emptySet())
        assertTrue("no nullable field may survive empty keys", keptFields(out).isEmpty())
    }

    @Test fun only_emptyKeys_preservesAnalysisAndReasoning() {
        val out = full().only(emptySet())
        assertEquals("a", out.analysis)
        assertEquals("r", out.reasoning)
    }

    @Test fun only_allKeys_keepsEverything() {
        val all = setOf(
            AiSuggestionKeys.EXPOSURE, AiSuggestionKeys.CONTRAST,
            AiSuggestionKeys.HIGHLIGHTS, AiSuggestionKeys.SHADOWS,
            AiSuggestionKeys.WHITES, AiSuggestionKeys.BLACKS,
            AiSuggestionKeys.VIBRANCE, AiSuggestionKeys.SATURATION,
            AiSuggestionKeys.TEMP, AiSuggestionKeys.TINT,
            AiSuggestionKeys.SHARPEN, AiSuggestionKeys.NOISE_REDUCTION,
            AiSuggestionKeys.VIGNETTE,
        )
        assertEquals(full(), full().only(all))
    }

    @Test fun only_selectiveKeys_keepExactlyTheTickedFields() {
        val out = full().only(setOf(AiSuggestionKeys.EXPOSURE, AiSuggestionKeys.TEMP))
        assertEquals(listOf("exposure", "temp"), keptFields(out).sorted())
        assertEquals(0.5f, out.exposure)
        assertEquals(42f, out.temp)
    }

    @Test fun only_doesNotMutateInput() {
        val before = full()
        before.only(setOf(AiSuggestionKeys.EXPOSURE))
        assertEquals(full(), before)
    }

    @Test fun only_unknownKeysIgnored() {
        val out = full().only(setOf("not-a-real-key"))
        assertTrue(keptFields(out).isEmpty())
    }

    // ---------- key <-> field wiring ----------

    /** Key -> the single AiSuggestions field it must keep (SHARPEN maps to
     *  sharpenAmount — the one name-mismatch seam in the file). */
    private val keyToField = mapOf(
        AiSuggestionKeys.EXPOSURE to "exposure",
        AiSuggestionKeys.CONTRAST to "contrast",
        AiSuggestionKeys.HIGHLIGHTS to "highlights",
        AiSuggestionKeys.SHADOWS to "shadows",
        AiSuggestionKeys.WHITES to "whites",
        AiSuggestionKeys.BLACKS to "blacks",
        AiSuggestionKeys.VIBRANCE to "vibrance",
        AiSuggestionKeys.SATURATION to "saturation",
        AiSuggestionKeys.TEMP to "temp",
        AiSuggestionKeys.TINT to "tint",
        AiSuggestionKeys.SHARPEN to "sharpenAmount",
        AiSuggestionKeys.NOISE_REDUCTION to "noiseReduction",
        AiSuggestionKeys.VIGNETTE to "vignette",
    )

    @Test fun keys_areUniqueAndNonBlank() {
        val vals = keyToField.keys.toList()
        assertEquals(13, vals.size)
        assertTrue(vals.all { it.isNotBlank() })
        assertEquals(13, vals.toSet().size)
    }

    @Test fun keys_coverAllThirteenNullableFields_exactlyOneFieldPerKey() {
        val base = full()
        for ((key, field) in keyToField) {
            val kept = keptFields(base.only(setOf(key)))
            assertEquals("key '$key' must keep exactly [$field]", listOf(field), kept)
        }
    }

    @Test fun sharpenKey_mapsToSharpenAmountField() {
        val out = full().only(setOf(AiSuggestionKeys.SHARPEN))
        assertEquals(25f, out.sharpenAmount)
        assertNull("noiseReduction must not leak through the SHARPEN key", out.noiseReduction)
    }

    // ---------- Gemini wire-model defaults ----------

    @Test fun genConfig_jsonModeDefaults() {
        val c = GeminiGenConfig()
        assertEquals(0.4, c.temperature, 0.0)
        assertEquals(2048, c.maxOutputTokens)
        assertEquals("application/json", c.responseMimeType)
    }

    @Test fun request_defaultGenConfigAndUserRole() {
        val req = GeminiRequest(contents = listOf(GeminiContent(parts = listOf(GeminiPart(text = "hi")))))
        assertEquals(0.4, req.generationConfig.temperature, 0.0)
        assertEquals("user", req.contents.single().role)
    }

    @Test fun response_errorDefaults() {
        val r = GeminiResponse()
        assertTrue(r.candidates.isEmpty())
        assertNull(r.error)
        val e = GeminiError()
        assertEquals(0, e.code)
        assertEquals("", e.message)
        assertEquals("", e.status)
    }

    @Test fun candidateAndPart_defaults() {
        assertNull(GeminiCandidate().content)
        val p = GeminiPart()
        assertNull(p.text)
        assertNull(p.inlineData)
    }

    @Test fun modelList_defaults() {
        assertTrue(ModelListResponse().models.isEmpty())
        val m = ModelInfo()
        assertEquals("", m.name)
        assertTrue(m.supportedGenerationMethods.isEmpty())
    }

    @Test fun chatAndUiState_defaults() {
        val msg = AiChatMessage(role = "user", text = "hi")
        assertTrue("timestamp default must be sane", msg.timestamp >= 0)
        val st = AiAssistantUiState()
        assertTrue(st.messages.isEmpty())
        assertFalse(st.isLoading)
        assertNull(st.error)
        assertNull(st.pendingSuggestions)
        assertFalse(st.hasApiKey)
    }

    @Test fun suggestions_defaults() {
        val s = AiSuggestions()
        assertEquals("", s.analysis)
        assertEquals("", s.reasoning)
        assertTrue(keptFields(s).isEmpty())
    }

    // ---------- @SerialName wire contract (runtime-retained) ----------

    /**
     * Reads the @SerialName the Kotlin compiler kept for [property] (it is
     * emitted on the synthetic `getX$annotations` method), or null when the
     * property relies on its default serial name.
     */
    private fun serialNameOf(cls: Class<*>, property: String): String? {
        val cap = property.replaceFirstChar { it.uppercaseChar() }
        val annMethods = listOf(
            runCatching { cls.getDeclaredMethod("get${cap}\$annotations") }.getOrNull(),
            runCatching { cls.getDeclaredMethod("get$cap") }.getOrNull(),
        ).filterNotNull()
        for (m in annMethods) {
            m.getAnnotation(SerialName::class.java)?.let { return it.value }
        }
        runCatching { cls.getDeclaredField(property) }.getOrNull()
            ?.getAnnotation(SerialName::class.java)?.let { return it.value }
        return null
    }

    @Test fun wire_generationConfigIsCamelCase() {
        assertEquals("generationConfig", serialNameOf(GeminiRequest::class.java, "generationConfig"))
    }

    @Test fun wire_imagePartsAreSnakeCase() {
        assertEquals("inline_data", serialNameOf(GeminiPart::class.java, "inlineData"))
        assertEquals("mime_type", serialNameOf(GeminiInlineData::class.java, "mimeType"))
    }

    @Test fun wire_genConfigNames() {
        val c = GeminiGenConfig::class.java
        assertEquals("temperature", serialNameOf(c, "temperature"))
        assertEquals("maxOutputTokens", serialNameOf(c, "maxOutputTokens"))
        assertEquals("responseMimeType", serialNameOf(c, "responseMimeType"))
    }

    @Test fun wire_errorFieldsUseDefaultNames() {
        // The 429 quota path decodes {"code","message","status"} — these must
        // stay default-named; a @SerialName here would silently break parsing.
        val c = GeminiError::class.java
        assertNull(serialNameOf(c, "code"))
        assertNull(serialNameOf(c, "message"))
        assertNull(serialNameOf(c, "status"))
    }

    @Test fun wire_serialNamesUniquePerClass() {
        val classes = listOf(
            GeminiRequest::class.java, GeminiContent::class.java,
            GeminiPart::class.java, GeminiInlineData::class.java,
            GeminiGenConfig::class.java, GeminiResponse::class.java,
            GeminiCandidate::class.java, GeminiError::class.java,
            ModelListResponse::class.java, ModelInfo::class.java,
            AiChatMessage::class.java, AiSuggestions::class.java,
        )
        for (cls in classes) {
            val names = cls.declaredFields
                .filter { !it.isSynthetic && !it.name.startsWith("\$") }
                .map { serialNameOf(cls, it.name) ?: it.name }
            assertEquals(
                "${cls.simpleName}: serial names must be unique",
                names.size, names.toSet().size,
            )
        }
    }
}
