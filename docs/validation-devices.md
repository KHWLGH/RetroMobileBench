# ARM 真机验证记录（2026-11-14—15）

本页合并小米 2A 的 2026-11-14 记录，以及三星 GT-I9260 的 2026-11-14 首测/复测和 2026-11-15 媒体修复记录。日期采用宿主日期；整理文档没有重新运行设备测试。通用检查入口与发布待验项见[验证与已知限制](validation.md)，固定协议见[测试与结果](benchmarks.md)。

## 新增 Web / fixed-suite-v3 的最终验收

全屏改版前，`web-daily-v1` 的四项 × 20 单元 × 三轮已在 API 10 / 16 / 19 / 29 x86 虚拟设备通过，API 16 的完整 15 阶段检查亦已通过。当前主界面固定竖屏，屏上测试使用独立横向全屏窗口，Web 协议已升级为 `web-daily-v2`；本次全屏专项证据与旧版完整套件记录分列于[验证记录](validation.md)。当前改版仍只使用虚拟设备验证，尚未重跑完整 15 阶段或物理设备验收。

小米 2A 与三星 GT-I9260 的新 Web 专项、生命周期及完整 15 阶段属于最后一步。2026-11-15 完成 Debug / Release / test APK、lint、原生静态检查、通知和对应源码归档 CRC 后，才停止模拟器并检查最终真机环境；`adb devices -l` 列表为空，两台设备均未连接。因此本次 **未执行 Web / v3 真机验收**，不记录任何新真机成绩。

待两台设备连接、授权并解锁后，各运行 `webOnly + webFaults`、`lifecycleOnly` 和 `production`，核对本次宿主/资源身份、正式三轮、完整 GPU 时长与持久化，再在本节追加独立结果。以下全部是既有 v2 记录，不能作为此次 Web / v3 真机通过证据。

## 既有 v2 结论与覆盖范围

| 设备 / 最近验证构建 | 最新已确认结果 | 完整正式流程的覆盖边界 |
|---|---|---|
| 小米 2A / `r10e-gcc49-c0471f9db9476f92b65fbd88`（11-14） | 严格 `mediaOnly` **PASS，21/21 completed**；`lifecycleOnly`、`gpuOnly + fullGpu` PASS | 修复前有效完整流程为 13 阶段 completed、视频 partial，五项正式 GPU 全部完成；修复后没有重跑完整 14 阶段 |
| 三星 GT-I9260 / `r10e-gcc49-a810bb3655c1d7d7af4c8c92`（11-15） | 严格 `mediaOnly` **PASS，21/21 completed**；High/FHD 四条系统/播放路径由 TI 首选完成，VP8 崩溃后的 Google 后备恢复成功 | 首版进程/容量修复的完整流程为 13 阶段 completed、视频 partial，五项正式 GPU 全部完成；最终时间线/窗口修复没有重跑完整 CPU/GPU 套件 |

`production PASS` 表示真实开始按钮、固定顺序、正式参数与时长、原始轮次、中位数及持久化断言通过，允许会话诚实保存为 `partial`。只有严格媒体专项要求七段软件/系统/播放共 21 条路径全部 completed。不同构建的专项与完整成绩分开归属，不合成为“最终构建整套通过”。

两台设备均为 API 16 ARM NEON 真机，不能替代其他平台或发布验收。解码器名称不证明硬件类型，API 16 的 `hardware_type` 保持 `unconfirmed`；Surface 播放没有画质回读、真实显示帧数或显示 FPS，渲染/提交速率不解释为实际显示速率。

## 设备与测试条件

| 项目 | 小米 2A | 三星 GT-I9260 |
|---|---|---|
| 设备 / ADB 序列号 | Xiaomi MI 2A / taurus / `c0e4aa9d` | Samsung GT-I9260 / superiorchn / `cff030496503230` |
| 系统 | Android 4.1.1 / API 16，MIUI 4.12.5 | Android 4.1.2 / API 16，`JZO54K.I9260ZCAMG1` |
| 构建指纹 | `Xiaomi/taurus/taurus:4.1.1/JRO03L/4.12.5:user/release-keys` | `samsung/superiorzc/superiorchn:4.1.2/JZO54K/I9260ZCAMG1:user/release-keys` |
| CPU / ABI | 2 个配置核心，最高频率声明 1728000 kHz；`armeabi-v7a`，Features 含 NEON；观察到核心上线变化 | OMAP4470 / 双 Cortex-A9，最高频率声明 1500000 kHz；`armeabi-v7a`，Features 含 NEON |
| GPU | Qualcomm / Adreno (TM) 320 | Imagination Technologies / PowerVR SGX 544，来自工作进程 EGL 初始化日志 |
| 系统可见总内存 | 810729472 bytes | 828379136 bytes |
| 首次有效完整会话供电 / 温度 | 初始 100%、AC powered；25.3 → 25.5°C，采样峰值 25.6°C | 安装前 78%、USB powered、30.3°C；首次会话 30.6 → 35.8°C，峰值 36.5°C |
| 首测可用内部空间 | 未在原记录列出 | CoreMark 前 3412262912 bytes |

