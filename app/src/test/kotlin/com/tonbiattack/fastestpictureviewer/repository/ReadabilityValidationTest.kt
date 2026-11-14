package com.tonbiattack.fastestpictureviewer.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 実体読み取り検証の安全弁のテスト (Issue #77)。
 *
 * 「削除済みファイルの行が MediaStore に残る」端末で壊れたフォルダを隠しつつ、
 * SD カードの一時的な取り外しなどで実在フォルダを隠さないことを固定する。
 */
class ReadabilityValidationTest {

    @Test
    fun `1件も失敗していなければ記録しない`() {
        assertFalse(ReadabilityValidation.shouldRecord(targetCount = 100, unreadableCount = 0))
        assertNull(ReadabilityValidation.skipReason(100, 0))
    }

    @Test
    fun `少数の失敗は記録する`() {
        // 実機の実測 (2,667 フォルダ中 66 フォルダが欠損) に近い割合
        assertTrue(ReadabilityValidation.shouldRecord(targetCount = 2667, unreadableCount = 66))
    }

    @Test
    fun `半分より多い失敗は一時的な障害とみなして記録しない`() {
        assertFalse(ReadabilityValidation.shouldRecord(targetCount = 100, unreadableCount = 51))
        assertNotNull(ReadabilityValidation.skipReason(100, 51))
    }

    @Test
    fun `ちょうど半分の失敗は記録する`() {
        assertTrue(ReadabilityValidation.shouldRecord(targetCount = 100, unreadableCount = 50))
    }

    @Test
    fun `対象が少ないときは割合で判断しない`() {
        // 小規模ライブラリで全滅していても、本当に消えている可能性があるため記録する
        assertTrue(ReadabilityValidation.shouldRecord(targetCount = 3, unreadableCount = 3))
        assertTrue(ReadabilityValidation.shouldRecord(targetCount = 1, unreadableCount = 1))
    }

    @Test
    fun `しきい値の境界を確認する`() {
        assertEquals(0.5, ReadabilityValidation.SUSPICIOUS_FAILURE_RATIO, 0.0)
        assertTrue(ReadabilityValidation.shouldRecord(9, 4))
        assertFalse(ReadabilityValidation.shouldRecord(9, 5))
    }
}
