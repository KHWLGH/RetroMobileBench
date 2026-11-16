# 开发与构建

本项目为单模块 Android 应用（`:app`），使用系统原生 Java Framework `Activity`/`View`、`org.json`、`SQLite` 与 `Instrumentation` 构建，生产代码不依赖 AndroidX、Jetpack Compose、Kotlin 标准库或 Google Play 服务。测试负载定义见[测试与结果](benchmarks.md)，测试入口与设备验证记录见[验证与限制](validation.md)。

## 架构与生命周期

```text
MainActivity → BenchmarkRunner → BenchmarkSuite.DEFAULT
                                  ├─ JNI：CPU / 内存 / 存储 / SQLite / 加密 / 图片
                                  ├─ MediaClient → :media 一次性子进程
                                  ├─ WebClient   → :web 可见 WebView 一次性子进程（无 JNI 依赖）
                                  └─ GpuClient   → :gpu 一次性子进程
                     ↓
              ResultStore：会话记录与逐轮检查点持久化
```

### 执行与状态管理

- **单工作线程调度**：`BenchmarkRunner` 作为应用级单串行工作线程运行，仅持有 `Application` 上下文。`MainActivity` 通过订阅状态更新界面，常规界面重建不会重复触发测试。
- **检查点与恢复**：会话启动时生成唯一 UUID 并校验解压内置资源。每轮测试完成后同步写入数据库检查点，再更新界面轮次进度；测试全部结束后格式化生成完整报告与 JSON。若应用启动时检测到遗留的 `running` 会话，会自动将其标记为 `interrupted` 并保留已完成的轮次数据。
- **进程隔离与取消机制**：媒体、Web 和 GPU 阶段分别在独立的 `:media`、`:web`、`:gpu` 一次性子进程中执行。父进程通过事件唤醒机制等待结果、取消、进程死亡或超时信号，测量期间不轮询 Binder。用户点击取消后立即反馈并阻止后续阶段启动：CPU 与同步 I/O 在安全检查点退出，媒体、Web 与 GPU 则通知子进程停止并等待进程回收确认。
- **温度采样**：测试期间由单个后台优先级的 `HandlerThread` 每 2 秒监听一次系统 `ACTION_BATTERY_CHANGED` 广播，各阶段共享同一采样流，在阶段结束后汇总生成温度记录。

### 窗口与全屏切换

- **竖屏主控与横屏展示**：主界面固定为竖屏；进入 GPU 屏上、Web 响应与视频播放阶段时，自动跳转至独立的横向全屏 Activity，渲染视图铺满窗口，右上角叠加半透明取消按钮，测试结束后返回竖屏主界面。API 19+ 启用沉浸式模式隐藏系统栏；更早期的系统隐藏标题栏与状态栏。
- **GPU 屏上 HUD**：屏上 GPU 窗口左上角叠加半透明状态层，约每秒读取一次工作进程的窗口缓冲区交换计数与系统电池温度。API 11+ 由工作进程每秒向宿主推送计数，API 8–10 在本地窗口进程内直接更新。
- **前台保持与中断**：测试过程保持屏幕常亮。除主界面与全屏展示窗口之间的受控切换外，切入后台、锁屏或活动 `Surface` 丢失均会触发中断保护；按下返回键或点击取消按钮记为 `cancelled`，按下 Home 键记为 `interrupted`。

## 平台兼容与原生层设计

项目最低兼容 API 8（ARMv7）与 API 9（x86），`targetSdk` 设为 23（满足 Android 14+ 的最低安装要求，同时保持旧系统行为一致），仅打包 32 位原生库。Java 17 语法通过 D8 脱糖，同時将 Lint 的 `NewApi` 与 `InlinedApi` 设为编译错误，高版本系统 API 均按版本分支隔离调用。

### 原生库组织与加载

| 目录 / 范围 | 原生库 | 说明 |
|---|---|---|
| `armeabi-v7a/` | `librmb.so` | 启用 NEON 的 ARMv7 主库（含 CPU、内存、存储及静态链接的 GFXBench 引擎） |
| `armeabi-v7a/` | `librmb_noneon.so` | 以 `VFPv3-D16` 编译的无 NEON 主库，供不支持 NEON 的 ARMv7 设备（如 Tegra 2）使用 |
| `x86/` | `librmb.so` | 原生 x86 主库，避免在 x86 设备上触发 ARM 指令转译 |
| 所有 ABI | `librmbmedia.so`、`libavutil.so`、`libavcodec.so`、`libavformat.so` | 媒体 JNI 库与独立的 FFmpeg 共享库（统一使用标量 C 实现，关闭汇编/SIMD） |

