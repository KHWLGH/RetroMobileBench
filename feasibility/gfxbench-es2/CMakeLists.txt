cmake_minimum_required(VERSION 3.18.1)
project(gfxbench_es2_feasibility C CXX)

# M1 feasibility check (docs/09-roadmap.md): does GFXBench's OpenGL ES 2.0 code path
# compile and link with NDK r23c at API 16, without libGLESv3?
#
# This build does NOT use GFXBench's own build scripts. It compiles only the sources the
# ES2 tests need (docs/04-gfxbench-port.md §4.2) straight from a GFXBench checkout, into
# one shared library with a static C++ runtime (docs/01-architecture.md §3.1).
#
# Usage: see scripts/feasibility-gfxbench-es2.ps1 (passes -DGFXBENCH_DIR=...).

if(NOT GFXBENCH_DIR OR NOT EXISTS "${GFXBENCH_DIR}/gfxbench40/src/gfx3_0")
    message(FATAL_ERROR "Set GFXBENCH_DIR to a GFXBench checkout (sparse: frameworks, gfxbench40, 3rdparty/zlib, 3rdparty/libpng)")
endif()

set(G   "${GFXBENCH_DIR}")
set(FW  "${G}/frameworks")
set(G40 "${G}/gfxbench40/src")
set(KCL "${FW}/kcl_framework/kcl")
set(KCLLIB "${FW}/kcl_framework/kcl_libraries")

set(CMAKE_CXX_STANDARD 11)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Same spirit as GFXBench's compiler_flags.cmake, minus -pedantic-errors: we want to see
# real API-level problems, not style errors from a newer clang.
add_compile_options(-w -O2 -fno-strict-aliasing -fvisibility=hidden)
# When embedded, each ABI/NEON variant owns a complete set of static libraries.
if(NOT DEFINED RMB_GFX_PREFIX)
    set(RMB_GFX_PREFIX "")
endif()

# Tegra 2 build (decision 5): no NEON anywhere, same flag as native/CMakeLists.txt uses
# for librmb_noneon.so. Check the result with scripts/check-neon.ps1.
option(RMB_NO_NEON "Build for ARMv7 CPUs without NEON (VFPv3-D16)" OFF)
if(ANDROID_ABI STREQUAL "armeabi-v7a" AND NOT RMB_NO_NEON)
    add_compile_options(-mfpu=neon)
endif()
if(RMB_NO_NEON)
    if(NOT ANDROID_ABI STREQUAL "armeabi-v7a")
        message(FATAL_ERROR "RMB_NO_NEON only applies to armeabi-v7a")
    endif()
    add_compile_options(-mfpu=vfpv3-d16)
endif()

# Fill in enum names GLES2 headers lack but GFXBench's guarded code mentions (C++ only;
# the C sources are third-party libraries that never include GL headers).
set(RMB_COMPAT_HEADER "${CMAKE_CURRENT_LIST_DIR}/compat/rmb_gles2_compat.h")
add_compile_options("$<$<COMPILE_LANGUAGE:CXX>:SHELL:-include ${RMB_COMPAT_HEADER}>")

# --- Generated headers -------------------------------------------------------
set(GEN "${CMAKE_CURRENT_BINARY_DIR}/gen")
file(MAKE_DIRECTORY "${GEN}")
# API 8 has GLES2 but no public native EGL. Use only the API 9 EGL type
# declarations; all context operations are delegated to Java EGL10.
if(CMAKE_SYSTEM_VERSION LESS 9)
    file(COPY "${CMAKE_ANDROID_NDK}/platforms/android-9/arch-arm/usr/include/EGL" DESTINATION "${GEN}")
    file(COPY "${CMAKE_ANDROID_NDK}/platforms/android-9/arch-arm/usr/include/KHR" DESTINATION "${GEN}")
