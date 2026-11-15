package com.tonbiattack.fastestpictureviewer.data

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

/**
 * 破損時に DB を削除しないことのテスト (Issue #88)。
 *
 * AndroidX の既定実装は、開いていない DB ファイルを削除する。
 * お気に入り・タグを含む DB を勝手に消さないことを固定する。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PreservingOpenHelperFactoryTest {

    private fun fakeDatabase(): SupportSQLiteDatabase =
        Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java)
        ) { _, _, _ -> null } as SupportSQLiteDatabase

    /** デリゲートには渡された設定だけを記録し、実ファイルは触らない */
    private class CapturingFactory : SupportSQLiteOpenHelper.Factory {
        var configuration: SupportSQLiteOpenHelper.Configuration? = null

        override fun create(
            configuration: SupportSQLiteOpenHelper.Configuration
        ): SupportSQLiteOpenHelper {
            this.configuration = configuration
            return object : SupportSQLiteOpenHelper {
                override val databaseName: String = configuration.name ?: ""
                override val writableDatabase: SupportSQLiteDatabase = fake()
                override val readableDatabase: SupportSQLiteDatabase = fake()
                override fun setWriteAheadLoggingEnabled(enabled: Boolean) = Unit
                override fun close() = Unit
            }
        }

        private fun fake(): SupportSQLiteDatabase =
            Proxy.newProxyInstance(
                javaClass.classLoader,
                arrayOf(SupportSQLiteDatabase::class.java)
            ) { _, _, _ -> null } as SupportSQLiteDatabase
    }

    private fun configuration(
        name: String,
        callback: SupportSQLiteOpenHelper.Callback,
        allowDataLoss: Boolean = false
    ) = SupportSQLiteOpenHelper.Configuration
        .builder(ApplicationProvider.getApplicationContext())
        .name(name)
        .callback(callback)
        .allowDataLossOnRecovery(allowDataLoss)
        .build()

    private fun originalCallback(
        onCorruption: () -> Unit = {},
        onCreate: () -> Unit = {},
        version: Int = 1
    ) = object : SupportSQLiteOpenHelper.Callback(version) {
        override fun onCreate(db: SupportSQLiteDatabase) = onCreate()
        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
            Unit
        override fun onCorruption(db: SupportSQLiteDatabase) = onCorruption()
    }

    @Test
    fun `破損を検出しても元のコールバックを呼ばず削除しない`() {
        var originalCorruptionCalled = false
        val delegate = CapturingFactory()
        PreservingOpenHelperFactory(delegate).create(
            configuration("preserve.db", originalCallback(onCorruption = { originalCorruptionCalled = true }))
        )

        // 例外を投げず、既定の削除処理（元コールバック）も呼ばない
        delegate.configuration!!.callback.onCorruption(fakeDatabase())

        assertFalse("元の破損処理（削除）が呼ばれた", originalCorruptionCalled)
    }

    @Test
    fun `通常のライフサイクルは元のコールバックに委譲する`() {
        var created = false
        val delegate = CapturingFactory()
        PreservingOpenHelperFactory(delegate).create(
            configuration(
                "lifecycle.db",
                originalCallback(onCreate = { created = true }, version = 3)
            )
        )

        delegate.configuration!!.callback.onCreate(fakeDatabase())

        assertTrue("作成処理が委譲されていない", created)
        assertEquals("DB 名が変わっている", "lifecycle.db", delegate.configuration!!.name)
    }

    @Test
    fun `データ消失を許容する設定を打ち消さない`() {
        val delegate = CapturingFactory()
        PreservingOpenHelperFactory(delegate).create(
            configuration("loss.db", originalCallback(), allowDataLoss = true)
        )

        assertTrue("明示された設定が失われている", delegate.configuration!!.allowDataLossOnRecovery)
    }
}
