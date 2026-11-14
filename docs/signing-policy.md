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

## 5. 漏えい・紛失時の扱い

- 鍵やパスワードが漏えいした可能性がある場合は、新しい鍵への移行を検討する
- ただし applicationId が同じままだと、既存インストールの更新には旧鍵が必要になる
- 個人用アプリのため、移行時はアンインストールを伴う可能性がある。写真や他アプリのデータには影響しないが、
  アプリ内キャッシュは失われる
- 署名鍵をリポジトリや Issue に貼らない
