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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tonbiattack.fastestpictureviewer.core.permission.MediaAccessLevel
import com.tonbiattack.fastestpictureviewer.core.permission.MediaPermissions
import com.tonbiattack.fastestpictureviewer.data.DatabaseError
import com.tonbiattack.fastestpictureviewer.perf.StartupTrace
import com.tonbiattack.fastestpictureviewer.ui.AppNavHost
import com.tonbiattack.fastestpictureviewer.ui.theme.FastestPictureViewerTheme

/**
 * 1activity / 3 画面構成 (フォルダ一覧 → グリッド → ビューア)。
 *
 * 権限の確認・要求はここで行い、許可状態の変化を AppViewModel に通知する。
 * 同期は ViewModel がバックグラウンドで実行し、UI は Room キャッシュを即表示する。
 */
class MainActivity : ComponentActivity() {

    /**
     * 依存生成に失敗した場合は ViewModel を作れないため、遅延生成にする (Issue #88)。
     * ここで失敗しても画面側で案内を出す。
     */
    private val appViewModel: AppViewModel by viewModels {
        val app = application as FastestPictureViewerApplication
        val container = app.container
            ?: throw IllegalStateException("container is not available", app.startupError)
        container.viewModelFactory
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        StartupTrace.mark("activity_oncreate_start")
        val app = application as FastestPictureViewerApplication

        // 依存の生成に失敗していたら、復旧の案内だけを出す (Issue #88)
        val failure = app.startupError
        if (failure != null) {
            setContent {
                FastestPictureViewerTheme {
                    StartupFailureScreen(
                        error = failure,
                        onReset = {
                            // 利用者が明示的に選んだときだけ DB を退避して作り直す
                            if (app.resetDatabaseAndRetry()) {
                                recreate()
                            }
                        },
                        onRetry = { recreate() }
                    )
                }
            }
            return
        }

        StartupTrace.mark("before_set_content")
        setContent {
            StartupTrace.mark("set_content")
            FastestPictureViewerTheme {
                // アクセスレベルは復帰 (ON_RESUME) のたびに再判定する
                var accessLevel by remember { mutableStateOf(MediaPermissions.accessLevel(this)) }
                // 依存生成に成功していることは上で確認済み
                val container = app.container!!
                // 計測: setContent から最初のコンポーズ本体まで (Issue #99)
                StartupTrace.mark("compose_first_pass")
                StartupTrace.span("set_content", "compose_first_pass")

                val launcher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) {
                    // 要求結果は OS の状態で再判定する (部分アクセスの場合も含む)
                    accessLevel = MediaPermissions.accessLevel(this)
                }

                // 権限が変わったら自動同期を開始する (同期は UI とは非同期)
                LaunchedEffect(accessLevel) {
                    StartupTrace.mark("sync_effect_start")
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

/**
 * 依存の生成に失敗したときの案内 (Issue #88)。
 *
 * 破損だと確認できた場合だけ、初期化の選択肢を出す。
 * 容量不足や権限の問題で初期化を勧めると、直せば戻るデータまで失うため。
 */
@Composable
private fun StartupFailureScreen(
    error: Throwable,
    onReset: () -> Unit,
    onRetry: () -> Unit
) {
    val canReset = DatabaseError.canOfferReset(error)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "画像の情報を読み込めませんでした",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = if (canReset) {
                "画像の情報を保存しているファイルが壊れている可能性があります。"
            } else {
                "端末の空き容量や権限を確認して、もう一度お試しください。"
            },
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(16.dp))
        if (canReset) {
            Text(
                text = DatabaseError.RESET_DESCRIPTION,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onReset) {
                Text("画像の情報を作り直す")
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "壊れたファイルは端末内に退避され、削除はされません。",
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            Button(onClick = onRetry) {
                Text("再試行")
            }
        }
    }
}
