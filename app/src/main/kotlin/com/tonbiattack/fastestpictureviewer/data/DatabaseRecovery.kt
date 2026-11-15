package com.tonbiattack.fastestpictureviewer.data

/**
 * DB を開けなかったときの復旧判断 (Issue #88)。
 *
 * SQLite が壊れると Room の初期化で例外が出て、そのままではアプリが起動できない。
 * 実機の DB は約 283MB あり、破損リスクはゼロではない。
 * 壊れた DB は退避して作り直し、写真は再スキャンで戻す（お気に入り・タグは JSON から復元できる）。
 *
 * 判定だけを純粋関数として切り出し、単体テストで固定する。
 */
object DatabaseRecovery {

    /** 復旧が必要と判断した理由 */
    enum class Reason {
        /** DB ファイルが壊れている */
        CORRUPTED,

        /** スキーマが想定と合わない・未知のバージョン */
        SCHEMA_MISMATCH,

        /** 読み取り専用・容量不足など、DB 自体は壊れていない可能性が高い */
        NOT_CORRUPT
    }

    /**
     * 例外から復旧の要否を判定する。
     *
     * 「壊れている」と断言できるものだけを作り直す対象にする。
     * 容量不足や権限の問題で作り直すと、貴重なキャッシュを無駄に失うため。
     *
     * @param message 例外のメッセージ（大文字小文字は問わない）
     * @param isSqliteException SQLite 由来の例外か
     */
    fun classify(message: String?, isSqliteException: Boolean): Reason {
        val text = (message ?: "").lowercase()

        // SQLite の破損を示す典型的なメッセージ
        val corruptionSigns = listOf(
            "database disk image is malformed",
            "file is not a database",
            "database corruption",
            "sqlite_corrupt",
            "corrupt"
        )
        if (corruptionSigns.any { it in text }) {
            return Reason.CORRUPTED
        }

        // スキーマ不整合（移行漏れ・ダウングレード）
        val schemaSigns = listOf(
            "no such table",
            "no such column",
            "migration",
            "downgrade",
            "room cannot verify",
            "pre-packaged database"
        )
        if (schemaSigns.any { it in text }) {
            return Reason.SCHEMA_MISMATCH
        }

        // SQLite 由来でない例外（権限・容量）は作り直さない
        if (!isSqliteException) return Reason.NOT_CORRUPT
        return Reason.NOT_CORRUPT
    }

    /** 実際に DB を作り直してよいか */
    fun shouldRecreate(reason: Reason): Boolean =
        reason == Reason.CORRUPTED || reason == Reason.SCHEMA_MISMATCH

    /**
     * 復旧後にユーザーへ伝える案内。フォルダ名やファイル名は含めない。
     */
    const val MESSAGE =
        "画像の情報を読み込めなかったため、データを作り直しました。" +
            "写真そのものは削除されていません。一覧が表示されるまで少し時間がかかります。"

    /** お気に入り・タグの復元を促す補足 */
    const val RESTORE_HINT =
        "お気に入りやタグは、メニューの「バックアップから復元」で戻せます。"
}