- **NEON 运行时探测**：C++ 运行时采用 `gnustl_static`。`NativeLoader` 在调用 `System.loadLibrary` 前先通过 Java 读取 ABI 与 `/proc/cpuinfo` 中的特性标志，确认支持 NEON 时加载 `librmb.so`，否则加载 `librmb_noneon.so`，从而避免静态初始化阶段触发非法指令崩溃。
- **旧版链接器兼容**：Android 4.3 之前的动态链接器不会自动解析应用私有目录中共享库之间的依赖关系，因此媒体模块按 `avutil` → `avcodec` → `avformat` → `rmbmedia` 的拓扑顺序显式加载。此外，原生代码仅依赖旧版 NDK 提供的 `memalign`、`pthread_mutex` 等基础符号，不使用 `getauxval`、`posix_memalign`、`pthread_barrier` 或 NDK `MediaCodec`。

### 媒体与旧系统窗口适配

- **解码接口分流**：API 16+ 使用 Java 同步 `MediaCodec` 的旧版缓冲区数组接口；API 29+ 的解码器属性查询封装在独立辅助类中，避免旧系统类加载失败。
- **Gingerbread Surface 适配**：Android 2.3（API 8–10）的 `Surface.writeToParcel` 不支持跨进程转发普通窗口创建的 `Surface`。因此，API 8–15 的 `MediaPlayer` 播放与 API 8–10 的屏上 GPU 渲染直接在对应子进程的 Activity 中创建本地窗口（API 8–10 视频使用 `SURFACE_TYPE_PUSH_BUFFERS`）；API 11+ 的 GPU 渲染与 API 16+ 的视频播放则由主进程的 `DisplayActivity` 提供全屏 `Surface`。

### GPU 宿主与 EGL 配置

GFXBench 的 OpenGL ES 2.0 源码清单与自定义宿主位于 `feasibility/gfxbench-es2`，将 `testfw`、`KCL`/`KRL` 及所需第三方库静态编译入主库，通过静态工厂表实例化场景，不依赖动态 `dlopen`、Poco、libepoxy 或 ES3/Vulkan 路径。

