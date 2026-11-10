package com.tonbiattack.fastestpictureviewer.ui.folder

import org.junit.Assert.assertEquals
import org.junit.Test

/** フォルダ一覧の復元先インデックス決定ロジックのテスト */
class FolderListScrollResolverTest {

    private fun folder(key: String) = FolderUiModel(
        folderKey = key,
        volumeName = "v",
        relativePath = key.substringAfter(':'),
        folderName = key,
        imageCount = 1,
        latestModifiedSec = 0,
        representativeImageUri = null
    )

    private val folders = listOf(folder("v:A/"), folder("v:B/"), folder("v:C/"))

    @Test
    fun `アンカーが見つかればその位置を返す`() {
        assertEquals(1, FolderListScrollResolver.resolve(folders, "v:B/", fallbackIndex = 2))
    }

    @Test
    fun `アンカーが削除されていれば保存時のインデックスへフォールバックする`() {
        assertEquals(2, FolderListScrollResolver.resolve(folders, "v:Deleted/", fallbackIndex = 2))
    }

    @Test
    fun `フォールバックインデックスは範囲内に丸める`() {
        assertEquals(2, FolderListScrollResolver.resolve(folders, null, fallbackIndex = 99))
        assertEquals(0, FolderListScrollResolver.resolve(folders, null, fallbackIndex = -5))
    }

    @Test
    fun `一覧が空なら0を返す`() {
        assertEquals(0, FolderListScrollResolver.resolve(emptyList(), "v:A/", fallbackIndex = 3))
    }
}
