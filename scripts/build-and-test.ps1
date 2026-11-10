# 単体テストと debug APK ビルドを再実行するスクリプト
# 使い方: powershell -ExecutionPolicy Bypass -File scripts\build-and-test.ps1
#   テストのみ:   -SkipBuild
#   APK のみ:     -SkipTests
# 終了コード: 0 = 成功 / それ以外 = Gradle の失敗コード

[CmdletBinding()]
param(
    [switch]$SkipTests,
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot

if ($SkipTests -and $SkipBuild) {
    Write-Host '-SkipTests と -SkipBuild を同時に指定すると実行する処理がありません。' -ForegroundColor Red
    exit 2
}

$gradlew = Join-Path $projectRoot 'gradlew.bat'
if (-not (Test-Path $gradlew)) {
    Write-Host ('gradlew.bat が見つかりません: {0}' -f $gradlew) -ForegroundColor Red
    exit 2
}

function Invoke-Gradle {
    param([string]$Task)

    Write-Host ('=== gradlew {0} ===' -f $Task) -ForegroundColor Cyan
    Push-Location $projectRoot
    try {
        & $gradlew $Task '--console=plain'
        $code = $LASTEXITCODE
    } finally {
        Pop-Location
    }

    if ($code -ne 0) {
        Write-Host ('{0} が失敗しました (exit {1})' -f $Task, $code) -ForegroundColor Red
        exit $code
    }
}

if (-not $SkipTests) {
    Invoke-Gradle -Task ':app:testDebugUnitTest'
}

if (-not $SkipBuild) {
    Invoke-Gradle -Task ':app:assembleDebug'

    $apk = Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk'
    if (-not (Test-Path $apk)) {
        Write-Host ('APK が見つかりません: {0}' -f $apk) -ForegroundColor Red
        exit 1
    }

    $info = Get-Item $apk
    Write-Host ''
    Write-Host '[OK] debug APK を生成しました' -ForegroundColor Green
    Write-Host ('     {0}' -f $info.FullName)
    Write-Host ('     {0:N1} MB / {1:yyyy-MM-dd HH:mm:ss}' -f ($info.Length / 1MB), $info.LastWriteTime)
}

Write-Host ''
Write-Host '完了しました。' -ForegroundColor Green
