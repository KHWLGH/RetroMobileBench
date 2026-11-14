param([string]$Djpeg = '', [string]$Ffmpeg = '')
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
if (!$Djpeg) { $Djpeg = Join-Path $root '.tools/jpeg-host/bin/djpeg.exe' }
if (!$Ffmpeg) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead((Join-Path $root '.tools/ffmpeg-host.zip'))
    try { $folder = $zip.Entries[0].FullName.Split('/')[0] } finally { $zip.Dispose() }
    $Ffmpeg = Join-Path $root ".tools/ffmpeg-host/$folder/bin/ffmpeg.exe"
}
$ErrorActionPreference = 'Continue' # djpeg writes its version to stderr.
$version = (& $Djpeg -version 2>&1 | Out-String)
$ErrorActionPreference = 'Stop'
if ($version -notmatch '2\.1\.5\.1') { throw "Expected pinned libjpeg-turbo 2.1.5.1: $version" }
$out = Join-Path $root 'app/src/main/assets/media'
$manifestPath = Join-Path $out 'manifest.json'
$manifest = [IO.File]::ReadAllText($manifestPath) | ConvertFrom-Json
$previousSimd = $env:JSIMD_FORCENONE
try {
    $env:JSIMD_FORCENONE = '1'
    foreach ($image in $manifest.images) {
        if ($image.format -ne 'jpeg') { continue }
        $ppm = Join-Path $root '.tools/jpeg-reference.ppm'
        & $Djpeg -dct int -rgb -outfile $ppm (Join-Path $out $image.file)
        if ($LASTEXITCODE) { throw "djpeg failed: $($image.id)" }
        & $Ffmpeg -hide_banner -loglevel error -y -i $ppm -frames:v 1 -pix_fmt rgba -f rawvideo (Join-Path $out $image.reference)
        if ($LASTEXITCODE) { throw "RGBA packing failed: $($image.id)" }
        $image.reference_sha256 = (Get-FileHash (Join-Path $out $image.reference) -Algorithm SHA256).Hash.ToLowerInvariant()
        $image | Add-Member -Force NoteProperty reference_decoder 'libjpeg-turbo 2.1.5.1 djpeg, JSIMD_FORCENONE=1, -dct int -rgb; RGB to RGBA with opaque alpha; byte-exact'
        Write-Output "$($image.id) $($image.reference_sha256)"
    }
    $manifest | Add-Member -Force NoteProperty host_djpeg_sha256 (Get-FileHash $Djpeg -Algorithm SHA256).Hash.ToLowerInvariant()
    [IO.File]::WriteAllText($manifestPath, ($manifest | ConvertTo-Json -Depth 12), (New-Object Text.UTF8Encoding $false))
} finally { $env:JSIMD_FORCENONE = $previousSimd }
