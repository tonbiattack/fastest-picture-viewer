package com.tonbiattack.fastestpictureviewer.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 同期状態を保持する単一行情報 (id=1 固定)。
 *
 * [lastSinceSec] 以降に DATE_MODIFIED が新しい画像だけを差分取得する。
 * 完全走査の成功時にのみ更新するため、一部ボリュームの走査に失敗した場合は
 * 前回値のままにして再度差分対象となる。
 */
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    /** 固定値 1 */
    @PrimaryKey val id: Int = 1,

    /** 前回の差分同期開始時刻 (エポック秒) */
    @ColumnInfo(name = "lastSinceSec") val lastSinceSec: Long,

    /** 前回の完全同期開始時刻 (エポック秒) */
    @ColumnInfo(name = "lastFullSyncSec") val lastFullSyncSec: Long,

    /**
     * 実体ファイルの読み取り検証を最後に完了した時刻 (エポック秒、Issue #77)。
     * 0 は未実施。定期的に再検証して、復活したファイルを表示へ戻す。
     */
    @ColumnInfo(name = "lastValidationSec", defaultValue = "0")
    val lastValidationSec: Long = 0L
)

/**
 * ボリューム単位の同期状態。
 *
 * MediaStore の世代番号 (generation) が変わっていなければそのボリュームの再走査を省略する。
 * 世代番号が取得できない環境 (API 29 など) では null となり、日時ベースの差分走査にフォールバックする。
 */
@Entity(tableName = "volume_sync_state")
data class VolumeSyncStateEntity(
    /** MediaStore のボリューム名 (external_primary / SD カードなど) */
    @PrimaryKey @ColumnInfo(name = "volumeName") val volumeName: String,

    /** 前回の走査時点の MediaStore 世代番号。取得できない環境では null */
    @ColumnInfo(name = "contentGeneration") val contentGeneration: Long?,

    /** 前回の走査開始時刻 (エポック秒) */
    @ColumnInfo(name = "lastScanSec") val lastScanSec: Long
)

/**
 * フォルダ一覧の集計結果を保持するキャッシュ (Issue #106)。
 *
 * 起動時に 35 万行を GROUP BY で集計すると実機で約 3.7 秒かかる。
 * 同期で内容が変わったときだけ作り直し、起動時はこのテーブルを読む。
 * 読み出しはフォルダ数（2,667 行）分の単純な SELECT で済む。
 *
 * [images] とは別に持つため、件数が古い瞬間があっても一覧は表示できる
 * (stale-while-revalidate)。同期完了時に [rebuiltAtSec] を更新する。
 */
@Entity(tableName = "folder_summaries")
data class FolderSummaryEntity(
    /** フォルダ識別子 (volumeName:relativePath) */
    @PrimaryKey @ColumnInfo(name = "folderKey") val folderKey: String,

    /** MediaStore のボリューム名 */
    @ColumnInfo(name = "volumeName") val volumeName: String,

    /** 正規化済みの相対パス (末尾スラッシュあり) */
    @ColumnInfo(name = "relativePath") val relativePath: String,

    /** フォルダ内の画像枚数 */
    @ColumnInfo(name = "imageCount") val imageCount: Long,

    /** フォルダ内で最も新しい更新日時 (エポック秒)。一覧の並び順に使う */
    @ColumnInfo(name = "latestModifiedSec") val latestModifiedSec: Long
)

/**
 * フォルダ代表サムネイルのキャッシュ (Issue #106)。
 *
 * [FolderSummaryEntity] と同じ理由で持つ。代表画像は自然順先頭の 1 枚なので、
 * 集計と一緒に作り直す。
 */
@Entity(tableName = "folder_representatives")
data class FolderRepresentativeEntity(
    /** フォルダ識別子 (volumeName:relativePath) */
    @PrimaryKey @ColumnInfo(name = "folderKey") val folderKey: String,

    /** 代表画像の content URI 文字列 */
    @ColumnInfo(name = "id") val id: String,

    /** 代表画像の更新日時。サムネイルキャッシュのキーに使う */
    @ColumnInfo(name = "dateModifiedSec") val dateModifiedSec: Long,

    /** 代表画像のファイルサイズ。サムネイルキャッシュのキーに使う */
    @ColumnInfo(name = "size") val size: Long
)

