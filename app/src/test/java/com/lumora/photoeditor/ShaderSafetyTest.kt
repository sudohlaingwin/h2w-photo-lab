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
