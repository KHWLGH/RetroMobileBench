# 11 本地跑分后端

2026-11-12：CPU、内存、文件存储、固定版本 SQLite 和六项 GPU ES2 离屏测试已接入 App。本页记录实际实现；性能基线和 ARM/Tegra 2 真机验证仍未完成。

## 1. 调度、记录和界面

`MainActivity` → `BenchmarkRunner` → `BenchmarkSuite` → JNI / `GpuClient` → `ResultStore`。

- 单轮或三轮；三轮间休息 3 秒，每轮重新检查运行条件。
- “依次运行全部测试”顺序执行全部套件，每项一轮、分别保存；失败或缺少场景数据会记录并继续，取消会停止后续项。
- 进程级单工作线程只持有 Application；Activity 在 onStart/onStop 订阅。旋转保留任务，离开前台取消，窗口保持亮屏。
- CPU/内存/存储使用合作取消；当前 codec 调用、内核 I/O 或 CoreMark 核心阶段结束后才可退出。GPU 在专用进程内运行，取消或超时结束该子进程。
- 系统 SQLite 仅保存历史；事务性能测试使用独立的、随包固定版本 SQLite。
- 每轮检查点，最近 20 次历史，启动时将遗留 running 改为 interrupted；保留已写入样本。保存失败会明确提示并允许复制当前 JSON。
- report schema 为 1；状态包括 running/completed/failed/cancelled/skipped/interrupted。未知套件保留原始 JSON，旧文件/zlib 成绩继续可读。
- 中位数只接收完整、成功、正且有限、测试版本和全部可比参数相同的样本。失败/取消/缺项不会当零分，也不生成综合分数。

## 2. CPU 协议

所有依赖版本、固定提交、下载哈希及本地修改见 [third_party/README.md](../third_party/README.md)。不使用 ROM 内置 codec，不更改调频策略、不绑核。

| 协议 | 内容 | 固定参数与校验 |
|---|---|---|
| `cpu-zlib-v1` | zlib 1.3.1 单线程/并行压缩与解压 | level 6；1 MiB 固定语料；CRC32 1765234832，压缩后 530830 字节 |
| `cpu-zstd-v1` | zstd 1.5.7 单线程/并行压缩与解压 | level 3；相同语料；每线程独立压缩流 |
| `cpu-coremark-v1` | CoreMark 单线程及最多四线程 | 2000 字节、MEM_STACK；performance 和 validation 两套种子；四个阶段均需 ≥10 秒、上游 CRC 校验通过 |
| `cpu-scimark-small-v1` | SciMark FFT、SOR、Monte Carlo、Sparse、LU | seed 101010；FFT 1024、SOR 100×100、MC 每批 16384、Sparse 1000/5000、LU 100；逐项独立数值验证 |

zlib/zstd：固定 1 MiB 数据由每 4 KiB 内的 2 KiB ASCII 文本与 2 KiB 固定种子二进制组成（种子 `0x524d4231`）。依次执行单线程压缩、单线程解压、并行压缩、并行解压。可用处理器数在 run 开始时限制为 1–4。各线程预热并校验，mutex/condition 同步起跑；计时到最晚线程结束。按全部输入字节 / 墙钟秒 / 1,000,000 计算 MB/s，每项至少 1500 ms。前后逐字节校验，结果包含版本、语料、线程数、级别、迭代数、实际秒数和压缩大小。并行是多个独立流合计吞吐量。zlib 使用 `Z_PREFIX`、`z_errmsg` 私有重命名和隐藏符号，与 GPU 自带旧版 zlib 隔离。

CoreMark：上游核心源文件保持原样，只定制 `core_portme.*`；使用 pthread 和单调微秒时钟。先校准到约 12 秒，再正式运行两套种子，分别测试单线程和并行。任何阶段不足 10 秒、CRC 错误或线程创建失败都不产生可汇总成绩。结果保留上游输出、编译器/参数、迭代数、实际时间和校验状态；短诊断运行明确失败，不能冒充正式 CoreMark。取消无法强行中止正在执行的未修改核心循环，可能需要等待当前阶段。

SciMark：复用 NIST 五个数值内核，由 RMB 计时驱动，每项至少 1500 ms。验证包括 FFT 往返、SOR 参考迭代/边界、Monte Carlo π 范围、稀疏点积和 LU 的 PA=LU。输出每项 MFLOPS；这是固定 small 数据集的自定义协议，不输出上游综合分数。仅修改上游 RNG/MonteCarlo 的分配失败处理，已在源码标注。

## 3. 内存协议

