# 実機の UI ツリーから要素を探す補助スクリプト (開発・検証用)
# 使い方:
#   powershell -ExecutionPolicy Bypass -File scripts\ui.ps1 -Action Dump
#   powershell -ExecutionPolicy Bypass -File scripts\ui.ps1 -Action Find -Query "タグ"
#   powershell -ExecutionPolicy Bypass -File scripts\ui.ps1 -Action Tap -Query "タグの管理"
# 日本語の文字化けを避けるため、検索は content-desc / text の部分一致で行う。

[CmdletBinding()]
param(
    [ValidateSet('Dump', 'Find', 'Tap', 'LongTap', 'Texts')]
    [string]$Action = 'Find',
    [string]$Query,
    [string]$Serial
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$tempFile = Join-Path $env:TEMP 'fpv-ui.xml'

function Invoke-Adb {
    param([string[]]$AdbArgs)
    if ($Serial) { $AdbArgs = @('-s', $Serial) + $AdbArgs }
    & adb @AdbArgs
}

function Get-UiDocument {
    Invoke-Adb @('shell', 'uiautomator', 'dump', '/sdcard/fpv-ui.xml') | Out-Null
    Invoke-Adb @('pull', '/sdcard/fpv-ui.xml', $tempFile) | Out-Null
    Invoke-Adb @('shell', 'rm', '/sdcard/fpv-ui.xml') | Out-Null
    [xml](Get-Content -Path $tempFile -Encoding UTF8 -Raw)
}

function Get-Nodes($doc) {
    $doc.SelectNodes('//node')
}

function Get-Bounds([string]$bounds) {
    # "[x1,y1][x2,y2]" → 中央座標
    $m = [regex]::Match($bounds, '\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
    if (-not $m.Success) { return $null }
    $x1 = [int]$m.Groups[1].Value; $y1 = [int]$m.Groups[2].Value
    $x2 = [int]$m.Groups[3].Value; $y2 = [int]$m.Groups[4].Value
    return [pscustomobject]@{ X = [int](($x1 + $x2) / 2); Y = [int](($y1 + $y2) / 2) }
}

switch ($Action) {
    'Dump' {
        $doc = Get-UiDocument
        Get-Nodes $doc | ForEach-Object {
            if ($_.text -or $_.'content-desc') {
                "{0} | {1} | {2}" -f $_.text, $_.'content-desc', $_.bounds
            }
        }
    }
    'Texts' {
        $doc = Get-UiDocument
        Get-Nodes $doc | ForEach-Object { $_.text } | Where-Object { $_ } | Select-Object -Unique
    }
    { $_ -in @('Find', 'Tap', 'LongTap') } {
        if (-not $Query) { throw '-Query を指定してください' }
        $doc = Get-UiDocument
        $found = Get-Nodes $doc | Where-Object {
            ($_.text -and $_.text.Contains($Query)) -or
            ($_.'content-desc' -and $_.'content-desc'.Contains($Query))
        }
        if (-not $found) { throw ("要素が見つかりません: {0}" -f $Query) }
        if ($Action -eq 'Find') {
            $found | ForEach-Object { "{0} | {1} | {2}" -f $_.text, $_.'content-desc', $_.bounds }
            return
        }
        $target = $found | Select-Object -First 1
        $boundsText = [string]$target.bounds
        $m = [regex]::Match($boundsText, '\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
        if (-not $m.Success) { throw ("座標を取得できませんでした: {0}" -f $boundsText) }
        $x1 = [int]$m.Groups[1].Value; $y1 = [int]$m.Groups[2].Value
        $x2 = [int]$m.Groups[3].Value; $y2 = [int]$m.Groups[4].Value
        $pointX = [int](($x1 + $x2) / 2)
        $pointY = [int](($y1 + $y2) / 2)
        if ($Action -eq 'Tap') {
            Invoke-Adb @('shell', 'input', 'tap', "$pointX", "$pointY") | Out-Null
            "tapped: $($target.text) $($target.'content-desc') at $pointX,$pointY"
        } else {
            Invoke-Adb @('shell', 'input', 'swipe', "$pointX", "$pointY", "$pointX", "$pointY", '800') | Out-Null
            "long-tapped: $($target.text) $($target.'content-desc') at $pointX,$pointY"
        }
    }
}

