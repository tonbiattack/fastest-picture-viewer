# Google Play 用の AAB をビルドして検証する (Issue #121)
#
# 使い方:
#   powershell -ExecutionPolicy Bypass -File scripts\build-aab.ps1
#   powershell -ExecutionPolicy Bypass -File scripts\build-aab.ps1 -VersionCode 2 -VersionName 0.2.0
#
# 前提:
#   - JDK 17 以上と Android SDK が設定済み
#   - リリース署名の情報が次のどちらかにある
#       1. 環境変数 FPV_KEYSTORE_PATH / FPV_KEYSTORE_PASSWORD / FPV_KEY_ALIAS / FPV_KEY_PASSWORD
#       2. %USERPROFILE%\.android-signing\pictobi-release.properties
#
# 安全:
#   - 署名鍵・パスワードは表示しない。ファイルの存在だけを確認する
#   - 鍵やパスワードをリポジトリへ書き出さない
#
# 出力:
#   app\build\outputs\bundle\release\app-release.aab

[CmdletBinding()]
param(
    # versionCode を上書きする（未指定なら build.gradle.kts の既定値）
    [int]$VersionCode = 0,

    # versionName を上書きする（未指定なら build.gradle.kts の既定値）
    [string]$VersionName = '',

    # ビルド後に署名の検証だけ行い、AAB を作らない
    [switch]$VerifyOnly
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$gradlew = Join-Path $projectRoot 'gradlew.bat'
$aabPath = Join-Path $projectRoot 'app\build\outputs\bundle\release\app-release.aab'

function Write-Step {
    param([string]$Message)
    Write-Host ''
    Write-Host ("=== {0} ===" -f $Message) -ForegroundColor Cyan
}

# 署名情報が揃っているかを確認する。値そのものは表示しない
function Test-SigningConfigured {
    $propsFile = Join-Path $env:USERPROFILE '.android-signing\pictobi-release.properties'
    $hasProps = Test-Path $propsFile

    $envKeys = @(
        'FPV_KEYSTORE_PATH',
        'FPV_KEYSTORE_PASSWORD',
        'FPV_KEY_ALIAS',
        'FPV_KEY_PASSWORD'
    )
    $missingEnv = @($envKeys | Where-Object { -not (Get-Item "env:$_" -ErrorAction SilentlyContinue) })

    if ($hasProps) {
        Write-Host ("署名設定ファイル: あり ({0})" -f $propsFile) -ForegroundColor Green
        return $true
    }
    if ($missingEnv.Count -eq 0) {
        Write-Host '署名設定: 環境変数で設定済み' -ForegroundColor Green
        return $true
    }

    Write-Host '署名設定が見つかりません。' -ForegroundColor Yellow
    Write-Host ("  探したファイル: {0}" -f $propsFile)
    Write-Host ("  未設定の環境変数: {0}" -f ($missingEnv -join ', '))
    Write-Host '  署名なしでは Play にアップロードできません（未署名 AAB は拒否されます）。'
    Write-Host '  手順は docs\signing-policy.md を参照してください。'
    return $false
}

# AAB の署名を jarsigner で検証する
function Test-AabSignature {
    param([string]$Path)

    $jarsigner = 'jarsigner'
    if ($env:JAVA_HOME) {
        $candidate = Join-Path $env:JAVA_HOME 'bin\jarsigner.exe'
        if (Test-Path $candidate) { $jarsigner = $candidate }
    }

    # 出力が日本語ロケールだと "jar verified" が出ないため、英語に固定する
    $previousLang = $env:JAVA_TOOL_OPTIONS
    $env:JAVA_TOOL_OPTIONS = '-Duser.language=en -Duser.country=US'
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & $jarsigner -verify $Path 2>&1
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
        if ($null -eq $previousLang) {
            Remove-Item env:JAVA_TOOL_OPTIONS -ErrorAction SilentlyContinue
        } else {
            $env:JAVA_TOOL_OPTIONS = $previousLang
        }
    }
    $text = ($output | ForEach-Object { "$_" }) -join "`n"

    # 自己署名証明書のため PKIX の警告は正常。jar verified が出れば署名自体は有効
    if ($text -match 'jar verified') {
        Write-Host '署名: 有効（jar verified）' -ForegroundColor Green
        return $true
    }
    Write-Host '署名: 検証できませんでした' -ForegroundColor Red
    Write-Host $text
    return $false
}

