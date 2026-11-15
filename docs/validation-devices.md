# ARM 真机验证记录（2026-11-14—15）

本页保留小米 2A 的 2026-11-14 历史记录、三星 GT-I9260 的 2026-11-14—15 媒体修复记录，并独立记录 2026-11-15 两台设备的 Web v2 / 全屏 / GPU HUD 实测。日期采用宿主日期，各次构建的专项与完整套件分别归属。通用检查入口与发布待验项见[验证与已知限制](validation.md)，固定协议见[测试与结果](benchmarks.md)。

## Web v2 / 全屏 / GPU HUD：小米 2A 实测（2026-11-15）

用户连接小米 2A 后，先将已接受的功能提交为本地 Git `6303812`（`Add daily Web benchmarks, full-screen test windows and GPU HUD`），再进入最终真机步骤。本次只连接 `c0e4aa9d`，没有把模拟器或三星旧构建的结果并入当前验证。覆盖安装 Debug 与测试 APK，保留应用历史，没有清数据、root、修改温度或性能设置。

设备仍为 Android 4.1.1 / API 16、MIUI 4.12.5、ARMv7 NEON、Adreno 320。安装前电量 99%、AC powered、电池温度 25.4°C，`/data` 约 2 GiB 可用；完整流程开始前为 100%、AC powered、25.5°C。Web 专项设备快照的可用内部空间为 2609987584 bytes。设备时钟保留原值，日期采用宿主日期，耗时使用单调时钟。

| 当前构建专项 | 结果与实际覆盖 |
|---|---|
| `displayOnly` | PASS；主界面竖屏，三次完整 Surface 创建/销毁，GPU 使用 1280×720 的完整横向窗口、实际交换缓冲区且 HUD 更新大于 0；SD 视频全屏、忙碌 runner 的 Web 三轮/中位数及返回竖屏通过 |
| `webOnly + webFaults` | PASS；四项 × 20 单元 × 三轮，独立 PID 3878 / 3922 / 3969；身份隔离、持久化、错误结果/计时拒绝、部分结果保留、过期请求、进程死亡、准备与测量 JS 卡死、取消、Home 中断和失败后重启通过 |
| `lifecycleOnly` | PASS；首轮落盘后取消保留 1 轮、无中位数，本次停止延迟 133 ms；GPU 全屏退后台记录为 interrupted，保持亮屏标志释放 |
| 严格 `mediaOnly` | PASS，21/21 completed；七段软件/系统/播放全部完成，四段 H.264 播放各 120 帧，其余各 30 帧，EOS 与 pending=0；AVC/H.263/MPEG-4 使用 qcom 名称，VP8 使用 Google VPX。另通过 EOS/时间线、连续 Surface、过期回调与取消等待检查 |

Web 专项为 `web-daily-v2`，宿主 `web-host-v2-beea43f1040cbf2d532ce979`，资源清单 SHA-256 `4547e35e130c8bc18bb0bce07d3ac7a47b4dbdf3eb1e365b205fe92ce9cdb873`。物理窗口与 WebView 均为 1280×720，CSS 视口 640×360、DPR/scale=2、文本缩放 100%、硬件加速开启，系统捆绑 WebView、`Date.getTime`（观测分辨率 1 ms）。三轮总任务时间中位数为 page 1193、todo 2647、text 1787、data 4748 ms，各项均含 20 单元；这不是输入延迟、真实显示 FPS 或浏览器综合分数。

完整 `production` **PASS，15/15 阶段全部 completed，0 partial / failed / skipped**。真实“开始测试”按钮触发固定顺序，九个三轮阶段均保留三条原始样本和有效中位数，视频与五项 GPU 各一次；五个 GPU 独立工作进程均为 `diagnostic_duration_ms=0`，正式配置为 Egypt 各 113 秒、T-Rex 各 56 秒、Fill 60 秒。温度、进度、轮间固定 1 秒间隔、完整会话与最新历史持久化断言通过。补跑 `interferenceOnly` PASS，覆盖事件等待/取消竞争、迟到回调、单一后台温度源、无效温度过滤、阶段窗口与清理。