endif()
include_directories(BEFORE "${CMAKE_CURRENT_SOURCE_DIR}/compat" "${GEN}")
file(READ "${G40}/gfxbench/gfxbench.cpp" RMB_GFX_FRONT)
string(REPLACE "#include \"graphics/eglgraphicscontext.h\"" "// Context hosted through Java EGL10" RMB_GFX_FRONT "${RMB_GFX_FRONT}")
string(REPLACE "((EGLGraphicsContext*)ctx_)->selectedConfig()" "0" RMB_GFX_FRONT "${RMB_GFX_FRONT}")
file(WRITE "${GEN}/gfxbench.cpp" "${RMB_GFX_FRONT}")
file(READ "${FW}/platform-utils/battery.cpp" RMB_BATTERY)
string(REPLACE "\"platform/platform_android.h\"" "\"${CMAKE_CURRENT_SOURCE_DIR}/compat/platform/platform_android.h\"" RMB_BATTERY "${RMB_BATTERY}")
file(WRITE "${GEN}/battery.cpp" "${RMB_BATTERY}")
file(READ "${KCL}/platforms/android/src/kcl_os.cpp" RMB_KCL_OS)
string(REPLACE "#include <android/asset_manager.h>" "// Assets are extracted by Java" RMB_KCL_OS "${RMB_KCL_OS}")
file(WRITE "${GEN}/kcl_os.cpp" "${RMB_KCL_OS}")
configure_file("${G}/3rdparty/zlib/zconf.h.cmakein" "${GEN}/zconf.h" @ONLY)
configure_file("${G}/3rdparty/libpng/scripts/pnglibconf.h.prebuilt" "${GEN}/pnglibconf.h" COPYONLY)

# --- zlib (the GFXBench-bundled copy) ----------------------------------------
add_library(${RMB_GFX_PREFIX}gfx_zlib STATIC
    ${G}/3rdparty/zlib/adler32.c ${G}/3rdparty/zlib/compress.c ${G}/3rdparty/zlib/crc32.c
    ${G}/3rdparty/zlib/deflate.c ${G}/3rdparty/zlib/gzclose.c ${G}/3rdparty/zlib/gzlib.c
    ${G}/3rdparty/zlib/gzread.c ${G}/3rdparty/zlib/gzwrite.c ${G}/3rdparty/zlib/inflate.c
    ${G}/3rdparty/zlib/infback.c ${G}/3rdparty/zlib/inftrees.c ${G}/3rdparty/zlib/inffast.c
    ${G}/3rdparty/zlib/trees.c ${G}/3rdparty/zlib/uncompr.c ${G}/3rdparty/zlib/zutil.c)
target_include_directories(${RMB_GFX_PREFIX}gfx_zlib PUBLIC "${GEN}" "${G}/3rdparty/zlib")

# --- libpng 1.6.7 (NEON code disabled, as GFXBench's CMake does) --------------
add_library(${RMB_GFX_PREFIX}gfx_png STATIC
    ${G}/3rdparty/libpng/png.c ${G}/3rdparty/libpng/pngerror.c ${G}/3rdparty/libpng/pngget.c
    ${G}/3rdparty/libpng/pngmem.c ${G}/3rdparty/libpng/pngpread.c ${G}/3rdparty/libpng/pngread.c
    ${G}/3rdparty/libpng/pngrio.c ${G}/3rdparty/libpng/pngrtran.c ${G}/3rdparty/libpng/pngrutil.c
    ${G}/3rdparty/libpng/pngset.c ${G}/3rdparty/libpng/pngtrans.c ${G}/3rdparty/libpng/pngwio.c
    ${G}/3rdparty/libpng/pngwrite.c ${G}/3rdparty/libpng/pngwtran.c ${G}/3rdparty/libpng/pngwutil.c)
target_compile_definitions(${RMB_GFX_PREFIX}gfx_png PRIVATE PNG_ARM_NEON_OPT=0)
target_include_directories(${RMB_GFX_PREFIX}gfx_png PUBLIC "${G}/3rdparty/libpng" "${GEN}")
target_link_libraries(${RMB_GFX_PREFIX}gfx_png PUBLIC ${RMB_GFX_PREFIX}gfx_zlib)

