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
| `transfer-to-sd.ps1` | PC のフォルダを SD カードへ転送（zip / 内部経由 / 直接） |
| `ui.ps1` | 実機の UI 要素検索・タップ・長押し（uiautomator 経由） |
| `benchmark.ps1` | 起動・復帰・フォルダ遷移の性能計測（P50/P95）。詳細は [benchmark.md](benchmark.md) |
| `viewer-benchmark.ps1` | ビューアのスワイプ応答（ドラッグ開始→ページ描画）の計測。詳細は [benchmark.md](benchmark.md#8-ビューアのスワイプ応答issue-101) |
| `ui_dump.py` | 取得した UI XML の要素一覧（UTF-8 表示用） |
| `ui_find.py` | 取得した UI XML から指定文字列の要素と中央座標を表示 |
| `ui_switches.py` | UI XML から `checkable` な要素の `checked` 状態を一覧表示 |
| `apk_has_string.py` | APK 内の dex に指定文字列が含まれるか確認（ビルド反映の確認用） |

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

## CI（GitHub Actions）

.github/workflows/android.yml が main への push と pull_request で次を検証する。

- :app:testDebugUnitTest（Robolectric / 純粋ロジック）
- :app:assembleDebug（APK が生成できること）

実機が必要な connectedDebugAndroidTest は CI では実行しない。
署名鍵や個人情報は使わず、debug ビルドのみを対象にしている。

## 性能計測（benchmark.ps1）

起動・復帰・フォルダ遷移を繰り返し測り、P50/P95 を JSON/CSV に残す。

| 目的 | コマンド |
| --- | --- |
| 3シナリオを 10 回ずつ | `benchmark.ps1` |
| コールド起動だけ 20 回 | `benchmark.ps1 -Scenario cold -Runs 20` |
| 前回結果と比較 | `benchmark.ps1 -Baseline captures\perf\<timestamp>.json` |

- 実機の計測は対応端末が接続された環境でのみ実行する（CI では動かさない）
- 端末アニメーションを一時的に 0 にし、終了時に元へ戻す
- 詳細と結果の読み方は [性能計測](benchmark.md) を参照

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

## PC から SD カードへの転送（transfer-to-sd.ps1）

エクスプローラー（MTP）で SD カードへ直接コピーすると、転送開始まで待たされ、
途中で失敗することがある。`transfer-to-sd.ps1` は ADB で転送し、検証まで行う。

```powershell
# 既定は zip 方式（小さいファイルが多いほど有利）
powershell -ExecutionPolicy Bypass -File scripts\transfer-to-sd.ps1 `
  -Source D:\pics -Destination Pictures\20261113

# 事前確認のみ（端末を書き換えない）
powershell -ExecutionPolicy Bypass -File scripts\transfer-to-sd.ps1 `
  -Source D:\pics -Destination Pictures\20261113 -DryRun

# MD5 まで検証し、工程ごとの所要時間を CSV に残す
powershell -ExecutionPolicy Bypass -File scripts\transfer-to-sd.ps1 `
  -Source D:\pics -Destination Pictures\20261113 -Verify Hash -OutputPath captures\transfer.csv
```

| 方式 | 指定 | 向いている場面 |
| --- | --- | --- |
| Zip（既定） | `-Mode Zip` | 小さいファイルが多い（写真・ページ画像など）。ASCII 名のみ |
| Staged | `-Mode Staged` | zip を作りたくない／圧縮済みの大ファイルが多い／日本語ファイル名を含む |
| Direct | `-Mode Direct` | 数 MB 以上のファイルが少しだけ |

- **日本語などの非 ASCII 名を含むと、zip 方式は自動的に Staged へ切り替わる**
  （端末の `unzip` が zip 内の非 ASCII 名を復元できず `Operation not permitted` になるため）。

- `-Destination` は SD カード直下からの相対パス。絶対パスと `..` は拒否する。
- 検証に成功したときだけ、内部のステージング（`/sdcard/Download/PictobiTransfer`）を削除する。
- 端末の写真・アプリデータは削除しない。失敗時は終了コード 3。
- 転送後はアプリの**手動更新**を実行する（MediaStore の反映が遅れるため）。
- 実測値と MTP 側の対策は [PC から SD カードへ転送する手順](sd-transfer.md) を参照。
