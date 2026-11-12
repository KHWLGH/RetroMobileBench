# 10 可行性验证记录

> 2026-11-12。验证 [09](09-roadmap.md) 里风险最大的两件事：minSdk 16 的工具链能不能搭起来（M0），GFXBench 的 ES2 部分能不能编过、在 Android 4.1 上跑通（M1/M2）。
>
> **结论：在本机的 Android 4.1.2 x86 模拟器上全部跑通。ARM 版只验证了编译，还没在真机上跑过。**

## 1. 环境

| 项 | 值 |
|---|---|
| 主机 | Windows 11，Intel Arc 核显 |
| 构建 | AGP 9.5.1、Gradle 9.7.0、Android Studio 自带 JBR（JDK 25）、NDK r23c（23.2.8568313）、CMake 3.18.1 + Ninja |
| 设备 | AVD "Android4.1"：Android 4.1.2（API 16），x86，1 GB 内存，GPU 是主机显卡经模拟器转译（"Android Emulator OpenGL ES Translator"） |
| GFXBench | `Kishonti-Opensource/gfxbench` commit `89aa956`（2025-12-08），`%TEMP%\rmb-research\gfxbench` |

## 2. M0：工具链和空 App

| 检查项 | 结果 |
|---|---|
| AGP 9.5.1 接受 minSdk 16 + NDK r23c | 接受（只对 32 位 ABI 有警告，已关掉） |
| debug APK | 约 835 KB，含 armeabi-v7a（NEON / 无 NEON 两个库）和 x86 |
| 不依赖 AndroidX、Kotlin 标准库 | 是（关掉了 AGP 9 默认加的 kotlin-stdlib） |
| dex 能在 API 16 上加载 | Java 17 源码，D8 输出 dex 035，没有 `invoke-custom`、没有 `java.util.Objects` |
| 原生库没有用到 API 16 之后的符号 | 是 |
| 在 4.1.2 模拟器上启动、加载原生库、JNI 调用 | 成功；探针约 0.12 秒跑完，ES2 上下文可用，支持 ETC1 |
| NEON / 无 NEON 两套库确实不同 | `librmb.so` 16 条 NEON 指令，`librmb_noneon.so` 0 条（`scripts/check-neon.ps1`） |

配置细节见 [05](05-build-and-toolchain.md)。

## 3. M1/M2：GFXBench ES2

### 3.1 编译

自己写的 CMake 工程（`feasibility/gfxbench-es2/`，见 [04](04-gfxbench-port.md) §4.1–4.2），直接引用 GFXBench 源码树，不改任何 GFXBench 文件。约 200 个源文件。

| 变体 | `libgfxbench_es2_probe.so` | `gfxprobe` | NEON 指令数 |
|---|---|---|---|
| x86 | 19.2 MB | 17.1 MB | — |
| armeabi-v7a | 17.4 MB | 15.6 MB | 654 |
| armeabi-v7a 无 NEON（`-mfpu=vfpv3-d16`） | 17.4 MB | 15.6 MB | **0** |

（Release，未裁剪符号。）

- `--no-undefined` 链接通过；导入符号只有 `libEGL`/`libGLESv2` 的 ES2 函数，没有 `libGLESv3`。
- 一共只改了两处：
  1. `glb_texture.cpp` 用到 GLES2 头文件里没有的 `GL_TEXTURE_WRAP_R`，用强制包含的兼容头文件补上。
  2. 漏编了 `gfxbench_ios_rawconfig.cpp`（`gfxbench.cpp` 无条件调用其中的函数），补上。
- 不需要 `OPT_TEST_GFX30`，不需要 Poco、libepoxy、SWIG、GFXBench 的 Java 宿主。

### 3.2 运行

宿主代码（`probe_main.cpp`、`rmb_egl_context.cpp`）在原生层：读配置 → 建 ES2 pbuffer 上下文 → 静态工厂表创建测试 → `init()`/`run()`/`result()`。编成命令行程序 `gfxprobe`，用 `adb shell` 直接跑，不需要 App。