# --- ngrtl core (logging, json, streams) --------------------------------------
set(NGRTL "${FW}/ngrtl")
add_library(${RMB_GFX_PREFIX}gfx_ngrtl STATIC
    ${NGRTL}/libs/core/src/require.cpp ${NGRTL}/libs/core/src/format.cpp
    ${NGRTL}/libs/core/src/stream/bytestream.cpp ${NGRTL}/libs/core/src/stream/filestreambuf.cpp
    ${NGRTL}/libs/core/src/stream/memstreambuf.cpp ${NGRTL}/libs/core/src/stream/textstream.cpp
    ${NGRTL}/libs/core/src/log/log.cpp ${NGRTL}/libs/core/src/bytevec.cpp
    ${NGRTL}/libs/core/src/json/deserialize.cpp ${NGRTL}/libs/core/src/json/json.cpp
    ${NGRTL}/libs/core/src/json/JSON_parser.c ${NGRTL}/libs/core/src/json/serialize.cpp
    ${NGRTL}/libs/core/src/mutex.cpp ${NGRTL}/libs/core/src/timer.cpp
    ${NGRTL}/libs/core/src/base64/cdecode.c ${NGRTL}/libs/core/src/base64/cencode.c
    ${NGRTL}/libs/core/src/base64/decode.cpp ${NGRTL}/libs/core/src/base64/encode.cpp
    ${NGRTL}/libs/core/src/stringutil.cpp ${NGRTL}/libs/core/src/cstring.cpp
    ${NGRTL}/libs/core/src/substring.cpp ${NGRTL}/libs/core/src/macros.cpp
    ${NGRTL}/libs/core/src/object.cpp ${NGRTL}/libs/core/src/var.cpp
    ${NGRTL}/libs/core/src/json_var/json_parse.cpp ${NGRTL}/libs/core/src/json_var/json_stringify.cpp
    ${NGRTL}/libs/core/src/json_var/js_json.cpp ${NGRTL}/libs/core/src/result.cpp
    ${NGRTL}/libs/core/src/frequency_counter.cpp ${NGRTL}/libs/core/src/hr_clock.cpp)
target_compile_definitions(${RMB_GFX_PREFIX}gfx_ngrtl PUBLIC NGRTL_STATIC NGLOG_ENABLE=NGLOG_SEVERITY_INFO)
target_include_directories(${RMB_GFX_PREFIX}gfx_ngrtl PUBLIC "${NGRTL}/include/core")
target_link_libraries(${RMB_GFX_PREFIX}gfx_ngrtl PUBLIC log)

# --- platform-utils (API 8 sysfs battery fallback) -------------------------
add_library(${RMB_GFX_PREFIX}gfx_platform STATIC compat/platform_android.cpp ${GEN}/battery.cpp)
target_include_directories(${RMB_GFX_PREFIX}gfx_platform BEFORE PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}/compat" "${FW}/platform-utils")
target_link_libraries(${RMB_GFX_PREFIX}gfx_platform PUBLIC log)

# --- Common GL definitions for everything GFXBench --------------------------------
# HAVE_GLES2 without HAVE_GLES3 is the whole point: API 16 has no libGLESv3.
set(GFX_DEFS USE_ANY_GL HAVE_EGL HAVE_GLES HAVE_GLES2 GFXB_DEPRECATED DISPLAY_PROTOCOL_ANDROID=1)

# --- KCL (scene graph, image/texture loading, IO) ------------------------------
add_library(${RMB_GFX_PREFIX}gfx_kcl STATIC
    ${KCL}/src/mesh_tool.cpp ${KCL}/src/kcl_base.cpp ${KCL}/src/kcl_actor.cpp
    ${KCL}/src/kcl_animation4.cpp ${KCL}/src/kcl_animationtrack.cpp ${KCL}/src/kcl_camera2.cpp
    ${KCL}/src/kcl_effect.cpp ${KCL}/src/kcl_image.cpp ${KCL}/src/kcl_light2.cpp
    ${KCL}/src/kcl_lod.cpp ${KCL}/src/kcl_material.cpp ${KCL}/src/kcl_math3d.cpp
    ${KCL}/src/kcl_mesh.cpp ${KCL}/src/kcl_node.cpp ${KCL}/src/kcl_object.cpp
    ${KCL}/src/kcl_serializable.cpp ${KCL}/src/kcl_room.cpp ${KCL}/src/kcl_particlesystem.cpp
    ${KCL}/src/kcl_particlesystem2.cpp ${KCL}/src/kcl_planarmap.cpp ${KCL}/src/kcl_scene_handler.cpp
    ${KCL}/src/kcl_texture.cpp ${KCL}/src/kcl_scene_xml.cpp ${KCL}/src/kcl_io.cpp
    ${KCL}/src/ogg_decoder.cpp ${KCL}/src/kcl_buffer.cpp ${KCL}/src/kcl_envprobe.cpp
    ${KCL}/src/hdr.cpp
    ${GEN}/kcl_os.cpp compat/kcl_io_os.cpp
    # KCL bundled libraries
    ${KCLLIB}/libetc1/src/etc1.cpp
    ${KCLLIB}/tinyxml/src/tinystr.cpp ${KCLLIB}/tinyxml/src/tinyxml.cpp
    ${KCLLIB}/tinyxml/src/tinyxmlerror.cpp ${KCLLIB}/tinyxml/src/tinyxmlparser.cpp
    ${KCLLIB}/tinyxml2/src/tinyxml2.cpp
    ${KCLLIB}/libogg_theora_vorbis/src/bitpack.c ${KCLLIB}/libogg_theora_vorbis/src/bitwise.c
    ${KCLLIB}/libogg_theora_vorbis/src/decinfo.c ${KCLLIB}/libogg_theora_vorbis/src/decode.c
    ${KCLLIB}/libogg_theora_vorbis/src/dequant.c ${KCLLIB}/libogg_theora_vorbis/src/fragment.c
    ${KCLLIB}/libogg_theora_vorbis/src/framing.c ${KCLLIB}/libogg_theora_vorbis/src/huffdec.c
    ${KCLLIB}/libogg_theora_vorbis/src/idct.c ${KCLLIB}/libogg_theora_vorbis/src/info.c
    ${KCLLIB}/libogg_theora_vorbis/src/internal.c ${KCLLIB}/libogg_theora_vorbis/src/quant.c
    ${KCLLIB}/libogg_theora_vorbis/src/state.c)
