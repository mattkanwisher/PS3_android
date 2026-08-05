# Injected into the RtMidi subproject via CMAKE_PROJECT_RtMidi_INCLUDE.
# The host harness sets the ANDROID CMake variable globally to reuse rpcs3's
# no-Qt embedding gates, but RtMidi keys its AMIDI backend on that same
# variable and would then need NDK-only headers. Neutralize it here so RtMidi
# builds its dummy backend (MIDI is unused in boot tests).
set(ANDROID 0)
set(RTMIDI_API_AMIDI OFF CACHE BOOL "" FORCE)
