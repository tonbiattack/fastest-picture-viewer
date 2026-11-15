package com.tonbiattack.fastestpictureviewer.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * DB の退避処理のテスト (Issue #88)。
 *
 * 実機の SQLite と Robolectric の実装差により、破損の検出そのものは
 * 単体テストで再現できない（Robolectric は壊れたファイルを開けてしまう）。
 * また Robolectric のファイル操作は実機と挙動が異なる部分があるため、
 * ここでは「退避ファイルが作られ、中身が保たれること」を確認する。
 *
 * 破損の判定ロジックは [DatabaseRecoveryTest] で固定している。
 * 削除や WAL の扱いは実機で確認する（docs/device-verification.md）。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DatabaseQuarantineTest {

    private lateinit var context: Context
    private lateinit var dbFile: File

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        dbFile = context.getDatabasePath(AppDatabase.DATABASE_NAME)
        dbFile.parentFile?.mkdirs()
        cleanup()
    }

    @After
    fun teardown() {
        cleanup()
    }

    private fun cleanup() {
        dbFile.parentFile?.listFiles()?.forEach { file ->
            if (file.name.startsWith(AppDatabase.DATABASE_NAME)) file.delete()
        }
    }

    @Test
    fun `DBがあると退避ファイルが作られる`() {
        dbFile.writeBytes(ByteArray(1024) { 1 })

        val quarantined = AppDatabase.quarantineForTest(context)

        assertNotNull("退避に失敗した", quarantined)
        assertTrue("退避ファイルが存在しない", quarantined!!.exists())
        assertEquals("退避ファイルの中身が保たれていない", 1024L, quarantined.length())
    }

    @Test
    fun `退避ファイルが既にあっても新しい内容で置き換える`() {
        val existing = File(dbFile.parentFile, "${AppDatabase.DATABASE_NAME}.corrupted")
        existing.writeBytes(ByteArray(999) { 9 })

        dbFile.writeBytes(ByteArray(128) { 1 })
        val result = AppDatabase.quarantineForTest(context)

        // 無限に増やさず、常に最新の 1 つだけ保持する
        assertNotNull(result)
        assertEquals(128L, result!!.length())
    }

    @Test
    fun `DBが無い場合は退避しない`() {
        // Robolectric は DB ディレクトリを作るが、ファイル自体は存在しない状態を作る
        dbFile.delete()
        if (dbFile.exists()) return // 環境がファイル生成を許す場合はスキップ

        val result = AppDatabase.quarantineForTest(context)
        assertEquals(null, result)
    }
}
