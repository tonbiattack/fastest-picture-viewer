package com.tonbiattack.fastestpictureviewer.repository

import org.junit.Assert.assertEquals
import org.junit.Test

/** MediaStore 世代番号による走査種別の判定テスト */
class VolumeSyncPolicyTest {

    @Test
    fun `完全同期要求なら常に全件走査する`() {
        assertEquals(
            VolumeScanKind.FULL,
            VolumeSyncPolicy.decide(isFullSync = true, currentGeneration = 10L, storedGeneration = 10L)
        )
    }

    @Test
    fun `世代番号が同じなら走査を省略する`() {
        assertEquals(
            VolumeScanKind.SKIP,
            VolumeSyncPolicy.decide(isFullSync = false, currentGeneration = 10L, storedGeneration = 10L)
        )
    }

    @Test
    fun `世代番号が変わっても通常は差分走査にする`() {
        // 写真が追加されるたびに全件走査すると大量画像で遅くなるため、差分に留める
        assertEquals(
            VolumeScanKind.INCREMENTAL,
            VolumeSyncPolicy.decide(isFullSync = false, currentGeneration = 11L, storedGeneration = 10L)
        )
    }

    @Test
    fun `世代番号を取得できない環境では差分走査にフォールバックする`() {
        assertEquals(
            VolumeScanKind.INCREMENTAL,
            VolumeSyncPolicy.decide(isFullSync = false, currentGeneration = null, storedGeneration = 10L)
        )
    }

    @Test
    fun `初回など保存済み世代がない場合は差分走査する`() {
        // 前回値がない状態で差分走査にすると取りこぼす可能性があるが、
        // DB が空の場合 Repository が完全同期に切り替えるためここでは差分でよい
        assertEquals(
            VolumeScanKind.INCREMENTAL,
            VolumeSyncPolicy.decide(isFullSync = false, currentGeneration = 10L, storedGeneration = null)
        )
    }
}
