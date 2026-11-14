package com.tonbiattack.fastestpictureviewer.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * タグ・お気に入り・除外・設定を JSON ファイルへ書き出し / 読み込みする (Issue #74)。
 *
 * - 端末の買い替えや初期化で失われる「手で作ったデータ」を守るための機能
 * - 画像そのものやサムネイルは含めない（再生成できる）
 * - 取り込みは**マージ**。既存データを消さない
 */
class BackupRepository(
    private val db: AppDatabase,
    /** 設定の読み書き (表示設定を持つクラスから渡す) */
    private val settingsAccess: BackupSettingsAccess,
    /** 現在時刻 (テスト差し替え用) */
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 }
) {

    private val tagDao = db.tagDao()
    private val favoriteFolderDao = db.favoriteFolderDao()
    private val favoriteImageDao = db.favoriteImageDao()
    private val excludedFolderDao = db.excludedFolderDao()

    /** 現在のデータをバックアップ用にまとめる */
    suspend fun collect(appVersionName: String): BackupData {
        val tags = tagDao.getAllTags()
        val nameById = tags.associate { it.tagId to it.normalizedName }
        val links = tagDao.getAllFolderTags()

        return BackupData(
            version = BackupData.CURRENT_VERSION,
            exportedAt = isoNow(),
            appVersionName = appVersionName,
            tags = tags.map { BackupTag(displayName = it.displayName, normalizedName = it.normalizedName) },
            folderTags = links.mapNotNull { link ->
                val name = nameById[link.tagId] ?: return@mapNotNull null
                BackupFolderTag(folderKey = link.folderKey, tag = name)
            },
            favoriteFolders = favoriteFolderDao.getAll().map {
                BackupFolderRef(volumeName = it.volumeName, relativePath = it.relativePath)
            },
            favoriteImages = favoriteImageDao.getAll().map { it.itemId },
            excludedFolders = excludedFolderDao.getAll().map {
                BackupFolderRef(volumeName = it.volumeName, relativePath = it.relativePath)
            },
            settings = settingsAccess.snapshot()
        )
    }

    /**
     * バックアップを現在のデータへ取り込む。
     * 既存データは消さず、足りないものだけを追加する。
     */
    suspend fun restore(data: BackupData): RestoreResult {
        // 1. タグ: 正規化名で照合し、無ければ作る
        val existing = tagDao.getAllTags().associateBy { it.normalizedName }
        val idByNormalized = existing.mapValues { it.value.tagId }.toMutableMap()
        var created = 0
        var reused = 0
        val now = nowSeconds()

        data.tags.forEach { tag ->
            if (idByNormalized.containsKey(tag.normalizedName)) {
                reused++
                return@forEach
            }
            val inserted = tagDao.insertIgnore(
                TagEntity(
                    displayName = tag.displayName,
                    normalizedName = tag.normalizedName,
                    createdAtSec = now
                )
            )
            if (inserted > 0L) {
                idByNormalized[tag.normalizedName] = inserted
                created++
            } else {
                // 競合した場合は既存を引き直す
                tagDao.findByNormalizedName(tag.normalizedName)?.let {
                    idByNormalized[tag.normalizedName] = it.tagId
                    reused++
                }
            }
        }

        // 2. フォルダとタグの紐付け
        var folderTagsAdded = 0
        var skipped = 0
        data.folderTags.forEach { link ->
            val tagId = idByNormalized[link.tag]
            if (tagId == null) {
                skipped++
                return@forEach
            }
            tagDao.addLink(FolderTagEntity(folderKey = link.folderKey, tagId = tagId, addedAtSec = now))
                .let { if (it > 0L) folderTagsAdded++ }
        }

        // 3. フォルダお気に入り
        var folderFavoritesAdded = 0
        data.favoriteFolders.forEach { ref ->
            val key = com.tonbiattack.fastestpictureviewer.core.util.FolderKeys.create(
                ref.volumeName,
                ref.relativePath
            )
            if (favoriteFolderDao.exists(key) == 0L) folderFavoritesAdded++
            favoriteFolderDao.add(
                FavoriteFolderEntity(
                    folderKey = key,
                    volumeName = ref.volumeName,
                    relativePath = ref.relativePath,
                    addedAtSec = now
                )
            )
        }

        // 4. 画像お気に入り
        var imageFavoritesAdded = 0
        data.favoriteImages.forEach { itemId ->
            if (favoriteImageDao.exists(itemId) == 0L) imageFavoritesAdded++
            favoriteImageDao.add(FavoriteImageEntity(itemId = itemId, addedAtSec = now))
        }

        // 5. 除外フォルダ
        var excludedAdded = 0
        data.excludedFolders.forEach { ref ->
            val key = com.tonbiattack.fastestpictureviewer.core.util.FolderKeys.create(
                ref.volumeName,
                ref.relativePath
            )
            if (excludedFolderDao.exists(key) == 0L) excludedAdded++
            excludedFolderDao.add(
                ExcludedFolderEntity(
                    folderKey = key,
                    volumeName = ref.volumeName,
                    relativePath = ref.relativePath,
                    excludedAtSec = now
                )
            )
        }

        // 6. 表示設定
        settingsAccess.apply(data.settings)

        return RestoreResult(
            tagsCreated = created,
            tagsReused = reused,
            folderTagsAdded = folderTagsAdded,
            favoriteFoldersAdded = folderFavoritesAdded,
            favoriteImagesAdded = imageFavoritesAdded,
            excludedFoldersAdded = excludedAdded,
            skippedFolderTags = skipped,
            settingsApplied = true
        )
    }

    /** 既定のファイル名。例: pictobi-backup-20261114-1230.json */
    fun defaultFileName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(nowSeconds() * 1000))
        return "pictobi-backup-$stamp.json"
    }

    private fun isoNow(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date(nowSeconds() * 1000))
}

/** バックアップ対象の表示設定を読み書きする口 */
interface BackupSettingsAccess {
    suspend fun snapshot(): BackupSettings
    suspend fun apply(settings: BackupSettings)
}
