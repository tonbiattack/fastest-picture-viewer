package com.tonbiattack.fastestpictureviewer.ui.folder

import android.content.Context
import androidx.core.content.edit
import com.tonbiattack.fastestpictureviewer.core.util.FolderSortMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * フォルダ一覧の表示設定。
 * 列数・並び順・「前回の続き」カードの表示はアプリ再起動後も維持する。
 */
interface FolderViewSettings {

    /** 現在の列数 (1〜4) */
    val columnCount: StateFlow<Int>

    /** 現在の並び順 */
    val sortMode: StateFlow<FolderSortMode>

    /** 「前回の続き」カードを表示するか (Issue #53)。既定は true */
    val showResumeCard: StateFlow<Boolean>

    /**
     * 「見つからない画像」の通知バナーを表示するか (Issue #126)。既定は true。
     *
     * OFF でも検出・集計・整理機能は止めない。バナーを出さないだけ。
     */
    val showStaleMediaNotice: StateFlow<Boolean>

    /** 列数を保存して即時反映する */
    fun setColumnCount(count: Int)

    /** 並び順を保存して即時反映する */
    fun setSortMode(mode: FolderSortMode)

    /** 「前回の続き」カードの表示を保存して即時反映する */
    fun setShowResumeCard(show: Boolean)

    /** 「見つからない画像」の通知バナーの表示を保存して即時反映する */
    fun setShowStaleMediaNotice(show: Boolean)
}

/** SharedPreferences による実装 */
class SharedPreferencesFolderViewSettings(context: Context) : FolderViewSettings {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _columnCount = MutableStateFlow(
        prefs.getInt(KEY_COLUMN_COUNT, DEFAULT_COLUMN_COUNT).coerceIn(MIN_COLUMN_COUNT, MAX_COLUMN_COUNT)
    )
    override val columnCount: StateFlow<Int> = _columnCount.asStateFlow()

    private val _sortMode = MutableStateFlow(
        prefs.getString(KEY_SORT_MODE, null)
            ?.let { value -> runCatching { FolderSortMode.valueOf(value) }.getOrNull() }
            ?: FolderSortMode.LATEST
    )
    override val sortMode: StateFlow<FolderSortMode> = _sortMode.asStateFlow()

    private val _showResumeCard = MutableStateFlow(
        prefs.getBoolean(KEY_SHOW_RESUME_CARD, DEFAULT_SHOW_RESUME_CARD)
    )
    override val showResumeCard: StateFlow<Boolean> = _showResumeCard.asStateFlow()

    private val _showStaleMediaNotice = MutableStateFlow(
        prefs.getBoolean(KEY_SHOW_STALE_MEDIA_NOTICE, DEFAULT_SHOW_STALE_MEDIA_NOTICE)
    )
    override val showStaleMediaNotice: StateFlow<Boolean> = _showStaleMediaNotice.asStateFlow()

    override fun setColumnCount(count: Int) {
        val clamped = count.coerceIn(MIN_COLUMN_COUNT, MAX_COLUMN_COUNT)
        prefs.edit { putInt(KEY_COLUMN_COUNT, clamped) }
        _columnCount.value = clamped
    }

    override fun setSortMode(mode: FolderSortMode) {
        prefs.edit { putString(KEY_SORT_MODE, mode.name) }
        _sortMode.value = mode
    }

    override fun setShowResumeCard(show: Boolean) {
        prefs.edit { putBoolean(KEY_SHOW_RESUME_CARD, show) }
        _showResumeCard.value = show
    }

    override fun setShowStaleMediaNotice(show: Boolean) {
        prefs.edit { putBoolean(KEY_SHOW_STALE_MEDIA_NOTICE, show) }
        _showStaleMediaNotice.value = show
    }

    companion object {
        const val DEFAULT_COLUMN_COUNT = 2
        const val MIN_COLUMN_COUNT = 1
        const val MAX_COLUMN_COUNT = 4

        /** 既存ユーザーの見た目を変えないよう、既定は表示する */
        const val DEFAULT_SHOW_RESUME_CARD = true

        /** 既存の動作を変えないよう、既定は通知を表示する (Issue #126) */
        const val DEFAULT_SHOW_STALE_MEDIA_NOTICE = true

        private const val PREFS_NAME = "folder_view_settings"
        private const val KEY_COLUMN_COUNT = "column_count"
        private const val KEY_SORT_MODE = "sort_mode"
        private const val KEY_SHOW_RESUME_CARD = "show_resume_card"
        private const val KEY_SHOW_STALE_MEDIA_NOTICE = "show_stale_media_notice"
    }
}
