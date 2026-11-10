package com.tonbiattack.fastestpictureviewer.ui.folder

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * フォルダ一覧の表示設定。
 * 列数はアプリ再起動後も維持する。
 */
interface FolderViewSettings {

    /** 現在の列数 (1〜4) */
    val columnCount: StateFlow<Int>

    /** 列数を保存して即時反映する */
    fun setColumnCount(count: Int)
}

/** SharedPreferences による実装 */
class SharedPreferencesFolderViewSettings(context: Context) : FolderViewSettings {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _columnCount = MutableStateFlow(
        prefs.getInt(KEY_COLUMN_COUNT, DEFAULT_COLUMN_COUNT).coerceIn(MIN_COLUMN_COUNT, MAX_COLUMN_COUNT)
    )
    override val columnCount: StateFlow<Int> = _columnCount.asStateFlow()

    override fun setColumnCount(count: Int) {
        val clamped = count.coerceIn(MIN_COLUMN_COUNT, MAX_COLUMN_COUNT)
        prefs.edit().putInt(KEY_COLUMN_COUNT, clamped).apply()
        _columnCount.value = clamped
    }

    companion object {
        const val DEFAULT_COLUMN_COUNT = 2
        const val MIN_COLUMN_COUNT = 1
        const val MAX_COLUMN_COUNT = 4

        private const val PREFS_NAME = "folder_view_settings"
        private const val KEY_COLUMN_COUNT = "column_count"
    }
}
