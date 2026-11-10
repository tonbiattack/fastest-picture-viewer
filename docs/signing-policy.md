# 署名鍵の運用方針

OPPO Reno13 A にインストールする APK の署名鍵について、秘密情報をリポジトリに残さないための方針をまとめる。
**この文書には鍵・パスワードの実値を書かない。**

## 1. 現状

- 開発用 APK は Android SDK のデバッグ鍵で署名される
- デバッグ鍵は `%USERPROFILE%\.android\debug.keystore` にあり、リポジトリには含まれない
- リリース署名鍵は**まだ作成していない**
- 同じ applicationId・同じ署名鍵で `adb install -r` すればアプリデータは維持される
- 署名鍵を失うと、同じアプリの更新版を配布できなくなる

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

## 5. 漏えい・紛失時の扱い

- 鍵やパスワードが漏えいした可能性がある場合は、新しい鍵への移行を検討する
- ただし applicationId が同じままだと、既存インストールの更新には旧鍵が必要になる
- 個人用アプリのため、移行時はアンインストールを伴う可能性がある。写真や他アプリのデータには影響しないが、
  アプリ内キャッシュは失われる
- 署名鍵をリポジトリや Issue に貼らない
