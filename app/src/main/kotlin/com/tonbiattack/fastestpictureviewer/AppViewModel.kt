package com.tonbiattack.fastestpictureviewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tonbiattack.fastestpictureviewer.core.permission.MediaAccessLevel
import com.tonbiattack.fastestpictureviewer.repository.GalleryRepository
import com.tonbiattack.fastestpictureviewer.repository.SyncMode
import kotlinx.coroutines.launch

/**
 * アクティビティ単位で権限 → 同期開始を持つ ViewModel。
 *
 * 同期は常にバックグラウンド実行で、一覧表示をブロックしない (stale-while-revalidate)。
 */
class AppViewModel(private val repository: GalleryRepository) : ViewModel() {

    private var lastAutoSyncParts = Long.MIN_VALUE

    /** アクセスレベルの変化を受け取る。許可されていれば自動同期を開始する */
    fun onAccessLevelChanged(level: MediaAccessLevel) {
        if (level == MediaAccessLevel.Denied) return
        requestSync(SyncMode.AUTO, canDelete = level == MediaAccessLevel.Full)
    }

    /**
     * フォアグラウンド復帰時の自動差分同期。
     * 頻繁な復帰ごとに MediaStore を叩きすぎないよう 5 分のスロットリングを入れる。
     */
    fun onAppForeground(level: MediaAccessLevel) {
        if (level == MediaAccessLevel.Denied) return
        val now = System.currentTimeMillis()
        if (now - lastAutoSyncParts < FOREGROUND_THROTTLE_MS) return
        lastAutoSyncParts = now
        requestSync(SyncMode.AUTO, canDelete = level == MediaAccessLevel.Full)
    }

    /** 手動更新ボタンからの完全同期要求 */
    fun requestManualFullSync(level: MediaAccessLevel) {
        requestSync(SyncMode.FULL, canDelete = level == MediaAccessLevel.Full)
    }

    private fun requestSync(mode: SyncMode, canDelete: Boolean) {
        viewModelScope.launch {
            repository.sync(mode = mode, canDelete = canDelete)
        }
    }

    companion object {
        private const val FOREGROUND_THROTTLE_MS = 5 * 60 * 1000L
    }
}
