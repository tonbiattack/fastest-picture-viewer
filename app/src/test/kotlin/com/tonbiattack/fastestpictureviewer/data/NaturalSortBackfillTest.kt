package com.tonbiattack.fastestpictureviewer.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tonbiattack.fastestpictureviewer.core.util.NaturalSortKey
import com.tonbiattack.fastestpictureviewer.repository.GalleryRepositoryImpl
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 旧DB (naturalSortKey 未設定) を自然順キーでバックフィルし、
 * 代表サムネイルが正しい先頭画像に揃うことのテスト (Issue #49)。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class NaturalSortBackfillTest {

    private lateinit var db: AppDatabase

    private fun item(index: Int, path: String, name: String, naturalKey: String): ImageItem =
        ImageItem(
            id = "id$index",
            mediaId = index.toLong(),
            volumeName = "v",
            uri = "id$index",
            displayName = name,
            sortName = name.lowercase(),
            relativePath = path,
            folderKey = "v:$path",
            folderName = "folder",
            dateModifiedSec = index.toLong(),
            size = 1L,
            mimeType = "image/jpeg",
            width = null,
            height = null,
            naturalSortKey = naturalKey
        )

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = AppDatabase.buildInMemory(context)
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun `未設定キーを埋めると自然順先頭が代表になる`() = runTest {
        val path = "Pictures/Legacy/"
        // 旧DBを再現: naturalSortKey は空。辞書順だと "10.jpg" < "2.jpg" になるが、
        // 自然順では 2.jpg が先頭。更新日時は 10.jpg が最新にして影響を排除する
        db.imageItemDao().upsertAll(
            listOf(
                item(3, path, "10.jpg", ""),
                item(2, path, "2.jpg", "")
            )
        )
        // バックフィル前は空キー同士の比較で辞書順 ("10.jpg") が先頭になる
        assertEquals("id3", db.imageItemDao().observeRepresentatives().first().single().id)

        val repository = GalleryRepositoryImpl(db, MediaStoreScanner(ApplicationProvider.getApplicationContext()))
        repository.backfillMissingNaturalSortKeys()

        assertEquals(0L, db.imageItemDao().countMissingNaturalSortKey())
        assertEquals("id2", db.imageItemDao().observeRepresentatives().first().single().id)
    }

    @Test
    fun `バックフィルは画像を消さずキーだけ更新する`() = runTest {
        val path = "Pictures/Keep/"
        val items = listOf(
            item(1, path, "1.jpg", ""),
            item(2, path, "2.jpg", "")
        )
        db.imageItemDao().upsertAll(items)

        val repository = GalleryRepositoryImpl(db, MediaStoreScanner(ApplicationProvider.getApplicationContext()))
        repository.backfillMissingNaturalSortKeys()

        val stored = db.imageItemDao().observeImages("v:$path").first()
        assertEquals(2, stored.size)
        assertEquals("1.jpg", stored.first().displayName)
        assertTrue(stored.all { it.naturalSortKey.isNotEmpty() })
        // 自然順キーはファイル名から再生成した値と一致する
        assertEquals(NaturalSortKey.create("1.jpg"), stored.first().naturalSortKey)
    }

    @Test
    fun `未設定キーが無ければバックフィルは何もしない`() = runTest {
        val path = "Pictures/Done/"
        db.imageItemDao().upsertAll(
            listOf(item(1, path, "1.jpg", NaturalSortKey.create("1.jpg")))
        )

        val repository = GalleryRepositoryImpl(db, MediaStoreScanner(ApplicationProvider.getApplicationContext()))
        repository.backfillMissingNaturalSortKeys()

        assertEquals(0L, db.imageItemDao().countMissingNaturalSortKey())
        assertEquals("id1", db.imageItemDao().observeRepresentatives().first().single().id)
    }
}
