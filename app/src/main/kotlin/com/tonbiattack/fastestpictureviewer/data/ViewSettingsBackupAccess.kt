package com.tonbiattack.fastestpictureviewer.data

import com.tonbiattack.fastestpictureviewer.core.util.FolderSortMode
import com.tonbiattack.fastestpictureviewer.ui.folder.FolderViewSettings
import com.tonbiattack.fastestpictureviewer.ui.grid.ImageGridViewSettings

/**
 * 表示設定をバックアップ形式へ変換する実装 (Issue #74)。
 *
 * SharedPreferences に保存している設定をまとめて読み書きする。
 */
class ViewSettingsBackupAccess(
    private val folderSettings: FolderViewSettings,
    private val imageSettings: ImageGridViewSettings
) : BackupSettingsAccess {

    override suspend fun snapshot(): BackupSettings = BackupSettings(
        folderColumns = folderSettings.columnCount.value,
        sortMode = folderSettings.sortMode.value.name,
        imageColumnsPortrait = imageSettings.portraitColumnCount.value,
        imageColumnsLandscape = imageSettings.landscapeColumnCount.value,
        showResumeCard = folderSettings.showResumeCard.value
    )

    override suspend fun apply(settings: BackupSettings) {
        folderSettings.setColumnCount(settings.folderColumns)
        runCatching { FolderSortMode.valueOf(settings.sortMode) }
            .getOrNull()
            ?.let { folderSettings.setSortMode(it) }
        folderSettings.setShowResumeCard(settings.showResumeCard)

        // 画像グリッドの列数は「現在の向き」に書き込む実装のため、
        // 縦横それぞれへ戻すときは向きを切り替えながら設定する
        imageSettings.setLandscape(false)
        imageSettings.setColumnCount(settings.imageColumnsPortrait)
        imageSettings.setLandscape(true)
        imageSettings.setColumnCount(settings.imageColumnsLandscape)
        imageSettings.setLandscape(false)
    }
}
