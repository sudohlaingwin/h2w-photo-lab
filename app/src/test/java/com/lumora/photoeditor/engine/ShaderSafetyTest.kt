package com.lumora.photoeditor.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Executed verification of [ShaderSafety] — the static Mali-safety audit
 * for the shipped GLSL. The source KDoc claims the audit "is covered by
 * [ShaderSafetyTest] unit tests"; this file makes that claim true.
 *
 * Pins the observable contract of all nine rules:
 *  (a) dynamic-sampler-index — sampler arrays must be indexed with
 *      constant expressions (the mask-pipeline Mali miscompile);
 *  (b) precision-on-sampler — `precision highp sampler2D;` is rejected;
 *  (c) unqualified-sampler — samplers without an explicit precision in
 *      `#version 300 es` sources (a spec-level compile error on strict
 *      drivers; legitimately fine in ES 1.00 sources);
 *  (d) dynamic-loop-bound — `for` trip counts must be compile-time
 *      constant (loop variable + numeric literals only);
 *  (e) dynamic-while-condition — `while`/`do`-`while` conditions must be
 *      constant (`while (true)` with an internal break is the allowed
 *      form);
 *  (f) dynamic-array-index — the same dynamic-indexing hazard for
 *      NON-sampler arrays (uniforms like `uHsl[8]` and locals like
 *      `float centers[8];`; the shipped HSL/B&W mixer loops shipped
 *      exactly this violation until 2026-10-19);
 *  (g) es100-builtin — ES 1.00-only names (`gl_FragColor`,
 *      `texture2D*`, `varying`, `attribute`, …) in an ES 3.00 source;
 *  (h) reserved-identifier — GLSL-ES-3.00 future-reserved words
 *      (`sample`, `input`, `filter`, …) used as variable/function names;
 *  (i) varying-precision-mismatch — vertex `out` / fragment `in` pairs
 *      must agree on effective precision (the int-family stage defaults
 *      differ: vertex highp vs fragment mediump, so even unqualified
 *      pairs can mismatch).
 *
 * Also pins the issue metadata shape (1-based line numbers, shader name
 * pass-through, comment-stripped offending line), comment handling
 * (line and block comments never produce issues; block comments keep
 * line numbers accurate), and the headline guarantee: [ShaderSafety.auditShipped]
 * on the REAL shipped shaders returns zero issues.
 */
class ShaderSafetyTest {

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun auditOne(name: String = "TEST", src: String): List<ShaderSafetyIssue> =
        ShaderSafety.audit(listOf(name to src))

    private fun rules(issues: List<ShaderSafetyIssue>): List<String> =
        issues.map { it.rule }

    // ------------------------------------------------------------------
    // Rule (a): dynamic sampler-array indexing
    // ------------------------------------------------------------------

    @Test
    fun ruleA_loopIndexedSamplerArray_isFlagged() {
        val src = """
            uniform highp sampler2D uMaskTex[4];
            void main() {
                for (int i = 0; i < 4; i++) {
                    float m = texture(uMaskTex[i], uv).r;
                }
            }
        """.trimIndent()
        val issues = auditOne(src = src)
        assertEquals(listOf("dynamic-sampler-index"), rules(issues))
        assertEquals(4, issues[0].lineNumber) // 1-based: the texture() line
        assertEquals("TEST", issues[0].shaderName)
        // Synthesized message names the array and the offending index.
        assertTrue(issues[0].line.contains("uMaskTex"))
        assertTrue(issues[0].line.contains("[i]"))
    }

    @Test
    fun ruleA_uniformIndexedSamplerArray_isFlagged() {
        val src = """
            uniform highp sampler2D uTex[4];
            uniform int uWhich;
            void main() { float m = texture(uTex[uWhich], uv).r; }
        """.trimIndent()
        val issues = auditOne(src = src)
        assertEquals(listOf("dynamic-sampler-index"), rules(issues))
    }

