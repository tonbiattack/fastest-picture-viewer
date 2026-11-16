package com.tonbiattack.fastestpictureviewer.repository

import android.os.SystemClock
import android.util.Log
import androidx.room.withTransaction
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
import com.tonbiattack.fastestpictureviewer.data.FolderRepresentativeEntity
import com.tonbiattack.fastestpictureviewer.data.FolderSummaryEntity
import com.tonbiattack.fastestpictureviewer.data.FolderSummaryStateEntity
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
import com.tonbiattack.fastestpictureviewer.perf.StartupTrace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
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
    private val readableProbe: ImageReadableProbe? = null,
    /**
     * 現在マウントされているボリューム名を返す (Issue #80)。
     * 既定では [MediaStoreScanner] に問い合わせる。テストで差し替えられるようにする。
     */
    private val mountedVolumesProvider: () -> Set<String> = { scanner.mountedVolumes() },
    /**
     * タグ・お気に入り・除外を変更したことを通知する (Issue #108)。
     *
     * バックアップの書き出し忘れを防ぐため、「前回の書き出し以降に変更があったか」を
     * 記録する。閲覧操作では呼ばず、利用者が手で作ったデータを変えたときだけ呼ぶ。
     * 既定は何もしない (テストの後方互換)。
     */
    private val onUserDataChanged: () -> Unit = {}
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
    private val summaryCacheDao = db.folderSummaryCacheDao()

    /** 同期の二重実行を防ぐ。実行中の要求は待たずにスキップする */
    private val syncMutex = Mutex()

    /** 自然順キーの遅延バックフィルをフォルダ単位で一度だけ行うための記録 */
    private val backfilledFolders = ConcurrentHashMap.newKeySet<String>()

    private val _syncStatus = MutableStateFlow(SyncStatus.IDLE)
    override val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    override fun observeRepresentatives(): Flow<List<RepresentativeImageRow>> =
        summaryCacheDao.observeState()
            .distinctUntilChanged()
            .flatMapLatest { state ->
                if (isFolderSummaryCacheReady(state)) {
                    // 集計キャッシュと一緒に保存した代表画像を読む (Issue #106)。
                    // 毎回 35 万行から MIN() を取ると実機で約 1 秒かかる
                    summaryCacheDao.observeRepresentatives().map { rows ->
                        rows.map { row ->
                            RepresentativeImageRow(
                                id = row.id,
                                folderKey = row.folderKey,
                                dateModifiedSec = row.dateModifiedSec,
                                size = row.size
                            )
                        }
                    }
                } else {
                    imageDao.observeRepresentatives()
                        .onStart { StartupTrace.mark("representatives_start") }
                        .onEach { StartupTrace.mark("representatives_emitted") }
                }
            }
            .flowOn(Dispatchers.IO)

    /** キャッシュが使えるかを購読する。未作成なら false（内部判定用） */
    private fun isFolderSummaryCacheReady(state: FolderSummaryStateEntity?): Boolean =
        state != null && state.rebuiltAtMs > 0L

    /**
     * フォルダ一覧を購読する (Issue #106)。
     *
     * キャッシュが作成済みなら [folder_summaries]（2,667 行）を読むだけ。
     * 未作成なら従来の集計クエリへ自動で切り替わる。
     */
    override fun observeFolderSummaries(): Flow<List<FolderSummaryRow>> =
        summaryCacheDao.observeState()
            .distinctUntilChanged()
            .flatMapLatest { state ->
                if (isFolderSummaryCacheReady(state)) {
                    summaryCacheDao.observeSummaries().map { rows ->
                        rows.map { row ->
                            FolderSummaryRow(
                                folderKey = row.folderKey,
                                volumeName = row.volumeName,
                                relativePath = row.relativePath,
                                imageCount = row.imageCount,
                                latestModifiedSec = row.latestModifiedSec
                            )
                        }
                    }
                } else {
                    // 初回起動・キャッシュ未作成時のみ従来の集計を使う
                    imageDao.observeFolderSummaries()
                        .onStart { StartupTrace.mark("folder_summaries_start") }
                        .onEach { StartupTrace.mark("folder_summaries_emitted") }
                }
            }
            .onStart { StartupTrace.mark("folder_summaries_subscribed") }
            .flowOn(Dispatchers.IO)

    /**
     * フォルダ一覧キャッシュを作り直す (Issue #106)。
     *
     * 35 万行の集計を伴うため、同期の完了時など限られた契機だけ呼ぶ。
     * 既存の集計クエリをそのまま使い、結果をキャッシュ用テーブルへ写す。
     */
    override suspend fun rebuildFolderSummaryCache() {
        withContext(Dispatchers.IO) {
            val started = SystemClock.elapsedRealtime()
            try {
                // 集計元の読み取りからキャッシュの公開まで、他の書き込みと混ざらないようにする。
                db.withTransaction {
                    val summaries = imageDao.getAllFolderSummaries()
                    val representatives = imageDao.getAllRepresentatives()
                    val total = imageDao.count()
                    summaryCacheDao.replaceAll(
                        summaries = summaries.map { row ->
                            FolderSummaryEntity(
                                folderKey = row.folderKey,
                                volumeName = row.volumeName,
                                relativePath = row.relativePath,
                                imageCount = row.imageCount,
                                latestModifiedSec = row.latestModifiedSec
                            )
                        },
                        representatives = representatives.map { row ->
                            FolderRepresentativeEntity(
                                folderKey = row.folderKey,
                                id = row.id,
                                dateModifiedSec = row.dateModifiedSec,
                                size = row.size
                            )
                        },
                        imageCount = total,
                        rebuiltAtMs = System.currentTimeMillis()
                    )
                }
                StartupTrace.task("folder_summary_cache_rebuild", SystemClock.elapsedRealtime() - started)
            } catch (t: CancellationException) {
                throw t
            } catch (t: Exception) {
                // キャッシュ作成の失敗で一覧を壊さない。次回の同期で再試行する
                Log.w("FPV-Perf", "folder summary cache rebuild failed", t)
            }
        }
    }

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
            val volumeReadable = isVolumeReadable(items.first().volumeName)
            when (val result = FolderReadabilityAssessor(probe).assess(items, volumeReadable)) {
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

                is FolderReadability.Undetermined -> {
                    // 判断できないので記録は変えない (Issue #93)。
                    // 一時的な読み取り失敗で実在フォルダを隠さないため
                    Log.w("FPV-Perf", "folder readability undetermined: volume unreadable")
                    0
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
        var undeterminedFolders = 0
        val now = nowSeconds()
        // ボリュームの読み取り可否は 1 回だけ確認する (Issue #93)。
        // フォルダごとに問い合わせると数千回になるため
        val volumeReadable = mutableMapOf<String, Boolean>()
        for (folderKey in suspect) {
            val items = imageDao.getImages(folderKey)
            val readable = items.firstOrNull()?.volumeName?.let { volumeName ->
                volumeReadable.getOrPut(volumeName) { isVolumeReadable(volumeName) }
            } ?: true
            // このフォルダの記録は作り直すので、先に消す
            imageDao.clearUnreadableByFolder(folderKey)
            when (val result = assessor.assess(items, readable)) {
                FolderReadability.Healthy -> Unit
                is FolderReadability.PartiallyUnreadable -> partial += result.items
                is FolderReadability.FolderGone -> goneFolders += folderKey
                is FolderReadability.Undetermined -> {
                    // 判断できないので記録しない (Issue #93)
                    undeterminedFolders++
                }
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
                "undetermined=$undeterminedFolders, " +
                "${SystemClock.elapsedRealtime() - startedAtMs}ms"
        )
        goneFolders.size
    }

    override fun observeFavorites(): Flow<List<FavoriteImageEntity>> = favoriteDao.observeAll()

    override fun observeExcludedFolders(): Flow<List<ExcludedFolderEntity>> =
        excludedFolderDao.observeAll()

    override suspend fun getUnreadableFolderSummary(): List<UnreadableFolderSummary> =
        withContext(Dispatchers.IO) {
            // 対応する images 行が無い記録は、数えても意味がないので先に片付ける (Issue #81)
            runCatching { imageDao.deleteOrphanedUnreadable() }
            imageDao.getUnreadableFolderSummary().map { row ->
                UnreadableFolderSummary(
                    folderKey = row.folderKey,
                    volumeName = row.volumeName,
                    imageCount = row.imageCount,
                    totalBytes = row.totalBytes
                )
            }
        }

    /**
     * 実体の無い行を整理する (Issue #80)。
     *
     * 誤削除は取り返しがつかないため、[StaleMediaCleanup] の安全条件を必ず通す。
     * 1 フォルダずつトランザクションで消し、途中で失敗しても他へ影響させない。
     */
    override suspend fun cleanupStaleEntries(dryRun: Boolean): StaleCleanupResult =
        withContext(Dispatchers.IO) {
            val rows = imageDao.getUnreadableFolderSummary()
            if (rows.isEmpty()) {
                return@withContext StaleCleanupResult(0, 0, 0)
            }

            val totalFolders = imageDao.countFolders()
            val candidates = rows.map {
                StaleMediaCleanup.Candidate(
                    folderKey = it.folderKey,
                    volumeName = it.volumeName,
                    imageCount = it.imageCount,
                    totalBytes = it.totalBytes
                )
            }

            val mounted = mountedVolumesProvider()
            when (
                val decision = StaleMediaCleanup.decide(
                    mountedVolumes = mounted,
                    candidates = candidates,
                    totalFolders = totalFolders.toInt()
                )
            ) {
                is StaleMediaCleanup.Decision.Rejected -> {
                    Log.i("FPV-Perf", "stale cleanup rejected: ${decision.reason}")
                    StaleCleanupResult(0, 0, 0, rejectedReason = decision.reason)
                }

                is StaleMediaCleanup.Decision.Allowed -> {
                    val targetKeys = decision.folderKeys.toSet()
                    val targets = candidates.filter { it.folderKey in targetKeys }
                    val imageCount = targets.sumOf { it.imageCount }
                    val totalBytes = targets.sumOf { it.totalBytes }

                    if (dryRun) {
                        return@withContext StaleCleanupResult(
                            folderCount = targets.size,
                            imageCount = imageCount,
                            totalBytes = totalBytes
                        )
                    }

                    var removedFolders = 0
                    var removedImages = 0L
                    for (target in targets) {
                        try {
                            // 開けなかった画像の行だけを消す。
                            // 欠損記録だけ消すと、壊れた画像が再び表示されてしまう (Issue #80)。
                            // 一部だけ欠けたフォルダでも、実在する画像は残る
                            val unreadableIds = imageDao.getUnreadableItemIds(target.folderKey)
                            unreadableIds.chunked(DELETE_CHUNK_SIZE).forEach { chunk ->
                                removedImages += imageDao.deleteImagesByIds(chunk)
                            }
                            imageDao.deleteUnreadableByFolder(target.folderKey)
                            removedFolders++
                        } catch (t: Exception) {
                            // 1 フォルダの失敗で全体を止めない。次回の実行で再試行される
                            Log.w("FPV-Perf", "stale cleanup failed: ${target.folderKey}", t)
                        }
                    }
                    Log.i(
                        "FPV-Perf",
                        "stale cleanup: $removedFolders folders, $removedImages images, " +
                            "${totalBytes / 1024 / 1024}MB"
                    )
                    StaleCleanupResult(
                        folderCount = removedFolders,
                        imageCount = removedImages,
                        totalBytes = totalBytes
                    )
                }
            }
        }

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
            onUserDataChanged()
        }
    }

    override suspend fun includeFolder(folderKey: String) {
        // 画面を離れた直後でも解除が失われないよう、キャンセル不可で書き込む
        withContext(Dispatchers.IO + NonCancellable) {
            excludedFolderDao.remove(folderKey)
            onUserDataChanged()
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
            onUserDataChanged()
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
            onUserDataChanged()
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
            onUserDataChanged()
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
            onUserDataChanged()
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
                onUserDataChanged()
                true
            }
        }
    }

    override suspend fun deleteTag(tagId: Long) {
        withContext(Dispatchers.IO + NonCancellable) {
            tagDao.deleteTag(tagId)
            onUserDataChanged()
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
        /** 削除を見送った理由 (Issue #92)。UI に伝える */
        val skippedDeletions = mutableSetOf<DeletionGuard.Reason>()
        /** お気に入りを引き継いだ件数 (Issue #94) */
        var migratedFavorites = 0

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
                    // 削除の安全判定に使う（Issue #92）
                    var scannedCount = 0

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
                        scannedCount += batch.size
                    }

                    added += scanAdded
                    updated += scanUpdated

                    if (canDelete && cachedIds != null && cachedIds.isNotEmpty()) {
                        // 削除してよいか、走査結果から判定する (Issue #92)。
                        // 走査 0 件や大量削除は、写真が消えたのではなく
                        // 読み取りに失敗した可能性がある
                        when (
                            val decision = DeletionGuard.decide(
                                cachedCount = cachedIds.size + scannedCount,
                                scannedCount = scannedCount,
                                deletionCount = cachedIds.size,
                                volumeReadable = isVolumeReadable(volumeName)
                            )
                        ) {
                            is DeletionGuard.Decision.Rejected -> {
                                skippedDeletions += decision.reason
                                Log.w(
                                    "FPV-Perf",
                                    "stale deletion skipped: ${decision.reason}"
                                )
                            }

                            is DeletionGuard.Decision.Allowed -> {
                                // 削除する前に、移動した写真のお気に入りを引き継ぐ (Issue #94)。
                                // 行を消した後では元の情報を辿れないため、必ず先に行う
                                migratedFavorites += migrateFavoritesBeforeDeletion(
                                    cachedIds.toList()
                                )
                                deleteInChunks(cachedIds.toList())
                                removed += cachedIds.size
                            }
                        }
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
                // 例外メッセージやスタックトレースに個人のパスが含まれうるため記録しない。
                Log.w(
                    "FPV-Perf",
                    "volume scan failed: ${t.javaClass.simpleName}"
                )
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
            skippedDeletions.isNotEmpty() ->
                skippedDeletions.joinToString(" ") { DeletionGuard.message(it) }
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
                "skipped=$skippedVolumes failed=${failedVolumes.size} " +
                "deletionSkipped=${skippedDeletions.size} favoritesMigrated=$migratedFavorites"
        )

        _syncStatus.value = SyncStatus(
            isRunning = false,
            isInitialScan = false,
            failedVolumeCount = failedVolumes.size,
            errorMessage = message,
            addedCount = added,
            removedCount = removed,
            wasFullScan = isFull
        )

        // 内容が変わった（または初回）ときだけ、フォルダ一覧キャッシュを作り直す (Issue #106)。
        // 変化が無ければ作り直さず、起動時の読み出しをキャッシュのまま維持する
        val hasChanges = added > 0 || updated > 0 || removed > 0
        if (hasChanges || summaryCacheDao.getState() == null) {
            rebuildFolderSummaryCache()
        }
        return result
    }

    /**
     * 削除直前にボリュームを読めるか確認する (Issue #92)。
     *
     * 走査中に SD カードが抜けた場合、走査結果だけでは判断できない。
     * 読めない状態で削除すると、写真が残っていても一覧から消える。
     */
    private suspend fun isVolumeReadable(volumeName: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { volumeName in mountedVolumesProvider() }.getOrDefault(false)
        }

    /**
     * 削除される行のお気に入りを、移動先の行へ引き継ぐ (Issue #94)。
     *
     * `favorite_images` は content URI を主キーにしているため、SD カードへ
     * 移動すると ID が変わり紐付けが切れる。削除前に、名前・サイズ・
     * 更新日時が一致する別の行を探して付け替える。
     *
     * 候補が複数ある場合は取り違えを避けるため移行しない
     * （別の写真にお気に入りが付く方が、消えるより悪い）。
     *
     * @return 引き継いだ件数
     */
    private suspend fun migrateFavoritesBeforeDeletion(idsToDelete: List<String>): Int {
        if (idsToDelete.isEmpty()) return 0
        val favorites = favoriteDao.getAll().associateBy { it.itemId }
        // お気に入りが付いている行だけを対象にする（大半は該当しない）
        val targets = idsToDelete.filter { it in favorites }
        if (targets.isEmpty()) return 0

        var migrated = 0
        for (chunk in targets.chunked(DELETE_CHUNK_SIZE)) {
            val rows = imageDao.getImagesByIds(chunk)
            for (row in rows) {
                val favorite = favorites[row.id] ?: continue
                val candidates = imageDao.findMoveCandidates(
                    displayName = row.displayName,
                    size = row.size,
                    dateModifiedSec = row.dateModifiedSec,
                    excludeId = row.id
                )
                val alreadyFavorite = candidates.any { it.id in favorites }
                when (val decision = FavoriteMigration.decide(candidates, alreadyFavorite)) {
                    is FavoriteMigration.Decision.Migrate -> {
                        favoriteDao.add(
                            FavoriteImageEntity(
                                itemId = decision.to.id,
                                addedAtSec = favorite.addedAtSec
                            )
                        )
                        // 移動元のお気に入りは残さない。残すと存在しない行を
                        // 参照し続け、バックアップにも不要な項目が載る
                        favoriteDao.remove(row.id)
                        migrated++
                    }

                    is FavoriteMigration.Decision.Keep -> {
                        // 移行先が無い・決められない場合は、元の行が消えるため
                        // お気に入りも一緒に消す
                        favoriteDao.remove(row.id)
                        Log.i(
                            "FPV-Perf",
                            "favorite migration skipped: ${decision.reason}"
                        )
                    }
                }
            }
        }
        return migrated
    }

    /** SQLite 変数上限を避けるため ID を分割して削除する */
    private suspend fun deleteInChunks(ids: List<String>) {        ids.chunked(DELETE_CHUNK_SIZE).forEach { chunk ->
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
