package com.tonbiattack.fastestpictureviewer.repository

import com.tonbiattack.fastestpictureviewer.data.ImageItem

/**
 * 走査結果と Room のキャッシュから「登録すべきもの / 削除すべきもの」を計算する純粋ロジック。
 *
 * 仕様に基づくルール:
 * - 削除判定は、正常に完了した走査結果をもとにボリューム単位で行う
 * - 変更がない既存行は upsert 候補に入れない (DB 負荷と UI の再描画を抑える)
 */
object PendingSyncPlanner {

    /** 差分計画 */
    data class Plan(
        /** 新規・更新された画像 */
        val upserts: List<ImageItem>,

        /** 走査結果に存在しないため削除すべき画像 */
        val deletions: List<ImageItem>
    )

    /**
     * 同一ボリュームの走査結果 [scanned] と Room 内のキャッシュ [cached] を比較する。
     * 両者は同じ volumeName の画像列であることを前提とする。
     */
    fun plan(scanned: List<ImageItem>, cached: List<ImageItem>): Plan {
        val cachedById = cached.associateBy { it.id }
        val scannedIds = scanned.mapTo(hashSetOf()) { it.id }

        val upserts = mutableListOf<ImageItem>()
        for (item in scanned) {
            val existing = cachedById[item.id]
            if (existing == null || existing.contentHash() != item.contentHash()) {
                upserts += item
            }
        }

        val deletions = cached.filter { it.id !in scannedIds }

        return Plan(upserts = upserts, deletions = deletions)
    }

    /** 変更検出に使う列のみでハッシュを取る。幅・高さは端末によって安定しないため除外する */
    private fun ImageItem.contentHash(): String =
        "$mediaId|$displayName|$relativePath|$dateModifiedSec|$size|$mimeType"
}
