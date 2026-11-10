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

/** 除外設定がアプリ再起動（DB 再オープン）後も維持されることのテスト */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ExcludedFolderPersistenceTest {

    private val dbName = "excluded-persistence-test.db"
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
    fun `除外設定はDBを開き直しても残る`() = runTest {
        val db1 = open()
        db1.excludedFolderDao().add(
            ExcludedFolderEntity("v:Pictures/A/", "v", "Pictures/A/", 10L)
        )
        db1.close()

        val db2 = open()
        val rows = db2.excludedFolderDao().observeAll().first()
        assertEquals(1, rows.size)
        assertEquals("v:Pictures/A/", rows.single().folderKey)
        db2.close()
    }

    @Test
    fun `解除した設定は開き直しても復活しない`() = runTest {
        val db1 = open()
        db1.excludedFolderDao().add(
            ExcludedFolderEntity("v:Pictures/A/", "v", "Pictures/A/", 10L)
        )
        db1.excludedFolderDao().remove("v:Pictures/A/")
        db1.close()

        val db2 = open()
        assertEquals(0, db2.excludedFolderDao().observeAll().first().size)
        db2.close()
    }
}