应用均为 0.0.1 / versionCode 1 的完整离线 Debug 包。小米使用覆盖安装；三星首测安装前 `pm path` 无输出，但两者都没有完成干净安装、首次解包峰值空间或运行内存峰值验收。后续覆盖安装保留应用数据和历史。

三星设备时钟停留在 2012-01-06，未修改时钟。日志 `01-06` 和 JSON 绝对时间保留原值，会话耗时使用 `wall_ms`。电池温度不是芯片结温，不据此推导降频因果。

### 前台与内存准备

每次运行前唤醒、实际解锁，并确认 `dumpsys window windows` 的 `mCurrentFocus` 是 `MainActivity`；仅有 `am start` 成功或 `mFocusedApp` 不足以证明前台。API 16 的 `input swipe` 不接受持续时间参数，自动滑动也非每次可靠。Instrumentation 结束会关闭测试 Activity，两项检查之间仍需重新确认前台。

应用在固定资源准备后、各项 GPU 预检和计时前，由工作线程请求一次 `System.gc()`；单项模式也在计时前执行。`memory_preparation` 保存系统可用内存、Java 堆占用前后值、耗时和 `best_effort=true`。整理仅作用于本应用可达范围，不保证释放固定容量，保留 GPU 的 192/128 MiB 门槛。

三星后续复测在启动前另执行 `am kill-all` 回收可结束的后台进程。一次 `/proc/meminfo` 快照的 `MemFree` 从 156920 kB 增至 242844 kB，增加 85924 kB（约 83.9 MiB）；它不等于 `ActivityManager.availMem`。没有 root、清数据、清素材缓存或执行 `drop_caches`。首版修复完整会话中的 GC 耗时 25 ms，Java 堆从 13090456 降至 12706456 bytes，系统可用内存却从 438689792 变为 438575104 bytes，不能把系统内存改善归因于一次 GC，或量化后台回收与进程修复各自的贡献。

## 最新媒体与专项结果

### 小米 2A：EOS、窗口与生命周期修复

严格 `mediaOnly` 在最终 11-14 安装构建再次 **PASS**。七段固定 FFmpeg 4.4.6 标量软件解码、系统吞吐和播放全部 completed，软件首帧 YUV 哈希通过。

| 素材 | 修复后播放解码器 | 播放帧数 | EOS 字节数 / PTS（µs） |
|---|---|---:|---|
| H.264 SD | OMX.qcom.video.decoder.avc | 120 | 0 / 3966666 |
| H.264 HD Baseline | OMX.qcom.video.decoder.avc | 120 | 0 / 3966666 |
| H.264 HD High | OMX.qcom.video.decoder.avc | 120 | 0 / 4033333 |
| H.264 FHD | OMX.qcom.video.decoder.avc | 120 | 0 / 4033333 |
| H.263 | OMX.qcom.video.decoder.h263 | 30 | 0 / 966666 |
| MPEG-4 | OMX.qcom.video.decoder.mpeg4 | 30 | 0 / 966666 |
| VP8 | OMX.google.vpx.decoder | 30 | 0 / 867000 |

所有播放均 `pending_frames=0`、`output_eos=true`。四段 qcom H.264 不再因少计最后一帧而进入 Google 后备；VP8 已消费时间戳的空 EOS 不计为额外帧。qcom 厂商颜色布局 2141391875 不可读，未取得系统路径像素抽查；Google VPX 可读线性布局的亮度抽查通过。

同批修复的专项结果：

| 检查 | 结果与实际覆盖 |
|---|---|
| `mediaOnly` | EOS 边界、缺帧/重复帧拒绝、连续 Surface 获取、过期回调、窗口等待取消及空闲前台亮屏断言通过 |
| `lifecycleOnly` | PASS；首轮落盘后取消保留 1 轮、不生成中位数，停止延迟 105 ms；真实屏上 GPU 退后台记为 interrupted，并释放亮屏标志 |
| `gpuOnly + fullGpu` | PASS；资源损坏恢复/身份、五项独立进程、Egypt/T-Rex 屏上/离屏各 5 秒诊断、正式 60 秒 Fill、实际窗口交换、预取消/运行中取消、父进程超时及回收后重启通过 |

此次 GPU 专项未复现早先 `eglSwapBuffers` 错误，短场景不替代正式时长。修复前默认后端检查和受锁屏影响的生命周期尝试仍不记为整体 PASS，历史见下文。

### 三星 GT-I9260：最终 21/21 媒体矩阵

最终 11-15 媒体 JSON 的 `wall_ms=71138`，这是媒体矩阵耗时，不是完整正式会话耗时。七段软件解码全部 completed、参考像素通过；系统与播放结果如下。

