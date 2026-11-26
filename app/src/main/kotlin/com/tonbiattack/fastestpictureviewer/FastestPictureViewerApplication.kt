package com.tonbiattack.fastestpictureviewer

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import com.tonbiattack.fastestpictureviewer.data.AppDatabase
import com.tonbiattack.fastestpictureviewer.data.AutoBackupStore
import com.tonbiattack.fastestpictureviewer.data.AutoBackupCoordinator
import com.tonbiattack.fastestpictureviewer.data.BackupRepository
import com.tonbiattack.fastestpictureviewer.data.BackupStatusStore
import com.tonbiattack.fastestpictureviewer.data.ContentResolverReadableProbe
import com.tonbiattack.fastestpictureviewer.data.DatabaseReset
import com.tonbiattack.fastestpictureviewer.data.InitialBackupPromptStore
import com.tonbiattack.fastestpictureviewer.data.LastViewedStore
import com.tonbiattack.fastestpictureviewer.data.MediaStoreScanner
import com.tonbiattack.fastestpictureviewer.data.SharedPreferencesLastViewedStore
import com.tonbiattack.fastestpictureviewer.data.SharedPreferencesBackupStatusStore
import com.tonbiattack.fastestpictureviewer.data.ViewSettingsBackupAccess
import com.tonbiattack.fastestpictureviewer.perf.StartupTrace
import com.tonbiattack.fastestpictureviewer.repository.GalleryRepository
import com.tonbiattack.fastestpictureviewer.repository.GalleryRepositoryImpl
import com.tonbiattack.fastestpictureviewer.ui.favorites.FavoriteImagesViewModel
import com.tonbiattack.fastestpictureviewer.ui.backup.BackupViewModel
import com.tonbiattack.fastestpictureviewer.ui.folder.ExcludedFoldersViewModel
import com.tonbiattack.fastestpictureviewer.ui.folder.FolderListViewModel
import com.tonbiattack.fastestpictureviewer.ui.folder.FolderViewSettings
import com.tonbiattack.fastestpictureviewer.ui.folder.SharedPreferencesFolderViewSettings
import com.tonbiattack.fastestpictureviewer.ui.grid.ImageGridViewSettings
import com.tonbiattack.fastestpictureviewer.ui.grid.ImageGridViewModel
import com.tonbiattack.fastestpictureviewer.ui.grid.SharedPreferencesImageGridViewSettings
import com.tonbiattack.fastestpictureviewer.ui.navigation.FolderSequenceStore
import com.tonbiattack.fastestpictureviewer.ui.viewer.ImageViewerViewModel
import com.tonbiattack.fastestpictureviewer.ui.viewer.SharedPreferencesSlideshowSettings
import com.tonbiattack.fastestpictureviewer.ui.viewer.SlideshowSettings
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.annotation.ExperimentalCoilApi
import coil.disk.DiskCache
import coil.memory.MemoryCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 手動の簡易 DI コンテナ。ViewModel 生成もここで担う。
 * SavedStateHandle は CreationExtras から復元する (Issue #4 のプロセス再生成対応)。
 */
class AppContainer(
    context: Context,
    private val imageLoader: ImageLoader,
    /**
     * アプリ全体のコルーチンスコープ (Issue #151)。
     * Auto Backup の控えは画面に依存しないため、ここで動かす。
     */
    private val appScope: kotlinx.coroutines.CoroutineScope
) {

    /** DB を開く前に既存インストールを判定する。案内は復元を自動実行しない。 */
    val initialBackupPromptStore = InitialBackupPromptStore(context).also {
        it.initializeForInstall(
            databaseExistsBeforeOpen = context.getDatabasePath(AppDatabase.DATABASE_NAME).exists()
        )
    }

    val database: AppDatabase = AppDatabase.build(context).also {
        // 起動区間の計測 (Issue #99)。DB を開き終えた時点
        StartupTrace.mark("db_open")
    }

    /**
     * バックアップの書き出し状況 (Issue #108)。
     * 最終書き出し日時と、書き出し後の変更有無だけを持つ。
     * repository より先に作る（変更通知の記録先として渡すため）。
     */
    val backupStatusStore: BackupStatusStore = SharedPreferencesBackupStatusStore(context)

    /** フォルダ一覧の列数など、画面設定の永続化 */
    val folderViewSettings: FolderViewSettings = SharedPreferencesFolderViewSettings(context)

    /** フォルダ内画像グリッドの列数設定 */
    val imageGridViewSettings: ImageGridViewSettings = SharedPreferencesImageGridViewSettings(context)

    /** スライドショーの表示間隔 (Issue #124) */
    val slideshowSettings: SlideshowSettings = SharedPreferencesSlideshowSettings(context)

    /** 起動時の「前回の続き」用の最後の閲覧位置 */
    val lastViewedStore: LastViewedStore = SharedPreferencesLastViewedStore(context)

    /** タグ・お気に入り・設定のバックアップ (Issue #74) */
    val backupRepository: BackupRepository = BackupRepository(
        db = database,
        settingsAccess = ViewSettingsBackupAccess(
            folderViewSettings,
            imageGridViewSettings,
            slideshowSettings
        )
    )

    /**
     * Auto Backup 用の控えの保存先 (Issue #151)。
     *
     * タグ・お気に入り・除外・表示設定は Room にあるが、DB は 295MB で
     * Auto Backup の上限を超えるため除外している (#63)。再生成できない
     * データだけを小さな JSON に書き出し、Auto Backup の対象に置く。
     */
    val autoBackup: AutoBackupStore = AutoBackupStore(context.filesDir)

    /**
     * Auto Backup の控えを維持する (Issue #151)。
     *
     * `repository` より先に作る。`repository` の変更通知から参照するため、
     * 生成順が逆だと未初期化の参照になる。
     */
    val autoBackupCoordinator: AutoBackupCoordinator = AutoBackupCoordinator(
        store = autoBackup,
        backupRepository = backupRepository,
        appVersionName = { BuildConfig.VERSION_NAME }
    )

    val repository: GalleryRepository =
        GalleryRepositoryImpl(
            db = database,
            scanner = MediaStoreScanner(context),
            readableProbe = ContentResolverReadableProbe(context),
            // タグ・お気に入り・除外の変更を記録し、バックアップのリマインドに使う (Issue #108)
            // 併せて Auto Backup 用の控えも更新する (Issue #151)
            onUserDataChanged = {
                backupStatusStore.markChanged()
                autoBackupCoordinator.scheduleSave(appScope)
            }
        ).also {
        StartupTrace.mark("repository_ready")
    }

    /**
     * フォルダ一覧に表示中の並び。ビューアから前後フォルダへ移動するときに使う (Issue #57)。
     * メモリ上のみで、DB への読み書きは行わない。
     */
    val folderSequenceStore: FolderSequenceStore = FolderSequenceStore()

    /**
     * サムネイルのメモリ・ディスクキャッシュを削除する。
     * Room のメタ情報は消さないため、次回表示時に再生成されるだけ。
     */
    @OptIn(ExperimentalCoilApi::class)
    suspend fun clearImageCaches() = withContext(Dispatchers.IO) {
        imageLoader.memoryCache?.clear()
        imageLoader.diskCache?.clear()
    }

    /** 全 ViewModel 共通のファクトリ */
    val viewModelFactory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            val savedStateHandle = with(extras) { createSavedStateHandle() }
            return when {
                modelClass.isAssignableFrom(FolderListViewModel::class.java) ->
                    FolderListViewModel(
                        repository,
                        folderViewSettings,
                        lastViewedStore,
                        backupStatusStore
                    )
                modelClass.isAssignableFrom(ExcludedFoldersViewModel::class.java) ->
                    ExcludedFoldersViewModel(repository)
                modelClass.isAssignableFrom(FavoriteImagesViewModel::class.java) ->
                    FavoriteImagesViewModel(repository)
                modelClass.isAssignableFrom(ImageGridViewModel::class.java) ->
                    ImageGridViewModel(repository, savedStateHandle, imageGridViewSettings)
                modelClass.isAssignableFrom(ImageViewerViewModel::class.java) ->
                    ImageViewerViewModel(
                        repository,
                        savedStateHandle,
                        folderSequenceStore,
                        slideshowSettings,
                        // グリッドと同じ並び順で読む (Issue #137)
                        imageGridViewSettings
                    )
                modelClass.isAssignableFrom(AppViewModel::class.java) ->
                    AppViewModel(repository)
                modelClass.isAssignableFrom(BackupViewModel::class.java) ->
                    BackupViewModel(
                        backupRepository = backupRepository,
                        appVersionName = BuildConfig.VERSION_NAME,
                        backupStatusStore = backupStatusStore
                    )
                else -> throw IllegalArgumentException("Unknown ViewModel: $modelClass")
            } as T
        }
    }

    /**
     * Auto Backup からの復元を試みる (Issue #151)。
     *
     * 起動直後に 1 回だけ呼ぶ。DB が空のときだけ取り込むため、
     * 通常の起動では何もしない。結果はログに残す。
     */
    fun restoreFromAutoBackupIfNeeded(scope: kotlinx.coroutines.CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            val restored = runCatching { autoBackupCoordinator.restoreIfNeeded() }
                .getOrDefault(false)
            if (restored) {
                android.util.Log.i("FPV-Perf", "auto backup restored")
            }
        }
    }
}

