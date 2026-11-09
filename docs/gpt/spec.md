はい。
他のAIにそのまま投げやすいように、**実装指示プロンプト**としてまとめるならこうです。

Androidネイティブアプリを実装してください。

## アプリ名

fastest-picture-viewer

## 目的

Android端末内、特にSDカード内に大量に存在する画像ファイルを、できるだけ高速にフォルダ別で閲覧できる画像ビューアを作りたいです。

既存の画像ビューアでは、アプリ起動時やフォルダから戻った時に再読み込みが走り、待たされることがあります。
このアプリでは、それを避けたいです。

## 開発方針

Android Nativeで実装してください。

* 言語: Kotlin
* UI: Jetpack Compose
* DB: Room
* 画像読み込み: Coil
* 画像取得: MediaStore
* 対象OS: Androidのみ
* iOS対応は不要

## 最重要要件

毎回MediaStoreやSDカードを全件スキャンしないでください。

初回スキャン時にMediaStoreから画像のメタ情報を取得し、Roomに保存してください。
2回目以降の画面表示はRoomのDBキャッシュを使って即表示してください。

アプリ起動時は、

1. まずRoomからフォルダ一覧を即表示
2. 裏側で必要に応じて差分スキャン
3. 差分があればDBを更新

という構成にしてください。

## 機能要件

### 1. フォルダ一覧画面

画像が存在するフォルダを一覧表示してください。

フォルダの並び順は、フォルダ内の画像の最新更新日時が新しい順にしてください。

SQLイメージ:

```sql
SELECT
  relativePath,
  MAX(dateModified) AS latestModified,
  COUNT(*) AS imageCount
FROM ImageItem
GROUP BY relativePath
ORDER BY latestModified DESC
```

表示項目:

* フォルダ名
* 画像枚数
* 最新更新日時
* 可能なら代表サムネイル

### 2. フォルダ内画像一覧画面

フォルダをタップすると、そのフォルダ内の画像をグリッド表示してください。

画像の並び順はファイル名の昇順にしてください。

SQLイメージ:

```sql
SELECT *
FROM ImageItem
WHERE relativePath = :path
ORDER BY displayName ASC
```

大量画像に対応するため、全画像のViewを一気に生成しないでください。
Jetpack ComposeのLazyVerticalGridなどを使ってください。

### 3. 画像詳細画面

画像一覧から画像をタップすると、画像を大きく表示してください。

最低限、以下ができればよいです。

* 画像の拡大表示
* 戻る
* 前後の画像への移動

### 4. 戻った時に再読み込みしない

フォルダ内画像一覧からフォルダ一覧へ戻ったとき、再スキャンや重い再読み込みをしないでください。

また、画像詳細から画像一覧へ戻ったときも、できればスクロール位置を保持してください。

ViewModelで画面状態を保持してください。

保持したい状態:

* フォルダ一覧のスクロール位置
* 画像一覧のスクロール位置
* 選択中のフォルダ
* 初回ロード済みかどうか

### 5. お気に入り機能

可能であれば、お気に入り機能を実装してください。

ただし、速度が落ちるなら後回しで構いません。

実装する場合は、RoomのImageItemに `isFavorite` を持たせるか、別テーブル `favorite_images` を作ってください。

DB内のフラグ管理だけでよいです。
画像ファイル自体には変更を加えないでください。

## 非機能要件

最速表示を重視してください。

やってはいけないこと:

* アプリ起動のたびにMediaStoreを全件スキャンする
* 画面遷移のたびにSDカードを走査する
* 一覧表示時に画像本体のBitmapを全件読み込む
* サムネイルをDBにBLOB保存する
* EXIF全文を全画像分読み込む
* DB insertを1件ずつ大量実行する
* フォルダ一覧へ戻るたびに再集計で待たせる

DBには画像本体を保存しないでください。
保存するのはメタ情報だけにしてください。

## DB設計

Roomで以下のようなEntityを作成してください。

