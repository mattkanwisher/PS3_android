# Where the cycles actually go — DOA5, AYN Thor

`simpleperf` profile taken during a Dead or Alive 5 Ultimate fight, to settle where time
is spent before optimising anything else. 44,358 samples over 10 s, 0 lost.

## How it was captured

Android blocks `perf_event_open` for both hardware and software events at
`perf_event_paranoid = 1`, and `setprop security.perf_harden 0` does not help (it was
already 0). The route that works for a debuggable app is simpleperf's in-app mode, which
re-executes itself as the app user:

```
adb shell "simpleperf record --app nu.hyperworks.cellstation -g -f 1000 --duration 10 \
  -o /data/local/tmp/perf.data"
```

The installed library is stripped, so symbols come from the local unstripped build:

```
python3 $NDK/simpleperf/binary_cache_builder.py -i perf.data -lib <repo>/build-android
$NDK/simpleperf/bin/darwin/x86_64/simpleperf report -i perf.data --symfs binary_cache
```

## Result

| Shared object | Share |
|---|---|
| unknown — JIT-compiled guest code, anonymous mappings with no symbols | **58.3%** |
| `libcellstation.so` — the emulator's own C++ | 27.1% |
| `[kernel.kallsyms]` | 11.6% |
| `libc.so` | 1.9% |
| **Turnip Vulkan driver** | **0.6%** |

Inside `libcellstation.so`:

| Symbol | Share of emulator time |
|---|---|
| `vm::writer_lock::writer_lock` — summed across 6 SPU threads (5.5–7.6% each) | **~41%** |
| `spu_thread::process_mfc_cmd` — across 5 SPU threads | ~12.5% |
| `rsx::FIFO::FIFO_control::fetch_u32` (RSX thread) | 8.0% |
| `rsx::thread::run_FIFO` | 1.6% |
| `spu_thread::do_dma_transfer` | 1.1% |
| `vm::passive_lock` | 1.0% |

## What this rules out

- **Not GPU-driver bound on the CPU side.** Turnip accounts for 0.6%. Driver-level
  optimisation has almost nothing to win here.
- **Not SPU NEON throughput.** The affinity experiment (patch 0017) moved SPU threads from
  16–52% little-core residency to 0% and frame rate did not improve. This profile explains
  why: the SPU threads' emulator-side time is dominated by lock waiting, not arithmetic, so
  giving them faster cores lets them spin faster.

## What it points at

`vm::writer_lock` **spins** — `busy_wait(5000)` inside its retry loop
(`rpcs3/Emu/Memory/vm.cpp`). Six SPU threads contending on guest memory reservations, each
burning ~6–8% of emulator time in that wait, is the single largest identified cost after
guest code execution itself. It is also the worst possible shape for a phone: spinning
holds clocks high and produces heat without doing work, which feeds straight back into
thermal throttling.

The reachable path is `process_mfc_cmd` → `do_dma_transfer` → `writer_lock`, i.e. SPU DMA.
Note `Accurate SPU DMA` is already `false`; this is the normal path, not the accurate one.

## Next experiments, in order of expected value

1. **Upstream PR #18913 (WFE-based reservation wait).** Directly replaces spinning with a
   wait primitive. The profile makes this the highest-value thing to try, and it should help
   thermals as well as throughput. Worth checking why it was closed before adopting.
2. Look at whether the reservation lock granularity can be reduced so six SPU threads
   contend less often, rather than making the wait cheaper.
3. `rsx::FIFO::fetch_u32` at 8% of emulator time on the RSX thread is worth a second look —
   that is a lot for command-buffer fetch.

Deliberately **not** on this list: more thread-scheduling work, and anything driver-side.
The profile says neither is where the time goes.


## Experiments run against this profile — both negative

### Thread affinity (patch 0017, kept)

Pinning PPU/SPU/RSX off the little cores took SPU little-core residency from 16–52%
to 0% and moved frame rate not at all (24.3–28.4 avg vs a 27.8–29.5 baseline on a
matched Forest-stage fight). Kept, because the behaviour is principled and should help
thermals, but it is not a performance win. The profile is why: those threads' emulator
time is lock waiting, so faster cores let them spin faster.

### WFE on the range-lock cache line (reverted)

Upstream PR #18913 proposes replacing arm64 reservation spinning with
`utils::spin_on_cacheline_once` (LDAXR + WFE). Two findings:

**The PR itself does not apply here.** It touches the `GETLLAR` re-poll and
`RdEventStat` branches. In this profile those sites are `set_ch_value` at 0.04% and
`reservation_notifier_end_wait` at 0.03% — under 0.1% of cycles combined. Whatever its
merit for the GoW3 freeze it targets (a hang, not throughput, and one two maintainers
could not reproduce), it cannot move DOA5 on this device. Note also that it was closed
for a missing AI disclosure rather than on tested technical grounds, and that
`busy_wait` is *not* broken on ARM — it is a real timed spin against the generic timer,
so the maintainer's scepticism about the stated rationale was fair.

**Applying the same technique to the site that *is* hot did not help either.** The
ungated `busy_wait(200)` in `vm::writer_lock`'s retry loop was replaced with
`spin_on_cacheline_once` on the range-lock bits. Result: `writer_lock` went 11.07% →
11.75% of cycles (unchanged within noise) and frame rate 28.2 mean vs 27.8–29.5
baseline. The share not *dropping* is the informative part — a core parked in WFE
retires no cycles, so if WFE were engaging the share would fall. It means the LDAXR
early-out returns almost every time: the range-lock bits change so often that there is
nothing to sleep on, and removing the timed backoff just tightens the loop. Reverted.

Note the `busy_wait(5000)` earlier in the same function is gated on
`ppu_reservation_priority_over_spu`, which is off by default, so it never runs — the
ungated `busy_wait(200)` in the retry loop is the one that matters.

### What that leaves

The contention itself, not the waiting, is the cost. Six SPU threads serialising on the
same range locks is the structural problem; making the wait cheaper does not help when
the threads genuinely cannot proceed. Worth investigating next: whether the range-lock
granularity can be narrowed so the SPUs collide less often, and the 8%-of-emulator-time
`rsx::FIFO::fetch_u32`. Neither is a small change.
