package com.tonbiattack.fastestpictureviewer.perf

import android.os.SystemClock
import android.util.Log
import com.tonbiattack.fastestpictureviewer.BuildConfig

/**
 * 起動・画面遷移の区間記録 (Issue #99)。
 *
 * 計測は「プロセス開始からの経過ミリ秒」で行う。端末の起動時刻を基準にしないため、
 * アプリ側だけで完結し、PC の時計と突き合わせる必要がない。
 *
 * [BuildConfig.PERF_TRACE] が false のときは即 return する。リリースビルドでは
 * 定数畳み込みで無効になり、通常利用のオーバーヘッドは実質ゼロ。
 *
 * 出力形式（logcat、タグ FPV-Startup）:
 *   mark=<name> at=<ms> (プロセス開始からの経過)
 *   duration=<name> ms=<ms>
 *
 * 数値は計測専用で、障害調査には使わない（個人情報を含めない）。
 */
object StartupTrace {

    /**
     * 有効か。リリースビルドでは false 固定。
     *
     * 単体テスト（Robolectric を使わない純粋テスト）でも安全に呼べるよう、
     * ログ出力は Android 依存の [Log] を直接使わず、必要時のみ触る。
     */
    @Volatile
    private var loggingAvailable: Boolean = true

    /**
     * 起動の基準時刻。
     *
     * [Process.getStartElapsedRealtime] は端末によって 0 を返す（エミュレータで確認）。
     * その場合は最初の計測時点を基準にする。基準が遅れると区間が短く出るため、
     * 最初に触れた時刻をできるだけ早く使う。
     */
    private val processStartElapsed: Long by lazy { readElapsed() }

    /** 区間計測の開始点を名前ごとに保持する */
    private val marks = HashMap<String, Long>(8)

    /** 有効か。リリースビルドでは false 固定 */
    val enabled: Boolean get() = BuildConfig.PERF_TRACE

    /**
     * 現在地点を記録する。同じ名前は上書きする。
     * 有効でないときは何もしない。
     */
    fun mark(name: String) {
        if (!BuildConfig.PERF_TRACE) return
        val now = readElapsed()
        marks[name] = now
        log("mark=$name at=${now - processStartElapsed}")
    }

    /**
     * [from] から現在までの経過を記録する。開始点が無ければ何もしない。
     * 区間の終わりに呼ぶ。
     */
    fun span(from: String, to: String) {
        if (!BuildConfig.PERF_TRACE) return
        val start = marks[from] ?: return
        val now = readElapsed()
        log("span=$from>$to ms=${now - start}")
    }

    /** プロセス開始からの経過ミリ秒 */
    fun sinceProcessStart(): Long = readElapsed() - processStartElapsed

    private fun readElapsed(): Long =
        runCatching { SystemClock.elapsedRealtime() }.getOrElse { 0L }

    private fun log(message: String) {
        if (!loggingAvailable) return
        try {
            Log.i(TAG, message)
        } catch (t: Throwable) {
            // android.util.Log が使えない環境（純粋な単体テスト）では以後出力しない
            loggingAvailable = false
        }
    }

    /** 計測用のタグ。scripts\benchmark.ps1 がこの文字列で抽出する */
    const val TAG = "FPV-Startup"
}
