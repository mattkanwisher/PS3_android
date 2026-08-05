# libcellstation.so: JNI bridge + the upstream sources that live outside
# emucore (version string, Input stack — already Android-guarded upstream).

add_library(cellstation SHARED
    bridge.cpp
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
)

set_target_properties(cellstation PROPERTIES CXX_STANDARD 23 CXX_STANDARD_REQUIRED ON)

target_include_directories(cellstation PRIVATE
    "${rpcs3_SOURCE_DIR}/rpcs3"
    "${rpcs3_SOURCE_DIR}"
)

# Match emucore's arch baseline; keep exceptions off like the rest of the tree.
target_compile_options(cellstation PRIVATE -march=armv8.1-a -fno-exceptions)

target_link_libraries(cellstation PRIVATE
    rpcs3_emu
    3rdparty_hidapi
    android
    log
    nativewindow
)
