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
