# 验证与已知限制

本页汇总截至 **2026-11-15** 已记录的验证证据与复现入口。构建、静态检查、模拟器功能和真机性能分别判断；整理文档不代表重新执行以下设备测试。当前套件定义见 [测试与结果](benchmarks.md)，环境与安装见 [开发与构建](development.md)。

## 检查入口

所有命令从项目根目录运行。先准备依赖和两套工具链，再构建应用与测试 APK：

```powershell
$env:JAVA_HOME = 'C:/Program Files/Android/Android Studio/jbr'
.\gradlew.bat :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :app:lintDebug :app:lintRelease --console=plain
.\scripts\check-legacy-native.ps1
.\scripts\check-neon.ps1 -Path app/build/generated/legacyJniLibs/armeabi-v7a/librmb_noneon.so -ExpectNeon $false
```

兼容静态检查覆盖 API 8/9 导入、SysV hash、动态依赖、RPATH/TEXTREL。除无 NEON 主库外，ARM 的 `librmbmedia.so`、`libavutil.so`、`libavcodec.so` 和 `libavformat.so` 也需逐个以 `check-neon.ps1 -ExpectNeon $false` 筛查；NEON 主库作阳性对照。这些检查不能证明 ARM 真机加载或运行已经通过。

安装 Debug 与测试 APK，解锁设备并保持测试窗口前台。将命令中的 `emulator-5554` 换成实际序列号，每次选择一个检查并串行运行，避免其他负载影响：

```powershell
adb -s emulator-5554 shell am instrument -w com.retromobilebench.test/com.retromobilebench.BackendInstrumentation
```

专项检查在 `-w` 后加入对应参数：

| 参数 | 覆盖内容 |
|---|---|
| 无参数 | 真实 JNI、存储/SQLite、CoreMark/SciMark、压缩、内存/Triad、加密与图片、汇总/旧历史/恢复、保存失败、宿主干扰和 GPU 回归 |
| `-e displayOnly true` | 竖屏主界面、5 秒 Egypt 屏上交换缓冲区、SD 视频全屏、忙碌 runner 的 Web 三轮及中位数、各阶段返回竖屏；API 11+ 另检查独立 Surface 尺寸与重复创建/销毁；诊断时长不作正式成绩 |
| `-e webOnly true` | 无 JNI 依赖的 Web 四项 × 20 单元 × 三轮、实际视口、独立进程、身份隔离、持久化与旧会话 |
| `-e webOnly true -e webFaults true` | 上述正式三轮，加校验错误、时钟错误、保留部分、进程死亡、JS 卡死超时、取消、Home 与失败后重启；仅 Debug |
| `-e interferenceOnly true` | 事件等待、取消竞争、迟到回调、共享温度源、阶段窗口及关闭 |
| `-e lifecycleOnly true` | API 16+ 第一轮落盘后取消、轻量进度与 GPU 后台中断；API 8–15 检查旧系统播放/窗口的返回取消和 Home 中断 |
| `-e fixedOnly true` | 拓扑、加密已知向量/坏标签/8 工作线程、六图参考像素、会话恢复、保存异常与七段视频的软件/系统/播放矩阵 |
| `-e mediaTimestamps true` | API 16+ 只做时间线诊断：平台与容器时间、逐包 SHA-256、旧 Extractor 的 DTS/PTS 识别；不运行解码或生成性能成绩 |
| `-e mediaOnly true` | API 16+ 严格媒体专项：EOS/时间戳计数、Surface 连续获取/过期回调/取消等待、前台亮屏，以及七段软件/系统/播放共 21 路径全部完成；仅用于预期支持这些固定档位的设备 |
| `-e gpuOnly true -e fullGpu true` | 要求完整资源包；GPU 隔离进程与资源校验，Egypt/T-Rex 四模式各 5 秒诊断及正式 60 秒 Fill，取消、超时与重启 |
| `-e production true` | 真实开始按钮触发完整固定套件，检查顺序、三轮/单次、中位数、五项正式 GPU 时长、窗口参数与持久化 |

例如运行完整一键流程：

```powershell
adb -s emulator-5554 shell am instrument -w -e production true com.retromobilebench.test/com.retromobilebench.BackendInstrumentation
```

