# 05 构建与工具链

> 2026-11-12 已在本机验证（M0、M1）：下面的版本组合能编出 minSdk 16 的 APK，能在 Android 4.1.2 模拟器上运行；GFXBench 的 ES2 部分用 NDK r23c 编过，并在模拟器上跑通。验证经过和结果见 [10-feasibility.md](10-feasibility.md)。

## 1. 工具版本

| 工具 | 版本 | 用途 | 状态 |
|---|---|---|---|
| Android Studio | 当前版本（自带 AGP 9.5.1） | IDE | 已装 |
| Android Gradle 插件（AGP） | **9.5.1** | 构建 | 已验证：接受 minSdk 16 + NDK r23c |
| Gradle | **9.7.0**（wrapper） | 构建 | 已验证 |
| JDK | Android Studio 自带的 JBR（JDK 25） | 运行 Gradle | 已验证，不需要另装 JDK 17 |
| Android SDK Platform | android-37（compileSdk） | 编译 | 已装 |
| **NDK** | **r23c（23.2.8568313）** | 原生代码 | 已装、已验证 |
| **CMake** | **3.18.1**（SDK Manager 里的版本） | 原生构建 | 已装、已验证 |
| Android 4.1 模拟器 | API 16，x86 | 验证 x86 构建 | AVD "Android4.1"（720×1280，1 GB 内存） |
| SWIG | — | 原计划用来生成 GFXBench testfw 的 Java 绑定 | **不需要了**：原生层自己做宿主，见 [04](04-gfxbench-port.md) §4.5 |

用 SDK Manager 安装 NDK 和 CMake（勾选 "Show Package Details" 才能选到具体版本）：

```
sdkmanager "ndk;23.2.8568313" "cmake;3.18.1"
```

## 2. 为什么是这些版本

- **NDK r23c**：最后一个支持 API 16–18 和无 NEON 设备的版本，r24 起最低 API 19。
- **CMake 3.18.1**：r23c 的 `android.toolchain.cmake` 默认用旧版 toolchain 文件。旧版 toolchain 文件配 CMake 3.22 编汇编文件时会多出 `-gcc-toolchain` 参数导致报错（NDK issue 1623，r24 才修），tinymembench 有汇编文件会受影响。cpuinfo 要求 CMake ≥ 3.18。
- **AGP 9.5.1**：调研时担心新版 AGP 不再接受 minSdk 16 或 NDK r23c，实测都接受，只是会对 32 位 ABI 给出警告（已在 `gradle.properties` 里用 `android.sync.suppressAgpWarnings=EXTERNAL_NATIVE_BUILD_CONFIGURATION` 关掉）。

## 3. Gradle 配置

主 App 的 CPU/内存/GPU 依赖通过固定提交子模块维护，SQLite/SciMark 使用哈希固定的归档源码。初始化命令和 GFXBench 稀疏检出目录见 [third_party/README.md](../third_party/README.md)。依赖准备完成后，Gradle/CMake 不下载源码。每套 librmb 包含全部计算内核和静态 GFXBench 依赖；CPU zlib 用 Z_PREFIX、z_errmsg 私有重命名和隐藏符号隔离 GPU 旧版 zlib 及 ROM libz。

实际配置见 `app/build.gradle.kts`，要点：

```kotlin
android {
    compileSdk = 37
    ndkVersion = "23.2.8568313"
    defaultConfig {
        minSdk = 16
        targetSdk = 23                      // 见 README "发布方式"
        ndk { abiFilters += listOf("armeabi-v7a", "x86") }
        externalNativeBuild {
            cmake { arguments += listOf("-DANDROID_STL=c++_static", "-DANDROID_PLATFORM=android-16") }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    externalNativeBuild {
        cmake { path = file("../native/CMakeLists.txt"); version = "3.18.1" }
    }
}
dependencies { }                            // 不加 AndroidX / appcompat / material / Kotlin
```

