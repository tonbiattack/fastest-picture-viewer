package com.tonbiattack.fastestpictureviewer.data

/**
 * バックアップの書き出しを促すかどうかの判定 (Issue #108)。
 *
 * 純粋なロジックだけを持ち、時刻は引数で受け取る。単体テストで境界を固定するため。
 *
 * 方針:
 * - **変更が無ければ促さない。** 書き出すものが無いのに催促しない
 * - 一度も書き出していない場合は、変更があれば促す
 * - 前回の書き出しから [REMIND_INTERVAL_SEC] 経過するまでは促さない
 * - 先送り (スヌーズ) 中は促さない
 */
object BackupReminder {

    /** リマインドを出す間隔 (30 日) */
    const val REMIND_INTERVAL_SEC: Long = 30L * 24 * 60 * 60

    /** 先送りしたときに次に出すまでの間隔 (7 日) */
    const val SNOOZE_INTERVAL_SEC: Long = 7L * 24 * 60 * 60

    /**
     * リマインドを出すべきか。
     *
     * @param status 現在の書き出し状況
     * @param nowSec 現在時刻 (エポック秒)
     * @param snoozedUntilSec 先送りの期限。0 なら先送りしていない
     */
    fun shouldRemind(
        status: BackupStatus,
        nowSec: Long,
        snoozedUntilSec: Long = 0L
    ): Boolean {
        // 書き出すものが無ければ促さない
        if (!status.hasChangesSinceExport) return false
        // 先送り中は出さない
        if (snoozedUntilSec > 0L && nowSec < snoozedUntilSec) return false
        // 一度も書き出していないなら、変更がある時点で促す
        if (!status.hasExported) return true
        // 前回から一定期間経っていなければ出さない
        val elapsed = nowSec - status.lastExportedAtSec
        return elapsed >= REMIND_INTERVAL_SEC
    }

    /**
     * 先送りしたときの期限を返す。
     *
     * @param nowSec 現在時刻 (エポック秒)
     */
    fun snoozeUntil(nowSec: Long): Long = nowSec + SNOOZE_INTERVAL_SEC

    /**
     * リマインドの本文を作る。
     *
     * 日時を出すのは「一度でも書き出した」場合だけ。
     * 一度も書き出していない場合は、その旨を出す。
     */
    fun message(status: BackupStatus, formattedLastExport: String?): String {
        return if (status.hasExported && !formattedLastExport.isNullOrBlank()) {
            "前回のバックアップ（$formattedLastExport）から時間が経っています。" +
                "タグやお気に入りを書き出しておくと安心です。"
        } else {
            "タグやお気に入りをまだ書き出していません。端末の故障に備えて書き出しておくと安心です。"
        }
    }
}
