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
        // so the unqualified form is legal there — the rule is ES 3.00 only.
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
}
