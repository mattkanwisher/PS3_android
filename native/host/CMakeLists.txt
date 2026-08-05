# Host integration-test harness: builds the unmodified rpcs3 core for the
# BUILD machine (Linux x86_64 or aarch64) plus a small CLI boot-runner used
# for local and CI boot tests (rpcs3/bin/test homebrew only).
#
# Trick: setting the ANDROID *CMake variable* reuses the same no-Qt embedding
# path as the Android build (Qt/rpcs3qt/GL/OpenAL/vendored-ffmpeg all gated
# out by upstream's own 'if(NOT ANDROID)' checks), while the ANDROID
# *preprocessor macro* stays undefined so sources compile their regular Linux
# code paths. Input TUs that hide Qt behind '#ifndef ANDROID' get a per-file
# ANDROID define, mirroring the JNI bridge.
#
#   cmake -S native/host -B build-host -G Ninja \
#     -DCMAKE_BUILD_TYPE=Release \
#     -DHOST_FFMPEG_ROOT=$HOME/ffmpeg-host \
#     -DLLVM_DIR=/usr/lib/llvm-18/lib/cmake/llvm
#   cmake --build build-host --target cellstation-host-runner

cmake_minimum_required(VERSION 3.28)
project(cellstation_host LANGUAGES C CXX)

set(ANDROID 1)
# Companion vars some 3rdparty scripts read when ANDROID is set (zstd checks
# the API level for fseeko availability - any glibc/bionic>=24 value is fine).
set(ANDROID_PLATFORM_LEVEL 29)
set(ANDROID_NATIVE_API_LEVEL 29)

# display_handle_t must have at least one variant alternative; HAVE_X11 only
# gates forward declarations in Emu/RSX/display.h (no X11 linkage in emucore).
add_compile_definitions(HAVE_X11)

# RtMidi keys its Android backend on the raw ANDROID variable; neutralize it
# inside that subproject only (see rtmidi-host-fixup.cmake).
set(CMAKE_PROJECT_RtMidi_INCLUDE "${CMAKE_CURRENT_SOURCE_DIR}/rtmidi-host-fixup.cmake")

set(USE_NATIVE_INSTRUCTIONS OFF CACHE BOOL "" FORCE)
set(USE_VULKAN OFF CACHE BOOL "" FORCE)      # boot tests run the Null renderer
set(USE_SDL OFF CACHE BOOL "" FORCE)
set(USE_FAUDIO OFF CACHE BOOL "" FORCE)
set(USE_LIBEVDEV OFF CACHE BOOL "" FORCE)
set(USE_DISCORD_RPC OFF CACHE BOOL "" FORCE)
set(USE_GAMEMODE OFF CACHE BOOL "" FORCE)
set(USE_LTO OFF CACHE BOOL "" FORCE)
set(USE_PRECOMPILED_HEADERS OFF CACHE BOOL "" FORCE)

# System LLVM (>= 18; pass -DLLVM_DIR). BUILD_LLVM stays OFF.
option(WITH_LLVM "" ON)
set(STATIC_LINK_LLVM OFF CACHE BOOL "" FORCE)

foreach(dep CUBEB CURL FAUDIO FFMPEG PROTOBUF GLSLANG HIDAPI LIBPNG LIBUSB
            MINIUPNPC OPENAL OPENCV PUGIXML RTMIDI SDL VULKAN_MEMORY_ALLOCATOR
            WOLFSSL ZLIB ZSTD)
    set(USE_SYSTEM_${dep} OFF CACHE BOOL "" FORCE)
endforeach()

# ffmpeg >= 7.1 static host build (embedder-provided, like the Android build)
add_library(3rdparty_ffmpeg INTERFACE)
if(NOT HOST_FFMPEG_ROOT)
    set(HOST_FFMPEG_ROOT "$ENV{HOME}/ffmpeg-host")
endif()
target_include_directories(3rdparty_ffmpeg SYSTEM INTERFACE "${HOST_FFMPEG_ROOT}/include")
foreach(lib avformat avcodec swscale swresample avutil)
    find_library(HOST_FFMPEG_LIB_${lib} ${lib} PATHS "${HOST_FFMPEG_ROOT}/lib" NO_DEFAULT_PATH)
    if(NOT HOST_FFMPEG_LIB_${lib})
        message(FATAL_ERROR "ffmpeg library '${lib}' not found under ${HOST_FFMPEG_ROOT}/lib")
    endif()
    target_link_libraries(3rdparty_ffmpeg INTERFACE "${HOST_FFMPEG_LIB_${lib}}")
endforeach()
target_link_libraries(3rdparty_ffmpeg INTERFACE z)

add_subdirectory("${CMAKE_CURRENT_SOURCE_DIR}/../../rpcs3" "${CMAKE_BINARY_DIR}/rpcs3")

# ---- boot runner -------------------------------------------------------------
set(HOST_INPUT_SOURCES
    "${rpcs3_SOURCE_DIR}/rpcs3/Input/pad_thread.cpp"
    "${rpcs3_SOURCE_DIR}/rpcs3/Input/product_info.cpp"
    "${rpcs3_SOURCE_DIR}/rpcs3/Input/hid_pad_handler.cpp"
    "${rpcs3_SOURCE_DIR}/rpcs3/Input/ds3_pad_handler.cpp"
    "${rpcs3_SOURCE_DIR}/rpcs3/Input/ds4_pad_handler.cpp"
    "${rpcs3_SOURCE_DIR}/rpcs3/Input/dualsense_pad_handler.cpp"
    "${rpcs3_SOURCE_DIR}/rpcs3/Input/skateboard_pad_handler.cpp"
    "${rpcs3_SOURCE_DIR}/rpcs3/Input/ps_move_handler.cpp"
    "${rpcs3_SOURCE_DIR}/rpcs3/Input/ps_move_config.cpp"
    "${rpcs3_SOURCE_DIR}/rpcs3/Input/ps_move_calibration.cpp"
    "${rpcs3_SOURCE_DIR}/rpcs3/Input/ps_move_tracker.cpp"
)

add_executable(cellstation-host-runner
    main.cpp
    "${rpcs3_SOURCE_DIR}/rpcs3/rpcs3_version.cpp"
    ${HOST_INPUT_SOURCES}
)

# These TUs guard their Qt usage behind '#ifndef ANDROID' (upstream's Android
# port convention); define it per-file so they build Qt-free, exactly like the
# JNI bridge does on Android proper.
set_source_files_properties(${HOST_INPUT_SOURCES} PROPERTIES
    COMPILE_DEFINITIONS ANDROID
    INCLUDE_DIRECTORIES "${rpcs3_SOURCE_DIR}/3rdparty/hidapi/hidapi/libusb"
)

set_target_properties(cellstation-host-runner PROPERTIES CXX_STANDARD 23 CXX_STANDARD_REQUIRED ON)

target_include_directories(cellstation-host-runner PRIVATE
    "${rpcs3_SOURCE_DIR}/rpcs3"
    "${rpcs3_SOURCE_DIR}"
)

target_compile_options(cellstation-host-runner PRIVATE -fno-exceptions)

target_link_libraries(cellstation-host-runner PRIVATE
    rpcs3_emu
    3rdparty_hidapi
    Fusion
)
