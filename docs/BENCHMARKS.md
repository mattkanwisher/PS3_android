# Benchmarks: CellStation vs aPS3e

Raw numbers collected for a future README comparison section. All measurements
on the same device, same game files, back to back on the same day.

> Commercial titles are the tester's own dumps and are referred to generically
> where required. Nothing here ships with either emulator.

## Test setup

| | |
|---|---|
| Device | AYN Thor — Snapdragon 8 Gen 2, Adreno 740, 12 GB RAM, Android 13 |
| Date | 2026-08-06 |
| CellStation | master (rpcs3 core 0.0.42, submodule 652cf60 + patches 0001–0014), LLVM 22 |
| aPS3e | 2.41 for the 2026-08-06 rows; 3.0.321 (beta)-pro for the 2026-08-07 Skate rows |
| Drivers | System = stock Qualcomm blob; Turnip = Mesa Turnip 26.0.0-rc8 via adrenotools |
| Method | Same ISO files, cold first boot (no caches), FPS read from each emulator's own overlay at matching scenes |

## First-boot compile time (cold cache → playable)

The single biggest experience gap. Both emulators must compile the game's
PPU/SPU code on first boot; CellStation inherits upstream's LLVM 22 and the
2026 SPU-compiler work (GBB via SDOT/UDOT, Cell dependency-chain improvements),
aPS3e's 0.0.34 base predates them.

| Game | CellStation | aPS3e 2.41 |
|---|---|---|
| BlazBlue: Continuum Shift | **~2 min** (boots to save-data prompt) | **~4 min** to first rendered frame (its own progress UI estimated 10 min for the PPU phase; actual total was better) |
| Racing title (2008 open-world, disc) | **~1–2 min** | **aborted after ~15 min wall / 86 CPU-minutes**, never reached title screen (progress display frozen at "module 1148 of 1148", process at ~715% CPU) |

## Frame rate

| Scene | CellStation (stock) | CellStation (Turnip) | aPS3e 2.41 (stock) |
|---|---|---|---|
| BlazBlue: intro cinematics | — | **59.9–60.1** | **59.1–60.0** |
| BlazBlue: main menu | — | **60.0** (15–20% CPU total) | TBD |
| BlazBlue: in match (Tutorial, both characters active) | — | **60.0** (17% CPU total) | TBD |
| Racing title: title/city scene | 11.9–13.4 | **14.1–15.2** | — (never reached) |

Both emulators hit the 60 FPS cap on this title's intros — on light 2D content
the differentiator is boot/compile experience, not frame rate. CellStation FPS
read from its built-in perf overlay; aPS3e has no visible FPS overlay, so its
figure was measured externally from SurfaceFlinger frame timestamps
(`dumpsys SurfaceFlinger --latency`, 127-frame windows), which reports
presented frames and is if anything the more authoritative method.

## GPU memory (same racing title, title scene, kgsl total)

Adreno GPU memory is invisible to Android's RSS accounting, so it is the
resource that actually kills emulator processes on this device.

| | CellStation (stock) | CellStation (Turnip) |
|---|---|---|
| GPU memory at steady state | ~3.1 GB | **~1.1 GB** |
| Free device RAM while running | ~3.5 GB | **~6.1 GB** |

(CellStation's stock-driver figure is *after* patch 0014's memory-management
fixes; before them the run climbed ~300 MB/s and was OOM-killed at ~75 s.)

## Skate (2026-08-07, aPS3e 3.0.321)

The most directly comparable run so far: same disc, same device, both cold
(caches deleted), settings matched where they matter. Both emulators split the
game's PPU code into **the same 162 modules**, so they are doing equivalent work.

| | CellStation | aPS3e 3.0.321 |
|---|---|---|
| PPU modules | 162 | 162 |
| Cold PPU compile | **503 s** (2 compile threads) | **201 s** (4 compile threads) |
| First cold run | completed the compile | **SIGSEGV** right after compiling, back to the game list |
| Second run | — | completed |
| Furthest reached | **in-game, Downtown** (needs Debug Console Mode) | **in-game name entry** |
| Frame rate | **20.0 fps** (in-game, open world) | **30.0 fps** (menu — different scene) |
| HDD install size | 2.5 GB | 2.5 GB |

