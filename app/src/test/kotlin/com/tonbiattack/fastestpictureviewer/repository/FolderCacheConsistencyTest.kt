package com.tonbiattack.fastestpictureviewer.repository

import android.content.Context
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tonbiattack.fastestpictureviewer.data.AppDatabase
import com.tonbiattack.fastestpictureviewer.data.BackupData
import com.tonbiattack.fastestpictureviewer.data.BackupFolderRef
import com.tonbiattack.fastestpictureviewer.data.BackupRepository
import com.tonbiattack.fastestpictureviewer.data.BackupSettings
import com.tonbiattack.fastestpictureviewer.data.BackupSettingsAccess
import com.tonbiattack.fastestpictureviewer.data.ExcludedFolderEntity
import com.tonbiattack.fastestpictureviewer.data.ImageItem
import com.tonbiattack.fastestpictureviewer.data.MediaStoreScanner
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class FolderCacheConsistencyTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: GalleryRepositoryImpl
    private val item = ImageItem(
        id = "content://image/1", mediaId = 1, volumeName = "v", uri = "content://image/1",
        displayName = "1.jpg", sortName = "1.jpg", relativePath = "A/", folderKey = "v:A/",
        folderName = "A", dateModifiedSec = 10, size = 20, mimeType = "image/jpeg",
        width = 100, height = 100, naturalSortKey = "1"
    )

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = AppDatabase.buildInMemory(context)
        repository = GalleryRepositoryImpl(db, MediaStoreScanner(context))
    }

    @After
    fun close() = db.close()

    @Test
    fun `除外追加と解除でキャッシュを無効化し一覧を正しく返す`() = runTest {
        db.imageItemDao().upsertAll(listOf(item))
        repository.rebuildFolderSummaryCache()
        assertNotNull(db.folderSummaryCacheDao().getState())
        assertEquals(1, repository.observeFolderSummaries().first().size)

        repository.excludeFolder("v", "A/")
        assertNull(db.folderSummaryCacheDao().getState())
        assertEquals(0, repository.observeFolderSummaries().first().size)
        repository.rebuildFolderSummaryCache()
        assertNotNull(db.folderSummaryCacheDao().getState())

        repository.includeFolder("v:A/")
        assertNull(db.folderSummaryCacheDao().getState())
        assertEquals(1, repository.observeFolderSummaries().first().size)
        assertEquals(item.id, repository.observeRepresentatives().first().single().id)
    }

    @Test
    fun `画像の更新と読み取り不可状態の変更でキャッシュを無効化する`() = runTest {
        db.imageItemDao().upsertAll(listOf(item))
        repository.rebuildFolderSummaryCache()
        db.imageItemDao().upsertAll(listOf(item.copy(dateModifiedSec = 30)))
        assertNull(db.folderSummaryCacheDao().getState())
        assertEquals(30L, repository.observeFolderSummaries().first().single().latestModifiedSec)

        repository.rebuildFolderSummaryCache()
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO unreadable_images (itemId, folderKey, volumeName, detectedAtSec) VALUES (?, ?, ?, ?)",
            arrayOf<Any>(item.id, item.folderKey, item.volumeName, 1L)
        )
        assertNull(db.folderSummaryCacheDao().getState())
        assertEquals(0, repository.observeFolderSummaries().first().size)
    }

    @Test
    fun `バックアップ復元で除外を追加しても無効化される`() = runTest {
        db.imageItemDao().upsertAll(listOf(item))
        repository.rebuildFolderSummaryCache()
        val settings = BackupSettings(2, "LATEST", 3, 3, true)
        val access = object : BackupSettingsAccess {
            override suspend fun snapshot() = settings
            override suspend fun apply(settings: BackupSettings) = Unit
        }
        BackupRepository(db, access).restore(
            BackupData(
                exportedAt = "", appVersionName = "", tags = emptyList(), folderTags = emptyList(),
                favoriteFolders = emptyList(), favoriteImages = emptyList(),
                excludedFolders = listOf(BackupFolderRef("v", "A/")), settings = settings
            )
        )
        assertNull(db.folderSummaryCacheDao().getState())
        assertEquals(0, repository.observeFolderSummaries().first().size)
    }

    @Test
    fun `購読中も除外と解除が伝播する`() = runBlocking {
        db.imageItemDao().upsertAll(listOf(item))
        repository.rebuildFolderSummaryCache()
        val values = Channel<Int>(Channel.UNLIMITED)
        val job = launch {
            repository.observeFolderSummaries().collect { values.send(it.size) }
        }
        withTimeout(10_000) {
            assertEquals(1, values.receive())
            repository.excludeFolder("v", "A/")
            while (values.receive() != 0) { }
            repository.includeFolder("v:A/")
            while (values.receive() != 1) { }
        }
        job.cancel()
    }

    @Test
    fun `書き込みがロールバックされればキャッシュも有効なまま`() = runTest {
        db.imageItemDao().upsertAll(listOf(item))
        repository.rebuildFolderSummaryCache()
        try {
            db.withTransaction {
                db.excludedFolderDao().add(ExcludedFolderEntity("v:A/", "v", "A/", 1))
                error("テスト用のロールバック")
            }
        } catch (_: IllegalStateException) {
            // DB トランザクション全体が戻ることを確認する。
        }
        assertNotNull(db.folderSummaryCacheDao().getState())
        assertEquals(1, repository.observeFolderSummaries().first().size)
    }

    @Test
    fun `集計中の書き込みは公開後に無効化される`() = runBlocking {
        db.imageItemDao().upsertAll(listOf(item))
        val reading = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val rebuild = async(Dispatchers.IO) {
            db.withTransaction {
                val rows = db.imageItemDao().getAllFolderSummaries()
                reading.complete(Unit)
                resume.await()
                db.folderSummaryCacheDao().replaceAll(
                    rows.map { com.tonbiattack.fastestpictureviewer.data.FolderSummaryEntity(
                        it.folderKey, it.volumeName, it.relativePath, it.imageCount, it.latestModifiedSec
                    ) },
                    emptyList(), 1, 1
                )
            }
        }
        reading.await()
        val writer = async(Dispatchers.IO) { repository.excludeFolder("v", "A/") }
        resume.complete(Unit)
        withTimeout(10_000) {
            rebuild.await()
            writer.await()
        }
        assertNull(db.folderSummaryCacheDao().getState())
        assertEquals(0, repository.observeFolderSummaries().first().size)
    }
}
