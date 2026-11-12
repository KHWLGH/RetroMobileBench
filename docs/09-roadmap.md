# 09 路线图与风险

## 1. 里程碑

| # | 里程碑 | 主要内容 | 完成标准 |
|---|---|---|---|
| M0 | 工具链与空项目 | 装 NDK r23c、CMake 3.18.1、JDK 17、SWIG；建 minSdk 16 空项目（删掉 AndroidX 依赖）；原生库编 armeabi-v7a（NEON / 无 NEON）和 x86 | 空 App 在 4.1 模拟器（x86）和 4.1 真机（ARM）上启动，JNI 调用成功；确认 AGP 接受 minSdk 16 + NDK r23c |
| M1 | GFXBench 底层测试 | 按 [04](04-gfxbench-port.md) 改构建，developer 模式，`adb push` 配置和数据 | 4.1 真机上跑通 gl_alu_off、gl_fill_off、gl_driver_off、gl_blending_off |
| M2 | GFXBench 场景测试 | 制作 ETC1 数据，`texture_type` 改 ETC1 | gl_trex_off、gl_egypt_off 跑通，和历史成绩相差 ±10% 以内（考虑厂商优化因素，见 [07](07-measurement.md) §6） |
| M3 | CPU / 内存 / 存储 / 设备信息 | 按 [03](03-benchmarks.md) 接入各组件 | 各项在真机上出结果，多次运行波动小于 5% |
| M4 | 整合 | 单 .so、静态工厂、NativeLoader、GPU 测试独立进程、我们的界面、数据包导入、full/lite 两个版本 | 一键跑完全部测试，结果写进本地记录 |
| M5 | 三套 ABI 验证 | 无 NEON 版在 Tegra 2 真机、x86 版在 Atom 设备（或模拟器）上跑通 | 全部测试在三种设备上能跑完 |
| M6 | 计分与发布 | 选基准机、定计分公式、导出、许可证页面、离线对比（可选） | 发布第一个 APK |

M1、M2 风险最大，建议排在最前面，限时 1–2 周。M3 不依赖 GFXBench，可以并行做。

## 2. 风险清单

| 风险 | 可能性 | 影响 | 应对 |
|---|---|---|---|
| AGP 不再接受 minSdk 16 或 NDK r23c | 中 | 高 | M0 第一件事就验证；不行就降 AGP 版本（需要对应的旧版 Gradle 和 JDK），或者原生库用 NDK 命令行单独编，Gradle 只打包预编译的 .so |
| GFXBench ES2 路径有没扫描出来的 ES3 依赖 | 中 | 高 | 编译时 API 16 的头文件会把缺失的函数报出来；运行时开 GL 错误检查；限时，超时就转备选方案 |
| 三份 zlib 符号冲突、Poco 等第三方库编不过 | 中 | 中 | 合并成一份 zlib，或给其中一份加 `Z_PREFIX`；Poco 只编 Foundation |
| GFXBench Java 宿主依赖难剥离 | 低 | 中 | 只用到 4 个文件里的 commons-io/Gson，替换量不大 |
| 无 NEON 构建在某些第三方库上编不过（汇编里写死 NEON） | 中 | 中 | 无 NEON 版只关掉对应的汇编实现，退回 C 实现 |
| 找不到 Tegra 2 真机 | 中 | 中 | 二手市场找 Motorola Xoom（官方 4.1）、Galaxy Tab 10.1 等；实在没有就把无 NEON 版标为"未验证" |
| 老 GPU 驱动 bug 导致个别测试崩溃 | 高 | 低 | GPU 测试独立进程，失败跳过，不影响其他项 |
| 存储测试在 FUSE 路径上 `O_DIRECT` 失败 | 高 | 低 | 退回普通读写并标注"含缓存" |
| 数据包太大，老设备空间不够 | 中 | 低 | 场景数据可选；按场景拆包；优先放 SD 卡 |
| 同型号成绩和历史数据差很多 | 中 | 中 | 查纹理格式、分辨率、垂直同步、降频；记得厂商当年有跑分白名单 |

## 3. GFXBench 移植的备选方案

如果 M1/M2 在限时内跑不通：

1. **缩小范围**：先只上四个底层测试（代码量小、不需要场景数据），场景测试放到下个版本。
2. **自写 ES2 测试**：参考 GFXBench 的着色器和测试思路，自己写填充率、着色器算力、驱动开销、混合四个测试；场景测试暂缺。分数自成体系，不能和 GFXBench 历史数据对照。
3. glmark2 只能用来交叉验证（GPL-3.0，代码不能并进来）。

## 4. 待确认的问题

| 问题 | 什么时候确认 |
|---|---|
| 当前 AGP 版本能否配合 minSdk 16 + NDK r23c | M0 |
| ES2 构建目标是否需要定义 `OPT_TEST_GFX30` | M1 |
| testfw 的 deviceinfo 是否必须依赖 libepoxy | M1 |
| SQLite 在 API 16 下是否需要 `SQLITE_DISABLE_LFS` | M3 |
| `arm-neon.S` 在无 NEON 构建中能否正常汇编 | M0/M3 |
| 基准机选哪台（候选 Nexus 7 2012） | M6 |
| `common/` 里的画质比对参考图能否删掉 | M2 |
| 屏上测试要不要计入总分 | M6 |

## 5. 以后的版本（不在第一版范围）

- 联网榜单（注意 TLS 1.2 和根证书问题，见 [02](02-platform-constraints.md) §7）。
- Android 4.3/4.4 上的可选 ES3 测试（GFXBench 的 Manhattan、ALU 2 等）。
- Android 2.3/4.0 支持（需要更老的 NDK，GFXBench 的 ES2 路径可能还能用）。
- 续航测试、长时间稳定性测试（GFXBench 有 battery_test 框架可以参考）。
