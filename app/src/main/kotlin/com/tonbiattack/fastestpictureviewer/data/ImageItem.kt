package com.tonbiattack.fastestpictureviewer.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * MediaStore から取得した画像のメタ情報を永続化する Entity。
 *
 * 主キー [id] は content URI 文字列。ボリューム (内部ストレージ / SD カード) を
 * またいでも media id が衝突しないよう、URI 文字列を一意の ID とする。
 *
 * 本体画像は保存せず、メタ情報のみを持つ。
 */
@Entity(
    tableName = "images",
    indices = [
        // フォルダ内一覧 (自然順キー昇順 + 同名タイブレーク) 用の複合インデックス
        Index(value = ["folderKey", "naturalSortKey", "sortName", "mediaId"]),
        // フォルダ一覧の集計 (MAX(dateModifiedSec)) 用
        Index(value = ["folderKey", "dateModifiedSec"]),
        // 前回同期時刻以降の差分検出用
        Index(value = ["dateModifiedSec"]),
        // ボリューム単位の全件取得 (削除判定) 用
        Index(value = ["volumeName"])
    ]
)
data class ImageItem(
    /** content URI 文字列。ボリューム跨ぎで衝突しない一意キー */
    @PrimaryKey val id: String,

    /** MediaStore の内部 ID (_ID) */
    @ColumnInfo(name = "mediaId") val mediaId: Long,

    /** 画像が存在するストレージのボリューム名 (external_primary / SD のボリューム名など) */
    @ColumnInfo(name = "volumeName") val volumeName: String,

    /** 画像を参照するための content URI 文字列 (= [id]) */
    @ColumnInfo(name = "uri") val uri: String,

    /** 表示ファイル名 */
    @ColumnInfo(name = "displayName") val displayName: String,

    /** 整類用キー (displayName を小文字化したもの)。COLLATE NOCASE に頼らず既定コレーションで安定整列する */
    @ColumnInfo(name = "sortName") val sortName: String,

    /** ボリューム内の相対パス (例: "Pictures/vacation/") */
    @ColumnInfo(name = "relativePath") val relativePath: String,

    /** フォルダ識別子 (volumeName:relativePath)。同名フォルダが別ボリュームにあっても区別する */
    @ColumnInfo(name = "folderKey") val folderKey: String,

    /** 表示用フォルダ名 */
    @ColumnInfo(name = "folderName") val folderName: String,

    /** 更新日時 (Unix エポック秒。MediaStore DATE_MODIFIED に対応) */
    @ColumnInfo(name = "dateModifiedSec") val dateModifiedSec: Long,

    /** ファイルサイズ (バイト) */
    @ColumnInfo(name = "size") val size: Long,

    /** MIME タイプ */
    @ColumnInfo(name = "mimeType") val mimeType: String?,

    /** 画像幅 (取得できない端末では null) */
    @ColumnInfo(name = "width") val width: Int?,

    /** 画像高さ (取得できない端末では null) */
    @ColumnInfo(name = "height") val height: Int?,

    /**
     * ファイル名の自然順ソートキー。
     * 既存キャッシュは空のまま移行し、フォルダを開いたときに遅延バックフィルする。
     */
    @ColumnInfo(name = "naturalSortKey", defaultValue = "")
    val naturalSortKey: String = ""
)

/** フォルダ一覧向けの集計行。フォルダ内画像の件数と最新更新日時を GROUP BY で取得する */
data class FolderSummaryRow(
    @ColumnInfo(name = "folderKey") val folderKey: String,
    @ColumnInfo(name = "volumeName") val volumeName: String,
    @ColumnInfo(name = "relativePath") val relativePath: String,
    @ColumnInfo(name = "imageCount") val imageCount: Long,
    @ColumnInfo(name = "latestModifiedSec") val latestModifiedSec: Long
)

/** フォルダ代表サムネイル行。各フォルダで最新の画像を取得する */
data class RepresentativeImageRow(
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "folderKey") val folderKey: String
)
