package com.tonbiattack.fastestpictureviewer.ui.folder

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tonbiattack.fastestpictureviewer.data.ExcludedFolderEntity
import com.tonbiattack.fastestpictureviewer.repository.GalleryRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 除外フォルダ一覧画面の ViewModel */
class ExcludedFoldersViewModel(
    private val repository: GalleryRepository
) : ViewModel() {

    val folders: StateFlow<List<ExcludedFolderEntity>> =
        repository.observeExcludedFolders().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    /** 除外を解除する。Room の Flow 経由で一覧へ即座に反映される */
    fun include(folderKey: String) {
        viewModelScope.launch {
            repository.includeFolder(folderKey)
        }
    }
}
