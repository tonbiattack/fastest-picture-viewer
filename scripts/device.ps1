# OPPO Reno13 A などの実機を ADB で操作するスクリプト
# 安全のため、アンインストール・端末リセット・写真削除は実装しない。
#
# 使い方:
#   powershell -ExecutionPolicy Bypass -File scripts\device.ps1 -Action Devices
#   powershell -ExecutionPolicy Bypass -File scripts\device.ps1 -Action Info
#   powershell -ExecutionPolicy Bypass -File scripts\device.ps1 -Action Install
#   powershell -ExecutionPolicy Bypass -File scripts\device.ps1 -Action Start
#   powershell -ExecutionPolicy Bypass -File scripts\device.ps1 -Action Measure -Runs 5
#   powershell -ExecutionPolicy Bypass -File scripts\device.ps1 -Action Verify -Build
# 端末が複数ある場合は -Serial を指定する。

[CmdletBinding()]
param(
    [ValidateSet('Devices', 'Info', 'Install', 'Start', 'Stop', 'Logs', 'Screenshot', 'Measure', 'Verify')]
    [string]$Action = 'Devices',

    [string]$Serial,
    [string]$Apk = 'app\build\outputs\apk\debug\app-debug.apk',
    [string]$Package = 'com.tonbiattack.fastestpictureviewer',
    [string]$Activity = '.MainActivity',
    [int]$Runs = 3,
    [int]$LogLines = 500,
    [string]$Output,
    [switch]$Build
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot

function Get-SdkDirFromLocalProperties {
    $localProperties = Join-Path $projectRoot 'local.properties'
    if (-not (Test-Path $localProperties)) { return $null }
    $line = Get-Content $localProperties |
        Where-Object { $_ -match '^\s*sdk\.dir\s*=' } |
        Select-Object -First 1
    if (-not $line) { return $null }
    $value = ($line -split '=', 2)[1].Trim()
    return $value -replace '\\\\', '\' -replace '\\:', ':' -replace '\\=', '='
}

function Resolve-Adb {
    $onPath = Get-Command adb -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }

    $sdk = $env:ANDROID_HOME
    if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
    if (-not $sdk) { $sdk = Get-SdkDirFromLocalProperties }
    if (-not $sdk) { $sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }

    $candidate = Join-Path $sdk 'platform-tools\adb.exe'
    if (Test-Path $candidate) { return $candidate }
    throw 'adb が見つかりません。scripts\setup-android-env.ps1 か PATH 設定を確認してください。'
}

function Invoke-Adb {
    param([string[]]$AdbArgs)
    & $script:Adb @AdbArgs
    if ($LASTEXITCODE -ne 0) {
        throw ("adb が失敗しました (exit {0}): adb {1}" -f $LASTEXITCODE, ($AdbArgs -join ' '))
    }
}

# "serial\tdevice" の行だけを返す
function Get-ConnectedSerials {
    $lines = & $script:Adb devices
    $serials = @()
    foreach ($line in $lines) {
        if ($line -match '^(\S+)\t(device|offline|unauthorized)$') {
            if ($Matches[2] -eq 'device') { $serials += $Matches[1] }
        }
    }
    return $serials
}

function Resolve-Serial {
    $serials = @(Get-ConnectedSerials)
    if ($Serial) {
        if ($serials -notcontains $Serial) {
            throw ("指定した端末が device 状態ではありません: {0}`n接続中: {1}" -f $Serial, ($serials -join ', '))
        }
        return $Serial
    }
    if ($serials.Count -eq 1) { return $serials[0] }
    if ($serials.Count -eq 0) { throw 'ADB に接続された端末がありません。USB デバッグと許可ダイアログを確認してください。' }
    throw ("端末が複数あります。-Serial で指定してください: {0}" -f ($serials -join ', '))
}

function Format-DeviceInfo {
    param([string]$Target)
    $props = @('ro.product.manufacturer', 'ro.product.model', 'ro.build.version.release', 'ro.build.version.sdk', 'ro.build.version.incremental', 'ro.product.cpu.abi')
    foreach ($prop in $props) {
        $value = (& $script:Adb -s $Target shell getprop $prop) -join ''
        Write-Output ("{0}: {1}" -f $prop, $value.Trim())
    }
}

function Write-CsvIfRequested {
    param([object[]]$Rows)
    if (-not $Output) { return }
    $path = [System.IO.Path]::GetFullPath((Join-Path $projectRoot $Output))
    $dir = Split-Path -Parent $path
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
    $Rows | Export-Csv -Path $path -NoTypeInformation -Encoding UTF8
    Write-Host ("保存しました: {0}" -f $path) -ForegroundColor Green
}

$script:Adb = Resolve-Adb

