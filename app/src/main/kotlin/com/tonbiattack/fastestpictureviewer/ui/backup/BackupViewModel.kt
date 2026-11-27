package com.tonbiattack.fastestpictureviewer.ui.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tonbiattack.fastestpictureviewer.core.util.FolderSortMode
import com.tonbiattack.fastestpictureviewer.data.BackupData
import com.tonbiattack.fastestpictureviewer.data.BackupError
import com.tonbiattack.fastestpictureviewer.data.BackupJson
import com.tonbiattack.fastestpictureviewer.data.BackupRepository
import com.tonbiattack.fastestpictureviewer.data.BackupSettings
import com.tonbiattack.fastestpictureviewer.data.BackupStatusStore
import com.tonbiattack.fastestpictureviewer.data.BackupSummary
import com.tonbiattack.fastestpictureviewer.data.RestoreResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

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
    val error: String? = null,
    /**
     * エラーダイアログの見出し。
     *
     * 読み込みの失敗と、取り込み途中の失敗では意味が違うため分ける。
     * 取り込み途中の失敗を「読み込めませんでした」と出すと、
     * 「何も反映されていない」と誤解させる。
     */
    val errorTitle: String = BackupViewModel.DEFAULT_ERROR_TITLE
)

/**
 * バックアップの書き出し・取り込み (Issue #74)。
 *
 * ファイル操作は Storage Access Framework の URI を使い、
 * アプリが保存先を勝手に決めない。
 *
 * 取り込みは「読む → 確認 → 反映」の 3 段階に分ける。
 * 読み込みに失敗した時点では DB も設定も変えない（確認前に書き換えない）。
 */