    @Test
    fun ruleA_constantIndexedSamplerArray_isClean() {
        // The shipped mask pipeline's unrolled form: every index constant.
        val src = """
            uniform highp sampler2D uMaskTex[4];
            void main() {
                float m0 = texture(uMaskTex[0], uv).r;
                float m1 = texture(uMaskTex[1], uv).r;
                float m2 = texture(uMaskTex[2], uv).r;
                float m3 = texture(uMaskTex[3], uv).r;
            }
        """.trimIndent()
        assertTrue(auditOne(src = src).isEmpty())
    }

    @Test
    fun ruleA_sampler3DArray_isFlagged() {
        val src = """
            uniform highp sampler3D uLut[2];
            void main() { vec4 c = texture(uLut[i], vec3(0.5)); }
        """.trimIndent()
        val issues = auditOne(src = src)
        assertEquals(listOf("dynamic-sampler-index"), rules(issues))
    }

    // ------------------------------------------------------------------
    // Rule (b): precision statements on sampler types
    // ------------------------------------------------------------------

    @Test
    fun ruleB_precisionOnSampler_isFlagged() {
        val src = """
            precision highp float;
            precision highp sampler2D;
            void main() { }
        """.trimIndent()
        val issues = auditOne(src = src)
        assertEquals(listOf("precision-on-sampler"), rules(issues))
        assertEquals(2, issues[0].lineNumber)
    }

    @Test
    fun ruleB_precisionOnFloat_isClean() {
        val src = """
            precision highp float;
            precision mediump int;
            void main() { }
        """.trimIndent()
        assertTrue(auditOne(src = src).isEmpty())
    }

    // ------------------------------------------------------------------
    // Rule (c): unqualified samplers in ES 3.00 sources
    // ------------------------------------------------------------------

    @Test
    fun ruleC_unqualifiedSampler_inEs300_isFlagged() {
        val src = """
            #version 300 es
            precision highp float;
            uniform sampler2D uImage;
            void main() { }
        """.trimIndent()
        val issues = auditOne(src = src)
        assertEquals(listOf("unqualified-sampler"), rules(issues))
        assertEquals(3, issues[0].lineNumber)
        // The offending line is reported with comments stripped.
        assertEquals("uniform sampler2D uImage;", issues[0].line)
    }

    @Test
    fun ruleC_qualifiedSampler_inEs300_isClean() {
        val src = """
            #version 300 es
            precision highp float;
            uniform highp sampler2D uImage;
            uniform mediump sampler3D uLut;
            void main() { }
        """.trimIndent()
        assertTrue(auditOne(src = src).isEmpty())
    }

    @Test
    fun ruleC_unqualifiedSampler_inEs100_isClean() {
        // ES 1.00 fragment shaders predeclare a default sampler
        // precision — the rule must not fire there.
        val src = """
            precision highp float;
            uniform sampler2D uImage;
            void main() { gl_FragColor = texture2D(uImage, vec2(0.5)); }
        """.trimIndent()
        assertTrue(auditOne(src = src).isEmpty())
    }

    // ------------------------------------------------------------------
    // Rule (d): dynamic for-loop bounds
    // ------------------------------------------------------------------

    @Test
    fun ruleD_constantLoopBound_isClean() {
        val src = """
            void main() {
                for (int i = 0; i < 8; i++) { }
            }
        """.trimIndent()
        assertTrue(auditOne(src = src).isEmpty())
    }

    @Test
    fun ruleD_uniformLoopBound_isFlagged() {
        val src = """
            uniform int uCount;
            void main() {
                for (int i = 0; i < uCount; i++) { }
            }
        """.trimIndent()
        val issues = auditOne(src = src)
        assertEquals(listOf("dynamic-loop-bound"), rules(issues))
        assertEquals(3, issues[0].lineNumber)
    }

    @Test
    fun ruleD_loopVarInBound_isClean() {
        // The bound may name the loop variable itself.
        val src = """
            void main() {
                for (int i = 0; i < 4 * i + 2; i++) { }
            }
        """.trimIndent()
        assertTrue(auditOne(src = src).isEmpty())
    }

