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

    /**
     * 指定フォルダ内の画像を開いて確認し、開けなかったものを記録する (Issue #77)。
     *
     * 代表サムネイルの読み込みに失敗したフォルダに対して呼ぶ。1 フォルダあたり
     * [limit] 件までに制限し、大量フォルダでも背景処理が長時間走らないようにする。
     *
     * @return 新たに「開けなかった」と判定した件数
     */
    suspend fun verifyFolderReadability(folderKey: String, limit: Int = DEFAULT_VERIFY_LIMIT): Int

    /**
     * 「開けなかった」記録をすべて消して再判定できるようにする (Issue #77)。
     * 手動更新時に呼ぶ。ファイルが復活していれば一覧へ戻る。
     * 次回起動時の全件検証もやり直せるよう、実施時刻もリセットする。
     */
    suspend fun clearUnreadableImages()

    /**
     * すべてのフォルダの代表画像を開いて確認し、開けないフォルダを非表示にする (Issue #77)。
     *
     * MediaStore に削除済みファイルの行が残る端末では、走査を何度繰り返しても
     * 壊れたフォルダが消えない。フォルダごとに代表 1 件だけ確認するため、
     * 全画像を開かずに済む。[lastValidationSec] から一定期間内は再実行しない。
     *
     * @return 新たに非表示にしたフォルダ数
     */
    suspend fun validateAllFolders(): Int

    /**
     * 実体を開けなかったフォルダの一覧を返す (Issue #80)。
     * ユーザーへの提示と、整理の候補に使う。
     */
    suspend fun getUnreadableFolderSummary(): List<UnreadableFolderSummary>

    /**
     * 実体を開けなかった行を DB から整理する (Issue #80)。
     *
     * **安全条件を満たす場合のみ実行する。**
     * - 対象のボリュームがマウントされていること
     * - 対象がライブラリ全体の半数以下であること
     * 条件を満たさない場合は何もせず、理由を返す。
     *
     * 元の画像ファイルには一切触れない (DB の索引を整理するだけ)。
     *
     * @param dryRun true なら削除せず、対象の見積りだけ返す
     */
    suspend fun cleanupStaleEntries(dryRun: Boolean = false): StaleCleanupResult

    /**
     * 画像 1 件の実体を確認し、開けなければ表示から除外する (Issue #77)。
     *
     * グリッドのサムネイル読み込み失敗を起点に呼ぶ。フォルダ全体ではなく
     * 失敗した画像だけを対象にするため、読める画像を巻き込まない。
     * その結果フォルダ内の全画像が除外されれば、フォルダ一覧からも自然に消える。
     *
     * @return 除外したら true (読める、または既に除外済みなら false)
     */
    suspend fun verifyImageReadable(item: ImageItem): Boolean

    /**
     * フォルダの代表サムネイル読み込みに失敗したときに呼ぶ (Issue #77)。
     *
     * 代表が開けない場合は他の画像も数件確認し、フォルダごと消えたのか
     * 一部だけ欠けたのかを切り分ける。読めた場合は何もしない。
     *
     * @return 非表示にした画像数 (0 なら表示を維持)
     */
    suspend fun verifyFolderFromThumbnailFailure(folderKey: String): Int

    companion object {
        /** [verifyFolderReadability] の既定上限件数 */
        const val DEFAULT_VERIFY_LIMIT = 400
    }
}

/** 実体を開けなかったフォルダ 1 件分 (Issue #80) */
data class UnreadableFolderSummary(
    val folderKey: String,
    val volumeName: String,
    /** 開けなかった画像の枚数 */
    val imageCount: Long,
    /** 開けなかった画像の合計サイズ (バイト) */
    val totalBytes: Long
)

/** 実体の無い行の整理結果 (Issue #80) */
data class StaleCleanupResult(
    /** 実際に整理 (または dryRun なら整理予定) したフォルダ数 */
    val folderCount: Int,
    /** 対象の画像枚数 */
    val imageCount: Long,
    /** 対象の合計サイズ (バイト) */
    val totalBytes: Long,
    /** 実行しなかった理由。null なら実行した (または対象なし) */
    val rejectedReason: String? = null
) {
    /** 何も変更していないか */
    val isNoOp: Boolean get() = folderCount == 0
}
