# OPPO Reno13 A などの実機を ADB で操作するスクリプト
# 安全のため、アンインストール・端末リセット・写真削除は実装しない。
#
# 使い方:
#   powershell -ExecutionPolicy Bypass -File scripts\device.ps1 -Action Devices
#   powershell -ExecutionPolicy Bypass -File scripts\device.ps1 -Action Info
#   powershell -ExecutionPolicy Bypass -File scripts\device.ps1 -Action Install
#   powershell -ExecutionPolicy Bypass -File scripts\device.ps1 -Action Install -Release
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
    # release 署名済み APK を導入する場合は -Release を付ける
    [switch]$Release,
    [string]$Package = 'com.tonbiattack.fastestpictureviewer',
    [string]$Activity = '.MainActivity',
    [int]$Runs = 3,
    [int]$LogLines = 500,
    [Alias('Output')]
    [string]$OutputPath,
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
    param([object[]]$Rows, [string]$Path)
    if ([string]::IsNullOrWhiteSpace($Path)) { return }
    $fullPath = [System.IO.Path]::GetFullPath((Join-Path $projectRoot $Path))
    $dir = Split-Path -Parent $fullPath
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
    $Rows | Export-Csv -Path $fullPath -NoTypeInformation -Encoding UTF8
    Write-Host ("保存しました: {0}" -f $fullPath) -ForegroundColor Green
}

# ---------------------------------------------------------------------------
# 署名の確認 (Issue #111)
#
# 署名が違う APK を上書きしようとすると INSTALL_FAILED_UPDATE_INCOMPATIBLE で
# 失敗する。この失敗自体はデータを壊さないが、そこで「アンインストールして
# 入れ直す」に進むとアプリ内データが消える。その入口で止めるための確認。
#
# 端末側の署名は dumpsys package の signatures からも取れるが、あれは
# PackageManager の Signature.hashCode() を hex にした短い値で、APK 側の
# 証明書 SHA-256 とは別物のため直接は比較できない。
#   例: signatures:[470b8fe3]  ←→  証明書 SHA-256 3a29d602...
# そのため、端末に入っている APK を取り出して apksigner に掛け、
# 証明書 SHA-256 同士で比較する。取り出せない場合だけ「比較できない」と明示する。
# ---------------------------------------------------------------------------

# apksigner は PATH か Android SDK の build-tools のうち新しいものを使う
function Resolve-ApkSigner {
    $onPath = Get-Command apksigner -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }

    $sdk = $env:ANDROID_HOME
    if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
    if (-not $sdk) { $sdk = Get-SdkDirFromLocalProperties }
    if (-not $sdk) { $sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
    $root = Join-Path $sdk 'build-tools'
    if (-not (Test-Path $root)) { return $null }
    # 名前の降順で十分（35.0.0 < 36.1.0 < 37.0.0 のような現行の採番を前提にする）
    foreach ($dir in @(Get-ChildItem $root -Directory | Sort-Object Name -Descending)) {
        $candidate = Join-Path $dir.FullName 'apksigner.bat'
        if (Test-Path $candidate) { return $candidate }
    }
    return $null
}

# apksigner verify --print-certs から証明書の SHA-256 を取り出す。
# 出力の見出しは build-tools の版で変わる（実測）:
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
    $text = (($output | ForEach-Object { "$_" }) -join "`n")
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

