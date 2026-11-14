package com.tonbiattack.fastestpictureviewer.data

/**
 * バックアップファイルの内容 (Issue #74)。
 *
 * 端末を買い替えても復元できるよう、**ID ではなく名前やパス**で参照する。
 * 画像そのもの・サムネイル・MediaStore のキャッシュは含めない（再生成できる）。
 */
data class BackupData(
    /** 形式のバージョン。将来の変更に備える */
    val version: Int = CURRENT_VERSION,
    /** 書き出した日時 (ISO-8601) */
    val exportedAt: String,
    /** 書き出したアプリのバージョン名 */
    val appVersionName: String,
    /** タグ本体 */
    val tags: List<BackupTag>,
    /** フォルダとタグの紐付け (タグは正規化名で参照) */
    val folderTags: List<BackupFolderTag>,
    /** フォルダお気に入り */
    val favoriteFolders: List<BackupFolderRef>,
    /** 画像お気に入り (content URI) */
    val favoriteImages: List<String>,
    /** 除外フォルダ */
    val excludedFolders: List<BackupFolderRef>,
    /** 表示設定 */
    val settings: BackupSettings
) {
    /** 取り込み前に件数を確認するための集計 */
    val summary: BackupSummary
        get() = BackupSummary(
            tags = tags.size,
            folderTags = folderTags.size,
            favoriteFolders = favoriteFolders.size,
            favoriteImages = favoriteImages.size,
            excludedFolders = excludedFolders.size
        )

    companion object {
        const val CURRENT_VERSION = 1
    }
}

/** バックアップ内のタグ */
data class BackupTag(
    val displayName: String,
    val normalizedName: String
)

/** フォルダへのタグ付与。タグは正規化名で参照する */
data class BackupFolderTag(
    val folderKey: String,
    val tag: String
)

/** フォルダを指す参照 (お気に入り・除外で共用) */
data class BackupFolderRef(
    val volumeName: String,
    val relativePath: String
)

/** 表示設定 */
data class BackupSettings(
    /** フォルダ一覧の列数 (1〜4) */
    val folderColumns: Int,
    /** 並び順 (LATEST / RECENT) */
    val sortMode: String,
    /** 画像グリッドの縦向き列数 (2〜6) */
    val imageColumnsPortrait: Int,
    /** 画像グリッドの横向き列数 (2〜6) */
    val imageColumnsLandscape: Int,
    /** 前回の続きカードを表示するか */
    val showResumeCard: Boolean
)

/** 取り込み前の確認用の件数 */
data class BackupSummary(
    val tags: Int,
    val folderTags: Int,
    val favoriteFolders: Int,
    val favoriteImages: Int,
    val excludedFolders: Int
)

/** 取り込み結果 */
data class RestoreResult(
    /** 新規作成したタグ数 */
    val tagsCreated: Int,
    /** 既存だったため再利用したタグ数 */
    val tagsReused: Int,
    /** 追加したフォルダのタグ紐付け数 */
    val folderTagsAdded: Int,
    /** 追加したフォルダお気に入り数 */
    val favoriteFoldersAdded: Int,
    /** 追加した画像お気に入り数 */
    val favoriteImagesAdded: Int,
    /** 追加した除外フォルダ数 */
    val excludedFoldersAdded: Int,
    /** フォルダが存在せず紐付けできなかった件数 */
    val skippedFolderTags: Int,
    /** 設定を反映したか */
    val settingsApplied: Boolean
) {
    val hasChanges: Boolean
        get() = tagsCreated + folderTagsAdded + favoriteFoldersAdded +
            favoriteImagesAdded + excludedFoldersAdded > 0
}

/** バックアップの読み書きで起きた問題 */
sealed class BackupError(message: String) : Exception(message) {
    /** JSON として読めない */
    class InvalidJson(detail: String) : BackupError("ファイルを読み取れませんでした: $detail")

    /** 形式が想定と違う */
    class UnsupportedFormat(detail: String) : BackupError("対応していない形式です: $detail")

    /** 新しいバージョンのファイル */
    class NewerVersion(val found: Int) : BackupError(
        "このファイルはより新しいバージョン ($found) で作成されています。アプリを更新してください。"
    )

    /** 必須項目が欠けている */
    class MissingField(name: String) : BackupError("必要な項目がありません: $name")

    /** 入出力の失敗 */
    class IoFailure(detail: String) : BackupError("ファイルの読み書きに失敗しました: $detail")
}
