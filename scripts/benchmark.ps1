# 起動・復帰・フォルダ遷移の性能ベンチマーク (Issue #99)
#
# 使い方:
#   powershell -ExecutionPolicy Bypass -File scripts\benchmark.ps1
#   powershell -ExecutionPolicy Bypass -File scripts\benchmark.ps1 -Scenario cold
#   powershell -ExecutionPolicy Bypass -File scripts\benchmark.ps1 -Runs 20 -Baseline captures\perf\baseline.json
#
# 前提:
#   - 端末が USB デバッグで接続され、Pictobi がインストール済み
#   - 画像の閲覧権限を許可済みで、初回スキャンが完了している
#   - 計測用に -Pfpv.perfTrace=true でビルドした APK を入れておく（debug は既定で有効）
#
# 安全:
#   - force-stop とタップ操作のみ。写真・アプリデータは削除しない
#   - 端末側に作る一時ファイルはスクリーンショットのみで、取得後に削除する
#
# 出力:
#   captures\perf\<timestamp>.json   … 生の試行値と集計
#   captures\perf\<timestamp>.csv    … 1 行 1 試行（表計算で見る用）

[CmdletBinding()]
param(
    [ValidateSet('cold', 'warm', 'navigation', 'all')]
    [string]$Scenario = 'all',

    [int]$Runs = 10,

    [string]$Serial,

    [string]$Package = 'com.tonbiattack.fastestpictureviewer',
    [string]$Activity = '.MainActivity',

    # 比較対象の前回結果（JSON）。指定すると回帰の有無を表示する
    [string]$Baseline,

    # 起動時間の目標（ミリ秒）。P50 がこれを下回れば達成
    [long]$TargetMs = 1000,

    [string]$OutputDir = 'captures\perf'
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

$script:Adb = Resolve-Adb

function Invoke-AdbText {
    param([string[]]$AdbArgs)
    # adb は正常時も stderr に警告を出す（例: Activity not started, its current task
    # has been brought to the front）。ErrorActionPreference=Stop のままだと
    # そこで止まるため、この呼び出しだけ Continue にする
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = & $script:Adb @AdbArgs 2>&1
    } finally {
        $ErrorActionPreference = $previous
    }
    return ($out -join "`n")
}

function Resolve-Target {
    $lines = & $script:Adb devices
    $serials = @()
    foreach ($line in $lines) {
        if ($line -match '^(\S+)\t(device)$') { $serials += $Matches[1] }
    }
    if ($Serial) {
        if ($serials -notcontains $Serial) { throw "指定した端末が device 状態ではありません: $Serial" }
        return $Serial
    }
    if ($serials.Count -eq 1) { return $serials[0] }
    if ($serials.Count -eq 0) { throw 'ADB に接続された端末がありません。' }
    throw ("端末が複数あります。-Serial で指定してください: {0}" -f ($serials -join ', '))
}

function Get-DeviceInfo {
    param([string]$Target)
    $model = (Invoke-AdbText @('-s', $Target, 'shell', 'getprop', 'ro.product.model')).Trim()
    $sdk = (Invoke-AdbText @('-s', $Target, 'shell', 'getprop', 'ro.build.version.sdk')).Trim()
    $release = (Invoke-AdbText @('-s', $Target, 'shell', 'getprop', 'ro.build.version.release')).Trim()
    $versionName = ''
    $dump = Invoke-AdbText @('-s', $Target, 'shell', 'dumpsys', 'package', $Package)
    $m = [regex]::Match($dump, 'versionName=(\S+)')
    if ($m.Success) { $versionName = $m.Groups[1].Value }
    return [pscustomobject]@{
        model     = $model
        android   = $release
        sdk       = $sdk
        appVersion = $versionName
        serial    = $Target
    }
}

# 画像件数（条件の記録用）
function Get-ImageCount {
    param([string]$Target)
    $text = Invoke-AdbText @('-s', $Target, 'shell',
        'content query --uri content://media/external/images/media --projection _id | wc -l')
    $value = ($text -replace '[^\d]', '')
    if ($value) { return [int]$value }
    return $null
}

# logcat の FPV-Startup から、指定スパンの値を取り出す
function Get-SpanValue {
    param([string]$LogText, [string]$SpanName)
    $m = [regex]::Match($LogText, "span=$([regex]::Escape($SpanName)) ms=(\d+)")
    if ($m.Success) { return [long]$m.Groups[1].Value }
    return $null
}

function Clear-Logcat {
    param([string]$Target)
    Invoke-AdbText @('-s', $Target, 'logcat', '-c') | Out-Null
}

function Get-StartupLog {
    param([string]$Target)
    # タグで絞ると取得が速い。起動直後のログだけを見る
    return Invoke-AdbText @('-s', $Target, 'logcat', '-d', '-s', 'FPV-Startup:I')
}

