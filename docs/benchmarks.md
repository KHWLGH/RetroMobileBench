# 测试与结果

RetroMobileBench 使用 `fixed-suite-v2` 固定套件，按下表顺序串行执行。内部阶段用于调度和结果解释，界面不提供单项、线程、分辨率、轮数或软硬解选择。完整流程与资源准备见 [开发与构建](development.md)，已获得的设备证据见 [验证与已知限制](validation.md)。

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
| 10 | Egypt HD 离屏 / `gpu-gl_egypt_off-v1` | ETC1，1920×1080，113 秒；frames、FPS | 1 |
| 11 | Egypt HD 屏上 / `gpu-gl_egypt-v1` | ETC1，实际窗口尺寸，113 秒；frames、FPS | 1 |
| 12 | T-Rex 离屏 / `gpu-gl_trex_off-v1` | ETC1，1920×1080，56 秒；frames、FPS | 1 |
| 13 | T-Rex 屏上 / `gpu-gl_trex-v1` | ETC1，实际窗口尺寸，56 秒；frames、FPS | 1 |
| 14 | Fill 离屏 / `gpu-gl_fill_off-v1` | ETC1，1920×1080，60 秒；MTexels/s | 1 |

八个三轮阶段各自连续运行三轮，轮间固定 1 秒；阶段间也固定 1 秒，最后不额外等待。五项 GPU 配置合计 398 秒，不含加载、资源准备和间隔。预计整套约 12–15 分钟，具体耗时受设备和资源缓存状态影响。

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

API 8–15 的系统播放成功只能证明播放路径成功，硬件类型为未确认。API 16–28 保留解码器名称和实际输出证据，厂商名称本身不是硬解证明；API 29+ 额外记录来自系统声明的硬件/软件属性。解码输出 FPS、向 Surface 提交的 FPS 与屏幕真正显示 FPS 分开解释，无法取得的显示帧数或画质指标不估算。

软件、系统和播放各自保存成功、不支持、失败、超时等状态；某一档位失败不推断其他档位失败。校验失败不得保留正常性能成绩。

## 内存、存储和 GPU 的可比边界

Triad 复用 STREAM `a=b+3*c` 内核，三个 double 数组各 8 MiB，单线程与 N 线程共享固定 24 MiB 总量。线程按 64 字节边界划分互不重叠区间，统一起点与截止，至少运行 1500 ms；以实际完成元素数 × 24 字节 / 墙钟秒数 / 1e6 计算 MB/s。初始化、预热及 `a=11, b=2, c=3` 全量校验不计时；复制/延迟工作区先释放再分配 Triad。固定工作集受缓存与调度影响，标为 STREAM 变体，不能解释为标准 STREAM 或纯 DRAM 峰值。8 MiB 复制/填充和 4 MiB 依赖链也不是完整缓存层级扫描；复制按读 + 写字节计量。

文件存储默认 32 MiB、最低 4 MiB，并保留 16 MiB 空间。写入使用 `O_SYNC`，读取优先 `O_DIRECT`，不支持时明确标记 `cached`；不声称已驱逐页缓存。文件系统、缓存模式和文件大小不同的结果不直接混算。SQLite 使用固定版本的独立测试数据库，与保存历史记录的系统 SQLite 数据库分开。

离屏 GPU 使用小 pbuffer + 固定 1080p FBO；屏上创建真实窗口 EGLSurface 并交换缓冲区，记录窗口归属、实际渲染尺寸、显示刷新率、交换间隔请求、成功交换次数和 GPU 身份。屏上成绩受分辨率、VSync 和刷新率影响，不与离屏混算，也不把交换次数当成精确显示帧数。

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

不生成综合分，缺项不填零。旧 `fixed-suite-v1`、`memory-tiny-v1` 和早期 CPU 协议记录继续可读，协议、构建、素材或参数不同不混合汇总。Zstd、完整 STREAM 四内核及 ALU / Driver / Blending 保留必要诊断与旧记录解释，不在固定流程中。网络、编码、音频、4K、新视频格式、ES3 和续航压力测试不属于当前套件。

参考：[STREAM 运行规则](https://www.cs.virginia.edu/stream/ref.html)、[MediaCodec](https://developer.android.com/reference/android/media/MediaCodec)、[系统硬件属性](https://developer.android.com/reference/android/media/MediaCodecInfo#isHardwareAccelerated())、[FFmpeg 解码接口](https://ffmpeg.org/doxygen/4.4/group__lavc__encdec.html)。
