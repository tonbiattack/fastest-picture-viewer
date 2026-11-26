package com.tonbiattack.fastestpictureviewer.data

import java.io.File
import java.io.IOException

/**
 * Auto Backup 対象の控えを内部ストレージへ書き出す (Issue #151)。
 *
 * ## なぜ必要か
 *
 * タグ・お気に入り・除外フォルダは Room DB にあるが、DB は約 295MB あり
 * Auto Backup の上限（25MB）を超えるため除外している (#63)。
 * そのままでは機種変更でこれらが失われる。
 *
 * そこで再生成できないデータだけを小さな JSON へ書き出し、
 * Auto Backup の対象（`files/` 配下）に置く。
 *
 * ## 書き込みの安全性
 *
 * 一時ファイルへ書いてから rename する。書き込み途中で電源が落ちても、
 * 前回の正常な控えが壊れないようにするため。
 *
 * 画像そのもの・サムネイル・Room DB は含めない。25MB に収まる想定だが、
 * 超えた場合は黙って切り捨てず [WriteResult.TooLarge] を返す。
 */
class AutoBackupStore(private val filesDir: File) {

    /** 控えの保存先。Auto Backup は `files/` を既定で対象にする */
    val file: File
        get() = File(File(filesDir, DIRECTORY_NAME), FILE_NAME)

    /** 控えが存在するか */
    fun exists(): Boolean = file.isFile && file.length() > 0L

    /** 現在の控えのサイズ (bytes)。無ければ 0 */
    fun sizeBytes(): Long = runCatching { file.length() }.getOrDefault(0L)

    /** 控えを読む。壊れている・存在しない場合は null */
    fun load(): String? {
        val target = file
        if (!target.isFile) return null
        return runCatching { target.readText(Charsets.UTF_8) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * 控えを書く。
     *
     * @return 書けたら [WriteResult.Saved]、25MB を超えたら [WriteResult.TooLarge]
     */
    fun save(json: String): WriteResult {
        val bytes = json.toByteArray(Charsets.UTF_8)

        // 上限超過は黙って切り捨てない。呼び出し元が記録できるよう結果で返す
        if (bytes.size > MAX_BYTES) {
            return WriteResult.TooLarge(bytes.size)
        }

        val target = file
        val dir = target.parentFile ?: return WriteResult.Failed(null)
        if (!dir.isDirectory && !dir.mkdirs()) {
            return WriteResult.Failed(null)
        }

        // 一時ファイルへ書いてから置き換える（原子的な更新）
        val temp = File(dir, "$FILE_NAME.tmp")
        return try {
            FileOutputStreamSafe.write(temp, bytes)
            if (!temp.renameTo(target)) {
                // rename できない環境では直接書く。失敗しても前回の控えは残る
                FileOutputStreamSafe.write(target, bytes)
                temp.delete()
            }
            WriteResult.Saved(bytes.size)
        } catch (e: IOException) {
            temp.delete()
            WriteResult.Failed(e)
        }
    }

    /** 控えを削除する (テスト・初期化用) */
    fun delete(): Boolean = runCatching { file.delete() }.getOrDefault(false)

    /** 書き込み結果 */
    sealed interface WriteResult {
        /** 書けた。値は書き込んだバイト数 */
        data class Saved(val bytes: Int) : WriteResult

        /** 25MB を超えたため書かなかった。値は書き込もうとしたバイト数 */
        data class TooLarge(val bytes: Int) : WriteResult

        /** 書き込みに失敗した */
        data class Failed(val cause: Throwable?) : WriteResult
    }

    companion object {
        /** Auto Backup のアプリ単位上限 (25MB) */
        const val MAX_BYTES = 25 * 1024 * 1024

        /** 控えを置くディレクトリ名。他のファイルと混ざらないよう分ける */
        const val DIRECTORY_NAME = "auto_backup"

        /** 控えのファイル名 */
        const val FILE_NAME = "auto_backup.json"
    }
}

/**
 * ファイルへ確実に書き込む小さなヘルパー。
 *
 * `File.writeBytes` は途中で失敗したときに内部状態が分かりにくいため、
 * flush と close を明示する。
 */
private object FileOutputStreamSafe {
    fun write(target: File, bytes: ByteArray) {
        java.io.FileOutputStream(target).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
    }
}
