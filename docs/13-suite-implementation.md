# 13 固定套件实施记录

日期：2026-11-14。范围依据 [12 计划](12-test-suite-plan.md)。固定套件已接入；构建、静态检查与模拟器证据如下。真机发布验收尚未完成，不把未获得的设备证据记为通过。

## 已接入的产品行为

- 一键自动准备完整离线资源，fixed-suite-v2固定14个内部阶段；CoreMark、SciMark、zlib、加密、图片、内存、文件存储和SQLite各连续三轮取中位数，轮间1秒；视频矩阵与各GPU模式一次；无分类/单项/线程/软硬解/资源包选择，无总分。
- CoreMark和zlib升级v2，1+N统一实际拓扑策略、64工作线程容量，单核省掉重复并行。Zstd、完整STREAM四内核、ALU/Driver/Blending退出正式流程；仅恢复Triad和Fill，旧记录解释保留。
- 加密：MbedTLS 2.28.10 AES-128-GCM加密/认证解密、SHA256；独立线程上下文、已知向量、错误标签验证与回读。
- 六张固定JPEG/PNG（480/720/1080p），libjpeg-turbo标量accurate DCT/libpng解码到RGBA8。JPEG参考由同版主机djpeg生成，两种格式逐字节验证，计时输出哈希也必须匹配。大参考数据流式校验并由原生读取，不经Java大数组复制。
- 七段视频：四档H.264含HD Baseline/High与FullHD，另H.263/MPEG4/VP8兼容探测。固定FFmpeg软解、命名系统吞吐与实时播放分别记录；软件真实YUV帧哈希、系统尺寸/PTS/EOS与可读布局抽样。当前视频的已完成路径会在后续窗口或工作进程异常时保留。
- API8–15使用MediaPlayer，硬件类型未确认；API16同步MediaCodec、至多两个确定候选；API29属性实现隔离。媒体与GPU路径使用一次性进程、父进程时限和回收确认。
- Gingerbread不能通过Surface.writeToParcel转发普通窗口收到的Surface。API8–15的MediaPlayer由媒体进程Activity持有本地窗口，API8–10使用push-buffer holder；私有素材通过文件描述符传入。API8–10屏上GPU同样由GPU进程Activity持有本地窗口。内部窗口切换不打断测试，用户取消/返回/按Home仍会取消或中断并回收进程。
- memory-tiny-v2：复制/填充与依赖链之外，增加1+N线程的固定24MiB STREAM Triad变体，三个double数组各8MiB，线程按64字节边界分区，共同起点/截止，实际元素数×24字节/墙钟时间算MB/s。计时外预热/全量校验，取消或线程启动失败回收已创建线程。单/多线程总量一致，缓存影响明确保留，不称标准STREAM或纯DRAM成绩。
- GPU固定Egypt离屏/屏上、T-Rex离屏/屏上、Fill离屏，配置398秒。Fill完整60秒，主指标MTexels/s。屏上真实window EGLSurface并交换缓冲区，记录window_owner、实际渲染尺寸/刷新率/交换间隔请求/成功交换计数。离屏1080p固定，保留场景192MiB可用内存门槛。
- 整次会话UUID/检查点/汇总/导出，每轮完成即保存active_report；进程退出恢复已完成轮次，当前阶段interrupted，无正常中位数。三轮完成界面优先显示中位数，完整原始轮次留JSON。六图按素材ID/哈希/参考像素等身份匹配，速率和每张耗时分别取中位数。旧fixed-suite-v1与memory-tiny-v1继续读取；保存异常独立告警，已有数据保留。取消立即反馈，停止耗时进入会话；后台/锁屏/Surface丢失记录中断。阶段间1秒，末尾不空等。
- 电池温度约2秒一次，只统计起止/采样峰值/温升/充电状态/来源。高温或缺传感器不触发停测/降温等待。

## 构建与分发

NDK r10e/GCC4.9实际编译API8 ARMv7、API9 x86；主库保留NEON/无NEON/x86，媒体ARM统一VFPv3-D16标量。独立librmbmedia及FFmpeg avutil/avcodec/avformat，旧链接器显式顺序加载。配置关闭FFmpeg网络、编码、GPL/nonfree与汇编。

