# Android ビルド環境をセットアップするスクリプト (Windows 11)
# - Android SDK の場所を解決する
# - cmdline-tools;latest が無ければ公式 zip を取得して配置する
# - ANDROID_HOME / ANDROID_SDK_ROOT とユーザー PATH を設定する
# 使い方:
#   powershell -ExecutionPolicy Bypass -File scripts\setup-android-env.ps1
#   powershell -ExecutionPolicy Bypass -File scripts\setup-android-env.ps1 -Force
# 注意: Android Studio 本体と SDK ライセンス同意は人間が行う前提。

[CmdletBinding()]
param(
    # Android SDK のパスを明示したい場合
    [string]$SdkPath,

    # cmdline-tools を再ダウンロードして上書きする
    [switch]$Force,

    # 環境変数と PATH の設定をスキップする
    [switch]$SkipEnvironment
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot

# 公式のコマンドラインツール (revision 23)。URL と SHA1 は固定して検証する
$cmdlineToolsUrl = 'https://dl.google.com/android/repository/commandlinetools-win-16111833_latest.zip'
$cmdlineToolsSha1 = '57d04f2d75eb8e8fffc5000a987e5de4b5a63e9d'

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

function Resolve-SdkPath {
    if ($SdkPath -and (Test-Path $SdkPath)) { return $SdkPath }
    foreach ($candidate in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)) {
        if ($candidate -and (Test-Path $candidate)) { return $candidate }
    }
    $fromLocal = Get-SdkDirFromLocalProperties
    if ($fromLocal -and (Test-Path $fromLocal)) { return $fromLocal }
    $default = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
    if (Test-Path $default) { return $default }
    return $null
}

$sdk = Resolve-SdkPath
if (-not $sdk) {
    Write-Host 'Android SDK が見つかりません。Android Studio の SDK Manager で導入するか、-SdkPath を指定してください。' -ForegroundColor Red
    exit 1
}
Write-Host ("Android SDK: {0}" -f $sdk) -ForegroundColor Cyan

# --- Java 確認 ---
$java = Get-Command java -ErrorAction SilentlyContinue
if (-not $java) {
    Write-Host 'Java (JDK 17 以上) が見つかりません。JAVA_HOME と PATH を設定してください。' -ForegroundColor Red
    exit 1
}

# --- cmdline-tools の配置 ---
$cmdlineToolsRoot = Join-Path $sdk 'cmdline-tools'
$latestDir = Join-Path $cmdlineToolsRoot 'latest'
$sdkManager = Join-Path $latestDir 'bin\sdkmanager.bat'

if ((Test-Path $sdkManager) -and -not $Force) {
    Write-Host 'cmdline-tools は既に配置されています。' -ForegroundColor Green
} else {
    $work = Join-Path $env:TEMP ('cmdline-tools-' + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Force -Path $work | Out-Null
    $zip = Join-Path $work 'commandlinetools.zip'
    try {
        Write-Host 'コマンドラインツールをダウンロードしています...' -ForegroundColor Cyan
        Invoke-WebRequest -Uri $cmdlineToolsUrl -OutFile $zip

        $hash = (Get-FileHash $zip -Algorithm SHA1).Hash.ToLower()
        if ($hash -ne $cmdlineToolsSha1) {
            throw ("SHA1 が一致しません: {0}" -f $hash)
        }

        $extract = Join-Path $work 'extract'
        Expand-Archive -Path $zip -DestinationPath $extract -Force
        $source = Join-Path $extract 'cmdline-tools'
        if (-not (Test-Path $source)) {
            throw '展開先に cmdline-tools ディレクトリがありません'
        }

        New-Item -ItemType Directory -Force -Path $cmdlineToolsRoot | Out-Null
        if (Test-Path $latestDir) { Remove-Item $latestDir -Recurse -Force }
        Move-Item $source $latestDir
        Write-Host 'cmdline-tools を配置しました。' -ForegroundColor Green
    } finally {
        Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue
    }
}

# --- 環境変数と PATH ---
if (-not $SkipEnvironment) {
    [Environment]::SetEnvironmentVariable('ANDROID_HOME', $sdk, 'User')
    [Environment]::SetEnvironmentVariable('ANDROID_SDK_ROOT', $sdk, 'User')

    $entries = @(
        (Join-Path $sdk 'platform-tools'),
        (Join-Path $sdk 'cmdline-tools\latest\bin'),
        (Join-Path $sdk 'emulator')
    )
    $userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
    if (-not $userPath) { $userPath = '' }
    $parts = @($userPath -split ';' | Where-Object { $_ -ne '' })
    foreach ($entry in $entries) {
        if ($parts -notcontains $entry) { $parts += $entry }
    }
    [Environment]::SetEnvironmentVariable('Path', ($parts -join ';'), 'User')

    Write-Host 'ANDROID_HOME / ANDROID_SDK_ROOT / PATH を設定しました。' -ForegroundColor Green
    Write-Host '新しいターミナルを開くと adb / sdkmanager を直接実行できます。' -ForegroundColor Yellow
}

Write-Host ''
Write-Host '次は scripts\check-env.ps1 で確認してください。' -ForegroundColor Cyan