    @Test
    fun ruleD_multilineForHeader_reportsCorrectLine() {
        val src = "void main() {\n" +
            "    for (int i = 0;\n" +
            "         i < uCount;\n" +
            "         i++) { }\n" +
            "}"
        val issues = auditOne(src = src)
        assertEquals(listOf("dynamic-loop-bound"), rules(issues))
        assertEquals(2, issues[0].lineNumber) // line of the `for`
    }

    // ------------------------------------------------------------------
    // Rule (e): dynamic while conditions
    // ------------------------------------------------------------------

    @Test
    fun ruleE_identifierWhileCondition_isFlagged() {
        val src = """
            uniform int uCount;
            void main() {
                int n = uCount;
                while (n > 0) { n--; }
            }
        """.trimIndent()
        val issues = auditOne(src = src)
        assertEquals(listOf("dynamic-while-condition"), rules(issues))
        assertEquals(4, issues[0].lineNumber)
    }

    @Test
    fun ruleE_whileTrue_isClean() {
        val src = """
            void main() {
                while (true) { if (done) break; }
            }
        """.trimIndent()
        assertTrue(auditOne(src = src).isEmpty())
    }

    @Test
    fun ruleE_doWhileWithIdentifier_isFlagged() {
        val src = """
            uniform int uCount;
            void main() {
                int n = uCount;
                do { n--; } while (n > 0);
            }
        """.trimIndent()
        val issues = auditOne(src = src)
        assertEquals(listOf("dynamic-while-condition"), rules(issues))
    }

    // ------------------------------------------------------------------
    // Rule (f): dynamic indexing of NON-sampler arrays
    // ------------------------------------------------------------------

    @Test
    fun ruleF_uniformFloatArrayLoopIndexed_isFlagged() {
        // The exact violation the shipped HSL/B&W mixer loops carried
        // until 2026-10-19.
        val src = """
            uniform float uHsl[8];
            void main() {
                float x = 0.0;
                for (int i = 0; i < 8; i++) { x += uHsl[i]; }
            }
        """.trimIndent()
        val issues = auditOne(src = src)
        assertEquals(listOf("dynamic-array-index"), rules(issues))
        assertEquals(4, issues[0].lineNumber)
    }

    @Test
    fun ruleF_localArrayIndexed_isFlagged() {
        val src = """
            void main() {
                float centers[8];
                float x = centers[i];
            }
        """.trimIndent()
        val issues = auditOne(src = src)
        assertEquals(listOf("dynamic-array-index"), rules(issues))
    }

    @Test
    fun ruleF_initializedLocalArrayDynamicallyIndexed_isFlagged() {
        // The hole this closes: the old local-array pattern required a
        // trailing `;`, and Pass 1b skipped every line containing `(` —
        // so an initialized declaration (`float lut[4] = float[4](...);`)
        // registered nothing and a dynamic index into it slipped past
        // rule (f) entirely.
        val src = """
            void main() {
                float lut[4] = float[4](0.0, 0.33, 0.66, 1.0);
                float y = lut[i];
            }
        """.trimIndent()
        val issues = auditOne(src = src)
        assertEquals(listOf("dynamic-array-index"), rules(issues))
        assertEquals(3, issues[0].lineNumber)
    }

    @Test
    fun ruleF_initializedLocalArrayConstantIndexed_isClean() {
        val src = """
            void main() {
                float lut[4] = float[4](0.0, 0.33, 0.66, 1.0);
                float y = lut[0] + lut[3];
            }
        """.trimIndent()
        assertTrue(auditOne(src = src).isEmpty())
    }

    @Test
    fun ruleF_constInitializedLocalArrayDynamicallyIndexed_isFlagged() {
        val src = """
            void main() {
                const vec3 palette[2] = vec3[2](vec3(1.0), vec3(0.0));
                vec3 c = palette[i];
            }
        """.trimIndent()
        val issues = auditOne(src = src)
        assertEquals(listOf("dynamic-array-index"), rules(issues))
    }

