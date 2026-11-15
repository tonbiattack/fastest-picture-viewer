package com.tonbiattack.fastestpictureviewer.repository

import com.tonbiattack.fastestpictureviewer.data.ImageItem

/**
 * 移動した写真へお気に入りを引き継ぐ判定 (Issue #94)。
 *
 * MediaStore の ID はファイルを移動すると変わる。`favorite_images` は
 * itemId (content URI) を主キーにしているため、SD カードへ移すと
 * お気に入りの紐付けが切れる。写真は無事だが、ユーザーには
 * 「お気に入りが消えた」と見える。
 *
 * 名前・サイズ・更新日時が一致する行を「同じ写真」の候補とするが、
 * 連番コピーなどで同じ値の写真が複数あり得る。**取り違えて別の写真に
 * お気に入りを付ける方が、お気に入りが消えるより悪い**ため、
 * 候補が 1 件だけのときに限って採用する。
 */
object FavoriteMigration {

    /** 移行の判定結果 */
    sealed interface Decision {
        /** 移行する */
        data class Migrate(val to: ImageItem) : Decision

        /** 移行しない */
        data class Keep(val reason: Reason) : Decision
    }

    enum class Reason {
        /** 一致する行が無い（削除された可能性） */
        NO_CANDIDATE,

        /** 一致する行が複数ある（取り違えの危険） */
        AMBIGUOUS,

        /** 移動先が既にお気に入り（統合しても情報が増えない） */
        ALREADY_FAVORITE
    }

    /**
     * 移行先を決める。
     *
     * @param candidates 名前・サイズ・更新日時が一致する行（元の行は除く）
     * @param alreadyFavorite 候補が既にお気に入りか
     */
    fun decide(candidates: List<ImageItem>, alreadyFavorite: Boolean): Decision {
        if (candidates.isEmpty()) return Decision.Keep(Reason.NO_CANDIDATE)
        if (candidates.size > 1) return Decision.Keep(Reason.AMBIGUOUS)
        if (alreadyFavorite) return Decision.Keep(Reason.ALREADY_FAVORITE)
        return Decision.Migrate(candidates.first())
    }
}
