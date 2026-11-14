param([string]$Ffmpeg = '', [string]$Djpeg = '')
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
if (!$Ffmpeg) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead((Join-Path $root '.tools/ffmpeg-host.zip'))
    try { $folder = $zip.Entries[0].FullName.Split('/')[0] } finally { $zip.Dispose() }
    $Ffmpeg = Join-Path $root ".tools/ffmpeg-host/$folder/bin/ffmpeg.exe"
}
$source = Join-Path $root '.tools/sintel-trailer-1080p.mp4'
if (!(Test-Path $source)) { Invoke-WebRequest 'https://download.blender.org/durian/trailer/sintel_trailer-1080p.mp4' -OutFile $source }
$out = Join-Path $root 'app/src/main/assets/media'
[IO.Directory]::CreateDirectory($out) | Out-Null
function Encode([string[]]$Arguments) { & $Ffmpeg -hide_banner -loglevel error -y @Arguments; if ($LASTEXITCODE -ne 0) { throw "FFmpeg failed: $Arguments" } }
$videos = @(
    @{ id='h264-sd'; width=854; height=480; codec='libx264'; profile='baseline'; level='3.0'; bitrate='1500k'; file='h264-sd.mp4'; mime='video/avc'; frames=120 },
    @{ id='h264-hd'; width=1280; height=720; codec='libx264'; profile='baseline'; level='3.1'; bitrate='4000k'; file='h264-hd.mp4'; mime='video/avc'; frames=120 },
    @{ id='h264-hd-high'; width=1280; height=720; codec='libx264'; profile='high'; level='3.1'; bitrate='4000k'; file='h264-hd-high.mp4'; mime='video/avc'; frames=120 },
    @{ id='h264-fhd'; width=1920; height=1080; codec='libx264'; profile='high'; level='4.0'; bitrate='8000k'; file='h264-fhd.mp4'; mime='video/avc'; frames=120 },
    @{ id='h263'; width=352; height=288; codec='h263'; bitrate='500k'; file='h263.3gp'; mime='video/3gpp'; frames=30 },
    @{ id='mpeg4'; width=640; height=480; codec='mpeg4'; bitrate='1500k'; file='mpeg4.mp4'; mime='video/mp4v-es'; frames=30 },
    @{ id='vp8'; width=640; height=480; codec='libvpx'; bitrate='1500k'; file='vp8.webm'; mime='video/x-vnd.on2.vp8'; frames=30 }
)
foreach ($v in $videos) {
    $args = @('-ss','25','-i',$source,'-an','-vf',"scale=$($v.width):$($v.height):flags=bicubic,setsar=1",'-r','30','-frames:v',"$($v.frames)",'-pix_fmt','yuv420p','-c:v',$v.codec,'-b:v',$v.bitrate,'-g','30','-threads','1')
    if ($v.codec -eq 'libx264') {
        $bframes = if ($v.profile -eq 'baseline') { '0' } else { '2' }
        $cabac = if ($v.profile -eq 'baseline') { '0' } else { '1' }
        $args += @('-profile:v',$v.profile,'-level:v',$v.level,'-refs','2','-bf',$bframes,'-x264-params',"cabac=$cabac`:scenecut=0:open-gop=0:keyint=30:min-keyint=30")
        $v.b_frames = [int]$bframes; $v.cabac = [int]$cabac; $v.refs = 2
    }
    $v.command = @($args | ForEach-Object { if ($_ -eq $source) { '${SINTEL_SOURCE}' } else { $_ } })
    Encode ($args + (Join-Path $out $v.file))
    $reference = Join-Path $root '.tools/first.yuv'
    Encode @('-i',(Join-Path $out $v.file),'-frames:v','1','-pix_fmt','yuv420p','-f','rawvideo',$reference)
    $v.first_yuv_sha256 = (Get-FileHash $reference -Algorithm SHA256).Hash.ToLowerInvariant()
    $pixels = [IO.File]::ReadAllBytes($reference)
    $means = @()
    for ($by=0; $by -lt 9; $by++) { for ($bx=0; $bx -lt 16; $bx++) {
        [long]$sum=0; [int]$n=0
        for ($y=[int][math]::Floor($by*$v.height/9); $y -lt [math]::Floor(($by+1)*$v.height/9); $y+=4) {
            for ($x=[int][math]::Floor($bx*$v.width/16); $x -lt [math]::Floor(($bx+1)*$v.width/16); $x+=4) { $sum += $pixels[$y*$v.width+$x]; $n++ }
        }
        $means += [int][math]::Round($sum/[math]::Max(1,$n))
    } }
    $v.first_luma_16x9 = $means
    $v.sha256 = (Get-FileHash (Join-Path $out $v.file) -Algorithm SHA256).Hash.ToLowerInvariant()
}
$images = @()
foreach ($size in @(@(854,480),@(1280,720),@(1920,1080))) {
    foreach ($format in @('jpeg','png')) {
        $w=$size[0]; $h=$size[1]; $name="$format-$w-$h"
        $file="$name.$(if ($format -eq 'jpeg') {'jpg'} else {'png'})"
        if ($format -eq 'jpeg') { Encode @('-ss','26','-i',$source,'-vf',"scale=$w`:$h`:flags=bicubic",'-frames:v','1','-q:v','3',(Join-Path $out $file)) }
        else { Encode @('-f','lavfi','-i',"testsrc2=size=$w`x$h`:rate=1",'-vf','format=rgba,colorchannelmixer=aa=0.65','-frames:v','1',(Join-Path $out $file)) }
        $reference="$name.rgba"
        Encode @('-i',(Join-Path $out $file),'-frames:v','1','-pix_fmt','rgba','-f','rawvideo',(Join-Path $out $reference))
        $images += @{ id=$name; file=$file; reference=$reference; width=$w; height=$h; format=$format;
            sha256=(Get-FileHash (Join-Path $out $file) -Algorithm SHA256).Hash.ToLowerInvariant();
            reference_sha256=(Get-FileHash (Join-Path $out $reference) -Algorithm SHA256).Hash.ToLowerInvariant() }
    }
}
$manifest = @{ version='media-corpus-v1'; source='Sintel trailer, Blender Foundation'; source_url='https://download.blender.org/durian/trailer/sintel_trailer-1080p.mp4';
    source_sha256=(Get-FileHash $source -Algorithm SHA256).Hash.ToLowerInvariant(); license='Sintel CC BY 3.0 https://durian.blender.org/sharing/; PNG test patterns generated by FFmpeg testsrc2';
    host_ffmpeg_sha256=(Get-FileHash $Ffmpeg -Algorithm SHA256).Hash.ToLowerInvariant();
    fps=30; pixel_format='yuv420p'; audio=$false; images=$images; videos=$videos }
[IO.File]::WriteAllText((Join-Path $out 'manifest.json'), ($manifest | ConvertTo-Json -Depth 10), (New-Object Text.UTF8Encoding($false)))
& (Join-Path $PSScriptRoot 'generate-jpeg-references.ps1') -Ffmpeg $Ffmpeg -Djpeg $Djpeg
Write-Output 'Generated fixed media corpus and manifest hashes.'
