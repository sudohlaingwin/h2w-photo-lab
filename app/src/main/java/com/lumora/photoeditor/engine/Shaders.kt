package com.lumora.photoeditor.engine

/**
 * GLSL ES 3.0 pipeline implementing the full Lumora adjustment stack.
 *
 * Every sampler uniform carries an explicit `highp` precision qualifier:
 * the GLSL ES 3.00 spec makes an unqualified sampler a compile-time error,
 * and while the drivers we have seen (including the vivo V60's Adreno)
 * accept the unqualified form, a strict driver is allowed to reject the
 * whole program — which on a phone means a black preview. The qualifiers
 * are core syntax since ES 1.00, so this is pure hardening with no behavior
 * change. Verified with glslangValidator (Khronos reference compiler);
 * see hidden_files/shader-check/.
 *
 * Pipeline order (display-referred, mirrors professional raw converters):
 *  1. geometry UV (vertex) -> lens distortion + CA (fragment)
 *  2. retouch overlay composite
 *  3. exposure -> white balance -> parametric tone -> contrast
 *  4. tone curve + per-channel RGB curves
 *  5. HSL color mixer -> vibrance -> saturation
 *  6. 3-way + global color grading
 *  7. clarity / texture / dehaze (via blurred guide texture)
 *  8. local adjustments (up to 4 masks)
 *  9. sharpening + noise reduction (via blurred guide texture)
 * 10. vignette -> film grain -> clipping/mask overlays
 */
object Shaders {

