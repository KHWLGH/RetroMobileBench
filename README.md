# RetroMobileBench

给旧安卓手机用的离线跑分软件。第一版只做 Android 4.x，最低兼容 Android 4.1（API 16）。

> 状态（2026-11-12）：**本地后端已开始实现**。保留 minSdk 16、NDK r23c 和 NEON / 无 NEON / x86 三套库；App 已接入原生存储读写、zlib 1.3.1 单线程 / 并行压缩解压、单轮 / 三轮中位数、取消、运行条件采集、SQLite 混合历史、JSON 复制和开源许可，后端集成测试已在 Android 4.1.2 x86 模拟器上通过，见 [docs/11-backend.md](docs/11-backend.md)。GFXBench ES2 的 6 项离屏测试仍在独立可行性工程中（[docs/10-feasibility.md](docs/10-feasibility.md)），尚未并入 App。**ARM 真机、Tegra 2 均待验证；CoreMark、SciMark、zstd、内存和 SQLite 事务跑分尚未接入。**

## 目标

- 让 2012–2014 年的 Android 4.x 手机和平板跑出可以横向比较的 CPU、内存、存储、GPU 成绩。
- 测试代码尽量复用宽松许可证的开源项目，避免自己造轮子，也避免 GPL 限制 App 本身的许可证。
- GPU 测试移植已开源的 GFXBench 4.0 中 OpenGL ES 2.0 的部分。

## 第一版范围

| 做 | 不做 |
|---|---|
| Android 4.1–4.4（API 16–19），后续版本能装就行，不专门适配 | 联网榜单、账号、成绩上传 |
| CPU：CoreMark、SciMark 2、zstd/zlib 压缩 | Vulkan、OpenGL ES 3.x 测试 |
| 内存：tinymembench、多线程带宽 | 续航测试、长时间稳定性测试 |
| 存储：按 AndroBench 方法自写，加 SQLite 事务测试 | Google Play 上架（见下方"发布方式"） |
| GPU：GFXBench ES2 测试（T-Rex、Egypt 和 4 个底层测试） | |
| 设备信息采集、本地成绩记录、导出成文字或图片 | |
| 支持没有 NEON 的 ARMv7 CPU（Tegra 2）和 x86（Intel Atom） | |

## 已确定的决策

| # | 决策 | 日期 |
|---|---|---|
| 1 | 最低系统版本 Android 4.1（API 16） | 2026-11-12 |
| 2 | 测试项目尽量复用开源项目 | 2026-11-12 |
| 3 | GPU 测试移植 GFXBench（只取 ES2 部分） | 2026-11-12 |
| 4 | 本版本不做联网榜单 | 2026-11-12 |
| 5 | 支持 Tegra 2（无 NEON），原生库多编一套 | 2026-11-12 |
| 6 | GFXBench 场景数据两种方式都支持：打进完整版 APK，或从 SD 卡导入数据包 | 2026-11-12 |

## 技术栈速览

| 项 | 选择 | 原因 |
|---|---|---|
| 语言 | Java（UI 和流程）+ C/C++（全部测试） | Dalvik 时代用 Java 写测试，测的是 JIT 而不是 CPU |
| UI | 原生 View + Holo 主题 | AndroidX 现在默认最低 API 24（2024 年 4 月起就已至少 21），Compose 至少 21 |
| NDK | **r23c**（23.2.8568313） | 最后一个支持 API 16–18 和无 NEON 设备的版本 |
| ABI | `armeabi-v7a`（NEON）、`armeabi-v7a`（无 NEON，单独一套）、`x86` | 见 [docs/02](docs/02-platform-constraints.md) |
| C++ 运行时 | `c++_static`，所有原生代码链接成一个 `.so` | Android 4.3 以前不会去 App 的 lib 目录加载依赖库 |
| 图形 | OpenGL ES 2.0 | Android 4.1/4.2 只有 ES2 |

## 文档目录

