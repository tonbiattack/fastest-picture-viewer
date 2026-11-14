package com.tonbiattack.fastestpictureviewer.repository

/**
 * 「新しい写真が出てこない」ときに、メディア再スキャンを案内すべきかの判定 (Issue #86)。
 *
 * SD カードへフォルダを移動した直後は、実ファイルがあっても MediaStore に登録されていない
 * ことがある。アプリは MediaStore を情報源にしているため、何度全件走査しても取得できない。
 * この場合ユーザーには「更新したのに変化がない」としか見えず、原因も対処法も分からない。
 *
 * そこで「全件走査は成功したのに 1 件も増えなかった」状況を検知して案内する。
 * ただし MediaStore の内部状態はアプリから完全には分からないため、**断定はしない**。
 * 判定だけを純粋関数として切り出し、単体テストで固定する。
 */
object MediaRescanHint {

    /**
     * 案内の要否を判定する。
     *
     * 「全件走査が成功し、追加も削除も 0 件」のときだけ案内する。
     * 初回スキャン・失敗あり・変化があった場合は出さない（誤解を招くため）。
     *
     * @param wasFullScan 直近の同期が全件走査だったか
     * @param addedCount 追加された画像数
     * @param removedCount 削除された画像数
     * @param failedVolumeCount 走査できなかったボリューム数
     * @param hasFolders 既に一覧へ表示できるフォルダがあるか
     */
    fun shouldSuggest(
        wasFullScan: Boolean,
        addedCount: Int,
        removedCount: Int,
        failedVolumeCount: Int,
        hasFolders: Boolean
    ): Boolean {
        // 全件走査でなければ「最新の状態を見た」と言えない
        if (!wasFullScan) return false
        // 失敗があるなら、未反映の原因がそちらである可能性が高い
        if (failedVolumeCount > 0) return false
        // 変化があったなら案内は不要
        if (addedCount > 0 || removedCount > 0) return false
        // 一覧が空の場合は別の案内（初回スキャン中・写真なし）を出す
        if (!hasFolders) return false
        return true
    }

    /**
     * 案内文。フォルダ名やファイル名は含めない（プライバシー配慮、Issue #86）。
     */
    const val MESSAGE =
        "新しい写真が見つかりませんでした。端末のメディア情報が更新されていない可能性があります。" +
            "端末を再起動するか、しばらく待ってからもう一度お試しください。"

    /** 補足の操作手順（端末設定の一般的な場所。機種により異なる） */
    const val DETAIL =
        "設定 → ストレージ → メディアの再スキャン、または端末の再起動で改善することがあります。"
}
