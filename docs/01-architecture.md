# 01 架构

> 本文主要是完整版本的设计方案。当前已实现 Java `BenchmarkRunner` / `BenchmarkSuite`、`ResultStore`、`DeviceSnapshot`、存储文件读写及 zlib CPU 压缩 JNI、最小 Holo 控制界面和许可入口，详见 [11-backend.md](11-backend.md)。CoreMark、SciMark、zstd、内存、SQLite 事务、GPU 调度和最终计分仍是待实现部分。

## 1. 总体结构

```
┌────────────────────────────────────────────────────────────┐
│  App（Java，原生 View + Holo）                               │
│  ├─ 首页：设备信息、开始跑分（快速 / 完整）                       │
│  ├─ 运行页：进度、当前测试、温度                                  │
│  ├─ 结果页：分项成绩、总分、导出                                  │
│  ├─ 历史页：本地成绩记录                                         │
│  └─ 数据包管理：检测、导入 GFXBench 场景数据                       │
├────────────────────────────────────────────────────────────┤
│  调度层（Java）                                               │
│  ├─ BenchmarkRunner：按顺序跑测试、控制降温间隔                    │
│  ├─ NativeLoader：选择并加载哪一套原生库                          │
│  └─ ResultStore：把成绩写进本地 SQLite                           │
├────────────────────────────────────────────────────────────┤
│  JNI 桥（一个 .so：librmb.so）                                 │
│  ├─ CPU：CoreMark、SciMark 2、zstd/zlib                       │
│  ├─ 内存：tinymembench、多线程带宽（STREAM 改版）                  │
│  ├─ 存储：顺序/随机读写、SQLite 事务                              │
│  ├─ 设备信息：cpuinfo、/proc、/sys                              │
│  └─ GPU：GFXBench testfw + ES2 测试（静态链接）                   │
└────────────────────────────────────────────────────────────┘
```

## 2. 建议的工程模块

| 模块 | 类型 | 内容 |
|---|---|---|
| `app` | Android application | 界面、调度、成绩存储 |
| `native` | CMake 工程（由 `app` 的 `externalNativeBuild` 引用） | 所有原生代码，产出一个 `librmb.so` |
| `third_party/` | 源码目录（git submodule 或直接放源码） | coremark、scimark2、zstd、zlib、tinymembench、cpuinfo、sqlite、gfxbench（裁剪后） |

不再需要单独的 `tfw-java` 模块：GFXBench 的宿主改在原生层做（见 [04](04-gfxbench-port.md) §4.5），Java 层只调一个 JNI 函数。

不使用 AndroidX、Compose。用 Java：方法数少。AGP 9 默认会加 kotlin-stdlib，已在 `gradle.properties` 里关掉（见 [05](05-build-and-toolchain.md) §3）。不管用哪种语言，都要控制依赖，保证不需要 multidex（见 [02](02-platform-constraints.md)）。

## 3. 原生库组织

### 3.1 为什么只有一个 .so

- Android 4.3（API 18）以前，系统动态链接器不会到 App 的 `lib/` 目录找 `DT_NEEDED` 依赖库，多个 .so 之间互相依赖时必须在 Java 里按依赖顺序逐个 `System.loadLibrary`，漏一个就崩溃。
- GFXBench 原本用 `dlopen("libgfxbench40_gl.so")` 按名字加载测试库，这在 4.1/4.2 上同样有问题。
- 解决办法：所有原生代码（我们的测试 + GFXBench testfw + ES2 测试 + 第三方库）**链接成一个 `librmb.so`**，C++ 运行时用 `c++_static`。GFXBench 的测试入口改用静态工厂表（宿主代码直接调 `create_test_gl_xxx()`），不再 `dlopen`。详见 [04](04-gfxbench-port.md) §4.3。

### 3.2 三套 ABI

| 目录 | 编译参数 | 适用设备 |
|---|---|---|
| `lib/armeabi-v7a/librmb.so` | NDK 默认（默认就开 NEON） | 绝大多数 ARMv7 设备 |
| `lib/armeabi-v7a/librmb_noneon.so` | 所有源文件加 `-mfpu=vfpv3-d16`（同一个 CMake 工程里的第二个目标） | Tegra 2 设备（没有 NEON） |
| `lib/x86/librmb.so` | 默认 | Intel Atom 设备（联想 K900、摩托 RAZR i、华硕 ZenFone 4/5/6 等） |

- 无 NEON 那份放在同一个 `armeabi-v7a` 目录下，只是换个文件名。APK 安装时整个目录都会解压，启动时由 `NativeLoader` 决定加载哪个。
- 不要只用"运行时判断 NEON"的办法：NDK r21 起编译器默认开 NEON，自动向量化会在任何 C 代码里生成 NEON 指令，Tegra 2 上一跑就 `SIGILL`。所以必须整套重编。已实测：GFXBench ES2 部分默认参数编出来有 654 条 NEON 指令，加 `-mfpu=vfpv3-d16` 后为 0（`scripts/check-neon.ps1`）。
- x86 设备上如果只有 ARM 库，系统会用 Houdini 把 ARM 指令转译执行，CPU 分数会失真，所以一定要带 x86 原生库。

### 3.3 NativeLoader 选择逻辑

```
读取 Build.CPU_ABI / Build.CPU_ABI2（API 16 没有 SUPPORTED_ABIS）
├─ 含 "x86"           → System.loadLibrary("rmb")（安装器已选好 x86 目录）
└─ armeabi-v7a
   ├─ /proc/cpuinfo 的 Features 行含 "neon" → System.loadLibrary("rmb")
   └─ 不含                                  → System.loadLibrary("rmb_noneon")
```

