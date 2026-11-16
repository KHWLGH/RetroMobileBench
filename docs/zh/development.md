# 开发与构建

本项目为单模块 Android 应用（`:app`），使用系统原生 Java Framework `Activity`/`View`、`org.json`、`SQLite` 与 `Instrumentation` 构建，生产代码不依赖 AndroidX、Jetpack Compose、Kotlin 标准库或 Google Play 服务。测试负载定义见[测试与结果](benchmarks.md)，测试入口与设备验证记录见[验证与限制](validation.md)。

## 架构与生命周期

```text
MainActivity → BenchmarkRunner → BenchmarkSuite.DEFAULT (12 阶段 fixed-suite-v1)
                                  ├─ JNI：CoreMark / SciMark 2.0 / 内存 / 存储 / SQLite
                                  ├─ WebClient   → :web 竖屏全屏 WebView 一次性子进程（信息流 + 高清大图解码）
                                  ├─ MediaClient → :media 一次性子进程（7 段视频 × 三路径）
                                  └─ GpuClient   → :gpu 一次性子进程（Egypt HD / T-Rex / Fill）
                     ↓
              ResultStore：会话记录与逐轮检查点持久化
```

### 执行与状态管理

- **单工作线程调度**：`BenchmarkRunner` 作为应用级单串行工作线程运行，仅持有 `Application` 上下文。`MainActivity` 通过订阅状态更新界面，常规界面重建不会重复触发测试。
- **双语本地化与统计说明**：`I18n` 管理 `auto` / `zh` / `en` 三种界面语言模式，应用界面、测试报告详情以及 `.csv` / `.txt` 导出文件均随语言设置切换（保留英文 JSON 原始键名与 `fixed-suite-v1` 协议标识符）。“更多”页直接平铺列出语言切换、测试套件说明（含结果统计规则说明）、底层硬件探针、内置开源项目、开源许可协议全文与关于软件（含项目 GitHub 链接）。
- **检查点与恢复**：会话启动时生成唯一 UUID 并校验解压内置资源。每轮测试完成后同步写入数据库检查点，再更新界面轮次进度；测试全部结束后格式化生成完整报告与 JSON。若应用启动时检测到遗留的 `running` 会话，会自动将其标记为 `interrupted` 并保留已完成的轮次数据。
- **进程隔离与取消机制**：媒体、Web 和 GPU 阶段分别在独立的 `:media`、`:web`、`:gpu` 一次性子进程中执行。父进程通过事件唤醒机制等待结果、取消、进程死亡或超时信号，测量期间不轮询 Binder。用户点击取消后立即反馈并阻止后续阶段启动：CPU 与同步 I/O 在安全检查点退出，媒体、Web 与 GPU 则通知子进程停止并等待进程回收确认。
- **温升与电量采样**：测试期间由单个后台优先级的 `HandlerThread`（`TemperatureTrace`）每 2 秒读取一次系统 `ACTION_BATTERY_CHANGED` 广播，记录电池温度、剩余电量百分比、电压、供电状态与当前阶段名，各阶段共享同一采样流，在结束后汇总并在报告详情中通过 `TelemetryCurveView` 绘制为双曲线图表。
- **多系统文件导出与分享**：`ReportExporter` 负责将完整报告（含全部轮次明细、硬件环境与完整遥测样本）无损生成 `.json`、UTF-8 BOM `.csv` 或 `.txt`；`ExportManager` 与 `ExportFileProvider` 负责分级适配导出（API 8–28 写入公共 `Download/RetroMobileBench/` 并通知媒体扫描，API 29+ 写入 `MediaStore.Downloads`，API 19+ 支持 `ACTION_CREATE_DOCUMENT` 另存为；系统分享在 API 8–23 外部文件使用 `file://`，API 24+ 或内部缓存使用 `content://`）。

### 窗口与全屏切换

- **竖屏主控与场景化全屏展示**：主界面与 Web 竖屏信息流测试（`WebBenchmarkActivity`）固定为竖屏全屏；进入 GPU 屏上与视频播放阶段时，自动跳转至独立的横向全屏 Activity，渲染视图铺满窗口，右上角叠加半透明取消按钮，测试结束后返回竖屏主界面。API 19+ 启用沉浸式模式隐藏系统栏；更早期的系统隐藏标题栏与状态栏。
- **GPU 屏上 HUD**：屏上 GPU 窗口左上角叠加半透明状态层，约每秒读取一次工作进程的窗口缓冲区交换计数与系统电池温度。API 11+ 由工作进程每秒向宿主推送计数，API 8–10 在本地窗口进程内直接更新。
- **前台保持与中断**：测试过程保持屏幕常亮。除主界面与全屏展示窗口之间的受控切换外，切入后台、锁屏或活动 `Surface` 丢失均会触发中断保护；按下返回键或点击取消按钮记为 `cancelled`，按下 Home 键记为 `interrupted`。

## 平台兼容与原生层设计

项目最低兼容 API 8（ARMv7）与 API 9（x86），`targetSdk` 设为 23（满足 Android 14+ 的最低安装要求，同时保持旧系统行为一致），仅打包 32 位原生库。Java 17 语法通过 D8 脱糖，同时将 Lint 的 `NewApi` 与 `InlinedApi` 设为编译错误，高版本系统 API 均按版本分支隔离调用。

### 原生库组织与加载

