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

## 8. ビューアのスワイプ応答（Issue #101）

スワイプ時に次画像が引っかかる問題について、**「指を置いてドラッグが始まってから、
移動先のページが描かれるまで」** を測る。スナップアニメーションの完了は含めない
（含めるとデコードの差がアニメーション時間に埋もれるため）。

```powershell
# 先頭のフォルダを開いて 12 回スワイプ
powershell -ExecutionPolicy Bypass -File scripts\viewer-benchmark.ps1 -Runs 12

# 端末とフォルダを指定
powershell -ExecutionPolicy Bypass -File scripts\viewer-benchmark.ps1 -Serial <serial> -Folder PerfTest -Runs 20

# 改善前後を比較
powershell -ExecutionPolicy Bypass -File scripts\viewer-benchmark.ps1 -Baseline captures\perf\viewer-xxx.json
```

出力は `captures\perf\viewer-<timestamp>.json` と `.csv`。

### アプリ側が出すログ

`benchmark.ps1` と同じ `FPV-Startup` タグ・同じ書式で出す（抽出処理を共通化するため）。

```
mark=viewer_swipe_start at=16944
mark=viewer_page_drawn at=17088
span=viewer_swipe_start>viewer_page_drawn ms=145
viewer_image_loaded cached=true
```

- `span=...` … スワイプ開始からページ確定まで。連続スワイプでは最初の 1 回だけ記録する
- `cached=true` … 表示する画像がメモリキャッシュにあった（＝先読みが効いた）。
  キー（`URI@更新日時@サイズ`）を直接引いて判定する。Coil は構成済みページで
  `onSuccess` を再通知しないため、表示側のコールバックでは判定できない

### 何を変えたか

| 変更 | 理由 |
| --- | --- |
| 前後 2 枚の先読み（開いた直後は 1 枚） | スワイプ開始後に初めてデコードが走る状態をなくす |
| 先読みと表示で同じリクエスト設定を共有 | サイズ・精度が違うとメモリキャッシュが再利用されず、毎回再デコードになる |
| デコードサイズを画面実寸に固定し、精度を `INEXACT` に | レイアウトからのサイズ解決待ちと、原寸一致要求によるキャッシュ破棄を避ける |
| クロスフェードを切る | 前の画像が無い状態からのフェード待ちが「もたつき」に見えるため |
| `beyondViewportPageCount = 1` | スワイプ開始時点で隣のページを構成済みにする |
| ページ変更時に未完了の先読みをキャンセル | 素早い連続スワイプでデコード待ちを溜めない |

`beyondViewportPageCount` により、同時に保持するページは最大 3 枚（前・現在・次）に
制限される。全件先読みはしないため、メモリ使用量は画像枚数に比例しない。

### 計測結果（OPPO Reno13 A・実測）

端末: OPPO Reno13 A（CPH2699 / Android 16）
フォルダ: `Screenshots`（**2,210 枚**）
スワイプ: 1,080px 幅を 140ms で払う操作を 20 回

| 指標 | 値 |
| --- | --- |
| 有効試行 | 16 / 20（4 回はスワイプが登録されず＝末尾に到達） |
| 先読みヒット（`cached=true`） | **16 / 16** |
| 区間 P50 | 149ms |
| 区間 P95 | 160ms |
| 最短 / 最長 | 109ms / 160ms |

**区間値にはスワイプ操作自体の 140ms が含まれる。** 実質の描画遅れは
`区間値 - 140ms` で、P50 では約 **9ms**、最悪でも 20ms に収まっている。

`cached=true` が全件であることから、スワイプ時にデコードが走っていない
（＝先読みが効いている）ことは確認できた。

#### 改善前との比較

改善前のビルドは区間ログを持たないため、同じ操作での**フレーム統計**
（`dumpsys gfxinfo`、`Screenshots` 2,210 枚で 10 スワイプ × 3 回）で比べた。

| 指標 | 改善前 | 改善後 |
| --- | --- | --- |
| フレーム時間 P50 | 6〜8ms | 6〜9ms |
| フレーム時間 P95 | 10〜13ms | 10〜18ms |
| Janky frames | 1.1〜5.5% | 1.3〜6.3% |

**フレーム統計では差が出なかった。** この端末とフォルダでは描画そのものが
十分速く、差がノイズに埋もれている。先読みの効果は「デコードが走らないこと」
（`cached=true`）として確認するのが適切で、描画時間の短縮としては現れていない。

数十枚規模のフォルダでは、体感できる差は出ない可能性が高い。効果が効くのは
大きな画像・遅いストレージ（SD カード）の条件と考えられる。



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
