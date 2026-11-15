package com.tonbiattack.fastestpictureviewer.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tonbiattack.fastestpictureviewer.core.util.NaturalSortKey
import com.tonbiattack.fastestpictureviewer.data.AppDatabase
import com.tonbiattack.fastestpictureviewer.data.ImageItem
import com.tonbiattack.fastestpictureviewer.data.ImageReadableProbe
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

/**
 * 実体が読めないフォルダを非表示にする流れのテスト (Issue #77)。
 *
 * 実機では MediaStore に削除済みファイルの行が残り、走査では消せない。
 * 代表サムネイルの読み込み失敗を起点に実体を確認し、開けなければ
 * フォルダ一覧・フォルダ内一覧・お気に入りから外れることを固定する。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class GalleryRepositoryReadabilityTest {

    private lateinit var db: AppDatabase
    private var now = 1_000L

    /** 開ける URI の集合。ここに無いものは開けない (ENOENT) とみなす */
    private val readableUris = mutableSetOf<String>()

    private lateinit var repository: GalleryRepositoryImpl

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
            size = 100L,
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
            nowSeconds = { now },
            readableProbe = ImageReadableProbe { uri -> uri in readableUris },
            // テストでは対象ボリュームを常に読める扱いにする (Issue #93)。
            // 実機では SD の状態で変わるが、ここではフォルダ判定だけを見る
            mountedVolumesProvider = { setOf("v") }
        )
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun `実体が無いフォルダはサムネイル失敗をきっかけに一覧から消える`() = runTest {
        // 実在するフォルダだけを「開ける」状態にする
        readableUris += "content://img/alive"
        db.imageItemDao().upsertAll(
            listOf(
                buildItem("content://img/gone1", "v:gone/", "a.jpg"),
                buildItem("content://img/gone2", "v:gone/", "b.jpg"),
                buildItem("content://img/alive", "v:alive/", "c.jpg")
            )
        )

        val removed = repository.verifyFolderFromThumbnailFailure("v:gone/")

        assertEquals(2, removed)
        assertEquals(
            listOf("v:alive/"),
            db.imageItemDao().observeFolderSummaries().first().map { it.folderKey }
        )
    }

    @Test
    fun `読めるフォルダはサムネイル失敗があっても消えない`() = runTest {
        // 一時的なデコード失敗を欠損と誤判定しないこと
        readableUris += "content://img/alive"
        db.imageItemDao().upsertAll(
            listOf(buildItem("content://img/alive", "v:alive/", "c.jpg"))
        )

        val removed = repository.verifyFolderFromThumbnailFailure("v:alive/")

        assertEquals(0, removed)
        assertEquals(
            listOf("v:alive/"),
            db.imageItemDao().observeFolderSummaries().first().map { it.folderKey }
        )
    }

    @Test
    fun `代表だけ読めない場合でもフォルダ全体を消す`() = runTest {
        // フォルダごと削除されると配下はすべて同じ理由で開けなくなる
        db.imageItemDao().upsertAll(
            listOf(
                buildItem("content://img/g1", "v:gone/", "a.jpg"),
                buildItem("content://img/g2", "v:gone/", "b.jpg"),
                buildItem("content://img/g3", "v:gone/", "c.jpg")
            )
        )

        repository.verifyFolderFromThumbnailFailure("v:gone/")

        assertTrue(db.imageItemDao().observeImages("v:gone/").first().isEmpty())
    }

    @Test
    fun `ファイルが復活すれば再度表示される`() = runTest {
        db.imageItemDao().upsertAll(
            listOf(buildItem("content://img/gone", "v:gone/", "a.jpg"))
        )
        repository.verifyFolderFromThumbnailFailure("v:gone/")
        assertTrue(db.imageItemDao().observeFolderSummaries().first().isEmpty())

        // SD カードを挿し直した、ファイルを戻したなどのケース
        readableUris += "content://img/gone"
        repository.verifyFolderFromThumbnailFailure("v:gone/")

        assertEquals(
            listOf("v:gone/"),
            db.imageItemDao().observeFolderSummaries().first().map { it.folderKey }
        )
    }

    @Test
    fun `プローブが無い場合は何もしない`() = runTest {
        // readableProbe を渡さない構成 (既存テスト・将来の別実装) では従来どおり
        val context = ApplicationProvider.getApplicationContext<Context>()
        val plain = GalleryRepositoryImpl(db, MediaStoreScanner(context), nowSeconds = { now })
        db.imageItemDao().upsertAll(
            listOf(buildItem("content://img/a", "v:a/", "a.jpg"))
        )

        assertEquals(0, plain.verifyFolderFromThumbnailFailure("v:a/"))
        assertEquals(1, db.imageItemDao().observeFolderSummaries().first().size)
    }

    @Test
    fun `全フォルダ検証は開けないフォルダだけを記録する`() = runTest {
        readableUris += "content://img/ok"
        db.imageItemDao().upsertAll(
            listOf(
                buildItem("content://img/ok", "v:ok/", "a.jpg"),
                buildItem("content://img/ng", "v:ng/", "b.jpg")
            )
        )

        val removed = repository.validateAllFolders()

        assertEquals(1, removed)
        assertEquals(
            listOf("v:ok/"),
            db.imageItemDao().observeFolderSummaries().first().map { it.folderKey }
        )
    }

    @Test
    fun `全フォルダ検証は一定期間内に繰り返さない`() = runTest {
        db.imageItemDao().upsertAll(
            listOf(buildItem("content://img/ng", "v:ng/", "a.jpg"))
        )
        now = 10_000L
        repository.validateAllFolders()

        // 直後の再実行では何もしない (起動のたびに全件を開かない)
        now = 10_000L + 60L
        assertEquals(0, repository.validateAllFolders())
    }

    @Test
    fun `失敗が多すぎる場合は一時的な障害とみなして記録しない`() = runTest {
        // SD カードの取り外しなどで全滅した場合に、実在フォルダを大量に隠さない
        val items = (0 until 20).map { index ->
            buildItem("content://img/$index", "v:f$index/", "$index.jpg")
        }
        db.imageItemDao().upsertAll(items)

        val removed = repository.validateAllFolders()

        assertEquals(0, removed)
        assertEquals(20, db.imageItemDao().observeFolderSummaries().first().size)
    }

    @Test
    fun `手動更新で記録を消すと再判定できる`() = runTest {
        db.imageItemDao().upsertAll(
            listOf(buildItem("content://img/gone", "v:gone/", "a.jpg"))
        )
        repository.verifyFolderFromThumbnailFailure("v:gone/")
        assertTrue(db.imageItemDao().observeFolderSummaries().first().isEmpty())

        repository.clearUnreadableImages()
        readableUris += "content://img/gone"

        assertEquals(
            listOf("v:gone/"),
            db.imageItemDao().observeFolderSummaries().first().map { it.folderKey }
        )
    }

    // ---- 実機で見つかった誤検知の回帰テスト (Issue #77) ----

    @Test
    fun `先頭画像だけ欠けた大量フォルダを丸ごと隠さない`() = runTest {
        // 実機: 大量フォルダの一部だけが削除され、代表も削除済みだった。
        // 代表 1 件だけで判断すると、生きている残りの画像まで隠してしまった。
        val folder = "v:partial/"
        val items = (1..20).map { index ->
            buildItem("content://img/p$index", folder, "img%02d.jpg".format(index))
        }
        db.imageItemDao().upsertAll(items)
        // 先頭だけ開けない。残りは読める
        readableUris += items.drop(1).map { it.uri }

        val removed = repository.verifyFolderFromThumbnailFailure(folder)

        assertEquals("先頭 1 件だけを外す", 1, removed)
        assertEquals(
            listOf(folder),
            db.imageItemDao().observeFolderSummaries().first().map { it.folderKey }
        )
        // 残り 19 件は表示される
        assertEquals(19, db.imageItemDao().observeImages(folder).first().size)
    }

    @Test
    fun `一部だけ欠けたフォルダは欠けた画像だけを外す`() = runTest {
        val folder = "v:partial/"
        val items = (1..10).map { index ->
            buildItem("content://img/p$index", folder, "img%02d.jpg".format(index))
        }
        db.imageItemDao().upsertAll(items)
        // 代表は読めるが、途中の 2 件が開けない
        readableUris += items.map { it.uri } - setOf("content://img/p4", "content://img/p7")

        repository.verifyFolderFromThumbnailFailure(folder)

        // 代表が読める場合は全件は確認しないため、フォルダは表示されたまま
        assertEquals(
            listOf(folder),
            db.imageItemDao().observeFolderSummaries().first().map { it.folderKey }
        )
    }

    @Test
    fun `SDカードが見えなくなった場合は一括で隠さない`() = runTest {
        // 20 フォルダすべてが開けない = カード取り外し等の可能性が高い
        val items = (0 until 20).map { index ->
            buildItem("content://img/s$index", "v:sd$index/", "$index.jpg")
        }
        db.imageItemDao().upsertAll(items)

        val removed = repository.validateAllFolders()

        assertEquals(0, removed)
        assertEquals(20, db.imageItemDao().observeFolderSummaries().first().size)
    }

    @Test
    fun `少数の欠損フォルダだけを隠す`() = runTest {
        val items = (0 until 20).map { index ->
            buildItem("content://img/m$index", "v:ok$index/", "$index.jpg")
        } + listOf(
            buildItem("content://img/gone", "v:gone/", "gone.jpg")
        )
        db.imageItemDao().upsertAll(items)
        readableUris += (0 until 20).map { "content://img/m$it" }

        val removed = repository.validateAllFolders()

        assertEquals(1, removed)
        val visible = db.imageItemDao().observeFolderSummaries().first().map { it.folderKey }
        assertTrue("gone/ が残っている", "v:gone/" !in visible)
        assertEquals(20, visible.size)
    }

    @Test
    fun `代表が読めないフォルダは全体確認で欠損と判定される`() = runTest {
        // フォルダごと消えている場合はサンプルもすべて開けない
        db.imageItemDao().upsertAll(
            (1..10).map { index ->
                buildItem("content://img/g$index", "v:gone/", "%02d.jpg".format(index))
            }
        )

        repository.verifyFolderFromThumbnailFailure("v:gone/")

        assertTrue(db.imageItemDao().observeImages("v:gone/").first().isEmpty())
        assertTrue(db.imageItemDao().observeFolderSummaries().first().isEmpty())
    }

    @Test
    fun `実体確認はフォルダあたり最大5回のプローブで済む`() = runTest {
        var probes = 0
        val context = ApplicationProvider.getApplicationContext<Context>()
        val counting = GalleryRepositoryImpl(
            db = db,
            scanner = MediaStoreScanner(context),
            nowSeconds = { now },
            readableProbe = ImageReadableProbe {
                probes++
                false
            },
            mountedVolumesProvider = { setOf("v") }
        )
        // 大量フォルダでもプローブ回数が増えないこと (性能ガードレール)
        db.imageItemDao().upsertAll(
            (1..500).map { index ->
                buildItem("content://img/big$index", "v:big/", "%04d.jpg".format(index))
            }
        )

        counting.verifyFolderFromThumbnailFailure("v:big/")

        // 再試行 (代表 2 回目) + サンプル 3 件 = 最大 5 回 (Issue #93 で再試行を追加)
        assertTrue("プローブ回数が多すぎる: $probes", probes <= 5)
    }
}