    const val VERTEX = """
#version 300 es
layout(location = 0) in vec2 aPos;
uniform mat3 uUvMatrix;
uniform mat3 uZoom; // NDC -> NDC zoom/pan (identity = fit)
out vec2 vUv;
out vec2 vNdc; // clip-space position, for the before/after split divider
void main() {
    vec3 z = uZoom * vec3(aPos, 1.0);
    vec3 uv = uUvMatrix * vec3(z.xy / z.z, 1.0);
    vUv = uv.xy / uv.z;
    vNdc = aPos;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

    const val FRAGMENT = """
#version 300 es
precision highp float;

in vec2 vUv;
in vec2 vNdc;
out vec4 fragColor;

uniform highp sampler2D uImage;
uniform highp sampler2D uBlur;
uniform highp sampler2D uRetouch;
uniform float uHasRetouch;
// AI Pro Retouch: image-adaptive 3D LUT (baked from pro recipes, blended per
// photo). Applied early in the chain — right after the retouch composite —
// so the user's manual sliders compose on top of the AI base grade.
// Single sampler3D (not an array): no dynamic indexing, Mali-safe.
uniform highp sampler3D uAiLut;
uniform float uAiLutStrength; // 0..1 mix; 0 skips the sample entirely
// Look transfer: reference-photo color match (Reinhard lαβ -> CPU-baked 3D
// LUT). Same single-sampler3D pattern as the AI grade: no dynamic indexing,
// Mali-safe. Applied right after the AI grade as the final color grade, so
// the reference match sees the same colors its statistics were computed
// from and the user's manual sliders compose on top predictably.
uniform highp sampler3D uLookLut;
uniform float uLookLutStrength; // 0..1 mix; 0 skips the sample entirely
uniform highp sampler2D uCurves; // 256x1 RGBA: r=master g=red b=green a=blue

uniform float uExposure;
uniform float uContrast;
uniform float uHighlights;
uniform float uShadows;
uniform float uWhites;
uniform float uBlacks;
uniform float uTemp;
uniform float uTint;
uniform float uClarity;
uniform float uTextureAmt;
uniform float uDehaze;

uniform vec3 uHsl[8];
uniform float uVibrance;
uniform float uSaturation;
uniform float uBw;
uniform float uBwMix[8];

uniform vec3 uGradeS;
uniform vec3 uGradeM;
uniform vec3 uGradeH;
uniform vec3 uGradeG;
uniform float uGradeBalance;
uniform float uGradeStrength;
// Lightroom Camera Calibration: primary hue/sat (-100..100) + shadows tint.
uniform vec3 uCalHue;
uniform vec3 uCalSat;
uniform float uCalShadowTint;

uniform float uSharpen;
uniform float uSharpMasking;
uniform float uSharpRadius;   // 0.5..3, default 1 (effective edge gain)
uniform float uSharpDetail;   // 0..1, default 0.25 (halo suppression)
uniform float uLumNr;
uniform float uLumNrDetail;   // 0..1, default 0.5 (edge preservation)
uniform float uColorNr;
uniform float uColorNrDetail; // 0..1, default 0.5 (chroma edge preservation)
uniform float uMoire;         // 0..1, default 0 (moiré / false-color oscillation reduction)
uniform float uDeconv;        // 0/1 gate: deconvolution sharpening (Wiener inverse kernel)
uniform float uDeconvK[25];   // 5x5 Wiener inverse kernel, row-major, sums to 1
uniform vec2 uTexel;          // 1/textureWidth, 1/textureHeight of uImage

uniform float uDistortion;
uniform float uDefringe;
uniform vec4 uVignette; // amount, midpoint, feather, roundness
uniform vec4 uLensBlur; // amount 0..1, focusX, focusY, size 0..1
uniform vec2 uLensBlur2; // feather 0..1, shape 0=radial 1=linear
uniform vec3 uGrain;    // amount, size, roughness
uniform float uTime;

uniform int uMaskCount;
uniform highp sampler2D uMaskTex[4];
uniform vec4 uMaskA[4]; // exposure, contrast, highlights, shadows (-1..1)
uniform vec4 uMaskB[4]; // temp, tint, saturation, clarity (-1..1)
uniform vec2 uMaskC[4]; // sharpness, dehaze (-1..1 / 0..1)
// Mask inversion (Lightroom "Invert"): 0 = adjustments apply to the mask,
// 1 = to the inverse region. Constant indexing only — set per slot by GlEngine.
uniform float uMaskInv[4];
// Hair decontamination (Lightroom "Decontaminate colors"): per-slot strength
// 0..1. uDeconTex[i] holds the despilled RGB (inward-filled from the selected
// interior) at mask-raster resolution; the shader mixes it in over the
// mask's transition band BEFORE the local adjustments run, so the fringe
// pixels that carry background color get subject color instead. Constant
// indexing only (Mali-safe); uDecon = 0 skips the sample entirely, which
// keeps default rendering bit-identical. Texture units 11..14, past the
// look-transfer 3D LUT on unit 10.
uniform float uDecon[4];
uniform highp sampler2D uDeconTex[4];

uniform int uClipMode;
uniform int uOverlayMask;
uniform float uOverlayAlpha;
uniform int uMaskViewMode; // 0 = red tint overlay, 1 = black & white mask check view
uniform float uSplitX; // NDC x of the before|after divider; <= -1.5 disables

// Creative effects overlay: rgb = light-leak glow (additive), a = film dust.
// Single sampler with a constant index — no dynamic sampler indexing.
uniform highp sampler2D uFxTex;
uniform float uFxLeak; // 0..1 light-leak strength
uniform float uFxDust; // 0..1 film-dust strength

float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

vec3 rgb2hsl(vec3 c) {
    float mx = max(c.r, max(c.g, c.b));
    float mn = min(c.r, min(c.g, c.b));
    float h = 0.0; float s = 0.0;
    float l = (mx + mn) * 0.5;
    float d = mx - mn;
    if (d > 1e-6) {
        s = l > 0.5 ? d / (2.0 - mx - mn) : d / (mx + mn);
        if (mx == c.r) h = (c.g - c.b) / d + (c.g < c.b ? 6.0 : 0.0);
        else if (mx == c.g) h = (c.b - c.r) / d + 2.0;
        else h = (c.r - c.g) / d + 4.0;
        h /= 6.0;
    }
    return vec3(h, s, l);
}

float hue2rgb(float p, float q, float t) {
    if (t < 0.0) t += 1.0;
    if (t > 1.0) t -= 1.0;
    if (t < 1.0/6.0) return p + (q - p) * 6.0 * t;
    if (t < 1.0/2.0) return q;
    if (t < 2.0/3.0) return p + (q - p) * (2.0/3.0 - t) * 6.0;
    return p;
}

vec3 hsl2rgb(vec3 c) {
    float h = c.x; float s = c.y; float l = c.z;
    if (s < 1e-6) return vec3(l);
    float q = l < 0.5 ? l * (1.0 + s) : l + s - l * s;
    float p = 2.0 * l - q;
    return vec3(hue2rgb(p, q, h + 1.0/3.0), hue2rgb(p, q, h), hue2rgb(p, q, h - 1.0/3.0));
}

vec3 applyWb(vec3 c, float temp, float tint) {
    // temp: blue(-)<->amber(+); tint: green(-)<->magenta(+) — the Lightroom
    // convention: positive tint cuts green (adds magenta), negative adds green.
    c.r *= (1.0 + temp * 0.42);
    c.b *= (1.0 - temp * 0.42);
    c.g *= (1.0 - tint * 0.30);
    return c;
}

vec3 applyParametric(vec3 c, float hi, float sh, float wh, float bl) {
    float l = luma(c);
    float wH = smoothstep(0.45, 0.95, l);
    float wS = 1.0 - smoothstep(0.05, 0.55, l);
    float wW = smoothstep(0.72, 1.0, l);
    float wB = 1.0 - smoothstep(0.0, 0.28, l);
    float ev = hi * 1.35 * wH + sh * 1.35 * wS + wh * 0.9 * wW + bl * 0.9 * wB;
    return c * exp2(ev);
}

vec3 applyLocal(vec3 c, vec4 a, vec4 b, vec2 cc, float m) {
    if (m <= 0.0005) return c;
    vec3 lc = c;
    lc *= exp2(a.x * m);
    float l = luma(lc);
    float wH = smoothstep(0.45, 0.95, l);
    float wS = 1.0 - smoothstep(0.05, 0.55, l);
    lc *= exp2((a.z * 1.35 * wH + a.w * 1.35 * wS) * m);
    lc = applyWb(lc, b.x * m, b.y * m);
    float ll = luma(lc);
    lc = mix(vec3(ll), lc, clamp(1.0 + a.y * m, 0.0, 4.0));       // contrast
    lc = mix(vec3(luma(lc)), lc, clamp(1.0 + b.z * m, 0.0, 4.0)); // saturation
    return mix(c, lc, clamp(m, 0.0, 1.0));
}

float hash(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

// Full Lightroom-style tone chain: exposure -> white balance -> parametric
// tone -> contrast -> tone/RGB curves -> HSL mixer -> B&W -> vibrance/
// saturation -> color grading -> camera calibration. Applied to the image
// AND to the blurred detail guide: the guide texture is blurred from the
// raw source, so running it through the identical chain keeps the
// high-pass term (image - guide) DC-free. Previously the guide skipped
// this chain, and any exposure/WB change injected a DC offset into
// clarity/texture/sharpening — e.g. exposure +0.5 with clarity 50 lifted
// flat areas by ~30/255, sharpening 25 added ~29/255 of global brightness,
// and lens-blur regions rendered darker than the adjusted image.
// (Found 2026-10-17 during the render-path audit; harness-verified.)
vec3 applyToneChain(vec3 c) {
    // --- exposure / wb / parametric / contrast ---
    c *= exp2(uExposure);
    c = applyWb(c, uTemp, uTint);
    c = applyParametric(c, uHighlights, uShadows, uWhites, uBlacks);
    c = mix(vec3(0.5), c, clamp(1.0 + uContrast, 0.0, 3.0));

    // --- tone + rgb curves ---
    // uCurves is 256x1 RGBA: r=master g=red b=green a=blue.
    // Pass 1 MUST sample the master curve (.r) for all three channels;
    // pass 2 then applies each channel's own RGB curve. (A previous
    // revision sampled .g/.b in pass 1 — red/green curves leaking onto the
    // green/blue channels — so the master curve visibly moved only red.)
    vec3 lut;
    lut.r = texture(uCurves, vec2(clamp(c.r, 0.0, 1.0), 0.5)).r;
    lut.g = texture(uCurves, vec2(clamp(c.g, 0.0, 1.0), 0.5)).r;
    lut.b = texture(uCurves, vec2(clamp(c.b, 0.0, 1.0), 0.5)).r;
    c = lut;
    c.r = texture(uCurves, vec2(clamp(c.r, 0.0, 1.0), 0.5)).g;
    c.g = texture(uCurves, vec2(clamp(c.g, 0.0, 1.0), 0.5)).b;
    c.b = texture(uCurves, vec2(clamp(c.b, 0.0, 1.0), 0.5)).a;

    // --- HSL color mixer ---
    vec3 hsl = rgb2hsl(clamp(c, 0.0, 1.0));
    float centers[8];
    centers[0]=0.0; centers[1]=0.0833; centers[2]=0.1667; centers[3]=0.3333;
    centers[4]=0.5; centers[5]=0.6667; centers[6]=0.75; centers[7]=0.8333;
    for (int i = 0; i < 8; i++) {
        float d = abs(fract(hsl.x - centers[i] + 0.5) - 0.5);
        float w = 1.0 - smoothstep(0.045, 0.10, d);
        if (w > 0.001) {
            hsl.x = fract(hsl.x + uHsl[i].x * 0.09 * w);
            hsl.y = clamp(hsl.y * (1.0 + uHsl[i].y * w), 0.0, 1.0);
            hsl.z = clamp(hsl.z + uHsl[i].z * 0.35 * w, 0.0, 1.0);
        }
    }
    c = hsl2rgb(hsl);

    // --- black & white conversion ---
    if (uBw > 0.5) {
        vec3 bhsl = rgb2hsl(clamp(c, 0.0, 1.0));
        float bw = luma(c);
        for (int i = 0; i < 8; i++) {
            float d = abs(fract(bhsl.x - centers[i] + 0.5) - 0.5);
            float w = 1.0 - smoothstep(0.045, 0.10, d);
            if (w > 0.001) {
                bw = clamp(bw + uBwMix[i] * 0.35 * w, 0.0, 1.0);
            }
        }
        c = vec3(bw);
    }

    // --- vibrance / saturation ---
    float sat = clamp((max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b))) /
        max(max(c.r, max(c.g, c.b)) + min(c.r, min(c.g, c.b)), 1e-4), 0.0, 1.0);
    float vibAmt = 1.0 + uVibrance * (1.15 - sat) * 1.2;
    float lum0 = luma(c);
    c = mix(vec3(lum0), c, clamp(vibAmt, 0.0, 3.0));
    c = mix(vec3(luma(c)), c, clamp(1.0 + uSaturation, 0.0, 3.0));

