# ビルド・テスト・実機操作のスクリプト

`scripts/` の各スクリプトは Windows PowerShell から実行する。すべて再実行可能で、
端末側に破壊的な操作（アンインストール・写真削除・端末リセット）は行わない。

## 一覧

| スクリプト | 用途 |
| --- | --- |
| `setup-android-env.ps1` | cmdline-tools / SDK の配置と環境変数の初回設定 |
| `check-env.ps1` | JDK・Android SDK・adb の確認 |
| `build-and-test.ps1` | 単体テスト + debug APK 生成 |
| `device.ps1` | 端末情報・APK 導入・起動計測・スクリーンショット・ログ |
| `ui.ps1` | 実機の UI 要素検索・タップ・長押し（uiautomator 経由） |
| `ui_dump.py` | 取得した UI XML の要素一覧（UTF-8 表示用） |
| `ui_find.py` | 取得した UI XML から指定文字列の要素と中央座標を表示 |

## 実機 UI 操作（ui.ps1）

端末が複数接続されている場合は `-Serial` を必ず指定する。

```powershell
# 画面内のテキスト・content-desc を一覧表示
powershell -ExecutionPolicy Bypass -File scripts\ui.ps1 -Action Texts -Serial <serial>

# 部分一致で要素を探す
powershell -ExecutionPolicy Bypass -File scripts\ui.ps1 -Action Find -Query "タグ" -Serial <serial>

# 要素の中央をタップ / 長押しする
powershell -ExecutionPolicy Bypass -File scripts\ui.ps1 -Action Tap -Query "タグの管理" -Serial <serial>
powershell -ExecutionPolicy Bypass -File scripts\ui.ps1 -Action LongTap -Query "フォルダ名" -Serial <serial>
```

`Find` / `Tap` は `text` と `content-desc` の部分一致で探す。日本語が化けて見える場合は
コンソールのコードページの問題で、端末側の表示は正しい。座標を使った直接操作は次の補助で行う。

```powershell
adb -s <serial> shell uiautomator dump /sdcard/u.xml
adb -s <serial> pull /sdcard/u.xml captures\u.xml
python -X utf8 scripts\ui_find.py captures\u.xml "タグ"
```

## 実機 UI テスト（instrumented test）

ピンチ操作のように 2 本指の入力が必要な検証は `app/src/androidTest/` に置き、
接続中の実機で実行する。

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

このテストは端末にテスト用 APK をインストールして実行するだけで、
アプリのデータ（写真・Room・設定）は削除しない。

## APK の導入・更新（device.ps1）

| 目的 | コマンド |
| --- | --- |
| 端末・導入状況の確認 | `device.ps1 -Action Info`（バージョン、debug/release、署名、初回/更新日時） |
| debug APK を上書き | `device.ps1 -Action Install` |
| 署名済み release APK を上書き | `device.ps1 -Action Install -Release` |
| 任意の APK を指定 | `device.ps1 -Action Install -Apk <path>` |

- 未署名の `app-release-unsigned.apk` を指定すると、導入前にエラーで止める。
- 署名が異なる APK を入れると `INSTALL_FAILED_UPDATE_INCOMPATIBLE` になる。
  その場合はアンインストールが必要で、アプリ内データは失われる（スクリプトでは実施しない）。
- 詳しい手順とトラブルシューティングは [APK の導入・更新手順](install-and-update.md) を参照。
