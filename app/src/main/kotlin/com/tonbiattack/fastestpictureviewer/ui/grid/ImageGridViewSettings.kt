package com.tonbiattack.fastestpictureviewer.ui.grid

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * フォルダ内画像グリッドの表示設定。
 * 列数はフォルダ一覧の列数設定とは独立して保存する (Issue #34)。
 */
interface ImageGridViewSettings {

    /** 現在の列数 (2〜6) */
    val columnCount: StateFlow<Int>

    /** 列数を保存して即時反映する */
    fun setColumnCount(count: Int)
}

/** SharedPreferences による実装 */
class SharedPreferencesImageGridViewSettings(context: Context) : ImageGridViewSettings {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _columnCount = MutableStateFlow(
        prefs.getInt(KEY_COLUMN_COUNT, DEFAULT_COLUMN_COUNT)
            .coerceIn(MIN_COLUMN_COUNT, MAX_COLUMN_COUNT)
    )
    override val columnCount: StateFlow<Int> = _columnCount.asStateFlow()

    override fun setColumnCount(count: Int) {
        val clamped = count.coerceIn(MIN_COLUMN_COUNT, MAX_COLUMN_COUNT)
        prefs.edit().putInt(KEY_COLUMN_COUNT, clamped).apply()
        _columnCount.value = clamped
    }

    companion object {
        const val DEFAULT_COLUMN_COUNT = 3
        const val MIN_COLUMN_COUNT = 2
        const val MAX_COLUMN_COUNT = 6

        private const val PREFS_NAME = "image_grid_view_settings"
        private const val KEY_COLUMN_COUNT = "column_count"
    }
}
