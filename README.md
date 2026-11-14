# RetroMobileBench

给旧安卓设备使用的离线一键跑分工具。当前构建基线为 Android 2.2 / API 8（ARMv7），x86 为 API 9；重点面向早期多核 Android 手机和平板。

本轮已接入 [固定套件计划](docs/12-test-suite-plan.md)，构建和模拟器流程验证通过，真机发布验收仍待完成。界面只有开始、取消和历史/设备/许可/JSON 导出；不要求选择分类、线程、分辨率或资源包。自动执行固定14阶段：CoreMark等8个阶段各三轮取中位数，视频和各GPU模式一次，输出整次会话；不生成未经校准的总分。实现和验证状态见 [实施记录](docs/13-suite-implementation.md)。构建成功不能替代真机硬解、内存峰值和温升验证。

| 固定顺序 | 内容 |
|---|---|
| CPU | CoreMark 单线程 + N 线程、SciMark small 五内核、zlib 单线程 + N 个独立流 |
| 加密 | Mbed TLS AES-128-GCM 加密/解密认证、SHA-256，单线程 + N 线程 |
| 图片 | 固定 libjpeg-turbo / libpng，480p、720p、1080p JPEG/PNG 软件解码及像素校验 |
| 视频 | H.264 SD、HD Baseline、HD High、Full HD High，以及 H.263、MPEG-4、VP8；固定 FFmpeg 软解、系统解码、短片实时播放 |
| 内存 | 复制、填充、依赖随机访问延迟；固定总工作集24MiB的单线程 + N线程STREAM Triad变体 |
| 存储 | 内部存储顺序/随机读写，固定 SQLite 事务 |
| GPU | Egypt HD 离屏、屏上，再 T-Rex 离屏、屏上、Fill离屏；离屏1080p，屏上实际窗口尺寸 |

五项GPU配置时长合计398秒（6分38秒），不含准备和加载。CoreMark、SciMark、zlib、加密、图片、内存、文件存储和SQLite各三轮，轮间1秒；视频矩阵及每个GPU模式一次。整套预计约12–15分钟；API16 x86模拟器本轮完整流程为752579ms（约12分33秒），视频partial，其余13阶段completed。真机时长以设备实测为准。Zstd、完整STREAM四内核、ALU/Driver/Blending退出产品流程，历史记录仍可查看。网络、编码、音频、4K 和新视频格式不属于本轮。

线程数综合 present CPU、CPU 目录、在线状态和运行时报告；缺少实际拓扑时才回退到 configured CPU。单核不重复测并行 1 线程。当前资源容量为 64 个工作线程，超出时明确拒绝，不能静默截断后声称测了全部核心。线程数不是物理核心同时执行的证明。

电池温度只作为约两秒一次的升温统计，不设高温拦截或冷却等待，也不能称 CPU/GPU 结温。低电量与真实内存/空间不足仍有明确提示。GPU 和系统媒体在可回收的独立进程中运行。

## 构建

使用 Java Framework View、C/C++、NDK r10e / GCC 4.9 / gnustl_static；现代 NDK r23c 只参与打包检查。目标 SDK 23，保留 v1 签名。没有 AndroidX 或新的测试框架。

1. 按 [固定依赖说明](third_party/README.md) 初始化子模块和两套 GPU 场景的 ETC1 数据。
2. 安装 Android Studio 所用 SDK、CMake 3.18.1、NDK r23c；将 r10e 安装到 `.tools/android-ndk-r10e`。需要 Git Bash；可用 `-PgitBash=<path>` 指定路径。
3. 构建默认完整离线 APK：

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
.\scripts\check-legacy-native.ps1
.\scripts\check-neon.ps1 -Path app/build/generated/legacyJniLibs/armeabi-v7a/librmb_noneon.so -ExpectNeon $false
```

FFmpeg 在 Gradle `buildFfmpeg` 中由同一 r10e 工具链构建，只开启四种视频解码器和必要解封装，不含网络、编码和 GPL/nonfree 组件。媒体库采用固定标量路径，在有/无 NEON 的 ARMv7 上使用相同协议；主测试库仍保留 NEON、无 NEON 和 x86 构建。

默认 APK 包含完整固定场景和媒体资源。产品没有 lite/full 选择或导入入口。首次运行自动校验并准备；包体和解包空间的代价需在发布前实测。固定媒体素材已纳入源码；`scripts/generate-media.ps1` 是生成配方，重新生成后必须审查哈希并升级语料协议。

## 验证与分发

```powershell
adb shell am instrument -w -e fixedOnly true com.retromobilebench.test/com.retromobilebench.BackendInstrumentation
adb shell am instrument -w -e production true com.retromobilebench.test/com.retromobilebench.BackendInstrumentation
.\gradlew.bat :app:correspondingSource
```

`fixedOnly` 检查多核策略、加密已知向量/坏标签、图片参考像素、会话恢复和完整视频矩阵；`production` 从真实开始按钮运行完整固定套件，核对自动三轮/单次策略，包括五项完整GPU时长。模拟器用于流程和异常验证；真机才用于硬解、速度、峰值内存和温升结论。

直接分发 APK，不上 Google Play。每份发布 APK 必须同时提供匹配的对应源码 ZIP、构建脚本与 LGPL 说明，不能仅链接上游仓库。FFmpeg 保持独立动态库并可替换，详见 [媒体分发说明](docs/media-distribution.md)。开源许可和素材署名也在 App 内可查看。

设计范围以 [12 固定套件计划](docs/12-test-suite-plan.md) 为准，实际验证以 [13 实施记录](docs/13-suite-implementation.md) 为准。01–11 文档保留早期方案和历史验证，其中旧的 API 16/r23c 编译、六项离屏、导入包、用户选择单/三轮和温度门槛描述已经被本轮替代。
