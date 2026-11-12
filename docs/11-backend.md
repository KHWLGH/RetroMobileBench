# 11 本地跑分后端（第一段实现）

2026-11-12：App 已从 M0 探针扩展为能运行、取消并保存文件存储测试的最小版本。本页描述当前代码；其他架构文档中的完整测试套件仍有待实现部分。

## 1. 已实现的路径

`MainActivity` → `BenchmarkRunner`（单工作线程）→ JNI → `storage_bench.c` → `ResultStore`。

| 文件 | 职责 |
|---|---|
| `app/src/main/java/com/retromobilebench/BenchmarkRunner.java` | 按单轮 / 三轮顺序运行、前置条件检查、轮次间休息、取消、保存及通知界面 |
| `DeviceSnapshot.java` | 系统 / ABI / 运行时、内存、剩余空间、电量、充电、电池温度、在线 CPU、CPU0 频率及调频策略 |
| `ResultStore.java` | 系统 SQLite 只用于保存成绩；逐轮检查点、最近 20 次历史、进程中断恢复 |
| `BenchmarkReport.java` | 原始结果展示、相同参数下三轮中位数；失败 / 取消不按零分计入 |
| `native/src/storage_bench.c` | 单线程原生顺序 / 随机读写、单调时钟计时、读回检查、合作取消和文件清理 |
| `NativeLoader.java` | 加载 NEON / 无 NEON / x86 库；未知 ARM 指令集能力时选择无 NEON，加载失败时显示错误 |

全部数据库和文件访问都在工作线程执行。进程级调度器仅保存 Application，不保存 Activity；界面在 `onStart` / `onStop` 订阅和取消订阅。旋转保留正在运行的任务；切到后台取消任务，当前 I/O 返回后停止。测试期间用窗口 flag 保持亮屏，不额外申请权限。

## 2. 文件测试协议 storage-file-v1

| 参数 | 实际值 |
|---|---|
| 文件 | App 私有 cache 目录，默认 32 MiB；空间不足缩至整数 MiB，最低 4 MiB；预留 16 MiB |
| 顺序块 | 256 KiB，遍历整个文件 |
| 随机块 | 4 KiB，固定 LCG 种子选偏移，256 次 / 分项 |
| 写入 | `O_SYNC`，顺序写和随机写 |
| 读取 | 优先 `O_DIRECT`；文件系统在 open / 第一次读时拒绝，允许的“不支持”错误才退回缓存读取 |
| 缓冲区 | 两个 256 KiB 对齐缓冲区；用 API 16 的 `memalign` |
| 数据 | 固定种子的伪随机 4 KiB 页，重复填充 256 KiB 块；所有测试文件偏移使用相同页内容 |
| 预热 | 计时前一次 256 KiB 同步写；各读取项计时前读一次对应大小的块，检查 O_DIRECT 是否可用 |
| 正确性 | 重试 EINTR / 短 I/O；读取完成后在计时外核对最后一块内容 |
| 顺序结果 | 十进制 MB/s，1 MB = 1,000,000 字节 |
| 随机结果 | IOPS |
| 计时 | 原生 `clock_gettime(CLOCK_MONOTONIC)` |
| 三轮模式 | 同一参数重复 3 次，中间休息 3 秒，每轮重新检查电量、温度和空间 |

三轮只有全部成功且文件大小、块大小、随机次数、读写模式、测试版本一致时才合并中位数。缓存读取会明确标注，不能与绕过页缓存的结果直接比较。没有基准机，所以不生成综合分数；这是本项目自己的测试协议，不宣称与 AndroBench 成绩等价。

文件用 `mkstemp` 创建，在所有读写句柄打开后立即 `unlink`。正常完成、取消、错误、甚至进程被杀死时，最后一个句柄关闭都会释放文件占用；不依赖 Java 的 finally 来清理大文件。并发应用仍可能耗尽空间，此时保留错误和已完成分项。

原生取消标记用 32 位原子读写，由 Java 的单个工作线程重置，取消线程设置。每次 I/O 前检查；无法强行终止卡在内核里的单次 I/O。

## 3. 条件与记录