/**
 * フォルダ一覧キャッシュの状態 (単一行、id=1 固定)。
 *
 * 起動時にキャッシュが使えるかの判定と、同期で作り直すかの判定に使う。
 */
@Entity(tableName = "folder_summary_state")
data class FolderSummaryStateEntity(
    /** 固定値 1 */
    @PrimaryKey val id: Int = 1,

    /**
     * キャッシュを最後に作り直した時刻 (エポックミリ秒)。
     * 0 は未作成。起動時はこれが 0 でなければキャッシュを即表示する。
     */
    @ColumnInfo(name = "rebuiltAtMs") val rebuiltAtMs: Long,

    /**
     * 作り直した時点の画像総数。
     * 同期後の総数と一致すれば「作り直し不要」と判断する材料にする。
     */
    @ColumnInfo(name = "imageCount") val imageCount: Long
)

/**
 * フォルダ一覧から除外するフォルダ。
 *
 * [relativePath] は末尾スラッシュに正規化したパス接頭辞で、配下のサブフォルダにも適用する。
 * 元の画像ファイルには一切変更を加えない非破壊の設定。
 */
@Entity(tableName = "excluded_folders")
data class ExcludedFolderEntity(
    /** 除外したフォルダの識別子 (volumeName:relativePath) */
    @PrimaryKey @ColumnInfo(name = "folderKey") val folderKey: String,

    /** MediaStore のボリューム名 */
    @ColumnInfo(name = "volumeName") val volumeName: String,

    /** 正規化済みの相対パス (末尾スラッシュあり)。空文字はボリューム直下のみを表す */
    @ColumnInfo(name = "relativePath") val relativePath: String,

    /** 除外した日時 (エポック秒) */
    @ColumnInfo(name = "excludedAtSec") val excludedAtSec: Long
)

/**
 * フォルダ単位のお気に入り。
 *
 * 画像単位のお気に入り ([FavoriteImageEntity]) とは別テーブルで管理する。
 * 元の画像ファイルには変更を加えず、画像の追加/削除や同期では消えない。
 */
@Entity(tableName = "favorite_folders")
data class FavoriteFolderEntity(
    /** フォルダ識別子 (volumeName:relativePath) */
    @PrimaryKey @ColumnInfo(name = "folderKey") val folderKey: String,

    /** MediaStore のボリューム名 */
    @ColumnInfo(name = "volumeName") val volumeName: String,

    /** 相対パス (MediaStore の RELATIVE_PATH) */
    @ColumnInfo(name = "relativePath") val relativePath: String,

    /** 登録日時 (エポック秒) */
    @ColumnInfo(name = "addedAtSec") val addedAtSec: Long
)

/**
 * フォルダごとの画像一覧スクロール位置。
 * 画像の安定 ID をアンカーにし、削除時は保存時のインデックスへフォールバックする。
 */
@Entity(tableName = "folder_scroll_positions")
data class FolderScrollPositionEntity(
    /** フォルダ識別子 (volumeName:relativePath) */
    @PrimaryKey @ColumnInfo(name = "folderKey") val folderKey: String,

    /** 先頭可視画像の content URI。見つからない場合は null */
    @ColumnInfo(name = "anchorItemId") val anchorItemId: String?,

    /** 保存時点の先頭可視インデックス (アンカーが見つからない場合のフォールバック) */
    @ColumnInfo(name = "anchorIndex") val anchorIndex: Int,

    /** 先頭可視画像のピクセルオフセット */
    @ColumnInfo(name = "anchorOffsetPx") val anchorOffsetPx: Int,

    /** 更新日時 (エポック秒) */
    @ColumnInfo(name = "updatedAtSec") val updatedAtSec: Long
)

/**
 * フォルダ一覧のスクロール位置。並び順 (LATEST/RECENT) ごとに独立して保持する。
 * 検索・お気に入りフィルタ中の位置は保存しない。
 */
@Entity(tableName = "folder_list_scroll_positions")
data class FolderListScrollPositionEntity(
    /** 並び順の識別子 (FolderSortMode.name) */
    @PrimaryKey @ColumnInfo(name = "sortMode") val sortMode: String,

    /** 先頭付近の安定したフォルダ識別子。見つからない場合は null */
    @ColumnInfo(name = "anchorFolderKey") val anchorFolderKey: String?,

    /** 保存時点の先頭インデックス (アンカーが見つからない場合のフォールバック) */
    @ColumnInfo(name = "anchorIndex") val anchorIndex: Int,

    /** 先頭アイテムのピクセルオフセット */
    @ColumnInfo(name = "anchorOffsetPx") val anchorOffsetPx: Int,

    /** 更新日時 (エポック秒) */
    @ColumnInfo(name = "updatedAtSec") val updatedAtSec: Long
)