```kotlin
@Entity
data class ImageItem(
    @PrimaryKey val mediaId: Long,
    val uri: String,
    val displayName: String,
    val relativePath: String,
    val dateModified: Long,
    val size: Long,
    val mimeType: String?,
    val width: Int?,
    val height: Int?,
    val isFavorite: Boolean = false
)
```

必要に応じてindexを付けてください。

推奨index:

```kotlin
indices = [
    Index(value = ["relativePath"]),
    Index(value = ["relativePath", "displayName"]),
    Index(value = ["dateModified"]),
    Index(value = ["isFavorite"])
]
```

## MediaStore取得項目

MediaStore.Images.Media から以下を取得してください。

* _ID
* DISPLAY_NAME
* RELATIVE_PATH
* DATE_MODIFIED
* SIZE
* MIME_TYPE
* WIDTH
* HEIGHT

URIは以下のように作ってください。

```kotlin
val contentUri = ContentUris.withAppendedId(
    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
    id
)
```

## 権限

Android 13以降では `READ_MEDIA_IMAGES` を使ってください。

Android 12以前も対応する場合は `READ_EXTERNAL_STORAGE` を使ってください。

全ファイルアクセス権限 `MANAGE_EXTERNAL_STORAGE` は使わないでください。
Google Play上の制限が強く、今回の画像ビューア用途では避けたいです。

## スキャン仕様

初回起動時:

1. 権限確認
2. 権限があればMediaStoreから画像一覧取得
3. メタ情報をRoomに保存
4. フォルダ一覧表示

2回目以降:

1. Roomから即フォルダ一覧を表示
2. 裏側で差分スキャン
3. 変更があればRoomを更新

差分スキャンはまず簡易で構いません。

例:

* 前回スキャン時刻以降に更新された画像だけ取得
* たまに全件同期して削除済み画像をDBから消す
* 手動更新ボタンで全件再同期できるようにする

## パフォーマンス目標

画像2000枚程度なら以下を目指してください。

* 初回スキャン: 数秒以内
* 2回目以降のアプリ起動: ほぼ即表示
* フォルダタップから画像一覧表示: 0.3秒以内を目標
* 戻る操作: 体感即時

## 画面構成

以下の構成で実装してください。

```text
FolderListScreen
  ↓
ImageGridScreen
  ↓
ImageViewerScreen
```

パッケージ構成は以下のようにしてください。

```text
app/
  data/
    ImageItem.kt
    ImageItemDao.kt
    AppDatabase.kt
    MediaScanner.kt

  repository/
    ImageRepository.kt

  ui/
    folder/
      FolderListScreen.kt
      FolderListViewModel.kt
    grid/
      ImageGridScreen.kt
      ImageGridViewModel.kt
    viewer/
      ImageViewerScreen.kt
      ImageViewerViewModel.kt
```

## 実装順序

以下の順番で実装してください。

1. Android StudioのKotlin + Jetpack Composeプロジェクト作成
2. 権限リクエスト処理
3. MediaStoreから画像一覧を取得してLogcatに件数を出す
4. Room Entity / DAO / Database作成
5. MediaStoreの結果をRoomに一括保存
6. Roomからフォルダ一覧を取得して表示
7. フォルダ内画像をGrid表示
8. Coilでサムネイル表示
9. 画像詳細画面を実装
10. 戻る時のスクロール位置保持
11. 差分スキャン
12. お気に入り機能

## 補足

まずはMVPを優先してください。

最初から完璧な差分スキャンやお気に入り機能まで作り込まなくてよいです。

最優先は、

* 起動が速い
* フォルダ一覧が速い
* フォルダ内画像一覧が速い
* 戻っても再読み込みで待たされない

ことです。

実装コードは、動作する最小構成から段階的に提示してください。
一度に巨大な完成コードを出すのではなく、まずはMediaStore取得、次にRoom保存、次に一覧表示というように分割して説明してください。
