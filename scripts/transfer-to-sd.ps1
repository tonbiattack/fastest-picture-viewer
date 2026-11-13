# PC のフォルダを OPPO Reno13 A の SD カードへ ADB 経由で転送するスクリプト
#
# エクスプローラー（MTP）で SD カードへ直接コピーすると、転送開始まで待たされ、
# 失敗することがある。このスクリプトは次の 3 方式を選べる。
#   - Zip    (既定): 1 つの zip にして内部ストレージへ push し、端末内で SD へ展開する
#   - Staged        : まず内部ストレージへ push し、検証してから端末内で SD へコピーする
#   - Direct        : SD カードへ直接 push する（1 個の大きいファイルでは最速）
#
# 使い方:
#   powershell -ExecutionPolicy Bypass -File scripts\transfer-to-sd.ps1 -Source D:\pics -Destination Pictures\20261113
#   powershell -ExecutionPolicy Bypass -File scripts\transfer-to-sd.ps1 -Source D:\pics -Destination Pictures\20261113 -Mode Direct
#   powershell -ExecutionPolicy Bypass -File scripts\transfer-to-sd.ps1 -Source D:\pics -Destination Pictures\20261113 -DryRun
#
# 安全のため、次は行わない。
#   - 端末側の写真・アプリデータの削除（自分のステージング領域のみ削除する）
#   - 端末のアンインストール・リセット・SD カードのフォーマット
# 終了コード: 0 = 成功 / 1 = 検証失敗 / 2 = 引数・前提のエラー / 3 = 転送コマンドの失敗

