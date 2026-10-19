package com.lumora.photoeditor.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

/**
 * First executed verification of [PresetBackupDoc] — the versioned envelope
 * behind the one-tap custom-preset backup/restore (the safety net before the
 * one final reinstall). Pins the documented contract:
 * - wrap: null on empty list; exact envelope shape; null (never throw) when
 *   any doc is malformed
 * - unwrap: envelope round-trip; bare-array tolerance; non-object entries
 *   skipped; anything malformed -> empty list; never throws
 */
class PresetBackupDocTest {

    private fun envelopeOf(wrapped: String) =
        Json.parseToJsonElement(wrapped).jsonObject

    private val docA = """{"id":"custom-1","name":"My Look","category":"Custom"}"""
    private val docB =
        """{"id":"custom-2","name":"Evening \"warm\" ☀","params":{"light":{"exposure":0.5}}}"""

    @Test fun `wrap empty list returns null`() {
        assertNull(PresetBackupDoc.wrap(emptyList()))
    }

    @Test fun `wrap produces exact envelope fields`() {
        val wrapped = PresetBackupDoc.wrap(listOf(docA)) ?: error("expected envelope")
        val env = envelopeOf(wrapped)
        assertEquals("h2w-photo-lab", env["app"]!!.jsonPrimitive.content)
        assertEquals("preset-backup", env["kind"]!!.jsonPrimitive.content)
        assertEquals(PresetBackupDoc.VERSION, env["version"]!!.jsonPrimitive.int)
        assertEquals(1, env["version"]!!.jsonPrimitive.int)
        val arr = env["presets"]!!.jsonArray
        assertEquals(1, arr.size)
        assertEquals(Json.parseToJsonElement(docA), arr[0])
    }

    @Test fun `wrap preserves order of multiple docs`() {
        val docs = listOf(docA, docB, docA)
        val wrapped = PresetBackupDoc.wrap(docs) ?: error("expected envelope")
        val arr = envelopeOf(wrapped)["presets"]!!.jsonArray
        assertEquals(3, arr.size)
        docs.forEachIndexed { i, d ->
            assertEquals(Json.parseToJsonElement(d), arr[i])
        }
    }

    @Test fun `wrap returns null when a doc is malformed JSON`() {
        assertNull(PresetBackupDoc.wrap(listOf(docA, "{not json")))
    }

    @Test fun `wrap returns null for empty-string doc`() {
        assertNull(PresetBackupDoc.wrap(listOf("")))
    }

    @Test fun `wrap never throws on garbage`() {
        // Returns null rather than throwing — the only assertion is survival.
        PresetBackupDoc.wrap(listOf("}{", "   "))
    }

    @Test fun `unwrap round-trips wrap output exactly`() {
        val docs = listOf(docA, docB)
        val wrapped = PresetBackupDoc.wrap(docs) ?: error("expected envelope")
        val out = PresetBackupDoc.unwrap(wrapped)
        assertEquals(docs.size, out.size)
        docs.forEachIndexed { i, d ->
            assertEquals(
                Json.parseToJsonElement(d),
                Json.parseToJsonElement(out[i]),
            )
        }
    }

    @Test fun `unwrap accepts bare array of objects`() {
        val out = PresetBackupDoc.unwrap("[$docA,$docB]")
        assertEquals(2, out.size)
        assertEquals(Json.parseToJsonElement(docA), Json.parseToJsonElement(out[0]))
        assertEquals(Json.parseToJsonElement(docB), Json.parseToJsonElement(out[1]))
    }

    @Test fun `unwrap skips non-object array entries`() {
        val out = PresetBackupDoc.unwrap("""[$docA, 123, "str", null, $docB, [1,2]]""")
        assertEquals(2, out.size)
    }

    @Test fun `unwrap returns empty list for malformed content`() {
        assertTrue(PresetBackupDoc.unwrap("{not json").isEmpty())
    }

    @Test fun `unwrap returns empty list for empty string`() {
        assertTrue(PresetBackupDoc.unwrap("").isEmpty())
    }

    @Test fun `unwrap returns empty list when envelope has no presets array`() {
        assertTrue(
            PresetBackupDoc.unwrap(
                """{"app":"h2w-photo-lab","kind":"preset-backup","version":1}""",
            ).isEmpty(),
        )
        assertTrue(
            PresetBackupDoc.unwrap("""{"app":"h2w-photo-lab","presets":"nope"}""").isEmpty(),
        )
        assertTrue(
            PresetBackupDoc.unwrap("""{"app":"h2w-photo-lab","presets":null}""").isEmpty(),
        )
    }

    @Test fun `unwrap returns empty list for empty presets array`() {
        assertTrue(
            PresetBackupDoc.unwrap(
                """{"app":"h2w-photo-lab","kind":"preset-backup","version":1,"presets":[]}""",
            ).isEmpty(),
        )
    }

    @Test fun `unwrap returns empty list for bare non-object JSON`() {
        assertTrue(PresetBackupDoc.unwrap("123").isEmpty())
        assertTrue(PresetBackupDoc.unwrap(""""just a string"""").isEmpty())
        assertTrue(PresetBackupDoc.unwrap("""["a","b"]""").isEmpty())
    }

    @Test fun `wrap accepts non-object JSON docs but unwrap drops them`() {
        // wrap is shape-agnostic; unwrap only keeps objects (documented).
        val wrapped = PresetBackupDoc.wrap(listOf(docA, "123")) ?: error("expected envelope")
        val out = PresetBackupDoc.unwrap(wrapped)
        assertEquals(1, out.size)
        assertEquals(Json.parseToJsonElement(docA), Json.parseToJsonElement(out[0]))
    }

    @Test fun `unwrap tolerates unknown envelope fields and versions`() {
        val env = """{"app":"h2w-photo-lab","kind":"preset-backup","version":99,"extra":{"x":1},"presets":[$docA]}"""
        val out = PresetBackupDoc.unwrap(env)
        assertEquals(1, out.size)
        assertEquals(Json.parseToJsonElement(docA), Json.parseToJsonElement(out[0]))
    }

    @Test fun `unwrap never throws on garbage`() {
        PresetBackupDoc.unwrap("}{")
        PresetBackupDoc.unwrap("   ")
    }
}
