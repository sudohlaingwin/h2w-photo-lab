# Third-party components — H2W Photo Lab

## LibRaw (RAW/DNG decoding engine)

- **Version:** 0.22.2 (Release)
- **Upstream:** https://www.libraw.org
- **Exact source used:** https://www.libraw.org/data/LibRaw-0.22.2.tar.gz
- **SHA256:** `de86b035655accff8d4010f1a221fdf50d353cb7b1422ba26f14a0db92612cfa`
- **License:** dual-licensed **GNU LGPL-2.1** and **CDDL-1.0**
  (see `LICENSE.LGPL` and `LICENSE.CDDL` inside the tarball).
- **How it is built:** compiled from the unmodified upstream sources by the
  Android NDK (r27c) into a **shared** library (`libraw.so`, arm64-v8a),
  via `app/src/main/cpp/CMakeLists.txt` (`FetchContent` of the pinned tarball
  above). Build flags: `USE_ZLIB` (links the NDK's libz); no libjpeg, no
  RawSpeed, no DNG SDK, no OpenMP. No upstream patches applied.
- **Linking:** the app links `libraw.so` dynamically (`native-lib` JNI bridge
  links against it). Only the JNI bridge calls into LibRaw; the bridge and
  the rest of the app are not derived from LibRaw.

### Source offer (LGPL-2.1 §6 / CDDL §3)

The complete corresponding source of the LibRaw version above — the exact
tarball plus the build script (`app/src/main/cpp/CMakeLists.txt`) needed to
reproduce `libraw.so` — is available on request. Contact the developer through
the in-app developer profile (H2W Photo Lab → developer popup) or reply to
the distribution channel this APK came from, and the source will be provided.

Written offers remain valid for at least three years from each distribution.
