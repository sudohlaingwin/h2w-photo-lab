package com.lumora.photoeditor.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A single chat message in the AI Assistant panel. */
@Serializable
data class AiChatMessage(
    val role: String, // "user" or "model"
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
)

/** Structured editing suggestions returned by Gemini. */
@Serializable
data class AiSuggestions(
    val analysis: String = "",
    // Light
    val exposure: Float? = null,
    val contrast: Float? = null,
    val highlights: Float? = null,
    val shadows: Float? = null,
    val whites: Float? = null,
    val blacks: Float? = null,
    // Color
    val vibrance: Float? = null,
    val saturation: Float? = null,
    val temp: Float? = null,
    val tint: Float? = null,
    // Detail
    val sharpenAmount: Float? = null,
    val noiseReduction: Float? = null,
    // Optics
    val vignette: Float? = null,
    val reasoning: String = "",
)

/**
 * Stable field keys for [AiSuggestions]. The suggestion card lets the user
 * tick which suggestions to apply instead of all-or-nothing; these keys
 * identify the checkbox rows and the fields [AiSuggestions.only] keeps.
 */
object AiSuggestionKeys {
    const val EXPOSURE = "exposure"
    const val CONTRAST = "contrast"
    const val HIGHLIGHTS = "highlights"
    const val SHADOWS = "shadows"
    const val WHITES = "whites"
    const val BLACKS = "blacks"
    const val VIBRANCE = "vibrance"
    const val SATURATION = "saturation"
    const val TEMP = "temp"
    const val TINT = "tint"
    const val SHARPEN = "sharpen"
    const val NOISE_REDUCTION = "noiseReduction"
    const val VIGNETTE = "vignette"
}

/**
 * Returns a copy with every field NOT in [keys] nulled out. Applying the
 * result keeps the current photo values for unchecked suggestions, because
 * applyAiSuggestions treats null as "keep current".
 */
fun AiSuggestions.only(keys: Set<String>): AiSuggestions = copy(
    exposure = exposure.takeIf { AiSuggestionKeys.EXPOSURE in keys },
    contrast = contrast.takeIf { AiSuggestionKeys.CONTRAST in keys },
    highlights = highlights.takeIf { AiSuggestionKeys.HIGHLIGHTS in keys },
    shadows = shadows.takeIf { AiSuggestionKeys.SHADOWS in keys },
    whites = whites.takeIf { AiSuggestionKeys.WHITES in keys },
    blacks = blacks.takeIf { AiSuggestionKeys.BLACKS in keys },
    vibrance = vibrance.takeIf { AiSuggestionKeys.VIBRANCE in keys },
    saturation = saturation.takeIf { AiSuggestionKeys.SATURATION in keys },
    temp = temp.takeIf { AiSuggestionKeys.TEMP in keys },
    tint = tint.takeIf { AiSuggestionKeys.TINT in keys },
    sharpenAmount = sharpenAmount.takeIf { AiSuggestionKeys.SHARPEN in keys },
    noiseReduction = noiseReduction.takeIf { AiSuggestionKeys.NOISE_REDUCTION in keys },
    vignette = vignette.takeIf { AiSuggestionKeys.VIGNETTE in keys },
)

/** UI state for the AI Assistant panel. */
data class AiAssistantUiState(
    val messages: List<AiChatMessage> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val pendingSuggestions: AiSuggestions? = null,
    val hasApiKey: Boolean = false,
)

// --- Gemini API wire models ---

@Serializable
data class GeminiRequest(
    val contents: List<GeminiContent>,
    @SerialName("generationConfig")
    val generationConfig: GeminiGenConfig = GeminiGenConfig(),
)

@Serializable
data class GeminiContent(
    val role: String = "user",
    val parts: List<GeminiPart>,
)

@Serializable
data class GeminiPart(
    val text: String? = null,
    @SerialName("inline_data")
    val inlineData: GeminiInlineData? = null,
)

@Serializable
data class GeminiInlineData(
    @SerialName("mime_type")
    val mimeType: String,
    val data: String, // base64
)

@Serializable
data class GeminiGenConfig(
    @SerialName("temperature")
    val temperature: Double = 0.4,
    @SerialName("maxOutputTokens")
    val maxOutputTokens: Int = 2048,
    @SerialName("responseMimeType")
    val responseMimeType: String = "application/json",
)

@Serializable
data class GeminiResponse(
    val candidates: List<GeminiCandidate> = emptyList(),
    val error: GeminiError? = null,
)

@Serializable
data class GeminiCandidate(
    val content: GeminiContent? = null,
)

@Serializable
data class GeminiError(
    val code: Int = 0,
    val message: String = "",
    val status: String = "",
)

/** Response of GET /v1beta/models — used to discover which models the key can use. */
@Serializable
data class ModelListResponse(
    val models: List<ModelInfo> = emptyList(),
)

@Serializable
data class ModelInfo(
    val name: String = "",
    val supportedGenerationMethods: List<String> = emptyList(),
)
