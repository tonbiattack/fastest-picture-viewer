# リリース手順（GitHub Releases）

Pictobi の署名済み APK を作成し、GitHub Releases で配布する手順。
署名鍵の作成・保管は [署名鍵の運用方針](signing-policy.md) を参照する。

## 1. リリース前の確認

```powershell
# main を最新化
git switch main
git pull --ff-only origin main

# テストとビルド
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug

# lint（アプリ側の警告が 0 件であること）
.\gradlew.bat :app:lintDebug
```

- [ ] 単体テストが成功している
- [ ] `assembleDebug` が成功している
- [ ] lint のアプリ側警告が 0 件
- [ ] 実機（OPPO Reno13 A）で主要機能を確認した
- [ ] 既知の問題を洗い出した

## 2. バージョンを決める

`versionCode` は**前回のリリースより必ず大きく**する。`versionName` は表示用。

| 変更内容 | 上げ方の例 |
| --- | --- |
| 不具合修正のみ | `0.1.0` → `0.1.1`（versionCode +1） |
| 機能追加 | `0.1.1` → `0.2.0`（versionCode +1） |
| 大きな変更 | `0.2.0` → `1.0.0`（versionCode +1） |

```powershell
git tag --list          # 既存タグの確認
gh release list         # 既存リリースの確認
```

## 3. 署名情報を設定する

パスワードをコマンド履歴に残さないよう、プロパティファイル方式を推奨する
（詳細は [署名鍵の運用方針](signing-policy.md#4-1-このリポジトリでの署名設定実装済み)）。

`%USERPROFILE%\.android-signing\pictobi-release.properties`:

```properties
storeFile=C:/Users/<user>/.android-signing/pictobi-release.jks
storePassword=***
keyAlias=pictobi
keyPassword=***
```

## 4. リリース APK をビルドする

```powershell
.\gradlew.bat :app:assembleRelease "-Pfpv.versionCode=2" "-Pfpv.versionName=0.2.0"
```

出力: `app\build\outputs\apk\release\app-release.apk`

署名情報が未設定だと `app-release-unsigned.apk` になる。**これは配布できない。**

## 5. 署名と内容を確認する

```powershell
# 署名の確認
$apksigner = "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.1.0\apksigner.bat"
& $apksigner verify --print-certs .\app\build\outputs\apk\release\app-release.apk

# バージョンと表示名
$aapt = "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.1.0\aapt2.exe"
& $aapt dump badging .\app\build\outputs\apk\release\app-release.apk | Select-String "versionCode|application-label"

# ハッシュ（リリースページに記載する）
Get-FileHash .\app\build\outputs\apk\release\app-release.apk -Algorithm SHA256
```

- [ ] `Signer #1 certificate DN` が**前回と同じ**（鍵を変えていない）
- [ ] `versionCode` が前回より大きい
- [ ] `application-label` が `Pictobi`
- [ ] SHA-256 を控えた

## 6. 実機で確認する

```powershell
# 上書きインストール（データ維持）
adb install -r .\app\build\outputs\apk\release\app-release.apk

# 起動とクラッシュ確認
adb shell am start -W -n com.tonbiattack.fastestpictureviewer/.MainActivity
adb logcat -d -s AndroidRuntime:E
```

確認項目:

- [ ] 起動する（固まらない）
- [ ] フォルダ一覧・画像一覧・画像詳細が表示される
- [ ] 既存データ（お気に入り・タグ・設定）が残っている
- [ ] 戻る操作で一覧が消えない
- [ ] FATAL EXCEPTION が出ていない

## 7. APK をリネームする

配布物は名前から内容が分かるようにする。

```powershell
Copy-Item .\app\build\outputs\apk\release\app-release.apk `
  .\dist\Pictobi-0.2.0.apk
```

## 8. タグを作成して push する

```powershell
git tag -a v0.2.0 -m "Pictobi 0.2.0"
git push origin v0.2.0
```

## 9. GitHub Release を作成する

```powershell
gh release create v0.2.0 `
  .\dist\Pictobi-0.2.0.apk `
  --title "Pictobi 0.2.0" `
  --notes-file .\dist\release-notes-0.2.0.md
```

リリースノートには次を書く。

- 変更内容（利用者に影響するもの）
- 既知の制限
- SHA-256 ハッシュ
- 対応 OS・必要権限

## 10. リリースノートのひな形

```markdown
## 変更内容
- ○○を追加しました
- ○○の不具合を修正しました

## インストール
1. 下の Assets から `Pictobi-0.2.0.apk` をダウンロード
2. 「提供元不明のアプリ」を許可してインストール
3. 起動して写真の閲覧を許可

既に Pictobi を入れている場合は、上書きインストールでデータを保持したまま更新できます。

## 動作確認
- OPPO Reno13 A（Android 16 / ColorOS 16.0.5）

## 既知の制限
- SD カードの抜き差しは未検証
- お気に入り・タグは端末の買い替えで引き継がれない

## ファイル
- Pictobi-0.2.0.apk
- SHA-256: `<hash>`

## ライセンス
MIT License
```

## 11. 公開範囲

リポジトリが private のままだと、Release は一般ユーザーから見えない。
公開する場合は所有者の判断で以下を確認してから変更する。

- [ ] 機密情報（署名鍵・パスワードの実値）が履歴に無い
- [ ] 個人情報（ユーザー名・シリアル等）の扱いを決めた
- [ ] ライセンスを設定した
- [ ] 依存ライブラリのライセンスを確認した
- [ ] 商標（アプリ名）の調査をした

```powershell
gh repo edit --visibility public --accept-visibility-change-consequences
```

## 12. トラブルシューティング

| 症状 | 対処 |
| --- | --- |
| `app-release-unsigned.apk` しかできない | 署名情報が未設定。手順 3 を確認 |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 署名が違う（debug と release の混在など） |
| `INSTALL_FAILED_VERSION_DOWNGRADE` | `versionCode` を上げる |
| 起動直後にクラッシュ | `adb logcat -d -s AndroidRuntime:E` を確認 |
