# 固定版本依赖

| 组件 | 来源 | 固定版本 / 提交 | 许可 |
|---|---|---|---|
| zlib | https://github.com/madler/zlib | 1.3.1 / `51b7f2abdade71cd9bb0e7a373ef2610ec6f9daf` | zlib |
| zstd | https://github.com/facebook/zstd | 1.5.7 / `f8745da6ff1ad1e7bab384bd1f9d742439278e99` | BSD-3（双许可中选用） |
| CoreMark | https://github.com/eembc/coremark | 1.0 / `1f483d5b8316753a742cbf5590caf5bd0a4e4777` | Apache-2.0 + 商标约定 |
| tinymembench | https://github.com/ssvb/tinymembench | `a2cf6d7e382e3aea1eb39173174d9fa28cad15f3` | MIT |
| STREAM | https://github.com/jeffhammond/STREAM | `6703f7504a38a8da96b353cadafa64d3c2d7a2d3` | STREAM 许可，结果标注变体 |
| GFXBench | https://github.com/Kishonti-Opensource/gfxbench | `89aa956f43836798d1eaaab8ec32d3dc8b02eead` | 代码 BSD-3 / 素材 CC BY 4.0 |
| SQLite | https://www.sqlite.org/2026/sqlite-amalgamation-3530100.zip | 3.53.1 | 公有领域 |
| SciMark | https://math.nist.gov/scimark2/scimark2c.zip | SciMark 2.0 C | NIST 公有领域 |
| Mbed TLS | https://github.com/Mbed-TLS/mbedtls | 2.28.10 / `2fc8413bfcb51354c8e679141b17b3f1a5942561` | Apache-2.0 |
| libjpeg-turbo | https://github.com/libjpeg-turbo/libjpeg-turbo | 2.1.5.1 / `8ecba3647edb6dd940463fedf38ca33a8e2a73d1` | IJG / BSD / zlib |
| libpng | https://github.com/pnggroup/libpng | 1.6.43 / `ed217e3e601d8e462f7fd1e04bed43ac42212429` | libpng-2.0 |
| FFmpeg | https://github.com/FFmpeg/FFmpeg | 4.4.6 / `44b04492bfc83215e136f2a68783bff71d328692` | 禁GPL/nonfree配置下LGPL-2.1-or-later |

SQLite 压缩包 SHA-256：`36ad6e7f38540a3b21a2ac36340833f0a9e426bc1c752751c3ba669466827eae`。
SciMark 压缩包 SHA-256：`53d6bffc6468f20a55bc3184ec293bb73bcb2b01cce4965869845ed2210b81ff`。
这两份解包源码直接纳入仓库；其余使用固定提交的子模块，不要 `--remote`。

```powershell
git submodule update --init third_party/zlib third_party/zstd third_party/coremark third_party/tinymembench third_party/stream third_party/mbedtls third_party/libjpeg-turbo third_party/libpng third_party/ffmpeg
# GFXBench 尚未初始化且目录不存在时：先不检出，避免下载所有场景格式。
git clone --filter=blob:none --no-checkout https://github.com/Kishonti-Opensource/gfxbench.git third_party/gfxbench
git -C third_party/gfxbench sparse-checkout init --cone
git -C third_party/gfxbench sparse-checkout set frameworks gfxbench40 3rdparty/zlib 3rdparty/libpng gfxbench-data40/data/gfx/common gfxbench-data40/data/gfx/fill
git -C third_party/gfxbench checkout --detach 89aa956f43836798d1eaaab8ec32d3dc8b02eead
git submodule absorbgitdirs third_party/gfxbench
# 已初始化的仓库不重复 clone；沿用 sparse 设置并 git submodule update。
# 默认完整离线APK必须检出两个场景：
git -C third_party/gfxbench sparse-checkout add gfxbench-data40/data/gfx/trex gfxbench-data40/data/gfx/egypt
```

构建本身不联网获取源码。GPU 大数据只在首次依赖检出时下载，Gradle 数据打包任务不会读取 `.git` 对象目录。

## 本地适配

- zlib、zstd、CoreMark、tinymembench、SQLite 上游源码不变。CoreMark 仅自定义 `native/src/coremark_port/core_portme.*`；CPU zlib 使用 `Z_PREFIX` 和 `z_errmsg=rmb_z_errmsg`，隔离 GPU 自带旧 zlib。
- SciMark 的 Random.c 增加分配失败判断 / 部分分配释放，MonteCarlo.c 分配失败返回 NaN；修改在文件头注明。计算内核不变，RMB 自己实现墙钟计时与结果校验，不编入上游命令行驱动。
- tinymembench 只编 util.c 的 C 内核；不使用上游汇编、分配函数或计时函数。随机依赖延迟是本项目实现。
- STREAM 参考内核抽取到 `native/src/stream_kernels.c`，改成 pthread 和固定工作集；标注变体，不称标准 STREAM 成绩。
- GFXBench 上游不改；ES2 源码清单和 EGL 宿主在 `feasibility/gfxbench-es2`，现在同时用于 App。每个原生变体拥有自己的全部静态库；无 NEON 参数覆盖所有源码。生成数据时给 ui.fs 添加片元精度回退，素材仅留 ETC1 并删除参考图。
- 主测试库仍为每变体一个librmb，gnustl_static；媒体每ABI另有librmbmedia及独立avutil/avcodec/avformat共享库。旧链接器按Java显式依赖顺序加载；媒体ARM全部标量、关闭汇编/SIMD，用r10e GCC4.9编译API8 ARM/API9 x86。
- MbedTLS只编AES/GCM/SHA最小配置；JPEG accurate整数DCT/RGBA，PNG保留透明通道。原始上游源码不改，配置在native/media.cmake和crypto_config.h。
- FFmpeg配置在scripts/build-ffmpeg.sh，仅四个软件视频解码器与必要解封装，无编码、网络或GPL/nonfree。对应源码ZIP和库替换说明见 [媒体分发](../docs/media-distribution.md)。
- Zstd与完整STREAM四内核保留诊断/旧记录用途；正式内存阶段恢复Triad（`native/src/triad_bench.c`），1/N线程共享固定24MiB总量，64字节边界分区、共同起点/截止、计时外全量校验。是固定工作集变体，不声称标准STREAM或纯DRAM峰值。GPU默认资源恢复Fill离屏，ALU/Driver/Blending仍不进入固定流程。

许可证来源为各子模块原文件。Gradle 将其打包成 `benchmark-notices.txt`；项目补充说明在 `licenses.txt` / `xiph-notices.txt`，App 的“开源许可”统一展示。升级任何依赖都需重新验证 API 8 ARM/API 9 x86、无 NEON 指令和动态依赖，并升级受影响的测试协议。
