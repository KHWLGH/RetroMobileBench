# Validation & Limits

This document covers static verification scripts, instrumentation test entry points, historical real-device validation records, and legacy Android compatibility fixes. For workload specifications, see [Benchmarks & Results](benchmarks.md); for build instructions, see [Development & Build](development.md).

## Build & Static Checks

Run the following commands from the project root to build all variants and verify legacy ABI symbol compatibility and NEON isolation:

```powershell
$env:JAVA_HOME = 'C:/Program Files/Android/Android Studio/jbr'
.\gradlew.bat :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :app:lintDebug :app:lintRelease --console=plain
.\scripts\check-legacy-native.ps1
.\scripts\check-neon.ps1 -Path app/build/generated/legacyJniLibs/armeabi-v7a/librmb_noneon.so -ExpectNeon $false
```

- `check-legacy-native.ps1`: Verifies that all generated `.so` files import only API 8 (ARM) / API 9 (x86) system symbols, retain SysV hash tables required by legacy dynamic linkers, and contain no undeclared dependencies, RPATH, or TEXTREL sections.
- `check-neon.ps1`: Disassembles `librmb_noneon.so`, `librmbmedia.so`, and FFmpeg shared libraries to confirm zero NEON instructions (`-ExpectNeon $true` can verify `librmb.so`).

## Device Instrumentation Entry Points

With both Debug and AndroidTest APKs installed, screen unlocked, and app in foreground, invoke `adb shell am instrument` (replace `emulator-5554` with your target device serial):

```powershell
# Run default backend regression suite
adb -s emulator-5554 shell am instrument -w io.github.khwlgh.retromobilebench.test/com.retromobilebench.BackendInstrumentation

# Tap "Start Test" and run the complete 12-stage production flow
adb -s emulator-5554 shell am instrument -w -e production true io.github.khwlgh.retromobilebench.test/com.retromobilebench.BackendInstrumentation
```

Common targeted instrumentation flags (passed after `-w`):

| Flag(s) | Coverage |
|---|---|
| *(none)* | Core JNI bridges, CoreMark/SciMark 1T & MT, Memory/Triad, Storage, SQLite, summary/recovery, and base GPU checks |
| `-e production true` | Taps UI Start button and executes the full 12-stage production run (official GPU durations, fullscreen transitions, persistence) |
| `-e displayOnly true` | Portrait MainActivity, 5 s Egypt fullscreen + HUD, SD video playback, 3-round portrait Web test, and orientation transitions |
| `-e webOnly true` | Web portrait feed + HD image decode (`4 × 20 × 3`), portrait viewport assertions, process isolation, and persistence |
| `-e webOnly true -e webFaults true` | Adds negative fault injection (DOM/image mismatch, clock anomalies, crash, JS hang, timeout & cancellation recovery) |
| `-e lifecycleOnly true` | Cancel after round-1 checkpoint, GPU background interruption, wake-lock release, and API 8–15 Back/Home handling |
| `-e interferenceOnly true` | Concurrent event waits, cancel races, stale callback isolation, and shared thermal/battery telemetry cleanup |
| `-e uiOnly true` | Bilingual (zh/en) 320dp narrow-screen layout & ellipsis checks, dual-curve telemetry rendering, virtualized reader perf, and locale-aware JSON/CSV/TXT exports |
| `-e fixedOnly true` | 12-stage suite topology, multi-thread SciMark, session recovery, and 7-clip 3-path video matrix |
| `-e mediaOnly true` | Strict API 16+ media validation requiring all 21 paths (7 clips × 3 paths) to pass with EOS, frame count, and timestamp checks |
| `-e mediaTimestamps true` | API 16+ diagnostic comparing `MediaExtractor` timestamps and sample hashes against container metadata |
| `-e gpuOnly true -e fullGpu true` | GPU pack integrity, 4 scenes × 5 s quick checks, 60 s Fill run, cancel/timeout recovery, and EGL config checks |
| `-e gpuSurfaceOnly true` | API 11+ renders known color patches and reads back pixels to verify actual EGL channel depths |
| `-e gpuCaptureOnly true -e gpuTest gl_egypt` | API 11+ captures 4 deterministic animation timestamps as images (also supports `gl_trex`) for visual diagnostics |

## Historical Real-Device Verification & Fixes

Full-suite validation has been completed on Android 4.1 (API 16) retail devices including Xiaomi Mi 2A (Adreno 320) and Samsung Galaxy Premier GT-I9260 (PowerVR SGX544), resolving Binder death-notification races, TI OMX decoder buffer/B-frame timestamp issues, and SGX544 opaque onscreen EGL selection (`RGB888, alpha = 0`). Archived raw evidence is stored in [`docs/evidence/2026-11-15/`](../evidence/2026-11-15/).
