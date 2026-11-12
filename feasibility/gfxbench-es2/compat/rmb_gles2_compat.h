/*
 * Force-included into every GFXBench translation unit (see CMakeLists.txt).
 *
 * GFXBench was last built against GLES3 headers. With only <GLES2/gl2.h> and
 * <GLES2/gl2ext.h> from NDK r23c, a few enum names it relies on are missing even
 * though the code that uses them is guarded and never runs on ES2 devices:
 *
 *   - gl2ext.h (GL_NV_texture_array) defines GL_TEXTURE_3D but not GL_TEXTURE_WRAP_R,
 *     so glb_texture.cpp's "#ifdef GL_TEXTURE_3D" branch fails to compile.
 *
 * Values are the standard Khronos enum values (identical in gl3.h and the OES
 * extension). Defining them does not enable any ES3 behaviour; the GL driver decides
 * at run time what it accepts.
 */
#ifndef RMB_GLES2_COMPAT_H
#define RMB_GLES2_COMPAT_H

#ifndef GL_TEXTURE_WRAP_R
#define GL_TEXTURE_WRAP_R 0x8072 /* == GL_TEXTURE_WRAP_R_OES (GL_OES_texture_3D) */
#endif

#endif
