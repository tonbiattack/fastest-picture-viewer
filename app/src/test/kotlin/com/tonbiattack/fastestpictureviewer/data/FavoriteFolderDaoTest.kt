package com.tonbiattack.fastestpictureviewer.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** フォルダお気に入りの保存・解除・画像同期との独立性テスト */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class FavoriteFolderDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: FavoriteFolderDao

    private fun buildItem(
        id: String,
        volume: String = "v",
        path: String = "Pictures/A/",
        modifiedSec: Long = 1L
    ) = ImageItem(
        id = id,
        mediaId = id.hashCode().toLong(),
        volumeName = volume,
        uri = id,
        displayName = "$id.jpg",
        sortName = "$id.jpg",
        relativePath = path,
        folderKey = "$volume:$path",
        folderName = "A",
        dateModifiedSec = modifiedSec,
        size = 1L,
        mimeType = "image/jpeg",
        width = null,
        height = null
    )

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = AppDatabase.buildInMemory(context)
        dao = db.favoriteFolderDao()
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun `フォルダお気に入りを登録・解除できる`() = runTest {
        dao.add(FavoriteFolderEntity("v:Pictures/A/", "v", "Pictures/A/", 10L))
        dao.add(FavoriteFolderEntity("sd:Pictures/A/", "sd", "Pictures/A/", 20L))

        assertEquals(1L, dao.exists("v:Pictures/A/"))
        assertEquals(2, dao.observeAll().first().size)

        dao.remove("v:Pictures/A/")
        assertEquals(0L, dao.exists("v:Pictures/A/"))
        assertEquals(1, dao.observeAll().first().size)
    }

    @Test
    fun `画像の追加削除ではフォルダお気に入りが消えない`() = runTest {
        dao.add(FavoriteFolderEntity("v:Pictures/A/", "v", "Pictures/A/", 10L))
        val imageDao = db.imageItemDao()

        imageDao.upsertAll(listOf(buildItem("a")))
        imageDao.deleteByIds(listOf("a"))

        assertEquals(1L, dao.exists("v:Pictures/A/"))
    }

    @Test
    fun `フォルダが一時的に消えてもお気に入りは保持される`() = runTest {
        dao.add(FavoriteFolderEntity("v:Pictures/A/", "v", "Pictures/A/", 10L))
        val imageDao = db.imageItemDao()

        // フォルダに画像が無い間もお気に入りは残る
        assertEquals(0, imageDao.observeFolderSummaries().first().size)
        assertEquals(1L, dao.exists("v:Pictures/A/"))

        // 画像が戻れば同じフォルダキーで表示対象になる
        imageDao.upsertAll(listOf(buildItem("a")))
        assertEquals("v:Pictures/A/", imageDao.observeFolderSummaries().first().single().folderKey)
        assertEquals(1L, dao.exists("v:Pictures/A/"))
    }
}

/** フォルダお気に入りがアプリ再起動（DB 再オープン）後も残ることのテスト */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class FavoriteFolderPersistenceTest {

    private val dbName = "favorite-folder-persistence-test.db"
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
    fun `フォルダお気に入りはDBを開き直しても残る`() = runTest {
        val db1 = open()
        db1.favoriteFolderDao().add(
            FavoriteFolderEntity("v:Pictures/A/", "v", "Pictures/A/", 10L)
        )
        db1.close()

        val db2 = open()
        val rows = db2.favoriteFolderDao().observeAll().first()
        assertEquals(1, rows.size)
        assertEquals("v:Pictures/A/", rows.single().folderKey)
        db2.close()
    }
}
