# 05 构建与工具链

2026-11-14 更新，当前基线 API8 ARMv7 / API9 x86。旧的“全部用r23c编译、minSdk16”方案已被替代。

| 工具 | 用途 |
|---|---|
| AGP9.5.1 / Gradle9.7.0 / Studio JBR | Android打包与Java编译 |
| SDK37，targetSdk23，minSdk8 | API编译与直接分发策略 |
| NDK r10e / GCC4.9 / gnustl_static | 所有原生计算和GPU/媒体代码的实际编译 |
| CMake3.18.1 / Ninja | 主原生目标与静态依赖构建 |
| NDK r23c | 现代打包/ELF与指令检查，不替代r10e |
| Git Bash / r10e make | FFmpeg configure/make |

1. 按 [固定依赖](../third_party/README.md) 初始化源码及两套场景数据。
2. 安装SDK、CMake3.18.1、r23c；r10e放`.tools/android-ndk-r10e`（`prepareLegacyNdk`可准备官方哈希固定归档）。
3. 运行：

```powershell
$env:JAVA_HOME = 'C:/Program Files/Android/Android Studio/jbr'
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain
.\scripts\check-legacy-native.ps1
.\scripts\check-neon.ps1 -Path app/build/generated/legacyJniLibs/armeabi-v7a/librmb_noneon.so -ExpectNeon $false
.\gradlew.bat :app:correspondingSource
```

默认完整离线APK，不用传gpuData=full。Git Bash路径可通过`-PgitBash=<path>`指定，r10e主目标可通过`-PlegacyNdkDir=<path>`指定；FFmpeg脚本目前固定使用`.tools/android-ndk-r10e`，自定义主工具链时也需在该位置准备相同版本。脚本依赖下载不属于设备运行流程。

`buildFfmpeg`构建API8 ARMv7标量/API9 x86的avutil、avcodec、avformat共享库；`buildLegacyNative`使用相同旧工具链构建主库及媒体JNI，再复制FFmpeg产物到生成的JNI目录。生成资源任务冻结路径/哈希，打包完整场景。已准备依赖后，构建不联网取第三方源码。

主库三套：ARMv7 librmb.so、ARMv7 librmb_noneon.so、x86 librmb.so。每个ABI另有librmbmedia.so与三份FFmpeg共享库；ARM媒体全标量，对NEON/无NEON使用同一实现。GFXBench静态依赖仍随主库分别构建，动态C++运行时不进入包。

Java17由D8脱糖，无AndroidX/Kotlin标准库。新Java语法不代表新系统API可用；Lint的NewApi/InlinedApi必须通过。保持v1签名以兼容老安卓；Release分发还需要发布签名。

开发诊断构建可使用`-PgpuData=diagnostic`，它不满足完整产品离线资源条件，不能作为正式包。旧feasibility脚本仅用于历史移植诊断；其原工具链输出不能替代当前App兼容验证。

对应源码ZIP必须与发布APK同时提供，详情见 [分发说明](media-distribution.md)。功能和真机验证分开记录于 [13](13-suite-implementation.md)。
