package com.lumora.photoeditor.engine

import kotlinx.serialization.Serializable
import kotlin.math.abs

/**
 * Non-destructive edit state for one image. The original pixels are never
 * modified; this parameter set is the single source of truth and is stored
 * as a JSON sidecar next to the library entry.
 *
 * All slider ranges mirror Lightroom conventions so behavior is predictable:
 * exposure in EV, most others in -100..100.
 */
@Serializable
data class EditState(
    val light: LightParams = LightParams(),
    val color: ColorParams = ColorParams(),
    val grading: GradingParams = GradingParams(),
    val calibration: CalibrationParams = CalibrationParams(),
    val detail: DetailParams = DetailParams(),
    val optics: OpticsParams = OpticsParams(),
    val geometry: GeometryParams = GeometryParams(),
    val masks: List<Mask> = emptyList(),
    val retouch: RetouchState = RetouchState(),
    val crop: CropParams = CropParams(),
    /**
     * AI Pro Retouch one-tap grade. The blend weights are image-specific
     * (derived from this photo's pixels), so [sanitizedForPaste] resets them
     * — same rule as retouch spots.
     */
    val aiRetouch: AiRetouchParams = AiRetouchParams(),
    /**
     * Look transfer: this photo's colors matched to a reference photo's
     * colors (Reinhard lαβ statistics -> CPU-baked 3D LUT). Both stat sets
     * are image-pair-specific, so [sanitizedForPaste] resets them — same
     * rule as [aiRetouch].
     */
    val lookTransfer: LookTransferParams = LookTransferParams(),
) {
    /** True when every parameter is at its default (image == original). */
    fun isDefault(): Boolean = this == DEFAULT

    /**
     * Copy-settings paste variant: drops image-specific work that must not
     * travel between photos — retouch spots (blemish positions sampled from
     * the source photo) and AI-derived mask components (rasters computed from
     * the source photo's pixels, e.g. Sky auto-select). Brush and gradient
     * masks plus every parametric adjustment paste through.
     */
    fun sanitizedForPaste(): EditState {
        val portable = setOf(
            MaskType.BRUSH, MaskType.LINEAR_GRADIENT, MaskType.RADIAL_GRADIENT)
        val masks = masks.mapNotNull { m ->
            val comps = m.components.filter { it.type in portable }
            if (comps.isEmpty()) null else m.copy(components = comps)
        }
        return copy(masks = masks, retouch = RetouchState(), aiRetouch = AiRetouchParams(),
            lookTransfer = LookTransferParams())
    }

    companion object {
        /**
         * Shared default instance. EditState is deeply immutable, so one
         * canonical instance is safe to reuse anywhere a fresh default is
         * needed — notably the GL thread's per-frame "show original" path,
         * which used to build ~a dozen objects 60x/sec.
         */
        val DEFAULT = EditState()
    }
}

/**
 * AI Pro Retouch state — the one-tap image-adaptive pro grade.
 *
 * [weights] blends the baked basis LUTs (order = [AiProRetouch.BASIS_NAMES],
 * sums to 1); [strength] 0..1 is the shader mix amount; [spatial] carries
 * the blended neighborhood-op deltas (clarity/texture/dehaze can't live in
 * a LUT) that were added to [LightParams] so Clear can subtract exactly them.
 * [wbGains] holds the per-photo white-balance gains [gR, gG, gB] measured at
 * apply time (null = photo needed no cast correction); the fused LUT is
 * fully determined by [weights] + [wbGains], so it is re-warped from these
 * whenever the GPU needs it (photo reopen, export, surface recreation).
 */
@Serializable
data class AiRetouchParams(
    val enabled: Boolean = false,
    val weights: List<Float> = listOf(0.25f, 0.25f, 0.25f, 0.25f),
    val strength: Float = 0f,
    val clarityDelta: Float = 0f,
    val textureDelta: Float = 0f,
    val dehazeDelta: Float = 0f,
    val wbGains: List<Float>? = null,
)

/**
 * Look transfer state — "match this photo's colors to a reference photo".
 *
 * [srcStats] / [refStats] are [ColorTransfer.analyze] outputs (6 floats:
 * lαβ means + std-devs) of this photo at apply time and of the reference
 * photo. The 3D LUT is fully determined by them, so it is re-baked from
 * these 12 floats whenever the GPU needs it (photo reopen, export, surface
 * recreation) — no reference-photo access required after the apply tap.
 * [refName] is the reference photo's display name, shown in the UI.
 * [strength] 0..1 rides as a live shader uniform (no re-bake on drag).
 */