运行时只遇到一个问题：testfw 的 `EGLGraphicsContext::initPBufferSurface()` 失败。原因是模拟器的 EGL 返回了句柄值为 0 的 `EGLConfig`（合法句柄），testfw 把 0 当成"没找到"。改为自己写的 `RmbPbufferContext`，只看 `eglChooseConfig` 返回的数量（[04](04-gfxbench-port.md) §4.5）。

### 3.3 结果

全部 6 项离屏测试（1920×1080），按配置里的默认时长跑，`texture_type` 固定为 ETC1：

| 测试 | 状态 | 成绩 | 单位 | 帧数 | 加载 | 进程内存峰值 |
|---|---|---|---|---|---|---|
| gl_alu_off | OK | 191036 | 帧 | 191100 | 21 ms | 6.7 MB |
| gl_fill_off | OK | 6116 | MTexels/s | 2500 | 25 ms | 6.8 MB |
| gl_driver_off | OK | 13064 | 帧 | 13065 | 20 ms | 6.7 MB |
| gl_blending_off | OK | 59580 | MB/s | 2355 | 18 ms | 10.7 MB |
| gl_trex_off | OK | 32316 | 帧 | 32336 | 3.0 s | 89 MB |
| gl_egypt_off | OK | 85994 | 帧 | 85999 | 1.9 s | 105 MB |

- **成绩没有参考意义**：GPU 是主机的 Intel Arc 核显经模拟器转译，比 2012 年的手机 GPU 快两个数量级（Galaxy S III 的 T-Rex 离屏是 362 帧，这里是 32316 帧）。这次只验证"能跑通、结果合理"。
- 进程内存峰值是 `/proc/self/status` 的 VmHWM，只算 CPU 侧；真机上 GPU 和 CPU 共享内存，纹理还会再占一部分。
- 全部 6 项连续跑完约 7.5 分钟（4 × 60 + 56 + 113 秒，加上加载）。

### 3.4 画面检查

帧数高也可能是什么都没画。用 GFXBench 自带的截图功能（`screenshot_frames`，`gfxprobe` 里通过环境变量 `RMB_SCREENSHOT_FRAMES` 打开）在第 20 秒各截一帧：

- T-Rex：丛林、摩托车手、扬尘，纹理和光照正常。
- Egypt：神殿、雕像、火把、地面反射，纹理和光照正常。

两张图都是 1920×1080，像素统计正常（T-Rex 采样到 14591 种颜色，Egypt 4520 种），不是空白或纯色。说明 ETC1 纹理、网格、动画、着色器都正确加载和渲染。截图存在 `feasibility/out/x86/emu-results/`（不提交）。

### 3.5 其他确认的事

| 事项 | 结论 |
|---|---|
| 去掉 `common/` 里 7.4 MB 的画质比对参考图 | T-Rex、Egypt 照常跑完，可以去掉 |
| pbuffer 比 1920×1080 小（800×480）时 | ALU、T-Rex 照常跑，结果里的渲染分辨率仍是 1920×1080。真机建不了大 pbuffer 时可以用这个退路 |
| `ui.fs` 的精度补丁（[04](04-gfxbench-port.md) §4.7） | 已在准备数据时自动打上，模拟器上补丁前后 gl_blending 都能跑；是否必要要在不支持片元 highp 的 GPU 上确认 |
| gl_blending 强制 `texture_type=ETC1` | 不影响：它的纹理是代码生成的 |
| 实际数据量（ETC1、去掉参考图） | 约 173.5 MB（[06](06-data-packs.md) §1） |

## 4. 遇到的坑（记下来，以后不用再踩）

