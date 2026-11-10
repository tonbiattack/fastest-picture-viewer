package com.tonbiattack.fastestpictureviewer.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** ボリューム同期状態の保存・更新・削除テスト */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class VolumeSyncStateDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: VolumeSyncStateDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = AppDatabase.buildInMemory(context)
        dao = db.volumeSyncStateDao()
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun `ボリューム名ごとに世代番号を保存できる`() = runTest {
        dao.upsert(VolumeSyncStateEntity("external_primary", contentGeneration = 10, lastScanSec = 100L))
        dao.upsert(VolumeSyncStateEntity("sdcard", contentGeneration = 20, lastScanSec = 200L))

        assertEquals(10L, dao.get("external_primary")?.contentGeneration)
        assertEquals(20L, dao.get("sdcard")?.contentGeneration)
        assertEquals(2, dao.getAll().size)
    }

    @Test
    fun `同じボリュームは上書きされる`() = runTest {
        dao.upsert(VolumeSyncStateEntity("external_primary", contentGeneration = 10, lastScanSec = 100L))
        dao.upsert(VolumeSyncStateEntity("external_primary", contentGeneration = 11, lastScanSec = 300L))

        val state = dao.get("external_primary")
        assertEquals(11L, state?.contentGeneration)
        assertEquals(300L, state?.lastScanSec)
        assertEquals(1, dao.getAll().size)
    }

    @Test
    fun `世代番号が取得できない環境ではnullを保存できる`() = runTest {
        dao.upsert(VolumeSyncStateEntity("external_primary", contentGeneration = null, lastScanSec = 100L))
        assertNull(dao.get("external_primary")?.contentGeneration)
    }

    @Test
    fun `ボリューム削除時に状態だけ消せる`() = runTest {
        dao.upsert(VolumeSyncStateEntity("sdcard", contentGeneration = 20, lastScanSec = 200L))
        dao.remove("sdcard")
        assertNull(dao.get("sdcard"))
    }
}
