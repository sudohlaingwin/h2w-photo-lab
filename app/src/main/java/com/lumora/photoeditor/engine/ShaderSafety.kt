package com.lumora.photoeditor.engine

/**
 * Static Mali-safety audit for the shipped GLSL.
 *
 * Background: several Mali drivers miscompile or crash on
 *  (a) sampler arrays indexed with a non-constant expression
 *      (dynamic/loop indexing of `uniform sampler2D x[N]`), and
 *  (b) precision statements applied to sampler types
 *      (`precision highp sampler2D;`).
 * The mask pipeline was bitten by (a) — the fix unrolled the
 * `uMaskTex[0..3]` sampling into constant indices. Rule (c) below caught a
 * live violation: the blur fragment shader shipped an unqualified
 * `uniform sampler2D uImage;`, which is a compile error per the GLSL ES
 * 3.00 spec (samplers have no default precision there) that strict drivers
 * reject outright — the blur program feeds sharpening/clarity, so on those
 * devices it meant a black preview. Rule (d) guards the remaining classic
 * Mali trip hazard: `for` loops whose trip count is not a compile-time
 * constant. Dynamic bounds are legal ES 3.00, but several Mali generations
 * miscompile (or refuse to unroll) them in fragment shaders; the shipped
 * shaders only use constant bounds and this keeps it that way.
 * This audit scans every shipped shader for all three patterns so a
 * regression can never ship silently: it runs as a check inside the in-app
 * GPU self-test ([runSelfTest]) and is covered by [ShaderSafetyTest] unit
 * tests.
 *
 * Pure Kotlin, no Android imports — unit-testable on the JVM.
 */
data class ShaderSafetyIssue(
    /** Shader name as listed by [ShaderSafety.shippedSources]. */
    val shaderName: String,
    /** 1-based line number inside that shader source. */
    val lineNumber: Int,
    /** Short rule id, e.g. "dynamic-sampler-index". */
    val rule: String,
    /** The offending source line (comments stripped). */
    val line: String,
)

object ShaderSafety {

    /** `uniform [precision] sampler2D name[N]` (also sampler3D, isampler2D, …). */
    private val samplerArrayDecl =
        Regex("""uniform\s+(?:(?:lowp|mediump|highp)\s+)?\w*[sS]ampler\w*\s+(\w+)\s*\[\s*(\d+)\s*\]""")

    /** `precision highp sampler2D;` — illegal-ish on several Mali drivers. */
    private val precisionOnSampler =
        Regex("""precision\s+(?:lowp|mediump|highp)\s+\w*[sS]ampler""")

    /**
     * `uniform sampler2D uImage;` (also arrays: `uniform sampler2D x[4];`)
     * with NO precision qualifier. GLSL ES 3.00 gives samplers no default
     * precision, so the unqualified form is a spec-level compile error —
     * lenient drivers (Adreno) accept it, strict ones (Mali) reject the
     * whole program. Scoped to `#version 300 es` sources only: ES 1.00
     * fragment shaders DO predeclare a default sampler precision, so the
     * rule must not fire there.
     */
    private val unqualifiedSampler =
        Regex("""uniform\s+(?!lowp\b|mediump\b|highp\b)\w*[sS]ampler\w*\s+\w+""")

    private val constantIndex = Regex("""^\d+$""")

    /**
     * `for` loop headers: group 1 = loop variable, group 2 = trip-count
     * (condition) expression. The type prefix is optional so `for (i = 0; …)`
     * (reused variable) is covered too.
     */
    private val forLoop =
        Regex("""for\s*\(\s*(?:(?:int|uint|float)\s+)?(\w+)\s*=[^;]*;([^;]*);[^)]*\)""")

    /** Identifier tokens inside a loop bound; numeric literals never match. */
    private val identifier = Regex("""[A-Za-z_]\w*""")

    /** GLSL keywords that may legally appear in a constant loop bound. */
    private val loopBoundKeywords = setOf("int", "uint", "float", "bool")

    /** Every GLSL source shipped with the app. */
    fun shippedSources(): List<Pair<String, String>> = listOf(
        "VERTEX" to Shaders.VERTEX,
        "FRAGMENT" to Shaders.FRAGMENT,
        "BLUR_VERTEX" to Shaders.BLUR_VERTEX,
        "BLUR_FRAGMENT" to Shaders.BLUR_FRAGMENT,
    )