| 协议 | 内容 | 参数 |
|---|---|---|
| `memory-tiny-v1` | tinymembench 原始 C 对齐复制/填充，加 RMB 随机依赖链延迟 | 8 MiB 缓冲区；复制按读+写字节、填充按写字节计算 MB/s；4 MiB 随机索引链，每批 4096 次依赖读取，ns/load；固定 Fisher–Yates 种子 `0x524d4233` |
| `memory-stream-variant-v1` | Copy/Scale/Add/Triad，pthread 1–4 线程 | 每线程三个 2 MiB double 数组，scalar=3；各项单独初始化，同步预热/起跑，全部线程 join 后校验 |

每项至少 1500 ms，计时外校验。tinymembench 只编 `util.c` C 内核，不把 ARM NEON 汇编放进无 NEON 库。STREAM 自定义固定工作集最多约 24 MiB（不含线程栈）；为老设备控制内存，没有保证工作集是每台机器缓存的四倍，因此明确标注“基于 STREAM 变体，可能含缓存影响”，不声称标准 STREAM 持续 DRAM 带宽。

## 4. 存储协议

### `storage-file-v1`

- App 私有 cache 目录，默认 32 MiB，空间不足缩至整数 MiB，最低 4 MiB；预留 16 MiB。
- 顺序 256 KiB、随机 4 KiB；随机分项 256 次，固定种子。
- 写使用 O_SYNC；读优先 O_DIRECT，仅对明确的“不支持”错误在 open/首次读时回退缓存读取，并在报告标记。
- 两个 API 16 `memalign` 对齐缓冲区；CLOCK_MONOTONIC 计时；计时前预热，计时外核对读回数据；处理 EINTR/短 I/O。
- 顺序 MB/s、随机 IOPS。测试文件 mkstemp 后、全部句柄打开后立即 unlink；取消/进程死亡时最后一个句柄关闭即可释放空间。
- 三轮必须文件大小、块大小、随机次数、缓存模式和版本全部一致才汇总。属于 RMB 自定义协议，不能直接视为 AndroBench 成绩。

### `sqlite-tps-v1`

SQLite **3.53.1** amalgamation；INSERT/UPDATE/DELETE 各 300 事务，一行/事务、1024 字节 payload。BEGIN IMMEDIATE + prepared statement + COMMIT；journal DELETE、synchronous FULL、page_size 4096、cache_size 2048 KiB、temp MEMORY、mmap 0。预热、完整数据验证及 quick_check 不计时。输出 TPS 和实际时间。

编译 `SQLITE_THREADSAFE=0`、`SQLITE_DISABLE_LFS=1`、`SQLITE_TEMP_STORE=3`、`SQLITE_OMIT_LOAD_EXTENSION=1`、关闭 mmap/memory status。只有串行工作线程使用该引擎。独立文件 `cache/rmb-sqlite-bench.db` 及其 journal/wal/shm 在前后清理，启动时也清理；绝不操作历史数据库。

## 5. GPU 独立进程与数据

六项：`gl_alu_off`、`gl_fill_off`、`gl_driver_off`、`gl_blending_off`、`gl_trex_off`、`gl_egypt_off`。协议为 `gpu-<test_id>-v1`；仅 ES2、ETC1、1920×1080 离屏。ALU/driver/场景输出 frames，fill 为 MTexels/s，blending 为 MB/s，保留上游原始 JSON。

GFXBench 子工程被每套主库静态链接，静态工厂创建测试。自己的 EGL 宿主使用 800×480 pbuffer，测试在 1920×1080 FBO 中渲染。导出的 ui.fs 对不支持 fragment highp 的 GPU 增加 mediump 回退，原始子模块不改。

`GpuService` 使用 `android:process=":gpu"`、`exported=false`、`stopWithTask=true`；通过显式绑定 Messenger 调用。每次测试用一个新子进程，返回后 STOP/unbind 并等待进程死亡；崩溃/系统回收通过 Binder death 变成失败，240 秒超时或取消也销毁该进程。Native C++ 异常变成失败结果，不影响后续套件。数据路径仅允许 App 私有 `files/gpu-packs`。

结果核验 test_id、实际渲染分辨率、状态和正且有限的 score；附源码提交、数据清单 SHA-256、纹理/分辨率、GPU vendor/renderer/version、工作进程 PID。三轮参数需一致，诊断时长也参与比较。Instrumentation 的 5000 ms GPU 运行只用于功能验证（1 秒在快速宿主 GPU 上不足以产生填充率采样）；界面正式运行使用上游完整时长（底层离屏每项 60 秒，T-Rex 56 秒，Egypt 113 秒）。

默认内置底层小包；`-PgpuData=full` 构建包含两个完整场景。API 16 ACTION_GET_CONTENT 文件选择导入 ZIP，内部私有目录解包，SHA-256/大小/路径/数量验证，临时目录完成后原子改名；损坏或取消不会替换原包，成功后清理被完全覆盖的旧包。无需存储权限；详细格式和容量限制见 [06-data-packs.md](06-data-packs.md)。

## 6. 设备信息与前置检查

