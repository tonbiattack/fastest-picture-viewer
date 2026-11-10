# Windows 11 での Android ビルド・テスト環境を確認するスクリプト
# 使い方: powershell -ExecutionPolicy Bypass -File scripts\check-env.ps1
# 終了コード: 0 = 必須項目がすべて揃っている / 1 = 不足あり

[CmdletBinding()]
param(
    # adb が見つからない場合も警告に留め、失敗扱いにしない（ビルドには不要なため）
    [switch]$StrictAdb
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot

$script:NgCount = 0

function Write-Result {
    param(
        [string]$Name,
        [bool]$Ok,
        [string]$Detail
    )
    if ($Ok) {
        Write-Host ("[OK]   {0,-16} {1}" -f $Name, $Detail) -ForegroundColor Green
    } else {
        Write-Host ("[NG]   {0,-16} {1}" -f $Name, $Detail) -ForegroundColor Red
        $script:NgCount++
    }
}

# local.properties の sdk.dir を読み取り、Java プロパティ形式のエスケープを戻す
function Get-SdkDirFromLocalProperties {
    $localProperties = Join-Path $projectRoot 'local.properties'
    if (-not (Test-Path $localProperties)) {
        return $null
    }

    $line = Get-Content $localProperties |
        Where-Object { $_ -match '^\s*sdk\.dir\s*=' } |
        Select-Object -First 1
    if (-not $line) {
        return $null
    }

    $value = ($line -split '=', 2)[1].Trim()
    # "C\:\\path" のようなエスケープを元に戻す
    $value = $value -replace '\\\\', '\' -replace '\\:', ':' -replace '\\=', '='
    return $value
}

# Android SDK の場所を環境変数 -> local.properties -> 既定パスの順で解決する
function Resolve-AndroidSdk {
    foreach ($candidate in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)) {
        if ($candidate -and (Test-Path $candidate)) {
            return $candidate
        }
    }

    $fromLocal = Get-SdkDirFromLocalProperties
    if ($fromLocal -and (Test-Path $fromLocal)) {
        return $fromLocal
    }

    $default = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
    if (Test-Path $default) {
        return $default
    }

    return $null
}

Write-Host '=== fastest-picture-viewer ビルド環境チェック ===' -ForegroundColor Cyan
Write-Host ("プロジェクト: {0}" -f $projectRoot)
Write-Host ''

# --- Git ---
$git = Get-Command git -ErrorAction SilentlyContinue
if ($git) {
    $gitVersion = (& git --version).Trim()
    Write-Result -Name 'Git' -Ok $true -Detail $gitVersion
} else {
    Write-Result -Name 'Git' -Ok $false -Detail 'git が見つかりません (PATH を確認)'
}

# --- Java / JAVA_HOME ---
$java = Get-Command java -ErrorAction SilentlyContinue
if ($java) {
    # java -version は標準エラーへ出力されるため、取得時だけ Stop を解除する
    $prevErrorAction = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $javaVersionRaw = (& java -version 2>&1 | Out-String)
    } finally {
        $ErrorActionPreference = $prevErrorAction
    }
    # "java.exe : openjdk ..." のように付くプレフィックスを除く
    $javaVersion = $javaVersionRaw.Trim().Split("`n")[0].Trim() -replace '^[^:]+ : ', ''
    Write-Result -Name 'Java' -Ok $true -Detail $javaVersion
} else {
    Write-Result -Name 'Java' -Ok $false -Detail 'java が見つかりません (JDK 17 以上が必要)'
}

if ($env:JAVA_HOME) {
    $javaHomeOk = Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe')
    Write-Result -Name 'JAVA_HOME' -Ok $javaHomeOk -Detail $env:JAVA_HOME
} else {
    Write-Result -Name 'JAVA_HOME' -Ok $false -Detail '未設定 (Gradle が使用する JDK を明示することを推奨)'
}

