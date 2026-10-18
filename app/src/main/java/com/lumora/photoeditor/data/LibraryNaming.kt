package com.lumora.photoeditor.data

/**
 * Naming for Lightroom-style virtual copies.
 *
 * A virtual copy is a second library entry pointing at the same source photo
 * with its own independent edit sidecar. The copy's display name is derived
 * from the original by inserting " copy" (or " copy N") before the file
 * extension, so copies of copies keep incrementing instead of stacking:
 * "IMG_1.jpg" -> "IMG_1 copy.jpg" -> "IMG_1 copy 2.jpg".
 *
 * Pure Kotlin, no Android dependencies — covered by a standalone harness.
 */
fun nextCopyName(displayName: String): String {
    // Case 1: the name already ends with " copy" / " copy N" (no extension
    // after it, e.g. "IMG.jpg copy"): peel the suffix off the full name.
    val full = Regex("""^(.*) copy(?: (\d+))?$""").matchEntire(displayName)
    if (full != null) {
        val stem = full.groupValues[1].ifEmpty { displayName }
        val n = full.groupValues[2].toIntOrNull()
        // "X copy" -> "X copy 2"; "X copy N" -> "X copy N+1".
        return insertCopySuffix(stem, if (n == null) 2 else n + 1)
    }
    // Case 2: split off the extension first, then look for the suffix on the
    // base — so "IMG_1234 copy.jpg" increments instead of stacking.
    val dot = displayName.lastIndexOf('.')
    val base = if (dot > 0) displayName.substring(0, dot) else displayName
    val ext = if (dot > 0) displayName.substring(dot) else ""
    val m = Regex("""^(.*) copy(?: (\d+))?$""").matchEntire(base)
    if (m == null) return "$base copy$ext"
    val stem = m.groupValues[1].ifEmpty { base }
    val n = m.groupValues[2].toIntOrNull()
    return "$stem copy ${if (n == null) 2 else n + 1}$ext"
}

/** Inserts " copy N" before the extension of [stem]. */
private fun insertCopySuffix(stem: String, n: Int): String {
    val dot = stem.lastIndexOf('.')
    return if (dot > 0) {
        "${stem.substring(0, dot)} copy $n${stem.substring(dot)}"
    } else {
        "$stem copy $n"
    }
}
