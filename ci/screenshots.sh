#!/usr/bin/env bash
# Capture app screenshots on a running emulator (adb connected).
#
#   ci/screenshots.sh <apk> <homebrew.elf> <outdir>
#
# Stages fictional demo game folders (ci/make-demo-games.py) so the library
# has content, then walks the app with uiautomator-located taps — no
# hardcoded coordinates. Everything is best-effort: a missed tap loses one
# shot, not the run.
set -uo pipefail

APK="$1"
ELF="$2"
OUT="$3"
PKG="nu.hyperworks.cellstation"

mkdir -p "$OUT"
adb wait-for-device

cap() {
    sleep 2
    adb shell screencap -p /sdcard/shot.png
    adb pull /sdcard/shot.png "$OUT/$1.png" > /dev/null && echo "captured $1"
}

tap_text() {
    adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
    adb pull /sdcard/ui.xml /tmp/ui.xml > /dev/null 2>&1 || return 1
    local coords
    coords=$(python3 ci/ui-bounds.py /tmp/ui.xml "$1") || { echo "no node: $1"; return 1; }
    adb shell input tap $coords
}

long_press_text() {
    adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
    adb pull /sdcard/ui.xml /tmp/ui.xml > /dev/null 2>&1 || return 1
    local coords
    coords=$(python3 ci/ui-bounds.py /tmp/ui.xml "$1") || { echo "no node: $1"; return 1; }
    adb shell input swipe $coords $coords 800
}

rotate() { # 0 = portrait, 1 = landscape
    adb shell settings put system accelerometer_rotation 0
    adb shell settings put system user_rotation "$1"
    sleep 2
}

launch() {
    adb shell am start -n "$PKG/$1" > /dev/null
    sleep 4
}

echo "== install + stage demo content"
adb install -r "$APK"
adb shell appops set "$PKG" MANAGE_EXTERNAL_STORAGE allow || true
python3 ci/make-demo-games.py /tmp/demo-games
adb shell mkdir -p /sdcard/PS3
adb push /tmp/demo-games/. /sdcard/PS3/ > /dev/null
[ -f "$ELF" ] && adb push "$ELF" /sdcard/PS3/homebrew-demo.elf > /dev/null

echo "== wizard"
rotate 0
launch .MainActivity
cap 01-wizard-portrait
rotate 1
sleep 2
cap 02-wizard-landscape

echo "== finish wizard -> library"
tap_text "Start playing" || tap_text "Skip for now" || true
sleep 3
cap 03-library-landscape

echo "== game sheet (long-press a tile)"
long_press_text "Aurora Vale" && cap 04-game-sheet-landscape
adb shell input keyevent KEYCODE_BACK
sleep 1

echo "== settings"
launch .SettingsActivity
cap 05-settings-landscape
tap_text "Controls" && cap 06-settings-controls-landscape

echo "== controller mapping"
launch .ControllerMappingActivity
cap 07-mapping-landscape

echo "== portrait pass"
rotate 0
launch .MainActivity
cap 08-library-portrait
launch .SettingsActivity
cap 09-settings-portrait

ls -la "$OUT"
# Success = we got the core shots.
[ -f "$OUT/03-library-landscape.png" ]
