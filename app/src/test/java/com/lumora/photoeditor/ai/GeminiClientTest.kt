package com.lumora.photoeditor.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * First-ever executed verification of GeminiClient's pure response-parsing
 * contract — the code that turns the wire [GeminiResponse] into the
 * [AiSuggestions] JSON the app decodes. Previously only exercised by the
 * network-bound suspend functions (analyzePhoto/chat) and by on-device use;
 * the multi-part concatenation fix (2026-10-18) had zero regression cover.
 */
class GeminiClientTest {

    private val client = GeminiClient()

    private fun resp(vararg candidates: GeminiCandidate) = GeminiResponse(candidates.toList())

    private fun cand(vararg texts: String?) =
        GeminiCandidate(GeminiContent(parts = texts.map { GeminiPart(text = it) }))

    // --- responseTextOf ---

    @Test fun singlePartReplyReturnedAsIs() {
        assertEquals("hello", client.responseTextOf(resp(cand("hello"))))
    }

    @Test fun multiPartReplyConcatenated() {
        // The 2026-10-18 regression: reading only parts.first() truncated the
        // suggestions JSON and surfaced as "Could not parse AI suggestions".
        assertEquals(
            "{\"a\":1}",
            client.responseTextOf(resp(cand("{", "\"a\":1", "}"))),
        )
    }

    @Test fun firstContentBearingCandidateWins() {
        val r = resp(
            GeminiCandidate(content = null),
            cand("second"),
        )
        assertEquals("second", client.responseTextOf(r))
    }

    @Test fun firstOfTwoContentBearingCandidatesWins() {
        // firstNotNullOfOrNull semantics: the API's first candidate wins.
        val r = resp(cand("first"), cand("second"))
        assertEquals("first", client.responseTextOf(r))
    }

    @Test fun nullTextPartsAreSkipped() {
        assertEquals("x", client.responseTextOf(resp(cand(null, "x", null))))
    }

    @Test fun noCandidatesYieldsNull() {
        assertNull(client.responseTextOf(resp()))
    }

    @Test fun nullContentYieldsNull() {
        assertNull(client.responseTextOf(resp(GeminiCandidate(content = null))))
    }

    @Test fun emptyPartsYieldsNull() {
        assertNull(client.responseTextOf(resp(GeminiCandidate(GeminiContent(parts = emptyList())))))
    }

    @Test fun allNullTextYieldsNull() {
        assertNull(client.responseTextOf(resp(cand(null, null))))
    }

    // --- stripCodeFences ---

    @Test fun plainJsonUnchanged() {
        assertEquals("{\"a\":1}", stripCodeFences("{\"a\":1}"))
    }

    @Test fun jsonFenceStripped() {
        assertEquals("{\"a\":1}", stripCodeFences("```json\n{\"a\":1}\n```"))
    }

    @Test fun plainFenceStripped() {
        assertEquals("{\"a\":1}", stripCodeFences("```\n{\"a\":1}\n```"))
    }

    @Test fun trailingFenceOnlyStripped() {
        assertEquals("{\"a\":1}", stripCodeFences("{\"a\":1}```"))
    }

    @Test fun surroundingWhitespaceHandled() {
        // Outer trim runs before the prefix/suffix removal.
        assertEquals("{\"a\":1}", stripCodeFences("  ```json\n{\"a\":1}\n```  "))
    }

    @Test fun emptyStaysEmpty() {
        assertEquals("", stripCodeFences(""))
    }

    @Test fun fenceOnlyYieldsEmpty() {
        assertEquals("", stripCodeFences("```json\n```"))
    }

    @Test fun uppercaseJsonFenceOnlyPartiallyStripped() {
        // Documents the current contract: removePrefix is case-sensitive, so
        // a "```JSON" fence loses its backticks (via the plain "```" prefix)
        // but keeps the "JSON" text, and the reply still fails to parse. The
        // prompt instructs the model to return bare JSON, so this is the
        // pinned behavior, not a supported case.
        assertEquals(
            "JSON\n{\"a\":1}",
            stripCodeFences("```JSON\n{\"a\":1}\n```"),
        )
    }
}
