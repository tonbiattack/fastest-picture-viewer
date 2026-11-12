package com.tonbiattack.fastestpictureviewer.ui.folder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tonbiattack.fastestpictureviewer.core.util.TagNames
import com.tonbiattack.fastestpictureviewer.data.TagWithCount

/**
 * フォルダのタグ編集ダイアログ (Issue #46)。
 *
 * 既存タグを検索して選ぶ方式を基本とし、未登録の名前を入力した場合は
 * その場で「新規作成して付与」できる。選択操作は即座に保存するため、
 * 画面を離れずに追加・解除を繰り返せる。
 */
@Composable
fun FolderTagEditorDialog(
    folderName: String,
    selectedTagIds: Set<Long>,
    allTags: List<TagWithCount>,
    onToggle: (tagId: Long, add: Boolean) -> Unit,
    onCreateAndAdd: (name: String) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    // 検索語で既存タグを絞り込む。入力が空なら全件
    val filtered = allTags.filter { TagNames.matches(query, it.displayName) }
    val exactExists = allTags.any { TagNames.key(it.displayName) == TagNames.key(query) }
    val canCreate = query.isNotBlank() && !exactExists

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("タグ編集") },
        text = {
            Column {
                Text(
                    folderName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("タグ名で検索・入力") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Filled.Close, contentDescription = "入力をクリア")
                            }
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {})
                )
                if (canCreate) {
                    TextButton(
                        onClick = {
                            onCreateAndAdd(query)
                            query = ""
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Text("「${TagNames.display(query)}」を新規作成して付与")
                    }
                }
                Spacer(Modifier.height(4.dp))
                if (filtered.isEmpty() && !canCreate) {
                    Text(
                        "一致するタグがありません",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 260.dp)) {
                        items(filtered, key = { it.tagId }) { tag ->
                            val selected = tag.tagId in selectedTagIds
                            TagSelectRow(
                                name = tag.displayName,
                                folderCount = tag.folderCount,
                                selected = selected,
                                onClick = { onToggle(tag.tagId, !selected) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("閉じる") }
        }
    )
}

/** 検索結果から複数フォルダへタグを一括付与するダイアログ */
@Composable
fun BulkTagAssignDialog(
    targetCount: Int,
    allTags: List<TagWithCount>,
    onApply: (tagIds: Set<Long>, newTagName: String?) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var selectedIds by remember { mutableStateOf(emptySet<Long>()) }
    val filtered = allTags.filter { TagNames.matches(query, it.displayName) }
    val exactExists = allTags.any { TagNames.key(it.displayName) == TagNames.key(query) }
    val canCreate = query.isNotBlank() && !exactExists

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("タグを一括付与") },
        text = {
            Column {
                Text(
                    "対象: ${targetCount} フォルダ。既存のタグは消さずに追加します。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("タグ名で検索・入力") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true
                )
                if (canCreate) {
                    TextButton(
                        onClick = {
                            onApply(selectedIds, query)
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Text("「${TagNames.display(query)}」を新規作成して付与")
                    }
                }
                LazyColumn(modifier = Modifier.heightIn(max = 260.dp)) {
                    items(filtered, key = { it.tagId }) { tag ->
                        val selected = tag.tagId in selectedIds
                        TagSelectRow(
                            name = tag.displayName,
                            folderCount = tag.folderCount,
                            selected = selected,
                            onClick = {
                                selectedIds = if (selected) {
                                    selectedIds - tag.tagId
                                } else {
                                    selectedIds + tag.tagId
                                }
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selectedIds.isNotEmpty(),
                onClick = {
                    onApply(selectedIds, null)
                    onDismiss()
                }
            ) { Text("${targetCount} 件に付与") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        }
    )
}

/** タグの絞り込みダイアログ。複数タグ指定時の AND/OR を明示する */
@Composable
fun TagFilterDialog(
    allTags: List<TagWithCount>,
    filter: TagFilterState,
    onToggle: (Long) -> Unit,
    onModeChange: (TagFilterMode) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filtered = allTags.filter { TagNames.matches(query, it.displayName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("タグで絞り込み") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("タグ名で検索") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = filter.mode == TagFilterMode.OR,
                        onClick = { onModeChange(TagFilterMode.OR) },
                        label = { Text("いずれか (OR)") }
                    )
                    FilterChip(
                        selected = filter.mode == TagFilterMode.AND,
                        onClick = { onModeChange(TagFilterMode.AND) },
                        label = { Text("すべて (AND)") }
                    )
                }
                Spacer(Modifier.height(8.dp))
                if (allTags.isEmpty()) {
                    Text(
                        "タグはまだありません。フォルダの長押しメニューからタグを付与できます。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 260.dp)) {
                        items(filtered, key = { it.tagId }) { tag ->
                            TagSelectRow(
                                name = tag.displayName,
                                folderCount = tag.folderCount,
                                selected = tag.tagId in filter.tagIds,
                                onClick = { onToggle(tag.tagId) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("閉じる") }
        },
        dismissButton = {
            TextButton(onClick = onClear) { Text("絞り込み解除") }
        }
    )
}

/** タグの名称変更・削除を行う管理ダイアログ */
@Composable
fun TagManagementDialog(
    allTags: List<TagWithCount>,
    onRename: (tagId: Long, name: String) -> Unit,
    onDelete: (tagId: Long) -> Unit,
    onDismiss: () -> Unit
) {
    var renaming by remember { mutableStateOf<TagWithCount?>(null) }
    var deleting by remember { mutableStateOf<TagWithCount?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("タグの管理") },
        text = {
            if (allTags.isEmpty()) {
                Text(
                    "タグはまだありません",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(allTags, key = { it.tagId }) { tag ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    tag.displayName,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    "${tag.folderCount} フォルダ",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { renaming = tag }) {
                                Icon(Icons.Filled.Edit, contentDescription = "タグ名を変更")
                            }
                            IconButton(onClick = { deleting = tag }) {
                                Icon(Icons.Filled.Delete, contentDescription = "タグを削除")
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("閉じる") }
        }
    )

    renaming?.let { tag ->
        TagNameInputDialog(
            title = "タグ名を変更",
            initial = tag.displayName,
            confirmLabel = "変更",
            onConfirm = { name ->
                onRename(tag.tagId, name)
                renaming = null
            },
            onDismiss = { renaming = null }
        )
    }

    deleting?.let { tag ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("タグを削除") },
            text = {
                Text(
                    "「${tag.displayName}」を削除します。${tag.folderCount} フォルダの紐付けも外れますが、画像は削除されません。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(tag.tagId)
                        deleting = null
                    }
                ) { Text("削除") }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("キャンセル") }
            }
        )
    }
}

/** タグ名を 1 行入力するダイアログ */
@Composable
private fun TagNameInputDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = { onConfirm(text) }
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        }
    )
}

/** タグ選択行 (チェックマーク付き) */
@Composable
private fun TagSelectRow(
    name: String,
    folderCount: Long,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilterChip(
            selected = selected,
            onClick = onClick,
            label = {
                Text(
                    text = "$name (${folderCount})",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            leadingIcon = if (selected) {
                { Icon(Icons.Filled.Check, contentDescription = null) }
            } else {
                null
            }
        )
    }
}
