package com.tonbiattack.fastestpictureviewer.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * バックアップの書き出し状況 (Issue #108)。
 *
 * 「最後にいつ書き出したか」と「書き出した後にタグ・お気に入りを変えたか」だけを持つ。
 * 起動時に読むのは SharedPreferences の数値 2 つだけで、DB も MediaStore も触らない。
 * 起動・復帰・閲覧を待たせないための最小構成。
 */
data class BackupStatus(
    /** 最後に書き出した日時 (エポック秒)。0 は一度も書き出していない */
    val lastExportedAtSec: Long = 0L,
    /**
     * 最後に書き出した後に、タグ・お気に入り・除外を変更したか。
     *
     * 変更が無いのに書き出しを促さないためのフラグ。
     * 書き出しに成功した時点で false に戻す。
     */
    val hasChangesSinceExport: Boolean = false
) {
    /** 一度でも書き出したか */
    val hasExported: Boolean get() = lastExportedAtSec > 0L
}

/** バックアップの書き出し状況の保存先 */
interface BackupStatusStore {

    /** 現在の状況。起動時に購読する */
    val status: StateFlow<BackupStatus>

    /** 書き出しに成功したことを記録する。変更フラグも下ろす */
    fun markExported(atSec: Long)

    /**
     * タグ・お気に入り・除外を変更したことを記録する。
     *
     * 書き出し前の変更でも呼ぶ。一度も書き出していない場合は
     * 「書き出すものが有る」と判断できるよう true にする。
     */
    fun markChanged()
}

/**
 * SharedPreferences による実装。
 *
 * 保存するのは数値と真偽値だけ。書き込みは変更時のみで、閲覧操作のたびには呼ばない。
 */
class SharedPreferencesBackupStatusStore(context: Context) : BackupStatusStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _status = MutableStateFlow(loadFromPrefs())
    override val status: StateFlow<BackupStatus> = _status.asStateFlow()

    override fun markExported(atSec: Long) {
        val next = BackupStatus(
            lastExportedAtSec = atSec.coerceAtLeast(0L),
            hasChangesSinceExport = false
        )
        prefs.edit {
            putLong(KEY_LAST_EXPORTED, next.lastExportedAtSec)
            putBoolean(KEY_HAS_CHANGES, next.hasChangesSinceExport)
        }
        _status.value = next
    }

    override fun markChanged() {
        // 既に true なら書き込まない。連続した変更で毎回 I/O しないため
        if (_status.value.hasChangesSinceExport) return
        val next = _status.value.copy(hasChangesSinceExport = true)
        prefs.edit { putBoolean(KEY_HAS_CHANGES, true) }
        _status.value = next
    }

    private fun loadFromPrefs(): BackupStatus = BackupStatus(
        lastExportedAtSec = prefs.getLong(KEY_LAST_EXPORTED, 0L).coerceAtLeast(0L),
        hasChangesSinceExport = prefs.getBoolean(KEY_HAS_CHANGES, false)
    )

    private companion object {
        const val PREFS_NAME = "backup_status"
        const val KEY_LAST_EXPORTED = "lastExportedAtSec"
        const val KEY_HAS_CHANGES = "hasChangesSinceExport"
    }
}
