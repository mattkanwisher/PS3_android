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

1. `0001-cmake-embed-as-subproject.patch` — resolve rpcs3-relative CMake paths
   via `rpcs3_SOURCE_DIR`/`rpcs3_BINARY_DIR` so the core builds when embedded
   via `add_subdirectory`. Upstream-status: candidate.
2. `0002-cellmic-without-openal.patch` — fix WITHOUT_OPENAL build: guard the
   `alc.h` include (opaque ALC typedefs instead) and the `fmt::alc_error`
   formatter. Upstream-status: candidate.
3. `0003-strfmt-noreturn-dtor-clang18.patch` — clang 18+ ignores `[[noreturn]]`
   on defaulted functions; give `fmt::throw_exception`'s destructor a
   `__builtin_unreachable()` body so `-Werror=return-type` passes.
   Upstream-status: candidate.
4. `0004-vk-android-surface-fix.patch` — `make_WSI_surface` for Android used
   `VkWin32SurfaceCreateInfoKHR` and `this->m_instance` in a free function;
   never-compiled code. Upstream-status: candidate.
5. `0005-vkoverlays-include-image.patch` — `VKOverlays.h` needs the full
   `vk::image` definition (unique_ptr members + virtual dtor); libc++
   rejects the forward-decl-only include chain. Upstream-status: candidate.
