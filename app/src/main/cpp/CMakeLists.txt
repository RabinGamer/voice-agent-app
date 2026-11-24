cmake_minimum_required(VERSION 3.22)
project(voiceagent LANGUAGES CXX)

# Prebuilt Cactus 4 engine (release 4.0.0, android-arm64), Apache-2.0.
# Downloaded from:
# https://huggingface.co/Cactus-Compute/cactus/tree/main/releases/4.0.0/android-arm64
add_library(cactus_engine STATIC IMPORTED)
set_target_properties(cactus_engine PROPERTIES
    IMPORTED_LOCATION "${CMAKE_CURRENT_SOURCE_DIR}/cactus/libcactus.a")

add_library(cactus SHARED cactus_jni.cpp)
target_include_directories(cactus PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/cactus")
target_compile_features(cactus PRIVATE cxx_std_20)

# The outline-atomics helpers the engine references (__aarch64_ldadd*) come from
# the NDK's compiler-rt builtins, which clang links automatically.
target_link_libraries(cactus
    cactus_engine
    c++_static
    log
    m
    dl)

target_link_options(cactus PRIVATE
    -Wl,-z,max-page-size=16384
    -Wl,--exclude-libs,ALL
    -Wl,--no-undefined)

set_target_properties(cactus PROPERTIES OUTPUT_NAME "cactus")
