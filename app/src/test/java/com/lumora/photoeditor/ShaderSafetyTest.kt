package com.lumora.photoeditor

import com.lumora.photoeditor.engine.ShaderSafety
import org.junit.Assert.*
import org.junit.Test

class ShaderSafetyTest {

    @Test fun shipped_shaders_are_mali_safe() {
        val issues = ShaderSafety.auditShipped()
        assertTrue(
            "Shipped GLSL must be Mali-safe, but found: $issues",
            issues.isEmpty(),
        )
    }

    @Test fun dynamic_sampler_index_is_flagged() {
        val src = """
            #version 300 es
            precision highp float;
            uniform highp sampler2D uMaskTex[4];
            uniform int uOverlayMask;
            void main() {
                float m = texture(uMaskTex[uOverlayMask], vec2(0.5)).r;
            }
        """.trimIndent()
        val issues = ShaderSafety.audit(listOf("BAD" to src))
        assertEquals(1, issues.size)
        assertEquals("dynamic-sampler-index", issues[0].rule)
        assertEquals("BAD", issues[0].shaderName)
        assertTrue(issues[0].line.contains("uOverlayMask"))
    }

    @Test fun loop_indexed_sampler_is_flagged() {
        val src = """
            #version 300 es
            uniform highp sampler2D uMaskTex[4];
            void main() {
                float m = 0.0;
                for (int i = 0; i < 4; i++) {
                    m += texture(uMaskTex[i], vec2(0.5)).r;
                }
            }
        """.trimIndent()
        val issues = ShaderSafety.audit(listOf("LOOP" to src))
        assertTrue(issues.any { it.rule == "dynamic-sampler-index" })
    }

    @Test fun precision_on_sampler_is_flagged() {
        val src = """
            #version 300 es
            precision highp float;
            precision mediump sampler2D;
            uniform highp sampler2D uImage;
        """.trimIndent()
        val issues = ShaderSafety.audit(listOf("PREC" to src))
        assertTrue(issues.any { it.rule == "precision-on-sampler" })
    }

    @Test fun unqualified_sampler_is_flagged() {
        val src = """
            #version 300 es
            precision highp float;
            uniform sampler2D uImage;
            uniform sampler2D uMaskTex[4];
        """.trimIndent()
        val issues = ShaderSafety.audit(listOf("UNQ" to src))
        assertEquals(2, issues.size)
        assertTrue(issues.all { it.rule == "unqualified-sampler" })
        assertTrue(issues.any { it.line.contains("uImage") })
        assertTrue(issues.any { it.line.contains("uMaskTex") })
    }

    @Test fun qualified_samplers_pass() {
        val src = """
            #version 300 es
            precision highp float;
            uniform highp sampler2D uA;
            uniform mediump sampler2D uB;
            uniform lowp sampler3D uC;
            uniform highp sampler2D uArr[4];
        """.trimIndent()
        assertTrue(ShaderSafety.audit(listOf("QUAL" to src)).isEmpty())
    }

    @Test fun unqualified_sampler_ignored_in_es100() {
        // ES 1.00 fragment shaders predeclare a default sampler precision,
        // so the unqualified form is legal there — rules (c) and (g) are
        // ES 3.00 only, and gl_FragColor/texture2D are the correct ES 1.00
        // forms.
        val src = """
            #version 100
            precision mediump float;
            uniform sampler2D uImage;
            void main() {
                gl_FragColor = texture2D(uImage, vec2(0.5));
            }
        """.trimIndent()
        assertTrue(ShaderSafety.audit(listOf("ES100" to src)).isEmpty())
    }

    @Test fun constant_indices_and_plain_samplers_pass() {
        val src = """
            #version 300 es
            precision highp float;
            uniform highp sampler2D uImage;
            uniform highp sampler2D uMaskTex[4];
            uniform vec4 uMaskA[4];
            void main() {
                vec4 c = texture(uImage, vec2(0.5));
                float m0 = texture(uMaskTex[0], vec2(0.5)).r;
                float m3 = texture(uMaskTex[3], vec2(0.5)).r;
                vec4 a = uMaskA[2];
            }
        """.trimIndent()
        assertTrue(ShaderSafety.audit(listOf("GOOD" to src)).isEmpty())
    }

