param(
    [string]$NdkDirectory = '.tools/android-ndk-r10e',
    [string]$LibraryDirectory = 'app/build/generated/legacyJniLibs',
    [string]$SdkDirectory = "$env:LOCALAPPDATA/Android/Sdk"
)
$ErrorActionPreference = 'Stop'
$reader = Join-Path $SdkDirectory 'ndk/23.2.8568313/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-readelf.exe'
if (-not (Test-Path $reader)) { throw "Missing ELF inspection tool: $reader" }

function Get-DefinedSymbols([string]$File) {
    $lines = & $reader --dyn-syms --wide $File
    if ($LASTEXITCODE -ne 0) { throw "Cannot inspect $File" }
    foreach ($line in $lines) {
        if ($line -match '\b(?:GLOBAL|WEAK)\s+DEFAULT\s+(?!UND\b)\S+\s+(\S+)\s*$') {
            ($Matches[1] -split '@')[0]
        }
    }
}
foreach ($abi in @('armeabi-v7a', 'x86')) {
    $api = if ($abi -eq 'x86') { 9 } else { 8 }
    $arch = if ($abi -eq 'x86') { 'x86' } else { 'arm' }
    $system = Join-Path $NdkDirectory "platforms/android-$api/arch-$arch/usr/lib"
    $allowed = @('liblog.so', 'libGLESv2.so', 'libm.so', 'libdl.so', 'libc.so')
    $exports = @{}
    foreach ($name in $allowed) {
        foreach ($symbol in (Get-DefinedSymbols (Join-Path $system $name))) { $exports[$symbol] = $true }
    }
    $names = if ($abi -eq 'x86') { @('librmb.so') } else { @('librmb.so', 'librmb_noneon.so') }
    foreach ($name in $names) {
        $library = Join-Path $LibraryDirectory "$abi/$name"
        $dynamic = & $reader -d --wide $library
        if ($LASTEXITCODE -ne 0) { throw "Cannot inspect $library" }
        if (-not ($dynamic -match '\(HASH\)')) { throw "Missing legacy SysV hash: $library" }
        if ($dynamic -match '\((?:RPATH|RUNPATH|TEXTREL)\)') { throw "Incompatible ELF tags: $library" }
        foreach ($line in $dynamic) {
            if ($line -match '\(NEEDED\).*\[(.+)\]' -and $Matches[1] -notin $allowed) {
                throw "Unexpected dynamic dependency $($Matches[1]): $library"
            }
        }
        $ownExports = @(Get-DefinedSymbols $library)
        if ('JNI_OnLoad' -notin $ownExports) { throw "Missing JNI initialization: $library" }
        $symbols = & $reader --dyn-syms --wide $library
        $imports = 0
        foreach ($line in $symbols) {
            # Undefined weak hooks are optional; every required import must exist in
            # the selected API's actual NDK stub exports, including libm/GLES2.
            if ($line -match '\bGLOBAL\s+DEFAULT\s+UND\s+(\S+)\s*$') {
                $symbol = ($Matches[1] -split '@')[0]
                if (-not $exports.ContainsKey($symbol)) { throw "Import unavailable at API ${api}: $symbol ($library)" }
                $imports++
            }
        }
        Write-Host "PASS $abi/$name : API $api, $imports required imports, system libraries only, SysV hash, JNI_OnLoad"
    }
}
