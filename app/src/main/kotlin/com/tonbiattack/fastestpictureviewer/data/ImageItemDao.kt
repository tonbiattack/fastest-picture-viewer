package com.tonbiattack.fastestpictureviewer.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * 画像メタ情報の DAO。
 *
 * 並び順は仕様書に基づく:
 * - フォルダ一覧: 最新更新日時の降順。同日時はフォルダキー昇順で安定させる
 * - フォルダ内画像: ファイル名昇順。同名は mediaId 昇順で安定させる
 */
@Dao
interface ImageItemDao {

    /** フォルダ一覧 (集計済み) を購読する */
    @Query(
        """
        SELECT folderKey,
               MAX(volumeName) AS volumeName,
               MAX(relativePath) AS relativePath,
               COUNT(*) AS imageCount,
               MAX(dateModifiedSec) AS latestModifiedSec
        FROM images
        WHERE NOT EXISTS (
            SELECT 1 FROM excluded_folders AS excluded
            WHERE excluded.volumeName = images.volumeName
              AND (
                  (excluded.relativePath = '' AND images.relativePath = '')
                  OR (excluded.relativePath <> ''
                      AND substr(images.relativePath, 1, length(excluded.relativePath)) = excluded.relativePath)
              )
        )
        GROUP BY folderKey
        ORDER BY latestModifiedSec DESC, folderKey ASC
        """
    )
    fun observeFolderSummaries(): Flow<List<FolderSummaryRow>>

    /**
     * 各フォルダの代表サムネイル (ファイル名の自然順で先頭の画像) を購読する (Issue #49)。
     *
     * - 並び順は画像一覧と同じ「naturalSortKey → sortName → mediaId」の昇順で、
     *   表示中のソート設定や更新日時、走査順に依存しない
     * - 旧DBで naturalSortKey が未設定 ('') の行があっても、空キーを後回しにするので
     *   一覧の遅延バックフィル前でも同じ判定になる
     * - SQLite の「MIN/MAX が 1 つだけのとき、裸の列はその最小/最大行の値になる」仕様を使い、
     *   自己結合なしの 1 スキャンで済ませる。合成キーは mediaId まで含むため必ず 1 行に定まる
     */
    @Query(
        """
        SELECT id AS id,
               folderKey AS folderKey,
               dateModifiedSec AS dateModifiedSec,
               size AS size,
               MIN(
                   (CASE WHEN naturalSortKey = '' THEN '1' ELSE '0' END) ||
                   naturalSortKey || char(1) ||
                   sortName || char(1) ||
                   printf('%020d', mediaId)
               ) AS minOrderKey
        FROM images INDEXED BY index_images_folderKey_naturalSortKey_sortName_mediaId
        WHERE NOT EXISTS (
            SELECT 1 FROM excluded_folders AS excluded
            WHERE excluded.volumeName = images.volumeName
              AND (
                  (excluded.relativePath = '' AND images.relativePath = '')
                  OR (excluded.relativePath <> ''
                      AND substr(images.relativePath, 1, length(excluded.relativePath)) = excluded.relativePath)
              )
        )
        GROUP BY folderKey
        """
    )
    fun observeRepresentatives(): Flow<List<RepresentativeImageRow>>

    /** 指定フォルダの画像一覧を購読する (sortName 昇順、同一キーは mediaId 昇順) */
    @Query(
        """
        SELECT * FROM images
        WHERE folderKey = :folderKey
          AND NOT EXISTS (
              SELECT 1 FROM excluded_folders AS excluded
              WHERE excluded.volumeName = images.volumeName
                AND (
                    (excluded.relativePath = '' AND images.relativePath = '')
                    OR (excluded.relativePath <> ''
                        AND substr(images.relativePath, 1, length(excluded.relativePath)) = excluded.relativePath)
                )
          )
        ORDER BY naturalSortKey ASC, sortName ASC, mediaId ASC
        """
    )
    fun observeImages(folderKey: String): Flow<List<ImageItem>>

