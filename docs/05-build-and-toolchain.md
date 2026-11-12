# 05 构建与工具链

> 版本组合还没实际验证。特别是"当前版本的 Android Gradle 插件能否配合 NDK r23c 和 minSdk 16"，调研时没查到明确说法，是第一个里程碑要确认的事。

## 1. 工具版本

| 工具 | 版本 | 用途 | 本机状态（2026-11-12） |
|---|---|---|---|
| Android Studio | 当前版本 | IDE | 已装 |
| Android SDK Platform | 当前（compileSdk） | 编译 | 已装 android-37 |
| Android SDK Build-Tools | 36.0.0 | 打包 | 已装 |
| **NDK** | **r23c（23.2.8568313）** | 原生代码 | **未装** |
| **CMake** | **3.18.1**（SDK Manager 里的版本） | 原生构建 | **未装** |
| **JDK 17** | 17 | GFXBench 原版构建脚本里的 Gradle 7.4.2 / 8.11.1 | **未装**（Android Studio 自带 JDK 25，跑 AGP 足够） |
| **SWIG** | 4.0.2 或 3.0.12 | 生成 GFXBench testfw 的 Java 绑定 | **未装** |
| Git Bash | — | GFXBench 构建脚本（官方说明不支持 Cygwin 和 WSL） | 已装 |
| NASM | 可选 | libjpeg-turbo 的 x86 SIMD（如果用的话） | 未装 |
| Android 4.1 模拟器 | API 16，x86 | 验证 x86 构建 | 已有 AVD "Android4.1"（720×1280，1 GB 内存） |

用 SDK Manager 安装 NDK 和 CMake（勾选 "Show Package Details" 才能选到具体版本）：

```
sdkmanager "ndk;23.2.8568313" "cmake;3.18.1"
```

## 2. 为什么是这些版本

- **NDK r23c**：最后一个支持 API 16–18 和无 NEON 设备的版本，r24 起最低 API 19。
- **CMake 3.18.1**：r23c 的 `android.toolchain.cmake` 默认用旧版 toolchain 文件。旧版 toolchain 文件配 CMake 3.22 编汇编文件时会多出 `-gcc-toolchain` 参数导致报错（NDK issue 1623，r24 才修），tinymembench 有汇编文件会受影响。cpuinfo 要求 CMake ≥ 3.18。
- **SWIG**：GFXBench testfw 的 Java 类（`net.kishonti.swig.*`）是 SWIG 从 `testfw.i` 生成的。也可以生成一次后把 Java 文件提交进仓库，以后就不需要 SWIG 了。

## 3. Gradle 配置（示意）

```groovy
// app/build.gradle（示意，未验证）
android {
    compileSdk 37                    // 跟随已安装的 SDK
    ndkVersion "23.2.8568313"

    defaultConfig {
        minSdk 16
        targetSdk 23                 // 见 README "发布方式"
        externalNativeBuild {
            cmake {
                arguments "-DANDROID_STL=c++_static",
                          "-DANDROID_PLATFORM=android-16"
            }
        }
        ndk { abiFilters "armeabi-v7a", "x86" }
    }

    externalNativeBuild {
        cmake {
            path "../native/CMakeLists.txt"
            version "3.18.1"
        }
    }

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_1_8
        targetCompatibility JavaVersion.VERSION_1_8
    }
}

dependencies {
    // 不加 AndroidX / appcompat / material
}
```

- 新建项目时 Android Studio 会自动加 appcompat、material、core-ktx、AndroidX 测试库，全部删掉。
- Java 8 语言特性（lambda 等）会被 D8 脱糖，可以在 API 16 上用；但 Java 8 的新 API（`java.util.stream`、`java.time` 等）不能用（除非开 core library desugaring，不建议，会增加方法数）。

## 4. 原生库的三套产物

AGP 的 `externalNativeBuild` 一次只按 ABI 编一份，无 NEON 那份要额外处理。两个可选方案：

| 方案 | 做法 | 优点 | 缺点 |
|---|---|---|---|
| A. CMake 里定义两个目标 | `rmb`（默认参数）和 `rmb_noneon`（对所有源文件加 `-mfpu=vfpv3-d16`，去掉 NEON） | AGP 一次构建全部产出 | 第三方库也要编两遍，CMake 写法稍复杂；要确认 NDK 的默认 `-mfpu=neon` 能被覆盖 |
| B. 单独编一次无 NEON 版 | 用 NDK 的 CMake 命令行加 `-DANDROID_ARM_NEON=OFF` 单独编，产物作为预编译库放进 `jniLibs/armeabi-v7a/` | 和官方推荐做法一致，参数干净 | 构建分两步，要写脚本 |

建议先用方案 B（行为明确），稳定后再考虑方案 A。x86 那份 AGP 正常编就行。

## 5. 构建变体

数据包的两种提供方式（见 [06](06-data-packs.md)）用 product flavor 区分：

| flavor | 内容 | 大小 |
|---|---|---|
| `lite` | App + 三套原生库 + 底层 GPU 测试需要的小数据（不到 3 MB） | 主要取决于原生库，编出来才知道 |
| `full` | 再加 T-Rex、Egypt 的 ETC1 场景数据 | 比 lite 多约 180 MB |

两个 flavor 用同一个 `applicationId`，装哪个都行，不能同时装。full 版可以通过"导入"把数据复制到 SD 卡给别的设备用，反过来 lite 版可以导入数据包。

## 6. GFXBench 的构建

GFXBench 移植初期先用它自己的构建脚本单独编，验证通过后再并进我们的 CMake 工程：

```bash
# Git Bash，在 GFXBench 仓库根目录（示意，需按 04 文档先改脚本里写死的 API 24）
export WORKSPACE=$PWD
export PLATFORM=android-armv7a
export CONFIG=Release
export APPLICATION_TYPE=developer
export PRODUCT_ID=gfxbench_gl
export PRODUCT_VERSION=4.0.0
export ANDROID_NDK="$LOCALAPPDATA/Android/Sdk/ndk/23.2.8568313"
export ANDROID_HOME="$LOCALAPPDATA/Android/Sdk"
export NG_CMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake"

scripts/build-3rdparty.sh
scripts/build.sh
```

- `PLATFORM` 可选 `android-armv7a`、`android-x86`（还有 arm64、x86-64，我们用不到）。
- 官方 CI 的安卓构建还会装 CUDA 工具包（`frameworks/cudaw` 要用），我们不需要 CUDA 采集，可以在 `build.sh` 里去掉 `frameworks/clew frameworks/cudaw`。
- `BUNDLE_DATA=false` 可以跳过复制场景数据，加快重复构建。

## 7. 获取第三方源码

| 组件 | 获取方式 | 备注 |
|---|---|---|
| GFXBench | `git clone --filter=blob:none --depth 1` 后用 sparse-checkout 只取 `frameworks`、`gfxbench40`、`scripts`、`app_android`、`doc`、`3rdparty/zlib`、`3rdparty/libpng`、`3rdparty/poco/Foundation` | 完整仓库约 1.7 GB；场景数据单独取，见 [06](06-data-packs.md) |
| CoreMark、cpuinfo、zstd、zlib、tinymembench | git submodule，固定到具体 commit | 固定版本，成绩才可比 |
| SciMark 2 | 从 NIST 下载 C 源码包，直接放进仓库 | 没有 git 仓库 |
| SQLite | 下载 amalgamation 源码包 | 固定版本 |

调研时的浅克隆在 `%TEMP%\rmb-research\gfxbench`（约 180 MB，不含场景数据，已包含 Poco Foundation），可以直接拿来开始移植。
