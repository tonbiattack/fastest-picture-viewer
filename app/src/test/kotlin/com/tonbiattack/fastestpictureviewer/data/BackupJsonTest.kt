package com.tonbiattack.fastestpictureviewer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import androidx.test.ext.junit.runners.AndroidJUnit4

/**
 * バックアップ JSON の変換テスト (Issue #74)。
 *
 * org.json は Android 実装に依存するため Robolectric 上で実行する。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class BackupJsonTest {

    private fun sample() = BackupData(
        exportedAt = "2026-11-14T12:00:00Z",
        appVersionName = "0.1.0",
        tags = listOf(
            BackupTag(displayName = "旅行", normalizedName = "旅行"),
            BackupTag(displayName = "Trip", normalizedName = "trip")
        ),
        folderTags = listOf(
            BackupFolderTag(folderKey = "external_primary:Pictures/A/", tag = "旅行"),
            BackupFolderTag(folderKey = "external_primary:Pictures/B/", tag = "trip")
        ),
        favoriteFolders = listOf(
            BackupFolderRef(volumeName = "external_primary", relativePath = "Pictures/A/")
        ),
        favoriteImages = listOf("content://media/external/images/media/1"),
        excludedFolders = listOf(
            BackupFolderRef(volumeName = "external_primary", relativePath = "Pictures/Private/")
        ),
        settings = BackupSettings(
            folderColumns = 3,
            sortMode = "RECENT",
            imageColumnsPortrait = 4,
            imageColumnsLandscape = 6,
            showResumeCard = false
        )
    )

    @Test
    fun `往復変換で内容が保たれる`() {
        val original = sample()
        val restored = BackupJson.decode(BackupJson.encode(original))

        assertEquals(original.version, restored.version)
        assertEquals(original.exportedAt, restored.exportedAt)
        assertEquals(original.appVersionName, restored.appVersionName)
        assertEquals(original.tags, restored.tags)
        assertEquals(original.folderTags, restored.folderTags)
        assertEquals(original.favoriteFolders, restored.favoriteFolders)
        assertEquals(original.favoriteImages, restored.favoriteImages)
        assertEquals(original.excludedFolders, restored.excludedFolders)
        assertEquals(original.settings, restored.settings)
    }

    @Test
    fun `タグはIDではなく正規化名で保存される`() {
        val json = BackupJson.encode(sample())
        assertTrue("正規化名が含まれる", json.contains("\"normalizedName\""))
        assertTrue("フォルダ紐付けはタグ名で参照", json.contains("\"tag\""))
        // DB の内部 ID が漏れていないこと
        assertTrue("tagId は含めない", !json.contains("tagId"))
    }

    @Test
    fun `壊れたJSONはInvalidJsonになる`() {
        val error = runCatching { BackupJson.decode("{ this is not json") }.exceptionOrNull()
        assertTrue(error is BackupError.InvalidJson)
    }

    @Test
    fun `versionが無ければMissingField`() {
        val error = runCatching { BackupJson.decode("""{"tags":[]}""") }.exceptionOrNull()
        assertTrue(error is BackupError.MissingField)
    }

    @Test
    fun `未来のバージョンはNewerVersionで拒否する`() {
        val error = runCatching {
            BackupJson.decode("""{"version":99,"tags":[]}""")
        }.exceptionOrNull()
        assertTrue(error is BackupError.NewerVersion)
        assertEquals(99, (error as BackupError.NewerVersion).found)
    }

    @Test
    fun `未知のフィールドは無視する`() {
        val json = """
            {
              "version": 1,
              "unknownField": {"nested": true},
              "tags": [{"displayName": "旅行", "normalizedName": "旅行", "extra": 1}],
              "settings": {"folderColumns": 4, "unknownSetting": "x"}
            }
        """.trimIndent()
        val data = BackupJson.decode(json)
        assertEquals(1, data.tags.size)
        assertEquals(4, data.settings.folderColumns)
        // 欠けている設定は既定値
        assertEquals("LATEST", data.settings.sortMode)
        assertTrue(data.settings.showResumeCard)
    }

    @Test
    fun `空のフィールドや不正な要素は読み飛ばす`() {
        val json = """
            {
              "version": 1,
              "tags": [{"displayName": ""}, {"displayName": "A", "normalizedName": "a"}],
              "folderTags": [{"folderKey": "", "tag": "a"}, {"folderKey": "v:A/", "tag": ""}],
              "favoriteImages": ["", "content://x/1"]
            }
        """.trimIndent()
        val data = BackupJson.decode(json)
        assertEquals(1, data.tags.size)
        assertEquals("A", data.tags.single().displayName)
        assertTrue(data.folderTags.isEmpty())
        assertEquals(listOf("content://x/1"), data.favoriteImages)
    }

    @Test
    fun `タグが空ならnullではなく空リスト`() {
        val data = BackupJson.decode("""{"version":1}""")
        assertTrue(data.tags.isEmpty())
        assertTrue(data.folderTags.isEmpty())
        assertTrue(data.favoriteImages.isEmpty())
    }

    @Test
    fun `集計が件数どおりになる`() {
        val summary = sample().summary
        assertEquals(2, summary.tags)
        assertEquals(2, summary.folderTags)
        assertEquals(1, summary.favoriteFolders)
        assertEquals(1, summary.favoriteImages)
        assertEquals(1, summary.excludedFolders)
    }

    @Test
    fun `JSONに日本語がそのまま入る`() {
        val json = BackupJson.encode(sample())
        assertTrue(json.contains("旅行"))
    }

    // ---- 古いバックアップとの互換性 (Issue #151) ----

    @Test
    fun `古い形式のJSONは欠けた設定を既定値で補う`() {
        // Issue #74 当時の形式。新しい設定項目が無い
        val legacy = """
            {
              "version": 1,
              "exportedAt": "2026-11-01T00:00:00Z",
              "app": { "packageName": "com.tonbiattack.fastestpictureviewer", "versionName": "0.1.0" },
              "tags": [],
              "folderTags": [],
              "favoriteFolders": [],
              "favoriteImages": [],
              "excludedFolders": [],
              "settings": {
                "folderColumns": 3,
                "sortMode": "RECENT",
                "imageColumnsPortrait": 4,
                "imageColumnsLandscape": 5,
                "showResumeCard": false
              }
            }
        """.trimIndent()

        val data = BackupJson.decode(legacy)

        assertEquals(3, data.settings.folderColumns)
        assertEquals("RECENT", data.settings.sortMode)
        assertEquals(4, data.settings.imageColumnsPortrait)
        assertEquals(5, data.settings.imageColumnsLandscape)
        // 新しい項目は既定値
        assertEquals("NAME", data.settings.imageSortMode)
        assertTrue(data.settings.folderSortOverrides.isEmpty())
        assertTrue(data.settings.restoreScrollPosition)
        assertTrue(data.settings.showStaleMediaNotice)
        assertEquals(3, data.settings.slideshowIntervalSec)
        // 実際に存在した項目だけを記録する
        assertTrue(data.settings.presentFields.contains("folderColumns"))
        assertTrue("存在しない項目を上書き対象にしている", "imageSortMode" !in data.settings.presentFields)
    }

    @Test
    fun `新しい形式のJSONは全項目を記録する`() {
        val data = BackupJson.decode(BackupJson.encode(sample()))

        assertEquals(BackupSettings.ALL_FIELDS, data.settings.presentFields)
    }
}