aPS3e gets further on this title today. Two things are worth separating out
from that, because they cut in different directions:

- **A first-boot crash on a heavy title is not specific to CellStation.** aPS3e
  segfaulted on its own first cold run of this game and needed a relaunch,
  which is the same "relaunch and it picks up where it left off" behaviour
  CellStation shows. Both inherit it from the shared core's caching design.
- **Skate needs `SPU XFloat Accuracy: Accurate` on CellStation** (shipped as a
  per-game config). With the default `Approximate` its self-contained SPU video
  decoder (`vp6_spu`) produces a green frozen frame and its job manager spins
  five SPU threads forever. aPS3e runs this title on `Approximate` without that
  artifact, so this is a divergence in our build, not a core-wide trait — worth
  chasing separately. It also means the FPS rows above are not a like-for-like
  SPU comparison.

CellStation's blocker on this title turned out to be a guest-side out-of-memory,
not an emulator fault. The game printed its own diagnostic immediately before
dying:

    sys_tty_write(): "GetLargestFreeBlock = 2552"
    PPU (load_thread) [0x008bc288]: Access violation reading location 0xfffffffc

Skate's allocator could not satisfy a request (largest free block 2552 bytes),
returned NULL, and the game dereferenced it — reading 4 bytes below null gives
exactly 0xfffffffc. The access violation was the symptom; the emulated console
simply ran out of RAM after the 2.5 GB HDD install.

Setting **Debug Console Mode** for this game (per-game config — 320 MB of devkit
RAM instead of the retail 256 MB) clears it, and Skate reaches actual gameplay:
Downtown San Vanelona, HUD and minimap live, **20.0 fps** measured from
SurfaceFlinger. One known artifact remains — untextured ground geometry,
alongside `RSX: Format incompatibility detected ... (VK_FORMAT=0x25,
GCM_FORMAT=0x95)` in the log.

Note the two FPS figures in the table above are **not** comparable: aPS3e's 30 fps
was its name-entry menu, CellStation's 20 fps is open-world gameplay with the
city streaming. A like-for-like row needs aPS3e at the same location.

### Skate: the untextured ground

Two per-game graphics settings were A/B'd against the artifact (a large flat
untextured surface where the road should be):

| Setting | Result |
|---|---|
| `Write Color Buffers: true` | **crashes the game** (SIGSEGV) |
| `Strict Rendering Mode: true` | **fixes it** — full cobblestone texture, grout lines, cast shadows |

**The artifact is Adreno/Turnip-specific, not a core bug.** Verified against
desktop rpcs3 on a Windows box (Ryzen 9 9950X, RTX 5070, native Vulkan,
v0.0.42-19679 — the same core version line), running the *same* disc at *stock
defaults*: `Approximate` xfloat, `Strict Rendering Mode: false`, no per-game
config. The desktop renders the ground correctly — full paving texture, visible
slab seams, correct materials — where the Thor at those same settings renders a
flat untextured surface.

So `Strict Rendering Mode` is a workaround for something in the Adreno/Turnip
path rather than a setting this game inherently needs, which makes it worth
narrowing down and reporting upstream instead of just documenting. Suspect the
`RSX: Format incompatibility detected ... (VK_FORMAT=0x25, GCM_FORMAT=0x95)`
seen in the Android log.

The desktop also reached gameplay **without** `Debug Console Mode`, where the
Thor needed it to survive a guest-side out-of-memory. That is suggestive but not
conclusive — it has not been confirmed at the same San Vanelona load point where
the Thor ran out.

Confirmed across three camera angles and lighting conditions, 30.0 fps locked
(median dt 33.33 ms, p90 33.34 ms), no crash. Left enabled in Skate's per-game
config, alongside `SPU XFloat Accuracy: Accurate` and `Debug Console Mode: true`.
All three are needed for this title.

