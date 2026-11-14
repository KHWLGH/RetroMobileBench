# 验证与已知限制

本页汇总截至 **2026-11-15** 已记录的验证证据与复现入口。构建、静态检查、模拟器功能和真机性能分别判断；整理文档不代表重新执行以下设备测试。当前套件定义见 [测试与结果](benchmarks.md)，环境与安装见 [开发与构建](development.md)。

## 检查入口

所有命令从项目根目录运行。先准备依赖和两套工具链，再构建应用与测试 APK：

```powershell
$env:JAVA_HOME = 'C:/Program Files/Android/Android Studio/jbr'
.\gradlew.bat :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :app:lintDebug :app:lintRelease --console=plain
.\scripts\check-legacy-native.ps1
.\scripts\check-neon.ps1 -Path app/build/generated/legacyJniLibs/armeabi-v7a/librmb_noneon.so -ExpectNeon $false
```

兼容静态检查覆盖 API 8/9 导入、SysV hash、动态依赖、RPATH/TEXTREL。除无 NEON 主库外，ARM 的 `librmbmedia.so`、`libavutil.so`、`libavcodec.so` 和 `libavformat.so` 也需逐个以 `check-neon.ps1 -ExpectNeon $false` 筛查；NEON 主库作阳性对照。这些检查不能证明 ARM 真机加载或运行已经通过。

安装 Debug 与测试 APK，解锁设备并保持测试窗口前台。将命令中的 `emulator-5554` 换成实际序列号，每次选择一个检查并串行运行，避免其他负载影响：

```powershell
adb -s emulator-5554 shell am instrument -w com.retromobilebench.test/com.retromobilebench.BackendInstrumentation
```

专项检查在 `-w` 后加入对应参数：

| 参数 | 覆盖内容 |
|---|---|
| 无参数 | 真实 JNI、存储/SQLite、CoreMark/SciMark、压缩、内存/Triad、加密与图片、汇总/旧历史/恢复、保存失败、宿主干扰和 GPU 回归 |
| `-e interferenceOnly true` | 事件等待、取消竞争、迟到回调、共享温度源、阶段窗口及关闭 |
| `-e lifecycleOnly true` | API 16+ 第一轮落盘后取消、轻量进度与 GPU 后台中断；API 8–15 检查旧系统播放/窗口的返回取消和 Home 中断 |
| `-e fixedOnly true` | 拓扑、加密已知向量/坏标签/8 工作线程、六图参考像素、会话恢复、保存异常与七段视频的软件/系统/播放矩阵 |
| `-e mediaTimestamps true` | API 16+ 只做时间线诊断：平台与容器时间、逐包 SHA-256、旧 Extractor 的 DTS/PTS 识别；不运行解码或生成性能成绩 |
| `-e mediaOnly true` | API 16+ 严格媒体专项：EOS/时间戳计数、Surface 连续获取/过期回调/取消等待、前台亮屏，以及七段软件/系统/播放共 21 路径全部完成；仅用于预期支持这些固定档位的设备 |
| `-e gpuOnly true -e fullGpu true` | 要求完整资源包；GPU 隔离进程与资源校验，Egypt/T-Rex 四模式各 5 秒诊断及正式 60 秒 Fill，取消、超时与重启 |
| `-e production true` | 真实开始按钮触发完整固定套件，检查顺序、三轮/单次、中位数、五项正式 GPU 时长、窗口参数与持久化 |

例如运行完整一键流程：

```powershell
adb -s emulator-5554 shell am instrument -w -e production true com.retromobilebench.test/com.retromobilebench.BackendInstrumentation
```

检查输出中的 PASS/FAIL 和逐路径状态，不只看 `adb shell` 退出码。`fixedOnly` 的通过条件包括七段软件解码完成及其他路径得到可解释的结果，不代表系统解码和播放全部成功。默认 GPU 回归含四场景模式短测和完整 60 秒 Fill；短测不能替代生产时长。

### 独立加密诊断

`tests/native/crypto_join_check.c` 直接包含实际加密包装层，在测试翻译单元中截获 pthread、计时和比较，延后最后一个线程的操作，检查输出验证是否发生在全部 join 之后；同时覆盖三种模式、已知向量、线程创建失败和取消清理，不加入 APK。

