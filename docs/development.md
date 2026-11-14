# 开发与构建

项目为单个 Android `app` 模块，使用 Java Framework Activity/View、系统 org.json、SQLite 和 Framework Instrumentation。生产代码不依赖 AndroidX、Compose、Kotlin 标准库或 Play 服务。测试负载与结果定义见 [测试与结果](benchmarks.md)，复现检查和设备覆盖见 [验证与已知限制](validation.md)。

## 架构与生命周期

```text
MainActivity → BenchmarkRunner → BenchmarkSuite.DEFAULT
                                  ├─ JNI：CPU / 内存 / 存储 / SQLite / 加密 / 图片
                                  ├─ MediaClient → :media 一次性进程
                                  └─ GpuClient   → :gpu 一次性进程
                     ↓
              ResultStore：整次会话与逐轮检查点
```

`BenchmarkRunner` 是应用级单串行工作线程，只持有 Application。Activity 订阅状态；普通界面重建不会重复启动测试。`BenchmarkSuite.DEFAULT` 定义固定 14 阶段、轮数与协议，诊断枚举不自动进入产品流程。

运行开始先创建 UUID 会话并准备完整资源，每轮结束同步保存检查点，再通知已保存轮数；只有保存成功才推进 `savedRounds`。结束后生成完整可读报告和 JSON。数据库创建或更新失败单独记录告警，不阻止已有测量结果保存在内存中。启动恢复将遗留 `running` 会话与活动阶段标为 `interrupted`，保留已完成轮次并移除当前阶段的正常中位数。

测量期间仅发布阶段、轮次和短文本，完整报告在结束后格式化。整次温度采样共用一个后台优先级 HandlerThread，约 2 秒采样一次，阶段使用同一数据源的窗口；采样期间暂存数值，阶段结束再生成 JSON，并在完成或异常退出时关闭采样源。

取消立即反馈且不启动下一阶段。CPU/CoreMark 和同步 I/O 等待安全边界，GPU/媒体发出停止请求并回收工作进程。请求状态通过结果、取消、死亡或超时事件唤醒，测量期间不轮询 Binder；每个请求独立保存终态，取消注册受锁保护，旧请求的迟到回调不影响新请求。进程回收确认位于测量之后。

界面保持亮屏。离开前台、锁屏、正在使用的 Surface 丢失按中断处理；旧系统内部 GPU/媒体 Activity 切换属于同一次测试，不当作用户退后台。返回与取消记为取消，Home 记为中断，已有结果保留。

## 平台兼容与原生库

兼容目标为 API 8 ARMv7 / API 9 x86，`targetSdk 23`，仅打包 32 位原生库。新系统的安装策略仍需对应版本验证；设备也必须支持包内 ABI。Java 17 语言特性由 D8 脱糖，不会补齐新系统 API，Lint `NewApi` / `InlinedApi` 按错误处理，较新 API 的实现分版本隔离。

### 库组织与加载

| 位置 | 库与用途 |
|---|---|
| `armeabi-v7a/librmb.so` | NEON 主库，包含 CPU / 内存 / 存储与静态 GFXBench 依赖 |
| `armeabi-v7a/librmb_noneon.so` | 全部相关源文件以 VFPv3-D16 编译的无 NEON 主库 |
| `x86/librmb.so` | 原生 x86 主库，避免 ARM 转译影响测量 |
| 每个 ABI | `librmbmedia.so` 与独立 `libavutil.so`、`libavcodec.so`、`libavformat.so` |

C++ 运行时使用 `gnustl_static`。ARM 媒体库和 FFmpeg 统一使用标量路径，关闭汇编/SIMD，有无 NEON 的 ARMv7 使用相同媒体协议。

`NativeLoader` 在加载主库前用 Java 读取 ABI 和 `/proc/cpuinfo` 的 Features。无法确认 NEON 时选择无 NEON 版，加载失败不冒险回退到 NEON；仅在 JNI 调用后检测不足以防止静态初始化阶段的非法指令。主库的每个变体都独立构建全部 GFXBench 静态依赖，CPU zlib 用符号前缀与 GPU 自带旧 zlib 隔离。