function Stop-App {
    param([string]$Target)
    Invoke-AdbText @('-s', $Target, 'shell', 'am', 'force-stop', $Package) | Out-Null
}

# アニメーションを止めて揺らぎを減らす。計測後に元へ戻す
function Disable-Animations {
    param([string]$Target)
    $keys = @('window_animation_scale', 'transition_animation_scale', 'animator_duration_scale')
    $script:AnimationBackup = @{}
    foreach ($key in $keys) {
        $current = (Invoke-AdbText @('-s', $Target, 'shell', 'settings', 'get', 'global', $key)).Trim()
        $script:AnimationBackup[$key] = $current
        Invoke-AdbText @('-s', $Target, 'shell', 'settings', 'put', 'global', $key, '0') | Out-Null
    }
}

function Restore-Animations {
    param([string]$Target)
    if (-not $script:AnimationBackup) { return }
    foreach ($key in $script:AnimationBackup.Keys) {
        $value = $script:AnimationBackup[$key]
        if ($value -and $value -ne 'null') {
            Invoke-AdbText @('-s', $Target, 'shell', 'settings', 'put', 'global', $key, $value) | Out-Null
        } else {
            Invoke-AdbText @('-s', $Target, 'shell', 'settings', 'delete', 'global', $key) | Out-Null
        }
    }
}

# 画面に一覧が出ているか（初回フレームの判定に使う補助）
function Wait-ForFolders {
    param([string]$Target, [int]$TimeoutMs = 15000)
    $deadline = (Get-Date).AddMilliseconds($TimeoutMs)
    while ((Get-Date) -lt $deadline) {
        $text = Invoke-AdbText @('-s', $Target, 'logcat', '-d', '-s', 'FPV-Startup:I')
        if ($text -match 'mark=folder_list_drawn') { return $true }
        Start-Sleep -Milliseconds 100
    }
    return $false
}

function Measure-ColdStart {
    param([string]$Target)
    $values = @()
    for ($i = 1; $i -le $Runs; $i++) {
        Stop-App -Target $Target
        Start-Sleep -Milliseconds 800
        Clear-Logcat -Target $Target

        # am start -W は Activity の起動完了を待つ（描画開始まで）
        $startText = Invoke-AdbText @('-s', $Target, 'shell', 'am', 'start', '-W', '-n', "$Package/$Activity")
        $amTotal = $null
        $m = [regex]::Match($startText, 'TotalTime:\s*(\d+)')
        if ($m.Success) { $amTotal = [long]$m.Groups[1].Value }

        # アプリ側の区間計測（初回フレーム）を待つ
        Wait-ForFolders -Target $Target | Out-Null
        $log = Get-StartupLog -Target $Target
        $drawn = Get-SpanValue -LogText $log -SpanName 'app_oncreate_start>folder_list_drawn'
        $dbOpen = Get-SpanValue -LogText $log -SpanName 'app_oncreate_start>db_open'

        # 「一覧が出た時点」を主指標にする。取れなければ am の値で代替
        $primary = if ($drawn -ne $null) { $drawn } else { $amTotal }
        $values += [pscustomobject]@{
            run        = $i
            totalMs    = $amTotal
            firstFrameMs = $drawn
            dbOpenMs   = $dbOpen
            valueMs    = $primary
            source     = $(if ($drawn -ne $null) { 'app_trace' } else { 'am_total' })
        }
        Write-Host ("  cold run {0}: firstFrame={1}ms am={2}ms" -f $i, $drawn, $amTotal)
        Stop-App -Target $Target
        Start-Sleep -Milliseconds 300
    }
    return $values
}

function Measure-WarmResume {
    param([string]$Target)
    $values = @()
    # 起動したまま、ホームへ戻って復帰する時間を測る
    Stop-App -Target $Target
    Start-Sleep -Milliseconds 500
    Invoke-AdbText @('-s', $Target, 'shell', 'am', 'start', '-n', "$Package/$Activity") | Out-Null
    Start-Sleep -Seconds 3

    for ($i = 1; $i -le $Runs; $i++) {
        Invoke-AdbText @('-s', $Target, 'shell', 'input', 'keyevent', 'KEYCODE_HOME') | Out-Null
        Start-Sleep -Milliseconds 700
        Clear-Logcat -Target $Target
        Invoke-AdbText @('-s', $Target, 'shell', 'am', 'start', '-n', "$Package/$Activity") | Out-Null
        # 復帰時の区間ログ（resume_start → folder_list_resumed）を待つ
        $duration = $null
        $deadline = (Get-Date).AddMilliseconds(8000)
        while ((Get-Date) -lt $deadline) {
            $log = Get-StartupLog -Target $Target
            $duration = Get-SpanValue -LogText $log -SpanName 'resume_start>folder_list_resumed'
            if ($duration -ne $null) { break }
            Start-Sleep -Milliseconds 100
        }
        $values += [pscustomobject]@{
            run     = $i
            valueMs = $duration
            source  = 'app_trace'
        }
        Write-Host ("  warm run {0}: {1}ms" -f $i, $duration)
        Start-Sleep -Milliseconds 300
    }
    return $values
}

