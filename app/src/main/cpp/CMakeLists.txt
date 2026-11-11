cmake_minimum_required(VERSION 3.22.1)
project(echocancel C)

# The echo canceller of the open-source WebRTC audio processing code (third_party/webrtc_aecm, unmodified, see its README.md) and the
# small JNI layer in front of it (echo_canceller_jni.c), as one library for the live microphone broadcast.
file(GLOB_RECURSE WEBRTC_SOURCES CONFIGURE_DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/third_party/webrtc_aecm/*.c)

add_library(echocancel SHARED echo_canceller_jni.c ${WEBRTC_SOURCES})

target_include_directories(echocancel PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/third_party/webrtc_aecm)
target_compile_definitions(echocancel PRIVATE WEBRTC_POSIX WEBRTC_LINUX WEBRTC_ANDROID)
# The vendored code is not ours to tidy: no warnings from it. Only the JNI functions are exported, and what nothing uses is dropped.
target_compile_options(echocancel PRIVATE -O2 -fvisibility=hidden -ffunction-sections -fdata-sections -w)
target_link_options(echocancel PRIVATE -Wl,--gc-sections -Wl,-z,max-page-size=16384)