    /** 指定フォルダの画像一覧を一括取得する (差分計算用) */
    @Query("SELECT * FROM images WHERE folderKey = :folderKey")
    suspend fun getImages(folderKey: String): List<ImageItem>

    /** 指定ボリュームの画像を全件取得する (削除判定用) */
    @Query("SELECT * FROM images WHERE volumeName = :volumeName")
    suspend fun getImagesByVolume(volumeName: String): List<ImageItem>

    /** 指定ボリュームの画像 ID だけを取得する (大量画像でもメモリを抑えた削除判定用) */
    @Query("SELECT id FROM images WHERE volumeName = :volumeName")
    suspend fun getIdsByVolume(volumeName: String): List<String>

    /** バッチ単位の変更検出用。500 件程度の ID を渡す */
    @Query("SELECT * FROM images WHERE id IN (:ids)")
    suspend fun getImagesByIds(ids: List<String>): List<ImageItem>

    /**
     * 画像のお気に入りを、フォルダの最終更新日時降順 → フォルダキー昇順 →
     * フォルダ内の自然順昇順で返す。
     *
     * @param folderKey null なら全フォルダ、指定時はそのフォルダのみ
     */
    @Query(
        """
        SELECT i.* FROM images AS i
        INNER JOIN favorite_images AS fav ON fav.itemId = i.id
        INNER JOIN (
            SELECT folderKey, MAX(dateModifiedSec) AS maxModified
            FROM images
            GROUP BY folderKey
        ) AS latestFolder ON latestFolder.folderKey = i.folderKey
        WHERE (:folderKey IS NULL OR i.folderKey = :folderKey)
          AND NOT EXISTS (
              SELECT 1 FROM excluded_folders AS excluded
              WHERE excluded.volumeName = i.volumeName
                AND (
                    (excluded.relativePath = '' AND i.relativePath = '')
                    OR (excluded.relativePath <> ''
                        AND substr(i.relativePath, 1, length(excluded.relativePath)) = excluded.relativePath)
                )
          )
        ORDER BY latestFolder.maxModified DESC, i.folderKey ASC,
                 i.naturalSortKey ASC, i.sortName ASC, i.mediaId ASC
        """
    )
    fun observeFavoriteImages(folderKey: String?): Flow<List<ImageItem>>

    /** 保存済み画像の総数 */
    @Query("SELECT COUNT(*) FROM images")
    suspend fun count(): Long

    /** 自然順キーが未設定の行数 (旧DBからの移行直後の検出用) */
    @Query("SELECT COUNT(*) FROM images WHERE naturalSortKey = ''")
    suspend fun countMissingNaturalSortKey(): Long

    /**
     * 自然順キーが未設定の行を先頭から取得する。
     * 旧DBの行をバックフィルして、フォルダ代表サムネイルを正しい先頭画像に揃える (Issue #49)。
     */
    @Query(
        """
        SELECT id AS id, displayName AS displayName
        FROM images
        WHERE naturalSortKey = ''
        LIMIT :limit
        """
    )
    suspend fun getMissingNaturalSortKey(limit: Int): List<MissingNaturalSortKeyRow>

    /** 自然順キーだけを更新する (他の列・お気に入り・除外設定には触れない) */
    @Query("UPDATE images SET naturalSortKey = :naturalSortKey WHERE id = :id")
    suspend fun updateNaturalSortKey(id: String, naturalSortKey: String)

    /** 複数行の自然順キー更新を 1 トランザクションにまとめる */
    @Transaction
    suspend fun updateNaturalSortKeys(updates: List<NaturalSortKeyUpdate>) {
        updates.forEach { updateNaturalSortKey(it.id, it.naturalSortKey) }
    }

    /** 差分・初期走査の結果を一括登録する (トランザクション内で呼ぶこと) */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<ImageItem>)

    /** 走査結果に存在しない画像を ID 指定で削除する */
    @Query("DELETE FROM images WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)
}
