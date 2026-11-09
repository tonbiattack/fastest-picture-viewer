package com.tonbiattack.fastestpictureviewer

import android.app.Application
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

/**
 * 手動の簡易 DI コンテナ。ViewModel 生成もここで担う。
 * SavedStateHandle は CreationExtras から復元する (Issue #4 のプロセス再生成対応)。
 */
class AppContainer(context: Context) {

    val database: AppDatabase = AppDatabase.build(context)
    val repository: GalleryRepository =
        GalleryRepositoryImpl(db = database, scanner = MediaStoreScanner(context))

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
class FastestPictureViewerApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
