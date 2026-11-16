package com.tonbiattack.fastestpictureviewer.data

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 初回インストール時の「バックアップから復元しますか」案内の状態管理 (Issue #111)。
 *
 * ## 何のために持つか
 *
 * 新規インストール直後は DB が空で、利用者が以前の端末で書き出したバックアップ JSON を
 * まだ取り込んでいない可能性がある。そこで一度だけ復元の案内を出す。
 * ただし「起動のたびに出す」「旧バージョンからの更新でも出す」のは邪魔なので、
 * 状態を永続化して 1 回に抑える。
 *
 * ## これは「データが無い」ことの判定ではない
 *
 * DB が無い・marker が無い、というだけでは「新規インストールでデータが無い」と断定できない。
 * 通常の SharedPreferences は OS の自動バックアップ（機種移行）で復元されうるため、
 * 設定だけが戻って DB は欠損している、という状態があり得る。
 * したがってこのクラスは**案内を出すかどうか**だけを決める。
 * 復元そのものは行わず、必ず利用者の確認を挟む。
 * 既存設定を上書きする可能性があることは、案内の画面側で明示すること。
 *
 * ## なぜ SharedPreferences を使わないか
 *
 * 通常の SharedPreferences は自動バックアップで転送されうる。転送されると
 * 「案内済み」フラグだけが新しい端末へ移り、DB は空のまま案内も出ない、という
 * 取りこぼしが起きる。そのため自動バックアップの対象外である
 * [Context.getNoBackupFilesDir] 配下に marker を置く。
 *
 * ## 状態
 *
 * - 不在 (Absent)  : まだ何も記録していない
 * - PENDING        : 案内を出すべき（未処理）
 * - HANDLED        : 案内を閉じた／選択した。以後出さない
 * - 破損 (Corrupt) : 読めない・内容が不正
 *
 * ## fail closed
 *
 * marker が破損している、または読み書きに失敗した場合は「案内しない」側に倒す。
 * 誤って案内を出すより出さない方が安全で、復元は利用者の明示操作でしか行わない。
 * アプリの起動自体は妨げない（このクラスは例外を投げない）。
 *
 * ## 復元はしない
 *
 * このクラスは状態を持つだけで、バックアップの読み込みや DB の書き換えは一切行わない。
 * 起動しただけ・案内を閉じただけでは復元は走らない。
 */
class InitialBackupPromptStore internal constructor(
    /** 状態を記録する marker。テストから差し替えられるようにしている */
    private val markerFile: File
) {

    constructor(context: Context) : this(
        File(
            (context.applicationContext ?: context).noBackupFilesDir,
            MARKER_FILE_NAME
        )
    )

    /**
     * 起動時、**DB を開く前**に呼ぶ。案内を出すべきなら true。
     *
     * 判定は次のとおり。
     * - HANDLED        : false（以後出さない）
     * - PENDING        : true（前回の続き。DB が既に存在していても保持して出す）
     * - 破損           : false（fail closed）
     * - 不在 + DB あり : 旧バージョンからの更新とみなし HANDLED を書いて false
     * - 不在 + DB なし : DB がないため案内対象とし PENDING を書いて true
     *
     * 「不在 + DB なし」で PENDING を書くのは DB を開く前なので、
     * ここで書いた後にプロセスが落ちても、次回起動で PENDING が残り案内を続けられる。
     *
     * なお「不在 + DB なし」は新規インストールとは限らない。OS の自動バックアップで
     * 設定だけ復元され DB が欠損した場合も同じ状態になる。そのためここで決めるのは
     * 「案内を出すか」だけで、データの有無は断定しない。
     *
     * @param databaseExistsBeforeOpen DB を開く前の時点で DB ファイルが存在したか
     * @return 案内を出すべきか
     */
    @Synchronized
    fun initializeForInstall(databaseExistsBeforeOpen: Boolean): Boolean {
        return when (readState()) {
            MarkerState.Handled -> false
            MarkerState.Pending -> true
            MarkerState.Corrupt -> false
            MarkerState.Absent -> {
                if (databaseExistsBeforeOpen) {
                    // 旧バージョンから更新した既存端末。初回案内は出さない。
                    // 書き込みに失敗しても false のままでよく、案内は出さない
                    writeState(STATE_HANDLED)
                    false
                } else {
                    // DB がないため案内対象。DB を開く前に pending を永続化する。
                    // 書けない場合は「出す」と言い切れないため fail closed で出さない
                    writeState(STATE_PENDING)
                }
            }
        }
    }

    /**
     * 案内を出すべきか。永続化された状態を毎回読み直す。
     *
     * Activity の再生成（recreate）や AppContainer 初期化後の再問い合わせでも
     * 同じ結果になるよう、メモリにキャッシュせず marker を正本にする。
     * 破損・読み取り失敗は false（fail closed）。
     */
    @Synchronized
    fun shouldShow(): Boolean = readState() == MarkerState.Pending

    /**
     * 案内を閉じた、または選択したときに呼ぶ。以後 [shouldShow] は false になる。
     *
     * 書き込みに失敗した場合は HANDLED を永続化できないため、次回起動で
     * もう一度案内が出る可能性がある。ただし復元は行わないので実害はない。
     * 失敗しても例外は投げない（起動を妨げない）。
     */
    @Synchronized
    fun dismiss() {
        writeState(STATE_HANDLED)
    }

    /** marker を読み、状態を判定する。読めない・内容が不正なら破損として扱う */
    private fun readState(): MarkerState {
        return try {
            if (!markerFile.isFile) {
                // 存在しないなら未記録。存在するのにファイルでない（ディレクトリ等）なら破損
                if (markerFile.exists()) MarkerState.Corrupt else MarkerState.Absent
            } else {
                val text = markerFile.readText(Charsets.UTF_8).trim()
                val parts = text.split(':')
                if (parts.size == 2 && parts[0] == MARKER_VERSION) {
                    when (parts[1]) {
                        STATE_PENDING -> MarkerState.Pending
                        STATE_HANDLED -> MarkerState.Handled
                        else -> MarkerState.Corrupt
                    }
                } else {
                    MarkerState.Corrupt
                }
            }
        } catch (e: Exception) {
            // 読めない = 破損。案内しない側に倒す
            MarkerState.Corrupt
        }
    }

    /**
     * marker を書く。成功したら true。
     *
     * 一時ファイルへ書いてから置き換えることで、書き込み途中でプロセスが落ちても
     * 中途半端な内容が残りにくいようにする。置き換えに失敗した環境では直接書く。
     * 失敗しても例外は投げない。
     */
    private fun writeState(state: String): Boolean {
        val dir = markerFile.parentFile ?: return false
        val temp = File(dir, TEMP_FILE_NAME)
        return try {
            if (!dir.exists() && !dir.mkdirs()) {
                false
            } else {
                temp.writeText("$MARKER_VERSION:$state", Charsets.UTF_8)
                try {
                    Files.move(
                        temp.toPath(),
                        markerFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                    )
                } catch (e: Exception) {
                    // 置き換えが使えない環境では直接書く
                    markerFile.writeText("$MARKER_VERSION:$state", Charsets.UTF_8)
                }
                true
            }
        } catch (e: Exception) {
            false
        } finally {
            // 一時ファイルが残っていても状態の判定には使わないため消しておく
            if (temp.exists()) {
                try {
                    temp.delete()
                } catch (e: Exception) {
                    // 消せなくても無害。次回の書き込みで上書きされる
                }
            }
        }
    }

    /** marker の状態 */
    private enum class MarkerState {
        /** まだ何も記録していない */
        Absent,

        /** 案内を出すべき */
        Pending,

        /** 案内済み。以後出さない */
        Handled,

        /** 読めない・内容が不正 */
        Corrupt
    }

    private companion object {
        /** marker のファイル名。noBackupFilesDir 直下に置く */
        const val MARKER_FILE_NAME = "initial_backup_prompt.marker"

        /** 書き込み途中の内容を残さないための一時ファイル名 */
        const val TEMP_FILE_NAME = "initial_backup_prompt.marker.tmp"

        /** 形式の版。将来変えるときはこの値を上げる */
        const val MARKER_VERSION = "v1"

        /** 案内を出すべき状態 */
        const val STATE_PENDING = "PENDING"

        /** 案内済みの状態 */
        const val STATE_HANDLED = "HANDLED"
    }
}
