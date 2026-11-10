package com.tonbiattack.fastestpictureviewer.data

import androidx.room.ColumnInfo
import androidx.room.Entity
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
    @ColumnInfo(name = "lastFullSyncSec") val lastFullSyncSec: Long
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
