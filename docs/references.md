# 参考资料

更新于 2026-11-14。历史设计依据和当前实现证据分别记录，验收状态见 [13](13-suite-implementation.md)。

## 媒体、加密与旧平台实现

- 固定依赖版本及原始许可：[third_party/README.md](../third_party/README.md)。
- MbedTLS 2.28.10配置/GCM/SHA源码：https://github.com/Mbed-TLS/mbedtls/tree/mbedtls-2.28.10 。
- libjpeg-turbo 2.1.5.1 API/accurate DCT：https://github.com/libjpeg-turbo/libjpeg-turbo/tree/2.1.5.1 。JPEG参考使用同版djpeg标量整数DCT，PNG参考保留RGBA。
- libpng 1.6.43：https://github.com/pnggroup/libpng/tree/v1.6.43 。
- FFmpeg 4.4解码/EAGAIN/EOS：https://ffmpeg.org/doxygen/4.4/group__lavc__encdec.html 。
- FFmpeg LGPL与分发：https://ffmpeg.org/legal.html ；本项目 [构建/替换/对应源码说明](media-distribution.md)。
- MediaCodec旧同步接口：https://developer.android.com/reference/android/media/MediaCodec 。
- MediaCodecInfo硬件属性：https://developer.android.com/reference/android/media/MediaCodecInfo#isHardwareAccelerated() 。属性来自系统声明。
- AOSP CTS历史解码性能测试：https://android.googlesource.com/platform/cts/+/4d48fa2917d59a56c65d54e1931e6b90545dd4a1/tests/tests/media/src/android/media/cts/VideoDecoderPerfTest.java 。
- Bigflake：https://bigflake.com/mediacodec/ ；Grafika：https://github.com/google/grafika ；ijkplayer：https://github.com/bilibili/ijkplayer 。参考方法，不直接移入高API宿主。
- Sintel源与许可：https://durian.blender.org/sharing/ ；生成配方和全部参数/哈希：[固定素材清单](../app/src/main/assets/media/manifest.json)。
- GLBenchmark/GFXBench 3.0底层测试、默认选择的历史证据与边界详见 [12 §8](12-test-suite-plan.md#8-调研依据)。


## GFXBench

- 源码：https://github.com/Kishonti-Opensource/gfxbench（调研时的 commit：`89aa956`）
- 历史成绩：https://github.com/Kishonti-Opensource/GFXBench_and_CompuBench_results
- 开源新闻：https://overclock3d.net/news/software/gfxbench-and-compubench-are-now-open-source/
- 安卓构建说明：仓库内 `doc/gfxbench_gl_android_build.txt`

## Android 平台

- NDK r24 变更（停止支持 API 16–18 和无 NEON）：https://github.com/android/ndk/wiki/Changelog-r24
- NDK r23 变更（CMake toolchain 相关修复）：https://github.com/android/ndk/wiki/Changelog-r23
- NDK issue 1623（旧版 toolchain 文件 + CMake 3.22 编汇编出错）：https://github.com/android/ndk/issues/1623
- NEON 支持：https://developer.android.com/ndk/guides/cpu-arm-neon
- C++ 库支持（4.3 以前加载 libc++_shared 的限制）：https://developer.android.com/ndk/guides/cpp-support
- bionic 各版本函数：https://github.com/aosp-mirror/platform_bionic/blob/main/libc/libc.map.txt
- bionic 的 POSIX 特性宏（`_POSIX_TIMEOUTS` 等）：https://android.googlesource.com/platform/bionic/+/refs/heads/main/libc/include/bits/posix_limits.h
- OpenGL ES 版本与纹理压缩：https://developer.android.com/develop/ui/views/graphics/opengl/about-opengl
- AndroidX 版本与 minSdk：https://developer.android.com/jetpack/androidx/versions
- Google Play 服务停止 Jelly Bean 更新：https://android-developers.googleblog.com/2021/07/google-play-services-discontinuing-jelly-bean.html
- Android 14 拒绝安装 targetSdk < 23 的应用：https://bayton.org/android/android-14-minimum-sdk/
- Gson 最低 Android 版本：https://github.com/google/gson/blob/main/README.md
- commons-io 2.7 起需要 Java 8：https://commons.apache.org/io/changes.html

## 测试组件

- CoreMark：https://github.com/eembc/coremark（LICENSE.md、README.md）
- CoreMark-PRO：https://github.com/eembc/coremark-pro
- SciMark 2：https://math.nist.gov/scimark2/
- zstd：https://github.com/facebook/zstd
- zlib：https://github.com/madler/zlib
- LZ4：https://github.com/lz4/lz4
- tinymembench：https://github.com/ssvb/tinymembench（issue #20、PR #21：clang 内置汇编器问题）
- STREAM：https://github.com/jeffhammond/STREAM
- multichase：https://github.com/google/multichase
- SQLite：https://sqlite.org/copyright.html
- pytorch/cpuinfo：https://github.com/pytorch/cpuinfo
- google/cpu_features：https://github.com/google/cpu_features
- Mbed TLS：https://github.com/Mbed-TLS/mbedtls
- libjpeg-turbo：https://github.com/libjpeg-turbo/libjpeg-turbo
- LZMA SDK：https://www.7-zip.org/sdk.html
- AndroBench 论文：http://csl.snu.ac.kr/papers/fce12a.pdf
- glmark2（GPL-3.0，只作交叉验证）：https://github.com/glmark2/glmark2

## 测量相关

- 三星 Galaxy S4 跑分优化事件：https://allthingsd.com/20130730/samsung-busted-boosting-international-galaxy-s4-benchmarks/
- 高通 CPU 电源管理（mpdecision）：https://www.qualcomm.com/news/onq/2013/10/power-vs-performance-management-cpu
- 移动 GPU 着色器精度问题：https://webglfundamentals.org/webgl/lessons/webgl-precision-issues.html
