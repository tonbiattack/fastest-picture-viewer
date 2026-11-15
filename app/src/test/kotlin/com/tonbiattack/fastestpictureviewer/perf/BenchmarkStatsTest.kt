package com.tonbiattack.fastestpictureviewer.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 計測値の集計ロジックのテスト (Issue #99)。
 *
 * 端末を必要としない純粋計算なので、CI で検証できる。
 * 集計を間違えると「速くなった/遅くなった」の判断を誤るため、
 * 境界と欠測の扱いを固定する。
 */
class BenchmarkStatsTest {

    @Test
    fun `奇数件の中央値は中央の値`() {
        val summary = BenchmarkStats.summarize("s", listOf(100L, 300L, 200L))
        assertEquals(200L, summary.p50Ms)
        assertEquals(3, summary.validRuns)
    }

    @Test
    fun `偶数件の中央値は中央2つのうち小さい方`() {
        // 100, 200, 300, 400 → 中央は 200 と 300。実測値に無い平均を報告しない
        val summary = BenchmarkStats.summarize("s", listOf(400L, 100L, 300L, 200L))
        assertEquals(200L, summary.p50Ms)
    }

    @Test
    fun `P95はほぼ最大側の値を返す`() {
        val values = (1L..100L).toList()
        val summary = BenchmarkStats.summarize("s", values)
        // 最近法なので、実測値の中から選ばれる
        assertTrue(summary.p95Ms!! >= 94L)
        assertTrue(summary.p95Ms!! <= 100L)
    }

    @Test
    fun `欠測は除外して数える`() {
        val summary = BenchmarkStats.summarize("s", listOf(100L, null, 300L, null, 200L))
        assertEquals(3, summary.validRuns)
        assertEquals(2, summary.failedRuns)
        assertEquals(200L, summary.p50Ms)
    }

    @Test
    fun `全部失敗なら統計は null`() {
        val summary = BenchmarkStats.summarize("s", listOf(null, null))
        assertEquals(0, summary.validRuns)
        assertNull(summary.p50Ms)
        assertNull(summary.p95Ms)
        assertNull(summary.minMs)
    }

    @Test
    fun `空の入力でも落ちない`() {
        val summary = BenchmarkStats.summarize("s", emptyList())
        assertEquals(0, summary.validRuns)
        assertNull(summary.p50Ms)
    }

    @Test
    fun `1件だけでも統計が出る`() {
        val summary = BenchmarkStats.summarize("s", listOf(1234L))
        assertEquals(1234L, summary.p50Ms)
        assertEquals(1234L, summary.p95Ms)
        assertEquals(1234L, summary.minMs)
    }

    @Test
    fun `目標判定は中央値で行う`() {
        val met = BenchmarkStats.summarize("s", listOf(900L, 950L, 1000L))
        assertEquals(true, met.meetsTarget(1000L))

        val notMet = BenchmarkStats.summarize("s", listOf(1100L, 1200L, 1300L))
        assertEquals(false, notMet.meetsTarget(1000L))

        // 目標が無ければ判定しない
        assertNull(met.meetsTarget(null))
    }

    @Test
    fun `5パーセント以上の悪化を回帰とみなす`() {
        val change = BenchmarkStats.compare(currentMs = 1100L, baselineMs = 1000L)!!
        assertTrue(change.regressed)
        assertFalse(change.improved)
        assertEquals(100L, change.deltaMs)
    }

    @Test
    fun `5パーセント未満の差はノイズとして扱う`() {
        val change = BenchmarkStats.compare(currentMs = 1030L, baselineMs = 1000L)!!
        assertFalse(change.regressed)
        assertFalse(change.improved)
    }

    @Test
    fun `改善も検出する`() {
        val change = BenchmarkStats.compare(currentMs = 800L, baselineMs = 1000L)!!
        assertTrue(change.improved)
        assertFalse(change.regressed)
        assertEquals(-200L, change.deltaMs)
    }

    @Test
    fun `比較できない入力ではnullを返す`() {
        assertNull(BenchmarkStats.compare(null, 1000L))
        assertNull(BenchmarkStats.compare(1000L, null))
        assertNull(BenchmarkStats.compare(1000L, 0L))
    }
}
