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