- **EGL 上下文管理**：由 Java `EGL10` 宿主创建上下文，仅链接 `GLESv2`。离屏模式使用小尺寸 pbuffer 与 1080p FBO，保留驱动首选配置；屏上模式遍历 `eglChooseConfig` 结果，严格筛选 `RGB 8/8/8`、`alpha = 0`、`samples = 0`、`depth >= 16` 的不透明窗口配置（修复部分 PowerVR SGX544 驱动因默认选中带 Alpha 通道配置而引发的画面花屏问题，详见[验证与限制](validation.md#sgx544-屏上花屏修复)）。
- **着色器与纹理适配**：场景纹理统一采用 ETC1 格式；打包时自动为 `lowlevel/ui.fs` 注入基于 `GL_FRAGMENT_PRECISION_HIGH` 的 `highp` / `mediump` 片元精度回退逻辑，以兼容仅支持 `mediump` 片元着色的早期 ES 2.0 GPU。

相关参考：[Android NDK C++ 库支持说明](https://developer.android.com/ndk/guides/cpp-support)、[Android 2.3.7 Surface JNI](https://android.googlesource.com/platform/frameworks/base/+/android-2.3.7_r1/core/jni/android_view_Surface.cpp)、[GFXBench 开源仓库](https://github.com/Kishonti-Opensource/gfxbench)。

## 第三方依赖

项目锁定所有第三方组件的源码版本或 Git 提交。开源许可与署名要求见[许可与分发](licenses.md)。

| 组件 | 上游仓库 / 来源 | 锁定版本 / 提交 |
|---|---|---|
| zlib | https://github.com/madler/zlib | 1.3.1 / `51b7f2abdade71cd9bb0e7a373ef2610ec6f9daf` |
| Zstd | https://github.com/facebook/zstd | 1.5.7 / `f8745da6ff1ad1e7bab384bd1f9d742439278e99` |
| CoreMark | https://github.com/eembc/coremark | 1.0 / `1f483d5b8316753a742cbf5590caf5bd0a4e4777` |
| tinymembench | https://github.com/ssvb/tinymembench | `a2cf6d7e382e3aea1eb39173174d9fa28cad15f3` |
| STREAM | https://github.com/jeffhammond/STREAM | `6703f7504a38a8da96b353cadafa64d3c2d7a2d3` |
| GFXBench | https://github.com/Kishonti-Opensource/gfxbench | `89aa956f43836798d1eaaab8ec32d3dc8b02eead` |
| SQLite | https://www.sqlite.org/2026/sqlite-amalgamation-3530100.zip | 3.53.1（SHA-256: `36ad6e7f...6827eae`） |
| SciMark | https://math.nist.gov/scimark2/scimark2c.zip | 2.0 C（SHA-256: `53d6bffc...210b81ff`） |
| Mbed TLS | https://github.com/Mbed-TLS/mbedtls | 2.28.10 / `2fc8413bfcb51354c8e679141b17b3f1a5942561` |
| libjpeg-turbo | https://github.com/libjpeg-turbo/libjpeg-turbo | 2.1.5.1 / `8ecba3647edb6dd940463fedf38ca33a8e2a73d1` |
| libpng | https://github.com/pnggroup/libpng | 1.6.43 / `ed217e3e601d8e462f7fd1e04bed43ac42212429` |
| FFmpeg | https://github.com/FFmpeg/FFmpeg | 4.4.6 / `44b04492bfc83215e136f2a68783bff71d328692` |

SQLite 与 SciMark 源码直接包含在仓库内，其余组件通过 Git 子模块管理。初始化子模块与 GFXBench 稀疏检出（避免拉取不需要的纹理格式）：

```powershell
git submodule update --init third_party/zlib third_party/zstd third_party/coremark third_party/tinymembench third_party/stream third_party/mbedtls third_party/libjpeg-turbo third_party/libpng third_party/ffmpeg

# 若 third_party/gfxbench 尚未初始化，使用稀疏检出拉取所需目录：
git clone --filter=blob:none --no-checkout https://github.com/Kishonti-Opensource/gfxbench.git third_party/gfxbench
git -C third_party/gfxbench sparse-checkout init --cone
git -C third_party/gfxbench sparse-checkout set frameworks gfxbench40 3rdparty/zlib 3rdparty/libpng gfxbench-data40/data/gfx/common gfxbench-data40/data/gfx/fill gfxbench-data40/data/gfx/trex gfxbench-data40/data/gfx/egypt
git -C third_party/gfxbench checkout --detach 89aa956f43836798d1eaaab8ec32d3dc8b02eead
git submodule absorbgitdirs third_party/gfxbench
```

本地适配均集中在项目的移植层或构建脚本中：CoreMark 仅定制 `core_portme`；CPU 测试所用的 zlib 开启 `Z_PREFIX` 与符号重命名，与 GFXBench 内部旧版 zlib 隔离；SciMark 补充了内存分配失败检查；Mbed TLS 仅编译 AES/GCM/SHA 模块；图片库与 FFmpeg 的编译裁剪配置分别位于 `native/media.cmake` 和 `scripts/build-ffmpeg.sh`。

## 工具链与构建流程

### 环境要求

| 工具组件 | 版本 / 用途说明 |
|---|---|
| Android Studio JBR、AGP 9.5.1、Gradle 9.7.0 | Java 编译与 Android 应用打包 |
| Android SDK 37 | 提供 `compileSdk 37`（`minSdk 8`、`targetSdk 23`） |
| Android NDK r10e（GCC 4.9 / `gnustl_static`） | 编译所有生产环境 C/C++ 原生代码（ARM API 8、x86 API 9） |
| CMake 3.18.1 / Ninja | 构建主原生库与静态依赖 |
| Android NDK r23c（23.2.8568313） | 供 AGP 处理符号剥离、APK 打包及 ELF 静态检查 |
| Git Bash | 运行 FFmpeg 的 `configure` 与 `make` 构建脚本 |

### 构建步骤

1. 按上文说明初始化 `third_party` 子模块及 GFXBench 场景数据。
2. 在 Android SDK 中安装 SDK Platform 37、CMake 3.18.1 和 NDK 23.2.8568313，并确认 `local.properties` 配置正确。
3. 准备 NDK r10e：可将官方 Windows 64 位 NDK r10e 解压至 `.tools/android-ndk-r10e`，或运行 `:app:prepareLegacyNdk` 任务自动分段下载（约 1 GB）并校验 SHA-256。
4. 执行构建：

```powershell
$env:JAVA_HOME = 'C:/Program Files/Android/Android Studio/jbr'
.\gradlew.bat :app:prepareLegacyNdk --console=plain
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain
```

> 若 Git Bash 未安装在默认的 `C:/Program Files/Git/bin/bash.exe`，可通过 `-PgitBash=<路径>` 指定。构建过程中，`buildFfmpeg` 会率先编译 FFmpeg 动态库，随后 `buildLegacyNative` 调用 NDK r10e 编译主库与媒体 JNI 库并汇总至生成的 JNI 目录。

### 主要构建产物

| 产物 | 输出路径 |
|---|---|
| Debug APK | `app/build/outputs/apk/debug/app-debug.apk` |
| Instrumentation 测试 APK | `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` |
| Release 未签名 APK | `app/build/outputs/apk/release/app-release-unsigned.apk` |
| 生成的原生库目录 | `app/build/generated/legacyJniLibs/<abi>/` |
| 匹配的对应源码归档 | `app/build/distributions/RetroMobileBench-corresponding-source-fixed-suite-v3.zip` |

通过 ADB 安装并启动应用（连接多台设备时请将 `emulator-5554` 替换为目标设备序列号）：

```powershell
adb devices
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am start -n com.retromobilebench/.MainActivity
```

## 内置资源打包与校验

- **GPU 场景包（`generateGpuPack`）**：构建时自动筛选 `gl_egypt_off`、`gl_egypt`、`gl_trex_off`、`gl_trex`、`gl_fill_off` 的 ETC1 纹理与着色器，按固定文件顺序与零时间戳生成包含文件大小和 SHA-256 的清单，并以不压缩（`STORED`）方式打包为 `gpu-data.zip`。应用首次运行时将其解压至私有暂存目录，逐一校验哈希后原子提交（设置 180 秒准备时限，并检查是否满足 Egypt/T-Rex 192 MiB、Fill 128 MiB 的最低可用内存要求）。
- **媒体语料（`assets/media`）**：图片与视频素材清单位于 [`manifest.json`](../app/src/main/assets/media/manifest.json)。运行时视频文件经哈希校验后缓存在内部私有目录；图片测试的参考像素通过原生层流式读取校验，避免在 Java 堆上分配大数组。`scripts/generate-media.ps1` 与 `scripts/generate-jpeg-references.ps1` 为素材生成脚本，日常构建无需重新运行。
- **Web 语料（`generateWebCorpus`）**：构建时使用当前 JBR 的 `ImageIO` 及确定性随机数算法生成合成图片、文本与 JSON 语料，并与 `app/web-corpus-lock.json` 核对文件大小和 SHA-256，确保不同机器生成的测试资源完全一致。

## 清理与调试提示

- **清理构建产物**：日常可执行 `.\gradlew.bat clean --console=plain`。如需彻底清理，可先停止 Gradle Daemon，再删除 `app/build/`、`build/`、`.gradle/` 和 `feasibility/out/`。请注意保留 `.tools/` 目录下的 NDK r10e 工具链与主机侧辅助工具。
- **开发期诊断选项**：构建时传入 `-PgpuData=diagnostic` 可生成仅含轻量级场景的诊断包以加快开发调试。`scripts/feasibility-gfxbench-*.ps1` 脚本用于早期独立移植验证。
- **日志排查**：可通过 `adb logcat -s RMB:* RMB-GFX:* _logcat_:* DEBUG:*` 查看应用宿主、GFXBench 引擎及原生崩溃日志。在早期 Android 系统上运行 `adb shell` 命令时，建议根据日志中的实际 `PASS`/`FAIL` 标记判断结果，无需依赖旧版 `adbd` 的返回退出码。
