package com.tonbiattack.fastestpictureviewer.data

import android.database.sqlite.SQLiteDatabaseCorruptException

/**
 * DB を開けなかった原因の判定 (Issue #88)。
 *
 * 「壊れている」と「その他の失敗（容量不足・権限など）」を分ける。
 * 前者だけ、利用者に初期化の選択肢を出す。後者で初期化を勧めると、
 * 直せば戻るデータまで失わせてしまう。
 *
 * 判定だけを純粋関数として切り出し、単体テストで固定する。
 */
object DatabaseError {

    /** 破損を示すメッセージ（小文字で比較） */
    private val CORRUPTION_SIGNS = listOf(
        "file is not a database",
        "database disk image is malformed",
        "sqlite_corrupt",
        "sqlite_notadb"
    )

    /**
     * DB の破損が原因か。
     *
     * 例外の連鎖（cause）をたどり、型とメッセージの両方を見る。
     * 型だけに頼らないのは、実装により包まれ方が変わるため。
     */
    fun isCorruption(t: Throwable?): Boolean =
        generateSequence(t) { it.cause }.any { cause ->
            cause is SQLiteDatabaseCorruptException ||
                CORRUPTION_SIGNS.any { it in (cause.message ?: "").lowercase() }
        }

    /** 初期化を勧めてよいか（破損のときだけ） */
    fun canOfferReset(t: Throwable?): Boolean = isCorruption(t)

    /**
     * 初期化の前に伝える内容。何が失われ、何が残るかを明示する。
     * フォルダ名・ファイル名は含めない。
     */
    const val RESET_DESCRIPTION =
        "写真そのものは削除されません。お気に入り・タグ・フォルダの設定・表示位置は失われます。" +
            "これらはバックアップから復元できます。"

    /** 初期化後に表示する案内 */
    const val RESET_DONE =
        "画像の情報を作り直しました。一覧の表示まで少し時間がかかります。"
}
