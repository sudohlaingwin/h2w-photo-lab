# H2W Photo Lab — native module, RAW program Phase 1 (slice 1: LibRaw linked).
# Approved program: briefs/pro-premium-program.md, feasibility: briefs/raw-roadmap.md.
#
# LibRaw 0.22.2 vendored at thirdparty/libraw-0.22.2/ (see VENDORED.txt there for
# version, tarball SHA256, and the LGPL-2.1/CDDL license note). Built as a SHARED
# library (libraw.so inside the APK) — dynamic linking keeps the LGPL route clean.
#
# Source list: the EXPLICIT upstream list lib_libraw_a_SOURCES from Makefile.am
# (79 files), machine-extracted — never GLOB. GLOB_RECURSE pulls in overlapping
# implementations and caused the duplicate-symbol link errors that disabled the
# LibRaw build in an earlier cycle.
#
# Defines: LIBRAW_NOTHREADS (upstream autotools default), USE_ZLIB (links NDK
# libz — required for DEFLATE-compressed DNG tiles, the Samsung Expert RAW
# common case). NOT defined: USE_JPEG (the NDK ships no libjpeg; lossy-JPEG
# DNGs fall back to the embedded preview per the roadmap), USE_RAWSPEED,
# USE_DNGSDK.
cmake_minimum_required(VERSION 3.22.1)

project("h2w-native")