- **Java 17**：D8 会把 Java 9–17 的语言特性脱糖，并替换 javac 生成的 `java.util.Objects` 等调用。已检查编出的 dex：格式版本 035，没有 `invoke-custom`，没有 `java.util.Objects` 引用，API 16 能加载。Java 8 源码级别在 JDK 25 上会报过时警告，所以直接用 17。Java 的新 API（`java.util.stream`、`java.time` 等）仍然不能用。
- **不要 Kotlin 标准库**：AGP 9 内置 Kotlin 支持，默认会给每个 App 加 `kotlin-stdlib`（约 1 万个方法，会把 minSdk 16 的 App 推向 64K 方法数上限和旧版 multidex）。`gradle.properties` 里加 `kotlin.stdlib.default.dependency=false` 去掉。不要用 `android.builtInKotlin=false`，它已经过时。
- 新建项目时 Android Studio 会自动加 appcompat、material、core-ktx、AndroidX 测试库，全部删掉。
- M0 的 debug APK 约 835 KB（含三套探针库）；实际体积随代码和调试符号变化。
- Gradle 使用宿主机的 JBR 25，不会使手机需要 Java 25。手机兼容性由 minSdk、D8 输出和原生 API 决定；保留 NDK r23c / CMake 3.18.1，不因 IDE 的升级建议替换它们。
- Lint 只排除与直接分发策略冲突的 `ExpiredTargetSdkVersion`；`NewApi` / `InlinedApi` 明确设为 error，`lintDebug` 必须通过。构建、集成测试命令见 [11-backend.md](11-backend.md)。

## 4. 原生库的三套产物

最终采用**在 CMake 里定义两个目标**（调研时的方案 A），AGP 一次构建就产出全部三套：

| 产物 | ABI 目录 | 编译参数 | 给谁用 |
|---|---|---|---|
| `librmb.so` | armeabi-v7a | NDK 默认（`-march=armv7-a -mthumb`，默认带 NEON） | 有 NEON 的 ARMv7 |
| `librmb_noneon.so` | armeabi-v7a | 所有源文件加 `-mfpu=vfpv3-d16` | Tegra 2 等没有 NEON 的 ARMv7 |
| `librmb.so` | x86 | NDK 默认 | x86 设备、模拟器 |

`NativeLoader.java` 读 `/proc/cpuinfo` 的 Features 行，ARM 设备上没有 `neon`/`asimd` 就加载 `rmb_noneon`。

注意事项（实测发现）：

- **NDK r23c 的 armeabi-v7a 默认就开 NEON**：预定义 `__ARM_NEON`，整数循环会被自动向量化成 NEON 指令。所以无 NEON 那套必须显式加 `-mfpu=vfpv3-d16`，不是"不写 NEON 代码就行"。
- **不能用 ELF 属性判断有没有 NEON**：链接后 `Tag_Advanced_SIMD_arch` 属性会被丢掉，`readelf -A` 看不出来。改用 `scripts/check-neon.ps1`：用 llvm-objdump 分别按"有 NEON"和"只有 VFPv3-D16"反汇编两遍，数只有前者能解码的指令。实测：
  - 探针库：`librmb.so` 16 条，`librmb_noneon.so` 0 条。
  - GFXBench ES2：NEON 版 654 条，无 NEON 版 0 条。
- 调研时建议的方案 B（单独编一套放进 `jniLibs`）试过，被方案 A 取代：两步构建容易忘，产物还要手动同步。

## 5. 构建变体

当前通过 Gradle 属性选择内置数据，尚未定义 product flavor：

| 构建参数 | 内容 |
|---|---|
| 默认 | 三套原生库 + 四项底层 GPU 数据（约 2.6 MB 解包后） |
| `-PgpuData=full` | 再加 T-Rex/Egypt ETC1（约 171 MB 解包后） |

