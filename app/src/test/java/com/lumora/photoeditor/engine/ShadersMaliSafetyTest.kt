package com.lumora.photoeditor.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * First-ever executed verification of the Mali GPU-safety language contract
 * on the ACTUAL shipped GLSL source strings ([Shaders.VERTEX],
 * [Shaders.FRAGMENT], [Shaders.BLUR_VERTEX], [Shaders.BLUR_FRAGMENT]).
 *
 * Why this matters on his vivo V60 (P0 history): the black-preview
 * regressions of 2026-10-17/18 all came down to shader-source details that
 * strict drivers resolve by blackening the frame instead of failing loudly:
 * - a `precision highp sampler2D;` default-precision statement (removed in
 *   cycle 1, 2026-10-15) that some Mali drivers reject;
 * - dynamically-indexed sampler arrays (`uMaskTex[i]` inside a loop),
 *   miscompiled by several Mali drivers — fixed by unrolling to constant
 *   indices, the fix that made masking work at all;
 * - unqualified samplers (`uniform sampler2D uImage;`), a spec-level
 *   compile error strict drivers are allowed to reject.
 *
 * Every cycle eyeballs these rules in the audit. This test pins them in
 * code so a future edit can never silently reintroduce the bug class: it
 * fails the build instead of shipping a black preview.
 *
 * What it pins, per program:
 * 1. Every program opens with `#version 300 es`.
 * 2. No `precision <q> sampler...` default-precision statements anywhere.
 * 3. Every sampler uniform declaration carries an explicit precision
 *    qualifier, with the exact expected sampler inventory per program (a
 *    new sampler that appears unannounced fails here first).
 * 4. Every `uName[...]` array access in real code uses a literal integer
 *    index — the unrolled-loop Mali rule, generalized to ALL uniform
 *    arrays (mask samplers, HSL bands, B&W mixer, deconv kernel, mask
 *    slot params). Comments are stripped first (the KDoc at the top of
 *    [Shaders] legitimately writes `uDeconTex[i]` in prose).
 * 5. The two mask sampler arrays are accessed exactly at slots 0..3 —
 *    pinning the unrolled x4 mask loop that fixed masking on Mali.
 *
 * Pure JVM: [Shaders] has zero Android imports.
 */
class ShadersMaliSafetyTest {

    private val programs = mapOf(
        "VERTEX" to Shaders.VERTEX,
        "FRAGMENT" to Shaders.FRAGMENT,
        "BLUR_VERTEX" to Shaders.BLUR_VERTEX,
        "BLUR_FRAGMENT" to Shaders.BLUR_FRAGMENT
    )

    /** GLSL with line comments and block comments removed. */
    private fun codeOf(src: String): String =
        src.replace(Regex("//.*"), "")
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")

    private val samplerDecl = Regex(
        "(?m)^\\s*uniform\\s+((?:highp|mediump|lowp)\\s+)?" +
            "(sampler2D|sampler3D)\\s+([A-Za-z_][A-Za-z0-9_]*)" +
            "\\s*(?:\\[(\\d+)\\])?\\s*;"
    )

    @Test
    fun everyProgramOpensWithEs300Version() {
        val bad = programs.filter { (_, src) -> !src.trimStart().startsWith("#version 300 es") }
        assertTrue(
            "shader programs not opening with '#version 300 es': ${bad.keys}",
            bad.isEmpty()
        )
    }

    @Test
    fun noPrecisionStatementOnSamplers() {
        // The cycle-1 bug class: `precision highp sampler2D;` — some Mali
        // drivers reject the program over it. Note `precision highp float;`
        // IS legal and stays.
        val stmt = Regex("(?m)^\\s*precision\\s+(?:highp|mediump|lowp)\\s+sampler(?:2D|3D|Cube)\\s*;")
        val hits = mutableListOf<String>()
        for ((name, src) in programs) {
            val code = codeOf(src)
            for (m in stmt.findAll(code)) hits += "$name: ${m.value.trim()}"
        }
        assertTrue(
            "precision-on-sampler statements found (Mali-rejected bug class): $hits",
            hits.isEmpty()
        )
    }

    @Test
    fun everySamplerDeclCarriesExplicitPrecision() {
        // Expected inventory per program: a sampler that appears unannounced
        // (or disappears) fails here, forcing a conscious contract update.
        val expected = mapOf(
            "VERTEX" to emptySet<String>(),
            "FRAGMENT" to setOf(
                "uImage", "uBlur", "uRetouch",
                "uAiLut", "uLookLut",       // sampler3D single samplers
                "uCurves", "uMaskTex", "uDeconTex", "uFxTex"
            ),
            "BLUR_VERTEX" to emptySet(),
            "BLUR_FRAGMENT" to setOf("uImage")
        )
        for ((name, src) in programs) {
            val code = codeOf(src)
            val decls = samplerDecl.findAll(code).toList()
            val unqualified = decls.filter { it.groupValues[1].isBlank() }.map { it.groupValues[3] }
            assertTrue(
                "$name: samplers without explicit precision qualifier (strict " +
                    "drivers may reject the program -> black preview): $unqualified",
                unqualified.isEmpty()
            )
            val found = decls.map { it.groupValues[3] }.toSet()
            assertEquals(
                "$name: sampler inventory drifted — update the contract or the shader",
                expected.getValue(name), found
            )
        }
    }

    @Test
    fun allUniformArrayAccessesUseConstantIndices() {
        // The unrolled-loop Mali rule: loop-variable indexing of uniform
        // arrays (uHsl[i], uBwMix[i], uMaskTex[i] ...) is miscompiled by
        // several Mali fragment-shader compilers. Every index below must be
        // a literal — this is what keeps masking and the HSL mixer alive
        // on his vivo V60.
        val access = Regex("\\bu([A-Za-z0-9_]*)\\[([^\\]]*)\\]")
        val hits = mutableListOf<String>()
        for ((name, src) in programs) {
            val code = codeOf(src)
            for (m in access.findAll(code)) {
                val idx = m.groupValues[2].trim()
                if (!idx.matches(Regex("\\d+"))) {
                    hits += "$name: ${m.value} (index '$idx' is not a constant)"
                }
            }
        }
        assertTrue(
            "non-constant uniform array indices found (Mali miscompile risk): $hits",
            hits.isEmpty()
        )
    }

    /** GLSL with uniform *declarations* removed — `[N]` there is a size, not an access. */
    private fun codeWithoutUniformDecls(src: String): String =
        codeOf(src).replace(
            Regex("(?m)^\\s*uniform\\s+(?:(?:highp|mediump|lowp)\\s+)?[A-Za-z0-9_]+\\s+[A-Za-z_][A-Za-z0-9_]*\\s*(?:\\[\\d+\\])?\\s*;"),
            ""
        )

    @Test
    fun maskSamplerArraysAccessedExactlyAtSlots0to3() {
        // Pins the unrolled x4 mask loop: uMaskTex and uDeconTex must be
        // sampled at literal slots 0..3 and nothing else. A regression to
        // `uMaskTex[i]` fails here before it ever reaches a Mali GPU.
        val code = codeWithoutUniformDecls(Shaders.FRAGMENT)
        for (sampler in listOf("uMaskTex", "uDeconTex")) {
            val slots = Regex("\\b${sampler}\\[(\\d+)\\]")
                .findAll(code).map { it.groupValues[1].toInt() }.toSet()
            assertEquals(
                "$sampler must be accessed exactly at slots 0..3",
                setOf(0, 1, 2, 3), slots
            )
        }
    }
}