target_compile_definitions(${RMB_GFX_PREFIX}gfx_kcl PUBLIC ${GFX_DEFS})
target_include_directories(${RMB_GFX_PREFIX}gfx_kcl PUBLIC
    "${KCL}/src" "${KCL}/platforms/android/src" "${KCLLIB}/libetc1/src" "${KCLLIB}/libogg_theora_vorbis" "${KCLLIB}/tinythread"
    "${KCLLIB}/tinyxml/src" "${KCLLIB}/tinyxml2/src" "${FW}/oglx/..")
target_link_libraries(${RMB_GFX_PREFIX}gfx_kcl PUBLIC ${RMB_GFX_PREFIX}gfx_png ${RMB_GFX_PREFIX}gfx_ngrtl ${RMB_GFX_PREFIX}gfx_platform)

# --- KRL (render-layer scene/material base) -----------------------------------
add_library(${RMB_GFX_PREFIX}gfx_krl STATIC
    ${FW}/krl/krl_material.cpp ${FW}/krl/krl_scene.cpp ${FW}/krl/krl_scene_reload.cpp ${FW}/krl/krl_mesh.cpp)
target_include_directories(${RMB_GFX_PREFIX}gfx_krl PUBLIC "${FW}/krl")
target_link_libraries(${RMB_GFX_PREFIX}gfx_krl PUBLIC ${RMB_GFX_PREFIX}gfx_kcl)

# --- testfw core + schemas (no SWIG / Java here) ----------------------------------
add_library(${RMB_GFX_PREFIX}gfx_testfw STATIC
    ${FW}/testfw/testfw.cpp
    ${FW}/testfw/schemas/apidefinition.cpp ${FW}/testfw/schemas/descriptors.cpp
    ${FW}/testfw/schemas/compareitem.cpp ${FW}/testfw/schemas/compareresult.cpp
    ${FW}/testfw/schemas/configuration.cpp ${FW}/testfw/schemas/compositedescriptor.cpp
    ${FW}/testfw/schemas/resultitem.cpp ${FW}/testfw/schemas/testinfo.cpp
    ${FW}/testfw/schemas/testitem.cpp ${FW}/testfw/schemas/testrepository.cpp
    ${FW}/testfw/schemas/chart.cpp ${FW}/testfw/schemas/computeresult.cpp
    ${FW}/testfw/schemas/gfxresult.cpp ${FW}/testfw/schemas/result.cpp
    ${FW}/testfw/schemas/graphics.cpp ${FW}/testfw/schemas/compute.cpp
    ${FW}/testfw/schemas/environment.cpp)