- 未充电且电量低于 30% 时跳过；电池温度达到 45°C 时跳过。
- 容量不足以容纳测试文件和 16 MiB 预留空间时跳过。
- 每次记录 schema / 测试 / App 版本、开始结束时间、设备快照、每轮开始结束时的运行条件、缓存模式及降级 errno。
- 每轮返回就写一次 SQLite；状态分别为 running、completed、failed、cancelled、skipped、interrupted。
- 下次进程启动把残留 running 记录改成 interrupted，保留先前已经写入的轮次。
- 数据库写入失败会在界面提示，并允许复制当前结果 JSON；不把未落库的结果显示成“已保存”。

数据库是本地成绩仓库，不是 SQLite 性能测试。以后接入事务跑分仍需打包固定版本 SQLite，不用 ROM 自带引擎测 TPS。

## 4. 兼容性排查

保留 minSdk 16、targetSdk 23、Java 源码级别 17、NDK r23c、CMake 3.18.1 和三套原生库。宿主 Gradle 已确认使用 JBR 25.0.4，未修改用户已经修好的 IDE JDK 设置。

修复了原 `NativeLoader` 的危险回退：无 NEON 库缺失 / 加载失败时，不再改加载 NEON 库，避免 Tegra 2 在加载阶段 SIGILL；读不到 ARM Features 时也保守地选择无 NEON。

Lint 仅排除直接分发 APK 不适用的 `ExpiredTargetSdkVersion`，`NewApi` / `InlinedApi` 设为 error。没有引入 AndroidX、Kotlin 标准库、网络库或新的运行时权限。数据库、JSON 和线程调度均使用 API 16 就有的框架类。

新增存储代码后的 ARM 无 NEON 库已用 `scripts/check-neon.ps1 -ExpectNeon $false` 反汇编检查，NEON 指令数为 **0**。`llvm-readelf -d` 确认依赖仅为系统 `libc` / `libm` / `libdl` / `liblog` / `libEGL` / `libGLESv2`，没有 `libGLESv3`、`libc++_shared` 或 App 自己的其他动态库。这是静态兼容性证据，不能代替 Tegra 2 真机验证。

## 5. 验证与复现

原项目没有 DI / 测试框架，使用 Java 原生 View。为保留 API 16 兼容性，测试直接继承系统 `android.app.Instrumentation`，不安装要求较新系统的测试运行器，也不引入 Hilt / Compose / Espresso。测试 APK 与发布 APK 分开。

```powershell
# 当前 Windows 环境；其他机器将 JAVA_HOME 指向兼容的 JDK。
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain
android emulator start Android4.1 --headless
.\gradlew.bat :app:installDebug :app:installDebugAndroidTest --console=plain
adb -s emulator-5554 shell am instrument -w com.retromobilebench.test/com.retromobilebench.BackendInstrumentation
```

测试入口：`app/src/androidTest/java/com/retromobilebench/BackendInstrumentation.java`。通过时输出 `PASS: JNI I/O, validation, cancellation/cleanup, medians, preflight, SQLite persistence and interrupted-run recovery`。

覆盖实际 JNI 读写、非法参数 / 不存在路径、预先与运行中取消、没有残留测试文件、离群值中位数、混合缓存模式 / 不同参数 / 未完成样本不汇总、电量 / 温度 / 空间门槛，以及 SQLite 关闭重开后的成绩保留和 interrupted 恢复。测试使用独立且唯一命名的数据库，结束后只删除自己的测试库。

已在 Android 4.1.2 / API 16 x86 模拟器通过上述测试和构建 / Lint。模拟器成绩仅用于流程验证，不用于性能比较。当前 Android CLI 的 layout 辅助 APK 和 screen shell 协议不兼容该旧模拟器，检查界面可使用系统 `uiautomator dump`；ADB 的 headless 截屏可能得到黑帧，不能据此评价视觉效果。

## 6. 接下来的后端工作

1. 固定许可证和版本后接入 CoreMark、SciMark、压缩、tinymembench；继续让所有源文件分别编成 NEON / 无 NEON 库。
2. 打包固定版本 SQLite 实现事务 TPS，补充所有 CPU 核的频率采样及 cpuinfo。
3. 把独立 GFXBench 工程并入原生库，完成 GPU 独立进程、数据包校验和失败恢复。
4. 在 ARMv7 真机、Tegra 2 和 Atom 上验证；特别检查 O_DIRECT 文件系统回退、温度策略和存储成绩波动。当前没有强制注入“不支持 O_DIRECT”的文件系统，缓存回退分支仍需实机验证。
