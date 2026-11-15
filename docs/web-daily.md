# Web 日常响应：web-daily-v2

v2 改为独立横向全屏窗口，主界面固定竖屏。四项工作单元、语料和三轮保持原负载量；视口及响应式排版改变，因此不与旧版 `web-daily-v1` 小窗口成绩混比。旧记录仍可查看。

此阶段是离线日常应用任务测试，使用设备的系统 WebView，不访问在线站点，不内置另一套浏览器引擎。它同时受到 JavaScript 引擎、DOM、样式与布局、WebView 设置和设备性能的影响，不能解释为纯 CPU 分数，也不是 Speedometer 官方成绩。

## 固定负载

四项各 20 个工作单元，每轮共 80 个正式单元，连续三轮共 240 个正式单元。每轮先对四项各预热、校验并复位一个单元。每轮使用新的 `:web` 一次性进程；轮间固定 1 秒。不得按设备速度动态减少负载或更换语料。

| 分项 | 一个单元 | 每轮正式工作量 |
|---|---|---|
| 页面构建 | 清空后创建 32 卡片，每卡 8 元素节点，应用样式并读取布局 | 640 卡片 / 5120 卡片元素 |
| 待办交互 | 添加 100 项，切换前 50 项；all / active / completed / all 四次过滤；删除全部 | 添加 2000、切换 1000、过滤 80、删除 2000 |
| 文本排版 | 24 段，每段 256 ASCII 字符；替换 48 个短语、突出 240 个关键词；折叠/展开前 12 段；恢复 | 替换 960、突出 4800、折叠/展开 240 对 |
| 数据展示 | 解析 1000 条 JSON，每条 8 字段；筛选 group 0–4 共 500 条；value 数值排序、id 打破平局；展示前 100 行 × 6 列，序列化筛选结果 | 解析 20000 条，展示 2000 行 / 12000 数据单元格 |

JSON 字段固定为 `id, group, value, price, enabled, name, description, weight`；name / description 分别为 32 / 96 个 ASCII 字符。十个分组等量。生成器 `web-corpus-v1` 使用种子 101010、32 位 LCG `(1664525*s+1013904223) mod 2^32`。记录字段生成顺序、值范围、文本和图像像素公式以 [生成配方](../app/web-assets.gradle.kts) 为准。

六张原创合成图为 4 JPEG + 2 PNG，全部 320×180。预加载成功、尺寸检查和文本准备在正式计时外；页面项不是冷加载或图像解码吞吐测试。生成资源清单包含每个文件的字节长度和 SHA-256，以及数据筛选/序列化的独立参考结果。启动每轮前用 Java 复核清单，JS 结果再核对元素数、状态、文本、完整排序 ID 和序列化长度/校验值。

## 窗口、计时与身份

独立横向全屏 worker Activity 内，WebView 的宽高与整个内容窗口一致，取消按钮叠加显示，不挤占视口。页面使用 `width=device-width` 和 100% 内容宽度，卡片固定两列并随窗口宽度伸展，表格占满视口；字体仍为 12 CSS px / 18 CSS px 行高、100% 文本缩放。实际 CSS 和物理视口均记入比较身份，测量中尺寸变化则中断；不缩减工作单元。

JSON 保留 `window_mode=fullscreen`、内容窗口宽高、实际 CSS/物理视口、DPR、密度、初始缩放请求、硬件加速实际状态、UA、系统 fingerprint 和可获得的 WebView 包版本；API 21 前记录系统捆绑身份；API 21–25 明示提供程序 API 不可获得并保留 UA/系统身份，不伪造包版本。另记录实际 WebView scale、CSS 到物理像素比例和 NORMAL 布局算法。v1 的固定 320×160 小视口记录继续可读，但不与 v2 汇总。

每项从第一个正式单元开始前计时，至第 20 个单元的应用任务和布局检查点完成为止；单元之间统一 `setTimeout(0)`，调度开销计入总时间。UI/状态计数等协议证据采集属于任务操作，完整正确性核对、跨进程传输和结果汇总在停止计时后进行。记录 20 单元总 ms 与平均 ms/单元，越低越好；不使用 `onPageFinished` 作为真实绘制完成，不报告 FPS、真实触摸、键盘/IME 或 input-to-photon 延迟。