@Serializable
data class LookTransferParams(
    val srcStats: List<Float> = emptyList(),
    val refStats: List<Float> = emptyList(),
    val refName: String = "",
    val strength: Float = 1f,
) {
    fun isActive(): Boolean = srcStats.size == 6 && refStats.size == 6
}

// ---------------------------------------------------------------------------
// Light
// ---------------------------------------------------------------------------

/** One editable point of a tone curve, in normalized 0..1 coordinates. */
@Serializable
data class CurvePoint(val x: Float, val y: Float)

@Serializable
data class ToneCurve(
    /** Sorted by x. Always contains at least (0,0) and (1,1) semantics. */
    val points: List<CurvePoint> = listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f)),
) {
    /** Catmull-Rom-interpolated lookup with [size] entries in 0..1. */
    fun toLut(size: Int = 256): FloatArray {
        val pts = points.sortedBy { it.x }
        val lut = FloatArray(size)
        if (pts.size < 2) {
            for (i in lut.indices) lut[i] = i / (size - 1f)
            return lut
        }
        for (i in lut.indices) {
            val x = i / (size - 1f)
            lut[i] = eval(pts, x).coerceIn(0f, 1f)
        }
        return lut
    }

    private fun eval(pts: List<CurvePoint>, x: Float): Float {
        if (x <= pts.first().x) return pts.first().y
        if (x >= pts.last().x) return pts.last().y
        var k = 0
        while (k < pts.size - 2 && x > pts[k + 1].x) k++
        // Two points: straight linear segment (Catmull-Rom would bow).
        if (pts.size == 2) {
            val (p1, p2) = pts
            val t = (x - p1.x) / (p2.x - p1.x).coerceAtLeast(1e-6f)
            return p1.y + (p2.y - p1.y) * t
        }
        val p0 = pts[maxOf(0, k - 1)]
        val p1 = pts[k]
        val p2 = pts[k + 1]
        val p3 = pts[minOf(pts.size - 1, k + 2)]
        val t = (x - p1.x) / (p2.x - p1.x).coerceAtLeast(1e-6f)
        // Catmull-Rom
        val t2 = t * t
        val t3 = t2 * t
        return 0.5f * (
            2f * p1.y + (-p0.y + p2.y) * t +
                (2f * p0.y - 5f * p1.y + 4f * p2.y - p3.y) * t2 +
                (-p0.y + 3f * p1.y - 3f * p2.y + p3.y) * t3
            )
    }
}

/**
 * Targeted-adjustment grab for a tone curve (Lightroom's TAT): given the
 * sampled on-screen tone [targetY] (0..1 — luminance for the master curve,
 * channel value for R/G/B), inverts the curve's LUT to find the controlling
 * input x, then either grabs the nearest existing point (within
 * [grabRadius] in x) or inserts a new point at the curve's current output
 * (so inserting never makes the image jump). Returns the new point list
 * (sorted by x, endpoints preserved) and the grabbed point's index.
 * Pure Kotlin — no Android dependencies.
 */
fun targetCurvePoint(
    curve: ToneCurve,
    targetY: Float,
    grabRadius: Float = 0.08f,
): Pair<List<CurvePoint>, Int> {
    val lut = curve.toLut(256)
    val ty = targetY.coerceIn(0f, 1f)
    var best = 0
    for (i in 1 until lut.size) {
        if (abs(lut[i] - ty) < abs(lut[best] - ty)) best = i
    }
    val xStar = best / 255f
    val pts = curve.points.sortedBy { it.x }
    for (i in pts.indices) {
        if (abs(pts[i].x - xStar) <= grabRadius) return pts to i
    }
    val at = pts.indexOfFirst { it.x > xStar }.let { if (it < 0) pts.size else it }
    val grown = pts.toMutableList()
    grown.add(at, CurvePoint(xStar, lut[best]))
    return grown to at
}

/**
 * Named tone-curve presets, Lightroom-style. Points are sorted by x and live
 * in 0..1 coordinates. Applies to any channel curve (master/red/green/blue).
 * Pure data — no Android dependencies — so the LUT math stays
 * harness-testable.
 */
