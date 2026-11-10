package com.tonbiattack.fastestpictureviewer

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import com.tonbiattack.fastestpictureviewer.data.AppDatabase
import com.tonbiattack.fastestpictureviewer.data.MediaStoreScanner
import com.tonbiattack.fastestpictureviewer.repository.GalleryRepository
import com.tonbiattack.fastestpictureviewer.repository.GalleryRepositoryImpl
import com.tonbiattack.fastestpictureviewer.ui.folder.FolderListViewModel
import com.tonbiattack.fastestpictureviewer.ui.grid.ImageGridViewModel
import com.tonbiattack.fastestpictureviewer.ui.viewer.ImageViewerViewModel
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.annotation.ExperimentalCoilApi
import coil.disk.DiskCache
import coil.memory.MemoryCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 手動の簡易 DI コンテナ。ViewModel 生成もここで担う。
 * SavedStateHandle は CreationExtras から復元する (Issue #4 のプロセス再生成対応)。
 */
class AppContainer(
    context: Context,
    private val imageLoader: ImageLoader
) {

    val database: AppDatabase = AppDatabase.build(context)
    val repository: GalleryRepository =
        GalleryRepositoryImpl(db = database, scanner = MediaStoreScanner(context))

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
                    FolderListViewModel(repository, savedStateHandle)
                modelClass.isAssignableFrom(ImageGridViewModel::class.java) ->
                    ImageGridViewModel(repository, savedStateHandle)
                modelClass.isAssignableFrom(ImageViewerViewModel::class.java) ->
                    ImageViewerViewModel(repository, savedStateHandle)
                modelClass.isAssignableFrom(AppViewModel::class.java) ->
                    AppViewModel(repository)
                else -> throw IllegalArgumentException("Unknown ViewModel: $modelClass")
            } as T
        }
    }
}

/** アプリケーション。グローバルな依存を 1 回だけ生成する */
class FastestPictureViewerApplication : Application(), ImageLoaderFactory {

    lateinit var container: AppContainer
        private set

    /** Coil の ImageLoader。ディスク/メモリキャッシュをアプリ全体で共有する */
    lateinit var imageLoader: ImageLoader
        private set

    override fun onCreate() {
        super.onCreate()
        imageLoader = buildImageLoader()
        container = AppContainer(this, imageLoader)
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
