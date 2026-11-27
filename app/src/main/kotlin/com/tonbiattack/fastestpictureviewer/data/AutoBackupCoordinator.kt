package com.tonbiattack.fastestpictureviewer.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException

/** Android のバックアップ対象外領域に復元済み状態を記録する。 */
class AutoBackupInstallState(private val marker: File) {
    fun isInitialized(): Boolean = marker.isFile && marker.readText() == "done"
    fun hasPendingRestore(): Boolean = marker.isFile && marker.readText() == "pending"

    fun markPendingRestore() = writeState("pending")
    fun markInitialized() = writeState("done")

    private fun writeState(state: String) {
        val dir = marker.parentFile ?: throw IOException("状態を保存できません")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("状態を保存できません")
        // noBackupFilesDir は端末移行時に引き継がれない。
        val temporary = File(dir, "${marker.name}.tmp")
        temporary.outputStream().use {
            it.write(state.toByteArray(Charsets.UTF_8))
            it.fd.sync()
        }
        try {
            java.nio.file.Files.move(
                temporary.toPath(),
                marker.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            )
        } catch (e: Exception) {
            temporary.delete()
            throw IOException("状態を保存できません", e)
        }
    }
}

/** 手動バックアップと同じ形式の控えを、バックアップサービス向けに維持する。 */
class AutoBackupCoordinator(
    private val store: AutoBackupStore,
    private val backupRepository: BackupDataSource,
    private val appVersionName: () -> String,
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    private val onSaved: (AutoBackupStore.WriteResult) -> Unit = {},
    private val installState: AutoBackupInstallState? = null,
    private val databaseExistedBeforeOpen: Boolean = false
) {
    private val mutex = Mutex()
    private var pendingSave: Job? = null
    private var initialized = false
    private var writable = false

    /** 起動時の初回移行と復元を、保存処理より先に直列化する。 */
    suspend fun initialize(): Boolean = mutex.withLock {
        if (initialized) return@withLock false
        val state = installState
        android.util.Log.i(
            "AutoBackup",
            "start initialized=${state?.isInitialized()} backup=${store.file.exists()} " +
                "dbExisted=$databaseExistedBeforeOpen"
        )
        var restored = false
        try {
            if (state != null && !state.isInitialized()) {
                // 初回移行。DB が既にある旧版からの更新では、控えを復元せず
                // 現在の DB を正とする。DB がない新規・端末移行時だけ取り込む。
                if (!databaseExistedBeforeOpen && store.file.exists()) {
                    restored = restoreFromStore()
                }
                // 復元が最後まで成功するまでは状態を確定しない。
                state.markInitialized()
            } else if (store.file.exists()) {
                // 状態が確定していても、DB にユーザーデータが無ければ取り込む。
                // OS の復元では no_backup の状態が戻らないことがあり、
                // 状態だけに頼ると「控えはあるのに空のまま」になる。
                val current = backupRepository.collect(appVersionName())
                if (!current.hasUserData()) restored = restoreFromStore()
            }
            initialized = true
            writable = true
            // 既存利用者も最初の起動で控えを作る。空状態も保存する。
            saveLocked()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 破損・読み取り失敗時に空データで控えを上書きしない。
            initialized = true
            writable = false
            onSaved(AutoBackupStore.WriteResult.Failed(e))
        }
        restored
    }

    /** 控えを読み、まだ取り込んでいなければ復元する */
    private suspend fun restoreFromStore(): Boolean {
        val json = store.load() ?: return false
        // 壊れた控えは例外にして、空データでの上書きを避ける
        val data = BackupJson.decode(json)
        if (!data.hasUserData()) return false
        backupRepository.restore(data)
        android.util.Log.i(
            "AutoBackup",
            "restored tags=${data.tags.size} folders=${data.favoriteFolders.size} " +
                "images=${data.favoriteImages.size} excluded=${data.excludedFolders.size}"
        )
        return true
    }

    @Synchronized
    fun scheduleSave(scope: CoroutineScope) {
        pendingSave?.cancel()
        pendingSave = scope.launch {
            delay(debounceMs)
            saveNow()
        }
    }

    /** 今すぐ書き出す。初期化前は控えを壊さないよう拒否する */
    suspend fun saveNow(): AutoBackupStore.WriteResult = mutex.withLock {
        if (installState != null && (!initialized || !writable)) {
            return@withLock AutoBackupStore.WriteResult.Failed(
                IOException("バックアップの初期化が完了していません")
            ).also(onSaved)
        }
        saveLocked()
    }

    private suspend fun saveLocked(): AutoBackupStore.WriteResult {
        return try {
            val data = backupRepository.collect(appVersionName())
            store.save(BackupJson.encode(data)).also(onSaved)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AutoBackupStore.WriteResult.Failed(e).also(onSaved)
        }
    }

    /** 従来 API。新しい起動処理では initialize を使う。 */
    suspend fun restoreIfNeeded(): Boolean {
        if (!store.exists()) return false
        val current = backupRepository.collect(appVersionName())
        if (current.hasUserData()) return false
        val json = store.load() ?: return false
        val data = try { BackupJson.decode(json) } catch (_: Exception) { return false }
        if (!data.hasUserData()) return false
        return try {
            backupRepository.restore(data)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        const val DEFAULT_DEBOUNCE_MS = 5_000L
    }
}

fun BackupData.hasUserData(): Boolean =
    tags.isNotEmpty() || folderTags.isNotEmpty() || favoriteFolders.isNotEmpty() ||
        favoriteImages.isNotEmpty() || excludedFolders.isNotEmpty()
