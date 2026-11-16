package com.tonbiattack.fastestpictureviewer.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/**
 * 初回復元案内の状態管理のテスト (Issue #111)。
 *
 * 大事なのは次の 3 点。
 * 1. 新規インストールでは案内を出し、既存端末（旧バージョンからの更新）では出さない
 * 2. DB を開く前に pending を永続化するため、途中でプロセスが落ちても案内を落とさない
 * 3. marker が壊れている・書けないときは「案内しない」側に倒す（fail closed）
 *
 * 状態の判定は marker ファイルだけで決まるため、大半は実ファイルを使う純粋な JVM テストとして書く。
 * Context 経由で noBackupFilesDir に置かれることだけ Robolectric で確認する。
 */
class InitialBackupPromptStoreTest {

    private lateinit var dir: File

    @Before
    fun setup() {
        dir = Files.createTempDirectory("fpv-prompt").toFile()
    }

    @After
    fun teardown() {
        dir.deleteRecursively()
    }

    /** テスト用の marker ファイル */
    private fun marker(): File = File(dir, "initial_backup_prompt.marker")

    /** marker の中身を直接書く（外部要因で状態が変わった状況を作る） */
    private fun writeMarkerRaw(text: String) {
        marker().writeText(text, Charsets.UTF_8)
    }

    private fun newStore(): InitialBackupPromptStore = InitialBackupPromptStore(marker())

    // ------------------------------------------------------------------
    // 新規インストール / 既存端末
    // ------------------------------------------------------------------

    @Test
    fun `新規インストールでは案内を出す`() {
        val store = newStore()

        val show = store.initializeForInstall(databaseExistsBeforeOpen = false)

        assertTrue("新規インストールで案内が出ない", show)
        assertTrue("pending が永続化されていない", store.shouldShow())
        assertEquals("v1:PENDING", marker().readText(Charsets.UTF_8))
    }

    @Test
    fun `旧バージョンから更新した既存端末では案内を出さない`() {
        val store = newStore()

        val show = store.initializeForInstall(databaseExistsBeforeOpen = true)

        assertFalse("既存端末で案内が出た", show)
        assertFalse("既存端末で shouldShow が true", store.shouldShow())
        assertEquals("v1:HANDLED", marker().readText(Charsets.UTF_8))
    }

    // ------------------------------------------------------------------
    // 再起動・同一プロセスでの再初期化
    // ------------------------------------------------------------------

    @Test
    fun `pending は再起動しても保持される`() {
        newStore().initializeForInstall(databaseExistsBeforeOpen = false)

        // 別インスタンス = アプリの再起動に相当
        val restarted = newStore()

        assertTrue("再起動後に案内が消えた", restarted.shouldShow())
        assertTrue(
            "再起動後の初期化で案内が出ない",
            restarted.initializeForInstall(databaseExistsBeforeOpen = false)
        )
    }

    @Test
    fun `DB作成前に落ちても次の起動で案内を出す`() {
        // DB を開く前に pending を書いた直後にプロセスが落ちた状況。
        // 次回起動では DB が存在するが、pending を優先して案内を続ける
        newStore().initializeForInstall(databaseExistsBeforeOpen = false)

        val nextLaunch = newStore()
        val show = nextLaunch.initializeForInstall(databaseExistsBeforeOpen = true)

        assertTrue("DB が存在すると pending が捨てられた", show)
        assertTrue("pending が HANDLED に上書きされた", nextLaunch.shouldShow())
    }

    @Test
    fun `同一プロセスで再初期化しても結果が変わらない`() {
        val store = newStore()

        val first = store.initializeForInstall(databaseExistsBeforeOpen = false)
        val second = store.initializeForInstall(databaseExistsBeforeOpen = false)
        val third = store.initializeForInstall(databaseExistsBeforeOpen = true)

        assertTrue("1 回目で案内が出ない", first)
        assertTrue("2 回目で案内が消えた", second)
        assertTrue("3 回目で案内が消えた", third)
    }

