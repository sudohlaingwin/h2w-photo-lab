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
 * shaders only use constant bounds and this keeps it that way. Rule (e)
 * covers the same hazard in its `while` / `do`-`while` form, which rule (d)
 * does not see: any `while` condition naming an identifier (a uniform, a
 * varying, a mutable counter) is flagged, so only literal conditions such
 * as `while (true)` with an internal break may appear. The shipped shaders
 * contain no `while` loops at all, so the rule is pure future-proofing.
 * Rule (f) closes the remaining gap in rule (a): dynamic indexing of
 * NON-sampler arrays. The shipped HSL/B&W mixer loops indexed
 * `uHsl[i]` / `uBwMix[i]` / `centers[i]` with the loop variable until
 * 2026-10-19 — legal ES 3.00, but several Mali drivers miscompile it in
 * fragment shaders even when the loop bound is constant. The loops are now
 * unrolled and the audit flags any non-constant index into any declared
 * array, sampler or not. "Declared" covers uniforms, locals, AND
 * array-typed function parameters (`float g(float x[8]) { ... x[i] ... }`):
 * the local-array pattern skips every line containing `(`, so parameters
 * are tracked separately — otherwise a dynamic index into a parameter
 * array would slip past the rule entirely.
 * Rule (g) covers the remaining spec-level hazard: ES 1.00-only built-ins
 * (`gl_FragColor`, `gl_FragData`, `gl_TexCoord`, `texture2D*` / `textureCube*` /
 * `shadow2D*`, and the `varying` / `attribute` qualifiers) appearing in a
 * `#version 300 es` source. These are compile errors per the ES 3.00 spec —
 * lenient drivers sometimes accept the familiar names, but strict (Mali)
 * drivers reject the whole program, which for the preview shaders means a
 * black screen. Scoped to ES 3.00 sources only, like rule (c): ES 1.00
 * shaders legitimately use all of these.
 * Rule (h) closes the last identifier-level hazard: words the GLSL ES 3.00
 * spec reserves for future use (`sample`, `input`, `output`, `filter`,
 * `partition`, `coherent`, `volatile`, `restrict`, `readonly`, `writeonly`,
 * `resource`, …) used as variable or function names. Lenient drivers accept
 * them as identifiers, strict (Mali) drivers reject the whole program — the
 * same lenient/strict split behind rules (c) and (g). The shipped shaders
 * use none, so this is future-proofing: a habitual `float sample;` can
 * never ship silently.
 * Rule (i) closes the cross-stage gap: a vertex `out` varying and its
 * fragment `in` counterpart must agree on effective precision (explicit
 * qualifier, or the stage default when unqualified). The ES 3.00 spec
 * makes a precision mismatch a link-time error, but the two stages
 * predeclare DIFFERENT int defaults — vertex `precision highp int;`
 * versus fragment `precision mediump int;` — so a bare `out ivec2 v;` /
 * `in ivec2 v;` pair links fine nowhere strict yet compiles on every
 * lenient driver, then the program fails to link on strict (Mali) drivers
 * and the preview goes black. The shipped pairs (`vUv`, `vNdc`) are all
 * float-family, where both stages default to highp, so this is
 * future-proofing: the next `ivec2` varying can never ship half-qualified.
 * This audit scans every shipped shader for all nine patterns so a
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

    /**
     * `while` loop conditions, group 1 = the condition expression. Also
     * matches the trailing `while (…)` of a `do`-`while` — the same hazard.
     * The `\b` keeps identifiers like `meanwhile` from matching.
     */
    private val whileLoop = Regex("""\bwhile\s*\(\s*([^)]*)\)""")

    /** Boolean literals: the only identifiers legal in a `while` condition. */
    private val whileLiteralKeywords = setOf("true", "false")

    /**
     * `uniform <type> name[N]` for any NON-sampler type (also with a
     * precision qualifier). Sampler arrays are tracked separately by
     * [samplerArrayDecl]; the caller filters sampler types out via the
     * captured type name.
     */
    private val nonSamplerArrayDecl =
        Regex("""uniform\s+(?:(?:lowp|mediump|highp)\s+)?(\w+)\s+(\w+)\s*\[\s*(\d+)\s*\]""")

    /**
     * Local array declaration: `float centers[8];` (also `const`).
     * Anchored to line start so function declarations with array
     * parameters (`void f(float x[8]);`) never match; the audit additionally
     * skips lines containing `(` before applying this — array-typed
     * function PARAMETERS are covered separately by [arrayParamDecl].
     */
    private val localArrayDecl =
        Regex("""^\s*(?:const\s+)?(?:float|int|uint|bool|vec2|vec3|vec4|ivec2|ivec3|ivec4|uvec2|uvec3|uvec4|mat2|mat3|mat4)\s+(\w+)\s*\[\s*(\d+)\s*\]\s*;""")

    /**
     * Array-typed function parameter inside a parameter list: `float x[8]`,
     * `highp sampler2D tex[4]`, or unsized `vec3 v[]`. Applied ONLY to the
     * text between a signature's parentheses (see [paramListOf]), so a call
     * argument like `f(a[0])` — a single identifier before the `[`, not a
     * `type name` pair — can never match. The leading `(?:^|[\s,(])` keeps
     * constructor calls like `vec3(1.0)` (no `type name` before `[` anyway)
     * and comma-separated arguments from matching.
     */
    private val arrayParamDecl =
        Regex("""(?:^|[\s,(])(?:lowp|mediump|highp)?\s*(\w*[sS]ampler\w*|\w+)\s+(\w+)\s*\[\s*(\d*)\s*\]""")

    /**
     * ES 1.00-only built-ins: compile errors in a `#version 300 es` source
     * that strict (Mali) drivers reject outright (lenient drivers sometimes
     * accept the familiar names, which is why this ships as an audit rule
     * rather than a build error). `gl_FragCoord` is deliberately NOT in the
     * list — it is a valid ES 3.00 built-in. The `\b` boundaries keep
     * `gl_FragCoord` (which contains no `gl_FragColor` substring) and
     * identifiers like `mytexture2D` (no boundary before the `t`) from
     * matching.
     */
    private val es100Builtins = listOf(
        Regex("""\bgl_FragColor\b"""),
        Regex("""\bgl_FragData\b"""),
        Regex("""\bgl_TexCoord\b"""),
        Regex("""\btexture2D\w*\s*\("""),
        Regex("""\btextureCube\w*\s*\("""),
        Regex("""\bshadow2D\w*\s*\("""),
        Regex("""\bvarying\b"""),
        Regex("""\battribute\b"""),
    )

    /**
     * Words the GLSL ES 3.00 spec (§3.6) reserves for future use. Current
     * keywords (`if`, `for`, `struct`, …) need no rule — every compiler
     * rejects them as identifiers. These do not: lenient drivers accept
     * `float sample;` while strict (Mali) drivers reject the program, the
     * same lenient/strict split behind rules (c) and (g). `attribute` and
     * `varying` are also spec-reserved in ES 3.00 but rule (g) already owns
     * them there, so they are excluded here to keep one issue per cause.
     */
    private val reservedWords = listOf(
        "sample", "input", "output", "filter", "partition",
        "coherent", "volatile", "restrict", "readonly", "writeonly",
        "resource", "common", "cast", "class", "union", "enum",
        "template", "namespace", "using", "sizeof", "goto",
        "inline", "noinline", "public", "long", "short", "extern",
        "half", "fixed", "superp", "active", "asm",
    )

    /**
     * A reserved word in identifier position: immediately after a type
     * (with an optional precision qualifier and an optional storage
     * qualifier before it), or after `void` for a function name.
     * Matches `float sample;`, `uniform highp vec3 input;`,
     * `float filter(float x)`, and `void f(float output)` alike. The
     * `\\b` boundaries keep `sampleCount` and `resampled` from matching.
     */
    private val reservedIdentifier = Regex(
        """(?:^|[;{}()\s,])\s*(?:uniform|in|out|inout|const)?\s*""" +
            """(?:lowp|mediump|highp)?\s*(?:float|int|uint|bool|vec2|vec3|vec4|""" +
            """ivec2|ivec3|ivec4|uvec2|uvec3|uvec4|mat2|mat3|mat4|sampler\w+|void)""" +
            """\s+\b(${reservedWords.joinToString("|")})\b"""
    )

    /**
     * Rule (i): a cross-stage varying declaration — `out <prec?> <type>
     * <name>;` in a vertex shader, `in <prec?> <type> <name>;` in its
     * fragment shader. Anchored to the line start so `layout(location = 0)
     * in vec2 aPos;` (a vertex attribute, not a varying) never matches;
     * samplers cannot be varyings, so no sampler types are listed.
     */
    private val varyingDecl =
        Regex("""^\s*(in|out)\s+(?:(lowp|mediump|highp)\s+)?""" +
            """(float|int|uint|bool|vec2|vec3|vec4|ivec2|ivec3|ivec4|""" +
            """uvec2|uvec3|uvec4|mat2|mat3|mat4)\s+(\w+)\s*;""")

    /** One parsed varying declaration (group order follows [varyingDecl]). */
    private data class VaryingDecl(
        val name: String,
        val type: String,
        val precision: String?,
        val lineNumber: Int,
        val line: String,
    )

    /**
     * Rule (i): the stage default precision for a varying type when the
     * declaration carries no explicit qualifier. GLSL ES 3.00 predeclares
     * `precision highp float; precision highp int;` in the vertex language
     * but `precision highp float; precision mediump int;` in the fragment
     * language — so a bare `out ivec2 v;` / `in ivec2 v;` pair is highp on
     * the vertex side and mediump on the fragment side (`uint` follows
     * `int`). `bool` has no predeclared default in either stage: a bare
     * bool varying is an error on every driver, not a lenient/strict
     * split, so it resolves highp/highp here and can never mismatch.
     */
    private fun defaultVaryingPrecision(isVertex: Boolean, type: String): String {
        val intFamily = type == "int" || type == "uint" ||
            type.startsWith("i") || type.startsWith("u")
        return if (intFamily) {
            if (isVertex) "highp" else "mediump"
        } else {
            "highp"
        }
    }

    /**
     * Rule (i): `out` varyings of one source, keyed by name. GLSL built-ins
     * (`gl_Position`, …) are skipped — they have no cross-stage
     * declaration to match.
     */
    private fun parseVaryings(code: String, qualifier: String): Map<String, VaryingDecl> {
        val varyings = mutableMapOf<String, VaryingDecl>()
        code.lines().forEachIndexed { idx, rawLine ->
            val line = stripLineComment(rawLine).trim()
            if (line.isEmpty()) return@forEachIndexed
            val m = varyingDecl.matchEntire(line) ?: return@forEachIndexed
            if (m.groupValues[1] != qualifier) return@forEachIndexed
            val name = m.groupValues[4]
            if (name.startsWith("gl_")) return@forEachIndexed
            varyings[name] = VaryingDecl(
                name = name,
                type = m.groupValues[3],
                precision = m.groupValues[2].ifEmpty { null },
                lineNumber = idx + 1,
                line = line,
            )
        }
        return varyings
    }

    /**
     * The parameter-list text of a function signature line: the source
     * between the first `(` and its matching `)`. Returns "" when the
     * parens are unbalanced (the caller then finds no parameters —
     * fail-closed, never a false positive). Only single-line signatures
     * are covered, matching the audit's line-oriented regex approach;
     * multi-line signatures are rare in the shipped shaders and a split
     * signature simply yields no parameter tracking for that line.
     */
    private fun paramListOf(line: String): String {
        val open = line.indexOf('(')
        if (open < 0) return ""
        var depth = 0
        for (i in open until line.length) {
            when (line[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return line.substring(open + 1, i)
                }
            }
        }
        return ""
    }

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
     *    `#version 300 es` source (no default sampler precision there),
     *  - any `for` loop whose trip count is not a compile-time constant
     *    (dynamic loop bound — a classic Mali miscompile hazard), and
     *  - any `while` / `do`-`while` condition naming an identifier
     *    (dynamic while condition — the same hazard class as (d)), and
     *  - any ES 1.00-only built-in (`gl_FragColor`, `texture2D*`,
     *    `varying`, `attribute`, …) in a `#version 300 es` source —
     *    a spec-level compile error that strict Mali drivers reject
     *    outright (lenient drivers sometimes accept the familiar names),
     *    and
     *  - any non-sampler array (uniform, local, or array-typed function
     *    parameter) indexed with a non-constant expression — the
     *    loop-indexed `uHsl[i]` / `uBwMix[i]` / `centers[i]` HSL/B&W mixer
     *    loops shipped exactly this until 2026-10-19; several Mali drivers
     *    miscompile dynamic array indexing in fragment shaders even when
     *    the loop bound is constant, so the shipped shaders index every
     *    array with constants only, and
     *  - any GLSL-ES-3.00-reserved word (`sample`, `input`, `filter`, …)
     *    used as a variable or function name — strict Mali drivers reject
     *    the program while lenient drivers accept it, and
     *  - any vertex `out` varying whose effective precision disagrees with
     *    its fragment `in` counterpart — a link-time error per the ES 3.00
     *    spec that lenient drivers accept and strict (Mali) drivers
     *    reject; the int-family stage defaults differ (vertex highp vs
     *    fragment mediump), so even fully unqualified pairs can mismatch.
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
            // Pass 1b: declared non-sampler arrays (uniforms of any other
            // type, plus locals like `float centers[8];`).
            val plainArrays = mutableSetOf<String>()
            code.lines().forEach { rawLine ->
                val line = stripLineComment(rawLine)
                samplerArrayDecl.findAll(line).forEach { m ->
                    samplers[m.groupValues[1]] = m.groupValues[2].toInt()
                }
                nonSamplerArrayDecl.findAll(line).forEach { m ->
                    if (!m.groupValues[1].contains("sampler", ignoreCase = true)) {
                        plainArrays += m.groupValues[2]
                    }
                }
                if (!line.contains('(')) {
                    localArrayDecl.findAll(line).forEach { m ->
                        plainArrays += m.groupValues[1]
                    }
                } else {
                    // Array-typed function parameters, e.g.
                    // `float g(float x[8]) { ... x[i] ... }`: the local-array
                    // regex above skips every line containing `(`, so without
                    // this a dynamic index into a parameter array would slip
                    // past rule (f) — the same Mali miscompile hazard as a
                    // dynamic index into a uniform or local array. Sampler
                    // array parameters join [samplers] (rule a), the rest join
                    // [plainArrays] (rule f); unsized parameters (`vec3 v[]`)
                    // register with size 0 (the size is informational only —
                    // Pass 2 flags any non-constant index regardless).
                    arrayParamDecl.findAll(paramListOf(line)).forEach { m ->
                        val type = m.groupValues[1]
                        val name = m.groupValues[2]
                        if (type.contains("sampler", ignoreCase = true)) {
                            samplers[name] = m.groupValues[3].toIntOrNull() ?: 0
                        } else {
                            plainArrays += name
                        }
                    }
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
                // Rule (g): ES 1.00-only built-ins in an ES 3.00 source are
                // spec-level compile errors on strict (Mali) drivers.
                // One issue per line is enough — the offending line names
                // the builtin.
                if (isEs300) {
                    for (builtin in es100Builtins) {
                        if (builtin.containsMatchIn(line)) {
                            issues += ShaderSafetyIssue(name, idx + 1,
                                "es100-builtin", line)
                            break
                        }
                    }
                }
                // Rule (h): a reserved word in identifier position is a
                // strict-driver compile error (lenient drivers accept it).
                // One issue per line is enough — the line names the word.
                if (reservedIdentifier.containsMatchIn(line)) {
                    issues += ShaderSafetyIssue(name, idx + 1,
                        "reserved-identifier", line)
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
                // Pass 2b: every index into a declared non-sampler array
                // must be constant too — dynamic indexing of plain arrays
                // is the same Mali miscompile hazard as rule (a).
                for (arr in plainArrays) {
                    val use = Regex(Regex.escape(arr) + """\s*\[\s*([^\]]*)\]""")
                    use.findAll(line).forEach { m ->
                        val expr = m.groupValues[1].trim()
                        if (!constantIndex.matches(expr)) {
                            issues += ShaderSafetyIssue(name, idx + 1,
                                "dynamic-array-index",
                                "index [$expr] into array '$arr': $line")
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
            // Pass 4: every `while` / `do`-`while` condition must be a
            // compile-time constant expression. Unlike `for`, there is no
            // declared loop variable to exempt, so any identifier (uniform,
            // varying, mutable counter) makes the trip count dynamic — the
            // same Mali miscompile hazard as rule (d). Only boolean
            // literals (e.g. `while (true)` with an internal break) pass.
            // The shipped shaders contain no `while` loops, so this pass is
            // future-proofing: newly introduced `while` loops must use
            // literal conditions or be rewritten as constant-bound `for`.
            whileLoop.findAll(code).forEach { m ->
                val cond = m.groupValues[1]
                val dynamic = identifier.findAll(cond)
                    .map { it.value }
                    .any { it !in loopBoundKeywords && it !in whileLiteralKeywords }
                if (dynamic) {
                    val lineNumber =
                        code.substring(0, m.range.first).count { it == '\n' } + 1
                    issues += ShaderSafetyIssue(name, lineNumber,
                        "dynamic-while-condition",
                        "non-constant while condition in: while (${cond.trim()})")
                }
            }
        }
        // Pass 5 (rule i): every vertex `out` varying must agree on
        // effective precision with its fragment `in` counterpart. Sources
        // pair by name stem: VERTEX↔FRAGMENT, BLUR_VERTEX↔BLUR_FRAGMENT.
        // A mismatch is a link-time error per the ES 3.00 spec — lenient
        // drivers accept it, strict (Mali) drivers reject the program, and
        // for the preview shaders that means a black screen. A vertex `out`
        // with no fragment `in` is an unused varying, not an error, and is
        // left alone.
        val codeByName = sources.associate { (n, s) -> n to stripBlockComments(s) }
        for ((vertName, vertCode) in codeByName) {
            if (!vertName.endsWith("VERTEX")) continue
            val fragName = vertName.dropLast("VERTEX".length) + "FRAGMENT"
            val fragCode = codeByName[fragName] ?: continue
            val fragIns = parseVaryings(fragCode, "in")
            for ((vName, vDecl) in parseVaryings(vertCode, "out")) {
                val fDecl = fragIns[vName] ?: continue
                val vPrec = vDecl.precision
                    ?: defaultVaryingPrecision(isVertex = true, vDecl.type)
                val fPrec = fDecl.precision
                    ?: defaultVaryingPrecision(isVertex = false, fDecl.type)
                if (vPrec != fPrec) {
                    issues += ShaderSafetyIssue(fragName, fDecl.lineNumber,
                        "varying-precision-mismatch",
                        "varying '$vName' is $vPrec in $vertName but $fPrec " +
                            "in $fragName (link-time error on strict " +
                            "drivers): ${fDecl.line}")
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
