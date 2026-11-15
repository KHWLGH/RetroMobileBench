# 测试与结果

RetroMobileBench 使用 `fixed-suite-v3` 固定套件，按下表顺序串行执行。内部阶段用于调度和结果解释，界面不提供单项、线程、分辨率、轮数或软硬解选择。完整流程与资源准备见 [开发与构建](development.md)，已获得的设备证据见 [验证与已知限制](validation.md)。

## 固定项目

| 顺序 | 项目 / 协议 | 固定负载与主要输出 | 轮数 |
|---|---|---|---|
| 1 | CoreMark / `cpu-coremark-v2` | 单线程 + N 线程；2000 字节工作区、两套种子，各正式计时至少 10 秒；iterations/s | 3 |
| 2 | SciMark / `cpu-scimark-small-v1` | 单线程 small 数据集，FFT、SOR、Monte Carlo、Sparse、LU；seed 101010，各至少 1500 ms；各内核 MFLOPS | 3 |
| 3 | zlib / `cpu-zlib-v2` | zlib 1.3.1，固定 1 MiB 语料、level 6；单线程 + N 个独立流，压缩与解压各至少 1500 ms；MB/s | 3 |
| 4 | 加密 / `cpu-crypto-v1` | Mbed TLS 2.28.10，AES-128-GCM 加密、认证解密及 SHA-256；每线程 256 KiB 独立缓冲区，单线程 + N，各至少 1500 ms；MB/s | 3 |
| 5 | 图片 / `image-decode-v1` | 固定 libjpeg-turbo 2.1.5.1 与 libpng 1.6.43，480p / 720p / 1080p JPEG、PNG 共六图，解码到 RGBA8；MPix/s、ms/张 | 3 |
| 6 | 视频 / `video-decode-v1` | 七段固定视频的软件吞吐、系统解码与实时播放，分别记录状态、帧数、时间和正确性证据 | 1 |
| 7 | 内存 / `memory-tiny-v2` | 8 MiB C 复制/填充、4 MiB 依赖访问延迟；另测单线程 + N 的固定总量 24 MiB Triad；MB/s、ns | 3 |
| 8 | 文件存储 / `storage-file-v1` | 内部私有目录，4–32 MiB 文件；顺序块 256 KiB、随机块 4 KiB、256 次随机操作；MB/s、IOPS | 3 |
| 9 | SQLite / `sqlite-tps-v1` | SQLite 3.53.1；INSERT / UPDATE / DELETE 各 300 个独立事务，每事务一行 1 KiB，DELETE 日志、FULL 同步；TPS | 3 |
| 10 | Web 日常响应 / `web-daily-v2` | 离线系统 WebView，独立横向全屏；页面/待办/文本/数据四项各 20 单元，记录实际 CSS/物理视口；总 ms、ms/单元，越低越好 | 3 |
| 11 | Egypt HD 离屏 / `gpu-gl_egypt_off-v1` | ETC1，1920×1080，113 秒；frames、FPS | 1 |
| 12 | Egypt HD 屏上 / `gpu-gl_egypt-v1` | ETC1，实际窗口尺寸，113 秒；frames、FPS | 1 |
| 13 | T-Rex 离屏 / `gpu-gl_trex_off-v1` | ETC1，1920×1080，56 秒；frames、FPS | 1 |
| 14 | T-Rex 屏上 / `gpu-gl_trex-v1` | ETC1，实际窗口尺寸，56 秒；frames、FPS | 1 |
| 15 | Fill 离屏 / `gpu-gl_fill_off-v1` | ETC1，1920×1080，60 秒；MTexels/s | 1 |

