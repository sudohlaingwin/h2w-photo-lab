// H2W Photo Lab — JNI bridge, RAW program Phase 0 (smoke) + Phase 1 (LibRaw).
//
// Phase 0 proved the NDK toolchain (externalNativeBuild + CMake) produces a
// working native library that loads on-device. The bridgeInfo() round-trip
// is the native-layer health check (SelfTest check 52).
//
// Phase 1 slice 1: LibRaw 0.22.2 (vendored, see VENDORED.txt) is now linked
// as a shared library; librawVersion() returns the real linked version
// string (SelfTest check 54). The DNG decode JNI comes in a later slice.
#include <jni.h>
#include <string>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <cmath>
#include <exception>

#include "libraw/libraw.h"

#ifndef H2W_ABI
#define H2W_ABI "unknown"
#endif

extern "C" JNIEXPORT jstring JNICALL
Java_com_lumora_photoeditor_engine_NativeLib_bridgeInfo(
        JNIEnv* env,
        jobject /* this */) {
    std::string info = "H2W native bridge OK (NDK r27c, ABI=";
    info += H2W_ABI;
    info += ")";
    return env->NewStringUTF(info.c_str());
}

// Phase 1 slice 1: returns the real LibRaw version string of the linked
// library (e.g. "0.22.2-Release"). Never returns null.
extern "C" JNIEXPORT jstring JNICALL
Java_com_lumora_photoeditor_engine_NativeLib_librawVersion(
        JNIEnv* env,
        jobject /* this */) {
    const char* v = LibRaw::version();
    return env->NewStringUTF(v ? v : "unknown");
}