# フォルダを開いてグリッドが出るまで、戻って一覧が出るまで
function Measure-Navigation {
    param([string]$Target)
    $values = @()

    Stop-App -Target $Target
    Start-Sleep -Milliseconds 500
    Invoke-AdbText @('-s', $Target, 'shell', 'am', 'start', '-n', "$Package/$Activity") | Out-Null
    Start-Sleep -Seconds 3

    for ($i = 1; $i -le $Runs; $i++) {
        # 先頭のフォルダをタップする（画面中央上部の最初のセル）
        $size = (Invoke-AdbText @('-s', $Target, 'shell', 'wm', 'size')) -join ' '
        $mSize = [regex]::Match($size, '(\d+)x(\d+)')
        if (-not $mSize.Success) { throw '画面サイズを取得できませんでした' }
        $w = [int]$mSize.Groups[1].Value
        $h = [int]$mSize.Groups[2].Value
        $tapX = [int]($w / 4)
        $tapY = [int]($h / 3)

        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        Invoke-AdbText @('-s', $Target, 'shell', 'input', 'tap', "$tapX", "$tapY") | Out-Null
        Start-Sleep -Milliseconds 1200
        $sw.Stop()
        $openMs = $sw.ElapsedMilliseconds

        # 戻る（グリッド→一覧）
        $sw2 = [System.Diagnostics.Stopwatch]::StartNew()
        Invoke-AdbText @('-s', $Target, 'shell', 'input', 'keyevent', 'KEYCODE_BACK') | Out-Null
        Start-Sleep -Milliseconds 900
        $sw2.Stop()

        $values += [pscustomobject]@{
            run      = $i
            openMs   = $openMs
            backMs   = $sw2.ElapsedMilliseconds
            valueMs  = $openMs
            source   = 'host_stopwatch_fixed_wait'
        }
        Write-Host ("  nav run {0}: open={1}ms back={2}ms" -f $i, $openMs, $sw2.ElapsedMilliseconds)
        Start-Sleep -Milliseconds 300
    }
    return $values
}

# --- 集計（アプリ側の BenchmarkStatsTest と同じ定義） ---
function Get-Percentile {
    param([long[]]$Sorted, [double]$Percentile)
    if (-not $Sorted -or $Sorted.Count -eq 0) { return $null }
    if ($Sorted.Count -eq 1) { return $Sorted[0] }
    $position = [Math]::Ceiling($Percentile / 100.0 * $Sorted.Count)
    $index = [Math]::Max(0, [Math]::Min($Sorted.Count - 1, $position - 1))
    return $Sorted[$index]
}

function Get-Summary {
    param([object[]]$Rows, [string]$Name)
    $valid = @($Rows | Where-Object { $_.valueMs -ne $null } | ForEach-Object { [long]$_.valueMs } | Sort-Object)
    if ($valid.Count -eq 0) {
        return [pscustomobject]@{
            scenario = $Name; runs = $Rows.Count; validRuns = 0
            failedRuns = $Rows.Count; min = $null; max = $null; p50 = $null; p95 = $null
        }
    }
    return [pscustomobject]@{
        scenario   = $Name
        runs       = $Rows.Count
        validRuns  = $valid.Count
        failedRuns = $Rows.Count - $valid.Count
        min        = $valid[0]
        max        = $valid[$valid.Count - 1]
        p50        = Get-Percentile -Sorted $valid -Percentile 50
        p95        = Get-Percentile -Sorted $valid -Percentile 95
    }
}

# --- 実行 ---
$target = Resolve-Target
$device = Get-DeviceInfo -Target $target
$imageCount = Get-ImageCount -Target $target

Write-Host '=== 計測条件 ===' -ForegroundColor Cyan
Write-Host ("端末: {0} (Android {1} / API {2})" -f $device.model, $device.android, $device.sdk)
Write-Host ("アプリ: {0}" -f $device.appVersion)
Write-Host ("画像: {0}" -f $(if ($imageCount) { "$imageCount 件" } else { '取得できません' }))
Write-Host ("試行: {0} 回 / シナリオ: {1}" -f $Runs, $Scenario)
Write-Host ("目標: 初回フレーム P50 < {0}ms" -f $TargetMs)
Write-Host ''

Disable-Animations -Target $target
$results = [ordered]@{}
$rawRows = @()

