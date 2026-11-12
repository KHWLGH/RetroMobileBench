# 02 Android 4.1 / API 16 平台约束

最低兼容 Android 4.1（API 16）带来的硬约束，以及每条的应对方法。

## 1. 工具链

| 约束 | 说明 | 应对 |
|---|---|---|
| NDK 版本 | NDK r24 起不再支持 API 16–18，也不再支持没有 NEON 的 ARMv7 设备；同时删除了 GNU as | 锁定 **NDK r23c**（23.2.8568313） |
| NEON 默认开启 | NDK r21 起 ARMv7 默认开 NEON | 同一 CMake 工程的无 NEON 目标给全部源文件加 `-mfpu=vfpv3-d16`，整套重编并反汇编检查（见 [01](01-architecture.md) §3.2） |
| CMake 版本 | r23c 默认使用"旧版"CMake toolchain 文件；配 CMake 3.22 时编汇编会多出 `-gcc-toolchain` 参数而报错（NDK issue 1623，r24 才修） | 用 SDK 里的 **CMake 3.18.1**（cpuinfo 也要求 ≥ 3.18） |
| GNU as | tinymembench 的汇编不能被 clang 内置汇编器接受 | r23c 还带 GNU as，对这些文件用 `-fno-integrated-as`，并显式传 `-Wa,-march=armv7-a`（否则 GNU as 会生成 ARMv5 指令，NDK issue 906） |
| JDK | GFXBench 自带的 Gradle 7.4.2 / 8.11.1 不支持 Android Studio 自带的 JDK 25 | 单独装 **JDK 17** 给 GFXBench 的构建脚本用 |

## 2. Java 层

| 约束 | 说明 | 应对 |
|---|---|---|
| AndroidX | 2024 年 4 月起所有 AndroidX 库最低 API 21，现在默认 24 | 不用 AndroidX；用原生 View、`android.app.Activity`、Holo 主题 |
| Jetpack Compose | 最低 API 21 | 不用 |
| 新建项目模板 | Android Studio 的模板会自动加 appcompat、material、core-ktx | 建项目后删掉这些依赖 |
| multidex | API 21 以下要用旧版 multidex 支持库，首次启动慢，容易 ANR | 控制依赖和方法数，不超过 64K；必要时开 R8 压缩 |
| Gson | Gson 2.10.1 及以下只验证到 API 19，2.11 起要 21 | 不用 Gson，用系统自带的 `org.json` |
| commons-io | 2.7 起依赖 `java.nio.file`（API 26） | 不用，自己写文件复制 |
| Google Play 服务 | Jelly Bean 上的 Play 服务停在 21.30.99（2021 年 8 月） | 不依赖 Play 服务，不用 Firebase |
| 新 API | 很多常用方法是 API 17/18 才加的，例如 `Display.getRealMetrics`（17）、`StatFs.getAvailableBlocksLong`（18）、`Build.SUPPORTED_ABIS`（21） | 开着 Lint 的 `NewApi` 检查；用 `Build.VERSION.SDK_INT` 判断或反射调用 |
| targetSdk | 新手机（Android 14 起）拒绝安装 targetSdk < 23 的 App | targetSdk = 23。在 4.x 上和任何 ≥ 19 的值效果相同；在 Android 6.0+ 上要运行时申请存储权限（`SDK_INT >= 23` 时调用） |

## 3. 原生层（bionic）

API 16 的 bionic 缺很多后来才加的函数，编译时 NDK 会按 API 等级报错，正好当作待修清单。调研中已经发现会遇到的：

| 函数 | 引入版本 | 谁用到 | 应对 |
|---|---|---|---|
| `getauxval` | 18 | LZMA SDK 的 CpuArch.c（cpuinfo 已经用 dlsym 处理） | 用 cpuinfo 的做法：`dlsym` 查找，找不到就读 `/proc/self/auxv` |
| `posix_memalign` | 17 | tinymembench 延迟测试 | 换成 `memalign`（API 16 就有） |
| `posix_fadvise` | 21 | 存储测试想用来清缓存 | 不用；读测试靠 `O_DIRECT` 绕过页缓存 |
| `pthread_mutex_timedlock` | 21 | Poco Foundation（`Mutex_POSIX.cpp`） | 不用改：Poco 只在 `_POSIX_TIMEOUTS >= 200112L` 时才用它，bionic 在 API 21 以下把这个宏定义为 -1 |
| `pthread_barrier_*` | 24 | 自己写多线程测试时可能想用 | 用 mutex + condvar 自己实现 |
| `pthread_setaffinity_np` | 36 | CoreMark-PRO（我们不用） | 绑核用 `sched_setaffinity`（API 16 就有） |
| `getline` | 18 | — | 解析 /proc 文件时用 `fgets` |
| `_FILE_OFFSET_BITS=64` | — | SQLite 默认会定义 | 当前 SQLite 3.53.1 已用 `SQLITE_DISABLE_LFS=1`、`SQLITE_TEMP_STORE=3`，API 16 x86 事务验证通过 |