| 文档 | 内容 |
|---|---|
| [docs/01-architecture.md](docs/01-architecture.md) | 整体架构、模块划分、原生库组织、运行流程、计分 |
| [docs/02-platform-constraints.md](docs/02-platform-constraints.md) | Android 4.1 / API 16 的各项硬约束和应对方法 |
| [docs/03-benchmarks.md](docs/03-benchmarks.md) | 每个测试项选什么开源项目、怎么改、许可证 |
| [docs/04-gfxbench-port.md](docs/04-gfxbench-port.md) | GFXBench ES2 移植方案（最大的风险项） |
| [docs/05-build-and-toolchain.md](docs/05-build-and-toolchain.md) | 工具链版本、构建配置、构建变体 |
| [docs/06-data-packs.md](docs/06-data-packs.md) | GFXBench 场景数据：打包与导入 |
| [docs/07-measurement.md](docs/07-measurement.md) | 测量方法与老机器上的各种坑 |
| [docs/08-licenses.md](docs/08-licenses.md) | 第三方许可证、署名和命名规则 |
| [docs/09-roadmap.md](docs/09-roadmap.md) | 里程碑、验证方法、风险清单 |
| [docs/10-feasibility.md](docs/10-feasibility.md) | 可行性验证记录：做了什么、结果、怎么在真机上复现 |
| [docs/11-backend.md](docs/11-backend.md) | 当前后端实现、CPU / 存储测试参数、API 16 集成测试与后续工作 |
| [docs/references.md](docs/references.md) | 参考资料链接 |

## 发布方式

不上 Google Play，直接发 APK：

- 目标设备基本用不了 Play 商店：Google Play 服务 2021 年 8 月起不再为 Jelly Bean 更新。
- Google Play 要求上架应用的 targetSdk 接近最新版本（2025 年起至少 35），而本项目 targetSdk 定为 23。

targetSdk 定为 **23** 的原因：

- Android 14 起系统拒绝安装 targetSdk 低于 23 的 App。定为 23，新手机也能装，方便开发调试。
- 在 Android 4.x 设备上，targetSdk 23 和任何 ≥ 19 的值效果相同（4.x 系统只认识到 API 19 为止的行为变更）。再往上调只会在新手机上打开新行为（24 起限制 `file://` 分享，29/30 起分区存储），反而让 SD 卡数据包导入和成绩分享更麻烦。
- 代价：在 Android 6.0+ 的新手机上调试时，存储权限要在运行时申请（4.x 上是安装时授予，不受影响）。

## 获取源码与构建

原生依赖通过固定提交的 git 子模块维护。首次克隆后执行：

```powershell
git submodule update --init third_party/zlib
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

需要 [docs/05](docs/05-build-and-toolchain.md) 中的工具链；依赖初始化完成后，原生构建不再下载源码。zlib 版本、提交和许可证见 [third_party/README.md](third_party/README.md)。

## 目录结构

| 路径 | 内容 |
|---|---|
| `app/` | Android App（Java / Holo）：CPU / 存储测试、设备探针、后台调度、本地成绩记录及许可；`androidTest` 为无第三方测试框架的集成测试 |
| `native/` | 三套原生库：设备探针、文件存储、CPU 压缩测试及 JNI |
| `third_party/` | 固定提交的开源源码依赖（目前为 zlib 1.3.1） |
| `feasibility/gfxbench-es2/` | GFXBench ES2 移植的独立 CMake 工程和宿主代码（以后并进 `native/`） |
| `scripts/` | 构建、运行、检查脚本（PowerShell） |
| `docs/` | 设计文档 |
| `feasibility/out/` | 编译产物、导出的测试数据、成绩（不提交） |

## 调研资料

调研时浅克隆的 GFXBench 源码（`blob:none` 部分克隆，场景数据按需取）和下载的 GFXBench 历史成绩 CSV（约 90 MB）放在 `%TEMP%\rmb-research`。`scripts/feasibility-gfxbench-*.ps1` 默认就用这份克隆，删掉的话要重新克隆（见 [docs/05](docs/05-build-and-toolchain.md) §7）。
