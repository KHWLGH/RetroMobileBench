# 06 GFXBench 场景数据：打包与导入

2026-11-12 实现：默认 APK 含底层小包；`-PgpuData=full` 含 T-Rex/Egypt。两种构建使用同一 applicationId。用户也可以通过文件管理器从 SD 卡等位置选择 ZIP，校验并复制到 App 私有内部存储。外置 SD 卡作为运行数据目标、直接使用已解压目录尚未实现。

## 1. 数据量与构建

仅保留 ETC1，移除 `common/ref_*` 画质比对参考图。离屏配置、common/fill、lowlevel 与 shaders.20 着色器为基础包；full 增加场景数据。

| 数据 | 约占用 |
|---|---|
| 底层测试 common/fill/着色器 | 2.6 MB |
| T-Rex | 68.4 MB |
| Egypt | 102.6 MB |
| 完整解包后 | 173.5 MB |

`app/build.gradle.kts` 中 `GpuPackTask` 自动选取文件、生成 SHA-256 清单，按路径排序、时间戳固定、ZIP STORED 输出。只在导出数据中给 ui.fs 添加 highp/mediump 精度回退，不修改依赖源码。

```powershell
# 首先按 third_party/README.md 检出 GFXBench 资源
.\gradlew.bat :app:generateGpuPack -PgpuData=full
# app/build/generated/gpuPack/gpu-data.zip 可直接导入 App
.\gradlew.bat :app:assembleDebug -PgpuData=full
# 不传 gpuData=full 则生成并内置底层小包
```

Gradle 将 ZIP 作为生成的 assets 打包。完整 APK 本身与解包后数据都占空间；导入还需要为新包预留临时空间。生成数据与 APK 不提交 Git。

## 2. 当前 ZIP 协议（format 2）

ZIP 第一项必须是 `manifest.json`，后续只有清单中声明的普通文件，不含目录条目：

```text
gpu-data.zip
├── manifest.json
├── config/gl_alu_off.json ...
└── data/gfx/common/...、fill/...、shaders_40/...、trex/...、egypt/...
```

```json
{
  "format": 2,
  "source": "89aa956f43836798d1eaaab8ec32d3dc8b02eead",
  "texture_type": "ETC1",
  "tests": ["gl_alu_off", "gl_fill_off", "gl_driver_off", "gl_blending_off", "gl_trex_off", "gl_egypt_off"],
  "files": [{"path": "config/gl_alu_off.json", "size": 123, "sha256": "<64 位小写十六进制>"}],
  "license": "CC BY 4.0 https://creativecommons.org/licenses/by/4.0/ Copyright 2005-2025 Kishonti Ltd; ETC1 subset, reference images removed, ui.fs precision fallback"
}
```

上述 size/hash 仅示意，实际由任务逐文件计算。旧设计中的 format 1 / SHA-1 未实现，不被当前导入器接受。可制作仅包含部分测试的包；每项必须有对应 config，场景还必须有 scene_trex.xml / scene_egypt.xml。

## 3. 导入与恢复

1. API 16 `ACTION_GET_CONTENT`，MIME application/zip，临时读 URI 授权。系统没有文件选择器时提示安装文件管理器，不申请外部存储权限。
2. 后台串行工作线程读取 ZIP；清单最多 2 MiB、3000 文件、256 MiB 总内容、单文件最多 64 MiB；固定源码版本/ETC1/测试名白名单。
3. 检查内部可用空间至少为声明总大小 +16 MiB。
4. 解包到 `files/gpu-packs/import-<uuid>`；路径只允许 config/ 或 data/gfx/ 下的有限字符，拒绝 ..、绝对路径、反斜杠、重复/未声明条目、超长内容、缺文件和大小/hash 不符。
5. 所有文件校验通过后最后写并 sync 清单，原子 rename 为 `pack-<uuid>`；取消或错误删除临时目录，保留原有有效包。
6. 新包提交后删除被其测试集合完全覆盖的旧包；保留含其他场景的包。清理失败记录日志，不把成功的新包标成失败。启动清理遗留 import-*。
7. 运行时选含目标测试的最新已安装包；没有时检查内置包是否支持该项，再校验安装。底层包不含场景时直接跳过，避免反复安装同一小包。

数据位于内部私有目录，不需要权限；卸载 App 会删除。SHA-256 是完整性检查，并非数字签名；应导入本项目生成、来源可信的数据。结果记录清单 SHA-256，三轮只合并同一包的数据。

## 4. 测试与内存

GPU 服务只接受私有 gpu-packs 子目录；六项均使用 ES2/ETC1、1920×1080 FBO 和 800×480 pbuffer。底层测试至少 128 MiB 可用内存，场景至少 192 MiB；低内存/缺数据作为跳过记录，不中断“一键全测”的其他项。

Instrumentation 覆盖有效导入、SHA 错误、路径越界、缺文件、非法 ZIP、取消、临时目录清理、原包保留和重复导入清理。完整包测试命令见 [11-backend.md](11-backend.md)。