# AAB の versionName を Gradle から取得する。
# BundleConfig.pb は protobuf で、フィールド名が入らないため直接は読めない。
# ビルドに使った値は build.gradle.kts と -P の指定なので、そこから求める
function Get-BuildVersion {
    param([int]$VersionCode, [string]$VersionName)

    if ($VersionCode -gt 0 -or $VersionName) {
        return [pscustomobject]@{
            Code = $(if ($VersionCode -gt 0) { "$VersionCode" } else { '(既定)' })
            Name = $(if ($VersionName) { $VersionName } else { '(既定)' })
        }
    }

    # build.gradle.kts の既定値を読む。
    # バージョンは `val defaultVersionName = "x.y.z"` に集約されており、
    # versionCode はそこから計算される（versionCodeOf）。
    $gradleFile = Join-Path $projectRoot 'app\build.gradle.kts'
    $code = '(不明)'
    $name = '(不明)'
    if (Test-Path $gradleFile) {
        $text = Get-Content $gradleFile -Raw
        $m = [regex]::Match($text, 'val defaultVersionName = "([^"]+)"')
        if ($m.Success) {
            $name = $m.Groups[1].Value
            # versionCodeOf と同じ計算式: major*10000 + minor*100 + patch
            $parts = $name.Split('.')
            if ($parts.Count -eq 3) {
                $nums = $parts | ForEach-Object { [int]$_ }
                $code = "$($nums[0] * 10000 + $nums[1] * 100 + $nums[2])"
            }
        }
    }
    return [pscustomobject]@{ Code = $code; Name = $name }
}

Write-Step '前提の確認'
if (-not (Test-Path $gradlew)) {
    throw ("gradlew.bat が見つかりません: {0}" -f $gradlew)
}
$signingOk = Test-SigningConfigured

if ($VerifyOnly) {
    if (-not (Test-Path $aabPath)) {
        throw ("AAB がありません: {0}`n先にビルドしてください。" -f $aabPath)
    }
    Write-Step 'AAB の検証'
    $info = Get-Item $aabPath
    Write-Host ("ファイル: {0}" -f $info.FullName)
    Write-Host ("サイズ: {0:N1} MB" -f ($info.Length / 1MB))
    Write-Host ("更新日時: {0}" -f $info.LastWriteTime)
    $version = Get-BuildVersion -VersionCode $VersionCode -VersionName $VersionName
    Write-Host ("versionCode: {0} / versionName: {1}" -f $version.Code, $version.Name)
    $ok = Test-AabSignature -Path $aabPath
    if (-not $ok) { exit 1 }
    exit 0
}

if (-not $signingOk) {
    # 署名なしでも AAB 自体は作れるが、Play には出せない。
    # 黙って未署名 AAB を渡さないよう、ここで止める
    throw '署名情報が無いため中止します。docs\signing-policy.md を確認してください。'
}

Write-Step 'AAB のビルド'
$gradleArgs = @(':app:bundleRelease', '--console=plain')
if ($VersionCode -gt 0) { $gradleArgs += "-Pfpv.versionCode=$VersionCode" }
if ($VersionName) { $gradleArgs += "-Pfpv.versionName=$VersionName" }

Write-Host ("実行: gradlew.bat {0}" -f ($gradleArgs -join ' '))
& $gradlew @gradleArgs
if ($LASTEXITCODE -ne 0) {
    throw ("AAB のビルドに失敗しました (exit {0})" -f $LASTEXITCODE)
}

Write-Step 'AAB の検証'
if (-not (Test-Path $aabPath)) {
    throw ("AAB が生成されていません: {0}" -f $aabPath)
}
$info = Get-Item $aabPath
Write-Host ("ファイル: {0}" -f $info.FullName)
Write-Host ("サイズ: {0:N1} MB" -f ($info.Length / 1MB))
Write-Host ("更新日時: {0}" -f $info.LastWriteTime)

$ver = Get-BuildVersion -VersionCode $VersionCode -VersionName $VersionName
Write-Host ("versionCode: {0} / versionName: {1}" -f $ver.Code, $ver.Name)

$ok = Test-AabSignature -Path $aabPath
if (-not $ok) {
    throw 'AAB の署名を検証できませんでした。Play にアップロードしないでください。'
}

Write-Step '完了'
Write-Host 'Play Console の「内部テスト」トラックへアップロードできます。' -ForegroundColor Green
Write-Host 'アップロード前に docs\store-listing.md の申告内容を確認してください。'
Write-Host ''
Write-Host '注意: この AAB はアップロード鍵で署名されています。'
Write-Host 'Play App Signing を有効にすると、配布用の署名は Google が管理します。'
Write-Host 'アップロード鍵を失うと更新版を出せなくなります。docs\signing-policy.md を参照してください。'
