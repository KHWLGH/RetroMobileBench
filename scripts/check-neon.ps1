<#
.SYNOPSIS
Checks ARMv7 ELF code for instructions requiring NEON or more than VFPv3-D16.
.DESCRIPTION
Compares llvm-objdump with NEON enabled/disabled. This is a static check, not a
replacement for running on a non-NEON device. Both ARM and Thumb views are used.
The linker may omit mapping symbols for its Thumb-to-ARM interworking veneers.
Their ARM instructions/literal can then look like Thumb NEON. Only the exact
12-byte bx pc / nop / ldr ip,[pc] / add pc,ip,pc veneer signature, plus its
4-byte address literal, is excluded. No source functions are whitelisted.
.PARAMETER Path
Path to an unstripped ARMv7 .so or .o.
.PARAMETER ExpectNeon
Pass $false for a library that must work on VFPv3-D16-only hardware (Tegra 2).
#>
param(
    [Parameter(Mandatory = $true)][string]$Path,
    [Nullable[bool]]$ExpectNeon = $null
)
$ErrorActionPreference = 'Stop'
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { "$env:LOCALAPPDATA/Android/Sdk" }
$objdump = Join-Path $sdk 'ndk\23.2.8568313\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-objdump.exe'
if (-not (Test-Path $objdump)) { throw "Missing $objdump" }
if (-not (Test-Path $Path)) { throw "Missing $Path" }

# Read ELF32 little-endian executable section bounds to map linker veneer bytes
# to virtual addresses. ET_REL objects have no linker veneers and are not masked.
$veneerAddresses = New-Object 'System.Collections.Generic.HashSet[long]'
$elf = [IO.File]::ReadAllBytes((Resolve-Path $Path).Path)
if ($elf.Length -lt 52 -or $elf[0] -ne 127 -or $elf[1] -ne 69 -or $elf[2] -ne 76 -or $elf[3] -ne 70 -or $elf[4] -ne 1 -or $elf[5] -ne 1 -or [BitConverter]::ToUInt16($elf, 18) -ne 40) { throw 'Expected a little-endian ARM ELF32 file' }
$veneerCount = 0
if ([BitConverter]::ToUInt16($elf, 16) -eq 3) {
    $sectionTable = [BitConverter]::ToUInt32($elf, 32)
    $sectionStride = [BitConverter]::ToUInt16($elf, 46)
    $sectionCount = [BitConverter]::ToUInt16($elf, 48)
    if ($sectionStride -lt 40 -or $sectionTable + [long]$sectionStride * $sectionCount -gt $elf.Length) { throw 'Invalid ELF section table' }
    $executable = @()
    for ($i = 0; $i -lt $sectionCount; $i++) {
        $header = $sectionTable + $i * $sectionStride
        if (([BitConverter]::ToUInt32($elf, $header + 8) -band 4) -eq 0) { continue }
        $offset = [BitConverter]::ToUInt32($elf, $header + 16)
        $size = [BitConverter]::ToUInt32($elf, $header + 20)
        if ([long]$offset + $size -gt $elf.Length) { throw 'Invalid ELF executable section' }
        $executable += @{ Offset = [long]$offset; Size = [long]$size; Address = [long][BitConverter]::ToUInt32($elf, $header + 12) }
    }
    $hex = [BitConverter]::ToString($elf)
    $signature = '78-47-C0-46-00-C0-9F-E5-0F-F0-8C-E0-'
    $position = $hex.IndexOf($signature, [StringComparison]::Ordinal)
    while ($position -ge 0) {
        $fileOffset = [long]($position / 3)
        foreach ($section in $executable) {
            $address = $section.Address + $fileOffset - $section.Offset
            if ($fileOffset -ge $section.Offset -and $fileOffset + 16 -le $section.Offset + $section.Size -and ($address % 4) -eq 0) {
                for ($j = 0; $j -lt 16; $j++) { [void]$veneerAddresses.Add($address + $j) }
                $veneerCount++
            }
        }
        $position = $hex.IndexOf($signature, $position + $signature.Length, [StringComparison]::Ordinal)
    }
}

# Compare actual vector/FP instructions at their enabled-decode addresses. Counting
# unknown bytes globally is unreliable: a rejected 32-bit instruction can change
# subsequent byte alignment and spill an extra unknown past a veneer boundary.
$total = 0
foreach ($triple in 'thumbv7-linux-androideabi', 'armv7-linux-androideabi') {
    $baseline = @{}
    & $objdump -d "--triple=$triple" '--mattr=-neon,+vfp3d16' $Path | ForEach-Object {
        if ($_ -match '^\s*([0-9a-fA-F]+):') { $baseline[$matches[1]] = $_.Trim() }
    }
    if ($LASTEXITCODE -ne 0) { throw "Disassembly failed: $Path" }
    & $objdump -d "--triple=$triple" '--mattr=+neon' $Path | ForEach-Object {
        if ($_ -match '^\s*([0-9a-fA-F]+):\s+(?:[0-9a-fA-F]{2}\s+)+v[a-zA-Z0-9]+(?:\.[a-zA-Z0-9]+)*\s') {
            $key = $matches[1]
            $address = [Convert]::ToInt64($key, 16)
            if (-not $veneerAddresses.Contains($address) -and $baseline[$key] -ne $_.Trim()) { $total++ }
        }
    }
    if ($LASTEXITCODE -ne 0) { throw "Disassembly failed: $Path" }
}
$name = Split-Path -Leaf $Path
Write-Host ("{0}: NEON/VFP-D32 decode differences (ARM+Thumb) = {1}; verified interworking veneers = {2}" -f $name, $total, $veneerCount)
if ($null -ne $ExpectNeon) {
    if ($ExpectNeon -and $total -eq 0) { throw "$name was expected to contain NEON code but none was detected" }
    if (-not $ExpectNeon -and $total -gt 0) { throw "$name contains instructions requiring inspection for non-NEON hardware" }
}
