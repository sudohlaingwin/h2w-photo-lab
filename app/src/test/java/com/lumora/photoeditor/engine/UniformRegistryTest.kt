package com.lumora.photoeditor.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * First-ever executed verification of [UniformRegistry] — the single source
 * of truth for the main fragment program's uniform names.
 *
 * What it pins (and why it matters on his vivo V60):
 * - Every name [GlEngine.cacheUniforms] binds is actually *declared* in the
 *   main program's GLSL ([Shaders.VERTEX] + [Shaders.FRAGMENT] — one linked
 *   program, one uniform namespace; uUvMatrix/uZoom live in the vertex
 *   stage). A bound-but-undeclared uniform resolves to location -1 and its
 *   `glUniform*` call becomes a silent no-op — the slider moves but the
 *   preview never changes. That is exactly how `uCalHue` / `uCalSat` /
 *   `uCalShadowTint` shipped dead (set via `u()` but missing from the old
 *   inline cache list) until the registry made the list exhaustive. This
 *   test fails the build if that bug class ever recurs.
 * - The documented array sizes (mask slots, HSL bands, deconv kernel):
 *   a shader-side size change that GlEngine's uploads don't follow would
 *   land garbled data on the GPU.
 * - Every sampler carries an explicit precision qualifier: an unqualified
 *   sampler is a spec-level compile error that strict (Mali) drivers are
 *   allowed to reject — a rejected program means a black preview.
 *
 * Pure JVM: [UniformRegistry] and [Shaders] have zero Android imports.
 */
class UniformRegistryTest {

    /**
     * `uniform [precision] <type> <name>;` or `... <name>[N];`, line-anchored
     * so KDoc prose that merely *mentions* the word "uniform" never matches.
     * Groups: 1 = precision (may be empty), 2 = type, 3 = name, 4 = array size (may be empty).
     */
    private val uniformDecl = Regex(
        "(?m)^\\s*uniform\\s+((?:highp|mediump|lowp)\\s+)?" +
            "(float|int|vec2|vec3|vec4|mat3|mat4|sampler2D|sampler3D)" +
            "\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*(?:\\[(\\d+)\\])?\\s*;"
    )

    private data class Decl(val precision: String, val type: String, val size: Int?)

    /** Main program GLSL: vertex + fragment stages share one uniform namespace. */
    private val mainProgramGlsl: String get() = Shaders.VERTEX + "\n" + Shaders.FRAGMENT

    private fun declaredInMainProgram(): Map<String, Decl> {
        val out = LinkedHashMap<String, Decl>()
        for (m in uniformDecl.findAll(mainProgramGlsl)) {
            val name = m.groupValues[3]
            assertTrue("duplicate GLSL declaration of $name in the main program", name !in out)
            out[name] = Decl(
                precision = m.groupValues[1].trim(),
                type = m.groupValues[2],
                size = m.groupValues[4].ifEmpty { null }?.toInt()
            )
        }
        return out
    }

    @Test
    fun everyRegisteredNameIsDeclaredInMainProgramGlsl() {
        val declared = declaredInMainProgram()
        val missing = UniformRegistry.FRAGMENT.filter { it !in declared }
        assertTrue(
            "uniforms bound by GlEngine.cacheUniforms but NOT declared in the main program GLSL " +
                "(their glUniform calls would be silent no-ops): $missing",
            missing.isEmpty()
        )
    }

    @Test
    fun noDuplicateRegisteredNames() {
        val names = UniformRegistry.FRAGMENT
        assertEquals(
            "duplicate entries in UniformRegistry.FRAGMENT: " +
                names.groupingBy { it }.eachCount().filter { it.value > 1 }.keys,
            names.size, names.distinct().size
        )
    }

    @Test
    fun namesAreValidPrefixedGlslIdentifiers() {
        val ident = Regex("[A-Za-z_][A-Za-z0-9_]*")
        val bad = UniformRegistry.FRAGMENT.filter { !it.matches(ident) || !it.startsWith("u") }
        assertTrue("registry names must be valid u-prefixed GLSL identifiers: $bad", bad.isEmpty())
    }

    @Test
    fun arraySizesMatchDocumentedContract() {
        val declared = declaredInMainProgram()
        // Size contract the GlEngine upload code is written against: mask
        // slots (4), HSL bands (8), 5x5 Wiener kernel (25).
        val expected = mapOf(
            "uHsl" to ("vec3" to 8),
            "uBwMix" to ("float" to 8),
            "uDeconvK" to ("float" to 25),
            "uMaskTex" to ("sampler2D" to 4),
            "uMaskA" to ("vec4" to 4),
            "uMaskB" to ("vec4" to 4),
            "uMaskC" to ("vec2" to 4),
            "uMaskInv" to ("float" to 4),
            "uDecon" to ("float" to 4),
            "uDeconTex" to ("sampler2D" to 4)
        )
        for ((name, want) in expected) {
            val decl = declared[name]
            assertTrue("$name: registered but has no GLSL declaration to check", decl != null)
            assertEquals("$name element type", want.first, decl!!.type)
            assertEquals("$name array size", want.second, decl.size)
        }
        // No other array uniform may appear unannounced: an unlisted one is
        // either a contract to add above or a shader edit that drifted.
        val unlisted = declared.filter { it.value.size != null && it.key !in expected }.keys
        assertTrue("array uniforms in GLSL with no pinned contract: $unlisted", unlisted.isEmpty())
    }

    @Test
    fun samplerUniformsCarryExplicitPrecision() {
        val declared = declaredInMainProgram()
        val samplers = declared.filter { it.value.type == "sampler2D" || it.value.type == "sampler3D" }
        assertTrue("no sampler declarations parsed — regex drift?", samplers.isNotEmpty())
        val unqualified = samplers.filter { it.value.precision.isEmpty() }.keys
        assertTrue(
            "samplers without an explicit precision qualifier (strict drivers may " +
                "reject the whole program -> black preview): $unqualified",
            unqualified.isEmpty()
        )
    }

    @Test
    fun criticalUniformsAreRegistered() {
        // Probes for the historically-dead names (uCal*) plus the uniforms
        // whose absence would silently kill a whole feature: image input,
        // masking, AI LUT, look transfer, deconv kernel.
        val probes = listOf(
            "uImage", "uUvMatrix", "uZoom", "uExposure", "uCurves",
            "uMaskCount", "uMaskTex", "uMaskA", "uMaskB", "uMaskC", "uMaskInv",
            "uAiLut", "uAiLutStrength", "uLookLut", "uLookLutStrength",
            "uDeconv", "uDeconvK", "uTexel",
            "uDecon", "uDeconTex", "uFxTex",
            "uHsl", "uBwMix",
            "uCalHue", "uCalSat", "uCalShadowTint"
        )
        val missing = probes.filter { it !in UniformRegistry.FRAGMENT }
        assertTrue("critical uniforms missing from the registry: $missing", missing.isEmpty())
    }

    @Test
    fun everyUniformLineInGlslParsesCleanly() {
        // Guard against a future uniform declared with a type this test's
        // regex doesn't know (bool, uint, sampler2DArray...): it must fail
        // loudly here instead of being silently skipped by the parser.
        val lineStarts = Regex("(?m)^\\s*uniform\\b").findAll(mainProgramGlsl).count()
        val parsed = uniformDecl.findAll(mainProgramGlsl).count()
        assertEquals(
            "a 'uniform' line in the main program GLSL did not parse — extend the " +
                "test regex or fix the declaration",
            lineStarts, parsed
        )
    }
}
