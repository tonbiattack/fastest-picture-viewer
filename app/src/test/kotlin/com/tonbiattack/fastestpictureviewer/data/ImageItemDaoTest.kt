package com.tonbiattack.fastestpictureviewer.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tonbiattack.fastestpictureviewer.repository.PendingSyncPlanner
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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
        height = 100
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
}
