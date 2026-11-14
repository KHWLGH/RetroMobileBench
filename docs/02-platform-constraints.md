# 02 老安卓平台约束

2026-11-14 更新。产品基线为 Android 2.2 / API 8 ARMv7，x86 为 API 9。实际验证状态见 [13 实施记录](13-suite-implementation.md)，范围见 [12 计划](12-test-suite-plan.md)。

## 工具链与加载

- 原生代码由 NDK r10e / GCC 4.9 构建，C++ 运行时 gnustl_static；CMake 3.18.1。r23c 只参与现代打包与检查，不能用它证明 API 8 兼容。
- 主库提供 ARMv7 NEON、ARMv7 VFPv3-D16、x86 三套。无法确认 NEON 时选择无 NEON 主库，不冒险回退。
- 媒体每 ABI 使用相同标量路径：librmbmedia.so，加独立 libavutil.so、libavcodec.so、libavformat.so。Java 按依赖顺序显式加载，适配旧动态链接器。禁止新增未检查的系统导入；使用 `check-legacy-native.ps1` 和无 NEON 反汇编检查。
- 不使用较新的 `getauxval`、`posix_memalign`、pthread barrier 或 NDK MediaCodec。使用 memalign、mutex/condition，以及 API 16 Java 同步 MediaCodec。

## Java 与系统接口

原生 Activity/View、系统 org.json 和 Framework Instrumentation；无 AndroidX、Compose、Kotlin 标准库、Play 服务或第三方测试框架。Java 17 经 D8 脱糖不能提供新的系统 API；Lint NewApi/InlinedApi 为 error，新 API 实现按版本隔离。

API 8–15 使用 MediaPlayer 播放，公共 API 不能确认硬解或指定命名解码器。播放窗口由媒体子进程自己的 Activity 持有；API 8–10 使用 push-buffer holder，私有素材通过文件描述符传给 MediaPlayer。Gingerbread 的 Surface.writeToParcel 不能转发普通窗口收到的 Surface，因此其屏上 GPU 也由 GPU 子进程持有本地窗口。内部窗口切换不算切后台，用户返回/按 Home 仍会取消或中断并回收进程。

API 16–28 使用同步 MediaCodec 的旧缓冲区接口；厂商名字只提供线索。API 29+ 单独加载属性读取类，硬件/软件标记注明来自系统声明。

直接分发 targetSdk 23 APK，保留旧设备需要的 v1 签名；新系统的安装策略还需对应版本实际验证。应用不申请网络或外部存储权限，资源与测试文件均在内部私有目录。

## 图形、内存与设备边界

只链接 ES2，ETC1 固定；不依赖 libGLESv3。离屏为小 pbuffer + 1920×1080 FBO，屏上为真实窗口 EGLSurface，记录实际尺寸/刷新率/交换间隔请求。不能仅依据 EGLConfig 句柄是否为 0 判断成功。

GPU 场景目前保留 192 MiB 可用内存保护，需真机测峰值后再调整。完整离线资源有明显空间成本，不自动降低分辨率或删掉场景。电池温度只采样统计；低电量、真实空间/内存不足仍明确提示。

CPU N 综合 present、目录、在线/运行时和 configured 后备信息；64 为显式资源容量，超过时拒绝并解释。CPU0–31 频率详情快照不等于线程识别上限。线程数也不等于同时运行的物理核数。

模拟器可验证功能和异常分支，不能替代 ARM 无 NEON、厂商硬解、GPU 驱动、峰值内存或温升的真机结论。