九个三轮阶段各自连续运行三轮，轮间固定 1 秒；阶段间也固定 1 秒，最后不额外等待。五项 GPU 配置合计 398 秒，不含加载、资源准备和间隔。Web 阶段上限 330 秒，异常回收另加至多 10 秒；整套耗时受设备和资源缓存状态影响，不能沿用 v2 耗时作为 v3 实测。Web 的负载与计时见下方 [Web 日常响应](#web-日常响应)。

### 线程与计时

CoreMark、zlib、加密和 Triad 采用单线程 + N 线程；单核省去重复的并行 1 线程。N 综合 CPU present、CPU 目录、在线状态和运行时报告确定，缺少实际拓扑时才使用 configured 后备值，不盲信 possible。当前资源容量为 64 个工作线程，超限明确拒绝，不静默截断。

原生计时使用 `CLOCK_MONOTONIC` 墙钟。预热、已知向量和正确性检查放在各协议规定的计时窗口之外；初始化、实际操作和校验的边界必须随协议解释。系统负责热插拔、调度和调频，应用不要求 root、不绑核、不改 governor。创建 N 个线程不能证明 N 个物理核始终同时运行；运行前后的在线数和频率也只是快照。

### 正确性

CoreMark 保留上游核心文件，仅自定义 port，正式结果要求两套种子校验及最短时间通过。SciMark 使用独立计时和数值校验，展示内核 MFLOPS，不作为原版 SciMark 综合成绩。zlib 在计时前后验证压缩与回读数据。

加密使用独立线程上下文，计时外验证 AES/GCM/SHA 已知向量、错误标签拒绝和完整输出。所有已创建线程 join 后才回读输出，避免校验活动干扰仍在计时的线程。

图片在计时前校验并载入压缩数据、准备可复用输出缓冲区，计时包含真实 RGBA8 解码，不含磁盘读取。JPEG 固定标量 accurate 整数 DCT / fancy upsampling，参考像素由同版 djpeg 生成；PNG 保留非预乘 RGBA 和透明通道。两者逐字节验证参考像素，计时输出哈希也必须匹配。

## 视频矩阵

| 素材 ID | 码流 | 尺寸 | 片长 / 帧数 |
|---|---|---|---|
| `h264-sd` | H.264 Baseline level 3.0 | 854×480 | 4 秒 / 120 帧 |
| `h264-hd` | H.264 Baseline level 3.1 | 1280×720 | 4 秒 / 120 帧 |
| `h264-hd-high` | H.264 High，CABAC / B 帧 | 1280×720 | 4 秒 / 120 帧 |
| `h264-fhd` | H.264 High | 1920×1080 | 4 秒 / 120 帧 |
| `h263` | H.263 | 352×288 | 1 秒 / 30 帧 |
| `mpeg4` | MPEG-4 Part 2 | 640×480 | 1 秒 / 30 帧 |
| `vp8` | VP8 | 640×480 | 1 秒 / 30 帧 |

全部为 30 fps、8-bit YUV420、无音轨。源、profile/level、码率、GOP、参考帧、生成命令和 SHA-256 以 [固定素材清单](../app/src/main/assets/media/manifest.json) 为准。视频与 JPEG 来自 Sintel 衍生素材，署名见 [许可与分发](licenses.md)。

每段素材独立尝试三条路径：

- **软件吞吐**：固定 FFmpeg 4.4.6 标量解码，不按播放速度节流，真实输出完整 YUV420 帧并校验首帧完整哈希。吞吐窗口包含缓存文件读取和解封装，记录请求线程数与实际线程类型；不保证所有格式都能用满 N 个线程。
- **系统吞吐**：API 16 起使用命名同步 MediaCodec，按确定规则选候选，主候选失败后最多一个后备。实际送入/取出数据，检查尺寸、PTS、EOS，并对可读线性布局抽样亮度；无法读取的布局明确记为画质抽查不可获得。
- **实时播放**：按 PTS 节奏向 Surface 提交，记录可取得的解码/提交数量、播放结果和低于实时速度等情况。API 8–15 使用 MediaPlayer，命名解码吞吐路径不可用。

API 16–17 的固定 B 帧 AVC 素材在计时前增加输入时间线预检：只用已捆绑 FFmpeg 的 MP4 解封装读取 PTS/DTS，不打开软件解码器；与系统 Extractor 逐样本核对 Annex-B SHA-256、大小、数量和顺序。仅当整条原始时间线确实对应归零 DTS 时，才向 MediaCodec 提交同一压缩样本的容器 PTS；正确的 PTS 保留原值，包括固定时间起点及微秒换算差异。样本不匹配或时间损坏不能确认时失败。JSON 保存输入时间来源、逐样本校验数量、原始/容器 PTS 和预检耗时；解码计时窗口仍包含平台样本读取与系统解码，预检在窗口外。输出 PTS 不排序或重写，原单调性、EOS 和完整帧数检查继续执行。

API 8–15 的系统播放成功只能证明播放路径成功，硬件类型为未确认。API 16–28 保留解码器名称和实际输出证据，厂商名称本身不是硬解证明；API 29+ 额外记录来自系统声明的硬件/软件属性。解码输出 FPS、向 Surface 提交的 FPS 与屏幕真正显示 FPS 分开解释，无法取得的显示帧数或画质指标不估算。

软件、系统和播放各自保存成功、不支持、失败、超时等状态；某一档位失败不推断其他档位失败。校验失败不得保留正常性能成绩。

## Web 日常响应

`web-daily-v2` 使用系统 WebView 的独立横向全屏窗口，覆盖 JavaScript、DOM、样式与布局。它是原创离线应用任务，不是纯 CPU 分数或 Speedometer 官方成绩。旧 v1 小视口成绩继续可读，与 v2 分开比较。

### 固定负载与语料

四项各 20 个正式工作单元，每轮 80 个、三轮 240 个。每轮使用新的 `:web` 一次性进程，先对各项预热、校验并复位一个单元，再开始测量；不按设备速度动态减量。

| 分项 | 一个工作单元 | 每轮正式工作量 |
|---|---|---|
| 页面构建 | 清空后创建 32 卡片，每卡 8 元素节点，应用样式并读取布局 | 640 卡片 / 5120 卡片元素 |
| 待办交互 | 添加 100 项，切换前 50 项；all/active/completed/all 过滤；删除全部 | 添加 2000、切换 1000、过滤 80、删除 2000 |
| 文本排版 | 24 段 × 256 ASCII 字符；替换 48 短语、突出 240 关键词；折叠/展开前 12 段；恢复 | 替换 960、突出 4800、折叠/展开 240 对 |
| 数据展示 | 解析 1000 条 × 8 字段 JSON；筛选 group 0–4 共 500 条；按 value 排序、id 打破平局；展示前 100 行 × 6 列，序列化筛选结果 | 解析 20000 条，展示 2000 行 / 12000 数据单元格 |

JSON 字段为 `id, group, value, price, enabled, name, description, weight`；name/description 分别 32/96 ASCII 字符，十个分组等量。`web-corpus-v1` 使用种子 101010 和 32 位 LCG `(1664525*s+1013904223) mod 2^32`，公式与生成顺序见[配方](../app/web-assets.gradle.kts)，字节和 SHA-256 受[语料锁](../app/web-corpus-lock.json)约束。

六张原创合成图为 4 JPEG + 2 PNG，均 320×180。预加载、尺寸检查和文本准备在计时外，不测冷加载或图片解码吞吐。每轮 Java 校验整个资源清单；JS 核对元素数、状态、文本、完整排序 ID 和序列化长度/校验值。

### 窗口、计时与比较身份

WebView 填满横向内容窗口，取消按钮叠加。页面使用 `width=device-width`、100% 宽度、固定两列卡片和全宽表格；字体 12 CSS px、行高 18 CSS px、文本缩放 100%。实际 CSS/物理视口均记录，测量中尺寸变化则中断。

每项从首个正式单元前计时，到第 20 单元的任务和布局检查点完成；单元间 `setTimeout(0)` 调度开销计入。任务状态检查属于操作，完整正确性核对、IPC 和汇总在停表后。总 ms 与 ms/单元越低越好；不以 `onPageFinished` 代替绘制完成，不报告真实输入延迟或显示 FPS。

优先 `performance.now`，后备 `Date.getTime`，预热前探测可观察分辨率。非法、倒退、非正时间和总量不足 20 个可观察刻度均拒绝，不自动加量。三轮完整，且资源 SHA-256、Web 宿主构建、系统/引擎、WebView 包版本、视口、缩放、硬件加速和时钟身份一致，才生成中位数。窗口身份还包含 `window_mode=fullscreen`、内容窗口、DPR/密度、初始缩放、实际 scale、CSS 到物理像素比例及 NORMAL 布局算法。

API 21 前记录系统捆绑身份；API 21–25 保留 UA/系统并明示提供程序 API 不可获得，API 26 起查询提供程序。Web 不依赖 JNI，不附加伪造的 native 构建身份。

### 时限与失败处理

父进程持有硬截止：每轮启动/预热 15 秒、每个正式分项 20 秒、整个阶段 330 秒，异常回收额外最多 10 秒。JS 卡死仍能取消、超时和回收。请求使用唯一 nonce、阶段顺序及终态封口；已完成分项立即发送父进程，失败保留原始部分结果，迟到消息不能污染后续请求。离开前台记中断，返回/取消记取消；确认进程回收后才继续 GPU，回收失败则停止后续负载。

设计依据（2026-11-15 核对）：[Speedometer 3.1](https://browserbench.org/Speedometer3.1/about.html)的应用任务思路、[onPageFinished 的绘制边界](https://developer.android.com/reference/android/webkit/WebViewClient#onPageFinished(android.webkit.WebView,%20java.lang.String))、[onJsPrompt 控制通道](https://developer.android.com/reference/android/webkit/WebChromeClient#onJsPrompt(android.webkit.WebView,%20java.lang.String,%20java.lang.String,%20java.lang.String,%20android.webkit.JsPromptResult))。本项目不复制其框架或计分；可信内置页的 prompt 立即 confirm 并抑制对话框，IPC 位于任务计时外。验证情况见[验证与限制](validation.md)。

## 内存、存储和 GPU 的可比边界

Triad 复用 STREAM `a=b+3*c` 内核，三个 double 数组各 8 MiB，单线程与 N 线程共享固定 24 MiB 总量。线程按 64 字节边界划分互不重叠区间，统一起点与截止，至少运行 1500 ms；以实际完成元素数 × 24 字节 / 墙钟秒数 / 1e6 计算 MB/s。初始化、预热及 `a=11, b=2, c=3` 全量校验不计时；复制/延迟工作区先释放再分配 Triad。固定工作集受缓存与调度影响，标为 STREAM 变体，不能解释为标准 STREAM 或纯 DRAM 峰值。8 MiB 复制/填充和 4 MiB 依赖链也不是完整缓存层级扫描；复制按读 + 写字节计量。

文件存储默认 32 MiB、最低 4 MiB，并保留 16 MiB 空间。写入使用 `O_SYNC`，读取优先 `O_DIRECT`，不支持时明确标记 `cached`；不声称已驱逐页缓存。文件系统、缓存模式和文件大小不同的结果不直接混算。SQLite 使用固定版本的独立测试数据库，与保存历史记录的系统 SQLite 数据库分开。

离屏 GPU 使用小 pbuffer + 固定 1080p FBO；屏上自动进入独立横向全屏窗口，创建填满窗口的真实 EGLSurface 并交换缓冲区，取消按钮作为浮层。记录窗口归属、全屏模式、实际渲染尺寸、显示刷新率、交换间隔请求、成功交换次数、GPU 身份及工作进程的实际 `egl_config`。屏上使用精确 RGB 8/8/8、alpha 0、无 MSAA 的不透明配置，depth 至少 16 位；离屏保留驱动首选配置。配置选择修复及其证据见 [SGX544 兼容验证](validation.md#sgx544-屏上花屏)，新旧构建分开比较。屏上成绩受分辨率、VSync 和刷新率影响，不与离屏混算，也不把交换次数当成精确显示帧数。

GPU 屏上左上角的“FPS（提交）”以约 1 秒内成功交换次数 / 实际经过秒数计算，电池温度使用系统最新广播读数；缺失温度显示“不可用”。悬浮值只用于观察，不替代正式结果或视为真实显示帧率，也不把电池温度当作 GPU 结温。离屏、Web 与视频不启用该 GPU 悬浮层；结果保留 `onscreen_hud`、`hud_interval_ms` 和 `hud_updates`，构建身份不同的记录仍隔离比较。

Fill 主指标为 MTexels/s，不是显存峰值带宽；完整 60 秒包含上游自适应负载，短诊断不能替代正式成绩。宿主保留既定同步行为，不为提分随意增删 `glFinish`。场景使用后续开源 GFXBench 的 ES2 路径，不宣称等同原版 GLBenchmark / GFXBench 官方分数。

## 结果、保存和运行条件

整次会话使用 schema 2，包含 `session_id`、`suite_version`、构建身份、起止时间、状态和 `results`；各阶段使用 schema 1，保留 `samples`、协议、参数、库/ABI、设备及运行条件。三轮阶段只有全部完成且协议、构建、参数一致时才生成有效中位数，原始轮次始终保留。图片按素材 ID、文件/参考像素哈希、尺寸、格式和库匹配，分别汇总 MPix/s 与 ms/张。

| 状态 | 含义 |
|---|---|
| `completed` | 当前报告要求的工作完成 |
| `partial` | 保留部分有效结果，仍有未完成或失败路径 |
| `unsupported` | 对应能力或路径不支持 |
| `skipped` | 运行前条件不满足，原因随报告保存 |
| `failed` / `timeout` | 执行或校验失败 / 超过时限 |
| `cancelled` / `interrupted` | 用户取消 / 前台、窗口或进程等条件中断 |

每轮完成后写入 `active_report` 检查点；进程重启恢复已保存轮次，将遗留运行阶段标为中断，不接着补跑或合成正常中位数。保存异常与测量状态分开，内存结果和保存告警仍可在结束后导出。JSON 导出复制当前结果或历史记录到剪贴板，运行中禁用。取消立即显示停止反馈，在安全边界结束并记录实际停止延迟。

整次运行共用一个低优先级温度采样线程，约 2 秒读取一次电池广播，各阶段截取同一数据流；边界补点在测量调用之外，阶段结束后才生成温度 JSON。记录起止、采样最大值、温升（结束减开始）、充电状态、来源和时间戳；缺失或异常读数不填 0。电池温度不等于 CPU/GPU 结温，升温本身不能证明降频，也不触发停测或冷却等待。低电量低于 30% 且未充电、真实空间或内存不足仍会明确提示。

不生成综合分，缺项不填零。旧 `fixed-suite-v1` / `fixed-suite-v2`、`memory-tiny-v1` 和早期 CPU 协议记录继续可读，协议、构建、素材或参数不同不混合汇总。Zstd、完整 STREAM 四内核及 ALU / Driver / Blending 保留必要诊断与旧记录解释，不在固定流程中。网络、编码、音频、4K、新视频格式、ES3 和续航压力测试不属于当前套件。

参考：[STREAM 运行规则](https://www.cs.virginia.edu/stream/ref.html)、[MediaCodec](https://developer.android.com/reference/android/media/MediaCodec)、[系统硬件属性](https://developer.android.com/reference/android/media/MediaCodecInfo#isHardwareAccelerated())、[FFmpeg 解码接口](https://ffmpeg.org/doxygen/4.4/group__lavc__encdec.html)。
