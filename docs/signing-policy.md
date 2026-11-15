# 署名鍵の運用方針

OPPO Reno13 A にインストールする APK の署名鍵について、秘密情報をリポジトリに残さないための方針をまとめる。
**この文書には鍵・パスワードの実値を書かない。**

## 1. 現状

- 開発用 APK は Android SDK のデバッグ鍵で署名される
- デバッグ鍵は `%USERPROFILE%\.android\debug.keystore` にあり、リポジトリには含まれない
- **リリース署名鍵は作成済み**（2026-11-14）。初回リリース `v0.1.0` を公開した
- 同じ applicationId・同じ署名鍵で `adb install -r` すればアプリデータは維持される
- 署名鍵を失うと、同じアプリの更新版を配布できなくなる

### 作成済みの鍵の情報（実値は書かない）

| 項目 | 値 |
| --- | --- |
| ファイル | `%USERPROFILE%\.android-signing\pictobi-release.jks` |
| 設定ファイル | `%USERPROFILE%\.android-signing\pictobi-release.properties` |
| エイリアス | `pictobi` |
| 方式 | RSA 4096 / 有効期限 10000 日 |
| 証明書 SHA-256 | `974205ee404b36fd15c11171f0f6a10f040cdd78140228757fb20b0c99056310` |
| バックアップ | `%USERPROFILE%\pictobi-signing-backup\pictobi-signing-backup.zip` |

> 証明書の SHA-256 は公開情報（APK から誰でも確認できる）。パスワードや鍵ファイルの中身は書かない。

## 2. リリース署名鍵の作成（人間が実施）

パスワードはパスワードマネージャ等で管理し、コマンド履歴やリポジトリに残さない。
鍵ファイルはリポジトリ外（例: ユーザーディレクトリ配下の専用フォルダ）に作成する。

```powershell
# 実行例。パスワードは対話入力し、このリポジトリ内には保存しない
keytool -genkeypair -v `
  -keystore "$env:USERPROFILE\.android-signing\fastest-picture-viewer-release.jks" `
  -alias fastest-picture-viewer `
  -keyalg RSA -keysize 4096 -validity 10000
