# 08 许可证、署名与命名

> 2026-11-14 更新。固定版本与本地适配见 [third_party](../third_party/README.md)，媒体对应源码/替换说明见 [media-distribution](media-distribution.md)。

## 1. 组件许可证

| 组件 | 许可证 | 我们的义务 |
|---|---|---|
| GFXBench 代码 | BSD-3-Clause，© 2005–2025 Kishonti Ltd | 保留版权声明和许可证全文；不能用 Kishonti 的名字为我们的产品背书 |
| GFXBench 素材（纹理、模型、动画） | CC BY 4.0 | 署名、附许可证链接、**注明已修改**（只保留 ETC1 纹理、删除部分文件） |
| GFXBench 第三方组件 | 见 GFXBench README 的 Third-Party Licenses（zlib、libpng、Poco BSL-1.0、tinyxml zlib、IJG 等） | 按各自许可证附声明；只附我们实际编进去的 |
| GFXBench 历史成绩（如内置） | 非代码内容 CC BY 4.0 | 注明来自 Kishonti 公开榜单 |
| CoreMark | Apache-2.0 + EEMBC 商标协议 | 附 Apache-2.0 全文；遵守运行规则；显示商标声明（见 §2） |
| SciMark 2 | 公有领域（NIST，不受版权保护） | 无强制义务；NIST 希望注明出处 |
| zstd | BSD-3-Clause（与 GPL-2.0 双许可，选 BSD） | 附版权声明 |
| zlib | zlib 许可证 | 不得声称是自己写的；修改过的版本要标明 |
| tinymembench | MIT | 附版权声明 |
| STREAM（改版） | STREAM 自有许可 | 改版成绩要标注（见 §2） |
| SQLite | 公有领域 | 无 |
| Mbed TLS 2.28.10 | Apache-2.0 | 附版权/许可，最小加密配置，不运行TLS |
| libjpeg-turbo 2.1.5.1 | IJG/BSD/zlib | 保留实际组件声明及IJG署名 |
| libpng 1.6.43 | libpng-2.0 | 保留版权/许可 |
| FFmpeg 4.4.6（禁GPL/nonfree） | LGPL-2.1-or-later | 独立共享库；提供匹配对应源码、构建/修改材料与替换说明 |
| Sintel衍生短片/照片 | CC BY 3.0 | Blender Foundation署名、来源/许可链接，说明裁剪缩放重编码去音轨 |
| pytorch/cpuinfo | BSD-2-Clause | 附版权声明 |
| Poco 1.5.3 Foundation | Boost Software License 1.0 | 二进制发布不强制附声明，建议附上 |
| libpng | libpng 许可证 | 源码里保留版权声明；二进制发布不强制，建议附上 |
| libIJG（GFXBench KCL 自带的 JPEG 库） | IJG 许可证 | 编进去的话，只发二进制时文档里必须写明"this software is based in part on the work of the Independent JPEG Group" |
| libogg / libtheora / libvorbis（KCL 自带，`glb_ogg_decoder.cpp` 用到） | BSD 风格 | 附版权声明 |

FFmpeg引入LGPL分发要求，不能继续把全部依赖称为宽松许可，也不能仅凭动态链接认为义务已履行。每份发布APK旁同时提供`:app:correspondingSource`生成的匹配源码ZIP；保留构建与库替换说明。

当前正式套件使用 **zlib 1.3.1、SQLite 3.53.1、CoreMark、SciMark C 2.0、tinymembench C 内核、STREAM Triad变体、GFXBench ES2、MbedTLS、libjpeg-turbo、libpng和FFmpeg**；Zstd/完整STREAM四内核仅保留必要诊断和旧记录解释。固定提交/归档 SHA-256 和本地修改见 [third_party/README.md](../third_party/README.md)。pytorch/cpuinfo 尚未引入；Poco、IJG 未编入当前 GPU 目标。

APK 中 `licenses.txt` 提供版本、项目来源和署名；Gradle 从实际依赖生成 `benchmark-notices.txt`，收录 CoreMark Apache/商标协议、STREAM、tinymembench、GFXBench、libpng、GPU zlib、tinyxml/tinyxml2、libetc1 原始许可；`xiph-notices.txt` 提供 libogg/libtheora BSD 声明。界面“开源许可”合并显示三份文件。GFXBench 数据包清单同时附 CC BY 4.0 链接、版权和 ETC1 子集/删图/精度回退修改说明。

## 2. 成绩命名规则

| 测试 | 规则 |
|---|---|
| GFXBench | 不要把成绩叫"GFXBench 分数"，也不要暗示是 Kishonti 官方结果。可以写"场景基于 GFXBench T-Rex（开源版，已修改）" |
| CoreMark | 只有未修改核心文件、遵守运行规则（单次 ≥ 10 秒、通过种子校验、统一编译参数）的结果才能叫 CoreMark；格式 `CoreMark 1.0 : 分数 / 编译器+参数`；要显示"CoreMark is a trademark of EEMBC"之类的商标声明 |
| STREAM | 改版结果要写明"基于 STREAM 改版"（based on a variant of the STREAM benchmark code） |
| SciMark | 可以叫 SciMark 2.0 成绩，注明来源 NIST |

## 3. "关于 / 开源许可"页面

App 里放一个页面，内容包括：

1. 每个组件的名称、版本、许可证、版权声明、项目链接。
2. GFXBench 素材的 CC BY 4.0 署名：

   > 3D 场景素材来自 GFXBench（© 2005–2025 Kishonti Ltd），以 CC BY 4.0 许可发布：https://creativecommons.org/licenses/by/4.0/ 。本应用对其进行了修改：只保留 ETC1 格式纹理，删除了部分未使用的文件。

3. EEMBC 商标声明。
4. 历史成绩来源（如内置）。

数据包里的 `manifest.json` 也写一份素材署名（见 [06](06-data-packs.md) §3）。

## 4. 不能用的项目

| 项目 | 原因 |
|---|---|
| glmark2 | GPL-3.0，代码并进来会要求整个 App 以 GPL 开源；只能用来交叉验证 |
| lmbench | GPL-2.0，另外禁止发布改版成绩、禁止在有广告收入的网站发布成绩 |
| fio | GPL-2.0 |
| Embench-IoT | GPL-3.0 |
| LZ4 的 bench 程序 | GPL-2.0（LZ4 的库本身是 BSD-2，可以用） |
| iozone | 作者保留发布衍生作品的独占权 |
| CoreMark-PRO | 成绩公开前必须先上传到 EEMBC 网站审核；商业营销引用需要商业许可 |
| Linpack C | 没有许可证 |
| Whetstone | 不允许修改 |
| Dhrystone 2.1、nbench、Mobibench | 没有明确的许可证 |
| 0xbench | 许可证混杂，原生部分捆绑 GPL 组件 |
