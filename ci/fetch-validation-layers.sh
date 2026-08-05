#!/usr/bin/env sh
# Fetch the Khronos Vulkan validation layer for android-arm64 into the app's
# jniLibs so debug builds can enable it.
#
#   sh ci/fetch-validation-layers.sh
#   (then rebuild the APK; the layer is ~90 MB, debug builds only)
#
# The layer is NOT committed — jniLibs/ is gitignored. Enable it at runtime by
# setting "Debug output: true" in the on-device config.yml; rpcs3 then requests
# VK_LAYER_KHRONOS_validation at instance creation and routes messages to its
# own log (visible via `adb logcat -s RPCS3`).
#
# Overridable env:
#   VVL_VERSION   validation layer release (default: 1.4.357.0)
#   ABI           target ABI              (default: arm64-v8a)
set -eu
cd "$(dirname "$0")/.."

VVL_VERSION="${VVL_VERSION:-1.4.357.0}"
ABI="${ABI:-arm64-v8a}"
DEST="app/src/main/jniLibs/$ABI"
LAYER_SO="libVkLayer_khronos_validation.so"

if [ -f "$DEST/$LAYER_SO" ]; then
    echo "==> $DEST/$LAYER_SO already present (delete it to re-fetch)"
    exit 0
fi

url="https://github.com/KhronosGroup/Vulkan-ValidationLayers/releases/download/vulkan-sdk-$VVL_VERSION/android-binaries-$VVL_VERSION.zip"
tmp="${TMPDIR:-/tmp}/vvl-$VVL_VERSION"
rm -rf "$tmp"; mkdir -p "$tmp"

echo "==> Downloading validation layers $VVL_VERSION"
curl -sSL -o "$tmp/vvl.zip" "$url"

echo "==> Extracting $ABI/$LAYER_SO"
unzip -q -o "$tmp/vvl.zip" -d "$tmp/x"
src=$(find "$tmp/x" -path "*$ABI/$LAYER_SO" | head -1)
[ -n "$src" ] || { echo "error: $LAYER_SO not found for $ABI in the release archive" >&2; exit 1; }

mkdir -p "$DEST"
cp "$src" "$DEST/$LAYER_SO"
rm -rf "$tmp"

ls -lh "$DEST/$LAYER_SO"
echo "==> Done. Rebuild the APK, then on the device:"
echo "    adb shell run-as nu.hyperworks.cellstation sed -i 's/Debug output: false/Debug output: true/' \\"
echo "        /data/data/nu.hyperworks.cellstation/files/config/config.yml"
