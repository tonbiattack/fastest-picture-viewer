package com.tonbiattack.fastestpictureviewer.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** タグと付与件数の集計行 */
data class TagWithCount(
    @ColumnInfo(name = "tagId") val tagId: Long,
    @ColumnInfo(name = "displayName") val displayName: String,
    @ColumnInfo(name = "normalizedName") val normalizedName: String,
    @ColumnInfo(name = "folderCount") val folderCount: Long
)

/** フォルダに付与されたタグの行 (フォルダ一覧の表示用) */
data class FolderTagNameRow(
    @ColumnInfo(name = "folderKey") val folderKey: String,
    @ColumnInfo(name = "tagId") val tagId: Long,
    @ColumnInfo(name = "displayName") val displayName: String
)

/**
 * フォルダタグの DAO (Issue #46)。
 *
 * タグ本体 ([TagEntity]) とフォルダとの紐付け ([FolderTagEntity]) を扱う。
 * 付与・解除は元画像や images テーブルに影響しない。
 */
@Dao
interface TagDao {

    /** 全タグを名前順で購読する */
    @Query("SELECT * FROM tags ORDER BY displayName ASC, tagId ASC")
    fun observeTags(): Flow<List<TagEntity>>

    /** タグごとの付与フォルダ数を購読する */
    @Query(
        """
        SELECT t.tagId AS tagId, t.displayName AS displayName,
               t.normalizedName AS normalizedName, COUNT(ft.folderKey) AS folderCount
        FROM tags AS t
        LEFT JOIN folder_tags AS ft ON ft.tagId = t.tagId
        GROUP BY t.tagId
        ORDER BY t.displayName ASC, t.tagId ASC
        """
    )
    fun observeTagsWithCount(): Flow<List<TagWithCount>>

    /** フォルダとタグの紐付けを購読する */
    @Query("SELECT * FROM folder_tags")
    fun observeFolderTags(): Flow<List<FolderTagEntity>>

    /** フォルダに付いたタグ名を購読する (フォルダ一覧のバッジ表示用) */
    @Query(
        """
        SELECT ft.folderKey AS folderKey, t.tagId AS tagId, t.displayName AS displayName
        FROM folder_tags AS ft
        INNER JOIN tags AS t ON t.tagId = ft.tagId
        ORDER BY ft.folderKey ASC, t.displayName ASC
        """
    )
    fun observeFolderTagNames(): Flow<List<FolderTagNameRow>>

    /** 正規化名でタグを検索する (重複作成の防止) */
    @Query("SELECT * FROM tags WHERE normalizedName = :normalizedName LIMIT 1")
    suspend fun findByNormalizedName(normalizedName: String): TagEntity?

    /** タグを登録し、採番された ID を返す。同名が既にあれば既存行の ID を返す */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(tag: TagEntity): Long

    /** タグ名を変更する */
    @Query("UPDATE tags SET displayName = :displayName, normalizedName = :normalizedName WHERE tagId = :tagId")
    suspend fun rename(tagId: Long, displayName: String, normalizedName: String)

    /** タグと紐付けを削除する */
    @Transaction
    suspend fun deleteTag(tagId: Long) {
        deleteLinksByTag(tagId)
        deleteTagRow(tagId)
    }

    @Query("DELETE FROM folder_tags WHERE tagId = :tagId")
    suspend fun deleteLinksByTag(tagId: Long)

    @Query("DELETE FROM tags WHERE tagId = :tagId")
    suspend fun deleteTagRow(tagId: Long)

    /** フォルダにタグを付与する (既に付いていれば何もしない) */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addLink(link: FolderTagEntity)

    /** フォルダからタグを解除する */
    @Query("DELETE FROM folder_tags WHERE folderKey = :folderKey AND tagId = :tagId")
    suspend fun removeLink(folderKey: String, tagId: Long)

    /** 複数フォルダへまとめて付与する (既存の他タグは消さない) */
    @Transaction
    suspend fun addLinks(links: List<FolderTagEntity>) {
        links.forEach { addLink(it) }
    }

    /** フォルダに付いているタグ ID を取得する */
    @Query("SELECT tagId FROM folder_tags WHERE folderKey = :folderKey")
    suspend fun tagIdsForFolder(folderKey: String): List<Long>

    /** 指定タグが付いたフォルダキーを取得する */
    @Query("SELECT folderKey FROM folder_tags WHERE tagId = :tagId")
    suspend fun folderKeysForTag(tagId: Long): List<String>

    /** タグの付与件数 */
    @Query("SELECT COUNT(*) FROM folder_tags WHERE tagId = :tagId")
    suspend fun countFolders(tagId: Long): Long
}
