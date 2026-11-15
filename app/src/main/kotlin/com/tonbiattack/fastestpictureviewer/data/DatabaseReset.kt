package com.tonbiattack.fastestpictureviewer.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * DB を開けなかったときの、利用者の明示的な操作による初期化 (Issue #88)。
 *
 * 自動では何も消さない。破損した DB をそのまま残すとアプリが使えなくなるため、
 * 利用者が「初期化して起動」を選んだときだけ、ファイルを退避して作り直す。
 *
 * 退避ファイルは既存のものを上書きしない。時刻を名前に付け、衝突したら連番を足す。
 * これにより、以前の退避データを失わない。
 *
 * 写真そのものには触れない。失われるのは DB に持っている情報
 * （お気に入り、タグ、除外フォルダ、スクロール位置、画像のキャッシュ）のみ。
 */
object DatabaseReset {

    /** DB 本体と付随ファイルの接尾辞 */
    private val SUFFIXES = listOf("", "-wal", "-shm")

    /** 退避ファイルの名前に使う接頭辞 */
    private const val PREFIX = ".corrupt-"

    /**
     * DB ファイル一式を退避する。
     *
     * @return 退避したファイル。対象が無ければ空。
     */
    fun reset(context: Context): List<File> {
        val dir = context.getDatabasePath(AppDatabase.DATABASE_NAME).parentFile
            ?: return emptyList()
        return resetIn(dir)
    }

    /**
     * 指定ディレクトリの DB ファイル一式を退避する。
     * 実ファイルを扱うため、単体テストから直接呼べるようにしている。
     */
    internal fun resetIn(dir: File): List<File> {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return SUFFIXES.mapNotNull { suffix ->
            val source = File(dir, AppDatabase.DATABASE_NAME + suffix)
            if (!source.exists()) return@mapNotNull null
            val target = uniqueTarget(dir, stamp, suffix)
            quarantine(source, target)
            target
        }
    }

    /** 既存ファイルと衝突しない名前を作る。上書きはしない。 */
    private fun uniqueTarget(dir: File, stamp: String, suffix: String): File {
        val base = "${AppDatabase.DATABASE_NAME}$PREFIX$stamp"
        var candidate = File(dir, base + suffix)
        var index = 1
        while (candidate.exists()) {
            candidate = File(dir, "$base-${index++}$suffix")
        }
        return candidate
    }

    /**
     * 元ファイルを退避先へ移す。
     * rename が使えない環境（別マウント等）ではコピーで代替する。
     */
    private fun quarantine(source: File, target: File) {
        if (source.renameTo(target)) return
        source.copyTo(target, overwrite = false)
        source.delete()
    }
}