| 素材 | 系统吞吐 | 播放输出 | 播放计时 |
|---|---|---|---:|
| H.264 SD | TI completed，255 帧 / 91.07 fps | TI，120 帧、EOS、pending=0 | 3967 ms |
| H.264 HD Baseline | TI completed，136 帧 / 48.57 fps | TI，120 帧、EOS、pending=0 | 3967 ms |
| H.264 HD High | TI completed，130 帧 / 46.40 fps | TI，120 帧、EOS、pending=0 | 3966 ms |
| H.264 FHD | TI completed，108 帧 / 38.56 fps | TI，120 帧、EOS、pending=0 | 3966 ms |
| H.263 | TI completed，456 帧，兼容性探针 | TI，30 帧、EOS、pending=0 | 967 ms |
| MPEG-4 | TI completed，261 帧，兼容性探针 | TI，30 帧、EOS、pending=0 | 967 ms |
| VP8 | Google VPX 后备 completed，234 帧，兼容性探针 | Google VPX 后备，30 帧、EOS、pending=0 | 985 ms |

High/FHD 系统吞吐各验证 120 个输入样本，计时外预检分别为 168 / 278 ms；两条播放也各自核验完整 120 个样本。四条原失败路径均由 `OMX.TI.DUCATI1.VIDEO.DECODER` 首选完成，attempts 长度均为 1，没有触发 Google H.264 后备，不声称其 High 能力已修复。

系统 FPS 为约 2.8 秒窗口内的解码输出速率，结束时可能处于下一轮或未排空轮次，其累计帧数不能按单次播放的 120 帧/EOS 要求判断。H.263、MPEG-4、VP8 是 30 帧兼容性探针，不发布正常吞吐 FPS。TI 专有布局仍为 `unavailable_color_layout`，未新增 TI 像素验证结论；Google VPX 可读输出的亮度抽查通过。

11-15 的 `mediaTimestamps` 诊断验证逐样本身份和输入 PTS 对照，覆盖正确 PTS（含起点和舍入差异）保持不变、归零 DTS 识别、未知损坏/重复 PTS 拒绝，以及原生错误帧数不返回可用时间表。严格 `mediaOnly` 保留 EOS/重复帧/未知时间戳/缺帧断言，另验证销毁旧 Surface 后重新获取窗口。

11-14 最终回收顺序构建还取得 `gpuOnly + fullGpu`、`interferenceOnly`、`lifecycleOnly` PASS：GPU 覆盖与小米专项相同；宿主检查覆盖事件唤醒、取消竞争、迟到回调、单一后台温度源、阶段窗口及清理；生命周期取消保留 1 轮、无中位数，实测停止延迟 135 ms，真实 GPU 退后台保存为 interrupted 并释放亮屏。此延迟不代表所有原生阶段的上限，这些专项没有在 11-15 构建重跑。

## 修复依据与根因更正

### 两台设备共用的流程保护

- 主 Activity 在 `onStart` 设置 `FLAG_KEEP_SCREEN_ON`，准备、测试间隙和结果页持续亮屏，`onStop` 释放；未新增权限或修改系统锁屏设置。手动锁屏和切后台继续按中断处理。
- 最后一个有效媒体输入样本附加 EOS；按已提交且未消费的 PTS 识别零字节 Surface 帧（含最后一帧），忽略已消费时间戳的空控制输出。播放帧数必须实测严格等于 120/30，继续拒绝缺帧和真实 PTS 倒退；失败保留已输出/待输出帧数、EOS 时间戳/大小与格式。
- Surface 异步隐藏不覆盖后来的获取，保留有效窗口，重新挂接时同步可用窗口并丢弃旧 Activity 回调，获取等待支持取消。尚未启动媒体工作进程的窗口获取失败只保留该路径结果，继续后续素材；工作进程无法回收则阻止后续同类负载。
- 测试工具等待真实前台焦点，以 ActivityMonitor 跟踪页面重建，并在主线程读取 Runner 状态；不再替换 Activity 会重新挂接的唯一 UI 监听器。

### 三星：输入容量、崩溃隔离与回收

媒体/GPU 服务进入原生负载前先报告 PID；父进程以 Binder 死亡通知等待结束，在独立线程执行有限时回收。已知 PID 时先终止本应用工作进程再解绑，移除主线程可能阻塞的 STOP/解绑和同步 `pingBinder` 轮询；超时后停止后续同类负载并要求重启应用。

候选顺序仍为厂商/未知名称优先、已知软件名称在后、最多两个候选，每个候选各用一个可丢弃进程。首选原生崩溃后仍保留名称/attempts，允许后备运行。媒体/GPU 的 `DisplaySurface.hide()` 移到工作进程回收之后，避免仍在使用的窗口先被隐藏。

仅对 API 16–17 的 `OMX.TI.*` 令 `max-input-size=0`，保留驱动默认容量，避免组件拒绝扩容后触发 ACodec 的 `CHECK(def.nBufferSize >= size)`。仍由读取/输入操作拒绝装不下的样本，不截断输入。TI 默认容量实测 SD 414720 bytes、HD 942080 bytes、FHD 2088960 bytes，Extractor 声明为 1572864 bytes；修复后未再观察到该容量断言。首帧像素容差、尺寸、EOS、帧数、PTS、分辨率、素材和正式时长均保留。

### 三星：High/FHD 的 B 帧时间线

