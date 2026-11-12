<#
.SYNOPSIS
    Counts NEON instructions in an armeabi-v7a ELF file (.so or .o).

.DESCRIPTION
    Disassembles the file twice with llvm-objdump: once with NEON enabled and once with
    NEON disabled (VFPv3-D16 only, like Tegra 2). Instructions that only decode when NEON
    is enabled are NEON instructions. A library meant for Tegra 2 must have zero.

    The ARM build attributes are not used for this: the linker drops Tag_Advanced_SIMD_arch
    from the final .so, so they say nothing reliable about the code inside.

.PARAMETER Path
    ELF file to check.

.PARAMETER ExpectNeon
    If given, exit with an error when the result does not match (true = NEON expected).
#>
param(
    [Parameter(Mandatory = $true)][string]$Path,
    [Nullable[bool]]$ExpectNeon = $null
)

$ErrorActionPreference = 'Stop'

$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$objdump = Join-Path $sdk 'ndk\23.2.8568313\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-objdump.exe'
if (-not (Test-Path $objdump)) { throw "Missing $objdump" }
if (-not (Test-Path $Path)) { throw "Missing $Path" }

function Count-Unknown([string]$mattr, [string]$triple) {
    $n = 0
    & $objdump -d "--triple=$triple" "--mattr=$mattr" $Path | ForEach-Object { if ($_ -match '<unknown>') { $n++ } }
    return $n
}

$total = 0
foreach ($triple in 'thumbv7-linux-androideabi', 'armv7-linux-androideabi') {
    $withNeon = Count-Unknown '+neon' $triple
    $withoutNeon = Count-Unknown '-neon,+vfp3d16' $triple
    $total += [Math]::Max(0, $withoutNeon - $withNeon)
}

# Thumb and ARM decoding of the same bytes both run; report the larger, more conservative view.
$name = Split-Path -Leaf $Path
Write-Host ("{0}: NEON-only instructions found (Thumb+ARM decode) = {1}" -f $name, $total)

if ($null -ne $ExpectNeon) {
    if ($ExpectNeon -and $total -eq 0) { throw "$name was expected to contain NEON code but has none" }
    if (-not $ExpectNeon -and $total -gt 0) { throw "$name must not contain NEON code (Tegra 2 would crash with SIGILL)" }
}
