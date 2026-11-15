# ビューアのスワイプ応答の比較計測 (Issue #101)
#
# 同じ端末・同じフォルダで繰り返しスワイプし、開始からページが切り替わるまでの時間を測る。
#
# 使い方:
#   powershell -ExecutionPolicy Bypass -File scripts\viewer-benchmark.ps1
#   powershell -ExecutionPolicy Bypass -File scripts\viewer-benchmark.ps1 -Runs 30
#   powershell -ExecutionPolicy Bypass -File scripts\viewer-benchmark.ps1 -Serial <serial> -Folder Screenshots
#   powershell -ExecutionPolicy Bypass -File scripts\viewer-benchmark.ps1 -Baseline captures\perf\viewer-xxx.json
#
# 前提:
#   - 端末が USB デバッグで接続され、Pictobi がインストール済み
#   - 画像の閲覧権限を許可済みで、フォルダ一覧が出ている
#   - 計測用のビルド（debug は既定で計測有効。release は -Pfpv.perfTrace=true）
#
# 安全:
#   - タップとスワイプのみ。写真・アプリデータは削除しない
#
# 出力:
#   captures\perf\viewer-<timestamp>.json / .csv
#
# 計測の定義:
#   「指を置いてドラッグが始まってから、移動先のページが描かれるまで」。
#   スナップアニメーションの完了は含まない（含めるとデコードの差が埋もれるため）。

