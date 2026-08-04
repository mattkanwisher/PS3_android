# Patch inventory: what prior Android ports actually changed in RPCS3

Reconnaissance of the archived `RPCS3-Android/rpcs3-android` app (alpha-7, 2025),
its emulator fork `DHrpcs3/rpcs3` branch `android`, RPCSX/rpcsx-ui-android, and
aps3e — to predict CellStation's patch burden before writing code. (2026-08-04)

## Headline conclusions

1. **The thin-wrapper model is proven end-to-end.** rpcs3-android alpha-7 was
   exactly our architecture: a Kotlin app + ~3.4k lines of JNI glue over a
   submodule'd emulator, whose fork delta vs upstream was only **36 commits /
   82 files / +1756−526** — and most of that was later upstreamed.
2. **Upstream already merged the structural Android work (Feb–Mar 2025, by DH):**
   Qt-free `rpcs3_emu` under `ANDROID` gates, Android Vulkan swapchain
   (`swapchain_android.hpp`), `ANativeWindow` display handle, memfd-based VM
   allocation, bionic thread/atomic fixes, aarch64 CPU-detection fallback,
   fd-based `fs::file::from_native_handle`, Android hid enumeration.
   **Caveat:** nothing Android-specific has landed after 2025-03-06 and upstream
   has no Android CI — expect bit-rot compile fixes (17 months untested).
3. **Do not base on RPCSX's tree** for upstream-tracking: its `rpcs3/` dir is
   irreversibly restructured (Cell HLE moved to `ps3fw/`, 329 vs 704 TUs).
   Its *packaging* is worth copying though: UI APK with a ~325-line dlopen shim
   over a flat C ABI (~26 `_rpcsx_*` functions), core `.so` shipped per
   micro-architecture (armv8-a … armv9.1-a matrix).

## Expected patch burden by subsystem (thin wrapper on today's master)

| Subsystem | Burden | Notes |
|---|---|---|
| Qt decoupling / emucore build | none–small | upstream; bit-rot fixes only (e.g. our 0001) |
| Vulkan surface + swapchain | small (~75 lines) | re-port fork's surface-lost/background lifecycle fixes |
| Memory (memfd, rlimits) | none | upstream + wrapper `setrlimit64` (MEMLOCK/NOFILE/STACK/AS) at init |
| JIT / W^X / signals | small (~160 lines) | Android allows anon PROT_EXEC; only OOM-robustness patches (PPU compiler OOM, skip module verify, LLVM thread cap) |
| Audio | none | upstream Cubeb → AAudio as-is |
| Input | small (~110 lines) | `virtual_pad_handler` (2 new files) + pad enum |
| Filesystem / content-URI | small (~80 lines) | `fs::file::write_at`; ISO-from-fd mounting lives in the wrapper (~330 lines) |
| Emu control API for JNI | small (~40 lines) | `Emu.SetState/SetTitleID`, hid list |
| cfg ↔ JSON settings bridge | medium (~460 lines) | additive to `Utilities/Config`; upstreamable |
| CPU topology / big.LITTLE / atomics | medium (~300 lines) | aarch64 model list, Qualcomm core order, affinity, spin-before-futex — needed for playable perf |
| AdrenoTools custom drivers | **large (~850 lines, 48 VK files)** | `VK_NO_PROTOTYPES` dynamic dispatch is rebase-hostile. **Deferred**: stock system Vulkan loader works without it; revisit when driver bugs demand it |
| Build glue | wrapper-side, not patches | prebuilt LLVM + ffmpeg for NDK (RPCS3-Android org published llvm-android/ffmpeg-android release artifacts we can compare against) |

Total (custom drivers excluded): **~900 net lines across ~35 commits' worth** —
consistent with our patches/-with-upstreaming-plan approach.

## JNI surface to copy (rpcsx-ui-android, adapted)

`initialize(rootDir, user)`, `installFw(fd, progressId)`, `install(fd, progressId)`,
`installKey(fd, requestId, gamePath)`, `isInstallableFile(fd)`, `getDirInstallPath(sfoFd)`,
`boot(path)`, `surfaceEvent(surface, event)` (0=ready, 2=destroyed/pause),
`usbDeviceEvent(fd, vid, pid, event)`, `processCompilationQueue()`,
`startMainThreadProcessor()` (pumps `EmuCallbacks.call_from_main_thread`),
`overlayPadData(digital1, digital2, lX, lY, rX, rY)`, `collectGameInfo(rootDir, progressId)`,
`systemInfo()`, `settingsGet(path)→JSON`, `settingsSet(path, json)`, `getState()`,
`kill()`, `resume()`, `shutdown()`, `openHomeMenu()`, `getTitleId()`, `getVersion()`.

The wrapper implements upstream's `EmuCallbacks` seam (~23 callbacks): `get_gs_frame`
returns a `GSFrameBase` whose `handle()` is the `ANativeWindow*`; `get_audio` returns
the Cubeb backend; message/OSK/save dialogs; `call_from_main_thread`.

## Risks carried forward

- Per-µarch builds: the fork dropped the armv8.1-a floor; RPCSX ships 7 arm64
  variants. A single generic build risks SIGILL (or losing LSE atomics perf).
  Plan a small build matrix (armv8-a baseline + armv8.2/armv9 tuned).
- 16 KB pages: none of the prior ports handle it explicitly; NDK r28 defaults
  cover the .so alignment, but keep it on the release checklist.
- Upstream Android gates have no CI upstream — our `android-core` workflow is
  effectively the canary. Failures there are candidates to upstream as fixes.
