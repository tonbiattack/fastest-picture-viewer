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
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { resolver.openInputStream(uri) }
            .getOrNull()
            ?.let { viewModel.prepareImport(it) }
    }

    if (state.pendingImport != null) {
        val summary = viewModel.summaryOf(state.pendingImport)
        AlertDialog(
            onDismissRequest = viewModel::cancelImport,
            title = { Text("バックアップを取り込みますか？") },
            text = {
                Column {
                    Text(
                        "既存のデータは消さずに、足りない項目だけを追加します。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("タグ: ${summary.tags} 件", style = MaterialTheme.typography.bodySmall)
                    Text("フォルダのタグ: ${summary.folderTags} 件", style = MaterialTheme.typography.bodySmall)
                    Text("フォルダお気に入り: ${summary.favoriteFolders} 件", style = MaterialTheme.typography.bodySmall)
                    Text("画像お気に入り: ${summary.favoriteImages} 件", style = MaterialTheme.typography.bodySmall)
                    Text("除外フォルダ: ${summary.excludedFolders} 件", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmImport) { Text("取り込む") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelImport) { Text("キャンセル") }
            }
        )
    }

    state.error?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::clearMessage,
            title = { Text("読み込めませんでした") },
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