    @Test fun dynamic_loop_bound_is_flagged() {
        val src = """
            #version 300 es
            precision highp float;
            uniform int uCount;
            void main() {
                float s = 0.0;
                for (int i = 0; i < uCount; i++) { s += float(i); }
            }
        """.trimIndent()
        val issues = ShaderSafety.audit(listOf("DYNLOOP" to src))
        assertTrue(issues.any { it.rule == "dynamic-loop-bound" })
        assertTrue(issues.any { it.line.contains("uCount") })
    }

    @Test fun constant_loop_bounds_pass() {
        // Constant-bound loops are fine; loop-VARIABLE array indexing is
        // not (rule f) — so this loop uses constant indices only.
        val src = """
            #version 300 es
            precision highp float;
            uniform highp vec3 uHsl[8];
            void main() {
                float s = 0.0;
                for (int i = 0; i < 8; i++) { s += uHsl[0].x + float(i); }
                for (int j = 8; j > 0; j--) { s -= float(j); }
            }
        """.trimIndent()
        assertTrue(ShaderSafety.audit(listOf("CONSTLOOP" to src)).isEmpty())
    }

    @Test fun loop_indexed_uniform_array_is_flagged() {
        // Rule (f): indexing a non-sampler uniform array with the loop
        // variable is a Mali miscompile hazard even though the bound is
        // constant — the shipped HSL mixer did exactly this until 2026-10-19.
        val src = """
            #version 300 es
            precision highp float;
            uniform highp vec3 uHsl[8];
            void main() {
                float s = 0.0;
                for (int i = 0; i < 8; i++) { s += uHsl[i].x; }
            }
        """.trimIndent()
        val issues = ShaderSafety.audit(listOf("UARR" to src))
        assertTrue(issues.any { it.rule == "dynamic-array-index" })
        assertTrue(issues.none { it.rule == "dynamic-loop-bound" })
        assertTrue(issues.none { it.rule == "dynamic-sampler-index" })
    }

    @Test fun loop_indexed_local_array_is_flagged() {
        // Rule (f) also covers locals: `float centers[8]; ... centers[i]`.
        val src = """
            #version 300 es
            precision highp float;
            void main() {
                float centers[8];
                centers[0] = 0.0;
                float s = 0.0;
                for (int i = 0; i < 8; i++) { s += centers[i]; }
            }
        """.trimIndent()
        val issues = ShaderSafety.audit(listOf("LARR" to src))
        assertEquals(1, issues.size)
        assertEquals("dynamic-array-index", issues[0].rule)
        assertTrue(issues[0].line.contains("centers"))
    }

    @Test fun constant_indexed_plain_arrays_pass() {
        // Constant indices into uniform and local arrays are always fine.
        val src = """
            #version 300 es
            precision highp float;
            uniform highp vec3 uHsl[8];
            uniform float uBwMix[8];
            uniform float uDeconvK[25];
            void main() {
                float centers[8];
                centers[0] = 0.0;
                float s = uHsl[0].x + uHsl[7].z + uBwMix[3] + uDeconvK[24] + centers[0];
            }
        """.trimIndent()
        assertTrue(ShaderSafety.audit(listOf("CARR" to src)).isEmpty())
    }

    @Test fun uniform_indexed_plain_array_is_flagged() {
        // Non-constant, non-loop indices (e.g. a uniform) are flagged too.
        val src = """
            #version 300 es
            precision highp float;
            uniform float uBwMix[8];
            uniform int uWhich;
            void main() {
                float s = uBwMix[uWhich];
            }
        """.trimIndent()
        val issues = ShaderSafety.audit(listOf("UIDX" to src))
        assertTrue(issues.any { it.rule == "dynamic-array-index" })
    }

    @Test fun dynamic_while_condition_is_flagged() {
        val src = """
            #version 300 es
            precision highp float;
            uniform int uCount;
            void main() {
                float s = 0.0;
                int i = 0;
                while (i < uCount) { s += float(i); i++; }
            }
        """.trimIndent()
        val issues = ShaderSafety.audit(listOf("DYNWHILE" to src))
        assertTrue(issues.any { it.rule == "dynamic-while-condition" })
        assertTrue(issues.any { it.line.contains("uCount") })
    }

    @Test fun do_while_dynamic_condition_is_flagged() {
        val src = """
            #version 300 es
            precision highp float;
            uniform int uCount;
            void main() {
                float s = 0.0;
                int i = 0;
                do { s += float(i); i++; } while (i < uCount);
            }
        """.trimIndent()
        val issues = ShaderSafety.audit(listOf("DODYNWHILE" to src))
        assertTrue(issues.any { it.rule == "dynamic-while-condition" })
    }