原 `non_monotonic_output_pts: 33333 <= 100000` 是真实观测，但仅凭输出倒退将根因归到 TI 驱动不充分。11-15 在同一设备对照平台 Extractor、宿主 ffprobe 与固定 FFmpeg 4.4.6 的 MP4 解封装后，确认 High/FHD 各 120 个样本的原始时间全部对应归零 DTS，而非对应样本的 PTS。压缩包 Annex-B 大小与 SHA-256 逐个匹配，排除了读取不同样本的假象。

| 压缩样本索引 | 本机 `MediaExtractor.getSampleTime()`（µs） | 对应容器 PTS（µs） |
|---|---:|---:|
| 0 | 0 | 0 |
| 1 | 33333 | 100000 |
| 2 | 66666 | 33333 |
| 3 | 100000 | 66666 |
| 4 | 133333 | 200000 |

将 DTS 作为 PTS 提交，本身会在正常 B 帧重排后产生倒退。[AvcInputTimeline.java](../app/src/main/java/com/retromobilebench/AvcInputTimeline.java) 仅对 API 16–17、固定清单内含 B 帧的 AVC 进行计时外预检；[video_bench.c](../native/src/video_bench.c) 使用捆绑 FFmpeg 读取包时间与压缩样本，不调用 `avformat_find_stream_info`、不打开或运行软件解码器。

只有全部样本数量、顺序、大小、SHA-256 和整条归零 DTS 时间线匹配，才为同一输入提交容器 PTS。正确的平台 PTS 保留原值，包括固定起点和整数微秒换算差异；损坏、重复容器 PTS、数量错误或无法归类的时间损坏继续失败，每次 flush/seek 后重新核对输入索引和原始时间。

[MediaCodecApi16.java](../app/src/main/java/com/retromobilebench/MediaCodecApi16.java) 继续运行真正的系统解码/播放，输出不排序、不合成或重写时间戳，原有单调性、输入/输出对应、尺寸、EOS、帧数和像素容差检查保留。JSON 保存原始/容器 PTS、逐样本验证数量、来源、预检耗时及输出前缀；修复后 TI 实际输出为 `0,33333,66666,100000,133333,166666,…`。

“输入时间错误时失败、按对应容器 PTS 输入后完成”的对照将本机四条故障定位到输入时间元数据，不能扩展为所有 TI 固件或所有 API 16 都有同一问题。AOSP 4.1.2 本身有 `ctts` 处理，也不能写成“Android 4.1 一律忽略 B 帧时间”。

### 三星：VP8 后备的窗口销毁

只加入时间线修复的首轮矩阵已解决 High/FHD，但为 20/21。SEC VP8 在 `initNativeWindow` 原生崩溃后，Google VPX 配置旧窗口失败：

```text
BufferQueue: [SurfaceView] connect: already connected (cur=3, req=3)
MediaCodec: native_window_api_connect returned an error: Invalid argument (-22)
```

Binder 死亡不保证旧 BufferQueue 已断开，快速 hide/acquire 也可能复用有效旧 Surface。[DisplaySurface.retire](../app/src/main/java/com/retromobilebench/DisplaySurface.java) 现在在失败播放工作进程清理后等待真实 `surfaceDestroyed` 回调，再允许后备获取重建窗口；限时 3 秒、支持取消，无法销毁时拒绝后备，正常快速复用保留。

最终 SEC 仍记录原生失败，attempts 中 `surface_destroyed_before_fallback=true`，Google VPX 在独立进程完成 30 帧、EOS、pending=0。这证明恢复有效，不代表 SEC 组件或系统驱动修好了。

## 历史完整流程与失败证据

### 三次完整会话的归属

| 记录 | 日期 / 原生构建 ID | 会话 ID / 数据库行 | 实际结果与墙钟 |
|---|---|---|---|
| 小米修复前、解锁前台 | 11-14 / `r10e-gcc49-d8cb5bd11d70d20f813b4558` | `3594ce1f-6e27-4a0a-a523-5dbf558c4a90` | production PASS；13 completed、视频 partial；982697 ms（16 分 22.697 秒） |
| 三星首测、修复前 | 11-14 / `r10e-gcc49-c0471f9db9476f92b65fbd88` | `d372a4d3-f316-40a6-ad80-9317cfa6e53d` / 行 1 | 验收未通过；8 completed、视频 partial、2 failed、3 skipped；961957 ms（16 分 1.957 秒）；Instrumentation 系统崩溃中止 |
| 三星首版进程/容量修复 | 11-14 / `r10e-gcc49-8f0f3bdf2cb589b2824945f3` | `752c07a1-47fd-49a1-aef1-b2dff1057459` / 行 2 | production PASS；13 completed、视频 partial、0 failed、0 skipped；1162760 ms（19 分 22.760 秒） |

完整记录均包含 14 个阶段。八个三轮阶段的 completed 结果保留三条原始样本与有效中位数；partial/failed/skipped 阶段没有正常中位数。三星首测数据库只读核对与完整性检查通过，只证明保存内容一致，不代替已中止的设备端断言。三星首版修复不再出现导致 Instrumentation 中断的系统崩溃或 GPU 解绑超时；不能将不同完成程度的墙钟作性能提升对比。其电池温度为 29.8 → 36.8°C。

### 首测非 GPU 阶段原始中位数

