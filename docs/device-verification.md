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

## 2. 端末情報の記録（未実施）

| 項目 | 値 |
| --- | --- |
| 端末 | OPPO Reno13 A |
| Android / ColorOS バージョン | 未取得 |
| API レベル | 未取得 |
| 画像枚数（MediaStore） | 未取得 |
| SD カード使用有無 | 未確認 |
| 空き容量 | 未取得 |
| 表示速度（キャッシュあり起動） | 未計測 |

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

## 5. 受け入れチェックリスト（#9、未実施）

実機がないため、以下はすべて未実施。実施時に結果を追記する。

- [ ] 画像アクセス権限を許可・拒否でき、拒否時にクラッシュしない
- [ ] 権限拒否時も既存キャッシュが表示され、案内が出る
- [ ] フォルダ一覧・画像一覧・画像詳細を閲覧できる
- [ ] フォルダ一覧 ↔ 画像詳細を繰り返しても空白表示が出ない（可能なら 100 往復）
- [ ] スクロール位置・表示中画像を保ったまま他アプリへ切替・復帰できる
- [ ] 再起動・プロセス再生成後に Room キャッシュから復元される
- [ ] 画像の追加・削除・改名・移動が手動更新または変更通知で反映される
- [ ] 読み込み失敗時に一覧全体が消えない
- [ ] 2,000 枚以上で実用速度が出る
- [ ] SD カード使用時、カード内画像と切断時の挙動が安全である
- [ ] 同じ署名鍵の APK 上書き更新でアプリデータが維持される

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
| UI 操作の反復・操作感確認 | 人が実施 | UI Automator / Maestro は実機導入後に必要性を判断 |

UI の反復操作を自動化する場合は、実機で実際の座標・文言を確認してから
UI Automator または Maestro のフローを追加する。現時点では未採用。

## 7. 注意事項

- **個人の写真やアプリデータを削除しない。** アンインストール、端末リセット、写真の一括削除は行わない。
- スクリプトが端末側に作る一時ファイルはスクリーンショットのみで、取得後に削除する。
- 署名鍵・パスワードはコミットしない。継続利用前にリリース署名鍵の作成・オフライン保管・バックアップ方針を決める。
- `local.properties`、`captures/` はローカル固有・生成物のためコミットしない。
- adb が PATH に無い場合は `scripts\setup-android-env.ps1` を実行し、新しいターミナルを開く。
