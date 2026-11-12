<#
.SYNOPSIS
    Runs the GFXBench ES2 off-screen tests on a device or emulator over adb (M1/M2 check).

.DESCRIPTION
    1. Stages what the tests need into feasibility/out/gfxdata (once; later runs reuse it):
         config/                         gl_{alu,fill,driver,blending,trex,egypt}{,_off}.json
         data/gfx/shaders_40/            lowlevel/, shaders.20/ (ui.fs patched, docs/04 §4.7)
         data/gfx/{fill,common,trex,egypt}/
                                         from gfxbench-data40, ETC1 textures only and without
                                         common/ref_* (quality-match reference images)
       The research clone is a blob:none partial clone, so the data blobs are fetched in
       batches of 200 first (about 180 MB for all four directories).
    2. Picks the gfxprobe build that fits the device: x86, armeabi-v7a, or
       armeabi-v7a-noneon when /proc/cpuinfo has no "neon" (Tegra 2).
    3. Pushes gfxprobe, configs and data to /data/local/tmp/rmb (adb push --sync, so reruns
       only copy what changed), runs each test at 1920x1080 off-screen, pulls the results
       into feasibility/out/results/<model>-<variant>-<time>/ and prints a summary.

    Build gfxprobe first with scripts/feasibility-gfxbench-es2.ps1
    (-Abi x86, -Abi armeabi-v7a, -Abi armeabi-v7a -NoNeon).

.PARAMETER Serial
    adb device serial. Optional when exactly one device is connected.

.PARAMETER Variant
    auto (default), x86, armeabi-v7a or armeabi-v7a-noneon. Forcing armeabi-v7a-noneon on a
    NEON device is a valid way to check that the no-NEON build runs.

.PARAMETER Tests
    Off-screen tests to run, in order. Default: the four low-level tests, T-Rex, Egypt.

.PARAMETER LowLevelOnly
    Skip T-Rex and Egypt, and do not stage or push their data (about 170 MB).

.PARAMETER PlayTimeMs
    Overrides each test's play_time. 0 (default) keeps the config: 30 s for low-level tests,
    56 s T-Rex, 113 s Egypt. Shorter runs are smoke tests; compare scores only at 0.

.PARAMETER GfxbenchDir
    GFXBench research clone. Default: %TEMP%\rmb-research\gfxbench.
#>
param(
    [string]$Serial,
    [ValidateSet('auto', 'x86', 'armeabi-v7a', 'armeabi-v7a-noneon')][string]$Variant = 'auto',
    [ValidateSet('gl_alu_off', 'gl_fill_off', 'gl_driver_off', 'gl_blending_off', 'gl_trex_off', 'gl_egypt_off')]
    [string[]]$Tests = @('gl_alu_off', 'gl_fill_off', 'gl_driver_off', 'gl_blending_off', 'gl_trex_off', 'gl_egypt_off'),
    [switch]$LowLevelOnly,
    [int]$PlayTimeMs = 0,
    [string]$GfxbenchDir = (Join-Path $env:TEMP 'rmb-research\gfxbench')
)

# Native tools (git, adb) print progress on stderr: judge them by exit code. Cmdlets that
# must not fail get -ErrorAction Stop.
$ErrorActionPreference = 'Continue'

$root = Split-Path -Parent $PSScriptRoot
$stage = Join-Path $root 'feasibility\out\gfxdata'
$remote = '/data/local/tmp/rmb'

if ($LowLevelOnly) { $Tests = @($Tests | Where-Object { $_ -notmatch 'trex|egypt' }) }
if ($Tests.Count -eq 0) { throw 'No tests to run' }
$dataDirs = @('fill', 'common')
foreach ($scene in 'trex', 'egypt') {
    if (@($Tests | Where-Object { $_ -match $scene }).Count -gt 0) { $dataDirs += $scene }
}
if (-not (Test-Path (Join-Path $GfxbenchDir 'gfxbench40'))) { throw "Not a GFXBench checkout: $GfxbenchDir" }

# --- 1. Stage ----------------------------------------------------------------------