| 现象 | 原因 | 做法 |
|---|---|---|
| armeabi-v7a 的 .so 用 `readelf -A` 看不到 NEON 标记，以为没开 NEON | 链接时 `Tag_Advanced_SIMD_arch` 被丢掉；实际 NDK r23c 默认开 NEON，还会自动向量化 | 用 `scripts/check-neon.ps1` 反汇编计数 |
| APK 里多了 kotlin-stdlib | AGP 9 内置 Kotlin，默认给所有 App 加 | `kotlin.stdlib.default.dependency=false` |
| GFXBench 的数据目录操作卡住几十分钟 | `blob:none` 部分克隆里，`git ls-tree -l`、`git archive` 会逐个按需下载缺失对象 | 先批量 `git fetch` 缺的对象，再用临时索引导出（[05](05-build-and-toolchain.md) §7） |
| `adb pull` 偶尔失败，结果文件明明在设备上 | 模拟器老版本 adbd 偶发 | 运行脚本里自动重试 3 次 |
| Android 4.1 的 shell 没有 `grep`、`head`、`wc`，`rm` 不支持 `-rf` | toolbox 精简 | 结果拉回主机再处理 |
| `android screen capture` 在 API 16 上失败 | 新版 adb 截图协议不支持 | 用 `adb shell screencap -p` + `adb pull` |
| `adb shell` 不返回远端命令的退出码 | Android 4.x 的 adbd 不支持 | 命令末尾 `echo EXIT=$?` |

## 5. 还没验证的

| 事项 | 需要什么 | 对应里程碑 |
|---|---|---|
| ARM 版在真机上跑通 | 一台 Android 4.1–4.4 的 ARM 手机或平板 | M1/M2 |
| 成绩和历史数据对照（±10%） | 历史榜单里有的机型，如 Galaxy S III、Nexus 7（2012）、Galaxy Nexus | M2 |
| 无 NEON 版在 Tegra 2 上跑通 | Motorola Xoom、Galaxy Tab 10.1 等 | M5 |
| 不支持片元 highp 的 GPU 上 gl_blending 能跑 | Mali-400 或 Tegra 2/3 | M1 |
| 屏上测试（窗口表面） | App 里的 `SurfaceView` 宿主 | M4 |
| CPU / 内存 / 存储组件在 API 16 上编译运行 | — | M3 |

## 6. 在真机上复现

1. 手机打开 USB 调试，连上电脑，`adb devices` 能看到。
2. 编译（只需一次，三套都编好备用）：

   ```powershell
   scripts\feasibility-gfxbench-es2.ps1 -Abi armeabi-v7a
   scripts\feasibility-gfxbench-es2.ps1 -Abi armeabi-v7a -NoNeon
   scripts\feasibility-gfxbench-es2.ps1 -Abi x86
   ```

3. 跑：

   ```powershell
   scripts\feasibility-gfxbench-run.ps1                    # 全部 6 项，约 8 分钟
   scripts\feasibility-gfxbench-run.ps1 -LowLevelOnly      # 只跑 4 个底层测试，不推场景数据
   scripts\feasibility-gfxbench-run.ps1 -PlayTimeMs 5000   # 每项 5 秒，快速检查能不能跑
   ```

   - 第一次运行会从 GFXBench 仓库取场景数据（约 1700 个文件，一两分钟），导出到 `feasibility/out/gfxdata/`，之后复用。
   - 脚本读 `/proc/cpuinfo` 自动选 NEON / 无 NEON 版；`-Variant armeabi-v7a-noneon` 可以在有 NEON 的设备上强制跑无 NEON 版。
   - 数据推到 `/data/local/tmp/rmb`（约 175 MB），用 `adb push --sync`，重复运行只推有变化的文件。
   - 有多台设备时加 `-Serial <序列号>`。

4. 结果在 `feasibility/out/results/<机型>-<变体>-<时间>/`：每项的结果 JSON、`summary.csv`、`device.txt`（机型、系统、GPU）。
5. 某项失败时看 `adb logcat -s RMB-GFX:* _logcat_:* DEBUG:*`（`_logcat_` 是 GFXBench 自己的日志标签，`DEBUG` 是崩溃堆栈）。
6. 如果是 `ERROR: eglCreatePbufferSurface 1920x1080 failed`：手动用小 pbuffer 试（`gfxprobe <config> <data/> <rw/> 800 480`），见 §3.5。
