package com.tonbiattack.fastestpictureviewer.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 最後に開いていたフォルダと画像。起動時の「前回の続き」に使う (Issue #41)。
 * 保存するのは1件だけにし、起動時の読み込みコストを増やさない。
 */
data class LastViewedFolder(
    val folderKey: String,
    val itemId: String,
    val itemIndex: Int,
    val updatedAtSec: Long
)

/** 最後に開いていたフォルダ・画像の保存先 */
interface LastViewedStore {

    /** 最後に見ていたフォルダ・画像。未保存なら null */
    val lastViewed: StateFlow<LastViewedFolder?>

    /** フォルダと画像を保存する */
    fun save(folderKey: String, itemId: String, itemIndex: Int)

    /** 履歴を消す */
    fun clear()
}

/** SharedPreferences による実装。読み書きは1レコードだけに限定する */
class SharedPreferencesLastViewedStore(context: Context) : LastViewedStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _lastViewed = MutableStateFlow(loadFromPrefs())
    override val lastViewed: StateFlow<LastViewedFolder?> = _lastViewed.asStateFlow()

    override fun save(folderKey: String, itemId: String, itemIndex: Int) {
        if (folderKey.isBlank() || itemId.isBlank()) return
        val record = LastViewedFolder(
            folderKey = folderKey,
            itemId = itemId,
            itemIndex = itemIndex.coerceAtLeast(0),
            updatedAtSec = System.currentTimeMillis() / 1000
        )
        prefs.edit {
            putString(KEY_FOLDER, record.folderKey)
            putString(KEY_ITEM, record.itemId)
            putInt(KEY_INDEX, record.itemIndex)
            putLong(KEY_UPDATED, record.updatedAtSec)
        }
        _lastViewed.value = record
    }

    override fun clear() {
        prefs.edit { clear() }
        _lastViewed.value = null
    }

    private fun loadFromPrefs(): LastViewedFolder? {
        val folderKey = prefs.getString(KEY_FOLDER, null) ?: return null
        val itemId = prefs.getString(KEY_ITEM, null) ?: return null
        return LastViewedFolder(
            folderKey = folderKey,
            itemId = itemId,
            itemIndex = prefs.getInt(KEY_INDEX, 0).coerceAtLeast(0),
            updatedAtSec = prefs.getLong(KEY_UPDATED, 0L)
        )
    }

    private companion object {
        const val PREFS_NAME = "last_viewed_folder"
        const val KEY_FOLDER = "folderKey"
        const val KEY_ITEM = "itemId"
        const val KEY_INDEX = "itemIndex"
        const val KEY_UPDATED = "updatedAtSec"
    }
}
