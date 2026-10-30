# Re-enable every package in a debloat list and hand Home back to the stock launcher.
# Usage: tools\restore.ps1 [-TvHost 192.168.1.50:5555] [debloat\mibox-s.txt]
param(
    [Parameter(Position = 0)][string]$List = (Join-Path $PSScriptRoot '..\debloat\mibox-s.txt'),
    [string]$TvHost = $env:TV_HOST
)
$ErrorActionPreference = 'Stop'
if (-not $TvHost) { throw 'pass -TvHost <tv ip>:5555 or set $env:TV_HOST' }
adb connect $TvHost | Out-Null

$pkgs = Get-Content $List | ForEach-Object { ($_ -replace '#.*$', '').Trim() } | Where-Object { $_ }
foreach ($p in $pkgs) {
    $out = adb -s $TvHost shell pm enable $p 2>&1
    Write-Output "$p : $out"
}
adb -s $TvHost shell cmd package set-home-activity com.google.android.tvlauncher/.MainActivity
adb -s $TvHost shell settings put secure screensaver_components com.google.android.backdrop/.Backdrop
Write-Output 'Still disabled:'
adb -s $TvHost shell pm list packages -d
