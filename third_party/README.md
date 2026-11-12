# 固定版本依赖

| 组件 | 官方来源 | 版本 | 固定提交 | 许可证 | 用途 |
|---|---|---|---|---|---|
| zlib | https://github.com/madler/zlib | 1.3.1 | `51b7f2abdade71cd9bb0e7a373ef2610ec6f9daf` | zlib，全文见 `zlib/LICENSE` | CPU 压缩 / 解压 |

首次克隆项目后运行：

```powershell
git submodule update --init third_party/zlib
```

子模块固定到上表的 commit；不要用 `--remote` 自动跟随上游。构建不会访问网络下载原生依赖。zlib 源码保持原样；只选择内存 codec 的 C 文件，直接编入每套 `librmb`，不使用其安装 / gz 文件 I/O 目标，也不链接 ROM 的 `libz.so`。使用 `Z_PREFIX` 和隐藏符号避免系统库抢占；`JNIEXPORT` 入口仍可见。每个文件都分别按 NEON、无 NEON、x86 参数编译。

升级依赖必须重新跑 API 16 和无 NEON 检查，并更新 CPU 测试协议版本；`cpu-zlib-v1` 在编译期要求 zlib 1.3.1。许可随 APK 打包到 `app/src/main/assets/licenses.txt`，可在界面“开源许可”中读取。
