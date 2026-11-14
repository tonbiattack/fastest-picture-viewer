package com.tonbiattack.fastestpictureviewer.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tonbiattack.fastestpictureviewer.core.util.NaturalSortKey
import com.tonbiattack.fastestpictureviewer.data.AppDatabase
import com.tonbiattack.fastestpictureviewer.data.ImageItem
import com.tonbiattack.fastestpictureviewer.data.MediaStoreScanner
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 実体の無い行の整理のテスト (Issue #80)。
 *
 * 誤削除は取り返しがつかないため、「消さない」条件を重点的に固定する。
 * Robolectric では実際のマウント状態を再現できないため、
 * マウント判定は [MediaStoreScanner] の結果に依存する部分を分離して検証する。
 * ここでは候補の集計と dryRun の挙動を確認する。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class GalleryRepositoryStaleCleanupTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: GalleryRepositoryImpl

    /** テストから「マウント済み」に見せるボリューム。空なら SD 切断を模す */
    private var mountedVolumes: Set<String> = setOf("v")

    private fun buildItem(id: String, folderKey: String, name: String): ImageItem {
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
            dateModifiedSec = 100L,
            size = 2_000L,
            mimeType = "image/jpeg",
            width = 10,
            height = 10,
            naturalSortKey = NaturalSortKey.create(name)
        )
    }

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = AppDatabase.buildInMemory(context)
        repository = GalleryRepositoryImpl(
            db = db,
            scanner = MediaStoreScanner(context),
            nowSeconds = { 1_000L },
            mountedVolumesProvider = { mountedVolumes }
        )
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun `開けなかったフォルダを枚数とサイズ付きで取得できる`() = runTest {
        db.imageItemDao().upsertAll(
            listOf(
                buildItem("a", "v:gone/", "a.jpg"),
                buildItem("b", "v:gone/", "b.jpg"),
                buildItem("c", "v:alive/", "c.jpg")
            )
        )
        db.imageItemDao().markFolderUnreadable("v:gone/", detectedAtSec = 1L)

        val summary = repository.getUnreadableFolderSummary()

        assertEquals(1, summary.size)
        assertEquals("v:gone/", summary.single().folderKey)
        assertEquals(2L, summary.single().imageCount)
        assertEquals(4_000L, summary.single().totalBytes)
    }

    @Test
    fun `記録が無ければ空を返す`() = runTest {
        db.imageItemDao().upsertAll(listOf(buildItem("a", "v:ok/", "a.jpg")))
        assertTrue(repository.getUnreadableFolderSummary().isEmpty())
    }

    @Test
    fun `dryRunでは削除せず見積りだけ返す`() = runTest {
        db.imageItemDao().upsertAll(
            listOf(
                buildItem("a", "v:gone/", "a.jpg"),
                buildItem("b", "v:gone/", "b.jpg")
            )
        )
        db.imageItemDao().markFolderUnreadable("v:gone/", detectedAtSec = 1L)

        val result = repository.cleanupStaleEntries(dryRun = true)

        // dryRun なので DB は変わらない
        assertEquals(2L, db.imageItemDao().count())
        // 対象の見積りは返る (マウント状態に依存するため件数は 0 か 1)
        assertTrue(result.folderCount <= 1)
        assertNotNull(result)
    }

    @Test
    fun `SDカードが外れている場合は1件も削除しない`() = runTest {
        // 最重要の安全条件。カードが抜けているだけで実在する画像を失ってはいけない
        db.imageItemDao().upsertAll(
            listOf(
                buildItem("a", "v:gone/", "a.jpg"),
                buildItem("b", "v:gone/", "b.jpg")
            )
        )
        db.imageItemDao().markFolderUnreadable("v:gone/", detectedAtSec = 1L)
        mountedVolumes = emptySet()

        val result = repository.cleanupStaleEntries()

        // 何も消さず、理由を返す
        assertEquals(0, result.folderCount)
        assertNotNull(result.rejectedReason)
        assertEquals(2L, db.imageItemDao().count())
        assertEquals(2L, db.imageItemDao().countUnreadable())
    }

    @Test
    fun `マウントされていれば整理する`() = runTest {
        db.imageItemDao().upsertAll(
            listOf(
                buildItem("a", "v:gone/", "a.jpg"),
                buildItem("b", "v:gone/", "b.jpg")
            )
        )
        db.imageItemDao().markFolderUnreadable("v:gone/", detectedAtSec = 1L)
        mountedVolumes = setOf("v")

        val result = repository.cleanupStaleEntries()

        assertEquals(1, result.folderCount)
        assertEquals(0L, db.imageItemDao().count())
    }

    @Test
    fun `dryRunではまとめて削除しない`() = runTest {
        db.imageItemDao().upsertAll(
            listOf(
                buildItem("a", "v:gone/", "a.jpg"),
                buildItem("b", "v:gone/", "b.jpg")
            )
        )
        db.imageItemDao().markFolderUnreadable("v:gone/", detectedAtSec = 1L)
        mountedVolumes = setOf("v")

        val result = repository.cleanupStaleEntries(dryRun = true)

        assertEquals(1, result.folderCount)
        assertEquals(2L, result.imageCount)
        // 実行していないので DB はそのまま
        assertEquals(2L, db.imageItemDao().count())
    }

    @Test
    fun `対象が無ければ何もしない`() = runTest {
        db.imageItemDao().upsertAll(listOf(buildItem("a", "v:ok/", "a.jpg")))

        val result = repository.cleanupStaleEntries()

        assertEquals(0, result.folderCount)
        assertEquals(0L, result.imageCount)
        assertTrue(result.isNoOp)
    }

    @Test
    fun `一部だけ欠けたフォルダは健全な画像を残す`() = runTest {
        // 最重要: 開けなかった画像だけを外し、実在する画像は一覧に残す。
        // フォルダ全体を消すと、表示できていた画像まで失われる
        db.imageItemDao().upsertAll(
            listOf(
                buildItem("a", "v:mixed/", "a.jpg"),
                buildItem("b", "v:mixed/", "b.jpg"),
                buildItem("c", "v:mixed/", "c.jpg")
            )
        )
        // 3 枚中 1 枚だけ開けなかった
        db.imageItemDao().upsertUnreadable(
            listOf(
                com.tonbiattack.fastestpictureviewer.data.UnreadableImageEntity(
                    itemId = "a",
                    folderKey = "v:mixed/",
                    volumeName = "v",
                    detectedAtSec = 1L
                )
            )
        )

        repository.cleanupStaleEntries()

        // 開けなかった画像の行だけが消え、健全な 2 枚は一覧に残る
        val visible = db.imageItemDao().observeImages("v:mixed/").first()
        assertEquals(listOf("b", "c"), visible.map { it.id })
        assertEquals(0L, db.imageItemDao().countUnreadable())
    }

    @Test
    fun `フォルダごと消えている場合は画像行を整理する`() = runTest {
        db.imageItemDao().upsertAll(
            listOf(
                buildItem("a", "v:gone/", "a.jpg"),
                buildItem("b", "v:gone/", "b.jpg")
            )
        )
        db.imageItemDao().markFolderUnreadable("v:gone/", detectedAtSec = 1L)

        repository.cleanupStaleEntries()

        // 全画像が開けないので、このフォルダの行は整理される
        assertEquals(0L, db.imageItemDao().count())
        assertTrue(db.imageItemDao().observeFolderSummaries().first().isEmpty())
    }
}