enum class CurvePreset(val label: String, val points: List<CurvePoint>) {
    LINEAR(
        "Linear",
        listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f)),
    ),
    MEDIUM_CONTRAST(
        "Medium",
        listOf(
            CurvePoint(0f, 0f), CurvePoint(0.25f, 0.20f), CurvePoint(0.5f, 0.5f),
            CurvePoint(0.75f, 0.80f), CurvePoint(1f, 1f),
        ),
    ),
    STRONG_CONTRAST(
        "Strong",
        listOf(
            CurvePoint(0f, 0f), CurvePoint(0.25f, 0.13f), CurvePoint(0.5f, 0.5f),
            CurvePoint(0.75f, 0.87f), CurvePoint(1f, 1f),
        ),
    ),
}

/**
 * True when this curve equals the preset's points. A small epsilon tolerates
 * float noise from JSON serialization round-trips.
 */
fun ToneCurve.matchesPreset(preset: CurvePreset, eps: Float = 1e-3f): Boolean {
    if (points.size != preset.points.size) return false
    return points.zip(preset.points).all { (a, b) ->
        kotlin.math.abs(a.x - b.x) <= eps && kotlin.math.abs(a.y - b.y) <= eps
    }
}

/** The preset this curve currently equals, or null when the user has customized it. */
fun ToneCurve.activePreset(): CurvePreset? =
    CurvePreset.entries.firstOrNull { matchesPreset(it) }

/**
 * Partial-strength blend of two tone curves. Tone curves were the one
 * interpolatable structural param that Presets.blend() still treated as a
 * whole-curve switch (useB at t >= 0.5, like masks/geometry/crop) — so a
 * preset carrying a tone curve applied at 40% strength silently dropped the
 * curve, and at 60% snapped it on fully. Instead, both curves are resampled
 * to the same [samples]-point grid through their Catmull-Rom evaluation and
 * the y values are interpolated linearly: the honest partial application of
 * a curve, which is what the Preset panel's Strength slider promises.
 *
 * B&W conversion keeps its whole-switch behavior on purpose: a half-applied
 * B&W conversion is not meaningful, while a half-applied curve is.
 */
fun blendToneCurve(a: ToneCurve, b: ToneCurve, t: Float, samples: Int = 17): ToneCurve {
    val tt = t.coerceIn(0f, 1f)
    val la = a.toLut(samples)
    val lb = b.toLut(samples)
    val pts = List(samples) { i ->
        val x = i / (samples - 1f)
        CurvePoint(x, la[i] + (lb[i] - la[i]) * tt)
    }
    return ToneCurve(pts)
}

@Serializable
data class LightParams(
    val exposure: Float = 0f,      // -5..5 EV
    val contrast: Float = 0f,      // -100..100
    val highlights: Float = 0f,    // -100..100
    val shadows: Float = 0f,       // -100..100
    val whites: Float = 0f,        // -100..100
    val blacks: Float = 0f,        // -100..100
    val temp: Float = 0f,          // -100..100 (amber-blue)
    val tint: Float = 0f,          // -100..100 (green-magenta)
    val clarity: Float = 0f,       // -100..100 local contrast
    val texture: Float = 0f,       // -100..100 fine detail
    val dehaze: Float = 0f,        // -100..100
    val toneCurve: ToneCurve = ToneCurve(),
    val redCurve: ToneCurve = ToneCurve(),
    val greenCurve: ToneCurve = ToneCurve(),
    val blueCurve: ToneCurve = ToneCurve(),
)

// ---------------------------------------------------------------------------
// Color
// ---------------------------------------------------------------------------

/** The 8 Lightroom-style hue ranges of the color mixer. */
enum class HueRange(val centerDeg: Float, val label: String) {
    RED(0f, "Red"), ORANGE(30f, "Orange"), YELLOW(60f, "Yellow"),
    GREEN(120f, "Green"), AQUA(180f, "Aqua"), BLUE(240f, "Blue"),
    PURPLE(270f, "Purple"), MAGENTA(300f, "Magenta"),
}

/**
 * Targeted-adjustment helper: which [HueRange] a picked RGB belongs to.
 * Converts to hue and takes the closest range center by circular distance,
 * matching the shader's per-range center weighting. Pure Kotlin, no Android
 * deps — harness-testable.
 */
