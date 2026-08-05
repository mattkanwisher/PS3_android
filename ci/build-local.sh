#!/usr/bin/env sh
# Local (macOS/Linux) mirror of .github/workflows/android-core.yml: build
# libcellstation.so for android-arm64 with the NDK, then (optionally) the APK.
#
# Prereqs: cmake >= 3.28, ninja, an NDK (brew install --cask android-ndk),
# the LLVM android-aarch64 prebuilt (see LLVM_PREBUILT below), and
# initialized submodules:
#   git submodule update --init --depth 1 rpcs3
#   cd rpcs3 && for m in $(git submodule status | awk '{print $2}' | grep -vE 'llvm|ffmpeg|MoltenVK'); do
#     git submodule update --init --depth 1 "$m"; done
#   sh ci/apply-patches.sh
#
# The LLVM prebuilt is the llvm-android workflow's artifact, e.g.:
#   gh run download <run-id> -n <llvm-artifact-name> -D /tmp/llvm-dl
#   tar -C ~ -xzf /tmp/llvm-dl/llvm-android-aarch64.tar.gz   # -> ~/llvm-android
#
# Overridable env:
#   ANDROID_NDK_HOME  NDK root        (default: /opt/homebrew/share/android-ndk)
#   FFMPEG_PREFIX     ffmpeg install  (default: $HOME/ffmpeg-android)
#   LLVM_PREBUILT     LLVM prefix     (default: $HOME/llvm-android)
#   BUILD_DIR         cmake build dir (default: build-android)
#   FFMPEG_VER        ffmpeg tag      (default: n7.1.1, keep in sync with CI;
#                     rpcs3 needs the 7.1 AVCodecConfig API)
set -eu
cd "$(dirname "$0")/.."

NDK="${ANDROID_NDK_HOME:-/opt/homebrew/share/android-ndk}"
FFMPEG_PREFIX="${FFMPEG_PREFIX:-$HOME/ffmpeg-android}"
LLVM_PREBUILT="${LLVM_PREBUILT:-$HOME/llvm-android}"
BUILD_DIR="${BUILD_DIR:-build-android}"
FFMPEG_VER="${FFMPEG_VER:-n7.1.1}"

case "$(uname -s)" in
    Darwin) HOST_TAG=darwin-x86_64; JOBS=$(sysctl -n hw.ncpu) ;;  # universal binaries, correct on arm64 too
    *)      HOST_TAG=linux-x86_64;  JOBS=$(nproc) ;;
esac
TC="$NDK/toolchains/llvm/prebuilt/$HOST_TAG"
[ -x "$TC/bin/clang" ] || { echo "error: NDK clang not found at $TC/bin/clang (set ANDROID_NDK_HOME)" >&2; exit 1; }
[ -f "$LLVM_PREBUILT/lib/cmake/llvm/LLVMConfig.cmake" ] || { echo "error: LLVM prebuilt not found at $LLVM_PREBUILT (see header comment)" >&2; exit 1; }

# ---- ffmpeg (android-aarch64, static) — skipped when already installed ------
if [ ! -f "$FFMPEG_PREFIX/lib/libavcodec.a" ]; then
    echo "==> Building ffmpeg $FFMPEG_VER for android-aarch64 -> $FFMPEG_PREFIX"
    ffsrc="${TMPDIR:-/tmp}/cellstation-ffmpeg-src"
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

# ---- configure + build libcellstation.so ------------------------------------
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
    -DWITH_LLVM=ON \
    -DBUILD_LLVM=OFF \
    -DSTATIC_LINK_LLVM=ON \
    -DLLVM_DIR="$LLVM_PREBUILT/lib/cmake/llvm" \
    -DCMAKE_FIND_ROOT_PATH="$LLVM_PREBUILT"
# Unlike CI we keep CMAKE_FIND_ROOT_PATH_MODE_PACKAGE at its hermetic default
# (ONLY) and add the LLVM prefix to the find root instead: with BOTH, hosts
# with Homebrew abseil leak /opt/homebrew dylibs into the Android link.

echo "==> Building libcellstation.so with $JOBS jobs"
cmake --build "$BUILD_DIR" --target cellstation -j"$JOBS"

echo "==> Staging stripped lib into app/src/main/jniLibs (mirrors CI)"
mkdir -p app/src/main/jniLibs/arm64-v8a
"$TC/bin/llvm-strip" --strip-unneeded "$BUILD_DIR/bridge/libcellstation.so" -o app/src/main/jniLibs/arm64-v8a/libcellstation.so
ls -lh app/src/main/jniLibs/arm64-v8a/libcellstation.so
echo "==> Done. Build the APK with: (cd app && gradle assembleDebug)"
