# 04 GFXBench ES2 移植方案

> 这是整个项目风险最大的部分。本文结论来自 2026-11-12 对 [Kishonti-Opensource/gfxbench](https://github.com/Kishonti-Opensource/gfxbench)（commit `89aa956`）的源码阅读和正则扫描，**还没有实际编译**。文中路径都相对于 GFXBench 仓库根目录。

## 1. 背景

- Kishonti 在 2025 年底停止运营 GFXBench 和 CompuBench，把源码放到 GitHub。代码 BSD-3-Clause，素材（纹理、模型、动画）CC BY 4.0。
- 仓库约 1.7 GB，`gfxbench-data40/`、`gfxbench-data50/` 是场景数据。
- 官方安卓工程：minSdk 21、native API 24、NDK 28、按 ES 3.1 编译，CI 只验证了 arm64。

## 2. 能移植的测试

Android 4.1/4.2 只有 ES 2.0，GFXBench 4.0 里能用的是这些（配置文件在 `gfxbench40/resources/gfxbench_gl/config/`）：

| 测试 ID | 引擎 | 要求 | 时长 | 最低内存 |
|---|---|---|---|---|
| gl_trex、gl_trex_off | Engine2（`GLB_Scene_ES2`） | ES 2.0 | 56 秒 | 160 MB |
| gl_egypt、gl_egypt_off | Engine2 | ES 2.0 | 113 秒 | 160 MB |
| gl_alu、gl_alu_off | alu_test | ES 2.0 | 30 秒 | 50 MB |
| gl_fill、gl_fill_off | compressed_fill_test | ES 2.0 | 30 秒 | 50 MB |
| gl_driver、gl_driver_off | cpu_overhead_test | ES 2.0 | 30 秒 | 50 MB |
| gl_blending、gl_blending_off | ui_test | ES 2.0 | 30 秒 | 50 MB |

`_off` 是 1920×1080 离屏渲染，不受屏幕分辨率和垂直同步影响，不同设备之间可以直接比较，**计分用离屏版**。

不能用的（需要 ES 3.0+）：ALU 2、Fill 2（Texturing）、Driver 2、Manhattan、Manhattan 3.1、Car Chase、Tessellation。以后想给 4.3/4.4 的 ES3 设备加可选测试再考虑。

## 3. 现状：代码在，构建被关掉了

### 3.1 好消息：ES2 代码路径完整

- **场景渲染器**：T-Rex 和 Egypt 用的 `GLB_Scene_ES2` 在 `gfxbench40/src/gfx3_0/high_level_tests_3/opengl/glb_scene_opengl27.cpp`，`Engine2` 在定义了 `OPT_TEST_GFX2` 时创建它（`engine.cpp`）。
- **ES3 调用都有保护**：`common/`、`gfx3_0/` 里的 ES3 函数调用都在 `#if defined HAVE_GLES3 || defined HAVE_GLEW` 之类的条件里。对 ES2 路径上约 290 个文件做了扫描，没有保护的 ES3 函数调用只出现在调试开关（`PER_FRAME_ALU_INFO`、`LOG_SHADERS`、`FBO_CAPTURE`）里，这些开关默认关闭。
- **自动去掉 ES3**：`frameworks/cmake-utils/cmake/FindOGLX.cmake` 在 `ANDROID_NATIVE_API_LEVEL < 18` 时只链接 `-lEGL -lGLESv1_CM -lGLESv2`，不定义 `HAVE_GLES3`。
- **着色器**：`gfxbench40/resources/data/gfx/shaders_40/shaders.20/`（场景）和 `lowlevel/`（底层测试）都是 GLSL ES 1.0，没有 ES3 语法。
- **原生代码的 libc 依赖**：扫描了 testfw、kcl、ngrtl、krl、platform-utils 和 ES2 测试代码，没发现 API 16 缺的函数。Poco Foundation 的 `Mutex_POSIX.cpp` 只在 `_POSIX_TIMEOUTS >= 200112L` 时才用 `pthread_mutex_timedlock`，bionic 在 API 21 以下把这个宏定义为 -1，所以不会编进去。
- **历史成绩佐证**：GFXBench 3.0 榜单里 Mali-400 跑 T-Rex 的成功记录有 2000 多条，Tegra 2、Tegra 3、SGX 540 也都有，说明 ES2 路径当年在这些 GPU 上是能跑的。

### 3.2 坏消息：ES2 构建目标被关掉了

- `gfxbench40/src/tests/40/CMakeLists.txt` 里专门的 ES2 目标 `gfxbench40_gl_es2` 被 `if(FALSE)` 关掉了，它依赖的 `glb_common_es2`（`common/CMakeLists.txt`）和 `gfxbench30_shared_es2`（`gfx3_0/CMakeLists.txt`）也都被 `if(FALSE)` 关掉了。
- 这个目标引用的 `create_factory_gfx27_corporate.cpp` / `create_factory_gfx27_community.cpp` **不在仓库里**。
- 现在唯一的 GL 目标 `gfxbench40_gl` 把 ES2、ES3、ES3.1、GL4 的测试都编在一起，依赖 `gfx4`、`gfx3_1`、`common_31`，这些需要 ES 3.1。
- 构建脚本 `scripts/build.sh` 和 `scripts/build-3rdparty.sh` 把 API 写死为 24。

## 4. 移植方案

### 4.1 构建配置

| 项 | 设置 | 原因 |
|---|---|---|
| `PRODUCT_ID` | `gfxbench_gl` | 不编 Vulkan、ngl |
| `PRODUCT_VERSION` | `4.0.0` | `gfxbench/CMakeLists.txt` 在主版本 ≥ 5 时才编 gfxbench50 |
| 版本类型 | corporate（`OPT_COMMUNITY_BUILD` 关闭） | community 版需要 `netman`（联网模块），仓库里没有；社区版的工厂文件也不在 |
| API 等级 | 16 | 改 `build.sh`、`build-3rdparty.sh` 里写死的 24 |
| `RENDER_API` | 编译时确认是否还需要（不编 ngl 的话应该用不到） | `ngl` 是 GFXBench 5 的图形抽象层；`build.sh` 在安卓上把它设成 `ES31` |
| C++ 运行时 | `c++_static` | 原来是 `c++_shared`；改成静态链接进单个 .so |
| `APPLICATION_TYPE` | 先用 `developer`，后期并进我们的 App | developer 模式自带简单测试程序，方便验证 |

### 4.2 新的 ES2 构建目标

照着被关掉的 `gfxbench40_gl_es2` 写一个新目标（建议放在我们自己的 CMake 工程里，不改 GFXBench 原文件，便于以后对比上游）：

```cmake
# 示意，未验证
add_library(gfxbench_es2 STATIC
    ${SHARED_GFXSOURCES}              # gfxbench/：global_test_environment、status_report、gfxbench.cpp
    ${SRC_COMMON}                     # common/
    ${SRC_HIGH_LEVEL_TESTS_3_0}       # gfx3_0/high_level_tests_3 + es2_common
    ${SRC_HIGH_LEVEL_TESTS_3_0_ENGINE}
    ${SRC_LOW_LEVEL_BASE}             # gfx3_0/low_level_tests（含 opengl/）
    tests/40/corporate/create_factory_trex_corporate.cpp
    tests/40/corporate/create_factory_egypt_corporate.cpp
    tests/40/corporate/create_factory_lowlevel_corporate.cpp
)
target_compile_definitions(gfxbench_es2 PRIVATE
    OPT_TEST_GFX2 USE_ANY_GL HAVE_EGL HAVE_GLES HAVE_GLES2)   # 不定义 OPT_TEST_GFX30/31/40、HAVE_GLES3
```

- 现有的三个 `*_corporate.cpp` 工厂文件直接复用，它们只是用 `CREATE_FACTORY` 宏注册测试。
- `create_factory_trex_corporate.cpp` 还注册了 `gl_trex_battery`、`gl_trex_qmatch`（画质比对）等，用不到可以删掉对应行。
- 不编 `gfx4`、`gfx3_1`、`common_31`、`gui`、`framecapture`、`frame_player`。
- `OPT_TEST_GFX30` 是否要定义需要实际编译时确认：`GLB_Scene_ES3` 在这个宏下，而 `gfx3_0` 的源码里 ES2/ES3 有共用部分。

### 4.3 原生库加载：改用静态工厂表

- `frameworks/testfw/testfw.cpp` 在 Linux/Android 上用 `dlopen("libgfxbench40_gl.so")` + `dlsym("create_test_xxx")` 找测试工厂。这在 4.1/4.2 上有问题（见 [02](02-platform-constraints.md) §5）。
- 同一个文件里有一段给 QNX 写的逻辑（`#if defined(__QNX__)`）：直接 `extern "C"` 声明 `create_test_gl_trex` 等函数，用字符串比较选择，不需要 `dlopen`。
- 改法：把这段逻辑的条件扩展到我们的 Android 构建，函数列表裁剪成只有 ES2 测试（12 个：6 个测试 × 屏上/离屏）。
- 配合 [01](01-architecture.md) §3.1 的单 .so 方案，GFXBench、testfw、我们的测试全部静态链接进 `librmb.so`。

### 4.4 第三方库

| 库 | 版本 | 用途 | 处理 |
|---|---|---|---|
| zlib | 仓库自带 | KCL 读 PNG | 静态链接；和我们 CPU 测试用的 zlib 合并成一份，避免符号冲突 |
| libpng | 1.6.7（仓库自带） | KCL 读纹理 | 静态链接 |
| Poco | 1.5.3 Foundation（仓库自带） | testfw 的 deviceinfo、resourcemanager（`Base64Encoder`、`SHA1Engine`、`FileStream` 等） | 只编 Foundation；其他组件用 `DISABLE_NET` 等选项关掉 |
| libepoxy | 仓库自带 | testfw 的 GL 信息采集 | 检查是否必需；不需要就用 `nullglinfocollector.cpp` 替代 |
| CUDA、OpenCL（clew、cudaw） | — | 设备信息里的 CUDA/OpenCL 采集 | 不编（CMake 里是 `find_package` 可选的） |
| AgilitySDK | — | DirectX 12，和安卓无关 | 从 `build-3rdparty.sh` 的项目列表里去掉 |

注意：Poco Foundation 自带一份 zlib（`deflate.c`、`inflate.c`）和 PCRE。三份 zlib（GFXBench 3rdparty、Poco、我们自己的）静态链接进同一个 .so 会有重复符号，要么只保留一份，要么给其中一份加 `Z_PREFIX`。

### 4.5 Java 宿主

复用 `frameworks/testfw/android/testfw-lib`：它负责创建 `SurfaceView`、EGL 上下文，在单独线程上跑测试（`TestRunner.java`），并把结果传回来。`app_android/benchui-lib`（GFXBench 自己的界面）和 `systeminfo-lib` 不用。

要改的地方：

| 问题 | 位置 | 改法 |
|---|---|---|
| commons-io 2.15（依赖 API 26 的 `java.nio.file`） | `AndroidInfoCollector.java`、`DeviceInfo.java`、`NativeLibraryLoader.java`、`TfwActivity.java` | 换成手写的文件复制、读文件 |
| Gson 2.10.1（只验证到 API 19） | `DeviceInfo.java` | 换成 `org.json` |
| minSdk 21、compileSdk 36 | `app_android/gradle/libs.versions.toml` | 不用它的 Gradle 配置，源码并进我们的工程 |
| `Surface.setFrameRate`（API 30） | `TestRunner.java` | 已经有 `SDK_INT >= 30` 判断，不用改 |
| `Build.SUPPORTED_ABIS`（API 21） | `AndroidInfoCollector.java` | 已经有版本判断，低版本用 `CPU_ABI`/`CPU_ABI2`，不用改 |
| 平台初始化 | `platform-utils` 的 `Platform.setApplicationContext()` | 必须在加载测试前调用，原生层靠它拿 `AssetManager` 和 `Context` |
| Java 绑定 | `testfw.i`（SWIG） | 构建机要装 SWIG（官方测过 3.0.12 和 4.0.2），生成的 Java 文件在 `net.kishonti.swig` 包 |

### 4.6 纹理格式

- 测试配置里 `"texture_type": "Auto"`，由 `gfxbench40/src/common/test_descriptor.cpp` 的 `GetTextureType()` 按设备能力选择：
  - 上下文是 ES 且有 ES3 兼容性 → `ETC2`
  - 否则有 ETC1 扩展 → `ETC1`
  - 否则 PVRTC4 → DXT1 → 888
- 问题：T-Rex 的纹理目录只有 `images_ASTC`、`images_DXT1`、`images_ETC1`、`images_PVRTC4`，Egypt 只有 DXT1、ETC1、PVRTC4，**都没有 ETC2**。在 4.3/4.4 的 ES3 GPU 上，"Auto" 会选 ETC2，找不到纹理。
- 改法：配置里把 `texture_type` 固定为 `"ETC1"`。ETC1 是所有 ES2 设备都支持的格式，也让所有设备跑同一份数据，成绩可比。
- 附带好处：只需要打包 `images_ETC1`，数据量减到原来的约三分之一（见 [06](06-data-packs.md)）。

### 4.7 着色器精度

- `shaders_40/lowlevel/ui.fs`（gl_blending 用）写死了 `precision highp float;`。Mali-400、Tegra 2/3 的片元着色器不支持 highp，会编译失败，gl_blending 在这些 GPU 上跑不了。
- 改法：

```glsl
#ifdef GL_ES
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
#endif
```

- `ui.vs` 也写了 highp，但顶点着色器总是支持 highp，不用改。
- 其他底层测试着色器用 `mediump`。场景着色器默认 `mediump`，`NEED_HIGHP` 只在 ES3 场景（`SV_30`/`SV_31`）里用。
- `force_highp` 配置项默认 `false`，保持不变。

### 4.8 数据读取路径

- `TestRunner.java` 的 `prepareDescriptor()` 把读取路径设为 `<basepath>/data/<data_prefix>/`，`data_prefix` 在配置里是 `gfx`。
- `frameworks/kcl_framework/kcl/platforms/android/src/kcl_io_os.cpp`：先按路径直接打开文件；打不开再从 APK assets 的 `data/gfx/` 下读，**读到后还会写一份到磁盘**（`OS_PreOpen`）。
- 这决定了数据包的两种提供方式怎么实现，见 [06](06-data-packs.md)。

## 5. 验证方法

### 5.1 分阶段

1. **能编译**：NDK r23c、API 16、armeabi-v7a，ES2 目标编过。
2. **能跑底层测试**：developer 模式，配置和数据用 `adb push` 推到 `/sdcard/kishonti/tfw`，在 4.1 真机上跑通 gl_alu_off、gl_fill_off、gl_driver_off、gl_blending_off。
3. **能跑场景**：gl_trex_off、gl_egypt_off 跑通，和历史成绩对照。
4. **三套 ABI**：无 NEON 版在 Tegra 2 上、x86 版在模拟器或 Atom 设备上跑通。
5. **并进 App**：单 .so、静态工厂、我们的界面。

### 5.2 对照历史成绩

[GFXBench_and_CompuBench_results](https://github.com/Kishonti-Opensource/GFXBench_and_CompuBench_results) 是官方榜单快照（每个测试、每种分组取最常见分辨率的最近 100 条结果，给出最大值、中位数、平均值）。非代码内容 CC BY 4.0。

GFXBench 4.0 榜单的"1080p T-Rex Offscreen"中位数（56 秒内渲染的帧数）：

| 机型 | GPU | T-Rex 1080p 离屏 |
|---|---|---|
| Galaxy S III（SC-03E） | Mali-400 MP4 | 362 帧 |
| Nexus 7（2012） | Tegra 3 | 184 帧 |
| Galaxy Tab 10.1 | Tegra 2 | 78 帧 |
| Motorola Xoom | Tegra 2 | 72 帧 |
| Galaxy S III mini | Mali-400 MP1 | 59 帧 |
| Galaxy Nexus | PowerVR SGX540 | 51 帧 |

GFXBench 2.7 榜单还有 Egypt 和 Fill rate 的离屏成绩（中位数）：

| 机型 | Egypt 1080p 离屏 | Fill rate 1080p 离屏 |
|---|---|---|
| Galaxy S III（SC-03E） | 17.5 fps | 802 MTexels/s |
| Nexus 7（2012） | 9.7 fps | 474 MTexels/s |
| Galaxy Tab 10.1（Tegra 2） | 523 帧 ≈ 4.6 fps | 152 MTexels/s |
| Galaxy S III mini | 6.4 fps | 195 MTexels/s |
| Galaxy Nexus | 5.5 fps | 296 MTexels/s |

注意：

- 2.7 的 Fill rate 是旧版测试，和 4.0 的 gl_fill 不一定完全一致，只能粗略对照。
- ALU、Driver、Blending 三个 ES2 底层测试在 3.x/4.0 榜单里没有记录，没有历史数据可对照。
- 同型号成绩相差 ±10% 以内可以认为移植正确；差很多要查纹理格式、分辨率、垂直同步、降频。

## 6. 工作量与风险

- 预计 1–2 周，主要花在重建构建目标、处理三份 zlib 等链接问题、改 Java 宿主。建议限时，超时就重新评估。
- 风险和应对见 [09-roadmap.md](09-roadmap.md)。最坏情况：ES2 路径有没扫描出来的问题，跑不通。备选方案是只用 GFXBench 的着色器和场景数据思路，自己写 ES2 测试；或者参考 glmark2 的测试设计（但 glmark2 是 GPL-3.0，代码不能并进来）。