以下是修复前两台设备的各自有效完整会话；三星首版修复仅确认这些阶段的三轮/中位数完整，原记录未另列数值。

| 阶段 | 小米 2A | 三星 GT-I9260 首测 |
|---|---|---|
| CoreMark | 单线程 6090.53、并行 12057.74 iterations/s | 单线程 4163.98、并行 8275.25 iterations/s |
| SciMark | FFT 367.45、SOR 372.49、Monte Carlo 87.34、稀疏矩阵 311.40、LU 606.44 MFLOPS | FFT 195.10、SOR 313.44、Monte Carlo 73.73、稀疏矩阵 181.86、LU 218.60 MFLOPS |
| zlib | 压缩单线程 12.80、并行 23.69 MB/s；解压 122.62 / 247.63 MB/s | 压缩 10.94 / 20.96 MB/s；解压 91.42 / 176.47 MB/s |
| 加密与摘要 | 三轮及中位数有效；GCM、认证、SHA-256 原始值保留 | 加密单线程 12.25、并行 24.35 MB/s；SHA-256 47.03 / 93.27 MB/s |
| 图片 | 六图各三轮，按素材 ID 汇总中位数 | 六图各三轮，原始样本和按素材 ID 汇总的中位数保存 |
| 内存 | 复制 4626.13、填充 7413.95 MB/s、延迟 141.66 ns；Triad 单线程 1517.17、并行 3087.80 MB/s | 复制 815.81、填充 2564.04 MB/s、延迟 124.37 ns；Triad 994.05 / 1186.37 MB/s |
| 文件存储 | 顺序写 13.17、读 74.35 MB/s；随机写 882.84、读 2878.46 IOPS | 顺序写 7.03、读 25.67 MB/s；随机写 233.37、读 2234.58 IOPS |
| SQLite | 插入 45.18、更新 45.96、删除 26.86 TPS | 插入 15.47、更新 15.74、删除 14.70 TPS |

### 正式 GPU 成绩与首测失败

| 正式阶段 / 渲染尺寸 | 小米修复前：completed 速率 / 阶段墙钟 | 三星首测 | 三星首版修复：completed 速率 / 阶段墙钟 / PID |
|---|---|---|---|
| Egypt 离屏 / 1920×1080 | 26.26 fps / 124485 ms | failed，解绑超时 / 257583 ms | 10.2484 fps / 136384 ms / 4888 |
| Egypt 屏上 / 1280×251 | 26.33 fps，2978 次交换 / 123417 ms | skipped，可用内存 147103744 bytes | 12.0886 fps，1368 次交换 / 136633 ms / 4917 |
| T-Rex 离屏 / 1920×1080 | 12.82 fps / 75557 ms | skipped，可用内存 146497536 bytes | 3.1345 fps / 103350 ms / 4953 |
| T-Rex 屏上 / 1280×251 | 22.18 fps，1245 次交换 / 74778 ms | skipped，可用内存 145965056 bytes | 9.9729 fps，560 次交换 / 104125 ms / 4981 |
| Fill 离屏 / 1920×1080 | 2401 MTexels/s / 61806 ms | failed，解绑超时 / 40096 ms | 508 MTexels/s / 63173 ms / 5011 |

两次有效 GPU 完整流程均使用五个独立工作进程、`diagnostic_duration_ms=0` 和固定正式时长。阶段墙钟含加载/测量/回收；屏上是界面内 1280×251 的实际 Surface，并非全屏或 1080p，不与离屏直接比较；Fill 主指标为 MTexels/s。

三星首测 Egypt 预检可用内存 214962176 bytes、Fill 145047552 bytes，分别满足 192/128 MiB 门槛但仍解绑失败；其余三项低于 192 MiB 跳过，没有有效 GPU 成绩。初始化日志 `ES2 pbuffer 800x480` 不证明已完成 1080p FBO。首版修复五项预检依次为 383942656、395776000、437542912、421273600、421928960 bytes，均过原门槛，没有降分辨率、缩时长或把失败补零。

首版修复前置的 5 秒单项诊断中，Egypt 离屏实测 5092 ms、Fill 因校准批次实测 9569 ms，均 completed 且回收正常；仅为短时诊断，不作正式速率。可指定 `-e gpuTest gl_fill_off` 或 `gl_egypt_off`。

### 媒体故障演进

修复前软件路径均为固定 FFmpeg 4.4.6 标量实现，七段完成且参考像素通过。两台首测的 H.264 原始吞吐如下，其余三段为兼容性探针。

