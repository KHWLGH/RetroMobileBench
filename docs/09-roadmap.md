# 09 路线图与风险

## 1. 里程碑

| # | 里程碑 | 主要内容 | 完成标准 | 状态（2026-11-12） |
|---|---|---|---|---|
| M0 | 工具链与空项目 | 装 NDK r23c、CMake 3.18.1；建 minSdk 16 空项目（删掉 AndroidX 依赖）；原生库编 armeabi-v7a（NEON / 无 NEON）和 x86 | 空 App 在 4.1 模拟器（x86）和 4.1 真机（ARM）上启动，JNI 调用成功；确认 AGP 接受 minSdk 16 + NDK r23c | 模拟器完成；ARM 真机待测 |
| M1 | GFXBench 底层测试 | 自己的 CMake 工程编 ES2 部分（[04](04-gfxbench-port.md) §4），`gfxprobe` 命令行程序 + `adb push` 配置和数据 | 4.1 真机上跑通 gl_alu_off、gl_fill_off、gl_driver_off、gl_blending_off | 模拟器完成；真机待测 |
| M2 | GFXBench 场景测试 | 导出 ETC1 数据，`texture_type` 固定 ETC1 | gl_trex_off、gl_egypt_off 跑通，和历史成绩相差 ±10% 以内（考虑厂商优化因素，见 [07](07-measurement.md) §6） | 模拟器跑通、画面正确；历史成绩对照要真机 |
| M3 | CPU / 内存 / 存储 / 设备信息 | 按 [03](03-benchmarks.md) 接入各组件 | 各项在真机上出结果，多次运行波动小于 5% | CoreMark、SciMark、zlib/zstd、tinymembench C/依赖延迟、STREAM 变体、文件/固定 SQLite、各核信息已实现，API 16 x86 集成测试通过；微架构/缓存识别及真机稳定性待做 |
| M4 | 整合 | 单 .so、静态工厂、NativeLoader、GPU 测试独立进程、我们的界面、数据包导入、full/lite 两个版本 | 一键跑完全部测试，结果写进本地记录 | 全部套件、GPU 一次一进程、单 .so、ZIP 校验导入、默认/完整数据构建、一键顺序测试已实现；外置 SD 目标和独立 product flavor 尚未做 |
| M5 | 三套 ABI 验证 | 无 NEON 版在 Tegra 2 真机、x86 版在 Atom 设备（或模拟器）上跑通 | 全部测试在三种设备上能跑完 | x86 在模拟器上完成；无 NEON 版已确认没有 NEON 指令，缺设备 |
| M6 | 计分与发布 | 选基准机、定计分公式、导出、许可证页面、离线对比（可选） | 发布第一个 APK | JSON 复制和已集成组件的许可入口已做；计分 / 发布待做 |

M1、M2 风险最大，已排在最前面先做。模拟器上只用了一天就跑通，剩下的不确定性都在真机上（老 GPU 驱动、成绩是否和历史数据一致）。**下一步最重要的是找一台 4.1–4.4 的 ARM 真机**，用 `scripts/feasibility-gfxbench-run.ps1` 跑一遍（见 [10-feasibility.md](10-feasibility.md) §6）。M3 的代码和 x86 功能验证现已完成，当前重点为 App 整合验证与真机对照。

## 2. 风险清单