API 9 x86 构建、API 16 模拟器运行示例，PowerShell 的 `--%` 保留 C 宏引号：

```powershell
.\.tools\android-ndk-r10e\toolchains\x86-4.9\prebuilt\windows-x86_64\bin\i686-linux-android-gcc.exe --% --sysroot=.tools/android-ndk-r10e/platforms/android-9/arch-x86 -std=gnu99 -O2 -mstackrealign -DMBEDTLS_CONFIG_FILE=\"crypto_config.h\" -Inative/src -Ithird_party/mbedtls/include tests/native/crypto_join_check.c third_party/mbedtls/library/aes.c third_party/mbedtls/library/gcm.c third_party/mbedtls/library/cipher.c third_party/mbedtls/library/cipher_wrap.c third_party/mbedtls/library/sha256.c third_party/mbedtls/library/platform_util.c third_party/mbedtls/library/constant_time.c -o app/build/crypto-join-check -lm
adb -s emulator-5554 push app/build/crypto-join-check /data/local/tmp/rmb-crypto-join-check
adb -s emulator-5554 shell chmod 700 /data/local/tmp/rmb-crypto-join-check
adb -s emulator-5554 shell /data/local/tmp/rmb-crypto-join-check
```

## 已记录的验证结论

以下以宿主开销优化后的最新回归为主，保留对当前兼容说明仍有效的旧系统证据。

| 环境 / 检查 | 已观察结果 |
|---|---|
| 构建 | Debug、Release、测试 APK、lintDebug 与 lintRelease 通过；Release 尚未使用发布密钥签名 |
| 原生静态检查 | 共 11 个共享库的 API 8/9 导入、SysV hash、依赖和无 RPATH/TEXTREL 检查通过；无 NEON 主库及四个 ARM 媒体/FFmpeg 库的 NEON/VFP-D32 筛查为 0，NEON 主库阳性对照有效 |
| API 16 / Android 4.1 x86 模拟器：后端 | 正式 CoreMark 双种子、SciMark、压缩、内存/Triad、存储/SQLite、加密、六图参考像素、保存失败导出、取消/恢复与 GPU 回归通过 |
| Triad 和汇总专项 | 1/2/3/4/8 线程含非整除分区、固定总工作集、MB/s 重算、实际创建数、取消重启通过；新旧内存协议、图片乱序身份匹配、错误像素/非法速度拒绝和旧 median 回退通过 |
| API 16：宿主干扰 | 事件等待/取消竞争/迟到回调、共享低优先级温度源及清理通过；独立加密 join 顺序诊断通过 |
| API 16：生命周期 | 第一轮检查点落盘后才通知已保存进度；取消保留 1 轮且不生成中位数；屏上 GPU 退后台记为中断。观测停止延迟 90 ms，不作为所有阶段的上限 |
| API 16：完整一键流程 | 14 阶段按序全部尝试，8 阶段三轮及中位数、5 项 GPU 的 113/113/56/56/60 秒完整时长、独立进程、渲染/窗口参数、温度和会话持久化通过 |
| API 10 / Android 2.3.7 x86 模拟器 | 宿主等待/温度专项通过；SD 实际播放约 4 秒，旧媒体/GPU 本地窗口、内部 Activity 切换、返回取消与 Home 中断通过；保留已完成软解结果且不生成正常汇总 |
| 小米 2A / Android 4.1.1 / API 16 ARM 真机 | 11-14 严格媒体专项 PASS，21/21；生命周期与 GPU 专项 PASS。修复前有效完整流程为 13 completed、视频 partial，五项正式 GPU 完成；最终修复后未重跑正式整套，详见[合并实机记录](validation-devices.md) |
| 三星 GT-I9260 / Android 4.1.2 / API 16 ARM 真机 | 11-15 严格媒体专项 PASS，21/21；修正 B 帧输入 DTS/PTS 后 High/FHD 四路由 TI 完成，VP8 后备窗口恢复通过。11-14 首版修复完整流程为 13 completed、视频 partial、五项正式 GPU 完成；最终构建未重跑正式整套。历史 Watchdog、17/21 失败、专项和产物证据统一见[合并实机记录](validation-devices.md) |
| 对应源码交付流程 | 既有 v2 源码 ZIP 生成并通过 7-Zip 完整 CRC 检查，包含 FFmpeg 源码/许可、项目和构建材料；重新分发必须为当前 APK 重新生成匹配归档 |