    @Test fun while_counter_against_literal_is_flagged() {
        // Strict by design: unlike `for`, there is no declared loop
        // variable to exempt, so even `while (i < 4)` is flagged — the
        // audit cannot prove `i` is a simple counter, and the safe rewrite
        // is a constant-bound `for`, which rule (d) already blesses.
        val src = """
            #version 300 es
            precision highp float;
            void main() {
                float s = 0.0;
                int i = 0;
                while (i < 4) { s += float(i); i++; }
            }
        """.trimIndent()
        val issues = ShaderSafety.audit(listOf("STRICTWHILE" to src))
        assertTrue(issues.any { it.rule == "dynamic-while-condition" })
    }

    @Test fun literal_while_condition_passes() {
        val src = """
            #version 300 es
            precision highp float;
            void main() {
                float s = 0.0;
                while (true) { s += 1.0; if (s > 4.0) break; }
            }
        """.trimIndent()
        assertTrue(ShaderSafety.audit(listOf("LITWHILE" to src)).isEmpty())
    }

    @Test fun es100_builtins_flagged_in_es300() {
        // Rule (g): ES 1.00-only built-ins are compile errors in ES 3.00
        // sources — strict (Mali) drivers reject the whole program.
        val src = """
            #version 300 es
            precision highp float;
            uniform highp sampler2D uImage;
            void main() {
                vec4 c = texture2D(uImage, vec2(0.5));
                gl_FragColor = c;
            }
        """.trimIndent()
        val issues = ShaderSafety.audit(listOf("ES100B" to src))
        assertEquals(2, issues.size)
        assertTrue(issues.all { it.rule == "es100-builtin" })
        assertTrue(issues.any { it.line.contains("texture2D") })
        assertTrue(issues.any { it.line.contains("gl_FragColor") })
    }

    @Test fun es100_qualifiers_flagged_in_es300_vertex() {
        // `varying` / `attribute` are ES 1.00 qualifiers; ES 3.00 uses
        // `in` / `out`. A habitual `attribute` in a 300 es vertex shader is
        // a strict-driver compile error.
        val src = """
            #version 300 es
            attribute vec2 aPos;
            varying vec2 vUv;
            void main() {
                vUv = aPos;
                gl_Position = vec4(aPos, 0.0, 1.0);
            }
        """.trimIndent()
        val issues = ShaderSafety.audit(listOf("ES100Q" to src))
        assertEquals(2, issues.size)
        assertTrue(issues.all { it.rule == "es100-builtin" })
    }

    @Test fun modern_es300_builtins_pass() {
        // The ES 3.00 forms — `texture(`, `gl_FragCoord`, `in`/`out` — must
        // not be over-matched by rule (g).
        val src = """
            #version 300 es
            precision highp float;
            uniform highp sampler2D uImage;
            in vec2 vUv;
            out vec4 fragColor;
            void main() {
                vec4 c = texture(uImage, vUv);
                float d = gl_FragCoord.x;
                fragColor = c * d;
            }
        """.trimIndent()
        assertTrue(ShaderSafety.audit(listOf("MODERN" to src)).isEmpty())
    }

    @Test fun commented_out_code_is_ignored() {
        val src = """
            #version 300 es
            uniform highp sampler2D uMaskTex[4];
            uniform int uOverlayMask;
            // float m = texture(uMaskTex[uOverlayMask], vec2(0.5)).r;
            /* float n = texture(uMaskTex[uOverlayMask], vec2(0.5)).r; */
            void main() { }
        """.trimIndent()
        assertTrue(ShaderSafety.audit(listOf("COMMENTS" to src)).isEmpty())
    }

    @Test fun reserved_word_as_variable_is_flagged() {
        // Rule (h): `sample` is reserved for future use in GLSL ES 3.00 —
        // lenient drivers accept it as an identifier, strict (Mali) drivers
        // reject the program.
        val src = """
            #version 300 es
            precision highp float;
            uniform highp sampler2D uImage;
            float sample;
            void main() {
                sample = texture(uImage, vec2(0.5)).r;
            }
        """.trimIndent()
        val issues = ShaderSafety.audit(listOf("RESVAR" to src))
        assertEquals(1, issues.size)
        assertEquals("reserved-identifier", issues[0].rule)
        assertTrue(issues[0].line.contains("sample"))
    }

