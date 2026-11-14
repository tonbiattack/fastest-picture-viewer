package com.tonbiattack.fastestpictureviewer.repository

/**
 * 実体読み取り検証の結果をどう扱うかの純粋ロジック (Issue #77)。
 *
 * MediaStore に残った削除済み行を非表示にするのが目的だが、SD カードの一時的な
 * 取り外し・アンマウント・権限の一時喪失でも「開けない」は起きる。
 * そのまま記録すると、実在するフォルダを大量に隠してしまう。
 *
 * そこで「失敗が極端に多い場合は端末側の一時的な問題とみなして記録しない」
 * という安全弁を置く。判定だけをここに切り出し、単体テストで固定する。
 */
object ReadabilityValidation {

    /** この割合を超えて失敗したら、一時的なストレージ障害とみなす */
    const val SUSPICIOUS_FAILURE_RATIO = 0.5

    /** 対象がこれ以下のときは割合で判断しない (小規模ライブラリで誤判定しないため) */
    const val MIN_TARGETS_FOR_RATIO_CHECK = 8

    /**
     * 検証結果を記録してよいか。
     *
     * @param targetCount 検証したフォルダ数
     * @param unreadableCount 開けなかったフォルダ数
     * @return true なら記録してよい。false なら一時的な障害とみなし、記録しない
     */
    fun shouldRecord(targetCount: Int, unreadableCount: Int): Boolean {
        if (unreadableCount <= 0) return false
        if (targetCount <= MIN_TARGETS_FOR_RATIO_CHECK) return true
        val ratio = unreadableCount.toDouble() / targetCount
        return ratio <= SUSPICIOUS_FAILURE_RATIO
    }

    /**
     * 一時的な障害と判断したときに、ログへ出す理由。
     * 記録しない場合だけ非 null を返す。
     */
    fun skipReason(targetCount: Int, unreadableCount: Int): String? {
        if (shouldRecord(targetCount, unreadableCount)) return null
        if (unreadableCount <= 0) return null
        return "開けないフォルダが多すぎるため記録を見送り " +
            "($unreadableCount/$targetCount)。SD カードや権限の一時的な問題の可能性"
    }
}
