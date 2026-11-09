package com.tonbiattack.fastestpictureviewer.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.tonbiattack.fastestpictureviewer.AppContainer
import com.tonbiattack.fastestpictureviewer.core.permission.MediaAccessLevel
import com.tonbiattack.fastestpictureviewer.ui.folder.FolderListScreen
import com.tonbiattack.fastestpictureviewer.ui.folder.FolderListViewModel
import com.tonbiattack.fastestpictureviewer.ui.grid.ImageGridScreen
import com.tonbiattack.fastestpictureviewer.ui.grid.ImageGridViewModel
import com.tonbiattack.fastestpictureviewer.ui.viewer.ImageViewerScreen
import com.tonbiattack.fastestpictureviewer.ui.viewer.ImageViewerViewModel

/** 画面経路 */
object Routes {
    const val FOLDER_LIST = "folders"
    const val IMAGE_GRID = "images/{folderKey}"
    const val IMAGE_VIEWER = "viewer/{folderKey}/{startIndex}"

    /** フォルダ識別子に "/" や ":" を含むため、全特殊文字をエンコードして渡す */
    private fun encode(folderKey: String): String = Uri.encode(folderKey, "")

    fun imageGrid(folderKey: String): String = "images/${encode(folderKey)}"
    fun imageViewer(folderKey: String, index: Int): String =
        "viewer/${encode(folderKey)}/$index"
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
    navController: NavHostController = rememberNavController(),
    modifier: Modifier = Modifier
) {
    val factory = container.viewModelFactory

    NavHost(
        navController = navController,
        startDestination = Routes.FOLDER_LIST,
        modifier = modifier
    ) {
        composable(Routes.FOLDER_LIST) {
            val viewModel: FolderListViewModel = viewModel(factory = factory)
            FolderListScreen(
                viewModel = viewModel,
                accessLevel = accessLevel,
                onRequestPermission = onRequestPermission,
                onManualRefresh = onManualRefresh,
                onOpenFolder = { folder ->
                    navController.navigate(Routes.imageGrid(folder.folderKey))
                }
            )
        }

        composable(Routes.IMAGE_GRID) {
            val viewModel: ImageGridViewModel = viewModel(factory = factory)
            ImageGridScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onOpenViewer = { folderKey, index ->
                    navController.navigate(Routes.imageViewer(folderKey, index))
                }
            )
        }

        composable(Routes.IMAGE_VIEWER) {
            val viewModel: ImageViewerViewModel = viewModel(factory = factory)
            ImageViewerScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
