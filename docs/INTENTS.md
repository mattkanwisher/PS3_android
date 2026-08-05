# CellStation launch-intent contract (draft)

The public Android intent API for launching games from frontends (Cocoon, Daijishō,
ES-DE, …). This contract becomes **stable at the first tagged release**; until then it
is a draft.

Package id: `nu.hyperworks.cellstation`

## Boot a game

Exported activity: `nu.hyperworks.cellstation/.EmulationActivity` (launchMode `singleTask`).

Accepted forms:

1. **Custom action + extras** (primary, Daijishō/Cocoon `amStartArguments` style):
   ```
   am start -n nu.hyperworks.cellstation/.EmulationActivity \
            -a nu.hyperworks.cellstation.EMULATE \
            -e bootPath <content-or-file URI>      # disc image (ISO)
            [-e gameDir <path>]                    # folder-format games (JB rips)
            [-e stretch true|false]                # per-launch display override
            --activity-clear-task --activity-clear-top
   ```
2. **`ACTION_VIEW` + data URI**: `-a android.intent.action.VIEW -d <content URI>`.

Behavior: boots directly into the game (no library UI), honors per-game settings,
back/exit returns to the caller. `content://` URIs are read via a granted read
permission (`--grant-read-uri-permission`).

### Per-launch overrides

| Extra     | Type                            | Default            | Effect |
| --------- | ------------------------------- | ------------------ | ------ |
| `stretch` | boolean (or `true`/`false` etc. as a string, as `am start -e` delivers it) | app setting (off) | `true` stretches the emulated output to fill the display; `false` keeps the game's aspect ratio (pillar/letterboxing) |

Overrides apply to that launch only — they are not written to `config.yml`, so the
user's own setting (Settings → Display in the app) is left untouched. Omit the extra
to use it.

## Cocoon / Daijishō player entry (to be PR'd once the APK exists)

```json
{
  "name": "CellStation",
  "uniqueId": "ps3.nu.hyperworks.cellstation",
  "acceptedFilenameRegex": "^(.*)\\.(?:iso)$",
  "amStartArguments": "-n nu.hyperworks.cellstation/.EmulationActivity\n -a nu.hyperworks.cellstation.EMULATE\n -e bootPath {file.uri}\n --activity-clear-task\n --activity-clear-top",
  "killPackageProcesses": true
}
```

Cocoon: PR to `inssekt/CocoonFE` `platforms/SonyPlayStation3.json` + `revisionNumber`
bump in `platforms/index.json` (the app pulls platform data from `main` at runtime).
Folder-format games use Cocoon's `{tags.ps3folder}` tag-file mechanism → maps to `gameDir`.
