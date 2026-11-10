package com.tonbiattack.fastestpictureviewer.ui.favorites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tonbiattack.fastestpictureviewer.core.util.FolderNames
import com.tonbiattack.fastestpictureviewer.data.ImageItem
import com.tonbiattack.fastestpictureviewer.repository.GalleryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** お気に入り画像の表示モード */
enum class FavoriteViewMode {
    /** フォルダ別 */
    FOLDERS,

    /** 全件まとめて */
    ALL
}

/** フォルダ別表示の 1 グループ */
data class FavoriteFolderGroup(
    val folderKey: String,
    val folderName: String,
    val images: List<ImageItem>
) {
    val imageCount: Int get() = images.size
    val representativeImage: ImageItem? get() = images.firstOrNull()
}

/** お気に入り画像をフォルダ単位にまとめる純粋ロジック (入力順を維持する) */
object FavoriteGrouping {
    fun group(images: List<ImageItem>): List<FavoriteFolderGroup> =
        images
            .groupBy { it.folderKey }
            .map { (folderKey, folderImages) ->
                FavoriteFolderGroup(
                    folderKey = folderKey,
                    folderName = FolderNames.parse(folderImages.first().relativePath),
                    images = folderImages
                )
            }
}

/** お気に入り画像画面の状態 */
data class FavoriteImagesUiState(
    val isLoading: Boolean = true,
    val mode: FavoriteViewMode = FavoriteViewMode.FOLDERS,
    val groups: List<FavoriteFolderGroup> = emptyList(),
    val selectedFolderKey: String? = null,
    val selectedFolderName: String? = null,
    val selectedImages: List<ImageItem> = emptyList(),
    val allImages: List<ImageItem> = emptyList(),
    val allIndexById: Map<String, Int> = emptyMap()
)

/**
 * お気に入り画像の一覧 ViewModel。
 *
 * 全件表示は「フォルダの最終更新日時降順 → フォルダキー昇順 → フォルダ内の自然順」で
 * 取得した一覧をそのまま使い、フォルダ単位のまとまりを維持する。
 */
class FavoriteImagesViewModel(
    private val repository: GalleryRepository
) : ViewModel() {

    private val mode = MutableStateFlow(FavoriteViewMode.FOLDERS)
    private val selectedFolderKey = MutableStateFlow<String?>(null)
    private val favoriteMutex = Mutex()

    val uiState: StateFlow<FavoriteImagesUiState> = combine(
        repository.observeFavoriteImages(null),
        mode,
        selectedFolderKey
    ) { images: List<ImageItem>, currentMode: FavoriteViewMode, selected: String? ->
        val groups = FavoriteGrouping.group(images)
        val selectedGroup = selected?.let { key -> groups.firstOrNull { it.folderKey == key } }
        FavoriteImagesUiState(
            isLoading = false,
            mode = currentMode,
            groups = groups,
            selectedFolderKey = selected,
            selectedFolderName = selectedGroup?.folderName
                ?: selected?.let { FolderNames.fromFolderKey(it) },
            selectedImages = selectedGroup?.images ?: emptyList(),
            allImages = images,
            allIndexById = images.mapIndexed { index, item -> item.id to index }.toMap()
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = FavoriteImagesUiState()
    )

    /** 表示モードを切り替える。フォルダ選択中なら解除する */
    fun setMode(newMode: FavoriteViewMode) {
        mode.value = newMode
        selectedFolderKey.value = null
    }

    /** フォルダ別表示からフォルダを選ぶ */
    fun selectFolder(folderKey: String) {
        selectedFolderKey.value = folderKey
    }

    /** 選択中のフォルダを閉じる */
    fun clearSelection() {
        selectedFolderKey.value = null
    }

    /** お気に入りを解除する。一覧は Room の Flow 経由で更新される */
    fun removeFavorite(itemId: String) {
        viewModelScope.launch {
            favoriteMutex.withLock {
                runCatching { repository.setFavorite(itemId, false) }
            }
        }
    }
}
