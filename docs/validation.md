# 验证与限制

本文档记录项目的静态检查方法、自动化测试入口、模拟器与真机验证结果，以及早期 Android 平台的兼容性修复细节。测试阶段定义见[测试与结果](benchmarks.md)，构建与安装说明见[开发与构建](development.md)。

## 构建与静态检查

在项目根目录执行以下命令，可编译所有构建变体并检查原生库的旧平台符号兼容性与指令集约束：

```powershell
$env:JAVA_HOME = 'C:/Program Files/Android/Android Studio/jbr'
.\gradlew.bat :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :app:lintDebug :app:lintRelease --console=plain
.\scripts\check-legacy-native.ps1
.\scripts\check-neon.ps1 -Path app/build/generated/legacyJniLibs/armeabi-v7a/librmb_noneon.so -ExpectNeon $false
```

- `check-legacy-native.ps1`：检查所有生成的 `.so` 文件是否仅导入 API 8（ARM）/ API 9（x86）允许的系统符号，是否包含旧链接器所需的 SysV hash 表，并排查未声明的动态依赖、RPATH 与 TEXTREL。
- `check-neon.ps1`：反汇编检查 `librmb_noneon.so`、`librmbmedia.so` 及 FFmpeg 库是否确实未混入 NEON 指令（可对 `librmb.so` 传入 `-ExpectNeon $true` 作为对照）。

## 设备自动化测试入口

安装 Debug APK 与 AndroidTest APK 后，保持设备屏幕解锁且应用位于前台，可通过 `adb shell am instrument` 运行全套或专项验证（多设备时请将 `emulator-5554` 替换为目标设备序列号）：

```powershell
# 运行默认后端回归测试
adb -s emulator-5554 shell am instrument -w com.retromobilebench.test/com.retromobilebench.BackendInstrumentation

# 模拟点击“开始测试”，运行完整 15 阶段生产流程
adb -s emulator-5554 shell am instrument -w -e production true com.retromobilebench.test/com.retromobilebench.BackendInstrumentation
```

常用专项测试参数（加在 `-w` 之后）：

| 参数配置 | 测试覆盖内容 |
|---|---|
| *无额外参数* | JNI 基础接口、CPU/内存/存储、加密与图片校验、结果汇总与中断恢复、GPU 基础回归 |
| `-e production true` | 触发主界面真实开始按钮，串行执行完整 15 阶段（含正式时长 GPU）、全屏切换与数据持久化 |
| `-e displayOnly true` | 竖屏主界面、5 秒 Egypt 全屏与 HUD、标清视频播放、Web 三轮测试及横竖屏切换 |
| `-e webOnly true` | Web 日常响应 4 分项 × 20 单元 × 3 轮、视口记录、独立进程隔离与持久化 |
| `-e webOnly true -e webFaults true` | 在 `webOnly` 基础上增加校验失败、时钟异常、进程崩溃、JS 卡死、超时与取消恢复测试 |
| `-e lifecycleOnly true` | 首轮落盘后取消、GPU 切后台中断、屏幕常亮释放，以及 API 8–15 窗口返回/Home 键处理 |
| `-e interferenceOnly true` | 并发事件等待、取消竞态、迟到回调隔离与共享温度采样源清理 |
| `-e fixedOnly true` | 固定负载参数、加密测试向量与 8 线程并发、6 张图片参考校验、7 段视频解码矩阵 |
| `-e mediaOnly true` | API 16+ 严格媒体测试，要求 7 段素材 × 3 条路径（共 21 项）全部通过，并核对 EOS、帧数与时间戳 |
| `-e mediaTimestamps true` | API 16+ 对比系统 `MediaExtractor` 与容器原始时间戳及样本哈希（仅预检，不执行解码） |
| `-e gpuOnly true -e fullGpu true` | GPU 资源包校验、4 个场景各 5 秒快测、60 秒 Fill 正式测试、取消/超时恢复及 EGL 配置检查 |
| `-e gpuSurfaceOnly true` | API 11+ 渲染已知色块并回读校验，验证实际 EGL 配置与通道位数（持续约 20 秒便于观察） |
| `-e gpuCaptureOnly true -e gpuTest gl_egypt` | API 11+ 在 4 个固定动画时间点回读场景画面并保存为图像（同样支持 `gl_trex`），用于排查渲染异常 |