本次原生构建为 `r10e-gcc49-d093143d31fbc32cbdde01c4`，会话 `b39a02d2-a62d-456d-895c-37ead83246b8`，数据库行 12；`wall_ms=1010097`（16 分 50.097 秒，已有资源缓存）。全程接通电源，会话电池温度 25.4→25.5°C，采样峰值 25.6°C；这些读数不是 GPU/CPU 结温，也不能据此判断降频或代表全面温升表现。

| 正式 GPU / 渲染尺寸 | 原始速率 | 阶段墙钟 / PID | 窗口提交 / HUD |
|---|---:|---|---|
| Egypt 离屏 / 1920×1080 | 26.3961 fps | 125081 ms / 6805 | 0 次窗口交换，HUD none / 0 次更新 |
| Egypt 屏上 / 1280×720 | 30.4851 fps | 124004 ms / 7224 | 3447 次成功窗口交换，HUD 更新 122 次 |
| T-Rex 离屏 / 1920×1080 | 12.8684 fps | 75833 ms / 7277 | 0 次窗口交换，HUD none / 0 次更新 |
| T-Rex 屏上 / 1280×720 | 18.2784 fps | 75510 ms / 7326 | 1026 次成功窗口交换，HUD 更新 74 次 |
| Fill 离屏 / 1920×1080 | 2403 MTexels/s | 61750 ms / 7417 | 0 次窗口交换，HUD none / 0 次更新 |

GPU 均由工作进程记录为 Qualcomm / Adreno (TM) 320。阶段墙钟包含加载与回收；原始 fps、窗口交换次数和 HUD 最近约一秒的提交速率是不同口径，不解释为实际屏幕显示帧率。截图实见 Egypt 的 HUD 为 FPS（提交）25.0 / 电池温度 25.6°C，T-Rex 为 19.0 / 25.5°C，场景铺满窗口、取消按钮叠加。结束后重新打开应用，竖屏结果页显示相同会话与构建。

| 当前完整会话非 GPU 中位数 | 原始值 |
|---|---|
| CoreMark | 单线程 6075.88、并行 10873.00 iterations/s |
| SciMark | FFT 372.30、SOR 375.74、Monte Carlo 92.93、稀疏矩阵 311.70、LU 607.23 MFLOPS |
| zlib | 压缩单线程/并行 12.31 / 24.77 MB/s，解压 122.68 / 246.34 MB/s |
| 加密与摘要 | GCM 加密单线程/并行 15.82 / 28.12 MB/s，解密认证 15.66 / 25.43 MB/s，SHA-256 73.95 / 122.70 MB/s |
| 图片 / 视频 | 六图各三轮并按素材汇总中位数；七段视频三条路径均 completed（21/21） |
| 内存 | 复制 4692.99、填充 7198.11 MB/s，延迟 141.19 ns；Triad 单线程/并行 1496.36 / 3032.69 MB/s |
| 文件存储 | 顺序写/读 10.88 / 72.84 MB/s，随机写/读 784.57 / 2889.37 IOPS |
| SQLite | 插入/更新/删除 43.84 / 34.13 / 29.71 TPS |
| Web 四项各 20 单元总时间 | page 1134、todo 2719、text 1767、data 4670 ms；独立阶段墙钟 39814 ms |

上表为同一次完整会话，前文 Web 专项中位数属于独立运行；不混合原始轮次。当前全屏/HUD 构建也不与历史 1280×251 小窗口 GPU 直接作提速比较。

| 当前验证产物（`app/build/reports/`，不纳入源码提交） | SHA-256 |
|---|---|
| `mi2a-web-v2-hud.json`（20593 bytes） | `ef4765c9c4416f0bffbb773fcb8a85e4d56135acdd9f7e3320880440ed675902` |
| `mi2a-media-hud.json`（141412 bytes） | `6b0529e596785c2f3cb738890c8c6b20c9db647477e95e23c3cb9fd564d0ba9e` |
| `mi2a-production-v3-hud.json`（737411 bytes） | `59561f39765d48b968b0fe7d27b8f761a813f650896d577804e772b65c143550` |
| `mi2a-v3-hud.db`（987136 bytes） | `eb400e2530d08dd2d1b78100d36d9c6b1a29ae8068ff2136d28c9f9784ad6bd2` |

