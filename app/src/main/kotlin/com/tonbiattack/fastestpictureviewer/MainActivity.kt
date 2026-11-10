package com.tonbiattack.fastestpictureviewer

import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
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
import androidx.lifecycle.compose.LocalLifecycleOwner
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
                // 表示中は MediaStore の変更通知も監視し、追加/削除/改名を背景で反映する
                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    var mediaObserver: ContentObserver? = null

                    fun registerMediaObserver() {
                        mediaObserver?.let { contentResolver.unregisterContentObserver(it) }
                        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                            override fun onChange(selfChange: Boolean, uri: android.net.Uri?) {
                                appViewModel.onMediaStoreChanged(
                                    MediaPermissions.accessLevel(this@MainActivity)
                                )
                            }
                        }
                        MediaStore.getExternalVolumeNames(this@MainActivity).forEach { volume ->
                            contentResolver.registerContentObserver(
                                MediaStore.Images.Media.getContentUri(volume),
                                true,
                                observer
                            )
                        }
                        mediaObserver = observer
                    }

                    val observer = LifecycleEventObserver { _, event ->
                        when (event) {
                            Lifecycle.Event.ON_START -> {
                                accessLevel = MediaPermissions.accessLevel(this@MainActivity)
                                appViewModel.onAppForeground(accessLevel)
                                registerMediaObserver()
                            }
                            Lifecycle.Event.ON_STOP -> {
                                mediaObserver?.let { contentResolver.unregisterContentObserver(it) }
                                mediaObserver = null
                            }
                            else -> Unit
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose {
                        lifecycleOwner.lifecycle.removeObserver(observer)
                        mediaObserver?.let { contentResolver.unregisterContentObserver(it) }
                    }
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
