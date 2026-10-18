package com.lumora.photoeditor.engine

/**
 * JNI smoke bridge — Phase 0 of the approved RAW program
 * (briefs/pro-premium-program.md, briefs/raw-roadmap.md).
 *
 * Proves the NDK toolchain (externalNativeBuild + CMake) works in this build
 * environment: the native `native-lib` .so is packaged in the APK, loads
 * on-device, and round-trips a call. Phase 1 will grow the native side into
 * the LibRaw DNG decode module; this bridge stays as the native-layer health
 * check, pinned by SelfTest check 52.
 *
 * Loading is defensive on purpose: if the .so is ever missing from a build,
 * [isAvailable] is false and [describe] reports the cause instead of throwing
 * UnsatisfiedLinkError into the caller.
 *
 * Phase 1 (slice 1) of the RAW program: the native module now also builds
 * LibRaw 0.22.2 as libraw.so (pinned upstream tarball, see CMakeLists.txt).
 * [librawVersion] returns LibRaw's REAL version string — the compile+link+load
 * proof, pinned on-device by SelfTest check 54. The DNG decode path
 * (open_buffer → unpack → dcraw_process → mem_image) is the next slice.
 *
 * Phase 1 (slice 2): the DNG decode bridge is live — [dngProbe] (cheap
 * metadata probe), [dngDecode] (full decode to a native RGBA buffer), and
 * [dngFreeBuffer] (releases that buffer; NewDirectByteBuffer takes no
 * ownership). Pinned on-device by SelfTest check 55; consumed by
 * [RawDecoder], which feeds the editor's load path.
 *
 * Phase 1 (slice 3): the lossy-JPEG DNG fallback is live — [dngPreview]
 * extracts the camera's embedded preview JPEG via LibRaw's thumbnail API
 * for DNGs whose raw tiles LibRaw cannot decode (the NDK ships no libjpeg,
 * so USE_JPEG is deliberately not linked). Pinned on-device by SelfTest
 * check 56; [RawDecoder] routes lossy-JPEG DNGs there automatically and
 * reports [RawDecoder.usedEmbeddedPreview] so the UI can say so honestly.
 */
object NativeLib {
    private var loadError: Throwable? = null

    /** True when libnative-lib.so loaded successfully. */
    val isAvailable: Boolean

    init {
        var ok = false
        try {
            System.loadLibrary("native-lib")
            ok = true
        } catch (t: Throwable) {
            loadError = t
        }
        isAvailable = ok
    }

    /** Native round-trip: returns "H2W native bridge OK (NDK r27c, ABI=...)". */
    external fun bridgeInfo(): String

    /**
     * Real LibRaw version string, e.g. "0.22.2-Release" (from libraw_version()).
     * Declared alongside bridgeInfo so a broken libraw.so link surfaces at
     * load/call time rather than silently.
     */
    external fun librawVersion(): String

    /**
     * Phase 1 slice 2: cheap DNG metadata probe (open + unpack, no pixel
     * decode). Returns "OK <width> <height>" (full sensor resolution),
     * "ERR LOSSYJPEG <reason>" when the file is a lossy-JPEG-compressed DNG
     * (LibRaw cannot decode its raw tiles — [RawDecoder] falls back to
     * [dngPreview] in that case), or "ERR <human-readable reason>". Never
     * null; the native side catches everything so this never throws out of
     * JNI.
     */
    external fun dngProbe(path: String): String

    /**
     * Phase 1 slice 3: extracts the camera's embedded preview JPEG from a
     * DNG (LibRaw thumbnail API — open + unpack_thumb, no raw decode).
     * Returns the raw JPEG bytes, or null when the file has no usable
     * embedded preview. The thumbnail IFD is independent of the raw tile
     * decoder, so this succeeds exactly where [dngDecode] refuses
     * lossy-JPEG DNGs. Decode the bytes with BitmapFactory on the Kotlin
     * side (no libjpeg needed natively — deliberate). Never throws out of
     * JNI.
     */
    external fun dngPreview(path: String): ByteArray?

    /**
     * Phase 1 slice 2: full DNG decode via LibRaw (half-size debayer, 8-bit
     * sRGB, auto white balance — phone-sane defaults documented in
     * native-lib.cpp). Returns a DirectByteBuffer laid out as
     * [int width][int height][width*height RGBA8888 bytes] in native byte
     * order, or null on any failure (call [dngProbe] for the reason).
     * The buffer is native malloc'd memory: the caller MUST release it with
     * [dngFreeBuffer] once the pixels are copied out.
     */
    external fun dngDecode(path: String): java.nio.ByteBuffer?

    /**
     * Releases a buffer returned by [dngDecode]. Safe to call with null or
     * a foreign buffer (the native side cross-checks before freeing).
     */
    external fun dngFreeBuffer(buf: java.nio.ByteBuffer?)

    /**
     * Phase 2 slice 6: shooting metadata for ANY LibRaw-readable RAW
     * (make/model/ISO/shutter/aperture/focal/capture time). Android's
     * ExifInterface cannot parse proprietary RAW containers (CR2, NEF,
     * ARW, ...), so without this the Details dialog's Camera section stays
     * empty for them. Returns the native tab-separated line documented in
     * native-lib.cpp ("OK\t<make>\t<model>\t<iso>\t<shutter>\t<aperture>\n
     * \t<focal>\t<timestamp>") or "ERR <reason>" — parse it with
     * [parseRawExif], which never lets a malformed line surface in the UI.
     * Cheap (open + unpack only, no pixel decode) but needs a real file
     * path, so callers copy the content URI to a temp file first.
     * Never throws out of JNI.
     */
    external fun rawExif(path: String): String

    /** Never throws: reports the bridge state or the failure cause. */
    fun describe(): String = try {
        if (!isAvailable) "native-lib load failed: ${loadError?.message}"
        else bridgeInfo()
    } catch (t: Throwable) {
        "bridge call failed: ${t.message}"
    }

    /** Never throws: reports the LibRaw build state or the failure cause. */
    fun describeRaw(): String = try {
        if (!isAvailable) "libraw unavailable (native-lib failed to load)"
        else "LibRaw ${librawVersion()}"
    } catch (t: Throwable) {
        "libraw call failed: ${t.message}"
    }
}
