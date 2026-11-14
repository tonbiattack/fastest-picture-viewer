# APK の導入・更新手順（Pictobi）

Windows PC から USB 接続した端末へ APK を入れる／更新する手順をまとめる。
対象端末は個人所有の **OPPO Reno13 A**（他機種は対象外）。

> **最初に読むこと:** 「更新」と「新規インストール」は別物。さらに **debug APK と release APK** は
> 署名鍵が違うため、互いに上書きできない。今どちらの状態かを必ず確認する。

## 0. 結論（最短手順）

### すでに同じ署名の APK が入っていて、データを残して更新したい

```powershell
git switch main
git pull --ff-only origin main
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
adb devices                                  # 端末が 1 台だけ表示されることを確認
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

`-r` は「置き換え（上書き）」の意味。**同じ applicationId ＋ 同じ署名鍵**なら
アプリ内データ（Room のキャッシュ、お気に入り、タグ、設定）はそのまま残る。

### 何も入っていない端末に新規で入れたい

```powershell
.\gradlew.bat :app:assembleDebug
adb install .\app\build\outputs\apk\debug\app-debug.apk
```

初回は画像アクセス権限のダイアログが出るので「許可」する。

### リリース署名した APK で配布・更新したい

`docs/signing-policy.md` の手順で鍵を作り、署名情報を環境変数かリポジトリ外の
プロパティファイルに設定してから:

```powershell
.\gradlew.bat :app:assembleRelease
adb install -r .\app\build\outputs\apk\release\app-release.apk
```

署名情報が未設定だと `app-release-unsigned.apk` ができ、**端末にはインストールできない**
（`INSTALL_PARSE_FAILED_NO_CERTIFICATES`）。

## 1. 前提（最初の 1 回だけ）

1. Windows に JDK 17 以上と Android SDK を入れる → `docs/build-environment.md`
2. 端末の「設定 → 端末情報 → ビルド番号」を 7 回タップして開発者向けオプションを有効化
3. 「開発者向けオプション → USB デバッグ」をオン
4. USB ケーブル（データ通信対応）で PC と接続し、端末に出る
   「USB デバッグを許可しますか？」を本人が許可
5. 認識確認:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\device.ps1 -Action Devices
# または
adb devices -l
```

`unauthorized` と出る場合は端末側の許可ダイアログが未承認。
`offline` の場合はケーブルを差し直す。

## 2. 更新と新規の違い

| やりたいこと | コマンド | データ | 条件 |
| --- | --- | --- | --- |
| データを残して更新 | `adb install -r <apk>` | 残る | 同じ applicationId・**同じ署名鍵** |
| 新規インストール | `adb install <apk>` | 新規作成 | 未インストール、または `-r` で上書き |
| 版を下げる（非推奨） | `adb install -r -d <apk>` | 残る | 同じ署名鍵 |
| 完全に入れ直す | `adb uninstall <pkg>` → `adb install <apk>` | **消える** | 署名は何でもよい |

`adb uninstall` はアプリ内データ（キャッシュ・お気に入り・タグ・設定）を削除する。
**写真そのものは消えない。** 破壊的なので、必要なとき以外は実行しない。

### 署名が違うとどうなるか

- debug APK と release APK は別の鍵で署名されるため、**上書きできない**
- エラー例: `INSTALL_FAILED_UPDATE_INCOMPATIBLE: Package ... signatures do not match`
- この場合の選択肢は 2 つ
  1. 今入っているのと同じ種類の APK（debug なら debug）で更新する
  2. 一度アンインストールして入れ直す（アプリ内データは失われる）

現在の端末に入っている APK の種類は次のコマンドで判別できる。

```powershell
adb shell dumpsys package com.tonbiattack.fastestpictureviewer | Select-String "pkgFlags"
# DEBUGGABLE が含まれていれば debug APK
```

## 3. どの APK を入れるか決める

| 用途 | ビルド | 出力パス | 署名 |
| --- | --- | --- | --- |
| 日常の開発・動作確認 | `:app:assembleDebug` | `app\build\outputs\apk\debug\app-debug.apk` | Android SDK の debug.keystore |
| 長期運用・配布 | `:app:assembleRelease` | `app\build\outputs\apk\release\app-release.apk` | 自分で作った release keystore |