[CmdletBinding()]
param(
    [int]$Runs = 10,

    [string]$Serial,

    # 開くフォルダ（content-desc の部分一致）。空なら一覧の先頭を開く
    [string]$Folder,

    [string]$Package = 'com.tonbiattack.fastestpictureviewer',
    [string]$Activity = '.MainActivity',

    # 比較対象の前回結果（JSON）
    [string]$Baseline,

    [int]$SwipeDurationMs = 140,

    [string]$OutputDir = 'captures\perf'
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot

function Resolve-Adb {
    $onPath = Get-Command adb -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }
    $sdk = $env:ANDROID_HOME
    if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
    if (-not $sdk) { $sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
    $candidate = Join-Path $sdk 'platform-tools\adb.exe'
    if (Test-Path $candidate) { return $candidate }
    throw 'adb が見つかりません。PATH を確認してください。'
}

$script:Adb = Resolve-Adb

function Invoke-AdbText {
    param([string[]]$AdbArgs)
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

function Get-ScreenSize {
    param([string]$Target)
    $text = (Invoke-AdbText @('-s', $Target, 'shell', 'wm', 'size')) -join ' '
    $m = [regex]::Match($text, '(\d+)x(\d+)')
    if (-not $m.Success) { throw '画面サイズを取得できませんでした' }
    return [pscustomobject]@{
        Width = [int]$m.Groups[1].Value
        Height = [int]$m.Groups[2].Value
    }
}

# 画面の UI ツリーから、指定パターンのノード中心を返す
function Find-NodeCenter {
    param([string]$Target, [string]$Pattern, [switch]$Plain)

    if (-not $Plain) {
        Invoke-AdbText @('-s', $Target, 'shell', 'uiautomator', 'dump', '/sdcard/fpv-viewer.xml') | Out-Null
    }
    $xml = Invoke-AdbText @('-s', $Target, 'shell', 'cat', '/sdcard/fpv-viewer.xml')
    if (-not $Plain) {
        Invoke-AdbText @('-s', $Target, 'shell', 'rm', '/sdcard/fpv-viewer.xml') | Out-Null
    }
    $m = [regex]::Match($xml, "$Pattern[^>]*bounds=`"\[(\d+),(\d+)\]\[(\d+),(\d+)\]`"")
    if (-not $m.Success) { return $null }
    $x1 = [int]$m.Groups[1].Value; $y1 = [int]$m.Groups[2].Value
    $x2 = [int]$m.Groups[3].Value; $y2 = [int]$m.Groups[4].Value
    return [pscustomobject]@{ X = [int](($x1 + $x2) / 2); Y = [int](($y1 + $y2) / 2) }
}

function Start-App {
    param([string]$Target)
    Invoke-AdbText @('-s', $Target, 'shell', 'am', 'force-stop', $Package) | Out-Null
    Start-Sleep -Milliseconds 600
    Invoke-AdbText @('-s', $Target, 'shell', 'am', 'start', '-n', "$Package/$Activity") | Out-Null
    Start-Sleep -Seconds 3
}

# 現在の画面種別を返す。uiautomator dump は起動直後などに失敗するため数回やり直す
function Get-ScreenKind {
    param([string]$Target)

    for ($i = 0; $i -lt 3; $i++) {
        Invoke-AdbText @('-s', $Target, 'shell', 'uiautomator', 'dump', '/sdcard/fpv-viewer.xml') | Out-Null
        $xml = Invoke-AdbText @('-s', $Target, 'shell', 'cat', '/sdcard/fpv-viewer.xml')
        Invoke-AdbText @('-s', $Target, 'shell', 'rm', '/sdcard/fpv-viewer.xml') | Out-Null

        if ($xml -match 'content-desc="フォルダを検索"' -or $xml -match 'content-desc="検索を閉じる"') {
            return 'folder_list'
        }
        if ($xml -match 'content-desc="画像を検索"') { return 'grid' }
        if ($xml -match 'ページスクラバー' -or $xml -match 'text="\d+ / \d+"') { return 'viewer' }
        if ($xml -match '<hierarchy') { return 'other_view' }
        # dump 自体が失敗した（空・null root）。少し待ってやり直す
        Start-Sleep -Seconds 1
    }
    return 'unknown'
}

# フォルダ一覧が出るまで戻る。
# 前回の計測でビューアやグリッドを開いたままだと、再起動時にその画面が復元されるため。
# 画面が読めない状態で BACK を押すとホームまで抜けるので、種別が分かったときだけ押す
function Reset-To-FolderList {
    param([string]$Target)

    for ($i = 0; $i -lt 4; $i++) {
        $kind = Get-ScreenKind -Target $Target
        if ($kind -eq 'folder_list') { return $true }

        if ($kind -eq 'grid' -or $kind -eq 'viewer') {
            Invoke-AdbText @('-s', $Target, 'shell', 'input', 'keyevent', 'KEYCODE_BACK') | Out-Null
            Start-Sleep -Seconds 2
            continue
        }

        # other_view / unknown はアプリが前面に無いか、読めていない。
        # BACK は押さずに起動し直す
        Invoke-AdbText @('-s', $Target, 'shell', 'am', 'start', '-n', "$Package/$Activity") | Out-Null
        Start-Sleep -Seconds 3
    }
    return ((Get-ScreenKind -Target $Target) -eq 'folder_list')
}

# ビューアを開く。フォルダを開き、先頭の画像をタップする
function Open-Viewer {
    param([string]$Target)

    $size = Get-ScreenSize -Target $Target

    if ($Folder) {
        # 対象フォルダが一覧の下の方にあると見つからないため、まず検索で絞り込む。
        # adb の input text は IME の状態に左右されるため、送れなければ一覧をそのまま使う
        $search = Find-NodeCenter -Target $Target -Pattern 'content-desc="フォルダを検索"'
        if ($null -ne $search) {
            Invoke-AdbText @('-s', $Target, 'shell', 'input', 'tap', "$($search.X)", "$($search.Y)") | Out-Null
            Start-Sleep -Seconds 2
            Invoke-AdbText @('-s', $Target, 'shell', 'input', 'text', $Folder) | Out-Null
            Start-Sleep -Seconds 3
        }

        $node = Find-NodeCenter -Target $Target -Pattern "content-desc=`"[^`"]*$([regex]::Escape($Folder))"
        if ($null -eq $node) {
            # 検索で絞り込めなかった場合は、検索を解除して先頭のフォルダに切り替える
            Write-Host ("フォルダ '{0}' を検索で開けませんでした。先頭のフォルダで計測します。" -f $Folder) `
                -ForegroundColor Yellow
            $close = Find-NodeCenter -Target $Target -Pattern 'content-desc="検索を閉じる"'
            if ($null -ne $close) {
                Invoke-AdbText @('-s', $Target, 'shell', 'input', 'tap', "$($close.X)", "$($close.Y)") | Out-Null
                Start-Sleep -Seconds 2
            }
            $node = Get-FirstFolderCell -Target $Target -Size $size
        }
        if ($null -eq $node) { throw 'フォルダのセルを取得できませんでした' }
        # セルの中央ではなく少し上を押す。中央はフォルダ名のテキストに当たり、
        # 拡大縮小やスクロール判定に吸われることがある
        Invoke-AdbText @('-s', $Target, 'shell', 'input', 'tap', "$($node.X)", "$($node.Y)") | Out-Null
    } else {
        $node = Get-FirstFolderCell -Target $Target -Size $size
        if ($null -eq $node) { throw 'フォルダのセルを取得できませんでした' }
        Invoke-AdbText @('-s', $Target, 'shell', 'input', 'tap', "$($node.X)", "$($node.Y)") | Out-Null
    }
    Start-Sleep -Seconds 4

    # グリッドの 1 枚目。座標決め打ちではなく、画像の content-desc から取る
    $cell = Find-FirstImageCell -Target $Target
    if ($null -eq $cell) { throw '画像のセルを取得できませんでした' }
    Invoke-AdbText @('-s', $Target, 'shell', 'input', 'tap', "$($cell.X)", "$($cell.Y)") | Out-Null
    Start-Sleep -Seconds 4
}

# フォルダ一覧の先頭セルの中心（やや上寄り）を返す
function Get-FirstFolderCell {
    param([string]$Target, [pscustomobject]$Size)

    Invoke-AdbText @('-s', $Target, 'shell', 'uiautomator', 'dump', '/sdcard/fpv-viewer.xml') | Out-Null
    $xml = Invoke-AdbText @('-s', $Target, 'shell', 'cat', '/sdcard/fpv-viewer.xml')
    Invoke-AdbText @('-s', $Target, 'shell', 'rm', '/sdcard/fpv-viewer.xml') | Out-Null

    # フォルダカード（クリック可能で、お気に入りの星を持つもの）の最初の要素
    foreach ($m in [regex]::Matches($xml, 'content-desc="([^"]+)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')) {
        $desc = $m.Groups[1].Value
        if ($desc -match 'お気に入り|検索|高速|先頭|メニュー|再同期') { continue }
        $x1 = [int]$m.Groups[2].Value; $y1 = [int]$m.Groups[3].Value
        $x2 = [int]$m.Groups[4].Value; $y2 = [int]$m.Groups[5].Value
        # 画面幅の 1/4 以上のカードだけを対象にする（アイコン類を除く）
        if (($x2 - $x1) -lt ($Size.Width / 4)) { continue }
        return [pscustomobject]@{
            X = [int](($x1 + $x2) / 2)
            Y = [int]($y1 + ($y2 - $y1) * 0.55)
        }
    }
    return $null
}

# グリッドの先頭画像セルの中心を返す
function Find-FirstImageCell {
    param([string]$Target)

    Invoke-AdbText @('-s', $Target, 'shell', 'uiautomator', 'dump', '/sdcard/fpv-viewer.xml') | Out-Null
    $xml = Invoke-AdbText @('-s', $Target, 'shell', 'cat', '/sdcard/fpv-viewer.xml')
    Invoke-AdbText @('-s', $Target, 'shell', 'rm', '/sdcard/fpv-viewer.xml') | Out-Null

    $m = [regex]::Match(
        $xml,
        'content-desc="[^"]+\.(jpg|jpeg|png|webp|gif)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
    )
    if (-not $m.Success) { return $null }
    $x1 = [int]$m.Groups[2].Value; $y1 = [int]$m.Groups[3].Value
    $x2 = [int]$m.Groups[4].Value; $y2 = [int]$m.Groups[5].Value
    return [pscustomobject]@{ X = [int](($x1 + $x2) / 2); Y = [int](($y1 + $y2) / 2) }
}

function Measure-Swipe {
    param([string]$Target, [pscustomobject]$Size)

    $midY = [int]($Size.Height / 2)
    $fromX = [int]($Size.Width * 0.8)
    $toX = [int]($Size.Width * 0.2)

    Invoke-AdbText @('-s', $Target, 'logcat', '-c') | Out-Null
    Invoke-AdbText @('-s', $Target, 'shell', 'input', 'swipe',
        "$fromX", "$midY", "$toX", "$midY", "$SwipeDurationMs") | Out-Null
    Start-Sleep -Milliseconds 700

    $log = Invoke-AdbText @('-s', $Target, 'logcat', '-d', '-s', 'FPV-Startup:I')
    $span = [regex]::Match($log, 'span=viewer_swipe_start>viewer_page_drawn ms=(\d+)')
    $cachedMatches = [regex]::Matches($log, 'viewer_image_loaded cached=(true|false)')
    $valueMs = if ($span.Success) { [long]$span.Groups[1].Value } else { $null }
    $cached = if ($cachedMatches.Count -gt 0) {
        $cachedMatches[$cachedMatches.Count - 1].Groups[1].Value
    } else {
        $null
    }

    return [pscustomobject]@{
        valueMs = $valueMs
        cached  = $cached
        source  = 'app_trace'
    }
}

function Get-Percentile {
    param([long[]]$Sorted, [double]$Percentile)
    if (-not $Sorted -or $Sorted.Count -eq 0) { return $null }
    if ($Sorted.Count -eq 1) { return $Sorted[0] }
    $position = [Math]::Ceiling($Percentile / 100.0 * $Sorted.Count)
    $index = [Math]::Max(0, [Math]::Min($Sorted.Count - 1, $position - 1))
    return $Sorted[$index]
}

function Get-Summary {
    param([object[]]$Rows)
    $valid = @($Rows | Where-Object { $_.valueMs -ne $null } | ForEach-Object { [long]$_.valueMs } | Sort-Object)
    $cachedHits = @($Rows | Where-Object { $_.cached -eq 'true' }).Count
    if ($valid.Count -eq 0) {
        return [pscustomobject]@{
            scenario = 'viewer_swipe'; runs = $Rows.Count; validRuns = 0; failedRuns = $Rows.Count
            min = $null; max = $null; p50 = $null; p95 = $null; cachedHits = 0
        }
    }
    return [pscustomobject]@{
        scenario   = 'viewer_swipe'
        runs       = $Rows.Count
        validRuns  = $valid.Count
        failedRuns = $Rows.Count - $valid.Count
        min        = $valid[0]
        max        = $valid[$valid.Count - 1]
        p50        = Get-Percentile -Sorted $valid -Percentile 50
        p95        = Get-Percentile -Sorted $valid -Percentile 95
        cachedHits = $cachedHits
    }
}

# --- 実行 ---
$target = Resolve-Target
$model = (Invoke-AdbText @('-s', $target, 'shell', 'getprop', 'ro.product.model')).Trim()
$release = (Invoke-AdbText @('-s', $target, 'shell', 'getprop', 'ro.build.version.release')).Trim()

Write-Host '=== 計測条件 ===' -ForegroundColor Cyan
Write-Host ("端末: {0} (Android {1})" -f $model, $release)
Write-Host ("フォルダ: {0}" -f $(if ($Folder) { $Folder } else { '先頭' }))
Write-Host ("試行: {0} 回 / スワイプ {1}ms" -f $Runs, $SwipeDurationMs)
Write-Host ''

Start-App -Target $target
if (-not (Reset-To-FolderList -Target $target)) {
    throw 'フォルダ一覧に戻れませんでした。アプリが前面にあるか確認してください。'
}
Open-Viewer -Target $target
$size = Get-ScreenSize -Target $target

$rows = @()
for ($i = 1; $i -le $Runs; $i++) {
    $row = Measure-Swipe -Target $target -Size $size
    $rows += $row
    Write-Host ("  run {0}: {1}ms cached={2}" -f $i, $row.valueMs, $row.cached)
    Start-Sleep -Milliseconds 300
}

$summary = Get-Summary -Rows $rows

Write-Host ''
Write-Host '=== 結果 ===' -ForegroundColor Cyan
$summary | Format-List

if ($Baseline -and (Test-Path $Baseline)) {
    $previous = Get-Content $Baseline -Raw | ConvertFrom-Json
    $previousP50 = $previous.summary.p50
    if ($previousP50 -and $summary.p50) {
        $delta = $summary.p50 - $previousP50
        $ratio = $summary.p50 / $previousP50
        $label = if ($ratio -le 0.95) {
            '改善'
        } elseif ($ratio -ge 1.05) {
            '回帰'
        } else {
            '変化なし（ノイズ範囲）'
        }
        Write-Host ("前回 P50={0}ms → 今回 P50={1}ms ({2:+0;-0}ms, {3})" -f `
            $previousP50, $summary.p50, $delta, $label)
    }
}

$dir = Join-Path $projectRoot $OutputDir
if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir | Out-Null }
$timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$jsonPath = Join-Path $dir ("viewer-{0}.json" -f $timestamp)
$csvPath = Join-Path $dir ("viewer-{0}.csv" -f $timestamp)

[pscustomobject]@{
    device  = [pscustomobject]@{ model = $model; android = $release; serial = $target }
    folder  = $Folder
    runs    = $Runs
    # 計測値にはスワイプ操作自体の時間が含まれる。実質の描画遅れは valueMs - swipeDurationMs
    swipeDurationMs = $SwipeDurationMs
    summary = $summary
    rows    = $rows
} | ConvertTo-Json -Depth 5 | Set-Content -Path $jsonPath -Encoding UTF8

$rows | Export-Csv -Path $csvPath -NoTypeInformation -Encoding UTF8

Write-Host ''
Write-Host ("保存: {0}" -f $jsonPath) -ForegroundColor Green
Write-Host ("保存: {0}" -f $csvPath) -ForegroundColor Green
