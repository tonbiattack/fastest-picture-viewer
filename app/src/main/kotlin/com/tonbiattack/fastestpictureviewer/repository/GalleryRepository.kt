package com.tonbiattack.fastestpictureviewer.repository

import com.tonbiattack.fastestpictureviewer.core.util.FolderSortMode
import com.tonbiattack.fastestpictureviewer.data.ExcludedFolderEntity
import com.tonbiattack.fastestpictureviewer.data.FavoriteFolderEntity
import com.tonbiattack.fastestpictureviewer.data.FavoriteImageEntity
import com.tonbiattack.fastestpictureviewer.data.FolderListScrollPositionEntity
import com.tonbiattack.fastestpictureviewer.data.FolderScrollPositionEntity
import com.tonbiattack.fastestpictureviewer.data.FolderSummaryRow
import com.tonbiattack.fastestpictureviewer.data.FolderTagNameRow
import com.tonbiattack.fastestpictureviewer.data.ImageItem
import com.tonbiattack.fastestpictureviewer.data.RecentFolderEntity
import com.tonbiattack.fastestpictureviewer.data.RepresentativeImageRow
import com.tonbiattack.fastestpictureviewer.data.TagEntity
import com.tonbiattack.fastestpictureviewer.data.TagWithCount
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * ギャラリーデータへのアクセス。UI はこの interface にのみ依存してテストしやすくする。
 *
 * 一覧はすべて Room (Flow) を単一の真実のソースとし、同期は背景で行う (stale-while-revalidate)。
 */
interface GalleryRepository {

    /** フォルダ一覧 (集計行) を購読する */
    fun observeFolderSummaries(): Flow<List<FolderSummaryRow>>

    /** フォルダ代表サムネイルを購読する */
    fun observeRepresentatives(): Flow<List<RepresentativeImageRow>>

    /** 指定フォルダの画像一覧を購読する */
    fun observeImages(folderKey: String): Flow<List<ImageItem>>

    /** 同期実行状態を購読する */
    val syncStatus: StateFlow<SyncStatus>

    /**
     * MediaStore → Room の同期を実行する。メインスレッドから呼んでもよい (内部で IO に切る)。
     *
     * @param canDelete 完全アクセスで削除判定付き同期が可能か (部分アクセスでは false を渡す)
     */
    suspend fun sync(mode: SyncMode, canDelete: Boolean): SyncResult

    /** お気に入りを登録/解除する (ファイル本体は変更しない) */
    suspend fun setFavorite(itemId: String, favorite: Boolean)

    /** お気に入り一覧を購読する */
    fun observeFavorites(): Flow<List<FavoriteImageEntity>>

    /** 除外フォルダ一覧を購読する */
    fun observeExcludedFolders(): Flow<List<ExcludedFolderEntity>>

    /**
     * フォルダを一覧から除外する。元画像ファイルには変更を加えない。
     * 親フォルダを除外すると配下のサブフォルダも表示対象から外れる。
     */
    suspend fun excludeFolder(volumeName: String, relativePath: String)

    /** 除外を解除する */
    suspend fun includeFolder(folderKey: String)

    /** フォルダお気に入り一覧を購読する */
    fun observeFavoriteFolders(): Flow<List<FavoriteFolderEntity>>

    /** フォルダのお気に入りを登録/解除する (元画像は変更しない) */
    suspend fun setFolderFavorite(
        folderKey: String,
        volumeName: String,
        relativePath: String,
        favorite: Boolean
    )

    /**
     * 画像のお気に入りを、フォルダの最終更新日時降順 → フォルダキー昇順 →
     * フォルダ内の自然順昇順で返す。[folderKey] が null なら全フォルダ。
     */
    fun observeFavoriteImages(folderKey: String?): Flow<List<ImageItem>>

    /** 最近閲覧したフォルダを購読する */
    fun observeRecentFolders(): Flow<List<RecentFolderEntity>>

    /** フォルダを開いた時刻を記録する */
    suspend fun markFolderOpened(folderKey: String)

    /** フォルダ別のスクロール位置を取得する */
    suspend fun getScrollPosition(folderKey: String): FolderScrollPositionEntity?

    /** フォルダ別のスクロール位置を保存する */
    suspend fun saveScrollPosition(
        folderKey: String,
        anchorItemId: String?,
        anchorIndex: Int,
        anchorOffsetPx: Int
    )

    /** フォルダ一覧の並び順ごとのスクロール位置を取得する */
    suspend fun getFolderListScrollPosition(
        sortMode: FolderSortMode
    ): FolderListScrollPositionEntity?

    /** フォルダ一覧の並び順ごとのスクロール位置を保存する */
    suspend fun saveFolderListScrollPosition(
        sortMode: FolderSortMode,
        anchorFolderKey: String?,
        anchorIndex: Int,
        anchorOffsetPx: Int
    )

    /** タグ一覧を購読する (Issue #46) */
    fun observeTags(): Flow<List<TagEntity>>

    /** 付与件数付きのタグ一覧を購読する */
    fun observeTagsWithCount(): Flow<List<TagWithCount>>

    /** フォルダ別のタグ名を購読する (バッジ表示用) */
    fun observeFolderTagNames(): Flow<List<FolderTagNameRow>>

    /**
     * タグ名からタグを取得する。存在しなければ新規作成する。
     * 正規化名が同じタグがあればそれを返すため、重複タグは作られない。
     * 空文字は null を返す。
     */
    suspend fun findOrCreateTag(rawName: String): TagEntity?

    /** フォルダのタグを指定内容に置き換える (複数タグの付与・解除) */
    suspend fun setFolderTags(folderKey: String, tagIds: Set<Long>)

    /** 複数フォルダへタグをまとめて付与する。既存の他タグは消さない */
    suspend fun addTagsToFolders(tagIds: Set<Long>, folderKeys: Collection<String>)

    /**
     * タグ名を変更する。正規化名が他のタグと衝突する場合は変更せず false を返す。
     * 空文字や長すぎる名前は正規化して扱う。
     */
    suspend fun renameTag(tagId: Long, rawName: String): Boolean

    /** タグとフォルダとの紐付けをまとめて削除する */
    suspend fun deleteTag(tagId: Long)

    /**
     * 自然順キーが未設定の旧DB行を埋める (Issue #49)。
     * 代表サムネイルを自然順先頭で決めるために、起動後に一度呼ぶ。失敗しても動作は継続する。
     */
    suspend fun backfillMissingNaturalSortKeys()
}
