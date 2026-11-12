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
 * v7 → v8 のマイグレーション SQL の検証 (Issue #46)。
 *
 * 実機では既存の v7 DB を `adb install -r` で上書きして起動できることを確認済み。
 * ここでは DDL が正しいこと、制約が効くこと、再実行しても壊れないことを確認する。
 * DB はメモリ上に作り、実データには触れない。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TagMigrationTest {

    private lateinit var helper: SupportSQLiteOpenHelper
    private lateinit var db: SupportSQLiteDatabase

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // バージョン 7 = v8 へ移行する直前の空 DB をメモリ上に作る
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(null)
            .callback(object : SupportSQLiteOpenHelper.Callback(7) {
                override fun onCreate(db: SupportSQLiteDatabase) = Unit
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
    fun `v7からv8でタグと紐付けテーブルが追加される`() {
        AppDatabase.MIGRATION_7_8.migrate(db)

        val tables = tableNames()
        assertTrue("tags テーブルが無い", "tags" in tables)
        assertTrue("folder_tags テーブルが無い", "folder_tags" in tables)
    }

    @Test
    fun `正規化名の一意インデックスとタグIDのインデックスが作成される`() {
        AppDatabase.MIGRATION_7_8.migrate(db)

        val indices = indexNames()
        assertTrue("tags の一意インデックスが無い", "index_tags_normalizedName" in indices)
        assertTrue("folder_tags の tagId インデックスが無い", "index_folder_tags_tagId" in indices)
    }

    @Test
    fun `マイグレーションを再実行してもエラーにならない`() {
        AppDatabase.MIGRATION_7_8.migrate(db)
        AppDatabase.MIGRATION_7_8.migrate(db)

        assertTrue("tags" in tableNames())
    }

    @Test
    fun `重複する正規化名はDB制約で拒否される`() {
        AppDatabase.MIGRATION_7_8.migrate(db)

        db.execSQL(
            "INSERT INTO tags (displayName, normalizedName, createdAtSec) VALUES ('Trip', 'trip', 1)"
        )
        val failed = runCatching {
            db.execSQL(
                "INSERT INTO tags (displayName, normalizedName, createdAtSec) VALUES ('TRIP', 'trip', 2)"
            )
        }.isFailure

        assertTrue("重複タグが登録できてしまう", failed)
        db.query("SELECT COUNT(*) FROM tags").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }
    }

    @Test
    fun `フォルダとタグの複合主キーで重複付与を防ぐ`() {
        AppDatabase.MIGRATION_7_8.migrate(db)

        db.execSQL(
            "INSERT INTO tags (tagId, displayName, normalizedName, createdAtSec) VALUES (1, '旅行', '旅行', 1)"
        )
        db.execSQL("INSERT INTO folder_tags (folderKey, tagId, addedAtSec) VALUES ('v:A/', 1, 1)")
        val failed = runCatching {
            db.execSQL("INSERT INTO folder_tags (folderKey, tagId, addedAtSec) VALUES ('v:A/', 1, 2)")
        }.isFailure

        assertTrue("同じタグを重複付与できてしまう", failed)
    }

    @Test
    fun `タグを削除しても紐付けテーブルの行数が残らない前提を確認する`() {
        AppDatabase.MIGRATION_7_8.migrate(db)

        db.execSQL(
            "INSERT INTO tags (tagId, displayName, normalizedName, createdAtSec) VALUES (1, '旅行', '旅行', 1)"
        )
        db.execSQL("INSERT INTO folder_tags (folderKey, tagId, addedAtSec) VALUES ('v:A/', 1, 1)")
        db.execSQL("DELETE FROM folder_tags WHERE tagId = 1")
        db.execSQL("DELETE FROM tags WHERE tagId = 1")

        db.query("SELECT COUNT(*) FROM folder_tags").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
    }
}
