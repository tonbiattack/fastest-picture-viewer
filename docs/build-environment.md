# Windows 11 ビルド・テスト環境手順

Issue #10 の環境構築手順と、再実行可能な確認・ビルド方法をまとめる。
対象は Windows 11 + OPPO Reno13 A 向けの個人開発環境であり、Play 公開や複数端末対応は対象外。

## 1. 現在の構成（2026-11-09 実測）

| 項目 | 値 |
| --- | --- |
| OS | Windows 11 (10.0.26300) |
| JDK | OpenJDK 21（`JAVA_HOME=C:\Program Files\jdk-21`） |
| Git | 2.56.0.windows.1 |
| Android Studio | AI-261 (2026.1.1)。インストール・初回設定済み |
| Android SDK | `C:\Users\<user>\AppData\Local\Android\Sdk` |
| Platform | android-36（android-36.1 も導入済み） |
| Build Tools | 35.0.0 / 36.1.0 / 37.0.0 |
| cmdline-tools | 23.0.0（`cmdline-tools\latest`） |
| adb | 1.0.41 (37.0.0)。新規ターミナルでは `adb` を直接実行可能 |
| 環境変数 | `ANDROID_HOME` / `ANDROID_SDK_ROOT` 設定済み。`platform-tools` / `cmdline-tools\latest\bin` / `emulator` をユーザー PATH に追加済み |
| Gradle | wrapper 8.13 |
| AGP / Kotlin / KSP | 8.13.2 / 2.2.20 / 2.2.20-2.0.4 |
| compileSdk / minSdk / targetSdk | 36 / 29 / 36 |

Android Studio の設定ディレクトリは `C:\Users\<user>\AppData\Roaming\Google\AndroidStudio2026.1.1`。
SDK ライセンスは同意済み（`license` ファイルあり。新しい Android CLI では `--licenses` は不要）。
仮想化は Hyper-V / VBS が動作中（`HypervisorPresent=True`、systeminfo で "A hypervisor has been detected"）で、
エミュレーターを使う場合も技術的には利用可能。ただし本開発は実機中心のため AVD は作成していない。

確認時点で以下は成功している。

- `.\gradlew.bat :app:testDebugUnitTest` → `BUILD SUCCESSFUL`
- `.\gradlew.bat :app:assembleDebug` → `app\build\outputs\apk\debug\app-debug.apk` を生成
- `scripts\check-env.ps1` → 終了コード 0

### セットアップで追加した設定（2026-11-09）

- 既存の Android Studio と SDK パッケージを再利用し、再インストールは行っていない
- `cmdline-tools;latest`（revision 23）を公式 zip から取得し、SHA1 を検証して `cmdline-tools\latest` に配置
- `ANDROID_HOME` / `ANDROID_SDK_ROOT` をユーザー環境変数に設定
- ユーザー PATH に `platform-tools`、`cmdline-tools\latest\bin`、`emulator` を追加（重複は追加しない）
- `sdkmanager` は非推奨で、`android sdk` が後継という警告を確認済み

## 2. 必要なもの

- JDK 17 以上（現在は 21 を使用）
- Android SDK Platform 36
- Android SDK Build Tools
- Android SDK Platform-Tools（adb。実機接続で使用）
- Android SDK Command-line Tools（sdkmanager / android CLI）
- Git
- `local.properties` に `sdk.dir` を設定（ローカル固有値のためコミットしない）

Android Studio は必須ではない。コマンドラインの Gradle ビルドだけなら SDK と JDK があれば動く。
ただし本環境では Android Studio AI-261 をインストール済みで、初回設定も完了している。
SDK のライセンス同意は実施済み。

## 3. 人と自動化の切り分け

| 作業 | 担当 | 内容 |
| --- | --- | --- |
| Android Studio / SDK の初回インストール | 完了済み | 既存の Android Studio AI-261 を再利用 |
| SDK ライセンスの同意 | 完了済み | 同意済み。新 CLI では `--licenses` 不要 |
| `JAVA_HOME` と SDK パスの設定 | 設定済み（確認は自動） | `ANDROID_HOME` / `ANDROID_SDK_ROOT` / PATH を設定済み |
| 環境確認 | 自動 | `scripts\check-env.ps1` |
| 単体テスト | 自動 | `scripts\build-and-test.ps1` |
| debug APK ビルド | 自動 | `scripts\build-and-test.ps1` |
| 実機接続・USB デバッグ許可 | 人間 | Issue #11。端末側の操作とダイアログ承認 |
| 実機への APK インストール | 自動（将来） | Issue #11 のスクリプト。`adb -s <serial>` を使用 |
| 実機の操作感・性能確認 | 人間 | Issue #9 / #7 |

## 4. 確認とビルドの実行

PowerShell（Windows PowerShell 5.1 / PowerShell 7 のどちらでも可）で実行する。

```powershell
# 初回のみ: cmdline-tools の配置と ANDROID_HOME / PATH の設定
powershell -ExecutionPolicy Bypass -File scripts\setup-android-env.ps1

# 環境確認（必須項目が揃っているか）
powershell -ExecutionPolicy Bypass -File scripts\check-env.ps1

# 単体テスト + debug APK ビルド
powershell -ExecutionPolicy Bypass -File scripts\build-and-test.ps1

# テストのみ
powershell -ExecutionPolicy Bypass -File scripts\build-and-test.ps1 -SkipBuild

# APK ビルドのみ
powershell -ExecutionPolicy Bypass -File scripts\build-and-test.ps1 -SkipTests
```

`setup-android-env.ps1` は既存の SDK を再利用し、不足している cmdline-tools だけを公式 zip（SHA1 検証付き）から配置する。
`check-env.ps1` は終了コード 0 で必須項目が揃っていることを示す。
adb はビルドに不要なため、既定では不足しても警告に留める。`-StrictAdb` を付けると失敗扱いになる。
実機操作は `scripts\device.ps1` と [実機検証手順](device-verification.md) を参照する。

生成物の確認先:

```
app\build\outputs\apk\debug\app-debug.apk
```

## 5. よくある詰まり

### `adb` が「認識されません」

SDK の platform-tools に PATH が通っていないだけ。ビルドには影響しない。
実機操作では次のフルパスを使う。

```
C:\Users\<user>\AppData\Local\Android\Sdk\platform-tools\adb.exe
```

### Android SDK が見つからない

`local.properties` の `sdk.dir` を確認する。このファイルは `.gitignore` 済みで、リポジトリには含めない。

```
sdk.dir=C\:\\Users\\<user>\\AppData\\Local\\Android\\Sdk
```

### JDK のバージョンが違う

`java -version` と `JAVA_HOME` が指す JDK を揃える。Gradle は `JAVA_HOME` を優先する。
本プロジェクトは Java 17 をターゲットにしており、JDK 17 以上なら動く。

### ライセンス未同意でビルドが止まる

Android Studio の SDK Manager、または `sdkmanager --licenses` で同意する。これは人間が実施する。

## 6. 注意事項

- `local.properties`、署名鍵、パスワードはコミットしない。
- 実機の写真・アプリデータを削除する操作（アンインストール、端末リセット、写真の一括削除）は自動化しない。
- ビルド成功を実機動作の成功とみなさない。実機確認は Issue #9 / #11 で行う。
- 継続運用前の専用リリース署名鍵の作成・保管は Issue #11 で整理する。
