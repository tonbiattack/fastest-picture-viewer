# 性能計測（ベンチマーク）

起動・復帰・フォルダ遷移を同じ条件で繰り返し測り、P50（中央値）と P95 を残す。
「速くなった気がする」ではなく、数値で判断するための仕組み（Issue #99）。

## 1. 何を測るか

| シナリオ | 内容 | 主指標 |
| --- | --- | --- |
| `cold` | プロセス停止 → 起動 → フォルダ一覧が描画されるまで | 初回フレームまでの時間 |
| `warm` | ホームへ戻る → 復帰 → 一覧が再描画されるまで | 復帰からの時間 |
| `navigation` | フォルダ一覧 → グリッド、グリッド → 一覧（戻る） | ホスト側の待機込み実測 |

**「一覧が操作可能になった時点」を初回フレームの描画と定義する。**
サムネイルが全部読み終わる時刻とは別物なので、混同しない。

## 2. 使い方

```powershell
# 3シナリオを 10 回ずつ
powershell -ExecutionPolicy Bypass -File scripts\benchmark.ps1

# コールド起動だけ 20 回
powershell -ExecutionPolicy Bypass -File scripts\benchmark.ps1 -Scenario cold -Runs 20

# 前回結果と比較（5% 以上の悪化を「回帰」として表示）
powershell -ExecutionPolicy Bypass -File scripts\benchmark.ps1 -Baseline captures\perf\20261115-174416.json

# 端末が複数あるとき
powershell -ExecutionPolicy Bypass -File scripts\benchmark.ps1 -Serial <serial>
```

主な引数:

| 引数 | 既定 | 意味 |
| --- | --- | --- |
| `-Scenario` | `all` | `cold` / `warm` / `navigation` / `all` |
| `-Runs` | `10` | 試行回数 |
| `-TargetMs` | `1000` | コールド起動の目標（P50 で判定） |
| `-Baseline` | なし | 比較する前回の JSON |
| `-OutputDir` | `captures\perf` | 出力先 |

出力は `captures\perf\<timestamp>.json`（集計）と `.csv`（1 行 1 試行）。
`captures/` は `.gitignore` 済みで、リポジトリには入らない。

## 3. 前提

- 端末が USB デバッグで接続され、Pictobi がインストール済み
- 画像の閲覧権限を許可済みで、初回スキャンが完了している
- 計測用のビルドが入っていること
  - **debug は既定で計測ログ有効**
  - release で測る場合は `-Pfpv.perfTrace=true` を付けてビルドする
  - 通常配布の release は無効（オーバーヘッドをゼロにするため）

## 4. 計測の仕組み

### アプリ側

`FPV-Startup` タグで logcat に区間を出す。時刻は `SystemClock.elapsedRealtime` 基準。

```
mark=app_oncreate_start at=0
mark=db_open at=77
mark=repository_ready at=83
mark=app_oncreate_end at=92
mark=folder_list_ready at=912
mark=folder_list_drawn at=941
span=app_oncreate_start>folder_list_drawn ms=941
```

- `mark=... at=N` … プロセス内の基準時刻からの経過
- `span=A>B ms=N` … 区間の所要時間

`Process.getStartElapsedRealtime` は端末によって 0 を返すため使わない（エミュレータで確認）。
代わりに `SystemClock.elapsedRealtime` を使い、最初の計測点を基準にしている。

### PC 側

`scripts\benchmark.ps1` が次を行う。

1. 端末アニメーションを 0 にして揺らぎを減らす（終了時に元へ戻す）
2. `am force-stop` → `am start -W` を繰り返す
3. logcat から区間を取り出し、取れなければ `am` の値で代替（`source` 列に記録）
4. P50/P95 を計算して JSON/CSV に保存する

集計ロジックはアプリ側の `BenchmarkStats` と同じ定義で、
CI では `BenchmarkStatsTest` が検証する。

## 5. 結果の読み方

```json
{
  "scenario": "cold_start",
  "runs": 10,
  "validRuns": 10,
  "min": 676,
  "max": 736,
  "p50": 704,
  "p95": 736
}
```

- `validRuns` が `runs` より少ない場合、計測に失敗した試行がある（`failedRuns`）
- P50 で目標達成を判定する。P95 は「たまに遅い」を見るため
- `comparison` は 5% 未満の差をノイズとして扱う（端末の状態で容易に動くため）

中央値は**実測値の中から選ぶ**（偶数件でも平均しない）。
「250ms」のような実在しない値を報告しないため。

## 6. 注意

