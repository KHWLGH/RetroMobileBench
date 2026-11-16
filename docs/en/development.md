# Development & Build

RetroMobileBench is a single-module Android application (`:app`) built purely with standard Java Framework `Activity`/`View` primitives, `org.json`, `SQLite`, and `Instrumentation`. Production code has zero dependencies on AndroidX, Jetpack Compose, Kotlin stdlib, or Google Play Services. For workload specifications, see [Benchmarks & Results](benchmarks.md); for validation entry points and device records, see [Validation & Limits](validation.md).

## Architecture & Lifecycle

```text
MainActivity → BenchmarkRunner → BenchmarkSuite.DEFAULT (12-stage fixed-suite-v1)
                                  ├─ JNI: CoreMark / SciMark 2.0 / Memory / Storage / SQLite
                                  ├─ WebClient   → :web portrait fullscreen WebView worker (Feed + HD Image Decode)
                                  ├─ MediaClient → :media disposable worker (7 clips × 3 paths)
                                  └─ GpuClient   → :gpu disposable worker (Egypt HD / T-Rex / Fill)
                     ↓
              ResultStore: session persistence & per-round checkpoints
```

### Execution & State Management

- **Single Worker Thread**: `BenchmarkRunner` executes serially on a single application-scoped worker thread holding only the `Application` context. `MainActivity` observes state updates cleanly across Activity recreations without re-triggering benchmarks.
- **Bilingual Localization & Policy Placement**: `I18n` manages `auto` / `zh` / `en` UI language modes. App screens, in-app reports, and `.csv` / `.txt` exports switch dynamically with the active language while keeping JSON schema keys and `fixed-suite-v1` identifiers invariant. The **More** tab presents a flat list containing Language switcher, Suite Protocol (including Result & Statistics Policy), Hardware Probe, Open-Source Projects, Open-Source Licenses (`licenses.txt` / `licenses_en.txt`), and About (with GitHub repository links).
- **Checkpoints & Recovery**: A unique session UUID is generated at launch and bundled assets are verified/unpacked. Each completed round is checkpointed to SQLite before UI progress notifications; full report formatting and JSON assembly occur only upon completion. Stale `running` sessions detected on startup are marked `interrupted` while preserving completed rounds.
- **Process Isolation & Cancellation**: Media, Web, and GPU workloads execute in isolated disposable `:media`, `:web`, and `:gpu` processes. The parent waits via event notifications (completion, cancellation, binder death, or timeout) without polling. Cancelling provides immediate feedback and blocks subsequent stages: CPU/sync I/O unwind at safe checkpoints while isolated workers are signalled and reaped.
- **Thermal & Battery Telemetry**: A single background-priority `HandlerThread` (`TemperatureTrace`) samples `ACTION_BATTERY_CHANGED` every 2 seconds for battery temperature, percentage, voltage, power state, and stage name, rendering dual-curve charts via `TelemetryCurveView`.
- **Cross-API File Export & Sharing**: `ReportExporter` produces lossless `.json`, UTF-8 BOM `.csv`, and `.txt` outputs; `ExportManager` and `ExportFileProvider` adapt across API levels (API 8–28 writes to `Download/RetroMobileBench/` with media scan, API 29+ writes via `MediaStore.Downloads`, API 19+ supports `ACTION_CREATE_DOCUMENT`, and sharing switches between `file://` and `content://` per API level).

### Windows & Fullscreen Transitions

- **Portrait Control & Scenario-Driven Fullscreen**: `MainActivity` and `WebBenchmarkActivity` lock to portrait fullscreen. Onscreen GPU and video playback stages launch dedicated landscape fullscreen Activities with an edge-to-edge render surface and a top-right translucent Cancel button, returning to portrait when done. API 19+ uses immersive mode; older versions hide title/status bars.
- **GPU Onscreen HUD**: Displays a top-left translucent overlay updating swap FPS and battery temperature approximately once per second (pushed via Messenger on API 11+ or updated locally in the worker window on API 8–10).
- **Foreground Lock & Interruption**: `FLAG_KEEP_SCREEN_ON` stays active while running. Aside from controlled transitions between `MainActivity` and fullscreen worker windows, backgrounding, screen lock, or `Surface` loss triggers interruption protection (Back/Cancel = `cancelled`, Home = `interrupted`).

## Platform Compatibility & Native Layer

Targets API 8+ (ARMv7) and API 9+ (x86) with `targetSdk = 23` (satisfying Android 14+ minimum installable target while preserving legacy behavior) and 32-bit native libraries only. Java 17 syntax is desugared via D8 while Lint `NewApi` and `InlinedApi` are enforced as fatal build errors.

### Native Libraries & Loading

| Directory / Scope | Library | Notes |
|---|---|---|
| `armeabi-v7a/` | `librmb.so` | NEON-enabled ARMv7 core library (CoreMark, SciMark 2.0, Memory, Storage, SQLite, statically linked GFXBench engine) |
| `armeabi-v7a/` | `librmb_noneon.so` | `VFPv3-D16` non-NEON core library for ARMv7 devices lacking NEON (e.g., Tegra 2) |
| `x86/` | `librmb.so` | Native x86 core library avoiding ARM binary translation |
| All ABIs | `librmbmedia.so`, `libavutil.so`, `libavcodec.so`, `libavformat.so` | Media JNI bridge and standalone scalar C FFmpeg shared libraries (assembly/SIMD disabled) |

