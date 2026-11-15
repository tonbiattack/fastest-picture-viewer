package com.tonbiattack.fastestpictureviewer.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * フォルダ一覧キャッシュの永続化テスト (Issue #106)。
 *
 * 起動時に 35 万行を集計しないため、集計結果をテーブルに持ち回る。
 * 保存・読み出し・作り直しが正しく動くことを確認する。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class FolderSummaryCacheDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: FolderSummaryCacheDao

    @Before
    fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = db.folderSummaryCacheDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun summary(folderKey: String, count: Long, latest: Long) = FolderSummaryEntity(
        folderKey = folderKey,
        volumeName = "external_primary",
        relativePath = folderKey.substringAfter(':'),
        imageCount = count,
        latestModifiedSec = latest
    )

    private fun representative(folderKey: String, id: String) = FolderRepresentativeEntity(
        folderKey = folderKey,
        id = id,
        dateModifiedSec = 100L,
        size = 1000L
    )

    @Test
    fun `初期状態ではキャッシュ未作成`() = runTest {
        assertNull("作成前は状態が無い", dao.getState())
        assertEquals(0L, dao.countSummaries())
        assertFalse(
            "未作成なら空を返す",
            dao.observeSummaries().first().isNotEmpty()
        )
    }

    @Test
    fun `作り直すと集計と状態が保存される`() = runTest {
        dao.replaceAll(
            summaries = listOf(
                summary("external_primary:Camera/", 10, 200),
                summary("external_primary:Download/", 5, 100)
            ),
            representatives = listOf(
                representative("external_primary:Camera/", "content://media/1"),
                representative("external_primary:Download/", "content://media/2")
            ),
            imageCount = 15,
            rebuiltAtMs = 1_700_000_000_000
        )

        val state = dao.getState()
        assertNotNull(state)
        assertEquals(1_700_000_000_000, state!!.rebuiltAtMs)
        assertEquals(15L, state.imageCount)
        assertEquals(2L, dao.countSummaries())
    }

    @Test
    fun `集計は更新日時の降順_同日時はフォルダキー昇順で返る`() = runTest {
        dao.replaceAll(
            summaries = listOf(
                summary("external_primary:B/", 1, 100),
                summary("external_primary:A/", 1, 100),
                summary("external_primary:C/", 1, 300)
            ),
            representatives = emptyList(),
            imageCount = 3,
            rebuiltAtMs = 1
        )

        val rows = dao.observeSummaries().first()
        assertEquals(
            listOf(
                "external_primary:C/",
                "external_primary:A/",
                "external_primary:B/"
            ),
            rows.map { it.folderKey }
        )
    }

    @Test
    fun `作り直すと古い行が残らない`() = runTest {
        dao.replaceAll(
            summaries = listOf(summary("external_primary:Old/", 1, 1)),
            representatives = listOf(representative("external_primary:Old/", "content://old")),
            imageCount = 1,
            rebuiltAtMs = 1
        )

        dao.replaceAll(
            summaries = listOf(summary("external_primary:New/", 2, 2)),
            representatives = listOf(representative("external_primary:New/", "content://new")),
            imageCount = 2,
            rebuiltAtMs = 2
        )

        val rows = dao.observeSummaries().first()
        assertEquals(1, rows.size)
        assertEquals("external_primary:New/", rows.first().folderKey)
        assertEquals(2L, dao.getState()!!.imageCount)
    }

    @Test
    fun `大量の行でも分割して保存できる`() = runTest {
        // SQLite のバインド変数上限 (999) を超える行数を入れる
        val count = 1_200
        val summaries = (0 until count).map { index ->
            summary("external_primary:Folder$index/", index.toLong(), index.toLong())
        }
        dao.replaceAll(
            summaries = summaries,
            representatives = emptyList(),
            imageCount = count.toLong(),
            rebuiltAtMs = 1
        )

        assertEquals(count.toLong(), dao.countSummaries())
    }

    @Test
    fun `代表サムネイルも保存される`() = runTest {
        dao.replaceAll(
            summaries = listOf(summary("external_primary:Camera/", 1, 1)),
            representatives = listOf(
                FolderRepresentativeEntity(
                    folderKey = "external_primary:Camera/",
                    id = "content://media/external/images/media/42",
                    dateModifiedSec = 1234L,
                    size = 5678L
                )
            ),
            imageCount = 1,
            rebuiltAtMs = 1
        )

        val rows = dao.observeRepresentatives().first()
        assertEquals(1, rows.size)
        assertEquals("content://media/external/images/media/42", rows.first().id)
        assertEquals(1234L, rows.first().dateModifiedSec)
        assertEquals(5678L, rows.first().size)
    }

    @Test
    fun `キャッシュの有無を状態で判定できる`() = runTest {
        assertFalse("未作成は false", isReady())
        dao.replaceAll(emptyList(), emptyList(), imageCount = 0, rebuiltAtMs = 1)
        assertTrue("作成後は true", isReady())
    }

    private suspend fun isReady(): Boolean {
        val state = dao.getState()
        return state != null && state.rebuiltAtMs > 0L
    }
}
