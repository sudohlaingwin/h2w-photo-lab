package com.lumora.photoeditor.core

/**
 * Pure migration decision for the mask-overlay opacity default change.
 *
 * On-device video review (2026-10-18) found the old 0.55 default rendered the
 * red mask overlay "far too opaque", so the default became 0.35 and existing
 * installs migrate on first read:
 * - stored < 0  → never set (the read fallback is -1f) → new default
 * - stored == 0.55f → sitting on the untouched pre-2026-10-18 default → new default
 * - anything else → the user deliberately moved the slider → keep verbatim
 *
 * The 0.55f equality is exact on purpose: the old read fallback was the
 * `0.55f` literal and the slider writes its value verbatim, so an untouched
 * install reads back bit-identical 0.55f. (A user who deliberately parked the
 * slider at exactly 55% is indistinguishable from untouched and also migrates
 * — accepted trade-off, documented here so it stays a conscious choice.)
 *
 * Zero imports on purpose: this file is compiled by the standalone kotlinc
 * harness (Gradle's unit-test workers are broken in this env).
 */
internal fun migrateMaskOverlayAlpha(stored: Float): Float =
    if (stored < 0f || stored == 0.55f) 0.35f else stored
