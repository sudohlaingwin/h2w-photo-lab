package com.lumora.photoeditor

import com.lumora.photoeditor.engine.Shaders
import com.lumora.photoeditor.engine.UniformRegistry
import org.junit.Assert.*
import org.junit.Test

/**
 * Executed verification for the uniform-name registry (pure Kotlin — runs on
 * the standalone kotlinc harness; Gradle's test workers are broken in this
 * env).
 *
 * Why this matters on-device: [com.lumora.photoeditor.engine.GlEngine]
 * pre-resolves every name in [UniformRegistry.FRAGMENT] right after linking.
 * A name that is *set* per frame but has no `uniform` declaration in the
 * shipped GLSL resolves to location -1 and its `glUniform*` call becomes a
 * SILENT no-op — the slider moves but the preview never changes. That is
 * exactly what happened with `uCalHue`/`uCalSat`/`uCalShadowTint` (camera
 * calibration shipped dead). These tests pin both directions of the
 * contract so that bug class can never ship silently again:
 *
 * 1. every registered name is declared in the shipped GLSL (VERTEX or
 *    FRAGMENT program), and
 * 2. every uniform declared in the shipped GLSL is registered (catches
 *    registry drift when a shader edit adds a uniform).
 */
class UniformRegistryTest {

    /** Matches `uniform [precision] type name;` and `uniform type name[N];`. */
    private val declRx =
        Regex("""uniform\s+(?:highp\s+|mediump\s+|lowp\s+)?\w+\s+(\w+)\s*(?:\[[^\]]*\])?\s*;""")

    private fun declaredIn(source: String): Set<String> =
        declRx.findAll(source).map { it.groupValues[1] }.toSet()

    private val declaredVertex: Set<String> by lazy { declaredIn(Shaders.VERTEX) }
    private val declaredFragment: Set<String> by lazy { declaredIn(Shaders.FRAGMENT) }

    @Test
    fun registry_isNotEmpty() {
        assertTrue(
            "UniformRegistry.FRAGMENT must list the bound uniforms",
            UniformRegistry.FRAGMENT.isNotEmpty()
        )
    }

    @Test
    fun registry_hasNoDuplicates() {
        val names = UniformRegistry.FRAGMENT
        val dupes = names.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
        assertTrue("duplicate registry entries: $dupes", dupes.isEmpty())
    }

    @Test
    fun everyRegisteredUniform_isDeclaredInShippedGlsl() {
        val declared = declaredVertex + declaredFragment
        val missing = UniformRegistry.FRAGMENT.filter { it !in declared }
        assertTrue(
            "registered but NOT declared in shipped GLSL — these glUniform calls " +
                "would silently no-op (location -1): $missing",
            missing.isEmpty()
        )
    }

    @Test
    fun everyDeclaredFragmentUniform_isRegistered() {
        val registered = UniformRegistry.FRAGMENT.toSet()
        val missing = declaredFragment.filter { it !in registered }
        assertTrue(
            "declared in Shaders.FRAGMENT but missing from UniformRegistry — " +
                "add it or the pre-resolve cache drifts from the shader: $missing",
            missing.isEmpty()
        )
    }

    @Test
    fun everyDeclaredVertexUniform_isRegistered() {
        val registered = UniformRegistry.FRAGMENT.toSet()
        val missing = declaredVertex.filter { it !in registered }
        assertTrue(
            "declared in Shaders.VERTEX but missing from UniformRegistry: $missing",
            missing.isEmpty()
        )
    }

    @Test
    fun vertexUniforms_coverUvAndZoom() {
        // uUvMatrix/uZoom live in the vertex program (geometry path); pin that
        // the registry really does span both programs, not just FRAGMENT.
        assertTrue("uUvMatrix must be a VERTEX declaration", "uUvMatrix" in declaredVertex)
        assertTrue("uZoom must be a VERTEX declaration", "uZoom" in declaredVertex)
        assertTrue("uImage must be a FRAGMENT declaration", "uImage" in declaredFragment)
    }
}
