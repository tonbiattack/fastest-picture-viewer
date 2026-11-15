package com.tonbiattack.fastestpictureviewer.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * DB の退避処理のテスト (Issue #88)。
 *
 * 利用者が初期化を選んだときだけ動く。大事なのは「既存の退避を上書きしないこと」。
 * 以前の実装は常に 1 つだけ保持していたため、2 回目の破損で 1 回目の退避が消えた。
 *
 * Robolectric のファイル操作には実機と異なる挙動があるため、
 * 実際のファイルシステムを使う純粋な JVM テストとして書く。
 */
class DatabaseResetTest {

    private lateinit var dir: File

    @Before
    fun setup() {
        dir = Files.createTempDirectory("fpv-dbreset").toFile()
    }

    @After
    fun teardown() {
        dir.deleteRecursively()
    }

    private fun create(name: String, size: Int): File =
        File(dir, name).apply {
            writeBytes(ByteArray(size) { 1 })
        }

    @Test
    fun `DB本体とWALとSHMを退避する`() {
        val db = create(AppDatabase.DATABASE_NAME, 64)
        val wal = create("${AppDatabase.DATABASE_NAME}-wal", 32)
        val shm = create("${AppDatabase.DATABASE_NAME}-shm", 16)

        val result = DatabaseReset.resetIn(dir)

        assertEquals("退避したファイル数が違う", 3, result.size)
        result.forEach { assertTrue("退避先が存在しない", it.exists()) }
        // 元ファイルは残さない。残すと作り直した DB に適用されて再び壊れる
        assertFalse("元の DB が残っている", db.exists())
        assertFalse("元の WAL が残っている", wal.exists())
        assertFalse("元の SHM が残っている", shm.exists())
    }

    @Test
    fun `中身が保たれる`() {
        create(AppDatabase.DATABASE_NAME, 128)

        val result = DatabaseReset.resetIn(dir)

        assertEquals(128L, result.first().length())
    }

    @Test
    fun `既存の退避を上書きしない`() {
        // 1 回目の破損
        create(AppDatabase.DATABASE_NAME, 64)
        val first = DatabaseReset.resetIn(dir).first()
        val firstSize = first.length()

        // 2 回目の破損。既存の退避が消えてはいけない
        create(AppDatabase.DATABASE_NAME, 256)
        val second = DatabaseReset.resetIn(dir).first()

        assertTrue("1 回目の退避が消えた", first.exists())
        assertEquals("1 回目の退避が書き換えられた", firstSize, first.length())
        assertTrue("2 回目の退避が無い", second.exists())
        assertEquals(256L, second.length())
        assertNotEquals("同じファイルになっている", first.absolutePath, second.absolutePath)
    }

    @Test
    fun `DBが無ければ何もしない`() {
        assertEquals(0, DatabaseReset.resetIn(dir).size)
    }

    @Test
    fun `DB以外のファイルには触れない`() {
        val other = create("keep-me.jpg", 8)
        create(AppDatabase.DATABASE_NAME, 64)

        DatabaseReset.resetIn(dir)

        assertTrue("DB 以外のファイルが消えた", other.exists())
        assertEquals(8L, other.length())
    }
}
