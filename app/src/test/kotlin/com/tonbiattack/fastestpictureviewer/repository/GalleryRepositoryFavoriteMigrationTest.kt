package com.tonbiattack.fastestpictureviewer.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tonbiattack.fastestpictureviewer.data.AppDatabase
import com.tonbiattack.fastestpictureviewer.data.FavoriteImageEntity
import com.tonbiattack.fastestpictureviewer.data.ImageItem
import com.tonbiattack.fastestpictureviewer.data.MediaStoreScanner
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 移動した写真のお気に入りが同期で引き継がれることのテスト (Issue #94)。
 *
 * 実際の流れ: SD へ写真を移す → MediaStore の ID が変わる →
 * 全件走査で古い行が削除される。このときお気に入りが消えないことを確認する。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class GalleryRepositoryFavoriteMigrationTest {

    private lateinit var db: AppDatabase
    private lateinit var context: Context

    private class FakeScanner(
        context: Context,
        private val items: List<ImageItem>
    ) : MediaStoreScanner(context) {
        override fun volumes(): List<String> = listOf("v")
        override fun mountedVolumes(): Set<String> = setOf("v")
        override fun generation(volumeName: String): Long? = 1L
        override suspend fun scanBatched(
            volumeName: String,
            modifiedAfterSec: Long?,
            batchSize: Int,
            onBatch: suspend (List<ImageItem>) -> Unit
        ) {
            if (items.isNotEmpty()) onBatch(items)
        }
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = AppDatabase.buildInMemory(context)
    }

    @After
    fun teardown() {
        db.close()
    }

    private fun item(
        id: String,
        name: String = "IMG_0001.jpg",
        size: Long = 1000L,
        modified: Long = 500L
    ): ImageItem = ImageItem(
        id = id,
        mediaId = id.hashCode().toLong(),
        volumeName = "v",
        uri = id,
        displayName = name,
        sortName = name.lowercase(),
        relativePath = "DCIM/Camera/",
        folderKey = "v:DCIM/Camera/",
        folderName = "Camera",
        dateModifiedSec = modified,
        size = size,
        mimeType = "image/jpeg",
        width = 10,
        height = 10,
        naturalSortKey = ""
    )

    private fun repository(items: List<ImageItem>, now: Long) = GalleryRepositoryImpl(
        db = db,
        scanner = FakeScanner(context, items),
        nowSeconds = { now },
        mountedVolumesProvider = { setOf("v") }
    )

    @Test
    fun `移動した写真のお気に入りが引き継がれる`() = runTest {
        val oldId = "content://media/v/images/1"
        val newId = "content://media/v/images/2"

        // 移動前: 旧 ID をお気に入りに登録
        repository(listOf(item(oldId)), now = 1000L).sync(SyncMode.FULL, canDelete = true)
        db.favoriteImageDao().add(FavoriteImageEntity(itemId = oldId, addedAtSec = 900L))

        // 移動後: 同じ名前・サイズ・更新日時で ID だけ変わる
        val result = repository(listOf(item(newId)), now = 2000L)
            .sync(SyncMode.FULL, canDelete = true)

        assertEquals(1, result.removedCount)
        // 旧 ID のお気に入りが消え、新 ID に移っている
        assertEquals(0L, db.favoriteImageDao().exists(oldId))
        assertEquals(1L, db.favoriteImageDao().exists(newId))
    }

    @Test
    fun `候補が複数あるときは引き継がない`() = runTest {
        val oldId = "content://media/v/images/1"
        repository(listOf(item(oldId)), now = 1000L).sync(SyncMode.FULL, canDelete = true)
        db.favoriteImageDao().add(FavoriteImageEntity(itemId = oldId, addedAtSec = 900L))

        // 同じ名前・サイズ・更新日時の行が 2 件できる（取り違えの危険）
        val result = repository(
            listOf(
                item("content://media/v/images/2"),
                item("content://media/v/images/3")
            ),
            now = 2000L
        ).sync(SyncMode.FULL, canDelete = true)

        assertEquals(1, result.removedCount)
        // どちらにも付けない（誤った写真に付けるより安全）
        assertEquals(0L, db.favoriteImageDao().exists("content://media/v/images/2"))
        assertEquals(0L, db.favoriteImageDao().exists("content://media/v/images/3"))
    }

    @Test
    fun `削除された写真のお気に入りは残らない`() = runTest {
        val oldId = "content://media/v/images/1"
        repository(listOf(item(oldId)), now = 1000L).sync(SyncMode.FULL, canDelete = true)
        db.favoriteImageDao().add(FavoriteImageEntity(itemId = oldId, addedAtSec = 900L))

        // 同名の行が別の場所にも無い（本当に削除された）
        repository(listOf(item("content://media/v/images/9", name = "other.jpg")), now = 2000L)
            .sync(SyncMode.FULL, canDelete = true)

        // 孤立したお気に入りは残らない（一覧に出ないため実害は無いが、掃除する）
        assertEquals(0L, db.favoriteImageDao().exists(oldId))
    }
}