# 端末に入っている APK を取り出し、その証明書 SHA-256 を返す。
# 取り出した APK は一時ファイルなので、確認後に必ず削除する
function Get-InstalledApkCertSha256 {
    param([string]$Target)

    $pathText = & $script:Adb -s $Target shell pm path $Package 2>&1
    $remoteApk = $null
    foreach ($line in (($pathText | ForEach-Object { "$_" }) -split "`n")) {
        $m = [regex]::Match($line.Trim(), '^package:(.+)$')
        if ($m.Success) { $remoteApk = $m.Groups[1].Value.Trim(); break }
    }
    if (-not $remoteApk) {
        return [pscustomobject]@{ Ok = $false; Reason = '端末にアプリが入っていないか、pm path で APK の場所を取得できませんでした'; Digests = @() }
    }

    $tempApk = Join-Path ([System.IO.Path]::GetTempPath()) ('fpv-installed-{0}.apk' -f ([guid]::NewGuid().ToString('N')))
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $script:Adb -s $Target pull $remoteApk $tempApk *> $null
        $pullCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }

    try {
        if ($pullCode -ne 0 -or -not (Test-Path $tempApk) -or (Get-Item -LiteralPath $tempApk).Length -eq 0) {
            return [pscustomobject]@{ Ok = $false; Reason = ('端末の APK を取り出せませんでした (adb pull exit {0})' -f $pullCode); Digests = @() }
        }
        $cert = Get-ApkCertSha256 -ApkPath $tempApk
        if (-not $cert.Ok) {
            return [pscustomobject]@{ Ok = $false; Reason = ('端末の APK の証明書を読めませんでした: {0}' -f $cert.Reason); Digests = @() }
        }
        return [pscustomobject]@{ Ok = $true; Reason = ''; Digests = $cert.Digests }
    } finally {
        Remove-Item -LiteralPath $tempApk -ErrorAction SilentlyContinue
    }
}

# 端末側と APK 側の証明書 SHA-256 を突き合わせる。
# 戻り値の Status: Match / Mismatch / Unknown
function Compare-ApkSignature {
    param([string]$Target, [string]$ApkPath)

    $newCert = Get-ApkCertSha256 -ApkPath $ApkPath
    if (-not $newCert.Ok) {
        return [pscustomobject]@{
            Status = 'Unknown'
            Reason = ('これから入れる APK の証明書を確認できませんでした: {0}' -f $newCert.Reason)
            NewDigests = @()
            InstalledDigests = @()
        }
    }

    $installedCert = Get-InstalledApkCertSha256 -Target $Target
    if (-not $installedCert.Ok) {
        return [pscustomobject]@{
            Status = 'Unknown'
            Reason = ('端末に入っている APK の証明書を確認できませんでした: {0}' -f $installedCert.Reason)
            NewDigests = $newCert.Digests
            InstalledDigests = @()
        }
    }

    $common = @($newCert.Digests | Where-Object { $installedCert.Digests -contains $_ })
    if ($common.Count -gt 0) {
        return [pscustomobject]@{
            Status = 'Match'
            Reason = ''
            NewDigests = $newCert.Digests
            InstalledDigests = $installedCert.Digests
        }
    }
    return [pscustomobject]@{
        Status = 'Mismatch'
        Reason = ''
        NewDigests = $newCert.Digests
        InstalledDigests = $installedCert.Digests
    }
}

$script:Adb = Resolve-Adb

