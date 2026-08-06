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
- [ ] Skate: untextured ground artifact (RSX format incompatibility)
- [ ] Dead or Alive 5 Ultimate rows (entry prepared for aPS3e's game list;
      CellStation reaches its title screen)
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
