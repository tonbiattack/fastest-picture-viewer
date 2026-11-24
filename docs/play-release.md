# Google Play 公開手順

Pictobi を Google Play で公開するための手順 (Issue #121)。
**コードで用意できるもの**と、**オーナーが手作業で行うもの**を分けて書く。

## 1. 全体の流れ

```
[コード] AAB ビルド → 署名検証
   ↓
[手作業] Play Console 登録・本人確認・支払い
   ↓
[手作業] アプリ作成・ストア掲載情報の入力
   ↓
[手作業] 内部テストで AAB をアップロード・実機確認
   ↓
[手作業] クローズドテスト（必要な場合）
   ↓
[手作業] 本番公開
```

## 2. コード側で用意済みのもの

| 項目 | 場所 | 状態 |
| --- | --- | --- |
| AAB ビルドスクリプト | `scripts/build-aab.ps1` | 作成済み・署名検証まで動作確認 |
| ストアアイコン (512x512) | `store/icon-512.png` | 生成済み |
| フィーチャーグラフィック (1024x500) | `store/feature-graphic-1024x500.png` | 生成済み |
| スクリーンショット (1200x1920 × 4) | `store/screenshots/` | 撮影済み（合成画像のみ） |
| 掲載文（短い説明・詳しい説明） | `docs/store-listing.md` | 作成済み |
| データセーフティ申告の原稿 | `docs/store-listing.md` 6 章 | 作成済み |
| プライバシーポリシー | `public/privacy.html`（GitHub Pages で公開中） | 公開済み |
| R8 有効ビルドの動作検証 | `-Pfpv.minifyDebug=true` | 実機で主要機能を確認済み |

### 素材の再生成

```powershell
python -X utf8 scripts\make-store-icon.py
python -X utf8 scripts\make-feature-graphic.py
python -X utf8 scripts\make-sample-images.py
```

スクリーンショットは端末で撮る。合成画像を端末へ入れてから撮影する手順は
`docs/store-listing.md` 4 章を参照。

## 3. AAB のビルド

```powershell
# 既定のバージョンで
powershell -ExecutionPolicy Bypass -File scripts\build-aab.ps1

# バージョンを指定して
powershell -ExecutionPolicy Bypass -File scripts\build-aab.ps1 -VersionCode 2 -VersionName 0.2.0

# 既存の AAB を検証だけする
powershell -ExecutionPolicy Bypass -File scripts\build-aab.ps1 -VerifyOnly
```

出力: `app\build\outputs\bundle\release\app-release.aab`

スクリプトは次を確認する。

- 署名情報が揃っているか（無ければ**中止**する。未署名 AAB は Play が拒否する）
- ビルドが成功したか
- `jarsigner` で署名が有効か

## 4. 署名の考え方（重要）

Play に公開すると、**署名の扱いが GitHub Releases と変わる**。

| 項目 | GitHub Releases | Google Play |
| --- | --- | --- |
| 署名する鍵 | 自分のリリース鍵 | アップロード鍵（自分）＋ アプリ署名鍵（Google） |
| 配布 APK の署名 | 自分のリリース鍵 | Google が管理する鍵で再署名 |
| 鍵を失った場合 | 更新版を出せない | アップロード鍵は再発行を申請できる |

### Play App Signing を有効にする

初回アップロード時に Play Console が案内する。**有効にすることを推奨**する。

- アップロード鍵: 手元の `pictobi-release.jks`。AAB の署名に使う
- アプリ署名鍵: Google が生成・管理。実際の配布 APK はこちらで署名される

### 既存ユーザーへの影響

**GitHub Releases 版と Play 版は別の署名になるため、相互に上書きできない。**

- GitHub 版から Play 版へ移るには、アンインストールが必要（アプリデータが消える）
- 移行前に**バックアップ JSON を書き出す**こと
- この注意は README にも記載済み

## 5. Play Console での作業（オーナー）

### 5-1. 開発者アカウント

1. [Play Console](https://play.google.com/console/) で登録
2. 初回登録料を支払う
3. 本人確認（身分証の提出）を完了する
4. 連絡先メールアドレスを設定する

### 5-2. アプリの作成

| 項目 | 入力値 |
| --- | --- |
| アプリ名 | Pictobi |
| 既定の言語 | 日本語 |
| アプリまたはゲーム | アプリ |
| 無料または有料 | 無料 |
| 宣言 | 該当するものにチェック |

### 5-3. ストアの掲載情報

`docs/store-listing.md` の内容をそのまま入力する。

- 短い説明（80 文字以内）
- 詳しい説明（4000 文字以内）
- アプリのアイコン: `store/icon-512.png`
- フィーチャーグラフィック: `store/feature-graphic-1024x500.png`
- スマートフォンのスクリーンショット: `store/screenshots/` の 4 枚

### 5-4. プライバシーポリシー

GitHub Pages で公開済み（Issue #136）。

**https://tonbiattack.github.io/fastest-picture-viewer/privacy.html**

この URL を Play Console の「プライバシーポリシー」欄に入力する。

仕組み:

- 公開用の HTML は `public/privacy.html`（`docs/privacy-policy.md` の内容を HTML 化したもの）
- `.github/workflows/pages.yml` が `public/` の中身だけを Pages にデプロイする
- `docs/` をソースにすると署名方針などの内部ドキュメントも公開されるため、
  専用ディレクトリに分けている

`docs/privacy-policy.md` を更新したら、`public/privacy.html` も同じ内容に更新する。

### 5-5. データセーフティ

`docs/store-listing.md` 6 章の表に従って入力する。
**実装と一致させること。** 依存ライブラリを追加したら見直す。

### 5-6. コンテンツレーティング

アンケートに回答する。想定は 3+（全年齢）。

### 5-7. 内部テスト

1. 「テスト」→「内部テスト」→「新しいリリースを作成」
2. `app-release.aab` をアップロード
3. リリースノートを入力（`docs/store-listing.md` 9 章）
4. テスターを登録（自分のメールアドレス）
5. 配布リンクから実機へインストール
6. 主要機能を確認

### 5-8. クローズドテスト（必要な場合）

新しい個人開発者アカウントでは、本番公開の前に
**12 人以上・14 日間連続のクローズドテスト**が必要な場合がある。
適用要件はアカウントの作成時期・種別で変わるため、Play Console の案内に従う。

### 5-9. 本番公開

1. 「本番」→「新しいリリースを作成」
2. 内部テストで確認した AAB と同じものをアップロード
3. 段階的公開の割合を決める（初回は 20% など）
4. 審査に提出

## 6. 公開後の運用

### 更新の流れ

```powershell
# 1. versionCode を上げて AAB を作る
powershell -ExecutionPolicy Bypass -File scripts\build-aab.ps1 -VersionCode 2 -VersionName 0.2.0

# 2. Play Console で新しいリリースを作成し、AAB をアップロード
# 3. 段階的に公開
```

`versionCode` は**必ず前回より大きく**する。同じ値はアップロードできない。

### ロールバック

Play は一度公開したバージョンを取り下げられない。
問題が出た場合は、修正版を**より大きい versionCode** で出して置き換える。

段階的公開中なら、割合を 0% にして停止できる。

### 問い合わせ対応

- GitHub Issues: https://github.com/tonbiattack/fastest-picture-viewer/issues
- ストアのレビュー欄

## 7. 審査で指摘されやすい点

| 指摘 | 対策 |
| --- | --- |
| 写真権限の用途が不明確 | `docs/store-listing.md` 6 章の用途説明をそのまま使う |
| プライバシーポリシーが実装と違う | 依存追加のたびに `docs/privacy-policy.md` の付記を確認する |
| データセーフティの申告漏れ | 6 章の表と実装を突き合わせる |
| スクリーンショットに実在の人物 | 合成画像のみを使う（`scripts/make-sample-images.py`） |
| クラッシュ | リリース前に R8 有効ビルドで主要機能を確認する |

## 8. 未実施・確認が必要な項目

- [ ] Play Console の開発者登録・本人確認・支払い（オーナー）
- [x] プライバシーポリシーの公開 URL 化
      → https://tonbiattack.github.io/fastest-picture-viewer/privacy.html
- [ ] 内部テストでの実機インストールと主要機能の確認
- [ ] クローズドテストの要否確認（アカウント種別による）
- [ ] 本番公開の申請
- [ ] OPPO Reno13 A 以外の端末での動作確認（可能なら）
- [ ] リリース署名済み APK を実機へ入れての動作確認
      （debug 署名と衝突するため、未実施）
