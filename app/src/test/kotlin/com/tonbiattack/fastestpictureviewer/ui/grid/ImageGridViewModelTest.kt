package com.tonbiattack.fastestpictureviewer.ui.grid

import androidx.lifecycle.SavedStateHandle
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
import com.tonbiattack.fastestpictureviewer.repository.GalleryRepository
import com.tonbiattack.fastestpictureviewer.repository.SyncMode
import com.tonbiattack.fastestpictureviewer.repository.SyncResult
import com.tonbiattack.fastestpictureviewer.repository.StaleCleanupResult
import com.tonbiattack.fastestpictureviewer.repository.SyncStatus
import com.tonbiattack.fastestpictureviewer.repository.UnreadableFolderSummary
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 画像グリッドの列数設定と詳細復帰位置のテスト */
@OptIn(ExperimentalCoroutinesApi::class)
class ImageGridViewModelTest {

    private class FakeViewSettings(initial: Int = 3) : ImageGridViewSettings {
        private val _columnCount = MutableStateFlow(initial)
        override val columnCount: StateFlow<Int> = _columnCount

        private val _portrait = MutableStateFlow(initial)
        override val portraitColumnCount: StateFlow<Int> = _portrait

        private val _landscape = MutableStateFlow(initial)
        override val landscapeColumnCount: StateFlow<Int> = _landscape

        private var isLandscape = false

        override fun setColumnCount(count: Int) {
            val clamped = count.coerceIn(2, 6)
            _columnCount.value = clamped
            if (isLandscape) _landscape.value = clamped else _portrait.value = clamped
        }

        override fun setLandscape(isLandscape: Boolean) {
            this.isLandscape = isLandscape
            _columnCount.value = if (isLandscape) _landscape.value else _portrait.value
        }
    }

    private class FakeRepository(private val images: List<ImageItem>) : GalleryRepository {
        override val syncStatus: StateFlow<SyncStatus> = MutableStateFlow(SyncStatus.IDLE)
        override fun observeFolderSummaries(): Flow<List<FolderSummaryRow>> = MutableStateFlow(emptyList())
        override suspend fun rebuildFolderSummaryCache() = Unit

        override fun observeRepresentatives(): Flow<List<RepresentativeImageRow>> = MutableStateFlow(emptyList())
        override fun observeImages(folderKey: String): Flow<List<ImageItem>> = MutableStateFlow(images)
        private val favoritesFlow = MutableStateFlow<List<FavoriteImageEntity>>(emptyList())
        override fun observeFavorites(): Flow<List<FavoriteImageEntity>> = favoritesFlow
        override fun observeExcludedFolders(): Flow<List<ExcludedFolderEntity>> = MutableStateFlow(emptyList())
        override fun observeFavoriteFolders(): Flow<List<FavoriteFolderEntity>> = MutableStateFlow(emptyList())
        override fun observeFavoriteImages(folderKey: String?): Flow<List<ImageItem>> = MutableStateFlow(emptyList())
        override fun observeRecentFolders(): Flow<List<RecentFolderEntity>> = MutableStateFlow(emptyList())
        override suspend fun sync(mode: SyncMode, canDelete: Boolean): SyncResult =
            SyncResult(0, 0, 0, emptyList())
        override suspend fun setFavorite(itemId: String, favorite: Boolean) {
            favoritesFlow.value = if (favorite) {
                favoritesFlow.value.filterNot { it.itemId == itemId } +
                    FavoriteImageEntity(itemId = itemId, addedAtSec = 1L)
            } else {
                favoritesFlow.value.filterNot { it.itemId == itemId }
            }
        }
        override suspend fun excludeFolder(volumeName: String, relativePath: String) = Unit
        override suspend fun includeFolder(folderKey: String) = Unit
        override suspend fun setFolderFavorite(
            folderKey: String,
            volumeName: String,
            relativePath: String,
            favorite: Boolean
        ) = Unit
        override suspend fun markFolderOpened(folderKey: String) = Unit
        override suspend fun getScrollPosition(folderKey: String): FolderScrollPositionEntity? = null
        override suspend fun saveScrollPosition(
            folderKey: String,
            anchorItemId: String?,
            anchorIndex: Int,
            anchorOffsetPx: Int
        ) = Unit
        override suspend fun getFolderListScrollPosition(
            sortMode: FolderSortMode
        ): FolderListScrollPositionEntity? = null
        override suspend fun saveFolderListScrollPosition(
            sortMode: FolderSortMode,
            anchorFolderKey: String?,
            anchorIndex: Int,
            anchorOffsetPx: Int
        ) = Unit
        override fun observeTags(): Flow<List<TagEntity>> = MutableStateFlow(emptyList())
        override fun observeTagsWithCount(): Flow<List<TagWithCount>> = MutableStateFlow(emptyList())
        override fun observeFolderTagNames(): Flow<List<FolderTagNameRow>> = MutableStateFlow(emptyList())
        override suspend fun findOrCreateTag(rawName: String): TagEntity? = null
        override suspend fun setFolderTags(folderKey: String, tagIds: Set<Long>) = Unit
        override suspend fun addTagsToFolders(tagIds: Set<Long>, folderKeys: Collection<String>) = Unit
        override suspend fun renameTag(tagId: Long, rawName: String): Boolean = true
        override suspend fun deleteTag(tagId: Long) = Unit
        override suspend fun backfillMissingNaturalSortKeys() = Unit

