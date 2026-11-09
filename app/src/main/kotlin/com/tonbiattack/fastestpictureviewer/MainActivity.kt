package com.tonbiattack.fastestpictureviewer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.tonbiattack.fastestpictureviewer.core.permission.MediaAccessLevel
import com.tonbiattack.fastestpictureviewer.core.permission.MediaPermissions
import com.tonbiattack.fastestpictureviewer.ui.AppNavHost
import com.tonbiattack.fastestpictureviewer.ui.theme.FastestPictureViewerTheme

/**
 * 1activity / 3 画面構成 (フォルダ一覧 → グリッド → ビューア)。
 *
 * 権限の確認・要求はここで行い、許可状態の変化を AppViewModel に通知する。
 * 同期は ViewModel がバックグラウンドで実行し、UI は Room キャッシュを即表示する。
 */
class MainActivity : ComponentActivity() {

    private val appViewModel: AppViewModel by viewModels {
        (application as FastestPictureViewerApplication).container.viewModelFactory
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FastestPictureViewerTheme {
                // アクセスレベルは復帰 (ON_RESUME) のたびに再判定する
                var accessLevel by remember { mutableStateOf(MediaPermissions.accessLevel(this)) }
                val container = (application as FastestPictureViewerApplication).container

                val launcher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) {
                    // 要求結果は OS の状態で再判定する (部分アクセスの場合も含む)
                    accessLevel = MediaPermissions.accessLevel(this)
                }

                // 権限が変わったら自動同期を開始する (同期は UI とは非同期)
                LaunchedEffect(accessLevel) {
                    appViewModel.onAccessLevelChanged(accessLevel)
                }

                // 他アプリから戻った際の軽い差分同期 (スロットリング付き)
                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_START) {
                            accessLevel = MediaPermissions.accessLevel(this@MainActivity)
                            appViewModel.onAppForeground(accessLevel)
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                AppNavHost(
                    container = container,
                    accessLevel = accessLevel,
                    onRequestPermission = {
                        launcher.launch(MediaPermissions.requestPermissions())
                    },
                    onManualRefresh = {
                        appViewModel.requestManualFullSync(
                            MediaPermissions.accessLevel(this)
                        )
                    }
                )
            }
        }
    }
}