fun hueRangeFor(r: Float, g: Float, b: Float): HueRange {
    val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
    val d = mx - mn
    val hueDeg = if (d <= 1e-6f) {
        0f // near-gray: hue undefined, land on Red (closest center to 0)
    } else {
        val h = when {
            mx == r -> (g - b) / d + (if (g < b) 6f else 0f)
            mx == g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        } / 6f
        h * 360f
    }
    var best = HueRange.RED
    var bestDist = Float.MAX_VALUE
    for (range in HueRange.values()) {
        var dist = kotlin.math.abs(hueDeg - range.centerDeg) % 360f
        if (dist > 180f) dist = 360f - dist
        if (dist < bestDist) { bestDist = dist; best = range }
    }
    return best
}

@Serializable
data class HslAdjust(
    val hue: Float = 0f,          // -100..100 -> hue shift in degrees (/100*~30deg)
    val saturation: Float = 0f,   // -100..100
    val luminance: Float = 0f,    // -100..100
)

@Serializable
data class ColorParams(
    val vibrance: Float = 0f,     // -100..100
    val saturation: Float = 0f,   // -100..100
    /** Keyed by HueRange.name */
    val mixer: Map<String, HslAdjust> = emptyMap(),
    /** Black & white conversion switch. */
    val bw: Boolean = false,
    /** Per-range B&W luminance, -100..100; keyed by HueRange.name. */
    val bwMix: Map<String, Float> = emptyMap(),
) {
    fun forRange(range: HueRange): HslAdjust = mixer[range.name] ?: HslAdjust()
    fun bwMixFor(range: HueRange): Float = bwMix[range.name] ?: 0f
}

// ---------------------------------------------------------------------------
// Color grading (3-way + global)
// ---------------------------------------------------------------------------

/** A grading wheel: hue angle in degrees + saturation 0..100. */
@Serializable
data class GradeWheel(val hueDeg: Float = 0f, val sat: Float = 0f)

@Serializable
data class GradingParams(
    val shadows: GradeWheel = GradeWheel(),
    val midtones: GradeWheel = GradeWheel(),
    val highlights: GradeWheel = GradeWheel(),
    val global: GradeWheel = GradeWheel(),
    /** -100..100 shifts the midtone weight toward shadows/highlights. */
    val balance: Float = 0f,
    /** 0..100 strength of the whole grading. */
    val blending: Float = 100f,
)

// ---------------------------------------------------------------------------
// Calibration (Lightroom Camera Calibration panel)
// ---------------------------------------------------------------------------

/** Hue/saturation shift for one color primary. */
@Serializable
data class PrimaryAdjust(
    val hue: Float = 0f,   // -100..100
    val sat: Float = 0f,   // -100..100
)

/**
 * Lightroom-style camera calibration: fine-tunes the color science itself.
 * Shadows tint shifts the green-magenta balance in the shadows; the three
 * primaries remap the red/green/blue channels for film-like color.
 */
@Serializable
data class CalibrationParams(
    val shadowsTint: Float = 0f,          // -100..100 (green-magenta in shadows)
    val red: PrimaryAdjust = PrimaryAdjust(),
    val green: PrimaryAdjust = PrimaryAdjust(),
    val blue: PrimaryAdjust = PrimaryAdjust(),
)

// ---------------------------------------------------------------------------
// Detail
// ---------------------------------------------------------------------------

@Serializable
data class DetailParams(
    val sharpening: Float = 0f,       // 0..150
    val radius: Float = 1f,           // 0.5..3
    val detail: Float = 25f,          // 0..100 halo suppression
    val masking: Float = 0f,         // 0..100 edge mask
    val luminanceNr: Float = 0f,      // 0..100
    val luminanceDetail: Float = 50f, // 0..100
    val colorNr: Float = 25f,        // 0..100
    val colorDetail: Float = 50f,    // 0..100
    val moire: Float = 0f,           // 0..100 moiré (false-color oscillation) reduction
    val deconv: Float = 0f,          // 0..100 deconvolution sharpening amount (Wiener inverse)
    val deconvRadius: Float = 1f,    // 0.5..1.5 assumed capture-blur sigma (px) to invert
)

// ---------------------------------------------------------------------------
// Optics / effects
// ---------------------------------------------------------------------------

/** Light-leak overlay style for creative effects (Optics panel). */
@Serializable
enum class LightLeakStyle(val label: String) {
    NONE("None"),
    SUNSET("Sunset"),
    GOLDEN("Golden"),
    ROSE("Rose"),
    TEAL("Teal"),
}

/**
 * Creative effect overlays composited additively through the GPU pipeline
 * (preview, export and thumbnails all share the same render path).
 * Parametric — portable across photos, so copy/paste and presets keep it.
 */