    // --- color grading ---
    float lum1 = luma(c);
    float lb = clamp(lum1 + uGradeBalance * 0.25, 0.0, 1.0);
    float wGs = 1.0 - smoothstep(0.0, 0.42, lb);
    float wGh = smoothstep(0.58, 1.0, lb);
    float wGm = clamp(1.0 - wGs - wGh, 0.0, 1.0);
    vec3 grade = (uGradeS - 0.5) * 2.0 * wGs + (uGradeM - 0.5) * 2.0 * wGm +
                 (uGradeH - 0.5) * 2.0 * wGh + (uGradeG - 0.5) * 2.0;
    c += grade * uGradeStrength * 0.55;

    // --- calibration (Lightroom Camera Calibration panel) ---
    // Shadows tint: green<->magenta balance in the shadows only.
    float calLum = luma(c);
    float shTintW = 1.0 - smoothstep(0.0, 0.5, calLum);
    c.g -= uCalShadowTint * shTintW * 0.10;
    c.r += uCalShadowTint * shTintW * 0.05;
    c.b += uCalShadowTint * shTintW * 0.05;
    // Primary hue/sat: shift colors near each primary's hue. Weights fall off
    // over 60 degrees so adjustments blend smoothly between primaries.
    vec3 calHsl = rgb2hsl(clamp(c, 0.0, 1.0));
    float hueDeg = calHsl.x * 360.0;
    float wR = 1.0 - clamp(min(abs(hueDeg), 360.0 - abs(hueDeg)) / 60.0, 0.0, 1.0);
    float wG = 1.0 - clamp(abs(hueDeg - 120.0) / 60.0, 0.0, 1.0);
    float wB = 1.0 - clamp(abs(hueDeg - 240.0) / 60.0, 0.0, 1.0);
    float hueShift = (uCalHue.x * wR + uCalHue.y * wG + uCalHue.z * wB) * (30.0 / 360.0 / 100.0);
    float satMul = 1.0 + (uCalSat.x * wR + uCalSat.y * wG + uCalSat.z * wB) / 100.0;
    calHsl.x = fract(calHsl.x + hueShift);
    calHsl.y = clamp(calHsl.y * satMul, 0.0, 1.0);
    c = hsl2rgb(calHsl);