/** 最近閲覧したフォルダ。フォルダを開いた時刻だけを更新する */
@Entity(tableName = "recent_folders")
data class RecentFolderEntity(
    /** フォルダ識別子 (volumeName:relativePath) */
    @PrimaryKey @ColumnInfo(name = "folderKey") val folderKey: String,

    /** 最後に開いた日時 (エポック秒) */
    @ColumnInfo(name = "openedAtSec") val openedAtSec: Long
)

/**
 * フォルダに付与するタグ (Issue #46)。
 *
 * [normalizedName] は表示名を NFKC + 小文字化 + 空白圧縮した重複判定キーで、
 * UNIQUE インデックスにより同名タグの重複作成を防ぐ。元画像には変更を加えない。
 */
@Entity(
    tableName = "tags",
    indices = [Index(value = ["normalizedName"], unique = true)]
)
data class TagEntity(
    /** 自動採番のタグ ID */
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "tagId") val tagId: Long = 0L,

    /** 表示名 (前後空白除去・NFKC 正規化済み) */
    @ColumnInfo(name = "displayName") val displayName: String,

    /** 重複判定用の正規化名 (小文字化 + 空白圧縮) */
    @ColumnInfo(name = "normalizedName") val normalizedName: String,

    /** 作成日時 (エポック秒) */
    @ColumnInfo(name = "createdAtSec") val createdAtSec: Long
)

/**
 * フォルダとタグの多対多関係 (Issue #46)。
 * [folderKey] は images と同じ安定した識別子 (volumeName:relativePath) を使う。
 */
@Entity(
    tableName = "folder_tags",
    primaryKeys = ["folderKey", "tagId"],
    indices = [Index(value = ["tagId"])]
)
data class FolderTagEntity(
    /** フォルダ識別子 (volumeName:relativePath) */
    @ColumnInfo(name = "folderKey") val folderKey: String,

    /** タグ ID */
    @ColumnInfo(name = "tagId") val tagId: Long,

    /** 付与日時 (エポック秒) */
    @ColumnInfo(name = "addedAtSec") val addedAtSec: Long
)

/**
 * 実体ファイルを開けなかった画像 (Issue #77)。
 *
 * OPPO/ColorOS では、フォルダごと削除しても MediaStore の行が残ることがある。
 * その行は走査で毎回返ってくるため、アプリ側の削除判定では消せず、
 * サムネイルが壊れたまま・フォルダを開いても何も表示されない状態になる。
 *
 * そこで「実際に開けなかった」ことを記録し、一覧クエリから除外する。
 * images そのものは消さない (ファイルが復活したときに再表示できるようにするため)。
 * 走査でファイルが見つかるようになれば [GalleryRepositoryImpl] が行を消す。
 */
@Entity(
    tableName = "unreadable_images",
    indices = [Index(value = ["volumeName"]), Index(value = ["folderKey"])]
)
data class UnreadableImageEntity(
    /** 対応する images.id (content URI 文字列) */
    @PrimaryKey @ColumnInfo(name = "itemId") val itemId: String,

    /** 画像が属していたフォルダ識別子 (volumeName:relativePath) */
    @ColumnInfo(name = "folderKey") val folderKey: String,

    /** MediaStore のボリューム名。再走査時に該当ボリュームだけ解除するために持つ */
    @ColumnInfo(name = "volumeName") val volumeName: String,

    /** 開けなかったことを検出した日時 (エポック秒) */
    @ColumnInfo(name = "detectedAtSec") val detectedAtSec: Long
)

/**
 * お気に入り。画像ファイル本体には一切変更を加えず、DB 内のフラグだけで管理する。
 * 別テーブルにすることで、走査による images の再 upsert でお気に入りが失われない。
 */
@Entity(tableName = "favorite_images")
data class FavoriteImageEntity(
    /** 対応する images.id (content URI 文字列) */
    @PrimaryKey val itemId: String,

    /** 登録日時 (エポック秒) */
    @ColumnInfo(name = "addedAtSec") val addedAtSec: Long
)
