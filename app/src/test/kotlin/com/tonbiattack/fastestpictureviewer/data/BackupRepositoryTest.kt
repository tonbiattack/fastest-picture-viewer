package com.tonbiattack.fastestpictureviewer.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
 * バックアップの書き出し・取り込みのテスト (Issue #74)。
 * 実 DB (メモリ) を使い、マージ動作と既存データ保護を確認する。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class BackupRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var settings: FakeSettingsAccess
    private lateinit var repository: BackupRepository

    private class FakeSettingsAccess(var current: BackupSettings = BackupSettings(
        folderColumns = 2,
        sortMode = "LATEST",
        imageColumnsPortrait = 3,
        imageColumnsLandscape = 3,
        showResumeCard = true
    )) : BackupSettingsAccess {
        var applied: BackupSettings? = null
        override suspend fun snapshot(): BackupSettings = current
        override suspend fun apply(settings: BackupSettings) {
            applied = settings
            current = settings
        }
    }

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = AppDatabase.buildInMemory(context)
        settings = FakeSettingsAccess()
        repository = BackupRepository(db, settings, nowSeconds = { 1_000L })
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun `タグと紐付けを書き出して読み戻せる`() = runTest {
        val tag = db.tagDao().insertIgnore(TagEntity(displayName = "旅行", normalizedName = "旅行", createdAtSec = 1L))
        db.tagDao().addLink(FolderTagEntity("external_primary:Pictures/A/", tag, 1L))
        db.favoriteFolderDao().add(FavoriteFolderEntity("external_primary:Pictures/A/", "external_primary", "Pictures/A/", 1L))
        db.favoriteImageDao().add(FavoriteImageEntity("content://x/1", 1L))
        db.excludedFolderDao().add(ExcludedFolderEntity("external_primary:Private/", "external_primary", "Private/", 1L))

        val backup = repository.collect("0.1.0")

        assertEquals(1, backup.tags.size)
        assertEquals("旅行", backup.tags.single().displayName)
        assertEquals(1, backup.folderTags.size)
        assertEquals("external_primary:Pictures/A/", backup.folderTags.single().folderKey)
        assertEquals(1, backup.favoriteFolders.size)
        assertEquals(listOf("content://x/1"), backup.favoriteImages)
        assertEquals(1, backup.excludedFolders.size)
        assertEquals("0.1.0", backup.appVersionName)
    }

    @Test
    fun `空のDBでも書き出せる`() = runTest {
        val backup = repository.collect("0.1.0")
        assertTrue(backup.tags.isEmpty())
        assertTrue(backup.folderTags.isEmpty())
        assertTrue(backup.favoriteFolders.isEmpty())
        assertTrue(backup.favoriteImages.isEmpty())
        assertTrue(backup.excludedFolders.isEmpty())
    }

    @Test
    fun `書き出した内容を別のDBへ復元できる`() = runTest {
        val tag = db.tagDao().insertIgnore(TagEntity(displayName = "旅行", normalizedName = "旅行", createdAtSec = 1L))
        db.tagDao().addLink(FolderTagEntity("external_primary:Pictures/A/", tag, 1L))
        db.favoriteFolderDao().add(FavoriteFolderEntity("external_primary:Pictures/A/", "external_primary", "Pictures/A/", 1L))
        db.favoriteImageDao().add(FavoriteImageEntity("content://x/1", 1L))
        db.excludedFolderDao().add(ExcludedFolderEntity("external_primary:Private/", "external_primary", "Private/", 1L))
        settings.current = BackupSettings(4, "RECENT", 2, 6, false)
        val backup = BackupJson.decode(BackupJson.encode(repository.collect("0.1.0")))

        // 別の空 DB に復元
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db2 = AppDatabase.buildInMemory(context)
        try {
            val settings2 = FakeSettingsAccess()
            val restored = BackupRepository(db2, settings2, nowSeconds = { 2_000L }).restore(backup)

            assertEquals(1, restored.tagsCreated)
            assertEquals(0, restored.tagsReused)
            assertEquals(1, restored.folderTagsAdded)
            assertEquals(1, restored.favoriteFoldersAdded)
            assertEquals(1, restored.favoriteImagesAdded)
            assertEquals(1, restored.excludedFoldersAdded)
            assertEquals(settings.current, settings2.current)

            val tags = db2.tagDao().observeTags().first()
            assertEquals("旅行", tags.single().displayName)
            val links = db2.tagDao().getAllFolderTags()
            assertEquals(tags.single().tagId, links.single().tagId)
            assertEquals("external_primary:Pictures/A/", links.single().folderKey)
            assertEquals(1, db2.favoriteFolderDao().getAll().size)
            assertEquals("content://x/1", db2.favoriteImageDao().getAll().single().itemId)
            assertEquals("external_primary:Private/", db2.excludedFolderDao().getAll().single().folderKey)
        } finally {
            db2.close()
        }
    }

    @Test
    fun `同じバックアップを2回復元しても重複しない`() = runTest {
        val tag = db.tagDao().insertIgnore(TagEntity(displayName = "旅行", normalizedName = "旅行", createdAtSec = 1L))
        db.tagDao().addLink(FolderTagEntity("external_primary:Pictures/A/", tag, 1L))
        db.favoriteFolderDao().add(FavoriteFolderEntity("external_primary:Pictures/A/", "external_primary", "Pictures/A/", 1L))
        db.favoriteImageDao().add(FavoriteImageEntity("content://x/1", 1L))
        val backup = repository.collect("0.1.0")

        val first = repository.restore(backup)
        assertEquals(0, first.tagsCreated)
        assertEquals(1, first.tagsReused)
        assertEquals(0, first.favoriteFoldersAdded)
        assertEquals(0, first.favoriteImagesAdded)

        val second = repository.restore(backup)
        assertEquals(0, second.tagsCreated)
        assertEquals(0, second.favoriteFoldersAdded)
        assertEquals(0, second.favoriteImagesAdded)
        assertTrue("2回目は追加なし", !second.hasChanges || second.folderTagsAdded == 0)

        assertEquals(1, db.tagDao().observeTags().first().size)
        assertEquals(1, db.tagDao().getAllFolderTags().size)
    }

    @Test
    fun `既存データは復元で消えない`() = runTest {
        val existing = db.tagDao().insertIgnore(TagEntity(displayName = "既存", normalizedName = "既存", createdAtSec = 1L))
        db.tagDao().addLink(FolderTagEntity("external_primary:Keep/", existing, 1L))
        db.favoriteFolderDao().add(FavoriteFolderEntity("external_primary:Keep/", "external_primary", "Keep/", 1L))

        // 別の内容を復元
        repository.restore(
            BackupData(
                exportedAt = "",
                appVersionName = "0.1.0",
                tags = listOf(BackupTag("新規", "新規")),
                folderTags = listOf(BackupFolderTag("external_primary:New/", "新規")),
                favoriteFolders = listOf(BackupFolderRef("external_primary", "New/")),
                favoriteImages = emptyList(),
                excludedFolders = emptyList(),
                settings = settings.current
            )
        )

        // 既存が残り、新規が追加される
        val tags = db.tagDao().observeTags().first().map { it.displayName }.toSet()
        assertEquals(setOf("既存", "新規"), tags)
        assertEquals(2, db.tagDao().getAllFolderTags().size)
        assertEquals(2, db.favoriteFolderDao().getAll().size)
    }

    @Test
    fun `存在しないタグへの紐付けは飛ばして件数を返す`() = runTest {
        val result = repository.restore(
            BackupData(
                exportedAt = "",
                appVersionName = "0.1.0",
                tags = listOf(BackupTag("ある", "ある")),
                folderTags = listOf(
                    BackupFolderTag("external_primary:A/", "ある"),
                    BackupFolderTag("external_primary:B/", "ない")
                ),
                favoriteFolders = emptyList(),
                favoriteImages = emptyList(),
                excludedFolders = emptyList(),
                settings = settings.current
            )
        )
        assertEquals(1, result.folderTagsAdded)
        assertEquals(1, result.skippedFolderTags)
    }

    @Test
    fun `復元で表示設定も反映される`() = runTest {
        val newSettings = BackupSettings(
            folderColumns = 4,
            sortMode = "RECENT",
            imageColumnsPortrait = 5,
            imageColumnsLandscape = 2,
            showResumeCard = false
        )
        val result = repository.restore(
            BackupData(
                exportedAt = "",
                appVersionName = "0.1.0",
                tags = emptyList(),
                folderTags = emptyList(),
                favoriteFolders = emptyList(),
                favoriteImages = emptyList(),
                excludedFolders = emptyList(),
                settings = newSettings
            )
        )
        assertTrue(result.settingsApplied)
        assertEquals(newSettings, settings.applied)
        assertNotNull(settings.applied)
    }

    @Test
    fun `除外フォルダも復元できる`() = runTest {
        repository.restore(
            BackupData(
                exportedAt = "",
                appVersionName = "0.1.0",
                tags = emptyList(),
                folderTags = emptyList(),
                favoriteFolders = emptyList(),
                favoriteImages = emptyList(),
                excludedFolders = listOf(BackupFolderRef("external_primary", "Private/")),
                settings = settings.current
            )
        )
        val excluded = db.excludedFolderDao().getAll()
        assertEquals(1, excluded.size)
        assertEquals("external_primary:Private/", excluded.single().folderKey)
    }

    @Test
    fun `既定のファイル名は日時を含む`() = runTest {
        // nowSeconds = 1000 → 1970-01-01 09:16 (JST)
        val name = repository.defaultFileName()
        assertTrue(name.startsWith("pictobi-backup-"))
        assertTrue(name.endsWith(".json"))
    }
}
