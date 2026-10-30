# Put a photo (e.g. a family picture) on the TV as a full-screen Home wallpaper.
# The photo is centre-cropped to 16:9, resized to 1920x1080 and saved as JPEG, then copied into the
# launcher's wallpapers/ folder. On the TV pick it under Launcher settings > Wallpaper to keep it on screen.
#
# Usage: tools\add-photo.ps1 -TvHost 192.168.1.50:5555 C:\Photos\family.jpg [-Name family]
#        tools\add-photo.ps1 -TvHost 192.168.1.50:5555 -Remove family
param(
    [Parameter(Position = 0)][string]$Photo,
    [string]$Name,
    [string]$Remove,
    [string]$TvHost = $env:TV_HOST
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
if (-not $TvHost) { throw 'pass -TvHost <tv ip>:5555 or set $env:TV_HOST' }
$dir = '/sdcard/Android/data/com.gathin.tvhome/files/wallpapers'
$width = 1920
$height = 1080
$quality = 90L

if ($Remove) {
    if ($Remove -notmatch '^[A-Za-z0-9_-]+$') { throw 'name may only use letters, digits, - and _' }
    adb -s $TvHost shell rm -f "$dir/zz-$Remove.jpg"
    Write-Output "Removed zz-$Remove.jpg"
    exit 0
}
if (-not $Photo -or -not (Test-Path $Photo)) { throw 'pass the path of a photo' }
if (-not $Name) { $Name = [IO.Path]::GetFileNameWithoutExtension($Photo) -replace '[^A-Za-z0-9_-]', '-' }
if ($Name -notmatch '^[A-Za-z0-9_-]+$') { throw 'name may only use letters, digits, - and _' }

# Centre-crop to 16:9 so the picture covers the whole screen without bars.
$src = [System.Drawing.Image]::FromFile((Resolve-Path $Photo).Path)
try {
    $target = $width / $height
    if ($src.Width / $src.Height -gt $target) {
        $cropH = $src.Height; $cropW = [int]($src.Height * $target)
    } else {
        $cropW = $src.Width; $cropH = [int]($src.Width / $target)
    }
    $cropX = [int](($src.Width - $cropW) / 2)
    $cropY = [int](($src.Height - $cropH) / 2)
    $bmp = New-Object System.Drawing.Bitmap $width, $height
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.DrawImage($src, (New-Object System.Drawing.Rectangle 0, 0, $width, $height),
        (New-Object System.Drawing.Rectangle $cropX, $cropY, $cropW, $cropH), [System.Drawing.GraphicsUnit]::Pixel)
    $g.Dispose()
} finally { $src.Dispose() }

$out = Join-Path ([IO.Path]::GetTempPath()) "zz-$Name.jpg"
$codec = [System.Drawing.Imaging.ImageCodecInfo]::GetImageEncoders() | Where-Object { $_.MimeType -eq 'image/jpeg' }
$params = New-Object System.Drawing.Imaging.EncoderParameters 1
$params.Param[0] = New-Object System.Drawing.Imaging.EncoderParameter ([System.Drawing.Imaging.Encoder]::Quality), $quality
$bmp.Save($out, $codec, $params)
$bmp.Dispose()

adb -s $TvHost shell mkdir -p $dir | Out-Null
adb -s $TvHost push $out "$dir/zz-$Name.jpg" | Out-Null
Remove-Item $out
Write-Output "On the TV: Launcher settings > Wallpaper > zz-$Name.jpg"
