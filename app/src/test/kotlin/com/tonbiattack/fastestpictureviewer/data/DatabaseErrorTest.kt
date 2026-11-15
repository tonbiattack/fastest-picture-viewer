package com.tonbiattack.fastestpictureviewer.data

import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteDiskIOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DB 異常の判定テスト (Issue #88)。
 *
 * 実機では、破損した DB を開いた例外が Room 内部のコルーチンで発生し、
 * 捕捉できずに起動直後クラッシュした。原因を「破損」と「その他」に分け、
 * 初期化を勧めてよいのは破損のときだけであることを固定する。
 */
class DatabaseErrorTest {

    @Test
    fun `file is not a database は破損と判定する`() {
        // 実機で観測したメッセージそのもの
        val error = SQLiteDatabaseCorruptException(
            "file is not a database (code 26 SQLITE_NOTADB): , while compiling: PRAGMA journal_mode"
        )
        assertTrue(DatabaseError.isCorruption(error))
        assertTrue(DatabaseError.canOfferReset(error))
    }

    @Test
    fun `malformed も破損と判定する`() {
        val error = SQLiteDatabaseCorruptException("database disk image is malformed")
        assertTrue(DatabaseError.canOfferReset(error))
    }

    @Test
    fun `例外が包まれていても破損を検出する`() {
        val wrapped = IllegalStateException(
            "初期化に失敗",
            RuntimeException(SQLiteDatabaseCorruptException("file is not a database"))
        )
        assertTrue(DatabaseError.isCorruption(wrapped))
    }

    @Test
    fun `容量不足では初期化を勧めない`() {
        // 直せば戻るデータまで失わせない
        val error = SQLiteDiskIOException("disk I/O error")
        assertFalse(DatabaseError.canOfferReset(error))
    }

    @Test
    fun `権限エラーでは初期化を勧めない`() {
        val error = IllegalStateException("attempt to write a readonly database")
        assertFalse(DatabaseError.canOfferReset(error))
    }

    @Test
    fun `null でも落ちない`() {
        assertFalse(DatabaseError.isCorruption(null))
        assertFalse(DatabaseError.canOfferReset(null))
    }

    @Test
    fun `案内に個人情報を含めない`() {
        val text = DatabaseError.RESET_DESCRIPTION + DatabaseError.RESET_DONE
        assertFalse(text.contains("/storage"))
        assertFalse(text.contains("DCIM"))
        assertFalse(text.contains(".db"))
    }
}