## Dead or Alive 5 Ultimate (2026-08-07, aPS3e 3.0.321)

DOA5 has an attract mode that auto-advances into a CPU-vs-CPU match with no
input. That made a stable title screen impossible to hold in either emulator but
gave a genuinely matched in-game scene: both reached the **Forest stage**
unprompted. CellStation ran on global settings (no per-game config, 2 compile
threads); aPS3e on its Default profile.

### In-game, Forest stage — matched scene

| | Median | **Average** | p90 frame time | RSS in-game |
|---|---|---|---|---|
| CellStation | 30.0 fps | **27.8 – 29.5** | **50.0 ms** | **2.02 GB** (flat) |
| aPS3e 3.0.321 | 30.0 fps | 24.8 – 26.3 | 66.7 – 83.3 ms | 3.35 GB (still climbing) |

CellStation is ~10–15% higher average frame rate with a materially tighter
frame-time tail, and holds ~40% less memory. On a later run in the DWA
wrestling-ring stage it held 30.0–32.2 avg through heavy alpha and lighting work.

**Rendering quality also favours CellStation here:** on the identical Forest
stage aPS3e shows green/magenta block corruption across the rock face and
foliage, and heavy dithering on the main-menu background. CellStation renders
both cleanly.

Note that ~30 fps is not "fine" — DOA5 targets 60, and rpcs3 ties emulated time
to that target, so the match genuinely plays at roughly two-thirds speed rather
than merely looking less smooth. The presented figure is also vsync-quantised:
the panel is pinned to 60 Hz, so an internal ~40 fps lands on screen as 30.

### Cold boot and crashes

| | aPS3e cold | aPS3e warm | CellStation cold | CellStation warm |
|---|---|---|---|---|
| PPU modules | 159 new | 0 (cached) | 159 new | 0 (cached) |
| Outcome | **crash at ~module 150** | reached in-game | **crash at ~module 100** | reached in-game |
| First game frame | never | t+34 s | never | t+51 s |
| In-game fight | never | t+130 s | never | t+115 s |
| Peak RSS | 6595 MB | 3484 MB | 5941 MB | 2008 MB |

**Both emulators fail the cold compile the same way**, which is the most
important result here:

    aPS3e:       Scudo ERROR: internal map failure (NO MEMORY) requesting 8KB
    CellStation: Scudo ERROR: internal map failure (NO MEMORY) requesting 4KB

