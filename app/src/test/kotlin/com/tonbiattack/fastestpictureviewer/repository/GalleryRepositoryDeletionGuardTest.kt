package com.tonbiattack.fastestpictureviewer.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tonbiattack.fastestpictureviewer.data.AppDatabase
import com.tonbiattack.fastestpictureviewer.data.ImageItem
import com.tonbiattack.fastestpictureviewer.data.MediaStoreScanner
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 走査が 0 件を返したときに実在画像を消さないことのテスト (Issue #92)。
 *
 * `MediaStoreScanner` はカーソルが null でも例外を出さず 0 件を返す。
 * 実機では SD カードが一時的に読めない場面でこれが起きる。
 * 同期処理まで含めて、行が消えないことを確認する。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class GalleryRepositoryDeletionGuardTest {

    private lateinit var db: AppDatabase
    private lateinit var context: Context

    /** 指定した結果だけを返すスキャナ */
    private class FakeScanner(
        context: Context,
        private val items: List<ImageItem>,
        private val readable: Boolean = true
    ) : MediaStoreScanner(context) {
        override fun volumes(): List<String> = listOf("test-volume")
        override fun mountedVolumes(): Set<String> = if (readable) setOf("test-volume") else emptySet()
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

    private fun item(id: Long): ImageItem = ImageItem(
        id = "content://media/test-volume/images/$id",
        mediaId = id,
        volumeName = "test-volume",
        uri = "content://media/test-volume/images/$id",
        displayName = "IMG_$id.jpg",
        sortName = "img_$id.jpg",
        relativePath = "DCIM/Camera/",
        folderKey = "test-volume:DCIM/Camera/",
        folderName = "Camera",
        dateModifiedSec = 1000L,
        size = 100L,
        mimeType = "image/jpeg",
        width = 100,
        height = 100,
        naturalSortKey = ""
    )

    @Test
    fun `走査が0件を返しても既存の行を削除しない`() = runTest {
        // 1 回目: 正常に 5 件を取り込む
        val populated = GalleryRepositoryImpl(
            db = db,
            scanner = FakeScanner(context, (1L..5L).map(::item)),
            nowSeconds = { 1000L },
            mountedVolumesProvider = { setOf("test-volume") }
        )
        populated.sync(SyncMode.FULL, canDelete = true)
        assertEquals(5L, db.imageItemDao().count())

        // 2 回目: 走査が 0 件を返す（カーソル null 相当）。削除されてはいけない
        val empty = GalleryRepositoryImpl(
            db = db,
            scanner = FakeScanner(context, emptyList()),
            nowSeconds = { 2000L },
            mountedVolumesProvider = { setOf("test-volume") }
        )
        val result = empty.sync(SyncMode.FULL, canDelete = true)

        assertEquals("実在する行が削除された", 5L, db.imageItemDao().count())
        assertEquals("削除件数が 0 でない", 0, result.removedCount)
    }

    @Test
    fun `ボリュームが読めないときは削除しない`() = runTest {
        val populated = GalleryRepositoryImpl(
            db = db,
            scanner = FakeScanner(context, (1L..5L).map(::item)),
            nowSeconds = { 1000L },
            mountedVolumesProvider = { setOf("test-volume") }
        )
        populated.sync(SyncMode.FULL, canDelete = true)

        // 走査は 1 件だけ返すが、削除直前に読めない状態になる
        val unreadable = GalleryRepositoryImpl(
            db = db,
            scanner = FakeScanner(context, listOf(item(1)), readable = false),
            nowSeconds = { 2000L },
            mountedVolumesProvider = { emptySet() }
        )
        val result = unreadable.sync(SyncMode.FULL, canDelete = true)

        assertEquals("読めない状態で削除された", 5L, db.imageItemDao().count())
        assertEquals(0, result.removedCount)
    }

    @Test
    fun `通常の削除は反映される`() = runTest {
        val populated = GalleryRepositoryImpl(
            db = db,
            scanner = FakeScanner(context, (1L..5L).map(::item)),
            nowSeconds = { 1000L },
            mountedVolumesProvider = { setOf("test-volume") }
        )
        populated.sync(SyncMode.FULL, canDelete = true)

        // 1 件だけ消えた状態。これは通常どおり反映する
        val shrunk = GalleryRepositoryImpl(
            db = db,
            scanner = FakeScanner(context, (2L..5L).map(::item)),
            nowSeconds = { 2000L },
            mountedVolumesProvider = { setOf("test-volume") }
        )
        val result = shrunk.sync(SyncMode.FULL, canDelete = true)

        assertEquals(4L, db.imageItemDao().count())
        assertEquals(1, result.removedCount)
    }

    @Test
    fun `見送ったときは案内を出す`() = runTest {
        val populated = GalleryRepositoryImpl(
            db = db,
            scanner = FakeScanner(context, (1L..5L).map(::item)),
            nowSeconds = { 1000L },
            mountedVolumesProvider = { setOf("test-volume") }
        )
        populated.sync(SyncMode.FULL, canDelete = true)

        val empty = GalleryRepositoryImpl(
            db = db,
            scanner = FakeScanner(context, emptyList()),
            nowSeconds = { 2000L },
            mountedVolumesProvider = { setOf("test-volume") }
        )
        empty.sync(SyncMode.FULL, canDelete = true)

        val message = empty.syncStatus.value.errorMessage
        assertTrue("案内が出ていない", message != null && message.isNotEmpty())
        assertTrue("個人情報を含む", "/storage" !in message!! && "DCIM" !in message)
    }
}