@Serializable
data class FxParams(
    val leak: LightLeakStyle = LightLeakStyle.NONE,
    val leakAmount: Float = 0f,   // 0..100
    val dust: Float = 0f,         // 0..100 film dust/scratches
    val dustSeed: Long = 7L,      // shuffle for a different dust pattern
) {
    fun isActive(): Boolean =
        (leak != LightLeakStyle.NONE && leakAmount > 0f) || dust > 0f

    fun coerced(): FxParams = copy(
        leakAmount = leakAmount.coerceIn(0f, 100f),
        dust = dust.coerceIn(0f, 100f),
    )
}

@Serializable
data class OpticsParams(
    val distortion: Float = 0f,   // -100..100 barrel/pincushion
    val defringe: Float = 0f,     // 0..100 chromatic aberration
    val vignetteAmount: Float = 0f,   // -100..100
    val vignetteMidpoint: Float = 50f, // 0..100
    val vignetteFeather: Float = 50f,  // 0..100
    val vignetteRoundness: Float = 0f, // -100..100
    val grainAmount: Float = 0f,  // 0..100
    val grainSize: Float = 25f,   // 0..100
    val grainRoughness: Float = 50f, // 0..100
    // Lens blur (Snapseed-style bokeh): focus point + size, blur outside
    val lensBlurAmount: Float = 0f,   // 0..100
    val lensFocusX: Float = 0.5f,     // 0..1
    val lensFocusY: Float = 0.5f,     // 0..1
    val lensSize: Float = 35f,        // 0..100 in-focus radius
    val lensFeather: Float = 50f,     // 0..100 transition softness
    val lensShape: Int = 0,           // 0=radial, 1=linear (tilt-shift)
    // Creative effects: light leak + film dust overlays (GPU-composited)
    val fx: FxParams = FxParams(),
)

@Serializable
data class GeometryParams(
    val rotate: Float = 0f,        // -45..45 degrees (fine-tune)
    val rotate90: Int = 0,         // quarter turns, clockwise positive
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    val perspectiveV: Float = 0f,   // -100..100
    val perspectiveH: Float = 0f,   // -100..100
    /**
     * Lightroom "Constrain to Image": when true, any geometry change
     * (rotation, quarter turns, keystone) auto-shrinks the crop to the
     * largest inscribed rectangle so rotated photos never show black
     * corners. Off = free crop, black corners allowed.
     */
    val constrainCrop: Boolean = true,
)

@Serializable
data class CropParams(
    /** Normalized 0..1 rect in the source image; full image by default. */
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f,
    /** Target aspect or null for free. */
    val aspect: Float? = null,
    val locked: Boolean = false,
)

// ---------------------------------------------------------------------------
// Masks
// ---------------------------------------------------------------------------

enum class MaskType { BRUSH, LINEAR_GRADIENT, RADIAL_GRADIENT, AI_SUBJECT, AI_BACKGROUND, AI_SKIN, SKY_SELECT, COLOR_RANGE, LUMINANCE_RANGE, DEPTH_RANGE }

@Serializable
data class BrushStroke(
    val points: List<BrushPoint>,
    val size: Float,      // normalized to image width
    val feather: Float,   // 0..1
    val flow: Float,      // 0..1 — per-stamp build-up rate
    val opacity: Float,   // 0..1 — ceiling: strongest mask density this
                          // stroke can reach (paint never exceeds it, erase
                          // never drives below 1-opacity)
    val erase: Boolean,
    /** Pro auto-mask: constrain paint to pixels similar in luminance to the
     *  stroke center (Lightroom-style edge-aware brush). Prevents bleeding
     *  across strong edges. */
    val autoMask: Boolean = false,
    /** Luminance distance (0..1) at which auto-mask fully rejects a pixel. */
    val autoMaskTolerance: Float = 0.14f,
)

@Serializable
data class BrushPoint(val x: Float, val y: Float) // normalized 0..1

@Serializable
data class LocalAdjust(
    val exposure: Float = 0f,
    val contrast: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val temp: Float = 0f,
    val tint: Float = 0f,
    val saturation: Float = 0f,
    val clarity: Float = 0f,
    val sharpness: Float = 0f,
    val dehaze: Float = 0f,
)

enum class MaskOp { ADD, SUBTRACT, INTERSECT }

/**
 * One color swatch of a color-range mask (0..1 RGB) — the primary picked
 * target or an extra refine sample (Lightroom "add color swatch").
 */
