package com.tonbiattack.fastestpictureviewer.ui.viewer

import androidx.lifecycle.SavedStateHandle
import com.tonbiattack.fastestpictureviewer.data.ExcludedFolderEntity
import com.tonbiattack.fastestpictureviewer.data.FavoriteImageEntity
import com.tonbiattack.fastestpictureviewer.data.FolderSummaryRow
import com.tonbiattack.fastestpictureviewer.data.ImageItem
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
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** ビューアのナビ引数復元テスト (String/Int の型差異で落ちないこと) */
@OptIn(ExperimentalCoroutinesApi::class)
class ImageViewerViewModelTest {

    private class FakeRepository(private val images: List<ImageItem>) : GalleryRepository {
        override val syncStatus: StateFlow<SyncStatus> = MutableStateFlow(SyncStatus.IDLE)
        override fun observeFolderSummaries(): Flow<List<FolderSummaryRow>> = MutableStateFlow(emptyList())
        override fun observeRepresentatives(): Flow<List<RepresentativeImageRow>> = MutableStateFlow(emptyList())
        override fun observeImages(folderKey: String): Flow<List<ImageItem>> = MutableStateFlow(images)
        override suspend fun sync(mode: SyncMode, canDelete: Boolean): SyncResult =
            SyncResult(0, 0, 0, emptyList())
        override suspend fun setFavorite(itemId: String, favorite: Boolean) = Unit
        override fun observeFavorites(): Flow<List<FavoriteImageEntity>> = MutableStateFlow(emptyList())
        override fun observeExcludedFolders(): Flow<List<ExcludedFolderEntity>> = MutableStateFlow(emptyList())
        override suspend fun excludeFolder(volumeName: String, relativePath: String) = Unit
        override suspend fun includeFolder(folderKey: String) = Unit
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
    fun `範囲外のstartIndexは末尾に丸められる`() = runTest {
        val handle = SavedStateHandle(
            mapOf("folderKey" to "v:Pictures/", "startIndex" to "99")
        )
        val vm = ImageViewerViewModel(FakeRepository(images(5)), handle)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

        assertEquals(4, vm.uiState.value.initialIndex)
    }
}
