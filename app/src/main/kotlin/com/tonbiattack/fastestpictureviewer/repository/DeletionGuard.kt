package com.tonbiattack.fastestpictureviewer.repository

/**
 * 全件走査の結果から、削除を実行してよいかを判定する (Issue #92)。
 *
 * 写真ファイルは消さないが、DB から行を消すと一覧から消える。誤削除は
 * ユーザーには「写真が消えた」と見えるため、疑わしいときは削除しない。
 *
 * 特に危険なのは「走査が 1 件も返らなかった」場合。`MediaStoreScanner` は
 * カーソルが null だと 0 件を返し、例外も出さない。これを「全画像が消えた」と
 * 解釈すると、SD カードが一時的に読めないだけで全件を失う。
 *
 * 判定だけを純粋関数として切り出し、単体テストで固定する。
 */
object DeletionGuard {

    /** 削除を見送った理由 */
    enum class Reason {
        /** 走査結果が 0 件。一時的な走査失敗と区別できない */
        EMPTY_SCAN,

        /** ボリュームが読めない。SD 未挿入・権限なしなど */
        VOLUME_UNREADABLE,

        /** 削除対象が全体に占める割合が大きすぎる。一括削除と走査異常を区別できない */
        TOO_LARGE_RATIO
    }

    sealed interface Decision {
        data object Allowed : Decision
        data class Rejected(val reason: Reason) : Decision
    }

    /** 削除してよい割合の上限 */
    const val MAX_DELETION_RATIO = 0.5

    /**
     * 割合の判定を始める件数。少ない件数では「全部消した」も自然に起きるため、
     * 割合では止めない。
     */
    const val MIN_COUNT_FOR_RATIO = 20

    /**
     * 削除してよいか判定する。
     *
     * @param cachedCount 削除前の DB 行数
     * @param scannedCount 今回の走査で見つかった件数
     * @param deletionCount 削除対象として残った件数
     * @param volumeReadable 削除直前にボリュームを読めることを確認できたか
     */
    fun decide(
        cachedCount: Int,
        scannedCount: Int,
        deletionCount: Int,
        volumeReadable: Boolean
    ): Decision {
        // 1. 読めないボリュームは触らない。SD を抜いただけで全消しになるのを防ぐ
        if (!volumeReadable) return Decision.Rejected(Reason.VOLUME_UNREADABLE)

        // 2. 0 件の走査は失敗とみなす。カーソルが null でも例外にならないため
        //    「全部消えた」と誤解しやすい
        if (cachedCount > 0 && scannedCount == 0) {
            return Decision.Rejected(Reason.EMPTY_SCAN)
        }

        // 3. 大量削除は一括削除か走査異常かを区別できない。安全側に倒す
        if (deletionCount > 0 &&
            cachedCount >= MIN_COUNT_FOR_RATIO &&
            deletionCount > cachedCount * MAX_DELETION_RATIO
        ) {
            return Decision.Rejected(Reason.TOO_LARGE_RATIO)
        }

        return Decision.Allowed
    }

    /** 見送った理由を利用者向けの文にする。フォルダ名・ファイル名は含めない */
    fun message(reason: Reason): String = when (reason) {
        Reason.EMPTY_SCAN ->
            "写真の一覧を取得できなかったため、削除の反映を見送りました。"
        Reason.VOLUME_UNREADABLE ->
            "ストレージを読み取れないため、削除の反映を見送りました。"
        Reason.TOO_LARGE_RATIO ->
            "削除される件数が多いため、確認のうえ反映を見送りました。"
    }
}