@Serializable
data class ColorSample(val r: Float, val g: Float, val b: Float)

/**
 * One component of a mask (brush strokes, gradient, or AI selection).
 * Components composite in order: ADD takes the max, SUBTRACT removes,
 * INTERSECT keeps the overlap — mirroring Lightroom's mask components.
 */
@Serializable
data class MaskComponent(
    val id: String,
    val type: MaskType,
    val op: MaskOp = MaskOp.ADD,
    val strokes: List<BrushStroke> = emptyList(),
    /** linear: x1,y1,x2,y2 normalized; radial: cx,cy,rx,ry,rotationDeg,feather01 */
    val shape: List<Float> = emptyList(),
    /** For AI_* types: key of the cached raster in the AI mask store. */
    val aiKey: String? = null,
    /** Luminance-range selection (0..1 Rec.709 luma band + edge softness). */
    val lumaLo: Float = 0f,
    val lumaHi: Float = 1f,
    val lumaFeather: Float = 0.5f,
    /** Depth-range selection (0..1 estimated-depth band + edge softness).
     *  Depth is estimated from local focus sharpness (see
     *  ImageMath.estimateDepth): 1 = sharpest = assumed near. */
    val depthLo: Float = 0f,
    val depthHi: Float = 1f,
    val depthFeather: Float = 0.5f,
    /** Color-range selection: target color (0..1 RGB) + tolerance/feather. */
    val colorR: Float = 0f,
    val colorG: Float = 0f,
    val colorB: Float = 0f,
    val colorTolerance: Float = 0.45f,
    val colorFeather: Float = 0.5f,
    /** Extra refine swatches: unioned with the primary colorR..colorB target
     *  (max of per-sample strengths). Empty = primary target only. */
    val colorSamples: List<ColorSample> = emptyList(),
    val revision: Long = 0L,
)

@Serializable
data class Mask(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val inverted: Boolean = false,
    /** 0..1 effect strength — scales all of this mask's adjustments (Lightroom "Amount"). */
    val amount: Float = 1f,
    /** Pro refine: global edge feather 0..1 (blur of the composited mask edge,
     *  Lightroom "Refine > Feather"). 0 = off. */
    val refineFeather: Float = 0f,
    /** Pro refine: expand/contract -1..1 (morphological grow/shrink of the
     *  composited mask, Lightroom "Refine > Expand"). Positive = grow the
     *  selection, negative = contract it. 0 = off. */
    val refineExpand: Float = 0f,
    /** Hair/edge decontamination 0..1 (Lightroom "Decontaminate colors"):
     *  recolors feathered edge pixels from the selected interior so changing
     *  the background through an inverted mask (or grading the subject)
     *  leaves no color halo around hair. Deterministic inward-fill despill,
     *  not AI. 0 = off (fully backward compatible). */
    val decontaminate: Float = 0f,
    val components: List<MaskComponent> = emptyList(),
    val adjust: LocalAdjust = LocalAdjust(),
    /** Bumped whenever any component changes; drives GPU re-upload. */
    val revision: Long = 0L,
) {
    fun isEmpty(): Boolean = components.isEmpty()
}

// ---------------------------------------------------------------------------
// Retouch
// ---------------------------------------------------------------------------

enum class RetouchOp { HEAL, CLONE, SMOOTH_SKIN, EYE_ENHANCE, TEETH_WHITEN, UNDER_EYE, RED_EYE, REMOVE, DODGE, BURN }

/** Tonal range selector for DODGE/BURN (the classic darkroom range control).
 *  SHADOWS limits the effect to dark pixels, MIDTONES to middle grays,
 *  HIGHLIGHTS to bright pixels. Defaulted so old sidecars decode identically. */
@Serializable
enum class RetouchRange { ALL, SHADOWS, MIDTONES, HIGHLIGHTS }

@Serializable
data class RetouchSpot(
    val op: RetouchOp,
    val x: Float, val y: Float,       // normalized target
    val srcX: Float, val srcY: Float, // normalized source (clone/heal)
    val size: Float,                  // normalized to image width
    val feather: Float = 0.6f,
    val strength: Float = 1f,         // 0..1
    /** SMOOTH_SKIN only: how much fine detail (pores) is kept. 1 = keep all.
     *  Default 0.45f = the pre-slider behavior; the UI exposes it only for
     *  the Skin brush, and old saved states decode identically. */
    val textureKeep: Float = 0.45f,  // 0..1
    /** DODGE/BURN only: tonal range the effect is limited to. Ignored by all
     *  other ops. Default ALL preserves the old behavior exactly. */
    val range: RetouchRange = RetouchRange.ALL,
)