target_compile_definitions(${RMB_GFX_PREFIX}gfx_testfw PUBLIC ${GFX_DEFS})
target_include_directories(${RMB_GFX_PREFIX}gfx_testfw PUBLIC "${FW}/testfw" "${FW}/testfw/schemas")
target_link_libraries(${RMB_GFX_PREFIX}gfx_testfw PUBLIC ${RMB_GFX_PREFIX}gfx_ngrtl GLESv2 dl)

# --- GFXBench ES2 tests ---------------------------------------------------------
set(GL_COMMON
    ${G40}/common/testfw_functions.cpp ${G40}/common/newnotificationmanager.cpp
    ${G40}/common/test_descriptor.cpp ${G40}/common/render_statistics.cpp
    ${G40}/common/xml_utils.cpp ${G40}/common/printable_result.cpp ${G40}/common/test_base.cpp
    ${G40}/common/opengl/bitmask.cpp ${G40}/common/opengl/ext.cpp ${G40}/common/opengl/fbo.cpp
    ${G40}/common/opengl/glbshader.cpp ${G40}/common/opengl/shader.cpp
    ${G40}/common/opengl/glb_shader_common.cpp ${G40}/common/opengl/glwindow.cpp
    ${G40}/common/opengl/misc2.cpp ${G40}/common/opengl/misc2_opengl.cpp
    ${G40}/common/opengl/glb_opengl_state_manager.cpp ${G40}/common/opengl/glb_discard_functions.cpp
    ${G40}/common/opengl/glb_image.cpp ${G40}/common/opengl/glb_texture.cpp
    ${G40}/common/opengl/notificationman_opengl.cpp ${G40}/common/opengl/offscrman_opengl.cpp
    ${G40}/common/opengl/texture.cpp ${G40}/common/opengl/newnotificationmanager_opengl.cpp
    ${G40}/common/opengl/glb_opengl_limits.cpp ${G40}/common/opengl/glb_opengl_debug_output.cpp
    ${G40}/common/opengl/vbopool.cpp)

set(GFX30_SCENE
    ${G40}/gfx3_0/high_level_tests_3/engine.cpp
    ${G40}/gfx3_0/high_level_tests_3/spacepartition.cpp ${G40}/gfx3_0/high_level_tests_3/qualitymatch.cpp
    ${G40}/gfx3_0/high_level_tests_3/test_wrapper.cpp ${G40}/gfx3_0/high_level_tests_3/battery_test.cpp
    ${G40}/es2_common/glb_scene_.cpp ${G40}/es2_common/glb_mesh.cpp ${G40}/es2_common/glb_mesh_opengl.cpp
    ${G40}/gfx3_0/high_level_tests_3/opengl/glb_scene_utils_opengl.cpp
    ${G40}/gfx3_0/high_level_tests_3/opengl/cubemap_opengl.cpp
    ${G40}/gfx3_0/high_level_tests_3/opengl/glb_light.cpp
    ${G40}/gfx3_0/high_level_tests_3/opengl/glb_material.cpp
    ${G40}/gfx3_0/high_level_tests_3/opengl/glb_material_opengl.cpp
    ${G40}/gfx3_0/high_level_tests_3/opengl/glb_planarmap_opengl.cpp
    ${G40}/gfx3_0/high_level_tests_3/opengl/glb_scene_opengl27.cpp
    ${G40}/gfx3_0/high_level_tests_3/opengl/shadowmap_opengl.cpp
    ${G40}/gfx3_0/high_level_tests_3/opengl/glb_scene.cpp
    ${G40}/gfx3_0/high_level_tests_3/opengl/xiph_decode/glb_ogg_decoder.cpp
    ${G40}/gfx3_0/high_level_tests_3/opengl/glb_particlesystem.cpp
    ${G40}/gfx3_0/high_level_tests_3/opengl/gbuffer.cpp
    ${G40}/gfx3_0/high_level_tests_3/opengl/glb_scene_opengl.cpp
    ${G40}/gfx3_0/high_level_tests_3/opengl/vao_storage.cpp)

set(GFX30_LOWLEVEL
    ${G40}/gfx3_0/low_level_tests/alu_base.cpp ${G40}/gfx3_0/low_level_tests/blending_base.cpp
    ${G40}/gfx3_0/low_level_tests/driver_base.cpp ${G40}/gfx3_0/low_level_tests/fill_base.cpp
    ${G40}/gfx3_0/low_level_tests/opengl/gl_alu.cpp ${G40}/gfx3_0/low_level_tests/opengl/gl_blending.cpp
    ${G40}/gfx3_0/low_level_tests/opengl/gl_driver.cpp ${G40}/gfx3_0/low_level_tests/opengl/gl_fill.cpp)

