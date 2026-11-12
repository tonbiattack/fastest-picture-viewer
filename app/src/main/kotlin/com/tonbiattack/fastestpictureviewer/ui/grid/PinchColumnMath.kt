package com.tonbiattack.fastestpictureviewer.ui.grid

import kotlin.math.roundToInt

/**
 * ピンチ操作で一時的に変化させるグリッド列数の計算 (Issue #45)。
 *
 * ピンチイン (2本指を近づける = scale < 1) で列数を増やし、
 * ピンチアウト (scale > 1) で列数を減らす。
 * 保存済みの列数は変更せず、指を離したら [baseColumns] に戻す。
 */
object PinchColumnMath {

    /** 列数の下限 */
    const val MIN_COLUMN_COUNT = 2

    /** 列数の上限 */
    const val MAX_COLUMN_COUNT = 6

    /**
     * 1 段階列数が変わるのに必要な拡大率の変化。
     * 小さすぎると僅かな指の動きで列数が暴れ、大きすぎると操作しにくい。
     */
    const val STEP_RATIO = 0.18f

    /**
     * ピンチ中の累積拡大率から一時的な列数を求める。
     *
     * @param baseColumns 保存済みの列数 (操作前の基準)
     * @param cumulativeScale ピンチ開始からの累積拡大率 (1.0 = 変化なし)
     */
    fun targetColumns(baseColumns: Int, cumulativeScale: Float): Int {
        val base = baseColumns.coerceIn(MIN_COLUMN_COUNT, MAX_COLUMN_COUNT)
        if (!cumulativeScale.isFinite() || cumulativeScale <= 0f) return base
        // ピンチインで 1/scale が大きくなり、列数が増える方向へ段階的に動く
        val steps = ((1f / cumulativeScale) - 1f) / STEP_RATIO
        return (base + steps.roundToInt()).coerceIn(MIN_COLUMN_COUNT, MAX_COLUMN_COUNT)
    }
}
