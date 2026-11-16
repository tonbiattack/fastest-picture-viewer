package com.tonbiattack.fastestpictureviewer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * バックアップのリマインド判定のテスト (Issue #108)。
 *
 * 大事なのは「変更が無いのに催促しない」こと。書き出すものが無いのに
 * バナーが出続けると、利用者は案内を無視するようになる。
 */
class BackupReminderTest {

    private val day = 24L * 60 * 60

    /** 一度も書き出していない状態 */
    private fun neverExported(changed: Boolean = true) = BackupStatus(
        lastExportedAtSec = 0L,
        hasChangesSinceExport = changed
    )

    /** 書き出し済みの状態 */
    private fun exportedAt(sec: Long, changed: Boolean = false) = BackupStatus(
        lastExportedAtSec = sec,
        hasChangesSinceExport = changed
    )

    // ------------------------------------------------------------------
    // 変更が無ければ促さない
    // ------------------------------------------------------------------

    @Test
    fun `変更が無ければ一度も書き出していなくても促さない`() {
        val status = neverExported(changed = false)

        assertFalse(
            "書き出すものが無いのに催促している",
            BackupReminder.shouldRemind(status, nowSec = 1_000_000L)
        )
    }

    @Test
    fun `変更が無ければ期間が過ぎていても促さない`() {
        val status = exportedAt(1_000_000L, changed = false)

        assertFalse(
            BackupReminder.shouldRemind(status, nowSec = 1_000_000L + 365 * day)
        )
    }

    // ------------------------------------------------------------------
    // 一度も書き出していない場合
    // ------------------------------------------------------------------

    @Test
    fun `一度も書き出しておらず変更があれば促す`() {
        val status = neverExported(changed = true)

        assertTrue(BackupReminder.shouldRemind(status, nowSec = 1_000_000L))
    }

    // ------------------------------------------------------------------
    // 前回の書き出しからの経過
    // ------------------------------------------------------------------

    @Test
    fun `前回から30日未満なら促さない`() {
        val base = 1_000_000L
        val status = exportedAt(base, changed = true)

        assertFalse(
            "30 日未満で催促している",
            BackupReminder.shouldRemind(status, nowSec = base + 29 * day)
        )
    }

    @Test
    fun `前回からちょうど30日で促す`() {
        val base = 1_000_000L
        val status = exportedAt(base, changed = true)

        assertTrue(
            "30 日ちょうどで催促していない",
            BackupReminder.shouldRemind(status, nowSec = base + 30 * day)
        )
    }

    @Test
    fun `前回から30日を超えても変更が無ければ促さない`() {
        val base = 1_000_000L
        val status = exportedAt(base, changed = false)

        assertFalse(BackupReminder.shouldRemind(status, nowSec = base + 60 * day))
    }

    // ------------------------------------------------------------------
    // 先送り
    // ------------------------------------------------------------------

    @Test
    fun `先送り中は促さない`() {
        val base = 1_000_000L
        val status = exportedAt(base, changed = true)
        val now = base + 40 * day
        val snoozedUntil = now + 7 * day

        assertFalse(
            "先送り中なのに催促している",
            BackupReminder.shouldRemind(status, nowSec = now, snoozedUntilSec = snoozedUntil)
        )
    }

    @Test
    fun `先送りの期限を過ぎたら再び促す`() {
        val base = 1_000_000L
        val status = exportedAt(base, changed = true)
        val snoozedUntil = base + 40 * day
        val now = snoozedUntil + 1

        assertTrue(
            "先送りの期限を過ぎても催促していない",
            BackupReminder.shouldRemind(status, nowSec = now, snoozedUntilSec = snoozedUntil)
        )
    }

    @Test
    fun `先送りは7日後を期限にする`() {
        val now = 1_000_000L

        assertEquals(now + 7 * day, BackupReminder.snoozeUntil(now))
    }

    @Test
    fun `先送りしていないときは期限0で判定できる`() {
        val base = 1_000_000L
        val status = exportedAt(base, changed = true)

        assertTrue(
            BackupReminder.shouldRemind(status, nowSec = base + 30 * day, snoozedUntilSec = 0L)
        )
    }

    // ------------------------------------------------------------------
    // 本文
    // ------------------------------------------------------------------

    @Test
    fun `一度も書き出していないときはその旨を伝える`() {
        val message = BackupReminder.message(neverExported(), formattedLastExport = null)

        assertTrue("未書き出しと分からない: $message", message.contains("まだ書き出していません"))
    }

    @Test
    fun `書き出し済みなら前回の日時を伝える`() {
        val message = BackupReminder.message(
            exportedAt(1_000_000L, changed = true),
            formattedLastExport = "2026/10/01"
        )

        assertTrue("前回の日時が出ていない: $message", message.contains("2026/10/01"))
    }

    @Test
    fun `日時が空でも本文が壊れない`() {
        val message = BackupReminder.message(exportedAt(1_000_000L, changed = true), null)

        assertTrue("本文が空", message.isNotBlank())
        assertTrue("未書き出し扱いになっていない", message.contains("まだ書き出していません"))
    }
}
