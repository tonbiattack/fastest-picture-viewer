package com.tonbiattack.fastestpictureviewer.ui.grid

import android.content.Context
import androidx.core.content.edit
import com.tonbiattack.fastestpictureviewer.core.util.ImageSortMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * フォルダ内画像グリッドの表示設定。
 *
 * - 列数はフォルダ一覧の列数設定とは独立して保存する (Issue #34)
 * - 縦向き (Portrait) と横向き (Landscape) で別々の列数を保持する (Issue #44)
 */
interface ImageGridViewSettings {

    /** 現在の向きで適用中の列数 (2〜6) */
    val columnCount: StateFlow<Int>

    /** 縦向き用として保存されている列数 */
    val portraitColumnCount: StateFlow<Int>

    /** 横向き用として保存されている列数 */
    val landscapeColumnCount: StateFlow<Int>

    /** 現在の向きの列数を保存して即時反映する */
    fun setColumnCount(count: Int)

    /** 画面の向きを通知する。保存値は変更せず、適用する列数だけ切り替える */
    fun setLandscape(isLandscape: Boolean)

    /** 現在の向きを変えずに、縦横それぞれの列数を復元する */
    fun restoreColumnCounts(portrait: Int, landscape: Int)

    /**
     * 画像一覧の並び順（全体の既定）。
     *
     * 個別に指定の無いフォルダはこの設定に従う。既定は名前順。
     */
    val sortMode: StateFlow<ImageSortMode>

    /**
     * フォルダごとの並び順の上書き (Issue #143)。
     *
     * 特定のフォルダだけ更新日時順にしたい、という使い方のためのもの。
     * ここに含まれないフォルダは [sortMode] に従う。
     */
    val folderSortOverrides: StateFlow<Map<String, ImageSortMode>>

    /** 全体の並び順を保存して即時反映する */
    fun setSortMode(mode: ImageSortMode)

    /**
     * フォルダ単位の並び順を保存する (Issue #143)。
     *
     * @param mode null を渡すと上書きを解除し、全体の設定に従うようになる
     */
    fun setFolderSortMode(folderKey: String, mode: ImageSortMode?)

    /**
     * 指定フォルダの実効並び順を返す。上書きが無ければ全体の設定。
     *
     * 保存値を読むだけなので、UI から同期的に呼べる。
     */
    fun sortModeFor(folderKey: String?): ImageSortMode {
        val key = folderKey?.takeIf { it.isNotEmpty() } ?: return sortMode.value
        return folderSortOverrides.value[key] ?: sortMode.value
    }
}

/**
 * SharedPreferences による実装。
 *
 * 旧バージョンの単一キー (`column_count`) が存在する場合は、
 * 縦向き・横向きの初期値として引き継ぎ、既存ユーザーの見た目を変えない。
 */
class SharedPreferencesImageGridViewSettings(context: Context) : ImageGridViewSettings {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 旧キーの値 (存在しなければ既定値)。移行時の初期値にだけ使う */
    private val legacyColumnCount: Int = prefs.getInt(KEY_LEGACY_COLUMN_COUNT, DEFAULT_COLUMN_COUNT)
        .coerceIn(MIN_COLUMN_COUNT, MAX_COLUMN_COUNT)

    private val _portraitColumnCount = MutableStateFlow(
        prefs.getInt(KEY_PORTRAIT_COLUMN_COUNT, legacyColumnCount)
            .coerceIn(MIN_COLUMN_COUNT, MAX_COLUMN_COUNT)
    )
    override val portraitColumnCount: StateFlow<Int> = _portraitColumnCount.asStateFlow()

    private val _landscapeColumnCount = MutableStateFlow(
        prefs.getInt(KEY_LANDSCAPE_COLUMN_COUNT, _portraitColumnCount.value)
            .coerceIn(MIN_COLUMN_COUNT, MAX_COLUMN_COUNT)
    )
    override val landscapeColumnCount: StateFlow<Int> = _landscapeColumnCount.asStateFlow()

    private val _isLandscape = MutableStateFlow(false)

    private val _columnCount = MutableStateFlow(_portraitColumnCount.value)
    override val columnCount: StateFlow<Int> = _columnCount.asStateFlow()

    private val _sortMode = MutableStateFlow(
        ImageSortMode.fromName(prefs.getString(KEY_SORT_MODE, null))
    )
    override val sortMode: StateFlow<ImageSortMode> = _sortMode.asStateFlow()

    private val _folderSortOverrides = MutableStateFlow(readFolderOverrides())
    override val folderSortOverrides: StateFlow<Map<String, ImageSortMode>> =
        _folderSortOverrides.asStateFlow()

    init {
        // 移行結果を書き戻し、次回起動で旧キーに依存しないようにする
        prefs.edit {
            putInt(KEY_PORTRAIT_COLUMN_COUNT, _portraitColumnCount.value)
            putInt(KEY_LANDSCAPE_COLUMN_COUNT, _landscapeColumnCount.value)
        }
    }

    override fun setColumnCount(count: Int) {
        val clamped = count.coerceIn(MIN_COLUMN_COUNT, MAX_COLUMN_COUNT)
        if (_isLandscape.value) {
            prefs.edit { putInt(KEY_LANDSCAPE_COLUMN_COUNT, clamped) }
            _landscapeColumnCount.value = clamped
        } else {
            prefs.edit { putInt(KEY_PORTRAIT_COLUMN_COUNT, clamped) }
            _portraitColumnCount.value = clamped
        }
        _columnCount.value = clamped
    }

    override fun setLandscape(isLandscape: Boolean) {
        _isLandscape.value = isLandscape
        _columnCount.value = if (isLandscape) {
            _landscapeColumnCount.value
        } else {
            _portraitColumnCount.value
        }
    }

    override fun restoreColumnCounts(portrait: Int, landscape: Int) {
        val portraitCount = portrait.coerceIn(MIN_COLUMN_COUNT, MAX_COLUMN_COUNT)
        val landscapeCount = landscape.coerceIn(MIN_COLUMN_COUNT, MAX_COLUMN_COUNT)
        prefs.edit {
            putInt(KEY_PORTRAIT_COLUMN_COUNT, portraitCount)
            putInt(KEY_LANDSCAPE_COLUMN_COUNT, landscapeCount)
        }
        _portraitColumnCount.value = portraitCount
        _landscapeColumnCount.value = landscapeCount
        _columnCount.value = if (_isLandscape.value) landscapeCount else portraitCount
    }

    override fun setSortMode(mode: ImageSortMode) {
        prefs.edit { putString(KEY_SORT_MODE, mode.name) }
        _sortMode.value = mode
    }

    override fun setFolderSortMode(folderKey: String, mode: ImageSortMode?) {
        if (folderKey.isEmpty()) return
        val next = _folderSortOverrides.value.toMutableMap()
        if (mode == null) {
            // 上書きを解除し、全体の設定に従わせる
            if (next.remove(folderKey) == null) return
        } else {
            if (next[folderKey] == mode) return
            next[folderKey] = mode
        }
        _folderSortOverrides.value = next
        prefs.edit { putString(KEY_FOLDER_SORT_OVERRIDES, writeFolderOverrides(next)) }
    }

    /**
     * 保存されているフォルダ単位の並び順を読む (Issue #143)。
     *
     * 壊れた JSON や未知の値は無視する。読めなかった場合は空のマップを返し、
     * すべてのフォルダが全体設定に従う状態へ戻す。
     *
     * 名前順もそのまま保持する。全体が新しい順のときに
     * 「このフォルダだけ名前順」を指定できるようにするため。
     */
    private fun readFolderOverrides(): Map<String, ImageSortMode> {
        val raw = prefs.getString(KEY_FOLDER_SORT_OVERRIDES, null) ?: return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            buildMap {
                json.keys().forEach { key ->
                    // 未知の値は fromName が既定へ倒すため、既知の名前だけ拾う
                    val name = json.optString(key)
                    ImageSortMode.ALL.firstOrNull { it.name == name }?.let { put(key, it) }
                }
            }
        }.getOrDefault(emptyMap())
    }

    /** フォルダ単位の並び順を JSON へ書く */
    private fun writeFolderOverrides(overrides: Map<String, ImageSortMode>): String {
        val json = JSONObject()
        overrides.forEach { (key, mode) -> json.put(key, mode.name) }
        return json.toString()
    }

    companion object {
        const val DEFAULT_COLUMN_COUNT = 3
        const val MIN_COLUMN_COUNT = 2
        const val MAX_COLUMN_COUNT = 6

        private const val PREFS_NAME = "image_grid_view_settings"
        private const val KEY_PORTRAIT_COLUMN_COUNT = "column_count_portrait"
        private const val KEY_LANDSCAPE_COLUMN_COUNT = "column_count_landscape"
        private const val KEY_SORT_MODE = "sort_mode"

        /** フォルダ単位の並び順。JSON 文字列で持つ (Issue #143) */
        private const val KEY_FOLDER_SORT_OVERRIDES = "folder_sort_overrides"

        /** 旧バージョン (Issue #34) の単一キー。移行の初期値としてのみ読む */
        private const val KEY_LEGACY_COLUMN_COUNT = "column_count"
    }
}
