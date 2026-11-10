package com.tonbiattack.fastestpictureviewer.ui.folder

import androidx.lifecycle.SavedStateHandle
import com.tonbiattack.fastestpictureviewer.core.util.FolderKeys
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
        val vm = FolderListViewModel(repo, SavedStateHandle())
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
        val vm = FolderListViewModel(repo, SavedStateHandle())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

        repo.statusFlow.value = SyncStatus(isRunning = true, isInitialScan = true)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.showInitialScanProgress)
    }

    @Test
    fun `同期失敗メッセージは一覧とは別に保持される`() = runTest {
        val repo = FakeRepository()
        val vm = FolderListViewModel(repo, SavedStateHandle())
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
        val vm = FolderListViewModel(repo, SavedStateHandle())
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
    fun `スクロール位置はSavedStateHandleを通じて保存と復元ができる`() = runTest {
        val handle = SavedStateHandle(mapOf(folderListScrollKey to 7))
        val vm = FolderListViewModel(FakeRepository(), handle)
        assertEquals(7, vm.initialScrollIndex())

        vm.saveScrollIndex(42)
        assertEquals(42, vm.initialScrollIndex())
    }

    private companion object {
        const val folderListScrollKey = "folderListScrollIndex"
    }
}
