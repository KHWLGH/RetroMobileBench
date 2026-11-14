# 06 固定离线资源与自动准备

2026-11-14 更新。产品只分发包含完整GPU与媒体素材的离线包，不再有lite/full选择或导入入口。

GPU素材使用GFXBench固定提交中的ETC1子集，保留Egypt/T-Rex各屏上、离屏配置。删除画质参考图和产品不执行的底层配置；生成时添加ui.fs精度回退，子模块源码不改。两个场景仍为百MB量级，负载较轻的Egypt不意味着资源更小。

```powershell
.\gradlew.bat :app:generateGpuPack :app:assembleDebug
```

Gradle的`GpuPackTask`按路径排序、固定时间戳、生成SHA256清单和STORED ZIP，再压入APK。format2清单含固定source、ETC1、tests和files(path/size/sha256)。默认tests固定为`gl_egypt_off`、`gl_egypt`、`gl_trex_off`、`gl_trex`。

启动一键测试后自动检查私有目录中的已安装包是否对应内置清单，并在会话首次使用时流式复核文件大小/哈希。缺少、陈旧或损坏时从内置ZIP恢复。解包使用暂存目录，检查路径、重复项、数量、总大小、单文件大小和SHA256，最后sync清单并原子改名；失败/取消清理暂存并保留已提交包。资源准备有独立180秒截止时间。

空间要求同时考虑APK、解包后的资源及新包临时目录；提交前检查声明内容加16MiB预留。产品仅内部私有目录，不要求SD卡权限。旧包的兼容导入器保留用于诊断和回归，产品界面不暴露。

媒体固定文件在`app/src/main/assets/media`，清单包含源、编码参数和各文件SHA256。图片压缩数据/参考像素在计时前校验载入；视频校验后原子写入`files/media-v1`，已有文件也复核哈希。素材不从网络取得。PNG包含透明通道；JPEG参考由固定libjpeg-turbo工具生成，不能用其他解码器输出直接替换。

`generate-media.ps1`和`generate-jpeg-references.ps1`为素材配方，生成环境工具哈希写入清单。发布后重新生成必须审查内容并升级语料协议；默认构建不会重新编码。

GPU场景当前保留192MiB可用内存保护；分辨率/素材不能因不足而静默降低。准备失败写入会话告警，各阶段仍能独立记录明确原因。取消、缓存损坏、空间不足、暂存清理和真实峰值需通过运行验证；最新证据与局限见 [13](13-suite-implementation.md)。