- **実機の計測は、対応端末が接続された環境でしかできない。** GitHub Actions では実行しない
- 端末の状態（温度・他アプリ・空き容量）で値が動く。比較するときは条件を揃える
- 自動操作は force-stop とタップのみで、写真やアプリデータを削除しない
- 端末側に作るファイルは無い（スクリーンショットも撮らない）

## 7. 検証済みの範囲

エミュレータ（Android 16 / API 36）で 3 シナリオとも動作を確認した。

| シナリオ | 確認できたこと |
| --- | --- |
| cold | 区間ログから初回フレームを取得、P50/P95 を算出 |
| warm | 復帰の区間を取得 |
| navigation | タップと戻るの実測を取得 |
| ベースライン比較 | 改善/回帰の判定を出力 |

**OPPO Reno13 A（35万枚）でのベースラインは未取得。** 実機テストを行わない方針のため、
必要になった時点で `-Runs 20` で実行する。

## 8. 起動時間の内訳（Issue #98 の調査結果）

エミュレータ（Android 16 / API 36 / 画像 201 枚 / 21 フォルダ / ハードウェア GPU）で
区間を計測した結果。数値は 1 回の実測例。

| 区間 | 時間 | 割合 |
| --- | --- | --- |
| `app_oncreate_start` → `app_oncreate_end`（DB open 含む） | 101ms | 6% |
| `before_set_content` → `set_content`（Compose 初期化） | 238ms | 13% |
| `set_content` → `folder_list_vm_created` | 95ms | 5% |
| `vm_created` → `data_source_combined`（Room 初回クエリ） | **486ms** | **27%** |
| `data_source_combined` → `folder_list_ready` | 7ms | 0.4% |
| `folder_list_ready` → `folder_list_drawn`（実描画） | 約 200ms | 11% |

### 分かったこと

- **Room の初回クエリ待ちが最大**（486ms）。5 つの Flow（フォルダ集計・代表サムネイル・
  お気に入り・最近閲覧・タグ）が揃うまで `combine` が emit しないため、
  一覧表示がその分待たされる
- SQL 自体は軽い。取得した DB で計測すると、フォルダ集計・代表サムネイルとも **0.1ms**（201 行）
- クエリプランも良好。フォルダ集計は `index_images_folderKey_dateModifiedSec` を使い、
  相関サブクエリはインデックスで解決している
- `Skipped 36 frames` は同期処理ではなく、21 フォルダの初回合成中のもの

### 計測時の注意（重要な罠）

**エミュレータの GPU 設定で結果が 2 倍以上変わる。**

| GPU 設定 | コールド起動 P50（201 枚） |
| --- | --- |
| `-gpu swiftshader_indirect`（ソフトウェア） | 3588ms |
| `-gpu host`（ハードウェア） | 941ms |

`setContent` の初期化だけで 1048ms 対 238ms の差が出た。
比較するときは GPU 設定を固定し、レポートの `device` と合わせて記録すること。

また `Process.getStartElapsedRealtime` はこの環境で 0 を返すため使っていない。

## 9. 最適化を保留している理由

Issue #98（起動高速化）は**調査のみ完了し、最適化は保留**している。

### 理由: エミュレータでは効果を判定できない

同じ条件で測っても値が安定しない。

| 測定 | コールド起動 P50 |
| --- | --- |
| ベースライン | 941ms |
| 変更後（1回目） | 897ms |
| 変更後（再起動後） | 1009ms |

起動ごとに 800〜1300ms とばらつき、差分は測定誤差と区別できない。
この状態で「改善した」と主張するのは不誠実なので、コード変更は取り消した。

### 現時点で分かっている最適化候補

計測から、**Room の初回クエリ待ち（486ms、全体の 27%）** が最大と判明している。

`dataSource` は 5 つの Flow を `combine` しており、すべての初回 emit が揃うまで一覧が出ない。
フォルダ集計を他 4 つと切り離して先に emit させれば短縮できる見込み。

ただし SQL 自体は 0.1ms で無実なので、**SQL のチューニングでは効果がない**。

### 再開するとき

1. OPPO Reno13 A（35万枚）で `benchmark.ps1 -Runs 20` を実行しベースラインを取る
2. フォルダ集計を他 4 つと切り離す
3. 同一端末・同一条件で再計測し、P50 の差がばらつきを超えているか確認する

### 当面の使い方

計測基盤は**退行の検知**に使う。改善を主張するのではなく、
「以前より遅くなっていないか」を確認する用途に限定する。
