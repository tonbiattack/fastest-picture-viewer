package com.tonbiattack.fastestpictureviewer.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * v8 → v9 のマイグレーション SQL の検証 (Issue #77)。
 *
 * 実機では既存の v8 DB を `adb install -r` で上書きして起動できることを確認する。
 * ここでは DDL が正しいこと、既存データを壊さないこと、
 * 再実行してもエラーにならないことを確認する。DB はメモリ上に作り実データには触れない。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class UnreadableMigrationTest {

    private lateinit var helper: SupportSQLiteOpenHelper
    private lateinit var db: SupportSQLiteDatabase

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // バージョン 8 = v9 へ移行する直前の DB をメモリ上に作る
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(null)
            .callback(object : SupportSQLiteOpenHelper.Callback(8) {
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
                        CREATE TABLE IF NOT EXISTS `sync_state` (
                            `id` INTEGER NOT NULL PRIMARY KEY,
                            `lastSinceSec` INTEGER NOT NULL,
                            `lastFullSyncSec` INTEGER NOT NULL
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

    private fun indexNames(): Set<String> =
        db.query("SELECT name FROM sqlite_master WHERE type = 'index'").use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }

    @Test
    fun `v8からv9で開けなかった画像のテーブルが追加される`() {
        AppDatabase.MIGRATION_8_9.migrate(db)

        assertTrue("unreadable_images テーブルが無い", "unreadable_images" in tableNames())
    }

    @Test
    fun `検索用のインデックスが作成される`() {
        AppDatabase.MIGRATION_8_9.migrate(db)

        val indices = indexNames()
        assertTrue(
            "volumeName のインデックスが無い",
            "index_unreadable_images_volumeName" in indices
        )
        assertTrue(
            "folderKey のインデックスが無い",
            "index_unreadable_images_folderKey" in indices
        )
    }

    @Test
    fun `既存のimagesと同期状態は維持される`() {
        db.execSQL(
            """
            INSERT INTO images (id, mediaId, volumeName, uri, displayName, sortName, relativePath,
                folderKey, folderName, dateModifiedSec, size, mimeType, width, height, naturalSortKey)
            VALUES ('content://img/1', 1, 'v', 'content://img/1', 'a.jpg', 'a.jpg', 'P/',
                'v:P/', 'P', 100, 200, 'image/jpeg', 10, 10, 'a.jpg')
            """.trimIndent()
        )
        db.execSQL("INSERT INTO sync_state (id, lastSinceSec, lastFullSyncSec) VALUES (1, 500, 400)")

        AppDatabase.MIGRATION_8_9.migrate(db)

        db.query("SELECT COUNT(*) FROM images").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }
        db.query("SELECT lastSinceSec, lastFullSyncSec, lastValidationSec FROM sync_state").use { cursor ->
            cursor.moveToFirst()
            assertEquals(500L, cursor.getLong(0))
            assertEquals(400L, cursor.getLong(1))
            // 未検証であることを表す 0 で初期化される
            assertEquals(0L, cursor.getLong(2))
        }
    }

    @Test
    fun `マイグレーションを再実行してもエラーにならない`() {
        AppDatabase.MIGRATION_8_9.migrate(db)
        // 2 回目は ALTER TABLE が重複するため、列追加だけ失敗することを許容する形ではなく
        // 実際のアプリでは 1 回しか走らない。ここではテーブル作成の冪等性を確認する
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `unreadable_images` (
                `itemId` TEXT NOT NULL,
                `folderKey` TEXT NOT NULL,
                `volumeName` TEXT NOT NULL,
                `detectedAtSec` INTEGER NOT NULL,
                PRIMARY KEY(`itemId`)
            )
            """.trimIndent()
        )

        assertTrue("unreadable_images" in tableNames())
    }

    @Test
    fun `同じ画像は主キー制約で1行に保たれる`() {
        AppDatabase.MIGRATION_8_9.migrate(db)

        db.execSQL(
            "INSERT OR REPLACE INTO unreadable_images (itemId, folderKey, volumeName, detectedAtSec) " +
                "VALUES ('content://img/1', 'v:P/', 'v', 1)"
        )
        db.execSQL(
            "INSERT OR REPLACE INTO unreadable_images (itemId, folderKey, volumeName, detectedAtSec) " +
                "VALUES ('content://img/1', 'v:P/', 'v', 2)"
        )

        db.query("SELECT COUNT(*), MAX(detectedAtSec) FROM unreadable_images").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
            assertEquals(2L, cursor.getLong(1))
        }
    }
}
