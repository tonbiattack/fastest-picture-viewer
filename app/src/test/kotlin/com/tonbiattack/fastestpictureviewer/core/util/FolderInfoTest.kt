package com.tonbiattack.fastestpictureviewer.core.util

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** FolderNames / FolderKeys の純粋ロジックテスト */
class FolderInfoTest {

    @Test
    fun `フォルダ名は相対パスの最終セグメントから取り出す`() {
        assertEquals("vacation", FolderNames.parse("Pictures/vacation/"))
        assertEquals("DCIM", FolderNames.parse("DCIM/"))
        assertEquals("nested", FolderNames.parse("Pictures/a/nested"))
    }

    @Test
    fun `空の相対パスはフォールバック名を返す`() {
        val name = FolderNames.parse("")
        assertTrue(name.isNotBlank())
    }

    @Test
    fun `フォルダキーはボリューム名と相対パスの複合で衝突しない`() {
        val internal = FolderKeys.create("external_primary", "Pictures/vacation/")
        val sdCard = FolderKeys.create("sdcard", "Pictures/vacation/")
        assertEquals("external_primary:Pictures/vacation/", internal)
        // 同名フォルダでもボリュームが違えば別キーになる (仕様書 §4.1)
        assertTrue(internal != sdCard)
    }

    @Test
    fun `フォルダキーからフォルダ名を復元できる`() {
        val key = FolderKeys.create("external_primary", "Pictures/vacation/")
        assertEquals("vacation", FolderNames.fromFolderKey(key))
    }

    @Test
    fun `相対パスが空のフォルダキーでもフォルダ名を復元できる`() {
        val key = FolderKeys.create("sdcard", "")
        val name = FolderNames.fromFolderKey(key)
        assertTrue(name.isNotBlank())
    }
}
