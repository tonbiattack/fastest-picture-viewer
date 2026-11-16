package com.tonbiattack.fastestpictureviewer.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.UUID

/** 実データには触れず、旧バージョン相当のテスト DB で Room 移行を検証する。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class FolderCacheInvalidationMigrationTest {
    @Test
    fun `v10からの移行で設定を保持して古いキャッシュを無効化する`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "test-${UUID.randomUUID()}.db"
        try {
            val original = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            original.favoriteFolderDao().add(FavoriteFolderEntity("v:A/", "v", "A/", 1))
            original.excludedFolderDao().add(ExcludedFolderEntity("v:B/", "v", "B/", 1))
            original.folderSummaryCacheDao().upsertState(FolderSummaryStateEntity(1, 1, 1))
            original.close()

            // v10 と v11 は Room 管理テーブルが同一。旧版相当のスキーマにする。
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use {
                it.version = 10
            }
            val migrated = Room.databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(AppDatabase.MIGRATION_10_11)
                .build()
            try {
                assertEquals("v:A/", migrated.favoriteFolderDao().getAll().single().folderKey)
                assertEquals("v:B/", migrated.excludedFolderDao().getAll().single().folderKey)
                assertNull(migrated.folderSummaryCacheDao().getState())
                migrated.folderSummaryCacheDao().upsertState(FolderSummaryStateEntity(1, 2, 1))
                migrated.excludedFolderDao().remove("v:B/")
                assertNull(migrated.folderSummaryCacheDao().getState())
            } finally {
                migrated.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
}