Android 4.3 以前的链接器不能可靠地按现代规则查找应用库间的依赖。媒体库必须按依赖顺序显式加载，FFmpeg 保持独立动态库，不静态合入 JNI；具体替换和交付要求见 [许可与分发](licenses.md)。不要引入未经旧 API 导入检查的系统符号；项目使用 memalign、mutex/condition，避免依赖新的 getauxval、posix_memalign、pthread barrier 或 NDK MediaCodec。

### 媒体和窗口

每个视频请求在一次性 `:media` 进程执行，父进程负责时限、Binder 死亡和回收确认。API 16 起使用 Java 同步 MediaCodec 的旧缓冲区接口；API 29 的属性访问隔离在单独类中，旧系统不加载相关类型。

API 8–15 的 MediaPlayer 由媒体进程 Activity 持有本地窗口，API 8–10 使用 push-buffer holder；私有素材通过文件描述符交给 MediaPlayer。Gingerbread 的 `Surface.writeToParcel` 无法转发普通窗口收到的 Surface，因此 API 8–10 屏上 GPU 也由 GPU 进程 Activity 创建本地窗口。API 11+ GPU 和 API 16+ 媒体播放可由主 Activity 提供 Surface。

### GPU 宿主

GFXBench ES2 源码清单与宿主位于 `feasibility/gfxbench-es2`，同时供主 App 构建使用，不使用上游完整构建系统或 Java 宿主。testfw、KCL/KRL 和所需依赖静态进入各主库变体，通过静态工厂表创建测试，避免按名称 `dlopen`。Poco、libepoxy 和 ES3/Vulkan 路径未编入当前目标。

API 8 的 EGL 操作由 Java EGL10 宿主完成，只链接 GLES2。离屏使用小 pbuffer 和 1080p FBO，屏上使用真实窗口 EGLSurface；判断 EGL 配置是否存在以 API 返回值和数量为准，不能把不透明的 `EGLConfig` 句柄 0 当作失败。GFXBench 的少量兼容转换在生成文件中完成，保留上游源码树。

纹理固定 ETC1，避免自动选择缺失的 ETC2 数据。打包时为 `lowlevel/ui.fs` 添加基于 `GL_FRAGMENT_PRECISION_HIGH` 的 highp / mediump 回退；顶点精度和其他着色器不随意调整。是否覆盖老 GPU 的真实驱动行为仍需真机验证。