[CmdletBinding()]
param(
    # 転送する PC 側のフォルダ (このフォルダの中身が Destination 配下に入る)
    [Parameter(Mandatory = $true)]
    [string]$Source,

    # SD カード上の転送先。SD カード直下からの相対パス。省略時はフォルダ名
    [string]$Destination,

    # Zip = zip にまとめて送る (小さいファイルが多いほど有利、既定) / Staged = 内部経由 / Direct = SD へ直接
    [ValidateSet('Zip', 'Staged', 'Direct')]
    [string]$Mode = 'Zip',

    [string]$Serial,

    # SD カードのマウント先。省略時は端末から自動判定する
    [string]$SdRoot,

    # Staged 方式で push する内部ストレージの作業場所
    [string]$StagingRoot = '/sdcard/Download/PictobiTransfer',

    # 検証方法: Size = 件数と合計バイト数 / Hash = 併せて MD5 も比較 (時間がかかる)
    [ValidateSet('Size', 'Hash')]
    [string]$Verify = 'Size',

    # 転送コマンドの再試行回数 (Direct 方式で失敗したときの再実行)
    [ValidateRange(0, 5)]
    [int]$Retries = 1,

    # Staged 方式で cp ではなく mv を使う (失敗時にステージング側が残らない)
    [switch]$Move,

    # 空き容量が不足していても続行する
    [switch]$Force,

    # 検証結果を CSV に保存する
    [Alias('Output')]
    [string]$OutputPath,

    # adb コマンドを表示するだけで実行しない
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot

# ---------------------------------------------------------------------------
# 共通ヘルパー (device.ps1 と同じ解決方法を使う)
# ---------------------------------------------------------------------------

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

# 端末側でシェルコマンドを実行し、標準出力の行を返す。失敗時は例外にする
# -Write を付けた書き込み系コマンドは -DryRun 時に実行せず表示だけする
function Invoke-DeviceShell {
    param(
        [string]$Target,
        [string]$Command,
        [switch]$AllowFailure,
        [switch]$Write
    )
    if ($DryRun -and $Write) {
        Write-Host ("[dry-run] adb -s {0} shell {1}" -f $Target, $Command) -ForegroundColor DarkYellow
        return @()
    }
    $output = & $script:Adb -s $Target shell $Command 2>&1
    $code = $LASTEXITCODE
    if ($code -ne 0 -and -not $AllowFailure) {
        throw ("端末側のコマンドが失敗しました (exit {0}): {1}`n{2}" -f $code, $Command, ($output -join "`n"))
    }
    return @($output)
}

# SD カード (PUBLIC ボリューム) のマウント先を /storage/<UUID> から判定する
function Resolve-SdRoot {
    param([string]$Target)
    $volumes = Invoke-DeviceShell -Target $Target -Command 'sm list-volumes all'
    $uuid = $null
    foreach ($line in $volumes) {
        # 例: "public:179:1 mounted EEFF-78C9"
        if ($line -match '^\s*public:\S+\s+mounted\s+(\S+)\s*$') {
            $uuid = $Matches[1]
            break
        }
    }
    if (-not $uuid) {
        throw 'SD カード (public ボリューム) が見つかりません。カードの装着とマウント状態を確認してください。'
    }
    return "/storage/$uuid"
}

function ConvertTo-DevicePath {
    param([string]$Path)
    # 端末側パスは ' を含めない。シェルに渡すため空白だけをクォートする
    return $Path
}

function Quote-DevicePath {
    param([string]$Path)
    if ($Path -match "'") { throw ("端末側パスにシングルクォートは使えません: {0}" -f $Path) }
    return "'{0}'" -f $Path
}

# 端末側のフォルダのファイル数と合計バイト数を返す
function Get-RemoteStats {
    param(
        [string]$Target,
        [string]$Path
    )
    $quoted = Quote-DevicePath $Path
    $exists = Invoke-DeviceShell -Target $Target -Command ("[ -d $quoted ] && echo yes || echo no")
    if (($exists -join '').Trim() -ne 'yes') {
        return [pscustomobject]@{ Exists = $false; Count = 0; Bytes = 0 }
    }
    $countLine = Invoke-DeviceShell -Target $Target -Command ("find $quoted -type f | wc -l")
    $sizeLines = Invoke-DeviceShell -Target $Target -Command ("find $quoted -type f -exec stat -c %s {} + 2>/dev/null")
    $count = 0L
    $bytes = 0L
    [void][int64]::TryParse((($countLine -join '').Trim()), [ref]$count)
    # 端末に awk が無い環境があるため、サイズの合計は PC 側で計算する
    foreach ($line in $sizeLines) {
        $value = 0L
        foreach ($token in (($line -split '\s+') | Where-Object { $_ })) {
            if ([int64]::TryParse($token, [ref]$value)) { $bytes += $value }
        }
    }
    return [pscustomobject]@{ Exists = $true; Count = $count; Bytes = $bytes }
}

# 端末側の指定パスの空き容量 (バイト)
function Get-RemoteFreeBytes {
    param(
        [string]$Target,
        [string]$Path
    )
    $quoted = Quote-DevicePath $Path
    $line = Invoke-DeviceShell -Target $Target -Command ("df -k $quoted 2>/dev/null | tail -n 1")
    $text = ($line -join ' ').Trim()
    # Filesystem 1K-blocks Used Available Use% Mounted
    $parts = $text -split '\s+'
    if ($parts.Count -ge 4) {
        $availKb = 0L
        if ([int64]::TryParse($parts[3], [ref]$availKb)) { return $availKb * 1024 }
    }
    return $null
}

function Format-Bytes {
    param([double]$Bytes)
    if ($Bytes -ge 1GB) { return ('{0:N2} GB' -f ($Bytes / 1GB)) }
    if ($Bytes -ge 1MB) { return ('{0:N1} MB' -f ($Bytes / 1MB)) }
    if ($Bytes -ge 1KB) { return ('{0:N0} KB' -f ($Bytes / 1KB)) }
    return ('{0:N0} B' -f $Bytes)
}

function Invoke-AdbPush {
    param(
        [string]$Target,
        [string]$Local,
        [string]$Remote
    )
    if ($DryRun) {
        Write-Host ("[dry-run] adb -s {0} push {1} {2}" -f $Target, $Local, $Remote) -ForegroundColor DarkYellow
        return
    }
    & $script:Adb -s $Target push $Local $Remote
    if ($LASTEXITCODE -ne 0) {
        throw ("adb push が失敗しました (exit {0}): {1} -> {2}" -f $LASTEXITCODE, $Local, $Remote)
    }
}

# ---------------------------------------------------------------------------
# 前提チェック
# ---------------------------------------------------------------------------

$sourceFull = [System.IO.Path]::GetFullPath($Source)
if (-not (Test-Path -LiteralPath $sourceFull)) {
    Write-Host ('転送元が見つかりません: {0}' -f $sourceFull) -ForegroundColor Red
    exit 2
}
if (-not (Test-Path -LiteralPath $sourceFull -PathType Container)) {
    Write-Host ('転送元はフォルダを指定してください: {0}' -f $sourceFull) -ForegroundColor Red
    exit 2
}

$sourceName = Split-Path -Leaf $sourceFull
if (-not $Destination) { $Destination = $sourceName }

# 転送先は SD 直下からの相対パス。絶対パスや .. を弾いて意図しない場所へ書かない
if ($Destination -match '^[/\\]' -or $Destination -match '(^|[/\\])\.\.([/\\]|$)') {
    Write-Host ('-Destination は SD カード直下からの相対パスで指定してください: {0}' -f $Destination) -ForegroundColor Red
    exit 2
}
$Destination = $Destination -replace '\\', '/'
$Destination = $Destination.Trim('/')

$files = @(Get-ChildItem -LiteralPath $sourceFull -Recurse -File -Force)
if ($files.Count -eq 0) {
    Write-Host ('転送するファイルがありません: {0}' -f $sourceFull) -ForegroundColor Yellow
    exit 2
}
$sourceBytes = ($files | Measure-Object -Property Length -Sum).Sum

# zip 内の日本語ファイル名は端末の unzip で復元できないことがあるため、
# 非 ASCII を含む場合は自動的に Staged 方式へ切り替える（-Force で回避可能）
$hasNonAscii = @($files | Where-Object { $_.FullName -match '[^\x00-\x7F]' }).Count -gt 0
if ($Mode -eq 'Zip' -and $hasNonAscii -and -not $Force) {
    Write-Host '日本語などのファイル名が含まれるため、zip 方式から Staged 方式へ切り替えます。' -ForegroundColor Yellow
    Write-Host '  （zip 内の名前が端末の unzip で復元されないため。ASCII 名だけなら zip が最速）'
    $Mode = 'Staged'
}

$script:Adb = Resolve-Adb
$target = Resolve-Serial

if (-not $SdRoot) { $SdRoot = Resolve-SdRoot -Target $target }
$SdRoot = $SdRoot.TrimEnd('/')
$sdTarget = "$SdRoot/$Destination"

# 空き容量。SD カードがほぼ満杯だと転送が途中で失敗するため、先に確認する
$freeBytes = Get-RemoteFreeBytes -Target $target -Path $SdRoot
$needsStaging = $Mode -ne 'Direct'

Write-Host '=== 転送計画 ===' -ForegroundColor Cyan
Write-Host ('端末          : {0}' -f $target)
Write-Host ('SD カード     : {0}' -f $SdRoot)
Write-Host ('転送元        : {0}' -f $sourceFull)
Write-Host ('転送先        : {0}' -f $sdTarget)
Write-Host ('方式          : {0}' -f $Mode)
Write-Host ('ファイル      : {0:N0} 件 / {1}' -f $files.Count, (Format-Bytes $sourceBytes))
if ($null -ne $freeBytes) {
    Write-Host ('SD 空き容量   : {0}' -f (Format-Bytes $freeBytes))
}
if ($needsStaging) {
    $stagingFree = Get-RemoteFreeBytes -Target $target -Path '/sdcard'
    if ($null -ne $stagingFree) {
        Write-Host ('内部空き容量  : {0}' -f (Format-Bytes $stagingFree))
        if ($stagingFree -lt $sourceBytes -and -not $Force) {
            Write-Host ('内部ストレージの空き容量が不足しています。不要なファイルを消すか -Mode Direct を使ってください。') -ForegroundColor Red
            exit 2
        }
    }
}
if ($null -ne $freeBytes -and $freeBytes -lt $sourceBytes -and -not $Force) {
    Write-Host ('SD カードの空き容量が不足しています ({0} < {1})。不要なファイルを消すか -Force を付けてください。' -f (Format-Bytes $freeBytes), (Format-Bytes $sourceBytes)) -ForegroundColor Red
    exit 2
}

# 転送先に既存ファイルがある場合は上書きになる。件数を出して知らせる
$existing = Get-RemoteStats -Target $target -Path $sdTarget
if ($existing.Exists -and $existing.Count -gt 0) {
    Write-Host ('転送先には既に {0:N0} 件あります。同名ファイルは上書きされます。' -f $existing.Count) -ForegroundColor Yellow
}

if ($DryRun) {
    Write-Host ''
    Write-Host '[dry-run] ここから先の端末操作は実行しません。' -ForegroundColor DarkYellow
}

# ---------------------------------------------------------------------------
# 転送
# ---------------------------------------------------------------------------

$rows = @()
$swTotal = [System.Diagnostics.Stopwatch]::StartNew()
$stagingDir = $null
$failed = $false

function Add-Row {
    param([string]$Step, [double]$Seconds, [string]$Detail)
    $script:rows += [pscustomobject]@{
        Step     = $Step
        Seconds  = [math]::Round($Seconds, 2)
        Detail   = $Detail
        Mode     = $Mode
        Files    = $files.Count
        Bytes    = $sourceBytes
    }
    Write-Host ('  {0,-18} {1,7:N2}s  {2}' -f $Step, $Seconds, $Detail)
}

# MD5 の一覧を作る (Hash 検証用)
# 端末側で一度ファイルに書き出してから pull する。adb shell の標準出力は
# PowerShell のコンソール文字コードで変換され、日本語ファイル名が壊れるため
function Get-RemoteHashes {
    param([string]$Target, [string]$Path)
    $quoted = Quote-DevicePath $Path
    $remoteFile = '/data/local/tmp/pictobi-md5.txt'
    $localFile = Join-Path ([System.IO.Path]::GetTempPath()) ('pictobi-md5-{0}.txt' -f ([guid]::NewGuid().ToString('N')))
    # -f 演算子を使うと find の {} が書式として解釈されるため文字列連結で組み立てる
    Invoke-DeviceShell -Target $Target -Command ("cd $quoted && find . -type f -exec md5sum {} + 2>/dev/null | sort > " + $remoteFile) -Write | Out-Null
    # adb pull は進捗を標準エラーへ出す。PowerShell 5.1 では ErrorActionPreference=Stop のとき
    # ネイティブコマンドの stderr が例外になるため、呼び出し中だけ Continue に落とす
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $script:Adb -s $Target pull $remoteFile $localFile *> $null
        & $script:Adb -s $Target shell rm -f $remoteFile *> $null
    } finally {
        $ErrorActionPreference = $previous
    }
    if (-not (Test-Path $localFile)) { return @() }
    $lines = [System.IO.File]::ReadAllLines($localFile, [System.Text.UTF8Encoding]::new($false))
    Remove-Item -LiteralPath $localFile -ErrorAction SilentlyContinue
    return @($lines | ForEach-Object { $_.Trim() } | Where-Object { $_ })
}

