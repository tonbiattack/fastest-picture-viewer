package com.tonbiattack.fastestpictureviewer.ui.folder

import com.tonbiattack.fastestpictureviewer.core.util.FolderKeys
import com.tonbiattack.fastestpictureviewer.core.util.FolderSortMode
import com.tonbiattack.fastestpictureviewer.data.ExcludedFolderEntity
import com.tonbiattack.fastestpictureviewer.data.FavoriteFolderEntity
import com.tonbiattack.fastestpictureviewer.data.FavoriteImageEntity
import com.tonbiattack.fastestpictureviewer.data.FolderListScrollPositionEntity
import com.tonbiattack.fastestpictureviewer.data.FolderScrollPositionEntity
import com.tonbiattack.fastestpictureviewer.data.FolderSummaryRow
import com.tonbiattack.fastestpictureviewer.data.ImageItem
import com.tonbiattack.fastestpictureviewer.data.RecentFolderEntity
import com.tonbiattack.fastestpictureviewer.data.RepresentativeImageRow
import com.tonbiattack.fastestpictureviewer.repository.GalleryRepository
import com.tonbiattack.fastestpictureviewer.repository.SyncMode
import com.tonbiattack.fastestpictureviewer.repository.SyncResult
import com.tonbiattack.fastestpictureviewer.repository.SyncStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * フォルダ一覧 ViewModel の挙動テスト。
 *
 * Issue #3 の核心:
 * - 同期を開始しても既存一覧 (folders) を emptyList に戻さない
 * - isSyncing は一覧データとは独立して変化する
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FolderListViewModelTest {

    /** テスト用の列数設定 */
    private class FakeViewSettings(
        initial: Int = 2,
        initialSortMode: FolderSortMode = FolderSortMode.LATEST
    ) : FolderViewSettings {
        private val _columnCount = MutableStateFlow(initial)
        override val columnCount: StateFlow<Int> = _columnCount

        private val _sortMode = MutableStateFlow(initialSortMode)
        override val sortMode: StateFlow<FolderSortMode> = _sortMode

        override fun setColumnCount(count: Int) {
            _columnCount.value = count.coerceIn(1, 4)
        }

        override fun setSortMode(mode: FolderSortMode) {
            _sortMode.value = mode
        }
    }

    /** テスト用の固定リポジトリ */
    private class FakeRepository : GalleryRepository {
        val foldersFlow = MutableStateFlow<List<FolderSummaryRow>>(emptyList())
        val representativesFlow = MutableStateFlow<List<RepresentativeImageRow>>(emptyList())
        val statusFlow = MutableStateFlow(SyncStatus.IDLE)

        override val syncStatus: StateFlow<SyncStatus> = statusFlow

        override fun observeFolderSummaries(): Flow<List<FolderSummaryRow>> = foldersFlow
        override fun observeRepresentatives(): Flow<List<RepresentativeImageRow>> = representativesFlow
        override fun observeImages(folderKey: String): Flow<List<ImageItem>> =
            MutableStateFlow(emptyList())

        override suspend fun sync(mode: SyncMode, canDelete: Boolean): SyncResult =
            SyncResult(0, 0, 0, emptyList())

        override suspend fun setFavorite(itemId: String, favorite: Boolean) {
            // テストでは無視
        }

        override fun observeFavorites(): Flow<List<FavoriteImageEntity>> =
            MutableStateFlow(emptyList())

        override fun observeExcludedFolders(): Flow<List<ExcludedFolderEntity>> =
            MutableStateFlow(emptyList())

        override suspend fun excludeFolder(volumeName: String, relativePath: String) = Unit

        override suspend fun includeFolder(folderKey: String) = Unit

        val favoriteFoldersFlow = MutableStateFlow<List<FavoriteFolderEntity>>(emptyList())

        override fun observeFavoriteFolders(): Flow<List<FavoriteFolderEntity>> = favoriteFoldersFlow

        override suspend fun setFolderFavorite(
            folderKey: String,
            volumeName: String,
            relativePath: String,
            favorite: Boolean
        ) = Unit

        override fun observeFavoriteImages(folderKey: String?): Flow<List<ImageItem>> =
            MutableStateFlow(emptyList())

        val recentFoldersFlow = MutableStateFlow<List<RecentFolderEntity>>(emptyList())
        override fun observeRecentFolders(): Flow<List<RecentFolderEntity>> = recentFoldersFlow

        override suspend fun markFolderOpened(folderKey: String) = Unit
        override suspend fun getScrollPosition(folderKey: String): FolderScrollPositionEntity? = null
        override suspend fun saveScrollPosition(
            folderKey: String,
            anchorItemId: String?,
            anchorIndex: Int,
            anchorOffsetPx: Int
        ) = Unit

        val folderListScroll =
            mutableMapOf<FolderSortMode, FolderListScrollPositionEntity>()

        override suspend fun getFolderListScrollPosition(
            sortMode: FolderSortMode
        ): FolderListScrollPositionEntity? = folderListScroll[sortMode]

        override suspend fun saveFolderListScrollPosition(
            sortMode: FolderSortMode,
            anchorFolderKey: String?,
            anchorIndex: Int,
            anchorOffsetPx: Int
        ) {
            folderListScroll[sortMode] = FolderListScrollPositionEntity(
                sortMode = sortMode.name,
                anchorFolderKey = anchorFolderKey,
                anchorIndex = anchorIndex,
                anchorOffsetPx = anchorOffsetPx,
                updatedAtSec = 1L
            )
        }
    }

    @Before
    fun setup() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    private fun summary(
        key: String,
        count: Long = 1L,
        modifiedSec: Long = 100L
    ) = FolderSummaryRow(
        folderKey = key,
        volumeName = "v",
        relativePath = key.substringAfter(':'),
        imageCount = count,
        latestModifiedSec = modifiedSec
    )

    private fun representative(folderKey: String) = RepresentativeImageRow(
        id = "content-1",
        folderKey = folderKey
    )

    @Test
    fun `同期開始中でも既存の一覧はクリアされない`() = runTest {
        val repo = FakeRepository()
        val vm = FolderListViewModel(repo, FakeViewSettings())
        // StateFlow は購読されないと開始しないため、背景で購読する
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

        repo.foldersFlow.value = listOf(summary(FolderKeys.create("v", "Pictures/a/")))
        repo.representativesFlow.value =
            listOf(RepresentativeImageRow(id = "content-1", folderKey = FolderKeys.create("v", "Pictures/a/")))
        advanceUntilIdle()

        // 同期を開始して書き換えなしの一覧が保たれることを確認する (Issue #3)
        repo.statusFlow.value = SyncStatus(isRunning = true)
        advanceUntilIdle()

        val midSync = vm.uiState.value
        assertTrue("同期中に一覧が消えた", midSync.folders.isNotEmpty())
        assertTrue(midSync.isSyncing)
        assertFalse(midSync.showInitialScanProgress)
        assertEquals(1L, midSync.folders.first().imageCount)
        assertEquals(
            FolderKeys.create("v", "Pictures/a/"),
            midSync.folders.first().folderKey
        )
        assertEquals("content-1", midSync.folders.first().representativeImageUri)
    }

    @Test
    fun `キャッシュが全く無い初回スキャン中は進捗表示になる`() = runTest {
        val repo = FakeRepository()
        val vm = FolderListViewModel(repo, FakeViewSettings())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

        repo.statusFlow.value = SyncStatus(isRunning = true, isInitialScan = true)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.showInitialScanProgress)
    }

    @Test
    fun `同期失敗メッセージは一覧とは別に保持される`() = runTest {
        val repo = FakeRepository()
        val vm = FolderListViewModel(repo, FakeViewSettings())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

        val key = FolderKeys.create("v", "Pictures/a/")
        repo.foldersFlow.value = listOf(summary(key))
        advanceUntilIdle()
        repo.statusFlow.value = SyncStatus(
            isRunning = false,
            failedVolumeCount = 1,
            errorMessage = "ストレージ 1 件の更新を確認できませんでした"
        )
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("ストレージ 1 件の更新を確認できませんでした", state.errorMessage)
        assertEquals(1, state.folders.size)
    }

    @Test
    fun `同期後の並び替えがUI状態に反映される`() = runTest {
        val repo = FakeRepository()
        val vm = FolderListViewModel(repo, FakeViewSettings())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

        val a = summary(FolderKeys.create("v", "Pictures/A/"), modifiedSec = 100L)
        val b = summary(FolderKeys.create("v", "Pictures/B/"), modifiedSec = 200L)

        repo.foldersFlow.value = listOf(b, a)
        advanceUntilIdle()
        assertEquals(listOf(b.folderKey, a.folderKey), vm.uiState.value.folders.map { it.folderKey })

        // 同期で A の最新日時が新しくなった場合、一覧を消さずに順序だけ更新する
        val aUpdated = summary(FolderKeys.create("v", "Pictures/A/"), modifiedSec = 300L)
        repo.foldersFlow.value = listOf(aUpdated, b)
        advanceUntilIdle()
        assertEquals(
            listOf(aUpdated.folderKey, b.folderKey),
            vm.uiState.value.folders.map { it.folderKey }
        )
        assertEquals(300L, vm.uiState.value.folders.first().latestModifiedSec)
    }

    @Test
    fun `検索は大文字小文字を区別せず部分一致で絞り込む`() = runTest {
        val repo = FakeRepository()
        val vm = FolderListViewModel(repo, FakeViewSettings())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

        val chatGpt = summary(FolderKeys.create("v", "Pictures/ChatGPT/"))
        val camera = summary(FolderKeys.create("v", "Pictures/Camera/"))
        repo.foldersFlow.value = listOf(chatGpt, camera)
        advanceUntilIdle()

        vm.onSearchQueryChange("chatg")
        advanceUntilIdle()
        assertEquals(listOf(chatGpt.folderKey), vm.uiState.value.folders.map { it.folderKey })

        vm.onSearchQueryChange("CAM")
        advanceUntilIdle()
        assertEquals(listOf(camera.folderKey), vm.uiState.value.folders.map { it.folderKey })

        vm.clearSearch()
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.folders.size)
    }

    @Test
    fun `日本語フォルダ名でも部分一致検索できる`() = runTest {
        val repo = FakeRepository()
        val vm = FolderListViewModel(repo, FakeViewSettings())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

        val trip = summary(FolderKeys.create("v", "Pictures/旅行写真/"))
        val food = summary(FolderKeys.create("v", "Pictures/食べ物/"))
        repo.foldersFlow.value = listOf(trip, food)
        advanceUntilIdle()

        vm.onSearchQueryChange("旅行")
        advanceUntilIdle()
        assertEquals(listOf(trip.folderKey), vm.uiState.value.folders.map { it.folderKey })
    }

    @Test
    fun `お気に入りのみ表示で絞り込める`() = runTest {
        val repo = FakeRepository()
        val vm = FolderListViewModel(repo, FakeViewSettings())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

        val favoriteKey = FolderKeys.create("v", "Pictures/Favorite/")
        val otherKey = FolderKeys.create("v", "Pictures/Other/")
        repo.foldersFlow.value = listOf(summary(favoriteKey), summary(otherKey))
        repo.favoriteFoldersFlow.value = listOf(
            FavoriteFolderEntity(favoriteKey, "v", "Pictures/Favorite/", 1L)
        )
        advanceUntilIdle()

        assertTrue(vm.uiState.value.folders.first { it.folderKey == favoriteKey }.isFavorite)
        assertFalse(vm.uiState.value.folders.first { it.folderKey == otherKey }.isFavorite)

        vm.toggleFavoritesOnly()
        advanceUntilIdle()
        assertEquals(listOf(favoriteKey), vm.uiState.value.folders.map { it.folderKey })
        assertTrue(vm.uiState.value.favoritesOnly)

        vm.toggleFavoritesOnly()
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.folders.size)
    }

    @Test
    fun `検索とお気に入りフィルタを併用できる`() = runTest {
        val repo = FakeRepository()
        val vm = FolderListViewModel(repo, FakeViewSettings())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

        val favoriteA = FolderKeys.create("v", "Pictures/TravelA/")
        val favoriteB = FolderKeys.create("v", "Pictures/TravelB/")
        repo.foldersFlow.value = listOf(summary(favoriteA), summary(favoriteB))
        repo.favoriteFoldersFlow.value = listOf(
            FavoriteFolderEntity(favoriteA, "v", "Pictures/TravelA/", 1L),
            FavoriteFolderEntity(favoriteB, "v", "Pictures/TravelB/", 2L)
        )
        advanceUntilIdle()

        vm.toggleFavoritesOnly()
        vm.onSearchQueryChange("TravelA")
        advanceUntilIdle()

        assertEquals(listOf(favoriteA), vm.uiState.value.folders.map { it.folderKey })
    }

    @Test
    fun `列数を変更すると設定とUI状態に即時反映される`() = runTest {
        val settings = FakeViewSettings(initial = 3)
        val vm = FolderListViewModel(FakeRepository(), settings)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        advanceUntilIdle()
        assertEquals(3, vm.uiState.value.columnCount)

        vm.setColumnCount(4)
        advanceUntilIdle()
        assertEquals(4, vm.uiState.value.columnCount)
        assertEquals(4, settings.columnCount.value)

        // 範囲外は 1〜4 に丸める
        vm.setColumnCount(9)
        advanceUntilIdle()
        assertEquals(4, vm.uiState.value.columnCount)
    }

    @Test
    fun `最近閲覧順は閲覧済みを先頭にし未閲覧は更新日時順にする`() = runTest {
        val repo = FakeRepository()
        val settings = FakeViewSettings()
        val vm = FolderListViewModel(repo, settings)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

        val a = summary(FolderKeys.create("v", "Pictures/A/"), modifiedSec = 100L)
        val b = summary(FolderKeys.create("v", "Pictures/B/"), modifiedSec = 200L)
        val c = summary(FolderKeys.create("v", "Pictures/C/"), modifiedSec = 300L)
        // DAO は更新日時降順で返す想定
        repo.foldersFlow.value = listOf(c, b, a)
        repo.recentFoldersFlow.value = listOf(
            RecentFolderEntity(a.folderKey, openedAtSec = 100L),
            RecentFolderEntity(b.folderKey, openedAtSec = 200L)
        )
        advanceUntilIdle()

        vm.setSortMode(FolderSortMode.RECENT)
        advanceUntilIdle()
        assertEquals(
            listOf(b.folderKey, a.folderKey, c.folderKey),
            vm.uiState.value.folders.map { it.folderKey }
        )
        assertEquals(FolderSortMode.RECENT, vm.uiState.value.sortMode)

        vm.setSortMode(FolderSortMode.LATEST)
        advanceUntilIdle()
        assertEquals(
            listOf(c.folderKey, b.folderKey, a.folderKey),
            vm.uiState.value.folders.map { it.folderKey }
        )
    }

    @Test
    fun `保存済みのスクロール位置をアンカーから復元する`() = runTest {
        val repo = FakeRepository()
        val a = summary(FolderKeys.create("v", "Pictures/A/"), modifiedSec = 100L)
        val b = summary(FolderKeys.create("v", "Pictures/B/"), modifiedSec = 200L)
        val c = summary(FolderKeys.create("v", "Pictures/C/"), modifiedSec = 300L)
        // DAO は更新日時降順で返す想定
        repo.foldersFlow.value = listOf(c, b, a)
        repo.folderListScroll[FolderSortMode.LATEST] = FolderListScrollPositionEntity(
            sortMode = FolderSortMode.LATEST.name,
            anchorFolderKey = b.folderKey,
            anchorIndex = 1,
            anchorOffsetPx = 48,
            updatedAtSec = 1L
        )
        val vm = FolderListViewModel(repo, FakeViewSettings())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state.isScrollRestored)
        assertEquals(1, state.initialIndex)
        assertEquals(48, state.initialOffset)
        assertEquals(listOf(c.folderKey, b.folderKey, a.folderKey), state.allFolderKeys)
    }

    @Test
    fun `スクロール位置はdebounce後にフォルダキーで保存される`() = runTest {
        val repo = FakeRepository()
        val a = summary(FolderKeys.create("v", "Pictures/A/"), modifiedSec = 300L)
        val b = summary(FolderKeys.create("v", "Pictures/B/"), modifiedSec = 200L)
        val c = summary(FolderKeys.create("v", "Pictures/C/"), modifiedSec = 100L)
        repo.foldersFlow.value = listOf(a, b, c)
        val vm = FolderListViewModel(repo, FakeViewSettings())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.onScrollPositionChanged(index = 2, offsetPx = 32)
        advanceUntilIdle()

        val saved = repo.folderListScroll[FolderSortMode.LATEST]
        assertEquals(c.folderKey, saved?.anchorFolderKey)
        assertEquals(2, saved?.anchorIndex)
        assertEquals(32, saved?.anchorOffsetPx)
    }

    @Test
    fun `検索中やお気に入り絞り込み中のスクロールは保存しない`() = runTest {
        val repo = FakeRepository()
        val a = summary(FolderKeys.create("v", "Pictures/A/"))
        val b = summary(FolderKeys.create("v", "Pictures/B/"))
        repo.foldersFlow.value = listOf(a, b)
        repo.favoriteFoldersFlow.value = listOf(
            FavoriteFolderEntity(a.folderKey, "v", "Pictures/A/", 1L)
        )
        val vm = FolderListViewModel(repo, FakeViewSettings())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.toggleFavoritesOnly()
        advanceUntilIdle()
        vm.onScrollPositionChanged(index = 0, offsetPx = 10)
        advanceUntilIdle()
        assertTrue(repo.folderListScroll.isEmpty())

        vm.toggleFavoritesOnly()
        vm.onSearchQueryChange("A")
        advanceUntilIdle()
        vm.onScrollPositionChanged(index = 0, offsetPx = 10)
        advanceUntilIdle()
        assertTrue(repo.folderListScroll.isEmpty())

        // フィルタ解除後の通常一覧では保存される
        vm.clearSearch()
        advanceUntilIdle()
        vm.onScrollPositionChanged(index = 1, offsetPx = 20)
        advanceUntilIdle()
        assertEquals(b.folderKey, repo.folderListScroll[FolderSortMode.LATEST]?.anchorFolderKey)
    }

    @Test
    fun `並び順ごとに独立したスクロール位置を復元する`() = runTest {
        val repo = FakeRepository()
        val a = summary(FolderKeys.create("v", "Pictures/A/"), modifiedSec = 300L)
        val b = summary(FolderKeys.create("v", "Pictures/B/"), modifiedSec = 200L)
        val c = summary(FolderKeys.create("v", "Pictures/C/"), modifiedSec = 100L)
        repo.foldersFlow.value = listOf(a, b, c)
        repo.folderListScroll[FolderSortMode.LATEST] = FolderListScrollPositionEntity(
            sortMode = FolderSortMode.LATEST.name,
            anchorFolderKey = b.folderKey,
            anchorIndex = 1,
            anchorOffsetPx = 12,
            updatedAtSec = 1L
        )
        repo.folderListScroll[FolderSortMode.RECENT] = FolderListScrollPositionEntity(
            sortMode = FolderSortMode.RECENT.name,
            anchorFolderKey = c.folderKey,
            anchorIndex = 2,
            anchorOffsetPx = 24,
            updatedAtSec = 1L
        )
        val settings = FakeViewSettings()
        val vm = FolderListViewModel(repo, settings)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.initialIndex)
        assertEquals(12, vm.uiState.value.initialOffset)

        vm.setSortMode(FolderSortMode.RECENT)
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.initialIndex)
        assertEquals(24, vm.uiState.value.initialOffset)
        assertEquals(FolderSortMode.RECENT, vm.uiState.value.sortMode)

        // 位置を動かさずに戻した場合、LATEST の保存位置を上書きしない
        vm.setSortMode(FolderSortMode.LATEST)
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.initialIndex)
        assertEquals(12, vm.uiState.value.initialOffset)
        assertEquals(b.folderKey, repo.folderListScroll[FolderSortMode.LATEST]?.anchorFolderKey)
    }
}
