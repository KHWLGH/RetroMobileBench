# GT-I9260 / PowerVR SGX544 屏上花屏调查（2026-11-15）

## 问题与定位

三星 GT-I9260、Android 4.1.2 / API 16、PowerVR SGX 544，驱动字符串 `OpenGL ES 2.0 build 1.9.RC2@2139099`。旧构建 Egypt 的系统截图在水面、天空、半透明取消按钮及部分 HUD 边缘出现彩色噪声；测试仍可 completed。因此原完整流程通过只说明流程和计时，不构成画质通过，也不能用旧 GPU 数值做设备排名。

本次先比较不同观测点，再验证窗口配置。以下诊断均不生成可比较成绩，没有降低场景精度、分辨率、纹理规格或正式时长。

| 对照 | 实际观察 / 适用边界 |
|---|---|
| 原宿主再次运行 Egypt 正式 113 秒 | completed，系统截图再次复现异常；完成状态不能判断画质 |
| 临时关闭 DisplayActivity 的 Android 2D 硬件加速 | 异常仍在，已撤回该 manifest 修改，不作为修复 |
| 已知 RGB / alpha 四色块 | GL RGB 与系统截图均正常；不是任何透明像素都可触发异常，不能用色块 PASS 证明复杂场景正常 |
| 原窗口配置下，上游 `screenshot_frames` 回读 Egypt | 动画 12000 / 35000 / 65000 / 95000 ms 的 GL RGB 均正常，包括反射、水面和天空。回读会同步 GPU，仅能说明这些观测帧，不能排除同步对驱动行为的影响 |
| 仅改屏上 EGL 选择，优先不透明 RGB888 配置 | 两次正式时长 Egypt completed，第二次中段和后段系统截图的反射、HUD、半透明取消按钮均正常；该次没有开启场景 GL 回读 |
| 不透明窗口四色块 | 实际 alpha 位数 0，四个回读 alpha 均为 255；色值正确 |

原选择器只请求 RGB 至少 8 位、depth 至少 16 位，并取 `eglChooseConfig` 返回第一项。三星实际得到 config 17：RGB 8/8/8、alpha 8、depth 24、stencil 8、native visual 5（BGRA8888）。这不是明确要求的不透明窗口配置。对照路径得到 config 22：RGB 8/8/8、alpha 0、depth 24、stencil 8、native visual 511（厂商格式，不能假定等于 Android 的 RGBX_8888 常量 2）。

证据支持修正宿主的屏上 EGL 配置选择：带 alpha 的窗口路径在本机出现合成画面异常，改为不透明配置后观测异常消失。尚未定位厂商驱动内部的具体错误，不声称所有 SGX544 或所有 BGRA8888 窗口都有相同问题，也不把四张 GL 回读当作整段逐帧像素验证。

## 联网资料与采用范围

