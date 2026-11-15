package com.tonbiattack.fastestpictureviewer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DB 復旧の判定テスト (Issue #88)。
 *
 * 壊れた DB は退避して作り直すが、容量不足や権限の問題で作り直すと
 * 貴重なキャッシュを無駄に失う。判定を誤らないことを固定する。
 */
class DatabaseRecoveryTest {

    @Test
    fun `malformed は破損と判定する`() {
        val reason = DatabaseRecovery.classify(
            message = "database disk image is malformed (code 11 SQLITE_CORRUPT)",
            isSqliteException = true
        )
        assertEquals(DatabaseRecovery.Reason.CORRUPTED, reason)
        assertTrue(DatabaseRecovery.shouldRecreate(reason))
    }

    @Test
    fun `file is not a database も破損と判定する`() {
        val reason = DatabaseRecovery.classify(
            message = "file is not a database",
            isSqliteException = true
        )
        assertEquals(DatabaseRecovery.Reason.CORRUPTED, reason)
        assertTrue(DatabaseRecovery.shouldRecreate(reason))
    }

    @Test
    fun `スキーマ不整合は作り直す対象にする`() {
        val reason = DatabaseRecovery.classify(
            message = "no such table: images",
            isSqliteException = true
        )
        assertEquals(DatabaseRecovery.Reason.SCHEMA_MISMATCH, reason)
        assertTrue(DatabaseRecovery.shouldRecreate(reason))
    }

    @Test
    fun `容量不足では作り直さない`() {
        // 権限・容量の問題は DB を作り直しても解決しない。キャッシュを無駄に消さない
        val reason = DatabaseRecovery.classify(
            message = "disk I/O error",
            isSqliteException = true
        )
        assertEquals(DatabaseRecovery.Reason.NOT_CORRUPT, reason)
        assertFalse(DatabaseRecovery.shouldRecreate(reason))
    }

    @Test
    fun `権限エラーでは作り直さない`() {
        val reason = DatabaseRecovery.classify(
            message = "attempt to write a readonly database",
            isSqliteException = true
        )
        assertFalse(DatabaseRecovery.shouldRecreate(reason))
    }

    @Test
    fun `SQLite以外の例外では作り直さない`() {
        val reason = DatabaseRecovery.classify(
            message = "OutOfMemory",
            isSqliteException = false
        )
        assertFalse(DatabaseRecovery.shouldRecreate(reason))
    }

    @Test
    fun `メッセージがnullでも落ちない`() {
        val reason = DatabaseRecovery.classify(message = null, isSqliteException = true)
        assertFalse(DatabaseRecovery.shouldRecreate(reason))
    }

    @Test
    fun `大文字小文字を問わず破損を検出する`() {
        val reason = DatabaseRecovery.classify(
            message = "DATABASE DISK IMAGE IS MALFORMED",
            isSqliteException = true
        )
        assertEquals(DatabaseRecovery.Reason.CORRUPTED, reason)
    }

    @Test
    fun `案内文に個人情報を含めない`() {
        // フォルダ名・ファイル名・パスを出さない
        val text = DatabaseRecovery.MESSAGE + DatabaseRecovery.RESTORE_HINT
        assertFalse(text.contains("/storage"))
        assertFalse(text.contains("DCIM"))
        assertFalse(text.contains(".db"))
    }
}
