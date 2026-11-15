package com.tonbiattack.fastestpictureviewer.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.tonbiattack.fastestpictureviewer.perf.StartupTrace
import kotlinx.coroutines.flow.Flow

/**
 * フォルダ一覧キャッシュの DAO (Issue #106)。
 *
 * 起動時に 35 万行を GROUP BY で集計すると実機で約 3.7 秒かかる。
 * 集計結果を [folder_summaries] / [folder_representatives] に持ち、
 * 起動時はそれを読むだけにする。読み出しはフォルダ数分の単純な SELECT。
 */
@Dao
interface FolderSummaryCacheDao {

    /** キャッシュ済みのフォルダ集計を購読する。並び順は従来と同じ */
    @Query(
        """
        SELECT folderKey, volumeName, relativePath, imageCount, latestModifiedSec
        FROM folder_summaries
        ORDER BY latestModifiedSec DESC, folderKey ASC
        """
    )
    fun observeSummaries(): Flow<List<FolderSummaryEntity>>

    /** キャッシュ済みの代表サムネイルを購読する */
    @Query("SELECT folderKey, id, dateModifiedSec, size FROM folder_representatives")
    fun observeRepresentatives(): Flow<List<FolderRepresentativeEntity>>

    /** キャッシュの状態を購読する。未作成なら空 */
    @Query("SELECT * FROM folder_summary_state WHERE id = 1")
    fun observeState(): Flow<FolderSummaryStateEntity?>

    /** キャッシュの状態を取得する */
    @Query("SELECT * FROM folder_summary_state WHERE id = 1")
    suspend fun getState(): FolderSummaryStateEntity?

    /** 集計キャッシュの現在の行数 (フォルダ数) */
    @Query("SELECT COUNT(*) FROM folder_summaries")
    suspend fun countSummaries(): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSummaries(rows: List<FolderSummaryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRepresentatives(rows: List<FolderRepresentativeEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertState(state: FolderSummaryStateEntity)

    @Query("DELETE FROM folder_summaries")
    suspend fun clearSummaries()

    @Query("DELETE FROM folder_representatives")
    suspend fun clearRepresentatives()

    /**
     * 集計キャッシュを作り直す。
     *
     * 削除と挿入を 1 トランザクションにまとめ、途中で落ちても
     * 「一部だけ新しい」状態を残さない。
     */
    @Transaction
    suspend fun replaceAll(
        summaries: List<FolderSummaryEntity>,
        representatives: List<FolderRepresentativeEntity>,
        imageCount: Long,
        rebuiltAtMs: Long
    ) {
        StartupTrace.mark("summary_cache_rebuild_start")
        clearSummaries()
        clearRepresentatives()
        // SQLite のバインド変数上限 (999) を避けるため分割して挿入する
        summaries.chunked(INSERT_CHUNK).forEach { insertSummaries(it) }
        representatives.chunked(INSERT_CHUNK).forEach { insertRepresentatives(it) }
        upsertState(
            FolderSummaryStateEntity(
                id = 1,
                rebuiltAtMs = rebuiltAtMs,
                imageCount = imageCount
            )
        )
        StartupTrace.mark("summary_cache_rebuild_end")
    }

    companion object {
        /** 1 回の INSERT に含める行数。バインド変数上限より十分小さくする */
        const val INSERT_CHUNK = 500
    }
}
