# Auto Backup（OS のクラウドバックアップ）

Issue #151 の実装メモ。タグ・お気に入り・除外フォルダ・表示設定を
Android 標準の Auto Backup で復元できるようにした内容をまとめる。

手動バックアップ（JSON の書き出し・復元）は従来どおり併用できる。
手順は [install-and-update.md](install-and-update.md) を参照。

## なぜ必要だったか

- タグ・お気に入り・除外フォルダは Room DB にある
- 実機の DB は約 295MB あり、Auto Backup のアプリ単位上限（25MB）を超える
- そのため #63 で DB を Auto Backup の対象外にしていた
- 結果、機種変更するとタグ・お気に入り・除外が失われていた

## 仕組み

再生成できないデータだけを小さな JSON へ書き出し、Auto Backup の対象に置く。

```text
files/auto_backup/auto_backup.json   ← Auto Backup の対象（既定で files/ は対象）
files/auto_backup/auto_backup.json.tmp  ← 書き込み中の一時ファイル
```

| 項目 | 内容 |
| --- | --- |
| 書き出しの契機 | タグ・お気に入り・除外の変更時（`onUserDataChanged`） |
| まとめ方 | 5 秒の debounce。連続操作では最後の 1 回だけ書く |
| 書き込み方法 | 一時ファイルへ書いてから rename（原子的）。失敗時は前回の控えを残す |
| 復元の契機 | 起動時に 1 回 |
| 復元の条件 | 控えがあり、かつ DB にユーザーデータが 1 件も無い |
| 復元の方法 | `BackupRepository.restore` の追加マージ（二重に走っても重複しない） |
| 対象外 | Room DB・画像そのもの・サムネイル・スクロール位置 |

### 復元条件を「DB が空のときだけ」にした理由

DB を除外しているため、Auto Backup から復元した直後は DB が存在しない
（＝新規作成され空になる）。この状態だけを取り込めば、
通常の起動・アプリ更新で既存データを触らずに済む。

追加マージなので、仮に条件が緩んでも重複はしない。

## 保存される表示設定

`BackupSettings` に含める設定（#151 で追加したものを含む）:

- フォルダ一覧の列数、並び順（LATEST / RECENT）、「前回の続き」カードの表示
- 画像グリッドの縦横別列数
- 画像一覧の並び順（NAME / DATE_NEWEST / DATE_OLDEST）
- フォルダ単位の並び順の上書き
- スクロール位置の復元（オン/オフ）
- 「見つからない画像」通知の表示
- スライドショーの表示間隔

古いバックアップ（#74 形式）には無い項目があるため、読み込み時は既定値を使う。
未知の値（`ImageSortMode` に無い名前など）は無視し、現在の設定を変えない。

## Auto Backup の対象範囲

`app/src/main/res/xml/backup_rules.xml`（API 30 以前）と
`app/src/main/res/xml/data_extraction_rules.xml`（API 31 以降）で、
巨大な Room DB だけを除外している。

**`include` は書かない。** 書くと「include したものだけ」が対象になり、
SharedPreferences（表示設定）が対象から外れてしまうため。

## 検証

### 自動テスト

`AutoBackupStoreTest`（11 件）と `AutoBackupCoordinatorTest`（10 件）。

- 控えの保存・読み戻し・置き換え・削除
- 25MB 超過時に書かずに結果で知らせる（既存の控えを壊さない）
- 一時ファイルを残さない
- 変更時に書き出す／連続変更は 1 回にまとめる
- DB が空で控えがあれば復元する
- 既にデータがある端末では復元しない
- 控えが壊れていても落ちない
- 復元に失敗しても例外を外へ出さない

`org.json` が Android 実装に依存するため、Robolectric（`@Config(sdk = [34])`）で実行する。

### 実機で確認したこと（OPPO Reno13 A / Android 16 / ColorOS 16.0.5）

debug 版（別 applicationId）で確認。既存のリリース版データには触れていない。

| 確認項目 | 結果 |
| --- | --- |
| フォルダをお気に入りに追加すると控えが生成される | ✅ `files/auto_backup/auto_backup.json`（732 bytes） |
| 控えにお気に入りと設定が入る | ✅ `favoriteFolders` 1 件、`settings` 一式 |
| DB を削除して起動すると復元される | ✅ ログ `FPV-Perf: auto backup restored` |
| 復元結果が UI に出る | ✅ お気に入りフィルタに `preview` が表示され「解除」状態 |

### 確認できなかったこと

- **実クラウド（Google）経由のバックアップ・復元は未検証。**
  実機の `bmgr backupnow` は `Transport error` を返した。
  debug ビルドは Auto Backup の対象外であるため、リリースビルドで
  Google アカウントのバックアップが走る環境でないと確認できない
- LocalTransport に切り替えても `Transport error` だった（同上の理由）

## 制約

- **実行時刻は OS が決める。** アプリからは保証できない
- **画像のお気に入りは端末ごとに URI が変わる。** 復元しても紐付かないことがある
- 控えはタグ・お気に入り・除外の件数に比例して大きくなる。
  25MB を超えた場合は書かずに記録する（黙って切り捨てない）
- 手動バックアップと違い、保存先は選べない