优先 `performance.now`，否则 `Date.getTime`。在预热前探测可观察分辨率并记录；非法、倒退、非正时间及总量不足 20 个可观察计时刻度均拒绝，不自动加量。三轮必须完整且资源 SHA-256、Web 宿主构建、引擎、视口、缩放、硬件加速和时钟设置等身份相同，才产生三轮中位数。Web 不要求 JNI 成功加载，不附加伪造的 native 构建身份。不生成总体分数，缺项不补零。

## 时限与中断

父进程持有所有硬截止：每轮启动与预热 15 秒、每个正式分项 20 秒、整个 Web 阶段 330 秒（包含轮间间隔和准备）；异常进程回收额外最多 10 秒。JS 卡死时父进程仍可取消、超时并回收 `:web`。结果有唯一请求 nonce、阶段顺序和终态封口，过期消息不得写入后续请求。每个已完成分项立即送至父进程，保留在原始轮结果中；失败不把此前项目补零或冒充完整轮。

主界面到 Web 窗口仅放行一次有时限的内部切换；worker 退出前台即中断，返回或取消按钮记为取消。Web 失败且进程回收确认后允许继续 GPU；回收无法确认时停止后续负载并要求重启应用。

## 实施和验收顺序

1. 固定资源、四项真实负载及 `webOnly` 入口；校验错误不能产出正常成绩。
2. 隔离进程、取消/超时/崩溃、后台、迟到回调、重启、检查点及持久化。
3. 虚拟设备矩阵：API 10 x86、API 16 x86、API 19 x86、API 29 x86；尽量补 API 8 ARM。缺失环境必须保留未验证状态，API 10 不代替 API 8。
4. v3 顺序、报告、中位数隔离和 v1/v2 旧历史。
5. 模拟器真实开始按钮完整 15 阶段，五项 GPU 正式时长；Debug / Release / test APK / lint / 原生静态检查 / 通知 / 对应源码归档。
6. 仅以上步骤完成后才进行最终物理设备验收：小米 2A 与三星 GT-I9260，各运行 Web 专项、生命周期及完整 15 阶段。真机发现问题先回模拟器修复和回归，再重做最终真机步骤。

最后一步之前仅使用虚拟设备。既有媒体或 v2 真机证据不作为新 Web / v3 已通过证据。

复现入口：

```powershell
adb -s emulator-5554 shell am instrument -w -e webOnly true com.retromobilebench.test/com.retromobilebench.BackendInstrumentation
adb -s emulator-5554 shell am instrument -w -e webOnly true -e webFaults true com.retromobilebench.test/com.retromobilebench.BackendInstrumentation
```

`webFaults` 为 Debug 开发故障路径；不能作为正式成绩。实际验证记录见 [验证与已知限制](validation.md)。

## 调研依据与选择边界

本项目采用原创、离线、旧 DOM / ES3 语法的固定应用任务。v2 使用实际全屏视口，保留适合旧设备的固定数据量；没有复制 Speedometer 源码、框架或计分方式，不能套用其分数或横向比较。

- [Speedometer 3.1 说明](https://browserbench.org/Speedometer3.1/about.html)：待办、新闻、编辑等用户任务能同时覆盖 JS、DOM 和布局；其异步测量方式与本项目的布局检查点协议不同。
- [Speedometer 3.1 页面](https://browserbench.org/Speedometer3.1/)：建议至少 850×650 视口。本项目不要求旧设备具备该视口或现代浏览器特性，使用自己的固定任务和正确性协议，不冒称等价负载。
- [WebViewClient.onPageFinished](https://developer.android.com/reference/android/webkit/WebViewClient#onPageFinished(android.webkit.WebView,%20java.lang.String))：页面加载回调不保证下一帧反映当前 DOM，本协议不把该回调当绘制完成。
- [WebChromeClient.onJsPrompt](https://developer.android.com/reference/android/webkit/WebChromeClient#onJsPrompt(android.webkit.WebView,%20java.lang.String,%20java.lang.String,%20java.lang.String,%20android.webkit.JsPromptResult))：API 1 起可用；立即 confirm 并返回 true 抑制对话框，用于可信内置页的低频控制/结果通道，IPC 在正式任务计时外。
- [WebView.getCurrentWebViewPackage](https://developer.android.com/reference/android/webkit/WebView#getCurrentWebViewPackage()) 与 [WebSettings](https://developer.android.com/reference/android/webkit/WebSettings)：提供程序 API 从 26 开始，textZoom 从 14 开始；宿主按 API 隔离调用并记录旧系统身份和实际窗口条件。

上述官方资料核对日期为 2026-11-15。资源量、三轮、时限和可比身份以本页项目协议为准，外部资料不代表对本项目成绩的认证。
