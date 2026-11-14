package com.tonbiattack.fastestpictureviewer.ui.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tonbiattack.fastestpictureviewer.data.BackupData
import com.tonbiattack.fastestpictureviewer.data.BackupError
import com.tonbiattack.fastestpictureviewer.data.BackupJson
import com.tonbiattack.fastestpictureviewer.data.BackupRepository
import com.tonbiattack.fastestpictureviewer.data.BackupSummary
import com.tonbiattack.fastestpictureviewer.data.RestoreResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

/** バックアップ画面の状態 */
data class BackupUiState(
    /** 書き出し中 */
    val isExporting: Boolean = false,
    /** 読み込み中 */
    val isImporting: Boolean = false,
    /** 取り込み前の確認待ち */
    val pendingImport: BackupData? = null,
    /** 完了メッセージ */
    val message: String? = null,
    /** 取り込み結果 */
    val lastResult: RestoreResult? = null,
    /** エラー */
    val error: String? = null
)

/**
 * バックアップの書き出し・取り込み (Issue #74)。
 *
 * ファイル操作は Storage Access Framework の URI を使い、
 * アプリが保存先を勝手に決めない。
 */
class BackupViewModel(
    private val backupRepository: BackupRepository,
    private val appVersionName: String
) : ViewModel() {

    private val _uiState = MutableStateFlow(BackupUiState())
    val uiState: StateFlow<BackupUiState> = _uiState.asStateFlow()

    /** 書き出し用の既定ファイル名 */
    fun suggestedFileName(): String = backupRepository.defaultFileName()

    /** 現在のデータを JSON にして [output] へ書く */
    fun export(output: OutputStream) {
        viewModelScope.launch {
            _uiState.update { it.copy(isExporting = true, error = null, message = null) }
            try {
                val data = withContext(Dispatchers.IO) {
                    backupRepository.collect(appVersionName)
                }
                val json = withContext(Dispatchers.Default) { BackupJson.encode(data) }
                withContext(Dispatchers.IO) {
                    output.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                }
                val summary = data.summary
                _uiState.update {
                    it.copy(
                        isExporting = false,
                        message = "書き出しました（タグ ${summary.tags} / 紐付け ${summary.folderTags} / " +
                            "お気に入り ${summary.favoriteFolders + summary.favoriteImages} / " +
                            "除外 ${summary.excludedFolders}）"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isExporting = false, error = BackupError.IoFailure(e.message ?: "").message)
                }
            }
        }
    }

    /** 選ばれたファイルを読み、確認待ちにする（まだ書き換えない） */
    fun prepareImport(input: InputStream) {
        viewModelScope.launch {
            _uiState.update { it.copy(isImporting = true, error = null, message = null) }
            try {
                val text = withContext(Dispatchers.IO) {
                    input.use { it.readBytes().toString(Charsets.UTF_8) }
                }
                val data = withContext(Dispatchers.Default) { BackupJson.decode(text) }
                _uiState.update { it.copy(isImporting = false, pendingImport = data) }
            } catch (e: BackupError) {
                _uiState.update { it.copy(isImporting = false, error = e.message) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isImporting = false, error = BackupError.InvalidJson(e.message ?: "").message)
                }
            }
        }
    }

    /** 確認後に取り込む */
    fun confirmImport() {
        val data = _uiState.value.pendingImport ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isImporting = true, pendingImport = null, error = null) }
            try {
                val result = withContext(Dispatchers.IO) { backupRepository.restore(data) }
                _uiState.update {
                    it.copy(
                        isImporting = false,
                        lastResult = result,
                        message = buildResultMessage(result)
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isImporting = false, error = BackupError.IoFailure(e.message ?: "").message)
                }
            }
        }
    }

    /** 取り込みを中止する */
    fun cancelImport() {
        _uiState.update { it.copy(pendingImport = null) }
    }

    /** メッセージを閉じる */
    fun clearMessage() {
        _uiState.update { it.copy(message = null, error = null) }
    }

    private fun buildResultMessage(result: RestoreResult): String {
        val lines = mutableListOf<String>()
        lines += "タグ: 新規 ${result.tagsCreated} 件 / 既存 ${result.tagsReused} 件"
        if (result.folderTagsAdded > 0) lines += "フォルダのタグ: ${result.folderTagsAdded} 件を追加"
        if (result.favoriteFoldersAdded > 0) lines += "フォルダお気に入り: ${result.favoriteFoldersAdded} 件を追加"
        if (result.favoriteImagesAdded > 0) lines += "画像お気に入り: ${result.favoriteImagesAdded} 件を追加"
        if (result.excludedFoldersAdded > 0) lines += "除外フォルダ: ${result.excludedFoldersAdded} 件を追加"
        if (result.skippedFolderTags > 0) {
            lines += "タグが見つからず飛ばした項目: ${result.skippedFolderTags} 件"
        }
        if (lines.size == 1 && !result.hasChanges) {
            lines += "追加された項目はありません（すでに取り込み済みです）"
        }
        return lines.joinToString("\n")
    }

    /** 確認ダイアログ表示用の集計 */
    fun summaryOf(data: BackupData): BackupSummary = data.summary
}
