cmake_minimum_required(VERSION 3.18.1)
project(retromobilebench C CXX)

# Everything native ends up in ONE shared library per variant, with a static C++
# runtime: before Android 4.3 the dynamic linker does not resolve DT_NEEDED entries
# from the app's own lib/ directory (docs/01-architecture.md §3.1).
#
# armeabi-v7a gets two variants built from the same sources:
#   librmb.so         NEON on (the NDK r21+ default)
#   librmb_noneon.so  NEON off, for Tegra 2. The compiler auto-vectorizes plain C
#                     loops into NEON, so the whole library has to be rebuilt.
# NativeLoader.java picks one at run time. scripts/check-neon.ps1 verifies the
# no-NEON library really contains no NEON instructions.

set(RMB_SOURCES
    src/rmb_jni.c
    src/probe.c
)

function(rmb_add_library name variant)
    add_library(${name} SHARED ${RMB_SOURCES})
    target_compile_options(${name} PRIVATE -Wall -Wextra -O2 ${ARGN})
    target_compile_definitions(${name} PRIVATE RMB_BUILD_VARIANT="${variant}")
    target_link_libraries(${name} PRIVATE log EGL GLESv2)
endfunction()

if(ANDROID_ABI STREQUAL "armeabi-v7a")
    rmb_add_library(rmb "armeabi-v7a-neon")
    # NDK r23c's own armeabi-v7a sysroot libraries (libc++_static, libunwind,
    # compiler-rt) are still built without NEON; r24 changed that.
    rmb_add_library(rmb_noneon "armeabi-v7a-noneon" -mfpu=vfpv3-d16)
else()
    rmb_add_library(rmb "${ANDROID_ABI}")
endif()
