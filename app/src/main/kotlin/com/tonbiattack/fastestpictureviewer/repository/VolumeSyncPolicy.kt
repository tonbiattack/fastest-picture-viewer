package com.tonbiattack.fastestpictureviewer.repository

/** ボリュームごとの走査種別 */
enum class VolumeScanKind {
    /** 全件を走査する (削除判定も可能) */
    FULL,

    /** 前回同期以降の更新分だけを走査する */
    INCREMENTAL,

    /** 変更がないため走査しない */
    SKIP
}

/**
 * MediaStore の世代番号から、ボリュームごとの走査種別を決める純粋ロジック。
 *
 * - 手動更新や DB が空の場合、定期全件走査の期限が来た場合は全件走査
 * - 世代番号が取得でき、前回と同じなら走査を省略
 * - 世代番号が変わっていても、通常は日時ベースの差分走査だけ行う
 *   (写真追加のたびに 35 万件を全件走査すると遅すぎるため)
 * - 削除の反映は手動更新または定期全件走査に任せる
 * - 世代番号が取得できない環境も日時ベースの差分走査
 */
object VolumeSyncPolicy {

    fun decide(
        isFullSync: Boolean,
        currentGeneration: Long?,
        storedGeneration: Long?
    ): VolumeScanKind {
        if (isFullSync) return VolumeScanKind.FULL
        if (currentGeneration == null || storedGeneration == null) {
            return VolumeScanKind.INCREMENTAL
        }
        return if (currentGeneration == storedGeneration) {
            VolumeScanKind.SKIP
        } else {
            VolumeScanKind.INCREMENTAL
        }
    }
}
