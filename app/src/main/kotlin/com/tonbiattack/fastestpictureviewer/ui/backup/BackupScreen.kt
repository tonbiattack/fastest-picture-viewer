package com.tonbiattack.fastestpictureviewer.ui.backup

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tonbiattack.fastestpictureviewer.data.BackupData

/**
 * 復元前の確認ダイアログ (Issue #111)。
 *
 * 取り込み前に「何が追加され、何が上書きされるか」を利用者が確認できるようにする。
 * 表示設定はバックアップの値で上書きされるため、上書き後の実値を出す。
 * 画面から切り離してあるのは、表示内容を Compose テストで固定するため。
 */
@Composable
internal fun RestoreConfirmDialog(
    data: BackupData,
    exportedAtLabel: String,
    settingsLabel: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    val summary = data.summary
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("バックアップを取り込みますか？") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "書き出し日時（記録値）: $exportedAtLabel",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "お気に入り・タグ・除外フォルダは、いまのデータを消さずに追加します。",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "表示設定は、いまの設定をバックアップの値で上書きします。",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    settingsLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "画像そのもの・閲覧履歴・サムネイルは対象外です（復元されません）。",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                Text("タグ: ${summary.tags} 件", style = MaterialTheme.typography.bodySmall)
                Text("フォルダのタグ: ${summary.folderTags} 件", style = MaterialTheme.typography.bodySmall)
                Text("フォルダお気に入り: ${summary.favoriteFolders} 件", style = MaterialTheme.typography.bodySmall)
                Text("画像お気に入り: ${summary.favoriteImages} 件", style = MaterialTheme.typography.bodySmall)
                Text("除外フォルダ: ${summary.excludedFolders} 件", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("取り込む") }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("キャンセル") }
        }
    )
}

/**
 * バックアップの書き出し・取り込み画面 (Issue #74)。
 *
 * 保存先・読み込み元は Storage Access Framework でユーザーが選ぶ。
 * 取り込みは内容を確認してから実行し、既存データは消さずにマージする。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    viewModel: BackupViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state = viewModel.uiState.collectAsStateWithLifecycle().value
    val context = LocalContext.current
    val resolver = context.contentResolver

    // 書き出し先を選ぶ
    val createLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { resolver.openOutputStream(uri) }
            .getOrNull()
            ?.let { viewModel.export(it) }
    }

    // 読み込み元を選ぶ
    val openLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        // ファイル選択のキャンセル。何も起きないのが正しい
        if (uri == null) return@rememberLauncherForActivityResult
        // 開けなかった理由を黙って捨てると、利用者は何が起きたか分からない
        val stream = runCatching { resolver.openInputStream(uri) }.getOrNull()
        if (stream == null) {
            viewModel.onOpenFailed()
        } else {
            viewModel.prepareImport(stream)
        }
    }

    if (state.pendingImport != null) {
        val data = state.pendingImport
        RestoreConfirmDialog(
            data = data,
            exportedAtLabel = viewModel.exportedAtLabel(data),
            settingsLabel = viewModel.settingsLabel(data.settings),
            onConfirm = viewModel::confirmImport,
            onCancel = viewModel::cancelImport
        )
    }

    state.error?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::clearMessage,
            title = { Text(state.errorTitle) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = viewModel::clearMessage) { Text("閉じる") }
            }
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("バックアップ") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                Text(
                    "タグ・お気に入り・除外フォルダ・表示設定をファイルに保存します。",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "端末の故障や買い替えに備えて、ときどき書き出しておいてください。" +
                        "画像そのものは含まれません（再生成できます）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(20.dp))
                HorizontalDivider()
                Spacer(Modifier.height(16.dp))

                Text("書き出す", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "保存先を選んで JSON ファイルを書き出します。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { createLauncher.launch(viewModel.suggestedFileName()) },
                    enabled = !state.isExporting && !state.isImporting && state.pendingImport == null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (state.isExporting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.height(0.dp))
                    } else {
                        Text("バックアップを書き出す")
                    }
                }

                Spacer(Modifier.height(20.dp))
                HorizontalDivider()
                Spacer(Modifier.height(16.dp))

                Text("復元する", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "書き出した JSON を選び、内容を確認してから取り込みます。" +
                        "既存のタグやお気に入りは消えません。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { openLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                    enabled = !state.isExporting && !state.isImporting && state.pendingImport == null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (state.isImporting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("バックアップから復元")
                    }
                }

                state.message?.let { message ->
                    Spacer(Modifier.height(20.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Spacer(Modifier.height(4.dp))
                            TextButton(
                                onClick = viewModel::clearMessage,
                                modifier = Modifier.align(Alignment.End)
                            ) { Text("閉じる") }
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
                Text(
                    "注意: 復元しても、フォルダの場所や名前が変わっている場合は紐付きません。" +
                        "画像のお気に入りは端末ごとに URI が変わるため、引き継げないことがあります。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Start
                )
            }
        }
    }
}