`generateGpuPack` 输出 `app/build/generated/gpuPack/gpu-data.zip`；所有 variant 的 assets 自动引用该任务。默认/完整构建使用同一 applicationId，安装会更新同一 App。默认版可以导入完整 ZIP；导入目标目前是内部私有目录。详见 [06](06-data-packs.md)。

## 6. GFXBench 的构建

**不用 GFXBench 自己的构建脚本**（`scripts/build.sh` 那套需要改 API、关 CUDA、装 SWIG，而且会把 ES3 部分一起编进来）。改为自己写一个 CMake 工程，直接引用 GFXBench 源码树里 ES2 测试需要的文件：

```powershell
# 编译（输出到 feasibility/out/<abi>）
scripts\feasibility-gfxbench-es2.ps1 -Abi x86
scripts\feasibility-gfxbench-es2.ps1 -Abi armeabi-v7a
scripts\feasibility-gfxbench-es2.ps1 -Abi armeabi-v7a -NoNeon

# 在设备或模拟器上跑（自动准备数据、按 CPU 选 NEON/无 NEON 版、推送、汇总成绩）
scripts\feasibility-gfxbench-run.ps1
```

- CMake 工程：`feasibility/gfxbench-es2/CMakeLists.txt`（源文件清单、宏定义见 [04](04-gfxbench-port.md) §4.2）。
- 每次全量编译约 200 个文件，本机 Ninja 并行编译约 20 秒。
- 独立模式仍输出 `libgfxbench_es2_probe.so` 和 `gfxprobe`，用于不安装 App 的可行性检查。
- **App 已整合**：`native/CMakeLists.txt` 每套库调用该子工程，静态目标名带 rmb_/rmb_noneon_ 前缀，宿主/JNI 直接加入主库；不产生额外 App 动态依赖。无 NEON 子工程所有源文件同样加 -mfpu=vfpv3-d16，C++ 运行时静态链接。

## 7. 获取第三方源码

| 组件 | 获取方式 | 备注 |
|---|---|---|
| GFXBench | `git clone --filter=blob:none --depth 1` 后用 sparse-checkout 只取 `frameworks`、`gfxbench40`、`scripts`、`app_android`、`doc`、`3rdparty/zlib`、`3rdparty/libpng`、`3rdparty/poco/Foundation` | 完整仓库约 1.7 GB；场景数据见下 |
| GFXBench 场景数据 | App 子模块可用 sparse-checkout add 检出；独立脚本使用批量 blob 导出 | 第三方 README 给出 App 实际命令；下方记录早期逐对象下载问题 |
| CoreMark、zstd、zlib、tinymembench、STREAM、GFXBench | git submodule，固定到具体 commit | 版本见 third_party/README；cpuinfo 尚未引入 |
| SciMark 2 | 从 NIST 下载 C 源码包，直接放进仓库 | 没有 git 仓库 |
| SQLite | 下载 amalgamation 源码包 | 固定版本 |

取场景数据时要注意（实测踩过的坑）：

- 仓库是 `blob:none` 部分克隆，场景数据是普通 git 对象，不是 Git LFS。
- 不要对数据目录直接跑 `git ls-tree -l`、`git archive`、`git checkout`：缺的对象会**一个一个**按需下载，一千个文件要几十分钟甚至卡住。
- 正确做法：先用 `git ls-tree -r` 列出要的文件（只要 `images_ETC1`），用 `git cat-file --batch-check` 找出本地缺的对象，再用 `git fetch origin <sha>...` 每批 200 个取回来（T-Rex 加 common 约 900 个对象，34 秒），最后通过临时索引文件（`GIT_INDEX_FILE`）+ `git checkout-index` 导出，不碰克隆本身的工作区。
- 设置 `GIT_NO_LAZY_FETCH=1` 可以让漏取的对象直接报错，而不是悄悄开始逐个下载。

调研时的浅克隆在 `%TEMP%\rmb-research\gfxbench`，独立脚本默认仍使用它。主 App 使用仓库内固定提交的 `third_party/gfxbench`，不依赖临时研究目录。
