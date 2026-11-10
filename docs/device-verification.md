# 実機（OPPO Reno13 A）検証手順

Issue #7 / #9 / #11 の実機接続・APK 導入・性能計測をまとめる。
**実機を使った操作のうち、人が行う必要があるものは未実施のまま正直に記録する。**
ビルド成功やスクリプトの実行成功を、実機での受け入れ成功とみなさない。

## 1. 前提（人間が行う作業）

- [ ] USB ケーブルが**データ通信対応**であることを確認する
- [ ] 端末の「設定 → 端末情報」でビルド番号を 7 回タップし、開発者向けオプションを有効化
- [ ] 「開発者向けオプション → USB デバッグ」を有効化
- [ ] PC と接続し、端末に表示される USB デバッグ許可ダイアログを本人が承認
- [ ] 必要なら「USB デバッグの許可を取り消す」でいったん解除し、再接続して再承認

### 検証後に USB デバッグを無効へ戻す手順

1. 端末の「設定 → 開発者向けオプション」を開く（ColorOS では「システム → 開発者向けオプション」等）
2. 「USB デバッグ」をオフにする
3. USB ケーブルを抜く
4. PC 側で `adb devices` を実行し、対象端末が表示されないことを確認する
5. 必要なら「USB デバッグの許可を取り消す」で承認済み PC の記録も消す

USB デバッグを無効化しても、インストール済みアプリと写真・アプリデータには影響しない。

署名鍵の作成・保管・バックアップ方針は [署名鍵の運用方針](signing-policy.md) を参照する。

## 2. 端末情報の記録（2026-11-10 実測）

| 項目 | 値 |
| --- | --- |
| 端末 | OPPO Reno13 A (CPH2699) |
| Android / ColorOS バージョン | Android 16 / ColorOS 16.0.5（`CPH2699_16.0.5.1300(EX01)`） |
| API レベル | 36 |
| 画像枚数（MediaStore） | 350,955 |
| SD カード使用有無 | 第2ボリューム `eeff-78c9` を確認（抜き差し試験は未実施） |
| 空き容量 | 約4.6GB（検証開始時） |
| 表示速度（キャッシュあり起動） | TotalTime avg 1.35秒 / 一覧初回表示 1.5〜2.1秒（初回のみ6〜9秒） |

取得コマンド:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\device.ps1 -Action Info
```

## 3. ビルドから導入までの流れ

```powershell
# 1. ビルド環境の確認
powershell -ExecutionPolicy Bypass -File scripts\check-env.ps1

# 2. 単体テスト + debug APK 生成
powershell -ExecutionPolicy Bypass -File scripts\build-and-test.ps1

# 3. 接続確認
powershell -ExecutionPolicy Bypass -File scripts\device.ps1 -Action Devices

# 4. 上書きインストール（アプリデータは維持される）
powershell -ExecutionPolicy Bypass -File scripts\device.ps1 -Action Install

# 5. 起動 + 起動時間計測
powershell -ExecutionPolicy Bypass -File scripts\device.ps1 -Action Measure -Runs 5

# 6. スクリーンショットとログ
powershell -ExecutionPolicy Bypass -File scripts\device.ps1 -Action Screenshot
powershell -ExecutionPolicy Bypass -File scripts\device.ps1 -Action Logs -OutputPath captures\logcat.txt