数据库分析副本 `PRAGMA integrity_check=ok`，最新行 12 为相同会话/15 阶段/completed；行 10 cancelled、行 11 interrupted 是本批生命周期检查的预期结果。通过应用身份复制导出后拉取，设备 `/sdcard` 中转文件已删除；未更改应用历史。验证产物仍在忽略的 build 目录，构建清理会删除，正式交付需另外归档。

本批 APK SHA-256：Debug `cbb371d263dae4060a9df09e1eb20ce7e745ff4f57b6ce8ef6a82ea8a1c0ac1c`（103173698 bytes）；测试 APK `72a998c6b0751ba47af75228c6e3bb9e2889f9110a34de569b765138d18bd8eb`（124779 bytes）。

## Web v2 / 全屏 / GPU HUD：三星 GT-I9260 实测（2026-11-15）

用户连接三星后，仅对 `cff030496503230` 执行本批测试；Android 4.1.2 / API 16，ARMv7 NEON、双 Cortex-A9、PowerVR SGX 544。覆盖安装与上节小米相同的 Debug / 测试 APK，保留已有历史，没有清数据、root、修改设备时钟或性能设置，也未执行 `am kill-all`。安装前 98%、USB powered、电池温度 26.1°C；Web 专项快照可用内部空间 3474927616 bytes。设备时钟为 2012-01-07，记录日期采用宿主 2026-11-15，耗时使用单调时钟。

先实际唤醒并滑动解锁，确认 `mCurrentFocus=MainActivity` 与竖屏截图后才开始。以下先列 Binder 清理修复前、原生构建 `r10e-gcc49-d093143d31fbc32cbdde01c4` 的独立专项；后面的修复后验收另列，不合并旧三星媒体修复构建。

| 当前构建专项 | 结果与实际覆盖 |
|---|---|
| `displayOnly` | PASS；三次 Surface 创建/销毁，GPU 全屏 1280×720、真实窗口交换和 HUD 更新，SD 视频、runner Web 三轮与中位数、返回竖屏 |
| `webOnly + webFaults` | PASS；四项 × 20 单元 × 三轮，独立 PID 3010 / 3038 / 3068；身份/持久化、错误结果与计时拒绝、部分保留、过期请求、进程死亡、准备/测量 JS 卡死、取消、Home 与失败后重启 |
| `lifecycleOnly` | PASS；首轮保存后取消，保留 1 轮、无中位数，停止延迟 129 ms；真实 GPU 退后台 interrupted，并释放亮屏 |
| 严格 `mediaOnly` | PASS，21/21 completed，矩阵 `wall_ms=75690`；四段 H.264 播放各 120 帧、其余各 30 帧，EOS 与 pending=0；Surface 连续获取/过期回调/取消等待及帧数边界检查通过 |

Web 专项宿主与资源身份和上节一致，物理窗口/WebView 1280×720、CSS 640×360、DPR/scale=2、文本缩放 100%、硬件加速开启；系统捆绑 WebView、`Date.getTime`，观测分辨率 1 ms。三轮 20 单元总时间中位数为 page 1539、todo 2707、text 2044、data 5128 ms。这些是应用任务与布局检查点耗时，不是触摸延迟或真实显示 FPS。

媒体中 H.264/H.263/MPEG-4 系统与播放由 `OMX.TI.DUCATI1.VIDEO.DECODER` 完成。High/FHD 的容器时间线修复继续有效。VP8 系统首选 `OMX.SEC.vp8.dec` 因 `first_frame_pixels_mismatch` 失败，播放首选进程退出/崩溃；Google VPX 后备分别完成，播放前 `surface_destroyed_before_fallback=true`，最终 30 帧、EOS、pending=0。保留全部 attempts，不能将后备成功写成 SEC 组件已修复。TI 专有颜色布局不可读，硬件类型继续 `unconfirmed`。

