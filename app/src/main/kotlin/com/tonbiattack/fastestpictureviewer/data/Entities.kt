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