@Serializable
data class RetouchState(val spots: List<RetouchSpot> = emptyList())

// ---------------------------------------------------------------------------
// Presets
// ---------------------------------------------------------------------------

/**
 * A preset stores a *delta* EditState plus metadata. Applying = merge deltas
 * over current state scaled by [strength].
 */
@Serializable
data class Preset(
    val id: String,
    val name: String,
    val category: String,
    val state: EditState,
    val strength: Float = 1f,
    val builtIn: Boolean = true,
    val thumbnailSeed: Long = 0L,
)

/**
 * A named snapshot: the FULL EditState of one photo at a moment in time
 * (Lightroom parity). Unlike presets (cross-photo deltas) and history
 * (every step), a snapshot is a user-named bookmark for instant A/B
 * comparison of complete looks within the same photo.
 */
@Serializable
data class EditSnapshot(
    val id: String,
    val name: String,
    val createdAt: Long,
    val state: EditState,
)

// ---------------------------------------------------------------------------
// Export filename
// ---------------------------------------------------------------------------

/**
 * Sanitizes a user-entered export file name (stem only, no extension):
 * drops path separators, filesystem-illegal characters and control chars,
 * trims whitespace, strips leading dots (hidden files on Unix) and trailing
 * dots/spaces, and caps the length at 120 chars. The result is always safe
 * for MediaStore DISPLAY_NAME. Returns "" when nothing usable remains.
 * Pure Kotlin, no Android dependencies.
 */
fun sanitizeExportName(raw: String): String {
    var s = raw.trim()
    if (s.isEmpty()) return ""
    s = s.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "")
    s = s.trim().trimStart('.').trimEnd('.', ' ').trim()
    if (s.length > 120) s = s.take(120).trimEnd('.', ' ').trim()
    return s
}

// ---------------------------------------------------------------------------
// Export
// ---------------------------------------------------------------------------

// ExportFormat is serialized by name inside export presets.
@Serializable
enum class ExportFormat { JPEG, PNG, WEBP }

@Serializable
data class ExportSettings(
    val format: ExportFormat = ExportFormat.JPEG,
    val quality: Int = 92,            // 1..100 (lossy)
    val longEdge: Int = 0,            // 0 = original, else max dimension px
    val keepMetadata: Boolean = true,
    /**
     * Embeds an sRGB ICC color profile into JPEG/PNG exports so colors stay
     * correct in color-managed viewers and printers. The profile is injected
     * into the encoded bytes ([IccEmbed]) — a genuine byte-level feature, not
     * a placeholder: when true and the format supports it, the exported file
     * carries an APP2 (JPEG) / iCCP (PNG) segment. WebP exports are unchanged
     * (no ICC chunk support in the injector).
     */
    val embedColorProfile: Boolean = true,
    val watermark: WatermarkSettings = WatermarkSettings(),
    /** User-entered export file name (stem only, no extension). Blank = auto. */
    val fileName: String = "",
) {
    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        if (quality !in 1..100) errors += "Quality must be 1..100"
        if (longEdge < 0 || longEdge > 16000) errors += "Long edge must be 0..16000"
        errors += watermark.validate()
        return errors
    }

    fun mimeType(): String = when (format) {
        ExportFormat.JPEG -> "image/jpeg"
        ExportFormat.PNG -> "image/png"
        ExportFormat.WEBP -> "image/webp"
    }

    /**
     * Full gallery file name (stem + extension). Uses the sanitized
     * [fileName] when the user typed one. When blank, prefers the source
     * photo's own stem ([sourceStem], e.g. "IMG_2041" from the library
     * display name) so exported files stay findable — and otherwise falls
     * back to the previous auto-generated "Lumora_<timestamp>" naming.
     * [sourceStem] is sanitized and extension-stripped; a null/unusable one
     * degrades to the timestamp fallback, never to a bare extension.
     */
    fun galleryFileName(sourceStem: String? = null): String {
        val clean = sanitizeExportName(fileName)
        val stem = clean.ifEmpty {
            val fromSource = sanitizeExportName(
                sourceStem.orEmpty().substringBeforeLast('.').ifBlank { sourceStem.orEmpty() })
            fromSource.ifEmpty { "Lumora_${System.currentTimeMillis()}" }
        }
        return "$stem.${extension()}"
    }

    fun extension(): String = when (format) {
        ExportFormat.JPEG -> "jpg"
        ExportFormat.PNG -> "png"
        ExportFormat.WEBP -> "webp"
    }
}