    return c;
}

void main() {
    vec2 uv = vUv;
    if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {
        fragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }

    // --- lens distortion + chromatic aberration ---
    vec2 centered = uv - 0.5;
    float r2 = dot(centered, centered);
    vec2 distUv = 0.5 + centered * (1.0 + uDistortion * r2 * 2.0);
    float ca = uDefringe * r2 * 0.06;
    vec2 off = centered * ca / max(r2, 1e-5);
    // pristine source texel: the "before" side of a split-view compare
    vec3 srcOrig = texture(uImage, uv).rgb;
    vec3 c;
    if (uDeconv > 0.001) {
        // --- deconvolution sharpening (pro-spec Phase 3) ---
        // Inverts mild capture blur with a Wiener inverse kernel (computed
        // on CPU from the assumed blur sigma; see DeconvKernel.kt) instead
        // of boosting edges like unsharp mask. Runs on the source fetch so
        // it behaves as capture sharpening at the front of the pipeline.
        // Mali-safe: float uniform array with constant indices, unrolled
        // (the sampler-array restriction does not apply to float arrays).
        // The subpixel CA offset is skipped in this path (documented).
        vec3 dc = vec3(0.0);
        dc += texture(uImage, distUv + uTexel * vec2(-2.0, -2.0)).rgb * uDeconvK[0];
        dc += texture(uImage, distUv + uTexel * vec2(-1.0, -2.0)).rgb * uDeconvK[1];
        dc += texture(uImage, distUv + uTexel * vec2( 0.0, -2.0)).rgb * uDeconvK[2];
        dc += texture(uImage, distUv + uTexel * vec2( 1.0, -2.0)).rgb * uDeconvK[3];
        dc += texture(uImage, distUv + uTexel * vec2( 2.0, -2.0)).rgb * uDeconvK[4];
        dc += texture(uImage, distUv + uTexel * vec2(-2.0, -1.0)).rgb * uDeconvK[5];
        dc += texture(uImage, distUv + uTexel * vec2(-1.0, -1.0)).rgb * uDeconvK[6];
        dc += texture(uImage, distUv + uTexel * vec2( 0.0, -1.0)).rgb * uDeconvK[7];
        dc += texture(uImage, distUv + uTexel * vec2( 1.0, -1.0)).rgb * uDeconvK[8];
        dc += texture(uImage, distUv + uTexel * vec2( 2.0, -1.0)).rgb * uDeconvK[9];
        dc += texture(uImage, distUv + uTexel * vec2(-2.0,  0.0)).rgb * uDeconvK[10];
        dc += texture(uImage, distUv + uTexel * vec2(-1.0,  0.0)).rgb * uDeconvK[11];
        dc += texture(uImage, distUv + uTexel * vec2( 0.0,  0.0)).rgb * uDeconvK[12];
        dc += texture(uImage, distUv + uTexel * vec2( 1.0,  0.0)).rgb * uDeconvK[13];
        dc += texture(uImage, distUv + uTexel * vec2( 2.0,  0.0)).rgb * uDeconvK[14];
        dc += texture(uImage, distUv + uTexel * vec2(-2.0,  1.0)).rgb * uDeconvK[15];
        dc += texture(uImage, distUv + uTexel * vec2(-1.0,  1.0)).rgb * uDeconvK[16];
        dc += texture(uImage, distUv + uTexel * vec2( 0.0,  1.0)).rgb * uDeconvK[17];
        dc += texture(uImage, distUv + uTexel * vec2( 1.0,  1.0)).rgb * uDeconvK[18];
        dc += texture(uImage, distUv + uTexel * vec2( 2.0,  1.0)).rgb * uDeconvK[19];
        dc += texture(uImage, distUv + uTexel * vec2(-2.0,  2.0)).rgb * uDeconvK[20];
        dc += texture(uImage, distUv + uTexel * vec2(-1.0,  2.0)).rgb * uDeconvK[21];
        dc += texture(uImage, distUv + uTexel * vec2( 0.0,  2.0)).rgb * uDeconvK[22];
        dc += texture(uImage, distUv + uTexel * vec2( 1.0,  2.0)).rgb * uDeconvK[23];
        dc += texture(uImage, distUv + uTexel * vec2( 2.0,  2.0)).rgb * uDeconvK[24];
        c = dc;
    } else {
        c.r = texture(uImage, distUv + off).r;
        c.g = texture(uImage, distUv).g;
        c.b = texture(uImage, distUv - off).b;
    }

    // --- retouch overlay (straight alpha, pre-adjustment composite) ---
    if (uHasRetouch > 0.5) {
        vec4 rt = texture(uRetouch, uv);
        c = mix(c, rt.rgb, rt.a);
    }

    // --- AI Pro Retouch 3D LUT (image-adaptive pro grade) ---
    // Hardware trilinear via sampler3D linear filtering. Applied here (not
    // at the end) so it behaves as the base grade: manual exposure/WB/curve
    // edits the user makes afterwards compose on top predictably.
    if (uAiLutStrength > 0.001) {
        vec3 aiGraded = texture(uAiLut, clamp(c, 0.0, 1.0)).rgb;
        c = mix(c, aiGraded, clamp(uAiLutStrength, 0.0, 1.0));
    }

    // --- Look transfer 3D LUT (reference-photo color match) ---
    // Hardware trilinear via sampler3D linear filtering. Same gating pattern
    // as the AI grade above.
    if (uLookLutStrength > 0.001) {
        vec3 lookGraded = texture(uLookLut, clamp(c, 0.0, 1.0)).rgb;
        c = mix(c, lookGraded, clamp(uLookLutStrength, 0.0, 1.0));
    }

    vec3 blurC = texture(uBlur, uv).rgb;
    // Detail guide through the identical tone chain (AI grade included):
    // keeps the high-pass term (image - guide) DC-free under any global
    // tone change, so Clarity/Texture/Sharpening never shift overall
    // brightness and lens-blur regions match the adjusted image.
    vec3 blurAdj = blurC;
    if (uAiLutStrength > 0.001) {
        vec3 aiGuide = texture(uAiLut, clamp(blurAdj, 0.0, 1.0)).rgb;
        blurAdj = mix(blurAdj, aiGuide, clamp(uAiLutStrength, 0.0, 1.0));
    }
    // The look-transfer LUT also shifts global color/luminance (it matches
    // the reference's means), so the guide must go through it too — same
    // DC-consistency rule as the AI grade above.
    if (uLookLutStrength > 0.001) {
        vec3 lookGuide = texture(uLookLut, clamp(blurAdj, 0.0, 1.0)).rgb;
        blurAdj = mix(blurAdj, lookGuide, clamp(uLookLutStrength, 0.0, 1.0));
    }
    blurAdj = applyToneChain(blurAdj);

    c = applyToneChain(c);

    // --- clarity / texture / dehaze ---
    vec3 hp = c - blurAdj;
    c += hp * (uClarity * 1.15 + uTextureAmt * 0.55);
    float dz = uDehaze;
    if (abs(dz) > 0.001) {
        float ldz = luma(c);
        c = mix(vec3(ldz), c, 1.0 + dz * 0.35);
        c = (c - 0.5) * (1.0 + dz * 0.30) + 0.5;
    }

    // --- local adjustments (masks) ---
    // Note: sampler arrays cannot be indexed with non-constant expressions
    // on many GPUs (e.g. Mali), so the loop is manually unrolled.
    if (uMaskCount > 0) {
        float m0 = texture(uMaskTex[0], uv).r;
        m0 = mix(m0, 1.0 - m0, uMaskInv[0]); // Lightroom "Invert"
        if (uDecon[0] > 0.0) {
            // Decontaminate colors: swap fringe pixels toward the despilled
            // interior color, strongest mid-band (m*(1-m)*4 peaks at m=0.5).
            vec3 dc0 = texture(uDeconTex[0], uv).rgb;
            c = mix(c, dc0, uDecon[0] * m0 * (1.0 - m0) * 4.0);
        }
        c = applyLocal(c, uMaskA[0], uMaskB[0], uMaskC[0], m0);
        c += hp * (uMaskB[0].w * 1.15) * m0;
        c += hp * (uMaskC[0].x * 2.0) * m0;
        float ldz0 = luma(c);
        c = mix(c, mix(vec3(ldz0), c, 1.0 + uMaskC[0].y * m0 * 0.35), clamp(abs(uMaskC[0].y * m0), 0.0, 1.0));
    }
    if (uMaskCount > 1) {
        float m1 = texture(uMaskTex[1], uv).r;
        m1 = mix(m1, 1.0 - m1, uMaskInv[1]); // Lightroom "Invert"
        if (uDecon[1] > 0.0) {
            vec3 dc1 = texture(uDeconTex[1], uv).rgb;
            c = mix(c, dc1, uDecon[1] * m1 * (1.0 - m1) * 4.0);
        }
        c = applyLocal(c, uMaskA[1], uMaskB[1], uMaskC[1], m1);
        c += hp * (uMaskB[1].w * 1.15) * m1;
        c += hp * (uMaskC[1].x * 2.0) * m1;
        float ldz1 = luma(c);
        c = mix(c, mix(vec3(ldz1), c, 1.0 + uMaskC[1].y * m1 * 0.35), clamp(abs(uMaskC[1].y * m1), 0.0, 1.0));
    }
    if (uMaskCount > 2) {
        float m2 = texture(uMaskTex[2], uv).r;
        m2 = mix(m2, 1.0 - m2, uMaskInv[2]); // Lightroom "Invert"
        if (uDecon[2] > 0.0) {
            vec3 dc2 = texture(uDeconTex[2], uv).rgb;
            c = mix(c, dc2, uDecon[2] * m2 * (1.0 - m2) * 4.0);
        }
        c = applyLocal(c, uMaskA[2], uMaskB[2], uMaskC[2], m2);
        c += hp * (uMaskB[2].w * 1.15) * m2;
        c += hp * (uMaskC[2].x * 2.0) * m2;
        float ldz2 = luma(c);
        c = mix(c, mix(vec3(ldz2), c, 1.0 + uMaskC[2].y * m2 * 0.35), clamp(abs(uMaskC[2].y * m2), 0.0, 1.0));
    }
    if (uMaskCount > 3) {
        float m3 = texture(uMaskTex[3], uv).r;
        m3 = mix(m3, 1.0 - m3, uMaskInv[3]); // Lightroom "Invert"
        if (uDecon[3] > 0.0) {
            vec3 dc3 = texture(uDeconTex[3], uv).rgb;
            c = mix(c, dc3, uDecon[3] * m3 * (1.0 - m3) * 4.0);
        }
        c = applyLocal(c, uMaskA[3], uMaskB[3], uMaskC[3], m3);
        c += hp * (uMaskB[3].w * 1.15) * m3;
        c += hp * (uMaskC[3].x * 2.0) * m3;
        float ldz3 = luma(c);
        c = mix(c, mix(vec3(ldz3), c, 1.0 + uMaskC[3].y * m3 * 0.35), clamp(abs(uMaskC[3].y * m3), 0.0, 1.0));
    }

    // --- sharpening + noise reduction ---
    float lumaC = luma(c);
    float lumaB = luma(blurAdj);
    float edge = lumaC - lumaB;
    // uSharpRadius: the guide texture is a fixed-scale blur, so radius acts
    // as an effective edge gain (larger radius strengthens the broad-edge
    // response); 1.0 is the identity, matching the old fixed behavior.
    float edgeGain = pow(max(uSharpRadius, 0.01), 0.75);
    // uSharpDetail: halo suppression. Large per-pixel corrections are
    // clamped harder at low detail (fewer halos, softer look); high detail
    // allows the full correction (crisper, more halos).
    float haloCap = mix(0.15, 0.8, clamp(uSharpDetail, 0.0, 1.0));
    float corr = edge * uSharpen * 2.2 * edgeGain;
    corr = sign(corr) * min(abs(corr), haloCap);
    float edgeGate = smoothstep(uSharpMasking * 0.35, uSharpMasking * 0.35 + 0.12, abs(edge));
    c += vec3(corr * mix(1.0, edgeGate, clamp(uSharpMasking, 0.0, 1.0)));
    if (uLumNr > 0.001) {
        float f = uLumNr * 0.85;
        // uLumNrDetail: higher values preserve genuine edges from
        // smoothing (detail 0 smooths almost everything, detail 1 keeps
        // edges above ~0.10 luma difference). Edges stay ordered so the
        // smoothstep is always well-defined.
        float kd = clamp(uLumNrDetail, 0.0, 1.0);
        float edgeKeep = smoothstep(0.02 + 0.40 * (1.0 - kd), 0.10 + 0.40 * (1.0 - kd), abs(edge));
        float newLum = mix(lumaC, lumaB, f * (1.0 - edgeKeep));
        c += vec3(newLum - lumaC);
    }
    if (uColorNr > 0.001) {
        vec3 chroma = c - vec3(luma(c));
        vec3 chromaB = blurAdj - vec3(lumaB);
        // uColorNrDetail: higher values preserve strong chroma structure
        // from smoothing; fine chroma variation is always smoothed.
        float chromaEdge = length(chroma - chromaB);
        float kc = clamp(uColorNrDetail, 0.0, 1.0);
        float keepC = smoothstep(0.02 + 0.30 * (1.0 - kc), 0.10 + 0.30 * (1.0 - kc), chromaEdge);
        vec3 nc = mix(chroma, chromaB, uColorNr * 0.9 * (1.0 - keepC));
        c = vec3(luma(c)) + nc;
    }
    if (uMoire > 0.001) {
        // Moiré / false-color reduction (pro-spec Phase 3). Moiré shows up
        // as rapid chroma oscillation on an otherwise smooth luminance
        // field (fabric, screens, roof tiles) — the guide blur (blurAdj)
        // averages those oscillations away, so chroma pulled toward the
        // guide kills the rainbow while the luminance gate keeps genuine
        // color detail (which normally carries luma variation too).
        // Mali-safe: pure arithmetic on existing varyings, no new samplers.
        float lumNow = luma(c);
        vec3 chromaM = c - vec3(lumNow);
        vec3 chromaBM = blurAdj - vec3(lumaB);
        float osc = length(chromaM - chromaBM);
        float lumVar = abs(lumNow - lumaB);
        float mGate = smoothstep(0.03, 0.10, osc) *
            (1.0 - smoothstep(0.04, 0.12, lumVar));
        vec3 mc = mix(chromaM, chromaBM, clamp(uMoire, 0.0, 1.0) * mGate);
        c = vec3(lumNow) + mc;
    }

    c = clamp(c, 0.0, 1.0);

    // --- lens blur (Snapseed-style bokeh) ---
    if (uLensBlur.x > 0.001) {
        vec2 focus = uLensBlur.yz;
        float d;
        if (uLensBlur2.y > 0.5) {
            // linear (tilt-shift): distance from horizontal band through focus
            d = abs(uv.y - focus.y) * 2.0;
        } else {
            // radial: distance from focus point
            d = length(uv - focus) * 1.4;
        }
        float inner = uLensBlur.w * 0.7;
        float feather = mix(0.05, 0.6, uLensBlur2.x);
        float lbMask = smoothstep(inner, inner + feather + 0.02, d);
        // Tone-matched guide (not the raw source blur): blurred regions keep
        // the adjusted brightness instead of the pre-adjustment one.
        vec3 blurred = blurAdj;
        c = mix(c, blurred, clamp(lbMask * uLensBlur.x, 0.0, 1.0));
    }

    // --- vignette ---
    vec2 vc = (uv - 0.5) * vec2(1.0 - uVignette.w * 0.5, 1.0 + uVignette.w * 0.5);
    float vd = length(vc) * 1.42;
    float vAmt = uVignette.x;
    float vMask = smoothstep(uVignette.y * 0.9, uVignette.y * 0.9 + (1.0 - uVignette.z * 0.85) + 0.05, vd);
    c *= (1.0 - vAmt * vMask * 0.85);

    // --- film grain ---
    if (abs(uGrain.x) > 0.001) {
        float gscale = mix(480.0, 90.0, uGrain.y);
        float n = hash(uv * gscale + fract(uTime) * 7.13);
        float n2 = hash(uv * gscale * 0.37 - fract(uTime) * 3.7);
        float gAmt = uGrain.x * 0.16 * mix(1.0, 1.0 - luma(c), uGrain.z * 0.7);
        c += vec3((n - 0.5) * 2.0 * gAmt * 0.6 + (n2 - 0.5) * 2.0 * gAmt * 0.4);
    }

    c = clamp(c, 0.0, 1.0);

    // --- overlays ---
    if (uClipMode == 1 || uClipMode == 3) {
        if (luma(c) < 0.012) c = mix(c, vec3(0.1, 0.35, 1.0), 0.75);
    }
    if (uClipMode == 2 || uClipMode == 3) {
        if (luma(c) > 0.988) c = mix(c, vec3(1.0, 0.15, 0.1), 0.75);
    }
    // The overlay index is valid for any of the 4 fixed texture slots, not
    // just the user-mask count: the object-removal tool reserves the slot
    // right after the last user mask (overlayMask == uMaskCount) for its
    // live red overlay. Guarding on uMaskCount kept that overlay from ever
    // rendering.
    if (uOverlayMask >= 0 && uOverlayMask < 4) {
        // unrolled: some drivers mishandle dynamic sampler indexing
        float m = 0.0;
        float inv = 0.0;
        if (uOverlayMask == 0) { m = texture(uMaskTex[0], uv).r; inv = uMaskInv[0]; }
        else if (uOverlayMask == 1) { m = texture(uMaskTex[1], uv).r; inv = uMaskInv[1]; }
        else if (uOverlayMask == 2) { m = texture(uMaskTex[2], uv).r; inv = uMaskInv[2]; }
        else { m = texture(uMaskTex[3], uv).r; inv = uMaskInv[3]; }
        m = mix(m, 1.0 - m, inv); // show the inverted region when the mask is inverted
        // uMaskViewMode is a uniform, so this branch is uniform control flow
        // (safe on all drivers). B&W mode shows the mask itself at full
        // opacity, like Lightroom's mask check view; red mode tints as before.
        if (uMaskViewMode == 1) c = vec3(m);
        else c = mix(c, vec3(1.0, 0.25, 0.2), m * uOverlayAlpha);
    }

    // --- creative effects: light leak (additive) + film dust (alpha specks).
    // Uniform control flow only (safe on all drivers); applied BEFORE the
    // split so the "before" side always shows the pristine original. Skipped
    // in the B&W mask check view, which must show the mask alone — same
    // principle as view aids not leaking onto the "before" side.
    if (uFxLeak > 0.001 || uFxDust > 0.001) {
        if (!(uMaskViewMode == 1 && uOverlayMask >= 0 && uOverlayMask < 4)) {
            vec4 fx = texture(uFxTex, uv);
            c += fx.rgb * uFxLeak;
            c = mix(c, fx.rgb, fx.a * uFxDust);
        }
    }

    // --- before/after split: left of the divider shows the original photo.
    // Applied last so view aids (clipping zebras, mask overlay) never leak
    // onto the "before" side. uSplitX is in NDC; <= -1.5 disables.
    if (uSplitX > -1.5 && vNdc.x < uSplitX) {
        c = srcOrig;
    }

    fragColor = vec4(c, 1.0);
}
"""

    const val BLUR_VERTEX = """