| 素材 | 小米首测：软件 / 系统吞吐 | 小米首测：最终播放 | 三星首测：软件 / 系统吞吐 | 三星首测：播放 |
|---|---|---|---|---|
| H.264 SD | 118.12 / 126.34 fps，qcom | completed，Google H.264 后备 | 100.58 fps / failed，进程退出或崩溃 | failed，进程退出或崩溃 |
| H.264 HD Baseline | 56.73 / 57.46 fps，qcom | failed，Google 后备 PTS 倒退 | 48.99 fps / failed，进程退出或崩溃 | failed，进程退出或崩溃 |
| H.264 HD High | 39.85 / 59.29 fps，qcom | failed，Google 后备 drain_output | 28.66 fps / failed，进程退出或崩溃 | failed，进程退出或崩溃 |
| H.264 FHD | 13.96 / 42.84 fps，qcom | failed，Google 后备 drain_output | 12.34 fps / failed，TI 与 Google 均 drain_output | failed，TI 与 Google 均 drain_output |
| H.263 | completed / completed，qcom | completed，Google H.263 后备 | completed / failed，进程退出或崩溃 | failed，进程退出或崩溃 |
| MPEG-4 | completed / completed，qcom | failed，Google 后备 PTS 倒退 | completed / failed，进程退出或崩溃 | failed，进程退出或崩溃 |
| VP8 | completed / completed，Google VPX | completed，Google VPX | completed / completed，Google VPX 后备 | failed，进程退出或崩溃 |

小米首测 qcom 播放四段 H.264 均 `incomplete_playback_output: 119`（应 120），H.263/MPEG-4 为 29（应 30），之后才尝试 Google 后备；SD/H.263 最终成功不表示 qcom 当时通过。EOS 计数修复后才取得上文 21/21。

三星首测为 8/21：七段软件加 Google VPX 吞吐。SEC VP8 吞吐因 `first_frame_pixels_mismatch` 被拒绝后由 Google 完成；FHD 保留 TI/Google 的失败尝试，返回 JSON 前崩溃的其余路径没有 decoder/attempts 明细，不补写猜测名称。系统库原始日志包括：

```text
ACodec.cpp:1047 CHECK(def.nBufferSize >= size) failed.
ACodec::setMinBufferSize
ACodec.cpp:3178 CHECK_EQ( (status_t)OK,mCodec->initNativeWindow()) failed: 0 vs. -2147483648
Fatal signal 11 (SIGSEGV) ... CodecLooper / MediaCodec_loop
```

三星首版容量/进程修复和 11-14 最终回收顺序构建均为 **17/21，严格媒体 FAIL**：SD/HD Baseline 的 TI 系统吞吐约 92.5/48.5 fps、播放各 120 帧，H.263/MPEG-4 的 TI 播放和 Google VPX 后备各 30 帧，均 EOS/pending=0；仅 High/FHD 系统/播放四路失败。TI 输出三帧后 `33333 <= 100000`，Google H.264 仅声明 Baseline，后备在 feed_input 或 drain_output 抛异常。11-15 的 20/21 中间轮和最终 21/21 由上文时间线与窗口修复分别解释，不再把旧观测写成当前未解决的 TI 根因。

### 无效前提、系统故障与历史保留

小米首个生产会话 `ce1668a5-1192-4a8a-9879-2b467726d9b9` 在锁屏/熄屏条件下运行，14 阶段中 11 completed；视频只留下首段，屏上 GPU 也无法取得 Surface，不作前台验收或性能基准。解锁后相同 APK 才得到上述有效完整会话。早先默认后端检查在 GPU 前断言及 Egypt 屏上/离屏、T-Rex 离屏短测通过，但 T-Rex 屏上报 `eglSwapBuffers (0x3003)`，伴随 `SurfaceTexture has been abandoned` / `EGL_BAD_ALLOC`，未继续 Fill/取消/超时/重启，不记整体 PASS。早期生命周期尝试还受锁屏与被覆盖的测试监听器影响；后续修复没有放宽产品断言。

三星首测时 Android CLI 的 layout 服务与 screen capture 不兼容旧系统，改用 ADB 截图和窗口状态确认解锁，不将工具失败计作应用失败。production 进度曾停在“正在准备固定套件”，实际日志/数据库仍推进，原因是测试替换的 Runner 监听器被 Activity onStart 覆盖，后续已修正。

三星首测会话完整落盘后发生：

```text
01-06 10:00:10.476 E/Watchdog(421): WATCHDOG caused by com.android.server.wm.WindowManagerService
INSTRUMENTATION_ABORTED: System has crashed.
```

`am force-stop` 超过一分钟未返回，终止宿主等待后观察到旧 system_server 为 zombie、框架重新启动；仍报 `Can't connect to activity manager; is the system running?`，遂用一次 `adb reboot` 恢复。重启后 `sys.boot_completed=1`、主 Activity 获得前台，历史页仍显示相同 partial 会话；重启前后数据库 SHA-256 相同、完整性为 ok，行 1 的 14 阶段保留。媒体崩溃、GPU 解绑失败、低内存与 Watchdog 分开记录，没有证据把 Watchdog 精确归到某个 GPU 驱动函数。

小米最终历史副本行 7 为取消会话（105 ms）、行 8 为 GPU 后台中断。三星 11-14 专项后行 3 cancelled、行 4 interrupted 是预期生命周期记录；与首版修复数据库逐行比较，行 1/2 的 report_json 完全相同，不是被改写的正式整套失败。

## 构建、静态检查与证据索引

所有 APK 均为当时工作区构建，不能以旧构建检查替代新版本发布验收。

