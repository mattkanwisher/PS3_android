# Porting Plan: RPCS3-based PS3 Emulator for Android

*Working title: **Chrysalis** (placeholder — see [Naming](#naming-and-attribution)). This project is based on [RPCS3](https://github.com/RPCS3/rpcs3), the PlayStation 3 emulator by the RPCS3 team, and is not affiliated with or endorsed by them.*

Last updated: 2026-08-04

---

## 1. Goals

1. Run PS3 games on Android gaming handhelds — primary targets are the **AYN Odin 3** (Dragonwing Q8 / Snapdragon 8 Elite class, Adreno 830, Android 15) and **AYN Thor / Odin 2 / Odin 2 Portal** (Snapdragon 8 Gen 2, Adreno 740, Android 13).
2. **Reuse RPCS3 core code unmodified wherever possible**, tracking upstream `RPCS3/rpcs3` master as a git submodule so we can pull their improvements (arm64 work is moving fast upstream — LLVM 22 bump and RawSPU arm64 fixes landed in the last two weeks alone).
3. New **Kotlin UI** (Jetpack Compose), all emulation in **C++**, with a **minimal JNI wrapper** between them.
4. First-class **frontend integration**, starting with the **Cocoon** launcher (CocoonFE) and, for free, Daijishō — via a documented Android launch intent.
5. Scrupulous **GPL-2.0 compliance and loud attribution** to RPCS3, under a distinct name.

### Non-goals (for now)

- x86 Android, tablets/phones without controllers, iOS.
- Google Play distribution (see [Risks](#9-risks-and-mitigations) — the Play Store PS3-emu space is a minefield of scam apps and the RPCS3 team's stated grievance; we sideload via GitHub Releases first).
- Beating aPS3e/RPCSX on compatibility short-term. The realistic near-term ceiling on this hardware is lighter 2D/indie PS3 titles; heavy AAA titles are not full speed on any mobile chip today.

---

## 2. Landscape (research summary, Aug 2026)

**Upstream arm64 status.** RPCS3 announced official arm64 support (Linux/macOS/Windows) on 2024-12-09. PPU LLVM and SPU LLVM recompilers work on arm64; the SPU ASMJIT recompiler is x86-only (LLVM path is used instead). The VM layer was reworked for 16 KiB pages (Apple Silicon), which conveniently also satisfies Android's 16 KB page-size era. Bundled LLVM is now 22. Recent RawSPU MMIO fixes restored hundreds of games on arm64. This is why now is a good moment: the hard CPU-side porting was done upstream, for other platforms.

**The RPCS3 team and Android.** In Dec 2024 they publicly said they have *no plans* for Android/iOS — citing the harassment that killed AetherSX2 and GPL-violating scam clones on Play, not technical blockers. In 2025, RPCS3 co-founder DH (who left in 2016) built `rpcs3-android` (alpha-7, archived 2025-04-09) and merged it into his **RPCSX** monorepo; **RPCSX-UI-Android** is the community-sanctioned Android path, still alpha. Mainline RPCS3 remains desktop-only. Lesson: forks are tolerated when they are GPL-compliant and clearly not impersonating RPCS3; aPS3e was pressured by RPCS3 devs until it published source.

**aPS3e** (aenu1/aps3e) is active (v2.41, July 2026), runs on Adreno and even Mali, but vendors a modified RPCS3 inside a single Android tree with squashed history — it cannot pull upstream and nobody can cleanly diff it. It is the anti-pattern this plan is designed to avoid. Its useful signals: PS3 emulation is viable on these SoCs, Android 9+ floor, `content://` ISO launching via intent, and a Cocoon/Daijishō platform entry already exists for it.

**GPU/driver reality.** RSX's Vulkan backend initializes Vulkan 1.2 and hard-requires `runtimeDescriptorArray` + `uniformBufferStandardLayout` (+ an unsized-descriptor-array feature); BCn texture support is *optional* (CPU decompress fallback exists). Adreno 740 (8 Gen 2) has mature rootless **Turnip** (Mesa, Vulkan 1.3, native BCn) — best driver stack today. Adreno 830 (Odin 3) is on the proprietary blob or experimental Turnip A8XX builds until Mesa Gen 8 Vulkan support matures later in 2026. Conclusion: ship **AdrenoTools custom-driver loading** (like Dolphin/PPSSPP/Winlator) from day one and treat Turnip as the reference driver.

**Frontend integration.** Cocoon's app is closed-source, but its emulator database is an open PR-friendly repo of Daijishō-format platform JSONs, fetched by the app at runtime from `inssekt/CocoonFE` `main`. `platforms/SonyPlayStation3.json` already exists (players: aPS3e). Adding our emulator = one small PR with an `amStartArguments` player entry; the same JSON works in Daijishō.

---

## 3. Repository architecture

The core constraint — *pull upstream without modification* — dictates the shape. Model: **Dolphin/yuzu-style thin native bridge, Vita3K-style upstream separation.**

```
PS3_android/                      (this repo; rename with the project)
├── rpcs3/                        git submodule → github.com/RPCS3/rpcs3 (upstream master,
│                                 pinned SHA, bumped on a cadence; NEVER edited in place)
├── patches/                      the ONLY divergence from upstream: numbered .patch files
│   ├── 0001-....patch            applied to the submodule at build time (CI verifies they
│   └── series                    apply cleanly; each has an upstreaming status header)
├── native/                       our C++ code (Android-only, new, GPL-2.0)
│   ├── CMakeLists.txt            builds emucore from the submodule WITHOUT rpcs3qt/Qt
│   ├── bridge/                   JNI implementation (thin: marshalling only, no logic)
│   ├── pad/                      Android pad handler (Android input → RPCS3 Input API)
│   ├── audio/                    (only if cubeb's AAudio backend proves insufficient)
│   └── vfs/                      content:// / SAF adapter for the fs layer (phase 2)
├── app/                          Kotlin Android app (Gradle, Jetpack Compose)
│   ├── src/main/java/...         UI: library, settings, firmware install, emulation activity
│   └── src/main/cpp/  → native/  (referenced, not duplicated)
├── docs/
│   ├── ATTRIBUTION.md            what comes from RPCS3, what we changed (patch inventory)
│   ├── INTENTS.md                the public launch-intent contract for frontends
│   └── BUILDING.md
├── ci/                           GitHub Actions: LLVM prebuilt job, APK build, patch check
├── LICENSE                       GPL-2.0 (inherited from RPCS3)
├── PLAN.md                       this file
└── README.md
```

**Rules that keep upstream pulls cheap:**

- The submodule is read-only. Any change to upstream code must be a file in `patches/`, with a header stating *why* and whether it is upstreamable. CI fails if patches don't apply to the pinned SHA.
- Prefer, in order: (1) no change — use upstream's existing extension points (pad handler registry, audio backend list, `headless_application`-style embedding); (2) a patch that upstream would plausibly accept (e.g. `__ANDROID__` guards, CMake options) — submit it to RPCS3 and drop it from `patches/` when merged; (3) a permanent local patch, kept minimal.
- Submodule bumps are routine PRs: bump SHA → CI re-applies patches → compat smoke test. Target a bump every 2–4 weeks.
- Study `RPCS3-Android/rpcs3-android` (archived, GPLv2) commit history as a *map* of what needed changing for Android — it is the cheapest possible reconnaissance of where the pain is (JIT/W^X, Qt removal, surface handling). Same for RPCSX's `android/` directory. Reference, don't copy-paste without attribution notes.

**Why not fork RPCSX instead?** It already has a working no-Qt Android wrapper — but it's a monorepo that *diverged* from RPCS3 (merged with a PS4 emulator, own refactors), so "pull RPCS3 upstream" becomes their merge problem, not ours to control. We'd inherit a fork-of-a-fork. Building our thin wrapper against true upstream keeps us one hop from the source of the arm64 work. RPCSX remains: (a) prior art to study, (b) the fallback base if the Qt-decoupling patch set turns out larger than expected (decision gate at end of Phase 1).

---

## 4. Native port: what actually has to be built

### 4.1 Toolchain and dependencies

- **NDK r28+** (16 KB page alignment default), CMake, `ANDROID_PLATFORM` = android-29 (Android 10) floor to keep target devices covered (Thor/Odin 2 = 13, Odin 3 = 15); revisit Android 9 floor later if trivial.
- **LLVM 22 (bundled submodule) cross-compiled for android-aarch64** — the single biggest build task. Built once per LLVM bump in CI, cached/published as a prebuilt artifact so regular builds take minutes, not hours. (aPS3e and RPCSX both prove LLVM-on-NDK works.)
- Other submodule deps (ffmpeg fork, curl, wolfssl, zlib/zstd, SDL-input parts, yaml-cpp, pugixml, cubeb, …): most are CMake and known to build on NDK. **cubeb has an AAudio backend** — audio likely needs zero new code.
- **Qt is the one dependency we must excise.** Upstream already splits `emucore` from the Qt GUI (`rpcs3qt/`), and `--headless` exists, but headless still links QtCore. Expect a patch set: CMake option to build emucore without Qt + a small shim for the few QtCore-isms that leak in. This is the highest-uncertainty item → Phase 1 spike measures it. (RPCSX did exactly this; their history shows the seams.)

### 4.2 Memory, JIT, pages

- PPU/SPU **LLVM recompilers on arm64 work upstream**; Android allows `PROT_EXEC` JIT pages in-app (unlike iOS), and upstream's Apple-driven W^X discipline (RAII guards, recently consolidated) is stricter than Android needs — so JIT should mostly *work*; the risk is in signal handling (fastmem SIGSEGV handler vs Android's seccomp/signal quirks) and `RLIMIT_MEMLOCK` (aPS3e hit this).
- **16 KiB pages: already solved upstream** for Apple Silicon; our job is only to keep all *our* .so files 16 KB-aligned (NDK r28 default) — required for Android 15+ devices regardless of distribution channel.
- RAM: PS3 games via RPCS3 want well over 8 GB host. 12 GB devices = minimum serious target, 16–24 GB (Odin 3 Ultra) = comfortable. State this honestly in docs.

### 4.3 Graphics

- Vulkan via `VK_KHR_android_surface` — the emulation activity owns a `SurfaceView`; `ANativeWindow` crosses JNI once at boot.
- Driver matrix: stock Adreno blob (baseline), **Turnip via AdrenoTools loading** (reference/best), Turnip A8XX experimental for Odin 3. Implement libadrenotools driver loading + a driver-picker in settings early — it converts "GPU bug" reports into "try the reference driver."
- RSX's optional-feature degradation (BCn optional, descriptor-indexing thresholds) means Adreno should initialize; expect a tail of Adreno-specific rendering bugs. Add a `driver_vendor` entry for Adreno/Turnip upstream-style (good candidate for an *upstreamable* patch).

### 4.4 Input, audio, storage

- **Input**: implement an Android pad handler in `native/pad/` feeding RPCS3's pad interface (target devices have built-in controllers; touch overlay is a later phase). Small registration patch may be needed → aim to upstream as a stub-friendly hook.
- **Audio**: cubeb/AAudio first; FAudio/OpenAL fallbacks exist in-tree.
- **Storage v1**: app-private dir (`Android/data/...`) for firmware/hdd0/cache + `MANAGE_EXTERNAL_STORAGE` ("All files access") for game folders — sideloaded apps can use it, and it avoids teaching RPCS3's VFS about `content://` on day one. **Storage v2**: SAF/`content://` adapter in `native/vfs/` with directory-listing cache (PPSSPP's lesson: SAF metadata ops are 25–50× slower than POSIX).

### 4.5 JNI surface (keep it boring)

One `EmuBridge` JNI class, roughly:

```
init(configDir, cacheDir)            installFirmware(pupFd)
installPkg(fd, progressCb)           scanGames(dir) → [{path, serial, title, iconPng}]
boot(path, surface)                  pause() / resume() / stop()
saveState() / loadState(slot)        settingGet/Set(key, value)   (maps to config.yml)
onPadEvent(...)                      callbacks: log, compileProgress, fatalError
```

Parsing PARAM.SFO, PUP/PKG install, savestates — all exist in upstream (`--installfw`, `--installpkg`, `--savestate` CLI paths show the seams); the bridge only exposes them.

---

## 5. Kotlin app

- **Compose UI, controller-first** (target devices are handhelds; navigable with D-pad from day one — Cocoon users may never touch the screen).
- Screens: first-run setup (firmware PUP install, game dir), game library (SFO metadata + icons), per-game + global settings (curated subset of RPCS3 config mapped through `settingGet/Set`), emulation screen (Vulkan surface + perf overlay + quick menu: savestate, driver info, exit), log viewer/export, About screen with **prominent RPCS3 attribution + license + source link**.
- Shader/PPU compile progress surfaced properly (first boot of a game compiles for minutes — silent hangs are how emulators get 1-star "broken" reputations).
- On-screen touch controls: later milestone, behind a toggle.

---

## 6. Cocoon (and Daijishō) integration

Two sides, both small because research confirmed the mechanism:

1. **Our side — public intent contract** (documented in `docs/INTENTS.md`, stable from first release):
   - Exported `EmulationActivity` accepting `ACTION_VIEW` with a `content://` data URI **and** a custom action (e.g. `nu.hyperworks.<name>.EMULATE`) with extras `bootPath` (string URI) and optional `gameDir` for folder-format games — mirroring the DuckStation/aPS3e patterns frontends already speak.
   - `--activity-clear-task`-friendly singleTask launch mode; boots straight into the game, back button returns to the frontend.
2. **Cocoon side**: PR to `inssekt/CocoonFE` adding a player entry to `platforms/SonyPlayStation3.json` (+ `revisionNumber` bump in `index.json`), Daijishō `amStartArguments` format:
   ```
   -n <package>/<...>.EmulationActivity
   -a nu.hyperworks.<name>.EMULATE
   -e bootPath {file.uri}
   --activity-clear-task --activity-clear-top
   ```
   The app pulls platform JSON from their `main` at runtime — no Cocoon app update needed. Same JSON imports into Daijishō. Test the ISO path and the `{tags.ps3folder}` folder path (Cocoon's tag-file mechanism for folder-format PS3 games).

---

## 7. Naming and attribution

- **Do not ship under "RPCS3"** (team explicitly disowns unofficial builds; DH renamed his own port). Avoid "PS3"/"PlayStation" in the *product* name and iconography (Sony trademarks) — descriptive use in text ("a PS3 emulator") is fine. The GitHub repo should eventually be renamed to the product name (GitHub redirects old URLs).
- Working title **Chrysalis** — pairs with Cocoon, fits the metamorphosis theme, no collisions found; alternatives: *Silkmoth*, *Cellbloom*, *Imago*. Final call: Matt.
- Attribution, everywhere it counts:
  - README top: "Based on RPCS3 (© RPCS3 team, GPL-2.0) — https://github.com/RPCS3/rpcs3. Unofficial; not affiliated with or supported by the RPCS3 team."
  - In-app About screen with the same + bundled license text + link to our source and the exact upstream SHA + patch list.
  - `docs/ATTRIBUTION.md`: submodule SHA, full patch inventory, licenses of all bundled deps.
  - All upstream copyright headers preserved; our patches never remove attribution.
- **GPL-2.0 compliance**: full source public from day one (this repo), releases tag the exact submodule SHA + patch set used, no source-behind-donation games (the aPS3e mistake), APK "About" links to the source of the exact build.
- Etiquette: never file Android bugs on RPCS3's tracker; state this in our issue template. If we want a patch upstreamed, it goes through their normal PR process on its own merits.

---

## 8. Milestones

**M0 — Scaffold (this commit).** Plan, license, attribution skeleton, repo rules.

**M1 — Core builds for Android (the spike).** Submodule + patch harness; LLVM 22 NDK prebuilt job; `emucore` compiling against NDK without Qt; a headless test harness APK (no UI) that initializes the emu and boots a homebrew ELF, logcat output only. **Exit criteria / decision gate:** patch count and Qt-shim size are known → confirm "thin wrapper on upstream" vs fallback to RPCSX base. Estimate: the longest, riskiest milestone.

**M2 — Pixels and inputs.** Vulkan surface wired (`VK_KHR_android_surface`), Android pad handler, cubeb/AAudio audio; boots a commercial disc image from app-private storage on Odin 2/Thor (Turnip) — even at single-digit FPS. AdrenoTools driver loading.

**M3 — Usable app.** Compose UI: setup wizard (firmware install), library, settings subset, emulation screen with progress + quick menu. `MANAGE_EXTERNAL_STORAGE` game dirs. First tagged APK release on GitHub (arm64-v8a only).

**M4 — Frontend citizen.** Stable intent contract + `docs/INTENTS.md`; CocoonFE platform PR (+ Daijishō); folder-format games; savestates in quick menu; per-game settings.

**M5 — Fit and finish.** Perf passes on 8 Gen 2 vs Odin 3 (blob vs Turnip matrix), compatibility list (community reports template), SAF storage v2, touch overlay, submodule-bump cadence proven (≥2 bumps merged), upstream any patches that qualify.

---

## 9. Risks and mitigations

| Risk | Reality check | Mitigation |
|---|---|---|
| Qt decoupling balloons the patch set | Highest technical unknown; RPCSX proves it's possible | M1 is scoped exactly to measure this; RPCSX fallback is pre-declared |
| Performance disappoints on 8 Gen 2 | aPS3e on 8 *Elite*: 4/17 games playable | Honest compat list from day one; Odin 3-class as the "runs more" tier; ride upstream arm64 gains via cheap submodule bumps |
| Adreno rendering bugs | Upstream never targeted Adreno | Turnip as reference driver; AdrenoTools loading; upstreamable driver-vendor quirks |
| Community blowback / impersonation concerns (the reason RPCS3 won't do Android) | Real; killed AetherSX2 | Distinct name, no Play Store initially, GitHub-only releases, clear unofficial-status labeling, issue templates that keep RPCS3's tracker clean, no monetization |
| Sony trademark exposure | Name/logo risk, not code risk | No "PS3/PlayStation" in product name or icon; emulator legality itself is settled ground (Bleem precedent), ship no BIOS/firmware |
| Upstream churn breaks patches | LLVM bumps invalidate caches; code moves | Small patch count by design; CI patch-apply check on a schedule against upstream master (early warning), pinned SHA releases |
| 16 KB page devices | Odin 3 ships Android 15 | NDK r28 defaults + upstream's 16K work already done; CI check on segment alignment |

---

## 10. Immediate next steps

1. ~~Research: upstream arm64 state, aPS3e, RPCSX, Cocoon mechanism, device/driver matrix~~ ✅ (2026-08-04)
2. ~~Repo scaffold: plan, license, README with attribution~~ ✅ (this commit)
3. Add `rpcs3` submodule pinned to current master; port `.gitmodules` skeleton; `docs/ATTRIBUTION.md` + `docs/INTENTS.md` stubs.
4. CI job 1: cross-compile bundled LLVM 22 for android-aarch64, publish as a build artifact.
5. Read through `RPCS3-Android/rpcs3-android` alpha history + RPCSX `android/` dir; write up the expected patch inventory before writing any code.
6. Pick the final name; rename repo; reserve the package id.