> 调试单个 GPU 场景时，可传入 `-e gpuTest <场景名>` 与 `-e gpuDurationMs <毫秒数>`（设为 `0` 时使用正式测试时长）。此外，`tests/native/crypto_join_check.c` 提供了针对原生加密多线程 `join` 顺序的独立检查。

## 模拟器与真机验证进展

### 模拟器覆盖情况

- **静态与构建检查**：Debug / Release / AndroidTest 构建及 Lint 检查通过；全部 11 个共享库通过 API 8/9 符号导入与指令集检查。
- **API 10 / 16 / 29（x86 模拟器）**：完成全屏窗口切换、GPU 屏上 HUD、Web 响应三轮测试与故障注入、生命周期中断、Binder 回收及固定套件专项测试。在 API 29 x86 模拟器上通过了 113 秒正式时长的 Egypt 屏上测试。

### 真机测试记录

已在两台搭载 Android 4.1（API 16）的双核 ARMv7 NEON 真机上完成了完整 15 阶段测试及多项专项验证：

| 设备型号 | 系统版本 / GPU | 屏幕与 Web 视口尺寸 | 测试期间供电与电池温度变化 | 完整 15 阶段耗时 |
|---|---|---|---|---|
| 小米 2A（`c0e4aa9d`） | Android 4.1.1（MIUI 4.12.5）/ Adreno 320 | 物理 1280×720 / CSS 640×360 | 交流充电；25.4°C → 25.5°C（峰值 25.6°C） | 16 分 50.1 秒（`1010097 ms`） |
| 三星 Galaxy Premier GT-I9260（`cff030496503230`） | Android 4.1.2（JZO54K）/ PowerVR SGX544 | 物理 1280×720 / CSS 640×360 | USB 供电；29.8°C → 35.8°C（峰值 35.8°C） | 20 分 29.4 秒（`1229389 ms`） |

两台设备均通过主界面“开始测试”按钮完成了全部 15 个阶段（包含 9 个三轮阶段、21/21 媒体测试路径以及 5 项正式时长 GPU 测试），数据库完整性校验（`integrity_check`）以及全屏、Web 故障、生命周期、严格媒体和宿主干扰等专项测试均通过。

## 典型兼容性问题与修复

### 旧平台 Binder 与媒体解码修复

