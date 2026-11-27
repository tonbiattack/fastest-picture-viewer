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
        val fields = settings.presentFields
        if ("folderColumns" in fields) folderSettings.setColumnCount(settings.folderColumns)
        if ("sortMode" in fields) {
            runCatching { FolderSortMode.valueOf(settings.sortMode) }
                .getOrNull()?.let { folderSettings.setSortMode(it) }
        }
        if ("showResumeCard" in fields) folderSettings.setShowResumeCard(settings.showResumeCard)
        if ("showStaleMediaNotice" in fields) {
            folderSettings.setShowStaleMediaNotice(settings.showStaleMediaNotice)
        }
        if ("imageColumnsPortrait" in fields || "imageColumnsLandscape" in fields) {
            imageSettings.restoreColumnCounts(
                if ("imageColumnsPortrait" in fields) settings.imageColumnsPortrait
                else imageSettings.portraitColumnCount.value,
                if ("imageColumnsLandscape" in fields) settings.imageColumnsLandscape
                else imageSettings.landscapeColumnCount.value
            )
        }
        if ("imageSortMode" in fields) {
            runCatching { ImageSortMode.valueOf(settings.imageSortMode) }
                .getOrNull()?.let { imageSettings.setSortMode(it) }
        }
        if ("restoreScrollPosition" in fields) {
            imageSettings.setRestoreScrollPosition(settings.restoreScrollPosition)
        }
        // 存在するフォルダ設定は保持し、控えにある指定だけ追加・更新する。
        if ("folderSortOverrides" in fields) {
            settings.folderSortOverrides.forEach { (folderKey, modeName) ->
                runCatching { ImageSortMode.valueOf(modeName) }
                    .getOrNull()?.let { imageSettings.setFolderSortMode(folderKey, it) }
            }
        }
        if ("slideshowIntervalSec" in fields) {
            slideshowSettings?.let { slideshow ->
                SlideshowInterval.ALL.firstOrNull { it.seconds == settings.slideshowIntervalSec }
                    ?.let { slideshow.setInterval(it) }
            }
        }
    }
}
