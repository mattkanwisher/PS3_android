# libchrysalis.so — thin JNI bridge over the unmodified rpcs3_emu core (M2).
# All emulation logic stays in the core; this layer only adapts paths, logging
# and lifecycle to Android.

set(RPCS3_SRC_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../rpcs3/rpcs3")

# The core expects its embedder to provide the pad/input layer and version
# info (the desktop app compiles these into the executable); reuse the
# upstream sources unmodified — they are already Android-aware.
add_library(chrysalis SHARED
    chrysalis_boot.cpp
    chrysalis_jni.cpp
    core_compat.cpp
    "${RPCS3_SRC_DIR}/rpcs3_version.cpp"
    "${RPCS3_SRC_DIR}/Input/ds3_pad_handler.cpp"
    "${RPCS3_SRC_DIR}/Input/ds4_pad_handler.cpp"
    "${RPCS3_SRC_DIR}/Input/dualsense_pad_handler.cpp"
    "${RPCS3_SRC_DIR}/Input/hid_pad_handler.cpp"
    "${RPCS3_SRC_DIR}/Input/pad_thread.cpp"
    "${RPCS3_SRC_DIR}/Input/product_info.cpp"
    "${RPCS3_SRC_DIR}/Input/ps_move_calibration.cpp"
    "${RPCS3_SRC_DIR}/Input/ps_move_config.cpp"
    "${RPCS3_SRC_DIR}/Input/ps_move_handler.cpp"
    "${RPCS3_SRC_DIR}/Input/ps_move_tracker.cpp"
    "${RPCS3_SRC_DIR}/Input/skateboard_pad_handler.cpp"
)

find_library(ANDROID_LOG_LIB log REQUIRED)

target_link_libraries(chrysalis PRIVATE
    rpcs3_emu
    3rdparty::hidapi
    3rdparty::fusion
    ${ANDROID_LOG_LIB}
)

# ffmpeg's aarch64 NEON asm addresses its own global tables PC-relatively;
# bind globals locally so those relocations stay legal inside a shared lib.
target_link_options(chrysalis PRIVATE -Wl,-Bsymbolic)

# Stage a stripped copy where the Gradle app expects its jniLibs, so the APK
# doesn't ship the ~360 MB of debug symbols.
add_custom_command(TARGET chrysalis POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E make_directory "${CMAKE_BINARY_DIR}/jniLibs/${ANDROID_ABI}"
    COMMAND ${CMAKE_STRIP} --strip-unneeded "$<TARGET_FILE:chrysalis>" -o "${CMAKE_BINARY_DIR}/jniLibs/${ANDROID_ABI}/libchrysalis.so"
    COMMENT "Staging stripped libchrysalis.so for the app")
