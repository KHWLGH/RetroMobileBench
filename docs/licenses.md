# 许可与分发

RetroMobileBench 直接分发 APK。组件固定版本、提交、归档哈希和本地适配以[开发文档的固定依赖](development.md#固定依赖)为准，原始许可保留在对应源码中。本页同时纳入 App 的开源许可通知，说明实际依赖、素材署名与媒体库交付方式。

## 组件和素材

| 组件 | 许可 | 使用及保留要求 |
|---|---|---|
| CoreMark 1.0 | Apache-2.0 + EEMBC 商标约定 | 保留原始许可与商标声明，仅修改 Android port，遵守正式运行规则 |
| SciMark 2.0 C | NIST 公有领域 | 注明来源；内存不足处理有本地修改，计算内核保持不变 |
| zlib 1.3.1 | zlib | 保留声明，不冒充原作者；CPU 版本通过符号前缀隔离 |
| Zstd 1.5.7 | BSD-3-Clause（双许可中选用） | 保留版权及许可；当前仅诊断和旧记录用途，相关代码仍参与构建 |
| tinymembench C 内核 | MIT | 保留版权及许可；仅使用 C 复制/填充内核，延迟由本项目实现 |
| STREAM 变体 | STREAM 自有许可 | 保留原始许可与结果发布规则，明确注明变体 |
| SQLite 3.53.1 | 公有领域 | 固定版本事务测试，与历史库分开 |
| Mbed TLS 2.28.10 | Apache-2.0 | 保留许可；仅 AES/GCM/SHA 最小配置，不运行 TLS |
| libjpeg-turbo 2.1.5.1 | IJG / BSD / zlib | 保留实际组件声明和 IJG 署名 |
| libpng 1.6.43 | libpng-2.0 | 保留版权与许可 |
| FFmpeg 4.4.6 | LGPL-2.1-or-later，禁用 GPL/nonfree | 独立共享库、匹配对应源码、构建和替换材料 |
| GFXBench 代码 | BSD-3-Clause，© 2005–2025 Kishonti Ltd | 保留版权和许可，不暗示官方认证或背书 |
| GFXBench 素材 | CC BY 4.0 | 署名、来源、许可链接与修改说明 |
| GFXBench 内含依赖 | 各自许可 | 保留实际编入的 zlib、libpng、TinyXML/TinyXML2、libetc1、Xiph Ogg/Theora 声明 |
| GNU 静态 C++ 运行时 | GPLv3 + GCC Runtime Library Exception | 保留工具链原始 COPYING3 / COPYING.RUNTIME，按运行时例外使用 |
| Web 日常响应 HTML/JS 与合成图/JSON/文本 | 本项目原创合成内容 | 不包含第三方网页或框架，不冒充官方浏览器基准 |
| Sintel 衍生视频和 JPEG | CC BY 3.0，Blender Foundation | 保留来源、许可和裁剪/缩放/重编码/去音轨说明 |

Poco、libepoxy、IJG 的 GFXBench 独立目标和 pytorch/cpuinfo 未编入当前目标，不将候选库列为实际依赖。JPEG 仍使用 libjpeg-turbo，保留其 IJG 相关署名：

> This software is based in part on the work of the Independent JPEG Group.

素材署名：

- GFXBench 场景来自 [Kishonti 开源仓库](https://github.com/Kishonti-Opensource/gfxbench)，© 2005–2025 Kishonti Ltd，以 [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/) 发布。本应用只保留 ETC1 子集，删除画质参考图，为 `ui.fs` 增加片元精度回退，并使用本项目的 ES2 宿主。
- 视频与 JPEG 照片源自 Sintel trailer，© Blender Foundation，以 [CC BY 3.0](https://creativecommons.org/licenses/by/3.0/) 发布，[来源与分享说明](https://durian.blender.org/sharing/)。视频经截短、缩放、重编码并去除音轨，不是原预告片文件；PNG 透明测试图由 FFmpeg testsrc2 生成。

完整素材参数、源与输出 SHA-256 见 [固定素材清单](../app/src/main/assets/media/manifest.json)。素材保持版本控制；重新生成会改变语料，必须审查哈希并升级协议。

App 的“许可”入口合并显示 `licenses.txt`、Gradle 生成的 `benchmark-notices.txt` 和 `xiph-notices.txt`。生成通知包含实际依赖的原始许可、本说明、工具链运行时许可和媒体清单；GPU 包清单另含 CC BY 4.0 与修改说明。升级依赖时同步更新这些来源，不只修改本页表格。

## 成绩命名

- CoreMark 只有在核心源码未改、两套种子校验通过、每个正式阶段至少 10 秒且编译参数符合规则时才报告正式成绩，并保留编译器/参数。`CoreMark® is a registered trademark of EEMBC.`
- STREAM 结果注明 `Based on a variant of the STREAM benchmark code.`，固定工作集、pthread 和独立计时协议不作为标准 STREAM 或纯 DRAM 峰值。
- SciMark 展示本项目计时的各内核 MFLOPS，注明 NIST 来源，不冒充原版综合成绩。
- GPU 结果可称“基于 GFXBench 开源场景的移植结果”，不能称 Kishonti 官方分数或暗示背书，也不承诺与历史 GLBenchmark / GFXBench 结果等价。

## FFmpeg 构建与对应源码

软件解码使用固定子模块 FFmpeg 4.4.6。`scripts/build-ffmpeg.sh` 仅开启 H.264、H.263、MPEG-4 Part 2、VP8 解码器及必要 parser / demuxer / file 协议，禁用编码器、网络、过滤器、GPL 和 nonfree。avutil、avcodec、avformat 保持独立共享库，不静态合入应用 JNI，并为旧 Android 链接器显式按依赖顺序加载。

在项目根目录的 Git Bash 中执行 `bash scripts/build-ffmpeg.sh`，或通过 Gradle `buildFfmpeg` 构建。需要 `.tools/android-ndk-r10e`、Git Bash 和 Android SDK；GCC 4.9 目标为 API 8 ARMv7 / VFPv3-D16 和 API 9 x86，禁用汇编，包括 NEON。固定配置记录在 `app/build/ffmpeg/<abi>/ffbuild/config.mak`。主工具链与操作见 [开发与构建](development.md)，静态兼容检查及真机要求见 [验证与已知限制](validation.md)。

每份公开 APK 必须同时提供 **与该 APK 匹配** 的对应源码 ZIP，不能仅给上游下载地址。生成命令：

```powershell
.\gradlew.bat :app:correspondingSource --console=plain
```

输出为 `app/build/distributions/RetroMobileBench-corresponding-source-fixed-suite-v3.zip`，包含实际 FFmpeg 源码、构建脚本、JNI 适配、项目构建文件、依赖源码与声明。保留依赖清单中的提交身份，核对归档完整性、当前文档及许可材料，再与对应 APK 一起分发。修改 APK 或构建输入后重新生成和核对，不复用不匹配的旧源码包。

动态链接本身不能代替 LGPL 交付义务。原始 [LGPL-2.1 文本](../third_party/ffmpeg/COPYING.LGPLv2.1) 和 [FFmpeg 法律说明](https://ffmpeg.org/legal.html) 随源码保留。Mbed TLS 仅执行固定加密负载；图片库仅解码内置素材，它们不是设备安全服务。

## FFmpeg 库替换

1. 使用相同公开 ABI 和 SONAME 重建 API 兼容的 `libavutil.so`、`libavcodec.so`、`libavformat.so`，分别满足对应 ARMv7 / x86 与最低 API 要求。
2. 解包 APK，将相应 `lib/<abi>/` 下的库替换，使用普通 Android 打包工具重新组装，以自己的兼容 v1 的密钥签名后安装。
3. 如果已有安装使用不同签名，需要先移除旧安装或改用不同 application ID；需要保留的结果应先导出。
4. 检查动态依赖、旧 API 加载及实际解码，分享修改后的结果时注明库与构建身份，将其作为独立性能比较组。

应用不对这些库实施完整性校验，也不禁止为调试 LGPL 库修改而进行替换或逆向工程。语料哈希只检查测试输入文件，不阻止库替换。

## 发布交付

发布包使用自己的签名并保留 v1，同时提供匹配的源码 ZIP、构建与替换说明、完整许可和素材署名。当前 Release 未配置发布密钥，构建成功不等于完成发布。

API 8/9 真机加载、厂商硬解、真实显示帧、八核调度、峰值内存和温升仍需物理设备验证，当前证据详见 [验证与已知限制](validation.md)。不得将能力声明、模拟器流程或构建成功写成这些性质已验证。