    /** Audits the shipped shaders; empty list = Mali-safe. */
    fun auditShipped(): List<ShaderSafetyIssue> = audit(shippedSources())

    /**
     * Audits arbitrary shader sources (name → GLSL). Flags:
     *  - any declared sampler array indexed with a non-constant expression
     *    (covers loop-indexed and uniform-indexed sampling),
     *  - any precision statement applied to a sampler type,
     *  - any sampler uniform without an explicit precision qualifier in a
     *    `#version 300 es` source (no default sampler precision there), and
     *  - any `for` loop whose trip count is not a compile-time constant
     *    (dynamic loop bound — a classic Mali miscompile hazard).
     */
    fun audit(sources: List<Pair<String, String>>): List<ShaderSafetyIssue> {
        val issues = mutableListOf<ShaderSafetyIssue>()
        for ((name, src) in sources) {
            val code = stripBlockComments(src)
            // ES 1.00 fragment shaders predeclare a default sampler
            // precision, so the unqualified-sampler rule is ES 3.00 only.
            val isEs300 = code.contains("#version 300 es")
            // Pass 1: declared sampler arrays.
            val samplers = mutableMapOf<String, Int>() // name -> size
            code.lines().forEach { rawLine ->
                val line = stripLineComment(rawLine)
                samplerArrayDecl.findAll(line).forEach { m ->
                    samplers[m.groupValues[1]] = m.groupValues[2].toInt()
                }
            }
            // Pass 2: every index into a declared sampler array must be constant.
            code.lines().forEachIndexed { idx, rawLine ->
                val line = stripLineComment(rawLine).trim()
                if (line.isEmpty()) return@forEachIndexed
                if (precisionOnSampler.containsMatchIn(line)) {
                    issues += ShaderSafetyIssue(name, idx + 1,
                        "precision-on-sampler", line)
                }
                if (isEs300 && unqualifiedSampler.containsMatchIn(line)) {
                    issues += ShaderSafetyIssue(name, idx + 1,
                        "unqualified-sampler", line)
                }
                for (sampler in samplers.keys) {
                    val use = Regex(Regex.escape(sampler) + """\s*\[\s*([^\]]*)\]""")
                    use.findAll(line).forEach { m ->
                        val expr = m.groupValues[1].trim()
                        if (!constantIndex.matches(expr)) {
                            issues += ShaderSafetyIssue(name, idx + 1,
                                "dynamic-sampler-index",
                                "index [$expr] into sampler array '$sampler': $line")
                        }
                    }
                }
            }
            // Pass 3: every for-loop trip count must be a compile-time
            // constant. The bound may name only the loop variable itself
            // and numeric literals; any other identifier (a uniform, a
            // varying, another variable) makes the trip count dynamic,
            // which several Mali drivers miscompile.
            forLoop.findAll(code).forEach { m ->
                val loopVar = m.groupValues[1]
                val bound = m.groupValues[2]
                val dynamic = identifier.findAll(bound)
                    .map { it.value }
                    .any { it != loopVar && it !in loopBoundKeywords }
                if (dynamic) {
                    val lineNumber =
                        code.substring(0, m.range.first).count { it == '\n' } + 1
                    val header = code.substring(m.range.first, m.range.last + 1)
                        .replace('\n', ' ').trim()
                    issues += ShaderSafetyIssue(name, lineNumber,
                        "dynamic-loop-bound",
                        "non-constant loop bound in: $header")
                }
            }
        }
        return issues
    }

    private fun stripLineComment(line: String): String {
        val i = line.indexOf("//")
        return if (i < 0) line else line.substring(0, i)
    }

    private fun stripBlockComments(src: String): String {
        val sb = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            if (src.startsWith("/*", i)) {
                val end = src.indexOf("*/", i + 2)
                // keep newlines so line numbers stay accurate
                val skipped = src.substring(i, if (end < 0) src.length else end + 2)
                sb.append("\n".repeat(skipped.count { it == '\n' }))
                i = if (end < 0) src.length else end + 2
            } else {
                sb.append(src[i])
                i++
            }
        }
        return sb.toString()
    }
}
