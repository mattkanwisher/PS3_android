# Attribution

This project is based on **[RPCS3](https://github.com/RPCS3/rpcs3)**, the PlayStation 3
emulator by the RPCS3 team and contributors, used under the GPL-2.0 license. It is an
unofficial, independent port — not affiliated with, endorsed by, or supported by the
RPCS3 team.

## Upstream pin

| | |
|---|---|
| Repository | https://github.com/RPCS3/rpcs3 |
| Branch tracked | `master` |
| Pinned commit | `652cf60bfee5482a8287efc19b65d94f5e68c5c0` (2026-08-04) |
| Notable at this pin | Bundled LLVM 22.1; arm64 PPU/SPU LLVM recompilers; RawSPU arm64 MMIO handling |
| Local patches | 11 — CMake subproject embedding; cellMic WITHOUT_OPENAL; fmt noreturn-dtor (clang 18+); Android Vulkan surface fix; VKOverlays include; JITLLVM pre-21 LLVM compat; arm64 -march baseline override; SQRDMLAH QRDMX guard; Adreno VK driver vendor; SIGILL-safe CNTFRQ read; unsized-UBO storage-buffer fallback. All upstream candidates (see [`patches/series`](../patches/series)) |

Update this table on every submodule bump. Every tagged release must state the exact
pin + patch series it was built from.

## Components

- **PS3 emulation core** (`rpcs3/` submodule): © RPCS3 team and contributors, GPL-2.0.
  Copyright headers in upstream files are preserved verbatim.
- **Android port** (`native/`, `app/`, `patches/`, build system): © contributors to this
  repository, GPL-2.0.
- **Third-party libraries**: bundled via upstream's `3rdparty/` submodules under their
  respective licenses (LLVM, ffmpeg, curl, wolfssl, zlib, zstd, cubeb, et al.). An
  exhaustive per-dependency license inventory will be added before the first binary release.
- **libadrenotools** (`native/3rdparty/libadrenotools` submodule, with its
  `linkernsbypass` submodule): © Billy Laws, BSD-2-Clause. Provides rootless custom
  GPU driver loading (e.g. Mesa Turnip) on Adreno devices. Driver packages themselves
  are not bundled; users install them from Mesa builds (MIT).

## Etiquette

- Do **not** report issues from this project to the RPCS3 team or their bug tracker.
- Patches useful to upstream are submitted through RPCS3's normal PR process on their
  own merits, per `patches/README.md`.

"PlayStation" and "PS3" are trademarks of Sony Interactive Entertainment. This project
is not affiliated with Sony and ships no firmware, BIOS, or game content.
