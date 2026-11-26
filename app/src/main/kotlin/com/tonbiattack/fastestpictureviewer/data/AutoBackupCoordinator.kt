package com.tonbiattack.fastestpictureviewer.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Android Auto Backup 用の控えを自動で維持する (Issue #151)。
 *
 * ## 役割
 *
 * - タグ・お気に入り・除外・表示設定が変わったら、小さな JSON へ書き出す
 * - 起動時に、控えはあるが DB が空（＝Auto Backup から復元された直後）なら取り込む
 *
 * ## 設計方針
 *
 * - 独自のクラウド API は使わない。OS のバックアップ設定に従う
 * - 書き出しは変更のたびではなく、一定時間まとめて行う（連続操作で走らせない）
 * - 起動・閲覧をブロックしない。すべてバックグラウンドで行う
 * - Room DB・画像・サムネイルは含めない（再生成できるか、大きすぎるため）
 *
 * @param store 控えの保存先
 * @param backupRepository タグ等の読み書き (Issue #74 の実装を再利用する)
 * @param appVersionName 控えに記録するアプリのバージョン名
 * @param debounceMs 変更から書き出しまでの待ち時間
 */
class AutoBackupCoordinator(
    private val store: AutoBackupStore,
    private val backupRepository: BackupDataSource,
    private val appVersionName: () -> String,
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    /** 書き出し結果を記録する (ログ・状態表示用) */
    private val onSaved: (AutoBackupStore.WriteResult) -> Unit = {}
) {

    private val saveMutex = Mutex()

    /** 予約中の書き出し。新しい変更が来たら待ち時間をやり直す */
    private var pendingSave: Job? = null

    /**
     * データ変更時に呼ぶ。待ち時間の後にまとめて書き出す。
     *
     * 連続して呼ばれても、最後の 1 回だけが実行される。
     */
    fun scheduleSave(scope: CoroutineScope) {
        pendingSave?.cancel()
        pendingSave = scope.launch {
            delay(debounceMs)
            saveNow()
        }
    }

    /**
     * 今すぐ書き出す。既に書き出し中なら終わるまで待つ。
     *
     * @return 書き出した結果。対象データが無い場合も空の控えを書く
     */
    suspend fun saveNow(): AutoBackupStore.WriteResult = saveMutex.withLock {
        val data = backupRepository.collect(appVersionName())
        val json = BackupJson.encode(data)
        val result = store.save(json)
        onSaved(result)
        result
    }

    /**
     * 控えから復元する必要があれば取り込む。
     *
     * 判定は「控えがある」かつ「DB にユーザーデータが 1 件も無い」場合だけ。
     * DB が除外されているため、Auto Backup からの復元直後はこの状態になる。
     * 既にデータがある端末（通常の起動・更新）では何もしない。
     *
     * 取り込みは [BackupRepository.restore] のマージ処理を使うため、
     * 仮に二重に走っても重複しない。
     *
     * @return 取り込んだら true
     */
    suspend fun restoreIfNeeded(): Boolean {
        if (!store.exists()) return false

        val current = backupRepository.collect(appVersionName())
        if (current.hasUserData()) return false

        val json = store.load() ?: return false
        val data = runCatching { BackupJson.decode(json) }.getOrNull() ?: return false
        if (!data.hasUserData()) return false

        val result = runCatching { backupRepository.restore(data) }
        return result.isSuccess
    }

    companion object {
        /** 変更から書き出しまでの待ち時間。操作の合間を縫って書く */
        const val DEFAULT_DEBOUNCE_MS = 5_000L
    }
}

/**
 * ユーザーが作ったデータ（再生成できないもの）を持っているか (Issue #151)。
 *
 * 表示設定は常に存在するため、復元要否の判定には含めない。
 */
fun BackupData.hasUserData(): Boolean =
    tags.isNotEmpty() ||
        folderTags.isNotEmpty() ||
        favoriteFolders.isNotEmpty() ||
        favoriteImages.isNotEmpty() ||
        excludedFolders.isNotEmpty()
