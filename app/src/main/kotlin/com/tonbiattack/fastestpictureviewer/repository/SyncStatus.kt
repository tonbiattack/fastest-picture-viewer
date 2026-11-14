package com.tonbiattack.fastestpictureviewer.repository

/** 同期要求の種別 */
enum class SyncMode {
    /** 空なら完全走査、データがあれば差分走査を自動選択する */
    AUTO,

    /** 強制的に完全走査し、削除済みファイルも反映する (手動更新) */
    FULL
}

/** 同期の実行状態。UI はこの状態を表示するが、一覧データは常に Room 側の Flow を優先する */
data class SyncStatus(
    /** 同期実行中かどうか */
    val isRunning: Boolean = false,

    /** 初回 (DB が空の) スキャンかどうか */
    val isInitialScan: Boolean = false,

    /** 直近の同期で走査できなかったボリューム数 (SD 未接続など) */
    val failedVolumeCount: Int = 0,

    /** 直近の同期で投稿するエラーメッセージ (問題なければ null) */
    val errorMessage: String? = null,

    /** 直近の同期で新規追加された画像数 */
    val addedCount: Int = 0,

    /** 直近の同期で削除された画像数 */
    val removedCount: Int = 0,

    /**
     * 直近の同期が全件走査だったか (Issue #86)。
     * 「更新したのに変化がない」を判定するために持つ。
     */
    val wasFullScan: Boolean = false
) {
    companion object {
        val IDLE = SyncStatus()
    }
}

/** 1 回の同期の結果 */
data class SyncResult(
    val addedCount: Int,
    val updatedCount: Int,
    val removedCount: Int,
    val failedVolumes: List<String>
)