1. [Imagination 官方论坛：Samsung S4 / SGX544MP 的 RGBA4 花屏](https://forums.imgtec.com/t/powervr-sgx544mp-on-samsung-galaxy-s4-gt-i9500/1942)。2013 年案例使用 RGBA4 renderbuffer + glCopyTexImage2D，同 GPU 的 Lenovo K900 正常；官方 Tobias 初步怀疑驱动 stride，建议改用 RGBA8 renderbuffer，并索取 APK / PVRTrace。后续用户升级驱动仍失败，官方认为与特定设备集成有关，建议联系三星。它说明需要核对设备、驱动和格式，**不证明升级驱动一定修复，也不直接适用于 Egypt 的 RGB565 反射纹理**。本项目没有据此把反射 FBO 改成 RGBA8。
2. [Imagination 官方论坛：SGX544 设备异常案例](https://forums.imgtec.com/t/my-app-works-fine-on-emulator-and-all-test-devices-but-not-with-powervr-sgx544-powered-devices/1904)。该案例最终发现传入 GL 的数据已出错，并非 GPU 缺陷；因此本次实际检查 GL 回读和窗口输出，不只按 GPU 型号归因。
3. [Android 官方 GLSurfaceView 文档](https://developer.android.com/reference/android/opengl/GLSurfaceView#setEGLConfigChooser(int,int,int,int,int,int))：组件选择器检查精确的 RGBA 位数，depth/stencil 为最低值；[EGLConfigChooser 文档](https://developer.android.com/reference/android/opengl/GLSurfaceView.EGLConfigChooser)也建议枚举和检查候选项。只设置 `EGL_ALPHA_SIZE=0` 仍是下限，不能代替实际 alpha 位数检查。
4. [Android 官方 SurfaceHolder.setFormat](https://developer.android.com/reference/android/view/SurfaceHolder#setFormat(int))：Surface 默认 OPAQUE；[硬件加速文档](https://developer.android.com/develop/ui/views/graphics/hardware-accel)提供 Activity/View 的关闭手段。本机已经验证关闭 Activity 2D 加速无效，正式实现保留原配置。

## 产品修复

`JavaEglContext` 的屏上选择策略为 `opaque-rgb888-v1`：枚举 ES2 window、depth 至少 16 位的候选，检查实际 RGB 为 8/8/8、alpha 为 0、samples 为 0。优先 Android RGBX_8888 / RGB_888 native visual，允许满足同等组件要求的厂商 visual；没有可用不透明配置则报告失败，不静默回退到问题 alpha 路径。选择时保留驱动在同等优先级内的顺序。

离屏仍使用原来的驱动首选 pbuffer 配置。生产交帧没有增加 glReadPixels 或 glFinish；Egypt/T-Rex 材质、RGB565 反射目标、ETC1、屏上完整窗口、离屏 1920×1080 和正式时长均未调整。GPU 工作进程在计时外记录实际 `egl_config`（policy/id/RGBA/depth/stencil/samples/native_visual_id），随结果返回并持久化。原生构建身份包含 Java 宿主，会自动改变；新旧构建成绩分别保留。

## 诊断与复现

以下命令先解锁并确认 MainActivity 真正前台，每次串行选一个入口。将序列号替换为目标设备；场景回读仅支持 API 11+。

```powershell
# 四色块、实际 EGL 配置与 GL 像素；显示约 20 秒供外部截图
adb -s cff030496503230 shell am instrument -w -e gpuSurfaceOnly true com.retromobilebench.test/com.retromobilebench.BackendInstrumentation
# 固定时间的实际场景 GL 回读：只做画质诊断
adb -s cff030496503230 shell am instrument -w -e gpuCaptureOnly true -e gpuTest gl_egypt com.retromobilebench.test/com.retromobilebench.BackendInstrumentation
# 无场景回读的正式时长，需在运行期间另拍系统截图
adb -s cff030496503230 shell am instrument -w -e gpuTest gl_egypt -e gpuDurationMs 0 com.retromobilebench.test/com.retromobilebench.BackendInstrumentation
```

`gpuSurfaceOnly` 保存 `files/gpu-surface-readback.png` / `gpu-surface-diagnostic.json`；`gpuCaptureOnly` 使用独立配置副本，上游固定资源及哈希不变，保存到 `files/gpu-scene-diagnostic/<test>/`。诊断开始先移除相同时间点的旧截图和旧报告，必须重新生成四张指定帧，避免用陈旧文件误报通过。报告标记 `diagnostic_only=true`、`score_eligible=false`；PNG 保留 GL RGB 并强制不透明，原 TGA 保留原始通道。含回读的速率不进入历史，不用于性能比较。

初始与对照截图位于忽略的 `app/build/reports/`：`i9260-egypt-before-overlayfix.png`（异常），`i9260-egypt-gl-012000.png` / `035000` / `065000` / `095000`（原配置 GL 回读），`i9260-egypt-opaque-mid.png` / `late.png`（不透明配置系统截图）。加载画面 `i9260-egypt-opaque-early.png` 不是场景画质证据。

## 最终构建验证

最终原生/宿主构建为 `r10e-gcc49-f7383420c0bf1e82654429f9`。覆盖安装 Debug 与测试 APK，保留历史；DisplayActivity 的硬件加速 manifest 已恢复，设备安装的是最终修复版。仅对三星 `cff030496503230` 实测，没有清数据、root 或更改系统性能设置。

最终选择 config 20：RGB 8/8/8、alpha 0、depth 24、stencil 8、samples 0、native visual 511；与前述临时对照 config 22 分开记录。两个正式场景均没有开启 `screenshot_frames` 或逐帧回读。以下速率只保留为本次观测，不与旧构建作性能优劣结论。

| 最终 APK 检查 | 实测结果 |
|---|---|
| `gpuSurfaceOnly` | PASS；实际 EGL 与宿主报告的 config ID 一致，RGB 已知值正确，四个 alpha 均为 255；诊断不计分 |
| Egypt 屏上正式 113 秒 | completed，实际测量 113005 ms，1453 帧 / 12.8578 fps，窗口交换 1454 次，HUD 更新 138 次，PID 11998；系统中段、后段截图的反射、HUD 与取消按钮均无原彩色噪声 |
| T-Rex 屏上正式 56 秒 | PASS，实际测量 56144 ms，312 帧 / 5.5571 fps，窗口交换 313 次，HUD 更新 96 次，PID 12804；系统截图实际看到场景、HUD 和取消按钮正常 |
| `displayOnly` | PASS；工作进程实际不透明 RGB888 配置断言、三次完整 Surface 创建/销毁、1280×720 屏上 GPU、SD 视频、runner Web 三轮与返回竖屏 |
| `lifecycleOnly` | PASS；取消保留首轮且无中位数，停止延迟 108 ms；GPU Home 中断、释放亮屏标志 |
| 构建与静态检查 | Debug / Release / 测试 APK、lintDebug / lintRelease PASS；11 个 ARM API 8 / x86 API 9 共享库的导入、SysV hash、依赖和 RPATH/TEXTREL 检查 PASS；`git diff --check` 通过 |

T-Rex 首次非等待式启动没有取得新报告，约 50 秒时截图仍是 Loading；导出的 `i9260-trex-fixed.json` 与此前 Egypt 报告完全相同，不能算 T-Rex 完成证据。等待式重跑明确输出 `PASS: official configured GPU duration`，随后按 test_id 与测量时长核对 `i9260-trex-final.json`。加载图 `i9260-trex-fixed.png` 不作为画质证据。

最终证据位于忽略的 `app/build/reports/`，构建清理会删除，需另行归档：

| 文件 / 证据 | SHA-256 |
|---|---|
| `i9260-egypt-fixed.json` | `431dcbe102bbf0a0889f61e38eb0ddc013fc9e306dd708eecff11495daa1ae61` |
| `i9260-trex-final.json` | `758a035e8828008199b142753c5499a0ffcce9bc94c78ba519a9ed380e8b0035` |
| `i9260-egypt-fixed-mid.png` | `b5a7c604d69c6a5dd79bf2f52f2b625a49696b552c4acd637269401d8e01383a` |
| `i9260-egypt-fixed-late.png` | `b16b283930403f4382a8784459324086a0fcbf961a6b27485f1af59d2905ca0b` |
| `i9260-trex-retry.png` | `b91ff1cc95815bf2d625aee3fc879c622aeaa0e148ad5a3eed9cbc9f803625ab` |
| Debug APK | `2d985d73f1f3b989f5744b3d1080ffe5b6c1e7d0b25f29b2b312b181681d1272` |
| Release 未签名 APK（未上真机） | `fc4ae980ce8172f1a9c429b0117ab981f6b5c48e78007ece7502a7a39357bb1e` |
| 测试 APK | `59df34f2a3ef059fbc6a529cc89d2d70921fa32f7311ebdcdf7dafc158e2ca3d` |

测试日志为 Studio 本会话 `shell-224`（构建与原生检查）、`shell-225`（四色块与 Egypt）、`shell-226`（全屏及生命周期）、`shell-227`（T-Rex 明确完成）。应用身份导出使用 `/system/bin/dd`，`/sdcard` 中转文件已删除。诊断输出保留在应用私有目录，不混入正式历史成绩。

本次确认 GT-I9260 上原彩色噪声在这些观测画面中消失。未重跑完整 15 阶段、其他 GPU 或 Release 真机，也没有逐帧黄金图像对比；不推断所有 SGX544 设备均已验收，旧异常成绩仍不用于排名。
