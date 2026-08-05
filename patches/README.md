# patches/

The **only** place this project is allowed to diverge from upstream RPCS3.

The `rpcs3/` submodule is read-only and pinned to an upstream master SHA. Any change
to upstream code must be a numbered patch file here, applied to the submodule at build
time in the order listed in `series`. CI verifies the whole series applies cleanly to
the pinned SHA on every commit and every submodule bump.

## Rules

1. Prefer no patch at all: use upstream extension points first.
2. Every patch starts with a header block:

   ```
   Subject: <what it does>
   Why: <why Android needs it>
   Upstream-status: candidate | submitted (<PR link>) | rejected | local-only
   ```

3. Patches that qualify get submitted to RPCS3 through their normal PR process and
   are deleted from here once merged upstream.
4. Keep patches minimal and mechanical; emulation logic changes belong upstream, not here.

## Applying

```sh
cd rpcs3
git apply --check ../patches/*.patch   # verify
git apply ../patches/*.patch           # apply (build systems do this automatically)
```

Use `git -C rpcs3 checkout -- . && git -C rpcs3 clean -fd` to reset the submodule.

## Current series

| Patch | Why | Upstream-status |
|---|---|---|
| `0001-cmake-embedded-build-paths.patch` | Fix `CMAKE_SOURCE_DIR`/`CMAKE_BINARY_DIR` assumptions in `FindWolfSSL.cmake`, `FindZLIB.cmake`, and the `rpcs3_emu` include root that break configure when rpcs3 is embedded via `add_subdirectory()` | candidate |
| `0002-cellmic-without-openal.patch` | `cellMic.h` includes `alc.h` unconditionally and `cellMic.cpp` has an unguarded `fmt::alc_error` helper, breaking `WITHOUT_OPENAL` (Android) builds; guard both | candidate |
| `0003-aarch64-backend-llvm-gate.patch` | `AArch64ASM.cpp`/`AArch64JIT.cpp` hard-require LLVM headers but are compiled on any arm64 host even with `WITH_LLVM=OFF`; gate them on `WITH_LLVM` (their only consumers are LLVM-gated) | candidate |
| `0004-vk-android-surface-stub.patch` | `swapchain_android.hpp` uses `VkWin32SurfaceCreateInfoKHR` (copy-paste) and `this->m_instance` in a free function; never compiled on Android | candidate |
