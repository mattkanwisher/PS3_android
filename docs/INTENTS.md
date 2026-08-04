# Launch-intent contract (draft)

The public Android intent API for launching games from frontends (Cocoon, Daijishō,
ES-DE, …). This contract becomes **stable at the first tagged release**; until then it
is a draft. Placeholders `<package>` / `<name>` resolve once the project name is final.

## Boot a game

Exported activity: `<package>/.EmulationActivity` (launchMode `singleTask`).

Accepted forms:

1. **Custom action + extras** (primary, Daijishō/Cocoon `amStartArguments` style):
   ```
   am start -n <package>/.EmulationActivity \
            -a nu.hyperworks.<name>.EMULATE \
            -e bootPath <content-or-file URI>      # disc image (ISO)
            [-e gameDir <path>]                    # folder-format games (JB rips)
            --activity-clear-task --activity-clear-top
   ```
2. **`ACTION_VIEW` + data URI**: `-a android.intent.action.VIEW -d <content URI>`.

Behavior: boots directly into the game (no library UI), honors per-game settings,
back/exit returns to the caller. `content://` URIs are read via a granted read
permission (`--grant-read-uri-permission`).

## Cocoon / Daijishō player entry (to be PR'd once the APK exists)

```json
{
  "name": "<Name>",
  "uniqueId": "ps3.<package>",
  "acceptedFilenameRegex": "^(.*)\\.(?:iso)$",
  "amStartArguments": "-n <package>/.EmulationActivity\n -a nu.hyperworks.<name>.EMULATE\n -e bootPath {file.uri}\n --activity-clear-task\n --activity-clear-top",
  "killPackageProcesses": true
}
```

Cocoon: PR to `inssekt/CocoonFE` `platforms/SonyPlayStation3.json` + `revisionNumber`
bump in `platforms/index.json` (the app pulls platform data from `main` at runtime).
Folder-format games use Cocoon's `{tags.ps3folder}` tag-file mechanism → maps to `gameDir`.
