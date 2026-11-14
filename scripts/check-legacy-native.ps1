param(
    [string]$NdkDirectory = '.tools/android-ndk-r10e',
    [string]$LibraryDirectory = 'app/build/generated/legacyJniLibs',
    [string]$SdkDirectory = "$env:LOCALAPPDATA/Android/Sdk"
)
$ErrorActionPreference = 'Stop'
$reader = Join-Path $SdkDirectory 'ndk/23.2.8568313/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-readelf.exe'
function Get-DefinedSymbols([string]$File) {
    $lines = & $reader --dyn-syms --wide $File
    if ($LASTEXITCODE -ne 0) { throw "Cannot inspect $File" }
    foreach ($line in $lines) {
        if ($line -match '\b(?:GLOBAL|WEAK)\s+DEFAULT\s+(?!UND\b)\S+\s+(\S+)\s*$') { ($Matches[1] -split '@')[0] }
    }
}
foreach ($abi in @('armeabi-v7a', 'x86')) {
    $api = if ($abi -eq 'x86') { 9 } else { 8 }
    $arch = if ($abi -eq 'x86') { 'x86' } else { 'arm' }
    $system = Join-Path $NdkDirectory "platforms/android-$api/arch-$arch/usr/lib"
    $allowedSystem = @('liblog.so','libGLESv2.so','libm.so','libdl.so','libc.so')
    $media = @('libavutil.so','libavcodec.so','libavformat.so','librmbmedia.so')
    $names = @(if ($abi -eq 'x86') { 'librmb.so' } else { 'librmb.so'; 'librmb_noneon.so' }) + $media
    $systemExports = @{}
    foreach ($name in $allowedSystem) { foreach ($symbol in (Get-DefinedSymbols (Join-Path $system $name))) { $systemExports[$symbol] = $true } }
    $libraryExports = @{}
    foreach ($name in $names) { $libraryExports[$name] = @(Get-DefinedSymbols (Join-Path $LibraryDirectory "$abi/$name")) }
    foreach ($name in $names) {
        $library = Join-Path $LibraryDirectory "$abi/$name"
        $dynamic = & $reader -d --wide $library
        if ($LASTEXITCODE -ne 0) { throw "Cannot inspect $library" }
        if (-not ($dynamic -match '\(HASH\)')) { throw "Missing legacy SysV hash: $library" }
        if ($dynamic -match '\((?:RPATH|RUNPATH|TEXTREL)\)') { throw "Incompatible ELF tags: $library" }
        if ($name -like 'librmb*' -and $name -ne 'librmbmedia.so' -and 'JNI_OnLoad' -notin $libraryExports[$name]) { throw "Missing JNI initialization: $library" }
        $exports = @{}; foreach ($key in $systemExports.Keys) { $exports[$key] = $true }
        foreach ($line in $dynamic) {
            if ($line -match '\(NEEDED\).*\[(.+)\]') {
                $dependency = $Matches[1]
                if ($dependency -notin $allowedSystem -and $dependency -notin $media) { throw "Unexpected dependency ${dependency}: $library" }
                if ($dependency -in $media) { foreach ($symbol in $libraryExports[$dependency]) { $exports[$symbol] = $true } }
            }
        }
        $imports = 0
        foreach ($line in (& $reader --dyn-syms --wide $library)) {
            if ($line -match '\bGLOBAL\s+DEFAULT\s+UND\s+(\S+)\s*$') {
                $symbol = ($Matches[1] -split '@')[0]
                if (-not $exports.ContainsKey($symbol)) { throw "Import unavailable at API ${api}: $symbol ($library)" }
                $imports++
            }
        }
        Write-Host "PASS $abi/$name : API $api imports=$imports; SysV hash; no RPATH/TEXTREL; only checked system/bundled dependencies"
    }
}
