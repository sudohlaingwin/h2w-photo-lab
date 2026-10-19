package com.lumora.photoeditor.engine

/**
 * Applies an aspect ratio to the crop rect, centered on it.
 *
 * Fits the largest centered rect of [aspect] inside the current crop bounds.
 * A null [aspect] (free crop) keeps the rect and just clears the aspect flag.
 *
 * Pure geometry — lives in the engine package (moved from the crop UI panel
 * on 2026-10-19) so the crop math is covered by the standalone JVM test
 * harness without pulling in Compose. Deterministic, no machine learning.
 */
fun applyAspect(crop: CropParams, aspect: Float?): CropParams {
    if (aspect == null) return crop.copy(aspect = null)
    val cw = crop.right - crop.left
    val ch = crop.bottom - crop.top
    val cx = (crop.left + crop.right) / 2f
    val cy = (crop.top + crop.bottom) / 2f
    // fit the largest centered rect of [aspect] inside current crop
    var w = cw; var h = w / aspect
    if (h > ch) { h = ch; w = h * aspect }
    return crop.copy(
        left = (cx - w / 2).coerceIn(0f, 1f),
        right = (cx + w / 2).coerceIn(0f, 1f),
        top = (cy - h / 2).coerceIn(0f, 1f),
        bottom = (cy + h / 2).coerceIn(0f, 1f),
        aspect = aspect,
    )
}
