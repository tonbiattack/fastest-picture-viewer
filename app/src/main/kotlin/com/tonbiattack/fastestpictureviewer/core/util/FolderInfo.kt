package com.tonbiattack.fastestpictureviewer.core.util

/**
 * MediaStore の RELATIVE_PATH からフォルダ表示名・フォルダ識別子を導出するユーティリティ。
 *
 * フォルダ識別子は「ボリューム名 + relative path」の複合キーとし、
 * 内部ストレージと SD カードで同名フォルダがあっても衝突しないようにする。
 */
object FolderKeys {

    /**
     * フォルダ識別子を生成する。
     * 例: volume="external_primary", relativePath="Pictures/DCIM/" → "external_primary:Pictures/DCIM/"
     */
    fun create(volumeName: String, relativePath: String): String =
        "$volumeName:$relativePath"
}

/**
 * RELATIVE_PATH の最終セグメントからフォルダ表示名を作る。
 * 例: "Pictures/vacation/" → "vacation"、空の場合はフォールバック名を返す。
 */
object FolderNames {

    private const val FALLBACK_NAME = "(ルートフォルダ)"

    fun parse(relativePath: String): String {
        val segments = relativePath.split('/').filter { it.isNotBlank() }
        return segments.lastOrNull() ?: FALLBACK_NAME
    }

    /** フォルダ識別子 ("volume:relativePath") からフォルダ名を取り出す */
    fun fromFolderKey(folderKey: String): String {
        val relativePath = folderKey.substringAfter(':', missingDelimiterValue = "")
        return parse(relativePath)
    }
}
