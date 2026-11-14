package com.tonbiattack.fastestpictureviewer.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tonbiattack.fastestpictureviewer.core.util.NaturalSortKey
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
 * 「開けなかった画像」を表示クエリから除外する挙動のテスト (Issue #77)。
 *
 * OPPO/ColorOS ではフォルダを削除しても MediaStore の行が残り、走査で消せない。
 * 実体を開けなかった行を unreadable_images に記録し、一覧から外すことで
 * 壊れたサムネイルと「開いても空のフォルダ」を解消する。
 * DB はメモリ上に作り、実データには触れない。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class UnreadableImageDaoTest {

    private lateinit var db: AppDatabase

    private fun buildItem(
        id: String,
        folderKey: String,
        name: String = "img.jpg",
        modifiedSec: Long = 100L
    ): ImageItem {
        val volume = folderKey.substringBefore(':')
        val path = folderKey.substringAfter(':')
        return ImageItem(
            id = id,
            mediaId = id.hashCode().toLong().let { if (it < 0) -it else it },
            volumeName = volume,
            uri = id,
            displayName = name,
            sortName = name.lowercase(),
            relativePath = path,
            folderKey = folderKey,
            folderName = "folder",
            dateModifiedSec = modifiedSec,
            size = 123L,
            mimeType = "image/jpeg",
            width = 100,
            height = 100,
            naturalSortKey = NaturalSortKey.create(name)
        )
    }

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
    fun `開けなかった画像はフォルダ一覧から除外される`() = runTest {
        val dao = db.imageItemDao()
        dao.upsertAll(
            listOf(
                buildItem("a", "v:gone/", "a.jpg"),
                buildItem("b", "v:gone/", "b.jpg"),
                buildItem("c", "v:alive/", "c.jpg")
            )
        )
        dao.markFolderUnreadable("v:gone/", detectedAtSec = 500L)

        val folders = dao.observeFolderSummaries().first()

        assertEquals(listOf("v:alive/"), folders.map { it.folderKey })
    }

    @Test
    fun `開けなかった画像はフォルダ内一覧から除外される`() = runTest {
        val dao = db.imageItemDao()
        dao.upsertAll(
            listOf(
                buildItem("a", "v:mixed/", "a.jpg"),
                buildItem("b", "v:mixed/", "b.jpg")
            )
        )
        dao.upsertUnreadable(
            listOf(
                UnreadableImageEntity(
                    itemId = "a",
                    folderKey = "v:mixed/",
                    volumeName = "v",
                    detectedAtSec = 1L
                )
            )
        )

        val items = dao.observeImages("v:mixed/").first()

        assertEquals(listOf("b"), items.map { it.id })
    }

    @Test
    fun `開けなかった画像は代表サムネイルに選ばれない`() = runTest {
        val dao = db.imageItemDao()
        dao.upsertAll(
            listOf(
                // a.jpg が自然順先頭。開けないので代表は b.jpg になる
                buildItem("a", "v:f/", "a.jpg"),
                buildItem("b", "v:f/", "b.jpg")
            )
        )
        dao.markFolderUnreadable("v:f/", detectedAtSec = 1L)
        // フォルダ全体ではなく b だけ戻した状態を作る
        dao.deleteUnreadable(listOf("b"))

        val representatives = dao.observeRepresentatives().first()

        assertEquals(listOf("b"), representatives.map { it.id })
    }

    @Test
    fun `全画像が除外されたフォルダは代表サムネイルも一覧も空になる`() = runTest {
        val dao = db.imageItemDao()
        dao.upsertAll(
            listOf(
                buildItem("a", "v:gone/", "a.jpg"),
                buildItem("b", "v:alive/", "b.jpg")
            )
        )
        dao.clearUnreadableByFolder("v:gone/")
        dao.markFolderUnreadable("v:gone/", detectedAtSec = 1L)

        assertTrue(dao.observeImages("v:gone/").first().isEmpty())
        assertEquals(listOf("b"), dao.observeRepresentatives().first().map { it.id })
    }

    @Test
    fun `お気に入り一覧からも除外される`() = runTest {
        val dao = db.imageItemDao()
        dao.upsertAll(listOf(buildItem("a", "v:gone/", "a.jpg")))
        db.favoriteImageDao().add(FavoriteImageEntity(itemId = "a", addedAtSec = 1L))
        dao.markFolderUnreadable("v:gone/", detectedAtSec = 2L)

        assertTrue(dao.observeFavoriteImages(null).first().isEmpty())
    }

    @Test
    fun `記録を消すと表示に戻る`() = runTest {
        val dao = db.imageItemDao()
        dao.upsertAll(listOf(buildItem("a", "v:gone/", "a.jpg")))
        dao.markFolderUnreadable("v:gone/", detectedAtSec = 1L)
        assertTrue(dao.observeFolderSummaries().first().isEmpty())

        // ファイルが復活した (または手動更新で再判定する) ケース
        dao.clearUnreadableImages()

        assertEquals(listOf("v:gone/"), dao.observeFolderSummaries().first().map { it.folderKey })
    }

    @Test
    fun `複数フォルダをまとめて記録できる`() = runTest {
        val dao = db.imageItemDao()
        dao.upsertAll(
            listOf(
                buildItem("a", "v:g1/", "a.jpg"),
                buildItem("b", "v:g2/", "b.jpg"),
                buildItem("c", "v:ok/", "c.jpg")
            )
        )

        dao.markFoldersUnreadable(listOf("v:g1/", "v:g2/"), detectedAtSec = 9L)

        assertEquals(listOf("v:ok/"), dao.observeFolderSummaries().first().map { it.folderKey })
        assertEquals(2L, dao.countUnreadable())
    }

    @Test
    fun `除外フォルダの設定は従来どおり効く`() = runTest {
        // Issue #77 の変更で既存の除外機能を壊していないことを確認する
        val dao = db.imageItemDao()
        dao.upsertAll(
            listOf(
                buildItem("a", "v:Pictures/secret/", "a.jpg"),
                buildItem("b", "v:Pictures/open/", "b.jpg")
            )
        )
        db.excludedFolderDao().add(
            ExcludedFolderEntity(
                folderKey = "v:Pictures/secret/",
                volumeName = "v",
                relativePath = "Pictures/secret/",
                excludedAtSec = 1L
            )
        )

        assertEquals(
            listOf("v:Pictures/open/"),
            dao.observeFolderSummaries().first().map { it.folderKey }
        )
    }

    @Test
    fun `検証対象はフォルダごとに自然順先頭の1件を返す`() = runTest {
        val dao = db.imageItemDao()
        dao.upsertAll(
            listOf(
                buildItem("a2", "v:f/", "b.jpg"),
                buildItem("a1", "v:f/", "a.jpg"),
                buildItem("b1", "v:g/", "c.jpg")
            )
        )

        val targets = dao.getFolderReadabilityTargets()

        assertEquals(2, targets.size)
        assertEquals("a1", targets.first { it.folderKey == "v:f/" }.id)
        assertEquals("b1", targets.first { it.folderKey == "v:g/" }.id)
    }

    @Test
    fun `同じ画像を重複して記録しても1行に保たれる`() = runTest {
        val dao = db.imageItemDao()
        dao.upsertAll(listOf(buildItem("a", "v:gone/", "a.jpg")))

        dao.markFolderUnreadable("v:gone/", detectedAtSec = 1L)
        dao.markFolderUnreadable("v:gone/", detectedAtSec = 2L)

        assertEquals(1L, dao.countUnreadable())
    }
}
