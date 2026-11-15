package com.tonbiattack.fastestpictureviewer.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.tonbiattack.fastestpictureviewer.AppContainer
import com.tonbiattack.fastestpictureviewer.core.permission.MediaAccessLevel
import com.tonbiattack.fastestpictureviewer.perf.StartupTrace
import com.tonbiattack.fastestpictureviewer.ui.backup.BackupScreen
import com.tonbiattack.fastestpictureviewer.ui.backup.BackupViewModel
import com.tonbiattack.fastestpictureviewer.ui.favorites.FavoriteImagesScreen
import com.tonbiattack.fastestpictureviewer.ui.favorites.FavoriteImagesViewModel
import com.tonbiattack.fastestpictureviewer.ui.folder.ExcludedFoldersScreen
import com.tonbiattack.fastestpictureviewer.ui.folder.ExcludedFoldersViewModel
import com.tonbiattack.fastestpictureviewer.ui.folder.FolderListScreen
import com.tonbiattack.fastestpictureviewer.ui.folder.FolderListViewModel
import com.tonbiattack.fastestpictureviewer.ui.grid.ImageGridScreen
import com.tonbiattack.fastestpictureviewer.ui.grid.ImageGridViewModel
import com.tonbiattack.fastestpictureviewer.ui.viewer.ImageViewerScreen
import com.tonbiattack.fastestpictureviewer.ui.viewer.ImageViewerViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** ビューアから一覧へ返す最後の閲覧位置 */
private const val KEY_LAST_VIEWED_ITEM = "lastViewedItemId"
private const val KEY_LAST_VIEWED_INDEX = "lastViewedIndex"

/** 「前回の続き」からグリッドを開く際の対象画像 */
private const val KEY_RESUME_ITEM = "resumeItemId"
private const val KEY_RESUME_INDEX = "resumeIndex"

/** 画面経路 */
object Routes {
    const val FOLDER_LIST = "folders"
    const val EXCLUDED_FOLDERS = "excluded-folders"
    const val FAVORITES = "favorites"
    const val BACKUP = "backup"
    const val IMAGE_GRID = "images/{folderKey}"
    const val IMAGE_VIEWER = "viewer/{folderKey}/{startIndex}?favoritesOnly={favoritesOnly}&fromEnd={fromEnd}"

    /** フォルダ識別子に "/" や ":" を含むため、全特殊文字をエンコードして渡す */
    private fun encode(folderKey: String): String = Uri.encode(folderKey, "")

    fun imageGrid(folderKey: String): String = "images/${encode(folderKey)}"
    fun imageViewer(
        folderKey: String,
        index: Int,
        favoritesOnly: Boolean = false,
        /** 前のフォルダから移動したときは末尾の画像から開く (Issue #57) */
        fromEnd: Boolean = false
    ): String =
        "viewer/${encode(folderKey)}/$index?favoritesOnly=$favoritesOnly&fromEnd=$fromEnd"
}

/**
 * アプリ内ナビゲーション。
 *
 * - 1 画面 = 1 backStackEntry。戻っても ViewModel は維持され、Room の Flow が
 *   即座に同一データを再発行するため一覧が消失しない (Issue #3)
 * - SavedStateHandle /.Nav 復元によりプロセス再生成後も同じフォルダ・画像位置を復元する (Issue #4)
 */
