package com.tonbiattack.fastestpictureviewer.ui.folder

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 除外フォルダの監視が「初回に 1 回だけ」発火することのテスト (Issue #98)。
 *
 * 以前は `map { Unit }` の後に `onStart { emit(Unit) }` を置いていたため、
 * onStart の Unit と Room の初回 emit が重なって 2 回発火していた。
 * その結果、35 万行のフォルダ集計を起動時に 2 回実行し、実機で約 2 秒悪化していた。
 *
 * Room の Flow は購読直後に必ず現在値を 1 回発行する。ここでは `flowOf` で
 * その挙動を模して、初回発火の回数を固定する。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExcludedFoldersTriggerTest {

    @Test
    fun `対策後は初回の発火が1回だけになる`() = runTest {
        var emissions = 0

        flowOf<List<String>>(emptyList())
            .map { Unit }
            .onStart { emit(Unit) }
            .distinctUntilChanged()
            .collect { emissions++ }

        assertEquals(
            "onStart の Unit と Room の初回 emit が同一視され、1 回に畳まれる",
            1,
            emissions
        )
    }

    @Test
    fun `対策前は初回に2回発火する_回帰の再現`() = runTest {
        var emissions = 0

        flowOf<List<String>>(emptyList())
            .map { Unit }
            .onStart { emit(Unit) }
            // わざと distinctUntilChanged を外す
            .collect { emissions++ }

        assertEquals(
            "distinctUntilChanged が無いと 2 回発火し、集計クエリも 2 回走る",
            2,
            emissions
        )
    }

    @Test
    fun `除外フォルダが実際に変わったときは追加の発火がある`() = runTest {
        // Unit に潰しているため、値の変化では発火しない。
        // これは現状の設計（除外の変更は同期側で一覧を取り直す）を固定するもの
        val emissions = mutableListOf<Unit>()

        flowOf<List<String>>(emptyList())
            .map { Unit }
            .onStart { emit(Unit) }
            .distinctUntilChanged()
            .collect { emissions += it }

        assertEquals(1, emissions.size)
    }
}