### 首次完整流程失败与 Binder 清理修复

以上专项属于修复前 APK（与小米同哈希）。首次完整流程起始为 100%、USB powered、33.1°C；CoreMark、SciMark、zlib、加密与图片五阶段已保存，进入视频后宿主进程崩溃，`production` 没有 PASS。数据库行 8、会话 `af2a4f63-02c1-46e2-b460-ff98e9b768ad` 在崩溃前为 running / 5 个结果；分析副本单独保留，不能与复测拼接。

设备没有重启。日志为宿主 PID 4297、线程 4313 的 `JNI ERROR (app bug): accessed deleted global reference`，SIGSEGV / `0xdeadd00d`，栈经 `DeathRecipientList::find`、Binder JNI；此前工作进程已经退出。这与计时外的 `WorkerProcess.close()` 在死亡通知唤醒后再次 `unlinkToDeath` 相符，不能归为 TI 输入容量、Surface 尺寸或内存不足。

对照 AOSP `android-4.1.2_r1` 的 `core/jni/android_util_Binder.cpp`：`binderDied` 调用 Java 回调后才把强全局引用降为弱引用并删除强引用，`matches` 另一路读取该引用。Java 的 latch 并不等待整个原生通知返回，后续 `unlinkToDeath` 查找可能与这一转换竞争。修复移除一次性工作进程结束后的显式 unlink，保留死亡通知、杀进程、异步解绑、硬时限及回收失败后停止后续负载；正常路径等待死亡，超时路径保留注册直到代理/进程释放，避免再次进入同一竞态。Media/GPU/Web 仅更新共用方法签名，未改变负载或通过门槛。

首轮日志 `app/build/reports/i9260-production-first-crash.log`（888774 bytes）及数据库 `i9260-first-crash.db`（724992 bytes）已导出，设备中转文件已删除。修复后先重新构建并在 API 16 x86 模拟器执行退出/取消/超时/重启回归，再重做三星最终验收；后续结果单独记录，不覆盖此次失败。

### Binder 修复后的独立回归

重新构建 Debug、Release、测试 APK，lintDebug / lintRelease 通过；11 个共享库的 API 8/9 导入、SysV hash、动态依赖、无 RPATH/TEXTREL 检查通过，无 NEON 主库筛查为 0。API 16 x86 模拟器通过 `displayOnly`、`webOnly + webFaults`、`lifecycleOnly` 和 `fixedOnly`；媒体专项为七段软解完成、系统/播放如实保留部分 `drain_output` 失败，不冒充严格 21/21。

模拟器生命周期首次在 runner 已中断而 Activity 尚未完成 `onStop` 时提前检查亮屏标志失败；测试改为最多 5 秒等待实际标志清除，仍要求释放，复测 PASS（停止延迟 89 ms），产品生命周期实现未改。额外 `gpuOnly + fullGpu` 在 API 16、API 29 及 API 29 显式 SwiftShader / 关闭 Vulkan 的尝试均未完成：推进至 T-Rex 离屏时模拟器进程退出、ADB 设备消失；最后一轮宿主 emulator 37.2.13 退出码 1。尚未定位该环境退出原因，不记 GPU 专项 PASS，也不将之归因于本次 Binder 修复。

三星在修复版覆盖安装后重新解锁并取得真实焦点，`displayOnly`、`webOnly + webFaults`、`lifecycleOnly`、严格 `mediaOnly`、`interferenceOnly` 均 PASS。窗口 1280×720，Web 四项 × 20 × 3 完整；Web PID 5274 / 5303 / 5339，专项中位数 page 1494、todo 2748、text 2079、data 5589 ms。宿主身份更新为 `web-host-v2-024d03561b8e7f678e773227`，固定资源 SHA-256 不变，不与修复前宿主身份混合汇总。生命周期取消保留 1 轮、无中位数，停止延迟 116 ms；GPU 后台中断与亮屏释放通过。严格媒体再次 21/21，TI 各档与 Google VPX 后备完成，全部尝试继续保留。

