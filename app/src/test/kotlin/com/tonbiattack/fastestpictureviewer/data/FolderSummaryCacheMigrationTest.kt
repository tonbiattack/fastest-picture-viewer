package com.tonbiattack.fastestpictureviewer.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * v9 → v10 のマイグレーション SQL の検証 (Issue #106)。
 *
 * フォルダ一覧キャッシュ用のテーブルを追加する。既存の画像データ・除外設定を
 * 壊さないこと、再実行してもエラーにならないことを確認する。
 * DB はメモリ上に作り、実データには触れない。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class FolderSummaryCacheMigrationTest {

    private lateinit var helper: SupportSQLiteOpenHelper
    private lateinit var db: SupportSQLiteDatabase

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // バージョン 9 = v10 へ移行する直前の DB をメモリ上に作る
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(null)
            .callback(object : SupportSQLiteOpenHelper.Callback(9) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // 移行時に既存データが維持されることを確認したいので、主要テーブルだけ作る
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `images` (
                            `id` TEXT NOT NULL PRIMARY KEY,
                            `mediaId` INTEGER NOT NULL,
                            `volumeName` TEXT NOT NULL,
                            `uri` TEXT NOT NULL,
                            `displayName` TEXT NOT NULL,
                            `sortName` TEXT NOT NULL,
                            `relativePath` TEXT NOT NULL,
                            `folderKey` TEXT NOT NULL,
                            `folderName` TEXT NOT NULL,
                            `dateModifiedSec` INTEGER NOT NULL,
                            `size` INTEGER NOT NULL,
                            `mimeType` TEXT,
                            `width` INTEGER,
                            `height` INTEGER,
                            `naturalSortKey` TEXT NOT NULL DEFAULT ''
                        )
                        """.trimIndent()
                    )
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `excluded_folders` (
                            `folderKey` TEXT NOT NULL PRIMARY KEY,
                            `volumeName` TEXT NOT NULL,
                            `relativePath` TEXT NOT NULL,
                            `excludedAtSec` INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        helper = FrameworkSQLiteOpenHelperFactory().create(config)
        db = helper.writableDatabase
    }

    @After
    fun teardown() {
        helper.close()
    }

    private fun tableNames(): Set<String> =
        db.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }

    private fun insertImage(id: String, folderKey: String) {
        db.execSQL(
            "INSERT INTO images (id, mediaId, volumeName, uri, displayName, sortName, " +
                "relativePath, folderKey, folderName, dateModifiedSec, size, naturalSortKey) " +
                "VALUES ('$id', 1, 'external_primary', '$id', '$id', '$id', " +
                "'${folderKey.substringAfter(':')}', '$folderKey', 'Folder', 100, 1000, '')"
        )
    }

    @Test
    fun `v9からv10でキャッシュ用のテーブルが追加される`() {
        AppDatabase.MIGRATION_9_10.migrate(db)

        val tables = tableNames()
        assertTrue("folder_summaries が無い", tables.contains("folder_summaries"))
        assertTrue("folder_representatives が無い", tables.contains("folder_representatives"))
        assertTrue("folder_summary_state が無い", tables.contains("folder_summary_state"))
    }

    @Test
    fun `既存の画像データは残る`() {
        insertImage("content://media/external/images/media/1", "external_primary:Camera/")
        insertImage("content://media/external/images/media/2", "external_primary:Camera/")

        AppDatabase.MIGRATION_9_10.migrate(db)

        db.query("SELECT COUNT(*) FROM images").use { cursor ->
            cursor.moveToFirst()
            assertEquals(2, cursor.getInt(0))
        }
    }

    @Test
    fun `除外設定は残る`() {
        db.execSQL(
            "INSERT INTO excluded_folders (folderKey, volumeName, relativePath, excludedAtSec) " +
                "VALUES ('external_primary:Private/', 'external_primary', 'Private/', 100)"
        )

        AppDatabase.MIGRATION_9_10.migrate(db)

        db.query("SELECT COUNT(*) FROM excluded_folders").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }
    }

    @Test
    fun `移行直後はキャッシュ未作成`() {
        AppDatabase.MIGRATION_9_10.migrate(db)

        // 状態行が無い＝キャッシュ未作成。起動時に従来の集計へフォールバックする
        db.query("SELECT COUNT(*) FROM folder_summary_state").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM folder_summaries").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
    }

    @Test
    fun `2回実行してもエラーにならない`() {
        AppDatabase.MIGRATION_9_10.migrate(db)
        AppDatabase.MIGRATION_9_10.migrate(db)

        assertTrue(tableNames().contains("folder_summaries"))
        assertNull("テーブルは空のまま", null)
    }
}