class BackupViewModel(
    private val backupRepository: BackupRepository,
    private val appVersionName: String,
    /** 取り込むファイルの上限。既定は [MAX_IMPORT_BYTES]（テストで小さくできる） */
    private val maxImportBytes: Long = MAX_IMPORT_BYTES,
    /** ファイル入出力のディスパッチャ（テスト差し替え用） */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** JSON 変換のディスパッチャ（テスト差し替え用） */
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /**
     * 書き出し状況の記録先 (Issue #108)。null なら記録しない（テストの後方互換）。
     *
     * 書き出しに成功した時点で「最終書き出し日時」を更新し、変更フラグを下ろす。
     */
    private val backupStatusStore: BackupStatusStore? = null,
    /** 現在時刻 (テスト差し替え用) */
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 }
) : ViewModel() {

    private val _uiState = MutableStateFlow(BackupUiState())
    val uiState: StateFlow<BackupUiState> = _uiState.asStateFlow()

    /** 書き出し用の既定ファイル名 */
    fun suggestedFileName(): String = backupRepository.defaultFileName()

    /**
     * 自動バックアップ（OS 経由）の控えの状態 (Issue #151)。
     *
     * 実クラウドの実行時刻は OS が決めるため、アプリが保証できるのは
     * 「控えがいつ作られたか」まで。null なら表示しない。
     */
    fun autoBackupLabel(autoBackupStore: com.tonbiattack.fastestpictureviewer.data.AutoBackupStore?): String? {
        val store = autoBackupStore ?: return null
        if (!store.exists()) return "自動バックアップの控え: まだ作成されていません"
        val size = store.sizeBytes()
        val savedAt = runCatching {
            val formatter = java.text.SimpleDateFormat(
                LAST_EXPORTED_PATTERN,
                java.util.Locale.getDefault()
            )
            formatter.format(java.util.Date(store.file.lastModified()))
        }.getOrNull()
        return "自動バックアップの控え: ${size / 1024} KB（${savedAt ?: "不明"} 更新）"
    }

    /**
     * 最終書き出し日時の表示 (Issue #108)。
     *
     * 一度も書き出していない場合は「まだ書き出していません」と明示する。
     * 書き出し状況を記録していない場合 (null) は null を返し、画面は何も出さない。
     */
    fun lastExportedLabel(): String? {
        val status = backupStatusStore?.status?.value ?: return null
        if (!status.hasExported) return NEVER_EXPORTED_LABEL
        return runCatching {
            val formatter = java.text.SimpleDateFormat(
                LAST_EXPORTED_PATTERN,
                java.util.Locale.getDefault()
            )
            formatter.format(java.util.Date(status.lastExportedAtSec * 1000))
        }.getOrNull()?.let { "最終書き出し: $it" }
    }

    /** 現在のデータを JSON にして [output] へ書く */
    fun export(output: OutputStream) {
        viewModelScope.launch {
            _uiState.update { it.copy(isExporting = true, error = null, message = null) }
            try {
                val data = withContext(ioDispatcher) {
                    backupRepository.collect(appVersionName)
                }
                val json = withContext(computeDispatcher) { BackupJson.encode(data) }
                withContext(ioDispatcher) {
                    output.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                }
                val summary = data.summary
                // 書き出しに成功した時点で記録する (Issue #108)。
                // 失敗時は更新しないため、リマインドは出続ける
                backupStatusStore?.markExported(nowSeconds())
                _uiState.update {
                    it.copy(
                        isExporting = false,
                        message = "書き出しました（タグ ${summary.tags} / 紐付け ${summary.folderTags} / " +
                            "お気に入り ${summary.favoriteFolders + summary.favoriteImages} / " +
                            "除外 ${summary.excludedFolders}）"
                    )
                }
            } catch (e: CancellationException) {
                // キャンセルは握りつぶさずに伝播させる
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isExporting = false, error = BackupError.IoFailure(e.message ?: "").message)
                }
            }
        }
    }

    /**
     * 選ばれたファイルを読み、確認待ちにする（まだ書き換えない）。
     *
     * - 上限 [maxImportBytes] を超えるファイルは読み切らずに拒否する
     * - 壊れた JSON・読み取り失敗でも DB と設定には触れない
     * - [input] は成功・失敗・キャンセルのいずれでも必ず閉じる
     */
    fun prepareImport(input: InputStream) {
        // launch の前に同期的に更新し、連打で二重に走らないようにする
        _uiState.update {
            it.copy(isImporting = true, error = null, errorTitle = DEFAULT_ERROR_TITLE, message = null)
        }
        val job = viewModelScope.launch {
            try {
                val text = withContext(ioDispatcher) { readTextLimited(input, maxImportBytes) }
                val data = withContext(computeDispatcher) { BackupJson.decode(text) }
                _uiState.update { it.copy(isImporting = false, pendingImport = data) }
            } catch (e: CancellationException) {
                // キャンセルは握りつぶさずに伝播させる
                throw e
            } catch (e: ImportTooLarge) {
                _uiState.update { it.copy(isImporting = false, error = tooLargeMessage(e.limitBytes)) }
            } catch (e: BackupError) {
                _uiState.update { it.copy(isImporting = false, error = e.message) }
            } catch (e: IOException) {
                // 例外の詳細には URI やパスが入りうるため、画面には出さない
                _uiState.update { it.copy(isImporting = false, error = IMPORT_IO_FAILURE_MESSAGE) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isImporting = false, error = IMPORT_INVALID_MESSAGE) }
            } finally {
                runCatching { input.close() }
            }
        }
        // コルーチンが開始前にキャンセルされると上の finally は走らない。
        // ジョブの完了（キャンセル含む）で必ず閉じる。二重 close は無害。
        job.invokeOnCompletion { runCatching { input.close() } }
    }

    /**
     * 選ばれたファイルを開けなかったことを伝える。
     *
     * URI が null（ファイル選択のキャンセル）は呼び出し側で無視し、ここには来ない。
     * 何も起きないと利用者が状況を判断できないため、必ず理由を出す。
     */
    fun onOpenFailed() {
        _uiState.update {
            it.copy(
                isImporting = false,
                errorTitle = DEFAULT_ERROR_TITLE,
                error = "ファイルを開けませんでした。選んだファイルが移動・削除されたか、" +
                    "このアプリに読み取りの許可がない可能性があります。別のファイルを選び直してください。"
            )
        }
    }

    /** 確認後に取り込む */
    fun confirmImport() {
        val data = _uiState.value.pendingImport ?: return
        // launch の前に同期的に消す。連打しても 2 回目は上の return で止まる
        _uiState.update {
            it.copy(
                isImporting = true,
                pendingImport = null,
                error = null,
                errorTitle = DEFAULT_ERROR_TITLE
            )
        }
        viewModelScope.launch {
            try {
                val result = withContext(ioDispatcher) { backupRepository.restore(data) }
                _uiState.update {
                    it.copy(
                        isImporting = false,
                        lastResult = result,
                        message = buildResultMessage(result)
                    )
                }
            } catch (e: CancellationException) {
                // キャンセルは握りつぶさずに伝播させる
                throw e
            } catch (e: Exception) {
                // restore は DB と SharedPreferences をまたぐ単一トランザクションではない。
                // 途中で失敗すると一部だけ反映されている可能性があるため、必ずそう伝える。
                _uiState.update {
                    it.copy(
                        isImporting = false,
                        error = IMPORT_PARTIAL_FAILURE_MESSAGE,
                        errorTitle = PARTIAL_FAILURE_TITLE
                    )
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
        _uiState.update { it.copy(message = null, error = null, errorTitle = DEFAULT_ERROR_TITLE) }
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

    /**
     * 確認ダイアログ表示用のバックアップ日時。
     *
     * **JSON に書かれている文字列をそのまま出す。** 日時として解釈し直さない。
     * 理由:
     * - 書き出し側はローカル時刻に `Z` を付けている（UTC 表記ではない）。パースすると
     *   端末のタイムゾーン次第で数時間ずれ、実際より新しい/古いバックアップに見える
     * - `SimpleDateFormat` は既定で lenient のため、不正な文字列を別の日時として
     *   解釈してしまうことがある
     *
     * 空の場合は「不明」と明示する（version だけの JSON でも復元できてしまうため、
     * いつのバックアップか分からないことを隠さない）。長すぎる値は切り詰める。
     */
    fun exportedAtLabel(data: BackupData): String {
        val raw = data.exportedAt.trim()
        if (raw.isEmpty()) return UNKNOWN_EXPORTED_AT
        if (raw.length <= MAX_EXPORTED_AT_CHARS) return raw
        return raw.take(MAX_EXPORTED_AT_CHARS) + "…"
    }

    /**
     * 確認ダイアログ表示用の表示設定の説明。
     *
     * 取り込みで**上書きされる**値そのものを出す。利用者が結果を確認できるようにする。
     */
    fun settingsLabel(settings: BackupSettings): String {
        val sort = when (settings.sortMode) {
            FolderSortMode.LATEST.name -> "更新日時順"
            FolderSortMode.RECENT.name -> "最近閲覧順"
            else -> settings.sortMode
        }
        val resume = if (settings.showResumeCard) "表示する" else "表示しない"
        return "フォルダ一覧: ${settings.folderColumns} 列 / 並び順: $sort\n" +
            "画像一覧: 縦 ${settings.imageColumnsPortrait} 列・横 ${settings.imageColumnsLandscape} 列\n" +
            "「前回の続き」: $resume"
    }

    /**
     * [input] を最大 [maxBytes] まで読み、UTF-8 の文字列にする。
     *
     * 上限を超えた時点で [ImportTooLarge] を投げ、残りは読まない。
     * ストリームの close は呼び出し側の責務にする（全経路で閉じるため）。
     */
    private fun readTextLimited(input: InputStream, maxBytes: Long): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(READ_BUFFER_BYTES)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) throw ImportTooLarge(maxBytes)
            out.write(buffer, 0, read)
        }
        return out.toByteArray().toString(Charsets.UTF_8)
    }

    /** 上限超過を利用者に分かる日本語にする */
    private fun tooLargeMessage(limitBytes: Long): String {
        val mib = limitBytes.toDouble() / (1024.0 * 1024.0)
        val label = if (mib >= 1.0) {
            String.format(Locale.US, "%.0fMiB", mib)
        } else {
            "${limitBytes}バイト"
        }
        return "ファイルが大きすぎます（上限 $label）。Pictobi のバックアップ JSON を選んでください。"
    }

    companion object {
        /** 取り込みを許可するファイルサイズの上限 (16MiB) */
        const val MAX_IMPORT_BYTES: Long = 16L * 1024 * 1024

        /** 読み込み時のバッファサイズ */
        private const val READ_BUFFER_BYTES = 8 * 1024

        /** 日時が分からないときの表示 */
        private const val UNKNOWN_EXPORTED_AT = "不明"

        /** 最終書き出し日時の書式 (Issue #108) */
        private const val LAST_EXPORTED_PATTERN = "yyyy/MM/dd HH:mm"

        /** 一度も書き出していないときの表示 (Issue #108) */
        private const val NEVER_EXPORTED_LABEL = "まだ書き出していません"

        /** エラーダイアログの既定の見出し */
        const val DEFAULT_ERROR_TITLE = "読み込めませんでした"

        /** 取り込み途中で失敗したときの見出し */
        const val PARTIAL_FAILURE_TITLE = "取り込みを完了できませんでした"

        /** 画面に出す日時の最大文字数（異常に長い値をそのまま出さない） */
        private const val MAX_EXPORTED_AT_CHARS = 40

        /** 読み取り失敗の文言。例外の詳細（URI・パス）は出さない */
        private const val IMPORT_IO_FAILURE_MESSAGE =
            "ファイルを読み取れませんでした。別のファイルを選ぶか、保存先の状態を確認してください。"

        /** 想定外の内容のときの文言。例外の詳細は出さない */
        private const val IMPORT_INVALID_MESSAGE =
            "ファイルを読み取れませんでした。Pictobi のバックアップ JSON か確認してください。"

        /**
         * 取り込み途中で失敗したときの文言。
         *
         * [BackupRepository.restore] は DB と SharedPreferences をまたぐ単一トランザクションでは
         * ないため、失敗しても完全には巻き戻せない。一部だけ反映された可能性を必ず伝える。
         */
        private const val IMPORT_PARTIAL_FAILURE_MESSAGE =
            "取り込みの途中で失敗しました。タグ・お気に入り・除外フォルダ・表示設定の一部だけが" +
                "反映されている可能性があります。内容を確認してください。"
    }
}

/** 取り込みファイルが上限を超えたことを表す内部例外 */
private class ImportTooLarge(val limitBytes: Long) : Exception()
