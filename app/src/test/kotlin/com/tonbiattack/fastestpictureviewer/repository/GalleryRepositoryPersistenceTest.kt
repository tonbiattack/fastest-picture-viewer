package com.tonbiattack.fastestpictureviewer.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tonbiattack.fastestpictureviewer.data.AppDatabase
import com.tonbiattack.fastestpictureviewer.data.MediaStoreScanner
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** フォルダ別スクロール位置と最近閲覧フォルダの永続化テスト */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class GalleryRepositoryPersistenceTest {

    private lateinit var db: AppDatabase
    private var now = 100L
    private lateinit var repository: GalleryRepositoryImpl

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = AppDatabase.buildInMemory(context)
        repository = GalleryRepositoryImpl(
            db = db,
            scanner = MediaStoreScanner(context),
            nowSeconds = { now }
        )
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun `フォルダ別スクロール位置を保存して取得できる`() = runTest {
        repository.saveScrollPosition(
            folderKey = "v:Pictures/A/",
            anchorItemId = "content://image/42",
            anchorIndex = 42,
            anchorOffsetPx = 120
        )

        val saved = repository.getScrollPosition("v:Pictures/A/")
        assertEquals("content://image/42", saved?.anchorItemId)
        assertEquals(42, saved?.anchorIndex)
        assertEquals(120, saved?.anchorOffsetPx)

        repository.saveScrollPosition("v:Pictures/A/", null, 7, 0)
        val updated = repository.getScrollPosition("v:Pictures/A/")
        assertEquals(null, updated?.anchorItemId)
        assertEquals(7, updated?.anchorIndex)
    }

    @Test
    fun `最近閲覧フォルダは最後に開いた順に並ぶ`() = runTest {
        now = 100L
        repository.markFolderOpened("v:Pictures/A/")
        now = 200L
        repository.markFolderOpened("v:Pictures/B/")
        now = 300L
        repository.markFolderOpened("v:Pictures/A/")

        val recent = repository.observeRecentFolders().first()
        assertEquals(
            listOf("v:Pictures/A/", "v:Pictures/B/"),
            recent.map { it.folderKey }
        )
        assertEquals(300L, recent.first().openedAtSec)
    }
}