已记录的 API 16 x86 模拟器完整流程耗时 **792405 ms（约 13 分 12 秒）**，已有资源缓存，不包含干净安装后的首次解包。结果为 13 阶段 `completed`、视频 `partial`，整次保持 `partial`。这是模拟器功能流程耗时，不是宿主优化前后的性能 A/B，也不能用于真机排名。

既有 v2 打包记录中 Debug APK 约 160 MiB、对应源码 ZIP 约 492 MiB；这些是宿主优化前的历史产物大小，不作为当前包体、安装空间或运行峰值。分发以实际生成的 APK 和匹配源码为准。

### 媒体已知限制

API 16 模拟器的七段 FFmpeg 软件解码均完成，首帧 YUV 哈希通过。OMX.google 路径中，SD、HD Baseline、H.263、MPEG-4 和 VP8 的系统解码与播放完成，并在可读布局进行亮度抽查；High 720p / 1080p 的系统/播放路径在 `feed_input` 或 `drain_output` 出现 `IllegalStateException`。失败独立保留，不能把“矩阵检查通过”写成全部系统路径成功。

API 10 曾在 High 720p 软件路径观察到约 15063 ms 的父进程超时，系统高档位也返回解码错误；旧系统完整严格矩阵未获得全部通过的结论。MediaPlayer 播放成功的硬件类型仍为未确认。Surface 路径无法取得真实显示 FPS、画质回读或显示帧数时保持不可获得。

旧系统窗口检查曾因模拟器冷启动锁屏而出现 `INJECT_EVENTS` 错误，解锁后在未修改测试和产品代码的情况下重跑通过。复现前应先解锁，避免将按键被系统窗口拦截误判为应用生命周期错误。

## 真机验收与发布要求

小米 2A 已补充 API 16 ARM NEON 加载、命名系统解码及五项正式 GPU 的物理设备证据，但不是完整发布验收。当前仍需以下覆盖，设备条件可以重叠：

- API 8–15 ARM：安装、主库/媒体加载、资源准备、旧系统播放与窗口生命周期。
- API 16–18 带厂商解码器的 ARM：覆盖更多设备、厂商输出布局和故障回收；小米 2A 七段 21 路径专项已完成，仍不能将解码器名称或成功播放解释为硬件类型确认。
- 可同时调度 8 核的设备：真实创建/回收 8 个工作线程，记录在线变化，区分线程数和物理并行。
- 无 NEON ARMv7 与原生 x86 设备：实际加载与完整运行；静态指令检查不能替代。
- API 29+：硬件/软件属性隔离分支与系统声明；新系统安装策略需单独实测。

每次验收至少检查：干净安装与首次解包峰值、缓存修复/取消/空间不足、运行内存、GPU 驱动和片元精度、固定分辨率与正式时长、取消/后台/锁屏/Surface 丢失、系统媒体失败后继续执行、保存异常及重启恢复、起止电池温度和采样峰值。

不承诺每台老设备都能完成 1080p 或完整场景。高档位失败保留低档位成绩，不补零或生成综合分；模拟器和构建成功不能代替硬解、真实速度、峰值内存或温升结论。尚无物理设备的宿主开销优化前后 A/B 数据，不给出提分幅度或“开销为零”的结论。

发布前使用匹配版本构建并保留 v1 签名，同时提供对应源码和许可，操作见 [许可与分发](licenses.md)。

```powershell
.\gradlew.bat :app:generateBenchmarkNotices :app:correspondingSource --console=plain
git diff --check
```

核对生成通知包含完整原始许可、署名和库替换说明；源码 ZIP 应包含当前 README、docs 中的说明与实机记录、实际依赖源码及构建脚本，排除 `.git`、`.tools` 和 build 目录。不要将此前的归档检查结论直接作为新发布包已检查的证据。
