package com.tonbiattack.fastestpictureviewer.repository

import com.tonbiattack.fastestpictureviewer.core.util.NaturalSortKey
import com.tonbiattack.fastestpictureviewer.data.ImageItem
import com.tonbiattack.fastestpictureviewer.data.ImageReadableProbe

/**
 * 1 フォルダ分の実体確認結果 (Issue #77)。
 */
sealed interface FolderReadability {

    /** フォルダは生きている。非表示にする画像は無い */
    data object Healthy : FolderReadability

    /** フォルダは生きているが、一部の画像だけ開けない */
    data class PartiallyUnreadable(val items: List<ImageItem>) : FolderReadability

    /** フォルダごと消えている。配下の全画像が開けない */
    data class FolderGone(val items: List<ImageItem>) : FolderReadability
}

/**
 * フォルダが「丸ごと消えた」のか「一部の画像だけ欠けた」のかを判定する (Issue #77)。
 *
 * OPPO/ColorOS では削除済みファイルの MediaStore 行が残る。フォルダごと削除された場合は
 * 配下の全画像が開けなくなるが、**先頭画像だけが削除された**ケースもある。
 * 代表 1 件だけで判断すると、生きているフォルダを丸ごと隠してしまう
 * (実機で、実在するフォルダを丸ごと非表示にしてしまった)。
 *
 * そこで代表が開けない場合は、少し離れた画像も最大 [SAMPLE_SIZE] 件だけ確認し、
 * 1 枚でも開ければフォルダは生きていると判断する。プローブ回数は最大 4 回に抑える。
 */
class FolderReadabilityAssessor(
    private val probe: ImageReadableProbe,
    /** 代表以外に追加で確認する件数。増やすほど誤判定は減るが遅くなる */
    private val sampleSize: Int = SAMPLE_SIZE
) {

    /**
     * フォルダ内の画像 [items] を確認する。空なら [FolderReadability.Healthy]。
     * 呼び出し側は結果に応じて記録を更新する。
     */
    suspend fun assess(items: List<ImageItem>): FolderReadability {
        if (items.isEmpty()) return FolderReadability.Healthy

        val ordered = items.sortedWith(NaturalSortKey.comparator)
        val representative = ordered.first()
        if (probe.isReadable(representative.uri)) {
            // 代表が読めるならフォルダは生きている。全件は確認しない (速度優先)
            return FolderReadability.Healthy
        }

        // 代表が読めない。フォルダごと消えたのか、この 1 枚だけかを切り分ける
        val samples = pickSamples(ordered)
        val failed = mutableListOf(representative)
        for (sample in samples) {
            if (probe.isReadable(sample.uri)) {
                // 1 枚でも読めればフォルダは生きている
                return FolderReadability.PartiallyUnreadable(failed)
            }
            failed += sample
        }
        return FolderReadability.FolderGone(ordered)
    }

    /**
     * 代表以外から、フォルダ全体の傾向を見るためのサンプルを選ぶ。
     * 偏りを避けるため、末尾側と中間を優先する。
     */
    private fun pickSamples(ordered: List<ImageItem>): List<ImageItem> {
        if (ordered.size <= 1 || sampleSize <= 0) return emptyList()
        val rest = ordered.drop(1)
        val candidates = buildList {
            // 末尾 (削除されていれば確実に開けない)
            add(rest.last())
            // 中間
            if (rest.size >= 3) add(rest[rest.size / 2])
            // 前半
            if (rest.size >= 4) add(rest[1])
        }
        return candidates.distinctBy { it.id }.take(sampleSize)
    }

    companion object {
        /** 代表以外に追加で確認する既定件数 */
        const val SAMPLE_SIZE = 3
    }
}