- debug APK は `DEBUGGABLE` フラグが付き、デバッグビルド用の鍵で署名される。
  PC を替えると debug.keystore が変わり、**別 PC で作った debug APK は上書きできない**。
- 長期運用するなら release 署名鍵を作って固定する（`docs/signing-policy.md`）。
  鍵を失うと更新版を出せなくなるので、オフラインでバックアップする。

## 4. 署名済みかを確認する

```powershell
$apksigner = "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.1.0\apksigner.bat"
& $apksigner verify --print-certs .\app\build\outputs\apk\debug\app-debug.apk
```

出力の `Signer #1 certificate SHA-256 digest` を、端末側の署名と見比べる。

```powershell
adb shell dumpsys package com.tonbiattack.fastestpictureviewer | Select-String "signatures"
```

`version:2, signatures:[...]` が出れば署名済み。`apksigner` が
`DOES NOT VERIFY` / `Missing META-INF/MANIFEST.MF` を出す APK は未署名で、導入できない。

## 5. バージョンを上げる

`versionCode` は更新のたびに増やす。`versionName` は表示用の文字列。

```powershell
# ビルド時だけ上書きする（build.gradle.kts は書き換えない）
.\gradlew.bat :app:assembleRelease -Pfpv.versionCode=2 -Pfpv.versionName=0.2.0
```

恒久的に上げる場合は `app/build.gradle.kts` の既定値を書き換える。
`versionCode` を下げた APK は通常インストールできない（`-d` が必要で非推奨）。

## 6. 更新後に確認すること

```powershell
# 起動と起動時間
powershell -ExecutionPolicy Bypass -File scripts\device.ps1 -Action Start

# スクリーンショット
powershell -ExecutionPolicy Bypass -File scripts\device.ps1 -Action Screenshot

# ログ（クラッシュ・DB エラーの確認）
powershell -ExecutionPolicy Bypass -File scripts\device.ps1 -Action Logs -LogLines 500
```

確認項目:

- 起動してフォルダ一覧が出る（固まらない）
- 既存のデータが残っている: お気に入り、タグ、除外フォルダ、列数設定、前回の続き
- DB バージョンが上がる更新（例: v7→v8）でクラッシュしない
- 画像を開いて戻れる
- `AndroidRuntime: FATAL EXCEPTION` がログに出ていない

初回起動時は権限ダイアログが出る。拒否した場合もキャッシュ表示は残る（案内バナーが出る）。

## 7. トラブルシューティング

| 症状 | 原因 / 対処 |
| --- | --- |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 署名が違う。同じ種類の APK を使うか、アンインストールして入れ直す |
| `INSTALL_FAILED_VERSION_DOWNGRADE` | versionCode が下がっている。`-d` を付けるか versionCode を上げる |
| `INSTALL_PARSE_FAILED_NO_CERTIFICATES` | 未署名 APK。署名設定を確認する |
| `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user` | 端末側の確認ダイアログで「拒否」を押した、または MIUI 系の追加制限。ダイアログで「インストール」を選ぶ |
| `adb: more than one device/emulator` | 端末が複数接続されている。`adb -s <serial>` か `-Serial <serial>` を指定する |
| `INSTALL_FAILED_INSUFFICIENT_STORAGE` | 端末の空き容量不足。写真を消すのではなく、不要なアプリやキャッシュを整理する |
| 端末が `unauthorized` | 端末側の USB デバッグ許可ダイアログを承認する |
| インストールしたのに起動しない | `adb logcat -d -s AndroidRuntime:E` でスタックトレースを確認する |

## 8. 実際に実行した更新記録（OPPO Reno13 A）

2026-11-13、OPPO Reno13 A（CPH2699 / Android 16 / API 36）の Pictobi を
main の最新（PR #50 / #51 まで）へ更新したときの実コマンドと結果。
**実行したコマンドをそのまま残す。** 端末は複数接続されていたため `-Serial` を指定した。

### 8-1. 実行前の状態確認

```powershell
adb devices -l
# <serial> / <serial>（CPH2699 = OPPO Reno13 A） / <serial> の 3 台

powershell -ExecutionPolicy Bypass -File scripts\device.ps1 -Action Info -Serial <serial>
```

結果（更新前）:

```text
versionCode=1 minSdk=29 targetSdk=36
versionName=0.1.0
lastUpdateTime=2026-11-12 21:21:15
signatures=PackageSignatures{f7d3e3c version:2, signatures:[470b8fe3], past signatures:[]}
pkgFlags=[ DEBUGGABLE HAS_CODE ALLOW_CLEAR_USER_DATA ALLOW_BACKUP ]
firstInstallTime=2026-11-12 20:51:28

画像件数 (MediaStore): 350973
ストレージ: 106231420K 中 4594508K 空き (96% 使用)
```

`DEBUGGABLE` があるので端末には **debug APK** が入っている。debug 同士なら上書きできる。

### 8-2. テストとビルド

```powershell
.\gradlew.bat :app:testDebugUnitTest --console=plain
# BUILD SUCCESSFUL / tests=171 failures=0

.\gradlew.bat :app:assembleDebug --console=plain
# BUILD SUCCESSFUL
# app\build\outputs\apk\debug\app-debug.apk (33,538,837 bytes, 2026/11/13 9:34)
```

### 8-3. 上書きインストール

```powershell
powershell -ExecutionPolicy Bypass -File scripts\device.ps1 -Action Install -Serial <serial>
# APK を上書きインストールします (アプリデータは維持): ...\app-debug.apk
# Performing Streamed Install
# Success
```

`adb install -r .\app\build\outputs\apk\debug\app-debug.apk` と同じ処理。

### 8-4. 更新後の確認

```powershell
adb -s <serial> shell dumpsys package com.tonbiattack.fastestpictureviewer |
  Select-String "versionCode|versionName|firstInstallTime|lastUpdateTime|pkgFlags"
```

結果（更新後）:

```text
versionCode=1 minSdk=29 targetSdk=36
versionName=0.1.0
lastUpdateTime=2026-11-13 10:03:11
pkgFlags=[ DEBUGGABLE HAS_CODE ALLOW_CLEAR_USER_DATA ALLOW_BACKUP ]
firstInstallTime=2026-11-12 20:51:28
```

`lastUpdateTime` だけが新しくなり、**`firstInstallTime` は変わっていない**
= アンインストールされず、アプリデータが維持された。

```powershell
adb -s <serial> logcat -c
adb -s <serial> shell am start -W -n com.tonbiattack.fastestpictureviewer/.MainActivity
```

結果:

```text
Status: ok
LaunchState: COLD
TotalTime: 1791
WaitTime: 1996
```

```powershell
Start-Sleep -Seconds 6
adb -s <serial> logcat -d -s AndroidRuntime:E FPV-Perf:I Room:E SQLiteLog:E
```

結果: クラッシュ・DB エラーなし。差分同期が 1 回走ったのみ。

```text
I FPV-Perf: volume=external_primary currentGen=3466426 storedGen=3466412 kind=INCREMENTAL
```

画面確認（スクリーンショットと UI ツリー取得）:

```powershell
adb -s <serial> shell screencap -p /sdcard/oppo1.png
adb -s <serial> pull /sdcard/oppo1.png captures\oppo-after-update.png
adb -s <serial> shell rm /sdcard/oppo1.png

adb -s <serial> shell uiautomator dump /sdcard/o.xml
adb -s <serial> pull /sdcard/o.xml captures\oppo.xml
adb -s <serial> shell rm /sdcard/o.xml
python -X utf8 scripts\ui_find.py captures\oppo.xml ""
```

結果:

- フォルダ一覧が表示され、「前回の続き（ララ1115 / 57枚）」も残っている
- タグ・お気に入り・除外設定・列数設定が維持されている
- フォルダを開くと `01.jpg` から始まる（#49 の自然順先頭）
- フォルダ一覧のサムネイルも `01.jpg` と同じ画像
- ビューアで `1 / 57 01.jpg` が開き、ページスクラバーも動作

### 8-5. 今回の更新に含まれる変更

- #49 フォルダ代表サムネイルをファイル名の自然順先頭に統一
- #51 APK の導入・更新手順の整備、リリース署名設定

DB のバージョンは v8 のまま（#46 で追加済み）なので、DB 移行は発生していない。

## 9. 注意事項

- **アンインストール・写真削除・端末リセットは破壊的。** 明示的な指示がない限り行わない。
- 署名鍵・パスワードは Git にコミットしない（`.gitignore` 済み）。
- `captures/`、`local.properties` もコミットしない。
- 実機でしか確認できない項目（SD カード抜き差し、権限拒否など）は Issue に残す。