#version 300 es
layout(location = 0) in vec2 aPos;
out vec2 vUv;
void main() {
    vUv = aPos * 0.5 + 0.5;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

    /** Separable gaussian blur; uDir = (1,0) or (0,1), uRadius in texels. */
    const val BLUR_FRAGMENT = """
#version 300 es
precision highp float;
in vec2 vUv;
out vec4 fragColor;
// Explicit highp on the sampler: ES 3.00 has no default sampler precision,
// so an unqualified sampler is a spec-level compile error that strict
// (Mali) drivers reject — the blur program feeds sharpening/clarity, so a
// rejected program here meant a black preview on those devices.
uniform highp sampler2D uImage;
uniform vec2 uDir;
uniform float uRadius;
void main() {
    vec3 acc = texture(uImage, vUv).rgb * 0.227027;
    vec2 off1 = uDir * uRadius * 1.3846153846;
    vec2 off2 = uDir * uRadius * 3.2307692308;
    acc += texture(uImage, vUv + off1).rgb * 0.3162162162;
    acc += texture(uImage, vUv - off1).rgb * 0.3162162162;
    acc += texture(uImage, vUv + off2).rgb * 0.0702702703;
    acc += texture(uImage, vUv - off2).rgb * 0.0702702703;
    fragColor = vec4(acc, 1.0);
}
"""
}