# -Release 指定時は署名済みリリース APK を使う (未署名 APK は導入できないため明示的に弾く)
if ($Release) {
    $Apk = 'app\build\outputs\apk\release\app-release.apk'
}

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
        Write-Output '--- インストール済みアプリ (Pictobi) ---'
        $installed = & $script:Adb -s $target shell pm list packages $Package 2>&1
        if ($installed -match [regex]::Escape($Package)) {
            $dump = & $script:Adb -s $target shell dumpsys package $Package 2>&1
            ($dump | Select-String -Pattern 'versionCode|versionName|firstInstallTime|lastUpdateTime|pkgFlags|signatures') |
                ForEach-Object { $_.Line.Trim() }
        } else {
            Write-Output '未インストール'
        }
    }

    'Install' {
        $target = Resolve-Serial
        $apkPath = [System.IO.Path]::GetFullPath((Join-Path $projectRoot $Apk))
        if (-not (Test-Path $apkPath)) {
            $hint = if ($Release) {
                "先に .\gradlew.bat :app:assembleRelease を実行し、署名情報を設定してください (docs\signing-policy.md)。"
            } else {
                "先に scripts\build-and-test.ps1 を実行してください。"
            }
            throw ("APK がありません: {0}`n{1}" -f $apkPath, $hint)
        }
        if ($apkPath -match 'unsigned') {
            throw ("未署名 APK は端末にインストールできません: {0}`n署名情報を設定し、app-release.apk を生成してください (docs\signing-policy.md)。" -f $apkPath)
        }

        # 署名の確認 (Issue #111)。署名が違うと上書きできず、その先の
        # 「アンインストールして入れ直す」でアプリ内データが消えるため、ここで止める
        Write-Host '署名を確認しています...' -ForegroundColor Cyan
        $signature = Compare-ApkSignature -Target $target -ApkPath $apkPath
        switch ($signature.Status) {
            'Match' {
                Write-Host '署名は端末の APK と一致しました。' -ForegroundColor Green
            }
            'Mismatch' {
                # アンインストールは案内しない（アプリ内データが消えるため）。
                # 同じ署名の APK を用意する方向へ誘導する。
                # 詳細は Write-Host で出し、例外は 1 行にして読みやすくする
                # （-Action Verify から呼ばれた場合もここで止まる）
                $installedText = ($signature.InstalledDigests -join ', ')
                $newText = ($signature.NewDigests -join ', ')
                Write-Host ''
                Write-Host '署名が一致しないため、インストールを中止しました。' -ForegroundColor Red
                Write-Host ''
                Write-Host ('  端末に入っている APK の証明書 SHA-256: {0}' -f $installedText)
                Write-Host ('  これから入れる APK の証明書 SHA-256  : {0}' -f $newText)
                Write-Host ''
                Write-Host 'このまま上書きすると INSTALL_FAILED_UPDATE_INCOMPATIBLE で失敗します。'
                Write-Host 'アンインストールするとアプリ内データ（お気に入り・タグ・除外フォルダ・列数設定・'
                Write-Host '前回の続き）が消えるため、このスクリプトでは案内しません。'
                Write-Host ''
                Write-Host '対処: 端末に入っている APK と 同じ署名 の APK を用意してください。'
                Write-Host '  - debug APK が入っている場合: その APK を作った PC の debug.keystore でビルドする'
                Write-Host '    （別 PC で作った debug APK は署名が異なります）'
                Write-Host '  - release APK が入っている場合: 同じ release 鍵でビルドする (docs\signing-policy.md)'
                Write-Host '  - どちらが入っているかは scripts\device.ps1 -Action Info の pkgFlags で確認できます'
                Write-Host '    （DEBUGGABLE があれば debug APK）'
                Write-Host ''
                Write-Host '署名を確認したうえで入れ替えたい場合は、先に scripts\snapshot.ps1 で退避し、'
                Write-Host 'データが失われることを承知のうえで人が判断してください (docs\install-and-update.md)。'
                throw '署名が一致しないため、インストールを中止しました（アンインストールは行わないでください）。'
            }
            default {
                # 比較できない場合。何が確認できなかったかを明示して続行する
                Write-Host '' -ForegroundColor Yellow
                Write-Host '警告: 署名を比較できませんでした。' -ForegroundColor Yellow
                Write-Host ('  理由: {0}' -f $signature.Reason) -ForegroundColor Yellow
                if ($signature.NewDigests.Count -gt 0) {
                    Write-Host ('  これから入れる APK の証明書 SHA-256: {0}' -f ($signature.NewDigests -join ', ')) -ForegroundColor Yellow
                }
                if ($signature.InstalledDigests.Count -gt 0) {
                    Write-Host ('  端末に入っている APK の証明書 SHA-256: {0}' -f ($signature.InstalledDigests -join ', ')) -ForegroundColor Yellow
                }
                Write-Host '  署名が違う場合は INSTALL_FAILED_UPDATE_INCOMPATIBLE で失敗します。' -ForegroundColor Yellow
                Write-Host '  その失敗ではアプリデータは消えません。アンインストールはしないでください。' -ForegroundColor Yellow
                Write-Host '  確認できないまま続行します（インストール自体は試行します）。' -ForegroundColor Yellow
                Write-Host '' -ForegroundColor Yellow
            }
        }

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
        if ($OutputPath) {
            $path = [System.IO.Path]::GetFullPath((Join-Path $projectRoot $OutputPath))
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
        Write-CsvIfRequested -Rows $rows -Path $OutputPath
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
        & $PSCommandPath -Action Logs -Serial $target -LogLines $LogLines -OutputPath "captures\logcat-$stamp.txt"

        Write-Host '実機での操作感・一覧消失の確認は docs\device-verification.md のチェックリストに沿って人が行ってください。' -ForegroundColor Yellow
    }
}