### 修复后完整正式流程：15/15 completed

一次 `production` 启动预检被锁屏拒绝，未进入负载，不作性能结果。再次唤醒解锁并验证真实焦点后重跑，起始 100%、USB powered、29.8°C，未清缓存或回收其他应用后台。

完整 `production` 流程/计时检查 **PASS，15/15 阶段 completed，0 partial / failed / skipped**。当前原生构建 `r10e-gcc49-8bfd6c4ebf061eb51f3bec46`，会话 `da9e89ea-256d-42f5-aab1-2def9f7a4286`，`wall_ms=1229389`（20 分 29.389 秒，已有资源缓存）。九个三轮阶段均有三条原始样本与有效中位数；视频与五项 GPU 各一次，GPU 均为独立 PID、`diagnostic_duration_ms=0`，Egypt 各 113 秒、T-Rex 各 56 秒、Fill 60 秒。包含加载与回收的阶段墙钟另列，不当作纯计时时长。

完整会话媒体同样 21/21 completed：四段 H.264 播放各 120 帧，其余各 30 帧，EOS / pending=0；High/FHD 的系统与播放继续为核验 Extractor DTS 后提交容器 PTS、TI 首选完成。VP8 系统 SEC 首选像素校验失败，播放 SEC 首选进程退出/崩溃，Google VPX 后备完成；失败播放 attempts 的 `surface_destroyed_before_fallback=true`。组件原生失败没有被删去，不能表述为全部首选解码器成功或硬件类型已确认。

完整会话电池温度 29.8→35.8°C、采样峰值 35.8°C、温升 +6.0°C，全程 USB powered。温度来自电池广播，不是芯片结温，不据此判断降频原因。

| 正式 GPU / 渲染尺寸 | 原始速率 | 阶段墙钟 / PID | 窗口提交 / HUD |
|---|---:|---|---|
| Egypt 离屏 / 1920×1080 | 10.2412 fps | 139545 ms / 7693 | 0 次窗口交换，HUD none / 0 次更新 |
| Egypt 屏上 / 1280×720 | 12.8637 fps | 141628 ms / 7725 | 1455 次成功窗口交换，HUD 更新 138 次 |
| T-Rex 离屏 / 1920×1080 | 3.1436 fps | 103714 ms / 7774 | 0 次窗口交换，HUD none / 0 次更新 |
| T-Rex 屏上 / 1280×720 | 5.5804 fps | 105967 ms / 7807 | 314 次成功窗口交换，HUD 更新 102 次 |
| Fill 离屏 / 1920×1080 | 508 MTexels/s | 63055 ms / 7841 | 0 次窗口交换，HUD none / 0 次更新 |

五项均报告 Imagination Technologies / PowerVR SGX 544。HUD 每 1000 ms 发布，更新次数覆盖窗口存在期间的加载/测量等过程；原始速率、窗口交换次数及最近一秒提交速率口径不同，不是真实显示 FPS。截图确认独立全屏、悬浮取消和 HUD；实时 Egypt 截图为 FPS（提交）13.0 / 34.9°C，另存 PNG 为 14.0 / 35.3°C。T-Rex 屏上有实际 HUD 更新证据，但本次未及时取得该场景截图，不补写截图结论。

**画质待查：** Egypt 截图的部分水面/透明叠加区域出现彩色异常，取消按钮附近也可见异常背景。尚未区分实屏渲染、驱动与截图合成原因，没有像素基准证明画质正确。保存 `i9260-egypt-hud.png`；上表仅保留原始观测，在异常排除前不用于设备排名或宣称等价画质的 GPU 性能比较。完整流程 PASS 不等于画质验收通过。