    @Test
    fun `shouldShow はメモリにキャッシュせず marker を読み直す`() {
        val store = newStore()
        store.initializeForInstall(databaseExistsBeforeOpen = false)
        assertTrue(store.shouldShow())

        // 外部要因で marker が変わった状況（別プロセス・手動削除など）
        writeMarkerRaw("v1:HANDLED")

        assertFalse("marker を読み直していない", store.shouldShow())
    }

    // ------------------------------------------------------------------
    // dismiss
    // ------------------------------------------------------------------

    @Test
    fun `dismiss すると以後案内を出さない`() {
        val store = newStore()
        store.initializeForInstall(databaseExistsBeforeOpen = false)

        store.dismiss()

        assertFalse("dismiss 後も shouldShow が true", store.shouldShow())
        assertFalse(
            "dismiss 後の初期化で案内が出た",
            store.initializeForInstall(databaseExistsBeforeOpen = false)
        )
    }

    @Test
    fun `dismiss は再起動後も維持される`() {
        val store = newStore()
        store.initializeForInstall(databaseExistsBeforeOpen = false)
        store.dismiss()

        val restarted = newStore()

        assertFalse("再起動後に案内が復活した", restarted.shouldShow())
        assertFalse(
            "再起動後の初期化で案内が出た",
            restarted.initializeForInstall(databaseExistsBeforeOpen = false)
        )
    }

    @Test
    fun `同一プロセスで Activity を再生成しても dismiss が反映される`() {
        // AppContainer 初期化後に Activity が recreate され、
        // 同じプロセス内で store を作り直す状況。メモリにキャッシュしていると
        // 古い pending を返して案内が再表示されてしまう
        val first = newStore()
        first.initializeForInstall(databaseExistsBeforeOpen = false)
        assertTrue("前提: 最初は案内が出る", first.shouldShow())

        first.dismiss()

        // 同一プロセス内での作り直し（Activity recreate / ViewModel 再生成に相当）
        val recreated = newStore()

        assertFalse("再生成後に shouldShow が true のまま", recreated.shouldShow())
        assertFalse(
            "再生成後の初期化で案内が再表示された",
            recreated.initializeForInstall(databaseExistsBeforeOpen = false)
        )
        // 元のインスタンスから見ても同じ結果になること
        assertFalse("元のインスタンスで shouldShow が true のまま", first.shouldShow())
    }

    @Test
    fun `同一プロセスで Activity を再生成しても pending が保持される`() {
        // dismiss していない場合、再生成しても案内は出続ける必要がある
        val first = newStore()
        first.initializeForInstall(databaseExistsBeforeOpen = false)

        val recreated = newStore()

        assertTrue("再生成後に pending が消えた", recreated.shouldShow())
        assertTrue(
            "再生成後の初期化で案内が出ない",
            recreated.initializeForInstall(databaseExistsBeforeOpen = false)
        )
    }

    @Test
    fun `DB手動リセット時も handled を維持する`() {
        // 案内を閉じたあと、利用者が DB を手動で初期化した状況。
        // DB が無くなっても「案内済み」は維持し、再表示しない
        val store = newStore()
        store.initializeForInstall(databaseExistsBeforeOpen = false)
        store.dismiss()

        val afterReset = newStore()
        val show = afterReset.initializeForInstall(databaseExistsBeforeOpen = false)

        assertFalse("DB リセットで案内が再表示された", show)
        assertFalse("DB リセットで shouldShow が true", afterReset.shouldShow())
    }

    // ------------------------------------------------------------------
    // 破損 marker（fail closed）
    // ------------------------------------------------------------------

    @Test
    fun `空の marker は破損として扱い案内しない`() {
        writeMarkerRaw("")

        val store = newStore()

        assertFalse("空 marker で案内が出た", store.shouldShow())
        assertFalse(
            "空 marker の初期化で案内が出た",
            store.initializeForInstall(databaseExistsBeforeOpen = false)
        )
    }

    @Test
    fun `不正な内容の marker は破損として扱い案内しない`() {
        writeMarkerRaw("これは marker ではない")

        val store = newStore()

        assertFalse("不正 marker で案内が出た", store.shouldShow())
        assertFalse(
            "不正 marker の初期化で案内が出た",
            store.initializeForInstall(databaseExistsBeforeOpen = false)
        )
    }