检查输出中的 PASS/FAIL 和逐路径状态，不只看 `adb shell` 退出码。`fixedOnly` 的通过条件包括七段软件解码完成及其他路径得到可解释的结果，不代表系统解码和播放全部成功。默认 GPU 回归含四场景模式短测和完整 60 秒 Fill；短测不能替代生产时长。

### 独立加密诊断

`tests/native/crypto_join_check.c` 直接包含实际加密包装层，在测试翻译单元中截获 pthread、计时和比较，延后最后一个线程的操作，检查输出验证是否发生在全部 join 之后；同时覆盖三种模式、已知向量、线程创建失败和取消清理，不加入 APK。

API 9 x86 构建、API 16 模拟器运行示例，PowerShell 的 `--%` 保留 C 宏引号：

```powershell
.\.tools\android-ndk-r10e\toolchains\x86-4.9\prebuilt\windows-x86_64\bin\i686-linux-android-gcc.exe --% --sysroot=.tools/android-ndk-r10e/platforms/android-9/arch-x86 -std=gnu99 -O2 -mstackrealign -DMBEDTLS_CONFIG_FILE=\"crypto_config.h\" -Inative/src -Ithird_party/mbedtls/include tests/native/crypto_join_check.c third_party/mbedtls/library/aes.c third_party/mbedtls/library/gcm.c third_party/mbedtls/library/cipher.c third_party/mbedtls/library/cipher_wrap.c third_party/mbedtls/library/sha256.c third_party/mbedtls/library/platform_util.c third_party/mbedtls/library/constant_time.c -o app/build/crypto-join-check -lm
adb -s emulator-5554 push app/build/crypto-join-check /data/local/tmp/rmb-crypto-join-check
adb -s emulator-5554 shell chmod 700 /data/local/tmp/rmb-crypto-join-check
adb -s emulator-5554 shell /data/local/tmp/rmb-crypto-join-check
```

## 全屏 / HUD 首批构建：小米 2A 真机验收（2026-11-15）

用户连接小米 2A 后，先本地提交功能版本 `6303812`，再覆盖安装当前 Debug / 测试 APK。设备为 `c0e4aa9d`、Android 4.1.1 / API 16、ARMv7 NEON / Adreno 320，保留应用数据和原有历史；本次只有这台真机在线。

| 当前原生构建 `r10e-gcc49-d093143d31fbc32cbdde01c4` | 实测结果 |
|---|---|
| `displayOnly` | PASS；主界面竖屏、1280×720 GPU 全屏与实际 HUD 更新、重复 Surface 创建/销毁、SD 播放、runner Web 三轮及竖屏返回 |
| `webOnly + webFaults` | PASS；`web-daily-v2` 四项 × 20 单元 × 三轮、中位数/独立进程/身份/持久化，错误校验、卡死超时、取消、Home 中断与失败后重启 |
| `lifecycleOnly` | PASS；取消保留首轮、无中位数，本次停止延迟 133 ms；GPU 切后台 interrupted 且释放亮屏 |
| 严格 `mediaOnly` | PASS，七段软件/系统/播放 21/21 completed，严格帧数/EOS 与 Surface 生命周期检查通过 |
| `production` | PASS，真实开始按钮的完整 15 阶段全部 completed，0 partial / failed / skipped；九个三轮阶段均有原始样本和中位数，五项正式 GPU 独立进程且未缩时长，视频亦 21/21 completed |
| `interferenceOnly` | PASS；事件等待、取消竞争、迟到回调、温度源、无效读数过滤、阶段窗口与清理 |

完整会话 `b39a02d2-a62d-456d-895c-37ead83246b8`，`wall_ms=1010097`（16 分 50.097 秒，已有资源缓存），数据库行 12 重新读取一致、分析副本 integrity_check=ok。接通电源，电池记录 25.4→25.5°C、采样峰值 25.6°C。截图确认全屏 Egypt / T-Rex 中 FPS（提交）与温度可见；正式两项 HUD 分别更新 122 / 74 次，离屏均为 none / 0 次。电池温度不是芯片结温，窗口提交不是实际屏幕显示 FPS。

