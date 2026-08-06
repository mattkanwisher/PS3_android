# Unrecognised GPU vendors are opted into an NVIDIA-only transfer hack

Draft for an upstream RPCS3 report. Everything below was reproduced on hardware;
where something is inference rather than observation it says so.

## Summary

`vk::get_chip_family()` only recognises NVIDIA, AMD, Apple and Intel vendor IDs.
Every other GPU — Qualcomm Adreno, ARM Mali, Imagination — resolves to
`chip_class::unknown`, which is the **first** enumerator and therefore compares
less than every real chip class.

`VKTexture.cpp` uses an ordering comparison against that enum to select a
transfer path that is only meant for pre-Turing NVIDIA parts:

```cpp
const bool use_unsafe_transport = !g_cfg.video.strict_rendering_mode &&
    (gpu_family != chip_class::NV_generic && gpu_family < chip_class::NV_turing);
```

`chip_class::unknown` is `0` and `chip_class::NV_turing` is `13`, so `unknown <
NV_turing` is true and every unrecognised GPU silently takes the hack. On Adreno
the result is visibly wrong geometry: in *skate.* (BLES00125) the paved ground
renders as a flat untextured surface.

## Environment

| | |
|---|---|
| Affected | AYN Thor — Snapdragon 8 Gen 2, Adreno 740, Android 13 |
| Driver | Mesa Turnip 26.0.0 via adrenotools (also reproduces on the stock Qualcomm blob) |
| Working reference | Ryzen 9 9950X + RTX 5070, Windows, native Vulkan |
| Core | 0.0.42 on both sides (Android build from 652cf60; Windows v0.0.42-19679) |
| Game | skate. (BLES00125), disc image, SHA-256 identical on both machines |

The Android side is a downstream Android port, but this code path carries no
downstream changes — the finding is about upstream code as written.

## Reproduction

1. Boot *skate.* on an Adreno device with default video settings
   (`Strict Rendering Mode: false`).
2. Reach gameplay in Downtown San Vanelona.
3. The ground is a flat untextured surface. Buildings, sky, character and HUD are
   all correct — only the ground is wrong.

Setting `Strict Rendering Mode: true` fixes it. That is the clue that led here:
on this code path that setting's *only* effect is forcing `use_unsafe_transport`
to false.

## Root cause

`vk::get_chip_family(vendor_id, device_id)` in `vkutils/chip_class.cpp`:

```cpp
if (vendor_id == 0x10DE) return s_NV_family_tree.find(device_id);   // NVIDIA
if (vendor_id == 0x1002) return s_AMD_family_tree.find(device_id);  // AMD
if (vendor_id == 0x106B) return ...;                                // Apple
if (vendor_id == 0x8086) return s_INTEL_family_tree.find(device_id);// Intel
return chip_class::unknown;                                         // everyone else
```

Qualcomm is `0x5143`, so Adreno returns `unknown`. In `chip_class`, `unknown` is
declared first, so it is `0` — below every vendor's classes, including
`NV_turing`. The guard in `VKTexture.cpp` reads as "pre-Turing NVIDIA" but
actually means "anything that sorts below NV_turing", which includes all
unrecognised hardware.

The comment directly above the fallback path (`// Ampere GPUs don't like the
direct transfer hack above`) makes the intent clear: the hack is an NVIDIA
workaround, and newer NVIDIA parts were excluded when they broke. Non-NVIDIA
vendors were never considered.

## Evidence that this is the mechanism

Same disc, same core version, same settings (`Approximate` xfloat, Vulkan,
`Strict Rendering Mode: false`, no per-game config):

| | Ground |
|---|---|
| Adreno 740 / Turnip — resolves to `unknown`, takes the hack | **wrong** |
| RTX 5070 — resolves to `NV_blackwell` (> `NV_turing`), takes the safe path | correct |
| Adreno 740 with `Strict Rendering Mode: true` — forced onto the safe path | correct |
| Adreno 740 with the patch below, strict rendering still off | **correct** |

The last row is the confirming one: the artifact disappears with no setting
change, only the vendor check corrected.

### One thing that looks related and is not

The Android log carries:

```
RSX: Format incompatibility detected, reporting failure to force data copy
     (VK_FORMAT=0x25, GCM_FORMAT=0x95)
```

`0x95` is `CELL_GCM_TEXTURE_Y16_X16`, which `render_target_format_is_compatible()`
has no case for, so it falls to `default:` and forces a data copy. This looks like
an obvious culprit but is **not** the bug: the Windows machine logs the identical
warning while rendering correctly. Both platforms take that path; only the
transport differs. Noting it so nobody re-investigates
`render_target_format_is_compatible`.

## Proposed fix

Minimal, and deliberately does not change behaviour for any currently recognised
vendor:

```diff
-const bool use_unsafe_transport = !g_cfg.video.strict_rendering_mode && (gpu_family != chip_class::NV_generic && gpu_family < chip_class::NV_turing);
+const bool use_unsafe_transport = !g_cfg.video.strict_rendering_mode &&
+    gpu_family != chip_class::unknown &&
+    (gpu_family != chip_class::NV_generic && gpu_family < chip_class::NV_turing);
```

AMD classes still sort below `NV_turing` and keep the hack exactly as today; only
hardware the family table cannot identify moves to the safe path.

Two alternatives, if maintainers prefer:

- **Restrict positively to NVIDIA** — `vk::is_NVIDIA(gpu_family) && gpu_family <
  chip_class::NV_turing`. Truest to the comment's intent, but it also takes the
  hack away from AMD, which is a behaviour change on hardware we have not tested.
- **Fix the root** — give Adreno (and Mali) real `chip_class` entries and a
  Qualcomm vendor-ID branch. Better long term, but a larger change, and any
  ordering comparison against `unknown` stays a trap for the next vendor.

A more general hardening would be to make `unknown` sort *above* every real
class, so an unrecognised GPU never accidentally satisfies a `<` comparison. That
is riskier — it would silently flip every other ordering test in the codebase —
so it is mentioned rather than recommended.

## Impact

Any Vulkan-capable GPU whose vendor is not one of the four recognised IDs. That
is every Android device (Adreno, Mali, PowerVR), plus desktop drivers that report
an unlisted vendor. The visible symptom depends on what the game does with typeless
transfers; *skate.* is simply an easy repro.

## Attachments to include when filing

- Screenshot: Adreno, defaults, ground untextured
- Screenshot: RTX 5070, same defaults, ground correct
- Screenshot: Adreno with the patch, strict rendering still off, ground correct
- RPCS3.log from both machines