# --- LibRaw 0.22.2 (vendored, explicit upstream source list) ---
add_library(
        raw
        SHARED
        thirdparty/libraw-0.22.2/src/libraw_c_api.cpp
        thirdparty/libraw-0.22.2/src/libraw_datastream.cpp
        thirdparty/libraw-0.22.2/src/decoders/canon_600.cpp
        thirdparty/libraw-0.22.2/src/decoders/crx.cpp
        thirdparty/libraw-0.22.2/src/decoders/pana8.cpp
        thirdparty/libraw-0.22.2/src/decoders/decoders_dcraw.cpp
        thirdparty/libraw-0.22.2/src/decoders/sonycc.cpp
        thirdparty/libraw-0.22.2/src/decompressors/losslessjpeg.cpp
        thirdparty/libraw-0.22.2/src/decoders/decoders_libraw_dcrdefs.cpp
        thirdparty/libraw-0.22.2/src/decoders/olympus14.cpp
        thirdparty/libraw-0.22.2/src/decoders/decoders_libraw.cpp
        thirdparty/libraw-0.22.2/src/decoders/dng.cpp
        thirdparty/libraw-0.22.2/src/decoders/fp_dng.cpp
        thirdparty/libraw-0.22.2/src/decoders/fuji_compressed.cpp
        thirdparty/libraw-0.22.2/src/decoders/generic.cpp
        thirdparty/libraw-0.22.2/src/decoders/kodak_decoders.cpp
        thirdparty/libraw-0.22.2/src/decoders/load_mfbacks.cpp
        thirdparty/libraw-0.22.2/src/decoders/smal.cpp
        thirdparty/libraw-0.22.2/src/decoders/unpack_thumb.cpp
        thirdparty/libraw-0.22.2/src/decoders/unpack.cpp
        thirdparty/libraw-0.22.2/src/demosaic/aahd_demosaic.cpp
        thirdparty/libraw-0.22.2/src/demosaic/ahd_demosaic.cpp
        thirdparty/libraw-0.22.2/src/demosaic/dcb_demosaic.cpp
        thirdparty/libraw-0.22.2/src/demosaic/dht_demosaic.cpp
        thirdparty/libraw-0.22.2/src/demosaic/misc_demosaic.cpp
        thirdparty/libraw-0.22.2/src/demosaic/xtrans_demosaic.cpp
        thirdparty/libraw-0.22.2/src/integration/dngsdk_glue.cpp
        thirdparty/libraw-0.22.2/src/integration/rawspeed_glue.cpp
        thirdparty/libraw-0.22.2/src/metadata/adobepano.cpp
        thirdparty/libraw-0.22.2/src/metadata/canon.cpp
        thirdparty/libraw-0.22.2/src/metadata/ciff.cpp
        thirdparty/libraw-0.22.2/src/metadata/cr3_parser.cpp
        thirdparty/libraw-0.22.2/src/metadata/epson.cpp
        thirdparty/libraw-0.22.2/src/metadata/exif_gps.cpp
        thirdparty/libraw-0.22.2/src/metadata/fuji.cpp
        thirdparty/libraw-0.22.2/src/metadata/identify_tools.cpp
        thirdparty/libraw-0.22.2/src/metadata/identify.cpp
        thirdparty/libraw-0.22.2/src/metadata/kodak.cpp
        thirdparty/libraw-0.22.2/src/metadata/leica.cpp
        thirdparty/libraw-0.22.2/src/metadata/makernotes.cpp
        thirdparty/libraw-0.22.2/src/metadata/mediumformat.cpp
        thirdparty/libraw-0.22.2/src/metadata/minolta.cpp
        thirdparty/libraw-0.22.2/src/metadata/misc_parsers.cpp
        thirdparty/libraw-0.22.2/src/metadata/nikon.cpp
        thirdparty/libraw-0.22.2/src/metadata/normalize_model.cpp
        thirdparty/libraw-0.22.2/src/metadata/olympus.cpp
        thirdparty/libraw-0.22.2/src/metadata/hasselblad_model.cpp
        thirdparty/libraw-0.22.2/src/metadata/p1.cpp
        thirdparty/libraw-0.22.2/src/metadata/pentax.cpp
        thirdparty/libraw-0.22.2/src/metadata/samsung.cpp
        thirdparty/libraw-0.22.2/src/metadata/sony.cpp
        thirdparty/libraw-0.22.2/src/metadata/tiff.cpp
        thirdparty/libraw-0.22.2/src/postprocessing/aspect_ratio.cpp
        thirdparty/libraw-0.22.2/src/postprocessing/dcraw_process.cpp
        thirdparty/libraw-0.22.2/src/postprocessing/mem_image.cpp
        thirdparty/libraw-0.22.2/src/postprocessing/postprocessing_aux.cpp
        thirdparty/libraw-0.22.2/src/postprocessing/postprocessing_utils_dcrdefs.cpp
        thirdparty/libraw-0.22.2/src/postprocessing/postprocessing_utils.cpp
        thirdparty/libraw-0.22.2/src/preprocessing/ext_preprocess.cpp
        thirdparty/libraw-0.22.2/src/preprocessing/raw2image.cpp
        thirdparty/libraw-0.22.2/src/preprocessing/subtract_black.cpp
        thirdparty/libraw-0.22.2/src/tables/cameralist.cpp
        thirdparty/libraw-0.22.2/src/tables/colorconst.cpp
        thirdparty/libraw-0.22.2/src/tables/colordata.cpp
        thirdparty/libraw-0.22.2/src/tables/wblists.cpp
        thirdparty/libraw-0.22.2/src/utils/curves.cpp
        thirdparty/libraw-0.22.2/src/utils/decoder_info.cpp
        thirdparty/libraw-0.22.2/src/utils/init_close_utils.cpp
        thirdparty/libraw-0.22.2/src/utils/open.cpp
        thirdparty/libraw-0.22.2/src/utils/phaseone_processing.cpp
        thirdparty/libraw-0.22.2/src/utils/read_utils.cpp
        thirdparty/libraw-0.22.2/src/utils/thumb_utils.cpp
        thirdparty/libraw-0.22.2/src/utils/utils_dcraw.cpp
        thirdparty/libraw-0.22.2/src/utils/utils_libraw.cpp
        thirdparty/libraw-0.22.2/src/write/apply_profile.cpp
        thirdparty/libraw-0.22.2/src/write/file_write.cpp
        thirdparty/libraw-0.22.2/src/write/tiff_writer.cpp
        thirdparty/libraw-0.22.2/src/x3f/x3f_parse_process.cpp
        thirdparty/libraw-0.22.2/src/x3f/x3f_utils_patched.cpp
)
target_include_directories(
        raw
        PRIVATE
        thirdparty/libraw-0.22.2
        thirdparty/libraw-0.22.2/internal
        thirdparty/libraw-0.22.2/src)
target_compile_definitions(
        raw
        PRIVATE
        LIBRAW_NOTHREADS
        USE_ZLIB)
# -w like upstream's autotools default (lib_libraw_a_CPPFLAGS = -DLIBRAW_NOTHREADS -w)
target_compile_options(raw PRIVATE -w)
find_library(z-lib z)
target_link_libraries(raw ${z-lib})

# --- JNI bridge (Phase 0 smoke bridge + Phase 1 LibRaw version probe) ---
# Proves the NDK toolchain works: externalNativeBuild + CMake produce a
# working .so that loads on-device. The bridgeInfo() round-trip is the
# native-layer health check (SelfTest check 52); librawVersion() returns the
# real linked LibRaw version (SelfTest check 54).
add_library(
        native-lib
        SHARED
        native-lib.cpp)

target_include_directories(
        native-lib
        PRIVATE
        thirdparty/libraw-0.22.2)

find_library(log-lib log)

target_link_libraries(
        native-lib
        raw
        ${log-lib})

# Bake the target ABI into the bridge string so the on-device self-test can
# prove the .so was compiled for this device's architecture (arm64-v8a for
# the vivo V60). ANDROID_ABI is set by the NDK toolchain file.
target_compile_definitions(native-lib PRIVATE H2W_ABI="${ANDROID_ABI}")