- **Runtime NEON Detection**: Built against `gnustl_static`. Before calling `System.loadLibrary`, `NativeLoader` inspects the ABI and `/proc/cpuinfo` feature flags in Java, loading `librmb.so` only when NEON is present and `librmb_noneon.so` otherwise to prevent `SIGILL` during static C++ initialization.
- **Legacy Linker Compatibility**: Pre-Android 4.3 dynamic linkers do not auto-resolve DT_NEEDED dependencies inside app private directories, so media libraries load explicitly in topological order (`avutil` → `avcodec` → `avformat` → `rmbmedia`). Native code avoids `getauxval`, `posix_memalign`, `pthread_barrier`, and NDK `MediaCodec`.

## Third-Party Dependencies (7 Components)

All third-party sources are pinned to explicit revisions or archive hashes. See [Licenses & Distribution](licenses.md) for license terms and attributions.

| Component | Upstream Repository / Source | Pinned Version / Commit |
|---|---|---|
| CoreMark | https://github.com/eembc/coremark | 1.0 / `1f483d5b8316753a742cbf5590caf5bd0a4e4777` |
| SciMark | https://math.nist.gov/scimark2/scimark2c.zip | 2.0 C (SHA-256: `53d6bffc...210b81ff`) |
| tinymembench | https://github.com/ssvb/tinymembench | `a2cf6d7e382e3aea1eb39173174d9fa28cad15f3` |
| STREAM | https://github.com/jeffhammond/STREAM | `6703f7504a38a8da96b353cadafa64d3c2d7a2d3` |
| SQLite | https://www.sqlite.org/2026/sqlite-amalgamation-3530100.zip | 3.53.1 (SHA-256: `36ad6e7f...6827eae`) |
| GFXBench | https://github.com/Kishonti-Opensource/gfxbench | `89aa956f43836798d1eaaab8ec32d3dc8b02eead` |
| FFmpeg | https://github.com/FFmpeg/FFmpeg | 4.4.6 / `44b04492bfc83215e136f2a68783bff71d328692` |

SQLite and SciMark sources are vendored in-tree; the remaining 5 components use Git submodules. Initialize submodules and sparse GFXBench checkout:

```powershell
git submodule update --init third_party/coremark third_party/tinymembench third_party/stream third_party/ffmpeg

# If third_party/gfxbench is not initialized yet, sparse-checkout required directories:
git clone --filter=blob:none --no-checkout https://github.com/Kishonti-Opensource/gfxbench.git third_party/gfxbench
git -C third_party/gfxbench sparse-checkout init --cone
git -C third_party/gfxbench sparse-checkout set frameworks gfxbench40 3rdparty/zlib 3rdparty/libpng gfxbench-data40/data/gfx/common gfxbench-data40/data/gfx/fill gfxbench-data40/data/gfx/trex gfxbench-data40/data/gfx/egypt
git -C third_party/gfxbench checkout --detach 89aa956f43836798d1eaaab8ec32d3dc8b02eead
git submodule absorbgitdirs third_party/gfxbench
```

## Toolchain & Build Workflow

### Prerequisites

| Tool | Version / Purpose |
|---|---|
| Android Studio JBR, AGP 9.5.1, Gradle 9.7.0 | Java compilation & Android APK packaging |
| Android SDK 37 | Provides `compileSdk 37` (`minSdk 8`, `targetSdk 23`) |
| Android NDK r10e (GCC 4.9 / `gnustl_static`) | Compiles all production C/C++ native code (ARM API 8, x86 API 9) |
| CMake 3.18.1 / Ninja | Builds core native libraries & static dependencies |
| Android NDK r23c (23.2.8568313) | Used by AGP for symbol stripping, packaging & ELF inspection |
| Git Bash | Runs FFmpeg `configure` & `make` build scripts |

### Build Commands

```powershell
$env:JAVA_HOME = 'C:/Program Files/Android/Android Studio/jbr'
.\gradlew.bat :app:prepareLegacyNdk --console=plain
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain
```

### Build Outputs

| Artifact | Path |
|---|---|
| Debug APK | `app/build/outputs/apk/debug/app-debug.apk` |
| Instrumentation Test APK | `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` |
| Release Unsigned APK | `app/build/outputs/apk/release/app-release-unsigned.apk` |
| Generated Native Libraries | `app/build/generated/legacyJniLibs/<abi>/` |
| Matching Corresponding Source Archive | `app/build/distributions/RetroMobileBench-corresponding-source-fixed-suite-v1.zip` |

## Bundled Asset Packaging & Verification

- **GPU Scene Pack (`generateGpuPack`)**: Selects ETC1 textures and shaders for `gl_egypt_off`, `gl_egypt`, `gl_trex_off`, `gl_trex`, and `gl_fill_off`, generates a deterministic manifest with byte sizes and SHA-256 digests, and stores entries uncompressed (`STORED`) inside `gpu-data.zip`.
- **Media Corpus (`assets/media`)**: 7 fixed video clips indexed in [`manifest.json`](../../app/src/main/assets/media/manifest.json), SHA-256 verified before caching in private storage.
- **Web Feed & HD Image Corpus (`generateWebCorpus`)**: Generates `assets/web/manifest.json` locking sizes and SHA-256 digests for `index.html`, `bench.js`, and six 480p/720p/1080p JPEG/PNG test images, verified prior to WebView launch.