- 判断要在 Java 里做，不能先加载 .so 再在原生代码里判断，否则 NEON 版加载时的静态初始化就可能已经崩了。
- 设备信息页显示实际加载的是哪一套，方便排查。
- 读不到 ARM 指令集能力时保守选择无 NEON；无 NEON 库加载失败时报告错误，不回退到 NEON 库，防止 Tegra 2 在加载阶段执行非法指令。

## 4. 运行流程

一次完整跑分的顺序（暂定，按"先轻后重、先冷后热"排列）：

1. 采集设备信息（不计分）。
2. 检查条件：电量 ≥ 30% 或在充电、温度不过高、剩余空间够存储测试用。不满足就提示，用户可以选择继续。
3. CPU：CoreMark（单核 → 多核）、SciMark 2、zstd/zlib（单线程 → 多线程）。
4. 内存：tinymembench 带宽和延迟、多线程带宽。
5. 存储：顺序写 → 顺序读 → 随机写 → 随机读 → SQLite 增/改/删。
6. 降温等待，同时显示温度。
7. GPU：4 个底层测试 → T-Rex → Egypt（有数据包才跑场景测试）。
8. 汇总计分、写入本地记录。

两档模式：

| 模式 | 内容 | 预计时长 |
|---|---|---|
| 快速 | 每项只跑一次；GPU 只跑底层测试的离屏版 | 5 分钟内 |
| 完整 | 每项跑 3 次取中位数；GPU 包括场景测试、屏上版和离屏版 | 20–30 分钟 |

时长是估算，以实测为准。光 GPU 部分就有：底层测试屏上版每个 30 秒、离屏版每个 60 秒，T-Rex 56 秒，Egypt 113 秒，还不算加载。离屏版六项合计约 7.5 分钟。

### 4.1 GPU 测试的运行方式

宿主在原生层（[04](04-gfxbench-port.md) §4.5），Java 层只负责在后台线程调用 JNI 函数、显示进度：

1. 每个测试新建一个 testfw 测试实例：`init()` 加载资源 → `run()` 渲染直到结束 → `result()` 取 JSON 结果 → `terminate()`。
2. 结果 JSON 里有原始成绩（帧数、MB/s 等）和单位，交给调度层换算分数。
3. **离屏测试**（计分用）用 pbuffer，不需要窗口，已在模拟器上跑通。界面上可以只显示进度，不显示渲染画面。
4. **屏上测试**（不计分，可选）需要窗口表面：用一个横屏、全屏、保持亮屏的 Activity，把 `SurfaceView` 的 Surface 通过 `ANativeWindow_fromSurface()` 交给原生层创建 EGL 窗口表面。还没验证。

### 4.2 进程隔离

GFXBench 的宿主把崩溃处理放在单独的进程（`:crasher`）。建议我们把 GPU 测试也放到单独的进程（`android:process=":gpu"` 的 Service 或 Activity）：老机器的 GPU 驱动不稳定，崩溃了不至于把整个 App 带走，主进程还能记录"该测试失败"并继续跑后面的项目。

另一个好处：场景测试进程的内存峰值（VmHWM，CPU 侧）实测约 105 MB（Egypt），放在单独进程里，测完进程退出就全部释放，不会让主进程的内存碎片化。

## 5. 结果与计分

### 5.1 原始成绩

每个测试都保存原始数值和单位，界面上也显示：

| 测试 | 原始单位 |
|---|---|
| CoreMark | iterations/s |
| SciMark 2 | Mflops（总分和 5 个子项） |
| zstd / zlib | MB/s（压缩、解压分开） |
| tinymembench | MB/s（带宽）、ns（延迟） |
| 存储 | MB/s（顺序）、IOPS（随机）、TPS（SQLite） |
| GFXBench T-Rex / Egypt / ALU / Driver | frames（固定时长内渲染的帧数），同时换算 fps |
| GFXBench Fill | MTexels/s |
| GFXBench Blending | MB/s |

### 5.2 分数

- 选一台有代表性的 4.1 设备作为基准机，每个子项在基准机上 = 1000 分，其他设备按比例换算。候选：Nexus 7（2012，Tegra 3，Android 4.1 首发机）。
- 大项分数 = 子项分数的几何平均；总分 = 大项分数的几何平均。
- 某个子项跑不了（例如没有数据包、驱动不支持），在大项里跳过并标注，不按 0 分算，否则几何平均会变成 0。
- 基准机定下来之前，先只显示原始成绩。

### 5.3 存储与导出

- 本地记录存 App 自己的 SQLite 数据库，包括设备信息快照、各项原始成绩、分数、运行条件（温度、电量、是否充电）、App 版本和测试版本号。
- 导出：文字（复制到剪贴板）、图片（分享），可选 JSON（方便以后做榜单）。
- 测试版本号要单独记，以后换了 CoreMark 参数或 GFXBench 配置，旧成绩不能直接比较。

## 6. 离线对比（可选）

Kishonti 公开了 GFXBench 历史榜单（CC BY 4.0，见 [04](04-gfxbench-port.md) 和 [08](08-licenses.md)），可以挑一批经典 4.x 机型的 T-Rex、Egypt 成绩内置到 App 里，结果页显示"你的设备 vs 当年的 Galaxy S III"。CPU、内存、存储没有现成的公开历史数据，只能靠我们自己积累。