// ---------------------------------------------------------------------------
// Phase 1 slice 2: DNG decode bridge (LibRaw).
//
// Three JNI entry points, all defensive (nothing throws into the JVM):
//
//   String dngProbe(String path)
//     Cheap metadata probe: open_file + unpack only, no pixel decode.
//     Returns "OK <width> <height>" (FULL sensor resolution),
//     "ERR LOSSYJPEG <reason>" when the file is a lossy-JPEG-compressed DNG
//     (LibRaw cannot decode the raw tiles — the slice-3 embedded preview is
//     the fallback), or "ERR <human-readable reason>". Never returns null.
//
//   ByteBuffer dngDecode(String path)
//     Full decode: open -> unpack -> dcraw_process -> mem_image, with
//     phone-sane defaults (half-size debayer, 8-bit sRGB, auto white
//     balance). Returns a DirectByteBuffer laid out as
//       [int width][int height][width*height RGBA8888 bytes]
//     in native byte order, or null on any failure (re-probe for the
//     human-readable reason via dngProbe()).
//
//   void dngFreeBuffer(ByteBuffer buf)
//     Releases the malloc'd memory behind a buffer from dngDecode().
//     NewDirectByteBuffer does NOT take ownership, so without this every
//     DNG open would leak up to 128 MB. The capacity/header cross-check
//     makes freeing a foreign buffer near-impossible.
//
// Phase 1 slice 3: dngPreview() extracts the camera's EMBEDDED preview JPEG
// (unpack_thumb -> mem_thumb) for lossy-JPEG DNGs whose raw tiles LibRaw
// cannot decode (the NDK ships no libjpeg, so we deliberately do not link
// USE_JPEG). The thumbnail IFD is independent of the raw tile decoder, so
// extraction succeeds exactly where dngDecode() refuses. The Kotlin seam
// (RawDecoder) labels the result honestly: the user edits the embedded
// preview, not a fresh RAW decode.
//
// Decode settings are a deliberate slice-2 tradeoff, documented here so a
// later slice can revisit them: the app's OpenGL ES 3.0 pipeline is 8-bit,
// so 16-bit linear output would need a float pipeline that does not exist
// yet; half_size keeps a 50 MP vivo V60 RAW (~12.5 MP decoded, ~50 MB RGBA)
// inside a phone-safe transient budget. Full-res / 16-bit arrive with the
// float pipeline in a later slice. Each call uses its own stack LibRaw
// instance (LIBRAW_NOTHREADS build), so concurrent decodes are safe.
// ---------------------------------------------------------------------------
namespace {

// Decode settings shared by probe and full decode.
void h2wConfigureRaw(LibRaw& raw) {
    raw.imgdata.params.half_size = 1;    // half-resolution debayer
    raw.imgdata.params.use_auto_wb = 1;  // auto white balance
    raw.imgdata.params.output_bps = 8;   // 8-bit (matches the GL pipeline)
    raw.imgdata.params.output_color = 1; // sRGB
}

// 32 MP decoded-pixel cap -> 128 MB RGBA transient. Past that, refuse
// instead of risking an OOM kill mid-decode.
constexpr uint64_t H2W_MAX_DECODED_PX = 32ull * 1024 * 1024;

// Embedded-preview JPEG cap: 64 MiB is generous for a camera preview
// (typically 1-8 MB) and bounds the jbyteArray copy.
constexpr size_t H2W_MAX_THUMB_BYTES = 64ull * 1024 * 1024;

// Human-readable reason for an open/unpack/process failure. Lossy-JPEG-
// compressed DNGs (some Samsung Expert RAW shots) surface as
// LIBRAW_FILE_UNSUPPORTED at unpack: LibRaw genuinely cannot decode those
// raw tiles. The probe tags that case with the LOSSYJPEG prefix so the
// Kotlin seam can fall back to the embedded preview (slice 3) instead of
// showing a cryptic error string.
std::string h2wDngReason(int rc) {
    if (rc == LIBRAW_FILE_UNSUPPORTED) {
        return "lossy-JPEG compressed DNG — the raw tiles cannot be decoded; "
               "the camera's embedded preview will be used instead";
    }
    const char* s = libraw_strerror(rc);
    return s ? s : "unknown LibRaw error";
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_lumora_photoeditor_engine_NativeLib_dngProbe(
        JNIEnv* env,
        jobject /* this */,
        jstring jpath) {
    std::string out = "ERR empty path";
    const char* cpath = jpath ? env->GetStringUTFChars(jpath, nullptr) : nullptr;
    if (cpath && *cpath) {
        try {
            LibRaw raw;
            h2wConfigureRaw(raw);
            int rc = raw.open_file(cpath);
            if (rc != LIBRAW_SUCCESS) {
                std::string tag = (rc == LIBRAW_FILE_UNSUPPORTED) ? "ERR LOSSYJPEG " : "ERR ";
                out = tag + "open: " + h2wDngReason(rc);
            } else if ((rc = raw.unpack()) != LIBRAW_SUCCESS) {
                std::string tag = (rc == LIBRAW_FILE_UNSUPPORTED) ? "ERR LOSSYJPEG " : "ERR ";
                out = tag + "unpack: " + h2wDngReason(rc);
            } else {
                char buf[64];
                snprintf(buf, sizeof buf, "OK %d %d",
                         raw.imgdata.sizes.iwidth, raw.imgdata.sizes.iheight);
                out = buf;
            }
            raw.recycle();
        } catch (const std::exception& e) {
            out = std::string("ERR native exception: ") + e.what();
        } catch (...) {
            out = "ERR unknown native exception";
        }
    }
    if (cpath) env->ReleaseStringUTFChars(jpath, cpath);
    return env->NewStringUTF(out.c_str());
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_lumora_photoeditor_engine_NativeLib_dngDecode(
        JNIEnv* env,
        jobject /* this */,
        jstring jpath) {
    jobject result = nullptr;
    const char* cpath = jpath ? env->GetStringUTFChars(jpath, nullptr) : nullptr;
    if (cpath && *cpath) {
        try {
            LibRaw raw;
            h2wConfigureRaw(raw);
            int rc = raw.open_file(cpath);
            if (rc == LIBRAW_SUCCESS) rc = raw.unpack();
            if (rc == LIBRAW_SUCCESS) rc = raw.dcraw_process();
            libraw_processed_image_t* img = nullptr;
            int merr = 0;
            if (rc == LIBRAW_SUCCESS) {
                img = raw.dcraw_make_mem_image(&merr);
                if (!img || merr != LIBRAW_SUCCESS) rc = LIBRAW_UNSPECIFIED_ERROR;
            }
            bool ok = (rc == LIBRAW_SUCCESS && img &&
                       img->type == LIBRAW_IMAGE_BITMAP &&
                       img->colors == 3 && img->bits == 8 &&
                       img->width > 0 && img->height > 0 &&
                       img->data_size >=
                           (size_t)img->width * (size_t)img->height * 3u);
            if (ok) {
                const uint64_t px = (uint64_t)img->width * (uint64_t)img->height;
                if (px <= H2W_MAX_DECODED_PX) {
                    const size_t total = (size_t)(8 + px * 4);
                    uint8_t* out = (uint8_t*)malloc(total);
                    if (out) {
                        memcpy(out, &img->width, 4);
                        memcpy(out + 4, &img->height, 4);
                        const uint8_t* src = img->data;
                        uint8_t* dst = out + 8;
                        for (uint64_t i = 0; i < px; i++) {
                            dst[0] = src[0];
                            dst[1] = src[1];
                            dst[2] = src[2];
                            dst[3] = 255;
                            src += 3;
                            dst += 4;
                        }
                        result = env->NewDirectByteBuffer(out, (jlong)total);
                        if (!result) free(out); // JVM takes no ownership; free on failure
                    }
                }
            }
            if (img) LibRaw::dcraw_clear_mem(img);
            raw.recycle();
        } catch (const std::exception&) {
            result = nullptr;
        } catch (...) {
            result = nullptr;
        }
    }
    if (cpath) env->ReleaseStringUTFChars(jpath, cpath);
    return result;
}

// ---------------------------------------------------------------------------
// Phase 2 slice 6: shooting metadata (EXIF) for ANY LibRaw-readable RAW.
//
// Android's ExifInterface cannot parse proprietary RAW containers (CR2,
// NEF, ARW, RAF, ...), so the library Details dialog's Camera section
// stayed empty for them even though the file genuinely carries the
// metadata. rawExif() opens + unpacks the file (cheap: no dcraw_process,
// no pixel decode) and returns the LibRaw shooting metadata as one
// tab-separated line:
//
//   OK\t<make>\t<model>\t<iso>\t<shutter>\t<aperture>\t<focal>\t<timestamp>
//
// Numerics are %g-formatted floats (shutter in seconds, aperture as
// f-number, focal in mm) or empty when LibRaw reports nothing useful;
// timestamp is a Unix epoch (UTC) or empty. make/model are sanitized to
// printable ASCII so NewStringUTF can never fail on camera firmware
// quirks. Errors return "ERR <human-readable reason>". Never throws into
// the JVM; never returns null. The Kotlin seam (parseRawExif) validates
// the contract and never lets a malformed line surface in the UI.
// ---------------------------------------------------------------------------
namespace {

// ASCII-printable sanitizer for camera make/model: control bytes,
// DEL, tab/newline (the field separator!) and any non-ASCII byte become
// a space, then leading/trailing spaces are trimmed. Guarantees the
// string is valid modified-UTF-8 for NewStringUTF.
void h2wSanitizeMeta(char* dst, size_t cap, const char* src) {
    size_t n = 0;
    if (src) {
        for (const unsigned char* p = reinterpret_cast<const unsigned char*>(src);
             *p && n + 1 < cap; ++p) {
            dst[n++] = (*p >= 0x20 && *p < 0x7F && *p != '\t') ? (char)*p : ' ';
        }
    }
    dst[n] = '\0';
    while (n > 0 && dst[n - 1] == ' ') dst[--n] = '\0';
    size_t lead = 0;
    while (dst[lead] == ' ') ++lead;
    if (lead) memmove(dst, dst + lead, n - lead + 1);
}

// Generic (non-DNG-specific) LibRaw failure reason. h2wDngReason() above
// carries DNG-specific wording; the EXIF path serves every RAW format,
// so it gets its own neutral helper.
std::string h2wRawReason(int rc) {
    const char* s = libraw_strerror(rc);
    return s ? s : "unknown LibRaw error";
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_lumora_photoeditor_engine_NativeLib_rawExif(
        JNIEnv* env,
        jobject /* this */,
        jstring jpath) {
    std::string out = "ERR empty path";
    const char* cpath = jpath ? env->GetStringUTFChars(jpath, nullptr) : nullptr;
    if (cpath && *cpath) {
        try {
            LibRaw raw;
            int rc = raw.open_file(cpath);
            if (rc != LIBRAW_SUCCESS) {
                out = std::string("ERR open: ") + h2wRawReason(rc);
            } else if ((rc = raw.unpack()) != LIBRAW_SUCCESS) {
                out = std::string("ERR unpack: ") + h2wRawReason(rc);
            } else {
                char make[128], model[128];
                h2wSanitizeMeta(make, sizeof make, raw.imgdata.idata.make);
                h2wSanitizeMeta(model, sizeof model, raw.imgdata.idata.model);
                auto fmtNum = [](float v) -> std::string {
                    if (!(v > 0.0f) || !std::isfinite(v)) return "";
                    char b[32];
                    snprintf(b, sizeof b, "%g", (double)v);
                    return b;
                };
                char ts[32] = "";
                if (raw.imgdata.other.timestamp > 0) {
                    snprintf(ts, sizeof ts, "%lld",
                             (long long)raw.imgdata.other.timestamp);
                }
                out = std::string("OK\t") + make + "\t" + model + "\t" +
                      fmtNum(raw.imgdata.other.iso_speed) + "\t" +
                      fmtNum(raw.imgdata.other.shutter) + "\t" +
                      fmtNum(raw.imgdata.other.aperture) + "\t" +
                      fmtNum(raw.imgdata.other.focal_len) + "\t" + ts;
            }
            raw.recycle();
        } catch (const std::exception& e) {
            out = std::string("ERR native exception: ") + e.what();
        } catch (...) {
            out = "ERR unknown native exception";
        }
    }
    if (cpath) env->ReleaseStringUTFChars(jpath, cpath);
    return env->NewStringUTF(out.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_lumora_photoeditor_engine_NativeLib_dngFreeBuffer(
        JNIEnv* env,
        jobject /* this */,
        jobject jbuf) {
    if (!jbuf) return;
    void* p = env->GetDirectBufferAddress(jbuf);
    if (!p) return;
    const jlong cap = env->GetDirectBufferCapacity(jbuf);
    // The header read below touches 8 bytes: a foreign direct buffer with
    // capacity < 8 would turn this into a heap over-read. Bail first so the
    // "safe to call with a foreign buffer" contract holds for ANY buffer,
    // not just plausibly-sized ones.
    if (cap < 8) return;
    uint32_t w = 0, h = 0;
    memcpy(&w, p, 4);
    memcpy(&h, (const uint8_t*)p + 4, 4);
    const uint64_t expect = 8ull + (uint64_t)w * (uint64_t)h * 4ull;
    // Cross-check capacity against the header so a foreign direct buffer
    // can never be freed by mistake.
    if (cap > 0 && (uint64_t)cap == expect && w > 0 && h > 0 &&
        (uint64_t)w * (uint64_t)h <= H2W_MAX_DECODED_PX) {
        free(p);
    }
}

// ---------------------------------------------------------------------------
// Phase 1 slice 3: embedded-preview extraction (LibRaw thumbnail API).
//
//   byte[] dngPreview(String path)
//     Extracts the camera's embedded preview JPEG (open_file ->
//     unpack_thumb -> dcraw_make_mem_thumb) and returns its raw JPEG bytes,
//     or null when there is no usable embedded preview. Defensive: nothing
//     throws into the JVM, the LibRaw instance is always recycled, and the
//     thumbnail memory is always released.
//
// This is the fallback for lossy-JPEG DNGs (dngProbe reports
// "ERR LOSSYJPEG ..."): LibRaw cannot decode the raw tiles without libjpeg,
// which the NDK does not ship, but the preview JPEG in the DNG's thumbnail
// IFD is independent of the tile decoder, so unpack_thumb succeeds exactly
// where unpack refuses. Only LIBRAW_IMAGE_JPEG thumbnails are returned;
// bitmap thumbnails and non-DNG inputs come back null — the Kotlin seam
// handles null with a plain-language error.
//
// The JPEG bytes go through Android's BitmapFactory on the Kotlin side,
// so no libjpeg is needed on the native side (deliberate).
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_lumora_photoeditor_engine_NativeLib_dngPreview(
        JNIEnv* env,
        jobject /* this */,
        jstring jpath) {
    jbyteArray result = nullptr;
    const char* cpath = jpath ? env->GetStringUTFChars(jpath, nullptr) : nullptr;
    if (cpath && *cpath) {
        try {
            LibRaw raw;
            int rc = raw.open_file(cpath);
            if (rc == LIBRAW_SUCCESS) rc = raw.unpack_thumb();
            libraw_processed_image_t* thumb = nullptr;
            int merr = 0;
            if (rc == LIBRAW_SUCCESS) {
                thumb = raw.dcraw_make_mem_thumb(&merr);
                if (!thumb || merr != LIBRAW_SUCCESS) rc = LIBRAW_UNSPECIFIED_ERROR;
            }
            if (rc == LIBRAW_SUCCESS && thumb &&
                thumb->type == LIBRAW_IMAGE_JPEG &&
                thumb->data_size > 0 &&
                thumb->data_size <= H2W_MAX_THUMB_BYTES) {
                result = env->NewByteArray((jsize)thumb->data_size);
                if (result) {
                    env->SetByteArrayRegion(result, 0, (jsize)thumb->data_size,
                                           reinterpret_cast<const jbyte*>(thumb->data));
                    if (env->ExceptionCheck()) {
                        env->ExceptionClear();
                        result = nullptr;
                    }
                }
            }
            if (thumb) LibRaw::dcraw_clear_mem(thumb);
            raw.recycle();
        } catch (const std::exception&) {
            result = nullptr;
        } catch (...) {
            result = nullptr;
        }
    }
    if (cpath) env->ReleaseStringUTFChars(jpath, cpath);
    return result;
}
