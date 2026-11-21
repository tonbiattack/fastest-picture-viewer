package com.tonbiattack.fastestpictureviewer.ui.viewer

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * スライドショーの表示間隔の保存先 (Issue #124)。
 *
 * 選んだ秒数は次回起動時も保持する。再生状態は保存しない
 * （起動直後に勝手に再生が始まらないようにするため）。
 */
interface SlideshowSettings {

    /** 保存されている表示間隔 */
    val interval: StateFlow<SlideshowInterval>

    /** 表示間隔を保存して即時反映する */
    fun setInterval(interval: SlideshowInterval)
}

/**
 * SharedPreferences による実装。
 *
 * 保存するのは秒数の数値 1 つだけ。壊れた値は既定値へ戻す。
 */
class SharedPreferencesSlideshowSettings(context: Context) : SlideshowSettings {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _interval = MutableStateFlow(
        SlideshowInterval.fromSeconds(
            prefs.getInt(KEY_INTERVAL_SECONDS, SlideshowInterval.DEFAULT.seconds)
        )
    )
    override val interval: StateFlow<SlideshowInterval> = _interval.asStateFlow()

    override fun setInterval(interval: SlideshowInterval) {
        prefs.edit { putInt(KEY_INTERVAL_SECONDS, interval.seconds) }
        _interval.value = interval
    }

    companion object {
        private const val PREFS_NAME = "slideshow_settings"
        private const val KEY_INTERVAL_SECONDS = "interval_seconds"
    }
}