    @Test
    fun `未知の状態値は破損として扱い案内しない`() {
        writeMarkerRaw("v1:UNKNOWN_STATE")

        val store = newStore()

        assertFalse("未知の状態値で案内が出た", store.shouldShow())
        assertFalse(
            "未知の状態値の初期化で案内が出た",
            store.initializeForInstall(databaseExistsBeforeOpen = false)
        )
    }

    @Test
    fun `版が違う marker は破損として扱い案内しない`() {
        writeMarkerRaw("v2:PENDING")

        val store = newStore()

        assertFalse("版違い marker で案内が出た", store.shouldShow())
        assertFalse(
            "版違い marker の初期化で案内が出た",
            store.initializeForInstall(databaseExistsBeforeOpen = false)
        )
    }

    @Test
    fun `marker がディレクトリなら破損として扱い案内しない`() {
        // ファイルであるべき場所がディレクトリになっている異常状態
        marker().mkdirs()

        val store = newStore()

        assertFalse("ディレクトリ marker で案内が出た", store.shouldShow())
        assertFalse(
            "ディレクトリ marker の初期化で案内が出た",
            store.initializeForInstall(databaseExistsBeforeOpen = false)
        )
    }

    // ------------------------------------------------------------------
    // 書き込み失敗（fail closed）
    // ------------------------------------------------------------------

    @Test
    fun `書き込みに失敗したら新規でも案内しない`() {
        // marker の親を通常ファイルにして、ディレクトリを作れない状況にする
        val blocker = File(dir, "blocker")
        blocker.writeText("not a directory", Charsets.UTF_8)
        val store = InitialBackupPromptStore(File(blocker, "initial_backup_prompt.marker"))

        val show = store.initializeForInstall(databaseExistsBeforeOpen = false)

        assertFalse("書き込み失敗なのに案内を出すと答えた", show)
        assertFalse("書き込み失敗なのに shouldShow が true", store.shouldShow())
    }

    @Test
    fun `書き込みに失敗した状態は再起動しても案内しない`() {
        // 書けなかったので marker は存在しない。次回起動でも同じ判定になること
        val blocker = File(dir, "blocker-restart")
        blocker.writeText("not a directory", Charsets.UTF_8)
        val markerPath = File(blocker, "initial_backup_prompt.marker")

        InitialBackupPromptStore(markerPath).initializeForInstall(databaseExistsBeforeOpen = false)

        val restarted = InitialBackupPromptStore(markerPath)

        assertFalse("書き込み失敗後の再起動で案内が出た", restarted.shouldShow())
        assertFalse(
            "書き込み失敗後の再起動で初期化しても案内が出た",
            restarted.initializeForInstall(databaseExistsBeforeOpen = false)
        )
    }

    @Test
    fun `書き込みに失敗した既存端末は再起動しても案内しない`() {
        val blocker = File(dir, "blocker-existing")
        blocker.writeText("not a directory", Charsets.UTF_8)
        val markerPath = File(blocker, "initial_backup_prompt.marker")

        InitialBackupPromptStore(markerPath).initializeForInstall(databaseExistsBeforeOpen = true)

        val restarted = InitialBackupPromptStore(markerPath)

        assertFalse("書き込み失敗の既存端末で再起動後に案内が出た", restarted.shouldShow())
        assertFalse(
            "書き込み失敗の既存端末で再初期化しても案内が出た",
            restarted.initializeForInstall(databaseExistsBeforeOpen = true)
        )
    }

    @Test
    fun `書き込みに失敗した既存端末では案内しない`() {
        val blocker = File(dir, "blocker2")
        blocker.writeText("not a directory", Charsets.UTF_8)
        val store = InitialBackupPromptStore(File(blocker, "initial_backup_prompt.marker"))

        val show = store.initializeForInstall(databaseExistsBeforeOpen = true)

        assertFalse("書き込み失敗の既存端末で案内が出た", show)
    }

    @Test
    fun `書き込みに失敗しても例外を投げない`() {
        val blocker = File(dir, "blocker3")
        blocker.writeText("not a directory", Charsets.UTF_8)
        val store = InitialBackupPromptStore(File(blocker, "initial_backup_prompt.marker"))

        // 起動を妨げないこと。例外が飛べばこのテストが失敗する
        store.initializeForInstall(databaseExistsBeforeOpen = false)
        store.dismiss()
        store.shouldShow()
    }

