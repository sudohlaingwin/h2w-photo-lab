package com.lumora.photoeditor.engine

/**
 * Shooting metadata parsed from the [NativeLib.rawExif] wire line
 * (RAW program Phase 2, slice 6).
 *
 * Numerics use -1.0 as "unknown" and [epochSeconds] uses 0 for unknown —
 * the UI never renders those; a field the camera didn't report simply
 * doesn't appear, exactly like stripped EXIF. Never throws.
 */
data class RawExifInfo(
    val make: String,
    val model: String,
    val iso: Double,
    val shutterSeconds: Double,
    val aperture: Double,
    val focalMm: Double,
    val epochSeconds: Long,
)

/**
 * Parses one native `rawExif()` result line:
 * `OK\t<make>\t<model>\t<iso>\t<shutter>\t<aperture>\t<focal>\t<timestamp>`
 *
 * Returns null for anything that isn't a well-formed OK line ("ERR ...",
 * null/empty, wrong field count). Garbage numerics degrade to the
 * "unknown" sentinels rather than failing the parse — one bad field must
 * not hide the good ones. Pure function, pinned by SelfTest check 60.
 */
fun parseRawExif(raw: String?): RawExifInfo? {
    if (raw.isNullOrEmpty() || !raw.startsWith("OK\t")) return null
    val f = raw.split('\t')
    if (f.size != 8) return null
    fun num(i: Int): Double =
        f[i].trim().toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: -1.0
    val ts = f[7].trim().toLongOrNull()?.takeIf { it > 0 } ?: 0L
    return RawExifInfo(
        make = f[1],
        model = f[2],
        iso = num(3),
        shutterSeconds = num(4),
        aperture = num(5),
        focalMm = num(6),
        epochSeconds = ts,
    )
}
