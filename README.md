# RetroMobileBench

面向旧安卓手机和平板的离线一键跑分工具，覆盖 CPU、加密、图片与视频解码、内存、内部存储、Web 日常响应和 OpenGL ES 2.0 GPU。兼容目标为 Android 2.2 / API 8 的 ARMv7 设备，以及 API 9 起的 x86 设备；ARMv7 提供 NEON 与无 NEON 实现。

## 使用

安装完整 APK，点击“开始测试”，应用会校验和准备内置资源，按 `fixed-suite-v3` 固定顺序执行 15 个阶段。九个阶段各测三轮，视频与五项 GPU 各测一次；展示原始值和有效中位数，不生成总分。

- 主界面固定竖屏；GPU 屏上、Web 和视频播放使用独立横向全屏窗口，结束后返回主界面。
- GPU 屏上悬浮显示“FPS（提交）”和电池温度，约每秒更新。提交速率不等于屏幕真实显示 FPS，电池温度也不是芯片结温。
- 前台保持亮屏；取消、切后台、锁屏或输出窗口丢失会停止测试，保留已完成结果。结束后可查看历史、设备信息、许可，并复制 JSON。
- 资源全部内置，运行无须联网。首次准备需要解包和暂存空间；电量低于 30% 且未充电、可用内存或内部空间不足时会提示原因。

GPU 配置时长合计 6 分 38 秒，另有加载和准备开销。完整套件耗时取决于设备与缓存；已有资源缓存的两台 API 16 真机曾分别用时约 17 和 20 分钟，具体构建与验证边界见[真机验证](docs/validation.md#真机验证)。

## 构建

按[开发与构建](docs/development.md)准备固定依赖、GPU 数据和工具链。实际原生编译使用 `.tools/android-ndk-r10e` 中的 GCC 4.9。

在项目根目录运行：

```powershell
$env:JAVA_HOME = 'C:/Program Files/Android/Android Studio/jbr'
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain
```

应用采用 Java Framework View、C/C++ 和系统 Instrumentation，targetSdk 23，保留旧系统需要的 v1 签名。默认产物为完整离线包。公开分发 APK 时须同时提供匹配的对应源码、许可及构建说明；Release 需配置发布签名。

## 文档

| 文档 | 内容 |
|---|---|
| [测试协议与结果](docs/benchmarks.md) | 固定负载、Web / 视频协议、计时、正确性与结果解释 |
| [开发与构建](docs/development.md) | 架构、固定依赖、平台兼容、构建安装、资源与清理 |
| [验证与限制](docs/validation.md) | 检查入口、模拟器 / 真机结果、SGX544 调查、原始证据及待验项 |
| [许可与分发](docs/licenses.md) | 依赖与素材署名、成绩命名、对应源码和 FFmpeg 库替换 |

截至 2026-11-15，两台真机已在各自记录的 Debug 构建完成完整 15 阶段。最新 EGL 修复构建在三星通过正式时长 Egypt / T-Rex 及全屏、生命周期专项，观测截图无原彩色噪声；尚未在该构建重跑整套或小米，也未完成 Release 真机及 API 8 ARM 验收。原始结果和关键对照图见[验证文档的证据索引](docs/validation.md#原始证据)。
