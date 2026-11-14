package com.tonbiattack.fastestpictureviewer.repository

import android.os.SystemClock
import android.util.Log
import com.tonbiattack.fastestpictureviewer.core.util.FolderKeys
import com.tonbiattack.fastestpictureviewer.core.util.FolderSortMode
import com.tonbiattack.fastestpictureviewer.core.util.NaturalSortKey
import com.tonbiattack.fastestpictureviewer.core.util.TagNames
import com.tonbiattack.fastestpictureviewer.data.AppDatabase
import com.tonbiattack.fastestpictureviewer.data.ExcludedFolderEntity
import com.tonbiattack.fastestpictureviewer.data.FavoriteFolderEntity
import com.tonbiattack.fastestpictureviewer.data.FavoriteImageEntity
import com.tonbiattack.fastestpictureviewer.data.FolderListScrollPositionEntity
import com.tonbiattack.fastestpictureviewer.data.FolderScrollPositionEntity
import com.tonbiattack.fastestpictureviewer.data.FolderSummaryRow
import com.tonbiattack.fastestpictureviewer.data.FolderTagEntity
import com.tonbiattack.fastestpictureviewer.data.FolderTagNameRow
import com.tonbiattack.fastestpictureviewer.data.ImageItem
import com.tonbiattack.fastestpictureviewer.data.MediaStoreScanner
import com.tonbiattack.fastestpictureviewer.data.NaturalSortKeyUpdate
import com.tonbiattack.fastestpictureviewer.data.RecentFolderEntity
import com.tonbiattack.fastestpictureviewer.data.ImageReadableProbe
import com.tonbiattack.fastestpictureviewer.data.RepresentativeImageRow
import com.tonbiattack.fastestpictureviewer.data.SyncStateEntity
import com.tonbiattack.fastestpictureviewer.data.TagEntity
import com.tonbiattack.fastestpictureviewer.data.TagWithCount
import com.tonbiattack.fastestpictureviewer.data.UnreadableImageEntity
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
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    /**
     * 画像の実体を開けるかの判定 (Issue #77)。null なら検証しない (テストの後方互換)。
     *
     * OPPO/ColorOS では削除済みファイルの MediaStore 行が残り、走査で毎回返る。
     * 開けない代表画像が見つかったらフォルダ単位で非表示にする。
     */
    private val readableProbe: ImageReadableProbe? = null
) : GalleryRepository {

    private val imageDao = db.imageItemDao()
    private val syncStateDao = db.syncStateDao()
    private val volumeStateDao = db.volumeSyncStateDao()
    private val favoriteDao = db.favoriteImageDao()
    private val favoriteFolderDao = db.favoriteFolderDao()
    private val excludedFolderDao = db.excludedFolderDao()
    private val folderScrollPositionDao = db.folderScrollPositionDao()
    private val folderListScrollPositionDao = db.folderListScrollPositionDao()
    private val recentFolderDao = db.recentFolderDao()
    private val tagDao = db.tagDao()

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

    /**
     * 自然順キーが未設定の旧DB行をバックグラウンドで埋める (Issue #49)。
     *
     * フォルダ一覧の代表サムネイルは自然順キーで先頭画像を決めるため、
     * 移行直後に空キーが残っているとフォルダを開くまでサムネイルがずれる。
     * 500 件ずつ・起動をブロックしない形で一度だけ埋める。
     */
    override suspend fun backfillMissingNaturalSortKeys() = withContext(Dispatchers.IO) {
        try {
            if (imageDao.countMissingNaturalSortKey() == 0L) return@withContext
            while (true) {
                val rows = imageDao.getMissingNaturalSortKey(BACKFILL_BATCH_SIZE)
                if (rows.isEmpty()) break
                val updates = rows.map { row ->
                    NaturalSortKeyUpdate(
                        id = row.id,
                        naturalSortKey = NaturalSortKey.create(row.displayName)
                    )
                }
                imageDao.updateNaturalSortKeys(updates)
                // 進捗が無い (同一行を繰り返し取得する) 場合は無限ループを避けて抜ける
                if (updates.isEmpty()) break
            }
        } catch (t: Exception) {
            // バックフィル失敗はサムネイルの一時的なずれに留める。アプリの動作は継続する
            Log.w("FPV-Perf", "naturalSortKey backfill failed", t)
        }
    }

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

    /**
     * フォルダ内の画像を実際に開いて確認する (Issue #77)。
     *
     * OPPO/ColorOS では削除済みファイルの行が MediaStore に残り、走査結果からは
     * 消えない。そこで URI を開けるか確認し、開けなかったものを記録して一覧から外す。
     * 全件ではなく [limit] 件までに制限し、スクロールを妨げない。
     */
    /**
     * フォルダ内の画像を実際に開いて確認する (Issue #77)。
     *
     * OPPO/ColorOS では削除済みファイルの行が MediaStore に残り、走査結果からは
     * 消えない。そこで URI を開けるか確認し、開けなかったものを記録して一覧から外す。
     *
     * 「フォルダごと消えた」と「一部の画像だけ欠けた」を区別するため、代表が
     * 開けない場合は他の画像も数件確認する ([FolderReadabilityAssessor])。
     * 代表 1 件だけで判断すると、生きているフォルダを丸ごと隠してしまう。
     */
    override suspend fun verifyFolderReadability(folderKey: String, limit: Int): Int {
        val probe = readableProbe ?: return 0
        if (limit <= 0) return 0
        return withContext(Dispatchers.IO) {
            val items = imageDao.getImages(folderKey)
            if (items.isEmpty()) return@withContext 0

            val now = nowSeconds()
            when (val result = FolderReadabilityAssessor(probe).assess(items)) {
                FolderReadability.Healthy -> {
                    // 読めるようになっていれば、以前の記録を取り消して一覧へ戻す (自己修復)
                    imageDao.clearUnreadableByFolder(folderKey)
                    0
                }

                is FolderReadability.PartiallyUnreadable -> {
                    // 以前にフォルダ全体を非表示にしていた場合、その記録を先に消す。
                    // 残したままだと読める画像まで非表示のままになる (実機で発生)
                    imageDao.clearUnreadableByFolder(folderKey)
                    imageDao.upsertUnreadable(
                        result.items.map { item ->
                            UnreadableImageEntity(
                                itemId = item.id,
                                folderKey = item.folderKey,
                                volumeName = item.volumeName,
                                detectedAtSec = now
                            )
                        }
                    )
                    result.items.size
                }

                is FolderReadability.FolderGone -> {
                    imageDao.markFolderUnreadable(folderKey, now)
                    result.items.size
                }
            }
        }
    }

    override suspend fun clearUnreadableImages() {
        withContext(Dispatchers.IO + NonCancellable) {
            imageDao.clearUnreadableImages()
            // 次回起動時の全件検証をやり直せるようにする (Issue #77)
            resetValidationClock()
        }
    }

    /** 実体検証の実施時刻を 0 に戻し、次回の起動時に再検証させる */
    private suspend fun resetValidationClock() {
        val state = syncStateDao.get() ?: SyncStateEntity(
            lastSinceSec = 0L,
            lastFullSyncSec = 0L
        )
        syncStateDao.upsert(state.copy(lastValidationSec = 0L))
    }

    /**
     * サムネイル読み込み失敗を起点にフォルダを検証する (Issue #77)。
     *
     * 表示側で「失敗した」通知を受けたフォルダだけを対象にするため、
     * 起動時の全件検証より負荷が低い。代表 1 件を開いて確認し、
     * 実際に開けなければフォルダ全体を非表示にする。
     */
    override suspend fun verifyFolderFromThumbnailFailure(folderKey: String): Int =
        verifyFolderReadability(folderKey)

    /**
     * 画像 1 件の実体を確認し、開けなければ表示から除外する (Issue #77)。
     * グリッドの失敗通知から呼ぶ。フォルダ全体ではなく 1 件だけを対象にする。
     */
    override suspend fun verifyImageReadable(item: ImageItem): Boolean {
        val probe = readableProbe ?: return false
        if (probe.isReadable(item.uri)) return false
        withContext(Dispatchers.IO + NonCancellable) {
            imageDao.upsertUnreadable(
                listOf(
                    UnreadableImageEntity(
                        itemId = item.id,
                        folderKey = item.folderKey,
                        volumeName = item.volumeName,
                        detectedAtSec = nowSeconds()
                    )
                )
            )
        }
        return true
    }

    /**
     * 全フォルダの代表画像を検証する (Issue #77)。
     *
     * フォルダごとに代表 1 件だけプローブする (実機で 1 件あたり約 17ms)。
     * 2,667 フォルダなら 40〜50 秒程度。起動直後の背景で実行し、
     * UI の表示は Room キャッシュのまま進める。前回から [VALIDATION_INTERVAL_SEC]
     * 以内なら再実行しない。
     */
    override suspend fun validateAllFolders(): Int = withContext(Dispatchers.IO) {
        val probe = readableProbe ?: return@withContext 0
        val startedSec = nowSeconds()

        // 起動のたびに全件を開くと無駄なので、一定期間は再検証しない。
        // 手動更新では記録を消して再判定できるようにしてある
        val state = syncStateDao.get()
        if (state != null &&
            startedSec - state.lastValidationSec < VALIDATION_INTERVAL_SEC
        ) {
            return@withContext 0
        }

        val startedAtMs = SystemClock.elapsedRealtime()
        val targets = imageDao.getFolderReadabilityTargets()
        if (targets.isEmpty()) return@withContext 0

        // まず代表 1 件だけ確認する (2,667 フォルダでも 1 回ずつで済む)。
        // 開けなかったフォルダだけ、フォルダ全体の判定に進む
        val suspect = ArrayList<String>()
        for (target in targets) {
            if (!probe.isReadable(target.id)) suspect += target.folderKey
        }

        // 失敗が多すぎる場合は SD カードの取り外しなど一時的な問題とみなすと判断したら、
        // 記録しない。実在するフォルダを大量に隠すほうが被害が大きいため (Issue #77)
        val record = ReadabilityValidation.shouldRecord(targets.size, suspect.size)
        if (!record) {
            ReadabilityValidation.skipReason(targets.size, suspect.size)?.let {
                Log.w("FPV-Perf", it)
            }
            state ?: syncStateDao.upsert(
                SyncStateEntity(lastSinceSec = 0L, lastFullSyncSec = 0L)
            )
            syncStateDao.updateLastValidationSec(startedSec)
            return@withContext 0
        }

        // 代表が開けないフォルダだけ「丸ごと消えた」か「一部だけ欠けた」かを切り分ける。
        // 記録は判定結果で置き換える。古い記録が残ると復活した画像が
        // 非表示のままになるため (自己修復)
        val assessor = FolderReadabilityAssessor(probe)
        val goneFolders = ArrayList<String>(suspect.size)
        val partial = ArrayList<ImageItem>()
        val now = nowSeconds()
        for (folderKey in suspect) {
            // このフォルダの記録は作り直すので、先に消す
            imageDao.clearUnreadableByFolder(folderKey)
            when (val result = assessor.assess(imageDao.getImages(folderKey))) {
                FolderReadability.Healthy -> Unit
                is FolderReadability.PartiallyUnreadable -> partial += result.items
                is FolderReadability.FolderGone -> goneFolders += folderKey
            }
        }
        if (partial.isNotEmpty()) {
            imageDao.upsertUnreadable(
                partial.map { item ->
                    UnreadableImageEntity(
                        itemId = item.id,
                        folderKey = item.folderKey,
                        volumeName = item.volumeName,
                        detectedAtSec = now
                    )
                }
            )
        }
        goneFolders.chunked(UNREADABLE_CHUNK_SIZE).forEach { chunk ->
            imageDao.markFoldersUnreadable(chunk, now)
        }

        // 次回の再検証までの間隔を計る
        state ?: syncStateDao.upsert(
            SyncStateEntity(lastSinceSec = 0L, lastFullSyncSec = 0L)
        )
        syncStateDao.updateLastValidationSec(startedSec)

        Log.i(
            "FPV-Perf",
            "readability validation: ${targets.size} folders, ${suspect.size} suspect, " +
                "${goneFolders.size} gone, ${partial.size} partial, " +
                "${SystemClock.elapsedRealtime() - startedAtMs}ms"
        )
        goneFolders.size
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

    override suspend fun getFolderListScrollPosition(
        sortMode: FolderSortMode
    ): FolderListScrollPositionEntity? = withContext(Dispatchers.IO) {
        folderListScrollPositionDao.get(sortMode.name)
    }

    override suspend fun saveFolderListScrollPosition(
        sortMode: FolderSortMode,
        anchorFolderKey: String?,
        anchorIndex: Int,
        anchorOffsetPx: Int
    ) {
        // 画面離脱直後でも最後の位置が失われないよう、キャンセル不可で書き込む
        withContext(Dispatchers.IO + NonCancellable) {
            folderListScrollPositionDao.upsert(
                FolderListScrollPositionEntity(
                    sortMode = sortMode.name,
                    anchorFolderKey = anchorFolderKey,
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

    override fun observeTags(): Flow<List<TagEntity>> = tagDao.observeTags()

    override fun observeTagsWithCount(): Flow<List<TagWithCount>> = tagDao.observeTagsWithCount()

    override fun observeFolderTagNames(): Flow<List<FolderTagNameRow>> =
        tagDao.observeFolderTagNames()

    override suspend fun findOrCreateTag(rawName: String): TagEntity? {
        val displayName = TagNames.display(rawName) ?: return null
        val normalizedName = TagNames.key(displayName) ?: return null
        // 画面を離れた直後でも付与が失われないよう、キャンセル不可で書き込む
        return withContext(Dispatchers.IO + NonCancellable) {
            tagDao.findByNormalizedName(normalizedName) ?: run {
                val entity = TagEntity(
                    displayName = displayName,
                    normalizedName = normalizedName,
                    createdAtSec = nowSeconds()
                )
                val id = tagDao.insertIgnore(entity)
                // IGNORE で採番されなかった場合は、他スレッドが先に作成している
                if (id > 0L) entity.copy(tagId = id) else tagDao.findByNormalizedName(normalizedName)
            }
        }
    }

    override suspend fun setFolderTags(folderKey: String, tagIds: Set<Long>) {
        withContext(Dispatchers.IO + NonCancellable) {
            val current = tagDao.tagIdsForFolder(folderKey).toSet()
            (current - tagIds).forEach { tagDao.removeLink(folderKey, it) }
            val now = nowSeconds()
            (tagIds - current).forEach { tagId ->
                tagDao.addLink(
                    FolderTagEntity(folderKey = folderKey, tagId = tagId, addedAtSec = now)
                )
            }
        }
    }

    override suspend fun addTagsToFolders(tagIds: Set<Long>, folderKeys: Collection<String>) {
        if (tagIds.isEmpty() || folderKeys.isEmpty()) return
        withContext(Dispatchers.IO + NonCancellable) {
            val now = nowSeconds()
            // 大量フォルダでも UI をブロックしないよう、値の生成だけ行って 1 トランザクションで書く
            val links = ArrayList<FolderTagEntity>(tagIds.size * folderKeys.size)
            folderKeys.forEach { folderKey ->
                tagIds.forEach { tagId ->
                    links += FolderTagEntity(folderKey = folderKey, tagId = tagId, addedAtSec = now)
                }
            }
            links.chunked(TAG_LINK_CHUNK_SIZE).forEach { chunk -> tagDao.addLinks(chunk) }
        }
    }

    override suspend fun renameTag(tagId: Long, rawName: String): Boolean {
        val displayName = TagNames.display(rawName) ?: return false
        val normalizedName = TagNames.key(displayName) ?: return false
        return withContext(Dispatchers.IO + NonCancellable) {
            val existing = tagDao.findByNormalizedName(normalizedName)
            if (existing != null && existing.tagId != tagId) {
                false
            } else {
                tagDao.rename(tagId, displayName, normalizedName)
                true
            }
        }
    }

    override suspend fun deleteTag(tagId: Long) {
        withContext(Dispatchers.IO + NonCancellable) {
            tagDao.deleteTag(tagId)
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

        /** 「開けなかった」記録の一括書き込みを分割する件数 (SQLite 変数上限対策) */
        private const val UNREADABLE_CHUNK_SIZE = 300

        /** 自然順キーのバックフィルを 1 回に処理する件数 */
        private const val BACKFILL_BATCH_SIZE = 500

        /** タグの一括付与を分割する件数 */
        private const val TAG_LINK_CHUNK_SIZE = 500

        /** 削除を反映するための定期全件走査の間隔 (24 時間) */
        private const val FULL_SCAN_INTERVAL_SEC = 24L * 60L * 60L

        /** 実体読み取り検証の再実行間隔 (24 時間、Issue #77) */
        private const val VALIDATION_INTERVAL_SEC = 24L * 60L * 60L
    }
}
