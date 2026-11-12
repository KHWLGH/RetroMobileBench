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

# Pinned official v1.3.1 submodule. Compile only the in-memory codec, no gz file I/O.
set(ZLIB_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../third_party/zlib)
if(NOT EXISTS ${ZLIB_DIR}/zlib.h)
    message(FATAL_ERROR "Missing zlib source. Run: git submodule update --init third_party/zlib")
endif()
set(ZLIB_SOURCES
    ${ZLIB_DIR}/adler32.c ${ZLIB_DIR}/compress.c ${ZLIB_DIR}/crc32.c
    ${ZLIB_DIR}/deflate.c ${ZLIB_DIR}/inflate.c ${ZLIB_DIR}/inffast.c
    ${ZLIB_DIR}/inftrees.c ${ZLIB_DIR}/trees.c ${ZLIB_DIR}/uncompr.c ${ZLIB_DIR}/zutil.c
)

set(RMB_SOURCES
    src/rmb_jni.c
    src/probe.c
    src/storage_bench.c
    src/cpu_bench.c
    ${ZLIB_SOURCES}
)

function(rmb_add_library name variant)
    add_library(${name} SHARED ${RMB_SOURCES})
    target_compile_options(${name} PRIVATE -Wall -Wextra -O2 -fvisibility=hidden ${ARGN})
    target_include_directories(${name} PRIVATE ${ZLIB_DIR})
    # Avoid symbol interposition with Android's system libz. JNIEXPORT stays visible.
    target_compile_definitions(${name} PRIVATE Z_PREFIX
        RMB_BUILD_VARIANT="${variant}")
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