function Export-GfxData([string]$dir) {
    $marker = Join-Path $stage ".staged-$dir"
    if (Test-Path $marker) { return }
    Write-Host "Staging data/gfx/$dir ..."
    $git = @('-C', $GfxbenchDir)
    $env:GIT_NO_LAZY_FETCH = '1'  # a missing blob must fail here, not start a one-by-one download

    $entries = @()
    foreach ($line in (& git @git ls-tree -r HEAD -- "gfxbench-data40/data/gfx/$dir")) {
        $meta, $path = $line -split "`t", 2
        if ($path -match '/images_(?!ETC1/)[^/]+/' -or $path -match '/common/ref_') { continue }
        $mode, $type, $sha = $meta -split ' '
        $entries += [pscustomobject]@{ Mode = $mode; Sha = $sha; Path = $path }
    }
    if ($entries.Count -eq 0) { throw "git ls-tree found nothing under gfxbench-data40/data/gfx/$dir" }

    $missing = @($entries.Sha | & git @git cat-file --batch-check |
        Where-Object { $_ -match ' missing$' } | ForEach-Object { ($_ -split ' ')[0].Trim() })
    if ($missing.Count -gt 0) {
        Write-Host ("  fetching {0} of {1} files from origin ..." -f $missing.Count, $entries.Count)
        Remove-Item Env:GIT_NO_LAZY_FETCH
        for ($i = 0; $i -lt $missing.Count; $i += 200) {
            $chunk = $missing[$i..([Math]::Min($i + 199, $missing.Count - 1))]
            & git @git -c fetch.negotiationAlgorithm=noop fetch origin --no-tags --no-write-fetch-head `
                --recurse-submodules=no --filter=blob:none @chunk 2>&1 | Out-Null
            if ($LASTEXITCODE -ne 0) { throw "git fetch of data blobs failed ($LASTEXITCODE)" }
        }
        $env:GIT_NO_LAZY_FETCH = '1'
    }

    # Check out through a throwaway index: the clone's own index and worktree stay untouched.
    $tmp = Join-Path $stage "export-$dir"
    $index = Join-Path $stage "export-$dir.index"
    Remove-Item -Recurse -Force $tmp, $index -ErrorAction SilentlyContinue
    $env:GIT_INDEX_FILE = $index
    for ($i = 0; $i -lt $entries.Count; $i += 100) {
        $a = @('update-index', '--add')
        foreach ($e in $entries[$i..([Math]::Min($i + 99, $entries.Count - 1))]) {
            $a += '--cacheinfo', ('{0},{1},{2}' -f $e.Mode, $e.Sha, $e.Path)
        }
        & git @git @a
        if ($LASTEXITCODE -ne 0) { throw "git update-index failed ($LASTEXITCODE)" }
    }
    & git @git checkout-index -a -f ('--prefix={0}/' -f ($tmp -replace '\\', '/'))
    $code = $LASTEXITCODE
    Remove-Item Env:GIT_INDEX_FILE
    Remove-Item Env:GIT_NO_LAZY_FETCH
    if ($code -ne 0) { throw "git checkout-index failed ($code)" }

    $dst = Join-Path $stage "data\gfx\$dir"
    Remove-Item -Recurse -Force $dst -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force (Split-Path -Parent $dst) | Out-Null
    Move-Item (Join-Path $tmp "gfxbench-data40\data\gfx\$dir") $dst -ErrorAction Stop
    Remove-Item -Recurse -Force $tmp, $index
    $files = @(Get-ChildItem $dst -Recurse -File)
    $info = '{0} files, {1:N1} MB' -f $files.Count, (($files | Measure-Object Length -Sum).Sum / 1MB)
    Set-Content -Path $marker -Value $info
    Write-Host "  data/gfx/$dir`: $info"
}

$cfgDir = Join-Path $stage 'config'
New-Item -ItemType Directory -Force $cfgDir | Out-Null
foreach ($t in 'alu', 'fill', 'driver', 'blending', 'trex', 'egypt') {
    foreach ($name in "gl_$t", "gl_${t}_off") {
        Copy-Item -Force (Join-Path $GfxbenchDir "gfxbench40\resources\gfxbench_gl\config\$name.json") $cfgDir -ErrorAction Stop
    }
}
foreach ($s in 'lowlevel', 'shaders.20') {
    $dst = Join-Path $stage "data\gfx\shaders_40\$s"
    New-Item -ItemType Directory -Force $dst | Out-Null
    Copy-Item -Recurse -Force (Join-Path $GfxbenchDir "gfxbench40\resources\data\gfx\shaders_40\$s\*") $dst -ErrorAction Stop
}
# docs/04 §4.7: ui.fs (gl_blending) hard-codes "precision highp float;", which fails to
# compile on GPUs without fragment-shader highp (Mali-400, Tegra 2/3). Use mediump there;
# GPUs that have highp still get highp, so their results do not change.
$uiFs = Join-Path $stage 'data\gfx\shaders_40\lowlevel\ui.fs'
$src = [IO.File]::ReadAllText($uiFs)
if ($src -notmatch 'GL_FRAGMENT_PRECISION_HIGH') {
    $nl = "`n"
    if ($src.Contains("`r`n")) { $nl = "`r`n" }
    $guarded = '#ifdef GL_FRAGMENT_PRECISION_HIGH', 'precision highp float;', '#else', 'precision mediump float;', '#endif' -join $nl
    $patched = $src.Replace('precision highp float;', $guarded)
    if ($patched -eq $src) { throw "$uiFs has no 'precision highp float;' line any more; recheck docs/04 §4.7" }
    [IO.File]::WriteAllText($uiFs, $patched)
}
foreach ($d in $dataDirs) { Export-GfxData $d }

# --- 2. Device ---------------------------------------------------------------------

$adbArgs = @()
if ($Serial) { $adbArgs = @('-s', $Serial) }
function Invoke-Adb { & adb @adbArgs @args }
function Get-Prop([string]$name) { (Invoke-Adb shell getprop $name | Out-String).Trim() }

$state = (Invoke-Adb get-state 2>$null | Out-String).Trim()
if ($state -ne 'device') { throw "No adb device ready (get-state: '$state'). Connect one device or pass -Serial." }

$model = Get-Prop ro.product.model
$release = Get-Prop ro.build.version.release
$sdkInt = Get-Prop ro.build.version.sdk
$abi = Get-Prop ro.product.cpu.abi
$cpuinfo = Invoke-Adb shell cat /proc/cpuinfo | Out-String
$hasNeon = $cpuinfo -match '(?m)^Features\s*:.*\b(neon|asimd)\b'
$hardware = ''
if ($cpuinfo -match '(?m)^Hardware\s*:\s*(.+)$') { $hardware = $Matches[1].Trim() }

if ($Variant -eq 'auto') {
    if ($abi -like 'x86*') { $Variant = 'x86' }
    elseif ($abi -eq 'armeabi-v7a' -or $abi -like 'arm64*') {
        $Variant = if ($hasNeon) { 'armeabi-v7a' } else { 'armeabi-v7a-noneon' }
    }
    else { throw "Device ABI '$abi' is not supported (needs armeabi-v7a or x86)" }
}
$bin = Join-Path $root "feasibility\out\$Variant\gfxprobe"
if (-not (Test-Path $bin)) { throw "Missing $bin. Build it with scripts/feasibility-gfxbench-es2.ps1 first." }

Write-Host ("Device: {0}, Android {1} (API {2}), {3}, NEON={4}, {5}" -f $model, $release, $sdkInt, $abi, $hasNeon, $hardware)
Write-Host "gfxprobe: $Variant"

function Push-Item([string]$local, [string]$remoteDir, [switch]$Sync) {
    $a = @('push')
    if ($Sync) { $a += '--sync' }
    # "$_" turns stderr ErrorRecords from Windows PowerShell into plain text.
    $msg = Invoke-Adb @a $local $remoteDir 2>&1 | ForEach-Object { "$_" } | Select-Object -Last 1
    if ($LASTEXITCODE -ne 0) { throw "adb push $local failed: $msg" }
    Write-Host "  $msg"
}

# The emulator's old adbd sometimes stalls on a pull; retry before calling it a failure.
function Receive-File([string]$remotePath, [string]$localPath) {
    $msg = ''
    for ($try = 1; $try -le 3; $try++) {
        $msg = Invoke-Adb pull $remotePath $localPath 2>&1 | ForEach-Object { "$_" } | Select-Object -Last 1
        if ($LASTEXITCODE -eq 0 -and (Test-Path $localPath)) { return '' }
        Start-Sleep -Seconds 2
    }
    return "adb pull failed: $msg"
}

Write-Host 'Pushing gfxprobe, configs and data ...'
# Remote parents must exist, otherwise "adb push dir parent/" renames dir to parent.
Invoke-Adb shell "mkdir $remote; mkdir $remote/rw; mkdir $remote/data; mkdir $remote/data/gfx" | Out-Null
Push-Item $bin "$remote/gfxprobe"
Invoke-Adb shell "chmod 755 $remote/gfxprobe" | Out-Null
Push-Item $cfgDir "$remote/" -Sync
foreach ($d in @('shaders_40') + $dataDirs) { Push-Item (Join-Path $stage "data\gfx\$d") "$remote/data/gfx/" -Sync }

# --- 3. Run ------------------------------------------------------------------------

$outDir = Join-Path $root ('feasibility\out\results\{0}-{1}-{2}' -f ($model -replace '[^A-Za-z0-9._-]', '_'), $Variant, (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Force $outDir -ErrorAction Stop | Out-Null

$rows = @()
$gpu = ''
foreach ($t in $Tests) {
    $play = $PlayTimeMs
    if ($play -le 0) { $play = [int](Get-Content (Join-Path $cfgDir "$t.json") -Raw | ConvertFrom-Json).raw_config.play_time }
    Write-Host ('[{0}] {1}: {2} s + loading' -f (Get-Date -Format 'HH:mm:ss'), $t, [int]($play / 1000))

    # adb shell on Android 4.x does not return the remote exit code; echo it instead.
    $sh = "cd $remote && ./gfxprobe config/$t.json $remote/data/gfx/ $remote/rw/ 1920 1080 $PlayTimeMs > rw/$t.json 2> rw/$t.err; echo EXIT=`$?"
    $exitText = (Invoke-Adb shell $sh | Out-String).Trim()
    $json = Join-Path $outDir "$t.json"
    $err = Join-Path $outDir "$t.err"
    $pullError = Receive-File "$remote/rw/$t.json" $json
    Receive-File "$remote/rw/$t.err" $err | Out-Null

    $row = [ordered]@{
        test = $t; exit = ($exitText -replace '(?s).*EXIT=(\d+).*', '$1'); status = ''; score = ''; unit = ''
        fps = ''; frames = ''; load_ms = ''; peak_rss_mb = ''; note = ''
    }
    $raw = ''
    if (Test-Path $json) { $raw = [string](Get-Content $json -Raw) }
    if ($pullError) { $row.note = $pullError }
    elseif (-not $raw.Trim()) { $row.note = 'empty result (crashed?) - check adb logcat' }
    elseif ($raw.StartsWith('ERROR')) { $row.note = ($raw -split "`n")[0].Trim() }
    else {
        $r = ($raw | ConvertFrom-Json).results[0]
        $row.status = $r.status
        $row.score = [math]::Round([double]$r.score, 1)
        $row.unit = $r.unit
        $row.fps = [math]::Round([double]$r.gfx_result.fps, 2)
        $row.frames = $r.gfx_result.frame_count
        $row.load_ms = $r.load_time
        if ($r.error_string) { $row.note = $r.error_string }
        if (-not $gpu) { $gpu = '{0} / {1} / {2}' -f $r.gfx_result.vendor, $r.gfx_result.renderer, $r.gfx_result.graphics_version }
    }
    if ((Test-Path $err) -and ([string](Get-Content $err -Raw) -match 'VmHWM:\s+(\d+)')) {
        $row.peak_rss_mb = [math]::Round([int]$Matches[1] / 1024, 1)
    }
    $rows += [pscustomobject]$row
}

Write-Host ($rows | Format-Table -AutoSize | Out-String)
$rows | Export-Csv -NoTypeInformation -Encoding UTF8 (Join-Path $outDir 'summary.csv')
$playText = 'config default'
if ($PlayTimeMs -gt 0) { $playText = "$PlayTimeMs ms" }
@(
    "model: $model", "android: $release (API $sdkInt)", "abi: $abi", "neon: $hasNeon", "hardware: $hardware",
    "gfxprobe: $Variant", "gpu: $gpu", "play_time: $playText", "resolution: 1920x1080 off-screen"
) | Set-Content -Encoding UTF8 (Join-Path $outDir 'device.txt')
Write-Host "GPU: $gpu"
Write-Host "Results: $outDir"
exit @($rows | Where-Object { $_.status -ne 'OK' }).Count
