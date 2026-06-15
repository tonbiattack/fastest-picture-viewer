結論、**MediaStore + Room DBキャッシュ + RecyclerView/Compose Lazy系 + Coil** で作るのがよいです。

「毎回SDカードを走査するアプリ」ではなく、**最初に画像一覧をDB化して、画面表示はDBだけを見るアプリ**にします。

## 方針

### 1. 画像取得は `MediaStore` を使う

Androidで画像ファイル一覧を見るなら、基本は `MediaStore.Images.Media` です。
Android公式も共有ストレージ上のメディアファイルアクセスには MediaStore を案内しており、ランダムアクセスでは直接ファイルパスより MediaStore の方が推奨されています。([Android Developers][1])

取得する主なカラムはこのあたりです。

```kotlin
_id
DISPLAY_NAME
RELATIVE_PATH
DATE_MODIFIED
SIZE
MIME_TYPE
WIDTH
HEIGHT
```

`RELATIVE_PATH` は画像が保存されている相対パス、`DATE_MODIFIED` はファイル更新日時として使えます。([Android Developers][2])

---

### 2. 初回スキャン結果をRoomに保存する

重要なのはここです。

毎回こうしない。

```text
アプリ起動
→ SDカード全件スキャン
→ フォルダ集計
→ 表示
```

こうします。

```text
初回起動
→ MediaStoreを全件スキャン
→ Roomに保存
→ 以後はRoomから即表示
```

テーブル例です。

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

フォルダテーブルは持たなくてもよいです。
`relativePath` で集計すれば十分です。

```sql
SELECT
  relativePath,
  MAX(dateModified) AS latestModified,
  COUNT(*) AS imageCount
FROM ImageItem
GROUP BY relativePath
ORDER BY latestModified DESC
```

これで要件の、

> フォルダのソートは更新日付順で降順

を満たせます。

---

### 3. フォルダ内画像はDBから名前順で出す

```sql
SELECT *
FROM ImageItem
WHERE relativePath = :path
ORDER BY displayName ASC
```

これで、

> フォルダの中のファイルは名前順の昇順

を満たせます。

---

### 4. 「戻ったとき再読み込みしない」はViewModelで状態保持

画面構成はこうです。

```text
FolderListScreen
  ↓
ImageGridScreen(folderPath)
  ↓
ImageViewerScreen(imageId)
```

`FolderListViewModel` と `ImageGridViewModel` に一覧状態を持たせます。

特に保持したいのは、

```kotlin
data class FolderListState(
    val folders: List<FolderSummary>,
    val scrollIndex: Int,
    val scrollOffset: Int,
    val isInitialLoaded: Boolean
)
```

戻ったときに再クエリしない、というより、

* RoomのFlowを購読
* UI状態はViewModelに保持
* スクロール位置も保存
* 初回スキャン処理はアプリ起動ごとに毎回走らせない

という設計にします。

Composeなら `LazyListState` / `LazyGridState` を `rememberSaveable` または ViewModel 側で保持します。

---

## 最速寄りの実装構成

おすすめはこれです。

```text
Kotlin
Jetpack Compose
Room
MediaStore
Coil
WorkManager
Paging 3
```

ただし、最初のMVPなら Paging 3 は後回しでもよいです。

### MVP構成

```text
Kotlin
Jetpack Compose
Room
MediaStore
Coil
```

これで十分作れます。

---

## 画像表示はCoilを使う

サムネイル表示は自前でBitmapを作らない方がよいです。
Coilに任せます。

```kotlin
AsyncImage(
    model = image.uri,
    contentDescription = image.displayName
)
```

大量画像なら、一覧では必ず小さいサイズで読むようにします。

```kotlin
ImageRequest.Builder(context)
    .data(image.uri)
    .size(200)
    .crossfade(false)
    .build()
```

速さ重視なら `crossfade(false)` でよいです。
アニメーションは不要です。

---

## 初回スキャンの流れ