Both SIGABRT in a PPU compile worker. RSS sits at 1–2 GB through the compile then
spikes to ~6 GB in the final half-second. Since aPS3e carries none of this port's
patches, this is a core-plus-Android-allocator problem rather than something
introduced here. Our 2-thread default buys a longer runway (crash at module 100
vs aPS3e's 150 at 4 threads) but hits the same wall; the cache survives, so a
relaunch resumes.

### UX gap: no boot progress

aPS3e shows "Compiling PPU Modules… module N of 159 (Xm remaining)" throughout.
CellStation shows a **black screen with only the touch overlay** for the entire
41 s warm boot and 5+ minute cold compile, despite having a live progress-dialog
thread. This is the single biggest usability gap found, and it is why a slow boot
is indistinguishable from a hang for the user.

### Diagnostic note

The heuristic "frozen emulation = every thread at 0% CPU" is **not reliable**.
Both emulators were observed hanging with threads pegged (SPUs at 100%, RSX at
94%, zero frames presented for 2.5+ minutes). Confirm liveness from the
SurfaceFlinger latency buffer going stale plus identical screenshot hashes, not
from CPU activity.

### Reference: desktop rpcs3, same disc

| | CellStation (Thor) | rpcs3 (Windows, RTX 5070) |
|---|---|---|
| Frame rate | 20.0 fps (in-game, Strict Rendering on) | **65.94 fps** |
| Ground rendering at stock defaults | untextured | **correct** |
| Cold PPU compile | 503 s (2 threads) | seconds (32 threads, znver5) |
| Needs Debug Console Mode | yes | no (at the point reached) |

Useful incidental finding: desktop rpcs3 with a fresh `dev_hdd0` stops at Skate's
"will install game data to the HDD" prompt and waits for a button press. From the
log alone that is indistinguishable from a hang — RSX goes quiet after two shader
programs while PPU threads spin in `sys_timer_usleep`. An earlier macOS run was
misread as a possible reproduction of the Android hang on that basis; it was just
this prompt.

### Compile threads: faster is not better here

Same disc, same cold cache, back to back — raising `Max LLVM Compile Threads`
from the shipped default of 2 to aPS3e's 4:

| Threads | Modules compiled | Time | Outcome |
|---|---|---|---|
| 4 | 102 of 162 | 203 s | `Scudo OOM` on every size class → `SIGABRT` in a compile worker |
| 2 | **162 of 162** | 503 s | completed cleanly |

4 threads is roughly 1.6x faster per module and never finishes: more concurrent
LLVM workers means more simultaneous heap, and Android's scudo allocator caps
each malloc size class at 256 MB (65536 chunks of 4112 bytes, exactly) — a
limit desktop allocators do not have. **The default stays at 2.**

This also corrects an earlier assumption in this file's history: the crashes
are *not* address-space exhaustion. The 2-thread run reached `VmSize` 204 GB
without dying. The JIT address-space leak inflates `VmSize` but the scudo
size-class ceiling is what actually kills the process, and it is the thing to
fix if heavy titles are ever to compile in a single pass.

## Setup and platform notes (qualitative)

- **CellStation**: firmware + game dir + driver chosen in-app; adrenotools
  custom driver support (Turnip verified working: `Turnip Adreno (TM) 740`).
- **aPS3e**: guided first-run wizard (firmware PUP → ISO directory via SAF →
  font → optional custom driver). Custom-driver support present (adrenotools,
  same mechanism). Runs emulation in a separate `:emu` process.
- Both installed the same firmware PUP without issue.

## TODO

- [x] CellStation BlazBlue in-match: **60.0 FPS** (Tutorial mode, 2026-08-06)
- [x] Skate: module counts, cold-compile times, thread-count A/B (2026-08-07)
- [x] CellStation Skate FPS: **20.0 fps** in-game (2026-08-07)
- [ ] aPS3e Skate in-game FPS at the same location, for a comparable row
- [x] Skate untextured ground: fixed by `Strict Rendering Mode` (2026-08-07)
- [ ] Boot progress UI for long cold compiles (biggest UX gap vs aPS3e)
- [x] Desktop rendering reference (Windows/RTX): ground artifact confirmed
      Adreno/Turnip-specific (2026-08-07)
- [ ] Narrow the Turnip ground artifact for an upstream report
- [ ] Desktop **arm64** control (macOS) for the SPU xfloat question — still open;
      set `visibility=Windowed` in GuiConfigs first or the render window lands on
      its own Space and cannot be observed
- [x] Dead or Alive 5 Ultimate rows (2026-08-07)
- [ ] aPS3e BlazBlue menu/in-match FPS rows
- [ ] Optional: aPS3e with the same Turnip driver for a driver-matched FPS row
- [ ] Distill into a README comparison section once rows are complete

## Method notes

- aPS3e has no FPS overlay, so its frame rate is measured externally from
  SurfaceFlinger frame timestamps: `dumpsys SurfaceFlinger --latency
  'SurfaceView[aenu.aps3e/aenu.aps3e.EmulatorActivity](BLAST)#<id>'`, median
  inter-frame delta over a ~126-frame window. That reports actually-presented
  frames, so it is if anything the stricter measurement.
- Games are registered with aPS3e in `game_list.json` (SAF content URI, name,
  serial, category, version, resolution, sound_format, base64 ICON0). Adding a
  disc that lives under an already-granted tree needs no re-picking.
- Compile time is measured to the end of the PPU worker threads, not to first
  frame, so install and SPU compilation are excluded from those rows.