# --- Android SDK ---
$sdk = Resolve-AndroidSdk
if ($sdk) {
    Write-Result -Name 'Android SDK' -Ok $true -Detail $sdk
} else {
    Write-Result -Name 'Android SDK' -Ok $false -Detail 'SDK を特定できません (local.properties の sdk.dir を設定)'
}

# ANDROID_HOME / ANDROID_SDK_ROOT がユーザー環境変数として設定されているか
$userAndroidHome = [Environment]::GetEnvironmentVariable('ANDROID_HOME', 'User')
Write-Result -Name 'ANDROID_HOME' -Ok ([bool]$userAndroidHome) -Detail $(if ($userAndroidHome) { $userAndroidHome } else { '未設定' })
$userSdkRoot = [Environment]::GetEnvironmentVariable('ANDROID_SDK_ROOT', 'User')
Write-Result -Name 'ANDROID_SDK_ROOT' -Ok ([bool]$userSdkRoot) -Detail $(if ($userSdkRoot) { $userSdkRoot } else { '未設定' })

if ($sdk) {
    # compileSdk 36 に対応するプラットフォーム
    $platform = Join-Path $sdk 'platforms\android-36'
    Write-Result -Name 'Platform 36' -Ok (Test-Path $platform) -Detail $platform

    # コマンドラインからの SDK 管理 (sdkmanager / android CLI)
    $sdkManager = Join-Path $sdk 'cmdline-tools\latest\bin\sdkmanager.bat'
    Write-Result -Name 'cmdline-tools' -Ok (Test-Path $sdkManager) -Detail $sdkManager

    # Build Tools は存在するバージョンのうち最新を表示する
    $buildToolsRoot = Join-Path $sdk 'build-tools'
    $buildTools = @()
    if (Test-Path $buildToolsRoot) {
        $buildTools = Get-ChildItem $buildToolsRoot -Directory | Sort-Object Name
    }
    if ($buildTools.Count -gt 0) {
        $latest = $buildTools[-1].Name
        Write-Result -Name 'Build Tools' -Ok $true -Detail ("{0} ({1} 件)" -f $latest, $buildTools.Count)
    } else {
        Write-Result -Name 'Build Tools' -Ok $false -Detail 'build-tools が見つかりません'
    }

    # adb はビルドには不要。実機接続 (#11) で使用する
    $adbPath = Join-Path $sdk 'platform-tools\adb.exe'
    if (Test-Path $adbPath) {
        $adbVersion = (& $adbPath version | Select-Object -First 1).Trim()
        Write-Result -Name 'adb' -Ok $true -Detail ("{0} / {1}" -f $adbVersion, $adbPath)
        $adbOnPath = Get-Command adb -ErrorAction SilentlyContinue
        $platformToolsInUserPath = ([Environment]::GetEnvironmentVariable('Path', 'User') -split ';') -contains (Join-Path $sdk 'platform-tools')
        if (-not $adbOnPath -and -not $platformToolsInUserPath) {
            Write-Host '       adb は PATH にないため、実機操作では上記フルパスを使用してください。' -ForegroundColor Yellow
        } elseif (-not $adbOnPath -and $platformToolsInUserPath) {
            Write-Host '       adb の PATH は設定済みです。新しいターミナルを開くと adb を直接実行できます。' -ForegroundColor Yellow
        }
    } else {
        $ok = -not $StrictAdb
        Write-Result -Name 'adb' -Ok $ok -Detail 'platform-tools が見つかりません (実機接続には必要)'
    }
}

# --- Gradle wrapper ---
$gradlew = Join-Path $projectRoot 'gradlew.bat'
Write-Result -Name 'gradlew.bat' -Ok (Test-Path $gradlew) -Detail $gradlew

Write-Host ''
if ($script:NgCount -eq 0) {
    Write-Host '必須項目はすべて揃っています。scripts\build-and-test.ps1 でテストとビルドを実行できます。' -ForegroundColor Green
    exit 0
} else {
    Write-Host ("不足している項目が {0} 件あります。docs\build-environment.md を参照してください。" -f $script:NgCount) -ForegroundColor Red
    exit 1
}
