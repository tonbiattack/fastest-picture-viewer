package com.tonbiattack.fastestpictureviewer.repository

import com.tonbiattack.fastestpictureviewer.data.AppDatabase
import com.tonbiattack.fastestpictureviewer.data.FavoriteImageEntity
import com.tonbiattack.fastestpictureviewer.data.FolderSummaryRow
import com.tonbiattack.fastestpictureviewer.data.ImageItem
import com.tonbiattack.fastestpictureviewer.data.MediaStoreScanner
import com.tonbiattack.fastestpictureviewer.data.RepresentativeImageRow
import com.tonbiattack.fastestpictureviewer.data.SyncStateEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import androidx.room.withTransaction

/**
 * [GalleryRepository] の実装。
 *
 * 設計方針 (仕様書 §5/§6):
 * - Room を単一の真実のソースとし、表示は常に Room の Flow 経由。同期開始で一覧を空にしない
 * - 削除判定は「正常に完了したボリュームの走査結果」にのみ基づく。部分的な失敗では削除しない
 * - DB 更新は 1 トランザクションにまとめ、UNIQUE は upsert で回避する
 */
class GalleryRepositoryImpl(
    private val db: AppDatabase,
    private val scanner: MediaStoreScanner,
    /** 差分の再走査を優しくするための緩衝 (秒)。端末のタイマ精度のずれを吸収する */
    private val differentialBufferSec: Long = 60L,
    /** 現在時刻取得 (テスト差し替え用) */
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 }
) : GalleryRepository {

    private val imageDao = db.imageItemDao()
    private val syncStateDao = db.syncStateDao()
    private val favoriteDao = db.favoriteImageDao()

    private val _syncStatus = MutableStateFlow(SyncStatus.IDLE)
    override val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    override fun observeFolderSummaries(): Flow<List<FolderSummaryRow>> =
        imageDao.observeFolderSummaries()

    override fun observeRepresentatives(): Flow<List<RepresentativeImageRow>> =
        imageDao.observeRepresentatives()

    override fun observeImages(folderKey: String): Flow<List<ImageItem>> =
        imageDao.observeImages(folderKey)

    override fun observeFavorites(): Flow<List<FavoriteImageEntity>> = favoriteDao.observeAll()

    override suspend fun setFavorite(itemId: String, favorite: Boolean) {
        withContext(Dispatchers.IO) {
            if (favorite) {
                favoriteDao.add(FavoriteImageEntity(itemId = itemId, addedAtSec = nowSeconds()))
            } else {
                favoriteDao.remove(itemId)
            }
        }
    }

    override suspend fun sync(mode: SyncMode, canDelete: Boolean): SyncResult =
        withContext(Dispatchers.IO) {
            // 二重実行ガード: 実行中は追加の同期を開始しない
            if (_syncStatus.value.isRunning) {
                SyncResult(addedCount = 0, updatedCount = 0, removedCount = 0, failedVolumes = emptyList())
            } else {
                runSync(mode, canDelete)
            }
        }

    private suspend fun runSync(mode: SyncMode, canDelete: Boolean): SyncResult {
        // 初回 (DB 空) かどうかを同期開始直前に判定する
        val isEmpty = imageDao.count() == 0L
        val isFull = mode == SyncMode.FULL || isEmpty

        val before = syncStateDao.get() ?: SyncStateEntity(lastSinceSec = 0L, lastFullSyncSec = 0L)
        val startSec = nowSeconds()

        _syncStatus.value = SyncStatus(
            isRunning = true,
            isInitialScan = isEmpty,
            errorMessage = null
        )

        var added = 0
        var updated = 0
        var removed = 0
        val failedVolumes = mutableListOf<String>()
        var anyFailure = false

        for (volumeName in scanner.volumes()) {
            try {
                val scanned: List<ImageItem> = scanner.scan(
                    volumeName = volumeName,
                    // 完全走査なら全部、差分なら前回値から緩衝を引いて取得
                    modifiedAfterSec = if (isFull) null else {
                        (before.lastSinceSec - differentialBufferSec).coerceAtLeast(0L)
                    }
                )

                if (isFull) {
                    // 完全走査: このボリュームの既存行と突き合わせて差分反映を行う
                    val cached = imageDao.getImagesByVolume(volumeName)
                    val plan = PendingSyncPlanner.plan(scanned, cached)
                    db.withTransaction {
                        imageDao.upsertAll(plan.upserts)
                        if (canDelete && plan.deletions.isNotEmpty()) {
                            deleteInChunks(plan.deletions.map { it.id })
                        }
                    }
                    val cachedIds = cached.mapTo(hashSetOf()) { it.id }
                    val newlyAdded = plan.upserts.count { it.id !in cachedIds }
                    added += newlyAdded
                    updated += plan.upserts.size - newlyAdded
                    removed += if (canDelete) plan.deletions.size else 0
                } else {
                    // 差分走査: 新規/更新のみを upsert する (削除反映は完全走査が担う)
                    imageDao.upsertAll(scanned)
                    added += scanned.size
                }
            } catch (t: Exception) {
                // SD カード未挿入・権限の局所性などで 1 ボリュームが失敗しても他を継続する
                // 失敗したボリュームの行は触らないため、既存のキャッシュは保護される
                failedVolumes += volumeName
                anyFailure = true
            }
        }

        // 走査を 1 つでも失敗したボリュームがあれば時刻を進めない (次回の再走査対象とする)
        if (!anyFailure) {
            val newLastSince = (startSec - differentialBufferSec).coerceAtLeast(0L)
            syncStateDao.upsert(
                SyncStateEntity(
                    id = 1,
                    lastSinceSec = newLastSince,
                    lastFullSyncSec = if (isFull) startSec else before.lastFullSyncSec
                )
            )
        }

        val message = when {
            failedVolumes.isNotEmpty() ->
                "ストレージ ${failedVolumes.size} 件の更新を確認できませんでした（キャッシュは維持されています）"
            else -> null
        }

        val result = SyncResult(
            addedCount = added,
            updatedCount = updated,
            removedCount = removed,
            failedVolumes = failedVolumes
        )

        _syncStatus.value = SyncStatus(
            isRunning = false,
            isInitialScan = false,
            failedVolumeCount = failedVolumes.size,
            errorMessage = message,
            addedCount = added,
            removedCount = removed
        )
        return result
    }

    /** SQLite 変数上限を避けるため ID を分割して削除する */
    private suspend fun deleteInChunks(ids: List<String>) {
        ids.chunked(DELETE_CHUNK_SIZE).forEach { chunk ->
            imageDao.deleteByIds(chunk)
        }
    }

    companion object {
        private const val DELETE_CHUNK_SIZE = 500
    }
}
