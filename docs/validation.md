# 验证与限制

本页集中记录截至 **2026-11-15** 的检查入口、设备结果、兼容调查和原始证据。构建、模拟器功能、真机流程和画质分别判断，不同构建的检查不能拼成一次完整验收。固定协议见[测试与结果](benchmarks.md)，工具链与安装见[开发与构建](development.md)。

## 构建与静态检查

从项目根目录运行：

```powershell
$env:JAVA_HOME = 'C:/Program Files/Android/Android Studio/jbr'
.\gradlew.bat :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :app:lintDebug :app:lintRelease --console=plain
.\scripts\check-legacy-native.ps1
.\scripts\check-neon.ps1 -Path app/build/generated/legacyJniLibs/armeabi-v7a/librmb_noneon.so -ExpectNeon $false
```

`check-legacy-native.ps1` 覆盖 API 8/9 导入、SysV hash、动态依赖和 RPATH/TEXTREL。ARM 的 `librmbmedia.so`、`libavutil.so`、`libavcodec.so`、`libavformat.so` 也需逐个以 `check-neon.ps1 -ExpectNeon $false` 检查，NEON 主库作阳性对照。静态检查不能替代旧设备实际加载。

## 设备检查入口

先安装 Debug 与测试 APK，解锁并确认 MainActivity 真正前台。将 `emulator-5554` 换成目标序列号，每次只运行一项，串行执行：

```powershell
adb -s emulator-5554 shell am instrument -w com.retromobilebench.test/com.retromobilebench.BackendInstrumentation
adb -s emulator-5554 shell am instrument -w -e production true com.retromobilebench.test/com.retromobilebench.BackendInstrumentation
```

在 `-w` 后选择专项参数：

