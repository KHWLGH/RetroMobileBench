# 06 GFXBench 场景数据：打包与导入

决策（2026-11-12）：两种方式都支持——完整版 APK 自带数据，或者从 SD 卡导入数据包。

## 1. 数据量

只保留 ETC1 纹理（理由见 [04](04-gfxbench-port.md) §4.6），各部分大小（来自 GitHub tree API，`gfxbench-data40/data/gfx/`）：

| 目录 | 内容 | 大小 |
|---|---|---|
| `trex/`（不含其他格式纹理） | animations 18.4 MB、meshes 24.2 MB、images_ETC1 25.7 MB、materials 等 | 约 68 MB |
| `egypt/`（不含其他格式纹理） | animations 29.9 MB、meshes 24.8 MB、images_ETC1 47.8 MB、materials 等 | 约 103 MB |
| `common/` | 粒子、镜头光晕等公共纹理，其中约 7 MB 是画质比对用的参考图 | 9.7 MB |
| `fill/images_ETC1/` | 填充率测试纹理 | 0.2 MB |
| 着色器 `shaders_40/shaders.20/`、`shaders_40/lowlevel/` | 来自 `gfxbench40/resources/data/gfx/` | 很小 |
| **合计** | | **约 181 MB** |

可以再瘦身的地方（待验证不会影响测试）：

- `common/` 里的 `ref_*.png`、`ref_precision.tga`（约 7 MB）是画质比对（Render Quality）测试的参考图，我们不跑画质比对，可以去掉。
- 只用到的底层测试数据（fill 纹理、着色器、common 里的少量文件）不到 3 MB。

## 2. 两种提供方式

| 方式 | 适用 | 数据位置 |
|---|---|---|
| **打包**：`full` 版 APK 自带 | 一次安装就能跑全部测试；不想折腾 SD 卡 | APK assets（首次运行解压到存储） |
| **导入**：`lite` 版 + 数据包 | 内部存储小的设备；多台设备共用一份数据；APK 太大不方便传 | SD 卡或内部存储，从文件导入 |

两种方式最后都落到同一个"数据目录"，测试代码只认这个目录。底层 GPU 测试需要的小数据两个版本都内置，没有数据包也能跑底层测试。

## 3. 数据包格式

```
RetroMobileBench-gfxdata-v1.zip
├─ manifest.json
└─ data/gfx/
   ├─ trex/ ...
   ├─ egypt/ ...
   └─ common/ ...
```

`manifest.json`：

```json
{
  "format": 1,
  "pack_version": "1.0.0",
  "source": "Kishonti-Opensource/gfxbench@89aa956",
  "texture_type": "ETC1",
  "scenes": ["trex", "egypt"],
  "files": [
    { "path": "data/gfx/trex/scene_trex.xml", "size": 766, "sha1": "..." }
  ],
  "license": "CC BY 4.0, (c) 2005-2025 Kishonti Ltd, modified: ETC1 textures only"
}
```

- zip 用"存储"模式（不压缩）：ETC1 纹理和网格本身压缩率低，存储模式解压快，老机器上更省时间。
- 每个文件记 SHA-1（Poco 里有现成的 `SHA1Engine`，Java 层用 `MessageDigest`），导入后校验，避免拷坏的数据跑出错误成绩。
- 可以按场景拆成两个包（trex 包约 68 MB，egypt 包约 103 MB），方便只想跑其中一个的用户。
- `manifest.json` 里写明许可证和"已修改"，满足 CC BY 4.0 的署名要求（见 [08](08-licenses.md)）。

## 4. 数据目录

| 优先级 | 位置 | 说明 |
|---|---|---|
| 1 | 外置 SD 卡上的 App 目录 | API 16–18 没有 `getExternalFilesDirs()`（API 19 才有），要自己找挂载点（读 `/proc/mounts`、常见路径 `/storage/sdcard1`、`/mnt/extSdCard` 等），并测试能否写入 |
| 2 | `Environment.getExternalStorageDirectory()` 下的 App 目录 | 多数 4.x 设备上是内置的"SD 卡"分区；需要 `WRITE_EXTERNAL_STORAGE` 权限（4.x 安装时授予） |
| 3 | App 内部存储 `getFilesDir()` | 空间最紧张，最后选择 |

