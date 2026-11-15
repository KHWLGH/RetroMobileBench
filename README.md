# RetroMobileBench

面向旧安卓手机和平板的离线一键跑分工具，覆盖 CPU、加密、图片与视频解码、内存、内部存储、Web 日常响应和 OpenGL ES 2.0 GPU。兼容目标为 Android 2.2 / API 8 的 ARMv7 设备，以及 API 9 起的 x86 设备；ARMv7 同时提供 NEON 与无 NEON 实现。

## 使用

安装完整 APK，点击“开始测试”，应用会自动校验和准备内置资源，按 `fixed-suite-v3` 固定顺序执行 15 个阶段。CPU、加密、图片、内存、文件存储、SQLite 与 Web 共 9 个阶段各测三轮，视频与 5 项 GPU 模式各测一次。结果展示原始测量值和有效中位数，不生成总分。

- 主界面固定竖屏；GPU 屏上、Web 和视频播放跳转独立横向全屏窗口，取消按钮浮在画面上，结束后返回竖屏主界面。Web 全屏协议为 `web-daily-v2`，与旧版小窗口成绩分开比较。
- GPU 屏上测试左上角悬浮显示每约 1 秒更新的“FPS（提交）”和电池温度；没有有效温度读数时显示“不可用”。提交速率不等于屏幕真实显示 FPS。
- 应用位于前台时持续保持亮屏，包括准备、测试间隙和结果查看；返回后台后恢复系统的自动熄屏行为。测试期间请保持前台，取消、切后台、手动锁屏或输出窗口丢失会停止测试并保留已完成的结果。
- 结束后可查看历史、设备信息和许可，或将 JSON 复制到剪贴板；运行中不开放导出。
- 电量低于 30% 且未充电、可用内存或内部空间不足时会说明原因。电池温度只作升温统计，不触发应用层冷却等待。
- 默认 APK 内置全部 GPU 和媒体资源，首次准备还需要解包与暂存空间，无须联网或导入资源包。
- 资源准备后和每项 GPU 测试前，会在计时外尽力整理本应用 Java 堆；JSON 保留 `memory_preparation` 前后内存值。不会清除数据或降低内存门槛，也不保证释放固定容量。

GPU 配置时长合计 6 分 38 秒，另有加载和准备开销。新增 Web 阶段四项各 20 单元、三轮；阶段时限 330 秒，任务完成 ms 越低越好，不是 FPS 或真实输入延迟。完整 v3 耗时以实测为准，首次准备和慢设备可能更久。

## 构建入口

开发环境使用 Windows、Android Studio JBR、SDK 37、CMake 3.18.1、NDK r23c 和 Git Bash；实际原生编译使用 `.tools/android-ndk-r10e` 中的 NDK r10e / GCC 4.9。先按 [固定依赖说明](third_party/README.md) 准备源码与两个 GPU 场景，再按 [开发与构建](docs/development.md) 准备工具链。

在项目根目录运行：

```powershell
$env:JAVA_HOME = 'C:/Program Files/Android/Android Studio/jbr'
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain
```

应用采用 Java Framework View、C/C++ 和系统 Instrumentation；目标 SDK 23，保留旧系统需要的 v1 签名。默认产物为完整离线包。

## 文档

| 文档 | 内容 |
|---|---|
| [测试与结果](docs/benchmarks.md) | 固定项目、参数、媒体矩阵、测量方法、结果状态与可比边界 |
| [Web 日常响应协议与实施顺序](docs/web-daily.md) | 固定负载、离线语料、时限、身份与虚拟设备优先验收 |
| [开发与构建](docs/development.md) | 架构、旧平台兼容、工具链、资源、安装和诊断 |
| [验证与已知限制](docs/validation.md) | 验证命令、已记录的检查结论、媒体失败及真机待验项 |
| [ARM 真机验证记录](docs/validation-devices.md) | 小米 2A / 三星 GT-I9260 的当前结论、修复依据、历史成绩与证据 |
| [许可与分发](docs/licenses.md) | 组件和素材署名、成绩命名、对应源码交付及 FFmpeg 库替换 |

2026-11-15，当前独立全屏 / Web v2 / GPU 悬浮显示构建已在小米 2A（Android 4.1.1 / API 16）完成真机验收：真实开始按钮的完整 15 阶段全部 completed，耗时 16 分 50.097 秒；九个三轮阶段均有有效中位数，五项 GPU 使用正式时长，视频 21/21 路径完成。另通过全屏/HUD、Web 故障矩阵、生命周期、严格媒体与宿主干扰专项。原始结果、构建身份和边界见[ARM 真机验证记录](docs/validation-devices.md)。

Web v1 此前已在 API 10、16、19、29 x86 模拟器通过四项三轮和故障回归；旧 API 16 模拟器完整 15 阶段为 14 completed、视频 partial。当前全屏/HUD 的模拟器专项与旧记录按匹配构建分列于[验证与已知限制](docs/validation.md)，不与当前真机混算。三星当前构建、API 8 ARM 与 Release 真机仍待验证；以下保留更早媒体修复构建的两台真机证据。

截至 2026-11-15，已有构建、静态检查、API 10 / API 16 x86 模拟器，以及[小米 2A 与三星 GT-I9260 的 ARM 真机验证记录](docs/validation-devices.md)。两台真机的严格媒体专项均已达到七段 21/21 路径 completed；三星 High/FHD 的四条系统/播放路径在修正 B 帧输入时间线后由 TI 首选完成，VP8 崩溃后的后备窗口恢复也已通过。

两台设备更早的完整流程均为五项正式 GPU 全部完成、视频 partial；小米当前构建已补齐上文 15 阶段全部完成，三星最终媒体修复后仍未重跑完整套件。各构建的成绩、专项回归、历史失败与证据分别保留。发布验收仍有 Release 真机、其他设备、硬件类型确认、运行内存峰值和全面温升等缺口。

应用直接分发 APK。每份发布 APK 必须同时提供匹配的对应源码 ZIP、构建与 LGPL 说明，详见 [许可与分发](docs/licenses.md)。Release 分发还需配置发布签名。