    @Test fun reserved_word_as_function_name_and_param_is_flagged() {
        // Both the function name (`filter`) and the parameter (`output`)
        // sit in identifier position and must be caught.
        val src = """
            #version 300 es
            precision highp float;
            float filter(float output) { return output; }
            void main() { }
        """.trimIndent()
        val issues = ShaderSafety.audit(listOf("RESFN" to src))
        assertEquals(1, issues.size)
        assertEquals("reserved-identifier", issues[0].rule)
    }

    @Test fun identifiers_containing_reserved_words_pass() {
        // The \\b boundaries must not over-match: `sampleCount` and
        // `resampled` merely contain a reserved word.
        val src = """
            #version 300 es
            precision highp float;
            uniform highp sampler2D uImage;
            in vec2 vUv;
            out vec4 fragColor;
            void main() {
                float sampleCount = 4.0;
                float resampled = texture(uImage, vUv).r * sampleCount;
                fragColor = vec4(resampled);
            }
        """.trimIndent()
        assertTrue(ShaderSafety.audit(listOf("RESOK" to src)).isEmpty())
    }

    @Test fun varying_precision_mismatch_is_flagged() {
        // Rule (i): a bare `out ivec2 vTile;` is highp (vertex int
        // default) while the matching `in ivec2 vTile;` is mediump
        // (fragment int default) — a link-time error per the ES 3.00 spec
        // that lenient drivers accept and strict (Mali) drivers reject.
        val vert = """
            #version 300 es
            out ivec2 vTile;
            void main() {
                vTile = ivec2(1, 2);
                gl_Position = vec4(0.0);
            }
        """.trimIndent()
        val frag = """
            #version 300 es
            precision highp float;
            in ivec2 vTile;
            out vec4 fragColor;
            void main() {
                fragColor = vec4(float(vTile.x));
            }
        """.trimIndent()
        val issues = ShaderSafety.audit(
            listOf("MISMATCH_VERTEX" to vert, "MISMATCH_FRAGMENT" to frag))
        assertEquals(1, issues.size)
        assertEquals("varying-precision-mismatch", issues[0].rule)
        assertEquals("MISMATCH_FRAGMENT", issues[0].shaderName)
        assertTrue(issues[0].line.contains("vTile"))
    }

    @Test fun matching_explicit_varying_precision_passes() {
        // Same qualifier on both sides: no mismatch, even for int-family
        // varyings where the stage defaults would disagree.
        val vert = """
            #version 300 es
            out mediump ivec2 vTile;
            out highp vec2 vUv;
            void main() {
                vTile = ivec2(1, 2);
                vUv = vec2(0.5);
                gl_Position = vec4(0.0);
            }
        """.trimIndent()
        val frag = """
            #version 300 es
            precision highp float;
            in mediump ivec2 vTile;
            in highp vec2 vUv;
            out vec4 fragColor;
            void main() {
                fragColor = vec4(vUv, float(vTile.x), 1.0);
            }
        """.trimIndent()
        assertTrue(ShaderSafety.audit(
            listOf("MATCHOK_VERTEX" to vert, "MATCHOK_FRAGMENT" to frag)).isEmpty())
    }

    @Test fun explicit_vertex_precision_matching_fragment_default_passes() {
        // `out highp vec2 vUv;` against a bare `in vec2 vUv;`: the fragment
        // float default is highp, so the effective precisions agree.
        val vert = """
            #version 300 es
            out highp vec2 vUv;
            void main() {
                vUv = vec2(0.5);
                gl_Position = vec4(0.0);
            }
        """.trimIndent()
        val frag = """
            #version 300 es
            precision highp float;
            in vec2 vUv;
            out vec4 fragColor;
            void main() {
                fragColor = vec4(vUv, 0.0, 1.0);
            }
        """.trimIndent()
        assertTrue(ShaderSafety.audit(
            listOf("DEFOK_VERTEX" to vert, "DEFOK_FRAGMENT" to frag)).isEmpty())
    }

    @Test fun unpaired_varying_sources_are_ignored() {
        // Rule (i) only fires on a VERTEX/FRAGMENT pair present in the same
        // audit call: a lone source's `in`/`out` declarations are not
        // cross-checked against anything.
        val src = """
            #version 300 es
            precision highp float;
            uniform highp sampler2D uImage;
            in vec2 vUv;
            out vec4 fragColor;
            void main() {
                fragColor = texture(uImage, vUv);
            }
        """.trimIndent()
        assertTrue(ShaderSafety.audit(listOf("LONE" to src)).isEmpty())
    }
}