| 同次完整会话非 GPU 中位数 | 原始值 |
|---|---|
| CoreMark | 单线程 4162.32、并行 8327.67 iterations/s |
| SciMark | FFT 198.26、SOR 313.50、Monte Carlo 73.28、稀疏矩阵 179.88、LU 222.00 MFLOPS |
| zlib | 压缩单线程/并行 11.17 / 21.27 MB/s，解压 91.39 / 176.35 MB/s |
| 加密与摘要 | GCM 加密单线程/并行 12.22 / 24.35 MB/s，解密认证 12.09 / 23.96 MB/s，SHA-256 46.45 / 92.78 MB/s |
| 图片 / 视频 | 六图各三轮、按素材汇总中位数；七段视频三条路径全部 completed（21/21） |
| 内存 | 复制 669.49、填充 2500.77 MB/s，延迟 124.69 ns；Triad 单线程/并行 993.02 / 1179.66 MB/s |
| 文件存储 | 顺序写/读 6.80 / 25.56 MB/s，随机写/读 232.91 / 2125.85 IOPS |
| SQLite | 插入/更新/删除 15.31 / 15.13 / 14.38 TPS |
| Web 四项各 20 单元总时间 | page 1574、todo 2747、text 2131、data 5524 ms；阶段墙钟 45895 ms |

完整 Web 三轮使用修复后宿主身份，物理窗口 1280×720 / CSS 640×360，专项与完整会话中位数分开。数据库行 12 重新读取为同一会话 / 15 阶段 / completed，分析副本 `PRAGMA integrity_check=ok`；行 8 是首轮崩溃后恢复为 interrupted 的五阶段会话，行 10 cancelled、行 11 interrupted 是复测生命周期的预期结果。再次打开应用并解锁后，竖屏结果页显示同一会话和构建，截图另存 `i9260-results.png`。

| 本批验证产物（`app/build/reports/`，不纳入源码提交） | SHA-256 |
|---|---|
| `i9260-web-v2-hud-binderfix.json`（20709 bytes） | `be29683ab9ae545df9b23a4270f5ebc00a941143d5c85fb9e04c5de1cb90e13b` |
| `i9260-media-hud-binderfix.json`（146910 bytes） | `ac95c04e5e5d49607834508e749fb581d6f1e1da2aa13ef50da4ee513d08504d` |
| `i9260-production-v3-hud-binderfix.json`（736169 bytes） | `7c4e59f372ef3b1ad07298c425c4aa235b0721df531cedf60b2c1509198cef5c` |
| `i9260-v3-hud-binderfix.db`（1134592 bytes） | `c0042bcab8fb5e1e42bc57009106cbfedc25802ff3e372f098c05b8d6a0ab403` |
| `i9260-egypt-hud.png`（1384553 bytes） | `73e6dfa73d9ed821c7ff8054d47f4640521f181c7ef42b265b631191e2a22335` |

修复后 APK：Debug SHA-256 `5475162d761445e78444afd6161e74b1d10457ed95fac5071407480807e43ac7`（103173705 bytes）；测试 APK `bcfe350dc5bfaedbeedd6f5559aee75d306f9d7ff63e2c29cb2ee8d4c08b3bf6`（124968 bytes）。设备 `/sdcard` 中转文件已删除；原始证据在忽略的 build 目录，构建清理会删除，正式交付需另行归档。小米上节使用修复前 APK，本次没有重新连接小米，不将三星修复后的结果扩展到小米或 Release。

以下保留既有 v2 证据，不能作为新构建的补充通过记录。

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

小米 2A 当前 Web v2 / 全屏 / HUD 构建已完成上文完整 15 阶段；三星最终媒体修复后仍未重跑完整套件，当前构建也未在三星复测。此次真机使用 Debug，没有 Release 真机、其他真机交叉回归或干净安装验收。Google H.264 High 后备能力仍未验证通过，SEC VP8 组件原生崩溃仍存在；TI/qcom 专有输出像素、Surface 画质、真实显示帧率/帧数和硬件类型仍未取得直接证据。

两台均为双核 NEON/API 16，尚不能覆盖无 NEON ARM、API 8–15 ARM 运行时、8 核真实并行、原生 x86 真机、API 29+ 属性分支。干净安装/首次解包峰值、运行内存峰值、GPU 片元精度、全面温升/降频与发布签名也未验收。没有宿主开销优化前后的真机 A/B，以上 GPU 数据不是官方 GLBenchmark/GFXBench 成绩。