- 让用户在数据包管理页看到可用位置和剩余空间，自己选。
- 选定后把路径存进设置，传给 GFXBench 的 `basepath`（`TestRunner.setBasepath()`）。
- 注意：放在外部存储的数据，卸载 App 时如果在 `Android/data/<包名>/` 下会被删掉；放在外部存储根目录下的自定义目录则不会。建议放在自定义目录（例如 `RetroMobileBench/data/`），卸载重装不用重新导入。
- 例外：Android 4.4 起，**可移除的外置 SD 卡**上只能写本 App 的 `Android/data/<包名>/` 目录（用 `getExternalFilesDirs()` 获取），写不了自定义目录，数据会随卸载删除。4.1–4.3 和主外部存储（内置的 sdcard 分区）一般没有这个限制。

## 5. 导入流程

1. 用户把数据包 zip 拷到手机任意位置。
2. 数据包管理页 → "导入"：
   - API 16–18 没有系统文件选择器（Storage Access Framework 是 API 19 才有），自己做一个简单的文件浏览器，或扫描常见目录（下载目录、SD 卡根目录）里匹配 `RetroMobileBench-gfxdata-*.zip` 的文件。
   - 也支持"已解压的目录"：用户直接把 `data/gfx/` 拷到数据目录，App 只做校验。
3. 检查剩余空间（至少要能放下解压后的数据）。
4. 解压到数据目录，逐个文件校验 SHA-1，显示进度。老机器上 180 MB 可能要几分钟。
5. 校验通过后写一个标记文件（记录 pack_version），以后启动时只检查标记，不重复校验全部文件。
6. 失败时删掉解压了一半的数据，提示原因（空间不足、文件损坏等）。

## 6. 打包版的处理

- full 版把数据放在 APK 的 `assets/data/gfx/`。
- GFXBench 的 `kcl_io_os.cpp`（`OS_PreOpen`）在找不到磁盘文件时会从 APK assets 读，**读完还会写一份到磁盘**，等于数据存两份（APK 里一份、磁盘上一份），约 360 MB。
- 建议做法：首次运行时由 Java 层把 assets 里的数据一次性解压到数据目录（走和导入相同的校验流程），之后 GFXBench 只读磁盘文件，`OS_PreOpen` 的复制逻辑不会触发。
  - 这样还是两份（APK + 磁盘），但可以让用户选把解压目标放 SD 卡。
  - 另一种做法是改 `OS_PreOpen`，直接从 assets 流式读取不落盘，只占一份空间。代价是改动 GFXBench 的 IO 层，而且每次跑测试都要从 APK 解压读取，加载更慢。可以作为后续优化。
- assets 里的文件要设成不压缩（`aaptOptions { noCompress ... }` 或 AGP 新写法 `androidResources { noCompress ... }`），否则读取时还要额外解压。

## 7. 测试前的检查

跑 T-Rex / Egypt 之前：

1. 数据目录存在且标记文件版本匹配。
2. 对应场景的 `scene_trex.xml` / `scene_egypt.xml` 存在。
3. 可用内存满足配置里的 `min_ram_required`（160 MB）。
4. 任何一项不满足：在测试列表里把该测试显示为"缺少数据包"或"内存不足"并跳过，不影响其他测试和计分（见 [01](01-architecture.md) §5.2）。

## 8. 制作数据包（开发者）

写一个脚本（PowerShell 或 Python）：

1. 从 GFXBench 仓库 sparse-checkout `gfxbench-data40/data/gfx/{trex,egypt,common,fill}`。
2. 删掉 `images_ASTC`、`images_DXT1`、`images_PVRTC4` 目录。
3. 可选：删掉 `common/` 里的画质比对参考图。
4. 生成 `manifest.json`（大小、SHA-1）。
5. 用存储模式打 zip。

脚本和生成的数据包都不要提交到 git（数据包放 Release 附件或网盘）。