默认完整Debug离线APK约160MB；包体、解包后的GPU/视频/参考像素会共同占内部空间。`correspondingSource`提供匹配源代码、依赖、脚本和说明，发布时必须与APK同时提供；详见 [媒体分发](media-distribution.md)。Release产物尚未使用发布密钥签名，直接安装验证使用Debug APK。

## v2增量验证

- Debug/Release/测试APK构建与lintDebug/lintRelease通过。首次Lint发现Arrays.copyOf需要API9，已改成API8可用的System.arraycopy。
- API16 x86后端检查已通过原生与汇总部分：CoreMark双种子/正式时间、SciMark、zlib、SQLite、Triad 1/2/3/4/8线程（含非整除分区）、固定总量/MB/s重算/实际创建数、取消重启；新旧内存中位数参数匹配；六图乱序按ID汇总、身份/像素/非法速度拒绝、空旧median回退；active_report中断保留完成轮次、旧会话读取；保存失败仍可导出。
- GPU后端回归通过：四场景模式短测、真实屏上交换、完整60秒Fill有效MTexels/s，资源损坏修复、一次性进程、取消/超时回收和重启。
- API16生命周期回归通过：在三轮CoreMark第一轮完成后核对数据库active_report检查点，取消后保留1轮且不生成中位数；本次停止延迟90ms。屏上GPU退后台记interrupted，无正常成绩；此延迟不保证适用于所有阶段。
- API16 Android4.1 x86完整一键流程通过：14阶段全部尝试，8个阶段各3轮且有效中位数存在，视频及5个GPU模式各一次；五GPU工作进程不同，113/113/56/56/60秒完整时长及渲染/窗口参数通过。752579ms（约12分33秒），整次会话持久化通过。本次已有资源缓存并在开始时校验，不是清空应用数据后的首次解包耗时。其余13阶段completed，视频为partial，整次准确保持partial；不能把此功能检查称为所有系统视频路径成功。
- 最终11个共享库API8/9导入、SysV hash、依赖与无RPATH/TEXTREL检查通过；无NEON主库及四个ARM媒体/FFmpeg库指令筛查为0，NEON主库阳性对照检出13768处。git diff --check通过。静态检查不能替代ARM真机加载或运行证据。
- v2对应源码包生成并通过7-Zip完整CRC检查，23823个文件、约492MiB；新Triad/JNI/调度/测试、FFmpeg源码和LGPL、脚本、Gradle wrapper、媒体清单与文档条目齐全，.git/.tools/build目录均排除。归档包含本节v2验证记录。
- 最终Debug APK为167822774字节（约160MiB），Release未签名包95864043字节；最终打包仅更新许可文字，Java与原生任务UP-TO-DATE，测量代码与上述实测一致。

## v1历史验证证据

下表为此前13阶段/一轮、338秒GPU版本的已观察结果，保留用于说明已有媒体与旧系统兼容证据。v2变更验证单独列在上方。

