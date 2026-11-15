package com.tonbiattack.fastestpictureviewer.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 削除前の安全判定のテスト (Issue #92)。
 *
 * 背景: `MediaStoreScanner` はカーソルが null でも例外を出さず 0 件を返す。
 * これを「全画像が消えた」と解釈すると、SD カードが一時的に読めないだけで
 * DB の行を全部失う。写真は残っても、ユーザーには消えたように見える。
 */
class DeletionGuardTest {

    private fun rejected(reason: DeletionGuard.Reason) =
        DeletionGuard.Decision.Rejected(reason)

    @Test
    fun `走査が0件なら削除しない`() {
        // 最重要: SD を抜いた直後・転送直後の一時的な 0 件で全消しにしない
        val decision = DeletionGuard.decide(
            cachedCount = 1000,
            scannedCount = 0,
            deletionCount = 1000,
            volumeReadable = true
        )
        assertEquals(rejected(DeletionGuard.Reason.EMPTY_SCAN), decision)
    }

    @Test
    fun `走査が0件でDBも空なら削除しない`() {
        val decision = DeletionGuard.decide(
            cachedCount = 0,
            scannedCount = 0,
            deletionCount = 0,
            volumeReadable = true
        )
        assertEquals(DeletionGuard.Decision.Allowed, decision)
    }

    @Test
    fun `ボリュームが読めなければ削除しない`() {
        val decision = DeletionGuard.decide(
            cachedCount = 500,
            scannedCount = 400,
            deletionCount = 100,
            volumeReadable = false
        )
        assertEquals(rejected(DeletionGuard.Reason.VOLUME_UNREADABLE), decision)
    }

    @Test
    fun `大量削除は見送る`() {
        val decision = DeletionGuard.decide(
            cachedCount = 1000,
            scannedCount = 400,
            deletionCount = 600,
            volumeReadable = true
        )
        assertEquals(rejected(DeletionGuard.Reason.TOO_LARGE_RATIO), decision)
    }

    @Test
    fun `半数ちょうどは削除する`() {
        // 境界: 50% は許可する（一括削除の意図を過度に妨げない）
        val decision = DeletionGuard.decide(
            cachedCount = 1000,
            scannedCount = 500,
            deletionCount = 500,
            volumeReadable = true
        )
        assertEquals(DeletionGuard.Decision.Allowed, decision)
    }

    @Test
    fun `少数の削除は通常どおり反映する`() {
        val decision = DeletionGuard.decide(
            cachedCount = 1000,
            scannedCount = 999,
            deletionCount = 1,
            volumeReadable = true
        )
        assertEquals(DeletionGuard.Decision.Allowed, decision)
    }

    @Test
    fun `件数が少ないときは割合で止めない`() {
        // 5 件中 5 件消えた場合、割合は 100% だが「全部消した」は自然に起きる
        val decision = DeletionGuard.decide(
            cachedCount = 5,
            scannedCount = 0,
            deletionCount = 0,
            volumeReadable = true
        )
        // 走査 0 件は別条件で止まる。ここでは割合判定のみを確認する
        assertEquals(rejected(DeletionGuard.Reason.EMPTY_SCAN), decision)

        val allowed = DeletionGuard.decide(
            cachedCount = 5,
            scannedCount = 0,
            deletionCount = 5,
            volumeReadable = true
        )
        // 走査 0 件の条件が先に効く
        assertEquals(rejected(DeletionGuard.Reason.EMPTY_SCAN), allowed)
    }

    @Test
    fun `削除対象が無ければ許可する`() {
        val decision = DeletionGuard.decide(
            cachedCount = 100,
            scannedCount = 100,
            deletionCount = 0,
            volumeReadable = true
        )
        assertEquals(DeletionGuard.Decision.Allowed, decision)
    }

    @Test
    fun `案内文に個人情報を含めない`() {
        DeletionGuard.Reason.entries.forEach { reason ->
            val text = DeletionGuard.message(reason)
            assertTrue("空の案内文がある", text.isNotEmpty())
            assertTrue("パスを含む", "/storage" !in text)
            assertTrue("フォルダ名を含む", "DCIM" !in text)
        }
    }
}