| 风险 | 可能性 | 影响 | 应对 |
|---|---|---|---|
| ~~AGP 不再接受 minSdk 16 或 NDK r23c~~ | 已排除 | — | AGP 9.5.1 实测接受（M0） |
| GFXBench ES2 路径有没扫描出来的 ES3 依赖 | 低 | 高 | 编译和链接已通过（导入符号只有 ES2 函数），6 项测试在 4.1 模拟器上跑通；真机上再确认 |
| ~~三份 zlib 符号冲突、Poco 等第三方库编不过~~ | 已排除 | — | 合并后的 GFXBench 不编 Poco、libepoxy；CPU zlib 用 Z_PREFIX + z_errmsg 私有重命名 + 隐藏符号隔离，已链接三套库 |
| ~~GFXBench Java 宿主依赖难剥离~~ | 已排除 | — | 不用 Java 宿主，原生层自己做（[04](04-gfxbench-port.md) §4.5） |
| 老 GPU 的 EGL 行为和模拟器不同 | 中 | 中 | 已经遇到一个：模拟器返回句柄为 0 的 EGLConfig，testfw 当成失败，已改用自己的上下文。真机上还要确认 1920×1080 pbuffer 能否创建；不行就用小 pbuffer：离屏测试画在 GFXBench 自己的 1920×1080 FBO 里，已实测 800×480 pbuffer 下 ALU、T-Rex 照常跑、结果里的渲染分辨率仍是 1920×1080 |
| 无 NEON 构建在某些第三方库上编不过（汇编里写死 NEON） | 中 | 中 | tinymembench 只编 C 内核；所有无 NEON 目标（含 GFXBench 静态依赖）加 -mfpu=vfpv3-d16，产物以反汇编检查 |
| 找不到 Tegra 2 真机 | 中 | 中 | 二手市场找 Motorola Xoom（官方 4.1）、Galaxy Tab 10.1 等；实在没有就把无 NEON 版标为"未验证"。也可以在有 NEON 的真机上强制跑无 NEON 版（`-Variant armeabi-v7a-noneon`），至少验证功能 |
| 老 GPU 驱动 bug 导致个别测试崩溃 | 高 | 低 | GPU 测试独立进程，失败跳过，不影响其他项 |
| 存储测试在 FUSE 路径上 `O_DIRECT` 失败 | 高 | 低 | 退回普通读写并标注"含缓存" |
| 数据包太大，老设备空间不够 | 中 | 低 | 场景数据可选、导入容量校验、成功后清理完全替代的旧包；当前存于内部私有目录，外置 SD 目标仍待做 |
| 同型号成绩和历史数据差很多 | 中 | 中 | 查纹理格式、分辨率、垂直同步、降频；记得厂商当年有跑分白名单 |

## 3. GFXBench 移植的备选方案

模拟器上已经跑通，这些方案只在真机上遇到无法解决的问题时才需要：

1. **缩小范围**：先只上四个底层测试（代码量小、不需要场景数据），场景测试放到下个版本。
2. **自写 ES2 测试**：参考 GFXBench 的着色器和测试思路，自己写填充率、着色器算力、驱动开销、混合四个测试；场景测试暂缺。分数自成体系，不能和 GFXBench 历史数据对照。
3. glmark2 只能用来交叉验证（GPL-3.0，代码不能并进来）。

## 4. 待确认的问题

| 问题 | 什么时候确认 | 结论 |
|---|---|---|
| 当前 AGP 版本能否配合 minSdk 16 + NDK r23c | M0 | **能**（AGP 9.5.1 + Gradle 9.7.0） |
| ES2 构建目标是否需要定义 `OPT_TEST_GFX30` | M1 | **不需要**，`OPT_TEST_GFX2` 就够 |
| testfw 的 deviceinfo 是否必须依赖 libepoxy | M1 | **不相关**：不编 deviceinfo |
| `common/` 里的画质比对参考图能否删掉 | M2 | **能**，删掉后 T-Rex、Egypt 正常 |
| 真机上 1920×1080 的 pbuffer 能否创建 | M1 真机 | 不行的话有退路：800×480 pbuffer 已实测可用 |
| `ui.fs` 的精度补丁在不支持 highp 的 GPU 上是否必要、是否生效 | M1 真机（Mali-400 或 Tegra 2/3） | |
| SQLite 在 API 16 下是否需要 `SQLITE_DISABLE_LFS` | M3 | 3.53.1 使用 SQLITE_DISABLE_LFS=1、TEMP_STORE=3；API 16 x86 事务测试通过 |
| `arm-neon.S` 在无 NEON 构建中能否正常汇编 | M3 | 当前仅编 tinymembench C 内核，不依赖该汇编，也不把 NEON 指令装入无 NEON 库 |
| 基准机选哪台（候选 Nexus 7 2012） | M6 | |
| 屏上测试要不要计入总分 | M6 | |

## 5. 以后的版本（不在第一版范围）

- 联网榜单（注意 TLS 1.2 和根证书问题，见 [02](02-platform-constraints.md) §7）。
- Android 4.3/4.4 上的可选 ES3 测试（GFXBench 的 Manhattan、ALU 2 等）。
- Android 2.3/4.0 支持（需要更老的 NDK，GFXBench 的 ES2 路径可能还能用）。
- 续航测试、长时间稳定性测试（GFXBench 有 battery_test 框架可以参考）。
