package com.tonbiattack.fastestpictureviewer.ui.folder

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tonbiattack.fastestpictureviewer.core.util.FolderNames
import com.tonbiattack.fastestpictureviewer.data.FolderSummaryRow
import com.tonbiattack.fastestpictureviewer.repository.GalleryRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

/** フォルダ一覧の 1 行分 */
data class FolderUiModel(
    val folderKey: String,
    val volumeName: String,
    val relativePath: String,
    val folderName: String,
    val imageCount: Long,
    val latestModifiedSec: Long,
    val representativeImageUri: String?
)

/** フォルダ一覧画面の状態 */
data class FolderListUiState(
    /** 最初の Room 発行待ち (キャッシュが全くない真の初回のみ表示する) */
    val isLoadingFirstFrame: Boolean = true,

    /** フォルダ一覧。同期開始時にもクリアしない (Issue #3) */
    val folders: List<FolderUiModel> = emptyList(),

    /** 背景同期中かどうか */
    val isSyncing: Boolean = false,

    /** 初回スキャン (まだキャッシュが全くない状態での走査) 中か */
    val isInitialScan: Boolean = false,

    /** 同期失敗のメッセージ */
    val errorMessage: String? = null
) {
    /** 同期中でまだ 1 件も無い場合に初回スキャン表示を出す */
    val showInitialScanProgress: Boolean get() = isSyncing && folders.isEmpty()
}

/**
 * フォルダ一覧の ViewModel。
 *
 * - Room の Flow をそのまま StateFlow にする。再取得中も直前の一覧を保持し続ける
 * - スクロール位置は SavedStateHandle に書き込み、プロセス再生成後も復元する
 */
class FolderListViewModel(
    repository: GalleryRepository,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    /** 一覧の初回表示までの時間計測用 (JVM 単体テストでも動くよう System.nanoTime を使う) */
    private val startedAtNanos = System.nanoTime()
    private var firstFrameLogged = false

    /**
     * フォルダ集計 + 代表サムネイル + 同期状態を合成する。
     *
     * 除外設定が変わったら内側の Room クエリを購読し直す。Room の無効化通知だけに頼らず、
     * 解除直後に戻ってきた場合でも最新の一覧を確実に取得するための保険。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<FolderListUiState> =
        repository.observeExcludedFolders()
            .map { Unit }
            .onStart { emit(Unit) }
            .flatMapLatest {
                combine(
                    repository.observeFolderSummaries(),
                    // 代表サムネイルの到着を待たず、フォルダ一覧を先に表示する
                    repository.observeRepresentatives().onStart { emit(emptyList()) },
                    repository.syncStatus
                ) { summaries, representatives, status ->
                    if (!firstFrameLogged && summaries.isNotEmpty()) {
                        firstFrameLogged = true
                        val elapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000
                        println("FPV-Perf: folder list first frame: ${elapsedMs}ms, folders=${summaries.size}")
                    }
                    FolderListUiState(
                        isLoadingFirstFrame = false,
                        folders = summaries.toUiModels(representatives),
                        isSyncing = status.isRunning,
                        isInitialScan = status.isInitialScan,
                        errorMessage = status.errorMessage
                    )
                }
            }
            .stateIn(
                scope = viewModelScope,
                // 設定画面離脱後も短時間はホットキープし、即戻るケースでの再発行を避ける
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = FolderListUiState(isLoadingFirstFrame = true)
            )

    /** 同期エラーメッセージは直近状態として表示する (誤操作で消えやすいランダム消去はしない) */

    /** スクロール位置をプロセス死に耐える形で保存する */
    fun saveScrollIndex(index: Int) {
        savedStateHandle[KEY_SCROLL_INDEX] = index
    }

    fun initialScrollIndex(): Int = savedStateHandle[KEY_SCROLL_INDEX] ?: 0

    private fun List<FolderSummaryRow>.toUiModels(
        representatives: List<com.tonbiattack.fastestpictureviewer.data.RepresentativeImageRow>
    ): List<FolderUiModel> {
        // フォルダごとに代表サムネイルを最大 1 件割り当てる
        val repByFolder = representatives.associateBy({ it.folderKey }, { it.id })
        return map { row ->
            FolderUiModel(
                folderKey = row.folderKey,
                volumeName = row.volumeName,
                relativePath = row.relativePath,
                folderName = FolderNames.parse(row.relativePath),
                imageCount = row.imageCount,
                latestModifiedSec = row.latestModifiedSec,
                representativeImageUri = repByFolder[row.folderKey]
            )
        }
    }

    private companion object {
        const val KEY_SCROLL_INDEX = "folderListScrollIndex"
    }
}
