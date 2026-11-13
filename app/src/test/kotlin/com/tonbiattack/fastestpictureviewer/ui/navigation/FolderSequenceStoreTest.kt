package com.tonbiattack.fastestpictureviewer.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 前後フォルダ移動のためのフォルダ並び共有のテスト (Issue #57) */
class FolderSequenceStoreTest {

    private val keys = listOf("v:A/", "v:B/", "v:C/", "v:D/")

    @Test
    fun `中間のフォルダは前後を返す`() {
        val store = FolderSequenceStore()
        store.update(keys)

        val adjacent = store.neighborsOf("v:B/")
        assertEquals("v:A/", adjacent.previous)
        assertEquals("v:C/", adjacent.next)
        assertTrue(adjacent.hasPrevious)
        assertTrue(adjacent.hasNext)
    }

    @Test
    fun `先頭は前が無く次だけ`() {
        val store = FolderSequenceStore()
        store.update(keys)

        val adjacent = store.neighborsOf("v:A/")
        assertNull(adjacent.previous)
        assertEquals("v:B/", adjacent.next)
        assertFalse(adjacent.hasPrevious)
    }

    @Test
    fun `末尾は次が無く前だけ`() {
        val store = FolderSequenceStore()
        store.update(keys)

        val adjacent = store.neighborsOf("v:D/")
        assertEquals("v:C/", adjacent.previous)
        assertNull(adjacent.next)
        assertFalse(adjacent.hasNext)
    }

    @Test
    fun `一覧に無いフォルダは前後ともnull`() {
        val store = FolderSequenceStore()
        store.update(keys)

        val adjacent = store.neighborsOf("v:Deleted/")
        assertNull(adjacent.previous)
        assertNull(adjacent.next)
        assertFalse(adjacent.hasAny)
    }

    @Test
    fun `1件だけなら前後ともnull`() {
        val store = FolderSequenceStore()
        store.update(listOf("v:Only/"))

        assertFalse(store.neighborsOf("v:Only/").hasAny)
    }

    @Test
    fun `空の一覧でも落ちない`() {
        val store = FolderSequenceStore()
        assertFalse(store.neighborsOf("v:A/").hasAny)
        assertTrue(store.folderKeys.value.isEmpty())
    }

    @Test
    fun `絞り込み後の並びを渡すとその順序で前後を返す`() {
        val store = FolderSequenceStore()
        // 検索結果が C, A の順だった場合
        store.update(listOf("v:C/", "v:A/"))

        assertEquals("v:A/", store.neighborsOf("v:C/").next)
        assertEquals("v:C/", store.neighborsOf("v:A/").previous)
        // 絞り込みで除外された B へは移動しない
        assertFalse(store.neighborsOf("v:C/").hasPrevious)
    }

    @Test
    fun `同じ内容の更新では再発行しない`() {
        val store = FolderSequenceStore()
        store.update(keys)
        val first = store.folderKeys.value
        store.update(keys.toList())
        // 値が等価なら同じインスタンスのまま (StateFlow の重複発行を避ける)
        assertTrue(first === store.folderKeys.value)
    }
}