/**
 * Canonical long-edge sizes (px) offered by the export UI. 0 = original
 * (no resize). Includes every size the built-in per-platform recipes use
 * (1080 Instagram post, 1920 Story/Reels, 1600 Web) so tapping a recipe
 * always lands on a selectable, honest value. The export pipeline itself
 * accepts any value in 0..16000 and never upscales.
 */
val EXPORT_LONG_EDGE_OPTIONS = listOf(0, 1080, 1600, 1920, 2048, 4096, 8192)

// ---------------------------------------------------------------------------
// Watermark (export)
// ---------------------------------------------------------------------------

/** Placement of the export watermark text. Serialized by name. */
@Serializable
enum class WatermarkPosition(val label: String) {
    BOTTOM_RIGHT("Bottom right"),
    BOTTOM_LEFT("Bottom left"),
    TOP_RIGHT("Top right"),
    TOP_LEFT("Top left"),
    CENTER("Center"),
}

/**
 * Text watermark stamped onto exported images. Pure presentation —
 * deterministic Canvas text drawing, no machine learning, no AI.
 */
@Serializable
data class WatermarkSettings(
    val enabled: Boolean = false,
    val text: String = "",
    val position: WatermarkPosition = WatermarkPosition.BOTTOM_RIGHT,
    /** Text height as a fraction of the image height (0.02..0.06). */
    val sizeFrac: Float = 0.035f,
    /** Text opacity (0.1..1). */
    val opacity: Float = 0.7f,
) {
    /** A watermark only draws when it is enabled AND has non-blank text. */
    fun isActive(): Boolean = enabled && text.isNotBlank()

    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        if (sizeFrac !in 0.02f..0.06f) errors += "Watermark size must be 0.02..0.06"
        if (opacity !in 0.1f..1f) errors += "Watermark opacity must be 0.1..1"
        return errors
    }
}

/** Horizontal text alignment for the watermark; maps to Paint.Align. */
enum class WatermarkAlign { LEFT, CENTER, RIGHT }

/**
 * Pixel layout of the watermark text on a [width] x [height] image.
 * Pure function (no Android drawing classes) so it is unit-testable.
 *
 * Text is inset 3% from the image edges; the draw call should set its
 * text alignment to [align] and draw at ([x], [baselineY]).
 */
data class WatermarkLayout(
    val textPx: Float,
    val x: Float,
    val baselineY: Float,
    val align: WatermarkAlign,
)

fun watermarkLayout(width: Int, height: Int, wm: WatermarkSettings): WatermarkLayout {
    val textPx = (wm.sizeFrac * height).coerceAtLeast(8f)
    val marginX = width * 0.03f
    val marginY = height * 0.03f
    val x = when (wm.position) {
        WatermarkPosition.BOTTOM_LEFT, WatermarkPosition.TOP_LEFT -> marginX
        WatermarkPosition.BOTTOM_RIGHT, WatermarkPosition.TOP_RIGHT -> width - marginX
        WatermarkPosition.CENTER -> width / 2f
    }
    val align = when (wm.position) {
        WatermarkPosition.BOTTOM_LEFT, WatermarkPosition.TOP_LEFT -> WatermarkAlign.LEFT
        WatermarkPosition.BOTTOM_RIGHT, WatermarkPosition.TOP_RIGHT -> WatermarkAlign.RIGHT
        WatermarkPosition.CENTER -> WatermarkAlign.CENTER
    }
    // Baseline placement: keep the cap-height box inside the 3% margin band.
    // Cap height ≈ 0.7 * textPx, so the baseline sits ~0.7*textPx below the
    // top margin and the descenders stay clear of the bottom margin.
    val baselineY = when (wm.position) {
        WatermarkPosition.BOTTOM_LEFT, WatermarkPosition.BOTTOM_RIGHT -> height - marginY
        WatermarkPosition.TOP_LEFT, WatermarkPosition.TOP_RIGHT -> marginY + textPx * 0.7f
        WatermarkPosition.CENTER -> height / 2f + textPx * 0.35f
    }
    return WatermarkLayout(textPx, x, baselineY, align)
}