- 系统/ABI/运行时/屏幕、内存、私有目录空间、电量/充电/电池温度。
- CPU0–31 的 online、当前/最低/最高频率、governor，以及 /proc/cpuinfo 中允许的身份字段；不采集 Serial 等唯一标识。频率是轮前/轮后快照，没有在计时区间持续采样。
- 不充电且电量 <30%，或电池温度 ≥45°C 时跳过。
- CPU 可用内存至少 32 MiB，内存测试至少 64 MiB，GPU 底层 128 MiB、场景 192 MiB；lowMemory 时跳过。SQLite 至少 20 MiB 磁盘可用空间。
- 没有集成 pytorch/cpuinfo 缓存/微架构库；不可读字段保持未知，不猜测。

## 7. 兼容性与复现

保留 minSdk 16、targetSdk 23、Java 17 经 D8 脱糖、NDK r23c、CMake 3.18.1、三套库。Gradle 使用宿主 JBR 25.0.4，不改变手机要求，也未更改用户已修好的 IDE JDK 设置。无 AndroidX/Kotlin 标准库/新权限；Lint 的 NewApi/InlinedApi 为 error。ARM Features 未知或无 NEON 时加载无 NEON 库；失败不会冒险回退 NEON。

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
# full 需按 third_party/README.md 检出两个场景
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug -PgpuData=full --console=plain
.\gradlew.bat :app:installDebug :app:installDebugAndroidTest -PgpuData=full --console=plain
adb -s emulator-5554 shell am instrument -w -e fullGpu true com.retromobilebench.test/com.retromobilebench.BackendInstrumentation
# 只验证 GPU（不含 CoreMark 的正式长时测试）
adb -s emulator-5554 shell am instrument -w -e gpuOnly true -e fullGpu true com.retromobilebench.test/com.retromobilebench.BackendInstrumentation
```

测试直接继承系统 Instrumentation，无第三方框架。涵盖实际 JNI、1/2/4 线程、CoreMark 双种子 ≥10 秒、SciMark 数值验证、内存/STREAM、SQLite 行校验、文件 I/O、非法参数、预先/运行中取消和重启、临时文件清理、中位数/参数不可比、混合历史/中断恢复，以及 GPU 新进程、取消/超时后重启、损坏/路径越界/缺文件数据包。测试历史使用独立临时数据库。

2026-11-13 最终验证记录：

- Android 4.1.2 / API 16 x86：合并 GPU 后，文件/zlib、CoreMark 正式双种子、SciMark、内存/STREAM、zstd/SQLite，以及历史/前置检查/中位数断言均通过；随后六项 GPU 的独立专项回归全部 PASS。
- GPU 专项采用每项 5 秒诊断窗口，包括实际 1920×1080 分辨率/测试名核验、每项 PID 不同、预先/运行中取消、400 ms 超时后重新运行、ZIP 校验/失败保留/重复导入清理。使用宿主 GPU 冷启动模拟器；SwiftShader 环境两次出现 ADB 断连且无测试结论，未计为通过。
- 修正 API 16 Binder 退出检测：保持 death recipient 直到结束，并用 pingBinder 主动确认；过早 unlink 后只看 isBinderAlive 会读到缓存状态。1 秒诊断在快速 GPU 的 fill 项得到零采样，改为 5 秒；正式界面仍使用上游完整时长。
- 实际 UI 点击一键全测，存储/zlib/zstd/SQLite 依次 completed 并落库；CoreMark 中取消后记录 cancelled、后续项停止、界面返回历史且按钮恢复。未将这一轮取消检查当成完整全测性能结果。
- Debug 完整数据 APK、测试 APK、lintDebug，以及 Release 完整数据 APK、lintRelease 均构建通过。Release 未配置发布签名，安装验证用 Debug APK。主库合并后 `check-neon.ps1` 的 Thumb+ARM 反汇编：Debug 与 Release 的 librmb_noneon.so 均为 NEON 指令 **0**。
- llvm-readelf 动态依赖只有 liblog、EGL、GLESv2、m、android、dl、c 系统库，没有 libz、GLESv3、libc++_shared 或其他 App 共享库依赖。

模拟器结果只证明功能，不能用作性能对比；静态无 NEON 检查不能代替 Tegra 2 真机。

## 8. 仍需完成的验证与产品增强

- ARMv7 NEON、Tegra 2 无 NEON、Intel Atom 真机；老 GPU 精度/驱动、纹理内存和 O_DIRECT 回退；历史 GPU 成绩及多轮稳定性。
- 选择实际基准机后才可做归一化/综合分，不制造参考分。
- 丰富缓存/微架构识别、外置 SD 卡数据目标与存储测试、已解压目录导入、文字/图片文件导出等为后续增强；目前支持私有目录 ZIP 导入和 JSON 剪贴板。
