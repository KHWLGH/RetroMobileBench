# Fixed scalar decoders keep the same workload on NEON and non-NEON ARMv7.
set(MEDIA_THIRD ${CMAKE_CURRENT_SOURCE_DIR}/../third_party)
set(ENABLE_SHARED OFF CACHE BOOL "" FORCE)
set(ENABLE_STATIC ON CACHE BOOL "" FORCE)
set(WITH_SIMD OFF CACHE BOOL "" FORCE)
set(WITH_TURBOJPEG ON CACHE BOOL "" FORCE)
set(WITH_JAVA OFF CACHE BOOL "" FORCE)
add_subdirectory(${MEDIA_THIRD}/libjpeg-turbo ${CMAKE_CURRENT_BINARY_DIR}/jpeg EXCLUDE_FROM_ALL)
set(PNG_DIR ${MEDIA_THIRD}/libpng)
configure_file(${PNG_DIR}/scripts/pnglibconf.h.prebuilt ${CMAKE_CURRENT_BINARY_DIR}/media/pnglibconf.h COPYONLY)
set(MBED_DIR ${MEDIA_THIRD}/mbedtls)
set(FFMPEG_BUILD ${CMAKE_CURRENT_SOURCE_DIR}/../app/build/ffmpeg/${ANDROID_ABI})
foreach(lib avutil avcodec avformat)
    add_library(media_${lib} SHARED IMPORTED)
    set_target_properties(media_${lib} PROPERTIES IMPORTED_LOCATION ${FFMPEG_BUILD}/install/lib/lib${lib}.so)
endforeach()
add_library(rmbmedia SHARED src/media_jni.c src/crypto_bench.c src/image_bench.c src/video_bench.c
    ${MBED_DIR}/library/aes.c ${MBED_DIR}/library/gcm.c ${MBED_DIR}/library/cipher.c ${MBED_DIR}/library/cipher_wrap.c
    ${MBED_DIR}/library/sha256.c ${MBED_DIR}/library/platform_util.c ${MBED_DIR}/library/constant_time.c
    ${PNG_DIR}/png.c ${PNG_DIR}/pngerror.c ${PNG_DIR}/pngget.c ${PNG_DIR}/pngmem.c ${PNG_DIR}/pngpread.c
    ${PNG_DIR}/pngread.c ${PNG_DIR}/pngrio.c ${PNG_DIR}/pngrtran.c ${PNG_DIR}/pngrutil.c
    ${PNG_DIR}/pngset.c ${PNG_DIR}/pngtrans.c ${PNG_DIR}/pngwio.c ${PNG_DIR}/pngwrite.c ${PNG_DIR}/pngwtran.c ${PNG_DIR}/pngwutil.c
    ${ZLIB_SOURCES})
target_include_directories(rmbmedia PRIVATE src ${MBED_DIR}/include ${MEDIA_THIRD}/libjpeg-turbo
    ${CMAKE_CURRENT_BINARY_DIR}/jpeg ${PNG_DIR} ${CMAKE_CURRENT_BINARY_DIR}/media ${ZLIB_DIR}
    ${FFMPEG_BUILD}/install/include)
target_compile_definitions(rmbmedia PRIVATE MBEDTLS_CONFIG_FILE="crypto_config.h" PNG_ARM_NEON_OPT=0)
target_compile_options(rmbmedia PRIVATE -O2 -fvisibility=hidden -Wall -Wextra)
if(ANDROID_ABI STREQUAL "armeabi-v7a")
    target_compile_options(rmbmedia PRIVATE -mfpu=vfpv3-d16)
endif()
# Bind private PNG/zlib/JPEG symbols locally, independently of the GFXBench image loader.
set_target_properties(rmbmedia PROPERTIES LINK_FLAGS "-Wl,-Bsymbolic -Wl,--exclude-libs,ALL -Wl,--no-undefined")
target_link_libraries(rmbmedia PRIVATE turbojpeg-static media_avformat media_avcodec media_avutil m log)
