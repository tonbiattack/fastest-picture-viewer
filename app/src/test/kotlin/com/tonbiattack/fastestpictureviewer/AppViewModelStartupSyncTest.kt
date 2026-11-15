package com.tonbiattack.fastestpictureviewer

import com.tonbiattack.fastestpictureviewer.core.permission.MediaAccessLevel
import com.tonbiattack.fastestpictureviewer.repository.GalleryRepository
import com.tonbiattack.fastestpictureviewer.repository.SyncMode
import com.tonbiattack.fastestpictureviewer.repository.SyncResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * 起動時の同期開始タイミングのテスト (Issue #98)。
 *
 * 同期は MediaStore の全件走査でディスクを占有する。起動直後に走らせると、
 * フォルダ一覧の初回クエリ（35 万行の集計）がその後ろで待たされ、
 * 実機で 5〜10 秒かかっていた。一覧の初回フレームが出るまで開始しない。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelStartupSyncTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `一覧が描画されるまで同期を開始しない`() = runTest(dispatcher) {
        val repository = RecordingRepository()
        val viewModel = AppViewModel(repository.proxy)

        viewModel.onAccessLevelChanged(MediaAccessLevel.Full)
        advanceUntilIdle()

        assertEquals("描画前は同期しない", 0, repository.syncCalls)

        viewModel.onFolderListDrawn()
        advanceUntilIdle()

        assertEquals("描画後に 1 回だけ同期する", 1, repository.syncCalls)
    }

    @Test
    fun `描画が先でも同期は 1 回だけ実行される`() = runTest(dispatcher) {
        val repository = RecordingRepository()
        val viewModel = AppViewModel(repository.proxy)

        // 描画 → 権限許可の順（復帰時などはこの順になりうる）
        viewModel.onFolderListDrawn()
        advanceUntilIdle()
        assertEquals("待機中の要求が無ければ同期しない", 0, repository.syncCalls)

        viewModel.onAccessLevelChanged(MediaAccessLevel.Full)
        advanceUntilIdle()

        assertEquals(1, repository.syncCalls)
    }

    @Test
    fun `描画後の権限変化で二重に同期しない`() = runTest(dispatcher) {
        val repository = RecordingRepository()
        val viewModel = AppViewModel(repository.proxy)

        viewModel.onAccessLevelChanged(MediaAccessLevel.Full)
        viewModel.onFolderListDrawn()
        advanceUntilIdle()

        // 2 回目の描画通知は無視される
        viewModel.onFolderListDrawn()
        advanceUntilIdle()

        assertEquals(1, repository.syncCalls)
    }

    @Test
    fun `権限が拒否されている場合は同期しない`() = runTest(dispatcher) {
        val repository = RecordingRepository()
        val viewModel = AppViewModel(repository.proxy)

        viewModel.onAccessLevelChanged(MediaAccessLevel.Denied)
        viewModel.onFolderListDrawn()
        advanceUntilIdle()

        assertEquals(0, repository.syncCalls)
    }

    @Test
    fun `部分アクセスでも描画後に同期し削除判定は無効になる`() = runTest(dispatcher) {
        val repository = RecordingRepository()
        val viewModel = AppViewModel(repository.proxy)

        viewModel.onAccessLevelChanged(MediaAccessLevel.Partial)
        viewModel.onFolderListDrawn()
        advanceUntilIdle()

        assertEquals(1, repository.syncCalls)
        assertTrue("部分アクセスでは削除判定を無効にする", repository.lastCanDelete == false)
    }

    /**
     * 同期の呼び出し回数だけを記録するリポジトリ。
     *
     * [GalleryRepository] はメソッドが多いため、Proxy で未実装メソッドを
     * 既定値（null / Unit / 空の Flow）に落とし、[sync] だけ差し替える。
     */
    private class RecordingRepository {
        var syncCalls = 0
        var lastCanDelete: Boolean? = null

        val proxy: GalleryRepository = Proxy.newProxyInstance(
            GalleryRepository::class.java.classLoader,
            arrayOf(GalleryRepository::class.java)
        ) { _, method, args ->
            when (method.name) {
                "sync" -> {
                    syncCalls++
                    lastCanDelete = args?.get(1) as? Boolean
                    SyncResult(
                        addedCount = 0,
                        updatedCount = 0,
                        removedCount = 0,
                        failedVolumes = emptyList()
                    )
                }
                else -> defaultValue(method.returnType)
            }
        } as GalleryRepository

        private fun defaultValue(type: Class<*>): Any? = when {
            type == Boolean::class.javaPrimitiveType -> false
            type == Int::class.javaPrimitiveType -> 0
            type == Long::class.javaPrimitiveType -> 0L
            type == Float::class.javaPrimitiveType -> 0f
            type == Double::class.javaPrimitiveType -> 0.0
            type.isAssignableFrom(kotlinx.coroutines.flow.Flow::class.java) ->
                kotlinx.coroutines.flow.MutableStateFlow<Any?>(null)
            type == Unit::class.java -> Unit
            else -> null
        }
    }
}
