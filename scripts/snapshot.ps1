# 検証前スナップショットを取得するスクリプト (Issue #111)
#
# 実機の設定・データを壊さない検証のために、検証前の状態を
# captures\backup\<timestamp>\ へ退避する。写真そのものには触れない。
# アンインストール・pm clear・データ削除は行わない。
#
# 使い方:
#   powershell -ExecutionPolicy Bypass -File scripts\snapshot.ps1 -Serial 6ad7aa65
#   powershell -ExecutionPolicy Bypass -File scripts\snapshot.ps1 -Serial 6ad7aa65 -Label before-111
#   powershell -ExecutionPolicy Bypass -File scripts\snapshot.ps1 -Help
#
# 端末が複数接続されている場合は -Serial を必ず指定する（device.ps1 と同じ挙動）。
#
# 必須 / 任意の切り分け:
#   必須 = これが無いと「検証前の状態に戻せる」と言えないもの。取得できなければ
#          終了コード 1 で中止し、検証を続行させない。
#            - 端末情報 (model / Android / API / serial)
#            - アプリのバージョン・署名情報 (dumpsys package)
#            - shared_prefs/*.xml の中身 (run-as)
#   任意 = 端末や PC の環境によって取得できないことがあるもの。取得できなくても
#          警告を出して検証を続行できる。
#            - インストール済み APK の退避と証明書 SHA-256 (apksigner が無いことがある)
#            - DB の主要テーブル件数 (端末に sqlite3 が無いことがある)
#            - DB ファイルのサイズ
#            - アプリ内バックアップ JSON (SAF で人が書き出す必要がある)
#            - MediaStore の画像件数 (権限や MediaStore の状態に依存する)
#
#   DB 全体の pull は約 300MB あり、時間と容量の面で毎回は行わない。
#   件数は端末側の sqlite3 で数え、無い場合は「未取得」と明示して任意項目に留める。
#   アプリ内バックアップ JSON は Storage Access Framework で人が保存先を選ぶため
#   自動化できない。既定のファイル名で保存済みなら拾い、無ければ手順を docs に残す。
#
# 終了コード: 0 = 必須項目をすべて退避 / 1 = 必須項目の失敗 / 2 = 引数・前提のエラー

