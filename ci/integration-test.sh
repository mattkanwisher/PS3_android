#!/usr/bin/env bash
# Boot-test CellStation on a running Android emulator (adb must be connected).
#
#   ci/integration-test.sh <apk> <homebrew.elf>
#
# Payload policy: homebrew ONLY — the RPCS3 team's own GPL test ELFs from the
# rpcs3 submodule (rpcs3/bin/test/*.elf). Never commercial content.
#
# Pass criteria:
#   1. "CELLSTATION: ... Boot OK:" in logcat  -> Emu.BootGame returned no_errors
#   2. an "RSX:" log line                     -> the emu actually entered the
#                                                game environment (renderer
#                                                thread up = guest boot ran)
#   3. no fatal crash of the app process
set -uxo pipefail

APK="$1"
ELF="$2"
PKG="nu.hyperworks.cellstation"
PAYLOAD="/data/local/tmp/boot-test.elf"
TIMEOUT_S=300

adb wait-for-device
adb install -r "$APK"

# /data/local/tmp is world-traversable; 644 makes the file readable by the app.
adb push "$ELF" "$PAYLOAD"
adb shell chmod 644 "$PAYLOAD"

adb logcat -c || true

adb shell am start -n "$PKG/.EmulationActivity" \
    -a nu.hyperworks.cellstation.EMULATE \
    -e bootPath "$PAYLOAD"

boot_ok=0
rsx_ok=0
deadline=$((SECONDS + TIMEOUT_S))

while [ "$SECONDS" -lt "$deadline" ]; do
    log="$(adb logcat -d 2>/dev/null || true)"

    if printf '%s' "$log" | grep -q "Boot OK: $PAYLOAD"; then boot_ok=1; fi
    if printf '%s' "$log" | grep -qE '\bRSX(\[|:)'; then rsx_ok=1; fi

    if printf '%s' "$log" | grep -qE "Boot failed for '$PAYLOAD'"; then
        echo "FAIL: core rejected the payload"
        break
    fi
    if printf '%s' "$log" | grep -E "FATAL EXCEPTION|Fatal signal" | grep -q "$PKG"; then
        echo "FAIL: app process crashed"
        break
    fi

    if [ "$boot_ok" = 1 ] && [ "$rsx_ok" = 1 ]; then
        echo "PASS: payload booted (Boot OK + RSX thread up)"
        break
    fi
    sleep 5
done

adb logcat -d > logcat.txt || true
tail -100 logcat.txt || true

[ "$boot_ok" = 1 ] && [ "$rsx_ok" = 1 ]
