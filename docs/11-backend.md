# 11 固定套件后端

2026-11-14 更新。本页描述当前调度与协议；实际通过的检查和剩余设备要求见 [13 实施记录](13-suite-implementation.md)，旧验证数据不自动迁移为本轮结论。

## 调度与记录

`MainActivity → BenchmarkRunner → BenchmarkSuite.DEFAULT → JNI / MediaClient / GpuClient → ResultStore`。

DEFAULT固定14个内部阶段：CoreMark、SciMark、zlib、加密、图片、视频、内存、文件存储、SQLite、Egypt离屏/屏上、T-Rex离屏/屏上、Fill离屏。用户只点击开始；前述除视频和GPU外8阶段各三轮，相邻轮间1秒，其余一次；SQLite在存储范围内，内部阶段不是菜单。应用级单串行工作线程只持有Application，Activity订阅状态，重建不启动第二份任务。界面保持亮屏；离开前台、锁屏、实际使用中的Surface丢失按中断停止。

整次schema2、`fixed-suite-v2`、UUID、results数组和活动阶段检查点；各阶段继续保存schema1样本/协议。启动恢复把遗留running标为interrupted，保留已保存分项与active_report中的轮次；每轮完成即保存，恢复时当前阶段标为interrupted且移除median。旧fixed-suite-v1/memory-tiny-v1仍可读。创建/更新数据库失败与测量状态分开，保留内存结果并可导出；运行中导出为已有会话检查点。结果含completed/partial/unsupported/timeout/failed/cancelled/interrupted/skipped等状态，缺项不填零，无总分。

取消立即显示正在停止，不启动下一阶段。CPU/CoreMark/同步I/O等待安全边界，媒体/GPU请求回收子进程。会话记录请求与实际停止耗时；阶段间固定1秒，没有末尾等待。电池温度约2秒采样，仅统计起止/采样峰值/温升。

## 原生与媒体

协议/参数见 [03](03-benchmarks.md)。CoreMark/zlib已升v2，统一1+N与64线程容量，单核不重复。旧v1报告仍可读，不混合汇总；完整STREAM保留原四线程诊断容量并显式校验；正式内存memory-tiny-v2新增单线程及N线程Triad，共享固定24MiB总工作集，使用64线程容量。CPU核心上游保持不变，CoreMark只改port；zlib前缀隔离GPU旧zlib。

MbedTLS最小AES/GCM/SHA配置、libjpeg-turbo标量与libpng链接到独立librmbmedia.so。FFmpeg avutil/avcodec/avformat动态分离，按依赖顺序加载以适配旧链接器；无网络/编码/GPL/nonfree。图片在主串行线程调用JNI，压缩数据/参考预载，真实解码并前后逐字节验证。

每个视频路径使用一次性`:media`进程，父进程看门狗、Binder死亡与清理确认；软件真实FFmpeg输出，系统API16命名同步MediaCodec，旧系统MediaPlayer播放，API29硬件属性引用单独隔离。API16+播放Surface从前台Activity取得，PTS节奏提交；API8–15由媒体子进程Activity持有本地窗口，API8–10使用push-buffer holder，私有素材以文件描述符交给MediaPlayer。内部窗口切换不打断运行；返回键/取消按钮与按Home分别回传取消/中断，已有软件结果保留。每档软件/系统/播放独立状态；厂商名字保留未知硬件类别，API8–15不误报硬解。

## GPU与资源

每轮一次`:gpu`进程，ES2静态工厂。离屏小pbuffer+1080p FBO；API11+屏上从主Activity传入真实Surface，API8–10绕过旧系统无法转发借用Surface的限制，由GPU子进程Activity创建本地窗口。Java EGL创建window surface并实际交换缓冲区。记录window_owner、实际尺寸/刷新率/交换间隔请求、成功交换计数与上游原始JSON，不把提交FPS解释为精确显示FPS。五项113/113/56/56/60秒，合计398秒；Fill使用MTexels/s。

默认APK完整内置资源；自动校验/原子解包/取消/空间保护与180秒准备截止，产品无导入入口。场景保留192MiB可用内存门槛，不能默默降低画质。详细资源协议见 [06](06-data-packs.md)。

## 构建与验证入口

API8 ARMv7/API9 x86，r10e GCC4.9/gnustl_static，主库NEON/无NEON/x86，媒体ARM统一标量。Java Framework与系统Instrumentation，无新测试框架。构建见 [05](05-build-and-toolchain.md)。

```powershell
adb shell am instrument -w -e fixedOnly true com.retromobilebench.test/com.retromobilebench.BackendInstrumentation
adb shell am instrument -w -e gpuOnly true -e fullGpu true com.retromobilebench.test/com.retromobilebench.BackendInstrumentation
adb shell am instrument -w -e production true com.retromobilebench.test/com.retromobilebench.BackendInstrumentation
```

不带参数执行后端回归（包括正式CoreMark、1/2/4/8线程、存储/SQLite/历史、资源异常、四模式短GPU与正式60秒Fill）。fixedOnly检查拓扑、加密、参考像素、恢复和七视频路径；production由真实开始按钮跑完整固定套件，检查各阶段三轮/单次策略和有效中位数。软件、系统解码和实时播放的状态须分别审查，不能只因已尝试就宣称全部支持。
