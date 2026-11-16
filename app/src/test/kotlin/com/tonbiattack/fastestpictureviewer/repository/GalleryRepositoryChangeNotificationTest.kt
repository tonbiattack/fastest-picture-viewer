package com.tonbiattack.fastestpictureviewer.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tonbiattack.fastestpictureviewer.data.AppDatabase
import com.tonbiattack.fastestpictureviewer.data.MediaStoreScanner
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * タグ・お気に入り・除外の変更が通知されることのテスト (Issue #108)。
 *
 * バックアップのリマインドは「前回の書き出し以降に変更があったか」で決まる。
 * 変更を取りこぼすと催促が出ず、逆に閲覧操作で通知すると常に催促が出てしまう。
 * ここでは「利用者が手で作ったデータを変えたときだけ通知される」ことを固定する。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class GalleryRepositoryChangeNotificationTest {

    private lateinit var db: AppDatabase
    private var changeCount = 0

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = AppDatabase.buildInMemory(context)
        changeCount = 0
    }

    @After
    fun teardown() {
        db.close()
    }

    private fun newRepository(): GalleryRepositoryImpl {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return GalleryRepositoryImpl(
            db = db,
            scanner = MediaStoreScanner(context),
            nowSeconds = { 1_000L },
            onUserDataChanged = { changeCount++ }
        )
    }

    @Test
    fun `フォルダのお気に入り変更が通知される`() = runTest {
        val repository = newRepository()

        repository.setFolderFavorite(
            folderKey = "external_primary:Pictures/A/",
            volumeName = "external_primary",
            relativePath = "Pictures/A/",
            favorite = true
        )

        assertEquals("お気に入り追加が通知されていない", 1, changeCount)
    }

    @Test
    fun `フォルダのお気に入り解除も通知される`() = runTest {
        val repository = newRepository()
        repository.setFolderFavorite(
            folderKey = "external_primary:Pictures/A/",
            volumeName = "external_primary",
            relativePath = "Pictures/A/",
            favorite = true
        )
        changeCount = 0

        repository.setFolderFavorite(
            folderKey = "external_primary:Pictures/A/",
            volumeName = "external_primary",
            relativePath = "Pictures/A/",
            favorite = false
        )

        assertEquals("お気に入り解除が通知されていない", 1, changeCount)
    }

    @Test
    fun `画像のお気に入り変更が通知される`() = runTest {
        val repository = newRepository()

        repository.setFavorite(itemId = "content://x/1", favorite = true)

        assertEquals("画像のお気に入りが通知されていない", 1, changeCount)
    }

    @Test
    fun `除外フォルダの追加と解除が通知される`() = runTest {
        val repository = newRepository()

        repository.excludeFolder("external_primary", "Private/")
        assertEquals("除外の追加が通知されていない", 1, changeCount)

        repository.includeFolder("external_primary:Private/")
        assertEquals("除外の解除が通知されていない", 2, changeCount)
    }

    @Test
    fun `タグの付与が通知される`() = runTest {
        val repository = newRepository()
        val tag = repository.findOrCreateTag("旅行")!!

        repository.setFolderTags("external_primary:Pictures/A/", setOf(tag.tagId))

        assertEquals("タグ付与が通知されていない", 1, changeCount)
    }

    @Test
    fun `タグの一括付与が通知される`() = runTest {
        val repository = newRepository()
        val tag = repository.findOrCreateTag("旅行")!!

        repository.addTagsToFolders(
            tagIds = setOf(tag.tagId),
            folderKeys = listOf("external_primary:A/", "external_primary:B/")
        )

        assertEquals("一括付与が通知されていない", 1, changeCount)
    }

    @Test
    fun `タグの改名と削除が通知される`() = runTest {
        val repository = newRepository()
        val tag = repository.findOrCreateTag("旅行")!!

        repository.renameTag(tag.tagId, "旅行2")
        assertEquals("タグ改名が通知されていない", 1, changeCount)

        repository.deleteTag(tag.tagId)
        assertEquals("タグ削除が通知されていない", 2, changeCount)
    }

    @Test
    fun `タグの改名に失敗したときは通知しない`() = runTest {
        val repository = newRepository()
        val first = repository.findOrCreateTag("旅行")!!
        repository.findOrCreateTag("出張")
        changeCount = 0

        // 既存の別タグと同じ名前にする改名は失敗する
        val renamed = repository.renameTag(first.tagId, "出張")

        assertEquals(false, renamed)
        assertEquals("失敗した改名で通知している", 0, changeCount)
    }

    @Test
    fun `閲覧操作では通知しない`() = runTest {
        val repository = newRepository()

        // フォルダを開いた記録は「手で作ったデータ」ではない
        repository.markFolderOpened("external_primary:Pictures/A/")
        repository.saveScrollPosition("external_primary:Pictures/A/", null, 10, 0)
        repository.saveFolderListScrollPosition(
            sortMode = com.tonbiattack.fastestpictureviewer.core.util.FolderSortMode.LATEST,
            anchorFolderKey = "external_primary:Pictures/A/",
            anchorIndex = 0,
            anchorOffsetPx = 0
        )

        assertEquals("閲覧操作で通知している", 0, changeCount)
    }

    @Test
    fun `通知先が未設定でも例外にならない`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // onUserDataChanged を渡さない（既定の空ラムダ）
        val repository = GalleryRepositoryImpl(
            db = db,
            scanner = MediaStoreScanner(context),
            nowSeconds = { 1_000L }
        )

        repository.setFavorite(itemId = "content://x/1", favorite = true)

        // 例外が飛ばなければよい
        assertEquals(0, changeCount)
    }
}
