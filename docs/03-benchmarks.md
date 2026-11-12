# 03 测试项目与开源组件

原则：测试代码尽量复用宽松许可证（MIT、BSD、Apache、zlib、公有领域）的开源项目；GPL、自定义限制性许可证、没有许可证的项目不用。所有测试都用原生代码实现。

> 许可证信息截至 2026-11-12，来源见 [references.md](references.md)。引入任何新组件前要重新核对许可证。

## 1. 选型总表

| 类别 | 组件 | 许可证 | 测什么 | 要改的地方 |
|---|---|---|---|---|
| CPU 整数 | [CoreMark](https://github.com/eembc/coremark) | Apache-2.0 + EEMBC 商标协议 | 链表、矩阵、状态机、CRC | 只能改 `core_portme*`；用 pthread 跑多核 |
| CPU 浮点 | [SciMark 2](https://math.nist.gov/scimark2/)（C 版） | 公有领域 | FFT、SOR、蒙特卡洛、稀疏矩阵乘、LU 分解 | 基本不用改 |
| CPU 综合（可多线程） | [zstd](https://github.com/facebook/zstd) + [zlib](https://github.com/madler/zlib) | BSD-3 / zlib | 压缩、解压速度 | 自己写计时外壳；打包固定版本 |
| 内存 | [tinymembench](https://github.com/ssvb/tinymembench) | MIT | 顺序带宽（C/NEON/SSE2 多种实现）、随机访问延迟 | `posix_memalign` → `memalign`；汇编用 GNU as |
| 内存（多核带宽） | [STREAM](https://github.com/jeffhammond/STREAM) 改 pthread 版 | STREAM 自有许可 | Copy/Scale/Add/Triad | 改成 pthread；成绩标注"基于 STREAM 改版" |
| 存储 | 自己写，参数照 AndroBench | — | 顺序读写、随机读写 | — |
| 数据库 | [SQLite](https://sqlite.org)（打包固定版本） | 公有领域 | 增、改、删事务速度 | 不用系统自带的 SQLite |
| 设备信息 | [pytorch/cpuinfo](https://github.com/pytorch/cpuinfo) | BSD-2 | SoC 名、核心、缓存、微架构、指令集 | 基本不用改 |
| GPU | [GFXBench](https://github.com/Kishonti-Opensource/gfxbench) ES2 部分 | 代码 BSD-3，素材 CC BY 4.0 | 场景渲染、着色器算力、填充率、驱动开销、混合 | 见 [04](04-gfxbench-port.md) |

可选（第一版不一定做）：

| 组件 | 许可证 | 测什么 | 注意 |
|---|---|---|---|
| [Mbed TLS 3.6](https://github.com/Mbed-TLS/mbedtls) benchmark | Apache-2.0 | AES/GCM、SHA、RSA/ECC | 它用 `alarm()`/`SIGALRM` 计时，在 App 进程里是全进程的信号，要小心；`development` 分支已删掉 benchmark 程序，要用 3.6 LTS |
| [libjpeg-turbo](https://github.com/libjpeg-turbo/libjpeg-turbo) tjbench | IJG + BSD-3 + zlib | JPEG 编解码 | 官方文档写了 API 16 的 NDK 编法；x86 SIMD 需要 NASM |
| [LZMA SDK](https://www.7-zip.org/sdk.html) | 公有领域 | LZMA 压缩，可多线程 | `CpuArch.c` 直接调 `getauxval`，要打补丁 |
| [multichase](https://github.com/google/multichase) | Apache-2.0 | 多线程指针追逐延迟 | 带 `-Werror`，ARMv7 上有 `#warning` 会导致编译失败；默认 256 MB 太大，要用 `-m` 调小 |
| SQLite speedtest1 | 公有领域 | 综合 SQL 负载 | 比 AndroBench 式事务测试重 |

## 2. CPU

### 2.1 CoreMark

- **用法**：编 posix 移植版，多核用 `MULTITHREAD=N` + `USE_PTHREAD`。不要用 `USE_FORK`（需要 `shmget`，API 26 才有）。
- **运行规则**（不遵守就不能叫"CoreMark"）：
  - 单次至少跑 10 秒。
  - 必须通过两组种子校验：`0,0,0x66` 和 `0x3415,0x3415,0x66`，数据区 2000 字节。
  - 所有文件用同一组编译参数。
  - 只能改 `core_portme*` 文件；`make check` 会校验其他文件的 MD5。
  - 成绩格式：`CoreMark 1.0 : 分数 / 编译器+参数`。
- **商标协议**要点：改过核心文件的版本不能用 CoreMark 这个名字；要显示 EEMBC 商标声明；不能用于贬损 EEMBC 的场合。
- **API 16**：posix 移植只用到 `clock_gettime` 和 pthread，应该没问题（待编译验证）。

### 2.2 SciMark 2

- ANSI C，5 个子项：FFT、SOR、蒙特卡洛、稀疏矩阵乘、LU 分解，结果单位 Mflops。
- 公有领域，NIST 希望注明出处。
- LU 分解覆盖了 Linpack 那类负载，所以不再单独用 Linpack（Linpack C 版没有许可证）。

### 2.3 zstd / zlib

**已实现**：zlib 1.3.1 固定源码，单线程及最多 4 线程的独立流压缩 / 解压。数据为固定 1 MiB 文本 / 二进制混合语料，级别 6、每项至少 1.5 秒，校验和、实际线程数与迭代数进入结果；协议 `cpu-zlib-v1`，细节及 API 16 验证见 [11-backend.md](11-backend.md)。zstd 仍待接入。

- 用库，不用它们自带的 bench 程序。自己写计时外壳：固定一份测试数据（例如内置一段文本 + 一段二进制），测压缩和解压 MB/s。
- zstd 开 `ZSTD_MULTITHREAD` 后可以多线程压缩，用来测多核。
- 打包固定版本的 zlib，**不用系统自带的 libz**，否则不同 ROM 的 zlib 版本不同，成绩不可比。
- 注意：LZ4 的库是 BSD-2，但它的 bench 程序是 GPL-2，不能拿来用。

### 2.4 不用的 CPU 测试

| 项目 | 原因 |
|---|---|
| CoreMark-PRO | 成绩公开前必须先上传到 EEMBC 网站 |
| Linpack C | 源码里没有许可证 |
| Whetstone | 只允许使用、复制、发布，不允许修改 |
| Dhrystone 2.1 | 没有明确的许可证 |
| Embench-IoT | GPL-3.0 |
| nbench（BYTEmark） | 没有许可证 |
| 0xbench | 许可证混杂（Apache + CDDL），原生部分还捆绑了 GPL 组件 |

## 3. 内存

### 3.1 tinymembench

- **测什么**：
  - 顺序带宽：C 实现、libc `memcpy`/`memset`、NEON 汇编（ARM）、SSE2（x86）多种写法。默认缓冲区 32 MB，块大小 2048 字节。
  - 随机访问延迟：不同数据块大小下的额外延迟，能看出 L1、L2、内存的层级。
- **要改的地方**：
  1. 延迟测试用了 `posix_memalign`（API 17），换成 `memalign`。
  2. 汇编文件（`arm-neon.S` 等）用了 `.func` 等指令和 Intel 语法，clang 内置汇编器不接受（上游 issue #20 从 2018 年开着，修复 PR #21 没合并）。编译这些文件时加 `-fno-integrated-as -Wa,-march=armv7-a`。
  3. 结果从 `printf` 改成返回结构体，交给 JNI。
- **无 NEON 设备**：tinymembench 运行时读 `/proc/cpuinfo` 的 Features 行决定是否跑 NEON 版本，在 Tegra 2 上会自动跳过 NEON 测试。`arm-neon.S` 文件里自己声明了 `.fpu neon`，在无 NEON 那套库里应该也能正常汇编，只是运行时不会调用（待验证）。
- **注意**：tinymembench 的 1 MB = 1,000,000 字节，界面显示时要统一单位。

### 3.2 多核带宽（STREAM 改版）

- tinymembench 只测单线程。多核带宽用 STREAM 的 Triad 等 4 个操作，用 pthread 改写（原版只支持 OpenMP）。
- STREAM 许可证允许修改，但改过的版本发布成绩时必须标注，例如"基于 STREAM 改版"（based on a variant of the STREAM benchmark code）。
- STREAM 运行规则：每个数组至少是缓存大小的 4 倍，每项测试至少 20 个时钟刻度。

### 3.3 不用的内存测试

| 项目 | 原因 |
|---|---|
| lmbench | GPL-2，还禁止发布改过的版本跑出的成绩，禁止在有广告收入的网站发布成绩 |

## 4. 存储

### 4.1 文件读写测试（自写）

没有合适的宽松许可开源项目，按 AndroBench 论文的方法自己写：

| 参数 | 值 | 说明 |
|---|---|---|
| 测试文件大小 | 32 MB（默认） | 剩余空间不足时按比例缩小，结果里注明 |
| 顺序读写块大小 | 256 KB | 结果单位 MB/s |
| 随机读写块大小 | 4 KB | 在 32 MB 文件内随机位置，结果单位 IOPS |
| 写模式 | `O_SYNC` | 每次写都落盘 |
| 读模式 | `O_DIRECT` | 绕过页缓存；缓冲区按 4 KB（或页大小）对齐 |
| 线程数 | 1 | AndroBench 不支持多线程 |
| 测试位置 | 内部存储 `/data`（主分），SD 卡（附加项） | — |

注意：

- API 16 没有 `posix_fadvise`（API 21），清不了缓存，只能靠 `O_DIRECT`。
- SD 卡路径（FUSE 挂载）上 `O_DIRECT` 可能直接返回 `EINVAL`。这时要退回普通读写，并把结果标记为"含缓存，不可比"。
- 测试文件写在 App 私有目录，测完删掉。

### 4.2 SQLite 事务测试

| 参数 | 值 |
|---|---|
| 测试项 | INSERT、UPDATE、DELETE |
| 每项事务数 | 300（AndroBench 默认） |
| 结果单位 | TPS（每秒事务数） |
| SQLite 版本 | 打包固定版本，不用系统自带的 |

- AndroBench 论文没写表结构、记录大小和日志模式，这些我们自己定，并写进测试版本号。
- 编译 SQLite 时可能需要 `-DSQLITE_DISABLE_LFS`（见 [02](02-platform-constraints.md) §3），`SQLITE_TEMP_STORE=3`（Android 没有 `/tmp`），待验证。

### 4.3 不用的存储测试

| 项目 | 原因 |
|---|---|
| fio | GPL-2 |
| iozone | 作者保留发布衍生作品的独占权 |
| Mobibench | 没有许可证（可以参考它的设计） |
| AndroBench | 闭源（只参考论文里的方法） |

## 5. 设备信息

### 5.1 pytorch/cpuinfo

- BSD-2，能拿到 SoC 名称（从 Android 系统属性读）、核心数、缓存大小、微架构、指令集特性。
- 它用 `dlopen`/`dlsym` 查找 `getauxval`，找不到就读 `/proc/self/auxv`，所以能在 API 16 上运行。官方 CI 就是按 `android-14` 编 armeabi-v7a 和 x86 的。
- 需要 CMake ≥ 3.18。
- 不用 NDK 自带的 cpufeatures：已经弃用，以后会删。

### 5.2 自己采集的信息

| 信息 | 来源 |
|---|---|
| 芯片型号 | `/proc/cpuinfo` 的 Hardware 行（老内核）、cpuinfo |
| 各核最高频率 | `/sys/devices/system/cpu/cpu*/cpufreq/cpuinfo_max_freq` |
| 在线核心 | `/sys/devices/system/cpu/online` |
| 调频策略 | `/sys/devices/system/cpu/cpu*/cpufreq/scaling_governor` |
| 内存 | `/proc/meminfo` |
| GPU | `GL_RENDERER`、`GL_VENDOR`、`GL_VERSION`、扩展列表 |
| 屏幕 | 分辨率、密度（API 16 没有 `getRealMetrics`，用反射或 `getMetrics`） |
| 系统 | `Build.*`、内核版本 |
| 温度、电量 | `ACTION_BATTERY_CHANGED` 广播 |
| 存储 | 内部存储和 SD 卡容量（API 16 用 `StatFs.getBlockCount()` 等 int 版本） |
| 实际加载的原生库 | NEON / 无 NEON / x86 |

## 6. GPU

见 [04-gfxbench-port.md](04-gfxbench-port.md)。

| 测试 | 内容 | 时长 | 原始单位 |
|---|---|---|---|
| gl_trex / gl_trex_off | T-Rex 场景（屏上 / 1080p 离屏） | 56 秒 | frames |
| gl_egypt / gl_egypt_off | Egypt 场景 | 113 秒 | frames |
| gl_alu / gl_alu_off | 着色器算力 | 30 秒 | frames |
| gl_fill / gl_fill_off | 纹理填充率 | 30 秒 | MTexels/s |
| gl_driver / gl_driver_off | 驱动开销 | 30 秒 | frames |
| gl_blending / gl_blending_off | 混合（UI 合成负载） | 30 秒 | MB/s |
