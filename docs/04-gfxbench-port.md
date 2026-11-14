# 04 GFXBench ES2 移植方案（历史技术记录）

> 2026-11-14：下文保留早期移植、六项离屏和r23c的历史证据，不作为当前产品配置。当前固定为Egypt/T-Rex各屏上与离屏，加Fill离屏，共398秒；主库实际使用r10e，默认完整离线资源，无单项/导入入口。窗口EGLSurface与交换缓冲区已接入，验证状态见 [13](13-suite-implementation.md)，当前范围见 [12](12-test-suite-plan.md)。

> 这是整个项目风险最大的部分。本文最初的结论来自 2026-11-12 对 [Kishonti-Opensource/gfxbench](https://github.com/Kishonti-Opensource/gfxbench)（commit `89aa956`）的源码阅读和正则扫描。**同日已实际编译并在 Android 4.1.2 模拟器上跑通全部 6 个 ES2 离屏测试**，本文已按实测结果修订，验证经过和成绩见 [10-feasibility.md](10-feasibility.md)。文中路径都相对于 GFXBench 仓库根目录。

## 1. 背景

- Kishonti 在 2025 年底停止运营 GFXBench 和 CompuBench，把源码放到 GitHub。代码 BSD-3-Clause，素材（纹理、模型、动画）CC BY 4.0。
- 仓库约 1.7 GB，`gfxbench-data40/`、`gfxbench-data50/` 是场景数据。
- 官方安卓工程：minSdk 21、native API 24、NDK 28、按 ES 3.1 编译，CI 只验证了 arm64。

## 2. 能移植的测试

Android 4.1/4.2 只有 ES 2.0，GFXBench 4.0 里能用的是这些（配置文件在 `gfxbench40/resources/gfxbench_gl/config/`）：

| 测试 ID | 引擎 | 要求 | 时长（屏上 / 离屏） | 最低内存 |
|---|---|---|---|---|
| gl_trex、gl_trex_off | Engine2（`GLB_Scene_ES2`） | ES 2.0 | 56 秒 | 160 MB |
| gl_egypt、gl_egypt_off | Engine2 | ES 2.0 | 113 秒 | 160 MB |
| gl_alu、gl_alu_off | alu_test | ES 2.0 | 30 / 60 秒 | 50 MB |
| gl_fill、gl_fill_off | compressed_fill_test | ES 2.0 | 30 / 60 秒 | 50 MB |
| gl_driver、gl_driver_off | cpu_overhead_test | ES 2.0 | 30 / 60 秒 | 50 MB |
| gl_blending、gl_blending_off | ui_test | ES 2.0 | 30 / 60 秒 | 100 MB |

离屏版六项按配置跑完约 7.5 分钟（4 × 60 + 56 + 113 秒，加上加载）。

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

> 4.1–4.5 是实测后的做法（`feasibility/gfxbench-es2/`）。和调研时的计划不同的地方都标了"（已改）"。

### 4.1 构建方式（已改）

调研时打算改 GFXBench 自己的构建脚本（`scripts/build.sh`：改写死的 API 24、关掉 CUDA、`APPLICATION_TYPE=developer`）。实际**完全不用它的构建脚本**，自己写一个 CMake 工程（`feasibility/gfxbench-es2/CMakeLists.txt`），直接列出 ES2 测试需要的源文件：

- 不改 GFXBench 的任何文件，源码树保持和上游一致（GFXBench 目录通过 `-DGFXBENCH_DIR` 传入），以后对比上游、升级都方便。
- 不需要 `PRODUCT_ID`、`RENDER_API`、`APPLICATION_TYPE` 这些构建脚本的变量，也不会把 ES3/Vulkan 部分带进来。
- 单个 .so，`c++_static`，`-Wl,--no-undefined` 链接通过（说明没有漏掉的符号）。
- 编译选项 `-w -fno-strict-aliasing`、C++11，和 GFXBench 的 `compiler_flags.cmake` 一致（去掉了 `-pedantic-errors`）。

### 4.2 ES2 构建目标（已验证）

| 静态库 | 内容 |
|---|---|
| `gfx_zlib`、`gfx_png` | GFXBench 自带的 zlib、libpng 1.6.7（`PNG_ARM_NEON_OPT=0`，和官方 CMake 一样关掉 libpng 的 NEON 代码） |
| `gfx_ngrtl` | ngrtl core：日志、JSON、流（`NGRTL_STATIC`） |
| `gfx_platform` | `platform-utils` 的 `platform_android.cpp`、`battery.cpp` |
| `gfx_kcl` | KCL 场景/图片/IO + Android 的 `kcl_os`、`kcl_io_os` + 自带的 libetc1、tinyxml、tinyxml2、libogg/theora |
| `gfx_krl` | `krl_material`、`krl_scene`、`krl_scene_reload`、`krl_mesh` |
| `gfx_testfw` | `testfw.cpp`、`graphics/eglgraphicscontext.cpp`、`schemas/*.cpp` |
| `gfxbench_es2` | `common/`（含 `opengl/`）、`gfx3_0/high_level_tests_3`（含 `opengl/`）、`es2_common/`、`gfx3_0/low_level_tests`（含 `opengl/`）、`gfxbench/` 前端、三个 `*_corporate.cpp` 工厂文件 |

宏定义：

```cmake
# 所有 GFXBench 库
USE_ANY_GL HAVE_EGL HAVE_GLES HAVE_GLES2 GFXB_DEPRECATED DISPLAY_PROTOCOL_ANDROID=1
# gfxbench_es2 再加
OPT_TEST_GFX2 PRODUCT_VERSION=4.0.0
# 不定义 OPT_TEST_GFX30/31/40、HAVE_GLES3
```

- **`OPT_TEST_GFX30` 不需要**（调研时的待确认项）：只定义 `OPT_TEST_GFX2`，`Engine2` 就会创建 `GLB_Scene_ES2`，T-Rex、Egypt 都能跑。
- **不需要 Poco、libepoxy**（调研时的待确认项）：testfw 只编核心和 schemas，不编 deviceinfo、resourcemanager，这两个库用不到。也就没有"三份 zlib 符号冲突"的问题，只剩 GFXBench 自带的一份（以后和 CPU 测试的 zlib 合并时再处理）。
- 链接时 `gfxbench_es2` 要用 `-Wl,--whole-archive`，否则工厂函数（只被字符串查找引用）会被链接器丢掉。
- 还要编 `gfxbench/gfxbench_ios_rawconfig.cpp`：`gfxbench.cpp` 无条件调用 `ParseIOSOverrideRawconfig`，函数体在非 iOS 平台上是空的。
- 编译只有一个文件报错：`common/opengl/glb_texture.cpp` 用到 `GL_TEXTURE_WRAP_R`。NDK 的 `gl2ext.h` 定义了 `GL_TEXTURE_3D`（`GL_OES_texture_3D`），让这段 3D 纹理代码被编进来，但没定义 `GL_TEXTURE_WRAP_R`。用强制包含的兼容头文件 `compat/rmb_gles2_compat.h` 补上这个常量（`0x8072`），只对 C++ 生效。ES2 测试不用 3D 纹理，运行时走不到这里。
- 产物大小（Release，未裁剪符号）：armeabi-v7a 约 17.4 MB，x86 约 19.3 MB。导入符号只有 `libEGL`/`libGLESv2` 的 ES2 函数，`DT_NEEDED` 是 EGL、GLESv2、log、android、dl、m、c。

### 4.3 原生库加载：静态工厂表（已改）

- `frameworks/testfw/testfw.cpp` 在 Linux/Android 上用 `dlopen("libgfxbench40_gl.so")` + `dlsym("create_test_xxx")` 找测试工厂。这在 4.1/4.2 上有问题（见 [02](02-platform-constraints.md) §5）。
- 调研时打算扩展 `testfw.cpp` 里给 QNX 写的那段逻辑。实际做法更简单：**宿主代码自己调工厂函数**，不经过 testfw 的查找。`feasibility/gfxbench-es2/probe_main.cpp` 里有一张 12 项的表（`gl_trex`、`gl_trex_off` …… `gl_blending_off`），按测试 ID 找到 `create_test_gl_xxx()`，拿到 `tfw::TestBase*`，然后 `setConfig()` → `setGraphicsContext()` → `init()` → `run()` → `result()`。
- 配合 [01](01-architecture.md) §3.1 的单 .so 方案，GFXBench、testfw、我们的测试全部静态链接进 `librmb.so`。

### 4.4 第三方库

| 库 | 用途 | 处理 |
|---|---|---|
| zlib（仓库自带） | KCL 读 PNG | 静态链接；以后和 CPU 测试用的 zlib 合并成一份 |
| libpng 1.6.7（仓库自带） | KCL 读纹理 | 静态链接，关掉 NEON 优化 |
| Poco Foundation | testfw 的 deviceinfo、resourcemanager | **不编**（这两部分用不到） |
| libepoxy | testfw 的 GL 信息采集 | **不编**（同上） |
| CUDA、OpenCL、AgilitySDK | 设备信息、DirectX 12 | 不编 |

### 4.5 宿主（已改：不用 GFXBench 的 Java 宿主）

调研时打算复用 `frameworks/testfw/android/testfw-lib` 的 Java 宿主（`TestRunner.java` 等），为此要替换 commons-io、Gson，还要装 SWIG 生成 Java 绑定。实际**在原生层自己做宿主**，几十行代码，这些改动全都不需要了：

| 原来由 Java 宿主做的事 | 现在的做法 |
|---|---|
| 读测试配置 JSON，填读写路径、分辨率 | `tfw::Descriptor::fromJsonString()`，再用 `env().setReadPath()` 等填，`setRawConfig("texture_type", "ETC1")` 固定纹理格式 |
| 创建 EGL 上下文 | `RmbPbufferContext`（`rmb_egl_context.cpp`）：ES2 + pbuffer，见下 |
| 按名字找测试工厂（`dlopen`） | 静态工厂表（§4.3） |
| 跑测试、取结果 | 直接调 `TestBase` 的 `init()`/`run()`/`result()`，结果是 JSON 字符串 |
| `Platform.setApplicationContext()` | 只在需要从 APK assets 读数据时才要（`kcl_io_os.cpp` 的回退路径）；数据放在文件系统上时不需要，实测不调用也能跑 |

两个入口共用这套代码：

- `gfxprobe`：命令行程序，`adb shell` 直接跑，不需要 App。验证阶段一直用它。
- JNI 函数 `Java_com_retromobilebench_GfxProbe_runTest`：给 App 用（M4 接入）。

**为什么不用 testfw 的 `EGLGraphicsContext::initPBufferSurface()`**：它把值为 0 的 `EGLConfig` 当成"没找到配置"（`eglgraphicscontext.cpp` 里有三处 `if (config_ == 0)`）。但 `EGLConfig` 是不透明句柄，Android 4.1 模拟器的 EGL 用下标当句柄，第一个配置的句柄**就是 0**：`eglChooseConfig` 返回成功、`num_config = 1`、config = 0x0，testfw 却报失败。`RmbPbufferContext` 只看 `eglChooseConfig` 返回的数量，不看句柄值。真机驱动有没有同样的情况不确定，自己的实现两种都能处理。

`RmbPbufferContext` 继承 `EGLGraphicsContext`（而不是直接继承 `GraphicsContext`），因为 `gfxbench.cpp` 在 Android 上会把上下文强转成 `EGLGraphicsContext*` 读 `selectedConfig()`。父类部分保持默认构造（不持有 EGL 对象），读到的值是 0，析构时什么也不做。

`swapBuffers()` 里调用 `glFinish()`。离屏模式下 GFXBench 只在 100 帧的预览拼图攒满时才调用 `swapBuffers()`（其余帧只 `glFlush()`），所以这里相当于定期等 GPU 做完，和真实窗口交换缓冲区时的反压效果类似，防止 CPU 无限制地往驱动里堆命令。testfw 原来的 pbuffer 路径用的是 `glFlush()`。

### 4.6 纹理格式

- 测试配置里 `"texture_type": "Auto"`，由 `gfxbench40/src/common/test_descriptor.cpp` 的 `GetTextureType()` 按设备能力选择：
  - 上下文是 ES 且有 ES3 兼容性 → `ETC2`
  - 否则有 ETC1 扩展 → `ETC1`
  - 否则 PVRTC4 → DXT1 → 888
- 问题：T-Rex 的纹理目录只有 `images_ASTC`、`images_DXT1`、`images_ETC1`、`images_PVRTC4`，Egypt 只有 DXT1、ETC1、PVRTC4，**都没有 ETC2**。在 4.3/4.4 的 ES3 GPU 上，"Auto" 会选 ETC2，找不到纹理。
- 改法：宿主里 `setRawConfig("texture_type", "ETC1")`，对所有测试固定为 ETC1（已实现，不改配置文件）。ETC1 是所有 ES2 设备都支持的格式，也让所有设备跑同一份数据，成绩可比。
  - gl_blending 的官方配置写的是 `888`，但它的纹理是代码生成的（`blending_base.cpp` 的 `generateImage()`），不读这个设置，固定成 ETC1 对它没有影响。
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
- 现状：`scripts/feasibility-gfxbench-run.ps1` 准备数据时自动打这个补丁（不改 GFXBench 源码树）。模拟器支持 highp，打补丁前后 gl_blending 都能跑；不支持 highp 的 GPU 上是否真的需要它，要在 Mali-400 或 Tegra 2/3 真机上确认。

### 4.8 数据读取路径

- GFXBench 的 Java 宿主（`TestRunner.java` 的 `prepareDescriptor()`）把读取路径设为 `<basepath>/data/<data_prefix>/`，`data_prefix` 在配置里是 `gfx`。我们的宿主直接把读取路径设成数据目录本身（如 `/data/local/tmp/rmb/data/gfx/`），里面放 `shaders_40/`、`common/`、`fill/`、`trex/`、`egypt/`。
- `frameworks/kcl_framework/kcl/platforms/android/src/kcl_io_os.cpp`：先按路径直接打开文件；打不开再从 APK assets 的 `data/gfx/` 下读，**读到后还会写一份到磁盘**（`OS_PreOpen`）。
- 这决定了数据包的两种提供方式怎么实现，见 [06](06-data-packs.md)。
- 着色器在源码树的 `gfxbench40/resources/data/gfx/shaders_40/`，不在场景数据目录里。ES2 只需要 `lowlevel/`（9 个文件）和 `shaders.20/`（29 个文件），共约 50 KB。

## 5. 验证方法

### 5.1 分阶段

| 阶段 | 内容 | 状态（2026-11-12） |
|---|---|---|
| 1. 能编译 | NDK r23c、API 16，armeabi-v7a（NEON / 无 NEON）和 x86 | **完成** |
| 2. 能跑底层测试 | gl_alu_off、gl_fill_off、gl_driver_off、gl_blending_off | **模拟器完成**，真机待测 |
| 3. 能跑场景 | gl_trex_off、gl_egypt_off 跑通，和历史成绩对照 | **模拟器跑通、画面正确**，历史成绩对照要真机 |
| 4. 三套 ABI | 无 NEON 版在 Tegra 2 上、x86 版在模拟器或 Atom 设备上跑通 | x86 完成；ARM 两套只验证了编译和 NEON 指令数 |
| 5. 并进 App | 单 .so、静态工厂、我们的界面 | 未开始（M4） |

真机验证用 `scripts/feasibility-gfxbench-run.ps1`：连上设备直接运行，脚本会按 CPU 选 NEON / 无 NEON 版、推数据、跑全部六项、汇总成绩（见 [10-feasibility.md](10-feasibility.md)）。

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

- 调研时估计 1–2 周。实际：编译只遇到 2 个问题（`GL_TEXTURE_WRAP_R`、漏编 `gfxbench_ios_rawconfig.cpp`），运行只遇到 1 个（`EGLConfig` 句柄为 0），一天内在模拟器上跑通。Java 宿主、SWIG、Poco、三份 zlib 这些预计的工作量都省掉了。
- 剩下的风险在真机上：老 GPU 驱动的兼容性（着色器精度、驱动 bug）、成绩是否和历史数据一致。风险和应对见 [09-roadmap.md](09-roadmap.md)。
- 备选方案（只在真机上发现无法解决的问题时才需要）：只用 GFXBench 的着色器和场景数据思路，自己写 ES2 测试；或者参考 glmark2 的测试设计（但 glmark2 是 GPL-3.0，代码不能并进来）。
