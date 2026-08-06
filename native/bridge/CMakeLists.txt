# libcellstation.so: JNI bridge + the upstream sources that live outside
# emucore (version string, Input stack — already Android-guarded upstream).

add_library(cellstation SHARED
    bridge.cpp
    android_pad_handler.cpp
    fatal_report.cpp
    vulkan_loader.cpp
    vulkan_trampolines.S
    "${rpcs3_SOURCE_DIR}/rpcs3/rpcs3_version.cpp"
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

set_target_properties(cellstation PROPERTIES CXX_STANDARD 23 CXX_STANDARD_REQUIRED ON)

target_include_directories(cellstation PRIVATE
    "${rpcs3_SOURCE_DIR}/rpcs3"
    "${rpcs3_SOURCE_DIR}"
)

# Match emucore's arch baseline (see patch 0007: RPCS3_ARM_BASELINE, default
# armv8.1-a; CI's emulator lane builds armv8-a because the arm64 binary
# translator lacks LSE); keep exceptions off like the rest of the tree.
if(NOT RPCS3_ARM_BASELINE)
    set(RPCS3_ARM_BASELINE armv8.1-a)
endif()
target_compile_options(cellstation PRIVATE -march=${RPCS3_ARM_BASELINE} -fno-exceptions)

# Describing the in-flight exception requires rethrowing it, so this one file
# opts back in (source options are appended after the target's).
set_source_files_properties(fatal_report.cpp PROPERTIES COMPILE_OPTIONS "-fexceptions")

target_link_libraries(cellstation PRIVATE
    rpcs3_emu
    3rdparty_hidapi
    Fusion
    adrenotools
    android
    log
    nativewindow
)

# Localize symbols from the static deps (ffmpeg/LLVM/...): keeps their
# PC-relative asm relocations valid inside a shared lib and exports only
# the JNI surface — plus the Vulkan trampolines, which the core's statically
# linked objects resolve internally anyway.
target_link_options(cellstation PRIVATE "-Wl,--exclude-libs,ALL")

# adrenotools' hook libraries load at runtime from the APK's native lib dir;
# building the bridge must produce them even though nothing links them.
add_dependencies(cellstation hook_impl main_hook file_redirect_hook gsl_alloc_hook)
