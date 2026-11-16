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

# 模拟点击“开始测试”，运行完整 12 阶段生产流程
adb -s emulator-5554 shell am instrument -w -e production true com.retromobilebench.test/com.retromobilebench.BackendInstrumentation
```

常用专项测试参数（加在 `-w` 之后）：

| 参数配置 | 测试覆盖内容 |
|---|---|
| *无额外参数* | JNI 基础接口、CoreMark/SciMark 单核与多核、内存/Triad、存储、SQLite、结果汇总与中断恢复、GPU 基础回归 |
| `-e production true` | 触发主界面真实开始按钮，串行执行完整 12 阶段（含正式时长 GPU）、全屏切换与数据持久化 |
| `-e displayOnly true` | 竖屏主界面、5 秒 Egypt 全屏与 HUD、标清视频播放、Web 竖屏全屏三轮测试及横竖屏切换 |
| `-e webOnly true` | Web 竖屏信息流与大图解码 4 分项 × 20 单元 × 3 轮、竖屏视口记录、独立进程隔离与持久化 |
| `-e webOnly true -e webFaults true` | 在 `webOnly` 基础上增加卡片/列表/长文/大图校验失败、时钟异常、进程崩溃、JS 卡死、超时与取消恢复测试 |
| `-e lifecycleOnly true` | 首轮落盘后取消、GPU 切后台中断、屏幕常亮释放，以及 API 8–15 窗口返回/Home 键处理 |
| `-e interferenceOnly true` | 并发事件等待、取消竞态、迟到回调隔离与共享温度采样源清理 |
| `-e fixedOnly true` | 固定 12 阶段拓扑、SciMark 多核、会话恢复与 7 段视频三路径解码矩阵 |
| `-e mediaOnly true` | API 16+ 严格媒体测试，要求 7 段素材 × 3 条路径（共 21 项）全部通过，并核对 EOS、帧数与时间戳 |
| `-e mediaTimestamps true` | API 16+ 对比系统 `MediaExtractor` 与容器原始时间戳及样本哈希（仅预检，不执行解码） |
| `-e gpuOnly true -e fullGpu true` | GPU 资源包校验、4 个场景各 5 秒快测、60 秒 Fill 正式测试、取消/超时恢复及 EGL 配置检查 |
| `-e gpuSurfaceOnly true` | API 11+ 渲染已知色块并回读校验，验证实际 EGL 配置与通道位数 |
| `-e gpuCaptureOnly true -e gpuTest gl_egypt` | API 11+ 在 4 个固定动画时间点回读场景画面并保存为图像（同样支持 `gl_trex`），用于排查渲染异常 |

## 历史真机与兼容性修复参考

前期在小米 2A（Adreno 320）与三星 Galaxy Premier GT-I9260（PowerVR SGX544）等 Android 4.1（API 16）真机上完成了全套验证，并修复了旧平台 Binder 死亡通知竞态、TI OMX 解码器缓冲区与 B 帧时间戳、以及 SGX544 屏上不透明 EGL 配置选择（`RGB888, alpha = 0`）等关键兼容性问题。原始归档数据保存于 `docs/evidence/2026-11-15/`。