switch ($Action) {
    'Devices' {
        Write-Output (& $script:Adb devices -l)
    }

    'Info' {
        $target = Resolve-Serial
        Write-Host ("=== 端末情報 ({0}) ===" -f $target) -ForegroundColor Cyan
        Format-DeviceInfo -Target $target
        Write-Output ''
        Write-Output '--- ストレージ ---'
        & $script:Adb -s $target shell df /sdcard 2>&1
        Write-Output '--- 画像件数 (MediaStore の行数) ---'
        & $script:Adb -s $target shell "content query --uri content://media/external/images/media --projection _id | wc -l" 2>&1
    }

    'Install' {
        $target = Resolve-Serial
        $apkPath = [System.IO.Path]::GetFullPath((Join-Path $projectRoot $Apk))
        if (-not (Test-Path $apkPath)) { throw ("APK がありません: {0}`n先に scripts\build-and-test.ps1 を実行してください。" -f $apkPath) }
        Write-Host ("APK を上書きインストールします (アプリデータは維持): {0}" -f $apkPath) -ForegroundColor Cyan
        # -r: 上書き。同一 applicationId・同一署名ならデータは保持される
        Invoke-Adb -AdbArgs @('-s', $target, 'install', '-r', $apkPath)
    }

    'Start' {
        $target = Resolve-Serial
        $component = "$Package/$Activity"
        Write-Host ("起動時間を計測します: {0}" -f $component) -ForegroundColor Cyan
        $result = & $script:Adb -s $target shell am start -W -n $component 2>&1
        Write-Output ($result -join "`n")
    }

    'Stop' {
        $target = Resolve-Serial
        Invoke-Adb -AdbArgs @('-s', $target, 'shell', 'am', 'force-stop', $Package)
        Write-Host 'アプリを停止しました。' -ForegroundColor Green
    }

    'Logs' {
        $target = Resolve-Serial
        $lines = & $script:Adb -s $target logcat -d -t $LogLines -v time 2>&1
        if ($Output) {
            $path = [System.IO.Path]::GetFullPath((Join-Path $projectRoot $Output))
            $dir = Split-Path -Parent $path
            if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
            $lines | Out-File -FilePath $path -Encoding utf8
            Write-Host ("ログを保存しました: {0}" -f $path) -ForegroundColor Green
        } else {
            Write-Output ($lines -join "`n")
        }
    }

    'Screenshot' {
        $target = Resolve-Serial
        $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
        $remote = "/sdcard/fpv-screenshot-$stamp.png"
        $local = Join-Path $projectRoot ("captures\screenshot-$stamp.png")
        $dir = Split-Path -Parent $local
        if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }

        Invoke-Adb -AdbArgs @('-s', $target, 'shell', 'screencap', '-p', $remote)
        Invoke-Adb -AdbArgs @('-s', $target, 'pull', $remote, $local)
        # 端末側の一時ファイルだけを削除する (写真には触れない)
        & $script:Adb -s $target shell rm $remote | Out-Null
        Write-Host ("スクリーンショットを保存しました: {0}" -f $local) -ForegroundColor Green
    }

    'Measure' {
        $target = Resolve-Serial
        $component = "$Package/$Activity"
        $rows = @()
        for ($i = 1; $i -le $Runs; $i++) {
            & $script:Adb -s $target shell am force-stop $Package | Out-Null
            Start-Sleep -Milliseconds 700
            $output = & $script:Adb -s $target shell am start -W -n $component 2>&1
            $text = $output -join "`n"
            $total = [regex]::Match($text, 'TotalTime:\s*(\d+)')
            $wait = [regex]::Match($text, 'WaitTime:\s*(\d+)')
            $status = [regex]::Match($text, 'Status:\s*(\S+)')

            $row = [pscustomobject]@{
                Run        = $i
                Status     = $(if ($status.Success) { $status.Groups[1].Value } else { 'unknown' })
                TotalTimeMs = $(if ($total.Success) { [int]$total.Groups[1].Value } else { $null })
                WaitTimeMs  = $(if ($wait.Success) { [int]$wait.Groups[1].Value } else { $null })
            }
            $rows += $row
            Write-Output ("Run {0}: TotalTime={1}ms WaitTime={2}ms Status={3}" -f $row.Run, $row.TotalTimeMs, $row.WaitTimeMs, $row.Status)
            & $script:Adb -s $target shell am force-stop $Package | Out-Null
            Start-Sleep -Milliseconds 300
        }

        $valid = @($rows | Where-Object { $_.TotalTimeMs -ne $null })
        if ($valid.Count -gt 0) {
            $avg = ($valid | Measure-Object -Property TotalTimeMs -Average).Average
            $min = ($valid | Measure-Object -Property TotalTimeMs -Minimum).Minimum
            $max = ($valid | Measure-Object -Property TotalTimeMs -Maximum).Maximum
            Write-Output ''
            Write-Output ("TotalTime: avg={0:N0}ms min={1}ms max={2}ms (n={3})" -f $avg, $min, $max, $valid.Count)
        }
        Write-CsvIfRequested -Rows $rows
    }

    'Verify' {
        if ($Build) {
            $buildScript = Join-Path $PSScriptRoot 'build-and-test.ps1'
            & $buildScript
            if (-not $?) { throw 'ビルドまたはテストが失敗しました' }
        }

        $target = Resolve-Serial
        Write-Host '=== 端末情報 ===' -ForegroundColor Cyan
        Format-DeviceInfo -Target $target

        Write-Host '=== APK インストール ===' -ForegroundColor Cyan
        & $PSCommandPath -Action Install -Serial $target -Apk $Apk

        Write-Host '=== 起動計測 ===' -ForegroundColor Cyan
        & $PSCommandPath -Action Measure -Serial $target -Runs $Runs

        Write-Host '=== スクリーンショット ===' -ForegroundColor Cyan
        & $PSCommandPath -Action Screenshot -Serial $target

        Write-Host '=== ログ取得 ===' -ForegroundColor Cyan
        $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
        & $PSCommandPath -Action Logs -Serial $target -LogLines $LogLines -Output "captures\logcat-$stamp.txt"

        Write-Host '実機での操作感・一覧消失の確認は docs\device-verification.md のチェックリストに沿って人が行ってください。' -ForegroundColor Yellow
    }
}