```kotlin
class MediaScanner(
    private val context: Context,
    private val dao: ImageItemDao
) {
    suspend fun scanImages() {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT
        )

        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        context.contentResolver.query(
            uri,
            projection,
            null,
            null,
            "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
        )?.use { cursor ->
            val items = mutableListOf<ImageItem>()

            while (cursor.moveToNext()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                val contentUri = ContentUris.withAppendedId(uri, id)

                items += ImageItem(
                    mediaId = id,
                    uri = contentUri.toString(),
                    displayName = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)),
                    relativePath = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)) ?: "",
                    dateModified = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)),
                    size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)),
                    mimeType = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)),
                    width = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)),
                    height = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)),
                )
            }

            dao.upsertAll(items)
        }
    }
}
```

---

## 再スキャン戦略

ここが速さの肝です。

### 起動時に全件再スキャンしない

起動時はまずDBを表示します。

```text
アプリ起動
→ Roomから即表示
→ 裏で差分スキャン
```

### 差分スキャン

雑にやるなら、

```text
前回スキャン時刻以降の DATE_MODIFIED の画像だけ取得
```

ただし削除検知が必要なので、たまに全件同期します。

おすすめはこれです。

```text
通常起動:
  DBから即表示
  裏で軽い差分スキャン

1日1回 or 手動更新:
  全件MediaStore同期
  消えた画像をDBから削除
```

---

## お気に入り機能

これは入れてよいです。
速さをほぼ落としません。

`ImageItem` に `isFavorite` を持たせるだけです。

```kotlin
@Query("UPDATE ImageItem SET isFavorite = :favorite WHERE mediaId = :mediaId")
suspend fun updateFavorite(mediaId: Long, favorite: Boolean)
```

ただし、お気に入り専用フォルダや同期などを凝ると重くなるので、最初はDB内フラグだけでよいです。

---

## 権限について

Android 13以降は画像読み取りに `READ_MEDIA_IMAGES` を使います。
一方、全ファイルアクセスの `MANAGE_EXTERNAL_STORAGE` は高リスク権限としてGoogle Playで制限されています。([Google ヘルプ][3])

このアプリは「画像を見る」用途なので、基本は **MediaStore + 画像権限** に寄せるべきです。
`MANAGE_EXTERNAL_STORAGE` は避けた方がよいです。

Photo Picker はユーザーが選んだ画像だけにアクセスする用途なので、「SDカード内の全画像をフォルダ別に高速閲覧」には向きません。公式にも、選択した画像・動画へのアクセスを安全に許可する仕組みとして説明されています。([Android Developers][4])

---

## 実装優先順位

最初はこの順番がよいです。

1. MediaStoreから画像一覧取得
2. Roomに保存
3. フォルダ一覧表示
4. フォルダ内画像グリッド表示
5. 画像詳細表示
6. 戻っても再読み込みしない状態保持
7. 裏側差分スキャン
8. お気に入り

---

## 個人的な設計案

最小構成はこれです。

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

このアプリで一番やってはいけないのは、**画面遷移のたびにMediaStoreやSDカードを全探索すること**です。

なので設計思想はシンプルに、

```text
重い処理: 初回・裏側・手動更新だけ
画面表示: Roomキャッシュだけ
画像読み込み: Coilに任せる
```

でよいと思います。

[1]: https://developer.android.com/training/data-storage/shared/media?utm_source=chatgpt.com "Access media files from shared storage | App data and files"
[2]: https://developer.android.com/reference/android/provider/MediaStore.Images.Media?utm_source=chatgpt.com "MediaStore.Images.Media | API reference - Android Developers"
[3]: https://support.google.com/googleplay/android-developer/answer/10467955?hl=en&utm_source=chatgpt.com "Use of All files access (MANAGE_EXTERNAL_STORAGE) ..."
[4]: https://developer.android.com/training/data-storage/shared/photo-picker?utm_source=chatgpt.com "Photo picker | App data and files - Android Developers"