try {
    if ($Scenario -in @('cold', 'all')) {
        Write-Host '--- コールド起動 ---' -ForegroundColor Yellow
        $rows = Measure-ColdStart -Target $target
        $results['cold_start'] = Get-Summary -Rows $rows -Name 'cold_start'
        $rawRows += $rows | ForEach-Object { $_ | Add-Member -NotePropertyName scenario -NotePropertyValue 'cold_start' -PassThru }
    }
    if ($Scenario -in @('warm', 'all')) {
        Write-Host '--- 復帰（ウォーム） ---' -ForegroundColor Yellow
        $rows = Measure-WarmResume -Target $target
        $results['warm_resume'] = Get-Summary -Rows $rows -Name 'warm_resume'
        $rawRows += $rows | ForEach-Object { $_ | Add-Member -NotePropertyName scenario -NotePropertyValue 'warm_resume' -PassThru }
    }
    if ($Scenario -in @('navigation', 'all')) {
        Write-Host '--- フォルダ遷移 ---' -ForegroundColor Yellow
        $rows = Measure-Navigation -Target $target
        $results['navigation'] = Get-Summary -Rows $rows -Name 'navigation'
        $rawRows += $rows | ForEach-Object { $_ | Add-Member -NotePropertyName scenario -NotePropertyValue 'navigation' -PassThru }
    }
}
finally {
    Restore-Animations -Target $target
}

# --- 出力 ---
$dir = [System.IO.Path]::GetFullPath((Join-Path $projectRoot $OutputDir))
if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$jsonPath = Join-Path $dir "$stamp.json"
$csvPath = Join-Path $dir "$stamp.csv"

$report = [ordered]@{
    generatedAt = (Get-Date).ToString('o')
    device      = $device
    imageCount  = $imageCount
    runs        = $Runs
    unit        = 'ms'
    targetMs    = $TargetMs
    scenarios   = $results
}

# ベースラインとの比較
if ($Baseline) {
    $baselinePath = [System.IO.Path]::GetFullPath((Join-Path $projectRoot $Baseline))
    if (Test-Path $baselinePath) {
        $base = Get-Content $baselinePath -Raw -Encoding UTF8 | ConvertFrom-Json
        $comparison = [ordered]@{}
        foreach ($key in $results.Keys) {
            $currentP50 = $results[$key].p50
            $baseP50 = $null
            if ($base.scenarios -and $base.scenarios.$key) { $baseP50 = $base.scenarios.$key.p50 }
            if ($currentP50 -ne $null -and $baseP50 -ne $null -and $baseP50 -gt 0) {
                $ratio = [double]$currentP50 / [double]$baseP50
                $comparison[$key] = [ordered]@{
                    baselineP50 = $baseP50
                    currentP50  = $currentP50
                    deltaMs     = [long]$currentP50 - [long]$baseP50
                    ratio       = [Math]::Round($ratio, 3)
                    regressed   = ($ratio -ge 1.05)
                    improved    = ($ratio -le (1 / 1.05))
                }
            }
        }
        $report['comparison'] = $comparison
    } else {
        Write-Host ("ベースラインが見つかりません: {0}" -f $baselinePath) -ForegroundColor Yellow
    }
}

$report | ConvertTo-Json -Depth 8 | Set-Content -Path $jsonPath -Encoding UTF8
$rawRows | Export-Csv -Path $csvPath -NoTypeInformation -Encoding UTF8

Write-Host ''
Write-Host '=== 結果 ===' -ForegroundColor Cyan
foreach ($key in $results.Keys) {
    $s = $results[$key]
    $line = "{0}: P50={1}ms P95={2}ms min={3} max={4} (n={5}/{6})" -f `
        $s.scenario, $s.p50, $s.p95, $s.min, $s.max, $s.validRuns, $s.runs
    Write-Host $line
}

if ($results.Contains('cold_start')) {
    $p50 = $results['cold_start'].p50
    if ($p50 -ne $null) {
        if ($p50 -lt $TargetMs) {
            Write-Host ("目標達成: P50 {0}ms < {1}ms" -f $p50, $TargetMs) -ForegroundColor Green
        } else {
            Write-Host ("目標未達: P50 {0}ms >= {1}ms" -f $p50, $TargetMs) -ForegroundColor Yellow
        }
    }
}

if ($report['comparison']) {
    Write-Host ''
    Write-Host '=== ベースライン比較 ===' -ForegroundColor Cyan
    foreach ($key in $report['comparison'].Keys) {
        $c = $report['comparison'][$key]
        $mark = if ($c.regressed) { '回帰' } elseif ($c.improved) { '改善' } else { '変化なし' }
        Write-Host ("{0}: {1} ({2}ms → {3}ms, {4})" -f $key, $mark, $c.baselineP50, $c.currentP50, $c.ratio)
    }
}

Write-Host ''
Write-Host ("保存: {0}" -f $jsonPath) -ForegroundColor Green
Write-Host ("保存: {0}" -f $csvPath) -ForegroundColor Green
