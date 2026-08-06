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
| aPS3e | 2.41 (2026-07-30 release, vendored rpcs3 core 0.0.34) |
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

## Setup and platform notes (qualitative)

- **CellStation**: firmware + game dir + driver chosen in-app; adrenotools
  custom driver support (Turnip verified working: `Turnip Adreno (TM) 740`).
- **aPS3e**: guided first-run wizard (firmware PUP → ISO directory via SAF →
  font → optional custom driver). Custom-driver support present (adrenotools,
  same mechanism). Runs emulation in a separate `:emu` process.
- Both installed the same firmware PUP without issue.

## TODO

- [x] CellStation BlazBlue in-match: **60.0 FPS** (Tutorial mode, 2026-08-06)
- [ ] aPS3e BlazBlue menu/in-match FPS rows
- [ ] Optional: aPS3e with the same Turnip driver for a driver-matched FPS row
- [ ] Distill into a README comparison section once rows are complete
