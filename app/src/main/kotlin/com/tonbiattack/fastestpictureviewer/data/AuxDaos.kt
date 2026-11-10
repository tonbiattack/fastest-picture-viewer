package com.tonbiattack.fastestpictureviewer.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** 同期状態 (前回差分同期時刻) の DAO */
@Dao
interface SyncStateDao {

    @Query("SELECT * FROM sync_state WHERE id = 1")
    suspend fun get(): SyncStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: SyncStateEntity)

    @Query("DELETE FROM sync_state")
    suspend fun clear()
}

/** ボリューム単位の同期状態 DAO */
@Dao
interface VolumeSyncStateDao {

    @Query("SELECT * FROM volume_sync_state WHERE volumeName = :volumeName")
    suspend fun get(volumeName: String): VolumeSyncStateEntity?

    @Query("SELECT * FROM volume_sync_state")
    suspend fun getAll(): List<VolumeSyncStateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: VolumeSyncStateEntity)

    @Query("DELETE FROM volume_sync_state WHERE volumeName = :volumeName")
    suspend fun remove(volumeName: String)
}

/** 除外フォルダの DAO */
@Dao
interface ExcludedFolderDao {

    @Query("SELECT * FROM excluded_folders ORDER BY excludedAtSec DESC, folderKey ASC")
    fun observeAll(): Flow<List<ExcludedFolderEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(entity: ExcludedFolderEntity)

    @Query("DELETE FROM excluded_folders WHERE folderKey = :folderKey")
    suspend fun remove(folderKey: String)

    @Query("SELECT COUNT(*) FROM excluded_folders WHERE folderKey = :folderKey")
    suspend fun exists(folderKey: String): Long
}

/** お気に入りの DAO */
@Dao
interface FavoriteImageDao {

    @Query("SELECT * FROM favorite_images")
    fun observeAll(): Flow<List<FavoriteImageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(item: FavoriteImageEntity)

    @Query("DELETE FROM favorite_images WHERE itemId = :itemId")
    suspend fun remove(itemId: String)

    @Query("SELECT COUNT(*) FROM favorite_images WHERE itemId = :itemId")
    suspend fun exists(itemId: String): Long
}