@Composable
fun AppNavHost(
    container: AppContainer,
    accessLevel: MediaAccessLevel,
    onRequestPermission: () -> Unit,
    onManualRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController()
) {
    val factory = container.viewModelFactory
    val scope = rememberCoroutineScope()

    NavHost(
        navController = navController,
        startDestination = Routes.FOLDER_LIST,
        modifier = modifier
    ) {
        composable(Routes.FOLDER_LIST) {
            val viewModel: FolderListViewModel = viewModel(factory = factory)
            StartupTrace.mark("folder_list_vm_created")
            val listState = viewModel.uiState.collectAsStateWithLifecycle().value
            StartupTrace.mark("folder_list_state_collected")
            // ビューアから前後フォルダへ移動できるよう、表示中の並びを共有する (Issue #57)
            LaunchedEffect(listState.visibleFolderKeys) {
                container.folderSequenceStore.update(listState.visibleFolderKeys)
            }
            FolderListScreen(
                viewModel = viewModel,
                accessLevel = accessLevel,
                onRequestPermission = onRequestPermission,
                onManualRefresh = onManualRefresh,
                onClearImageCache = {
                    scope.launch { container.clearImageCaches() }
                },
                onOpenFavorites = {
                    navController.navigate(Routes.FAVORITES)
                },
                onOpenExcludedFolders = {
                    navController.navigate(Routes.EXCLUDED_FOLDERS)
                },
                onOpenBackup = {
                    navController.navigate(Routes.BACKUP)
                },
                onExcludeFolder = { folder ->
                    scope.launch {
                        container.repository.excludeFolder(
                            volumeName = folder.volumeName,
                            relativePath = folder.relativePath
                        )
                    }
                },
                onSetFolderFavorite = { folder, favorite ->
                    scope.launch {
                        container.repository.setFolderFavorite(
                            folderKey = folder.folderKey,
                            volumeName = folder.volumeName,
                            relativePath = folder.relativePath,
                            favorite = favorite
                        )
                    }
                },
                onOpenFolder = { folder ->
                    // 最近閲覧順の履歴を更新してから開く (画像のページ移動では更新しない)
                    scope.launch {
                        container.repository.markFolderOpened(folder.folderKey)
                    }
                    navController.navigate(Routes.imageGrid(folder.folderKey))
                },
                onOpenResume = { folder, itemId, index ->
                    scope.launch {
                        container.repository.markFolderOpened(folder.folderKey)
                    }
                    navController.currentBackStackEntry?.savedStateHandle?.apply {
                        set(KEY_RESUME_ITEM, itemId)
                        set(KEY_RESUME_INDEX, index)
                    }
                    navController.navigate(Routes.imageGrid(folder.folderKey))
                }
            )
        }

        composable(Routes.EXCLUDED_FOLDERS) {
            val viewModel: ExcludedFoldersViewModel = viewModel(factory = factory)
            ExcludedFoldersScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }

        // タグ・お気に入り・設定のバックアップ (Issue #74)
        composable(Routes.BACKUP) {
            val viewModel: BackupViewModel = viewModel(factory = factory)
            BackupScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.FAVORITES) {
            val viewModel: FavoriteImagesViewModel = viewModel(factory = factory)
            FavoriteImagesScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onOpenFavoriteImage = { folderKey, index ->
                    navController.navigate(
                        Routes.imageViewer(
                            folderKey = folderKey ?: ImageViewerViewModel.ALL_FAVORITES,
                            index = index,
                            favoritesOnly = true
                        )
                    )
                }
            )
        }

        composable(
            route = Routes.IMAGE_GRID,
            arguments = listOf(
                navArgument("folderKey") { type = NavType.StringType }
            )
        ) {
            val viewModel: ImageGridViewModel = viewModel(factory = factory)
            val previousEntry = navController.previousBackStackEntry
            LaunchedEffect(previousEntry) {
                val resumeItemId = previousEntry?.savedStateHandle
                    ?.get<String>(KEY_RESUME_ITEM) ?: return@LaunchedEffect
                val resumeIndex = previousEntry.savedStateHandle
                    .get<Int>(KEY_RESUME_INDEX) ?: 0
                viewModel.onReturnedFromViewer(resumeItemId, resumeIndex)
                previousEntry.savedStateHandle.remove<String>(KEY_RESUME_ITEM)
                previousEntry.savedStateHandle.remove<Int>(KEY_RESUME_INDEX)
            }
            val entry = navController.currentBackStackEntry
            val lastViewedFlow = remember(entry) {
                entry?.savedStateHandle
                    ?.getStateFlow<String?>(KEY_LAST_VIEWED_ITEM, null)
                    ?: MutableStateFlow<String?>(null)
            }
            val lastViewedItemId by lastViewedFlow.collectAsStateWithLifecycle()
            LaunchedEffect(lastViewedItemId) {
                val itemId = lastViewedItemId ?: return@LaunchedEffect
                val index = entry?.savedStateHandle?.get<Int>(KEY_LAST_VIEWED_INDEX) ?: 0
                viewModel.onReturnedFromViewer(itemId, index)
                entry?.savedStateHandle?.remove<String>(KEY_LAST_VIEWED_ITEM)
                entry?.savedStateHandle?.remove<Int>(KEY_LAST_VIEWED_INDEX)
            }
            ImageGridScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onOpenViewer = { folderKey, index ->
                    navController.navigate(Routes.imageViewer(folderKey, index))
                }
            )
        }

        composable(
            route = Routes.IMAGE_VIEWER,
            arguments = listOf(
                navArgument("folderKey") { type = NavType.StringType },
                navArgument("startIndex") { type = NavType.IntType },
                navArgument("favoritesOnly") {
                    type = NavType.BoolType
                    defaultValue = false
                },
                navArgument("fromEnd") {
                    type = NavType.BoolType
                    defaultValue = false
                }
            )
        ) {
            val viewModel: ImageViewerViewModel = viewModel(factory = factory)
            ImageViewerScreen(
                viewModel = viewModel,
                onOpenAdjacentFolder = { folderKey, fromEnd ->
                    // 前後フォルダへの移動は現在のフォルダとビューアを置き換える。
                    // 戻ると移動先フォルダの画像一覧へ戻る (Issue #57)
                    navController.navigate(Routes.imageGrid(folderKey)) {
                        popUpTo(Routes.FOLDER_LIST)
                    }
                    navController.navigate(
                        Routes.imageViewer(folderKey, index = 0, fromEnd = fromEnd)
                    )
                },
                onBack = { itemId, index ->
                    val viewerEntry = navController.currentBackStackEntry
                    val folderKey = viewerEntry?.arguments?.getString("folderKey")
                    val favoritesOnly = viewerEntry?.arguments
                        ?.getBoolean("favoritesOnly", false) ?: false
                    if (!favoritesOnly && folderKey != null && itemId != null) {
                        // 起動時の「前回の続き」用に最後のフォルダ・画像を1件保存する
                        container.lastViewedStore.save(folderKey, itemId, index)
                    }
                    // 呼び出し元 (通常は画像グリッド) に最後の閲覧位置を返す
                    navController.previousBackStackEntry?.savedStateHandle?.apply {
                        if (itemId != null) {
                            set(KEY_LAST_VIEWED_ITEM, itemId)
                        } else {
                            remove<String>(KEY_LAST_VIEWED_ITEM)
                        }
                        set(KEY_LAST_VIEWED_INDEX, index)
                    }
                    navController.popBackStack()
                }
            )
        }
    }
}