## 4. 图形

| 约束 | 说明 | 应对 |
|---|---|---|
| OpenGL ES 版本 | 4.1/4.2 只有 ES 2.0，ES 3.0 从 4.3（API 18）开始，且要 GPU 支持 | 只做 ES2 测试 |
| `libGLESv3.so` | 4.1/4.2 上不存在，链接了它的 .so 会直接加载失败 | 只链接 `libEGL` 和 `libGLESv2`（GFXBench 在 API < 18 时已经自动去掉了 `-lGLESv3`） |
| 片元着色器精度 | Mali-400、Tegra 2/3 等 GPU 的片元着色器不支持 `highp`，写死 `highp` 会编译失败 | 用 `GL_FRAGMENT_PRECISION_HIGH` 判断，不支持时退回 `mediump` |
| 纹理压缩 | ETC1 是所有 ES2 设备都支持的格式 | GFXBench 场景数据只保留 ETC1（见 [06](06-data-packs.md)） |
| `EGLConfig` 句柄可能是 0 | `EGLConfig` 是不透明句柄。Android 4.1 模拟器的 EGL 用下标当句柄，第一个配置就是 0（实测） | 只看 `eglChooseConfig` 返回的数量，不要拿句柄和 0 比较。GFXBench testfw 就踩了这个坑（见 [04](04-gfxbench-port.md) §4.5） |

## 5. 原生库加载

| 约束 | 说明 | 应对 |
|---|---|---|
| 依赖库加载 | 4.3 以前动态链接器不会到 App 的 lib 目录找依赖库 | 所有原生代码链接成一个 .so，用 `c++_static` |
| `dlopen` 按名字找库 | 4.1/4.2 上 `dlopen("libxxx.so")` 不会搜 App 的 lib 目录 | 不 dlopen 自己的库；GFXBench 改用静态工厂表 |
| `thread_local` + `dlclose` | 带非平凡析构的 `thread_local` 变量在库被 `dlclose` 后会崩（API 28 才修） | 我们只有一个 .so 且从不 `dlclose`，不受影响 |

## 6. 硬件与 ABI

| 情况 | 代表设备 | 应对 |
|---|---|---|
| 没有 NEON 的 ARMv7 | Tegra 2：Motorola Xoom（官方升到 4.1）、Galaxy Tab 10.1、LG Optimus 2X 等 | 单独一套无 NEON 原生库；很多 Tegra 2 设备官方只到 4.0，靠刷机才有 4.1 |
| x86 | 联想 K900、摩托 RAZR i、华硕 ZenFone 4/5/6、部分 Atom 平板 | 带 x86 原生库，避免 Houdini 转译 |
| 内存很小 | 512 MB RAM 的入门机 | GFXBench 配置里 T-Rex/Egypt 要求 160 MB 可用内存；测试前检查，不够就跳过 |
| 存储很小 | 内部存储只有 1–4 GB | 存储测试文件大小按剩余空间调整；场景数据包优先放 SD 卡 |

## 7. 联网（本版本不做，记录备查）

以后如果做联网榜单，要注意：

- API 16–19 默认没开 TLS 1.2，要自己写 `SSLSocketFactory` 开启。
- Android 7.1.1 以前不信任 Let's Encrypt 的 ISRG Root X1 根证书，要内置根证书。
- OkHttp 只能用已停止维护的 3.12.x 分支。
- 也可以绕开：把成绩编码成二维码，用新手机扫码上传。

## 8. 测试设备

| 环境 | 用途 | 局限 |
|---|---|---|
| 本机的 Android 4.1 x86 模拟器（AVD "Android4.1"） | 验证 x86 库能装能跑、UI、流程；GFXBench 测试能否跑通 | 不能测 ARM 库，成绩没有意义（GPU 是主机显卡经模拟器转译） |
| Android 4.1–4.4 真机（ARM，有 NEON） | 主要测试设备 | — |
| Tegra 2 真机 | 验证无 NEON 那套库 | 设备较难找 |
| 新手机（Android 14+） | 开发时快速验证 UI | 不代表目标平台 |
