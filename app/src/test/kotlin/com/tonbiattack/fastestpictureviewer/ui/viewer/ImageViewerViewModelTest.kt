package com.tonbiattack.fastestpictureviewer.ui.viewer

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
import com.tonbiattack.fastestpictureviewer.ui.navigation.FolderSequenceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** ビューアのナビ引数復元テスト (String/Int の型差異で落ちないこと) */
@OptIn(ExperimentalCoroutinesApi::class)
class ImageViewerViewModelTest {

    private class FakeRepository(
        private val images: List<ImageItem>,
        private val favoriteImages: List<ImageItem> = emptyList()
    ) : GalleryRepository {
        override val syncStatus: StateFlow<SyncStatus> = MutableStateFlow(SyncStatus.IDLE)
        override fun observeFolderSummaries(): Flow<List<FolderSummaryRow>> = MutableStateFlow(emptyList())
        override suspend fun rebuildFolderSummaryCache() = Unit

        override fun observeRepresentatives(): Flow<List<RepresentativeImageRow>> = MutableStateFlow(emptyList())
        override fun observeImages(folderKey: String): Flow<List<ImageItem>> = MutableStateFlow(images)

        private val favoritesFlow = MutableStateFlow<List<FavoriteImageEntity>>(emptyList())
        override fun observeFavorites(): Flow<List<FavoriteImageEntity>> = favoritesFlow
        override suspend fun setFavorite(itemId: String, favorite: Boolean) {
            favoritesFlow.value = if (favorite) {
                favoritesFlow.value.filterNot { it.itemId == itemId } +
                    FavoriteImageEntity(itemId = itemId, addedAtSec = 1L)
            } else {
                favoritesFlow.value.filterNot { it.itemId == itemId }
            }
        }

        override suspend fun sync(mode: SyncMode, canDelete: Boolean): SyncResult =
            SyncResult(0, 0, 0, emptyList())
        override fun observeExcludedFolders(): Flow<List<ExcludedFolderEntity>> = MutableStateFlow(emptyList())
        override suspend fun excludeFolder(volumeName: String, relativePath: String) = Unit
        override suspend fun includeFolder(folderKey: String) = Unit
        override fun observeFavoriteFolders(): Flow<List<FavoriteFolderEntity>> = MutableStateFlow(emptyList())
        override suspend fun setFolderFavorite(
            folderKey: String,
            volumeName: String,
            relativePath: String,
            favorite: Boolean
        ) = Unit
        override fun observeFavoriteImages(folderKey: String?): Flow<List<ImageItem>> =
            MutableStateFlow(
                if (folderKey == null) favoriteImages
                else favoriteImages.filter { it.folderKey == folderKey }
            )
        override fun observeRecentFolders(): Flow<List<RecentFolderEntity>> = MutableStateFlow(emptyList())
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

        override suspend fun verifyImageReadable(item: ImageItem): Boolean = false

        override suspend fun verifyFolderFromThumbnailFailure(folderKey: String): Int = 0

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
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `startIndexがStringでも数値として復元できる`() = runTest {
        // Navigation の引数型を宣言していない場合、String で入ることがある
        val handle = SavedStateHandle(
            mapOf("folderKey" to "v:Pictures/", "startIndex" to "3")
        )
        val vm = ImageViewerViewModel(FakeRepository(images(5)), handle)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

        assertEquals(3, vm.uiState.value.initialIndex)
    }

    @Test
    fun `startIndexがIntでも復元できる`() = runTest {
        val handle = SavedStateHandle(
            mapOf("folderKey" to "v:Pictures/", "startIndex" to 2)
        )
        val vm = ImageViewerViewModel(FakeRepository(images(5)), handle)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

        assertEquals(2, vm.uiState.value.initialIndex)
    }

    @Test
    fun `画像のお気に入りをワンタップで切り替えられる`() = runTest {
        val repo = FakeRepository(images(3))
        val handle = SavedStateHandle(
            mapOf("folderKey" to "v:Pictures/", "startIndex" to 0)
        )
        val vm = ImageViewerViewModel(repo, handle)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        advanceUntilIdle()

        val itemId = vm.uiState.value.images.first().id
        assertFalse(vm.uiState.value.favoriteIds.contains(itemId))

        vm.toggleFavorite(itemId, false)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.favoriteIds.contains(itemId))

        vm.toggleFavorite(itemId, true)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.favoriteIds.contains(itemId))
    }

    @Test
    fun `お気に入りビューアはお気に入り画像だけを対象にする`() = runTest {
        val all = images(5)
        val favorites = listOf(all[1], all[3])
        val handle = SavedStateHandle(
            mapOf(
                "folderKey" to ImageViewerViewModel.ALL_FAVORITES,
                "startIndex" to "1",
                "favoritesOnly" to "true"
            )
        )
        val vm = ImageViewerViewModel(FakeRepository(all, favorites), handle)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        advanceUntilIdle()

        assertEquals(listOf(all[1].id, all[3].id), vm.uiState.value.images.map { it.id })
        assertEquals(1, vm.uiState.value.initialIndex)
    }

    @Test
    fun `範囲外のstartIndexは末尾に丸められる`() = runTest {
        val handle = SavedStateHandle(
            mapOf("folderKey" to "v:Pictures/", "startIndex" to "99")
        )
        val vm = ImageViewerViewModel(FakeRepository(images(5)), handle)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

        assertEquals(4, vm.uiState.value.initialIndex)
    }

    @Test
    fun `ランダムジャンプ先は現在の画像と異なる有効なインデックスになる`() = runTest {
        val vm = ImageViewerViewModel(FakeRepository(images(8)), SavedStateHandle(mapOf("folderKey" to "v:Pictures/")))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        advanceUntilIdle()

        repeat(50) {
            val target = vm.randomJumpTarget(currentIndex = 3)
            assertNotNull(target)
            assertTrue("range: $target", target!! in 0 until 8)
            assertNotEquals(3, target)
        }
    }

    @Test
    fun `画像が1枚ならランダムジャンプ先は同じ画像`() = runTest {
        val vm = ImageViewerViewModel(FakeRepository(images(1)), SavedStateHandle(mapOf("folderKey" to "v:Pictures/")))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        advanceUntilIdle()

        assertEquals(0, vm.randomJumpTarget(currentIndex = 0))
    }

    @Test
    fun `画像が空ならランダムジャンプ先はnull`() = runTest {
        val vm = ImageViewerViewModel(FakeRepository(emptyList()), SavedStateHandle(mapOf("folderKey" to "v:Pictures/")))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        advanceUntilIdle()

        assertNull(vm.randomJumpTarget(currentIndex = 0))
    }

    @Test
    fun `前後フォルダは一覧の並びから決まる`() = runTest {
        val store = FolderSequenceStore()
        store.update(listOf("v:A/", "v:Pictures/", "v:C/"))
        val vm = ImageViewerViewModel(
            FakeRepository(images(3)),
            SavedStateHandle(mapOf("folderKey" to "v:Pictures/")),
            store
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.adjacentFolders.collect {} }
        advanceUntilIdle()

        assertEquals("v:A/", vm.adjacentFolders.value.previous)
        assertEquals("v:C/", vm.adjacentFolders.value.next)
    }

    @Test
    fun `一覧に無いフォルダでは前後移動を出さない`() = runTest {
        val store = FolderSequenceStore()
        store.update(listOf("v:A/", "v:C/"))
        val vm = ImageViewerViewModel(
            FakeRepository(images(3)),
            SavedStateHandle(mapOf("folderKey" to "v:Pictures/")),
            store
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.adjacentFolders.collect {} }
        advanceUntilIdle()

        assertFalse(vm.adjacentFolders.value.hasAny)
    }

    @Test
    fun `お気に入りビューアでは前後フォルダ移動を出さない`() = runTest {
        val store = FolderSequenceStore()
        store.update(listOf("v:A/", "v:Pictures/", "v:C/"))
        val vm = ImageViewerViewModel(
            FakeRepository(images(3)),
            SavedStateHandle(mapOf("folderKey" to "v:Pictures/", "favoritesOnly" to true)),
            store
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.adjacentFolders.collect {} }
        advanceUntilIdle()

        assertFalse(vm.adjacentFolders.value.hasAny)
    }

    @Test
    fun `前のフォルダから来た場合は末尾の画像から開く`() = runTest {
        val handle = SavedStateHandle(
            mapOf("folderKey" to "v:Pictures/", "startIndex" to 0, "fromEnd" to true)
        )
        val vm = ImageViewerViewModel(FakeRepository(images(6)), handle)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        advanceUntilIdle()

        assertTrue(vm.uiState.value.startsWithLastImage)
        assertEquals(5, vm.uiState.value.initialIndex)
    }

    // ---- スライドショー (Issue #124) ----

    @Test
    fun `初期状態は停止している`() = runTest {
        val vm = ImageViewerViewModel(
            FakeRepository(images(5)),
            SavedStateHandle(mapOf("folderKey" to "v:Pictures/"))
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        advanceUntilIdle()

        // 画面を開いた直後に勝手に再生が始まらないこと
        assertEquals(SlideshowState.STOPPED, vm.slideshowState.value)
    }

    @Test
    fun `開始すると再生中になる`() = runTest {
        val vm = ImageViewerViewModel(
            FakeRepository(images(5)),
            SavedStateHandle(mapOf("folderKey" to "v:Pictures/"))
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.startSlideshow()

        assertEquals(SlideshowState.PLAYING, vm.slideshowState.value)
    }

    @Test
    fun `画像が1枚以下なら開始しない`() = runTest {
        val vm = ImageViewerViewModel(
            FakeRepository(images(1)),
            SavedStateHandle(mapOf("folderKey" to "v:Pictures/"))
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.startSlideshow()

        assertEquals(SlideshowState.STOPPED, vm.slideshowState.value)
    }

    @Test
    fun `画像が空なら開始しない`() = runTest {
        val vm = ImageViewerViewModel(
            FakeRepository(emptyList()),
            SavedStateHandle(mapOf("folderKey" to "v:Pictures/"))
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.startSlideshow()

        assertEquals(SlideshowState.STOPPED, vm.slideshowState.value)
    }

    @Test
    fun `再生と一時停止を切り替えられる`() = runTest {
        val vm = ImageViewerViewModel(
            FakeRepository(images(5)),
            SavedStateHandle(mapOf("folderKey" to "v:Pictures/"))
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.toggleSlideshow()
        assertEquals(SlideshowState.PLAYING, vm.slideshowState.value)

        vm.toggleSlideshow()
        assertEquals(SlideshowState.PAUSED, vm.slideshowState.value)

        vm.toggleSlideshow()
        assertEquals(SlideshowState.PLAYING, vm.slideshowState.value)
    }

    @Test
    fun `停止すると停止状態になる`() = runTest {
        val vm = ImageViewerViewModel(
            FakeRepository(images(5)),
            SavedStateHandle(mapOf("folderKey" to "v:Pictures/"))
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.startSlideshow()
        vm.stopSlideshow()

        assertEquals(SlideshowState.STOPPED, vm.slideshowState.value)
    }

    @Test
    fun `バックグラウンド移行で一時停止する`() = runTest {
        val vm = ImageViewerViewModel(
            FakeRepository(images(5)),
            SavedStateHandle(mapOf("folderKey" to "v:Pictures/"))
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.startSlideshow()
        vm.onEnterBackground()

        // 復帰時に意図せず再開しないよう、停止ではなく一時停止
        assertEquals(SlideshowState.PAUSED, vm.slideshowState.value)
    }

    @Test
    fun `停止中のバックグラウンド移行では状態が変わらない`() = runTest {
        val vm = ImageViewerViewModel(
            FakeRepository(images(5)),
            SavedStateHandle(mapOf("folderKey" to "v:Pictures/"))
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.onEnterBackground()

        assertEquals(SlideshowState.STOPPED, vm.slideshowState.value)
    }

    @Test
    fun `設定が無くても既定の間隔で動く`() = runTest {
        val vm = ImageViewerViewModel(
            FakeRepository(images(5)),
            SavedStateHandle(mapOf("folderKey" to "v:Pictures/"))
        )

        // slideshowSettings を渡さない場合でも落ちないこと
        assertEquals(SlideshowInterval.DEFAULT, vm.slideshowInterval.value)
    }
}
