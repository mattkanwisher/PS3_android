# Chrysalis (working title) — a PS3 emulator for Android handhelds

> **Based on [RPCS3](https://github.com/RPCS3/rpcs3)** — the PlayStation 3 emulator by the RPCS3 team, used under the GPL-2.0 license. This is an **unofficial, independent port**. It is **not affiliated with, endorsed by, or supported by the RPCS3 team** — please do not report issues from this project to them.

An Android port of the RPCS3 emulation core for Snapdragon-powered gaming handhelds (AYN Odin 3, Odin 2, Thor class), with a Kotlin/Jetpack Compose UI, a minimal JNI bridge, and first-class launch-intent integration for frontends like [Cocoon](https://github.com/inssekt/CocoonFE) and Daijishō.

**Status: planning / pre-alpha.** See [PLAN.md](PLAN.md) for the full porting plan, architecture, and milestones.

## Design principles

- **Upstream-first.** RPCS3 is consumed as an unmodified git submodule pinned to upstream master. Any unavoidable change lives as a reviewed patch in `patches/` with an upstreaming plan. We ride upstream's arm64 progress instead of forking away from it.
- **Thin wrappers.** Kotlin UI ↔ small JNI bridge ↔ untouched C++ core. No emulation logic outside the core.
- **Frontend-friendly.** A documented, stable Android intent contract so any launcher can boot games directly.
- **Honest.** Public compatibility expectations, full source for every release, no monetization.

## Attribution & license

This project is licensed under **GPL-2.0** ([LICENSE](LICENSE)), inherited from RPCS3.

- PS3 emulation: © the [RPCS3 team and contributors](https://github.com/RPCS3/rpcs3) (GPL-2.0). This project exists because of their work, including their December 2024 arm64 support effort.
- Android port code (JNI bridge, Kotlin app, build system): © contributors to this repository (GPL-2.0).
- Each release documents the exact upstream commit and patch set used (see `docs/ATTRIBUTION.md`, forthcoming).

"PlayStation" and "PS3" are trademarks of Sony Interactive Entertainment. This project is not affiliated with Sony. It does not include any game content, firmware, or copyrighted system software; users must supply their own legally-obtained firmware and games.
