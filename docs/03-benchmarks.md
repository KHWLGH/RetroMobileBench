# 03 固定测试项目与组件

2026-11-14 更新。产品只有一键固定顺序，内部子项不成为选择菜单；8 个阶段自动三轮取中位数，视频与各 GPU 模式一次，无总分。会话协议为 `fixed-suite-v2`。范围依据 [12](12-test-suite-plan.md)，实现/验证依据 [13](13-suite-implementation.md)。

| 顺序 | 协议 | 内容与参数 |
|---|---|---|
| 1 | cpu-coremark-v2 | CoreMark 单线程 + N；2000 字节、两套种子、正式每阶段 ≥10 秒；仅修改 port，保留上游校验/编译输出 |
| 2 | cpu-scimark-small-v1 | 单线程 FFT/SOR/Monte Carlo/Sparse/LU，small 数据集、seed 101010、各 ≥1500 ms，逐项数值校验 |
| 3 | cpu-zlib-v2 | zlib 1.3.1，1 MiB 固定语料、level 6，单线程及 N 个独立流压缩/解压，各 ≥1500 ms，前后回读校验 |
| 4 | cpu-crypto-v1 | Mbed TLS 2.28.10，AES-128-GCM 加密/解密认证和 SHA-256；256 KiB 每线程独立缓冲区；已知向量/错误标签在计时外验证 |
| 5 | image-decode-v1 | libjpeg-turbo 2.1.5.1 标量 accurate DCT + libpng 1.6.43；480p/720p/1080p 六图；压缩数据预载，真实解码到 RGBA8，MPix/s、ms/张、逐字节参考像素校验 |
| 6 | video-decode-v1 | FFmpeg 4.4.6 固定软解、命名系统解码、PTS 节奏播放；四档 H.264 和三种旧格式探测，完整矩阵见下 |
| 7 | memory-tiny-v2 | tinymembench C 复制/填充，8 MiB；RMB 4 MiB 依赖访问延迟；增加单线程 + N 线程 STREAM Triad 变体，三数组总工作集固定 24 MiB，各 ≥1500 ms |
| 8 | storage-file-v1 | 私有内部存储，4–32 MiB，顺序256 KiB/随机4 KiB，256随机操作，O_SYNC 写/O_DIRECT 读；回退含缓存时明确标记 |
| 9 | sqlite-tps-v1 | 固定 SQLite 3.53.1，INSERT/UPDATE/DELETE 各300事务；每事务一行1 KiB，DELETE日志/FULL同步；独立于历史库 |
| 10–13 | gpu-<test>-v1 | Egypt离屏113s、屏上113s，T-Rex离屏56s、屏上56s；ETC1，离屏1080p，屏上实际窗口尺寸 |
| 14 | gpu-gl_fill_off-v1 | Fill 1080p 离屏60s；MTexels/s，独立纹理填充吞吐，不当作显存峰值带宽 |

CoreMark、SciMark、zlib、加密、图片、内存、文件存储和 SQLite 各连续三轮，轮间1秒，无末尾等待；视频矩阵与五个 GPU 模式各一次。GPU配置合计398秒（6分38秒），加载另计。图片按素材ID/哈希与参考像素匹配三轮，分别汇总MPix/s和ms/张；原始值保留。

Triad复用STREAM运算内核 `a=b+3*c`，三个double数组各8MiB，1与N线程均共享固定24MiB总量，按缓存行边界划分互不重叠区间。初始化/预热/全量校验在计时外，共同起点与截止，用总元素数×24字节/墙钟秒数/1e6计算MB/s。不是标准STREAM成绩或纯DRAM峰值；缓存和调度均会影响结果。

CoreMark、zlib、加密、Triad为 1+N；单核省掉重复并行阶段。N 根据实际拓扑确定，支持8线程以上，当前容量64，超限明确拒绝。SciMark 保持单线程。

## 视频固定矩阵

| ID | 码流 | 尺寸 | 片长 |
|---|---|---|---|
| h264-sd | H.264 Baseline level3.0 | 854×480 | 4s/120帧 |
| h264-hd | H.264 Baseline level3.1 | 1280×720 | 4s/120帧 |
| h264-hd-high | H.264 High，CABAC/B帧 | 1280×720 | 4s/120帧 |
| h264-fhd | H.264 High | 1920×1080 | 4s/120帧 |
| h263 | H.263 | 352×288 | 1s/30帧 |
| mpeg4 | MPEG-4 Part2 | 640×480 | 1s/30帧 |
| vp8 | VP8 | 640×480 | 1s/30帧 |

ID、profile/level、码率、GOP、参考帧、编码命令与哈希以 [固定清单](../app/src/main/assets/media/manifest.json) 为准。全部30fps、8-bit YUV420、无音轨，来自 Sintel 短片。吞吐真实输出完整帧；软件校验首帧完整YUV，系统路径检查尺寸/PTS/EOS并在可读线性布局抽样亮度。不可读取的画质/实际显示帧数不估算。系统解码输出fps与屏幕真正显示fps分别解释。

API8–15只有 MediaPlayer 系统播放，硬解类型未确认；API16命名同步MediaCodec；API29属性单独隔离。软件、系统和播放分别记录成功/不支持/失败/超时/低于实时等结果。一个档位失败不推断其他档位失败。

## 删除与历史

Zstd、完整STREAM四内核扫描、ALU/Driver/Blending 退出产品流程；仅恢复Triad并入内存阶段，以及GPU Fill，必要诊断代码与旧记录解释保留。LZMA SDK、multichase、完整 SQLite speedtest1 从候选删除。网络、编码、音频、4K、HEVC/VP9/AV1 不在本轮范围。

旧 fixed-suite-v1、memory-tiny-v1、cpu-zlib-v1、cpu-coremark-v1、cpu-zstd-v1 等记录仍可读；不与新并行协议混合汇总。GPU 来自后续开源GFXBench ES2路径，不宣称与原版GLBenchmark官方分数等价。

依赖提交、许可和适配见 [third_party](../third_party/README.md)；FFmpeg LGPL分发见 [媒体分发说明](media-distribution.md)。