/** アプリケーション。グローバルな依存を 1 回だけ生成する */
class FastestPictureViewerApplication : Application(), ImageLoaderFactory {

    /**
     * 依存の生成に失敗したときに保持するエラー (Issue #88)。
     *
     * DB を開けなかった場合などに、起動を諦めず案内画面を出すために使う。
     */
    @Volatile
    var startupError: Throwable? = null
        private set

    /**
     * アプリ全体で使うコルーチンスコープ (Issue #151)。
     * Auto Backup の控えの書き出し・復元に使う。画面を閉じても中断しない。
     */
    private val appScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.IO
    )

    /** 依存一式。生成に失敗した場合は null */
    var container: AppContainer? = null
        private set

    /** Coil の ImageLoader。ディスク/メモリキャッシュをアプリ全体で共有する */
    lateinit var imageLoader: ImageLoader
        private set

    override fun onCreate() {
        super.onCreate()
        StartupTrace.mark("app_oncreate_start")
        // 画像キャッシュは DB に依存しないため、先に必ず作る
        imageLoader = buildImageLoader()
        StartupTrace.mark("image_loader_ready")
        initializeContainer()
        StartupTrace.mark("app_oncreate_end")
        StartupTrace.span("app_oncreate_start", "app_oncreate_end")
        StartupTrace.span("app_oncreate_start", "image_loader_ready")
    }

    /**
     * 依存一式を作る。失敗してもプロセスを落とさず、理由を保持する。
     * 利用者が初期化を選んだときに再度呼ぶ。
     */
    fun initializeContainer() {
        container = try {
            AppContainer(this, imageLoader, appScope).also { created ->
                // Auto Backup から復元された直後なら取り込む (Issue #151)。
                // DB が空のときだけ動くため、通常の起動では何もしない
                created.restoreFromAutoBackupIfNeeded(appScope)
            }
        } catch (t: Exception) {
            startupError = t
            android.util.Log.e("FPV-Perf", "AppContainer initialization failed", t)
            null
        }
    }

    /**
     * 破損した DB を退避してから、依存を作り直す (Issue #88)。
     *
     * 自動では行わない。利用者が画面で明示的に選んだときだけ呼ぶ。
     * 写真そのものには触れない。
     *
     * @return 作り直せたか
     */
    fun resetDatabaseAndRetry(): Boolean {
        DatabaseReset.reset(this)
        startupError = null
        initializeContainer()
        return container != null
    }

    override fun newImageLoader(): ImageLoader = imageLoader

    /**
     * メモリ逼迫時はデコード済み画像のキャッシュだけを解放する。
     * ディスクキャッシュと Room は残すため、再表示時に全件再生成は発生しない。
     */
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            imageLoader.memoryCache?.clear()
        }
    }

    /**
     * サムネイル中心のため、メモリは画面表示分、ディスクはアプリキャッシュの一部に制限する。
     * 元画像の複製は保存せず、Coil が縮小デコードした結果のみキャッシュする。
     */
    private fun buildImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.15)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizePercent(0.03)
                    .build()
            }
            .crossfade(true)
            .build()
}