| 目录 / 范围 | 原生库 | 说明 |
|---|---|---|
| `armeabi-v7a/` | `librmb.so` | 启用 NEON 的 ARMv7 主库（含 CoreMark、SciMark 2.0、内存、存储、SQLite 及静态链接的 GFXBench 引擎） |
| `armeabi-v7a/` | `librmb_noneon.so` | 以 `VFPv3-D16` 编译的无 NEON 主库，供不支持 NEON 的 ARMv7 设备（如 Tegra 2）使用 |
| `x86/` | `librmb.so` | 原生 x86 主库，避免在 x86 设备上触发 ARM 指令转译 |
| 所有 ABI | `librmbmedia.so`、`libavutil.so`、`libavcodec.so`、`libavformat.so` | 媒体 JNI 库与独立的 FFmpeg 共享库（统一使用标量 C 实现，关闭汇编/SIMD） |

- **NEON 运行时探测**：C++ 运行时采用 `gnustl_static`。`NativeLoader` 在调用 `System.loadLibrary` 前先通过 Java 读取 ABI 与 `/proc/cpuinfo` 中的特性标志，确认支持 NEON 时加载 `librmb.so`，否则加载 `librmb_noneon.so`，从而避免静态初始化阶段触发非法指令崩溃。
- **旧版链接器兼容**：Android 4.3 之前的动态链接器不会自动解析应用私有目录中共享库之间的依赖关系，因此媒体模块按 `avutil` → `avcodec` → `avformat` → `rmbmedia` 的拓扑顺序显式加载。此外，原生代码仅依赖旧版 NDK 提供的 `memalign`、`pthread_mutex` 等基础符号，不使用 `getauxval`、`posix_memalign`、`pthread_barrier` 或 NDK `MediaCodec`。

## 第三方依赖（共 7 项）

项目锁定所有第三方组件的源码版本或 Git 提交。开源许可与署名要求见[许可与分发](licenses.md)。

| 组件 | 上游仓库 / 来源 | 锁定版本 / 提交 |
|---|---|---|
| CoreMark | https://github.com/eembc/coremark | 1.0 / `1f483d5b8316753a742cbf5590caf5bd0a4e4777` |
| SciMark | https://math.nist.gov/scimark2/scimark2c.zip | 2.0 C（SHA-256: `53d6bffc...210b81ff`） |
| tinymembench | https://github.com/ssvb/tinymembench | `a2cf6d7e382e3aea1eb39173174d9fa28cad15f3` |
| STREAM | https://github.com/jeffhammond/STREAM | `6703f7504a38a8da96b353cadafa64d3c2d7a2d3` |
| SQLite | https://www.sqlite.org/2026/sqlite-amalgamation-3530100.zip | 3.53.1（SHA-256: `36ad6e7f...6827eae`） |
| GFXBench | https://github.com/Kishonti-Opensource/gfxbench | `89aa956f43836798d1eaaab8ec32d3dc8b02eead` |
| FFmpeg | https://github.com/FFmpeg/FFmpeg | 4.4.6 / `44b04492bfc83215e136f2a68783bff71d328692` |

SQLite 与 SciMark 源码直接包含在仓库内，其余 5 项组件通过 Git 子模块管理。初始化子模块与 GFXBench 稀疏检出（避免拉取不需要的纹理格式）：

```powershell
git submodule update --init third_party/coremark third_party/tinymembench third_party/stream third_party/ffmpeg

# 若 third_party/gfxbench 尚未初始化，使用稀疏检出拉取所需目录：
git clone --filter=blob:none --no-checkout https://github.com/Kishonti-Opensource/gfxbench.git third_party/gfxbench
git -C third_party/gfxbench sparse-checkout init --cone
git -C third_party/gfxbench sparse-checkout set frameworks gfxbench40 3rdparty/zlib 3rdparty/libpng gfxbench-data40/data/gfx/common gfxbench-data40/data/gfx/fill gfxbench-data40/data/gfx/trex gfxbench-data40/data/gfx/egypt
git -C third_party/gfxbench checkout --detach 89aa956f43836798d1eaaab8ec32d3dc8b02eead
git submodule absorbgitdirs third_party/gfxbench
```

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

```powershell
$env:JAVA_HOME = 'C:/Program Files/Android/Android Studio/jbr'
.\gradlew.bat :app:prepareLegacyNdk --console=plain
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain
```

### 主要构建产物

| 产物 | 输出路径 |
|---|---|
| Debug APK | `app/build/outputs/apk/debug/app-debug.apk` |
| Instrumentation 测试 APK | `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` |
| Release 未签名 APK | `app/build/outputs/apk/release/app-release-unsigned.apk` |
| 生成的原生库目录 | `app/build/generated/legacyJniLibs/<abi>/` |
| 匹配的对应源码归档 | `app/build/distributions/RetroMobileBench-corresponding-source-fixed-suite-v1.zip` |

## 内置资源打包与校验

- **GPU 场景包（`generateGpuPack`）**：构建时自动筛选 `gl_egypt_off`、`gl_egypt`、`gl_trex_off`、`gl_trex`、`gl_fill_off` 的 ETC1 纹理与着色器，按固定文件顺序与零时间戳生成包含文件大小和 SHA-256 的清单，并以不压缩（`STORED`）方式打包为 `gpu-data.zip`。
- **视频语料（`assets/media`）**：7 段固定视频素材清单位于 [`manifest.json`](../../app/src/main/assets/media/manifest.json)。运行时视频文件经 SHA-256 哈希校验后缓存在内部私有目录。
- **Web 竖屏信息流与高清大图语料（`generateWebCorpus`）**：构建时生成 `assets/web/manifest.json`，锁定 `index.html`、`bench.js` 以及 6 张 480p/720p/1080p 高清 JPEG/PNG 测试图的字节大小与 SHA-256 校验值，运行时在启动 WebView 前逐一校验。
