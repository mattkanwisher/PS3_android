#!/usr/bin/env sh
# Local (macOS/Linux) mirror of .github/workflows/android-core.yml: build the
# unmodified upstream rpcs3_emu core for android-arm64 with the NDK.
#
# Prereqs: cmake >= 3.28, ninja, an NDK (brew install --cask android-ndk),
# and initialized submodules:
#   git submodule update --init --depth 1 rpcs3
#   cd rpcs3 && for m in $(git submodule status | awk '{print $2}' | grep -vE 'llvm|ffmpeg|MoltenVK'); do
#     git submodule update --init --depth 1 "$m"; done
#   sh ci/apply-patches.sh
#
# Overridable env:
#   ANDROID_NDK_HOME  NDK root        (default: /opt/homebrew/share/android-ndk)
#   FFMPEG_PREFIX     ffmpeg install  (default: $HOME/ffmpeg-android)
#   BUILD_DIR         cmake build dir (default: build-android)
#   FFMPEG_VER        ffmpeg tag      (default: n6.1.2, keep in sync with CI)
set -eu
cd "$(dirname "$0")/.."

NDK="${ANDROID_NDK_HOME:-/opt/homebrew/share/android-ndk}"
FFMPEG_PREFIX="${FFMPEG_PREFIX:-$HOME/ffmpeg-android}"
BUILD_DIR="${BUILD_DIR:-build-android}"
FFMPEG_VER="${FFMPEG_VER:-n6.1.2}"

case "$(uname -s)" in
    Darwin) HOST_TAG=darwin-x86_64; JOBS=$(sysctl -n hw.ncpu) ;;  # universal binaries, correct on arm64 too
    *)      HOST_TAG=linux-x86_64;  JOBS=$(nproc) ;;
esac
TC="$NDK/toolchains/llvm/prebuilt/$HOST_TAG"
[ -x "$TC/bin/clang" ] || { echo "error: NDK clang not found at $TC/bin/clang (set ANDROID_NDK_HOME)" >&2; exit 1; }

# ---- ffmpeg (android-aarch64, static) — skipped when already installed ------
if [ ! -f "$FFMPEG_PREFIX/lib/libavcodec.a" ]; then
    echo "==> Building ffmpeg $FFMPEG_VER for android-aarch64 -> $FFMPEG_PREFIX"
    ffsrc="${TMPDIR:-/tmp}/chrysalis-ffmpeg-src"
    rm -rf "$ffsrc"
    git clone --depth 1 --branch "$FFMPEG_VER" https://github.com/FFmpeg/FFmpeg.git "$ffsrc"
    cd "$ffsrc"
    ./configure \
        --prefix="$FFMPEG_PREFIX" \
        --enable-cross-compile --target-os=android --arch=aarch64 --cpu=armv8-a \
        --cc="$TC/bin/aarch64-linux-android29-clang" \
        --cxx="$TC/bin/aarch64-linux-android29-clang++" \
        --ar="$TC/bin/llvm-ar" --ranlib="$TC/bin/llvm-ranlib" \
        --nm="$TC/bin/llvm-nm" --strip="$TC/bin/llvm-strip" \
        --enable-static --disable-shared --enable-pic \
        --disable-programs --disable-doc --disable-avdevice \
        --disable-avfilter --disable-postproc --disable-network \
        --disable-vulkan
    make -j"$JOBS"
    make install
    cd - >/dev/null
    rm -rf "$ffsrc"
else
    echo "==> ffmpeg already installed at $FFMPEG_PREFIX (delete it to rebuild)"
fi

# ---- configure + build rpcs3_emu --------------------------------------------
echo "==> Configuring ($BUILD_DIR)"
"$TC/bin/clang" --version | head -1
cmake -S native -B "$BUILD_DIR" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-29 \
    -DANDROID_FFMPEG_ROOT="$FFMPEG_PREFIX" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_C_COMPILER_LAUNCHER=ccache \
    -DCMAKE_CXX_COMPILER_LAUNCHER=ccache \
    -DWITH_LLVM=OFF

echo "==> Building rpcs3_emu with $JOBS jobs"
cmake --build "$BUILD_DIR" --target rpcs3_emu -j"$JOBS"
echo "==> Done."