| 构建 / 验证批次 | Debug、测试 APK、lintDebug 构建结果 | 原生构建 ID |
|---|---|---|
| 小米首测 | BUILD SUCCESSFUL in 1m 20s | `r10e-gcc49-d8cb5bd11d70d20f813b4558` |
| 小米修复后 / 三星首测所用包 | 小米最终构建 47s；三星首测构建 1s（多数缓存） | `r10e-gcc49-c0471f9db9476f92b65fbd88` |
| 三星首版容量/进程修复 | 45s | `r10e-gcc49-8f0f3bdf2cb589b2824945f3` |
| 三星 11-14 最终回收顺序 | 46s | `r10e-gcc49-8f1f28e230467b80df6e5a15` |
| 三星 11-15 最终时间线/窗口修复 | 49s | `r10e-gcc49-a810bb3655c1d7d7af4c8c92` |

11-15 另通过 `check-legacy-native.ps1`：11 个共享库的 API 8/9 导入、SysV hash、依赖及无 RPATH/TEXTREL 检查；修改后的 ARM `librmbmedia.so` 的 NEON/VFP-D32 decode differences=0、verified interworking veneers=15；当时 `git diff --check` 通过。没有重跑 Release、其他真机、API 8–15 运行时或 API 18+，小米也未重跑三星后续修改。

### APK SHA-256

| 批次 | Debug APK | 测试 APK |
|---|---|---|
| 小米首测（应用 167822774 bytes） | `b7d93262df25e71e6778d2c9ff8622dfd9dbd791e2a2d45ea3d33d2225ad7cb9` | `c54c3e3cb867e75fe99215d61b93aaf47006e082dafa521a46184328ba78cac7` |
| 小米最终 / 三星首测（应用 167822774 bytes） | `d2762a58bac67e23770239dbaa289b0b546c508040d29fee2591d8ca0be6f55c` | `2c5783d6a9b4b3529e7ebf788b0059a611670e58af48e9b2de34594ae937a79d` |
| 三星首版修复 | `78eca8294ee530c2b1b923491027fc51415a39bb6b2c081801cf674c69c129b9` | `d48ef0e46cc57cc97347bfea6751325d42e92d9376c6c1b4631c0c11a2f2df95` |
| 三星 11-14 最终回收顺序 | `abf5e193319d3e8def2471b7eab8b5e18da354deca3f7b7251c3a6f5c50a7aad` | `7b99f16c0f337690fe214bf0d4229d54cb9a0c5e8adebda0c7c0e2a5ddb93f5d` |
| 三星 11-15 最终修复 | `0b32c160eff36a47aa6258ea704abb8fa884be0fb85e4eec27dcb9b03357f790` | `dd1ba077ea6c148aa067d19658ce5801291f6c7f9389f5b13ec1948aa25a590b` |

### 原始会话与诊断副本

下列文件统一位于忽略的 `app/build/reports/`，数据库为从 Debug 应用复制的分析副本。构建清理会删除它们，不纳入源码提交；正式交付需另行归档。表中未列哈希的文件不补造哈希。

| 文件名 | 用途 / SHA-256 |
|---|---|
| `mi2a-2026-11-14-unlocked.db` / `mi2a-2026-11-14.db` | 小米有效完整会话及此前历史 / 首个锁屏会话备份 |
| `mi2a-media-fixed-final.json` / `mi2a-fixed-final.db` | 小米最终媒体矩阵 / 最终历史，含取消和后台中断记录 |
| `i9260-production-final.db` / `i9260-after-reboot.db` | 三星首测 / 重启后同一数据库；SHA-256 均为 `1f20ed1a5370387f1b0e7a61ed98c51a682f43e327a39fdde16e3b61f6be4cc5`，integrity_check=ok |
| `i9260-critical-log.txt` | 三星首测系统关键日志 |
| `i9260-mem-before.txt` / `i9260-mem-after.txt` | 后台回收前后的 /proc/meminfo 快照 |
| `i9260-media-fix1.json` | 三星首版修复 17/21 矩阵 |
| `i9260-production-fix1.db` | 首版修复完整会话，integrity_check=ok；`edc74ecf6c45c4aa536a028263b8a9730efac71b6fd6351a13eb9a4d05e72b71` |
| `i9260-media-final.json` | 11-14 最终仍 17/21；`7985f7dcd92cb815ab421f4f84a827aa93d2224bd94415940ec755dfeca1ce0f` |
| `i9260-post-regression.db` | 11-14 专项后历史，integrity_check=ok；`869a4ced6fab89028d60482a4d59e4360d04f22cd6c6917f652f9b5afc344abe` |
| `i9260-timestamps-before.json` | 修复前平台时间诊断；`f8d22cd8e3a6ac4cacef8c30ddf4f250922331dc1194866e53d7517dd54ecd7f` |
| `i9260-media-pts-first.json` | 首轮时间线修复 20/21、VP8 后备失败；`d4910858e3786ba66a409363c087cfd43ccf1b7a491a3e6e53f6f54c9531d5cc` |
| `i9260-media-pts-final.json`（144590 bytes） | 最终 21/21；`f032274b056b1d513c6433e88c4957eb78bd5e87ebbe7122c78ba81731b8f275` |
| `i9260-timestamps-final.json`（18889 bytes） | 最终时间诊断；`159b8503697da241aca84f49b630791c294100e396e0c794081d62aa270ec62f` |