| 检查 | 已观察结果 |
|---|---|
| 默认完整Gradle构建 | assembleDebug、assembleRelease、assembleDebugAndroidTest、lintDebug、lintRelease通过；buildFfmpeg自动编译通过，后续增量UP-TO-DATE |
| API8/9 ELF导入/依赖 | 三套主库及两ABI媒体/FFmpeg共11个共享库通过；SysV hash，无RPATH/TEXTREL，导入来自检查过的系统/包内依赖 |
| 无NEON静态检查 | 无NEON主库及四个ARM媒体/FFmpeg库筛查为零，NEON主库阳性对照能检出；修复检查器把链接器固定Thumb→ARM跳转桩误读为NEON的问题，不按函数名放行 |
| 后端回归 | 正式CoreMark双种子/至少10秒与8工作线程、SciMark、zlib/Zstd、内存/STREAM、存储/SQLite、参数拒绝、取消/重启、汇总/旧历史/恢复通过 |
| 固定功能检查 | 拓扑/热插拔、高温和缺传感器不拦截、加密KAT与8工作线程、坏标签拒绝、六图严格像素与计时输出哈希、会话恢复、损坏参考文件自动恢复通过 |
| API16固定软件视频 | 七种码流均完成真实解码，首帧YUV哈希通过 |
| API16系统媒体 | 模拟器OMX.google路径：SD/HD Baseline、H.263/MPEG4/VP8输出/播放成功并通过可读首帧亮度抽查；High720/1080系统/播放路径在送入或取出缓冲区时失败（feed_input/drain_output，IllegalStateException），分别保留，整视频为partial |
| API16完整一键流程 | 正式一轮13阶段、四轮完整GPU时长通过，521672ms（约8分42秒）；结果完整持久化，总体partial准确保留媒体失败。后续旧系统窗口兼容修正另作专项回归 |
| GPU窗口/资源/故障 | API10与API16四模式短测、真实屏上交换、不同工作进程、损坏安装资源恢复、取消/超时回收与重启通过 |
| API10系统播放窗口 | Android2.3.7 x86模拟器：本地媒体进程窗口SD播放完成约4秒，854×480，硬件类型保持unconfirmed；私有文件描述符路径通过 |
| API10窗口生命周期 | 视频和屏上GPU的实际runner检查通过：内部Activity切换继续运行；返回取消、Home中断；保留已完成软解结果，取消/中断无正常汇总成绩 |
| API10严格视频矩阵的限制 | 曾在High720软件路径观察到15063ms父进程超时；系统播放高档位也返回解码错误。不能将API10严格矩阵记为全部通过；超时/失败保留，后续路径仍自动尝试 |
| 保存失败 | 注入begin/save两种数据库写入失败，实际存储测量仍完成、原始结果与告警可导出，通过 |
| 会话取消/后台 | 最终API16回归通过：取消保留两个已完成阶段，立即显示停止反馈并记录实际延迟；屏上GPU退后台记为interrupted，无正常成绩。本次观测停止延迟93ms，不作为所有阶段保证 |
| 对应源码归档 | correspondingSource生成通过，约492MiB、23822个文件；FFmpeg源码/许可、构建脚本、JNI、Gradle wrapper、固定素材和说明均在包内；无.git、.tools和build目录，7-Zip完整CRC检查通过 |

API16设备为Android4.1 x86模拟器，API10设备为Android2.3.7 x86模拟器。速度不能用于真机比较，OMX.google或MediaPlayer成功也不是厂商硬解证据。Surface路径无法取得真实显示FPS、画质回读或帧数时明确为null/不可获得。

## 仍需物理设备验收

API8–15 ARM、API16–18有厂商解码的ARM、可同时调度8核、无NEON ARMv7、x86真实设备，以及API29属性分支。需检查安装/解包峰值、运行内存、GPU驱动/片元精度、硬件解码、时长和电池温升。API10模拟器补充了旧系统窗口与加载证据，静态导入/反汇编仍不能替代无NEON或ARM真机。

尚未获得这些设备，不宣称M1/M5全部通过，也不承诺每台老设备都能完成1080p或完整场景。高档位失败不删除低档位成绩，不将缺项填零或归入综合分。

## 对应实现与产物位置

- 产品调度：`app/src/main/java/com/retromobilebench/BenchmarkSuite.java`、`BenchmarkRunner.java`、`MainActivity.java`。
- 加密/图片/视频：`native/src/crypto_bench.c`、`image_bench.c`、`video_bench.c`；Java媒体客户端、API隔离实现和旧系统播放窗口在同包目录。
- 旧系统GPU窗口：`LegacyGpuActivity.java`；现代窗口与EGL桥接保留现有实现。
- Debug安装包：`app/build/outputs/apk/debug/app-debug.apk`；Release未签名包：`app/build/outputs/apk/release/app-release-unsigned.apk`。
- 匹配源码包：`app/build/distributions/RetroMobileBench-corresponding-source-fixed-suite-v2.zip`。

旧Surface边界依据：[Android2.3.7 Surface JNI](https://android.googlesource.com/platform/frameworks/base/+/android-2.3.7_r1/core/jni/android_view_Surface.cpp)、[Surface实现](https://android.googlesource.com/platform/frameworks/base/+/android-2.3.7_r1/libs/surfaceflinger_client/Surface.cpp)。私有文件读取依据：[MediaPlayer.setDataSource](https://developer.android.com/reference/android/media/MediaPlayer#setDataSource(java.io.FileDescriptor))。