    @Test
    fun `dismiss の書き込みに失敗しても例外を投げない`() {
        val blocker = File(dir, "blocker4")
        blocker.writeText("not a directory", Charsets.UTF_8)
        val store = InitialBackupPromptStore(File(blocker, "initial_backup_prompt.marker"))

        store.dismiss()

        // 書けていないので案内は出ない（fail closed）
        assertFalse(store.shouldShow())
    }

    @Test
    fun `dismiss の書き込みに失敗すると pending が残り次回起動で案内が出る`() {
        // 同一の marker に対して dismiss の書き込みだけを失敗させる。
        // 実装は一時ファイル経由で書くため、その一時パスをディレクトリにして
        // 書き込みを失敗させる（marker 本体には触れない）
        val store = newStore()
        store.initializeForInstall(databaseExistsBeforeOpen = false)
        assertTrue("前提: pending が永続化されている", marker().isFile)
        assertEquals("前提: pending である", "v1:PENDING", marker().readText(Charsets.UTF_8))

        val tempPath = File(dir, "initial_backup_prompt.marker.tmp")
        assertTrue("前提: 一時パスをディレクトリにできない", tempPath.mkdirs())

        // ここで書き込みが失敗する。例外は投げない
        store.dismiss()

        // 書けていないので marker は pending のまま。次回起動でも案内を出す
        assertEquals(
            "dismiss の書き込み失敗で marker が書き換わった",
            "v1:PENDING",
            marker().readText(Charsets.UTF_8)
        )
        assertTrue("書き込み失敗なのに shouldShow が false", store.shouldShow())

        val restarted = newStore()
        assertTrue("次回起動で案内が出ない", restarted.shouldShow())
        assertTrue(
            "次回起動の初期化で案内が出ない",
            restarted.initializeForInstall(databaseExistsBeforeOpen = false)
        )
    }

    // ------------------------------------------------------------------
    // 一時ファイルを残さない
    // ------------------------------------------------------------------

    @Test
    fun `書き込み後に一時ファイルを残さない`() {
        val store = newStore()
        store.initializeForInstall(databaseExistsBeforeOpen = false)

        val leftovers = dir.listFiles()?.filter { it.name.endsWith(".tmp") } ?: emptyList()

        assertTrue("一時ファイルが残っている: $leftovers", leftovers.isEmpty())
    }

}

/**
 * Context 経由の生成が noBackupFilesDir を使うことの確認。
 *
 * 通常の SharedPreferences は OS の自動バックアップで転送されうるため、
 * marker は自動バックアップ対象外の noBackupFilesDir に置く必要がある。
 * ここだけ Robolectric の Context が要るので、純粋な JVM テストとは分ける。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class InitialBackupPromptStoreContextTest {

    @Test
    fun `Context 経由では noBackupFilesDir に marker を置く`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val expected = File(context.noBackupFilesDir, "initial_backup_prompt.marker")
        expected.delete()

        val store = InitialBackupPromptStore(context)
        val show = store.initializeForInstall(databaseExistsBeforeOpen = false)

        assertTrue("Context 経由で案内が出ない", show)
        assertTrue("noBackupFilesDir に marker が無い", expected.isFile)
        assertEquals("v1:PENDING", expected.readText(Charsets.UTF_8))

        expected.delete()
    }

    @Test
    fun `Context 経由の dismiss は再生成後も維持される`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val expected = File(context.noBackupFilesDir, "initial_backup_prompt.marker")
        expected.delete()

        InitialBackupPromptStore(context).apply {
            initializeForInstall(databaseExistsBeforeOpen = false)
            dismiss()
        }

        // Activity の recreate や AppContainer 再初期化に相当する作り直し
        val recreated = InitialBackupPromptStore(context)

        assertFalse("再生成後に案内が復活した", recreated.shouldShow())
        assertFalse(
            "再生成後の初期化で案内が出た",
            recreated.initializeForInstall(databaseExistsBeforeOpen = false)
        )

        expected.delete()
    }
}