相关依据：[旧 C++ 库加载限制](https://developer.android.com/ndk/guides/cpp-support)、[Android 2.3.7 Surface JNI](https://android.googlesource.com/platform/frameworks/base/+/android-2.3.7_r1/core/jni/android_view_Surface.cpp)、[Surface 实现](https://android.googlesource.com/platform/frameworks/base/+/android-2.3.7_r1/libs/surfaceflinger_client/Surface.cpp)、[GFXBench 源码](https://github.com/Kishonti-Opensource/gfxbench)。

## 工具链与构建

| 工具 | 当前配置 / 用途 |
|---|---|
| Android Studio JBR、AGP 9.5.1、Gradle 9.7.0 | Java 编译和 Android 打包 |
| Android SDK 37 | compileSdk 37，minSdk 8，targetSdk 23 |
| NDK r10e / GCC 4.9 / gnustl_static | 所有生产原生代码的实际编译，ARM API 8、x86 API 9 |
| CMake 3.18.1 / Ninja | 主原生目标与静态依赖构建 |
| NDK r23c / 23.2.8568313 | 现代打包及 ELF、指令检查，不替代 r10e 编译 |
| Git Bash / r10e make | FFmpeg configure / make |

1. 按 [固定版本依赖](../third_party/README.md) 初始化固定提交和 GFXBench 的 Egypt / T-Rex 数据。不要使用 `--remote` 更新到不同提交。
2. 在 Studio 所用 SDK 中安装 SDK 37、CMake 3.18.1、NDK r23c，确认本机 `local.properties` 指向该 SDK。
3. 将官方 r10e 安装到 `.tools/android-ndk-r10e`，或先执行下列准备任务。该任务下载约 1 GB 官方归档并验证固定 SHA-256；依赖准备需要联网，设备运行离线。

```powershell
$env:JAVA_HOME = 'C:/Program Files/Android/Android Studio/jbr'
.\gradlew.bat :app:prepareLegacyNdk --console=plain
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain
```

已手工准备 r10e 时可跳过 `prepareLegacyNdk`。Git Bash 默认路径为 `C:/Program Files/Git/bin/bash.exe`，可通过 `-PgitBash=<path>` 指定。主原生目标支持 `-PlegacyNdkDir=<path>`，但 FFmpeg 脚本仍固定读取 `.tools/android-ndk-r10e`，该位置也必须有同版工具链。

`buildFfmpeg` 使用固定配置构建 avutil / avcodec / avformat；`buildLegacyNative` 使用 r10e 构建主库和媒体 JNI，再复制 FFmpeg 产物至生成的 JNI 目录。已准备源码后构建不会另行下载第三方源码。默认 APK 包含完整资源，无须 `-PgpuData=full`。

| 产物 | 路径 |
|---|---|
| Debug APK | `app/build/outputs/apk/debug/app-debug.apk` |
| 测试 APK | `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` |
| Release 未签名 APK | `app/build/outputs/apk/release/app-release-unsigned.apk` |
| 生成的原生库 | `app/build/generated/legacyJniLibs/<abi>/` |
| 匹配对应源码 ZIP | `app/build/distributions/RetroMobileBench-corresponding-source-fixed-suite-v2.zip` |

`assembleRelease` 不等于可发布的签名产物；发布时配置自己的签名并保留 v1。原生构建身份会根据源码、宿主和构建输入更新，比较结果需核对 `native_build_id`。

安装示例（多设备时将 `emulator-5554` 替换为实际序列号）：

```powershell
adb devices
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am start -n com.retromobilebench/.MainActivity
```

## 固定离线资源

`generateGpuPack` 按路径排序、固定 ZIP 时间戳，生成包含路径、大小和 SHA-256 的 format 2 清单，以 STORED ZIP 打包 ETC1 子集。默认 tests 为 `gl_egypt_off`、`gl_egypt`、`gl_trex_off`、`gl_trex`、`gl_fill_off`，删除其他纹理格式与画质参考图，保留素材署名和精度回退说明。

```powershell
.\gradlew.bat :app:generateGpuPack :app:assembleDebug --console=plain
```

开始测试后自动核对私有目录的资源清单，首次使用时流式复核文件大小和哈希；缺少、陈旧或损坏则从内置 ZIP 恢复。解包到暂存目录，检查路径、重复项、数量、大小和 SHA-256，同步清单后原子提交；失败或取消清理暂存，保留已提交包。资源准备共用独立 180 秒截止时间，准备告警写入会话，各阶段保留具体失败原因。

媒体固定文件位于 [assets/media](../app/src/main/assets/media/manifest.json)。视频校验后原子写入 `files/media-v1`，缓存也复核哈希；图片的压缩文件与参考像素在计时前校验。大参考像素由原生流式读取，避免经 Java 大数组复制。`scripts/generate-media.ps1` 和 `scripts/generate-jpeg-references.ps1` 是生成配方，默认构建不重新编码；重新生成须审查哈希并升级语料协议，不能用不同解码器的像素覆盖 JPEG 参考。

安装和更新需要同时容纳 APK、解包内容及新包暂存；资源提交还保留 16 MiB。场景要求至少 192 MiB 可用内存，Fill 为 128 MiB；不足时说明原因，不静默降低分辨率或删场景。资源和测试文件均在内部私有目录，无网络或外部存储权限要求。

## 开发诊断

正式验证入口集中在 [验证与已知限制](validation.md)。`-PgpuData=diagnostic` 仅用于开发，不含完整产品场景，不能作为正式离线包。旧包导入器和 Zstd / 完整 STREAM 等接口保留回归用途，界面不暴露。

`scripts/feasibility-gfxbench-es2.ps1` 与 `scripts/feasibility-gfxbench-run.ps1` 保留独立 GPU 移植诊断，原工具链和六项离屏流程不代表当前 App 协议。不要用其输出替代生产 APK 的 API 8/9 加载验证。设置 `RMB_SCREENSHOT_FRAMES` 会引入像素回读开销，只能检查画面，不能用于正式测量。

排查时可用 `adb logcat -s RMB:* RMB-GFX:* _logcat_:* DEBUG:*` 查看宿主、GFXBench 与崩溃日志。旧版 adbd 不可靠地返回远端退出码，检查实际 PASS/FAIL 和结果内容；老系统 toolbox 也缺少部分常用主机命令。部分克隆的数据检出应沿用依赖说明中的 sparse 设置，避免对未检出对象进行全量归档。