try {
    if ($Mode -eq 'Zip') {
        # 1. PC 側で zip にまとめる。小さいファイルを大量に送るとファイル数分の遅延が乗るため、
        #    1 ファイルにして転送のオーバーヘッドを 1 回に抑える
        $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
        $zipLocal = Join-Path ([System.IO.Path]::GetTempPath()) ('pictobi-{0}.zip' -f $stamp)
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        if (-not $DryRun) {
            Add-Type -AssemblyName System.IO.Compression.FileSystem
            [System.IO.Compression.ZipFile]::CreateFromDirectory(
                $sourceFull,
                $zipLocal,
                [System.IO.Compression.CompressionLevel]::NoCompression,
                $false
            )
        }
        $sw.Stop()
        Add-Row -Step 'zip 作成 (PC)' -Seconds $sw.Elapsed.TotalSeconds -Detail ('{0}' -f (Format-Bytes $sourceBytes))

        $stagingDir = '{0}/{1}-{2}' -f $StagingRoot.TrimEnd('/'), ($sourceName -replace '[^\w\.\-]', '_'), $stamp
        $remoteZip = "$stagingDir.zip"

        # 2. zip を内部ストレージへ push する (開始待ちが少なく、失敗しても SD を汚さない)
        $sw.Restart()
        Invoke-DeviceShell -Target $target -Command ("mkdir -p {0}" -f (Quote-DevicePath $StagingRoot)) -Write | Out-Null
        Invoke-AdbPush -Target $target -Local $zipLocal -Remote $remoteZip
        $sw.Stop()
        Add-Row -Step 'push (zip→内部)' -Seconds $sw.Elapsed.TotalSeconds -Detail ('{0}' -f (Format-Bytes (Get-Item $zipLocal).Length))

        # 3. 端末内で解凍しながら SD へ書き込む
        $sw.Restart()
        Invoke-DeviceShell -Target $target -Command ("mkdir -p {0} && cd {0} && unzip -o -q {1}" -f (Quote-DevicePath $sdTarget), (Quote-DevicePath $remoteZip)) -Write | Out-Null
        $sw.Stop()
        Add-Row -Step 'unzip (SD)' -Seconds $sw.Elapsed.TotalSeconds -Detail '端末内で解凍'

        # 4. SD 側の検証
        $sw.Restart()
        $remote = Get-RemoteStats -Target $target -Path $sdTarget
        $sw.Stop()
        Add-Row -Step 'verify (SD)' -Seconds $sw.Elapsed.TotalSeconds -Detail ('{0:N0} 件 / {1}' -f $remote.Count, (Format-Bytes $remote.Bytes))
        if ($remote.Count -lt $files.Count -and -not $DryRun) {
            Write-Host ('SD への展開が不完全です: {0:N0} 件 < {1:N0} 件' -f $remote.Count, $files.Count) -ForegroundColor Red
            $failed = $true
        }

        # 5. 検証が通ったときだけステージングを消す
        if (-not $failed -and -not $DryRun) {
            $sw.Restart()
            Invoke-DeviceShell -Target $target -Command ("rm -f {0}" -f (Quote-DevicePath $remoteZip)) -Write | Out-Null
            $sw.Stop()
            Add-Row -Step 'cleanup (内部)' -Seconds $sw.Elapsed.TotalSeconds -Detail 'zip を削除'
        }
        $stagingDir = $null
        Remove-Item -LiteralPath $zipLocal -ErrorAction SilentlyContinue
    } elseif ($Mode -eq 'Direct') {
        $attempt = 0
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        while ($true) {
            try {
                Invoke-AdbPush -Target $target -Local $sourceFull -Remote $sdTarget
                break
            } catch {
                $attempt++
                if ($attempt -gt $Retries) { throw }
                Write-Host ('  転送に失敗しました。{0} 回目を再試行します...' -f ($attempt + 1)) -ForegroundColor Yellow
            }
        }
        $sw.Stop()
        Add-Row -Step 'push (SD直接)' -Seconds $sw.Elapsed.TotalSeconds -Detail ('{0} 件' -f $files.Count)

        if ($Verify -eq 'Size') {
            $sw.Restart()
            $remote = Get-RemoteStats -Target $target -Path $sdTarget
            $sw.Stop()
            Add-Row -Step 'verify (件数/容量)' -Seconds $sw.Elapsed.TotalSeconds -Detail ('{0:N0} 件 / {1}' -f $remote.Count, (Format-Bytes $remote.Bytes))
            if ($remote.Count -lt $files.Count -and -not $DryRun) {
                Write-Host ('検証に失敗しました: 端末側 {0:N0} 件 < PC 側 {1:N0} 件' -f $remote.Count, $files.Count) -ForegroundColor Red
                $failed = $true
            }
        }
    } else {
        $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
        $stagingDir = '{0}/{1}-{2}' -f $StagingRoot.TrimEnd('/'), ($sourceName -replace '[^\w\.\-]', '_'), $stamp

        # 1. 内部ストレージへ push (MTP を経由しないため開始待ちが少ない)
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        Invoke-DeviceShell -Target $target -Command ("mkdir -p {0}" -f (Quote-DevicePath $StagingRoot))  -Write | Out-Null
        Invoke-AdbPush -Target $target -Local $sourceFull -Remote $stagingDir
        $sw.Stop()
        Add-Row -Step 'push (内部)' -Seconds $sw.Elapsed.TotalSeconds -Detail ('{0} 件' -f $files.Count)

        # 2. 内部側の検証 (ここで欠けていれば SD へコピーしない)
        $sw.Restart()
        $staged = Get-RemoteStats -Target $target -Path $stagingDir
        $sw.Stop()
        Add-Row -Step 'verify (内部)' -Seconds $sw.Elapsed.TotalSeconds -Detail ('{0:N0} 件 / {1}' -f $staged.Count, (Format-Bytes $staged.Bytes))
        if ($staged.Count -lt $files.Count -and -not $DryRun) {
            Write-Host ('内部への転送が不完全です: {0:N0} 件 < {1:N0} 件。SD へはコピーしません。' -f $staged.Count, $files.Count) -ForegroundColor Red
            $failed = $true
            throw 'staged transfer incomplete'
        }

        # 3. 端末内で SD へコピー (または移動)
        $sw.Restart()
        if ($Move -and -not $existing.Exists) {
            # 移動先が無い場合だけ mv を使う。既存フォルダがあると配下に入ってしまうため cp に切り替える
            # 端末側パスは Windows の Split-Path を通すと区切りが \ になるため、文字列で親を求める
            $parent = if ($sdTarget.Contains('/')) { $sdTarget.Substring(0, $sdTarget.LastIndexOf('/')) } else { $SdRoot }
            Invoke-DeviceShell -Target $target -Command ("mkdir -p {0} && mv {1} {2}" -f (Quote-DevicePath $parent), (Quote-DevicePath $stagingDir), (Quote-DevicePath $sdTarget))  -Write | Out-Null
            $stagingDir = $null
            Add-Row -Step 'mv (内部→SD)' -Seconds $sw.Elapsed.TotalSeconds -Detail '端末内で移動'
        } else {
            Invoke-DeviceShell -Target $target -Command ("mkdir -p {0} && cp -R {1}/. {2}/" -f (Quote-DevicePath $sdTarget), (Quote-DevicePath $stagingDir), (Quote-DevicePath $sdTarget))  -Write | Out-Null
            Add-Row -Step 'cp (内部→SD)' -Seconds $sw.Elapsed.TotalSeconds -Detail '端末内でコピー'
        }
        $sw.Stop()

        # 4. SD 側の検証
        $sw.Restart()
        $remote = Get-RemoteStats -Target $target -Path $sdTarget
        $sw.Stop()
        Add-Row -Step 'verify (SD)' -Seconds $sw.Elapsed.TotalSeconds -Detail ('{0:N0} 件 / {1}' -f $remote.Count, (Format-Bytes $remote.Bytes))
        if ($remote.Count -lt $files.Count -and -not $DryRun) {
            Write-Host ('SD へのコピーが不完全です: {0:N0} 件 < {1:N0} 件' -f $remote.Count, $files.Count) -ForegroundColor Red
            $failed = $true
        }

        # 5. 検証が通ったときだけステージングを消す
        if (-not $failed -and $stagingDir) {
            $sw.Restart()
            Invoke-DeviceShell -Target $target -Command ("rm -rf {0}" -f (Quote-DevicePath $stagingDir))  -Write | Out-Null
            $stagingDir = $null
            $sw.Stop()
            Add-Row -Step 'cleanup (内部)' -Seconds $sw.Elapsed.TotalSeconds -Detail 'ステージングを削除'
        }
    }

    if ($Verify -eq 'Hash' -and -not $failed) {
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        $remoteHashes = Get-RemoteHashes -Target $target -Path $sdTarget
        $localHashes = @($files |
            ForEach-Object {
                $rel = $_.FullName.Substring($sourceFull.Length).TrimStart('\', '/') -replace '\\', '/'
                $hash = (Get-FileHash -LiteralPath $_.FullName -Algorithm MD5).Hash.ToLower()
                # md5sum の出力形式 (ハッシュ + 2 空白 + パス) に合わせる
                '{0}  ./{1}' -f $hash, $rel
            } |
            Sort-Object)
        $sw.Stop()
        $sameCount = @($remoteHashes | Where-Object { $localHashes -contains $_ }).Count
        Add-Row -Step 'verify (MD5)' -Seconds $sw.Elapsed.TotalSeconds -Detail ('{0:N0} / {1:N0} 件一致' -f $sameCount, $files.Count)
        if ($sameCount -lt $files.Count) {
            Write-Host 'MD5 が一致しないファイルがあります。' -ForegroundColor Red
            $failed = $true
        }
    }
} catch {
    Write-Host ('転送に失敗しました: {0}' -f $_.Exception.Message) -ForegroundColor Red
    if ($stagingDir) {
        Write-Host ('内部のステージングを残しています: {0}' -f $stagingDir) -ForegroundColor Yellow
        Write-Host '確認後に手動で削除してください (スクリプトは検証成功時のみ削除します)。'
    }
    exit 3
}

$swTotal.Stop()

Write-Host ''
Write-Host ('合計: {0:N1} 秒' -f $swTotal.Elapsed.TotalSeconds) -ForegroundColor Cyan

if ($OutputPath) {
    $path = [System.IO.Path]::GetFullPath((Join-Path $projectRoot $OutputPath))
    $dir = Split-Path -Parent $path
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
    $rows | Export-Csv -Path $path -NoTypeInformation -Encoding UTF8
    Write-Host ('結果を保存しました: {0}' -f $path) -ForegroundColor Green
}

if ($failed) {
    Write-Host ''
    Write-Host '転送は完了しましたが検証に失敗しました。端末のファイルを確認してください。' -ForegroundColor Red
    exit 1
}

Write-Host ''
Write-Host ('[OK] {0} へ {1:N0} 件を転送しました。' -f $sdTarget, $files.Count) -ForegroundColor Green
Write-Host 'アプリのフォルダ一覧に出ない場合は、アプリの手動更新を実行してください。'
