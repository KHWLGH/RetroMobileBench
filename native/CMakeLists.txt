cmake_minimum_required(VERSION 3.18.1)
project(retromobilebench C CXX)
set(ANDROID_ABI "${CMAKE_ANDROID_ARCH_ABI}")
set(CMAKE_C_STANDARD 99)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 11)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
file(READ "${CMAKE_ANDROID_NDK}/RELEASE.TXT" RMB_NDK_RELEASE)
if(NOT RMB_NDK_RELEASE MATCHES "^r10e" OR NOT CMAKE_C_COMPILER_VERSION MATCHES "^4[.]9[.]")
    message(FATAL_ERROR "This score protocol requires NDK r10e / GCC 4.9")
endif()

# Gingerbread's x86 pthread/JNI callers can enter with only 4-byte stack
# alignment. Realign functions that need SSE stack slots; inherit this for all
# C/C++ dependencies, including the embedded GFXBench libraries.
set(RMB_ABI_COMPILE_OPTIONS)
if(ANDROID_ABI STREQUAL "x86")
    list(APPEND RMB_ABI_COMPILE_OPTIONS -mstackrealign)
    add_compile_options(${RMB_ABI_COMPILE_OPTIONS})
endif()

# Everything native ends up in ONE shared library per variant, with a static C++
# runtime: before Android 4.3 the dynamic linker does not resolve DT_NEEDED entries
# from the app's own lib/ directory (docs/01-architecture.md §3.1).
#
# armeabi-v7a gets two variants built from the same sources:
#   librmb.so         NEON explicitly enabled
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

set(ZSTD_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../third_party/zstd/lib)
if(NOT EXISTS ${ZSTD_DIR}/zstd.h)
    message(FATAL_ERROR "Missing zstd source. Run: git submodule update --init")
endif()
# Only codec sources, no CLI, dictionary builder or legacy-format decoders.
file(GLOB ZSTD_SOURCES CONFIGURE_DEPENDS
    ${ZSTD_DIR}/common/*.c ${ZSTD_DIR}/compress/*.c ${ZSTD_DIR}/decompress/*.c)
set(SQLITE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../third_party/sqlite-amalgamation-3530100)
set_source_files_properties(${SQLITE_DIR}/sqlite3.c PROPERTIES COMPILE_DEFINITIONS
    "SQLITE_THREADSAFE=0;SQLITE_DISABLE_LFS=1;SQLITE_TEMP_STORE=3;SQLITE_OMIT_LOAD_EXTENSION=1;SQLITE_DEFAULT_MEMSTATUS=0;SQLITE_DEFAULT_MMAP_SIZE=0;SQLITE_MAX_MMAP_SIZE=0")

set(COREMARK_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../third_party/coremark)
set(COREMARK_SOURCES
    ${COREMARK_DIR}/core_main.c ${COREMARK_DIR}/core_list_join.c
    ${COREMARK_DIR}/core_matrix.c ${COREMARK_DIR}/core_state.c ${COREMARK_DIR}/core_util.c
    src/coremark_port/core_portme.c)

set(SCIMARK_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../third_party/scimark2)
set(SCIMARK_SOURCES ${SCIMARK_DIR}/FFT.c ${SCIMARK_DIR}/SOR.c ${SCIMARK_DIR}/LU.c
    ${SCIMARK_DIR}/MonteCarlo.c ${SCIMARK_DIR}/SparseCompRow.c ${SCIMARK_DIR}/Random.c)
set(TINYMEM_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../third_party/tinymembench)

set(RMB_SOURCES
    src/rmb_jni.c
    src/probe.c
    src/storage_bench.c
    src/cpu_bench.c
    src/cpu_zstd_bench.c
    src/sqlite_bench.c
    src/memory_bench.c
    src/stream_bench.c
    src/stream_kernels.c
    ${TINYMEM_DIR}/util.c
    src/scimark_bench.c
    ${SCIMARK_SOURCES}
    ${COREMARK_SOURCES}
    ${ZLIB_SOURCES}
    ${ZSTD_SOURCES}
    ${SQLITE_DIR}/sqlite3.c
)

function(rmb_add_library name variant)
    add_library(${name} SHARED ${RMB_SOURCES})
    target_compile_options(${name} PRIVATE -Wall -Wextra -O2 -fvisibility=hidden ${ARGN})
    target_include_directories(${name} PRIVATE ${ZLIB_DIR} ${ZSTD_DIR} ${SQLITE_DIR}
        ${COREMARK_DIR} src/coremark_port ${SCIMARK_DIR} ${TINYMEM_DIR})
    string(TOUPPER "${CMAKE_BUILD_TYPE}" build_type_upper)
    string(JOIN " " core_flags "${CMAKE_C_FLAGS}" "${CMAKE_C_FLAGS_${build_type_upper}}"
        "-Wall -Wextra -O2 -fvisibility=hidden" ${RMB_ABI_COMPILE_OPTIONS} ${ARGN})
    target_compile_definitions(${name} PRIVATE RMB_CORE_FLAGS="${core_flags}")
    # Avoid symbol interposition with Android's system libz. JNIEXPORT stays visible.
    target_compile_definitions(${name} PRIVATE Z_PREFIX z_errmsg=rmb_z_errmsg
        ZSTD_DISABLE_ASM=1 ZSTD_LEGACY_SUPPORT=0 ZSTDLIB_VISIBLE=
        RMB_BUILD_VARIANT="${variant}")
    target_compile_definitions(${name} PRIVATE RMB_NATIVE_BUILD_ID="${RMB_NATIVE_BUILD_ID}")
    target_link_libraries(${name} PRIVATE log GLESv2 m)
    set(GFXBENCH_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../third_party/gfxbench")
    set(RMB_EMBED_TARGET ${name})
    set(RMB_GFX_PREFIX "${name}_")
    set(RMB_NO_NEON OFF)
    if(variant STREQUAL "armeabi-v7a-noneon")
        set(RMB_NO_NEON ON)
    endif()
    add_subdirectory("${CMAKE_CURRENT_SOURCE_DIR}/../feasibility/gfxbench-es2"
        "${CMAKE_CURRENT_BINARY_DIR}/gfx-${name}")
endfunction()

if(ANDROID_ABI STREQUAL "armeabi-v7a")
    rmb_add_library(rmb "armeabi-v7a-neon" -mfpu=neon)
    # The old static GNU runtime supports baseline ARMv7 without NEON.
    rmb_add_library(rmb_noneon "armeabi-v7a-noneon" -mfpu=vfpv3-d16)
else()
    rmb_add_library(rmb "${ANDROID_ABI}")
endif()