## 复现入口

从项目根目录运行，替换设备序列号，每次只选一项并串行执行。先解锁并确认实际前台；测试工具退出后再次核对。以下命令用于重新验证，执行结果不自动继承本页历史 PASS。

```powershell
# 三星测试前回收可结束的后台进程；不清除应用数据。
adb -s cff030496503230 shell am kill-all
adb -s cff030496503230 shell am instrument -w -e mediaTimestamps true com.retromobilebench.test/com.retromobilebench.BackendInstrumentation
# 再次确认主 Activity 真正前台后，运行严格媒体专项。
adb -s cff030496503230 shell am instrument -w -e mediaOnly true com.retromobilebench.test/com.retromobilebench.BackendInstrumentation
```

其他入口（这里以小米序列号为例）：

```powershell
adb -s c0e4aa9d shell am instrument -w -e production true com.retromobilebench.test/com.retromobilebench.BackendInstrumentation
adb -s c0e4aa9d shell am instrument -w -e mediaOnly true com.retromobilebench.test/com.retromobilebench.BackendInstrumentation
adb -s c0e4aa9d shell am instrument -w -e lifecycleOnly true com.retromobilebench.test/com.retromobilebench.BackendInstrumentation
adb -s c0e4aa9d shell am instrument -w -e gpuOnly true -e fullGpu true com.retromobilebench.test/com.retromobilebench.BackendInstrumentation
adb -s c0e4aa9d shell am instrument -w -e interferenceOnly true com.retromobilebench.test/com.retromobilebench.BackendInstrumentation
```

production 的典型成功输出为 `PASS: production UI all-tests, official GPU durations, full CPU parameters and saved history`，仍需同时读取会话状态和逐路径结果；三星首测的 `INSTRUMENTATION_ABORTED` 不能由数据库核对通过替代。

## 联网来源与适用边界

以下为当时定位时使用的资料，本次文档合并没有重新开展联网调查。

| 来源 | 本机采用的结论 / 边界 |
|---|---|
| [Android 4.1.2 ACodec.cpp](https://android.googlesource.com/platform/frameworks/av/+/android-4.1.2_r1/media/libstagefright/ACodec.cpp) | max-input-size 进入 setMinBufferSize，组件拒绝扩容可触发硬 CHECK；与本机堆栈/复测相符，仅为旧 TI 范围的兼容处理 |
| [TI Ducati/OpenMAX Android 案例](https://e2e.ti.com/support/processors-group/processors/f/processors-forum/273489/ducati-openmax-on-android) | 区分 native-window TILER 与线性 YUV 转换，吞吐成功不能代替播放成功 |
| [Android 4.3 CTS EncodeDecodeTest](https://android.googlesource.com/platform/cts/+/android-4.3_r1/tests/tests/media/src/android/media/cts/EncodeDecodeTest.java) | TI 格式 0x7f000100 和 Galaxy Nexus 裁剪/填充说明厂商布局不能按紧密 YUV 解释；本机没有新增未经验证的 TI 像素布局识别 |
| [Imagination SGX544 类似案例](https://forums.imgtec.com/t/my-app-works-fine-on-emulator-and-all-test-devices-but-not-with-powervr-sgx544-powered-devices/1904) | 案例最终发现提交数据有误，不证明本机 Watchdog 是驱动缺陷，未据此改正式 GPU 负载 |
| [MediaExtractor.getSampleTime](https://developer.android.com/reference/android/media/MediaExtractor#getSampleTime()) | 返回当前样本 presentation time，不能用 DTS 代替 |
| [MediaCodec.BufferInfo.presentationTimeUs](https://developer.android.com/reference/android/media/MediaCodec.BufferInfo#presentationTimeUs) | 输出时间来自对应输入时间，压缩数据正确不代表输入时间元数据正确 |
| [Android 4.1.2 MPEG4Extractor.cpp](https://android.googlesource.com/platform/frameworks/av/+/android-4.1.2_r1/media/libstagefright/MPEG4Extractor.cpp) | 有 ctts 处理并以 sample CTS 设置 kKeyTime，必须检测实际时间线，不能泛化为全部 Android 4.1 的缺陷 |

## 仍待覆盖

最终修复没有再跑完整 14 阶段或 Release，也没有其他真机的交叉回归。Google H.264 High 后备能力仍未验证通过，SEC VP8 组件原生崩溃仍存在；TI/qcom 专有输出像素、Surface 画质、真实显示帧率/帧数和硬件类型仍未取得直接证据。

两台均为双核 NEON/API 16，尚不能覆盖无 NEON ARM、API 8–15 ARM 运行时、8 核真实并行、原生 x86 真机、API 29+ 属性分支。干净安装/首次解包峰值、运行内存峰值、GPU 片元精度、全面温升/降频与发布签名也未验收。没有宿主开销优化前后的真机 A/B，以上 GPU 数据不是官方 GLBenchmark/GFXBench 成绩。
