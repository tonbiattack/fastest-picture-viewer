package com.tonbiattack.fastestpictureviewer.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.nio.file.Files

/**
 * Auto Backup の控えの自動更新と復元 (Issue #151)。
 *
 * 「控えはあるが DB が空」のときだけ復元し、通常の起動では触らないことを確認する。
 * org.json は Android 実装に依存するため Robolectric 上で実行する。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class AutoBackupCoordinatorTest {

    private lateinit var filesDir: File
    private lateinit var store: AutoBackupStore

    @Before
    fun setUp() {
        filesDir = Files.createTempDirectory("auto-backup-coordinator").toFile()
        store = AutoBackupStore(filesDir)
    }

    @After
    fun tearDown() {
        filesDir.deleteRecursively()
    }

    /** メモリ上のデータを持つ差し替え用データソース */
    private class FakeBackupSource(
        var data: BackupData = emptyBackup(),
        var restoreCount: Int = 0,
        /** restore を失敗させる */
        var failRestore: Boolean = false
    ) : BackupDataSource {
        var collectedCount: Int = 0

        override suspend fun collect(appVersionName: String): BackupData {
            collectedCount++
            return data.copy(appVersionName = appVersionName)
        }

        override suspend fun restore(data: BackupData): RestoreResult {
            if (failRestore) throw IllegalStateException("restore failed")
            restoreCount++
            this.data = data
            return RestoreResult(
                tagsCreated = data.tags.size,
                tagsReused = 0,
                folderTagsAdded = data.folderTags.size,
                favoriteFoldersAdded = data.favoriteFolders.size,
                favoriteImagesAdded = data.favoriteImages.size,
                excludedFoldersAdded = data.excludedFolders.size,
                skippedFolderTags = 0,
                settingsApplied = true
            )
        }
    }

    @Test
    fun `データ変更時に控えを書き出す`() = runTest {
        val source = FakeBackupSource(data = backupWithTag("テスト"))
        val coordinator = AutoBackupCoordinator(
            store = store,
            backupRepository = source,
            appVersionName = { "0.6.0" },
            debounceMs = 0
        )

        coordinator.saveNow()

        assertTrue(store.exists())
        val json = store.load()
        assertNotNull(json)
        assertTrue(json!!.contains("テスト"))
    }

    @Test
    fun `予約した書き出しは待ち時間の後に実行される`() = runTest {
        val source = FakeBackupSource(data = backupWithTag("予約"))
        val coordinator = AutoBackupCoordinator(
            store = store,
            backupRepository = source,
            appVersionName = { "0.6.0" },
            debounceMs = 5_000
        )

        coordinator.scheduleSave(this)
        advanceUntilIdle()

        assertTrue("控えが書かれていない", store.exists())
        assertEquals("collect が呼ばれていない", 1, source.collectedCount)
    }

    @Test
    fun `連続した変更では最後の一度だけ書き出す`() = runTest {
        val source = FakeBackupSource(data = backupWithTag("連続"))
        val coordinator = AutoBackupCoordinator(
            store = store,
            backupRepository = source,
            appVersionName = { "0.6.0" },
            debounceMs = 5_000
        )

        repeat(5) {
            coordinator.scheduleSave(this)
            // 待ち時間の途中で次の変更が来る
        }
        advanceUntilIdle()

        assertEquals("変更のたびに書き出している", 1, source.collectedCount)
    }

    // ---- 復元 (Issue #151) ----

    @Test
    fun `DB が空で控えがあれば復元する`() = runTest {
        // Auto Backup から復元された直後の状態を再現する
        val restored = backupWithTag("復元される")
        store.save(BackupJson.encode(restored))

        val source = FakeBackupSource(data = emptyBackup())
        val coordinator = AutoBackupCoordinator(
            store = store,
            backupRepository = source,
            appVersionName = { "0.6.0" },
            debounceMs = 0
        )

        val didRestore = coordinator.restoreIfNeeded()

        assertTrue(didRestore)
        assertEquals(1, source.restoreCount)
        assertEquals("復元される", source.data.tags.first().displayName)
    }

    @Test
    fun `既にデータがある端末では復元しない`() = runTest {
        store.save(BackupJson.encode(backupWithTag("控えのタグ")))

        // 既にユーザーデータがある（通常の起動・更新）
        val source = FakeBackupSource(data = backupWithTag("既存のタグ"))
        val coordinator = AutoBackupCoordinator(
            store = store,
            backupRepository = source,
            appVersionName = { "0.6.0" },
            debounceMs = 0
        )

        val didRestore = coordinator.restoreIfNeeded()

        assertFalse("既存データがあるのに復元した", didRestore)
        assertEquals(0, source.restoreCount)
        assertEquals("既存のタグ", source.data.tags.first().displayName)
    }

    @Test
    fun `控えが無ければ何もしない`() = runTest {
        val source = FakeBackupSource(data = emptyBackup())
        val coordinator = AutoBackupCoordinator(
            store = store,
            backupRepository = source,
            appVersionName = { "0.6.0" },
            debounceMs = 0
        )

        assertFalse(coordinator.restoreIfNeeded())
        assertEquals(0, source.restoreCount)
    }

    @Test
    fun `控えが壊れていても落ちない`() = runTest {
        store.save("{ これは壊れた JSON")

        val source = FakeBackupSource(data = emptyBackup())
        val coordinator = AutoBackupCoordinator(
            store = store,
            backupRepository = source,
            appVersionName = { "0.6.0" },
            debounceMs = 0
        )

        // 例外を投げず、復元しなかったことを返す
        assertFalse(coordinator.restoreIfNeeded())
        assertEquals(0, source.restoreCount)
    }

    @Test
    fun `控えにユーザーデータが無ければ復元しない`() = runTest {
        store.save(BackupJson.encode(emptyBackup()))

        val source = FakeBackupSource(data = emptyBackup())
        val coordinator = AutoBackupCoordinator(
            store = store,
            backupRepository = source,
            appVersionName = { "0.6.0" },
            debounceMs = 0
        )

        assertFalse(coordinator.restoreIfNeeded())
        assertEquals(0, source.restoreCount)
    }

    @Test
    fun `復元に失敗しても例外を外へ出さない`() = runTest {
        store.save(BackupJson.encode(backupWithTag("失敗する")))

        val source = FakeBackupSource(data = emptyBackup(), failRestore = true)
        val coordinator = AutoBackupCoordinator(
            store = store,
            backupRepository = source,
            appVersionName = { "0.6.0" },
            debounceMs = 0
        )

        assertFalse(coordinator.restoreIfNeeded())
    }

    @Test
    fun `控えは画像やサムネイルを含まない`() = runTest {
        val source = FakeBackupSource(
            data = backupWithTag("タグ").copy(
                favoriteImages = listOf("content://media/external/images/media/1")
            )
        )
        val coordinator = AutoBackupCoordinator(
            store = store,
            backupRepository = source,
            appVersionName = { "0.6.0" },
            debounceMs = 0
        )

        coordinator.saveNow()

        // 画像のお気に入りは URI の文字列だけを持つ。画像そのものは含まない
        val decoded = BackupJson.decode(store.load()!!)
        assertEquals(
            listOf("content://media/external/images/media/1"),
            decoded.favoriteImages
        )
        assertTrue("控えが大きすぎる: ${store.sizeBytes()} bytes", store.sizeBytes() < 64 * 1024)
    }

    // ---- 初回移行と復元済み判定 (Issue #151) ----

    @Test
    fun `既存端末では復元せず初回の控えを作る`() = runTest {
        // 旧バージョンからの更新。DB は既にある
        val source = FakeBackupSource(data = backupWithTag("既存"))
        val state = AutoBackupInstallState(File(filesDir, "state"))
        val coordinator = AutoBackupCoordinator(
            store = store,
            backupRepository = source,
            appVersionName = { "0.6.1" },
            debounceMs = 0,
            installState = state,
            databaseExistedBeforeOpen = true
        )

        val restored = coordinator.initialize()

        assertFalse("既存端末で復元した", restored)
        assertEquals(0, source.restoreCount)
        assertTrue("初回の控えが作られていない", store.exists())
        assertTrue(state.isInitialized())
    }

    @Test
    fun `新規端末で控えがあれば復元する`() = runTest {
        store.save(BackupJson.encode(backupWithTag("復元")))

        val source = FakeBackupSource(data = emptyBackup())
        val state = AutoBackupInstallState(File(filesDir, "state"))
        val coordinator = AutoBackupCoordinator(
            store = store,
            backupRepository = source,
            appVersionName = { "0.6.1" },
            debounceMs = 0,
            installState = state,
            databaseExistedBeforeOpen = false
        )

        val restored = coordinator.initialize()

        assertTrue(restored)
        assertEquals(1, source.restoreCount)
        assertTrue(state.isInitialized())
    }

    @Test
    fun `復元が済んだ端末では二度目に復元しない`() = runTest {
        store.save(BackupJson.encode(backupWithTag("控え")))

        val source = FakeBackupSource(data = emptyBackup())
        val state = AutoBackupInstallState(File(filesDir, "state"))
        val first = AutoBackupCoordinator(
            store, source, { "0.6.1" }, debounceMs = 0,
            installState = state, databaseExistedBeforeOpen = false
        )
        first.initialize()

        // 2 回目の起動（DB は前回の控えで作られている）
        val second = AutoBackupCoordinator(
            store, source, { "0.6.1" }, debounceMs = 0,
            installState = state, databaseExistedBeforeOpen = true
        )
        assertFalse("二度目も復元した", second.initialize())
    }

    @Test
    fun `控えが壊れているときは空データで上書きしない`() = runTest {
        store.save("{ 壊れた JSON")

        val source = FakeBackupSource(data = emptyBackup())
        val state = AutoBackupInstallState(File(filesDir, "state"))
        val coordinator = AutoBackupCoordinator(
            store, source, { "0.6.1" }, debounceMs = 0,
            installState = state, databaseExistedBeforeOpen = false
        )

        assertFalse(coordinator.initialize())
        // 壊れた控えを残し、状態も確定しない（次回やり直せる）
        assertEquals("{ 壊れた JSON", store.load())
        assertFalse(state.isInitialized())
    }

    @Test
    fun `復元に失敗したら状態を確定せず次回に再試行する`() = runTest {
        store.save(BackupJson.encode(backupWithTag("再試行")))

        val failure = FakeBackupSource(data = emptyBackup(), failRestore = true)
        val state = AutoBackupInstallState(File(filesDir, "state"))
        AutoBackupCoordinator(
            store, failure, { "0.6.1" }, debounceMs = 0,
            installState = state, databaseExistedBeforeOpen = false
        ).initialize()

        assertFalse("失敗したのに復元済みになった", state.isInitialized())

        // 次回起動（DB が空のまま）で成功する
        val success = FakeBackupSource(data = emptyBackup())
        val retried = AutoBackupCoordinator(
            store, success, { "0.6.1" }, debounceMs = 0,
            installState = state, databaseExistedBeforeOpen = false
        )
        assertTrue("再試行されていない", retried.initialize())
        assertTrue(state.isInitialized())
    }

    @Test
    fun `初期化前の保存は控えを書き換えない`() = runTest {
        val state = AutoBackupInstallState(File(filesDir, "state"))
        val coordinator = AutoBackupCoordinator(
            store,
            FakeBackupSource(data = backupWithTag("x")),
            { "0.6.1" },
            debounceMs = 0,
            installState = state,
            databaseExistedBeforeOpen = true
        )

        // initialize 前に saveNow しても、破損控えを上書きしない
        val result = coordinator.saveNow()

        assertTrue(result is AutoBackupStore.WriteResult.Failed)
        assertFalse(store.exists())
    }

    private companion object {
        fun emptyBackup(): BackupData = BackupData(
            version = BackupData.CURRENT_VERSION,
            exportedAt = "2026-11-27T00:00:00Z",
            appVersionName = "0.6.0",
            tags = emptyList(),
            folderTags = emptyList(),
            favoriteFolders = emptyList(),
            favoriteImages = emptyList(),
            excludedFolders = emptyList(),
            settings = defaultSettings()
        )

        fun backupWithTag(name: String): BackupData = emptyBackup().copy(
            tags = listOf(BackupTag(displayName = name, normalizedName = name.lowercase()))
        )

        fun defaultSettings(): BackupSettings = BackupSettings(
            folderColumns = 2,
            sortMode = "LATEST",
            imageColumnsPortrait = 3,
            imageColumnsLandscape = 3,
            showResumeCard = true
        )
    }
}