1. **Binder 死亡通知竞态崩溃**：早期在三星 GT-I9260（Android 4.1.2）上运行完整测试时，曾出现一次性子进程退出后宿主因 `DeathRecipientList::find` 访问已释放 JNI 全局引用而崩溃的问题（参见 [AOSP Binder JNI 实现](https://android.googlesource.com/platform/frameworks/base/+/android-4.1.2_r1/core/jni/android_util_Binder.cpp)）。修复方案为移除子进程正常结束后的冗余 `unlinkToDeath` 调用，保留死亡通知、硬超时与异步解绑保护，从而消除了 JNI 引用竞态。
2. **TI OMX 解码器缓冲区与 B 帧时间戳**：在搭载德州仪器 OMAP4470 的三星 GT-I9260 上，系统 `OMX.TI.*` 解码器需要设置 `max-input-size = 0` 以保留驱动默认缓冲区容量（参见 [AOSP ACodec](https://android.googlesource.com/platform/frameworks/av/+/android-4.1.2_r1/media/libstagefright/ACodec.cpp)）。同时，系统 `MediaExtractor` 在读取含 B 帧的 H.264 High Profile 素材时会将 DTS 归零作为 PTS 返回。应用通过在计时前对比内置 FFmpeg 解封装器的样本哈希与容器 PTS，在确认样本一致的前提下向解码器提交正确的容器 PTS，使 High/FHD 硬解与播放路径顺利通过。
3. **解码器回退与窗口重建**：针对部分三星设备上 `OMX.SEC.vp8.dec` 偶发崩溃或校验失败的情况，支持自动回退至系统自带的 Google VPX 解码器完成测试；当子进程异常退出时，等待旧 `Surface` 彻底销毁后再重建后备渲染窗口。

### SGX544 屏上花屏修复

在三星 GT-I9260（驱动版本 `OpenGL ES 2.0 build 1.9.RC2@2139099`）的首次完整测试中，Egypt 屏上渲染的水面、天空以及半透明 UI 叠加区域曾出现明显的彩色噪点。

- **原因排查**：通过 `gpuSurfaceOnly` 与 `gpuCaptureOnly` 诊断发现，OpenGL 帧缓冲区的 RGB 像素回读本身正常，问题出在窗口合成阶段。旧版 EGL 配置选择器仅按最低下限要求取 `eglChooseConfig` 返回的第一项（`config 17`：`RGB888` + `alpha 8`，`native visual 5`），而 `SurfaceHolder` 默认为不透明窗口，带 Alpha 通道的 EGL 表面与驱动合成器配合时产生了彩色噪点。
- **修复方案**：屏上模式改为遍历候选配置，精确匹配 `RGB 8/8/8`、`alpha = 0`、`samples = 0`、`depth >= 16` 的不透明窗口配置（在该设备上选中 `config 20`，`alpha = 0`）。修复后，三星 GT-I9260 跑完 113 秒正式 Egypt 屏上（1453 帧）与 56 秒正式 T-Rex 屏上（312 帧）测试，系统截图中画面、HUD 与取消按钮均显示正常，无彩色噪点。

### 原始测试数据与截图归档

`docs/evidence/2026-11-15/` 目录保存了真机验证的原始导出 JSON 与屏幕截图，供核对参考：

| 归档文件 | 说明 |
|---|---|
| [`mi2a-production-v3-hud.json`](evidence/2026-11-15/mi2a-production-v3-hud.json) | 小米 2A 完整 15 阶段测试报告 |
| [`i9260-production-v3-hud-binderfix.json`](evidence/2026-11-15/i9260-production-v3-hud-binderfix.json) | 三星 GT-I9260 完整 15 阶段测试报告（EGL 花屏修复前） |
| [`i9260-egypt-fixed.json`](evidence/2026-11-15/i9260-egypt-fixed.json) | 三星 GT-I9260 在 EGL 修复后的 Egypt 屏上 113 秒正式报告 |
| [`i9260-trex-final.json`](evidence/2026-11-15/i9260-trex-final.json) | 三星 GT-I9260 在 EGL 修复后的 T-Rex 屏上 56 秒正式报告 |
| [`i9260-surface-final.json`](evidence/2026-11-15/i9260-surface-final.json) | 三星 GT-I9260 的 EGL 配置与色块回读诊断报告 |
| [`i9260-egypt-before-overlayfix.png`](evidence/2026-11-15/i9260-egypt-before-overlayfix.png) | 修复前三星 GT-I9260 的 Egypt 屏上花屏现象截图 |
| [`i9260-egypt-fixed-mid.png`](evidence/2026-11-15/i9260-egypt-fixed-mid.png) | 修复后三星 GT-I9260 的 Egypt 屏上正常渲染截图 |
| [`i9260-trex-retry.png`](evidence/2026-11-15/i9260-trex-retry.png) | 修复后三星 GT-I9260 的 T-Rex 屏上正常渲染截图 |

## 已知限制与待测事项

项目目前仍处于早期开发与持续完善阶段，以下场景有待后续进一步测试与覆盖：

- **更多旧硬件与系统版本**：API 8–15 的 ARM 真机、无 NEON 的 ARMv7 设备（如 Tegra 2）、原生 x86 移动设备、八核设备的并发调度表现，以及 API 29+ 设备的系统解码器属性查询分支。
- **完整回归与发布验证**：在最新 EGL 修复构建上重新跑完两台真机的完整 15 阶段测试、测试全新安装首次解包资源时的存储与内存峰值，以及完成 Release 签名包的真机验证。
- **模拟器 GPU 限制**：在 API 16 与 API 29 x86 模拟器上运行完整 T-Rex 离屏测试时，宿主模拟器进程偶发崩溃退出（即使显式指定 SwiftShader 仍复现），因此重度 GPU 场景的稳定性与性能验证以物理真机为准。
