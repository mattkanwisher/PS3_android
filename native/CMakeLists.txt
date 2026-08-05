# Android superproject embedding the unmodified upstream RPCS3 core (../rpcs3).
#
# Upstream's CMake is already Android-aware: under ANDROID it skips Qt/rpcs3qt,
# the GL renderer, OpenAL and its vendored ffmpeg prebuilts, uses ANativeWindow
# as the native display type, and builds only the `rpcs3_emu` core. The one
# contract left to the embedder is providing a `3rdparty_ffmpeg` target before
# rpcs3's 3rdparty directory aliases it (pass -DANDROID_FFMPEG_ROOT=<prefix>).
#
# Configure with the NDK toolchain, e.g.:
#   cmake -S native -B build -G Ninja \
#     -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
#     -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-29 \
#     -DANDROID_FFMPEG_ROOT=/path/to/ffmpeg-android \
#     -DCMAKE_BUILD_TYPE=Release
#   cmake --build build --target rpcs3_emu

cmake_minimum_required(VERSION 3.28)
project(cellstation_native LANGUAGES C CXX)

if(NOT ANDROID)
    message(FATAL_ERROR "This superproject is Android-only; build upstream rpcs3 directly for desktop.")
endif()

# ---- upstream options pinned for Android ------------------------------------
# arm64 requires LLVM (Emu/CPU/Backends/AArch64/* assume it): build with
# WITH_LLVM=ON STATIC_LINK_LLVM=ON LLVM_DIR=<android-aarch64 prebuilt>/lib/cmake/llvm,
# using the artifact from the llvm-android workflow.
option(WITH_LLVM "Use LLVM (requires LLVM_DIR pointing at an android-aarch64 build)" ON)

set(USE_NATIVE_INSTRUCTIONS OFF CACHE BOOL "" FORCE)
set(USE_VULKAN ON  CACHE BOOL "" FORCE)
set(USE_SDL OFF CACHE BOOL "" FORCE)         # pad handler will be native/pad, later
set(USE_FAUDIO OFF CACHE BOOL "" FORCE)      # cubeb (AAudio/OpenSL) is the audio path
set(USE_LIBEVDEV OFF CACHE BOOL "" FORCE)
set(USE_DISCORD_RPC OFF CACHE BOOL "" FORCE)
set(USE_GAMEMODE OFF CACHE BOOL "" FORCE)
set(USE_LTO OFF CACHE BOOL "" FORCE)         # keep CI turnaround sane during bring-up
set(USE_PRECOMPILED_HEADERS OFF CACHE BOOL "" FORCE)

# The NDK sysroot has none of these; always use the vendored submodules.
foreach(dep CUBEB CURL FAUDIO FFMPEG PROTOBUF GLSLANG HIDAPI LIBPNG LIBUSB
            MINIUPNPC OPENAL OPENCV PUGIXML RTMIDI SDL VULKAN_MEMORY_ALLOCATOR
            WOLFSSL ZLIB ZSTD)
    set(USE_SYSTEM_${dep} OFF CACHE BOOL "" FORCE)
endforeach()

# ---- ffmpeg (embedder-provided, see header comment) --------------------------
add_library(3rdparty_ffmpeg INTERFACE)
if(ANDROID_FFMPEG_ROOT)
    target_include_directories(3rdparty_ffmpeg SYSTEM INTERFACE "${ANDROID_FFMPEG_ROOT}/include")
    foreach(lib avformat avcodec swscale swresample avutil)
        find_library(FFMPEG_LIB_${lib} ${lib}
            PATHS "${ANDROID_FFMPEG_ROOT}/lib"
            NO_DEFAULT_PATH NO_CMAKE_FIND_ROOT_PATH)
        if(NOT FFMPEG_LIB_${lib})
            message(FATAL_ERROR "ffmpeg library '${lib}' not found under ${ANDROID_FFMPEG_ROOT}/lib")
        endif()
        target_link_libraries(3rdparty_ffmpeg INTERFACE "${FFMPEG_LIB_${lib}}")
    endforeach()
    target_link_libraries(3rdparty_ffmpeg INTERFACE z)
else()
    message(WARNING "ANDROID_FFMPEG_ROOT not set: configure will succeed but media modules will fail to compile/link.")
endif()

# ---- unmodified upstream core ------------------------------------------------
add_subdirectory("${CMAKE_CURRENT_SOURCE_DIR}/../rpcs3" "${CMAKE_BINARY_DIR}/rpcs3")

# ---- JNI bridge: libcellstation.so wrapping rpcs3_emu ------------------------
add_subdirectory(bridge)