        override suspend fun verifyFolderReadability(folderKey: String, limit: Int): Int = 0

        val verifyCalls = mutableListOf<String>()
        var verifyResult = 0

        override suspend fun verifyFolderFromThumbnailFailure(folderKey: String): Int {
            verifyCalls += folderKey
            return verifyResult
        }

        /** Issue #77: 画像単位の確認。呼ばれた画像 ID を記録する */
        val verifyImageCalls = mutableListOf<String>()
        var verifyImageResult = false

        override suspend fun verifyImageReadable(item: ImageItem): Boolean {
            verifyImageCalls += item.id
            return verifyImageResult
        }

        override suspend fun clearUnreadableImages() = Unit

        override suspend fun validateAllFolders(): Int = 0

        // Issue #80: 実体の無い行の整理。テストでは何もしない
        override suspend fun getUnreadableFolderSummary(): List<UnreadableFolderSummary> =
            emptyList()

        override suspend fun cleanupStaleEntries(dryRun: Boolean): StaleCleanupResult =
            StaleCleanupResult(0, 0, 0)
    }

    private fun images(count: Int): List<ImageItem> = (0 until count).map { index ->
        ImageItem(
            id = "id$index",
            mediaId = index.toLong(),
            volumeName = "v",
            uri = "content://image/$index",
            displayName = "$index.jpg",
            sortName = "$index.jpg",
            relativePath = "Pictures/",
            folderKey = "v:Pictures/",
            folderName = "Pictures",
            dateModifiedSec = index.toLong(),
            size = 1L,
            mimeType = "image/jpeg",
            width = null,
            height = null
        )
    }

    @Before
    fun setup() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(
        settings: ImageGridViewSettings = FakeViewSettings(),
        items: List<ImageItem> = images(5)
    ): ImageGridViewModel = ImageGridViewModel(
        repository = FakeRepository(items),
        savedStateHandle = SavedStateHandle(mapOf("folderKey" to "v:Pictures/")),
        viewSettings = settings
    )

    @Test
    fun `列数設定がUI状態に反映され変更できる`() = runTest {
        val settings = FakeViewSettings(initial = 4)
        val vm = viewModel(settings = settings)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        advanceUntilIdle()
        assertEquals(4, vm.uiState.value.columnCount)

        vm.setColumnCount(6)
        advanceUntilIdle()
        assertEquals(6, vm.uiState.value.columnCount)
        assertEquals(6, settings.columnCount.value)
    }

    @Test
    fun `ファイル名検索で現在のフォルダ内だけを絞り込める`() = runTest {
        val vm = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.onSearchQueryChange("3")
        advanceUntilIdle()
        val filtered = vm.uiState.value
        assertEquals(listOf("id3"), filtered.images.map { it.id })
        assertEquals(5, filtered.allImages.size)
        assertTrue(filtered.isFiltered)

        vm.clearSearch()
        advanceUntilIdle()
        assertEquals(5, vm.uiState.value.images.size)
        assertTrue(!vm.uiState.value.isFiltered)
    }

    @Test
    fun `お気に入りのみ表示で絞り込める`() = runTest {
        val vm = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.toggleFavorite("id1", currentFavorite = false)
        advanceUntilIdle()
        vm.toggleFavoritesOnly()
        advanceUntilIdle()
        assertEquals(listOf("id1"), vm.uiState.value.images.map { it.id })

        vm.toggleFavoritesOnly()
        advanceUntilIdle()
        assertEquals(5, vm.uiState.value.images.size)
    }

    @Test
    fun `フィルタ中の詳細復帰ではスクロール先を設定しない`() = runTest {
        val vm = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.onSearchQueryChange("1")
        advanceUntilIdle()
        vm.onReturnedFromViewer("id0", fallbackIndex = 0)
        advanceUntilIdle()
        assertNull(vm.uiState.value.scrollTarget)
    }

