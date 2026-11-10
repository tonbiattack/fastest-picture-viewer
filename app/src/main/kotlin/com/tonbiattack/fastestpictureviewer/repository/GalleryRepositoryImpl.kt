package com.tonbiattack.fastestpictureviewer.repository

import android.os.SystemClock
import android.util.Log
import com.tonbiattack.fastestpictureviewer.core.util.FolderKeys
import com.tonbiattack.fastestpictureviewer.core.util.NaturalSortKey
import com.tonbiattack.fastestpictureviewer.data.AppDatabase
import com.tonbiattack.fastestpictureviewer.data.ExcludedFolderEntity
import com.tonbiattack.fastestpictureviewer.data.FavoriteFolderEntity
import com.tonbiattack.fastestpictureviewer.data.FavoriteImageEntity
import com.tonbiattack.fastestpictureviewer.data.FolderScrollPositionEntity
import com.tonbiattack.fastestpictureviewer.data.FolderSummaryRow
import com.tonbiattack.fastestpictureviewer.data.ImageItem
import com.tonbiattack.fastestpictureviewer.data.MediaStoreScanner
import com.tonbiattack.fastestpictureviewer.data.RecentFolderEntity
import com.tonbiattack.fastestpictureviewer.data.RepresentativeImageRow
import com.tonbiattack.fastestpictureviewer.data.SyncStateEntity
import com.tonbiattack.fastestpictureviewer.data.VolumeSyncStateEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

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
    private val volumeStateDao = db.volumeSyncStateDao()
    private val favoriteDao = db.favoriteImageDao()
    private val favoriteFolderDao = db.favoriteFolderDao()
    private val excludedFolderDao = db.excludedFolderDao()
    private val folderScrollPositionDao = db.folderScrollPositionDao()
    private val recentFolderDao = db.recentFolderDao()

    /** 同期の二重実行を防ぐ。実行中の要求は待たずにスキップする */
    private val syncMutex = Mutex()

    /** 自然順キーの遅延バックフィルをフォルダ単位で一度だけ行うための記録 */
    private val backfilledFolders = ConcurrentHashMap.newKeySet<String>()

    private val _syncStatus = MutableStateFlow(SyncStatus.IDLE)
    override val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    override fun observeFolderSummaries(): Flow<List<FolderSummaryRow>> =
        imageDao.observeFolderSummaries()

    override fun observeRepresentatives(): Flow<List<RepresentativeImageRow>> =
        imageDao.observeRepresentatives()

    override fun observeImages(folderKey: String): Flow<List<ImageItem>> =
        imageDao.observeImages(folderKey).map { items ->
            if (items.none { it.naturalSortKey.isEmpty() }) {
                // バックフィル済みなら DAO のインデックス順をそのまま使う
                items
            } else {
                withContext(Dispatchers.IO) {
                    val prepared = items.map { item ->
                        if (item.naturalSortKey.isEmpty()) {
                            item.copy(naturalSortKey = NaturalSortKey.create(item.displayName))
                        } else {
                            item
                        }
                    }
                    if (backfilledFolders.add(folderKey)) {
                        try {
                            // 既存キャッシュのキーを遅延生成する。元画像・お気に入り等には影響しない
                            imageDao.upsertAll(prepared)
                        } catch (t: Exception) {
                            backfilledFolders.remove(folderKey)
                        }
                    }
                    prepared.sortedWith(NaturalSortKey.comparator)
                }
            }
        }

    override fun observeFavorites(): Flow<List<FavoriteImageEntity>> = favoriteDao.observeAll()

    override fun observeExcludedFolders(): Flow<List<ExcludedFolderEntity>> =
        excludedFolderDao.observeAll()

    override suspend fun excludeFolder(volumeName: String, relativePath: String) {
        // 画面を離れた直後でも設定変更が失われないよう、キャンセル不可で書き込む
        withContext(Dispatchers.IO + NonCancellable) {
            excludedFolderDao.add(
                ExcludedFolderEntity(
                    folderKey = FolderKeys.create(volumeName, relativePath),
                    volumeName = volumeName,
                    relativePath = FolderKeys.normalizeRelativePath(relativePath),
                    excludedAtSec = nowSeconds()
                )
            )
        }
    }

    override suspend fun includeFolder(folderKey: String) {
        // 画面を離れた直後でも解除が失われないよう、キャンセル不可で書き込む
        withContext(Dispatchers.IO + NonCancellable) {
            excludedFolderDao.remove(folderKey)
        }
    }

    override fun observeFavoriteFolders(): Flow<List<FavoriteFolderEntity>> =
        favoriteFolderDao.observeAll()

    override suspend fun setFolderFavorite(
        folderKey: String,
        volumeName: String,
        relativePath: String,
        favorite: Boolean
    ) {
        // 画面を離れた直後でも登録/解除が失われないよう、キャンセル不可で書き込む
        withContext(Dispatchers.IO + NonCancellable) {
            if (favorite) {
                favoriteFolderDao.add(
                    FavoriteFolderEntity(
                        folderKey = folderKey,
                        volumeName = volumeName,
                        relativePath = relativePath,
                        addedAtSec = nowSeconds()
                    )
                )
            } else {
                favoriteFolderDao.remove(folderKey)
            }
        }
    }

    override fun observeFavoriteImages(folderKey: String?): Flow<List<ImageItem>> =
        imageDao.observeFavoriteImages(folderKey)

    override fun observeRecentFolders(): Flow<List<RecentFolderEntity>> =
        recentFolderDao.observeAll()

    override suspend fun markFolderOpened(folderKey: String) {
        withContext(Dispatchers.IO + NonCancellable) {
            recentFolderDao.upsert(
                RecentFolderEntity(folderKey = folderKey, openedAtSec = nowSeconds())
            )
        }
    }

    override suspend fun getScrollPosition(folderKey: String): FolderScrollPositionEntity? =
        withContext(Dispatchers.IO) {
            folderScrollPositionDao.get(folderKey)
        }

    override suspend fun saveScrollPosition(
        folderKey: String,
        anchorItemId: String?,
        anchorIndex: Int,
        anchorOffsetPx: Int
    ) {
        // 画面離脱直後でも最後の位置が失われないよう、キャンセル不可で書き込む
        withContext(Dispatchers.IO + NonCancellable) {
            folderScrollPositionDao.upsert(
                FolderScrollPositionEntity(
                    folderKey = folderKey,
                    anchorItemId = anchorItemId,
                    anchorIndex = anchorIndex,
                    anchorOffsetPx = anchorOffsetPx,
                    updatedAtSec = nowSeconds()
                )
            )
        }
    }

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
            // 二重実行ガード: 実行中は追加の同期を開始しない (Mutex で排他する)
            if (!syncMutex.tryLock()) {
                SyncResult(addedCount = 0, updatedCount = 0, removedCount = 0, failedVolumes = emptyList())
            } else {
                try {
                    runSync(mode, canDelete)
                } finally {
                    syncMutex.unlock()
                }
            }
        }

    private suspend fun runSync(mode: SyncMode, canDelete: Boolean): SyncResult {
        // 初回 (DB 空) かどうかを同期開始直前に判定する
        val isEmpty = imageDao.count() == 0L
        val before = syncStateDao.get() ?: SyncStateEntity(lastSinceSec = 0L, lastFullSyncSec = 0L)
        val startSec = nowSeconds()

        // 削除反映のため、手動更新に加えて一定期間ごとに全件走査する
        val fullScanDue = (startSec - before.lastFullSyncSec) >= FULL_SCAN_INTERVAL_SEC
        val isFull = mode == SyncMode.FULL || isEmpty || fullScanDue
        val syncStartedAtMs = SystemClock.elapsedRealtime()

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
        var skippedVolumes = 0

        val volumes = scanner.volumes()
        for (volumeName in volumes) {
            try {
                val stored = volumeStateDao.get(volumeName)
                val currentGeneration = scanner.generation(volumeName)

                // 世代番号が同じなら再走査しない。変わっていれば削除/改名/移動を拾うため全件走査する
                val scanKind = VolumeSyncPolicy.decide(
                    isFullSync = isFull,
                    currentGeneration = currentGeneration,
                    storedGeneration = stored?.contentGeneration
                )
                Log.i(
                    "FPV-Perf",
                    "volume=$volumeName currentGen=$currentGeneration " +
                        "storedGen=${stored?.contentGeneration} kind=$scanKind"
                )
                if (scanKind == VolumeScanKind.SKIP) {
                    skippedVolumes++
                    continue
                }

                // 完全走査なら全部、差分なら前回値から緩衝を引いて取得
                val modifiedAfterSec = if (scanKind == VolumeScanKind.FULL) null else {
                    (before.lastSinceSec - differentialBufferSec).coerceAtLeast(0L)
                }

                if (scanKind == VolumeScanKind.FULL) {
                    // 大量画像でも OOM しないよう、既存 ID の集合だけ保持して 500 件ずつ処理する。
                    // 削除は走査が最後まで成功した場合にだけ行う。
                    val cachedIds = if (canDelete) {
                        imageDao.getIdsByVolume(volumeName).toMutableSet()
                    } else {
                        null
                    }
                    var scanAdded = 0
                    var scanUpdated = 0

                    scanner.scanBatched(volumeName, modifiedAfterSec) { batch ->
                        val existing = imageDao.getImagesByIds(batch.map { it.id })
                        val plan = PendingSyncPlanner.plan(batch, existing)
                        if (plan.upserts.isNotEmpty()) {
                            imageDao.upsertAll(plan.upserts)
                        }
                        cachedIds?.let { ids -> batch.forEach { ids.remove(it.id) } }

                        val existingIds = existing.mapTo(hashSetOf()) { it.id }
                        val newlyAdded = plan.upserts.count { it.id !in existingIds }
                        scanAdded += newlyAdded
                        scanUpdated += plan.upserts.size - newlyAdded
                    }

                    added += scanAdded
                    updated += scanUpdated

                    if (canDelete && cachedIds != null && cachedIds.isNotEmpty()) {
                        deleteInChunks(cachedIds.toList())
                        removed += cachedIds.size
                    }
                } else {
                    // 差分走査: 新規/更新のみを upsert する (削除反映は完全走査が担う)
                    var scanCount = 0
                    scanner.scanBatched(volumeName, modifiedAfterSec) { batch ->
                        imageDao.upsertAll(batch)
                        scanCount += batch.size
                    }
                    added += scanCount
                }

                // 成功したボリュームだけ世代番号を更新する。失敗時は前回値のまま残し再走査させる
                volumeStateDao.upsert(
                    VolumeSyncStateEntity(
                        volumeName = volumeName,
                        contentGeneration = currentGeneration,
                        lastScanSec = startSec
                    )
                )
            } catch (t: Exception) {
                // SD カード未挿入・権限の局所性などで 1 ボリュームが失敗しても他を継続する
                // 失敗したボリュームの行は触らないため、既存のキャッシュは保護される
                failedVolumes += volumeName
                anyFailure = true
            }
        }

        // 見えなくなったボリュームの同期状態だけを片付ける (画像キャッシュは削除しない)
        if (isFull && !anyFailure) {
            volumeStateDao.getAll()
                .filter { it.volumeName !in volumes }
                .forEach { volumeStateDao.remove(it.volumeName) }
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

        Log.i(
            "FPV-Perf",
            "sync done: ${SystemClock.elapsedRealtime() - syncStartedAtMs}ms, " +
                "full=$isFull added=$added updated=$updated removed=$removed " +
                "skipped=$skippedVolumes failed=${failedVolumes.size}"
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

        /** 削除を反映するための定期全件走査の間隔 (24 時間) */
        private const val FULL_SCAN_INTERVAL_SEC = 24L * 60L * 60L
    }
}
