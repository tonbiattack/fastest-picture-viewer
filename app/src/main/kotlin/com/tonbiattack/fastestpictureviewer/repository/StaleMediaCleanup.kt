package com.tonbiattack.fastestpictureviewer.repository

/**
 * 「実体が見つからない画像」を一覧から整理してよいかの判定 (Issue #80)。
 *
 * #77 では実体の無い行を表示から外すだけで、DB には残していた。
 * そのため件数が積み上がり、毎回の検証で同じフォルダを調べ続ける。
 * ここでは安全に整理するための条件だけを純粋関数として切り出し、単体テストで固定する。
 *
 * **誤って実在する画像を消すと一覧から見えなくなる**ため、判断は常に安全側に倒す。
 */
object StaleMediaCleanup {

    /** 1 回の整理で扱うフォルダ数の上限。大量削除を一度に実行しない */
    const val MAX_FOLDERS_PER_RUN = 500

    /** 整理を許可するかの判定結果 */
    sealed interface Decision {

        /** 整理してよい。対象フォルダを返す */
        data class Allowed(val folderKeys: List<String>) : Decision

        /** 整理しない。理由を返す */
        data class Rejected(val reason: String) : Decision
    }

    /**
     * 整理の可否を判定する。
     *
     * @param mountedVolumes 現在マウントされているボリューム名
     * @param candidates 整理候補 (フォルダごとのボリューム名とキー)
     * @param totalFolders ライブラリ全体のフォルダ数 (割合の安全弁に使う)
     */
    fun decide(
        mountedVolumes: Set<String>,
        candidates: List<Candidate>,
        totalFolders: Int
    ): Decision {
        if (candidates.isEmpty()) {
            return Decision.Rejected("整理する対象がありません")
        }

        // マウントされていないボリュームの候補は絶対に触らない。
        // SD カードが抜けているだけで「実体が無い」と誤判定するため (最重要)
        val mounted = candidates.filter { it.volumeName in mountedVolumes }
        val skipped = candidates.size - mounted.size

        if (mounted.isEmpty()) {
            return Decision.Rejected(
                "対象のストレージが見つかりません。SD カードが外れていないか確認してください" +
                    if (skipped > 0) "（$skipped 件を保留）" else ""
            )
        }

        // 候補が多すぎる場合は分割して実行する (1 回の削除を小さく保つ)
        val limited = mounted
            .sortedBy { it.folderKey }
            .take(MAX_FOLDERS_PER_RUN)
            .map { it.folderKey }

        // ライブラリの大半が候補になる場合は、端末側の一時的な不調とみなす。
        // 実在する画像を大量に失うより、整理しないほうが安全。
        // ただし小規模ライブラリでは割合が高くなりやすいため、件数が少ないときは割合で判断しない
        if (totalFolders > MIN_FOLDERS_FOR_RATIO_CHECK &&
            mounted.size.toDouble() / totalFolders > SUSPICIOUS_RATIO
        ) {
            return Decision.Rejected(
                "対象が多すぎるため中止しました（${mounted.size}/$totalFolders フォルダ）。" +
                    "SD カードや権限の状態を確認してください"
            )
        }

        return Decision.Allowed(limited)
    }

    /** 整理の対象 1 件分 */
    data class Candidate(
        val folderKey: String,
        val volumeName: String,
        val imageCount: Long,
        val totalBytes: Long
    )

    /** 候補の割合がこれを超えたら一時的な不調とみなす */
    const val SUSPICIOUS_RATIO = 0.5

    /** フォルダ総数がこれ未満なら割合で判断しない（小規模ライブラリの誤判定を防ぐ） */
    const val MIN_FOLDERS_FOR_RATIO_CHECK = 20
}