set(GFX_FRONT
    ${G40}/gfxbench/global_test_environment.cpp ${G40}/gfxbench/status_report.cpp
    ${GEN}/gfxbench.cpp
    # Always built by GFXBench's own CMake; the function body is empty outside iOS.
    ${G40}/gfxbench/gfxbench_ios_rawconfig.cpp
    ${G40}/tests/40/corporate/create_factory_trex_corporate.cpp
    ${G40}/tests/40/corporate/create_factory_egypt_corporate.cpp
    ${G40}/tests/40/corporate/create_factory_lowlevel_corporate.cpp)

add_library(${RMB_GFX_PREFIX}gfxbench_es2 STATIC ${GL_COMMON} ${GFX30_SCENE} ${GFX30_LOWLEVEL} ${GFX_FRONT})
# OPT_TEST_GFX2: build GLB_Scene_ES2 (T-Rex, Egypt). Deliberately NOT defining
# OPT_TEST_GFX30/31/40 or HAVE_GLES3.
target_compile_definitions(${RMB_GFX_PREFIX}gfxbench_es2 PRIVATE OPT_TEST_GFX2 PRODUCT_VERSION=4.0.0)
target_include_directories(${RMB_GFX_PREFIX}gfxbench_es2 PRIVATE
    "${G40}" "${G40}/common" "${G40}/es2_common"
    "${G40}/gfx3_0" "${G40}/gfx3_0/high_level_tests_3" "${G40}/gfx3_0/low_level_tests"
    "${G40}/gfxbench" "${G40}/gfxbench/corporate" "${G40}/frame_player"
    "${G}/gfxbench40/resources/data/gfx/shaders_40/shaders.20")
target_link_libraries(${RMB_GFX_PREFIX}gfxbench_es2 PUBLIC ${RMB_GFX_PREFIX}gfx_krl ${RMB_GFX_PREFIX}gfx_kcl ${RMB_GFX_PREFIX}gfx_testfw)

# --- Final shared library: everything linked in, no undefined symbols ------------
set(GFX_LINK_LIBS
    -Wl,--whole-archive ${RMB_GFX_PREFIX}gfxbench_es2 -Wl,--no-whole-archive
    ${RMB_GFX_PREFIX}gfx_testfw ${RMB_GFX_PREFIX}gfx_krl ${RMB_GFX_PREFIX}gfx_kcl ${RMB_GFX_PREFIX}gfx_png ${RMB_GFX_PREFIX}gfx_zlib ${RMB_GFX_PREFIX}gfx_ngrtl ${RMB_GFX_PREFIX}gfx_platform
    GLESv2 log dl m)

set(RMB_HOST_SRCS probe_main.cpp rmb_native_egl_context.cpp)

if(RMB_EMBED_TARGET)
    target_compile_definitions(${RMB_EMBED_TARGET} PRIVATE RMB_JAVA_EGL=1)
    target_include_directories(${RMB_EMBED_TARGET} BEFORE PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/compat" "${GEN}")
    target_sources(${RMB_EMBED_TARGET} PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/probe_main.cpp ${CMAKE_CURRENT_SOURCE_DIR}/rmb_egl_context.cpp)
    target_link_libraries(${RMB_EMBED_TARGET} PRIVATE ${GFX_LINK_LIBS})
    target_compile_features(${RMB_EMBED_TARGET} PRIVATE cxx_std_11)
    target_link_options(${RMB_EMBED_TARGET} PRIVATE -Wl,--no-undefined -Wl,--exclude-libs,ALL)
else()
    add_library(gfxbench_es2_probe SHARED ${RMB_HOST_SRCS})
    target_link_libraries(gfxbench_es2_probe PRIVATE ${GFX_LINK_LIBS} EGL)
    target_link_options(gfxbench_es2_probe PRIVATE -Wl,--no-undefined)
    # PIE works on Android 4.1 and is required on 5.0+.
    add_executable(gfxprobe cli_main.cpp ${RMB_HOST_SRCS})
    target_link_libraries(gfxprobe PRIVATE ${GFX_LINK_LIBS} EGL)
endif()