完整原始 JSON、数据库哈希、正式 GPU 与各阶段结果见[匹配构建的 ARM 真机记录](validation-devices.md#web-v2--全屏--gpu-hud小米-2a-实测2026-11-15)。随后三星实测发现 Binder 清理竞态并修复，记录如下；小米这批结果仍归属于修复前 APK，未在新 APK 重跑。Release 真机、其他架构/系统与干净安装等缺口仍保留，不以本次结果替代其他设备或发布验收。

## 三星 GT-I9260：Binder 清理修复与完整复测（2026-11-15）

仅连接 `cff030496503230`、Android 4.1.2 / API 16、ARMv7 NEON / PowerVR SGX 544，覆盖安装并保留历史。首次同小米 APK 的全屏、Web、生命周期与严格媒体通过，但完整流程进入视频后宿主 `DeathRecipientList::find` 触发已删除 JNI 全局引用并崩溃；已保存五阶段，不能记为完整 PASS。移除一次性工作进程结束后多余的 `unlinkToDeath`，保留死亡通知、异步解绑、硬截止和回收失败保护，先回模拟器再进入真机复测。

| Binder 修复后 APK / 原生构建 `r10e-gcc49-8bfd6c4ebf061eb51f3bec46` | 实测结果 |
|---|---|
| API 16 x86 模拟器回归 | `displayOnly`、`webOnly + webFaults`、`lifecycleOnly`、`fixedOnly` PASS；后者七段软解完成，系统/播放仍有原有 `drain_output` 失败。生命周期测试改为限时等待实际 `onStop` 清除亮屏标志，未改产品实现 |
| 额外模拟器 GPU 专项 | 未完成；API 16、API 29 和显式 SwiftShader 尝试在 T-Rex 离屏阶段模拟器整体退出、ADB 消失，原因尚未定位，不记 PASS |
| 三星 `displayOnly` | PASS；1280×720 完整横向窗口、真实窗口交换和 HUD 更新、重复 Surface、视频/Web 与返回竖屏 |
| 三星 `webOnly + webFaults` | PASS；四项 × 20 × 三轮、独立 PID / 身份 / 中位数 / 持久化，错误计时、卡死、取消、Home 与重启 |
| 三星 `lifecycleOnly` | PASS；取消保留 1 轮、无中位数，停止延迟 116 ms；GPU 退后台 interrupted 并释放亮屏 |
| 三星严格 `mediaOnly` | PASS，21/21 completed；TI 各档及 Google VPX 后备、严格帧数/EOS 与 Surface 生命周期通过，保留 SEC 失败 attempts |
| 三星 `interferenceOnly` | PASS；事件等待/取消竞争、过期回调、温度源、阶段窗口与清理 |
| 三星完整 `production` | 流程/计时 PASS，15/15 completed，0 partial / failed / skipped；九项三轮与中位数、视频 21/21、五项正式 GPU 独立进程与持久化全部通过 |

完整会话 `da9e89ea-256d-42f5-aab1-2def9f7a4286`，`wall_ms=1229389`（20 分 29.389 秒，已有资源缓存），数据库行 12、integrity_check=ok；首轮崩溃的行 8 恢复为 interrupted，未与复测拼接。USB 供电，电池温度 29.8→35.8°C、采样峰值 35.8°C，不是结温。屏上 Egypt / T-Rex HUD 更新 138 / 102 次，离屏 none / 0；Egypt HUD 截图与最终竖屏结果页已确认。Web 宿主为 `web-host-v2-024d03561b8e7f678e773227`，资源未变，修复前后身份与成绩分别保留。

**画质缺口保留：** Egypt 截图的水面/透明叠加区域和取消按钮附近有彩色异常，尚未区分实屏、驱动与截图合成原因。未取得正确性像素基准，GPU 数值暂不用于设备排名或等价画质比较；完整流程 PASS 不等于画质验收通过。详见[三星完整原始证据与边界](validation-devices.md#web-v2--全屏--gpu-hud三星-gt-i9260-实测2026-11-15)。小米 Binder 修复后 APK、Release 与其他平台仍不在本轮三星结论内。

## GPU 屏上 FPS / 电池温度悬浮层：模拟器验证（2026-11-15）

在独立全屏窗口左上角增加半透明两行 HUD，显示最近约 1 秒的成功缓冲区交换速率和系统最新电池温度。FPS 标为“提交”，不是屏幕真实显示帧率；温度无有效读数时显示“不可用”。逐帧路径未增加计时或 IPC，跨进程窗口每秒推送一次，旧系统本地窗口直接更新；离屏不启动发布器。离开窗口时停止发布器并注销温度接收器。

| HUD 构建 / x86 虚拟设备 | 本次实际检查 |
|---|---|
| Android 10 / API 29（RMB_API29_x86） | Egypt 屏上正式 113 秒完成，6774 次成功窗口交换，HUD 更新 114 次；截图可见 FPS（提交）与电池温度 25.0 °C，Surface 保持 1280×768。`displayOnly` PASS，覆盖真实 HUD 更新、重复窗口、SD 视频、Web 三轮及返回竖屏；`interferenceOnly` PASS，含无效温度、温度来源和清理。Egypt 离屏 5 秒完成，`onscreen_hud=none`、`hud_updates=0` |
| Android 2.3.7 / API 10（Android2.3） | `displayOnly` PASS，旧 GPU 窗口收到真实 HUD 更新，SD 视频和 Web 三轮返回正常；T-Rex 屏上 5 秒完成、HUD 更新 10 次；`lifecycleOnly` PASS，覆盖旧 GPU/视频返回取消与 Home 中断 |

该批 Debug / Release / 测试 APK、lintDebug 与 lintRelease 均通过；上表当时仅使用模拟器，没有运行完整 15 阶段或所有 GPU 正式时长。之后同一产品构建的小米 2A 完整真机记录独立列于上节。模拟器温度与 FPS 只用于核对功能，不作为真实设备热表现或性能结论，也不把下节改版前专项视为 HUD 构建的补充验收。

单场景诊断可用 `-e gpuTest gl_egypt` 或 `-e gpuTest gl_trex`，默认 5000 ms；`-e gpuDurationMs 10000` 延长短测，`-e gpuDurationMs 0` 使用资源中的正式配置时长。非零诊断仅允许 1–10000 ms，不计作正式时长成绩。

## HUD 加入前：竖屏主界面 / 独立全屏改版验证（2026-11-15）

当前改版将主界面固定为竖屏，GPU 屏上、Web 和视频播放使用独立横向全屏窗口；取消按钮叠加显示，不占用渲染布局。Web 使用实际全屏视口与响应式排版，协议升级为 `web-daily-v2`；仍为四项 × 20 单元 × 三轮，旧 v1 成绩可读但不与 v2 混合。API 19+ 隐藏系统栏，旧系统保留平台必须显示的导航区域。

| HUD 加入前的 Debug 构建 / x86 虚拟设备 | 实际观察结果 |
|---|---|
| Android 2.3.7 / API 10（Android2.3） | `displayOnly` PASS：旧工作进程 GPU 窗口实际渲染 800×480、成功交换缓冲区、SD 全屏播放、忙碌 runner 的 Web 三轮及中位数、返回竖屏；`webOnly + webFaults` PASS；`lifecycleOnly` PASS，覆盖旧 GPU/视频返回取消与 Home 中断 |
| Android 4.1.2 / API 16（Android4.1） | `displayOnly` PASS：三次完整 Surface 创建/销毁与竖屏返回、1196×720 窗口与 Surface 等大、5 秒 Egypt 真实渲染、SD 全屏播放、忙碌 runner 的 Web 三轮及中位数；`webOnly + webFaults` PASS；`lifecycleOnly` PASS，首轮落盘后取消保留 1 轮，GPU 退后台中断并释放亮屏，观察停止延迟 91 ms |
| Android 10 / API 29（RMB_API29_x86） | `displayOnly` PASS：三次 Surface 创建/销毁与返回、1280×768 独立全屏窗口、5 秒 Egypt 真实渲染、SD 视频、忙碌 runner 的 Web 三轮及中位数；`webOnly + webFaults` PASS；实际截图确认 GPU 场景和 Web 文本内容铺满窗口、取消按钮叠加 |

Web 故障矩阵覆盖校验错误、非法时钟、后续失败保留已完成分项、旧 nonce、进程死亡、准备/测量 JS 卡死、父进程取消、Home 中断和失败后重启。以上均为模拟器功能证据，不作为物理设备性能、温升或浏览器排名。主界面截图已确认竖屏，方向切换等待横向尺寸和窗口焦点就绪，内部切换不被误判为用户退后台。

该批 Debug / Release / 测试 APK、lintDebug 和 lintRelease 已通过。当时未重跑完整 15 阶段、全部 GPU 正式时长、严格媒体 21 路矩阵、API 19 或物理设备；下节旧 v1 完整流程及更早真机证据不代表该全屏构建已完成这些验收。之后增加 HUD，并在用户连接小米 2A 后执行最终真机步骤，匹配的新记录独立列于本页前节。

## 全屏改版前：Web v1 / fixed-suite-v3 验证（2026-11-15）

以下保留新增 `web-daily-v1` 时的原始验证记录，使用固定 320×160 CSS px 小视口。固定四项 × 20 单元 × 三轮，每轮使用新的 `:web` 进程；均为全屏改版前 Debug 构建的虚拟设备功能证据，不能作为当前 v2 或物理设备速度、温升、浏览器排名的结论。

| 虚拟环境 | 正式负载与故障回归 |
|---|---|
| Android 2.3.7 / API 10 / x86（Android2.3） | PASS：四项三轮、不同 PID、实际 320×160 CSS px、三轮中位数、无 JNI 加载、持久化及故障矩阵 |
| Android 4.1.2 / API 16 / x86（Android4.1） | PASS：同上；另补宿主事件等待/取消竞争/迟到回调、温度源和清理专项 |
| Android 4.4.2 / API 19 / x86（RMB_API19_x86） | PASS：同上；修复该 WebView prompt 返回 `file:///` 安全源导致的启动超时，仍要求顶层 URL 精确匹配内置页面 |
| Android 10 / API 29 / x86（RMB_API29_x86） | PASS：同上；提供程序记录为 `com.android.webview:74.0.3729.185:373018515` |
| Android 2.2 / API 8 / ARM | **未验证**：已安装 `platforms/android-8` 旧镜像，但当前 avdmanager 对该平台创建 AVD 时抛出 `PlatformDetailsType` → `SysImgDetailsType` 类型转换错误；没有可运行的 API 8 设备证据 |

故障矩阵包括四项错误结果、零/倒退计时、后续分项失败时保留已完成结果、旧 nonce 回调、进程死亡、准备/正式阶段 JS 卡死、父进程超时与取消、Home 中断、失败后新进程重启。修改资源、宿主、引擎、窗口、时钟等任一比较身份后均不生成混合中位数；恢复 `running` 记录保留原始轮次并删除过期中位数。Debug 故障结果不能进入正式汇总。

本次 Web 宿主身份为 `web-host-v1-a053a06a046c21d066d3631f`，资源清单 SHA-256 为 `d7f1d70d3b4a3e400abb8bda4f2bb1140be83a2f44953ac0bcedfefca15e3619`。本地完整三轮原始 JSON 位于 `app/build/web-api10.json`、`web-api16.json`、`web-api19.json`、`web-api29.json`，均为生成的验证产物，不随源码提交。各版本分别保留实际缩放、硬件加速、UA/系统、可用提供程序及计时源，不跨设备汇总这些时间。

API 16 x86 真实开始按钮的完整 `fixed-suite-v3` 检查 **PASS**：15 阶段按序尝试，九个三轮阶段（含 Web 四项 × 20 × 3）全部完成并生成中位数，五项 GPU 以 113/113/56/56/60 秒正式时长全部完成，检查独立进程、窗口参数、温度、历史和整次持久化。结果为 **14 completed、视频 partial**，会话保持 `partial`；七段视频的三种路径均独立记录，不能把流程 PASS 写成媒体全部成功。本次有资源缓存，`wall_ms=805113`（约 13 分 25 秒），完整原始导出为 `app/build/production-api16-v3.json`。

另运行 API 16 宿主干扰与生命周期检查均 PASS：首轮落盘后取消保留 1 轮、不生成中位数，GPU 后台中断且释放亮屏标志；该次停止延迟 100 ms，不作为通用上限。`fixedOnly` 也 PASS，覆盖 15 阶段定义、加密已知向量/8 工作线程、六图参考像素、会话恢复与保存失败。其媒体复核中七段软件解码均完成；SD、HD Baseline、VP8 的系统/播放完成，High/FHD、H.263、MPEG-4 的系统或播放出现 `feed_input` / `drain_output` 失败，均保留为失败，不视为媒体严格专项通过。

最新 Debug、Release、测试 APK、lintDebug 和 lintRelease 已通过。11 个共享库的 API 8/9 导入、SysV hash、依赖与无 RPATH/TEXTREL 复核通过；无 NEON 主库及四个 ARM 媒体/FFmpeg 库的 NEON/VFP-D32 筛查均为 0，NEON 主库阳性对照为 13768。`git diff --check` 未发现空白错误。匹配 v3 对应源码 ZIP 已生成，23831 个文件通过 7-Zip 兼容工具的完整 CRC 检查；内容检查确认包含 Web HTML/JS、四个宿主类、Web 回归测试、生成器、固定语料锁、当前文档、原生诊断源与 FFmpeg 许可。生成通知已核对包含原创 Web 内容说明与既有完整许可/署名/库替换说明。

完成上述虚拟设备、构建和归档检查后才进入最终真机步骤；停止模拟器后 `adb devices -l` 列表为空，小米 2A 与三星 GT-I9260 均不在线，所以 **Web / v3 最终物理验收未执行**。本次没有运行真机负载；需两台设备连接、授权并解锁后，各执行 Web 专项、生命周期和真实开始按钮完整 15 阶段，再补充匹配构建的独立记录。下方既有 v2 真机记录不代表新 Web / v3 已通过。

## 既有 v2 验证结论

以下为新增 Web 之前的宿主开销优化回归，保留对当前兼容说明仍有效的旧系统证据。

| 环境 / 检查 | 已观察结果 |
|---|---|
| 构建 | Debug、Release、测试 APK、lintDebug 与 lintRelease 通过；Release 尚未使用发布密钥签名 |
| 原生静态检查 | 共 11 个共享库的 API 8/9 导入、SysV hash、依赖和无 RPATH/TEXTREL 检查通过；无 NEON 主库及四个 ARM 媒体/FFmpeg 库的 NEON/VFP-D32 筛查为 0，NEON 主库阳性对照有效 |
| API 16 / Android 4.1 x86 模拟器：后端 | 正式 CoreMark 双种子、SciMark、压缩、内存/Triad、存储/SQLite、加密、六图参考像素、保存失败导出、取消/恢复与 GPU 回归通过 |
| Triad 和汇总专项 | 1/2/3/4/8 线程含非整除分区、固定总工作集、MB/s 重算、实际创建数、取消重启通过；新旧内存协议、图片乱序身份匹配、错误像素/非法速度拒绝和旧 median 回退通过 |
| API 16：宿主干扰 | 事件等待/取消竞争/迟到回调、共享低优先级温度源及清理通过；独立加密 join 顺序诊断通过 |
| API 16：生命周期 | 第一轮检查点落盘后才通知已保存进度；取消保留 1 轮且不生成中位数；屏上 GPU 退后台记为中断。观测停止延迟 90 ms，不作为所有阶段的上限 |
| API 16：完整一键流程 | 14 阶段按序全部尝试，8 阶段三轮及中位数、5 项 GPU 的 113/113/56/56/60 秒完整时长、独立进程、渲染/窗口参数、温度和会话持久化通过 |
| API 10 / Android 2.3.7 x86 模拟器 | 宿主等待/温度专项通过；SD 实际播放约 4 秒，旧媒体/GPU 本地窗口、内部 Activity 切换、返回取消与 Home 中断通过；保留已完成软解结果且不生成正常汇总 |
| 小米 2A / Android 4.1.1 / API 16 ARM 真机 | 11-14 严格媒体专项 PASS，21/21；生命周期与 GPU 专项 PASS。修复前有效完整流程为 13 completed、视频 partial，五项正式 GPU 完成；最终修复后未重跑正式整套，详见[合并实机记录](validation-devices.md) |
| 三星 GT-I9260 / Android 4.1.2 / API 16 ARM 真机 | 11-15 严格媒体专项 PASS，21/21；修正 B 帧输入 DTS/PTS 后 High/FHD 四路由 TI 完成，VP8 后备窗口恢复通过。11-14 首版修复完整流程为 13 completed、视频 partial、五项正式 GPU 完成；最终构建未重跑正式整套。历史 Watchdog、17/21 失败、专项和产物证据统一见[合并实机记录](validation-devices.md) |
| 对应源码交付流程 | 既有 v2 源码 ZIP 生成并通过 7-Zip 完整 CRC 检查，包含 FFmpeg 源码/许可、项目和构建材料；重新分发必须为当前 APK 重新生成匹配归档 |

已记录的 API 16 x86 模拟器完整流程耗时 **792405 ms（约 13 分 12 秒）**，已有资源缓存，不包含干净安装后的首次解包。结果为 13 阶段 `completed`、视频 `partial`，整次保持 `partial`。这是模拟器功能流程耗时，不是宿主优化前后的性能 A/B，也不能用于真机排名。

既有 v2 打包记录中 Debug APK 约 160 MiB、对应源码 ZIP 约 492 MiB；这些是宿主优化前的历史产物大小，不作为当前包体、安装空间或运行峰值。分发以实际生成的 APK 和匹配源码为准。

### 媒体已知限制

API 16 模拟器的七段 FFmpeg 软件解码均完成，首帧 YUV 哈希通过。OMX.google 路径中，SD、HD Baseline、H.263、MPEG-4 和 VP8 的系统解码与播放完成，并在可读布局进行亮度抽查；High 720p / 1080p 的系统/播放路径在 `feed_input` 或 `drain_output` 出现 `IllegalStateException`。失败独立保留，不能把“矩阵检查通过”写成全部系统路径成功。

API 10 曾在 High 720p 软件路径观察到约 15063 ms 的父进程超时，系统高档位也返回解码错误；旧系统完整严格矩阵未获得全部通过的结论。MediaPlayer 播放成功的硬件类型仍为未确认。Surface 路径无法取得真实显示 FPS、画质回读或显示帧数时保持不可获得。

旧系统窗口检查曾因模拟器冷启动锁屏而出现 `INJECT_EVENTS` 错误，解锁后在未修改测试和产品代码的情况下重跑通过。复现前应先解锁，避免将按键被系统窗口拦截误判为应用生命周期错误。

## 真机验收与发布要求

小米 2A 已补充 API 16 ARM NEON 加载、命名系统解码及五项正式 GPU 的物理设备证据，但不是完整发布验收。当前仍需以下覆盖，设备条件可以重叠：

- API 8–15 ARM：安装、主库/媒体加载、资源准备、旧系统播放与窗口生命周期。
- API 16–18 带厂商解码器的 ARM：覆盖更多设备、厂商输出布局和故障回收；小米 2A 七段 21 路径专项已完成，仍不能将解码器名称或成功播放解释为硬件类型确认。
- 可同时调度 8 核的设备：真实创建/回收 8 个工作线程，记录在线变化，区分线程数和物理并行。
- 无 NEON ARMv7 与原生 x86 设备：实际加载与完整运行；静态指令检查不能替代。
- API 29+：硬件/软件属性隔离分支与系统声明；新系统安装策略需单独实测。

每次验收至少检查：干净安装与首次解包峰值、缓存修复/取消/空间不足、运行内存、GPU 驱动和片元精度、固定分辨率与正式时长、取消/后台/锁屏/Surface 丢失、系统媒体失败后继续执行、保存异常及重启恢复、起止电池温度和采样峰值。

不承诺每台老设备都能完成 1080p 或完整场景。高档位失败保留低档位成绩，不补零或生成综合分；模拟器和构建成功不能代替硬解、真实速度、峰值内存或温升结论。尚无物理设备的宿主开销优化前后 A/B 数据，不给出提分幅度或“开销为零”的结论。

发布前使用匹配版本构建并保留 v1 签名，同时提供对应源码和许可，操作见 [许可与分发](licenses.md)。

```powershell
.\gradlew.bat :app:generateBenchmarkNotices :app:correspondingSource --console=plain
git diff --check
```

核对生成通知包含完整原始许可、署名和库替换说明；源码 ZIP 应包含当前 README、docs 中的说明与实机记录、实际依赖源码及构建脚本，排除 `.git`、`.tools` 和 build 目录。不要将此前的归档检查结论直接作为新发布包已检查的证据。
