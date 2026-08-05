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
#   2. an RSX log line                        -> the emu actually entered the
#                                                game environment
#   3. no fatal crash of the app process
set -euo pipefail

APK="$1"
ELF="$2"
PKG="nu.hyperworks.cellstation"
PAYLOAD="/data/local/tmp/boot-test.elf"
TIMEOUT_S=300

adb wait-for-device

echo "== device ABIs: $(adb shell getprop ro.product.cpu.abilist)"
echo "== installing $APK"
adb install -r "$APK"

echo "== pushing payload"
# /data/local/tmp is world-traversable; 644 makes the file readable by the app.
adb push "$ELF" "$PAYLOAD"
adb shell chmod 644 "$PAYLOAD"

adb logcat -c || true

echo "== launching via EMULATE intent"
adb shell am start -n "$PKG/.EmulationActivity" \
    -a nu.hyperworks.cellstation.EMULATE \
    -e bootPath "$PAYLOAD"

sleep 3
if ! adb shell pidof "$PKG" > /dev/null; then
    echo "FAIL: app process did not start"
    adb logcat -d > logcat.txt || true
    exit 1
fi

boot_ok=0
rsx_ok=0
verdict=1
deadline=$((SECONDS + TIMEOUT_S))

while [ "$SECONDS" -lt "$deadline" ]; do
    adb logcat -d > logcat.txt 2>/dev/null || true

    grep -q "Boot OK: $PAYLOAD" logcat.txt && boot_ok=1 || true
    grep -qE '\bRSX(\[|:| )' logcat.txt && rsx_ok=1 || true

    if grep -q "Boot failed for '$PAYLOAD'" logcat.txt; then
        echo "FAIL: core rejected the payload"
        break
    fi
    if grep -E "FATAL EXCEPTION|Fatal signal" logcat.txt | grep -q "$PKG"; then
        echo "FAIL: app process crashed"
        break
    fi

    if [ "$boot_ok" = 1 ] && [ "$rsx_ok" = 1 ]; then
        echo "PASS: payload booted (Boot OK + RSX active)"
        verdict=0
        break
    fi
    echo "== waiting (boot_ok=$boot_ok rsx_ok=$rsx_ok, $((deadline - SECONDS))s left)"
    sleep 5
done

adb logcat -d > logcat.txt 2>/dev/null || true
echo "== last relevant logcat lines:"
grep -E "CellStation|CELLSTATION|RPCS3|cellstation" logcat.txt | tail -60 || true

if [ "$verdict" != 0 ] && [ "$boot_ok$rsx_ok" != 11 ]; then
    echo "RESULT: FAIL (boot_ok=$boot_ok rsx_ok=$rsx_ok)"
fi
exit "$verdict"