[CmdletBinding()]
param(
    # 対象端末の serial。複数接続時は必須
    [string]$Serial,

    [string]$Package = 'com.tonbiattack.fastestpictureviewer',

    # 退避先の親フォルダ（プロジェクトからの相対パス）
    [string]$OutputRoot = 'captures\backup',

    # 退避先フォルダ名の後ろに付ける識別子（例: before-111）
    [string]$Label,

    # インストール済み APK の退避と証明書の確認を行わない
    [switch]$SkipInstalledApk,

    # DB のテーブル件数とファイルサイズの取得を行わない
    [switch]$SkipDbCounts,

    # DB 件数の取り方。
    #   Auto (既定) : 端末に sqlite3 があればそれで数える。無ければ未取得（DB は pull しない）
    #   Pull        : 端末に sqlite3 が無い場合も DB を PC へ pull して PC 側の sqlite3 で数える。
    #                 DB は約 300MB あるため、時間と容量を使ってよいときだけ明示的に指定する
    [ValidateSet('Auto', 'Pull')]
    [string]$DbCountMode = 'Auto',

    # 使い方を表示して終了する（端末には触れない）
    [switch]$Help
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot

function Show-Usage {
    Write-Output @'
検証前スナップショット (scripts\snapshot.ps1)

使い方:
  powershell -ExecutionPolicy Bypass -File scripts\snapshot.ps1 -Serial <serial>
  powershell -ExecutionPolicy Bypass -File scripts\snapshot.ps1 -Serial <serial> -Label before-111
  powershell -ExecutionPolicy Bypass -File scripts\snapshot.ps1 -Help

主なパラメータ:
  -Serial <serial>   対象端末。複数接続時は必須（省略時は 1 台だけなら自動）
  -Package <name>    対象アプリ（既定: com.tonbiattack.fastestpictureviewer）
  -OutputRoot <path> 退避先の親フォルダ（既定: captures\backup）
  -Label <text>      退避先フォルダ名に付ける識別子
  -SkipInstalledApk  インストール済み APK の退避と証明書確認を行わない
  -SkipDbCounts      DB のテーブル件数・ファイルサイズの取得を行わない
  -DbCountMode <mode> DB 件数の取り方。Auto (既定) = 端末の sqlite3 で数える /
                     Pull = 端末に sqlite3 が無ければ DB を PC へ pull して数える
                     （DB は約 300MB。時間と容量を使ってよいときだけ Pull を指定）

退避する内容:
  必須: 端末情報 / アプリのバージョン・署名情報 / shared_prefs/*.xml
  任意: インストール済み APK と証明書 SHA-256 / DB のテーブル件数 /
        DB ファイルのサイズ / アプリ内バックアップ JSON / 画像件数

終了コード:
  0 = 必須項目をすべて退避
  1 = 必須項目の失敗（検証を中止すること）
  2 = 引数・前提のエラー（端末未接続、複数接続で -Serial 未指定など）

退避先は captures\backup\<timestamp>\ で、captures/ は .gitignore 済み。
写真・アプリデータの削除、アンインストール、pm clear は行わない。
'@
}

if ($Help) {
    Show-Usage
    return
}

# ---------------------------------------------------------------------------
# 共通ヘルパー (device.ps1 / benchmark.ps1 と同じ解決方法を使う)
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

function Resolve-AndroidSdk {
    $sdk = $env:ANDROID_HOME
    if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
    if (-not $sdk) { $sdk = Get-SdkDirFromLocalProperties }
    if (-not $sdk) { $sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
    if ($sdk -and (Test-Path $sdk)) { return $sdk }
    return $null
}

function Resolve-Adb {
    $onPath = Get-Command adb -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }

    $sdk = Resolve-AndroidSdk
    if (-not $sdk) {
        throw 'adb が見つかりません。scripts\setup-android-env.ps1 か PATH 設定を確認してください。'
    }
    $candidate = Join-Path $sdk 'platform-tools\adb.exe'
    if (Test-Path $candidate) { return $candidate }
    throw 'adb が見つかりません。scripts\setup-android-env.ps1 か PATH 設定を確認してください。'
}

# apksigner は PATH か Android SDK の build-tools のうち新しいものを使う
function Resolve-ApkSigner {
    $onPath = Get-Command apksigner -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }

    $sdk = Resolve-AndroidSdk
    if (-not $sdk) { return $null }
    $root = Join-Path $sdk 'build-tools'
    if (-not (Test-Path $root)) { return $null }
    # 名前の降順で十分（35.0.0 < 36.1.0 < 37.0.0 のような現行の採番を前提にする）
    foreach ($dir in @(Get-ChildItem $root -Directory | Sort-Object Name -Descending)) {
        $candidate = Join-Path $dir.FullName 'apksigner.bat'
        if (Test-Path $candidate) { return $candidate }
    }
    return $null
}

# adb の出力を文字列で返す。adb は正常時も stderr に警告を出すため、
# この呼び出しだけ ErrorActionPreference を Continue に落とす
function Invoke-AdbText {
    param([string[]]$AdbArgs)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = & $script:Adb @AdbArgs 2>&1
    } finally {
        $ErrorActionPreference = $previous
    }
    # 2>&1 で拾った ErrorRecord は "$_" で例外メッセージの塊になる。
    # 端末の応答として解釈すると誤検出の元になるため、文字列化だけして返す
    return (($out | ForEach-Object { "$_" }) -join "`n")
}

# adb の出力が「端末の応答」ではなく PowerShell のエラーや adb の失敗メッセージか判定する。
# これを弾かないと、エラー文が model 名や versionName として保存されてしまう
function Test-AdbOutputError {
    param([string]$Text)
    if ([string]::IsNullOrWhiteSpace($Text)) { return $true }
    if ($Text -match 'Unexpected token|ParserError|CategoryInfo|FullyQualifiedErrorId') { return $true }
    if ($Text -match 'error: (device|no devices|closed)|adb: |device offline|device unauthorized') { return $true }
    return $false
}

# getprop の値を取り出す。1 行の妥当な値だけを受け付け、エラー文は空として扱う
function Get-DeviceProp {
    param([string]$Target, [string]$Prop)
    $text = (Invoke-AdbText @('-s', $Target, 'shell', 'getprop', $Prop)).Trim()
    if (Test-AdbOutputError -Text $text) { return '' }
    # 改行を含む出力は値ではない（エラー文が混ざった可能性がある）
    if ($text -match "`n") { return '' }
    return $text
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

# UTF-8 (BOM なし) でテキストを書く。Set-Content -Encoding UTF8 は BOM を付けるため使わない
function Write-Utf8File {
    param([string]$Path, [string]$Content)
    $encoding = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Path, $Content, $encoding)
}

# 端末のファイルを、文字コード変換を挟まずにそのまま PC へ保存する。
# adb exec-out の標準出力をプロセスのリダイレクトで受けるため、
# shared_prefs 内の日本語（フォルダキーなど）が壊れない
function Save-RemoteFile {
    param(
        [string]$Target,
        [string]$RemotePath,
        [string]$LocalPath
    )
    $errFile = [System.IO.Path]::GetTempFileName()
    try {
        $proc = Start-Process -FilePath $script:Adb `
            -ArgumentList @('-s', $Target, 'exec-out', 'run-as', $Package, 'cat', $RemotePath) `
            -NoNewWindow -Wait -PassThru `
            -RedirectStandardOutput $LocalPath -RedirectStandardError $errFile
        $code = $proc.ExitCode
        $errText = ''
        if (Test-Path $errFile) {
            $errText = Get-Content -LiteralPath $errFile -Raw -ErrorAction SilentlyContinue
        }
        if (-not $errText) { $errText = '' }
        if ($code -ne 0) {
            $detail = ($errText -replace '\s+', ' ').Trim()
            return [pscustomobject]@{ Ok = $false; Detail = ("run-as が失敗しました (exit {0}): {1}" -f $code, $detail) }
        }
        if (-not (Test-Path $LocalPath) -or (Get-Item -LiteralPath $LocalPath).Length -eq 0) {
            return [pscustomobject]@{ Ok = $false; Detail = '中身が空でした（run-as が使えない可能性）' }
        }
        return [pscustomobject]@{ Ok = $true; Detail = '' }
    } catch {
        return [pscustomobject]@{ Ok = $false; Detail = $_.Exception.Message }
    } finally {
        Remove-Item -LiteralPath $errFile -ErrorAction SilentlyContinue
    }
}

# apksigner verify --print-certs から証明書の SHA-256 を取り出す
#
# 出力の見出しは build-tools の版で変わる。実測した違い:
#   35.0.0 / 36.1.0 : "Signer #1 certificate SHA-256 digest: <hex>"
#   37.0.0          : "V2 Signer: certificate SHA-256 digest: <hex>"
# どちらでも拾えるよう、SHA-256 digest の行から hex を取る緩い条件にする
function Get-ApkCertSha256 {
    param([string]$ApkPath)
    $signer = Resolve-ApkSigner
    if (-not $signer) {
        return [pscustomobject]@{ Ok = $false; Reason = 'apksigner が見つかりません（Android SDK の build-tools を確認）'; Digests = @() }
    }
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & $signer verify --print-certs $ApkPath 2>&1
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
    $text = ($output | ForEach-Object { "$_" }) -join "`n"
    $digests = @()
    foreach ($m in [regex]::Matches($text, 'certificate SHA-256 digest:\s*([0-9a-fA-F]{64})')) {
        $digest = $m.Groups[1].Value.ToLower()
        if ($digests -notcontains $digest) { $digests += $digest }
    }
    if ($digests.Count -eq 0) {
        $reason = 'APK の証明書を読めませんでした'
        if ($code -ne 0) { $reason = ("apksigner が失敗しました (exit {0})" -f $code) }
        return [pscustomobject]@{ Ok = $false; Reason = $reason; Digests = @() }
    }
    return [pscustomobject]@{ Ok = $true; Reason = ''; Digests = $digests }
}

# 結果の一覧（必須/任意と成否）を貯める
$script:Items = @()
function Add-Item {
    param(
        [string]$Name,
        [ValidateSet('必須', '任意')]
        [string]$Kind,
        [bool]$Ok,
        [string]$Detail,
        [string]$Path
    )
    $script:Items += [pscustomobject]@{
        Name   = $Name
        Kind   = $Kind
        Ok     = $Ok
        Detail = $Detail
        Path   = $Path
    }
}

# 端末側の sqlite3 で主要テーブルの件数を数える
function Get-DbTableCounts {
    param(
        [string]$Target,
        [string]$DbRelPath,
        [string[]]$Tables
    )
    $rows = @()
    foreach ($table in $Tables) {
        # シェルに渡すため、SQL は単一引用符で囲んだ 1 つの文字列にする
        $command = "run-as $Package sqlite3 $DbRelPath 'select count(*) from $table'"
        $text = Invoke-AdbText @('-s', $Target, 'shell', $command)
        $trimmed = $text.Trim()
        $count = $null
        if ($trimmed -match '^\d+$') {
            $count = [int64]$trimmed
        }
        $rows += [pscustomobject]@{
            Table = $table
            Count = $count
            Raw   = ($trimmed -replace '\s+', ' ')
        }
    }
    return $rows
}

# PC 側の sqlite3 を探す（Android SDK の platform-tools にも同梱されている）
function Resolve-HostSqlite3 {
    $onPath = Get-Command sqlite3 -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }
    $sdk = Resolve-AndroidSdk
    if (-not $sdk) { return $null }
    $candidate = Join-Path $sdk 'platform-tools\sqlite3.exe'
    if (Test-Path $candidate) { return $candidate }
    return $null
}

# 端末に sqlite3 が無い場合の代替。-DbCountMode Pull のときだけ使う。
# DB を PC へ pull して PC 側の sqlite3 で数える。DB は約 300MB あるため重い。
# 取得した DB は一時ファイルなので、集計後に必ず削除する
function Get-DbTableCountsByPull {
    param(
        [string]$Target,
        [string]$DbRelPath,
        [string[]]$Tables
    )
    $hostSqlite = Resolve-HostSqlite3
    if (-not $hostSqlite) {
        return $null
    }

    $tempDb = Join-Path ([System.IO.Path]::GetTempPath()) ('fpv-db-{0}.db' -f ([guid]::NewGuid().ToString('N')))
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        # run-as で読める場所から pull する。DB はアプリ専用領域にあるため exec-out で取り出す
        $proc = Start-Process -FilePath $script:Adb `
            -ArgumentList @('-s', $Target, 'exec-out', 'run-as', $Package, 'cat', $DbRelPath) `
            -NoNewWindow -Wait -PassThru `
            -RedirectStandardOutput $tempDb -RedirectStandardError ([System.IO.Path]::GetTempFileName())
        $code = $proc.ExitCode
    } finally {
        $ErrorActionPreference = $previous
    }

    try {
        if ($code -ne 0 -or -not (Test-Path $tempDb) -or (Get-Item -LiteralPath $tempDb).Length -eq 0) {
            return $null
        }
        $rows = @()
        foreach ($table in $Tables) {
            $previous = $ErrorActionPreference
            $ErrorActionPreference = 'Continue'
            try {
                $out = & $hostSqlite $tempDb "select count(*) from $table;" 2>&1
                $sqliteCode = $LASTEXITCODE
            } finally {
                $ErrorActionPreference = $previous
            }
            $trimmed = (($out | ForEach-Object { "$_" }) -join ' ').Trim()
            $count = $null
            if ($sqliteCode -eq 0 -and $trimmed -match '^\d+$') {
                $count = [int64]$trimmed
            }
            $rows += [pscustomobject]@{
                Table = $table
                Count = $count
                Raw   = ($trimmed -replace '\s+', ' ')
            }
        }
        return $rows
    } finally {
        Remove-Item -LiteralPath $tempDb -ErrorAction SilentlyContinue
    }
}

# ---------------------------------------------------------------------------
# 前提チェック
# ---------------------------------------------------------------------------

try {
    $script:Adb = Resolve-Adb
    $target = Resolve-Serial
} catch {
    Write-Host ('前提を満たしていません: {0}' -f $_.Exception.Message) -ForegroundColor Red
    exit 2
}

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$dirName = $stamp
if ($Label) {
    $safeLabel = $Label -replace '[^\w\.\-]', '_'
    $dirName = "$stamp-$safeLabel"
}
# -OutputRoot はプロジェクトからの相対パスを既定にするが、絶対パスも受け付ける
# （Join-Path は絶対パスを連結して壊すため、絶対かどうかを先に判定する）
if ([System.IO.Path]::IsPathRooted($OutputRoot)) {
    $outputRootFull = [System.IO.Path]::GetFullPath($OutputRoot)
} else {
    $outputRootFull = [System.IO.Path]::GetFullPath((Join-Path $projectRoot $OutputRoot))
}
$backupDir = Join-Path $outputRootFull $dirName
if (-not (Test-Path $backupDir)) {
    New-Item -ItemType Directory -Force -Path $backupDir | Out-Null
}

Write-Host '=== 検証前スナップショット ===' -ForegroundColor Cyan
Write-Host ('端末    : {0}' -f $target)
Write-Host ('アプリ  : {0}' -f $Package)
Write-Host ('退避先  : {0}' -f $backupDir)
Write-Host ''

# ---------------------------------------------------------------------------
# 1. 端末情報（必須）
# ---------------------------------------------------------------------------

$propKeys = [ordered]@{
    manufacturer = 'ro.product.manufacturer'
    model        = 'ro.product.model'
    android      = 'ro.build.version.release'
    sdk          = 'ro.build.version.sdk'
    incremental  = 'ro.build.version.incremental'
    abi          = 'ro.product.cpu.abi'
}
$deviceLines = @()
$deviceValues = @{}
foreach ($key in $propKeys.Keys) {
    $value = Get-DeviceProp -Target $target -Prop $propKeys[$key]
    $deviceValues[$key] = $value
    $deviceLines += ('{0}: {1}' -f $key, $value)
}
$deviceLines += ('serial: {0}' -f $target)
$deviceLines += ''
$deviceLines += '--- ストレージ (/sdcard) ---'
$deviceLines += (Invoke-AdbText @('-s', $target, 'shell', 'df', '/sdcard'))
$deviceFile = Join-Path $backupDir 'device.txt'
Write-Utf8File -Path $deviceFile -Content (($deviceLines -join "`n") + "`n")

# model と sdk は端末を特定する最小限。どちらか欠けたら必須項目の失敗として扱う
if (-not $deviceValues['model'] -or -not $deviceValues['sdk']) {
    Add-Item -Name '端末情報' -Kind '必須' -Ok $false -Detail 'model / sdk を取得できませんでした' -Path $deviceFile
} else {
    Add-Item -Name '端末情報' -Kind '必須' -Ok $true `
        -Detail ('{0} / Android {1} (API {2})' -f $deviceValues['model'], $deviceValues['android'], $deviceValues['sdk']) `
        -Path $deviceFile
}

# ---------------------------------------------------------------------------
# 2. アプリのバージョン・署名情報（必須）
# ---------------------------------------------------------------------------

$dump = Invoke-AdbText @('-s', $target, 'shell', 'dumpsys', 'package', $Package)
$appLines = @()
foreach ($line in ($dump -split "`n")) {
    # エラー文が混ざった行を保存しない（後で署名比較に使うため）
    if ($line -match 'Unexpected token|ParserError|CategoryInfo|FullyQualifiedErrorId') { continue }
    if ($line -match 'versionCode|versionName|firstInstallTime|lastUpdateTime|pkgFlags|signatures') {
        $appLines += $line.Trim()
    }
}
$appInfoFile = Join-Path $backupDir 'app-info.txt'
Write-Utf8File -Path $appInfoFile -Content (($appLines -join "`n") + "`n")

$appText = $appLines -join "`n"
$hasVersion = $appText -match 'versionName=\S'
$hasSignature = $appText -match 'signatures=\S'
if (-not $hasVersion -or -not $hasSignature) {
    $reason = 'アプリが未インストールか、dumpsys package の出力を読めませんでした'
    if (-not $hasVersion -and $hasSignature) { $reason = 'versionName を取得できませんでした' }
    if ($hasVersion -and -not $hasSignature) { $reason = '署名情報 (signatures) を取得できませんでした' }
    Add-Item -Name 'アプリのバージョン・署名情報' -Kind '必須' -Ok $false -Detail $reason -Path $appInfoFile
} else {
    $versionName = ''
    $mVersion = [regex]::Match($appText, 'versionName=(\S+)')
    if ($mVersion.Success) { $versionName = $mVersion.Groups[1].Value }
    Add-Item -Name 'アプリのバージョン・署名情報' -Kind '必須' -Ok $true `
        -Detail ('versionName={0}' -f $versionName) -Path $appInfoFile
}

# ---------------------------------------------------------------------------
# 3. shared_prefs の内容（必須）
# ---------------------------------------------------------------------------

$prefsDir = Join-Path $backupDir 'shared_prefs'
if (-not (Test-Path $prefsDir)) {
    New-Item -ItemType Directory -Force -Path $prefsDir | Out-Null
}
$prefsListing = Invoke-AdbText @('-s', $target, 'shell', 'run-as', $Package, 'ls', 'shared_prefs')
$prefNames = @()
foreach ($line in ($prefsListing -split "`n")) {
    $name = $line.Trim()
    if ($name -match '^[\w\.\-]+\.xml$') { $prefNames += $name }
}

$savedPrefs = @()
$failedPrefs = @()
foreach ($name in $prefNames) {
    $localPref = Join-Path $prefsDir $name
    $result = Save-RemoteFile -Target $target -RemotePath ("shared_prefs/{0}" -f $name) -LocalPath $localPref
    if ($result.Ok) {
        $savedPrefs += $name
    } else {
        $failedPrefs += ('{0} ({1})' -f $name, $result.Detail)
    }
}

if ($prefNames.Count -eq 0) {
    $detail = 'shared_prefs を一覧できませんでした（run-as が使えない可能性）'
    $rawListing = ($prefsListing -replace '\s+', ' ').Trim()
    # エラー文をそのまま出すと長すぎるため、先頭だけ添える
    if ($rawListing) {
        if ($rawListing.Length -gt 200) { $rawListing = $rawListing.Substring(0, 200) + '...' }
        $detail = "$detail : $rawListing"
    }
    Add-Item -Name 'shared_prefs の内容' -Kind '必須' -Ok $false -Detail $detail -Path $prefsDir
} elseif ($failedPrefs.Count -gt 0) {
    Add-Item -Name 'shared_prefs の内容' -Kind '必須' -Ok $false `
        -Detail ('取得できなかったファイル: {0}' -f ($failedPrefs -join ' / ')) -Path $prefsDir
} else {
    Add-Item -Name 'shared_prefs の内容' -Kind '必須' -Ok $true `
        -Detail ('{0} 件: {1}' -f $savedPrefs.Count, ($savedPrefs -join ', ')) -Path $prefsDir
}

# ---------------------------------------------------------------------------
# 4. インストール済み APK の退避と証明書 SHA-256（任意）
# ---------------------------------------------------------------------------

if ($SkipInstalledApk) {
    Add-Item -Name 'インストール済み APK の退避' -Kind '任意' -Ok $false -Detail 'スキップしました (-SkipInstalledApk 指定)'
    Add-Item -Name 'APK の証明書 SHA-256' -Kind '任意' -Ok $false -Detail 'スキップしました (-SkipInstalledApk 指定)'
} else {
    $pathText = Invoke-AdbText @('-s', $target, 'shell', 'pm', 'path', $Package)
    $remoteApk = $null
    foreach ($line in ($pathText -split "`n")) {
        $m = [regex]::Match($line.Trim(), '^package:(.+)$')
        if ($m.Success) { $remoteApk = $m.Groups[1].Value.Trim(); break }
    }

    if (-not $remoteApk) {
        Add-Item -Name 'インストール済み APK の退避' -Kind '任意' -Ok $false -Detail 'pm path で APK の場所を取得できませんでした'
        Add-Item -Name 'APK の証明書 SHA-256' -Kind '任意' -Ok $false -Detail 'APK を取り出せないため確認できませんでした'
    } else {
        $localApk = Join-Path $backupDir 'installed-app.apk'
        Invoke-AdbText @('-s', $target, 'pull', $remoteApk, $localApk) | Out-Null
        if ((Test-Path $localApk) -and (Get-Item -LiteralPath $localApk).Length -gt 0) {
            $apkSize = (Get-Item -LiteralPath $localApk).Length
            Add-Item -Name 'インストール済み APK の退避' -Kind '任意' -Ok $true `
                -Detail ('{0:N1} MB' -f ($apkSize / 1MB)) -Path $localApk

            $cert = Get-ApkCertSha256 -ApkPath $localApk
            if ($cert.Ok) {
                $sigFile = Join-Path $backupDir 'apk-signature.txt'
                $sigLines = @()
                $sigLines += ('apk: {0}' -f $localApk)
                $sigLines += ('source: {0}' -f $remoteApk)
                foreach ($digest in $cert.Digests) {
                    $sigLines += ('certificate SHA-256: {0}' -f $digest)
                }
                Write-Utf8File -Path $sigFile -Content (($sigLines -join "`n") + "`n")
                Add-Item -Name 'APK の証明書 SHA-256' -Kind '任意' -Ok $true `
                    -Detail ($cert.Digests -join ', ') -Path $sigFile
            } else {
                Add-Item -Name 'APK の証明書 SHA-256' -Kind '任意' -Ok $false -Detail $cert.Reason
            }
        } else {
            Add-Item -Name 'インストール済み APK の退避' -Kind '任意' -Ok $false -Detail 'adb pull に失敗しました'
            Add-Item -Name 'APK の証明書 SHA-256' -Kind '任意' -Ok $false -Detail 'APK を取り出せないため確認できませんでした'
        }
    }
}

# ---------------------------------------------------------------------------
# 5. DB の主要テーブル件数とファイルサイズ（任意）
# ---------------------------------------------------------------------------

if ($SkipDbCounts) {
    Add-Item -Name 'DB の主要テーブル件数' -Kind '任意' -Ok $false -Detail 'スキップしました (-SkipDbCounts 指定)'
    Add-Item -Name 'DB ファイルのサイズ' -Kind '任意' -Ok $false -Detail 'スキップしました (-SkipDbCounts 指定)'
} else {
    $dbRelPath = 'databases/fastest-picture-viewer.db'
    $dbTables = @(
        'images',
        'folder_summaries',
        'folder_representatives',
        'favorite_folders',
        'favorite_images',
        'tags',
        'folder_tags',
        'excluded_folders',
        'recent_folders',
        'folder_scroll_positions',
        'folder_list_scroll_positions',
        'unreadable_images',
        'volume_sync_state',
        'sync_state'
    )

    # 端末に sqlite3 があるか確認する。無ければ DB 全体を pull せず「未取得」に留める。
    # 実機 (OPPO Reno13 A / Android 16) では sqlite3 が入っていないことを確認済み。
    # 製品ビルドの Android には sqlite3 バイナリが含まれないことが多い。
    # 判定は 1 つの方法に頼らず、実際に動かして確かめる
    $probeMethods = @(
        "run-as $Package sqlite3 --version",
        "run-as $Package sh -c 'command -v sqlite3'",
        "run-as $Package which sqlite3"
    )
    $hasSqlite = $false
    $probeDetail = ''
    foreach ($method in $probeMethods) {
        $probe = Invoke-AdbText @('-s', $target, 'shell', $method)
        $probeTrimmed = $probe.Trim()
        # "SQLite version 3.x" のような応答、または sqlite3 のパスが返れば使える
        if ($probeTrimmed -match 'SQLite version|/sqlite3$|^/system/bin/sqlite3') {
            $hasSqlite = $true
            break
        }
        if (-not $probeDetail -and $probeTrimmed) {
            $probeDetail = ($probeTrimmed -replace '\s+', ' ')
            if ($probeDetail.Length -gt 120) { $probeDetail = $probeDetail.Substring(0, 120) + '...' }
        }
    }

    if (-not $hasSqlite) {
        # 端末に sqlite3 が無い場合。-DbCountMode Pull のときだけ DB を PC へ pull して数える。
        # 既定 (Auto) では約 300MB の転送を避けるため未取得に留める
        if ($DbCountMode -eq 'Pull') {
            $rows = Get-DbTableCountsByPull -Target $target -DbRelPath $dbRelPath -Tables $dbTables
            if ($rows -eq $null) {
                Add-Item -Name 'DB の主要テーブル件数' -Kind '任意' -Ok $false `
                    -Detail 'DB を PC へ pull できませんでした（-DbCountMode Pull）'
            } else {
                $tableLines = @()
                $tableLines += ('db: {0} (PC 側の sqlite3 で集計)' -f $dbRelPath)
                $tableLines += ('取得日時: {0}' -f (Get-Date).ToString('o'))
                $tableLines += ''
                $tableLines += ('{0,-32} {1}' -f 'table', 'count')
                $missing = @()
                foreach ($row in $rows) {
                    if ($row.Count -eq $null) {
                        $tableLines += ('{0,-32} {1}' -f $row.Table, ('未取得 ({0})' -f $row.Raw))
                        $missing += $row.Table
                    } else {
                        $tableLines += ('{0,-32} {1}' -f $row.Table, $row.Count)
                    }
                }
                $tableFile = Join-Path $backupDir 'db-tables.txt'
                Write-Utf8File -Path $tableFile -Content (($tableLines -join "`n") + "`n")
                $got = @($rows | Where-Object { $_.Count -ne $null })
                if ($got.Count -eq 0) {
                    Add-Item -Name 'DB の主要テーブル件数' -Kind '任意' -Ok $false `
                        -Detail 'pull した DB から件数を取得できませんでした' -Path $tableFile
                } else {
                    Add-Item -Name 'DB の主要テーブル件数' -Kind '任意' -Ok $true `
                        -Detail ('{0}/{1} テーブル取得（PC 側で集計）' -f $got.Count, $rows.Count) -Path $tableFile
                }
            }
        } else {
            $detail = '端末に sqlite3 が無いため件数を取得できませんでした（DB 全体の pull は容量・時間のため行わない。-DbCountMode Pull で取得できる）'
            if ($probeDetail) { $detail = "$detail : $probeDetail" }
            Add-Item -Name 'DB の主要テーブル件数' -Kind '任意' -Ok $false -Detail $detail
        }
    } else {
        $rows = Get-DbTableCounts -Target $target -DbRelPath $dbRelPath -Tables $dbTables
        $tableLines = @()
        $tableLines += ('db: {0}' -f $dbRelPath)
        $tableLines += ('取得日時: {0}' -f (Get-Date).ToString('o'))
        $tableLines += ''
        $tableLines += ('{0,-32} {1}' -f 'table', 'count')
        $missing = @()
        foreach ($row in $rows) {
            if ($row.Count -eq $null) {
                $tableLines += ('{0,-32} {1}' -f $row.Table, ('未取得 ({0})' -f $row.Raw))
                $missing += $row.Table
            } else {
                $tableLines += ('{0,-32} {1}' -f $row.Table, $row.Count)
            }
        }
        $tableFile = Join-Path $backupDir 'db-tables.txt'
        Write-Utf8File -Path $tableFile -Content (($tableLines -join "`n") + "`n")

        $got = @($rows | Where-Object { $_.Count -ne $null })
        if ($got.Count -eq 0) {
            Add-Item -Name 'DB の主要テーブル件数' -Kind '任意' -Ok $false `
                -Detail 'sqlite3 はあるが件数を取得できませんでした（DB が無い、または読み取りできない）' -Path $tableFile
        } elseif ($missing.Count -gt 0) {
            Add-Item -Name 'DB の主要テーブル件数' -Kind '任意' -Ok $true `
                -Detail ('{0}/{1} テーブル取得（未取得: {2}）' -f $got.Count, $rows.Count, ($missing -join ', ')) -Path $tableFile
        } else {
            Add-Item -Name 'DB の主要テーブル件数' -Kind '任意' -Ok $true `
                -Detail ('{0} テーブル取得' -f $got.Count) -Path $tableFile
        }
    }

    # DB ファイルのサイズは sqlite3 の有無に関係なく取得できる（pull はしない）
    $dbList = Invoke-AdbText @('-s', $target, 'shell', 'run-as', $Package, 'ls', '-l', 'databases')
    $dbListTrimmed = $dbList.Trim()
    if ($dbListTrimmed -match 'fastest-picture-viewer\.db') {
        $dbFilesFile = Join-Path $backupDir 'db-files.txt'
        Write-Utf8File -Path $dbFilesFile -Content ($dbListTrimmed + "`n")
        Add-Item -Name 'DB ファイルのサイズ' -Kind '任意' -Ok $true -Detail 'databases/ の一覧を保存' -Path $dbFilesFile
    } else {
        $detail = 'databases/ を一覧できませんでした'
        if ($dbListTrimmed) { $detail = "$detail : $(($dbListTrimmed -replace '\s+', ' '))" }
        Add-Item -Name 'DB ファイルのサイズ' -Kind '任意' -Ok $false -Detail $detail
    }
}

# ---------------------------------------------------------------------------
# 6. アプリ内バックアップ JSON（任意）
# ---------------------------------------------------------------------------

# バックアップ画面は Storage Access Framework で保存先を人が選ぶため自動化できない。
# 既定のファイル名 (pictobi-backup-YYYYMMDD-HHMM.json) で保存済みなら拾う
$searchDirs = @('/sdcard/Download', '/sdcard/Documents', '/sdcard')
$foundJson = @()
foreach ($searchDir in $searchDirs) {
    $findText = Invoke-AdbText @('-s', $target, 'shell', "find $searchDir -maxdepth 1 -name 'pictobi-backup-*.json' 2>/dev/null")
    foreach ($line in ($findText -split "`n")) {
        $candidate = $line.Trim()
        if ($candidate -match '^/sdcard/.+pictobi-backup-[\w\-]+\.json$') {
            if ($foundJson -notcontains $candidate) { $foundJson += $candidate }
        }
    }
}

if ($foundJson.Count -eq 0) {
    Add-Item -Name 'アプリ内バックアップ JSON' -Kind '任意' -Ok $false `
        -Detail '端末に見つかりませんでした（アプリのバックアップ画面から書き出す必要があります。手順は docs\install-and-update.md）'
} else {
    # ファイル名に日時が入っているため、名前の降順で最新を選ぶ
    $latestJson = @($foundJson | Sort-Object -Descending)[0]
    $jsonDir = Join-Path $backupDir 'backup-json'
    if (-not (Test-Path $jsonDir)) {
        New-Item -ItemType Directory -Force -Path $jsonDir | Out-Null
    }
    $localJson = Join-Path $jsonDir (Split-Path -Leaf $latestJson)
    Invoke-AdbText @('-s', $target, 'pull', $latestJson, $localJson) | Out-Null
    if ((Test-Path $localJson) -and (Get-Item -LiteralPath $localJson).Length -gt 0) {
        Add-Item -Name 'アプリ内バックアップ JSON' -Kind '任意' -Ok $true `
            -Detail ('{0} ({1:N0} バイト)' -f $latestJson, (Get-Item -LiteralPath $localJson).Length) -Path $localJson
    } else {
        Add-Item -Name 'アプリ内バックアップ JSON' -Kind '任意' -Ok $false `
            -Detail ('adb pull に失敗しました: {0}' -f $latestJson)
    }
}

# ---------------------------------------------------------------------------
# 7. MediaStore の画像件数（任意）
# ---------------------------------------------------------------------------

$imageText = Invoke-AdbText @('-s', $target, 'shell',
    'content query --uri content://media/external/images/media --projection _id | wc -l')
# 数字だけを抜き出すと、エラー文に含まれる行番号などを件数と誤認する。
# 出力全体が 1 つの整数のときだけ件数として受け付ける
$imageTrimmed = $imageText.Trim()
$imageDigits = $null
if ($imageTrimmed -match '^\d+$') { $imageDigits = $imageTrimmed }
if ($imageDigits) {
    $imageCountFile = Join-Path $backupDir 'image-count.txt'
    Write-Utf8File -Path $imageCountFile -Content ("MediaStore 画像件数: {0}`n" -f $imageDigits)
    Add-Item -Name 'MediaStore の画像件数' -Kind '任意' -Ok $true -Detail ('{0} 件' -f $imageDigits) -Path $imageCountFile
} else {
    Add-Item -Name 'MediaStore の画像件数' -Kind '任意' -Ok $false -Detail 'content query で件数を取得できませんでした'
}

# ---------------------------------------------------------------------------
# 8. 結果の一覧と保存
# ---------------------------------------------------------------------------

$requiredFailed = @($script:Items | Where-Object { $_.Kind -eq '必須' -and -not $_.Ok })
$okItems = @($script:Items | Where-Object { $_.Ok })
$ngItems = @($script:Items | Where-Object { -not $_.Ok })

$summary = @()
$summary += ''
$summary += '=== 退避結果 ==='
$summary += ('退避先: {0}' -f $backupDir)
$summary += ''
$summary += '--- 退避した項目 ---'
if ($okItems.Count -eq 0) {
    $summary += '  (なし)'
} else {
    foreach ($item in $okItems) {
        $line = '  [{0}][OK] {1}' -f $item.Kind, $item.Name
        if ($item.Detail) { $line += (' — {0}' -f $item.Detail) }
        if ($item.Path) { $line += (' ({0})' -f $item.Path) }
        $summary += $line
    }
}
$summary += ''
$summary += '--- 取得できなかった項目 / スキップした項目 ---'
if ($ngItems.Count -eq 0) {
    $summary += '  (なし)'
} else {
    foreach ($item in $ngItems) {
        $line = '  [{0}][未取得] {1}' -f $item.Kind, $item.Name
        if ($item.Detail) { $line += (' — {0}' -f $item.Detail) }
        $summary += $line
    }
}
$summary += ''
if ($requiredFailed.Count -gt 0) {
    $summary += ('必須項目が {0} 件取得できませんでした。この状態で検証を続行しないでください。' -f $requiredFailed.Count)
} else {
    $summary += '必須項目はすべて退避しました。検証を続行できます。'
}

foreach ($line in $summary) {
    if ($line -match '\[未取得\]') {
        Write-Host $line -ForegroundColor Yellow
    } elseif ($line -match '必須項目が') {
        Write-Host $line -ForegroundColor Red
    } elseif ($line -match '必須項目はすべて') {
        Write-Host $line -ForegroundColor Green
    } elseif ($line -match '\[OK\]') {
        Write-Host $line -ForegroundColor Green
    } else {
        Write-Host $line
    }
}

# 復旧手順を退避先にも残す（人がフォルダを開いたとき分かるように）
$readmeLines = @()
$readmeLines += 'このフォルダは scripts\snapshot.ps1 が退避した検証前スナップショットです。'
$readmeLines += 'captures/ は .gitignore 済みのため Git にはコミットされません。'
$readmeLines += ''
$readmeLines += '中身:'
$readmeLines += '  device.txt        端末情報（model / Android / API / serial / ストレージ）'
$readmeLines += '  app-info.txt      アプリの versionCode / versionName / 署名 / 初回・更新日時'
$readmeLines += '  shared_prefs/     アプリの設定 XML（列数・前回の続きなど）'
$readmeLines += '  installed-app.apk 検証前に端末へ入っていた APK（任意）'
$readmeLines += '  apk-signature.txt その APK の証明書 SHA-256（任意）'
$readmeLines += '  db-tables.txt     DB の主要テーブル件数（任意）'
$readmeLines += '  db-files.txt      DB ファイルのサイズ（任意）'
$readmeLines += '  backup-json/      アプリ内バックアップ JSON（任意）'
$readmeLines += '  image-count.txt   MediaStore の画像件数（任意）'
$readmeLines += '  snapshot.json     上記の取得結果（必須/任意と成否）'
$readmeLines += ''
$readmeLines += '復旧の手順は docs\install-and-update.md の「検証前のスナップショット」を参照してください。'
$readmeLines += 'shared_prefs を端末へ戻す操作はこのスクリプトでは行いません（手作業・自己責任）。'
Write-Utf8File -Path (Join-Path $backupDir 'README.txt') -Content (($readmeLines -join "`n") + "`n")

Write-Utf8File -Path (Join-Path $backupDir 'summary.txt') -Content (($summary -join "`n") + "`n")

$reportItems = @()
foreach ($item in $script:Items) {
    $reportItems += [ordered]@{
        name   = $item.Name
        kind   = $item.Kind
        ok     = $item.Ok
        detail = $item.Detail
        path   = $item.Path
    }
}
$report = [ordered]@{
    generatedAt    = (Get-Date).ToString('o')
    serial         = $target
    package        = $Package
    outputDir      = $backupDir
    requiredFailed = $requiredFailed.Count
    items          = $reportItems
}
$report | ConvertTo-Json -Depth 6 | Set-Content -Path (Join-Path $backupDir 'snapshot.json') -Encoding UTF8

Write-Host ''
Write-Host ('保存: {0}' -f (Join-Path $backupDir 'snapshot.json')) -ForegroundColor Green

if ($requiredFailed.Count -gt 0) {
    exit 1
}
exit 0
