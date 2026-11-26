package com.tonbiattack.fastestpictureviewer.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Auto Backup 用の控えの保存・読み込み (Issue #151)。
 *
 * 実ファイルを使う。Android に依存しないため JVM テストで検証できる。
 */
class AutoBackupStoreTest {

    private lateinit var filesDir: File
    private lateinit var store: AutoBackupStore

    @Before
    fun setUp() {
        filesDir = Files.createTempDirectory("auto-backup-test").toFile()
        store = AutoBackupStore(filesDir)
    }

    @After
    fun tearDown() {
        filesDir.deleteRecursively()
    }

    @Test
    fun `保存した控えを読み戻せる`() {
        val json = """{"version":1,"tags":[]}"""

        val result = store.save(json)

        assertTrue(result is AutoBackupStore.WriteResult.Saved)
        assertEquals(json, store.load())
        assertTrue(store.exists())
    }

    @Test
    fun `控えは専用ディレクトリに置かれる`() {
        store.save("""{"version":1}""")

        // files/auto_backup/auto_backup.json。他と混ざらないこと
        assertEquals(
            File(File(filesDir, AutoBackupStore.DIRECTORY_NAME), AutoBackupStore.FILE_NAME),
            store.file
        )
        assertTrue(store.file.parentFile?.isDirectory == true)
    }

    @Test
    fun `控えが無ければ null を返す`() {
        assertNull(store.load())
        assertFalse(store.exists())
        assertEquals(0L, store.sizeBytes())
    }

    @Test
    fun `空のファイルは控えとみなさない`() {
        store.file.parentFile?.mkdirs()
        store.file.writeText("")

        assertFalse(store.exists())
        assertNull(store.load())
    }

    @Test
    fun `保存すると古い内容を置き換える`() {
        store.save("""{"version":1,"note":"old"}""")
        store.save("""{"version":1,"note":"new"}""")

        assertEquals("""{"version":1,"note":"new"}""", store.load())
    }

    @Test
    fun `一時ファイルを残さない`() {
        store.save("""{"version":1}""")

        val leftovers = store.file.parentFile
            ?.listFiles()
            ?.filter { it.name.endsWith(".tmp") }
            ?.toList()
            .orEmpty()
        assertTrue("一時ファイルが残っている: $leftovers", leftovers.isEmpty())
    }

    @Test
    fun `25MB を超える控えは書かずに結果で知らせる`() {
        // 上限ちょうど + 1 バイト
        val oversize = "a".repeat(AutoBackupStore.MAX_BYTES + 1)

        val result = store.save(oversize)

        assertTrue("上限超過が検出されていない: $result", result is AutoBackupStore.WriteResult.TooLarge)
        // 黙って切り捨てず、既存の控えも壊さない
        assertFalse(store.exists())
    }

    @Test
    fun `上限を超えた書き込みは既存の控えを壊さない`() {
        store.save("""{"version":1,"note":"keep"}""")

        val result = store.save("a".repeat(AutoBackupStore.MAX_BYTES + 1))

        assertTrue(result is AutoBackupStore.WriteResult.TooLarge)
        assertEquals("""{"version":1,"note":"keep"}""", store.load())
    }

    @Test
    fun `控えを削除できる`() {
        store.save("""{"version":1}""")
        assertTrue(store.delete())

        assertFalse(store.exists())
        assertNull(store.load())
    }

    @Test
    fun `サイズを測れる`() {
        val json = """{"version":1}"""
        store.save(json)

        assertEquals(json.toByteArray(Charsets.UTF_8).size.toLong(), store.sizeBytes())
    }

    @Test
    fun `既存の控えを上書きしても読める`() {
        // 2 回目の保存が rename で失敗しても、直接書き込みへ退避して読めること
        store.save("""{"version":1,"note":"first"}""")
        store.save("""{"version":1,"note":"second"}""")

        val loaded = store.load()
        assertNotNull(loaded)
        assertTrue(loaded!!.contains("second"))
    }
}
