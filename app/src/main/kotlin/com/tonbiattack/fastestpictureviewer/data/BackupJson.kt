package com.tonbiattack.fastestpictureviewer.data

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * バックアップ JSON の変換 (Issue #74)。
 *
 * Android 標準の org.json だけを使い、追加のシリアライズライブラリに依存しない。
 * 読み込み側は寛容にする（未知のフィールドは無視し、既定値で補う）。
 */
object BackupJson {

    /** バックアップを JSON 文字列へ変換する */
    fun encode(data: BackupData): String {
        val root = JSONObject()
        root.put("version", data.version)
        root.put("exportedAt", data.exportedAt)
        root.put(
            "app",
            JSONObject()
                .put("packageName", APP_PACKAGE)
                .put("versionName", data.appVersionName)
        )

        root.put(
            "tags",
            JSONArray().apply {
                data.tags.forEach { tag ->
                    put(JSONObject().put("displayName", tag.displayName).put("normalizedName", tag.normalizedName))
                }
            }
        )
        root.put(
            "folderTags",
            JSONArray().apply {
                data.folderTags.forEach { link ->
                    put(JSONObject().put("folderKey", link.folderKey).put("tag", link.tag))
                }
            }
        )
        root.put(
            "favoriteFolders",
            JSONArray().apply {
                data.favoriteFolders.forEach { ref ->
                    put(
                        JSONObject()
                            .put("volumeName", ref.volumeName)
                            .put("relativePath", ref.relativePath)
                    )
                }
            }
        )
        root.put(
            "favoriteImages",
            JSONArray().apply { data.favoriteImages.forEach { put(it) } }
        )
        root.put(
            "excludedFolders",
            JSONArray().apply {
                data.excludedFolders.forEach { ref ->
                    put(
                        JSONObject()
                            .put("volumeName", ref.volumeName)
                            .put("relativePath", ref.relativePath)
                    )
                }
            }
        )
        root.put(
            "settings",
            JSONObject()
                .put("folderColumns", data.settings.folderColumns)
                .put("sortMode", data.settings.sortMode)
                .put("imageColumnsPortrait", data.settings.imageColumnsPortrait)
                .put("imageColumnsLandscape", data.settings.imageColumnsLandscape)
                .put("showResumeCard", data.settings.showResumeCard)
                // 以下は Issue #151 で追加。古いアプリは無視するだけなので安全
                .put("imageSortMode", data.settings.imageSortMode)
                .put(
                    "folderSortOverrides",
                    JSONObject().also { json ->
                        data.settings.folderSortOverrides.forEach { (key, mode) ->
                            json.put(key, mode)
                        }
                    }
                )
                .put("restoreScrollPosition", data.settings.restoreScrollPosition)
                .put("showStaleMediaNotice", data.settings.showStaleMediaNotice)
                .put("slideshowIntervalSec", data.settings.slideshowIntervalSec)
        )
        return root.toString(2)
    }

    /**
     * JSON 文字列を [BackupData] に変換する。
     * 壊れている・形式が違う場合は [BackupError] を投げ、呼び出し側のデータには触れない。
     */
    fun decode(text: String): BackupData {
        val root = try {
            JSONObject(text)
        } catch (e: JSONException) {
            throw BackupError.InvalidJson(e.message ?: "JSON の解析に失敗しました")
        }

        val version = root.optInt("version", -1)
        if (version < 0) throw BackupError.MissingField("version")
        if (version > BackupData.CURRENT_VERSION) throw BackupError.NewerVersion(version)

        val settingsJson = root.optJSONObject("settings")
        val settings = BackupSettings(
            folderColumns = settingsJson?.optInt("folderColumns", 2) ?: 2,
            sortMode = settingsJson?.optString("sortMode", "LATEST") ?: "LATEST",
            imageColumnsPortrait = settingsJson?.optInt("imageColumnsPortrait", 3) ?: 3,
            imageColumnsLandscape = settingsJson?.optInt("imageColumnsLandscape", 3) ?: 3,
            showResumeCard = settingsJson?.optBoolean("showResumeCard", true) ?: true,
            // Issue #151 で追加。古いバックアップには無いため既定値を使う
            imageSortMode = settingsJson?.optString("imageSortMode", "NAME") ?: "NAME",
            folderSortOverrides = readStringMap(settingsJson?.optJSONObject("folderSortOverrides")),
            restoreScrollPosition = settingsJson?.optBoolean("restoreScrollPosition", true) ?: true,
            showStaleMediaNotice = settingsJson?.optBoolean("showStaleMediaNotice", true) ?: true,
            slideshowIntervalSec = settingsJson?.optInt("slideshowIntervalSec", 3) ?: 3,
            presentFields = BackupSettings.ALL_FIELDS.filterTo(mutableSetOf()) { name ->
                settingsJson?.has(name) == true && !settingsJson.isNull(name)
            }
        )

        return BackupData(
            version = version,
            exportedAt = root.optString("exportedAt", ""),
            appVersionName = root.optJSONObject("app")?.optString("versionName", "") ?: "",
            tags = readTags(root.optJSONArray("tags")),
            folderTags = readFolderTags(root.optJSONArray("folderTags")),
            favoriteFolders = readFolderRefs(root.optJSONArray("favoriteFolders")),
            favoriteImages = readStrings(root.optJSONArray("favoriteImages")),
            excludedFolders = readFolderRefs(root.optJSONArray("excludedFolders")),
            settings = settings
        )
    }

    /** JSON オブジェクトを文字列マップとして読む。無ければ空 (Issue #151) */
    private fun readStringMap(json: JSONObject?): Map<String, String> {
        if (json == null) return emptyMap()
        val result = LinkedHashMap<String, String>(json.length())
        json.keys().forEach { key ->
            val value = json.optString(key)
            if (value.isNotEmpty()) result[key] = value
        }
        return result
    }

    private fun readTags(array: JSONArray?): List<BackupTag> {
        if (array == null) return emptyList()
        val result = ArrayList<BackupTag>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val displayName = item.optString("displayName", "").trim()
            if (displayName.isEmpty()) continue
            val normalized = item.optString("normalizedName", "").trim()
                .ifEmpty { displayName.lowercase() }
            result += BackupTag(displayName = displayName, normalizedName = normalized)
        }
        return result
    }

    private fun readFolderTags(array: JSONArray?): List<BackupFolderTag> {
        if (array == null) return emptyList()
        val result = ArrayList<BackupFolderTag>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val folderKey = item.optString("folderKey", "").trim()
            val tag = item.optString("tag", "").trim()
            if (folderKey.isEmpty() || tag.isEmpty()) continue
            result += BackupFolderTag(folderKey = folderKey, tag = tag)
        }
        return result
    }

    private fun readFolderRefs(array: JSONArray?): List<BackupFolderRef> {
        if (array == null) return emptyList()
        val result = ArrayList<BackupFolderRef>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val volumeName = item.optString("volumeName", "").trim()
            val relativePath = item.optString("relativePath", "")
            if (volumeName.isEmpty()) continue
            result += BackupFolderRef(volumeName = volumeName, relativePath = relativePath)
        }
        return result
    }

    private fun readStrings(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        val result = ArrayList<String>(array.length())
        for (i in 0 until array.length()) {
            val value = array.optString(i, "").trim()
            if (value.isNotEmpty()) result += value
        }
        return result
    }

    private const val APP_PACKAGE = "com.tonbiattack.fastestpictureviewer"
}