| 参数 | 覆盖内容 |
|---|---|
| 无参数 | JNI、CPU/内存/存储、加密与图片、汇总/旧历史/恢复、保存失败、宿主干扰和 GPU 回归 |
| `-e production true` | 真实开始按钮、完整 15 阶段、轮数/中位数、五项 GPU 正式时长、窗口与持久化 |
| `-e displayOnly true` | 竖屏主界面、5 秒 Egypt 全屏/HUD、SD 视频、runner Web 三轮、中位数与返回竖屏；API 11+ 重复 Surface 创建/销毁 |
| `-e webOnly true` | 四项 × 20 单元 × 三轮、实际视口、独立进程、身份隔离、持久化和旧会话 |
| `-e webOnly true -e webFaults true` | 上述负载，加校验/时钟错误、部分保留、进程死亡、JS 卡死、超时/取消、Home 和重启；仅 Debug |
| `-e lifecycleOnly true` | API 16+ 首轮落盘后取消、GPU 后台中断与亮屏释放；API 8–15 旧窗口返回取消与 Home 中断 |
| `-e interferenceOnly true` | 事件等待、取消竞争、迟到回调、共享温度源及清理 |
| `-e fixedOnly true` | 固定参数、加密向量/坏标签/8 线程、六图参考、恢复/保存失败、七段媒体矩阵；允许系统路径如实失败 |
| `-e mediaOnly true` | API 16+ 严格媒体检查，要求软件/系统/播放共 21 路径全部完成，并核对 EOS、帧数、时间戳和 Surface；用于预期支持固定档位的设备 |
| `-e mediaTimestamps true` | API 16+ 平台/容器时间和逐包哈希对照，不运行解码、不计分 |
| `-e gpuOnly true -e fullGpu true` | 完整资源校验、四模式各 5 秒、正式 60 秒 Fill、取消/超时/重启及实际 EGL 配置 |
| `-e gpuSurfaceOnly true` | API 11+ 已知色块、实际 EGL 配置与 GL 回读，显示约 20 秒供截图；诊断不计分 |
| `-e gpuCaptureOnly true -e gpuTest gl_egypt` | API 11+ 四个固定动画时间的场景回读，也支持 gl_trex；[使用与边界](#sgx544-屏上花屏) |

单场景使用 `-e gpuTest gl_egypt` / `gl_trex` / `gl_egypt_off` / `gl_trex_off` / `gl_fill_off`。默认诊断 5000 ms，`-e gpuDurationMs 0` 使用正式配置；非零值限 1–10000 ms，不能作为正式成绩。原生加密线程 join 顺序另有独立诊断源 [crypto_join_check.c](../tests/native/crypto_join_check.c)。

检查实际 PASS/FAIL、会话状态和逐路径结果，不只看旧 adbd 的退出码。`production PASS` 证明流程断言通过，允许会话如实为 partial；`mediaOnly PASS` 才要求 21/21。短测、场景回读和故障注入均不能替代正式性能结果。

## 模拟器与构建覆盖

| 环境 / 构建范围 | 已确认 | 边界 |
|---|---|---|
| 最新 EGL 修复构建 | Debug / Release / test APK、lintDebug / lintRelease；11 个共享库的 API 8/9 导入及依赖检查通过 | Release 未做真机验收；实际构建见下方 SGX544 记录 |
| API 10 x86，分批全屏 / HUD 验证 | 全屏构建的 Web 三轮及故障；HUD 构建的旧 GPU/视频窗口、HUD、返回取消/Home 中断通过 | 不替代 API 8 ARM；未完成全部严格媒体路径 |
| API 16 x86，Binder 修复构建 | 全屏、Web 故障、生命周期、固定套件专项通过 | 七段软解完成，部分系统/播放路径仍有 feed_input / drain_output 失败 |
| API 29 x86，分批全屏 / HUD 验证 | 全屏构建的 Web 故障；HUD 构建的全屏、HUD、温度源清理和 Egypt 正式 113 秒通过 | 非物理性能或温升证据 |
| Web v1 历史构建 | API 10 / 16 / 19 / 29 x86 四项三轮和故障回归通过；API 16 完整 v3 为 14 completed、视频 partial | 固定小视口，不代表当前 Web v2；API 19 尚未补 v2 复测 |

API 16 / 29 的额外完整 GPU 专项曾在 T-Rex 离屏时出现模拟器进程退出、ADB 消失，显式 SwiftShader 重试也未完成；原因未定位，不记 PASS。API 8 ARM 的旧镜像因 avdmanager 类型转换错误未能创建可运行 AVD，仍无运行时证据。

## 真机验证

两台均为双核 ARMv7 NEON / API 16，使用完整离线 Debug APK，覆盖安装并保留历史。记录日期采用宿主日期，三星系统时钟保留在 2012 年，耗时使用单调时钟。完整会话已有资源缓存，不代表干净安装或首次解包峰值；电池温度不是芯片结温。

| 设备 | 系统 / GPU | 窗口与 Web 视口 | 完整会话供电 / 电池温度 |
|---|---|---|---|
| 小米 2A（`c0e4aa9d`） | Android 4.1.1，MIUI 4.12.5 / Adreno 320 | 物理 1280×720 / CSS 640×360 | AC；25.4→25.5°C，峰值 25.6°C |
| 三星 GT-I9260（`cff030496503230`） | Android 4.1.2，JZO54K.I9260ZCAMG1 / PowerVR SGX544 | 物理 1280×720 / CSS 640×360 | USB；29.8→35.8°C，峰值 35.8°C |

| 设备 / 原生构建 ID | 完整 fixed-suite-v3 结果 | 会话 |
|---|---|---|
| 小米 / `r10e-gcc49-d093143d31fbc32cbdde01c4` | 15/15 completed；1010097 ms（16 分 50.097 秒） | `b39a02d2-a62d-456d-895c-37ead83246b8` |
| 三星 / `r10e-gcc49-8bfd6c4ebf061eb51f3bec46`（Binder 修复） | 流程/计时 PASS，15/15 completed；1229389 ms（20 分 29.389 秒） | `da9e89ea-256d-42f5-aab1-2def9f7a4286` |

均由真实开始按钮触发；九个三轮阶段有完整样本和有效中位数，视频与五项 GPU 各一次，GPU 独立进程使用正式 113/113/56/56/60 秒，媒体 21/21 completed。数据库重读一致，分析副本 integrity_check=ok。匹配构建的全屏、Web 故障、生命周期、严格媒体和宿主干扰专项也通过。

**三星该历史完整会话的 Egypt 存在彩色噪声，旧 GPU 值不用于排名或等价画质比较。** 后续 EGL 修复没有倒算到旧会话，小米也没有在后续 Binder / EGL APK 重跑。原始成绩见下方证据，不在文档重复展开。

### 旧平台兼容修复

- **Binder 回收**：首次三星 v3 在已保存五阶段后因 `DeathRecipientList::find` / 已删除 JNI 全局引用崩溃。移除一次性进程结束后多余的 `unlinkToDeath`，避免旧 JNI 死亡通知引用转换竞态；保留死亡通知、硬截止、异步解绑和回收失败保护。先在 API 16 模拟器回归，再做上表完整复测。依据：[AOSP Binder JNI](https://android.googlesource.com/platform/frameworks/base/+/android-4.1.2_r1/core/jni/android_util_Binder.cpp)。
- **TI 容量与 B 帧时间**：API 16–17 的 `OMX.TI.*` 使用 `max-input-size=0` 保留驱动容量，输入仍禁止截断。固定 High/FHD 素材在逐样本核对数量、顺序、大小、SHA-256 和整条 DTS 后，才改用同一样本的容器 PTS，输出不排序或重写，四条系统/播放路径随后由 TI 完成。依据：[AOSP ACodec](https://android.googlesource.com/platform/frameworks/av/+/android-4.1.2_r1/media/libstagefright/ACodec.cpp)；完整条件见[视频协议](benchmarks.md#视频矩阵)，不能泛化为所有 Android 4.1。
- **EOS 与后备窗口**：严格统计最后一帧和 EOS；失败进程回收后等待真实 `surfaceDestroyed` 再重建后备窗口。SEC VP8 仍可能校验失败或原生崩溃，Google VPX 后备完成，失败 attempts 保留。TI/qcom 专有布局缺少直接像素证据，API 16 `hardware_type=unconfirmed`，名称本身不是硬解证明。

### SGX544 屏上花屏

GT-I9260 驱动为 `OpenGL ES 2.0 build 1.9.RC2@2139099`。旧 Egypt 在水面、天空、HUD 和半透明取消按钮附近有彩色噪声；关闭 Activity 2D 硬件加速无效并已撤回。已知色块及 12000/35000/65000/95000 ms 的场景 GL RGB 回读正常，但回读会同步 GPU，只能证明观测帧。仅修改屏上 EGL 选择后，无回读的正式时长系统截图不再出现原噪声。

旧选择器只取最低要求的第一项，本机实际为 config 17、RGB888/alpha8、depth24/stencil8、native visual5。`opaque-rgb888-v1` 检查精确 RGB888/alpha0、samples0、depth≥16，允许合格厂商 visual，无合格配置则失败。最终为 config20、depth24/stencil8、native visual511；511 不能假定等于 RGBX_8888 常量2。离屏、材质、ETC1、RGB565 反射目标、分辨率和正式时长未改，生产交帧未增加 `glReadPixels` / `glFinish`，实际 `egl_config` 随结果保存。

构建 `r10e-gcc49-f7383420c0bf1e82654429f9` 在三星完成以下定向验证，未重跑完整套件、其他 GPU 或 Release 真机：

| 检查 | 实测结果 |
|---|---|
| Egypt 屏上正式 113 秒 | completed，113005 ms、1453 帧、1454 次交换、138 次 HUD 更新；中段/后段截图无原噪声 |
| T-Rex 屏上正式 56 秒 | PASS，56144 ms、312 帧、313 次交换、96 次 HUD 更新；场景/HUD/取消按钮截图正常 |
| 色块 / 全屏 / 生命周期 | `gpuSurfaceOnly`、`displayOnly`、`lifecycleOnly` PASS；实际 EGL/色值/alpha、重复 Surface、视频/Web、返回与亮屏释放通过 |

依据：[Android EGL 组件选择器](https://developer.android.com/reference/android/opengl/GLSurfaceView#setEGLConfigChooser(int,int,int,int,int,int))要求检查精确 RGBA 位数，`EGL_ALPHA_SIZE=0` 下限不足以保证 alpha0；[SurfaceHolder](https://developer.android.com/reference/android/view/SurfaceHolder#setFormat(int))默认不透明。[Imagination 官方 S4 / SGX544MP 案例](https://forums.imgtec.com/t/powervr-sgx544mp-on-samsung-galaxy-s4-gt-i9500/1942)涉及 RGBA4 renderbuffer / glCopyTexImage2D，官方建议 RGBA8 和 PVRTrace，升级驱动未普遍解决；路径不同，本项目未据此改 FBO。尚未确定厂商内部错误，不能扩展为所有 SGX544 均已验收。

诊断使用本页 `gpuSurfaceOnly` / `gpuCaptureOnly` 入口，仅 API 11+：色块输出在 `files/gpu-surface-*`，场景四帧在 `files/gpu-scene-diagnostic/<test>/`。回读标记 `diagnostic_only=true` / `score_eligible=false`，不进入正式历史；先删除旧同时间点产物，必须重新生成四帧。PNG 保留 RGB 并强制不透明，TGA 保留原通道。正式运行用 `gpuDurationMs=0`，另拍系统截图，并核对新报告 test_id/时长；加载图和旧报告不能作完成证据。

## 原始证据

以下 8 份原始导出未重写或合并，保存在 `docs/evidence/2026-11-15/`，不随 build 清理删除。截图为不同动画时刻的观测，不是逐像素黄金图像对比；历史报告不替代发布验收。

| 文件 | 用途 | SHA-256 |
|---|---|---|
| [mi2a-production-v3-hud.json](evidence/2026-11-15/mi2a-production-v3-hud.json) | 小米完整 15 阶段 | `59561f39765d48b968b0fe7d27b8f761a813f650896d577804e772b65c143550` |
| [i9260-production-v3-hud-binderfix.json](evidence/2026-11-15/i9260-production-v3-hud-binderfix.json) | 三星完整 15 阶段，旧 GPU 画质异常 | `7c4e59f372ef3b1ad07298c425c4aa235b0721df531cedf60b2c1509198cef5c` |
| [i9260-egypt-fixed.json](evidence/2026-11-15/i9260-egypt-fixed.json) | 最终 EGL 构建，Egypt 正式 113 秒 | `431dcbe102bbf0a0889f61e38eb0ddc013fc9e306dd708eecff11495daa1ae61` |
| [i9260-trex-final.json](evidence/2026-11-15/i9260-trex-final.json) | 最终 EGL 构建，T-Rex 正式 56 秒 | `758a035e8828008199b142753c5499a0ffcce9bc94c78ba519a9ed380e8b0035` |
| [i9260-surface-final.json](evidence/2026-11-15/i9260-surface-final.json) | 最终 EGL 配置与色块诊断，不计分 | `b375adf463c8b915ea5371861f6ef14c7799b979c396efd8375194bec1e768e1` |
| [i9260-egypt-before-overlayfix.png](evidence/2026-11-15/i9260-egypt-before-overlayfix.png) | 修复前花屏 | `b4c8f6f8e95c96553950e405651c59f6ae031d1c8e07a39ce5373fdd89d5aa57` |
| [i9260-egypt-fixed-mid.png](evidence/2026-11-15/i9260-egypt-fixed-mid.png) | 最终 Egypt 正常观测画面 | `b5a7c604d69c6a5dd79bf2f52f2b625a49696b552c4acd637269401d8e01383a` |
| [i9260-trex-retry.png](evidence/2026-11-15/i9260-trex-retry.png) | 最终 T-Rex 正常观测画面 | `b91ff1cc95815bf2d625aee3fc879c622aeaa0e148ad5a3eed9cbc9f803625ab` |

## 仍待验收

- 最新 EGL 构建的完整 15 阶段、小米交叉回归、Release 真机、干净安装及首次解包峰值。
- API 8–15 ARM、无 NEON ARMv7、原生 x86 真机、可调度八核设备，以及 API 29+ 系统解码器属性分支。
- 更多厂商媒体布局、GPU 驱动和片元精度；视频逐帧画质、真实显示 FPS/帧数、API 16 硬件类型仍无直接证据。
- 运行内存峰值、全面温升/降频及宿主开销优化的真机 A/B。电池温度、频率快照或模拟器结果不构成这些结论。

后续改动先完成构建、静态检查与模拟器回归，再做最终真机验收。核对固定参数/时长、取消/后台/锁屏/Surface 丢失、失败后继续、保存异常和重启恢复；高档位失败保留其他有效结果，不补零、不生成综合分。发布前生成匹配 APK 的签名、通知和对应源码，见[许可与分发](licenses.md)。本次文档整理没有重新执行设备测试。
