package com.lumora.photoeditor.engine

import kotlinx.serialization.Serializable

/**
 * Normalized 0..1 crop rectangle in the source image; full image by default.
 *
 * Lives in its own file (moved from EditParams.kt on 2026-10-19) so the pure
 * crop-geometry math (see GeometryAspect.applyAspect) can be covered by the
 * standalone JVM test harness without pulling in EditParams' other
 * dependencies. Same package, same declaration — behavior-preserving move.
 */
@Serializable
data class CropParams(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f,
    /** Target aspect or null for free. */
    val aspect: Float? = null,
    val locked: Boolean = false,
)
