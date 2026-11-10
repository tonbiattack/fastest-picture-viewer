package com.tonbiattack.fastestpictureviewer.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tonbiattack.fastestpictureviewer.core.util.NaturalSortKey
import com.tonbiattack.fastestpictureviewer.repository.PendingSyncPlanner
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
 * Room (SQLite) のクエリ検証。
 * 仕様書の並び順要件 (FR-01/FR-02) と同期系のボリューム分離を確認する。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ImageItemDaoTest {

    private lateinit var db: AppDatabase

    /** テスト用の画像行を作るヘルパー */
    private fun buildItem(
        id: String,
        volume: String,
        path: String,
        name: String,
        modifiedSec: Long,
        mediaId: Long = id.hashCode().toLong()
    ): ImageItem = ImageItem(
        id = id,
        mediaId = mediaId,
        volumeName = volume,
        uri = id,
        displayName = name,
        sortName = name.lowercase(),
        relativePath = path,
        folderKey = "$volume:$path",
        folderName = "folder",
        dateModifiedSec = modifiedSec,
        size = 123L,
        mimeType = "image/jpeg",
        width = 100,
        height = 100,
        naturalSortKey = NaturalSortKey.create(name)
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
    fun `フォルダ一覧は最新更新日時の降順で並ぶ`() = runTest {
        db.imageItemDao().upsertAll(
            listOf(
                buildItem("1", "v", "Pictures/old/", "a.jpg", modifiedSec = 100L),
                buildItem("2", "v", "Pictures/new/", "b.jpg", modifiedSec = 300L),
                buildItem("3", "v", "Pictures/mid/", "c.jpg", modifiedSec = 200L)
            )
        )
        val rows = db.imageItemDao().observeFolderSummaries().first()
        assertEquals(
            listOf("v:Pictures/new/", "v:Pictures/mid/", "v:Pictures/old/"),
            rows.map { it.folderKey }
        )
        assertEquals(1L, rows.first().imageCount)
    }

    @Test
    fun `フォルダ一覧は同日時でもキー昇順で安定する`() = runTest {
        // 更新日時が同一のフォルダが複数あっても並び順がブレない (仕様書 FR-01)
        val dao = db.imageItemDao()
        dao.upsertAll(
            listOf(
                buildItem("1", "v", "Pictures/b/", "a.jpg", modifiedSec = 100L),
                buildItem("2", "v", "Pictures/a/", "b.jpg", modifiedSec = 100L),
                buildItem("3", "v", "Pictures/c/", "c.jpg", modifiedSec = 100L)
            )
        )
        val rows = dao.observeFolderSummaries().first()
        assertEquals(
            listOf("v:Pictures/a/", "v:Pictures/b/", "v:Pictures/c/"),
            rows.map { it.folderKey }
        )
    }

    @Test
    fun `フォルダ内画像はファイル名昇順で同値はmediaId昇順`() = runTest {
        val dao = db.imageItemDao()
        val path = "Pictures/x/"
        dao.upsertAll(
            listOf(
                buildItem("a3", "v", path, "IMG_010.jpg", 1L, mediaId = 20L),
                buildItem("a2", "v", path, "img_010.jpg", 2L, mediaId = 10L), // 大文字小文字を無視して同値扱い、mediaId 昇順で先
                buildItem("a1", "v", path, "IMG_009.jpg", 3L, mediaId = 99L)
            )
        )
        val images = dao.observeImages("v:Pictures/x/").first()
        assertEquals(listOf("a1", "a2", "a3"), images.map { it.id })
    }

    @Test
    fun `代表サムネイルは各フォルダの最新画像を1枚返す`() = runTest {
        val dao = db.imageItemDao()
        dao.upsertAll(
            listOf(
                buildItem("oldA", "v", "Pictures/A/", "a_old.jpg", 10L),
                buildItem("newA", "v", "Pictures/A/", "a_new.jpg", 20L),
                buildItem("newB", "v", "Pictures/B/", "b.jpg", 30L)
            )
        )
        val reps = dao.observeRepresentatives().first()
            .associateBy({ it.folderKey }, { it.id })
        assertEquals("newA", reps["v:Pictures/A/"])
        assertEquals("newB", reps["v:Pictures/B/"])
    }

    @Test
    fun `削除判定は指定ボリュームのみに作用する`() = runTest {
        val dao = db.imageItemDao()
        // ボリューム v1 の走査結果が v2 の行を含まないケースでも v2 は消えない
        dao.upsertAll(
            listOf(
                buildItem("keep", "v1", "Pictures/", "a.jpg", 1L),
                buildItem("gone", "v1", "Pictures/", "b.jpg", 1L),
                buildItem("sd", "v2", "Pictures/", "c.jpg", 1L)
            )
        )
        val cached = dao.getImagesByVolume("v1")
        val plan = PendingSyncPlanner.plan(
            scanned = listOf(cached.first { it.id == "keep" }),
            cached = cached
        )
        dao.upsertAll(plan.upserts)
        dao.deleteByIds(plan.deletions.map { it.id })

        val all = dao.getImagesByVolume("v1") + dao.getImagesByVolume("v2")
        assertEquals(listOf("keep", "sd"), all.map { it.id })
    }

    @Test
    fun `お気に入りは走査によるimages再保存で消えない`() = runTest {
        val dao = db.imageItemDao()
        val favDao = db.favoriteImageDao()
        dao.upsertAll(listOf(buildItem("pic1", "v", "Pictures/", "a.jpg", 1L)))
        favDao.add(FavoriteImageEntity(itemId = "pic1", addedAtSec = 1L))

        // 再走査で同一画像を再度 upsert しても favorites は別テーブルなので保持される
        dao.upsertAll(listOf(buildItem("pic1", "v", "Pictures/", "a.jpg", 2L)))
        assertEquals(1L, favDao.exists("pic1"))
    }

    @Test
    fun `削除は分割実行でも正しく反映される`() = runTest {
        val dao = db.imageItemDao()
        val ids = (0 until 1000).map { "id$it" }
        dao.upsertAll(ids.map { buildItem(it, "v", "Pictures/", "$it.jpg", 1L) })
        // 500 x 2 チャンクの分割削除
        ids.chunked(500).forEach { dao.deleteByIds(it) }
        assertEquals(0L, dao.count())
    }

    @Test
    fun `バッチ処理用にIDだけ取得できる`() = runTest {
        val dao = db.imageItemDao()
        val ids = (0 until 600).map { "id$it" }
        dao.upsertAll(ids.map { buildItem(it, "v", "Pictures/", "$it.jpg", 1L) })

        val volumeIds = dao.getIdsByVolume("v")
        assertEquals(600, volumeIds.size)

        // 500 件ずつ本体を取得できる (SQLite の変数上限を超えない)
        val batch = dao.getImagesByIds(volumeIds.take(500))
        assertEquals(500, batch.size)
    }

    // --- #12 フォルダ最終更新日時順の再計算 ---

    @Test
    fun `フォルダ内の最新画像を更新すると順序が再計算される`() = runTest {
        val dao = db.imageItemDao()
        dao.upsertAll(
            listOf(
                buildItem("a", "v", "Pictures/A/", "a.jpg", modifiedSec = 100L),
                buildItem("b", "v", "Pictures/B/", "b.jpg", modifiedSec = 200L)
            )
        )
        assertEquals(
            listOf("v:Pictures/B/", "v:Pictures/A/"),
            dao.observeFolderSummaries().first().map { it.folderKey }
        )

        // A の画像だけを更新すると A が先頭へ移動する
        dao.upsertAll(listOf(buildItem("a", "v", "Pictures/A/", "a.jpg", modifiedSec = 300L)))
        assertEquals(
            listOf("v:Pictures/A/", "v:Pictures/B/"),
            dao.observeFolderSummaries().first().map { it.folderKey }
        )
    }

    @Test
    fun `フォルダ内の最新画像を削除すると残存画像の日時で再計算される`() = runTest {
        val dao = db.imageItemDao()
        dao.upsertAll(
            listOf(
                buildItem("a-old", "v", "Pictures/A/", "old.jpg", modifiedSec = 100L),
                buildItem("a-new", "v", "Pictures/A/", "new.jpg", modifiedSec = 200L),
                buildItem("b", "v", "Pictures/B/", "b.jpg", modifiedSec = 150L)
            )
        )
        assertEquals("v:Pictures/A/", dao.observeFolderSummaries().first().first().folderKey)

        dao.deleteByIds(listOf("a-new"))
        assertEquals(
            listOf("v:Pictures/B/", "v:Pictures/A/"),
            dao.observeFolderSummaries().first().map { it.folderKey }
        )
    }

    @Test
    fun `画像の移動でフォルダ集計と並び順が更新される`() = runTest {
        val dao = db.imageItemDao()
        dao.upsertAll(
            listOf(
                buildItem("move", "v", "Pictures/A/", "a.jpg", modifiedSec = 200L),
                buildItem("b", "v", "Pictures/B/", "b.jpg", modifiedSec = 100L)
            )
        )
        // 同じ ID のまま B へ移動 (MediaStore の改名/移動に相当)
        dao.upsertAll(listOf(buildItem("move", "v", "Pictures/B/", "b_moved.jpg", modifiedSec = 200L)))

        val rows = dao.observeFolderSummaries().first()
        assertEquals(listOf("v:Pictures/B/"), rows.map { it.folderKey })
        assertEquals(2L, rows.first().imageCount)
    }

    @Test
    fun `更新日時が0の画像は後方に並ぶ`() = runTest {
        val dao = db.imageItemDao()
        dao.upsertAll(
            listOf(
                buildItem("old", "v", "Pictures/Old/", "old.jpg", modifiedSec = 0L),
                buildItem("new", "v", "Pictures/New/", "new.jpg", modifiedSec = 100L)
            )
        )
        assertEquals(
            listOf("v:Pictures/New/", "v:Pictures/Old/"),
            dao.observeFolderSummaries().first().map { it.folderKey }
        )
    }

    // --- #13 除外フォルダ ---

    @Test
    fun `除外フォルダと配下サブフォルダは一覧から消え同名別パスは残る`() = runTest {
        val dao = db.imageItemDao()
        dao.upsertAll(
            listOf(
                buildItem("a", "v", "Pictures/A/", "a.jpg", modifiedSec = 100L),
                buildItem("a-sub", "v", "Pictures/A/sub/", "sub.jpg", modifiedSec = 200L),
                buildItem("ab", "v", "Pictures/AB/", "ab.jpg", modifiedSec = 300L),
                buildItem("b", "v", "Pictures/B/", "b.jpg", modifiedSec = 400L)
            )
        )
        db.excludedFolderDao().add(
            ExcludedFolderEntity(
                folderKey = "v:Pictures/A/",
                volumeName = "v",
                relativePath = "Pictures/A/",
                excludedAtSec = 1L
            )
        )

        val rows = dao.observeFolderSummaries().first()
        assertEquals(listOf("v:Pictures/B/", "v:Pictures/AB/"), rows.map { it.folderKey })
        assertTrue(dao.observeImages("v:Pictures/A/").first().isEmpty())
        assertEquals(1, dao.observeImages("v:Pictures/AB/").first().size)
        assertTrue(dao.observeRepresentatives().first().none { it.folderKey == "v:Pictures/A/" })
    }

    @Test
    fun `除外を解除すると再スキャンなしで一覧に戻る`() = runTest {
        val dao = db.imageItemDao()
        dao.upsertAll(listOf(buildItem("a", "v", "Pictures/A/", "a.jpg", modifiedSec = 100L)))
        val excludedDao = db.excludedFolderDao()
        excludedDao.add(
            ExcludedFolderEntity("v:Pictures/A/", "v", "Pictures/A/", 1L)
        )
        assertTrue(dao.observeFolderSummaries().first().isEmpty())

        excludedDao.remove("v:Pictures/A/")
        assertEquals(
            listOf("v:Pictures/A/"),
            dao.observeFolderSummaries().first().map { it.folderKey }
        )
    }

    @Test
    fun `ボリューム直下の除外は同じボリューム直下の画像だけに適用される`() = runTest {
        val dao = db.imageItemDao()
        dao.upsertAll(
            listOf(
                buildItem("root", "v", "", "root.jpg", modifiedSec = 100L),
                buildItem("sub", "v", "Pictures/", "sub.jpg", modifiedSec = 200L)
            )
        )
        db.excludedFolderDao().add(
            ExcludedFolderEntity(
                folderKey = "v:",
                volumeName = "v",
                relativePath = "",
                excludedAtSec = 1L
            )
        )
        assertEquals(
            listOf("v:Pictures/"),
            dao.observeFolderSummaries().first().map { it.folderKey }
        )
    }

    @Test
    fun `同じパスでもボリュームが違えば除外は巻き込まない`() = runTest {
        val dao = db.imageItemDao()
        dao.upsertAll(
            listOf(
                buildItem("internal", "external_primary", "Pictures/A/", "a.jpg", modifiedSec = 100L),
                buildItem("sd", "sdcard", "Pictures/A/", "b.jpg", modifiedSec = 200L)
            )
        )
        db.excludedFolderDao().add(
            ExcludedFolderEntity(
                folderKey = "external_primary:Pictures/A/",
                volumeName = "external_primary",
                relativePath = "Pictures/A/",
                excludedAtSec = 1L
            )
        )
        assertEquals(
            listOf("sdcard:Pictures/A/"),
            dao.observeFolderSummaries().first().map { it.folderKey }
        )
    }

    @Test
    fun `除外中も同期更新は保持され解除後に最新状態で表示される`() = runTest {
        val dao = db.imageItemDao()
        dao.upsertAll(listOf(buildItem("a", "v", "Pictures/A/", "a.jpg", modifiedSec = 100L)))
        val excludedDao = db.excludedFolderDao()
        excludedDao.add(ExcludedFolderEntity("v:Pictures/A/", "v", "Pictures/A/", 1L))
        assertTrue(dao.observeFolderSummaries().first().isEmpty())

        // 除外中でも同期による更新は通常どおり images に反映される
        dao.upsertAll(listOf(buildItem("a", "v", "Pictures/A/", "a.jpg", modifiedSec = 500L)))
        excludedDao.remove("v:Pictures/A/")

        val row = dao.observeFolderSummaries().first().single()
        assertEquals("v:Pictures/A/", row.folderKey)
        assertEquals(500L, row.latestModifiedSec)
    }

    @Test
    fun `フォルダ内画像は自然順で並ぶ`() = runTest {
        val dao = db.imageItemDao()
        val path = "Pictures/Natural/"
        dao.upsertAll(
            listOf(
                buildItem("100", "v", path, "100_R.jpg", 1L),
                buildItem("10", "v", path, "10_R.jpg", 1L),
                buildItem("8", "v", path, "8_R.jpg", 1L),
                buildItem("7", "v", path, "7_R.jpg", 1L),
                buildItem("2", "v", path, "2_R.jpg", 1L),
                buildItem("02", "v", path, "02_R.jpg", 1L),
                buildItem("1", "v", path, "1_R.jpg", 1L),
                buildItem("p10", "v", path, "page2_10.jpg", 1L),
                buildItem("p3", "v", path, "page2_3.jpg", 1L)
            )
        )

        assertEquals(
            listOf(
                "1_R.jpg", "02_R.jpg", "2_R.jpg", "7_R.jpg", "8_R.jpg",
                "10_R.jpg", "100_R.jpg", "page2_3.jpg", "page2_10.jpg"
            ),
            dao.observeImages("v:$path").first().map { it.displayName }
        )
    }

    @Test
    fun `除外設定は保存・解除できる`() = runTest {
        val excludedDao = db.excludedFolderDao()
        excludedDao.add(ExcludedFolderEntity("v:Pictures/A/", "v", "Pictures/A/", 10L))
        excludedDao.add(ExcludedFolderEntity("sd:Pictures/A/", "sd", "Pictures/A/", 20L))

        assertEquals(1L, excludedDao.exists("v:Pictures/A/"))
        assertEquals(2, excludedDao.observeAll().first().size)

        excludedDao.remove("v:Pictures/A/")
        assertEquals(0L, excludedDao.exists("v:Pictures/A/"))
        assertEquals(1, excludedDao.observeAll().first().size)
    }
}
