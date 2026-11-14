package com.tonbiattack.fastestpictureviewer.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 実体の無い行を整理してよいかの判定テスト (Issue #80)。
 *
 * 誤削除は取り返しがつかないため、「削除しない」側の条件を重点的に固定する。
 * 特に SD カードが抜けているだけの状態で実在する画像を消さないことを最優先する。
 */
class StaleMediaCleanupTest {

    private fun candidate(
        key: String,
        volume: String = "external_primary",
        images: Long = 10,
        bytes: Long = 1_000
    ) = StaleMediaCleanup.Candidate(
        folderKey = key,
        volumeName = volume,
        imageCount = images,
        totalBytes = bytes
    )

    @Test
    fun `候補が無ければ何もしない`() {
        val decision = StaleMediaCleanup.decide(
            mountedVolumes = setOf("external_primary"),
            candidates = emptyList(),
            totalFolders = 100
        )
        assertTrue(decision is StaleMediaCleanup.Decision.Rejected)
    }

    @Test
    fun `SDカードが抜けている場合は削除しない`() {
        // 最重要: カードが外れているだけで「実体が無い」と誤判定してはいけない
        val decision = StaleMediaCleanup.decide(
            mountedVolumes = setOf("external_primary"),
            candidates = listOf(candidate("sd:DCIM/Camera/", volume = "sd")),
            totalFolders = 100
        )

        assertTrue("SD の候補は必ず拒否する", decision is StaleMediaCleanup.Decision.Rejected)
    }

    @Test
    fun `マウントされていないボリュームの候補だけを除外する`() {
        val decision = StaleMediaCleanup.decide(
            mountedVolumes = setOf("external_primary"),
            candidates = listOf(
                candidate("external_primary:A/"),
                candidate("sd:B/", volume = "sd")
            ),
            totalFolders = 100
        )

        assertTrue(decision is StaleMediaCleanup.Decision.Allowed)
        decision as StaleMediaCleanup.Decision.Allowed
        assertEquals(listOf("external_primary:A/"), decision.folderKeys)
    }

    @Test
    fun `健全な状態では対象フォルダを返す`() {
        val decision = StaleMediaCleanup.decide(
            mountedVolumes = setOf("external_primary"),
            candidates = listOf(
                candidate("external_primary:A/"),
                candidate("external_primary:B/")
            ),
            totalFolders = 1000
        )

        assertTrue(decision is StaleMediaCleanup.Decision.Allowed)
        decision as StaleMediaCleanup.Decision.Allowed
        assertEquals(2, decision.folderKeys.size)
    }

    @Test
    fun `候補が多すぎる場合は一時的な不調とみなして中止する`() {
        // ライブラリの半数超が候補 = 端末側の異常。実在する画像を大量に失うより中止が安全
        val candidates = (0 until 60).map { candidate("external_primary:f$it/") }
        val decision = StaleMediaCleanup.decide(
            mountedVolumes = setOf("external_primary"),
            candidates = candidates,
            totalFolders = 100
        )

        assertTrue(decision is StaleMediaCleanup.Decision.Rejected)
    }

    @Test
    fun `ちょうど半数は実行する`() {
        val candidates = (0 until 50).map { candidate("external_primary:f$it/") }
        val decision = StaleMediaCleanup.decide(
            mountedVolumes = setOf("external_primary"),
            candidates = candidates,
            totalFolders = 100
        )

        assertTrue(decision is StaleMediaCleanup.Decision.Allowed)
    }

    @Test
    fun `1回の実行で扱うフォルダ数に上限がある`() {
        val candidates = (0 until 800).map { candidate("external_primary:f%04d/".format(it)) }
        val decision = StaleMediaCleanup.decide(
            mountedVolumes = setOf("external_primary"),
            candidates = candidates,
            totalFolders = 10_000
        )

        assertTrue(decision is StaleMediaCleanup.Decision.Allowed)
        decision as StaleMediaCleanup.Decision.Allowed
        assertEquals(StaleMediaCleanup.MAX_FOLDERS_PER_RUN, decision.folderKeys.size)
    }

    @Test
    fun `上限を超えた場合は同じフォルダから順に扱う`() {
        // 毎回同じ順序で処理し、取りこぼしや偏りを避ける
        val candidates = (0 until 600).map { candidate("external_primary:f%04d/".format(it)) }
        val first = StaleMediaCleanup.decide(
            mountedVolumes = setOf("external_primary"),
            candidates = candidates,
            totalFolders = 10_000
        ) as StaleMediaCleanup.Decision.Allowed
        val second = StaleMediaCleanup.decide(
            mountedVolumes = setOf("external_primary"),
            candidates = candidates.reversed(),
            totalFolders = 10_000
        ) as StaleMediaCleanup.Decision.Allowed

        assertEquals(first.folderKeys, second.folderKeys)
    }

    @Test
    fun `フォルダ数が 0 でも落ちない`() {
        val decision = StaleMediaCleanup.decide(
            mountedVolumes = setOf("external_primary"),
            candidates = listOf(candidate("external_primary:A/")),
            totalFolders = 0
        )
        assertTrue(decision is StaleMediaCleanup.Decision.Allowed)
    }

    @Test
    fun `小規模ライブラリでは割合が高くても実行する`() {
        // フォルダが数件しかない端末で、1 件だけ欠けているケースを止めない
        val decision = StaleMediaCleanup.decide(
            mountedVolumes = setOf("external_primary"),
            candidates = listOf(candidate("external_primary:only/")),
            totalFolders = 1
        )
        assertTrue(decision is StaleMediaCleanup.Decision.Allowed)
    }
}
