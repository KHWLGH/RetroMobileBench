<#
.SYNOPSIS
    M1 feasibility build: compiles GFXBench's OpenGL ES 2.0 code path with NDK r23c at API 16.

.DESCRIPTION
    Configures feasibility/gfxbench-es2 with the NDK's CMake toolchain and builds it with
    Ninja. Output goes to feasibility/out/<abi> (or feasibility/out/armeabi-v7a-noneon with
    -NoNeon). The full compiler log is written to build.log in that directory so failures
    can be grouped and counted.

    Targets: libgfxbench_es2_probe.so (JNI) and gfxprobe (adb shell runner).

.PARAMETER GfxbenchDir
    GFXBench checkout (sparse is fine: frameworks, gfxbench40, 3rdparty/zlib, 3rdparty/libpng).
    Default: %TEMP%\rmb-research\gfxbench (the research clone).

.PARAMETER Abi
    armeabi-v7a (default) or x86.

.PARAMETER NoNeon
    armeabi-v7a only: build for CPUs without NEON (Tegra 2), with -mfpu=vfpv3-d16.

.PARAMETER KeepGoing
    Pass -k 0 to Ninja so every failing file is reported, not just the first one.
#>
param(
    [string]$GfxbenchDir = (Join-Path $env:TEMP 'rmb-research\gfxbench'),
    [ValidateSet('armeabi-v7a', 'x86')][string]$Abi = 'armeabi-v7a',
    [switch]$NoNeon,
    [switch]$KeepGoing
)

$ErrorActionPreference = 'Stop'

if ($NoNeon -and $Abi -ne 'armeabi-v7a') { throw '-NoNeon only applies to armeabi-v7a' }

$root = Split-Path -Parent $PSScriptRoot
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$ndk = Join-Path $sdk 'ndk\23.2.8568313'
$cmake = Join-Path $sdk 'cmake\3.18.1\bin\cmake.exe'
$ninja = Join-Path $sdk 'cmake\3.18.1\bin\ninja.exe'

foreach ($p in $ndk, $cmake, $ninja, $GfxbenchDir) {
    if (-not (Test-Path $p)) { throw "Missing $p" }
}

$variant = if ($NoNeon) { 'armeabi-v7a-noneon' } else { $Abi }
$out = Join-Path $root "feasibility\out\$variant"
New-Item -ItemType Directory -Force $out | Out-Null
$log = Join-Path $out 'build.log'

# Native tools write warnings to stderr; judge success by exit code, not by stderr output.
$ErrorActionPreference = 'Continue'

& $cmake -S (Join-Path $root 'feasibility\gfxbench-es2') -B $out -G Ninja `
    "-DCMAKE_MAKE_PROGRAM=$ninja" `
    "-DCMAKE_TOOLCHAIN_FILE=$ndk\build\cmake\android.toolchain.cmake" `
    "-DANDROID_ABI=$Abi" `
    -DANDROID_PLATFORM=android-16 `
    -DANDROID_STL=c++_static `
    -DCMAKE_BUILD_TYPE=Release `
    "-DRMB_NO_NEON=$(if ($NoNeon) { 'ON' } else { 'OFF' })" `
    "-DGFXBENCH_DIR=$($GfxbenchDir -replace '\\','/')" 2>&1 | Out-File -Encoding utf8 (Join-Path $out 'configure.log')
if ($LASTEXITCODE -ne 0) { Get-Content (Join-Path $out 'configure.log') -Tail 30; throw "cmake configure failed ($LASTEXITCODE)" }

$ninjaArgs = @('-C', $out)
if ($KeepGoing) { $ninjaArgs += @('-k', '0') }
& $ninja @ninjaArgs 2>&1 | Out-File -Encoding utf8 $log
$code = $LASTEXITCODE

$failed = (Select-String -Path $log -Pattern '^FAILED:' | Measure-Object).Count
$errors = (Select-String -Path $log -Pattern ': error:' | Measure-Object).Count
Write-Host ("ninja exit={0}  failed steps={1}  error lines={2}  log={3}" -f $code, $failed, $errors, $log)
exit $code