    @Test
    fun ruleF_constantIndex_isClean() {
        val src = """
            uniform float uHsl[8];
            void main() {
                float x = uHsl[0] + uHsl[7];
            }
        """.trimIndent()
        assertTrue(auditOne(src = src).isEmpty())
    }

    @Test
    fun ruleF_arrayParameterInFunctionDecl_isClean() {
        // A bare `void f(float x[8]);` declaration has no body, so no
        // dynamic use exists — the audit stays clean. (The parameter IS
        // tracked as a declared array now, so a DEFINITION body indexing
        // it dynamically is still caught — see the tests below.)
        val src = """
            void f(float x[8]);
            void main() { }
        """.trimIndent()
        assertTrue(auditOne(src = src).isEmpty())
    }

    @Test
    fun ruleF_arrayParameterDynamicallyIndexed_isFlagged() {
        // The gap this closes: the local-array pattern skips every line
        // containing `(`, so without parameter tracking a dynamic index
        // into a parameter array slipped past rule (f) entirely.
        val src = """
            float g(float x[8], int i) { return x[i]; }
            void main() { }
        """.trimIndent()
        val issues = auditOne(src = src)
        assertEquals(listOf("dynamic-array-index"), rules(issues))
        assertEquals(1, issues[0].lineNumber)
    }

    @Test
    fun ruleF_arrayParameterConstantIndexed_isClean() {
        val src = """
            float g(float x[8]) { return x[0] + x[7]; }
            void main() { }
        """.trimIndent()
        assertTrue(auditOne(src = src).isEmpty())
    }

    @Test
    fun ruleF_samplerArrayParameterDynamicallyIndexed_isFlaggedAsRuleA() {
        // A sampler array parameter dynamically indexed is rule (a)'s
        // territory — it must not double-report as a plain-array index.
        val src = """
            float g(highp sampler2D tex[4], int i) { return texture(tex[i], vec2(0.5)).r; }
            void main() { }
        """.trimIndent()
        val issues = auditOne(src = src)
        assertEquals(listOf("dynamic-sampler-index"), rules(issues))
    }

    @Test
    fun ruleF_callArgumentsNeverRegisterAsParameters() {
        // Call arguments index real arrays but declare nothing: `mix(...)`
        // and the constructor call `vec3(1.0)` must not register phantom
        // arrays, and the constant indices used here stay clean.
        val src = """
            vec3 h(vec3 p) { return p; }
            void main() {
                float a[8];
                float y = mix(a[0], a[1], 0.5);
                vec3 q = h(vec3(1.0));
            }
        """.trimIndent()
        assertTrue(auditOne(src = src).isEmpty())
    }

    @Test
    fun ruleF_samplerArrayDynamicIndex_reportsOnlyRuleA() {
        // A sampler array's dynamic index is rule (a)'s territory —
        // it must not double-report as a plain-array index.
        val src = """
            uniform highp sampler2D uTex[4];
            void main() { float m = texture(uTex[i], uv).r; }
        """.trimIndent()
        val issues = auditOne(src = src)
        assertEquals(listOf("dynamic-sampler-index"), rules(issues))
    }

    // ------------------------------------------------------------------
    // Rule (g): ES 1.00-only built-ins in ES 3.00 sources
    // ------------------------------------------------------------------

    @Test
    fun ruleG_es100Builtins_inEs300_areFlagged() {
        val src = """
            #version 300 es
            varying vec2 vUv;
            void main() {
                gl_FragColor = texture2D(uImage, vUv);
            }
        """.trimIndent()
        val issues = auditOne(src = src)
        // One issue per offending line: `varying` line, `gl_FragColor`+`texture2D` line.
        assertEquals(listOf("es100-builtin", "es100-builtin"), rules(issues))
        assertEquals(listOf(2, 4), issues.map { it.lineNumber })
    }

