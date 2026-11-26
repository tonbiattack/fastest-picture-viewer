package com.tonbiattack.fastestpictureviewer.data

import com.tonbiattack.fastestpictureviewer.core.util.FolderSortMode
import com.tonbiattack.fastestpictureviewer.core.util.ImageSortMode
import com.tonbiattack.fastestpictureviewer.ui.folder.FolderViewSettings
import com.tonbiattack.fastestpictureviewer.ui.grid.ImageGridViewSettings
import com.tonbiattack.fastestpictureviewer.ui.viewer.SlideshowInterval
import com.tonbiattack.fastestpictureviewer.ui.viewer.SlideshowSettings

/**
 * 表示設定をバックアップ形式へ変換する実装 (Issue #74 / #151)。
 *
 * SharedPreferences に保存している設定をまとめて読み書きする。
 * Issue #151 で画像の並び順・フォルダ単位の並び順・スクロール復元・
 * 通知の表示・スライドショー間隔も対象に加えた。
 */
class ViewSettingsBackupAccess(
    private val folderSettings: FolderViewSettings,
    private val imageSettings: ImageGridViewSettings,
    /** スライドショーの表示間隔 (Issue #151)。無ければ既定値のまま */
    private val slideshowSettings: SlideshowSettings? = null
) : BackupSettingsAccess {

    override suspend fun snapshot(): BackupSettings = BackupSettings(
        folderColumns = folderSettings.columnCount.value,
        sortMode = folderSettings.sortMode.value.name,
        imageColumnsPortrait = imageSettings.portraitColumnCount.value,
        imageColumnsLandscape = imageSettings.landscapeColumnCount.value,
        showResumeCard = folderSettings.showResumeCard.value,
        imageSortMode = imageSettings.sortMode.value.name,
        folderSortOverrides = imageSettings.folderSortOverrides.value
            .mapValues { (_, mode) -> mode.name },
        restoreScrollPosition = imageSettings.restoreScrollPosition.value,
        showStaleMediaNotice = folderSettings.showStaleMediaNotice.value,
        slideshowIntervalSec = slideshowSettings?.interval?.value?.seconds
            ?: SlideshowInterval.DEFAULT.seconds
    )

    override suspend fun apply(settings: BackupSettings) {
        folderSettings.setColumnCount(settings.folderColumns)
        runCatching { FolderSortMode.valueOf(settings.sortMode) }
            .getOrNull()
            ?.let { folderSettings.setSortMode(it) }
        folderSettings.setShowResumeCard(settings.showResumeCard)
        folderSettings.setShowStaleMediaNotice(settings.showStaleMediaNotice)

        imageSettings.restoreColumnCounts(settings.imageColumnsPortrait, settings.imageColumnsLandscape)

        // 未知の値は無視し、現在の設定を変えない (Issue #151)
        runCatching { ImageSortMode.valueOf(settings.imageSortMode) }
            .getOrNull()
            ?.let { imageSettings.setSortMode(it) }
        imageSettings.setRestoreScrollPosition(settings.restoreScrollPosition)

        // フォルダ単位の上書きは、今回のバックアップ内容で置き換える。
        // 消えたフォルダの指定が残ると混乱するため、いったん全解除してから入れ直す
        imageSettings.folderSortOverrides.value.keys.toList().forEach { key ->
            imageSettings.setFolderSortMode(key, null)
        }
        settings.folderSortOverrides.forEach { (folderKey, modeName) ->
            runCatching { ImageSortMode.valueOf(modeName) }
                .getOrNull()
                ?.let { imageSettings.setFolderSortMode(folderKey, it) }
        }

        slideshowSettings?.let { slideshow ->
            // 想定外の値は fromSeconds が既定値へ戻す
            slideshow.setInterval(SlideshowInterval.fromSeconds(settings.slideshowIntervalSec))
        }
    }
}
