# 许可与分发

RetroMobileBench 的原创代码与原创内容采用 **Apache-2.0** 许可证发布（见根目录 [LICENSE](../../LICENSE)）。`third_party/` 下的第三方组件以及 CC BY 3.0 / CC BY 4.0 媒体与 3D 场景素材仍适用其各自的原始许可与署名要求。

RetroMobileBench 以 APK 形式直接分发。第三方组件的锁定版本与源码来源见[开发与构建 - 第三方依赖](development.md#第三方依赖共-7-项)，各组件的原始许可证文本随源码保留，并在应用内的“开源许可协议全文”页面集中展示。

## 第三方组件与素材许可

| 组件 / 素材 | 许可证 | 使用方式与署名说明 |
|---|---|---|
| CoreMark 1.0 | Apache-2.0 + EEMBC 商标约定 | 保留原始许可与商标声明，仅定制 Android 移植层，遵循标准计时与双种子校验规则 |
| SciMark 2.0 C | NIST 公有领域 | 注明 NIST 来源；本地补充了内存分配失败检查与多线程安全初始化，核心计算逻辑不变 |
| tinymembench C 内核 | MIT License | 保留版权与许可声明；仅使用 C 语言内存复制与填充内核 |
| STREAM Triad 变体 | STREAM License | 保留原始许可声明，并在报告中注明为共享固定 24 MiB 工作集的 STREAM 变体实现 |
| SQLite 3.53.1 | 公有领域 | 作为独立事务测试负载编入原生库，与应用自身的数据库分离 |
| FFmpeg 4.4.6 | LGPL-2.1-or-later（禁用 GPL/nonfree） | 编译为独立共享库，公开分发时提供匹配的对应源码与库替换说明 |
| GFXBench 引擎代码 | BSD-3-Clause（© 2005–2025 Kishonti Ltd） | 保留版权与许可声明 |
| GFXBench 场景素材 | CC BY 4.0 | 保留来源署名、许可链接及本地裁剪修改说明 |
| GFXBench 内置依赖 | 各自开源许可 | 保留实际参与编译的 zlib、libpng、TinyXML/TinyXML2、libetc1、Xiph Ogg/Theora 声明 |
| GNU 静态 C++ 运行时 | GPLv3 + GCC Runtime Library Exception | 保留 NDK 工具链中的 `COPYING3` 与 `COPYING.RUNTIME`，适用运行时例外条款 |
| Web 测试页面与语料 | 本项目原创合成内容 + Sintel 衍生图片 | 项目自研竖屏信息流 HTML/JS 与 480p/720p/1080p 测试图，不引入外部第三方网页框架 |
| Sintel 衍生视频与图片 | CC BY 3.0（© Blender Foundation） | 保留来源署名、CC BY 3.0 许可链接及裁剪重编码说明 |

### 署名与商标声明

- **CoreMark**：
  > CoreMark® is a registered trademark of EEMBC.
- **STREAM**：测试结果中注明 `Based on a variant of the STREAM benchmark code.`
- **GFXBench 场景素材**：源自 [Kishonti 开源仓库](https://github.com/Kishonti-Opensource/gfxbench)（© 2005–2025 Kishonti Ltd，采用 [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/) 许可发布）。本项目仅打包 ETC1 纹理子集，移除了画质参考图，并为 `ui.fs` 添加了片元精度回退适配。测试结果在应用中标注为基于 GFXBench 开源场景的移植成绩。
- **Sintel 媒体素材**：视频与 JPEG 测试图像源自 Blender Foundation 的 *Sintel* 预告片（采用 [CC BY 3.0](https://creativecommons.org/licenses/by/3.0/) 许可发布，详见[官方分享说明](https://durian.blender.org/sharing/)）。测试素材经过时长截取、缩放、重编码并移除了音轨；PNG 透明测试图则由 FFmpeg `testsrc2` 滤镜生成。

构建时，Gradle 的 `generateBenchmarkNotices` 任务会自动汇总各组件的原始许可证文本、工具链运行时许可、媒体清单及本文档，并与 `licenses.txt`、`licenses_en.txt`、`xiph-notices.txt` 一并在应用的“开源许可协议全文”页面中展示。

## FFmpeg 构建与 LGPL 源码交付

软件视频解码使用 FFmpeg 4.4.6（子模块 `third_party/ffmpeg`）。构建脚本 `scripts/build-ffmpeg.sh` 仅启用 H.264、H.263、MPEG-4 Part 2、VP8、VP9 解码器及必需的解析器、解封装器和本地文件协议，显式禁用编码器、网络协议、滤镜以及 `--enable-gpl` / `--enable-nonfree` 选项。编译产物 `libavutil.so`、`libavcodec.so`、`libavformat.so` 作为独立的动态共享库打包。

### 生成对应源码包

根据 LGPL-2.1 许可证要求，公开分发 APK 时应一并提供与该 APK 完全匹配的对应源码归档（包含 FFmpeg 源码、构建脚本、JNI 适配层及项目构建配置）。可通过以下 Gradle 任务直接生成：

```powershell
.\gradlew.bat :app:correspondingSource --console=plain
```

归档文件将输出至 `app/build/distributions/RetroMobileBench-corresponding-source-fixed-suite-v1.zip`。完整的 [LGPL-2.1 文本](../../third_party/ffmpeg/COPYING.LGPLv2.1) 随源码树一并保留。
