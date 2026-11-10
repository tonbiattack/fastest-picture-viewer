package com.tonbiattack.fastestpictureviewer.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tonbiattack.fastestpictureviewer.data.AppDatabase
import com.tonbiattack.fastestpictureviewer.data.ImageItem
import com.tonbiattack.fastestpictureviewer.data.MediaStoreScanner
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 既存キャッシュ（naturalSortKey が空）の遅延バックフィルと自然順表示のテスト */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class GalleryRepositoryImageSortTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: GalleryRepositoryImpl

    private fun item(name: String, mediaId: Long) = ImageItem(
        id = "content://image/$mediaId",
        mediaId = mediaId,
        volumeName = "v",
        uri = "content://image/$mediaId",
        displayName = name,
        sortName = name.lowercase(),
        relativePath = "Pictures/A/",
        folderKey = "v:Pictures/A/",
        folderName = "A",
        dateModifiedSec = mediaId,
        size = 1L,
        mimeType = "image/jpeg",
        width = null,
        height = null,
        naturalSortKey = ""
    )

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = AppDatabase.buildInMemory(context)
        repository = GalleryRepositoryImpl(db, MediaStoreScanner(context))
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun `キーが空の既存行を開いたときに自然順へ並べ替えてバックフィルする`() = runTest {
        // わざとバラバラの順序で、キー未生成の状態を再現する
        db.imageItemDao().upsertAll(
            listOf(
                item("10_R.jpg", 10L),
                item("7_R.jpg", 7L),
                item("2_R.jpg", 2L),
                item("02_R.jpg", 20L),
                item("100_R.jpg", 100L),
                item("1_R.jpg", 1L)
            )
        )

        val sorted = repository.observeImages("v:Pictures/A/").first()
        assertEquals(
            listOf("1_R.jpg", "02_R.jpg", "2_R.jpg", "7_R.jpg", "10_R.jpg", "100_R.jpg"),
            sorted.map { it.displayName }
        )

        // バックフィル後のキーが保存されている
        val stored = db.imageItemDao().getImages("v:Pictures/A/")
        assertTrue(stored.all { it.naturalSortKey.isNotEmpty() })
    }
}
