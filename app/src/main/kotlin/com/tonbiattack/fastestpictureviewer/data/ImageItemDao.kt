package com.tonbiattack.fastestpictureviewer.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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
        GROUP BY folderKey
        ORDER BY latestModifiedSec DESC, folderKey ASC
        """
    )
    fun observeFolderSummaries(): Flow<List<FolderSummaryRow>>

    /** 各フォルダの代表サムネイル (フォルダ内で最新の画像 1 枚) を購読する */
    /**
     * 各フォルダの代表サムネイル (フォルダ内で最新の画像) を購読する。
     * 同じ更新日時の画像が複数ある場合は複数行返り得るが、Repository 側で 1 件に丸める。
     */
    @Query(
        """
        SELECT i.id AS id, i.folderKey AS folderKey
        FROM images AS i
        INNER JOIN (
            SELECT folderKey, MAX(dateModifiedSec) AS maxModified
            FROM images
            GROUP BY folderKey
        ) AS latest
          ON i.folderKey = latest.folderKey
         AND i.dateModifiedSec = latest.maxModified
        """
    )
    fun observeRepresentatives(): Flow<List<RepresentativeImageRow>>

    /** 指定フォルダの画像一覧を購読する (sortName 昇順、同一キーは mediaId 昇順) */
    @Query(
        """
        SELECT * FROM images
        WHERE folderKey = :folderKey
        ORDER BY sortName ASC, mediaId ASC
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

    /** 保存済み画像の総数 */
    @Query("SELECT COUNT(*) FROM images")
    suspend fun count(): Long

    /** 差分・初期走査の結果を一括登録する (トランザクション内で呼ぶこと) */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<ImageItem>)

    /** 走査結果に存在しない画像を ID 指定で削除する */
    @Query("DELETE FROM images WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)
}