```

- `keytool` は JDK に含まれる
- `-validity` は十分に長く取る（例: 10000 日）
- 作成後、フィンガープリントを控えて別媒体にも記録する
- `-alias` は任意の名前でよいが、`pictobi` など分かりやすい名前にする
- このリポジトリの Gradle 設定は `pictobi-release.properties` の
  `storeFile` / `storePassword` / `keyAlias` / `keyPassword` を読む（下の「4-1」を参照）

```powershell
keytool -list -v -keystore "<鍵ファイル>" -alias fastest-picture-viewer
```

## 3. 保管・バックアップ

- 鍵ファイルとパスワードは**別々の場所**に保管する
- オフラインのバックアップを 2 か所以上に置く（USB メモリ、暗号化したクラウド等）
- バックアップから復元できることを定期的に確認する
- 端末紛失・PC 故障時も更新版をビルドできる状態を保つ

## 4. リポジトリに含めないもの

以下はコミットしない。`.gitignore` にも追加済み。

- `*.jks` / `*.keystore`
- `keystore.properties` などの鍵設定ファイル
- パスワード、フィンガープリントの実値
- `local.properties`

署名情報を Gradle に渡す場合も、`%USERPROFILE%\.gradle\gradle.properties` や環境変数を使い、
プロジェクト内のファイルには書かない。

## 4-1. このリポジトリでの署名設定（実装済み）

`app/build.gradle.kts` は次のどちらかから署名情報を読み、揃っているときだけ release に署名する。

**方法 A: リポジトリ外のプロパティファイル**

`%USERPROFILE%\.android-signing\pictobi-release.properties`（`.gitignore` 対象外の場所）:

```properties
storeFile=C:/Users/<user>/.android-signing/pictobi-release.jks
storePassword=<パスワード>
keyAlias=pictobi
keyPassword=<パスワード>
```

**方法 B: 環境変数（現在の PowerShell セッションのみ）**

```powershell
$env:FPV_KEYSTORE_PATH = "$env:USERPROFILE\.android-signing\pictobi-release.jks"
$env:FPV_KEYSTORE_PASSWORD = Read-Host -AsSecureString | ConvertFrom-SecureString -AsPlainText
$env:FPV_KEY_ALIAS = "pictobi"
$env:FPV_KEY_PASSWORD = $env:FPV_KEYSTORE_PASSWORD
.\gradlew.bat :app:assembleRelease
```

どちらも設定していない場合、`assembleRelease` は
`app-release-unsigned.apk` を生成する。これは**端末にインストールできない**ため、
リリース用途では必ず署名情報を設定する。

確認:

```powershell
Get-ChildItem app\build\outputs\apk\release -Filter *.apk
# app-release.apk が出れば署名済み、app-release-unsigned.apk だけなら未署名
```

導入・更新の詳しい手順は [APK の導入・更新手順](install-and-update.md) を参照する。

## 4-3. リリースの自動化（タグ push）

`v*` 形式のタグを push すると、GitHub Actions が署名済み APK をビルドして Release に添付する。
手元でビルドする必要はない。

```bash
git tag v0.3.0
git push origin v0.3.0
```

`.github/workflows/release.yml` が行うこと:

1. タグから `versionName` と `versionCode` を決める（`v1.2.3` → `1203`）
2. 単体テストを実行（壊れたコードを配らないため）
3. シークレットから鍵を取り出して署名する
4. **証明書の SHA-256 を照合**する（別の鍵で署名すると既存利用者が更新できなくなるため）
5. Release を作成し、APK を添付する

### 必要なシークレット

| 名前 | 内容 |
| --- | --- |
| `SIGNING_KEYSTORE_BASE64` | 鍵ファイル（`.jks`）を base64 にしたもの |
| `SIGNING_STORE_PASSWORD` | キーストアのパスワード |
| `SIGNING_KEY_ALIAS` | 鍵のエイリアス |
| `SIGNING_KEY_PASSWORD` | 鍵のパスワード |

登録は値を表示せずに行う。

```powershell
# 鍵を base64 にする（画面に出さずファイル経由で渡す）
[Convert]::ToBase64String([IO.File]::ReadAllBytes("$env:USERPROFILE\.android-signing\pictobi-release.jks")) |
  Set-Content "$env:TEMP\keystore.b64" -Encoding ASCII -NoNewline
Get-Content "$env:TEMP\keystore.b64" -Raw | gh secret set SIGNING_KEYSTORE_BASE64
Remove-Item "$env:TEMP\keystore.b64"
```

パスワード類も同じく `gh secret set` に標準入力で渡す。**コマンド履歴やファイルに残さない。**

### 注意

- シークレットが未設定の場合、ワークフローは明示的に失敗する（未署名 APK を配らない）
- 証明書が想定と違う場合も失敗する（更新不能になるのを防ぐ）
- タグは `v<major>.<minor>.<patch>` 形式のみ受け付ける。打ち間違いで壊れた APK を出さないため
- 鍵を GitHub に置くため、リポジトリの権限管理が重要になる。鍵のバックアップ（`docs` の方針）は引き続き手元に保持する

### 動作確認（2026-11-15）

検証用タグ `v0.2.1` で実際にワークフローを走らせ、次を確認した。

| 項目 | 結果 |
| --- | --- |
| ワークフローの起動 | 成功（約 3 分） |
| 単体テスト | 成功 |
| Release の自動作成 | 成功（APK 3,918,499 バイト） |
| 署名 | v0.1.0 / v0.2.0 と同じ証明書（SHA-256 `974205ee...56310`） |
| versionCode / versionName | 201 / 0.2.1（タグから正しく算出） |

確認後、検証用の Release とタグは削除した。次回以降は `git tag v0.3.0 && git push origin v0.3.0` だけで公開できる。

## 5. 漏えい・紛失時の扱い

- 鍵やパスワードが漏えいした可能性がある場合は、新しい鍵への移行を検討する
- ただし applicationId が同じままだと、既存インストールの更新には旧鍵が必要になる
- 個人用アプリのため、移行時はアンインストールを伴う可能性がある。写真や他アプリのデータには影響しないが、
  アプリ内キャッシュは失われる
- 署名鍵をリポジトリや Issue に貼らない