    @Test
    fun `詳細から戻った画像IDがスクロール先になる`() = runTest {
        val vm = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.onReturnedFromViewer("id3", fallbackIndex = 3)
        advanceUntilIdle()
        val target = vm.uiState.value.scrollTarget
        assertEquals("id3", target?.itemId)
        assertEquals(3, target?.fallbackIndex)
        assertEquals(1L, vm.uiState.value.returnRequestId)

        vm.consumeScrollTarget(target!!.requestId)
        advanceUntilIdle()
        assertNull(vm.uiState.value.scrollTarget)
        // 既存グリッドを再生成しすぎないよう、消費後も世代は維持する
        assertEquals(1L, vm.uiState.value.returnRequestId)
    }

    @Test
    fun `既に画面内の画像へ戻る場合は位置を動かさない`() = runTest {
        val vm = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.onVisibleRangeChanged(firstIndex = 0, lastIndex = 10, offsetPx = 0)
        advanceUntilIdle()
        vm.onReturnedFromViewer("id3", fallbackIndex = 3)
        advanceUntilIdle()

        assertNull(vm.uiState.value.scrollTarget)
        assertEquals(0L, vm.uiState.value.returnRequestId)
    }

    @Test
    fun `削除済み画像でもフォールバック位置を返す`() = runTest {
        val vm = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.onReturnedFromViewer("deleted", fallbackIndex = 99)
        advanceUntilIdle()
        val target = vm.uiState.value.scrollTarget!!
        val resolved = ScrollPositionResolver.resolve(
            images = vm.uiState.value.images,
            anchorItemId = target.itemId,
            fallbackIndex = target.fallbackIndex
        )
        assertEquals(4, resolved)
    }

    // ---- Issue #77: 読み込みに失敗した画像の扱い ----
    @Test
    fun `読み込み失敗はその画像だけ実体確認する`() = runTest {
        val repo = FakeRepository(images(5))
        val vm = ImageGridViewModel(
            repository = repo,
            savedStateHandle = SavedStateHandle(mapOf("folderKey" to "v:Pictures/")),
            viewSettings = FakeViewSettings()
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        advanceUntilIdle()

        val items = vm.uiState.value.images
        vm.onImageLoadError(items[0])
        vm.onImageLoadError(items[2])
        advanceUntilIdle()

        // フォルダ全体ではなく、失敗した画像だけを確認する
        assertEquals(listOf("id0", "id2"), repo.verifyImageCalls)
        assertTrue(repo.verifyCalls.isEmpty())
    }

    @Test
    fun `同じ画像の失敗は1回だけ確認する`() = runTest {
        val repo = FakeRepository(images(5))
        val vm = ImageGridViewModel(
            repository = repo,
            savedStateHandle = SavedStateHandle(mapOf("folderKey" to "v:Pictures/")),
            viewSettings = FakeViewSettings()
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        advanceUntilIdle()

        val first = vm.uiState.value.images.first()
        repeat(3) { vm.onImageLoadError(first) }
        advanceUntilIdle()

        assertEquals(listOf("id0"), repo.verifyImageCalls)
    }

    @Test
    fun `読めた画像は次回の失敗で再確認できる`() = runTest {
        val repo = FakeRepository(images(5))
        val vm = ImageGridViewModel(
            repository = repo,
            savedStateHandle = SavedStateHandle(mapOf("folderKey" to "v:Pictures/")),
            viewSettings = FakeViewSettings()
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        advanceUntilIdle()

        val first = vm.uiState.value.images.first()
        repo.verifyImageResult = false
        vm.onImageLoadError(first)
        advanceUntilIdle()
        vm.onImageLoadError(first)
        advanceUntilIdle()

        assertEquals(2, repo.verifyImageCalls.size)
    }

    @Test
    fun `実体確認の例外で一覧が壊れない`() = runTest {
        val base = FakeRepository(images(5))
        val repo = object : GalleryRepository by base {
            override suspend fun verifyImageReadable(item: ImageItem): Boolean =
                throw IllegalStateException("probe failed")
        }
        val vm = ImageGridViewModel(
            repository = repo,
            savedStateHandle = SavedStateHandle(mapOf("folderKey" to "v:Pictures/")),
            viewSettings = FakeViewSettings()
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.onImageLoadError(vm.uiState.value.images.first())
        advanceUntilIdle()

        assertEquals(5, vm.uiState.value.images.size)
    }
}
