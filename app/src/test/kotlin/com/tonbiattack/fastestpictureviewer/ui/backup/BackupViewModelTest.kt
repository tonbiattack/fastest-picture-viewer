package com.tonbiattack.fastestpictureviewer.ui.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tonbiattack.fastestpictureviewer.data.AppDatabase
import com.tonbiattack.fastestpictureviewer.data.BackupData
import com.tonbiattack.fastestpictureviewer.data.BackupFolderRef
import com.tonbiattack.fastestpictureviewer.data.BackupJson
import com.tonbiattack.fastestpictureviewer.data.BackupRepository
import com.tonbiattack.fastestpictureviewer.data.BackupSettings
import com.tonbiattack.fastestpictureviewer.data.BackupSettingsAccess
import com.tonbiattack.fastestpictureviewer.data.BackupTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException
import java.io.InputStream

/** テストの初期状態として使う表示設定 */
private val DEFAULT_SETTINGS = BackupSettings(
    folderColumns = 2,
    sortMode = "LATEST",
    imageColumnsPortrait = 3,
    imageColumnsLandscape = 3,
    showResumeCard = true
)

/**
 * バックアップ画面の ViewModel のテスト (Issue #111)。
 *
 * 重点:
 * - ファイルを読めない・壊れている・大きすぎる場合に、DB も設定も変えないこと
 * - 確認 (confirmImport) するまでは何も反映されないこと
 * - キャンセルしても何も変わらないこと
 * - ストリームがどの経路でも閉じられること
 *
 * 実 DB (メモリ) と Fake の設定を使い、端末の実設定には書き込まない。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class BackupViewModelTest {

    private lateinit var db: AppDatabase
    private lateinit var settings: FakeSettingsAccess
    private lateinit var repository: BackupRepository

    /** 設定の読み書きを記録するだけの Fake。実設定には触れない */
    private class FakeSettingsAccess(
        var current: BackupSettings = DEFAULT_SETTINGS
    ) : BackupSettingsAccess {
        /** apply が呼ばれた回数。0 なら設定は書き換わっていない */
        var applyCount: Int = 0
        var applied: BackupSettings? = null

        override suspend fun snapshot(): BackupSettings = current

        override suspend fun apply(settings: BackupSettings) {
            applyCount++
            applied = settings
            current = settings
        }
    }

    /** 読み取りで必ず失敗するストリーム。close されたかを記録する */
    private class FailingInputStream : InputStream() {
        var closed: Boolean = false
        override fun read(): Int = throw IOException("読み取りに失敗しました")
        override fun close() {
            closed = true
        }
    }

    /** close されたかを記録するだけのストリーム */
    private class TrackingInputStream(private val body: ByteArray) : InputStream() {
        var closed: Boolean = false
        private var index = 0

        override fun read(): Int {
            if (index >= body.size) return -1
            return body[index++].toInt() and 0xFF
        }

        override fun close() {
            closed = true
        }
    }

    /**
     * テスト用ディスパッチャ。
     *
     * `Dispatchers.setMain` に渡すものと `runTest` に渡すものを**同じインスタンス**にしないと、
     * `viewModelScope`（Main）と `withContext`（テスト用）が別スケジューラで動き、
     * `advanceUntilIdle()` が片方を進められずテストが不安定になる。
     */
    private val dispatcher = StandardTestDispatcher()

    /**
     * Room の suspend DAO を呼び出しスレッドでその場実行するためのエグゼキュータ。
     *
     * 既定の Room は独自のスレッドプールで問い合わせを実行するため、
     * `advanceUntilIdle()` が戻った時点ではまだ DB への書き込みが終わっていないことがある。
     * 直接実行にすると `restore()` の完了と状態更新が同じ流れで進み、テストが決定的になる。
     */
    private val directExecutor = java.util.concurrent.Executor { it.run() }

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        // 集計キャッシュ無効化トリガはこのテストの対象外なので、素の Room で組み立てる
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(directExecutor)
            .setTransactionExecutor(directExecutor)
            .build()
        settings = FakeSettingsAccess()
        repository = BackupRepository(db, settings, nowSeconds = { 1_000L })
    }

    @After
    fun teardown() {
        db.close()
        Dispatchers.resetMain()
    }

    /**
     * テスト用の ViewModel。
     *
     * ディスパッチャは [dispatcher] に揃える。別のスケジューラを渡すと
     * `advanceUntilIdle()` が待てず、テストが不安定になる。
     * 上限を小さくして境界を数 KB で試せるようにする。
     */
    private fun newViewModel(
        maxImportBytes: Long = BackupViewModel.MAX_IMPORT_BYTES
    ): BackupViewModel {
        return BackupViewModel(
            backupRepository = repository,
            appVersionName = "0.1.0",
            maxImportBytes = maxImportBytes,
            ioDispatcher = dispatcher,
            computeDispatcher = dispatcher
        )
    }

    /** 取り込み対象のサンプル。タグ・お気に入り・除外・設定をひととおり含む */
    private fun sampleBackup(): BackupData = BackupData(
        exportedAt = "2026-11-16T10:30:00Z",
        appVersionName = "0.1.0",
        tags = listOf(BackupTag(displayName = "旅行", normalizedName = "旅行")),
        folderTags = emptyList(),
        favoriteFolders = listOf(BackupFolderRef("external_primary", "Pictures/A/")),
        favoriteImages = emptyList(),
        excludedFolders = listOf(BackupFolderRef("external_primary", "Private/")),
        settings = BackupSettings(
            folderColumns = 4,
            sortMode = "RECENT",
            imageColumnsPortrait = 5,
            imageColumnsLandscape = 2,
            showResumeCard = false
        )
    )

    private fun sampleJson(): String = BackupJson.encode(sampleBackup())

    // --- 読み込み失敗系: DB も設定も変えない ---

    @Test
    fun `読み取りに失敗してもDBと設定は変わらずエラーになる`() = runTest(dispatcher) {
        val vm = newViewModel()
        val stream = FailingInputStream()

        vm.prepareImport(stream)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertNotNull("エラーが利用者に伝わること", state.error)
        assertNull("確認待ちにはならない", state.pendingImport)
        assertFalse(state.isImporting)
        assertTrue("ストリームは閉じられること", stream.closed)
        assertNoDataChanged()
    }

    @Test
    fun `壊れたJSONは確認待ちにせずDBと設定を変えない`() = runTest(dispatcher) {
        val vm = newViewModel()
        val stream = TrackingInputStream("{ これは JSON ではない".toByteArray())

        vm.prepareImport(stream)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertNotNull(state.error)
        assertNull(state.pendingImport)
        assertTrue(stream.closed)
        assertNoDataChanged()
    }

    @Test
    fun `空のファイルも確認待ちにせずDBと設定を変えない`() = runTest(dispatcher) {
        val vm = newViewModel()
        val stream = TrackingInputStream(ByteArray(0))

        vm.prepareImport(stream)
        advanceUntilIdle()

        assertNotNull(vm.uiState.value.error)
        assertNull(vm.uiState.value.pendingImport)
        assertTrue(stream.closed)
        assertNoDataChanged()
    }

    @Test
    fun `上限を超えるファイルは拒否しDBと設定を変えない`() = runTest(dispatcher) {
        // 上限 1KiB に対し 2KiB のファイル。境界を数 KB で試す
        val vm = newViewModel(maxImportBytes = 1024L)
        val stream = TrackingInputStream(ByteArray(2048) { 'a'.code.toByte() })

        vm.prepareImport(stream)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertNotNull(state.error)
        assertTrue(
            "拒否理由が分かる文言であること: ${state.error}",
            state.error!!.contains("大きすぎます")
        )
        assertNull(state.pendingImport)
        assertTrue(stream.closed)
        assertNoDataChanged()
    }

    @Test
    fun `上限ちょうどのサイズはサイズ判定で弾かれない`() = runTest(dispatcher) {
        // 上限 1KiB ちょうど。中身は正しい JSON ではないので decode で落ちるが、
        // 「サイズ判定では弾かれない」ことを確認する
        val vm = newViewModel(maxImportBytes = 1024L)
        val stream = TrackingInputStream(ByteArray(1024) { 'a'.code.toByte() })

        vm.prepareImport(stream)
        advanceUntilIdle()

        val error = vm.uiState.value.error
        assertNotNull(error)
        assertFalse(
            "サイズでは弾かれないこと: $error",
            error!!.contains("大きすぎます")
        )
        assertTrue(stream.closed)
        assertNoDataChanged()
    }

    @Test
    fun `本番の上限は16MiBである`() {
        assertEquals(16L * 1024 * 1024, BackupViewModel.MAX_IMPORT_BYTES)
    }

    @Test
    fun `ファイルを開けなかったことを利用者に伝える`() = runTest(dispatcher) {
        val vm = newViewModel()

        vm.onOpenFailed()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertNotNull(state.error)
        assertTrue(
            "何が起きたか分かる文言であること: ${state.error}",
            state.error!!.contains("ファイルを開けませんでした")
        )
        assertNull(state.pendingImport)
        assertNoDataChanged()
    }

    // --- 読み取り成功: 確認前は何も変えない ---

    @Test
    fun `読み取りに成功しても確認前はDBと設定を変えない`() = runTest(dispatcher) {
        val vm = newViewModel()
        val stream = TrackingInputStream(sampleJson().toByteArray())

        vm.prepareImport(stream)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertNull("エラーにならないこと", state.error)
        assertNotNull("確認待ちになること", state.pendingImport)
        assertFalse(state.isImporting)
        assertTrue(stream.closed)
        // ここが本質: まだ何も反映されていない
        assertNoDataChanged()
    }

    @Test
    fun `キャンセルすると確認待ちが消えてDBと設定は変わらない`() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.prepareImport(TrackingInputStream(sampleJson().toByteArray()))
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.pendingImport)

        vm.cancelImport()
        advanceUntilIdle()

        assertNull(vm.uiState.value.pendingImport)
        assertNoDataChanged()
    }

    @Test
    fun `確認待ちが無い状態でconfirmImportしても何も起きない`() = runTest(dispatcher) {
        val vm = newViewModel()

        vm.confirmImport()
        advanceUntilIdle()

        assertNull(vm.uiState.value.lastResult)
        assertNoDataChanged()
    }

    // --- 確認後: 反映される ---

    @Test
    fun `確認後にタグお気に入り除外と設定が反映される`() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.prepareImport(TrackingInputStream(sampleJson().toByteArray()))
        advanceUntilIdle()
        assertNoDataChanged()

        vm.confirmImport()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertNull(state.error)
        assertNull("確認待ちは消えること", state.pendingImport)
        assertNotNull(state.lastResult)
        assertNotNull(state.message)

        // DB に反映されている
        val tags = db.tagDao().getAllTags()
        assertEquals(listOf("旅行"), tags.map { it.displayName })
        assertEquals(1, db.favoriteFolderDao().getAll().size)
        assertEquals(1, db.excludedFolderDao().getAll().size)

        // 設定も反映されている
        assertEquals(1, settings.applyCount)
        assertEquals(4, settings.current.folderColumns)
        assertEquals("RECENT", settings.current.sortMode)
        assertEquals(5, settings.current.imageColumnsPortrait)
        assertEquals(2, settings.current.imageColumnsLandscape)
        assertFalse(settings.current.showResumeCard)
    }

    @Test
    fun `confirmImportを連打しても復元は1回だけ`() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.prepareImport(TrackingInputStream(sampleJson().toByteArray()))
        advanceUntilIdle()

        // 確認ダイアログのボタン連打を模す。2 回目は pendingImport が
        // 同期的に消えているため何もしない
        vm.confirmImport()
        vm.confirmImport()
        advanceUntilIdle()

        assertEquals("設定の適用は 1 回だけであること", 1, settings.applyCount)
        assertEquals(1, db.tagDao().getAllTags().size)
        assertEquals(1, db.favoriteFolderDao().getAll().size)
    }

    @Test
    fun `取り込み途中で失敗したら一部反映の可能性を必ず伝える`() = runTest(dispatcher) {
        // 設定の適用で失敗する Fake。restore は DB と SharedPreferences をまたぐ
        // 単一トランザクションではないため、途中まで反映されうる
        val failingSettings = object : BackupSettingsAccess {
            override suspend fun snapshot(): BackupSettings = DEFAULT_SETTINGS
            override suspend fun apply(settings: BackupSettings) {
                throw IllegalStateException("設定の保存に失敗しました")
            }
        }
        val failingRepository = BackupRepository(db, failingSettings, nowSeconds = { 1_000L })
        val vm = BackupViewModel(
            backupRepository = failingRepository,
            appVersionName = "0.1.0",
            ioDispatcher = dispatcher,
            computeDispatcher = dispatcher
        )

        vm.prepareImport(TrackingInputStream(sampleJson().toByteArray()))
        advanceUntilIdle()
        vm.confirmImport()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertNotNull("エラーになること", state.error)
        assertTrue(
            "一部だけ反映された可能性を伝えること: ${state.error}",
            state.error!!.contains("一部だけが反映されている可能性")
        )
        assertEquals(
            "見出しが読み込み失敗と混同されないこと",
            BackupViewModel.PARTIAL_FAILURE_TITLE,
            state.errorTitle
        )
        // 例外の詳細（内部メッセージ）は画面に出さない
        assertFalse(
            "内部の例外メッセージを出さないこと: ${state.error}",
            state.error!!.contains("設定の保存に失敗しました")
        )
    }

    @Test
    fun `読み込み失敗の見出しは取り込み失敗と区別される`() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.prepareImport(TrackingInputStream("{ 壊れた".toByteArray()))
        advanceUntilIdle()

        assertEquals(BackupViewModel.DEFAULT_ERROR_TITLE, vm.uiState.value.errorTitle)
    }

    // --- 表示用ラベル ---

    @Test
    fun `書き出し日時はJSONの記録値をそのまま出す`() = runTest(dispatcher) {
        val vm = newViewModel()
        // タイムゾーン変換しないこと（書き出し側はローカル時刻に Z を付けている）
        assertEquals("2026-11-16T10:30:00Z", vm.exportedAtLabel(sampleBackup()))
    }

    @Test
    fun `書き出し日時が空なら不明と明示する`() = runTest(dispatcher) {
        val vm = newViewModel()
        val data = sampleBackup().copy(exportedAt = "")
        assertEquals("不明", vm.exportedAtLabel(data))
    }

    @Test
    fun `書き出し日時が長すぎる場合は切り詰める`() = runTest(dispatcher) {
        val vm = newViewModel()
        val data = sampleBackup().copy(exportedAt = "x".repeat(500))
        val label = vm.exportedAtLabel(data)
        assertTrue("長すぎる値をそのまま出さないこと", label.length <= 41)
        assertTrue(label.endsWith("…"))
    }

    @Test
    fun `表示設定のラベルに上書きされる実値が出る`() = runTest(dispatcher) {
        val vm = newViewModel()
        val label = vm.settingsLabel(sampleBackup().settings)

        assertTrue("列数が出ること: $label", label.contains("4 列"))
        assertTrue("並び順が日本語で出ること: $label", label.contains("最近閲覧順"))
        assertTrue("縦の列数が出ること: $label", label.contains("縦 5 列"))
        assertTrue("横の列数が出ること: $label", label.contains("横 2 列"))
        assertTrue("前回の続きの設定が出ること: $label", label.contains("表示しない"))
    }

    @Test
    fun `並び順が未知の値でもラベルが壊れない`() = runTest(dispatcher) {
        val vm = newViewModel()
        val label = vm.settingsLabel(sampleBackup().settings.copy(sortMode = "SOMETHING"))
        assertTrue(label.contains("SOMETHING"))
    }

    /** DB と設定が初期状態のままであることを確認する */
    private suspend fun assertNoDataChanged() {
        assertTrue("タグが増えていないこと", db.tagDao().getAllTags().isEmpty())
        assertTrue("フォルダお気に入りが増えていないこと", db.favoriteFolderDao().getAll().isEmpty())
        assertTrue("画像お気に入りが増えていないこと", db.favoriteImageDao().getAll().isEmpty())
        assertTrue("除外フォルダが増えていないこと", db.excludedFolderDao().getAll().isEmpty())
        assertEquals("設定が書き換わっていないこと", 0, settings.applyCount)
        assertEquals(DEFAULT_SETTINGS, settings.current)
    }
}
