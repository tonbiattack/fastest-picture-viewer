# バージョンを上げる (AGENTS.md「バージョンの更新」)
#
# Obtainium は「リリースのタグ」と「APK の versionCode」を見て更新を判定する。
# コードを変更したら、このスクリプトで build.gradle.kts の既定値を上げてから
# コミットし、マージ後にタグを切る。
#
# 使い方:
#   powershell -ExecutionPolicy Bypass -File scripts\bump-version.ps1 -Part patch
#   powershell -ExecutionPolicy Bypass -File scripts\bump-version.ps1 -Part minor
#   powershell -ExecutionPolicy Bypass -File scripts\bump-version.ps1 -Part major
#   powershell -ExecutionPolicy Bypass -File scripts\bump-version.ps1 -Set 0.3.0
#
# 何も指定しないと現在のバージョンを表示するだけ（確認用）。

[CmdletBinding()]
param(
    # 上げる桁。patch / minor / major
    [ValidateSet('patch', 'minor', 'major')]
    [string]$Part,

    # バージョンを直接指定する（-Part とは併用しない）
    [string]$Set,

    # 変更せず、現在のバージョンだけ表示する
    [switch]$Show
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$gradleFile = Join-Path $projectRoot 'app\build.gradle.kts'

# versionName から versionCode を求める。release.yml と同じ計算式
function Get-VersionCode {
    param([string]$VersionName)
    $parts = $VersionName.Split('.')
    if ($parts.Count -ne 3) {
        throw "versionName は major.minor.patch 形式にしてください: $VersionName"
    }
    $nums = $parts | ForEach-Object {
        $n = 0
        if (-not [int]::TryParse($_, [ref]$n)) {
            throw "versionName に数値でない部分があります: $VersionName"
        }
        $n
    }
    return $nums[0] * 10000 + $nums[1] * 100 + $nums[2]
}

# build.gradle.kts から現在の既定 versionName を読む
function Get-CurrentVersion {
    param([string]$Path)
    $text = Get-Content $Path -Raw -Encoding UTF8
    $m = [regex]::Match($text, 'val defaultVersionName = "([^"]+)"')
    if (-not $m.Success) {
        throw "build.gradle.kts に defaultVersionName が見つかりません"
    }
    return $m.Groups[1].Value
}

# 次のバージョンを計算する
function Get-NextVersion {
    param([string]$Current, [string]$BumpPart, [string]$Explicit)
    if ($Explicit) { return $Explicit }

    $parts = $Current.Split('.') | ForEach-Object { [int]$_ }
    $major, $minor, $patch = $parts
    switch ($BumpPart) {
        'major' { $major++; $minor = 0; $patch = 0 }
        'minor' { $minor++; $patch = 0 }
        'patch' { $patch++ }
    }
    return "$major.$minor.$patch"
}

$current = Get-CurrentVersion -Path $gradleFile
$currentCode = Get-VersionCode -VersionName $current

if ($Show -or (-not $Part -and -not $Set)) {
    Write-Host ("現在のバージョン: {0} (versionCode={1})" -f $current, $currentCode)
    Write-Host ''
    Write-Host '上げるには -Part patch|minor|major か -Set x.y.z を指定してください。'
    exit 0
}

if ($Part -and $Set) {
    throw '-Part と -Set は同時に指定できません'
}

$next = Get-NextVersion -Current $current -BumpPart $Part -Explicit $Set
$nextCode = Get-VersionCode -VersionName $next

if ($nextCode -le $currentCode) {
    throw ("versionCode が増えません: {0}({1}) -> {2}({3})" -f $current, $currentCode, $next, $nextCode)
}

# 既定値だけを書き換える（-P での上書きには影響しない）
$text = Get-Content $gradleFile -Raw -Encoding UTF8
$updated = $text -replace 'val defaultVersionName = "[^"]+"', "val defaultVersionName = `"$next`""
if ($updated -eq $text) {
    throw 'build.gradle.kts を書き換えられませんでした'
}
# BOM なし UTF-8 で保存する（Kotlin のソースとして読ませるため）
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText($gradleFile, $updated, $utf8NoBom)

Write-Host ("バージョンを上げました: {0}({1}) -> {2}({3})" -f $current, $currentCode, $next, $nextCode) -ForegroundColor Green
Write-Host ''
Write-Host '次の手順:'
Write-Host ("  1. コミットに含める（app/build.gradle.kts）")
Write-Host ("  2. PR をマージする")
Write-Host ("  3. タグを切ってリリースする: git tag v{0}; git push origin v{0}" -f $next)
Write-Host ''
Write-Host '注意: タグを切らないと Obtainium は更新を検知できません。'