# まとめて実行する場合（ビルド込み）
powershell -ExecutionPolicy Bypass -File scripts\device.ps1 -Action Verify -Build
```

- 端末が複数接続されている場合は必ず `-Serial <serial>` を指定する。
- `-Action Install` は `adb install -r` を使う。同一 applicationId・同一署名鍵ならアプリデータは維持される。
- **アンインストール、端末リセット、写真の一括削除はスクリプト化していない。**

## 4. 起動時間の計測方法

`scripts\device.ps1 -Action Measure` は次の手順を繰り返す。

1. `am force-stop` でプロセスを終了する（コールドスタート条件）
2. `am start -W -n <package>/<activity>` を実行する
3. 出力の `TotalTime` / `WaitTime` / `Status` を記録する

出力例（形式のみ）:

```
Run 1: TotalTime=xxxms WaitTime=xxxms Status=ok
TotalTime: avg=xxxms min=xxxms max=xxxms (n=5)
```

`-OutputPath captures\startup.csv` を付けると CSV で保存できる（`-Output` もエイリアスとして使用可）。
計測値は端末の状態（権限許可、初回スキャン完了、画像枚数）に大きく左右されるため、
条件を記録してから比較する。

## 5. 受け入れチェックリスト（#9）

2026-11-10 に OPPO Reno13 A で一部を実施。未実施項目は残す。

- [ ] 画像アクセス権限を許可・拒否でき、拒否時にクラッシュしない（許可のみ確認）
- [ ] 権限拒否時も既存キャッシュが表示され、案内が出る
- [x] フォルダ一覧・画像一覧・画像詳細を閲覧できる
- [x] フォルダ一覧 ↔ 画像詳細を繰り返しても空白表示が出ない（60回+40回の計100往復で失敗0）
- [x] スクロール位置・表示中画像を保ったまま他アプリへ切替・復帰できる（ビューア 2/301 を復帰後も維持）
- [x] 再起動後に Room キャッシュから復元される（`am force-stop` 後の再起動で一覧復元）
- [ ] 画像の追加・削除・改名・移動が手動更新または変更通知で反映される
- [ ] 読み込み失敗時に一覧全体が消えない
- [x] 2,000 枚以上で実用速度が出る（350,955 枚で計測。200ms 目標は未達）
- [ ] SD カード使用時、カード内画像と切断時の挙動が安全である
- [x] 同じ署名鍵の APK 上書き更新でアプリデータが維持される

## 6. 自動化の範囲（#7）

| 処理 | 状態 | 実装 |
| --- | --- | --- |
| 環境確認 | 自動 | `scripts\check-env.ps1` |
| 単体テスト | 自動 | `scripts\build-and-test.ps1` |
| debug APK 生成 | 自動 | `scripts\build-and-test.ps1` |
| 端末情報取得 | 自動 | `scripts\device.ps1 -Action Info` |
| APK 導入 | 自動 | `scripts\device.ps1 -Action Install` |
| 起動時間計測 | 自動 | `scripts\device.ps1 -Action Measure` |
| スクリーンショット | 自動 | `scripts\device.ps1 -Action Screenshot` |
| ログ収集 | 自動 | `scripts\device.ps1 -Action Logs` |
| UI 操作の反復・操作感確認 | 一部自動 | 実機検証時に adb + uiautomator で100往復を自動実行（スクリプトはリポジトリ外） |

UI Automator ベースの adb 操作で「一覧→グリッド→ビューア→戻る」を100往復し、
一覧が空白にならないことを確認した。恒久的なUIテストスクリプトは未整備。

## 7. 実機検証結果（2026-11-10）

- 端末: OPPO Reno13 A / CPH2699 / ColorOS 16.0.5 / Android 16 / API 36
- 画像 350,955 件、フォルダ 2,640、Room DB 約248MB、空き容量 4.6GB
- キャッシュあり起動: TotalTime avg 1.35秒（min 1.25 / max 1.52、n=3）
- フォルダ一覧初回表示: 1.5〜2.1秒（初回のみ6〜9秒）。参考目標 200ms はこの規模では未達
- 一覧→グリッド→ビューア→戻る: 100往復で失敗0、空白表示なし
- ビューア: 1/301 → 2/301 のスワイプ成功、ホーム→復帰後も 2/301 を維持
- 除外フォルダ: 除外で即時に一覧から消え、設定の「解除」で復帰。再起動後も維持
- `adb install -r` の上書きでアプリデータ維持。クラッシュ/OOM/ANR なし

検証中に以下を修正した。

- ビューアの `startIndex` が String で入り ClassCastException（f9ded36）
- 350,955 件の全件走査時の OutOfMemoryError（500件バッチ化、f9ded36）
- 写真追加のたびに全件走査していた同期方式（差分走査＋24時間ごとの全件走査、f9ded36）
- ビューアのスワイプがズーム用ジェスチャーに奪われる問題（a66add5）
- 除外解除直後に画面を離れると設定が失われる問題と一覧の即時反映（e3a14c4）

## 8. 注意事項

- **個人の写真やアプリデータを削除しない。** アンインストール、端末リセット、写真の一括削除は行わない。
- スクリプトが端末側に作る一時ファイルはスクリーンショットのみで、取得後に削除する。
- 署名鍵・パスワードはコミットしない。継続利用前にリリース署名鍵の作成・オフライン保管・バックアップ方針を決める。
- `local.properties`、`captures/` はローカル固有・生成物のためコミットしない。
- adb が PATH に無い場合は `scripts\setup-android-env.ps1` を実行し、新しいターミナルを開く。