    @Test
    fun ruleG_glFragCoord_isNotABannedBuiltin() {
        val src = """
            #version 300 es
            void main() {
                vec2 p = gl_FragCoord.xy;
            }
        """.trimIndent()
        assertTrue(auditOne(src = src).isEmpty())
    }

    @Test
    fun ruleG_es100Builtins_inEs100_areClean() {
        val src = """
            varying vec2 vUv;
            uniform sampler2D uImage;
            void main() { gl_FragColor = texture2D(uImage, vUv); }
        """.trimIndent()
        assertTrue(auditOne(src = src).isEmpty())
    }

    // ------------------------------------------------------------------
    // Rule (h): reserved words as identifiers
    // ------------------------------------------------------------------

    @Test
    fun ruleH_reservedWordAsVariable_isFlagged() {
        val src = """
            void main() {
                float sample;
                sample = 1.0;
            }
        """.trimIndent()
        val issues = auditOne(src = src)
        assertEquals(listOf("reserved-identifier"), rules(issues))
        assertEquals(2, issues[0].lineNumber)
    }

    @Test
    fun ruleH_reservedWordAfterQualifiers_isFlagged() {
        val src = """
            uniform highp vec3 input;
            void main() { }
        """.trimIndent()
        assertEquals(
            listOf("reserved-identifier"),
            rules(auditOne(src = src)),
        )
    }

    @Test
    fun ruleH_substringMatch_isClean() {
        // \b boundaries: identifiers merely CONTAINING a reserved word
        // are fine.
        val src = """
            void main() {
                float sampleCount = 0.0;
                float resampled = 1.0;
                sampleCount += resampled;
            }
        """.trimIndent()
        assertTrue(auditOne(src = src).isEmpty())
    }

    // ------------------------------------------------------------------
    // Rule (i): cross-stage varying precision agreement
    // ------------------------------------------------------------------

    private fun varyingPair(vertDecl: String, fragDecl: String) =
        listOf("PAIR_VERTEX" to "void main() {\n    $vertDecl\n}",
            "PAIR_FRAGMENT" to "void main() {\n    $fragDecl\n}")

    @Test
    fun ruleI_bareIvec2Pair_mismatches() {
        // The documented trap: vertex highp int default vs fragment
        // mediump int default. Reported on the FRAGMENT declaration.
        val issues = ShaderSafety.audit(
            varyingPair("out ivec2 vTile;", "in ivec2 vTile;"))
        assertEquals(listOf("varying-precision-mismatch"), rules(issues))
        assertEquals("PAIR_FRAGMENT", issues[0].shaderName)
        assertEquals(2, issues[0].lineNumber)
        assertTrue(issues[0].line.contains("vTile"))
    }

    @Test
    fun ruleI_floatPair_isClean() {
        // Both stages default float varyings to highp.
        val issues = ShaderSafety.audit(
            varyingPair("out vec2 vUv;", "in vec2 vUv;"))
        assertTrue(issues.isEmpty())
    }

    @Test
    fun ruleI_explicitMatchingPrecision_isClean() {
        val issues = ShaderSafety.audit(
            varyingPair("out highp ivec2 vTile;", "in highp ivec2 vTile;"))
        assertTrue(issues.isEmpty())
    }

    @Test
    fun ruleI_explicitMismatch_isFlagged() {
        val issues = ShaderSafety.audit(
            varyingPair("out mediump ivec2 vTile;", "in highp ivec2 vTile;"))
        assertEquals(listOf("varying-precision-mismatch"), rules(issues))
    }

    @Test
    fun ruleI_unusedVertexVarying_isClean() {
        // A vertex `out` with no fragment `in` is unused, not an error.
        val issues = ShaderSafety.audit(
            varyingPair("out ivec2 vTile;", "void unused() { }"))
        assertTrue(issues.isEmpty())
    }

