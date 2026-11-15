package com.tonbiattack.fastestpictureviewer.perf

/**
 * 計測試行の集計 (Issue #99)。
 *
 * P50（中央値）と P95 を出す。平均だけだと、たまに起きる遅延（GC・初回デコード・
 * 端末の負荷）が埋もれるため、分布の上側も見る。
 *
 * 集計は端末を必要としない純粋ロジックにして、CI でテストする。
 */
object BenchmarkStats {

    /** 集計結果 */
    data class Summary(
        val scenario: String,
        val runs: Int,
        /** 有効な試行数（失敗・欠測を除く） */
        val validRuns: Int,
        val minMs: Long?,
        val maxMs: Long?,
        val p50Ms: Long?,
        val p95Ms: Long?,
        /** 失敗した試行数 */
        val failedRuns: Int
    ) {
        /** 目標を満たしたか。目標が無ければ null */
        fun meetsTarget(targetMs: Long?): Boolean? {
            val p50 = p50Ms ?: return null
            val target = targetMs ?: return null
            return p50 < target
        }
    }

    /**
     * 値を集計する。null は失敗・欠測として除外し、件数だけ数える。
     *
     * 中央値の定義: 件数が奇数なら中央、偶数なら中央 2 つの平均（四捨五入）。
     * P95 は「95% の試行がこの値以下」になる位置を最近法で選ぶ。
     */
    fun summarize(scenario: String, values: List<Long?>, runs: Int = values.size): Summary {
        val valid = values.filterNotNull().sorted()
        val failed = values.count { it == null }
        if (valid.isEmpty()) {
            return Summary(
                scenario = scenario,
                runs = runs,
                validRuns = 0,
                minMs = null,
                maxMs = null,
                p50Ms = null,
                p95Ms = null,
                failedRuns = failed
            )
        }
        return Summary(
            scenario = scenario,
            runs = runs,
            validRuns = valid.size,
            minMs = valid.first(),
            maxMs = valid.last(),
            p50Ms = percentile(valid, 50.0),
            p95Ms = percentile(valid, 95.0),
            failedRuns = failed
        )
    }

    /**
     * パーセンタイルを最近法で求める。
     *
     * 補間しないのは、実測値に無い値を報告しないため
     * （「P95 = 1234.5ms」のような不自然な数字を避ける）。
     *
     * 中央値（P50）で件数が偶数なら、中央 2 つのうち小さい方を採る。
     * 平均にすると実測値に無い値（例: 200 と 300 で 250）を報告してしまい、
     * 「どの試行の値か」を追えなくなる。
     */
    fun percentile(sortedValues: List<Long>, percentile: Double): Long? {
        if (sortedValues.isEmpty()) return null
        if (sortedValues.size == 1) return sortedValues.first()
        // ceil(p/100 * n) - 1 で「n 件中の位置」を決める。
        // 例: 4 件の P50 → ceil(2) - 1 = 1（0 始まりで 2 番目 = 小さい側の中央）
        //     100 件の P95 → ceil(95) - 1 = 94（95 番目の値）
        val position = Math.ceil(percentile / 100.0 * sortedValues.size).toInt()
        val index = (position - 1).coerceIn(0, sortedValues.size - 1)
        return sortedValues[index]
    }

    /**
     * 前回のベースラインと比べた変化。回帰の検知に使う。
     *
     * @param currentMs 今回の P50
     * @param baselineMs ベースラインの P50
     */
    fun compare(currentMs: Long?, baselineMs: Long?): Change? {
        if (currentMs == null || baselineMs == null || baselineMs <= 0) return null
        val deltaMs = currentMs - baselineMs
        val ratio = currentMs.toDouble() / baselineMs
        return Change(
            deltaMs = deltaMs,
            // 5% 未満の差はノイズとして扱う（端末の状態で容易に動くため）
            regressed = ratio >= REGRESSION_THRESHOLD,
            improved = ratio <= (1.0 / REGRESSION_THRESHOLD)
        )
    }

    data class Change(val deltaMs: Long, val regressed: Boolean, val improved: Boolean)

    /** これを超える悪化を回帰とみなす（1.05 = 5% 増） */
    const val REGRESSION_THRESHOLD = 1.05
}
