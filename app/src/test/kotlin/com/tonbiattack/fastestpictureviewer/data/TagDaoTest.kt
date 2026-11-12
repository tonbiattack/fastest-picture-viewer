package com.tonbiattack.fastestpictureviewer.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** フォルダタグの保存・重複防止・紐付け整合性のテスト (Issue #46) */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TagDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: TagDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = AppDatabase.buildInMemory(context)
        dao = db.tagDao()
    }

    @After
    fun teardown() {
        db.close()
    }

    private fun tag(name: String, normalized: String = name.lowercase()) = TagEntity(
        displayName = name,
        normalizedName = normalized,
        createdAtSec = 1L
    )

    @Test
    fun `タグを登録し付与件数を集計できる`() = runTest {
        val travelId = dao.insertIgnore(tag("旅行", "旅行"))
        val familyId = dao.insertIgnore(tag("家族", "家族"))
        dao.addLink(FolderTagEntity("v:Pictures/A/", travelId, 1L))
        dao.addLink(FolderTagEntity("v:Pictures/B/", travelId, 1L))
        dao.addLink(FolderTagEntity("v:Pictures/B/", familyId, 1L))

        val counts = dao.observeTagsWithCount().first()
        assertEquals(2, counts.size)
        assertEquals(2L, counts.first { it.tagId == travelId }.folderCount)
        assertEquals(1L, counts.first { it.tagId == familyId }.folderCount)
    }

    @Test
    fun `同じ正規化名のタグは重複作成されない`() = runTest {
        val first = dao.insertIgnore(tag("Trip", "trip"))
        val second = dao.insertIgnore(tag("TRIP", "trip"))

        assertTrue(first > 0L)
        // IGNORE により既存行は増えず、採番もされない
        assertEquals(-1L, second)
        assertEquals(1, dao.observeTags().first().size)
        assertNotNull(dao.findByNormalizedName("trip"))
    }

    @Test
    fun `1フォルダに複数タグを付与・解除できる`() = runTest {
        val a = dao.insertIgnore(tag("A", "a"))
        val b = dao.insertIgnore(tag("B", "b"))
        val key = "v:Pictures/A/"
        dao.addLink(FolderTagEntity(key, a, 1L))
        dao.addLink(FolderTagEntity(key, b, 1L))

        assertEquals(setOf(a, b), dao.tagIdsForFolder(key).toSet())

        dao.removeLink(key, a)
        assertEquals(setOf(b), dao.tagIdsForFolder(key).toSet())
    }

    @Test
    fun `一括付与しても既存のタグは消えない`() = runTest {
        val existing = dao.insertIgnore(tag("既存", "既存"))
        val added = dao.insertIgnore(tag("追加", "追加"))
        val keys = listOf("v:A/", "v:B/", "v:C/")
        dao.addLink(FolderTagEntity(keys.first(), existing, 1L))

        dao.addLinks(keys.map { FolderTagEntity(it, added, 2L) })

        keys.forEach { key ->
            val ids = dao.tagIdsForFolder(key).toSet()
            assertTrue("$key に追加タグが無い", added in ids)
        }
        assertTrue(existing in dao.tagIdsForFolder(keys.first()).toSet())
    }

    @Test
    fun `同名フォルダでもフォルダキーが違えば別々に付与される`() = runTest {
        val tagId = dao.insertIgnore(tag("旅行", "旅行"))
        dao.addLink(FolderTagEntity("external_primary:Pictures/Trip/", tagId, 1L))
        dao.addLink(FolderTagEntity("sdcard:Pictures/Trip/", tagId, 1L))

        assertEquals(2, dao.tagIdsForFolder("external_primary:Pictures/Trip/").size +
            dao.tagIdsForFolder("sdcard:Pictures/Trip/").size)
        assertEquals(2, dao.folderKeysForTag(tagId).size)
    }

    @Test
    fun `タグを削除すると紐付けも消える`() = runTest {
        val tagId = dao.insertIgnore(tag("旅行", "旅行"))
        dao.addLink(FolderTagEntity("v:A/", tagId, 1L))
        dao.addLink(FolderTagEntity("v:B/", tagId, 1L))

        dao.deleteTag(tagId)

        assertEquals(0, dao.observeTags().first().size)
        assertEquals(0, dao.tagIdsForFolder("v:A/").size)
        assertEquals(0, dao.countFolders(tagId))
    }

    @Test
    fun `タグ名を変更しても紐付けは維持される`() = runTest {
        val tagId = dao.insertIgnore(tag("旅行", "旅行"))
        dao.addLink(FolderTagEntity("v:A/", tagId, 1L))

        dao.rename(tagId, "家族旅行", "家族旅行")

        val tags = dao.observeTags().first()
        assertEquals("家族旅行", tags.single().displayName)
        assertEquals(setOf(tagId), dao.tagIdsForFolder("v:A/").toSet())
    }

    @Test
    fun `画像の追加削除ではタグが消えない`() = runTest {
        val tagId = dao.insertIgnore(tag("旅行", "旅行"))
        dao.addLink(FolderTagEntity("v:Pictures/A/", tagId, 1L))
        val imageDao = db.imageItemDao()

        imageDao.upsertAll(
            listOf(
                ImageItem(
                    id = "a",
                    mediaId = 1L,
                    volumeName = "v",
                    uri = "a",
                    displayName = "a.jpg",
                    sortName = "a.jpg",
                    relativePath = "Pictures/A/",
                    folderKey = "v:Pictures/A/",
                    folderName = "A",
                    dateModifiedSec = 1L,
                    size = 1L,
                    mimeType = "image/jpeg",
                    width = null,
                    height = null
                )
            )
        )
        imageDao.deleteByIds(listOf("a"))

        assertEquals(setOf(tagId), dao.tagIdsForFolder("v:Pictures/A/").toSet())
    }

    @Test
    fun `存在しないタグIDの付与は件数0になる`() = runTest {
        assertNull(dao.findByNormalizedName("なし"))
        assertEquals(0L, dao.countFolders(999L))
    }
}

/** タグが DB 再オープン後も残ることのテスト */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TagPersistenceTest {

    private val dbName = "tag-persistence-test.db"
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(dbName)
    }

    @After
    fun teardown() {
        context.deleteDatabase(dbName)
    }

    private fun open(): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, dbName).build()

    @Test
    fun `タグと紐付けはDBを開き直しても残る`() = runTest {
        val db1 = open()
        val tagId = db1.tagDao().insertIgnore(
            TagEntity(displayName = "旅行", normalizedName = "旅行", createdAtSec = 1L)
        )
        db1.tagDao().addLink(FolderTagEntity("v:Pictures/A/", tagId, 1L))
        db1.close()

        val db2 = open()
        val tags = db2.tagDao().observeTags().first()
        assertEquals(1, tags.size)
        assertEquals("旅行", tags.single().displayName)
        assertEquals(setOf(tagId), db2.tagDao().tagIdsForFolder("v:Pictures/A/").toSet())
        db2.close()
    }
}
