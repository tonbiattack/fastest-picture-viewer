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

## 8. 注意事項

- **アンインストール・写真削除・端末リセットは破壊的。** 明示的な指示がない限り行わない。
- 署名鍵・パスワードは Git にコミットしない（`.gitignore` 済み）。
- `captures/`、`local.properties` もコミットしない。
- 実機でしか確認できない項目（SD カード抜き差し、権限拒否など）は Issue に残す。
