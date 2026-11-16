# 更新日志 / Changelog

本文件记录 **RetroMobileBench** 的重要版本变更，格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 并遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [0.1.0] - 2026-11-17

首个公开发布版本（测试协议 `fixed-suite-v1`）。

### 新增（Added）
- **12 阶段离线固定基准测试套件（`fixed-suite-v1`）**：
  - CPU 整数（CoreMark® 单核 / 多核）与 CPU 浮点（SciMark 2.0 五内核单核 / 多核）；
  - 内存带宽与延迟（tinymembench 8 MiB 复制/填充、4 MiB 随机指针链延迟、STREAM 24 MiB 共享 Triad）；
  - 内部存储 I/O（4–32 MiB 自适应私有文件，顺序 256 KiB + 随机 4 KiB，`O_SYNC` / `O_DIRECT`）与 SQLite 3.53.1 事务（INSERT / UPDATE / DELETE 各 300 次独立提交）；
  - `:web` 独立竖屏 WebView 信息流排版与 20 次高清大图解码；
  - 视频 7 段语料三路径（FFmpeg 软解 / MediaCodec 系统解码 / 实时播放）基准；
  - OpenGL ES 2.0 场景（Egypt HD 离屏/屏上、T-Rex 离屏/屏上、Texture Fill Rate）与实时左上角 GPU HUD（FPS + 电池温度）。
- **旧平台广泛兼容与双原生库分发**：
  - 支持 Android 2.2（API 8）起 ARMv7 设备（运行时探测并按需加载 `librmb.so` NEON 或 `librmb_noneon.so` VFPv3-D16）及 Android 2.3（API 9）起 x86 设备；
  - 无 AndroidX / Compose / Kotlin / Play 服务依赖，单 DEX（`035`）、`targetSdk 23`、JAR/v1 签名，原生代码统一以 NDK r10e GCC 4.9 编译。
- **MIUI V5 风格界面与离线硬件探测**：
  - 跑分、成绩、设备、更多四主标签与阶段详情、硬件探针、测试套件说明等二级页面；
  - 内置离线 SoC 与 CPU 微架构识别数据库，结合系统属性与原生硬件探针展示完整硬件信息；
  - 后台 2 秒间隔采样电池温度、电量与电压，绘制整套测试及分阶段温升/电量双曲线，支持每轮 SQLite 检查点断点保护。
- **中英文双语本地化与语言跟随报告**：
  - 支持「跟随系统 / 简体中文 / English」实时切换；
  - 应用界面、测试报告详情、核心指标速览以及 `.csv` / `.txt` 导出文件均随语言动态输出，JSON（`retromobilebench-full-v1`）保持结构键名稳定。
- **完整数据导出与分享**：
  - 支持导出 `.json`、UTF-8 BOM `.csv` 与 `.txt` 至公共 `Download/RetroMobileBench/` 目录、系统分享或 Android 4.4+ SAF。
- **开源合规与双语文档体系**：
  - 变更应用包名为 `io.github.khwlgh.retromobilebench`，确定 Apache-2.0 原创代码许可（[`LICENSE`](LICENSE)）；
  - 完善内置 7 项第三方组件说明、完整开源许可正文与 CC BY 素材署名，提供 `:app:correspondingSource` FFmpeg LGPL 对应源码打包任务；
  - 整理中英文双语 `README.md` / `README.en.md`、`docs/zh/` / `docs/en/` 文档及基于 GT-I9260 实测数据展示的主要界面中英文截图（`docs/screenshots/zh/` 与 `docs/screenshots/en/`）。