    @Test
    fun ruleI_vertexAttributeLayout_isClean() {
        // `layout(location = 0) in vec2 aPos;` is an attribute, not a
        // varying — the anchored regex must not match it.
        val issues = ShaderSafety.audit(
            varyingPair("layout(location = 0) in vec2 aPos;", "in vec2 vUv;"))
        assertTrue(issues.isEmpty())
    }

    // ------------------------------------------------------------------
    // Comments: offenses inside comments never fire; line numbers survive
    // ------------------------------------------------------------------

    @Test
    fun lineComment_hidesOffense() {
        val src = """
            #version 300 es
            precision highp float;
            // uniform sampler2D uImage;
            uniform highp sampler2D uOk;
            void main() { }
        """.trimIndent()
        assertTrue(auditOne(src = src).isEmpty())
    }

    @Test
    fun blockComment_hidesOffenseAndKeepsLineNumbers() {
        val src = "/*\n" +
            "uniform sampler2D uImage;\n" +
            "*/\n" +
            "#version 300 es\n" +
            "precision highp float;\n" +
            "uniform sampler2D uReal;\n" +
            "void main() { }"
        val issues = auditOne(src = src)
        assertEquals(listOf("unqualified-sampler"), rules(issues))
        // The real offense is on line 6, not shifted by the block comment.
        assertEquals(6, issues[0].lineNumber)
    }

    @Test
    fun issueLine_isCommentStripped() {
        val src = """
            #version 300 es
            uniform sampler2D uImage; // trailing note
            void main() { }
        """.trimIndent()
        val issues = auditOne(src = src)
        assertEquals(1, issues.size)
        assertEquals("uniform sampler2D uImage;", issues[0].line)
    }

    // ------------------------------------------------------------------
    // The headline guarantee: the real shipped shaders are Mali-safe
    // ------------------------------------------------------------------

    @Test
    fun shippedSources_listsFourShaders() {
        val names = ShaderSafety.shippedSources().map { it.first }
        assertEquals(
            listOf("VERTEX", "FRAGMENT", "BLUR_VERTEX", "BLUR_FRAGMENT"),
            names,
        )
    }

    @Test
    fun auditShipped_realShaders_haveZeroIssues() {
        // The money test for backlog item #1: every shipped GLSL source
        // passes all nine Mali-safety rules — dynamic sampler indexing,
        // precision-on-sampler, unqualified samplers, dynamic loop bounds,
        // dynamic while conditions, dynamic plain-array indexing, ES
        // 1.00 built-ins, reserved identifiers, and cross-stage varying
        // precision agreement.
        val issues = ShaderSafety.auditShipped()
        assertTrue(
            "Shipped shaders must be Mali-safe, but got: $issues",
            issues.isEmpty(),
        )
    }

    @Test
    fun shippedSources_coversEveryGlslConstant_inShaders() {
        // Anti-rot for the headline guarantee: [ShaderSafety.shippedSources]
        // is a hardcoded list, so a newly added `Shaders` constant would
        // silently escape the Mali audit (and the in-app GPU self-test that
        // runs it). This reflects over every static String constant on
        // [Shaders] and requires each GLSL source (identified by its
        // `#version` line) to appear in the audited list. Adding a shader
        // now fails loudly here (and in shippedSources_listsFourShaders)
        // instead of shipping unaudited.
        val audited = ShaderSafety.shippedSources().map { it.first }.toSet()
        val glslConstants = Shaders::class.java.declaredFields
            .filter { field ->
                field.type == String::class.java &&
                    java.lang.reflect.Modifier.isStatic(field.modifiers) &&
                    (field.get(null) as? String)?.contains("#version") == true
            }
            .map { it.name }
        assertTrue(
            "Shaders must declare at least one GLSL source",
            glslConstants.isNotEmpty(),
        )
        val missing = glslConstants.filter { it !in audited }
        assertTrue(
            "Shaders constants missing from ShaderSafety.shippedSources " +
                "(would ship unaudited on Mali GPUs): $missing",
            missing.isEmpty(),
        )
    }
}
