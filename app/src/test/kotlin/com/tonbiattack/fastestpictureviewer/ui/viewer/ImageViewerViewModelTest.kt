package com.tonbiattack.fastestpictureviewer.ui.viewer

import androidx.lifecycle.SavedStateHandle
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

/** ビューアのナビ引数復元テスト (String/Int の型差異で落ちないこと) */
@OptIn(ExperimentalCoroutinesApi::class)
class ImageViewerViewModelTest {

    private class FakeRepository(
        private val images: List<ImageItem>,
        private val favoriteImages: List<ImageItem> = emptyList()
    ) : GalleryRepository {
        override val syncStatus: StateFlow<SyncStatus> = MutableStateFlow(SyncStatus.IDLE)
        override fun observeFolderSummaries(): Flow<List<FolderSummaryRow>> = MutableStateFlow(emptyList())
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
}
